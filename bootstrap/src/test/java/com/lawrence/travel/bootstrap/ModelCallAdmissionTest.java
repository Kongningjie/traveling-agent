package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.execution.ModelCallAdmission;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.domain.execution.control.CallKind;
import com.lawrence.travel.domain.execution.control.CallRejectedException;
import com.lawrence.travel.domain.execution.control.FreeQuotaEvidence;
import com.lawrence.travel.domain.execution.control.RunBudget;
import com.lawrence.travel.domain.execution.control.RunLimits;
import com.lawrence.travel.domain.execution.control.TokenUsage;
import com.lawrence.travel.infrastructure.execution.SemaphoreModelRequestSlots;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 通过替身仓储和真实并发槽验证准入编排，不构建 SDK，不访问供应商。 */
class ModelCallAdmissionTest {
    private final Instant now = Instant.parse("2026-10-15T10:00:00Z");
    private final RunLimits limits = new RunLimits(30, 60, 2, Duration.ofSeconds(180), Duration.ofMinutes(15));
    private final BudgetLedger ledger = mock(BudgetLedger.class);
    private final SemaphoreModelRequestSlots slots = new SemaphoreModelRequestSlots(4);
    private final UUID runId = UUID.randomUUID();
    private final UUID callId = UUID.randomUUID();

    /** 验证默认关闭在任何持久化、计量或真实请求资源操作前阻断。 */
    @Test
    void disabledGateNeverTouchesLedger() {
        assertThatThrownBy(() -> gate(false).begin(runId, "planner", "cn-beijing", "REALTIME", 10))
                .hasMessage("EXTERNAL_DISABLED");
        verifyNoInteractions(ledger);
    }

    /** 验证未知角色不能借用有额度的规划模型静默回退。 */
    @Test
    void unknownRoleNeverFallsBackToAnotherModel() {
        assertThatThrownBy(() -> gate(true).begin(runId, "reviewer", "cn-beijing", "REALTIME", 10))
                .hasMessage("MODEL_NOT_APPROVED");
        verifyNoInteractions(ledger);
    }

    /** 验证未核实接入范围在申请并发槽和计数前拒绝。 */
    @Test
    void wrongApiDoesNotReserveCalls() {
        assertThatThrownBy(() -> gate(true).begin(runId, "planner", "cn-beijing", "BATCH", 10))
                .hasMessage("QUOTA_UNVERIFIED");
        verifyNoInteractions(ledger);
    }

    /** 验证即使全局开关打开，缺少额度快照的已配置型号也不能放行。 */
    @Test
    void enabledConfigurationWithoutQuotaEvidenceStillFailsClosed() {
        var admission = new ModelCallAdmission(true, Map.of("planner", "test-model"), Map.of(),
                limits, ledger, slots, Clock.fixed(now, ZoneOffset.UTC));
        assertThatThrownBy(() -> admission.begin(runId, "planner", "cn-beijing", "REALTIME", 10))
                .hasMessage("QUOTA_UNVERIFIED");
        verifyNoInteractions(ledger);
    }

    /** 验证本项目未决用量也会扣减快照余额，不只检查原始控制台数字。 */
    @Test
    void accountsForPreviouslyReservedTokens() {
        when(ledger.tokensSince(any(), any())).thenReturn(95L);
        assertThatThrownBy(() -> gate(true).begin(runId, "planner", "cn-beijing", "REALTIME", 10))
                .hasMessage("QUOTA_INSUFFICIENT");
    }

    /** 验证未知或超出 Token 上界的结果会暂停后续调用，不能继续信任旧快照。 */
    @Test
    void uncertainUsageRequiresQuotaReverification() {
        when(ledger.hasUncertainUsage(any(), any(), any())).thenReturn(true);
        assertThatThrownBy(() -> gate(true).begin(runId, "planner", "cn-beijing", "REALTIME", 10))
                .hasMessage("QUOTA_UNVERIFIED");
    }

