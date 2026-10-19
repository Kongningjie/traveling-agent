package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.memory.AgentContext;
import com.lawrence.travel.application.port.ExecutionTrace;
import com.lawrence.travel.domain.memory.ConversationDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 仅包围实际压缩调用，不以提示词猜测费用类型，仍使用原Harness组件和预算。 */
final class TracedCompression implements AgentContext.Compression {
    private final AgentContext.Compression delegate;
    private final ExecutionTrace trace;
    private final UUID budget;
    private final GuardedDashScopeTransport transport;
    /** @param delegate 原压缩器 @param trace 事实 @param budget 原预算 @param transport 同一发送边界 */
    TracedCompression(AgentContext.Compression delegate, ExecutionTrace trace, UUID budget, GuardedDashScopeTransport transport) {this.delegate=delegate;this.trace=trace;this.budget=budget;this.transport=transport;}
    /** {@inheritDoc} 不改变原业务快照。 */
    @Override public void bind(AgentContext.Source source) {delegate.bind(source);}
    /** {@inheritDoc} 估算不产生模型请求。 */
    @Override public long estimate(String text) {return delegate.estimate(text);}
    /** {@inheritDoc} 失败轨迹保留，父轨迹恢复后主调用不能归到已结束压缩。 */
    @Override public ConversationDigest compact(List<AgentContext.Message> messages) {
        try(var span=trace.open(budget,ExecutionTrace.Kind.COMPACT,Map.of("schema","conversation-digest-v1","sourceCount",Integer.toString(messages.size())))) {
            transport.traceParent(com.lawrence.travel.application.execution.ExecutionCorrelation.traceParent());
            try{return delegate.compact(messages);}catch(RuntimeException failure){span.fail(ExecutionTrace.Failure.FAILED);throw failure;}
        } finally {transport.traceParent(com.lawrence.travel.application.execution.ExecutionCorrelation.traceParent());}
    }
}
