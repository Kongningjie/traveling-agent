package com.lawrence.travel.bootstrap;

import com.lawrence.travel.domain.interaction.DialogueIntent;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 意图协议与来源的确定性校验，不把手工分类样本宣称为模型识别准确率。 */
class DialogueIntentContractTest {
    /** 真实调用开关关闭时，连Harness入口都不能触达；本测试没有配置真实Key。 */
    @Test void disabledInterpreterNeverTouchesRuntime() {
        var runtime = org.mockito.Mockito.mock(com.lawrence.travel.application.port.AgentRuntimePort.class);
        var adapter = new com.lawrence.travel.agent.runtime.DialogueIntentInterpreter(runtime, new com.lawrence.travel.infrastructure.interaction.InteractionJson(new TravelJson()), false);
        assertThatThrownBy(() -> adapter.interpret(input("{}"))).isInstanceOf(com.lawrence.travel.domain.execution.control.CallRejectedException.class);
        org.mockito.Mockito.verifyNoInteractions(runtime);
    }
    /** 大上下文保留完整原文并交统一会话预算处理，调用仍沿用指定预算且不启用工具；结构化回复来自离线句柄。 */
    @Test void boundedInterpretationUsesOriginalBudgetWithoutTools() {
        var runtime = org.mockito.Mockito.mock(com.lawrence.travel.application.port.AgentRuntimePort.class);
        var invocation = org.mockito.Mockito.mock(com.lawrence.travel.application.port.AgentRuntimePort.Invocation.class);
        var json = new TravelJson(); var expected = new DialogueIntent.Result(List.of(new DialogueIntent.Intent(DialogueIntent.Kind.CONVERSATION, DialogueIntent.Action.CHAT, "你好")), List.of(), "你好", "", null, "", "", "");
        org.mockito.Mockito.when(invocation.result()).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(json.encode(expected)));
        var claim = input(json.encode(java.util.Map.of("large", "x".repeat(10000))));
        org.mockito.Mockito.when(runtime.startContextual(org.mockito.ArgumentMatchers.eq(claim.budgetId()), org.mockito.ArgumentMatchers.eq("coordinator"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(invocation);
        var adapter = new com.lawrence.travel.agent.runtime.DialogueIntentInterpreter(runtime, new com.lawrence.travel.infrastructure.interaction.InteractionJson(json), true);
        assertThat(adapter.interpret(claim)).isEqualTo(expected);
        var prompt = org.mockito.ArgumentCaptor.forClass(String.class); org.mockito.Mockito.verify(runtime).startContextual(org.mockito.ArgumentMatchers.eq(claim.budgetId()), org.mockito.ArgumentMatchers.eq("coordinator"), prompt.capture(), org.mockito.ArgumentMatchers.any());
        assertThat(prompt.getValue()).contains("你好", "x".repeat(10000)); org.mockito.Mockito.verify(invocation).close();
    }
    /** @param context 显式测试上下文 @return 不含真实账号或任务的离线领取数据 */
    private com.lawrence.travel.application.interaction.Dialogue.Claim input(String context) {
        var id = java.util.UUID.fromString("00000000-0000-4000-8000-000000000001");
        return new com.lawrence.travel.application.interaction.Dialogue.Claim(id, id, id, 1, id, "你好", java.time.Instant.parse("2026-10-18T00:00:00Z"), new com.lawrence.travel.application.interaction.Dialogue.Basis(null, null, 0, 0, null), context);
    }
    /** 固定40条人工标注语料覆盖四类和混合诉求；只校验语料契约，不宣称模型准确率。 */
    @Test void fixedCorpusIsPartitionedAndSourceGrounded() throws Exception {
        try (var stream = getClass().getResourceAsStream("/dialogue/intent-cases.json")) {
            assertThat(stream).isNotNull(); var root = new TravelJson().decode(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), java.util.Map.class);
            var cases = (List<?>) root.get("cases"); assertThat(cases).hasSize(40);
            assertThat(cases.stream().filter(c -> ((java.util.Map<?, ?>) c).get("partition").equals("HOLDOUT")).count()).isEqualTo(10);
            for (Object raw : cases) {
                var row = (java.util.Map<?, ?>) raw; String text = (String) row.get("text");
                for (Object expected : (List<?>) row.get("expected")) {
                    var intent = (java.util.Map<?, ?>) expected;
                    var parsed = new DialogueIntent.Intent(DialogueIntent.Kind.valueOf((String) intent.get("kind")), DialogueIntent.Action.valueOf((String) intent.get("action")), (String) intent.get("quote"));
                    assertThat(text).contains(parsed.quote());
                }
            }
        }
    }
    /** 所有一级意图仅允许相容动作，咨询不能夹带执行。 */
    @Test void actionsCannotEscapeTheirKind() {
        assertThatThrownBy(() -> new DialogueIntent.Intent(DialogueIntent.Kind.CONVERSATION, DialogueIntent.Action.ACCEPT, "不错")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DialogueIntent.Intent(DialogueIntent.Kind.PLANNING, DialogueIntent.Action.CHAT, "你好")).isInstanceOf(IllegalArgumentException.class);
    }
    /** 需求字段必须归于规划，且原文依据不能来自模型自己编写的文本。 */
    @Test void factsNeedPlanningAndVerbatimUserSource() {
        var fact = new DialogueIntent.Fact("budgetCny", "6000", "六千");
        var consulting = new DialogueIntent.Intent(DialogueIntent.Kind.CONVERSATION, DialogueIntent.Action.TRAVEL_QA, "预算");
        assertThatThrownBy(() -> new DialogueIntent.Result(List.of(consulting), List.of(fact), "", "", null, "", "", "")).isInstanceOf(IllegalArgumentException.class);
        var planning = new DialogueIntent.Intent(DialogueIntent.Kind.PLANNING, DialogueIntent.Action.CHANGE_REQUIREMENTS, "预算");
        var result = new DialogueIntent.Result(List.of(planning), List.of(fact), "", "", null, "", "", "");
        assertThatThrownBy(() -> result.validateSource("预算改成七千")).isInstanceOf(IllegalArgumentException.class);
        result.validateSource("预算改成六千");
    }
    /** 混合诉求保留有序集合，严格JSON仍拒绝重复字段及尾随内容。 */
    @Test void mixedIntentsRoundTripWithoutBecomingAutomaticOperations() {
        var result = new DialogueIntent.Result(List.of(new DialogueIntent.Intent(DialogueIntent.Kind.CONVERSATION, DialogueIntent.Action.EXPLAIN_PLAN, "为什么"),
                new DialogueIntent.Intent(DialogueIntent.Kind.PLANNING, DialogueIntent.Action.REVISE_ITINERARY, "轻松点")), List.of(new DialogueIntent.Fact("pace", "轻松", "轻松点")), "解释", "", null, "", "", "");
        var json = new TravelJson(); assertThat(json.decode(json.encode(result), DialogueIntent.Result.class)).isEqualTo(result);
        result.validateSource("为什么这么满，轻松点");
        assertThatThrownBy(() -> json.decode(json.encode(result) + "{}", DialogueIntent.Result.class)).isInstanceOf(RuntimeException.class);
    }
}
