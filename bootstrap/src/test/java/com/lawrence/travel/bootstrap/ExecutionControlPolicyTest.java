package com.lawrence.travel.bootstrap;

import com.lawrence.travel.domain.execution.control.CallKind;
import com.lawrence.travel.domain.execution.control.CallRejectedException;
import com.lawrence.travel.domain.execution.control.FreeQuotaEvidence;
import com.lawrence.travel.domain.execution.control.RunBudget;
import com.lawrence.travel.domain.execution.control.RunLimits;
import com.lawrence.travel.domain.execution.control.TokenUsage;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 离线验证冻结预算边界和精确免费范围，不调用真实模型或读取真实密钥。 */
class ExecutionControlPolicyTest {
    private final RunLimits limits =
            new RunLimits(30, 60, 2, Duration.ofSeconds(180), Duration.ofMinutes(15));
    private final Instant now = Instant.parse("2026-10-15T10:00:00Z");

    /** 验证最后一次调用可完成，只有新增下一次才暂停，所有类别共享原快照。 */
    @Test
    void permitsExactLastCallsButRejectsAdditionalCalls() {
        var last =
                new RunBudget(29, 59, 1, Duration.ZERO)
                        .reserve(CallKind.MODEL, limits)
                        .reserve(CallKind.TOOL, limits)
                        .reserve(CallKind.REPAIR, limits);
        assertThat(last).isEqualTo(new RunBudget(30, 60, 2, Duration.ZERO));
        for (CallKind kind : CallKind.values()) {
            assertThatThrownBy(() -> last.reserve(kind, limits))
                    .isInstanceOf(CallRejectedException.class)
                    .hasMessage("CALL_BUDGET_EXHAUSTED");
        }
    }

    /** 验证剩余主动时间缩短请求超时，刚好达到总时间上限时拒绝新增工作。 */
    @Test
    void limitsRequestTimeoutToRemainingActiveTime() {
        assertThat(limits.requestTimeout(new RunBudget(0, 0, 0, Duration.ZERO)))
                .isEqualTo(Duration.ofSeconds(180));
        assertThat(limits.requestTimeout(new RunBudget(0, 0, 0, Duration.ofSeconds(899))))
                .isEqualTo(Duration.ofSeconds(1));
        assertThatThrownBy(
                        () ->
                                new RunBudget(0, 0, 0, Duration.ofMinutes(15))
                                        .reserve(CallKind.MODEL, limits))
                .hasMessage("ACTIVE_TIME_EXHAUSTED");
    }

    /** 验证未核实的保护、地域、接口和精确型号不能互相替代。 */
    @Test
    void rejectsScopeMismatchAndMissingStopProtection() {
        var valid = evidence(true);
        assertThatThrownBy(
                        () ->
                                valid.requireUsable(
                                        "another-model", "cn-beijing", "REALTIME", 1, now))
                .hasMessage("QUOTA_UNVERIFIED");
        assertThatThrownBy(() -> valid.requireUsable("model", "other-region", "REALTIME", 1, now))
                .hasMessage("QUOTA_UNVERIFIED");
        assertThatThrownBy(() -> valid.requireUsable("model", "cn-beijing", "BATCH", 1, now))
                .hasMessage("QUOTA_UNVERIFIED");
        assertThatThrownBy(
                        () ->
                                evidence(false)
                                        .requireUsable("model", "cn-beijing", "REALTIME", 1, now))
                .hasMessage("QUOTA_UNVERIFIED");
    }

    /** 验证未来核实时间、供应商额度到期及不足仍拒绝，本地历史截止不代表真实失效。 */
    @Test
    void rejectsStaleFutureExpiredOrInsufficientEvidence() {
        var valid = evidence(true);
        assertThatThrownBy(() -> valid.requireUsable("model", "cn-beijing", "REALTIME", 101, now))
                .hasMessage("QUOTA_INSUFFICIENT");
        assertThatThrownBy(
                        () ->
                                valid.requireUsable(
                                        "model", "cn-beijing", "REALTIME", 1, now.minusSeconds(2)))
                .hasMessage("QUOTA_EXPIRED");
        valid.requireUsable("model", "cn-beijing", "REALTIME", 1, now.plusSeconds(10));
        var expired =
                new FreeQuotaEvidence(
                        "model",
                        "cn-beijing",
                        "REALTIME",
                        100,
                        now.minusSeconds(1),
                        now.plusSeconds(10),
                        now,
                        true);
        assertThatThrownBy(() -> expired.requireUsable("model", "cn-beijing", "REALTIME", 1, now))
                .hasMessage("QUOTA_EXPIRED");
        valid.requireUsable("model", "cn-beijing", "REALTIME", 100, now);
    }

    /** 跨越旧快照截止后仍按供应商实际截止判定，不刷新核实时间，截止本身为排他边界。 */
    @Test
    void ignoresLocalSnapshotDeadlineButPreservesActualExpiry() {
        var stored =
                new FreeQuotaEvidence(
                        "model",
                        "cn-beijing",
                        "REALTIME",
                        100,
                        now.minusSeconds(86400),
                        now.minusSeconds(3600),
                        now.plusSeconds(60),
                        true);
        stored.requireUsable("model", "cn-beijing", "REALTIME", 100, now);
        stored.requireUsable("model", "cn-beijing", "REALTIME", 1, now.plusSeconds(59));
        assertThat(stored.verifiedAt()).isEqualTo(now.minusSeconds(86400));
        assertThatThrownBy(
                        () ->
                                stored.requireUsable(
                                        "model", "cn-beijing", "REALTIME", 1, now.plusSeconds(60)))
                .hasMessage("QUOTA_EXPIRED");
    }

    /** 验证配置、恢复快照及供应商用量不能以负数或溢出绕过限制。 */
    @Test
    void rejectsInvalidLimitsSnapshotsAndTokenUsage() {
        assertThatThrownBy(() -> new RunBudget(-1, 0, 0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new RunLimits(0, 60, 2, Duration.ofSeconds(1), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenUsage(-1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunBudget(0, 0, 0, Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenUsage(Long.MAX_VALUE, 1))
                .isInstanceOf(ArithmeticException.class);
    }

    /**
     * 创建仅供本测试使用的短期额度，不表示真实供应商信息。
     * @param stopConfirmed 是否模拟已核实用完即停
     * @return 100 Token 的固定测试快照
     */
    private FreeQuotaEvidence evidence(boolean stopConfirmed) {
        return new FreeQuotaEvidence(
                "model",
                "cn-beijing",
                "REALTIME",
                100,
                now.minusSeconds(1),
                now.plusSeconds(10),
                now.plusSeconds(20),
                stopConfirmed);
    }
}
