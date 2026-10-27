package com.lawrence.travel.infrastructure.execution.persistence;

import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.DEPENDENCY_UNAVAILABLE;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.IDEMPOTENCY_CONFLICT;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.INVALID_INPUT;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.NOT_FOUND;

import com.lawrence.travel.application.execution.StageCommitCommand;
import com.lawrence.travel.application.execution.StageOutput;
import com.lawrence.travel.application.execution.StagePlan;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.StageProjection;
import com.lawrence.travel.application.port.TravelStore;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.domain.execution.ExecutionFence;
import com.lawrence.travel.domain.execution.ExecutionLease;
import com.lawrence.travel.domain.execution.ExecutionRun;
import com.lawrence.travel.domain.execution.ExecutionStep;
import com.lawrence.travel.domain.execution.OutboxState;
import com.lawrence.travel.domain.execution.StageDispatch;
import com.lawrence.travel.domain.execution.StageType;
import com.lawrence.travel.domain.execution.control.RunBudget;
import com.lawrence.travel.domain.execution.control.RunLimits;
import com.lawrence.travel.domain.interaction.DecisionAnswer;
import com.lawrence.travel.domain.interaction.DecisionRequest;
import com.lawrence.travel.domain.rule.DomainViolation;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.domain.travel.TravelTask;
import com.lawrence.travel.infrastructure.persistence.control.BudgetMapper;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import com.lawrence.travel.infrastructure.travel.TravelMapper;

import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 旅行阶段的PG权威执行引擎，管理Outbox事件、租约、围栏、检查点、业务投影与后继阶段。
 *
 * <p>上游协商服务start登记运行，Worker经candidates/claim领取，StageExecutor事务外执行；
 * prepare独立保存产物，commit在短事务内复查generation/需求版本/attempt/租约，
 * 投影业务结果、保存检查点、完成事件并可靠登记下一阶段。模型返回不等于commit成功。
 *
 * <p>cancel/supersede使旧执行失去提交权，recover找回可恢复事件但不重置原预算。
 * 用户可见运行事件与诊断执行轨迹有不同用途：前者供SSE补读，后者供追踪固定分类问题及计量。
 */
public final class PostgresExecutionEngine implements ExecutionEngineStore {
    /** {@inheritDoc} 归属先经原运行读取校验，不能凭任意runId取得其他用户链路。 */
    @Override
    public com.lawrence.travel.application.execution.ExecutionCorrelation.Ids correlation(
            Claim claim) {
        run(claim.fence().scope(), claim.fence().runId());
        return mapper.correlation(claim.fence().runId());
    }

    private final EngineMapper mapper;
    private final TravelMapper tasks;
    private final BudgetMapper budgets;
    private final TravelJson json;
    private final TransactionTemplate transaction;
    private final RunLimits initialLimits;
    private final int maximumRuns;
    private final int maximumResearch;
    private final Duration leaseTime;
    private final int maximumAttempts;
    private final StageProjection projection;

    /**
     * @param mapper 执行 SQL
     * @param tasks 任务 SQL
     * @param budgets 同源权威预算
     * @param json 规范 JSON
     * @param manager 事务
     * @param limits 新运行上限
     * @param maximumRuns 活动运行上限
     * @param maximumResearch 调查并行上限
     * @param leaseTime 租约正时长
     * @param maximumAttempts 阶段恢复总尝试上限
     */
    public PostgresExecutionEngine(
            EngineMapper mapper,
            TravelMapper tasks,
            BudgetMapper budgets,
            TravelJson json,
            PlatformTransactionManager manager,
            RunLimits limits,
            int maximumRuns,
            int maximumResearch,
            Duration leaseTime,
            int maximumAttempts) {
        this(
                mapper,
                tasks,
                budgets,
                json,
                manager,
                limits,
                maximumRuns,
                maximumResearch,
                leaseTime,
                maximumAttempts,
                new StageProjection() {
                    /** 无候选仓储的测试引擎不投影业务产物。 */
                    @Override
                    public void beforeTaskLock(ExecutionFence fence, StageOutput output) {}

                    /**
                     * @return 空；禁止无候选仓储时进入等待接受
                     */
                    @Override
                    public StageProjection.Result project(
                            ExecutionFence fence, StageType type, StageOutput output) {
                        return null;
                    }
                });
    }

    /**
     * @param mapper 执行 SQL
     * @param tasks 任务 SQL
     * @param budgets 权威预算
     * @param json JSON
     * @param manager 事务
     * @param limits 上限
     * @param maximumRuns 并行数
     * @param maximumResearch 调查数
     * @param leaseTime 租约
     * @param maximumAttempts 尝试数
     * @param projection 同事务业务投影
     */
    public PostgresExecutionEngine(
            EngineMapper mapper,
            TravelMapper tasks,
            BudgetMapper budgets,
            TravelJson json,
            PlatformTransactionManager manager,
            RunLimits limits,
            int maximumRuns,
            int maximumResearch,
            Duration leaseTime,
            int maximumAttempts,
            StageProjection projection) {
        require(
                maximumRuns > 0
                        && maximumResearch > 0
                        && leaseTime != null
                        && leaseTime.toMillis() > 0
                        && maximumAttempts > 0,
                INVALID_INPUT);
        this.mapper = mapper;
        this.tasks = tasks;
        this.budgets = budgets;
        this.json = json;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(10);
        initialLimits = limits;
        this.maximumRuns = maximumRuns;
        this.maximumResearch = maximumResearch;
        this.leaseTime = leaseTime;
        this.maximumAttempts = maximumAttempts;
        this.projection = java.util.Objects.requireNonNull(projection);
    }

    /**
     * {@inheritDoc} 输入、运行、预算和首事件同事务；同输入不同阶段定义明确冲突。
     */
    @Override
    public ExecutionRun start(TaskScope scope, UUID inputId, StagePlan first, Goal goal) {
        require(inputId != null && first != null && goal != null, INVALID_INPUT);
        return tx(
                () -> {
                    TravelMapper.TaskRow task = taskLock(scope);
                    String hash = json.hash(json.encode(List.of(first, goal)));
                    var replay = mapper.startRequest(scope, inputId);
                    if (replay != null) {
                        require(replay.requestHash().equals(hash), IDEMPOTENCY_CONFLICT);
                        return run(scope, replay.runId());
                    }
                    UUID current = currentRun(scope);
                    if (current != null) {
                        Session old = lock(scope, current);
                        if (!old.run.state().terminal()) stop(old, true);
                    }
                    long generation =
                            current == null
                                    ? task.executionGeneration()
                                    : Math.incrementExact(task.executionGeneration());
                    ExecutionRun run = newRun(scope, generation, task.constraintRevision(), null);
                    insertRun(run, goal, initialLimits);
                    checked(mapper.pointRun(scope, run.id(), generation));
                    enqueue(run, inputId, first);
                    checked(mapper.insertStart(scope, inputId, hash, run.id()));
                    visible(scope, run.id(), null, "RUN_QUEUED");
                    return run;
                });
    }

