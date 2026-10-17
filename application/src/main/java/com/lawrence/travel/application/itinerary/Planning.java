package com.lawrence.travel.application.itinerary;

import com.lawrence.travel.application.knowledge.Research;
import com.lawrence.travel.domain.itinerary.TripSnapshot;
import com.lawrence.travel.domain.itinerary.ItineraryReport;
import com.lawrence.travel.domain.itinerary.ItineraryVersion;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TaskScope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 初始规划应用契约；模型只能提供草案／问题，不能提交版本或自报校验通过。 */
public final class Planning {
    /** 命名空间。 */
    private Planning() { }
    /** @param scope 私有归属 @param revision 当前需求 @param baseVersionId 当前生效指针 @param evidence 可用及未知资料 @param now 数据库时间 */
    public record Context(TaskScope scope, ConstraintRevision revision, UUID baseVersionId, List<Research.View> evidence, Instant now) { }
    /** @param candidateId 草案身份 @param version 不可变元信息 @param snapshot 完整内容 @param validation 程序报告 @param review 独立报告 @param state 候选状态 */
    public record Candidate(UUID candidateId, ItineraryVersion version, TripSnapshot snapshot, ItineraryReport validation, ItineraryReport review, String state) { }
    /** @param snapshot 一个推荐草案及其中局部备选 */
    public record Draft(TripSnapshot snapshot) { }
    /** @param issues 独立上下文审核问题 */
    public record Review(List<ItineraryReport.Issue> issues) { }
    /** @param candidateId 当前候选，可空 @param repair 是否为有界修订 @param issues 程序或审核问题 @param snapshotId 兼容可靠重校验输入，内容仍由候选权威读取 */
    public record Input(UUID candidateId, boolean repair, List<ItineraryReport.Issue> issues, UUID snapshotId) {
        /** @param candidateId 候选 @param repair 修订标记 @param issues 问题 */
        public Input(UUID candidateId, boolean repair, List<ItineraryReport.Issue> issues) { this(candidateId, repair, issues, null); }
    }
    /** @param candidate 草案及精确报告 @param costs Java计算预算，可空表示坏格式或未知 @param acceptable 当前可供确认，接受时仍重新校验 */
    public record View(Candidate candidate, com.lawrence.travel.domain.itinerary.CostSummary costs, boolean acceptable) { }
}
