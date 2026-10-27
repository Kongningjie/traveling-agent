package com.lawrence.travel.domain.itinerary;

import com.lawrence.travel.domain.knowledge.EvidenceRecord;
import com.lawrence.travel.domain.memory.PreferenceVersion;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 不可变精确绑定报告；不把可变审核状态放入快照。
 * @param id 报告身份
 * @param binding 完整依赖
 * @param kind 校验或审核
 * @param issues 结构化问题
 * @param checkedAt 检查时间
 * @param validUntil 适用截止
 * @param producerStepId 来源阶段
 * @param producerAttemptId 来源尝试
 */
public record ItineraryReport(
        UUID id,
        Binding binding,
        Kind kind,
        List<Issue> issues,
        Instant checkedAt,
        Instant validUntil,
        UUID producerStepId,
        UUID producerAttemptId) {
    /**
     * @param snapshotId 快照
     * @param schemaVersion 格式
     * @param snapshotHash 正文摘要
     * @param constraintRevision 需求
     * @param evidenceRefs 精确证据集合
     * @param preferenceVersion 实际使用偏好或空
     * @param ruleVersion 规则
     * @param reviewConfiguration 审核 Prompt／模型配置指纹
     */
    public record Binding(
            UUID snapshotId,
            int schemaVersion,
            String snapshotHash,
            long constraintRevision,
            Set<EvidenceRecord.Reference> evidenceRefs,
            PreferenceVersion preferenceVersion,
            String ruleVersion,
            String reviewConfiguration) {
        /** 绑定字段必须完整；无使用偏好时才可为空。 */
        public Binding {
            java.util.Objects.requireNonNull(snapshotId);
            java.util.Objects.requireNonNull(ruleVersion);
            java.util.Objects.requireNonNull(reviewConfiguration);
            if (schemaVersion < 1
                    || constraintRevision < 1
                    || snapshotHash == null
                    || !snapshotHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("REPORT_BINDING_INVALID");
            evidenceRefs = Set.copyOf(evidenceRefs);
        }
    }

    /** 区分程序校验与独立审核。 */
    public enum Kind {
        /** Java 确定性。 */
        VALIDATION,
        /** 独立上下文审核。 */
        REVIEW
    }

    /** 已知违反优先于未知。 */
    public enum Conclusion {
        /** 全部门槛满足。 */
        PASS,
        /** 已知阻断违反。 */
        FAIL,
        /** 阻断事实不足。 */
        UNKNOWN
    }

    /**
     * @param code 固定分类
     * @param objectId 关联对象或空
     * @param blocking 是否阻断
     * @param unknown 是否事实未知；不写不可解释总分
     */
    public record Issue(String code, UUID objectId, boolean blocking, boolean unknown) {
        /** 固定码不能夹带模型原始输出或用户隐私。 */
        public Issue {
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{1,63}"))
                throw new IllegalArgumentException("REPORT_ISSUE_INVALID");
        }
    }

    /** 固定时间范围与产物来源；角色本身由持久化阶段而非模型自报确定。 */
    public ItineraryReport {
        java.util.Objects.requireNonNull(id);
        java.util.Objects.requireNonNull(binding);
        java.util.Objects.requireNonNull(kind);
        java.util.Objects.requireNonNull(producerStepId);
        java.util.Objects.requireNonNull(producerAttemptId);
        issues = List.copyOf(issues);
        if (checkedAt == null || validUntil == null || !validUntil.isAfter(checkedAt))
            throw new IllegalArgumentException("REPORT_TIME_INVALID");
    }

    /**
     * @return 已知阻断 FAIL 优先，否则阻断未知 UNKNOWN；建议不阻断
     */
    public Conclusion conclusion() {
        if (issues.stream().anyMatch(issue -> issue.blocking() && !issue.unknown()))
            return Conclusion.FAIL;
        return issues.stream().anyMatch(Issue::blocking) ? Conclusion.UNKNOWN : Conclusion.PASS;
    }

    /**
     * @param now 当前数据库时间
     * @return 是否仍适用，不把未来报告视作有效
     */
    public boolean freshAt(Instant now) {
        return !now.isBefore(checkedAt) && now.isBefore(validUntil);
    }
}
