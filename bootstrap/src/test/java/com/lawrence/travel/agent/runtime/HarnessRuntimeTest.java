package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.execution.ModelCallAdmission;
import com.lawrence.travel.domain.execution.control.CallRejectedException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/** 从应用端口运行完整 SDK 格式化路径，验证门禁失败保留分类且不会重试或发送。 */
class HarnessRuntimeTest {
    @TempDir Path workspace;

    /** 无业务契约或错误类型在读取凭据、上下文和计量前拒绝。 */
    @Test
    void contextualCallsRequireExplicitSupportedOutputType() {
        var admission = mock(ModelCallAdmission.class);
        var runtime =
                new HarnessAgentRuntimeAdapter(
                        admission,
                        Map.of("coordinator", "local"),
                        () -> {
                            throw new AssertionError("MUST_NOT_READ_CREDENTIAL");
                        },
                        workspace);
        var basis = new com.lawrence.travel.application.memory.AgentContext.Basis(0, null);
        UUID run = UUID.randomUUID();
        assertThatThrownBy(() -> runtime.startContextual(run, "coordinator", "本地", basis))
                .hasMessage("BUSINESS_OUTPUT_TYPE_REQUIRED");
        assertThatThrownBy(
                        () -> runtime.startContextual(run, "coordinator", "本地", basis, Map.class))
                .hasMessage("BUSINESS_OUTPUT_TYPE_INVALID");
        assertThatThrownBy(() -> runtime.startContextual(run, "coordinator", "本地", basis, null))
                .hasMessage("BUSINESS_OUTPUT_TYPE_INVALID");
        org.mockito.Mockito.verifyNoInteractions(admission);
    }

    /** 门禁拒绝在 SDK 发送前生效，异步调用方得到可处理的暂停原因。 */
    @Test
    void forwardsAdmissionReasonWithoutRetrying() {
        var admission = mock(ModelCallAdmission.class);
        when(admission.begin(any(), any(), any(), any(), anyLong()))
                .thenThrow(
                        new CallRejectedException(CallRejectedException.Reason.QUOTA_UNVERIFIED));
        var runtime =
                new HarnessAgentRuntimeAdapter(
                        admission,
                        Map.of("planner", "test-model"),
                        () -> "test-placeholder",
                        workspace);
        try (var invocation = runtime.start(UUID.randomUUID(), "planner", "test")) {
            assertThatThrownBy(
                            () ->
                                    invocation
                                            .result()
                                            .toCompletableFuture()
                                            .get(10, TimeUnit.SECONDS))
                    .rootCause()
                    .hasMessage("QUOTA_UNVERIFIED");
            verify(admission, times(1)).begin(any(), any(), any(), any(), anyLong());
        }
    }
}
