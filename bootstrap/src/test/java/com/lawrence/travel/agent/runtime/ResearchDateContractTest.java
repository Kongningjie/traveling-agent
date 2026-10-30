package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.knowledge.EvidencePolicy;
import com.lawrence.travel.application.knowledge.Research;
import com.lawrence.travel.application.port.AgentRuntimePort;
import com.lawrence.travel.application.port.TravelToolPort;
import com.lawrence.travel.application.port.TravelToolPort.Operation;
import com.lawrence.travel.domain.execution.AgentStrategy;
import com.lawrence.travel.domain.knowledge.EvidenceRecord;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.infrastructure.interaction.InteractionJson;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证实际调查提示、DTO解码与日期校验一致；只替换模型句柄，不调用供应商。 */
class ResearchDateContractTest {
    private final InteractionJson json = new InteractionJson(new TravelJson());
    private final EvidencePolicy policy = new EvidencePolicy(json, Set.of("official.example"));
    private final LocalDate start = LocalDate.parse("2026-11-01");
    private final LocalDate end = start.plusDays(2);

    /** 模型收到按操作区分的日期要求；五种合法查询经实际DTO解码后通过原校验。 */
    @Test
    void planningPromptAndDecodedQueriesMatchApplicationDateRules() {
        var queries =
                List.of(
                        query(Operation.PLACE, null),
                        query(Operation.SEARCH, null),
                        query(Operation.WEATHER, start),
                        query(Operation.ROUTE, end),
                        query(Operation.EXTRACT, start));
        var branches = new ArrayList<AgentStrategy.Branch>();
        for (int i = 0; i < queries.size(); i++) {
            branches.add(
                    new AgentStrategy.Branch(
                            i, List.of(), List.of(queries.get(i).operation().name())));
        }
        var plan =
                new Research.Plan(
                        queries,
                        new AgentStrategy(
                                1,
                                AgentStrategy.Goal.RESEARCH,
                                AgentStrategy.Stop.PLAN,
                                branches,
                                List.of()));
        var runtime = mock(AgentRuntimePort.class);
        var invocation = mock(AgentRuntimePort.Invocation.class);
        when(runtime.startContextual(
                        any(), eq("coordinator"), anyString(), any(), eq(Research.Plan.class)))
                .thenReturn(invocation);
        when(invocation.result()).thenReturn(CompletableFuture.completedFuture(json.encode(plan)));
        var scope = new TaskScope(UUID.randomUUID(), UUID.randomUUID());
        var requirements =
                List.of(
                        new ConstraintRevision.Requirement(
                                UUID.randomUUID(),
                                "startDate",
                                start.toString(),
                                ConstraintRevision.Kind.HARD,
                                ConstraintRevision.Source.USER,
                                Set.of()),
                        new ConstraintRevision.Requirement(
                                UUID.randomUUID(),
                                "endDate",
                                end.toString(),
                                ConstraintRevision.Kind.HARD,
                                ConstraintRevision.Source.USER,
                                Set.of()));
        var context =
                new Research.Context(
                        new ConstraintRevision(scope, 1, requirements, List.of()),
                        List.of(),
                        Instant.parse("2026-10-30T00:00:00Z"));
        var result =
                new HarnessResearchAdvisor(runtime, json, true, Set.of("official.example"))
                        .plan(UUID.randomUUID(), context);
        assertThat(result).isEqualTo(plan);
        result.queries().forEach(q -> assertThat(policy.checked(q, start, end)).isSameAs(q));
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(runtime)
                .startContextual(
                        any(), eq("coordinator"), prompt.capture(), any(), eq(Research.Plan.class));
        assertThat(prompt.getValue())
                .contains(
                        "PLACE/poi 参数city,keywords，query.date可为null",
                        "SEARCH/searchLead 参数query，query.date可为null",
                        "这两种操作的query.date必填，且必须与arguments.date完全一致",
                        "query.date必须填写目标旅行日期，不得为null",
                        "已确认旅行起止日期范围内",
                        "缺少所需旅行日期时使用CLARIFY",
                        "validForDate仍须依据原文，未知保持null");
        assertThat(prompt.getValue()).doesNotContain("date为适用ISO旅行日期或null");
        verify(invocation).close();
    }

