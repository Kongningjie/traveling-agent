package com.lawrence.travel.domain.execution;

import com.lawrence.travel.domain.rule.DomainViolation;
import com.lawrence.travel.domain.travel.TaskScope;
import java.util.UUID;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.INVALID_INPUT;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.ILLEGAL_TRANSITION;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.ATTEMPT_MISMATCH;

/**
 * 逻辑阶段实例；重试保留身份，新补查或修订另建实例，不裁定租约权威。
 * @param id 逻辑 stepId
 * @param runId 父运行
 * @param scope 归属
 * @param key 含类型、轮次与分支的逻辑键
 * @param mode 仅 PLAN 必须指定 INITIAL／REPLAN
 * @param state 阶段状态
 * @param attemptCount 已开始尝试数
 * @param currentAttemptId 最近尝试 ID，首次执行前为空
 */
public record ExecutionStep(UUID id, UUID runId, TaskScope scope, Key key, PlanningMode mode,
                            State state, int attemptCount, UUID currentAttemptId) {
    /** 阶段状态独立于运行和 Outbox。 */
    public enum State {
        /** 已具备前置条件。 */ READY,
        /** 当前尝试正在执行。 */ RUNNING,
        /** 产物已提交。 */ COMPLETED,
        /** 有界重试等待。 */ RETRY_WAIT,
        /** 阶段失败。 */ FAILED,
        /** 所属运行取消。 */ CANCELLED,
        /** 所属代次失效。 */ SUPERSEDED;
        /** @return 是否不能再开始新尝试 */
        public boolean terminal() { return this == COMPLETED || this == FAILED || this == CANCELLED || this == SUPERSEDED; }
    }

    /**
     * 逻辑键必须保留轮次和分支，不能仅用 StageType 去重。
     * @param type 阶段类型
     * @param round 新一轮补查／修订编号，从 0 开始
     * @param branchKey 稳定分支名，非并行阶段也需明确值
     */
    public record Key(StageType type, int round, String branchKey) {
        /** 拒绝缺失分支或负轮次。 */
        public Key { DomainViolation.require(type != null && round >= 0 && branchKey != null && !branchKey.isBlank(), INVALID_INPUT); }
    }

    /** 验证规划模式及尝试历史形状，不把 attemptId 当成 stepId。 */
    public ExecutionStep {
        DomainViolation.require(id != null && runId != null && scope != null && key != null && state != null
                && attemptCount >= 0 && ((attemptCount == 0) == (currentAttemptId == null)), INVALID_INPUT);
        DomainViolation.require((key.type() == StageType.PLAN) == (mode != null), INVALID_INPUT);
        DomainViolation.require((state != State.RUNNING && state != State.COMPLETED && state != State.RETRY_WAIT && state != State.FAILED)
                || currentAttemptId != null, INVALID_INPUT);
    }

    /**
     * 开始同一逻辑阶段的新尝试，不能复用最近尝试标识。
     * @param attemptId 新尝试 ID
     * @return RUNNING 阶段，逻辑身份与键不变
     */
    public ExecutionStep begin(UUID attemptId) {
        DomainViolation.require(state == State.READY, ILLEGAL_TRANSITION);
        DomainViolation.require(attemptId != null && !attemptId.equals(currentAttemptId), ATTEMPT_MISMATCH);
        return new ExecutionStep(id, runId, scope, key, mode, State.RUNNING, Math.incrementExact(attemptCount), attemptId);
    }

    /**
     * 记录本次尝试的结果；真正提交前还必须检查 ExecutionFence 和数据库条件。
     * @param attemptId 本次尝试
     * @param outcome 完成、重试等待或失败
     * @return 相同 stepId 的结果状态
     */
    public ExecutionStep finish(UUID attemptId, State outcome) {
        DomainViolation.require(state == State.RUNNING, ILLEGAL_TRANSITION);
        DomainViolation.require(currentAttemptId.equals(attemptId), ATTEMPT_MISMATCH);
        DomainViolation.require(outcome == State.COMPLETED || outcome == State.RETRY_WAIT || outcome == State.FAILED, ILLEGAL_TRANSITION);
        return new ExecutionStep(id, runId, scope, key, mode, outcome, attemptCount, currentAttemptId);
    }

    /** @return 到期且预算允许后重新就绪的同一阶段；到期与预算由应用判断 */
    public ExecutionStep retryReady() {
        DomainViolation.require(state == State.RETRY_WAIT, ILLEGAL_TRANSITION);
        return new ExecutionStep(id, runId, scope, key, mode, State.READY, attemptCount, currentAttemptId);
    }

    /**
     * 停止任意非终态阶段，不能将完成结果重新写成可执行。
     * @param superseded 是否由新代次取代，否则为取消
     * @return 停止状态
     */
    public ExecutionStep stop(boolean superseded) {
        State target = superseded ? State.SUPERSEDED : State.CANCELLED;
        if (state == target) return this;
        DomainViolation.require(!state.terminal(), ILLEGAL_TRANSITION);
        return new ExecutionStep(id, runId, scope, key, mode, target, attemptCount, currentAttemptId);
    }
}
