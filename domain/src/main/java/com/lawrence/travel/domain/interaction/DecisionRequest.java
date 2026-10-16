package com.lawrence.travel.domain.interaction;

import com.lawrence.travel.domain.rule.DomainViolation;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.domain.travel.TravelTask;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.INVALID_INPUT;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.STALE_DECISION;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.ILLEGAL_TRANSITION;

/**
 * 可部分回答的决策卡；推荐预选、等待超时和接口接收都不会隐含采纳。
 * @param id 决策 ID
 * @param scope 归属
 * @param runId 提问运行
 * @param generation 提问执行代次
 * @param revision 问题版本
 * @param constraintRevision 依赖需求版本
 * @param remaining 剩余问题，以稳定问题 ID 索引
 * @param freeTextAllowed 是否允许自由回答
 * @param allowedAuthorizationScope 可以明确授权的对象边界
 * @param state 决策生命周期
 */
public record DecisionRequest(UUID id, TaskScope scope, UUID runId, long generation, long revision, long constraintRevision,
                              Map<UUID, Question> remaining, boolean freeTextAllowed, Set<UUID> allowedAuthorizationScope, State state) {
    /** 卡片终态不能被旧回答复活。 */
    public enum State { /** 仍有问题。 */ OPEN, /** 全部明确处理。 */ ANSWERED, /** 被新要求取代。 */ SUPERSEDED }

    /**
     * 选项推荐只用于展示。
     * @param id 问题 ID
     * @param prompt 问题文本
     * @param options 选项 ID 与可展示文本
     * @param recommendedOption 推荐选项，可空
     */
    public record Question(UUID id, String prompt, Map<UUID, String> options, UUID recommendedOption) {
        /** 推荐项必须来自选项集合，问题与选项文本不能为空。 */
        public Question {
            DomainViolation.require(id != null && prompt != null && !prompt.isBlank() && options != null, INVALID_INPUT);
            DomainViolation.require(options.entrySet().stream().allMatch(entry -> entry.getKey() != null && entry.getValue() != null), INVALID_INPUT);
            options = Map.copyOf(options);
            DomainViolation.require(options.values().stream().allMatch(value -> !value.isBlank())
                    && (recommendedOption == null || options.containsKey(recommendedOption)), INVALID_INPUT);
        }
    }

    /** 复制集合并验证问题 ID 与键一致，避免部分回答误删其他问题。 */
    public DecisionRequest {
        DomainViolation.require(id != null && scope != null && runId != null && generation > 0 && revision > 0
                && constraintRevision > 0 && remaining != null && allowedAuthorizationScope != null && state != null, INVALID_INPUT);
        DomainViolation.require(remaining.entrySet().stream().allMatch(entry -> entry.getKey() != null && entry.getValue() != null)
                && allowedAuthorizationScope.stream().allMatch(java.util.Objects::nonNull), INVALID_INPUT);
        remaining = Map.copyOf(remaining);
        allowedAuthorizationScope = Set.copyOf(allowedAuthorizationScope);
        DomainViolation.require(remaining.entrySet().stream().allMatch(entry -> entry.getKey().equals(entry.getValue().id()))
                && (state != State.OPEN || !remaining.isEmpty()) && (state != State.ANSWERED || remaining.isEmpty()), INVALID_INPUT);
    }

    /**
     * 仅应用已解析并通过 Java 冲突检查的原问题范围内回答；不执行自由文本解析或偏好写入。
     * @param answer 已解释的显式回答
     * @param current 当前任务，必须尚未被新修改取代
     * @param resolved 此次确实解决的问题子集
     * @param nextConstraintRevision 同事务保存后的需求版本，不得倒退
     * @return 剩余问题及问题版本更新的新卡片；重复回答由外层先做持久幂等检查
     */
    public DecisionRequest applyConfirmed(DecisionAnswer answer, TravelTask current, Set<UUID> resolved, long nextConstraintRevision) {
        DomainViolation.require(answer != null && current != null && resolved != null, INVALID_INPUT);
        DomainViolation.require(resolved.stream().allMatch(java.util.Objects::nonNull), INVALID_INPUT);
        scope.requireSame(current.scope());
        DomainViolation.require(state == State.OPEN && id.equals(answer.decisionId()) && revision == answer.expectedDecisionRevision()
                && constraintRevision == answer.expectedConstraintRevision() && constraintRevision == current.constraintRevision()
                && generation == current.executionGeneration(), STALE_DECISION);
        DomainViolation.require(answer.state() == DecisionAnswer.State.INTERPRETED && answer.kind() != DecisionAnswer.Kind.UNDECIDED, ILLEGAL_TRANSITION);
        DomainViolation.require(!resolved.isEmpty() && remaining.keySet().containsAll(resolved) && nextConstraintRevision >= constraintRevision
                && (freeTextAllowed || answer.freeText().isBlank())
                && allowedAuthorizationScope.containsAll(answer.authorizationScope()), INVALID_INPUT);
        for (var selection : answer.selectedOptions().entrySet()) {
            Question question = remaining.get(selection.getKey());
            DomainViolation.require(question != null && question.options().containsKey(selection.getValue()), INVALID_INPUT);
        }
        DomainViolation.require(!answer.freeText().isBlank() || answer.selectedOptions().keySet().containsAll(resolved), INVALID_INPUT);
        var next = new HashMap<>(remaining);
        resolved.forEach(next::remove);
        // 需求推进后剩余问题必须获得新的问题及需求绑定，不能让部分回答永久废掉整张卡。
        return new DecisionRequest(id, scope, runId, generation, Math.incrementExact(revision), nextConstraintRevision,
                next, freeTextAllowed, allowedAuthorizationScope, next.isEmpty() ? State.ANSWERED : State.OPEN);
    }

    /** @return 新要求取代后的卡片；只作用于尚待回答的问题，不覆盖已采纳历史 */
    public DecisionRequest supersede() {
        DomainViolation.require(state == State.OPEN || state == State.SUPERSEDED, ILLEGAL_TRANSITION);
        return new DecisionRequest(id, scope, runId, generation, revision, constraintRevision,
                remaining, freeTextAllowed, allowedAuthorizationScope, State.SUPERSEDED);
    }
}
