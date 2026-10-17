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

/** 初始及局部规划应用契约；模型只能提供草案／问题，不能提交版本或自报校验通过。 */
public final class Planning {
    /** 命名空间。 */
    private Planning() { }
    /** @param scope 私有归属 @param revision 当前需求 @param baseVersionId 当前生效指针 @param evidence 可用及未知资料 @param now 数据库时间 @param restorationTarget 历史恢复目标，普通规划为空 @param preferences 权威筛选的长期软偏好与读取版本，不从历史摘要恢复 */
    public record Context(TaskScope scope, ConstraintRevision revision, UUID baseVersionId, List<Research.View> evidence, Instant now, TripSnapshot restorationTarget, com.lawrence.travel.domain.memory.Preference.View preferences) {
        /** @param scope 归属 @param revision 需求 @param baseVersionId 指针 @param evidence 证据 @param now 时间 @param restorationTarget 恢复目标；兼容不使用偏好的本地调用 */
        public Context(TaskScope scope, ConstraintRevision revision, UUID baseVersionId, List<Research.View> evidence, Instant now, TripSnapshot restorationTarget) { this(scope, revision, baseVersionId, evidence, now, restorationTarget, null); }
        /** @param scope 归属 @param revision 当前需求 @param baseVersionId 当前指针 @param evidence 资料 @param now 时间 */
        public Context(TaskScope scope, ConstraintRevision revision, UUID baseVersionId, List<Research.View> evidence, Instant now) { this(scope, revision, baseVersionId, evidence, now, null); }
    }
    /** @param candidateId 草案身份 @param version 不可变元信息 @param snapshot 完整内容 @param validation 程序报告 @param review 独立报告 @param state 候选状态 */
    public record Candidate(UUID candidateId, ItineraryVersion version, TripSnapshot snapshot, ItineraryReport validation, ItineraryReport review, String state) { }
    /** @param snapshot 一个推荐草案及其中局部备选 */
    public record Draft(TripSnapshot snapshot) { }
    /** @param issues 独立上下文审核问题 */
    public record Review(List<ItineraryReport.Issue> issues) { }
    /** @param snapshot 原生效内容 @param hash 权威正文摘要 @param revision 原需求版本 */
    public record Base(TripSnapshot snapshot, String hash, ConstraintRevision revision) { }
    /** @param patch 累计补丁，与扩大请求互斥 @param expansionReason 无法在当前范围完成时的具体理由，可空 */
    public record PatchReply(com.lawrence.travel.domain.itinerary.ItineraryPatch patch, String expansionReason) { }
    /** @param candidateId 当前候选，可空 @param repair 是否为有界修订 @param issues 程序或审核问题 @param snapshotId 兼容可靠重校验输入，内容仍由候选权威读取 @param expansionHistory 已提交扩大理由 @param restoreSnapshotId 显式历史恢复目标，可空 */
    public record Input(UUID candidateId, boolean repair, List<ItineraryReport.Issue> issues, UUID snapshotId, List<String> expansionHistory, UUID restoreSnapshotId) {
        /** @param candidateId 候选 @param repair 修订 @param issues 问题 @param snapshotId 基础身份 @param expansionHistory 扩大历史；普通规划兼容入口 */
        public Input(UUID candidateId, boolean repair, List<ItineraryReport.Issue> issues, UUID snapshotId, List<String> expansionHistory) { this(candidateId, repair, issues, snapshotId, expansionHistory, null); }
        /** @param candidateId 候选 @param repair 修订标记 @param issues 问题 */
        public Input(UUID candidateId, boolean repair, List<ItineraryReport.Issue> issues) { this(candidateId, repair, issues, null, List.of(), null); }
    }
    /** @param candidate 草案及精确报告 @param costs Java计算预算，可空表示坏格式或未知 @param acceptable 当前可供确认，接受时仍重新校验 */
    public record View(Candidate candidate, com.lawrence.travel.domain.itinerary.CostSummary costs, boolean acceptable) { }
}
