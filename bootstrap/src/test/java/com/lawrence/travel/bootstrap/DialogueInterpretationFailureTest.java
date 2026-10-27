package com.lawrence.travel.bootstrap;

import com.lawrence.travel.agent.runtime.DialogueIntentInterpreter;
import com.lawrence.travel.application.interaction.Dialogue;
import com.lawrence.travel.application.interaction.DialogueInterpretationFailure;
import com.lawrence.travel.application.port.AgentRuntimePort;
import com.lawrence.travel.infrastructure.interaction.InteractionJson;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import com.lawrence.travel.domain.execution.control.CallRejectedException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 完全离线的适配器异常边界测试；合成私密标记不得进入异常文本、cause或suppressed。 */
class DialogueInterpretationFailureTest {
    /** 合法传输但业务JSON损坏时明确解码失败，关闭句柄且不携带输出正文。 */
    @Test
    void malformedResultHasOnlyDecodeCode() {
        var runtime = mock(AgentRuntimePort.class);
        var call = mock(AgentRuntimePort.Invocation.class);
        when(runtime.startContextual(any(), anyString(), anyString(), any())).thenReturn(call);
        when(call.result()).thenReturn(CompletableFuture.completedFuture("SYNTHETIC_PRIVATE_BODY"));
        org.mockito.Mockito.doThrow(new IllegalStateException("SYNTHETIC_PRIVATE_CLOSE_FAILURE"))
                .when(call)
                .close();
        assertThatThrownBy(() -> adapter(runtime).interpret(claim()))
                .isInstanceOf(
                        com.lawrence.travel.application.interaction.DialogueDecodeFailure.class)
                .hasMessage("DIALOGUE_DECODE_SYNTAX_ROOT")
                .hasNoCause()
                .satisfies(e -> assertThat(e.getSuppressed()).isEmpty());
        verify(call).close();
    }

    /** 建立失败和异步执行失败分开，二者均不保留供应商异常或嵌套文本。 */
    @Test
    void startAndExecutionFailuresHaveDifferentCodes() {
        var runtime = mock(AgentRuntimePort.class);
        when(runtime.startContextual(any(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("SYNTHETIC_PRIVATE_FAILURE"));
        assertThatThrownBy(() -> adapter(runtime).interpret(claim()))
                .hasMessage("DIALOGUE_MODEL_START_FAILED")
                .hasNoCause();
        var call = mock(AgentRuntimePort.Invocation.class);
        org.mockito.Mockito.doReturn(call)
                .when(runtime)
                .startContextual(any(), anyString(), anyString(), any());
        when(call.result())
                .thenReturn(
                        CompletableFuture.failedFuture(
                                new IllegalStateException("SYNTHETIC_PRIVATE_FAILURE")));
        assertThatThrownBy(() -> adapter(runtime).interpret(claim()))
                .hasMessage("DIALOGUE_MODEL_EXECUTION_FAILED")
                .hasNoCause();
        verify(call).close();
    }

    /** 已有调用门禁分类优先，不能被通用诊断码覆盖或当成模型协议错误。 */
    @Test
    void admissionFailureKeepsItsOriginalType() {
        var runtime = mock(AgentRuntimePort.class);
        var call = mock(AgentRuntimePort.Invocation.class);
        when(runtime.startContextual(any(), anyString(), anyString(), any())).thenReturn(call);
        var denial = new CallRejectedException(CallRejectedException.Reason.CALL_BUDGET_EXHAUSTED);
        when(call.result()).thenReturn(CompletableFuture.failedFuture(denial));
        assertThatThrownBy(() -> adapter(runtime).interpret(claim())).isSameAs(denial);
        verify(call).close();
    }

    /**
     * @param runtime 仅内存句柄端口
     * @return 启用适配器协议但无真实模型通道
     */
    private DialogueIntentInterpreter adapter(AgentRuntimePort runtime) {
        return new DialogueIntentInterpreter(runtime, new InteractionJson(new TravelJson()), true);
    }

    /**
     * @return 无数据库和凭据的合成领取；不能用于真实准入
     */
    private Dialogue.Claim claim() {
        return new Dialogue.Claim(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                "改到十二月",
                Instant.parse("2026-10-27T00:00:00Z"),
                new Dialogue.Basis(null, null, 0, 0, null),
                "{}");
    }
}
