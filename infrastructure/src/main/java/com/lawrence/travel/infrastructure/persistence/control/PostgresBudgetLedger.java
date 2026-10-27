package com.lawrence.travel.infrastructure.persistence.control;

import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.domain.execution.control.CallKind;
import com.lawrence.travel.domain.execution.control.RunBudget;
import com.lawrence.travel.domain.execution.control.RunLimits;
import com.lawrence.travel.domain.execution.control.TokenUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 模型和外部工具的PG预算/用量账本，串接准入许可与call_meter、配额及诊断记录。
 *
 * <p>ModelCallAdmission.begin经reserve短事务锁预算、校验次数/主动时间/配额并保存预占；
 * 实际网络调用发生在事务外。Permit结束经finish写可信Token或UNKNOWN，关联原请求轨迹，
 * 请求失败不退次数，重启遗留预占也不自动当成零。
 * 不允许在持有业务事务时reserve/finish，避免外层回滚后供应商已消耗却账本没有记录。
 */
public final class PostgresBudgetLedger implements BudgetLedger {
    private final BudgetMapper mapper;
    private final TransactionTemplate transaction;
    private final com.lawrence.travel.infrastructure.execution.jobs.QuotaMapper quotas;
    private final com.lawrence.travel.infrastructure.execution.jobs.DiagnosticMapper diagnostics;
    private final com.lawrence.travel.application.execution.JobPolicy policy;

    /**
     * @param mapper 计量SQL
     * @param manager 同源事务
     * @param quotas 日配额
     * @param diagnostics 最小链路
     * @param policy 输入上限；生产装配必须完整提供
     */
    public PostgresBudgetLedger(
            BudgetMapper mapper,
            PlatformTransactionManager manager,
            com.lawrence.travel.infrastructure.execution.jobs.QuotaMapper quotas,
            com.lawrence.travel.infrastructure.execution.jobs.DiagnosticMapper diagnostics,
            com.lawrence.travel.application.execution.JobPolicy policy) {
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
        this.quotas = quotas;
        this.diagnostics = diagnostics;
        this.policy = policy;
    }

    /** {@inheritDoc} 输入budget由持久归属识别，调用方不能传入旅行上限绕过。 */
    @Override
    public RunLimits limitsFor(UUID runId, RunLimits fallback) {
        var saved = quotas == null ? null : quotas.limits(runId);
        return saved == null
                ? fallback
                : new RunLimits(
                        saved.models(),
                        saved.tools(),
                        0,
                        Duration.ofMillis(saved.timeoutMillis()),
                        Duration.ofMillis(saved.activeMillis()));
    }

    /**
     * 绑定 Mapper 与事务管理器，不另建执行调度或内存计量权威来源。
     * @param mapper 显式计量 SQL
     * @param manager 同一 PostgreSQL 数据源事务管理器
     */
    public PostgresBudgetLedger(BudgetMapper mapper, PlatformTransactionManager manager) {
        this(mapper, manager, null, null, null);
    }

    /** {@inheritDoc} 重复建账使用 ON CONFLICT，不重置已有运行。 */
    @Override
    public void create(UUID runId) {
        mapper.create(Objects.requireNonNull(runId));
    }

