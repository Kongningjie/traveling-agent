package com.lawrence.travel.application.execution;

import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.application.port.ModelRequestSlots;
import com.lawrence.travel.domain.execution.control.CallKind;
import com.lawrence.travel.domain.execution.control.CallRejectedException;
import com.lawrence.travel.domain.execution.control.FreeQuotaEvidence;
import com.lawrence.travel.domain.execution.control.RunLimits;
import com.lawrence.travel.domain.execution.control.TokenUsage;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 发送前检查型号、执行预算和并发，并预占请求额度。
 */
public final class ModelCallAdmission {
    private final boolean enabled;
    private final Map<String, String> models;
    private final RunLimits limits;
    private final BudgetLedger ledger;
    private final ModelRequestSlots slots;
    private final Clock clock;
    private final java.util.Set<BudgetLedger.Target> unreliableTargets = new java.util.HashSet<>();

    /**
     * 绑定执行准入依赖，不自动切换型号。
     *
     * @param enabled 真实外部调用总开关
     * @param models 角色到精确型号的白名单
     * @param evidence 兼容旧调用的历史快照，已不参与准入
     * @param limits 原运行预算限制
     * @param ledger 持久化计量仓储
     * @param slots 实际在途并发槽
     * @param clock 预算计时及记账时钟
     */
    public ModelCallAdmission(
            boolean enabled,
            Map<String, String> models,
            Map<String, FreeQuotaEvidence> evidence,
            RunLimits limits,
            BudgetLedger ledger,
            ModelRequestSlots slots,
            Clock clock) {
        this.enabled = enabled;
        this.models = Map.copyOf(models);
        this.limits = java.util.Objects.requireNonNull(limits);
        this.ledger = java.util.Objects.requireNonNull(ledger);
        this.slots = java.util.Objects.requireNonNull(slots);
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    /**
     * 检查执行预算并持久化预占，之后才允许适配器发送一个实际请求。
     *
     * @param runId 已建立预算的原运行标识
     * @param role 已配置角色，不接受自动回退型号
     * @param region 实际 SDK 接入地域
     * @param api 实际接口类型，写入计量记录
     * @param tokenUpperBound 保守输入加最大输出 Token 上界
     * @return 实际请求许可；必须在请求真正结束后结束计量并释放
     * @throws CallRejectedException 总开关关闭、型号未配置、预算或并发受限
     */
    public synchronized Permit begin(
            UUID runId, String role, String region, String api, long tokenUpperBound) {
        if (!enabled)
            throw new CallRejectedException(CallRejectedException.Reason.EXTERNAL_DISABLED);
        String model = models.get(role);
        if (model == null || model.isBlank())
            throw new CallRejectedException(CallRejectedException.Reason.MODEL_NOT_APPROVED);
        if (tokenUpperBound <= 0
                || region == null
                || region.isBlank()
                || api == null
                || api.isBlank()) throw new IllegalArgumentException("MODEL_REQUEST_INVALID");
        var now = clock.instant();
        var target = new BudgetLedger.Target(model, region, api);
        if (unreliableTargets.contains(target))
            throw new CallRejectedException(CallRejectedException.Reason.LEDGER_UNAVAILABLE);
        var lease = slots.tryAcquire();
        if (lease == null)
            throw new CallRejectedException(CallRejectedException.Reason.MODEL_CONCURRENCY_FULL);
        try {
            // 单实例的短临界区仅串行“检查执行资格 + 预占”，真实网络请求不持此锁。
            var reservation =
                    ledger.reserve(runId, CallKind.MODEL, limits, target, tokenUpperBound, now);
            var effective = ledger.limitsFor(runId, limits);
            return new Permit(
                    reservation,
                    target,
                    lease,
                    (effective == null ? limits : effective).requestTimeout(reservation.budget()));
        } catch (RuntimeException failure) {
            lease.close();
            throw failure;
        }
    }

    /** 一个实际请求的计量句柄；不得在 HTTP 仍在途时用 close 假装请求已经结束。 */
    public final class Permit implements AutoCloseable {
        private final BudgetLedger.Reservation reservation;
        private final BudgetLedger.Target target;
        private final ModelRequestSlots.Lease lease;
        private final Duration timeout;
        private final long startedNanos = System.nanoTime();
        private final AtomicBoolean finished = new AtomicBoolean();

        /**
         * 保存已经提交的预占与并发资源。
         *
         * @param reservation 持久化预占
         * @param target 已核实并被预占的精确请求范围
         * @param lease 在途槽
         * @param timeout 收紧后的请求超时
         */
        private Permit(
                BudgetLedger.Reservation reservation,
                BudgetLedger.Target target,
                ModelRequestSlots.Lease lease,
                Duration timeout) {
            this.reservation = reservation;
            this.target = target;
            this.lease = lease;
            this.timeout = timeout;
        }

        /**
         * 提供适配器必须采用的精确请求范围，不再由工具自行选择其他型号。
         * @return 已核实的型号、地域和接口
         */
        public BudgetLedger.Target target() {
            return target;
        }

        /**
         * 读取必须传递给 SDK／传输层的超时；实际取消接线在 Harness 阶段验证。
         * @return 单请求上限和原运行剩余主动时间的较小值
         */
        public Duration timeout() {
            return timeout;
        }

        /**
         * 在实际HTTP结束后幂等结算一次请求。
         *
         * @param usage 供应商已知 Token；null 表示失败或结束后仍未知
         */
        public void complete(TokenUsage usage) {
            if (!finished.compareAndSet(false, true)) return;
            try {
                // 与准入共享短临界区；UNKNOWN保留未知记录，不退还已发起的调用次数。
                synchronized (ModelCallAdmission.this) {
                    try {
                        ledger.finish(
                                reservation.id(),
                                usage,
                                Duration.ofNanos(System.nanoTime() - startedNanos));
                    } catch (RuntimeException failure) {
                        unreliableTargets.add(target);
                        throw failure;
                    }
                }
            } finally {
                // 计量写失败时保留 RESERVED 作为未知已发起请求，不把它退回未调用。
                lease.close();
            }
        }

        /** 实际请求已结束但没有完整用量时，以未知结算；重复结束不重复释放。 */
        @Override
        public void close() {
            complete(null);
        }
    }
}
