package com.lawrence.travel.application.knowledge;

import com.lawrence.travel.application.port.TravelToolPort;
import com.lawrence.travel.domain.knowledge.FieldEvidence;
import com.lawrence.travel.domain.knowledge.EvidenceRecord;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 调查传输契约；模型只建议查询，不能指定来源类型、有效期、证据身份或提交状态。 */
public final class Research {
    /** 命名空间，不持有共享可变状态。 */
    private Research() { }
    /** @param targetId 明确对象 @param fieldPath 待查字段 @param operation 白名单工具 @param arguments 精确业务参数 @param date 适用日期，可空 */
    public record Query(String targetId, String fieldPath, TravelToolPort.Operation operation, Map<String, String> arguments, LocalDate date) {
        /** 参数防御复制；各工具参数和值在应用边界继续校验。 */
        public Query { arguments = arguments == null ? Map.of() : Map.copyOf(arguments); }
    }
    /** @param revision 已确认需求 @param evidence 已登记资料 @param now 当前业务时刻 */
    public record Context(ConstraintRevision revision, List<View> evidence, Instant now) { }
    /** @param queries 有界查询计划；空计划保留未知，不编造结果 */
    public record Plan(List<Query> queries) { }
    /** @param value 原文支持的字段值 @param quote 连续原文摘录 @param publishedAt 明确发布时间 @param validForDate 原文明确的适用日期，可空 */
    public record Extraction(String value, String quote, Instant publishedAt, LocalDate validForDate) { }
    /** @param queries 已提交查询计划 @param cursor 当前查询下标 @param dependencyHash 依赖需求的规范摘要 */
    public record Input(List<Query> queries, int cursor, String dependencyHash) { }
    /** @param queryKey 精确工具参数摘要 @param dependencyHash 依赖摘要 @param facts 新增证据 @param reused 精确复用引用 @param raw 原始工具响应，可空 */
    public record Prepared(String queryKey, String dependencyHash, List<FieldEvidence> facts, Set<EvidenceRecord.Reference> reused, TravelToolPort.Evidence raw) { }
    /** @param evidence 不可变事实 @param effectiveStatus 追加通知合并后的认知状态 @param reason 固定状态码 */
    public record View(FieldEvidence evidence, EvidenceRecord.Status effectiveStatus, String reason) { }
    /** @param allCurrent 引用全都仍可用于报告 @param validUntil 最早有效截止，无引用时为调用方上限 */
    public record Dependencies(boolean allCurrent, Instant validUntil) { }
}