    /**
     * {@inheritDoc} JSON 中预算仅为历史快照，实际次数及累计时间一律从 execution_budget 读。
     */
    @Override
    public ExecutionRun run(TaskScope scope, UUID runId) {
        return safe(
                () -> {
                    var row = mapper.run(scope, runId);
                    require(row != null, NOT_FOUND);
                    return withBudget(json.decode(row.body(), ExecutionRun.class));
                });
    }

    /**
     * {@inheritDoc} 不将无锁提示当成领取成功。
     */
    @Override
    public List<Hint> candidates(int limit) {
        batch(limit);
        return safe(() -> mapper.candidates(limit).stream().map(this::hint).toList());
    }

    /**
     * {@inheritDoc} 领取互斥只覆盖短数据库检查；单实例之外仍由数据库约束守住业务提交。
     */
    @Override
    public Optional<Claim> claim(Hint hint, String owner) {
        require(owner != null && owner.matches("[A-Za-z0-9_-]{1,128}"), INVALID_INPUT);
        return tx(
                () -> {
                    if (!mapper.claimMutex()) return Optional.empty();
                    Session session = lock(hint.scope(), hint.runId());
                    EngineMapper.EventRow event = event(session, hint.eventId());
                    require(event.stepId().equals(hint.stepId()), NOT_FOUND);
                    Instant now = mapper.now();
                    if (!(event.status().equals("READY") || event.status().equals("RETRY_WAIT"))
                            || event.availableAt().isAfter(now)) return Optional.empty();
                    if (!current(session, event)) {
                        obsolete(session);
                        return Optional.empty();
                    }
                    settle(session, now);
                    if (exhausted(session)) {
                        pause(session, Failure.BUDGET);
                        return Optional.empty();
                    }
                    int active = mapper.leased(session.run.id());
                    if (session.run.state() != ExecutionRun.State.QUEUED
                            && session.run.state() != ExecutionRun.State.RUNNING)
                        return Optional.empty();
                    if (active == 0
                            && (mapper.activeWork() >= maximumRuns
                                    || mapper.otherUserWork(hint.scope().userId(), hint.runId())
                                            > 0)) return Optional.empty();
                    ExecutionStep step = step(session, event.stepId());
                    StagePlan definition = definition(session, step.id());
                    if (active > 0
                            && (step.key().type() != StageType.RESEARCH
                                    || active >= maximumResearch)) return Optional.empty();
                    for (UUID dependency : definition.dependencies()) {
                        var row = mapper.step(session.scope, session.run.id(), dependency);
                        require(row != null, NOT_FOUND);
                        if (json.decode(row.body(), ExecutionStep.class).state()
                                != ExecutionStep.State.COMPLETED) return Optional.empty();
                    }
                    if (step.state() == ExecutionStep.State.COMPLETED) {
                        update(event, "DONE", now, null, event.fencingToken(), null);
                        reconcile(session);
                        return Optional.empty();
                    }
                    if (step.attemptCount() >= maximumAttempts) {
                        terminate(session, event, step, false);
                        return Optional.empty();
                    }
                    if (step.key().type() == StageType.PLAN
                            && step.key().round() > 0
                            && step.attemptCount() == 0) {
                        if (mapper.repair(
                                        session.run.id(),
                                        json.decode(session.row.limits(), RunLimits.class)
                                                .repairRounds())
                                != 1) {
                            pause(session, Failure.BUDGET);
                            return Optional.empty();
                        }
                        session.run = withBudget(session.run);
                    }
                    if (step.state() == ExecutionStep.State.RETRY_WAIT) step = step.retryReady();
                    step = step.begin(UUID.randomUUID());
                    updateStep(step);
                    long token = Math.incrementExact(event.fencingToken());
                    checked(
                            mapper.insertAttempt(
                                    session.scope,
                                    session.run.id(),
                                    step.id(),
                                    event.id(),
                                    step.currentAttemptId(),
                                    step.attemptCount(),
                                    token));
                    var leased =
                            copy(
                                    event,
                                    "LEASED",
                                    now,
                                    owner,
                                    now.plus(leaseTime),
                                    token,
                                    step.attemptCount(),
                                    null);
                    checked(mapper.updateEvent(leased, event.fencingToken()));
                    if (session.run.state() == ExecutionRun.State.QUEUED)
                        session.run = session.run.start();
                    session.activeSince = now;
                    save(session);
                    visible(session.scope, session.run.id(), step.id(), "STAGE_STARTED");
                    return Optional.of(
                            new Claim(
                                    fence(leased, step.currentAttemptId()),
                                    step,
                                    definition.input()));
                });
    }

    /**
     * {@inheritDoc} 续租前按实时数据库时间检查旧权威；过期尚未恢复也不能续租复活。
     */
    @Override
    public boolean renew(ExecutionFence fence) {
        return tx(
                () -> {
                    Session session = lock(fence.scope(), fence.runId());
                    var event = event(session, fence.claim().eventId());
                    if (!valid(session, fence, event)) return false;
                    settle(session, mapper.now());
                    if (exhausted(session)) {
                        pause(session, Failure.BUDGET);
                        return false;
                    }
                    return mapper.renewEvent(fence, leaseTime.toMillis()) == 1;
                });
    }

