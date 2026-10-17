package com.lawrence.travel.bootstrap;

import com.lawrence.travel.agent.runtime.HarnessContextCompression;
import com.lawrence.travel.application.memory.AgentContext;
import com.lawrence.travel.application.memory.ContextAssembler;
import com.lawrence.travel.application.port.ContextStore;
import com.lawrence.travel.domain.memory.ConversationDigest;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.infrastructure.interaction.InteractionJson;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 真实SDK压缩＋本地模型、保护区和输入预算确定性验证；无供应商网络。 */
class ContextRulesTest {
    private final TravelJson json = new TravelJson();
    /** 模型摘要只能逐条引用实际连续原文，新增事实和重复来源拒绝。 */
    @Test void summaryRejectsInventedFactsAndForeignSources() {
        UUID id = UUID.randomUUID(); var good = new ConversationDigest(List.of(new ConversationDigest.Excerpt(id, "原文"))); assertThat(good.verified(Map.of(id, "这是原文"))).isSameAs(good);
        assertThatThrownBy(() -> good.verified(Map.of(UUID.randomUUID(), "这是原文"))).hasMessage("SUMMARY_SOURCE_MISMATCH");
        assertThatThrownBy(() -> new ConversationDigest(List.of(new ConversationDigest.Excerpt(id, "自动接受"))).verified(Map.of(id, "这是原文"))).hasMessage("SUMMARY_SOURCE_MISMATCH");
        assertThatThrownBy(() -> new ConversationDigest(List.of(good.excerpts().getFirst(), good.excerpts().getFirst())).verified(Map.of(id, "这是原文"))).hasMessage("SUMMARY_SOURCE_MISMATCH");
    }
    /** 使用2.0.4实际ConversationCompactor，摘要调用没有业务保护区或工具Schema。 */
    @Test void frameworkCompactionUsesOnlyHistoryAndNoTools() {
        UUID id = UUID.randomUUID(); var calls = new AtomicInteger();
        Model model = new Model() {
            /** {@inheritDoc} 捕获真实框架发起的摘要请求，只返回固定原文摘录。 */
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                calls.incrementAndGet(); assertThat(tools == null || tools.isEmpty()).isTrue(); String text = messages.stream().map(Msg::getTextContent).collect(java.util.stream.Collectors.joining());
                assertThat(text).contains(id.toString(), "历史原文").doesNotContain("PROTECTED_HARD_CONSTRAINT");
                return Flux.just(ChatResponse.builder().id("local-summary").content(List.of(TextBlock.builder().text(json.encode(new ConversationDigest(List.of(new ConversationDigest.Excerpt(id, "历史原文"))))).build())).build());
            }
            /** @return 固定离线模型身份，不能误认为真实型号 */
            @Override public String getModelName() { return "offline-context-summary"; }
        };
        var compressor = new HarnessContextCompression(model, new InteractionJson(json), ignored -> { });
        var result = compressor.compact(List.of(new AgentContext.Message(id, 1, "历史原文", AgentContext.hash("历史原文"))));
        assertThat(result.excerpts().getFirst().quote()).isEqualTo("历史原文"); assertThat(calls.get()).isEqualTo(1);
    }
    /** 中文、emoji及带引用JSON使用保守估计，不把字符数当作模型Token精确值。 */
    @Test void tokenEstimateBoundsUtf8AndBudgetsAreRoleSpecific() {
        String text = "你好🌧️".repeat(100); assertThat(HarnessContextCompression.estimateInput(text)).isGreaterThanOrEqualTo(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 4096L);
        assertThat(ContextAssembler.limit("coordinator")).isEqualTo(16384); assertThat(ContextAssembler.limit("research")).isEqualTo(16384); assertThat(ContextAssembler.limit("planner")).isEqualTo(32768); assertThat(ContextAssembler.limit("reviewer")).isEqualTo(32768);
    }
    /** 关键区本身无法容纳时暂停，不调用摘要器或保存被截断产物。 */
    @Test void oversizedProtectedInputCannotBeCompressedAway() {
        var store = mock(ContextStore.class); var compression = mock(AgentContext.Compression.class); UUID run = UUID.randomUUID();
        when(store.load(run, "planner")).thenReturn(source(run, "planner", "{\"hard\":\"PROTECTED_HARD_CONSTRAINT\"}", List.of())); when(compression.estimate(any())).thenReturn(40000L);
        assertThatThrownBy(() -> new ContextAssembler(store, new InteractionJson(json)).prepare(run, "planner", new AgentContext.Basis(1, 0L), "当前完整请求", compression)).hasMessage("CONTEXT_BUDGET_EXHAUSTED");
        verify(compression, never()).compact(any()); verify(store, never()).save(any(), any());
    }
    /** 审核即使来源包含历史也不注入，不触发压缩或读取历史摘要。 */
    @Test void reviewerNeverReceivesConversationOrPlannerSelfEvaluation() {
        var store = mock(ContextStore.class); var compression = mock(AgentContext.Compression.class); UUID run = UUID.randomUUID();
        when(store.load(run, "reviewer")).thenReturn(source(run, "reviewer", "{\"hard\":\"保留\"}", List.of(new AgentContext.Message(UUID.randomUUID(), 1, "PLANNER_SELF_EVALUATION", "h")))); when(compression.estimate(any())).thenReturn(5000L);
        var result = new ContextAssembler(store, new InteractionJson(json)).prepare(run, "reviewer", new AgentContext.Basis(1, 0L), "独立审核数据", compression);
        assertThat(result.prompt()).contains("独立审核数据", "保留").doesNotContain("PLANNER_SELF_EVALUATION"); verify(store, never()).cached(any()); verify(compression, never()).compact(any());
    }
    /** @param run 原运行 @param role 固定角色 @param authority 保护区 @param history 测试历史 @return 明确版本来源 */
    private AgentContext.Source source(UUID run, String role, String authority, List<AgentContext.Message> history) { return new AgentContext.Source(new TaskScope(UUID.randomUUID(), UUID.randomUUID()), run, role, 1, 0, 1, 1, authority, AgentContext.hash(authority), history); }
    /** 十天结构化草案在输入预算内整体保留，不能为了压缩偷偷删掉后几天对象。 */
    @Test void tenDayStructuredRequestRemainsByteForByteComplete() {
        var store = mock(ContextStore.class); UUID run = UUID.randomUUID();
        var days = java.util.stream.IntStream.rangeClosed(1, 10).mapToObj(day -> Map.of("day", day, "id", UUID.randomUUID(), "details", "x".repeat(1400))).toList(); String request = json.encode(Map.of("days", days));
        when(store.load(run, "planner")).thenReturn(source(run, "planner", "{\"hard\":\"不可删除日期\"}", List.of()));
        var compression = new HarnessContextCompression(null, new InteractionJson(json), ignored -> { });
        var result = new ContextAssembler(store, new InteractionJson(json)).prepare(run, "planner", new AgentContext.Basis(1, 0L), request, compression);
        var body = json.decode(result.prompt().substring(result.prompt().indexOf('{')), Map.class); assertThat(body.get("currentRequest")).isEqualTo(request); verify(store, never()).save(any(), any());
    }

    /** SDK的摘要不可用回退不能吞掉预算／免费门禁，否则执行器无法正确暂停原运行。 */
    @Test void frameworkFallbackDoesNotHideBudgetRejection() {
        Model rejected = new Model() {
            /** {@inheritDoc} 只返回固定门禁异常，无真实请求。 */
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) { return Flux.error(new com.lawrence.travel.domain.execution.control.CallRejectedException(com.lawrence.travel.domain.execution.control.CallRejectedException.Reason.CALL_BUDGET_EXHAUSTED)); }
            /** @return 本地门禁替身名称 */
            @Override public String getModelName() { return "offline-rejected-summary"; }
        };
        var compressor = new HarnessContextCompression(rejected, new InteractionJson(json), ignored -> { });
        assertThatThrownBy(() -> compressor.compact(List.of(new AgentContext.Message(UUID.randomUUID(), 1, "本地原文", "hash")))).hasMessage("CALL_BUDGET_EXHAUSTED");
    }

}
