package com.lawrence.travel.domain.execution;

import com.lawrence.travel.domain.rule.DomainViolation;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.domain.travel.TravelTask;
import java.time.Instant;
import java.util.UUID;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.INVALID_INPUT;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.ILLEGAL_TRANSITION;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.ATTEMPT_MISMATCH;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.STALE_GENERATION;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.STALE_REVISION;

/**
 * 阶段提交前置条件；只有事务内读到的当前对象可作为判定输入，不能凭旧内存检查写库。
 * @param scope 受信任务归属
 * @param runId 父运行 ID
 * @param stepId 逻辑阶段 ID
 * @param attemptId 本次尝试 ID
 * @param generation 输入执行代次
 * @param constraintRevision 输入需求版本
 * @param claim 领取时的执行权副本
 */
public record ExecutionFence(TaskScope scope, UUID runId, UUID stepId, UUID attemptId, long generation,
                             long constraintRevision, ExecutionLease claim) {
    /** 验证必需关联，不接受空提交资格。 */
    public ExecutionFence {
        DomainViolation.require(scope != null && runId != null && stepId != null && attemptId != null
                && generation > 0 && constraintRevision > 0 && claim != null, INVALID_INPUT);
        scope.requireSame(claim.scope());
        DomainViolation.require(runId.equals(claim.runId()) && stepId.equals(claim.stepId()), INVALID_INPUT);
    }

    /**
     * 按当前任务、运行、阶段和权威租约检查，纯函数不取得任何数据库锁。
     * @param task 当前任务行值
     * @param run 当前运行行值
     * @param step 当前阶段行值
     * @param authority 当前 Outbox 租约行值
     * @param databaseNow 当前事务数据库时间
     */
    public void requireCurrent(TravelTask task, ExecutionRun run, ExecutionStep step,
                               ExecutionLease authority, Instant databaseNow) {
        DomainViolation.require(task != null && run != null && step != null && authority != null, INVALID_INPUT);
        scope.requireSame(task.scope());
        scope.requireSame(run.scope());
        scope.requireSame(step.scope());
        DomainViolation.require(runId.equals(run.id()) && runId.equals(step.runId()) && stepId.equals(step.id()), INVALID_INPUT);
        DomainViolation.require(generation == task.executionGeneration() && generation == run.generation(), STALE_GENERATION);
        DomainViolation.require(constraintRevision == task.constraintRevision() && constraintRevision == run.constraintRevision(), STALE_REVISION);
        DomainViolation.require(run.state() == ExecutionRun.State.RUNNING && step.state() == ExecutionStep.State.RUNNING, ILLEGAL_TRANSITION);
        DomainViolation.require(attemptId.equals(step.currentAttemptId()), ATTEMPT_MISMATCH);
        authority.requireCurrent(claim, databaseNow);
    }
}
