package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.execution.ExecutionCorrelation;
import com.lawrence.travel.application.memory.AgentContext;
import com.lawrence.travel.application.port.ExecutionTrace;
import com.lawrence.travel.domain.memory.ConversationDigest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 原Harness压缩器的追踪装饰契约；不调用模型或供应商。 */
class TracedCompressionTest {
    /** 失败时关闭压缩节点并恢复父级，业务attempt始终不变。 */
    @Test
    void compressionRestoresParentAndRetainsFailure() {
        UUID parent = UUID.randomUUID(), child = UUID.randomUUID(), attempt = UUID.randomUUID();
        var failure = new AtomicReference<ExecutionTrace.Failure>();
        var closed = new java.util.concurrent.atomic.AtomicInteger();
        ExecutionTrace trace =
                (budget, kind, metadata) -> {
                    assertThat(kind).isEqualTo(ExecutionTrace.Kind.COMPACT);
                    var binding = ExecutionCorrelation.bindTrace(child);
                    return new ExecutionTrace.Span() {
                        /** 保存固定失败分类，不收异常正文。 */
                        @Override
                        public void fail(ExecutionTrace.Failure value) {
                            failure.set(value);
                        }

                        /** 模拟持久化节点结束并恢复诊断父级。 */
                        @Override
                        public void close() {
                            closed.incrementAndGet();
                            binding.close();
                        }
                    };
                };
        var transport = mock(GuardedDashScopeTransport.class);
        AgentContext.Compression delegate =
                new AgentContext.Compression() {
                    /** 本地估算不产生模型请求。 */
                    @Override
                    public long estimate(String text) {
                        return text.length();
                    }

                    /** 验证绑定；空输入作为本地失败反例。 */
                    @Override
                    public ConversationDigest compact(List<AgentContext.Message> messages) {
                        assertThat(ExecutionCorrelation.traceParent()).isEqualTo(child);
                        assertThat(ExecutionCorrelation.parentSpan()).isEqualTo(attempt);
                        if (messages.isEmpty())
                            throw new IllegalStateException("SYNTHETIC_COMPRESSION_FAILURE");
                        return new ConversationDigest(List.of());
                    }
                };
        var compression = new TracedCompression(delegate, trace, UUID.randomUUID(), transport);
        try (var business = ExecutionCorrelation.bindSpan(attempt);
                var outer = ExecutionCorrelation.bindTrace(parent)) {
            assertThatThrownBy(() -> compression.compact(List.of()))
                    .hasMessage("SYNTHETIC_COMPRESSION_FAILURE");
            assertThat(failure.get()).isEqualTo(ExecutionTrace.Failure.FAILED);
            assertThat(closed.get()).isEqualTo(1);
            assertThat(ExecutionCorrelation.traceParent()).isEqualTo(parent);
            verify(transport).traceParent(child);
            verify(transport).traceParent(parent);
        }
    }
}
