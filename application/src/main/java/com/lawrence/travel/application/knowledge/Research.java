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
    /** @param revision 已确认需求 @param evidence 已登记资料 @param now 当前业务时刻 @param baseVersionId 当前生效版本，可空 @param preferenceRevision 当前偏好水位 */
    public record Context(ConstraintRevision revision, List<View> evidence, Instant now, java.util.UUID baseVersionId, long preferenceRevision) {
        /** @param revision 需求 @param evidence 证据 @param now 时间 @param baseVersionId 历史调用兼容 */
        public Context(ConstraintRevision revision, List<View> evidence, Instant now, java.util.UUID baseVersionId) { this(revision, evidence, now, baseVersionId, 0); }
        /** @param revision 需求 @param evidence 证据 @param now 时间；旧初始调查输入 */
        public Context(ConstraintRevision revision, List<View> evidence, Instant now) { this(revision, evidence, now, null); }
    }
    /** @param queries 有界查询计划；空计划保留未知，不编造结果 */
    public record Plan(List<Query> queries, com.lawrence.travel.domain.execution.AgentStrategy strategy) {
        /** @param queries 历史阶段/旧调用兼容；新Harness入口必须显式提供策略 */
        public Plan(List<Query> queries) { this(queries, null); }
    }
    /** @param value 原文支持的字段值 @param quote 连续原文摘录 @param publishedAt 明确发布时间 @param validForDate 原文明确的适用日期，可空 */
    public record Extraction(String value, String quote, Instant publishedAt, LocalDate validForDate) { }
    /** @param queries 已提交查询计划 @param cursor 当前查询下标 @param dependencyHash 依赖需求的规范摘要 */
    public record Input(List<Query> queries, int cursor, String dependencyHash, Handoff handoff) {
        /** @param queries 历史顺序查询 @param cursor 原游标 @param dependencyHash 原依赖；旧检查点继续兼容 */
        public Input(List<Query> queries, int cursor, String dependencyHash) { this(queries, cursor, dependencyHash, null); }
    }
    /** @param scope 任务与用户归属 @param run 原预算与运行 @param generation 原代次 @param revision 原需求 @param preferenceRevision 原偏好 @param role 固定research @param schema 固定输出协议 @param tools 本分支唯一工具 @param sources 已登记来源引用；不传任意聊天 */
    public record Handoff(com.lawrence.travel.domain.travel.TaskScope scope, java.util.UUID run, long generation, long revision, long preferenceRevision, String role, String schema, Set<String> tools, Set<EvidenceRecord.Reference> sources) { }
    /** @param queryKey 精确工具参数摘要 @param dependencyHash 依赖摘要 @param facts 新增证据 @param reused 精确复用引用 @param raw 原始工具响应，可空 */
    public record Prepared(String queryKey, String dependencyHash, List<FieldEvidence> facts, Set<EvidenceRecord.Reference> reused, TravelToolPort.Evidence raw) { }
    /** @param evidence 不可变事实 @param effectiveStatus 追加通知合并后的认知状态 @param reason 固定状态码 */
    public record View(FieldEvidence evidence, EvidenceRecord.Status effectiveStatus, String reason) { }
    /** @param allCurrent 引用全都仍可用于报告 @param validUntil 最早有效截止，无引用时为调用方上限 */
    public record Dependencies(boolean allCurrent, Instant validUntil) { }
}
