package com.lawrence.travel.infrastructure.execution.jobs;

import com.lawrence.travel.application.execution.ExecutionCorrelation;
import com.lawrence.travel.application.execution.JobPolicy;
import com.lawrence.travel.application.execution.Jobs;
import com.lawrence.travel.application.port.JobStore;
import com.lawrence.travel.domain.execution.BackgroundJob;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 保存后台作业，处理领取、续租、暂停和完成。
 */
public final class PostgresJobStore implements JobStore {
    /** {@inheritDoc} 不领取物理容量、不消耗模型额度；不能将运行中的任意模型结果伪造成直接完成。 */
    @Override
    public void completeCommand(Jobs.Scope scope, UUID id) {
        transaction(
                () -> {
                    lock(scope);
                    var row = required(scope, id);
                    if (row.state() == BackgroundJob.State.COMPLETED) return null;
                    if (row.kind() != BackgroundJob.Kind.CHAT_INPUT
                            || row.state() != BackgroundJob.State.QUEUED)
                        throw new IllegalStateException("INVALID_COMMAND_STATE");
                    move(scope, row, BackgroundJob.State.COMPLETED, "", false, null);
                    finishOperation(row.operationId());
                    return null;
                });
    }

    private final JobMapper mapper;
    private final DiagnosticMapper diagnostics;
    private final QuotaMapper quotas;
    private final JobPolicy policy;
    private final int maximumWorks;
    private final TransactionTemplate tx;

    /**
     * @param mapper 作业SQL
     * @param diagnostics 脱敏记录
     * @param quotas 日配额
     * @param policy 上限
     * @param manager 同数据源事务
     */
    public PostgresJobStore(
            JobMapper mapper,
            DiagnosticMapper diagnostics,
            QuotaMapper quotas,
            JobPolicy policy,
            PlatformTransactionManager manager) {
        this(mapper, diagnostics, quotas, policy, manager, 2);
    }

    /**
     * @param mapper 作业SQL
     * @param diagnostics 诊断
     * @param quotas 日配额
     * @param policy 输入限额
     * @param manager PG事务
     * @param maximumWorks 沿用配置的全局模型工作上限
     */
    public PostgresJobStore(
            JobMapper mapper,
            DiagnosticMapper diagnostics,
            QuotaMapper quotas,
            JobPolicy policy,
            PlatformTransactionManager manager,
            int maximumWorks) {
        if (maximumWorks < 1) throw new IllegalArgumentException("INVALID_GLOBAL_CAPACITY");
        this.maximumWorks = maximumWorks;
        this.mapper = mapper;
        this.diagnostics = diagnostics;
        this.quotas = quotas;
        this.policy = policy;
        this.tx = new TransactionTemplate(manager);
    }

    /** {@inheritDoc} 同身份异参数拒绝；上下文、预算与作业一起提交。 */
    @Override
    public Jobs.View submit(Jobs.Submit c) {
        return transaction(
                () -> {
                    if (c == null
                            || c.operationId() == null
                            || c.kind() == null
                            || c.branch() == null
                            || !c.branch().matches("[A-Za-z0-9_-]{1,64}")
                            || c.payload() == null
                            || c.payload().length() > 65536)
                        throw new IllegalArgumentException("INVALID_JOB");
                    long version = lock(c.scope());
                    if (c.priorAttempts() < 0
                            || (c.legacyBudget() == null && c.priorAttempts() != 0)
                            || (c.legacyBudget() != null
                                    && !c.legacyBudget()
                                            .toString()
                                            .equals(mapper.legacyBudget(c.scope()))))
                        throw new IllegalArgumentException("INVALID_LEGACY_BUDGET");
                    if (c.dependency() != null
                            && mapper.dependency(c.scope(), c.dependency()) == null)
                        throw new IllegalArgumentException("INVALID_DEPENDENCY");
                    var ids = ExecutionCorrelation.current();
                    mapper.budget(c.operationId(), c.legacyBudget());
                    int created =
                            mapper.operation(
                                    c.scope(),
                                    c.operationId(),
                                    c.legacyBudget(),
                                    ids.requestId(),
                                    ids.traceId(),
                                    policy.inputLimits(),
                                    policy.inputLimits().modelTimeout().toMillis(),
                                    policy.inputLimits().activeTime().toMillis());
                    if (!mapper.owns(c.scope(), c.operationId()))
                        throw new IllegalStateException("JOB_NOT_FOUND");
                    if (created == 1)
                        diagnostics.span(
                                c.operationId(),
                                ids.traceId(),
                                null,
                                c.scope().userId(),
                                ids.requestId(),
                                c.scope().chatId(),
                                c.operationId(),
                                null,
                                null,
                                null,
                                "INPUT");
                    UUID id =
                            UUID.nameUUIDFromBytes(
                                    (c.scope().userId()
                                                    + "/"
                                                    + c.operationId()
                                                    + "/"
                                                    + c.kind()
                                                    + "/"
                                                    + c.branch())
                                            .getBytes(StandardCharsets.UTF_8));
                    String hash = hash(c.payload());
                    mapper.insert(c, id, hash, version);
                    if (!mapper.same(c.scope(), id, hash, c.dependency()))
                        throw new IllegalArgumentException("JOB_IDEMPOTENCY_CONFLICT");
                    return view(required(c.scope(), id));
                });
    }

