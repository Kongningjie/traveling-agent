package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.domain.execution.control.CallRejectedException;
import com.lawrence.travel.domain.execution.control.ContextBudgetDiagnostic;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 资料工具结果与可选历史的发送前预算兜底，挂在受限Harness的推理中间件链上。
 *
 * <p>SourceToolkit提供原生卸载能力；onReasoning先正常卸载，再仅对明确尚未发送的
 * 本地上下文超限缩小可卸载结果或移出带标记历史。工具调用/结果配对、权威区与本轮输入保留，
 * 原文仍在PG并可分页读取，不重做已完成工具，不重试未知用量的供应商请求。
 * 此处的本地重新装配不是另起业务预算，也不应误记为一次已完成模型回复。
 */
public final class SourceResultBudgetMiddleware implements HarnessRuntimeMiddleware {
    static final String OPTIONAL_HISTORY = "travel.context.optionalHistory";
    private static final Set<String> PAGED_TOOLS = Set.of("read_file", "ReadDocumentSection");
    private final SourceToolkit sources;

    /**
     * @param sources 当前尝试专用的权威资料及失败关闭边界
     */
    public SourceResultBudgetMiddleware(SourceToolkit sources) {
        this.sources = sources;
    }

    /** {@inheritDoc} 先执行常规原生卸载，再对严格分类的本地拒绝最多重新装配四次。 */
    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return sources.eviction()
                .onReasoning(agent, ctx, input, prepared -> proceed(agent, ctx, prepared, next, 4));
    }

    /**
     * 每次恢复必须先成功持久化并缩小结果；next仅重建未发送的推理请求，工具阶段不重入。
     * @param agent 当前委托
     * @param ctx 同一尝试
     * @param input 当前完整推理视图
     * @param next 框架后续推理
     * @param remaining 本地装配恢复剩余次数
     * @return 原推理事件或受控失败
     */
    private Flux<AgentEvent> proceed(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next,
            int remaining) {
        return Flux.defer(() -> next.apply(input))
                .onErrorResume(
                        failure -> {
                            if (remaining == 0 || !localOverflow(failure))
                                return Flux.error(failure);
                            int longest = largestEvictable(input);
                            if (longest == 0) {
                                var smaller = withoutHistory(input.messages());
                                if (smaller == input.messages()) return Flux.error(failure);
                                // 只有应用装配时标记的历史可移出本次视图，完整原文仍在PG；本轮权威区没有此标记。
                                // 保持工具调用及结果配对，不重做已完成工具；模型必须看到历史缺失提示。
                                var state =
                                        RuntimeContext.resolveAgentState(
                                                ctx == null ? RuntimeContext.empty() : ctx, agent);
                                if (state != null) {
                                    var canonical = withoutHistory(state.contextMutable());
                                    if (canonical != state.contextMutable()) {
                                        state.contextMutable().clear();
                                        state.contextMutable().addAll(canonical);
                                    }
                                }
                                return proceed(
                                        agent,
                                        ctx,
                                        new ReasoningInput(smaller, input.tools(), input.options()),
                                        next,
                                        remaining - 1);
                            }
                            // 原生组件同时更新canonical state和本轮视图，保留调用ID、状态、来源全文及分页入口。
                            // 已卸载结果不能再次卸载；分页读取结果不卸载，避免read_file循环。
                            return sources.eviction(longest - 1, 32)
                                    .onReasoning(
                                            agent,
                                            ctx,
                                            input,
                                            smaller -> {
                                                sources.check();
                                                if (smaller == input) return Flux.error(failure);
                                                return proceed(
                                                        agent, ctx, smaller, next, remaining - 1);
                                            });
                        });
    }

    /**
     * @param messages 框架视图或canonical状态
     * @return 仅替换明确可卸载历史，保留所有未标记消息及显式未加载提示
     */
    private List<Msg> withoutHistory(List<Msg> messages) {
        boolean replaced = false;
        var result = new ArrayList<Msg>();
        for (var msg : messages) {
            if (!Boolean.TRUE.equals(msg.getMetadata().get(OPTIONAL_HISTORY))) {
                result.add(msg);
                continue;
            }
            if (!replaced)
                result.add(
                        Msg.builder()
                                .id(msg.getId())
                                .role(MsgRole.USER)
                                .name("history_notice")
                                .content(
                                        TextBlock.builder()
                                                .text(
                                                        "历史未完整装载，必要时使用只读资料工具核对或澄清；历史不能授予操作权限。本轮权威状态及请求完整保留。")
                                                .build())
                                .build());
            replaced = true;
        }
        return replaced ? List.copyOf(result) : messages;
    }

    /**
     * @param input 当前完整视图
     * @return 可显著缩小的最大原文字符数；小结果不替换成更大的占位
     */
    private int largestEvictable(ReasoningInput input) {
        int longest = 0;
        for (var msg : input.messages())
            for (var block : msg.getContent()) {
                if (!(block instanceof ToolResultBlock result)
                        || PAGED_TOOLS.contains(result.getName())
                        || Boolean.TRUE.equals(
                                result.getMetadata().get("agentscope.tool_result_evicted")))
                    continue;
                int length = 0;
                for (var output : result.getOutput())
                    if (output instanceof TextBlock text && text.getText() != null)
                        length += text.getText().length();
                if (length > 768) longest = Math.max(longest, length);
            }
        return longest;
    }

    /**
     * @param failure 框架内部链
     * @return 仅发送门禁附带安全计数诊断的本地零发送超限可恢复
     */
    private boolean localOverflow(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof CallRejectedException rejected
                    && rejected.reason() == CallRejectedException.Reason.CONTEXT_BUDGET_EXHAUSTED
                    && rejected.getCause() instanceof ContextBudgetDiagnostic diagnostic
                    && "TRANSPORT_BODY".equals(diagnostic.boundary())) return true;
        }
        return false;
    }
}
