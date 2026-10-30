package com.lawrence.travel.bootstrap;

import com.lawrence.travel.agent.runtime.SanitizedAgentFailure;
import com.lawrence.travel.application.port.AgentRuntimePort;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 本地验证类型诊断不泄露正文，且不改变原句柄结果和关闭行为。 */
class RuntimeFailureEvidenceTest {
    /** 首个异步失败完成时立即锁定批次停止标记，后续成功不能清除它。 */
    @Test
    void firstFailureLatchesBatchStopBeforeNextReservation() {
        var evidence = new RuntimeFailureEvidence();
        var pending = new CompletableFuture<String>();
        var original = mock(AgentRuntimePort.Invocation.class);
        when(original.result()).thenReturn(pending);
        var observed = evidence.observe("coordinator", original);
        assertThat(evidence.failed()).isFalse();
        pending.completeExceptionally(
                new SanitizedAgentFailure(
                        "MODEL_OUTPUT_UNSTRUCTURED",
                        new IllegalStateException("SYNTHETIC_PRIVATE")));
        assertThatThrownBy(() -> observed.result().toCompletableFuture().join())
                .hasCauseInstanceOf(SanitizedAgentFailure.class);
        assertThat(evidence.failed()).isTrue();
        assertThat(evidence.snapshot().getFirst())
                .containsEntry("code", "MODEL_OUTPUT_UNSTRUCTURED");
        var success = mock(AgentRuntimePort.Invocation.class);
        when(success.result()).thenReturn(CompletableFuture.completedFuture("合成成功"));
        assertThat(evidence.observe("planner", success).result().toCompletableFuture().join())
                .isEqualTo("合成成功");
        assertThat(evidence.failed()).isTrue();
        assertThat(evidence.snapshot().toString()).doesNotContain("SYNTHETIC_PRIVATE");
    }

    /** 只有白名单固定码可以导出；空值或任意异常字符串不能混入诊断。 */
    @Test
    void unlistedCodesAreNotExported() {
        for (String code : new String[] {null, "SYNTHETIC_PRIVATE_CODE"}) {
            var original = mock(AgentRuntimePort.Invocation.class);
            when(original.result())
                    .thenReturn(
                            CompletableFuture.failedFuture(
                                    new SanitizedAgentFailure(
                                            code,
                                            new IllegalStateException("SYNTHETIC_PRIVATE_BODY"))));
            var evidence = new RuntimeFailureEvidence();
            try (var observed = evidence.observe("coordinator", original)) {
                assertThatThrownBy(() -> observed.result().toCompletableFuture().join())
                        .hasCauseInstanceOf(SanitizedAgentFailure.class);
            }
            assertThat(evidence.snapshot().getFirst())
                    .containsEntry("code", "MODEL_EXECUTION_FAILED");
            assertThat(evidence.snapshot().toString()).doesNotContain("SYNTHETIC_PRIVATE");
        }
    }

    /** 空角色及非白名单角色只记录OTHER，不泄露角色参数或改变原失败。 */
    @Test
    void unknownRolesAreRedacted() {
        for (String role : new String[] {null, "SYNTHETIC_PRIVATE_ROLE"}) {
            var original = mock(AgentRuntimePort.Invocation.class);
            when(original.result())
                    .thenReturn(
                            CompletableFuture.failedFuture(
                                    new IllegalStateException("SYNTHETIC_PRIVATE_ERROR")));
            var evidence = new RuntimeFailureEvidence();
            try (var observed = evidence.observe(role, original)) {
                assertThatThrownBy(() -> observed.result().toCompletableFuture().join())
                        .hasCauseInstanceOf(IllegalStateException.class);
            }
            assertThat(evidence.snapshot().getFirst()).containsEntry("role", "OTHER");
            assertThat(evidence.snapshot().toString()).doesNotContain("SYNTHETIC_PRIVATE");
        }
    }

    /** 成功结果保持原样，不产生失败记录，关闭仍交给原句柄。 */
    @Test
    void preservesSuccessAndClose() {
        var original = mock(AgentRuntimePort.Invocation.class);
        when(original.result()).thenReturn(CompletableFuture.completedFuture("合成回答"));
        var evidence = new RuntimeFailureEvidence();
        try (var observed = evidence.observe("coordinator", original)) {
            assertThat(observed.result().toCompletableFuture().join()).isEqualTo("合成回答");
        }
        assertThat(evidence.snapshot()).isEmpty();
        verify(original).close();
    }

    /** 异步框架失败只保留Java类型，原私密异常消息和模型正文均不写入记录。 */
    @Test
    void recordsOnlySanitizedExceptionTypes() {
        var original = mock(AgentRuntimePort.Invocation.class);
        var failure =
                new SanitizedAgentFailure(
                        "MODEL_EXECUTION_FAILED",
                        new IllegalArgumentException(
                                "SYNTHETIC_PRIVATE_MODEL_BODY",
                                new NullPointerException("SYNTHETIC_PRIVATE_CAUSE")));
        when(original.result()).thenReturn(CompletableFuture.failedFuture(failure));
        var evidence = new RuntimeFailureEvidence();
        try (var observed = evidence.observe("coordinator", original)) {
            assertThatThrownBy(() -> observed.result().toCompletableFuture().join())
                    .hasCause(failure);
        }
        assertThat(evidence.snapshot()).hasSize(1);
        assertThat((List<?>) evidence.snapshot().getFirst().get("exceptionSites")).hasSize(2);
        String record = evidence.snapshot().toString();
        assertThat(record)
                .contains("java.lang.IllegalArgumentException", "java.lang.NullPointerException")
                .contains("MODEL_EXECUTION_FAILED")
                .doesNotContain("SYNTHETIC_PRIVATE", ".java");
    }

    /** 关闭失败也只记录类型，并把原失败继续交回调用方。 */
    @Test
    void recordsCloseFailureWithoutSwallowingIt() {
        var original = mock(AgentRuntimePort.Invocation.class);
        when(original.result()).thenReturn(CompletableFuture.completedFuture("合成回答"));
        var failure = new IllegalStateException("SYNTHETIC_PRIVATE_CLOSE");
        org.mockito.Mockito.doThrow(failure).when(original).close();
        var evidence = new RuntimeFailureEvidence();
        var observed = evidence.observe("coordinator", original);
        assertThatThrownBy(observed::close).isSameAs(failure);
        assertThat(evidence.snapshot().getFirst()).containsEntry("boundary", "CLOSE");
        assertThat(evidence.snapshot().toString()).doesNotContain("SYNTHETIC_PRIVATE_CLOSE");
    }
}