    /** {@inheritDoc} 所有读取带归属，缺失不泄露其他用户对象。 */
    @Override
    public Jobs.View read(Jobs.Scope scope, UUID id) {
        return transaction(
                () -> {
                    lock(scope);
                    return view(required(scope, id));
                });
    }

    /** {@inheritDoc} 每个发现提示分别事务，避免跨用户累计锁形成反向锁序。 */
    @Override
    public Optional<Jobs.Claim> claim(Set<BackgroundJob.Kind> kinds) {
        return claim(kinds, Set.of());
    }

    /** {@inheritDoc} 全局轮转提示仍逐个用户短事务，排除在途取消者。 */
    @Override
    public Optional<Jobs.Claim> claim(Set<BackgroundJob.Kind> kinds, Set<UUID> excluded) {
        if (kinds.isEmpty()) return Optional.empty();
        for (var hint : mapper.hints(kinds)) {
            if (excluded.contains(hint.userId())) continue;
            var found = claim(new Jobs.Scope(hint.userId(), hint.chatId()), hint.id());
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    /** {@inheritDoc} 过期尝试先结算；等待用户不占租约；缺少处理器的类型不会被发现。 */
    @Override
    public Optional<Jobs.Claim> claim(Jobs.Scope scope, UUID id) {
        return transaction(
                () -> {
                    long version = lock(scope);
                    var row = required(scope, id);
                    if (BackgroundJob.terminal(row.state())
                            || row.state() == BackgroundJob.State.PAUSED
                            || row.state() == BackgroundJob.State.WAITING_USER)
                        return Optional.empty();
                    if (row.version() != version) {
                        move(
                                scope,
                                row,
                                BackgroundJob.State.SUPERSEDED,
                                "SOURCE_CHANGED",
                                false,
                                null);
                        return Optional.empty();
                    }
                    if (row.state() == BackgroundJob.State.RUNNING
                            && row.leaseUntil().isAfter(mapper.now())) return Optional.empty();
                    String dependency = mapper.dependencyId(scope, id);
                    if (dependency != null) {
                        var state =
                                BackgroundJob.State.valueOf(
                                        mapper.dependency(scope, UUID.fromString(dependency)));
                        if (state != BackgroundJob.State.COMPLETED) {
                            if (BackgroundJob.terminal(state))
                                move(
                                        scope,
                                        row,
                                        BackgroundJob.State.PAUSED,
                                        "DEPENDENCY_FAILED",
                                        false,
                                        null);
                            return Optional.empty();
                        }
                    }
                    if (row.state() == BackgroundJob.State.RUNNING)
                        move(scope, row, BackgroundJob.State.QUEUED, "LEASE_EXPIRED", false, null);
                    row = required(scope, id);
                    if (row.attempts() >= 3) {
                        move(
                                scope,
                                row,
                                BackgroundJob.State.PAUSED,
                                "ATTEMPTS_EXHAUSTED",
                                false,
                                null);
                        return Optional.empty();
                    }
                    if (mapper.earlier(scope, id)) return Optional.empty();
                    if (mapper.operationActive(row.operationId()) > 0) return Optional.empty();
                    boolean parser = row.kind() == BackgroundJob.Kind.DOCUMENT_PARSE;
                    var quotaDay = mapper.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
                    if (mapper.activeCount(scope.userId(), parser) > 0) return Optional.empty();
                    if (!parser
                            && (!mapper.modelDispatch()
                                    || mapper.travelWork(scope.userId()) > 0
                                    || mapper.modelWork() >= maximumWorks)) return Optional.empty();
                    if (parser) {
                        mapper.dispatch();
                        if (mapper.parses() > 0) return Optional.empty();
                        quotas.create(scope.userId(), quotaDay, "PARSE");
                        if (quotas.reserve(scope.userId(), quotaDay, "PARSE", policy.dailyParses())
                                != 1) {
                            move(
                                    scope,
                                    row,
                                    BackgroundJob.State.PAUSED,
                                    "DAILY_QUOTA_EXHAUSTED",
                                    false,
                                    null);
                            return Optional.empty();
                        }
                    }
                    mapper.lease(scope, id);
                    row = required(scope, id);
                    UUID span = UUID.randomUUID();
                    mapper.attempt(row, span);
                    diagnostics.span(
                            span,
                            row.traceId(),
                            row.operationId(),
                            scope.userId(),
                            row.requestId(),
                            scope.chatId(),
                            row.operationId(),
                            null,
                            id,
                            row.token(),
                            row.kind().name());
                    if (parser) {
                        var owner = quotas.operation(row.budgetId());
                        quotas.own(span, owner, row.budgetId(), quotaDay, "PARSE");
                    }
                    return Optional.of(new Jobs.Claim(fence(scope, row), view(row), row.payload()));
                });
    }

    /** {@inheritDoc} 版本和租约必须同时有效。 */
    @Override
    public boolean live(Jobs.Fence fence) {
        return mapper.live(fence);
    }

    /** {@inheritDoc} 原资格已过期时不复活。 */
    @Override
    public boolean renew(Jobs.Fence fence) {
        return transaction(
                () -> {
                    lock(fence.scope());
                    return mapper.live(fence) && mapper.renew(fence) == 1;
                });
    }

    /** {@inheritDoc} 产物、后继与终态同事务；后继非法会整体回滚。 */
    @Override
    public void finish(Jobs.Claim claim, Jobs.Output output) {
        transaction(
                () -> {
                    lock(claim.fence().scope());
                    if (!mapper.live(claim.fence())) throw new IllegalStateException("STALE_JOB");
                    if (!Set.of(
                                    BackgroundJob.State.COMPLETED,
                                    BackgroundJob.State.WAITING_USER,
                                    BackgroundJob.State.WAITING_DEPENDENCY,
                                    BackgroundJob.State.PAUSED,
                                    BackgroundJob.State.FAILED)
                            .contains(output.state()))
                        throw new IllegalArgumentException("INVALID_JOB_RESULT");
                    if (output.artifact() != null && output.artifact().length() > 65536)
                        throw new IllegalArgumentException("ARTIFACT_TOO_LARGE");
                    for (var next : output.next())
                        if (!next.scope().equals(claim.fence().scope())
                                || !next.operationId().equals(claim.job().operationId()))
                            throw new IllegalArgumentException("INVALID_SUCCESSOR");
                    var row = required(claim.fence().scope(), claim.job().id());
                    move(
                            claim.fence().scope(),
                            row,
                            output.state(),
                            code(output.reason()),
                            false,
                            output.artifact(),
                            true);
                    output.next().forEach(this::submit);
                    finishOperation(row.operationId());
                    return null;
                });
    }

    /** {@inheritDoc} 明确继续保持原预算；只有暂停／等待用户允许继续。 */
    @Override
    public void control(Jobs.Scope scope, UUID id, boolean retry) {
        transaction(
                () -> {
                    lock(scope);
                    var row = required(scope, id);
                    var state = retry ? BackgroundJob.State.QUEUED : BackgroundJob.State.CANCELLED;
                    if (!retry && row.state() == BackgroundJob.State.CANCELLED) return null;
                    if (retry && row.state() == BackgroundJob.State.QUEUED) return null;
                    if (retry
                            && row.state() != BackgroundJob.State.PAUSED
                            && row.state() != BackgroundJob.State.WAITING_USER)
                        throw new IllegalStateException("INVALID_JOB_STATE");
                    if (!BackgroundJob.allows(row.state(), state))
                        throw new IllegalStateException("INVALID_JOB_STATE");
                    move(scope, row, state, retry ? "" : "USER_CANCELLED", !retry, null);
                    if (retry) mapper.resetAttempts(scope, id);
                    finishOperation(row.operationId());
                    return null;
                });
    }

    /** {@inheritDoc} 已完成幂等；其他终态不能借确认复活。 */
    @Override
    public void acknowledge(Jobs.Scope scope, UUID id) {
        transaction(
                () -> {
                    lock(scope);
                    var row = required(scope, id);
                    if (row.state() == BackgroundJob.State.COMPLETED) return null;
                    if (row.state() != BackgroundJob.State.WAITING_USER)
                        throw new IllegalStateException("INVALID_JOB_STATE");
                    move(scope, row, BackgroundJob.State.COMPLETED, "", false, null);
                    finishOperation(row.operationId());
                    return null;
                });
    }

    /** {@inheritDoc} 删除／资料变化立即使旧token失效，不等待物理线程退出。 */
    @Override
    public long invalidate(Jobs.Scope scope) {
        return transaction(
                () -> {
                    lock(scope);
                    long version = mapper.invalidate(scope);
                    for (String id : mapper.active(scope)) {
                        var row = required(scope, UUID.fromString(id));
                        move(
                                scope,
                                row,
                                BackgroundJob.State.SUPERSEDED,
                                "SOURCE_CHANGED",
                                true,
                                null);
                        finishOperation(row.operationId());
                    }
                    return version;
                });
    }

    /**
     * @param scope 归属
     * @param row 旧行
     * @param state 新态
     * @param reason 固定码
     * @param full 取消保守结算
     * @param artifact 产物；调用者已持归属锁
     */
    private void move(
            Jobs.Scope scope,
            JobMapper.Row row,
            BackgroundJob.State state,
            String reason,
            boolean full,
            String artifact) {
        move(scope, row, state, reason, full, artifact, false);
    }

    /**
     * @param scope 归属
     * @param row 旧行
     * @param state 目标
     * @param reason 固定码
     * @param full 保守计时
     * @param artifact JSON
     * @param strict 正常完成必须在SQL写入时仍有有效租约
     */
    private void move(
            Jobs.Scope scope,
            JobMapper.Row row,
            BackgroundJob.State state,
            String reason,
            boolean full,
            String artifact,
            boolean strict) {
        if (!BackgroundJob.allows(row.state(), state))
            throw new IllegalStateException("INVALID_JOB_STATE");
        mapper.settle(row, full);
        mapper.endAttempt(row.id(), row.token(), state.name());
        String spanText = mapper.attemptSpan(row.id(), row.token());
        UUID span = spanText == null ? null : UUID.fromString(spanText);
        if (span != null) {
            diagnostics.end(span, state.name(), reason);
            if (row.kind() == BackgroundJob.Kind.DOCUMENT_PARSE)
                quotas.finish(span, state != BackgroundJob.State.QUEUED && !full);
        }
        if (mapper.state(fence(scope, row), state.name(), reason, artifact, strict) != 1)
            throw new IllegalStateException("STALE_JOB");
        if (Set.of(
                        BackgroundJob.State.CANCELLED,
                        BackgroundJob.State.SUPERSEDED,
                        BackgroundJob.State.FAILED)
                .contains(state)) mapper.withdrawProjection(row.id(), reason);
        if (!reason.isEmpty())
            diagnostics.problem(
                    UUID.randomUUID(),
                    scope.userId(),
                    row.traceId(),
                    span,
                    row.requestId(),
                    "JOB",
                    reason);
    }

    /**
     * @param operation 输入；所有后继进入终态后才关闭根span，避免父完成早于子创建
     */
    private void finishOperation(UUID operation) {
        if (mapper.pending(operation) == 0)
            diagnostics.end(operation, mapper.outcome(operation), "");
    }

    /**
     * @param scope 归属
     * @return 资料水位，按user→chat顺序加锁
     */
    private long lock(Jobs.Scope scope) {
        if (scope == null || mapper.user(scope.userId()) == null)
            throw new IllegalStateException("JOB_NOT_FOUND");
        Long version = mapper.version(scope);
        if (version == null) throw new IllegalStateException("JOB_NOT_FOUND");
        return version;
    }

    /**
     * @param scope 私有范围
     * @param id 作业
     * @return 已锁行
     */
    private JobMapper.Row required(Jobs.Scope scope, UUID id) {
        var row = mapper.lock(scope, id);
        if (row == null) throw new IllegalStateException("JOB_NOT_FOUND");
        return row;
    }

    /**
     * @param scope 范围
     * @param row 数据行
     * @return 不可变资格
     */
    private Jobs.Fence fence(Jobs.Scope scope, JobMapper.Row row) {
        return new Jobs.Fence(scope, row.id(), row.token(), row.version());
    }

    /**
     * @param row 数据行
     * @return 不暴露参数的状态
     */
    private Jobs.View view(JobMapper.Row row) {
        return new Jobs.View(
                row.id(),
                row.operationId(),
                row.budgetId(),
                row.kind(),
                row.state(),
                row.token(),
                row.version(),
                row.leaseUntil(),
                row.traceId(),
                row.requestId(),
                row.error());
    }

    /**
     * @param work 短事务
     * @param <T> 结果
     * @return 提交后结果；可加入外层业务事务
     */
    private <T> T transaction(Supplier<T> work) {
        try {
            return tx.execute(status -> work.get());
        } catch (org.springframework.dao.DataAccessException failure) {
            com.lawrence.travel.infrastructure.execution.AdmissionErrors.rethrowKnown(failure);
            throw new IllegalStateException("JOB_STORAGE_UNAVAILABLE");
        }
    }

    /**
     * @param value 固定大写码
     * @return 合法分类，拒绝任意异常消息
     */
    private String code(String value) {
        if (value == null || value.isEmpty()) return "";
        if (!value.matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new IllegalArgumentException("INVALID_ERROR_CODE");
        return value;
    }

    /**
     * @param text 有界参数
     * @return SHA256，不输出正文
     */
    private String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("HASH_UNAVAILABLE");
        }
    }
}
