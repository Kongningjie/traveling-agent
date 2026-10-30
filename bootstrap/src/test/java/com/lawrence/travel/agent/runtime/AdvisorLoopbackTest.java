package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.execution.AgentCallFailure;
import com.lawrence.travel.application.interaction.Dialogue;
import com.lawrence.travel.application.interaction.Negotiation;
import com.lawrence.travel.application.itinerary.Planning;
import com.lawrence.travel.application.knowledge.Research;
import com.lawrence.travel.application.port.TravelToolPort;
import com.lawrence.travel.domain.interaction.DialogueIntent;
import com.lawrence.travel.domain.itinerary.ItineraryReport;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 经真实SDK→HTTP/SSE→Harness→Advisor→DTO验证七类入口；不替换业务Advisor或读取真实凭据。 */
class AdvisorLoopbackTest {
    @TempDir Path root;
    private final Instant now = Instant.parse("2026-10-30T00:00:00Z");
    private final LocalDate date = LocalDate.parse("2026-11-01");

    /** 七种生产业务入口及其实际输出契约。 */
    enum Entry {
        INTENT,
        NEGOTIATION,
        RESEARCH,
        EXTRACTION,
        DRAFT,
        PATCH,
        REVIEW
    }

    /**
     * 结构化工具响应必须经实际业务Schema与DTO解码，且每个入口只发送一次请求。
     *
     * @param entry 当前入口
     * @throws Exception 回环服务无法启动
     */
    @ParameterizedTest
    @EnumSource(Entry.class)
    void structuredToolResponseTraversesProductionChain(Entry entry) throws Exception {
        succeeds(entry, true);
    }

    /**
     * 完整JSON正文兜底与工具路径使用同一业务契约，不丢字段或额外重试。
     *
     * @param entry 当前入口
     * @throws Exception 回环服务无法启动
     */
    @ParameterizedTest
    @EnumSource(Entry.class)
    void completeJsonTextTraversesSameProductionChain(Entry entry) throws Exception {
        succeeds(entry, false);
    }

    /**
     * 字段不合法的JSON经实际传输后应停止，不能被Advisor抹平成可重试错误。
     *
     * @param entry 当前入口
     * @throws Exception 回环服务无法启动
     */
    @ParameterizedTest
    @EnumSource(Entry.class)
    void invalidBusinessJsonStopsAfterExactlyOneRequest(Entry entry) throws Exception {
        invalid(entry, false);
    }

    /**
     * generate_response中的错误业务对象也必须停止，不能触发隐藏的协议修复请求。
     *
     * @param entry 当前入口
     * @throws Exception 回环服务无法启动
     */
    @ParameterizedTest
    @EnumSource(Entry.class)
    void invalidToolObjectStopsAfterExactlyOneRequest(Entry entry) throws Exception {
        invalid(entry, true);
    }

    /**
     * 两种响应路径共用非法对象断言；必须保留模型错误码与实际用量。
     *
     * @param entry 当前业务入口
     * @param tool 是否通过框架工具返回
     * @throws Exception 本机服务无法启动
     */
    private void invalid(Entry entry, boolean tool) throws Exception {
        try (var fixture = new AdvisorLoopbackFixture(root)) {
            fixture.answer("{\"unexpected\":true}", tool);
            assertThatThrownBy(() -> invoke(fixture, entry))
                    .isInstanceOf(AgentCallFailure.class)
                    .hasMessage("MODEL_OUTPUT_SCHEMA")
                    .satisfies(
                            error ->
                                    assertThat(((AgentCallFailure) error).recovery())
                                            .isEqualTo(AgentCallFailure.Recovery.STOP));
            fixture.assertCalls(1);
            assertThat(fixture.usage)
                    .containsExactly(
                            new com.lawrence.travel.domain.execution.control.TokenUsage(17, 3));
        }
    }

    /** 权限拒绝、429/503零用量及429未知用量通过实际SDK保留分类；运行时本身不自动重发。 */
    @Test
    void providerFailuresKeepRecoveryEvidenceAndRequestCounts() throws Exception {
        for (int scenario = 0; scenario < 4; scenario++) {
            try (var fixture = new AdvisorLoopbackFixture(root.resolve("provider-" + scenario))) {
                int status = scenario == 0 ? 403 : scenario == 2 ? 503 : 429;
                boolean zero = scenario == 1 || scenario == 2;
                fixture.failure(
                        status,
                        zero
                                ? "{\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}"
                                : "{\"message\":\"SYNTHETIC_PRIVATE\"}");
                String code =
                        status == 403
                                ? "MODEL_ACCESS_DENIED"
                                : status == 503 ? "MODEL_PROVIDER_HTTP_503" : "MODEL_RATE_LIMITED";
                assertThatThrownBy(() -> invoke(fixture, Entry.RESEARCH))
                        .isInstanceOf(AgentCallFailure.class)
                        .hasMessage(code)
                        .satisfies(
                                error -> {
                                    var failure = (AgentCallFailure) error;
                                    assertThat(failure.knownZeroUsage())
                                            .as(
                                                    "fixed=%s types=%s",
                                                    code,
                                                    ((SanitizedAgentFailure) failure)
                                                            .exceptionTypes())
                                            .isEqualTo(zero);
                                    assertThat(failure.recovery())
                                            .isEqualTo(
                                                    zero
                                                            ? AgentCallFailure.Recovery.RETRY
                                                            : AgentCallFailure.Recovery.PAUSE);
                                    assertThat(failure.getCause()).isNull();
                                    assertThat(failure.toString())
                                            .doesNotContain("SYNTHETIC_PRIVATE");
                                });
                fixture.assertCalls(1);
                if (zero)
                    assertThat(fixture.usage)
                            .containsExactly(
                                    new com.lawrence.travel.domain.execution.control.TokenUsage(
                                            0, 0));
                else assertThat(fixture.usage.getFirst()).isNull();
            }
        }
    }

