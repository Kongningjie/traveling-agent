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

/** PostgreSQL 计量适配器；每次预占是短事务，不在模型或工具请求期间持锁。 */
public final class PostgresBudgetLedger implements BudgetLedger {
    private final BudgetMapper mapper;
    private final TransactionTemplate transaction;

    /**
     * 绑定 Mapper 与事务管理器，不另建执行调度或内存计量权威来源。
     * @param mapper 显式计量 SQL
     * @param manager 同一 PostgreSQL 数据源事务管理器
     */
    public PostgresBudgetLedger(BudgetMapper mapper, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
    }

    /** {@inheritDoc} 重复建账使用 ON CONFLICT，不重置已有运行。 */
    @Override
    public void create(UUID runId) {
        mapper.create(Objects.requireNonNull(runId));
    }

    /** {@inheritDoc} 先锁预算行，再更新次数并插入计量记录；任一失败整体回滚。 */
    @Override
    public Reservation reserve(UUID runId, CallKind kind, RunLimits limits, Target target,
                                long tokenUpperBound, Instant now) {
        // 不加入外层业务事务，避免返回许可时预占尚未提交，随后外层回滚却已产生供应商消耗。
        // 也不通过 REQUIRES_NEW 隐式挂起持锁事务，防止同一预算行自锁等待。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Call reservation requires no ambient business transaction");
        }
        if (tokenUpperBound < 0) throw new IllegalArgumentException("Negative token bound");
        return Objects.requireNonNull(transaction.execute(status -> {
            RunBudget next = budget(mapper.lock(runId)).reserve(kind, limits);
            UUID id = UUID.randomUUID();
            mapper.update(runId, next, next.activeTime().toMillis());
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
            throw new IllegalArgumentException("Active time must be nonnegative whole milliseconds");
        }
        transaction.executeWithoutResult(status -> {
            RunBudget current = budget(mapper.lock(runId));
            long total = Math.addExact(current.activeTime().toMillis(), elapsed.toMillis());
            mapper.update(runId, current, total);
        });
    }

    /** {@inheritDoc} 首次结束事件幂等生效，失败不退还任何请求计数。 */
    @Override
    public void finish(UUID reservationId, TokenUsage usage, Duration latency) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Call completion requires no ambient business transaction");
        }
        if (latency.isNegative()) throw new IllegalArgumentException("Negative latency");
        mapper.finish(reservationId, usage == null ? "UNKNOWN" : "COMPLETED",
                usage == null ? null : usage.inputTokens(), usage == null ? null : usage.outputTokens(),
                latency.toMillis());
    }

    /** {@inheritDoc} 不查询其他账号或供应商实时余额。 */
    @Override
    public long tokensSince(Target target, Instant since) {
        return mapper.tokensSince(target, since);
    }

    /** {@inheritDoc} 未知或超出上界后暂停该精确范围，不用原始快照继续放行。 */
    @Override
    public boolean hasUncertainUsage(Target target, Instant since, Instant unresolvedBefore) {
        return mapper.hasUncertainUsage(target, since, unresolvedBefore);
    }

    /**
     * 将完整数据库行转为不可变规则快照。
     * @param row 查询或锁定的行，不能缺失
     * @return 领域预算快照
     * @throws IllegalStateException 运行未建账或恢复引用无效
     */
    private RunBudget budget(BudgetMapper.BudgetRow row) {
        if (row == null) throw new IllegalStateException("Execution budget does not exist");
        return new RunBudget(row.modelCalls(), row.toolCalls(), row.repairRounds(),
                Duration.ofMillis(row.activeMillis()));
    }
}