    /**
     * {@inheritDoc}
     *
     * <p>受控模型/工具发送前调用，独立短事务中核对原预算、有效限制和归属配额，更新调用计数、
     * 插入唯一call_meter预占，并绑定诊断身份。预占提交后才允许网络发送，不能靠业务大事务回滚退还。
     * 并发槽由上层实际请求持有，本账本不负责取消网络或运行模型。
     */
    @Override
    public Reservation reserve(
            UUID runId,
            CallKind kind,
            RunLimits limits,
            Target target,
            long tokenUpperBound,
            Instant now) {
        // 不加入外层业务事务，避免返回许可时预占尚未提交，随后外层回滚却已产生供应商消耗。
        // 也不通过 REQUIRES_NEW 隐式挂起持锁事务，防止同一预算行自锁等待。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Call reservation requires no ambient business transaction");
        }
        if (tokenUpperBound < 0) throw new IllegalArgumentException("Negative token bound");
        return Objects.requireNonNull(
                transaction.execute(
                        status -> {
                            RunBudget current = budget(mapper.lock(runId));
                            long pending = quotas == null ? 0 : quotas.activeMillis(runId);
                            RunBudget observed =
                                    new RunBudget(
                                            current.modelCalls(),
                                            current.toolCalls(),
                                            current.repairRounds(),
                                            current.activeTime().plusMillis(pending));
                            RunBudget next = observed.reserve(kind, limitsFor(runId, limits));
                            UUID id = UUID.randomUUID();
                            if (quotas != null && kind != CallKind.REPAIR) {
                                var owner = quotas.operation(runId);
                                if (owner == null) owner = quotas.run(runId);
                                // 没有业务归属的旧离线探针维持原契约；业务输入即使已取消也不能降级为匿名预算。
                                if (owner != null) {
                                    if (com.lawrence.travel.application.execution
                                                    .ExecutionCorrelation.traceParent()
                                            != null)
                                        owner =
                                                owner.withParent(
                                                        com.lawrence.travel.application.execution
                                                                .ExecutionCorrelation
                                                                .traceParent());
                                    if (!owner.active())
                                        throw new IllegalStateException("STALE_JOB");
                                    var day =
                                            now.atZone(java.time.ZoneId.of("Asia/Shanghai"))
                                                    .toLocalDate();
                                    quotas.create(owner.userId(), day, kind.name());
                                    long cap =
                                            kind == CallKind.MODEL
                                                    ? policy.dailyModels()
                                                    : policy.dailyTools();
                                    if (quotas.reserve(owner.userId(), day, kind.name(), cap) != 1)
                                        throw new com.lawrence.travel.domain.execution.control
                                                .CallRejectedException(
                                                com.lawrence.travel.domain.execution.control
                                                        .CallRejectedException.Reason
                                                        .DAILY_QUOTA_EXHAUSTED);
                                    quotas.own(id, owner, runId, day, kind.name());
                                    diagnostics.span(
                                            id,
                                            owner.traceId(),
                                            owner.parentSpanId(),
                                            owner.userId(),
                                            owner.requestId(),
                                            owner.chatId(),
                                            owner.operationId(),
                                            owner.runId(),
                                            owner.jobId(),
                                            null,
                                            kind.name());
                                }
                            }
                            // 未结算区间仅用于本次准入和超时，不重复累加；唯一作业结束器负责正式结算。
                            mapper.update(runId, next, current.activeTime().toMillis());
                            mapper.insert(id, runId, kind.name(), target, tokenUpperBound, now);
                            return new Reservation(id, next);
                        }));
    }

    /** {@inheritDoc} 未建账明确失败，不从缺失行推断为零用量。 */
    @Override
    public RunBudget read(UUID runId) {
        return budget(mapper.read(runId));
    }

    /** {@inheritDoc} 上层唯一活跃区间管理者负责避免并行分支重复累加。 */
    @Override
    public void addActiveTime(UUID runId, Duration elapsed) {
        if (elapsed.isNegative() || !elapsed.equals(Duration.ofMillis(elapsed.toMillis()))) {
            throw new IllegalArgumentException(
                    "Active time must be nonnegative whole milliseconds");
        }
        transaction.executeWithoutResult(
                status -> {
                    RunBudget current = budget(mapper.lock(runId));
                    long total = Math.addExact(current.activeTime().toMillis(), elapsed.toMillis());
                    mapper.update(runId, current, total);
                });
    }

    /**
     * {@inheritDoc}
     *
     * <p>请求真正结束后登记COMPLETED或UNKNOWN及延迟；SQL只允许首次结束改变预占状态。
     * 可信Token结算与配额/诊断更新使用同一短事务，未知用量保留保守边界；不能重复结束冲掉历史失败或降低消耗。
     */
    @Override
    public void finish(UUID reservationId, TokenUsage usage, Duration latency) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Call completion requires no ambient business transaction");
        }
        if (latency.isNegative()) throw new IllegalArgumentException("Negative latency");
        transaction.executeWithoutResult(
                status -> {
                    int changed =
                            mapper.finish(
                                    reservationId,
                                    usage == null ? "UNKNOWN" : "COMPLETED",
                                    usage == null ? null : usage.inputTokens(),
                                    usage == null ? null : usage.outputTokens(),
                                    latency.toMillis());
                    if (changed == 1 && quotas != null) {
                        quotas.finish(reservationId, usage != null);
                        diagnostics.end(
                                reservationId,
                                usage == null ? "UNKNOWN" : "COMPLETED",
                                usage == null ? "USAGE_UNKNOWN" : "");
                    }
                });
    }

    /** {@inheritDoc} 不查询其他账号或供应商实时余额。 */
    @Override
    public long tokensSince(Target target, Instant since) {
        return mapper.tokensSince(target, since);
    }

    /** {@inheritDoc} 保留不确定记录供诊断，不自动清除或刷新快照。 */
    @Override
    public boolean hasUncertainUsage(Target target, Instant since, Instant unresolvedBefore) {
        return mapper.hasUncertainUsage(target, since, unresolvedBefore);
    }

    /** {@inheritDoc} 仅已知超上界事实参与准入阻断，未知记录仍保守计费。 */
    @Override
    public boolean hasUsageOverrun(Target target, Instant since) {
        return mapper.hasUsageOverrun(target, since);
    }

    /**
     * 将完整数据库行转为不可变规则快照。
     * @param row 查询或锁定的行，不能缺失
     * @return 领域预算快照
     * @throws IllegalStateException 运行未建账或恢复引用无效
     */
    private RunBudget budget(BudgetMapper.BudgetRow row) {
        if (row == null) throw new IllegalStateException("Execution budget does not exist");
        return new RunBudget(
                row.modelCalls(),
                row.toolCalls(),
                row.repairRounds(),
                Duration.ofMillis(row.activeMillis()));
    }
}
