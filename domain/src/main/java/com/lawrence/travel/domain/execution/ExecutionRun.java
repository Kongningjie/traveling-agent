package com.lawrence.travel.domain.execution;

import com.lawrence.travel.domain.execution.control.RunBudget;
import com.lawrence.travel.domain.rule.DomainViolation;
import com.lawrence.travel.domain.travel.TaskScope;
import java.util.UUID;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.INVALID_INPUT;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.ILLEGAL_TRANSITION;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.IDEMPOTENCY_CONFLICT;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.STALE_REVISION;

/**
 * 一次目标执行的不可变生命周期，不负责领取、事务、计时或执行线程。
 * @param id 运行 ID
 * @param scope 所属用户及任务
 * @param generation 绑定执行代次
 * @param constraintRevision 绑定需求版本
 * @param state 生命周期状态
 * @param budget 原运行共享消耗，恢复与普通回答不能清零
 * @param continuedFromRunId 显式继续来源，可空
 * @param continuedByRunId 唯一直接后继，可空
 * @param failureKind FAILED 时必填的失败分类，其他状态为空
 */
public record ExecutionRun(
        UUID id,
        TaskScope scope,
        long generation,
        long constraintRevision,
        State state,
        RunBudget budget,
        UUID continuedFromRunId,
        UUID continuedByRunId,
        FailureKind failureKind) {
    /** 运行状态与业务任务、阶段状态分开。 */
    public enum State {
        /** 可等待领取。 */
        QUEUED,
        /** 仍有活动阶段或分支。 */
        RUNNING,
        /** 没有可推进工作，等待用户。 */
        WAITING_USER,
        /** 有合格候选，等待单独接受事务。 */
        WAITING_ACCEPTANCE,
        /** 原预算耗尽，显式继续创建新运行。 */
        PAUSED_BUDGET,
        /** 免费条件不满足，不能自行恢复。 */
        PAUSED_QUOTA,
        /** 已完成咨询或已接受行程。 */
        SUCCEEDED,
        /** 当前运行失败，不能复活。 */
        FAILED,
        /** 用户取消终态。 */
        CANCELLED,
        /** 新修改取代终态。 */
        SUPERSEDED;

        /**
         * @return 是否禁止再次运行或提交业务结果
         */
        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == SUPERSEDED;
        }
    }

    /** 明确失败是否允许创建新运行，不将所有失败一概视为可重试。 */
    public enum FailureKind {
        /** 可显式继续。 */
        RETRYABLE,
        /** 需先解决原因，不直接继续。 */
        PERMANENT
    }

    /** 验证稳定归属、版本、预算及继续关系，禁止自身形成后继。 */
    public ExecutionRun {
        DomainViolation.require(
                id != null
                        && scope != null
                        && generation > 0
                        && constraintRevision > 0
                        && state != null
                        && budget != null
                        && !id.equals(continuedFromRunId)
                        && !id.equals(continuedByRunId)
                        && ((state == State.FAILED) == (failureKind != null)),
                INVALID_INPUT);
    }

    /**
     * @return 领取后状态；实际租约领取和预算检查仍由应用事务完成
     */
    public ExecutionRun start() {
        return change(State.QUEUED, State.RUNNING);
    }

    /**
     * @return 阶段结束且没有活动分支时排队的状态；调用者负责判断分支汇合
     */
    public ExecutionRun queueAfterStage() {
        return change(State.RUNNING, State.QUEUED);
    }

    /**
     * 只在全运行无法推进时等待，问题卡片存在本身不阻断独立调查。
     * @param hasPendingDecision 已保存有效决策请求
     * @param hasIndependentWork 是否还有不依赖回答的可推进工作
     * @return 等待状态或仍运行的原状态
     */
    public ExecutionRun waitForUser(boolean hasPendingDecision, boolean hasIndependentWork) {
        DomainViolation.require(state == State.RUNNING && hasPendingDecision, ILLEGAL_TRANSITION);
        return hasIndependentWork ? this : change(State.RUNNING, State.WAITING_USER);
    }

    /**
     * 已验证回答继续同一运行，不创建新预算；部分回答的版本推进由应用在同一事务协调。
     * @param nextConstraintRevision 当前有效需求版本，不能倒退
     * @return 排队的同一运行
     */
    public ExecutionRun resumeAfterAnswer(long nextConstraintRevision) {
        DomainViolation.require(state == State.WAITING_USER, ILLEGAL_TRANSITION);
        DomainViolation.require(nextConstraintRevision >= constraintRevision, STALE_REVISION);
        return new ExecutionRun(
                id,
                scope,
                generation,
                nextConstraintRevision,
                State.QUEUED,
                budget,
                continuedFromRunId,
                continuedByRunId,
                failureKind);
    }

    /**
     * 原问题范围内的部分回答更新需求绑定，独立调查尚在运行时不强制全局暂停。
     * @param expectedRevision 更新前需求版本
     * @param nextRevision 同事务保存的更新版本
     * @return 保留状态、预算及代次的运行
     */
    public ExecutionRun advanceConstraints(long expectedRevision, long nextRevision) {
        DomainViolation.require(
                state == State.RUNNING || state == State.WAITING_USER || state == State.QUEUED,
                ILLEGAL_TRANSITION);
        DomainViolation.require(
                expectedRevision == constraintRevision && nextRevision > constraintRevision,
                STALE_REVISION);
        return new ExecutionRun(
                id,
                scope,
                generation,
                nextRevision,
                state,
                budget,
                continuedFromRunId,
                continuedByRunId,
                failureKind);
    }

    /**
     * 通过应用候选门槛并展示后等待接受，本方法不替代报告与 Schema 校验。
     * @param eligibleCandidateId 已被应用确认可接受的候选 ID，不可为问题草案
     * @return 等待接受状态
     */
    public ExecutionRun waitForAcceptance(UUID eligibleCandidateId) {
        DomainViolation.require(eligibleCandidateId != null, INVALID_INPUT);
        return change(State.RUNNING, State.WAITING_ACCEPTANCE);
    }

    /**
     * @return 原预算内重校验的排队状态；接受意图不能自动沿用
     */
    public ExecutionRun revalidateCandidate() {
        return change(State.WAITING_ACCEPTANCE, State.QUEUED);
    }

    /**
     * @return 免费资源不足的暂停状态
     */
    public ExecutionRun pauseQuota() {
        return change(State.RUNNING, State.PAUSED_QUOTA);
    }

    /**
     * 额度暂停只可显式恢复，不重置消耗或代次。
     * @param freeConditionsConfirmed 应用已核实可继续，不能由模型自行判定
     * @return 排队状态
     */
    public ExecutionRun resumeQuota(boolean freeConditionsConfirmed) {
        DomainViolation.require(freeConditionsConfirmed, ILLEGAL_TRANSITION);
        return change(State.PAUSED_QUOTA, State.QUEUED);
    }

    /**
     * @return 主动执行或等待重校验时预算耗尽的暂停状态，不能在原运行清零重启
     */
    public ExecutionRun pauseBudget() {
        DomainViolation.require(
                state == State.RUNNING || state == State.WAITING_ACCEPTANCE, ILLEGAL_TRANSITION);
        return copy(State.PAUSED_BUDGET, budget, continuedByRunId);
    }

    /**
     * @return 无新增行程的纯咨询结束状态，不代表已生成或接受行程
     */
    public ExecutionRun completeConsultation() {
        return change(State.RUNNING, State.SUCCEEDED);
    }

    /**
     * 行程接受事务成功后完成运行；不能从 RUNNING 直接跳过用户接受。
     * @param acceptanceId 已保存的接受记录 ID，应用负责完整报告及归属门槛
     * @return 成功终态
     */
    public ExecutionRun completeAfterAcceptance(UUID acceptanceId) {
        DomainViolation.require(acceptanceId != null, INVALID_INPUT);
        return change(State.WAITING_ACCEPTANCE, State.SUCCEEDED);
    }

    /**
     * 保存失败分类，区别可重试故障与永久原因。
     * @param kind 应用确定的非空失败分类
     * @return 原运行失败终态，绝不重置后复活
     */
    public ExecutionRun fail(FailureKind kind) {
        DomainViolation.require(state == State.RUNNING, ILLEGAL_TRANSITION);
        DomainViolation.require(kind != null, INVALID_INPUT);
        return new ExecutionRun(
                id,
                scope,
                generation,
                constraintRevision,
                State.FAILED,
                budget,
                continuedFromRunId,
                continuedByRunId,
                kind);
    }

    /**
     * @return 任意非终态的取消结果；重复取消幂等，终态不可复活
     */
    public ExecutionRun cancel() {
        return stop(State.CANCELLED);
    }

    /**
     * @return 任意非终态被新修改取代的结果；重复取代幂等
     */
    public ExecutionRun supersede() {
        return stop(State.SUPERSEDED);
    }

    /**
     * 已确认消耗只能累加，不能在等待或恢复时清零。
     * @param next 新共享预算消耗
     * @return 状态不变的新运行
     */
    public ExecutionRun account(RunBudget next) {
        DomainViolation.require(!state.terminal(), ILLEGAL_TRANSITION);
        DomainViolation.require(
                next != null
                        && next.modelCalls() >= budget.modelCalls()
                        && next.toolCalls() >= budget.toolCalls()
                        && next.repairRounds() >= budget.repairRounds()
                        && next.activeTime().compareTo(budget.activeTime()) >= 0,
                INVALID_INPUT);
        return copy(state, next, continuedByRunId);
    }

    /**
     * 预算暂停、取消或失败只允许唯一显式直接后继；数据库须再加唯一约束。
     * @param successorId 新运行 ID
     * @return 建立后继关系的原运行；同一后继重放幂等
     */
    public ExecutionRun linkSuccessor(UUID successorId) {
        DomainViolation.require(successorId != null && !id.equals(successorId), INVALID_INPUT);
        DomainViolation.require(
                state == State.PAUSED_BUDGET
                        || state == State.CANCELLED
                        || (state == State.FAILED && failureKind == FailureKind.RETRYABLE),
                ILLEGAL_TRANSITION);
        DomainViolation.require(
                continuedByRunId == null || continuedByRunId.equals(successorId),
                IDEMPOTENCY_CONFLICT);
        return copy(state, budget, successorId);
    }

    /**
     * 单一源状态转换，所有对象保持不可变。
     * @param expected 合法来源
     * @param target 目标
     * @return 转换后的运行
     */
    private ExecutionRun change(State expected, State target) {
        DomainViolation.require(state == expected, ILLEGAL_TRANSITION);
        return copy(target, budget, continuedByRunId);
    }

    /**
     * 取消／取代只作用于非终态。
     * @param target 停止终态
     * @return 原对象或新停止状态
     */
    private ExecutionRun stop(State target) {
        if (state == target) return this;
        DomainViolation.require(!state.terminal(), ILLEGAL_TRANSITION);
        return copy(target, budget, continuedByRunId);
    }

    /**
     * 保持身份及需求绑定不变复制运行。
     * @param nextState 新状态
     * @param nextBudget 新消耗
     * @param successor 后继 ID
     * @return 新运行
     */
    private ExecutionRun copy(State nextState, RunBudget nextBudget, UUID successor) {
        return new ExecutionRun(
                id,
                scope,
                generation,
                constraintRevision,
                nextState,
                nextBudget,
                continuedFromRunId,
                successor,
                failureKind);
    }
}