    /** 自由文本不冒充结构化产物，实际链路保留UNSTRUCTURED并停止，不重发。 */
    @Test
    void unstructuredTextStopsWithoutLosingFixedCode() throws Exception {
        try (var fixture = new AdvisorLoopbackFixture(root)) {
            fixture.answer("SYNTHETIC_NON_JSON", false);
            assertThatThrownBy(() -> invoke(fixture, Entry.EXTRACTION))
                    .isInstanceOf(AgentCallFailure.class)
                    .hasMessage("MODEL_OUTPUT_UNSTRUCTURED");
            fixture.assertCalls(1);
        }
    }

    /**
     * 验证完整成功路径、实际发送的工具Schema及计量；不把DTO成功解码当作行程可接受。
     *
     * @param entry 生产业务入口
     * @param tool 是否通过generate_response工具返回
     * @throws Exception 本地服务无法启动
     */
    private void succeeds(Entry entry, boolean tool) throws Exception {
        try (var fixture = new AdvisorLoopbackFixture(root)) {
            String body = body(fixture, entry);
            fixture.answer(body, tool);
            assertThat(invoke(fixture, entry)).isEqualTo(fixture.json.decode(body, type(entry)));
            fixture.assertCalls(1);
            assertThat(fixture.usage)
                    .containsExactly(
                            new com.lawrence.travel.domain.execution.control.TokenUsage(17, 3));
            var request = fixture.requests.getFirst();
            assertThat(request.get("model"))
                    .isEqualTo(entry == Entry.REVIEW ? "local-reviewer" : "local-model");
            var parameters = (Map<?, ?>) request.get("parameters");
            var tools = (List<?>) parameters.get("tools");
            assertThat(tools).hasSize(1);
            var function = (Map<?, ?>) ((Map<?, ?>) tools.getFirst()).get("function");
            assertThat(function.get("name")).isEqualTo("generate_response");
            var arguments = (Map<?, ?>) function.get("parameters");
            var response = (Map<?, ?>) ((Map<?, ?>) arguments.get("properties")).get("response");
            assertThat(((Map<?, ?>) response.get("properties")).containsKey(field(entry))).isTrue();
            if (entry != Entry.PATCH) {
                assertThat(((List<?>) response.get("required")).contains(field(entry))).isTrue();
            }
        }
    }

    /**
     * 调用真实Advisor；仅用合成上下文，主链中的运行时与模型组件不替换。
     *
     * @param f 本地HTTP与持久端口夹具
     * @param entry 业务入口
     * @return 生产解码的业务DTO
     */
    private Object invoke(AdvisorLoopbackFixture f, Entry entry) {
        var revision = new ConstraintRevision(f.scope, 1, List.of(), List.of());
        var research =
                new HarnessResearchAdvisor(f.runtime, f.json, true, Set.of("official.example"));
        var planner = new HarnessPlanningAdvisor(f.runtime, f.json, true);
        var context = new Planning.Context(f.scope, revision, null, List.of(), now, null);
        var snapshot = f.json.decode(body(f, Entry.DRAFT), Planning.Draft.class).snapshot();
        var report =
                new ItineraryReport(
                        UUID.randomUUID(),
                        new ItineraryReport.Binding(
                                UUID.randomUUID(),
                                1,
                                "a".repeat(64),
                                1,
                                Set.of(),
                                null,
                                "local",
                                "local"),
                        ItineraryReport.Kind.VALIDATION,
                        List.of(),
                        now,
                        now.plusSeconds(60),
                        UUID.randomUUID(),
                        UUID.randomUUID());
        return switch (entry) {
            case INTENT ->
                    new DialogueIntentInterpreter(f.runtime, f.json, true)
                            .interpret(
                                    new Dialogue.Claim(
                                            f.scope.userId(),
                                            UUID.randomUUID(),
                                            UUID.randomUUID(),
                                            1,
                                            f.run,
                                            "北京",
                                            now,
                                            new Dialogue.Basis(null, null, 0, 0, null),
                                            "{}"));
            case NEGOTIATION ->
                    new NegotiationInterpreter(f.runtime, f.json, true)
                            .interpret(
                                    f.run,
                                    new Negotiation.Context(
                                            f.scope,
                                            revision,
                                            new Negotiation.Message(
                                                    UUID.randomUUID(),
                                                    "USER",
                                                    "北京",
                                                    List.of(),
                                                    null,
                                                    0,
                                                    1,
                                                    "RECEIVED"),
                                            null,
                                            0,
                                            null,
                                            now));
            case RESEARCH -> research.plan(f.run, new Research.Context(revision, List.of(), now));
            case EXTRACTION ->
                    research.extract(
                            f.run,
                            new Research.Query(
                                    "北京",
                                    "price",
                                    TravelToolPort.Operation.EXTRACT,
                                    Map.of("url", "https://official.example/tickets"),
                                    date),
                            new TravelToolPort.Evidence(
                                    URI.create("https://official.example/tickets"), now, "{}"));
            case DRAFT -> planner.draft(f.run, context, null, List.of());
            case PATCH ->
                    planner.patch(
                            f.run,
                            context,
                            new Planning.Base(snapshot, "a".repeat(64), revision),
                            null,
                            null,
                            List.of());
            case REVIEW ->
                    planner.review(
                            f.run,
                            context,
                            new Planning.Candidate(
                                    UUID.randomUUID(), null, snapshot, report, null, "LOCAL_TEST"));
        };
    }

