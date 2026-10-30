package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.execution.AgentCallFailure;
import com.lawrence.travel.application.execution.StageExecutor;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.StageHandler;
import com.lawrence.travel.domain.execution.ExecutionFence;
import com.lawrence.travel.domain.execution.ExecutionStep;
import com.lawrence.travel.domain.execution.StageType;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 模型失败与中断/取消的交界测试，不使用数据库替身推断事务结论。 */
class StageFailureControlTest {
    /** 暂停状态写入时暂时清除中断位，结束后恢复，且不进入旧通用重试分支。 */
    @Test
    void interruptionIsRestoredAfterFailurePersistence() throws Exception {
        var store = mock(ExecutionEngineStore.class);
        var handler = mock(StageHandler.class);
        var control = mock(StageExecutor.Control.class);
        var claim = claim();
        when(store.renew(claim.fence())).thenReturn(true);
        when(handler.supports(StageType.RESEARCH)).thenReturn(true);
        when(control.enter()).thenReturn(true);
        when(handler.execute(claim))
                .thenAnswer(
                        call -> {
                            Thread.currentThread().interrupt();
                            throw new AgentCallFailure(AgentCallFailure.Code.MODEL_INTERRUPTED);
                        });
        var persisted = new AtomicBoolean();
        doAnswer(
                        call -> {
                            assertThat(Thread.currentThread().isInterrupted()).isFalse();
                            assertThat(((AgentCallFailure) call.getArgument(1)).diagnosticCode())
                                    .isEqualTo("MODEL_INTERRUPTED");
                            persisted.set(true);
                            return null;
                        })
                .when(store)
                .failModel(any(), any());
        try {
            new StageExecutor(store, List.of(handler)).execute(claim, control);
            assertThat(persisted).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(handler).execute(claim);
            verify(store, never()).fail(any(), any());
        } finally {
            Thread.interrupted();
        }
    }

    /** 执行期间收到取消，随后模型报错不能触发重试或写入暂停/失败状态。 */
    @Test
    void cancellationWinsOverLateModelFailure() throws Exception {
        var store = mock(ExecutionEngineStore.class);
        var handler = mock(StageHandler.class);
        var control = mock(StageExecutor.Control.class);
        var claim = claim();
        var cancelled = new AtomicBoolean();
        when(store.renew(claim.fence())).thenReturn(true);
        when(handler.supports(StageType.RESEARCH)).thenReturn(true);
        when(control.enter()).thenReturn(true);
        when(control.cancelled()).thenAnswer(call -> cancelled.get());
        when(handler.execute(claim))
                .thenAnswer(
                        call -> {
                            cancelled.set(true);
                            throw new AgentCallFailure(AgentCallFailure.Code.MODEL_CANCELLED);
                        });
        new StageExecutor(store, List.of(handler)).execute(claim, control);
        verify(handler).execute(claim);
        verify(store, never()).failModel(any(), any());
        verify(store, never()).fail(any(), any());
        new StageExecutor(store, List.of(handler)).execute(claim, control);
        verify(handler).execute(claim);
    }

    /**
     * 只构造执行器读取的领取信息；真实围栏与状态事务由PG测试另行覆盖。
     *
     * @return 无真实用户资料的本地领取
     */
    private ExecutionEngineStore.Claim claim() {
        var fence = mock(ExecutionFence.class);
        when(fence.runId()).thenReturn(UUID.randomUUID());
        when(fence.stepId()).thenReturn(UUID.randomUUID());
        var step = mock(ExecutionStep.class);
        when(step.key()).thenReturn(new ExecutionStep.Key(StageType.RESEARCH, 0, "local"));
        return new ExecutionEngineStore.Claim(fence, step, "{}");
    }
}
