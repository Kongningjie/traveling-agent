package com.lawrence.travel.domain.execution;

import com.lawrence.travel.domain.rule.DomainViolation;
import java.util.UUID;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.INVALID_INPUT;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.ILLEGAL_TRANSITION;

/**
 * 尝试审计；领取时的 token 仅为副本，不能替代当前 Outbox 权威租约。
 * @param id attemptId
 * @param stepId 同一逻辑阶段 ID
 * @param eventId 领取事件 ID
 * @param number 从 1 开始的尝试序号
 * @param fencingToken 领取时 token 副本
 * @param state 尝试状态
 */
public record StepAttempt(UUID id, UUID stepId, UUID eventId, int number, long fencingToken, State state) {
    /** 尝试终态不再作为有效执行权。 */
    public enum State {
        /** 本次尝试尚未结束。 */ RUNNING,
        /** 正常提交。 */ COMPLETED,
        /** 尝试失败。 */ FAILED,
        /** 租约失效、取消或被取代。 */ ABANDONED
    }
    /** 校验身份及正序号。 */
    public StepAttempt {
        DomainViolation.require(id != null && stepId != null && eventId != null && number > 0 && fencingToken > 0 && state != null, INVALID_INPUT);
    }
    /**
     * 结束单次尝试，不能复活或重复覆盖已有结束原因。
     * @param outcome 非 RUNNING 的结束状态
     * @return 同一审计身份的结束结果
     */
    public StepAttempt finish(State outcome) {
        DomainViolation.require(state == State.RUNNING && outcome != null && outcome != State.RUNNING, ILLEGAL_TRANSITION);
        return new StepAttempt(id, stepId, eventId, number, fencingToken, outcome);
    }
}