    /** 验证在途上限、父请求结束后子请求可接入、结束回调幂等且不凭空扩容。 */
    @Test
    void holdsOnlyActualRequestsAndReleasesExactlyOnce() {
        reserveSuccessfully();
        var admission = gate(true);
        var permits = new java.util.ArrayList<ModelCallAdmission.Permit>();
        for (int i = 0; i < 4; i++) permits.add(admission.begin(runId, "planner", "cn-beijing", "REALTIME", 10));
        assertThatThrownBy(() -> admission.begin(runId, "planner", "cn-beijing", "REALTIME", 10))
                .hasMessage("MODEL_CONCURRENCY_FULL");
        var parent = permits.getFirst();
        assertThat(parent.target()).isEqualTo(new BudgetLedger.Target("test-model", "cn-beijing", "REALTIME"));
        assertThat(parent.timeout()).isEqualTo(Duration.ofSeconds(180));
        parent.complete(new TokenUsage(3, 2));
        parent.close();
        var child = admission.begin(runId, "planner", "cn-beijing", "REALTIME", 10);
        assertThatThrownBy(() -> admission.begin(runId, "planner", "cn-beijing", "REALTIME", 10))
                .hasMessage("MODEL_CONCURRENCY_FULL");
        child.close();
        permits.forEach(ModelCallAdmission.Permit::close);
        verify(ledger, times(1)).finish(eq(callId), eq(new TokenUsage(3, 2)), any());
    }

    /** 验证未获得供应商完整用量时登记 UNKNOWN，不能伪记为零 Token。 */
    @Test
    void unknownCompletionUsesMissingUsageAndIsIdempotent() {
        reserveSuccessfully();
        var permit = gate(true).begin(runId, "planner", "cn-beijing", "REALTIME", 10);
        permit.close();
        permit.close();
        verify(ledger, times(1)).finish(eq(callId), isNull(), any());
    }

    /** 验证预占失败和计量回调失败均不泄漏并发槽，也不重置失败计数。 */
    @Test
    void failuresReleaseSlotsWithoutRefundingLedger() {
        when(ledger.reserve(any(), any(), any(), any(), anyLong(), any()))
                .thenThrow(new CallRejectedException(CallRejectedException.Reason.CALL_BUDGET_EXHAUSTED));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> gate(true).begin(runId, "planner", "cn-beijing", "REALTIME", 10))
                    .hasMessage("CALL_BUDGET_EXHAUSTED");
        }
        reserveSuccessfully();
        doThrow(new IllegalStateException("Meter unavailable")).when(ledger).finish(any(), any(), any());
        var admission = gate(true);
        var permit = admission.begin(runId, "planner", "cn-beijing", "REALTIME", 10);
        assertThatThrownBy(permit::close).hasMessage("Meter unavailable");
        assertThatThrownBy(() -> admission.begin(runId, "planner", "cn-beijing", "REALTIME", 10))
                .hasMessage("QUOTA_UNVERIFIED");
        var leases = new java.util.ArrayList<com.lawrence.travel.application.port.ModelRequestSlots.Lease>();
        for (int i = 0; i < 4; i++) leases.add(slots.tryAcquire());
        assertThat(leases).doesNotContainNull();
        leases.forEach(com.lawrence.travel.application.port.ModelRequestSlots.Lease::close);
    }

    /** 配置只存在于测试中的持久化成功返回，不是真实请求或实际计量数据。 */
    private void reserveSuccessfully() {
        doReturn(new BudgetLedger.Reservation(callId, new RunBudget(1, 0, 0, Duration.ZERO)))
                .when(ledger).reserve(any(), eq(CallKind.MODEL), any(), any(), anyLong(), any());
    }

    /**
     * 创建固定时间的离线准入服务。
     * @param enabled 是否模拟开启真实调用总开关
     * @return 只有 planner 测试角色的门禁
     */
    private ModelCallAdmission gate(boolean enabled) {
        var evidence = new FreeQuotaEvidence("test-model", "cn-beijing", "REALTIME", 100,
                now.minusSeconds(1), now.plusSeconds(60), now.plusSeconds(120), true);
        return new ModelCallAdmission(enabled, Map.of("planner", "test-model"), Map.of("test-model", evidence),
                limits, ledger, slots, Clock.fixed(now, ZoneOffset.UTC));
    }
}