    /**
     * 返回最小合成产物；快照只用于适配契约，不声称通过可行性或用户接受校验。
     *
     * @param f 合成任务身份
     * @param entry 业务入口
     * @return 合成业务JSON
     */
    private String body(AdvisorLoopbackFixture f, Entry entry) {
        return switch (entry) {
            case INTENT ->
                    "{\"intents\":[{\"kind\":\"CONVERSATION\",\"action\":\"TRAVEL_QA\",\"quote\":\"北京\"}],\"facts\":[],\"reply\":\"可以聊聊北京\",\"clarification\":\"\",\"category\":\"\",\"city\":\"\",\"value\":\"\",\"targetId\":null,\"resolvedInputs\":[],\"advice\":[]}";
            case NEGOTIATION ->
                    "{\"facts\":[],\"outsideScope\":false,\"clarification\":\"请补充旅行日期\"}";
            case RESEARCH ->
                    "{\"queries\":[],\"strategy\":{\"schemaVersion\":1,\"goal\":\"REUSE\",\"stop\":\"PLAN\",\"branches\":[],\"missingFields\":[]}}";
            case EXTRACTION ->
                    "{\"value\":\"20元\",\"quote\":\"2026-11-01 门票20元\",\"publishedAt\":null,\"validForDate\":\"2026-11-01\"}";
            case DRAFT ->
                    "{\"snapshot\":{\"schemaVersion\":1,\"taskId\":\""
                            + f.scope.taskId()
                            + "\",\"constraintRevision\":1,\"baseVersionId\":null,\"window\":{\"cityIds\":[\"北京\"],\"startsOn\":\"2026-11-01\",\"endsOn\":\"2026-11-01\",\"travelers\":2},\"cityStays\":[],\"days\":[],\"items\":[],\"transportLegs\":[],\"accommodationStays\":[],\"costEntries\":[],\"alternatives\":[],\"evidenceRefs\":[]}}";
            case PATCH ->
                    "{\"patch\":{\"expectedBaseHash\":\""
                            + "a".repeat(64)
                            + "\",\"window\":null,\"operations\":[{\"kind\":\"ITEM\",\"action\":\"REMOVE\",\"id\":\"00000000-0000-4000-8000-000000000001\",\"item\":null,\"evidenceUses\":[]}]},\"expansionReason\":null}";
            case REVIEW ->
                    "{\"issues\":[{\"code\":\"EVIDENCE_MISSING\",\"objectId\":null,\"blocking\":true,\"unknown\":true}]}";
        };
    }

    /**
     * 查找生产入口对应的DTO，用于与真实解码结果逐字段比较。
     *
     * @param entry 业务入口
     * @return 对应输出类型
     */
    private Class<?> type(Entry entry) {
        return switch (entry) {
            case INTENT -> DialogueIntent.Result.class;
            case NEGOTIATION -> Negotiation.Interpretation.class;
            case RESEARCH -> Research.Plan.class;
            case EXTRACTION -> Research.Extraction.class;
            case DRAFT -> Planning.Draft.class;
            case PATCH -> Planning.PatchReply.class;
            case REVIEW -> Planning.Review.class;
        };
    }

    /**
     * 查找必须出现在SDK请求Schema中的业务字段；Patch字段允许为空。
     *
     * @param entry 业务入口
     * @return 业务字段名
     */
    private String field(Entry entry) {
        return switch (entry) {
            case INTENT -> "intents";
            case NEGOTIATION -> "facts";
            case RESEARCH -> "queries";
            case EXTRACTION -> "value";
            case DRAFT -> "snapshot";
            case PATCH -> "patch";
            case REVIEW -> "issues";
        };
    }
}
