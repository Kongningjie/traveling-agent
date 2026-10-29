package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.interaction.Negotiation;
import com.lawrence.travel.application.itinerary.Planning;
import com.lawrence.travel.application.knowledge.Research;
import com.lawrence.travel.application.safety.SafetyGate;
import com.lawrence.travel.domain.interaction.DialogueIntent;
import com.lawrence.travel.infrastructure.interaction.InteractionJson;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.tool.ToolValidator;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用实际SDK的Schema生成、工具校验和Harness循环，不请求真实模型。 */
class BusinessOutputSchemaTest {
    @TempDir Path root;
    private final InteractionJson codec = new InteractionJson(new TravelJson());

    /** 七类生产输出均生成具体字段，且每次Schema互相隔离。 */
    @Test
    void allProductionOutputsExposeTheirProperties() {
        for (Class<?> type :
                List.of(
                        DialogueIntent.Result.class,
                        Negotiation.Interpretation.class,
                        Research.Plan.class,
                        Research.Extraction.class,
                        Planning.Draft.class,
                        Planning.PatchReply.class,
                        Planning.Review.class)) {
            var schema = BusinessOutputSchema.create(type);
            assertThat(schema)
                    .containsEntry("type", "object")
                    .containsEntry("additionalProperties", false);
            assertThat((Map<?, ?>) schema.get("properties")).isNotEmpty();
            assertThat(ToolValidator.validateInput("{}", schema)).isNotNull();
        }
        var first = BusinessOutputSchema.create(Research.Plan.class);
        first.clear();
        assertThat(BusinessOutputSchema.create(Research.Plan.class)).containsKey("properties");
        assertThatThrownBy(() -> BusinessOutputSchema.create(Map.class))
                .hasMessage("BUSINESS_OUTPUT_TYPE_INVALID");
    }

    /** 可空和兼容缺省字段保留；必填布尔值不能缺失后自动变成false。 */
    @Test
    void nullableFieldsDoNotWeakenRequiredFields() {
        accepts(
                Research.Extraction.class,
                "{\"value\":\"\",\"quote\":\"\",\"publishedAt\":null,\"validForDate\":null}");
        rejects(Research.Extraction.class, "{\"quote\":\"\"}");
        accepts(
                Negotiation.Interpretation.class,
                "{\"facts\":[],\"outsideScope\":false,\"clarification\":\"\"}");
        rejects(Negotiation.Interpretation.class, "{\"facts\":[],\"clarification\":\"\"}");
        accepts(
                Planning.Review.class,
                "{\"issues\":[{\"code\":\"MISSING\",\"objectId\":null,\"blocking\":true,\"unknown\":true}]}");
        rejects(Planning.Review.class, "{\"issues\":[{\"code\":\"MISSING\",\"blocking\":true}]}");
        rejects(Planning.Review.class, "{\"issues\":\"不是数组\"}");
    }

    /** 数组元素枚举、字典值和嵌套必填字段也需满足契约。 */
    @Test
    void nestedTypesAndEnumsAreVisibleToFrameworkValidation() {
        String plan = plan();
        accepts(Research.Plan.class, plan);
        rejects(Research.Plan.class, plan.replace("REUSE", "SYNTHETIC_INVALID"));
        rejects(Research.Plan.class, "{\"queries\":[],\"strategy\":{\"goal\":\"REUSE\"}}");
        String query =
                "{\"targetId\":\"北京\",\"fieldPath\":\"poi\",\"operation\":\"PLACE\",\"arguments\":{\"city\":\"北京\",\"keywords\":\"公园\"},\"date\":null}";
        String research = plan.replace("\"queries\":[]", "\"queries\":[" + query + "]");
        accepts(Research.Plan.class, research);
        rejects(Research.Plan.class, research.replace("\"city\":\"北京\"", "\"city\":42"));
        rejects(Research.Plan.class, research.replace("PLACE", "SYNTHETIC_INVALID"));
        accepts(DialogueIntent.Result.class, dialogue());
        rejects(DialogueIntent.Result.class, dialogue().replace("TRAVEL_QA", "SYNTHETIC_INVALID"));
    }

