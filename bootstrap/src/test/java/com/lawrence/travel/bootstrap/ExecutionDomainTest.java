package com.lawrence.travel.bootstrap;

import com.lawrence.travel.domain.execution.ExecutionRun;
import com.lawrence.travel.domain.execution.ExecutionStep;
import com.lawrence.travel.domain.execution.ExecutionLease;
import com.lawrence.travel.domain.execution.ExecutionFence;
import com.lawrence.travel.domain.execution.OutboxState;
import com.lawrence.travel.domain.execution.StageDispatch;
import com.lawrence.travel.domain.execution.StageType;
import com.lawrence.travel.domain.execution.PlanningMode;
import com.lawrence.travel.domain.execution.StepAttempt;
import com.lawrence.travel.domain.execution.control.RunBudget;
import com.lawrence.travel.domain.itinerary.CandidateItinerary;
import com.lawrence.travel.domain.itinerary.ItineraryVersion;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.domain.travel.TravelTask;
import com.lawrence.travel.application.execution.StageCommitCommand;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 执行身份、状态和提交前置规则；纯函数不代表真实事务或恢复已实现。 */
class ExecutionDomainTest {
    private final TaskScope scope = new TaskScope(UUID.randomUUID(), UUID.randomUUID());
    private final RunBudget budget = new RunBudget(3, 4, 1, Duration.ofSeconds(20));
    private final Instant now = Instant.parse("2026-10-16T01:00:00Z");

    /**
     * @return 有已消耗预算的排队运行，便于验证恢复不能清零
     */
    private ExecutionRun run() {
        return new ExecutionRun(
                UUID.randomUUID(),
                scope,
                1,
                1,
                ExecutionRun.State.QUEUED,
                budget,
                null,
                null,
                null);
    }

    /** 等待回答与额度恢复保留同一预算，独立工作尚存时不进入全运行等待。 */
    @Test
    void waitingAndQuotaResumptionKeepOriginalBudget() {
        var running = run().start();
        assertThat(running.waitForUser(true, true)).isSameAs(running);
        var resumed = running.waitForUser(true, false).resumeAfterAnswer(2);
        assertThat(resumed.id()).isEqualTo(running.id());
        assertThat(resumed.constraintRevision()).isEqualTo(2);
        assertThat(resumed.budget()).isEqualTo(budget);
        var paused = resumed.start().pauseQuota();
        assertThatThrownBy(() -> paused.resumeQuota(false)).hasMessage("ILLEGAL_TRANSITION");
        assertThat(paused.resumeQuota(true).budget()).isEqualTo(budget);
        var rebound = running.advanceConstraints(1, 2);
        assertThat(rebound.state()).isEqualTo(ExecutionRun.State.RUNNING);
        assertThat(rebound.budget()).isEqualTo(budget);
        assertThat(rebound.generation()).isEqualTo(running.generation());
        assertThatThrownBy(() -> running.account(new RunBudget(0, 0, 0, Duration.ZERO)))
                .hasMessage("INVALID_INPUT");
    }

    /** 排队、等待与暂停均可取消或取代，任何终态都不能被迟到回调重新启动。 */
    @Test
    void allInactiveStatesCanStopAndTerminalStatesCannotRevive() {
        var base = run();
        for (var current :
                List.of(
                        base,
                        base.start(),
                        base.start().waitForUser(true, false),
                        base.start().waitForAcceptance(UUID.randomUUID()),
                        base.start().pauseBudget(),
                        base.start().pauseQuota())) {
            assertThat(current.cancel().state()).isEqualTo(ExecutionRun.State.CANCELLED);
            assertThat(current.supersede().state()).isEqualTo(ExecutionRun.State.SUPERSEDED);
        }
        for (var terminal :
                List.of(
                        base.start().completeConsultation(),
                        base.start().fail(ExecutionRun.FailureKind.PERMANENT),
                        base.cancel(),
                        base.supersede())) {
            assertThat(terminal.state().terminal()).isTrue();
            assertThatThrownBy(terminal::start).hasMessage("ILLEGAL_TRANSITION");
            assertThatThrownBy(terminal::queueAfterStage).hasMessage("ILLEGAL_TRANSITION");
        }
    }

