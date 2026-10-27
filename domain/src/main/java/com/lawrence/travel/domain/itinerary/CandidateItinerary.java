package com.lawrence.travel.domain.itinerary;

import com.lawrence.travel.domain.execution.ExecutionRun;
import com.lawrence.travel.domain.rule.DomainViolation;
import com.lawrence.travel.domain.travel.TravelTask;
import java.util.UUID;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.INVALID_INPUT;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.ILLEGAL_TRANSITION;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.STALE_GENERATION;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.STALE_REVISION;

/**
 * 候选与生效指针分离；创建本对象不意味着通过校验、展示或被用户接受。
 * @param id 候选 ID
 * @param snapshot 不可变内容及精确依赖
 * @param sourceRunId 来源运行
 * @param generation 来源执行代次
 */
public record CandidateItinerary(
        UUID id, ItineraryVersion snapshot, UUID sourceRunId, long generation) {
    /** 候选必须能追溯运行和内容。 */
    public CandidateItinerary {
        DomainViolation.require(
                id != null && snapshot != null && sourceRunId != null && generation > 0,
                INVALID_INPUT);
    }

    /**
     * 检查候选来源仍属于当前任务；完整接受还需报告、证据、前置指针及未解析输入门槛。
     * @param task 当前受锁任务值
     * @param run 当前来源运行值
     */
    public void requireCurrent(TravelTask task, ExecutionRun run) {
        DomainViolation.require(
                task != null && run != null && sourceRunId.equals(run.id()), INVALID_INPUT);
        snapshot.scope().requireSame(task.scope());
        snapshot.scope().requireSame(run.scope());
        DomainViolation.require(
                generation == task.executionGeneration() && generation == run.generation(),
                STALE_GENERATION);
        DomainViolation.require(
                snapshot.constraintRevision() == task.constraintRevision()
                        && snapshot.constraintRevision() == run.constraintRevision(),
                STALE_REVISION);
        DomainViolation.require(!run.state().terminal(), ILLEGAL_TRANSITION);
    }
}