    /** 初始快照的空基础版本，以及删除补丁的空载荷，不能因生成Schema而失效。 */
    @Test
    void draftAndPatchPreserveExistingNullContracts() {
        String id = "00000000-0000-4000-8000-000000000001";
        String snapshot =
                "{\"snapshot\":{\"schemaVersion\":1,\"taskId\":\""
                        + id
                        + "\",\"constraintRevision\":1,\"baseVersionId\":null,"
                        + "\"window\":{\"cityIds\":[\"北京\"],\"startsOn\":\"2026-11-07\",\"endsOn\":\"2026-11-07\",\"travelers\":2},"
                        + "\"cityStays\":[],\"days\":[],\"items\":[],\"transportLegs\":[],\"accommodationStays\":[],\"costEntries\":[],\"alternatives\":[],\"evidenceRefs\":[]}}";
        accepts(Planning.Draft.class, snapshot);
        codec.decode(snapshot, Planning.Draft.class);
        String patch =
                "{\"patch\":{\"expectedBaseHash\":\""
                        + "a".repeat(64)
                        + "\",\"window\":null,\"operations\":[{\"kind\":\"ITEM\",\"action\":\"REMOVE\",\"id\":\""
                        + id
                        + "\",\"item\":null,\"evidenceUses\":[]}]},\"expansionReason\":null}";
        accepts(Planning.PatchReply.class, patch);
        codec.decode(patch, Planning.PatchReply.class);
        accepts(Planning.PatchReply.class, "{\"patch\":null,\"expansionReason\":\"需要扩大范围\"}");
    }

    /** 同一个coordinator的两次调用向模型暴露不同Schema，而非按角色固定选择。 */
    @Test
    void realHarnessReceivesPerCallBusinessContract() {
        var dialogueSchema = throughHarness(dialogue(), DialogueIntent.Result.class);
        var researchSchema = throughHarness(plan(), Research.Plan.class);
        assertThat(((Map<?, ?>) dialogueSchema.get("properties")).containsKey("intents")).isTrue();
        assertThat(((Map<?, ?>) researchSchema.get("properties")).containsKey("queries")).isTrue();
        assertThat(
                        ((List<?>) dialogueSchema.get("required"))
                                .containsAll(List.of("intents", "facts", "reply")))
                .isTrue();
        assertThat(
                        ((List<?>) researchSchema.get("required"))
                                .containsAll(List.of("queries", "strategy")))
                .isTrue();
    }

    /**
     * 经真实Harness工具循环捕获模型所见的generate_response参数Schema。
     *
     * @param body 合成合法输出
     * @param type 当前业务DTO
     * @return 模型实际收到的业务Schema
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> throughHarness(String body, Class<?> type) {
        var data = codec.decode(body, Map.class);
        var model =
                new HarnessProbeFixture.ProbeModel(
                        (messages, n) ->
                                Flux.just(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("local-schema")
                                                                        .name("generate_response")
                                                                        .input(
                                                                                Map.of(
                                                                                        "response",
                                                                                        data))
                                                                        .content(
                                                                                codec.encode(
                                                                                        Map.of(
                                                                                                "response",
                                                                                                data)))
                                                                        .build()))
                                                .build()));
        String answer =
                ControlledHarnessCall.execute(
                        () ->
                                RestrictedHarnessFactory.create(
                                        model,
                                        new Toolkit(),
                                        root.resolve(type.getSimpleName()),
                                        "coordinator",
                                        true),
                        () -> List.of(HarnessProbeFixture.user("本地契约验证")),
                        () -> {
                            throw new AssertionError("NO_CONTEXT_RETRY");
                        },
                        () -> {},
                        SafetyGate.local(),
                        () -> false,
                        agent -> {},
                        true,
                        codec,
                        type);
        assertThat(codec.decode(answer, type)).isNotNull();
        assertThat(model.requests).hasSize(1);
        var tool =
                model.schemas.getFirst().stream()
                        .filter(s -> s.getName().equals("generate_response"))
                        .findFirst()
                        .orElseThrow();
        return (Map<String, Object>)
                ((Map<?, ?>) tool.getParameters().get("properties")).get("response");
    }

    /**
     * 验证合法形状；不把它视为业务可行性通过。
     *
     * @param type 输出DTO
     * @param body 合成JSON
     */
    private void accepts(Class<?> type, String body) {
        assertThat(ToolValidator.validateInput(body, BusinessOutputSchema.create(type))).isNull();
    }

    /**
     * 验证SDK能拒绝非法字段，不输出包含合成值的错误详情。
     *
     * @param type 输出DTO
     * @param body 合成JSON
     */
    private void rejects(Class<?> type, String body) {
        assertThat(ToolValidator.validateInput(body, BusinessOutputSchema.create(type)))
                .isNotNull();
    }

    /**
     * 返回不含真实资料的意图输出。
     *
     * @return 合法只读咨询
     */
    private String dialogue() {
        return "{\"intents\":[{\"kind\":\"CONVERSATION\",\"action\":\"TRAVEL_QA\",\"quote\":\"北京\"}],\"facts\":[],\"reply\":\"可以聊聊\",\"clarification\":\"\",\"category\":\"\",\"city\":\"\",\"value\":\"\",\"targetId\":null}";
    }

    /**
     * 返回空查询的复用策略，仅用于Schema验证。
     *
     * @return 合法复用计划
     */
    private String plan() {
        return "{\"queries\":[],\"strategy\":{\"schemaVersion\":1,\"goal\":\"REUSE\",\"stop\":\"PLAN\",\"branches\":[],\"missingFields\":[]}}";
    }
}
