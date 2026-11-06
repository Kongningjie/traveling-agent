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
    public void completeCommand(Jobs.Scope jobScope, UUID jobId) {
        executeInTransaction(
                () -> {
                    lockJobScopeAndGetSourceRevision(jobScope);
                    var jobRow = getRequiredJobForUpdate(jobScope, jobId);
                    if (jobRow.state() == BackgroundJob.State.COMPLETED) return null;
                    if (jobRow.kind() != BackgroundJob.Kind.CHAT_INPUT
                            || jobRow.state() != BackgroundJob.State.QUEUED)
                        throw new IllegalStateException("INVALID_COMMAND_STATE");
                    transitionJobState(
                            jobScope, jobRow, BackgroundJob.State.COMPLETED, "", false, null);
                    finishOperation(jobRow.operationId());
                    return null;
                });
    }

    private final JobMapper jobMapper;
    private final DiagnosticMapper diagnosticMapper;
    private final QuotaMapper quotaMapper;
    private final JobPolicy jobPolicy;
    private final int maxConcurrentModelWork;
    private final TransactionTemplate transactionTemplate;

    /**
     * @param jobMapper 作业SQL
     * @param diagnosticMapper 脱敏记录
     * @param quotaMapper 日配额
     * @param jobPolicy 上限
     * @param manager 同数据源事务
     */
    public PostgresJobStore(
            JobMapper jobMapper,
            DiagnosticMapper diagnosticMapper,
            QuotaMapper quotaMapper,
            JobPolicy jobPolicy,
            PlatformTransactionManager manager) {
        this(jobMapper, diagnosticMapper, quotaMapper, jobPolicy, manager, 2);
    }

    /**
     * @param jobMapper 作业SQL
     * @param diagnosticMapper 诊断
     * @param quotaMapper 日配额
     * @param jobPolicy 输入限额
     * @param manager PG事务
     * @param maxConcurrentModelWork 沿用配置的全局模型工作上限
     */
    public PostgresJobStore(
            JobMapper jobMapper,
            DiagnosticMapper diagnosticMapper,
            QuotaMapper quotaMapper,
            JobPolicy jobPolicy,
            PlatformTransactionManager manager,
            int maxConcurrentModelWork) {
        if (maxConcurrentModelWork < 1)
            throw new IllegalArgumentException("INVALID_GLOBAL_CAPACITY");
        this.maxConcurrentModelWork = maxConcurrentModelWork;
        this.jobMapper = jobMapper;
        this.diagnosticMapper = diagnosticMapper;
        this.quotaMapper = quotaMapper;
        this.jobPolicy = jobPolicy;
        this.transactionTemplate = new TransactionTemplate(manager);
    }

    /** {@inheritDoc} 同身份异参数拒绝；上下文、预算与作业一起提交。 */
    @Override
    public Jobs.View registerBackgroundJob(Jobs.Submit submission) {
        return executeInTransaction(
                () -> {
                    if (submission == null
                            || submission.operationId() == null
                            || submission.kind() == null
                            || submission.branch() == null
                            || !submission.branch().matches("[A-Za-z0-9_-]{1,64}")
                            || submission.payload() == null
                            || submission.payload().length() > 65536)
                        throw new IllegalArgumentException("INVALID_JOB");
                    long sourceRevision = lockJobScopeAndGetSourceRevision(submission.scope());
                    if (submission.priorAttempts() < 0
                            || (submission.legacyBudget() == null
                                    && submission.priorAttempts() != 0)
                            || (submission.legacyBudget() != null
                                    && !submission
                                            .legacyBudget()
                                            .toString()
                                            .equals(jobMapper.legacyBudget(submission.scope()))))
                        throw new IllegalArgumentException("INVALID_LEGACY_BUDGET");
                    if (submission.dependency() != null
                            && jobMapper.dependency(submission.scope(), submission.dependency())
                                    == null)
                        throw new IllegalArgumentException("INVALID_DEPENDENCY");
                    var ids = ExecutionCorrelation.current();
                    jobMapper.budget(submission.operationId(), submission.legacyBudget());
                    int createdOperationCount =
                            jobMapper.operation(
                                    submission.scope(),
                                    submission.operationId(),
                                    submission.legacyBudget(),
                                    ids.requestId(),
                                    ids.traceId(),
                                    jobPolicy.inputLimits(),
                                    jobPolicy.inputLimits().modelTimeout().toMillis(),
                                    jobPolicy.inputLimits().activeTime().toMillis());
                    if (!jobMapper.owns(submission.scope(), submission.operationId()))
                        throw new IllegalStateException("JOB_NOT_FOUND");
                    if (createdOperationCount == 1)
                        diagnosticMapper.span(
                                submission.operationId(),
                                ids.traceId(),
                                null,
                                submission.scope().userId(),
                                ids.requestId(),
                                submission.scope().chatId(),
                                submission.operationId(),
                                null,
                                null,
                                null,
                                "INPUT");
                    UUID jobId =
                            UUID.nameUUIDFromBytes(
                                    (submission.scope().userId()
                                                    + "/"
                                                    + submission.operationId()
                                                    + "/"
                                                    + submission.kind()
                                                    + "/"
                                                    + submission.branch())
                                            .getBytes(StandardCharsets.UTF_8));
                    String payloadSha256 = computePayloadSha256(submission.payload());
                    jobMapper.insert(submission, jobId, payloadSha256, sourceRevision);
                    if (!jobMapper.same(
                            submission.scope(), jobId, payloadSha256, submission.dependency()))
                        throw new IllegalArgumentException("JOB_IDEMPOTENCY_CONFLICT");
                    return toJobView(getRequiredJobForUpdate(submission.scope(), jobId));
                });
    }

    /** {@inheritDoc} 所有读取带归属，缺失不泄露其他用户对象。 */
    @Override
    public Jobs.View read(Jobs.Scope jobScope, UUID jobId) {
        return executeInTransaction(
                () -> {
                    lockJobScopeAndGetSourceRevision(jobScope);
                    return toJobView(getRequiredJobForUpdate(jobScope, jobId));
                });
    }

    /** {@inheritDoc} 每个发现提示分别事务，避免跨用户累计锁形成反向锁序。 */
    @Override
    public Optional<Jobs.Claim> claim(Set<BackgroundJob.Kind> kinds) {
        return claim(kinds, Set.of());
    }

    /** {@inheritDoc} 全局轮转提示仍逐个用户短事务，排除在途取消者。 */
    @Override
    public Optional<Jobs.Claim> claim(Set<BackgroundJob.Kind> kinds, Set<UUID> excludedUserIds) {
        if (kinds.isEmpty()) return Optional.empty();
        for (var hint : jobMapper.hints(kinds)) {
            if (excludedUserIds.contains(hint.userId())) continue;
            var found = claim(new Jobs.Scope(hint.userId(), hint.chatId()), hint.id());
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    /** {@inheritDoc} 过期尝试先结算；等待用户不占租约；缺少处理器的类型不会被发现。 */
    @Override
    public Optional<Jobs.Claim> claim(Jobs.Scope jobScope, UUID jobId) {
        return executeInTransaction(
                () -> {
                    long sourceRevision = lockJobScopeAndGetSourceRevision(jobScope);
                    var jobRow = getRequiredJobForUpdate(jobScope, jobId);
                    if (BackgroundJob.terminal(jobRow.state())
                            || jobRow.state() == BackgroundJob.State.PAUSED
                            || jobRow.state() == BackgroundJob.State.WAITING_USER)
                        return Optional.empty();
                    if (jobRow.version() != sourceRevision) {
                        transitionJobState(
                                jobScope,
                                jobRow,
                                BackgroundJob.State.SUPERSEDED,
                                "SOURCE_CHANGED",
                                false,
                                null);
                        return Optional.empty();
                    }
                    if (jobRow.state() == BackgroundJob.State.RUNNING
                            && jobRow.leaseUntil().isAfter(jobMapper.now()))
                        return Optional.empty();
                    String dependency = jobMapper.dependencyId(jobScope, jobId);
                    if (dependency != null) {
                        var state =
                                BackgroundJob.State.valueOf(
                                        jobMapper.dependency(
                                                jobScope, UUID.fromString(dependency)));
                        if (state != BackgroundJob.State.COMPLETED) {
                            if (BackgroundJob.terminal(state))
                                transitionJobState(
                                        jobScope,
                                        jobRow,
                                        BackgroundJob.State.PAUSED,
                                        "DEPENDENCY_FAILED",
                                        false,
                                        null);
                            return Optional.empty();
                        }
                    }
                    if (jobRow.state() == BackgroundJob.State.RUNNING)
                        transitionJobState(
                                jobScope,
                                jobRow,
                                BackgroundJob.State.QUEUED,
                                "LEASE_EXPIRED",
                                false,
                                null);
                    jobRow = getRequiredJobForUpdate(jobScope, jobId);
                    if (jobRow.attempts() >= 3) {
                        transitionJobState(
                                jobScope,
                                jobRow,
                                BackgroundJob.State.PAUSED,
                                "ATTEMPTS_EXHAUSTED",
                                false,
                                null);
                        return Optional.empty();
                    }
                    if (jobMapper.earlier(jobScope, jobId)) return Optional.empty();
                    if (jobMapper.operationActive(jobRow.operationId()) > 0)
                        return Optional.empty();
                    boolean parser = jobRow.kind() == BackgroundJob.Kind.DOCUMENT_PARSE;
                    var quotaDay = jobMapper.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
                    if (jobMapper.activeCount(jobScope.userId(), parser) > 0)
                        return Optional.empty();
                    if (!parser
                            && (!jobMapper.modelDispatch()
                                    || jobMapper.travelWork(jobScope.userId()) > 0
                                    || jobMapper.modelWork() >= maxConcurrentModelWork))
                        return Optional.empty();
                    if (parser) {
                        jobMapper.dispatch();
                        if (jobMapper.parses() > 0) return Optional.empty();
                        quotaMapper.create(jobScope.userId(), quotaDay, "PARSE");
                        if (quotaMapper.reserve(
                                        jobScope.userId(),
                                        quotaDay,
                                        "PARSE",
                                        jobPolicy.dailyParses())
                                != 1) {
                            transitionJobState(
                                    jobScope,
                                    jobRow,
                                    BackgroundJob.State.PAUSED,
                                    "DAILY_QUOTA_EXHAUSTED",
                                    false,
                                    null);
                            return Optional.empty();
                        }
                    }
                    jobMapper.lease(jobScope, jobId);
                    jobRow = getRequiredJobForUpdate(jobScope, jobId);
                    UUID span = UUID.randomUUID();
                    jobMapper.attempt(jobRow, span);
                    diagnosticMapper.span(
                            span,
                            jobRow.traceId(),
                            jobRow.operationId(),
                            jobScope.userId(),
                            jobRow.requestId(),
                            jobScope.chatId(),
                            jobRow.operationId(),
                            null,
                            jobId,
                            jobRow.token(),
                            jobRow.kind().name());
                    if (parser) {
                        var owner = quotaMapper.operation(jobRow.budgetId());
                        quotaMapper.own(span, owner, jobRow.budgetId(), quotaDay, "PARSE");
                    }
                    return Optional.of(
                            new Jobs.Claim(
                                    fence(jobScope, jobRow), toJobView(jobRow), jobRow.payload()));
                });
    }

    /** {@inheritDoc} 版本和租约必须同时有效。 */
    @Override
    public boolean isJobExecutionValid(Jobs.Fence fence) {
        return jobMapper.live(fence);
    }

    /** {@inheritDoc} 原资格已过期时不复活。 */
    @Override
    public boolean renew(Jobs.Fence fence) {
        return executeInTransaction(
                () -> {
                    lockJobScopeAndGetSourceRevision(fence.scope());
                    return jobMapper.live(fence) && jobMapper.renew(fence) == 1;
                });
    }

    /** {@inheritDoc} 产物、后继与终态同事务；后继非法会整体回滚。 */
    @Override
    public void finish(Jobs.Claim claim, Jobs.Output output) {
        executeInTransaction(
                () -> {
                    lockJobScopeAndGetSourceRevision(claim.fence().scope());
                    if (!jobMapper.live(claim.fence()))
                        throw new IllegalStateException("STALE_JOB");
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
                    for (var submission : output.next())
                        if (!submission.scope().equals(claim.fence().scope())
                                || !submission.operationId().equals(claim.job().operationId()))
                            throw new IllegalArgumentException("INVALID_SUCCESSOR");
                    var jobRow = getRequiredJobForUpdate(claim.fence().scope(), claim.job().id());
                    transitionJobState(
                            claim.fence().scope(),
                            jobRow,
                            output.state(),
                            code(output.reason()),
                            false,
                            output.artifact(),
                            true);
                    output.next().forEach(this::registerBackgroundJob);
                    finishOperation(jobRow.operationId());
                    return null;
                });
    }

    /** {@inheritDoc} 明确继续保持原预算；只有暂停／等待用户允许继续。 */
    @Override
    public void applyJobRetryOrCancellation(
            Jobs.Scope jobScope, UUID jobId, boolean retryRequested) {
        executeInTransaction(
                () -> {
                    lockJobScopeAndGetSourceRevision(jobScope);
                    var jobRow = getRequiredJobForUpdate(jobScope, jobId);
                    var state =
                            retryRequested
                                    ? BackgroundJob.State.QUEUED
                                    : BackgroundJob.State.CANCELLED;
                    if (!retryRequested && jobRow.state() == BackgroundJob.State.CANCELLED)
                        return null;
                    if (retryRequested && jobRow.state() == BackgroundJob.State.QUEUED) return null;
                    if (retryRequested
                            && jobRow.state() != BackgroundJob.State.PAUSED
                            && jobRow.state() != BackgroundJob.State.WAITING_USER)
                        throw new IllegalStateException("INVALID_JOB_STATE");
                    if (!BackgroundJob.allows(jobRow.state(), state))
                        throw new IllegalStateException("INVALID_JOB_STATE");
                    transitionJobState(
                            jobScope,
                            jobRow,
                            state,
                            retryRequested ? "" : "USER_CANCELLED",
                            !retryRequested,
                            null);
                    if (retryRequested) jobMapper.resetAttempts(jobScope, jobId);
                    finishOperation(jobRow.operationId());
                    return null;
                });
    }

    /** {@inheritDoc} 已完成幂等；其他终态不能借确认复活。 */
    @Override
    public void acknowledge(Jobs.Scope jobScope, UUID jobId) {
        executeInTransaction(
                () -> {
                    lockJobScopeAndGetSourceRevision(jobScope);
                    var jobRow = getRequiredJobForUpdate(jobScope, jobId);
                    if (jobRow.state() == BackgroundJob.State.COMPLETED) return null;
                    if (jobRow.state() != BackgroundJob.State.WAITING_USER)
                        throw new IllegalStateException("INVALID_JOB_STATE");
                    transitionJobState(
                            jobScope, jobRow, BackgroundJob.State.COMPLETED, "", false, null);
                    finishOperation(jobRow.operationId());
                    return null;
                });
    }

    /** {@inheritDoc} 删除／资料变化立即使旧token失效，不等待物理线程退出。 */
    @Override
    public long invalidate(Jobs.Scope jobScope) {
        return executeInTransaction(
                () -> {
                    lockJobScopeAndGetSourceRevision(jobScope);
                    long sourceRevision = jobMapper.invalidate(jobScope);
                    for (String jobId : jobMapper.active(jobScope)) {
                        var jobRow = getRequiredJobForUpdate(jobScope, UUID.fromString(jobId));
                        transitionJobState(
                                jobScope,
                                jobRow,
                                BackgroundJob.State.SUPERSEDED,
                                "SOURCE_CHANGED",
                                true,
                                null);
                        finishOperation(jobRow.operationId());
                    }
                    return sourceRevision;
                });
    }

    /**
     * @param jobScope 归属
     * @param jobRow 旧行
     * @param state 新态
     * @param reason 固定码
     * @param full 取消保守结算
     * @param artifact 产物；调用者已持归属锁
     */
    private void transitionJobState(
            Jobs.Scope jobScope,
            JobMapper.Row jobRow,
            BackgroundJob.State state,
            String reason,
            boolean full,
            String artifact) {
        transitionJobState(jobScope, jobRow, state, reason, full, artifact, false);
    }

    /**
     * @param jobScope 归属
     * @param jobRow 旧行
     * @param state 目标
     * @param reason 固定码
     * @param full 保守计时
     * @param artifact JSON
     * @param strict 正常完成必须在SQL写入时仍有有效租约
     */
    private void transitionJobState(
            Jobs.Scope jobScope,
            JobMapper.Row jobRow,
            BackgroundJob.State state,
            String reason,
            boolean full,
            String artifact,
            boolean strict) {
        if (!BackgroundJob.allows(jobRow.state(), state))
            throw new IllegalStateException("INVALID_JOB_STATE");
        jobMapper.settle(jobRow, full);
        jobMapper.endAttempt(jobRow.id(), jobRow.token(), state.name());
        String spanText = jobMapper.attemptSpan(jobRow.id(), jobRow.token());
        UUID span = spanText == null ? null : UUID.fromString(spanText);
        if (span != null) {
            diagnosticMapper.end(span, state.name(), reason);
            if (jobRow.kind() == BackgroundJob.Kind.DOCUMENT_PARSE)
                quotaMapper.finish(span, state != BackgroundJob.State.QUEUED && !full);
        }
        if (jobMapper.state(fence(jobScope, jobRow), state.name(), reason, artifact, strict) != 1)
            throw new IllegalStateException("STALE_JOB");
        if (Set.of(
                        BackgroundJob.State.CANCELLED,
                        BackgroundJob.State.SUPERSEDED,
                        BackgroundJob.State.FAILED)
                .contains(state)) jobMapper.withdrawProjection(jobRow.id(), reason);
        if (!reason.isEmpty())
            diagnosticMapper.problem(
                    UUID.randomUUID(),
                    jobScope.userId(),
                    jobRow.traceId(),
                    span,
                    jobRow.requestId(),
                    "JOB",
                    reason);
    }

    /**
     * @param operation 输入；所有后继进入终态后才关闭根span，避免父完成早于子创建
     */
    private void finishOperation(UUID operation) {
        if (jobMapper.pending(operation) == 0)
            diagnosticMapper.end(operation, jobMapper.outcome(operation), "");
    }

    /**
     * @param jobScope 归属
     * @return 资料水位，按user→chat顺序加锁
     */
    private long lockJobScopeAndGetSourceRevision(Jobs.Scope jobScope) {
        if (jobScope == null || jobMapper.user(jobScope.userId()) == null)
            throw new IllegalStateException("JOB_NOT_FOUND");
        Long sourceRevision = jobMapper.version(jobScope);
        if (sourceRevision == null) throw new IllegalStateException("JOB_NOT_FOUND");
        return sourceRevision;
    }

    /**
     * @param jobScope 私有范围
     * @param jobId 作业
     * @return 已锁行
     */
    private JobMapper.Row getRequiredJobForUpdate(Jobs.Scope jobScope, UUID jobId) {
        var jobRow = jobMapper.lock(jobScope, jobId);
        if (jobRow == null) throw new IllegalStateException("JOB_NOT_FOUND");
        return jobRow;
    }

    /**
     * @param jobScope 范围
     * @param jobRow 数据行
     * @return 不可变资格
     */
    private Jobs.Fence fence(Jobs.Scope jobScope, JobMapper.Row jobRow) {
        return new Jobs.Fence(jobScope, jobRow.id(), jobRow.token(), jobRow.version());
    }

    /**
     * @param jobRow 数据行
     * @return 不暴露参数的状态
     */
    private Jobs.View toJobView(JobMapper.Row jobRow) {
        return new Jobs.View(
                jobRow.id(),
                jobRow.operationId(),
                jobRow.budgetId(),
                jobRow.kind(),
                jobRow.state(),
                jobRow.token(),
                jobRow.version(),
                jobRow.leaseUntil(),
                jobRow.traceId(),
                jobRow.requestId(),
                jobRow.error());
    }

    /**
     * @param transactionAction 短事务
     * @param <T> 结果
     * @return 提交后结果；可加入外层业务事务
     */
    private <T> T executeInTransaction(Supplier<T> transactionAction) {
        try {
            return transactionTemplate.execute(status -> transactionAction.get());
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
    private String computePayloadSha256(String text) {
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
