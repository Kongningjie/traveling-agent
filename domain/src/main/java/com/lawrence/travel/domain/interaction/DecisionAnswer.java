package com.lawrence.travel.domain.interaction;

import com.lawrence.travel.domain.rule.DomainViolation;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.INVALID_INPUT;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.ILLEGAL_TRANSITION;

/**
 * 回答可靠收到后仍待解释和 Java 校验；原始 freeText 不能直接覆盖需求或偏好。
 * @param id 回答唯一 ID，重放由持久幂等记录识别
 * @param decisionId 所答决策
 * @param expectedDecisionRevision 客户端看到的问题版本
 * @param expectedConstraintRevision 关联需求版本
 * @param kind 回答、暂未决定或有限授权
 * @param selectedOptions 问题 ID 到选项 ID
 * @param freeText 自由补充，可为空字符串
 * @param authorizationScope 明确授权对象，仅 AUTHORIZE 可非空
 * @param state 回答处理状态
 */
public record DecisionAnswer(
        UUID id,
        UUID decisionId,
        long expectedDecisionRevision,
        long expectedConstraintRevision,
        Kind kind,
        Map<UUID, UUID> selectedOptions,
        String freeText,
        Set<UUID> authorizationScope,
        State state) {
    /** 明确选择语义，默认推荐不能构造为用户回答。 */
    public enum Kind {
        /** 已明确回答。 */
        ANSWER,
        /** 未确认，不推进需求。 */
        UNDECIDED,
        /** 明确有限授权。 */
        AUTHORIZE
    }

    /** HTTP 接收与业务采纳分开记录。 */
    public enum State {
        /** 已可靠收到。 */
        RECEIVED,
        /** 已解析待校验／提交。 */
        INTERPRETED,
        /** 已在业务事务中采纳。 */
        APPLIED
    }

    /** 校验回答形状，不替代当前问题和授权范围校验。 */
    public DecisionAnswer {
        DomainViolation.require(
                id != null
                        && decisionId != null
                        && expectedDecisionRevision > 0
                        && expectedConstraintRevision > 0
                        && kind != null
                        && selectedOptions != null
                        && freeText != null
                        && authorizationScope != null
                        && state != null,
                INVALID_INPUT);
        DomainViolation.require(
                selectedOptions.entrySet().stream()
                                .allMatch(
                                        entry -> entry.getKey() != null && entry.getValue() != null)
                        && authorizationScope.stream().allMatch(java.util.Objects::nonNull),
                INVALID_INPUT);
        selectedOptions = Map.copyOf(selectedOptions);
        authorizationScope = Set.copyOf(authorizationScope);
        DomainViolation.require(
                kind == Kind.AUTHORIZE
                        ? !authorizationScope.isEmpty()
                        : authorizationScope.isEmpty(),
                INVALID_INPUT);
        DomainViolation.require(
                kind == Kind.UNDECIDED || !selectedOptions.isEmpty() || !freeText.isBlank(),
                INVALID_INPUT);
    }

    /**
     * @return 已解释的同一回答，不表示已通过冲突校验或采纳
     */
    public DecisionAnswer interpreted() {
        DomainViolation.require(state == State.RECEIVED, ILLEGAL_TRANSITION);
        return new DecisionAnswer(
                id,
                decisionId,
                expectedDecisionRevision,
                expectedConstraintRevision,
                kind,
                selectedOptions,
                freeText,
                authorizationScope,
                State.INTERPRETED);
    }

    /**
     * @return 事务完成后记录的采纳状态；暂未决定不能被记为采纳
     */
    public DecisionAnswer applied() {
        DomainViolation.require(
                state == State.INTERPRETED && kind != Kind.UNDECIDED, ILLEGAL_TRANSITION);
        return new DecisionAnswer(
                id,
                decisionId,
                expectedDecisionRevision,
                expectedConstraintRevision,
                kind,
                selectedOptions,
                freeText,
                authorizationScope,
                State.APPLIED);
    }
}