    /** 四类官方字段提取都必须给目标旅行日期，不允许以空日期或范围外日期绕过校验。 */
    @Test
    void extractionRequiresInRangeTravelDateForEveryField() {
        for (String field : List.of("openingHours", "price", "reservation", "accessibility")) {
            var dated = query(Operation.EXTRACT, start);
            var valid =
                    new Research.Query(
                            dated.targetId(), field, dated.operation(), dated.arguments(), end);
            assertThat(policy.checked(valid, start, end)).isSameAs(valid);
            var missing =
                    new Research.Query(
                            valid.targetId(), field, valid.operation(), valid.arguments(), null);
            assertThatThrownBy(() -> policy.checked(missing, start, end))
                    .hasMessage("RESEARCH_DATE_REQUIRED");
            var outside =
                    new Research.Query(
                            valid.targetId(),
                            field,
                            valid.operation(),
                            valid.arguments(),
                            end.plusDays(1));
            assertThatThrownBy(() -> policy.checked(outside, start, end))
                    .hasMessage("RESEARCH_QUERY_INVALID");
        }
    }

    /** 天气与路线必须同时提供相同日期；缺失、错日和非ISO格式都不能通过。 */
    @Test
    void weatherAndRouteRequireMatchingArgumentDate() {
        for (Operation operation : List.of(Operation.WEATHER, Operation.ROUTE)) {
            var valid = query(operation, start);
            assertThat(policy.checked(valid, start, end)).isSameAs(valid);
            var missing =
                    new Research.Query(
                            valid.targetId(),
                            valid.fieldPath(),
                            operation,
                            valid.arguments(),
                            null);
            assertThatThrownBy(() -> policy.checked(missing, start, end))
                    .hasMessage("RESEARCH_DATE_MISMATCH");
            for (String argumentDate : List.of("", end.toString(), "2026/11/01")) {
                var arguments = new HashMap<>(valid.arguments());
                if (argumentDate.isEmpty()) arguments.remove("date");
                else arguments.put("date", argumentDate);
                var mismatch =
                        new Research.Query(
                                valid.targetId(), valid.fieldPath(), operation, arguments, start);
                assertThatThrownBy(() -> policy.checked(mismatch, start, end))
                        .hasMessage("RESEARCH_DATE_MISMATCH");
            }
        }
    }

    /** 地点和搜索允许空日期；主动绑定旅行日期时仍受旅行范围限制。 */
    @Test
    void placeAndSearchKeepOptionalButBoundedDates() {
        for (Operation operation : List.of(Operation.PLACE, Operation.SEARCH)) {
            assertThat(policy.checked(query(operation, null), start, end).date()).isNull();
            assertThat(policy.checked(query(operation, end), start, end).date()).isEqualTo(end);
            assertThatThrownBy(
                            () -> policy.checked(query(operation, start.minusDays(1)), start, end))
                    .hasMessage("RESEARCH_QUERY_INVALID");
        }
    }

    /** 查询日期仅表示调查目标，原文未确认适用日期时不能自动提升为已知证据。 */
    @Test
    void targetDateDoesNotReplaceUnknownEvidenceDate() {
        String text = "2026-11-01 门票20元";
        var raw =
                new TravelToolPort.Evidence(
                        URI.create("https://official.example/tickets"),
                        Instant.parse("2026-10-30T00:00:00Z"),
                        json.encode(
                                Map.of(
                                        "operation",
                                        "EXTRACT",
                                        "status",
                                        "AVAILABLE",
                                        "data",
                                        Map.of(
                                                "results",
                                                List.of(
                                                        Map.of(
                                                                "url",
                                                                "https://official.example/tickets",
                                                                "raw_content",
                                                                text))))));
        var evidence =
                policy.normalize(
                        query(Operation.EXTRACT, start),
                        raw,
                        new Research.Extraction("20元", text, null, null),
                        raw.retrievedAt());
        assertThat(evidence.status()).isEqualTo(EvidenceRecord.Status.UNKNOWN);
    }

    /**
     * 构造五种工具的合法参数；天气和路线参数日期固定为对应目标日期。
     *
     * @param operation 受测操作
     * @param date 查询目标日期，地点/搜索可空
     * @return 不含真实用户或外部账号信息的查询
     */
    private Research.Query query(Operation operation, LocalDate date) {
        var arguments =
                switch (operation) {
                    case PLACE -> Map.of("city", "北京", "keywords", "公园");
                    case SEARCH -> Map.of("query", "北京公园官方票价");
                    case EXTRACT -> Map.of("url", "https://official.example/tickets");
                    case WEATHER -> Map.of("city", "110000", "date", date.toString());
                    case ROUTE ->
                            Map.of(
                                    "origin",
                                    "116,40",
                                    "destination",
                                    "117,40",
                                    "mode",
                                    "walking",
                                    "date",
                                    date.toString());
                };
        String field =
                switch (operation) {
                    case PLACE -> "poi";
                    case SEARCH -> "searchLead";
                    case EXTRACT -> "price";
                    case WEATHER -> "weather";
                    case ROUTE -> "route";
                };
        return new Research.Query("北京:" + operation, field, operation, arguments, date);
    }
}