    /** 预算耗尽或取消需唯一直接新运行；SUPERSEDED 不可继续原目标。 */
    @Test
    void continuationCannotResetOrForkOldRun() {
        var paused = run().start().pauseBudget();
        UUID next = UUID.randomUUID();
        var linked = paused.linkSuccessor(next);
        assertThat(linked.linkSuccessor(next)).isEqualTo(linked);
        assertThat(linked.budget()).isEqualTo(budget);
        assertThatThrownBy(() -> linked.linkSuccessor(UUID.randomUUID()))
                .hasMessage("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(paused::start).hasMessage("ILLEGAL_TRANSITION");
        assertThatThrownBy(() -> run().supersede().linkSuccessor(next))
                .hasMessage("ILLEGAL_TRANSITION");
        assertThatThrownBy(
                        () ->
                                run().start()
                                        .fail(ExecutionRun.FailureKind.PERMANENT)
                                        .linkSuccessor(next))
                .hasMessage("ILLEGAL_TRANSITION");
        assertThat(
                        run().start()
                                .fail(ExecutionRun.FailureKind.RETRYABLE)
                                .linkSuccessor(next)
                                .continuedByRunId())
                .isEqualTo(next);
        assertThatThrownBy(() -> run().start().completeAfterAcceptance(UUID.randomUUID()))
                .hasMessage("ILLEGAL_TRANSITION");
        assertThat(
                        run().start()
                                .waitForAcceptance(UUID.randomUUID())
                                .completeAfterAcceptance(UUID.randomUUID())
                                .state())
                .isEqualTo(ExecutionRun.State.SUCCEEDED);
    }

    /** 重试保留 stepId 及逻辑键但新建 attemptId，PLAN 模式约束与分支身份明确。 */
    @Test
    void stepRetrySeparatesLogicalIdentityFromAttemptIdentity() {
        UUID runId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        var original =
                new ExecutionStep(
                        UUID.randomUUID(),
                        runId,
                        scope,
                        new ExecutionStep.Key(StageType.PLAN, 1, "main"),
                        PlanningMode.REPLAN,
                        ExecutionStep.State.READY,
                        0,
                        null);
        var retry =
                original.begin(first).finish(first, ExecutionStep.State.RETRY_WAIT).retryReady();
        UUID second = UUID.randomUUID();
        var next = retry.begin(second);
        assertThat(next.id()).isEqualTo(original.id());
        assertThat(next.key()).isEqualTo(original.key());
        assertThat(next.attemptCount()).isEqualTo(2);
        assertThatThrownBy(() -> retry.begin(first)).hasMessage("ATTEMPT_MISMATCH");
        assertThatThrownBy(() -> next.finish(first, ExecutionStep.State.COMPLETED))
                .hasMessage("ATTEMPT_MISMATCH");
        assertThatThrownBy(
                        () ->
                                new ExecutionStep(
                                        UUID.randomUUID(),
                                        runId,
                                        scope,
                                        original.key(),
                                        null,
                                        ExecutionStep.State.READY,
                                        0,
                                        null))
                .hasMessage("INVALID_INPUT");
        assertThatThrownBy(
                        () ->
                                new ExecutionStep(
                                        UUID.randomUUID(),
                                        runId,
                                        scope,
                                        original.key(),
                                        PlanningMode.INITIAL,
                                        ExecutionStep.State.COMPLETED,
                                        0,
                                        null))
                .hasMessage("INVALID_INPUT");
        assertThat(new ExecutionStep.Key(StageType.RESEARCH, 2, "a"))
                .isNotEqualTo(new ExecutionStep.Key(StageType.RESEARCH, 2, "b"));
    }

    /** 尝试审计结束不能变回运行，Outbox 完成、失效或死信也不能重新领取。 */
    @Test
    void attemptsAndEventsKeepTerminalHistory() {
        var attempt =
                new StepAttempt(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1,
                        1,
                        StepAttempt.State.RUNNING);
        var failed = attempt.finish(StepAttempt.State.FAILED);
        assertThatThrownBy(() -> failed.finish(StepAttempt.State.COMPLETED))
                .hasMessage("ILLEGAL_TRANSITION");
        assertThat(OutboxState.LEASED.transitionTo(OutboxState.READY)).isEqualTo(OutboxState.READY);
        for (var terminal : List.of(OutboxState.DONE, OutboxState.OBSOLETE, OutboxState.DEAD)) {
            assertThatThrownBy(() -> terminal.transitionTo(OutboxState.LEASED))
                    .hasMessage("ILLEGAL_TRANSITION");
        }
    }

    /**
     * @return 全部属于同一任务、运行、阶段和尝试的当前提交上下文
     */
    private Fixture fixture() {
        var task = new TravelTask(scope, 1, 1, null, Map.of());
        var run = run().start();
        var step =
                new ExecutionStep(
                                UUID.randomUUID(),
                                run.id(),
                                scope,
                                new ExecutionStep.Key(StageType.RESEARCH, 0, "main"),
                                null,
                                ExecutionStep.State.READY,
                                0,
                                null)
                        .begin(UUID.randomUUID());
        var lease =
                new ExecutionLease(
                        UUID.randomUUID(),
                        scope,
                        run.id(),
                        step.id(),
                        "instance-a",
                        5,
                        now.plusSeconds(60),
                        OutboxState.LEASED);
        var fence =
                new ExecutionFence(
                        scope, run.id(), step.id(), step.currentAttemptId(), 1, 1, lease);
        return new Fixture(task, run, step, lease, fence);
    }

    /**
     * 纯测试对象，不代表真实锁定读取。
     * @param task 当前任务
     * @param run 当前运行
     * @param step 当前阶段
     * @param lease 权威租约
     * @param fence 领取副本
     */
    private record Fixture(
            TravelTask task,
            ExecutionRun run,
            ExecutionStep step,
            ExecutionLease lease,
            ExecutionFence fence) {}

    /** 过期但尚未重新领取也必须拒绝；新 token、owner 或非 LEASED 状态均不能提交。 */
    @Test
    void fenceRejectsExpiredAndReplacedAuthority() {
        var f = fixture();
        f.fence.requireCurrent(f.task, f.run, f.step, f.lease, now);
        assertThatThrownBy(
                        () ->
                                f.fence.requireCurrent(
                                        f.task, f.run, f.step, f.lease, now.plusSeconds(60)))
                .hasMessage("STALE_LEASE");
        for (var invalid :
                List.of(
                        new ExecutionLease(
                                f.lease.eventId(),
                                scope,
                                f.run.id(),
                                f.step.id(),
                                "instance-b",
                                6,
                                now.plusSeconds(60),
                                OutboxState.LEASED),
                        new ExecutionLease(
                                f.lease.eventId(),
                                scope,
                                f.run.id(),
                                f.step.id(),
                                "instance-a",
                                5,
                                now.plusSeconds(60),
                                OutboxState.DONE),
                        new ExecutionLease(
                                f.lease.eventId(),
                                scope,
                                f.run.id(),
                                UUID.randomUUID(),
                                "instance-a",
                                5,
                                now.plusSeconds(60),
                                OutboxState.LEASED))) {
            assertThatThrownBy(() -> f.fence.requireCurrent(f.task, f.run, f.step, invalid, now))
                    .hasMessage("STALE_LEASE");
        }
        var renewed =
                new ExecutionLease(
                        f.lease.eventId(),
                        scope,
                        f.run.id(),
                        f.step.id(),
                        "instance-a",
                        5,
                        now.plusSeconds(120),
                        OutboxState.LEASED);
        f.fence.requireCurrent(f.task, f.run, f.step, renewed, now.plusSeconds(70));
    }

    /** 新需求、新代次、其他用户、停止运行和旧尝试均被拒绝，不能只检查租约。 */
    @Test
    void fenceChecksAllBusinessBindings() {
        var f = fixture();
        assertThatThrownBy(
                        () ->
                                f.fence.requireCurrent(
                                        f.task.fenceExecution(), f.run, f.step, f.lease, now))
                .hasMessage("STALE_GENERATION");
        var changed = new TravelTask(scope, 2, 1, null, Map.of());
        assertThatThrownBy(() -> f.fence.requireCurrent(changed, f.run, f.step, f.lease, now))
                .hasMessage("STALE_REVISION");
        var foreign =
                new TravelTask(
                        new TaskScope(UUID.randomUUID(), scope.taskId()), 1, 1, null, Map.of());
        assertThatThrownBy(() -> f.fence.requireCurrent(foreign, f.run, f.step, f.lease, now))
                .hasMessage("SCOPE_MISMATCH");
        assertThatThrownBy(
                        () -> f.fence.requireCurrent(f.task, f.run.cancel(), f.step, f.lease, now))
                .hasMessage("ILLEGAL_TRANSITION");
        var newer =
                f.step.finish(f.step.currentAttemptId(), ExecutionStep.State.RETRY_WAIT)
                        .retryReady()
                        .begin(UUID.randomUUID());
        assertThatThrownBy(() -> f.fence.requireCurrent(f.task, f.run, newer, f.lease, now))
                .hasMessage("ATTEMPT_MISMATCH");
    }

    /** 同一原因重投不得覆盖不同目标或输入；后继绑定 stepId 而非阶段类型。 */
    @Test
    void dispatchAndCommitContractsBindCausationAndPayload() {
        var f = fixture();
        var key = new StageDispatch.Key(f.run.id(), f.step.id(), "RUN_STAGE", "main");
        var request = new StageDispatch(key, UUID.randomUUID(), "1".repeat(64));
        request.requireSameReplay(new StageDispatch(key, request.stepId(), request.payloadHash()));
        assertThatThrownBy(
                        () ->
                                request.requireSameReplay(
                                        new StageDispatch(key, request.stepId(), "2".repeat(64))))
                .hasMessage("IDEMPOTENCY_CONFLICT");
        assertThat(
                        new StageCommitCommand(
                                        f.fence,
                                        UUID.randomUUID(),
                                        "a".repeat(64),
                                        List.of(request))
                                .nextStages())
                .hasSize(1);
        assertThatThrownBy(
                        () ->
                                new StageCommitCommand(
                                        f.fence,
                                        UUID.randomUUID(),
                                        "a".repeat(64),
                                        List.of(request, request)))
                .hasMessage("INVALID_INPUT");
        var duplicateTarget =
                new StageDispatch(
                        new StageDispatch.Key(f.run.id(), f.step.id(), "RUN_STAGE", "branch-b"),
                        request.stepId(),
                        request.payloadHash());
        assertThatThrownBy(
                        () ->
                                new StageCommitCommand(
                                        f.fence,
                                        UUID.randomUUID(),
                                        "a".repeat(64),
                                        List.of(request, duplicateTarget)))
                .hasMessage("INVALID_INPUT");
        var wrong =
                new StageDispatch(
                        new StageDispatch.Key(f.run.id(), UUID.randomUUID(), "RUN_STAGE", "main"),
                        UUID.randomUUID(),
                        "1".repeat(64));
        assertThatThrownBy(
                        () ->
                                new StageCommitCommand(
                                        f.fence, UUID.randomUUID(), "a".repeat(64), List.of(wrong)))
                .hasMessage("INVALID_INPUT");
    }

    /** 候选创建不切换生效指针，取消或代次变化后即使内容相同也不能沿用来源资格。 */
    @Test
    void candidateIdentityDoesNotImplyAcceptance() {
        var f = fixture();
        var snapshot =
                new ItineraryVersion(
                        UUID.randomUUID(), scope, 1, "a".repeat(64), 1, null, Set.of(), null);
        var candidate = new CandidateItinerary(UUID.randomUUID(), snapshot, f.run.id(), 1);
        candidate.requireCurrent(f.task, f.run);
        assertThat(f.task.currentVersionId()).isNull();
        assertThatThrownBy(() -> candidate.requireCurrent(f.task, f.run.cancel()))
                .hasMessage("ILLEGAL_TRANSITION");
        assertThatThrownBy(() -> candidate.requireCurrent(f.task.fenceExecution(), f.run))
                .hasMessage("STALE_GENERATION");
    }
}