    /**
     * {@inheritDoc}
     *
     * <p>StageExecutor在业务处理结束后调用，先验证检查点JSON并保存本attempt的完整产物、哈希与长度。
     * 产物单独持久化便于审计，但尚未被业务选中，不能因此对用户展示或推进阶段。
     * 返回命令携带原围栏及确定性后继身份，随后commit决定是否正式提交；取消可使此产物最终不被采用。
     */
    @Override
    public StageCommitCommand prepare(Claim claim, StageOutput output) {
        require(claim != null && output != null, INVALID_INPUT);
        objectJson(output.checkpoint());
        String body = json.encode(output);
        size(body);
        String hash = json.hash(body);
        UUID id = UUID.randomUUID();
        var f = claim.fence();
        tx(
                () -> {
                    checked(
                            mapper.insertArtifact(
                                    f.scope(),
                                    f.runId(),
                                    f.stepId(),
                                    f.attemptId(),
                                    id,
                                    hash,
                                    body.getBytes(StandardCharsets.UTF_8).length));
                    checked(mapper.insertContent(id, body));
                    return true;
                });
        return new StageCommitCommand(
                f, id, hash, dispatches(f.runId(), f.stepId(), output.next()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>最终业务生效边界：核对产物归属/哈希/后继与相同内容重放回执，再锁定原任务和运行，
     * 复查围栏及数据库租约。占定完成资格后调用业务投影，更新需求/候选/决策等检查点，
     * 并登记后继Outbox及可见事件；任一步失败共同回滚，不允许完成阶段却丢失下一步。
     * 已提交同内容返回ALREADY_COMMITTED；旧代次/租约/attempt返回STALE，不通过重新签名旧产物强行提交。
     */
    @Override
    public Result commit(StageCommitCommand command) {
        return tx(
                () -> {
                    var f = command.fence();
                    var preview =
                            mapper.artifact(f.scope(), f.runId(), f.stepId(), command.artifactId());
                    require(preview != null, NOT_FOUND);
                    projection.beforeTaskLock(f, json.decode(preview.body(), StageOutput.class));
                    Session session = lock(f.scope(), f.runId());
                    var event = event(session, f.claim().eventId());
                    var stepRow = session.steps.get(f.stepId());
                    require(stepRow != null, NOT_FOUND);
                    var artifact =
                            mapper.artifact(f.scope(), f.runId(), f.stepId(), command.artifactId());
                    require(artifact != null, NOT_FOUND);
                    require(
                            artifact.attemptId().equals(f.attemptId())
                                    && artifact.contentHash().equals(command.contentHash()),
                            IDEMPOTENCY_CONFLICT);
                    StageOutput output = json.decode(artifact.body(), StageOutput.class);
                    // 哈希核对保存的原始规范JSON；领域Set反序列化后顺序可变，不得让其重编码制造假冲突。
                    // 原文、内容哈希、attempt归属和后继dispatch仍全部核对，不修改旧产物或重签历史哈希。
                    require(
                            json.hash(json.encode(json.decode(artifact.body(), Object.class)))
                                            .equals(command.contentHash())
                                    && dispatches(f.runId(), f.stepId(), output.next())
                                            .equals(command.nextStages()),
                            IDEMPOTENCY_CONFLICT);
                    String receipt =
                            json.hash(
                                    json.encode(
                                            List.of(
                                                    f.attemptId(),
                                                    command.contentHash(),
                                                    command.nextStages())));
                    if (stepRow.committedAttemptId() != null) {
                        if (!stepRow.committedAttemptId().equals(f.attemptId()))
                            return Result.STALE;
                        require(receipt.equals(stepRow.receiptHash()), IDEMPOTENCY_CONFLICT);
                        return Result.ALREADY_COMMITTED;
                    }
                    if (!valid(session, f, event)) return Result.STALE;
                    settle(session, mapper.now());
                    if (exhausted(session)) {
                        pause(session, Failure.BUDGET);
                        return Result.STALE;
                    }
                    // SQL 执行时再次比较数据库时钟，封住校验与写入之间到期的窗口。
                    // 此时只是同一事务内占定完成资格；后续任一失败都会连 DONE 状态一起回滚。
                    if (mapper.completeEvent(f) != 1) return Result.STALE;
                    var projected =
                            projection.project(f, step(session, f.stepId()).key().type(), output);
                    UUID eligibleCandidate = projected == null ? null : projected.candidateId();
                    if (projected != null && projected.constraintRevision() != null) {
                        var updated = tasks.task(f.scope());
                        require(
                                updated.constraintRevision() == projected.constraintRevision()
                                        && updated.executionGeneration()
                                                == session.task.executionGeneration()
                                        && mapper.leased(f.runId()) == 0
                                        && mapper.pending(f.runId()) == 0,
                                INVALID_INPUT);
                        session.run =
                                session.run.advanceConstraints(
                                        session.task.constraintRevision(),
                                        updated.constraintRevision());
                        session.task = updated;
                    }
                    if (output.outcome() == StageOutput.Outcome.PRESENT_CANDIDATE) {
                        require(
                                eligibleCandidate != null
                                        && session.row.goal().equals(Goal.PLANNING.name())
                                        && mapper.leased(f.runId()) == 0
                                        && mapper.pending(f.runId()) == 0
                                        && mapper.openDecisions(f.scope(), f.runId()) == 0,
                                INVALID_INPUT);
                    }
                    if (output.outcome() == StageOutput.Outcome.CONSULTATION_COMPLETE) {
                        require(
                                session.row.goal().equals(Goal.CONSULTATION.name())
                                        && mapper.leased(f.runId()) == 0
                                        && mapper.pending(f.runId()) == 0
                                        && mapper.openDecisions(f.scope(), f.runId()) == 0,
                                INVALID_INPUT);
                    }
                    if (output.decision() != null) {
                        DecisionRequest decision = output.decision();
                        require(
                                decision.scope().equals(f.scope())
                                        && decision.runId().equals(f.runId())
                                        && decision.generation() == f.generation()
                                        && decision.constraintRevision()
                                                == session.run.constraintRevision()
                                        && decision.state() == DecisionRequest.State.OPEN,
                                INVALID_INPUT);
                        String existingDecision =
                                mapper.decision(f.scope(), f.runId(), decision.id());
                        if (existingDecision == null)
                            checked(
                                    mapper.insertDecision(
                                            f.scope(),
                                            f.runId(),
                                            decision.id(),
                                            json.encode(decision)));
                        else
                            require(
                                    json.decode(existingDecision, DecisionRequest.class)
                                            .equals(decision),
                                    IDEMPOTENCY_CONFLICT);
                    }
                    // 新阶段及因果事件全部成功才允许完成当前事件；任一唯一键冲突回滚当前完成及进度。
                    boolean replace = projected != null && projected.replacementInput() != null;
                    if (replace)
                        require(
                                output.outcome() == StageOutput.Outcome.CONTINUE
                                        && output.next().size() == 1,
                                INVALID_INPUT);
                    else
                        for (StagePlan next : output.next()) enqueue(session.run, f.stepId(), next);
                    ExecutionStep completed =
                            step(session, f.stepId())
                                    .finish(f.attemptId(), ExecutionStep.State.COMPLETED);
                    checked(
                            mapper.finishStep(
                                    f.scope(),
                                    f.runId(),
                                    f.stepId(),
                                    json.encode(completed),
                                    f.attemptId(),
                                    receipt,
                                    command.artifactId()));
                    checked(mapper.finishAttempt(f.attemptId(), "COMPLETED"));
                    if (replace) {
                        save(session);
                        start(f.scope(), projected.replacementInput(), output.next().getFirst());
                        return Result.COMMITTED;
                    }
                    if (output.outcome() == StageOutput.Outcome.CONSULTATION_COMPLETE)
                        session.run = session.run.completeConsultation();
                    if (output.outcome() == StageOutput.Outcome.PRESENT_CANDIDATE)
                        session.run = session.run.waitForAcceptance(eligibleCandidate);
                    reconcile(session);
                    visible(f.scope(), f.runId(), f.stepId(), "STAGE_COMPLETED");
                    return Result.COMMITTED;
                });
    }

    /** {@inheritDoc} 接受失败后的可靠重校验登记与外层接受请求事务共同提交。 */
    @Override
    public void revalidateCandidate(TaskScope scope, UUID runId, UUID cause, StagePlan next) {
        tx(
                () -> {
                    Session session = lock(scope, runId);
                    require(
                            session.run.state() == ExecutionRun.State.WAITING_ACCEPTANCE
                                    && runId.equals(currentRun(scope))
                                    && session.run.generation()
                                            == session.task.executionGeneration()
                                    && session.run.constraintRevision()
                                            == session.task.constraintRevision()
                                    && next.key().type() == StageType.VALIDATE,
                            INVALID_INPUT);
                    session.run =
                            exhausted(session)
                                    ? session.run.pauseBudget()
                                    : session.run.revalidateCandidate();
                    enqueue(session.run, cause, next);
                    save(session);
                    visible(scope, runId, next.id(), "CANDIDATE_REVALIDATION_QUEUED");
                    return true;
                });
    }

    /** 原问题回答沿用运行与预算，只排队解释，未在接收时采纳。 */
    @Override
    public void interpretAnswer(TaskScope scope, UUID runId, UUID input, StagePlan next) {
        tx(
                () -> {
                    Session session = lock(scope, runId);
                    require(
                            runId.equals(currentRun(scope))
                                    && session.run.generation()
                                            == session.task.executionGeneration()
                                    && session.run.constraintRevision()
                                            == session.task.constraintRevision()
                                    && next.key().type() == StageType.UNDERSTAND,
                            INVALID_INPUT);
                    session.run = session.run.resumeAfterAnswer(session.task.constraintRevision());
                    enqueue(session.run, input, next);
                    save(session);
                    visible(scope, runId, null, "ANSWER_RECEIVED");
                    return true;
                });
    }

    /**
     * {@inheritDoc} 旧回调不触碰当前状态，重试使用同一事件、阶段和预算。
     */
    @Override
    public void fail(ExecutionFence fence, Failure failure) {
        tx(
                () -> {
                    Session session = lock(fence.scope(), fence.runId());
                    var event = event(session, fence.claim().eventId());
                    if (!valid(session, fence, event)) return false;
                    settle(session, mapper.now());
                    if (failure == Failure.BUDGET || failure == Failure.QUOTA) {
                        pause(session, failure);
                        return true;
                    }
                    ExecutionStep step = step(session, fence.stepId());
                    if (failure == Failure.PERMANENT || step.attemptCount() >= maximumAttempts)
                        terminate(session, event, step, failure == Failure.PERMANENT);
                    else retry(session, event, step, false);
                    return true;
                });
    }

    /**
     * {@inheritDoc} 只有当前运行取消才提升任务代次；取消旧历史不能失效新运行。
     */
    @Override
    public ExecutionRun cancel(TaskScope scope, UUID runId) {
        return tx(
                () -> {
                    Session session = lock(scope, runId);
                    if (session.run.state() == ExecutionRun.State.CANCELLED) return session.run;
                    require(!session.run.state().terminal(), INVALID_INPUT);
                    stop(session, false);
                    if (runId.equals(currentRun(scope)))
                        checked(
                                mapper.pointRun(
                                        scope,
                                        runId,
                                        Math.incrementExact(session.task.executionGeneration())));
                    return session.run;
                });
    }

    /**
     * {@inheritDoc} 任务锁内与输入保存原子执行；新代次立即封住所有旧提交。
     */
    @Override
    public List<UUID> supersede(TaskScope scope) {
        return tx(
                () -> {
                    taskLock(scope);
                    UUID id = currentRun(scope);
                    if (id == null) return List.of();
                    Session session = lock(scope, id);
                    if (session.run.state().terminal()
                            || session.run.generation() == session.task.executionGeneration())
                        return List.of();
                    stop(session, true);
                    return List.of(id);
                });
    }

    /**
     * {@inheritDoc} 继续保留原终态，任务锁内建立唯一后继及新预算，不能复活原运行。
     */
    @Override
    public ExecutionRun continueRun(TaskScope scope, UUID oldRunId, UUID inputId, StagePlan first) {
        require(inputId != null && first != null, INVALID_INPUT);
        return tx(
                () -> {
                    Session old = lock(scope, oldRunId);
                    if (old.run.continuedByRunId() != null)
                        return run(scope, old.run.continuedByRunId());
                    require(oldRunId.equals(currentRun(scope)), INVALID_INPUT);
                    ExecutionRun next =
                            newRun(
                                    scope,
                                    Math.incrementExact(old.task.executionGeneration()),
                                    old.task.constraintRevision(),
                                    oldRunId);
                    old.run = old.run.linkSuccessor(next.id());
                    obsoleteEvents(old, false);
                    old.activeSince = null;
                    save(old);
                    insertRun(next, Goal.valueOf(old.row.goal()), initialLimits);
                    checked(mapper.pointRun(scope, next.id(), next.generation()));
                    enqueue(next, inputId, first);
                    checked(
                            mapper.insertStart(
                                    scope,
                                    inputId,
                                    json.hash(json.encode(List.of(oldRunId, first))),
                                    next.id()));
                    visible(scope, next.id(), null, "RUN_CONTINUED");
                    return next;
                });
    }

    /**
     * {@inheritDoc} 回答确认和新执行请求同事务；问题仍未解决时保留等待，不轮询模型。
     */
    @Override
    public ExecutionRun resume(
            TaskScope scope,
            UUID runId,
            DecisionAnswer answer,
            Set<UUID> resolved,
            StagePlan next,
            TravelStore.RevisionContent requirements) {
        return tx(
                () -> {
                    Session session = lock(scope, runId);
                    String hash =
                            json.hash(
                                    json.encode(
                                            java.util.Arrays.asList(
                                                    answer,
                                                    resolved.stream().sorted().toList(),
                                                    next,
                                                    requirements)));
                    var replay = mapper.answer(scope, runId, answer.id());
                    if (replay != null) {
                        require(hash.equals(replay.requestHash()), IDEMPOTENCY_CONFLICT);
                        return session.run;
                    }
                    require(
                            session.run.state() == ExecutionRun.State.WAITING_USER
                                    && runId.equals(currentRun(scope))
                                    && session.run.generation()
                                            == session.task.executionGeneration(),
                            INVALID_INPUT);
                    String body = mapper.decision(scope, runId, answer.decisionId());
                    require(body != null, NOT_FOUND);
                    var decision = json.decode(body, DecisionRequest.class);
                    long revision =
                            requirements == null
                                    ? session.task.constraintRevision()
                                    : Math.incrementExact(session.task.constraintRevision());
                    var updated =
                            decision.applyConfirmed(
                                    answer, taskValue(session.task), resolved, revision);
                    if (requirements != null) {
                        // 必须先用旧任务检查原问题及回答，再一次事务追加需求；不能先改版本而使合法回答自己过期。
                        var constraints =
                                new ConstraintRevision(
                                        scope,
                                        revision,
                                        requirements.requirements(),
                                        requirements.fixedArrangements());
                        checked(
                                tasks.insertRevision(
                                        scope,
                                        revision,
                                        json.encode(constraints),
                                        requirements.source(),
                                        requirements.difference()));
                        var old = session.task;
                        var row =
                                new TravelMapper.TaskRow(
                                        old.id(),
                                        old.userId(),
                                        old.title(),
                                        revision,
                                        old.executionGeneration(),
                                        old.currentVersionId(),
                                        old.pendingInputs(),
                                        old.eventSequence(),
                                        Math.incrementExact(old.lockVersion()));
                        checked(tasks.updateTask(row, old.lockVersion()));
                        session.run =
                                session.run.advanceConstraints(old.constraintRevision(), revision);
                        session.task = row;
                        if (mapper.staleDecisions(scope, runId, decision.id(), revision) > 0)
                            visible(scope, runId, null, "DECISIONS_STALE");
                    }
                    checked(
                            mapper.updateDecision(
                                    scope, runId, decision.id(), json.encode(updated)));
                    checked(
                            mapper.insertAnswer(
                                    scope,
                                    runId,
                                    answer.id(),
                                    decision.id(),
                                    hash,
                                    json.encode(answer.applied())));
                    if (updated.state() == DecisionRequest.State.ANSWERED
                            && mapper.openDecisions(scope, runId) == 0) {
                        session.run =
                                session.run.resumeAfterAnswer(session.task.constraintRevision());
                        enqueue(session.run, answer.id(), next);
                    }
                    save(session);
                    visible(scope, runId, null, "ANSWER_APPLIED");
                    return session.run;
                });
    }

    /**
     * {@inheritDoc} 额度恢复仅重新就绪原事件，原预算与尝试历史保持。
     */
    @Override
    public ExecutionRun resumeQuota(TaskScope scope, UUID runId, boolean confirmed) {
        return tx(
                () -> {
                    Session session = lock(scope, runId);
                    require(
                            runId.equals(currentRun(scope))
                                    && session.run.generation()
                                            == session.task.executionGeneration(),
                            INVALID_INPUT);
                    session.run = session.run.resumeQuota(confirmed);
                    for (var event : session.events)
                        if (event.status().equals("RETRY_WAIT"))
                            update(event, "READY", mapper.now(), null, event.fencingToken(), null);
                    save(session);
                    visible(scope, runId, null, "RUN_RESUMED");
                    return session.run;
                });
    }

    /**
     * {@inheritDoc} 到期副本再次锁内复核；先撤销 token，再交回原事件领取入口。
     */
    @Override
    public int recover(int limit) {
        batch(limit);
        int recovered = 0;
        for (var row : safe(() -> mapper.expired(limit))) {
            boolean changed =
                    tx(
                            () -> {
                                Session session =
                                        lock(
                                                new TaskScope(row.userId(), row.taskId()),
                                                row.runId());
                                var event = event(session, row.eventId());
                                Instant now = mapper.now();
                                if (!event.status().equals("LEASED")
                                        || now.isBefore(event.leaseUntil())) return false;
                                settle(session, now);
                                if (!current(session, event)) {
                                    obsolete(session);
                                    return true;
                                }
                                if (exhausted(session)) {
                                    pause(session, Failure.BUDGET);
                                    return true;
                                }
                                ExecutionStep step = step(session, event.stepId());
                                if (step.state() == ExecutionStep.State.COMPLETED) {
                                    update(
                                            event,
                                            "DONE",
                                            now,
                                            null,
                                            Math.incrementExact(event.fencingToken()),
                                            null);
                                    reconcile(session);
                                    return true;
                                }
                                if (step.attemptCount() >= maximumAttempts)
                                    terminate(session, event, step, false);
                                else retry(session, event, step, true);
                                return true;
                            });
            if (changed) recovered++;
        }
        return recovered;
    }

    /**
     * @param scope 归属
     * @param id 运行
     * @return 严格锁序内的最新事务视图
     */
    private Session lock(TaskScope scope, UUID id) {
        var task = taskLock(scope);
        var row = mapper.lockRun(scope, id);
        require(row != null, NOT_FOUND);
        var steps = new java.util.LinkedHashMap<UUID, EngineMapper.StepRow>();
        for (String value : mapper.lockSteps(scope, id)) {
            UUID step = UUID.fromString(value);
            steps.put(step, mapper.step(scope, id, step));
        }
        return new Session(
                scope,
                task,
                row,
                withBudget(json.decode(row.body(), ExecutionRun.class)),
                steps,
                mapper.lockEvents(scope, id));
    }

    /**
     * @param scope 已锁任务
     * @return 当前可提交运行；标量 UUID 显式读取文本避免 MyBatis 反射 JDK 内部字段
     */
    private UUID currentRun(TaskScope scope) {
        String value = mapper.currentRun(scope);
        return value == null ? null : UUID.fromString(value);
    }

    /**
     * @param scope 归属
     * @return 任务行；不存在和越权同类拒绝
     */
    private TravelMapper.TaskRow taskLock(TaskScope scope) {
        var row = tasks.lockTask(scope);
        require(row != null, NOT_FOUND);
        return row;
    }

    /**
     * @param row 任务状态
     * @return 领域状态，不改当前行程指针
     */
    private TravelTask taskValue(TravelMapper.TaskRow row) {
        return new TravelTask(
                new TaskScope(row.userId(), row.id()),
                row.constraintRevision(),
                row.executionGeneration(),
                row.currentVersionId(),
                json.pendingInputs(row.pendingInputs()));
    }

    /**
     * @param source 运行历史
     * @return 使用同事务权威计量重建
     */
    private ExecutionRun withBudget(ExecutionRun source) {
        var b = budgets.read(source.id());
        require(b != null, NOT_FOUND);
        return new ExecutionRun(
                source.id(),
                source.scope(),
                source.generation(),
                source.constraintRevision(),
                source.state(),
                new RunBudget(
                        b.modelCalls(),
                        b.toolCalls(),
                        b.repairRounds(),
                        Duration.ofMillis(b.activeMillis())),
                source.continuedFromRunId(),
                source.continuedByRunId(),
                source.failureKind());
    }

    /**
     * @param scope 归属
     * @param generation 代次
     * @param revision 需求
     * @param from 继续来源
     * @return 新预算运行
     */
    private ExecutionRun newRun(TaskScope scope, long generation, long revision, UUID from) {
        return new ExecutionRun(
                UUID.randomUUID(),
                scope,
                generation,
                revision,
                ExecutionRun.State.QUEUED,
                new RunBudget(0, 0, 0, Duration.ZERO),
                from,
                null,
                null);
    }

    /**
     * @param run 新运行
     * @param goal 用例目标
     * @param limits 原预算；建账和运行插入必须同一事务
     */
    private void insertRun(ExecutionRun run, Goal goal, RunLimits limits) {
        budgets.create(run.id());
        checked(
                mapper.insertRun(
                        run.scope(),
                        run.id(),
                        json.encode(run),
                        goal.name(),
                        json.encode(limits),
                        com.lawrence.travel.application.execution.ExecutionCorrelation.current()));
    }

    /**
     * @param session 当前事务
     * @param id 事件
     * @return 已锁权威行
     */
    private EngineMapper.EventRow event(Session session, UUID id) {
        return session.events.stream()
                .filter(e -> e.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new TravelStorageFailure(NOT_FOUND));
    }

    /**
     * @param session 当前事务
     * @param id 阶段
     * @return 已锁阶段状态
     */
    private ExecutionStep step(Session session, UUID id) {
        var row = session.steps.get(id);
        require(row != null, NOT_FOUND);
        return json.decode(row.body(), ExecutionStep.class);
    }

    /**
     * @param session 当前事务
     * @param id 阶段
     * @return 持久定义
     */
    private StagePlan definition(Session session, UUID id) {
        return json.decode(session.steps.get(id).definition(), StagePlan.class);
    }

    /**
     * @param row 数据库无锁提示
     * @return 可跨端口使用的提示
     */
    private Hint hint(EngineMapper.HintRow row) {
        return new Hint(
                new TaskScope(row.userId(), row.taskId()),
                row.runId(),
                row.stepId(),
                row.eventId(),
                row.type());
    }

    /**
     * @param event 当前权威
     * @param attempt 尝试
     * @return 执行资格副本
     */
    private ExecutionFence fence(EngineMapper.EventRow event, UUID attempt) {
        var scope = new TaskScope(event.userId(), event.taskId());
        return new ExecutionFence(
                scope,
                event.runId(),
                event.stepId(),
                attempt,
                event.expectedGeneration(),
                event.expectedConstraintRevision(),
                new ExecutionLease(
                        event.id(),
                        scope,
                        event.runId(),
                        event.stepId(),
                        event.leaseOwner(),
                        event.fencingToken(),
                        event.leaseUntil(),
                        OutboxState.LEASED));
    }

    /**
     * @param session 当前事务
     * @param event 事件
     * @return 运行与任务仍属于最新执行
     */
    private boolean current(Session session, EngineMapper.EventRow event) {
        return !session.run.state().terminal()
                && session.run.id().equals(currentRun(session.scope))
                && session.run.generation() == session.task.executionGeneration()
                && session.run.constraintRevision() == session.task.constraintRevision()
                && event.expectedGeneration() == session.run.generation()
                && event.expectedConstraintRevision() == session.run.constraintRevision();
    }

    /**
     * @param session 当前事务
     * @param fence 提交资格
     * @param event 权威事件
     * @return 完整 fence 是否成立
     */
    private boolean valid(Session session, ExecutionFence fence, EngineMapper.EventRow event) {
        if (!current(session, event)) return false;
        try {
            fence.requireCurrent(
                    taskValue(session.task),
                    session.run,
                    step(session, fence.stepId()),
                    new ExecutionLease(
                            event.id(),
                            session.scope,
                            event.runId(),
                            event.stepId(),
                            event.leaseOwner() == null ? "unclaimed" : event.leaseOwner(),
                            Math.max(1, event.fencingToken()),
                            event.leaseUntil() == null ? Instant.EPOCH : event.leaseUntil(),
                            OutboxState.valueOf(event.status())),
                    mapper.now());
            return true;
        } catch (DomainViolation stale) {
            return false;
        }
    }

    /**
     * @param session 事务
     * @param now DB 时间；并行区间只记一次，失联最多计到最后租约到期
     */
    private void settle(Session session, Instant now) {
        if (session.activeSince != null) {
            Instant last = mapper.latestLease(session.run.id());
            Instant end = last == null || now.isBefore(last) ? now : last;
            long millis = Math.max(0, Duration.between(session.activeSince, end).toMillis());
            if (millis > 0) checked(mapper.accrue(session.run.id(), millis));
            session.activeSince = last != null && now.isBefore(last) ? now : null;
            session.run = withBudget(session.run);
            save(session);
        }
    }

    /**
     * @param session 当前事务
     * @return 主动时间已耗尽；调用次数在每次真实请求预占时检查
     */
    private boolean exhausted(Session session) {
        return session.run
                        .budget()
                        .activeTime()
                        .compareTo(json.decode(session.row.limits(), RunLimits.class).activeTime())
                >= 0;
    }

    /**
     * @param session 当前事务；根据实际事件及问题收敛状态，不能留下无工作 RUNNING
     */
    private void reconcile(Session session) {
        if (session.run.state().terminal()) {
            session.activeSince = null;
            save(session);
            return;
        }
        int leased = mapper.leased(session.run.id());
        int pending = mapper.pending(session.run.id());
        if (leased == 0) {
            session.activeSince = null;
            if (session.run.state() == ExecutionRun.State.RUNNING) {
                if (pending > 0) session.run = session.run.queueAfterStage();
                else if (mapper.openDecisions(session.scope, session.run.id()) > 0)
                    session.run = session.run.waitForUser(true, false);
                else session.run = session.run.fail(ExecutionRun.FailureKind.RETRYABLE);
            }
        }
        save(session);
    }

    /**
     * @param session 当前事务
     * @param superseded 是否取代；先记账，再终止业务并撤销所有执行权
     */
    private void stop(Session session, boolean superseded) {
        settle(session, mapper.now());
        session.run = superseded ? session.run.supersede() : session.run.cancel();
        obsoleteEvents(session, superseded);
        session.activeSince = null;
        save(session);
        visible(
                session.scope,
                session.run.id(),
                null,
                superseded ? "RUN_SUPERSEDED" : "RUN_CANCELLED");
    }

    /**
     * @param session 当前事务；旧代次直接取代，终态仅清理残余请求
     */
    private void obsolete(Session session) {
        if (!session.run.state().terminal()) stop(session, true);
        else obsoleteEvents(session, true);
    }

    /**
     * @param session 当前事务
     * @param superseded 取代或取消；阶段／事件先前已按稳定顺序锁定
     */
    private void obsoleteEvents(Session session, boolean superseded) {
        for (UUID id : session.steps.keySet()) {
            ExecutionStep step = step(session, id);
            if (!step.state().terminal()) {
                updateStep(step.stop(superseded));
                // 只有 RUNNING 尝试需要首次结束；重试等待保留此前 FAILED／ABANDONED 审计，不能改写它。
                if (step.state() == ExecutionStep.State.RUNNING)
                    checked(mapper.finishAttempt(step.currentAttemptId(), "ABANDONED"));
            }
        }
        for (var e : session.events)
            if (!Set.of("DONE", "DEAD", "OBSOLETE").contains(e.status()))
                update(
                        e,
                        "OBSOLETE",
                        mapper.now(),
                        null,
                        Math.incrementExact(e.fencingToken()),
                        superseded ? "SUPERSEDED" : "CANCELLED");
    }

    /**
     * @param session 当前事务
     * @param failure 暂停原因；保留原阶段与事件，暂停不创建新预算
     */
    private void pause(Session session, Failure failure) {
        if (session.run.state() == ExecutionRun.State.QUEUED) session.run = session.run.start();
        for (UUID id : session.steps.keySet()) {
            ExecutionStep step = step(session, id);
            if (step.state() == ExecutionStep.State.RUNNING) {
                updateStep(step.finish(step.currentAttemptId(), ExecutionStep.State.RETRY_WAIT));
                checked(mapper.finishAttempt(step.currentAttemptId(), "ABANDONED"));
            }
        }
        for (var e : session.events)
            if (e.status().equals("LEASED"))
                update(
                        e,
                        "RETRY_WAIT",
                        mapper.now(),
                        null,
                        Math.incrementExact(e.fencingToken()),
                        failure.name());
        session.run =
                failure == Failure.BUDGET ? session.run.pauseBudget() : session.run.pauseQuota();
        session.activeSince = null;
        save(session);
        visible(session.scope, session.run.id(), null, "RUN_PAUSED_" + failure.name());
    }

    /**
     * @param session 当前事务
     * @param event 事件
     * @param step 阶段
     * @param abandoned 失联或普通错误；尝试数和预算不退回
     */
    private void retry(
            Session session, EngineMapper.EventRow event, ExecutionStep step, boolean abandoned) {
        updateStep(step.finish(step.currentAttemptId(), ExecutionStep.State.RETRY_WAIT));
        checked(mapper.finishAttempt(step.currentAttemptId(), abandoned ? "ABANDONED" : "FAILED"));
        update(
                event,
                "RETRY_WAIT",
                mapper.now().plusSeconds(Math.min(30, 1L << Math.min(5, step.attemptCount()))),
                null,
                Math.incrementExact(event.fencingToken()),
                abandoned ? "LEASE_EXPIRED" : "STAGE_RETRY");
        reconcile(session);
        visible(session.scope, session.run.id(), step.id(), "STAGE_RETRY");
    }

    /**
     * @param session 当前事务
     * @param event 事件
     * @param step 阶段
     * @param permanent 永久失败；当前策略所有阶段均为必要分支
     */
    private void terminate(
            Session session, EngineMapper.EventRow event, ExecutionStep step, boolean permanent) {
        if (step.state() == ExecutionStep.State.RUNNING) {
            updateStep(step.finish(step.currentAttemptId(), ExecutionStep.State.FAILED));
            checked(mapper.finishAttempt(step.currentAttemptId(), "FAILED"));
        } else if (!step.state().terminal() && step.attemptCount() > 0)
            updateStep(step.exhaustRetries());
        update(
                event,
                "DEAD",
                mapper.now(),
                null,
                Math.incrementExact(event.fencingToken()),
                permanent ? "PERMANENT_FAILURE" : "ATTEMPTS_EXHAUSTED");
        if (session.run.state() == ExecutionRun.State.QUEUED) session.run = session.run.start();
        session.run =
                session.run.fail(
                        permanent
                                ? ExecutionRun.FailureKind.PERMANENT
                                : ExecutionRun.FailureKind.RETRYABLE);
        // 刷新锁内状态，避免把刚完成的 FAILED 再按旧视图写为 CANCELLED。
        for (UUID id : session.steps.keySet())
            session.steps.put(id, mapper.step(session.scope, session.run.id(), id));
        session.events = mapper.lockEvents(session.scope, session.run.id());
        obsoleteEvents(session, false);
        session.activeSince = null;
        save(session);
        visible(session.scope, session.run.id(), step.id(), "RUN_FAILED");
    }

    /**
     * @param run 运行
     * @param cause 触发原因
     * @param plan 新阶段；检查依赖归属及同因果内容
     */
    private void enqueue(ExecutionRun run, UUID cause, StagePlan plan) {
        String input = objectJson(plan.input());
        StagePlan canonical =
                new StagePlan(plan.id(), plan.key(), plan.mode(), input, plan.dependencies());
        for (UUID dependency : canonical.dependencies())
            require(mapper.step(run.scope(), run.id(), dependency) != null, NOT_FOUND);
        String hash = json.hash(input);
        var existing =
                mapper.causal(
                        run.scope(),
                        run.id(),
                        cause,
                        plan.key().type().name(),
                        plan.key().branchKey());
        if (existing != null) {
            require(
                    existing.stepId().equals(plan.id()) && existing.payloadHash().equals(hash),
                    IDEMPOTENCY_CONFLICT);
            var old = mapper.step(run.scope(), run.id(), plan.id());
            require(
                    old != null && json.decode(old.definition(), StagePlan.class).equals(canonical),
                    IDEMPOTENCY_CONFLICT);
            return;
        }
        var step =
                new ExecutionStep(
                        plan.id(),
                        run.id(),
                        run.scope(),
                        plan.key(),
                        plan.mode(),
                        ExecutionStep.State.READY,
                        0,
                        null);
        checkedConflict(
                mapper.insertStep(
                        run.scope(),
                        run.id(),
                        step.id(),
                        json.encode(step),
                        json.encode(canonical)));
        var event =
                new EngineMapper.EventRow(
                        UUID.randomUUID(),
                        run.scope().userId(),
                        run.scope().taskId(),
                        run.id(),
                        step.id(),
                        cause,
                        plan.key().type().name(),
                        plan.key().branchKey(),
                        hash,
                        run.generation(),
                        run.constraintRevision(),
                        "READY",
                        mapper.now(),
                        0,
                        null,
                        null,
                        0,
                        null);
        checkedConflict(mapper.insertEvent(event));
    }

    /**
     * @param run 运行
     * @param cause 当前阶段
     * @param next 后续定义
     * @return 与实际数据库请求同语义的因果列表
     */
    private List<StageDispatch> dispatches(UUID run, UUID cause, List<StagePlan> next) {
        return next.stream()
                .map(
                        plan ->
                                new StageDispatch(
                                        new StageDispatch.Key(
                                                run,
                                                cause,
                                                plan.key().type().name(),
                                                plan.key().branchKey()),
                                        plan.id(),
                                        json.hash(objectJson(plan.input()))))
                .toList();
    }

    /**
     * @param input JSON 对象
     * @return 规范化正文；不允许任意标量输入或超大阶段载荷
     */
    private String objectJson(String input) {
        require(input != null, INVALID_INPUT);
        size(input);
        Object value = json.decode(input, Object.class);
        require(value instanceof Map<?, ?>, INVALID_INPUT);
        return json.encode(value);
    }

    /**
     * @param body 正文；正文不会根据大小自动转到文件
     */
    private static void size(String body) {
        require(
                body.length() <= 2 * 1024 * 1024
                        && body.getBytes(StandardCharsets.UTF_8).length <= 2 * 1024 * 1024,
                INVALID_INPUT);
    }

    /**
     * @param session 最新事务状态；预算已从权威账本刷新
     */
    private void save(Session session) {
        checked(
                mapper.updateRun(
                        session.scope,
                        session.run.id(),
                        json.encode(session.run),
                        session.activeSince));
    }

    /**
     * @param step 已验证领域新状态
     */
    private void updateStep(ExecutionStep step) {
        checked(mapper.updateStep(step.scope(), step.runId(), step.id(), json.encode(step)));
    }

    /**
     * @param old 原事件
     * @param status 新状态
     * @param available 可运行时间
     * @param owner 实例或空
     * @param token 新令牌
     * @param error 固定错误；非租约状态移除截止时间
     */
    private void update(
            EngineMapper.EventRow old,
            String status,
            Instant available,
            String owner,
            long token,
            String error) {
        checked(
                mapper.updateEvent(
                        copy(old, status, available, owner, null, token, old.attemptCount(), error),
                        old.fencingToken()));
    }

    /**
     * @param e 原事件
     * @param state 新状态
     * @param available 可运行时刻
     * @param owner 实例
     * @param until 截止
     * @param token 令牌
     * @param attempts 次数
     * @param error 固定分类
     * @return 新权威参数
     */
    private EngineMapper.EventRow copy(
            EngineMapper.EventRow e,
            String state,
            Instant available,
            String owner,
            Instant until,
            long token,
            int attempts,
            String error) {
        return new EngineMapper.EventRow(
                e.id(),
                e.userId(),
                e.taskId(),
                e.runId(),
                e.stepId(),
                e.causationId(),
                e.eventType(),
                e.branchKey(),
                e.payloadHash(),
                e.expectedGeneration(),
                e.expectedConstraintRevision(),
                state,
                available,
                attempts,
                owner,
                until,
                token,
                error);
    }

    /**
     * @param scope 归属
     * @param run 运行
     * @param step 阶段或空
     * @param type 固定事件类型；与业务状态同事务
     */
    private void visible(TaskScope scope, UUID run, UUID step, String type) {
        checked(mapper.event(scope, run, step, type));
    }

    /**
     * @param count 批量上限，拒绝无界扫描
     */
    private static void batch(int count) {
        require(count > 0 && count <= 100, INVALID_INPUT);
    }

    /**
     * @param count 更新行数；零行不能冒充成功
     */
    private static void checked(int count) {
        require(count == 1, INVALID_INPUT);
    }

    /**
     * @param count 唯一插入行数；冲突不能覆盖逻辑阶段
     */
    private static void checkedConflict(int count) {
        require(count == 1, IDEMPOTENCY_CONFLICT);
    }

    /**
     * @param value 前置条件
     * @param code 固定分类
     */
    private static void require(boolean value, TravelStorageFailure.Code code) {
        if (!value) throw new TravelStorageFailure(code);
    }

    /**
     * @param work 短事务
     * @param <T> 类型
     * @return 提交结果，失败全回滚
     */
    private <T> T tx(Supplier<T> work) {
        return safe(() -> transaction.execute(status -> work.get()));
    }

    /**
     * @param work 数据库操作
     * @param <T> 类型
     * @return 结果，数据库错误不传播私有 SQL 及参数
     */
    private <T> T safe(Supplier<T> work) {
        try {
            return work.get();
        } catch (DataAccessException
                | org.springframework.transaction.TransactionException failure) {
            throw new TravelStorageFailure(DEPENDENCY_UNAVAILABLE);
        }
    }

    /**
     * 单事务可变视图，不跨线程或跨事务缓存执行资格。
     */
    private static final class Session {
        private final TaskScope scope;
        private TravelMapper.TaskRow task;
        private final EngineMapper.RunRow row;
        private ExecutionRun run;
        private Instant activeSince;
        private final Map<UUID, EngineMapper.StepRow> steps;
        private List<EngineMapper.EventRow> events;

        /**
         * @param scope 归属
         * @param task 最新任务
         * @param row 运行配置
         * @param run 最新预算状态
         * @param steps 已锁阶段
         * @param events 已锁事件
         */
        private Session(
                TaskScope scope,
                TravelMapper.TaskRow task,
                EngineMapper.RunRow row,
                ExecutionRun run,
                Map<UUID, EngineMapper.StepRow> steps,
                List<EngineMapper.EventRow> events) {
            this.scope = scope;
            this.task = task;
            this.row = row;
            this.run = run;
            activeSince = row.activeSince();
            this.steps = steps;
            this.events = events;
        }
    }
}
