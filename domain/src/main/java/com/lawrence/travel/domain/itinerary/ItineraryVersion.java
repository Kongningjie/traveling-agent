package com.lawrence.travel.domain.itinerary;

import com.lawrence.travel.domain.knowledge.EvidenceRecord;
import com.lawrence.travel.domain.memory.PreferenceVersion;
import com.lawrence.travel.domain.rule.DomainViolation;
import com.lawrence.travel.domain.travel.TaskScope;
import java.util.Set;
import java.util.UUID;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.INVALID_INPUT;

/**
 * 不可变快照身份与依赖契约；完整 JSON 载荷在存储／行程阶段定义，不复制为第二份可编辑模型。
 * @param snapshotId 稳定快照 UUID，候选和接受记录引用同一内容
 * @param scope 所属用户和任务
 * @param schemaVersion 载荷格式正版本
 * @param payloadHash 完整快照 SHA256
 * @param constraintRevision 依赖需求版本
 * @param baseVersionId 基础生效版本，可空
 * @param evidenceRefs 实际选用的精确证据集合
 * @param preferenceVersion 实际使用的偏好版本，未用偏好时为空
 */
public record ItineraryVersion(UUID snapshotId, TaskScope scope, int schemaVersion, String payloadHash,
                               long constraintRevision, UUID baseVersionId, Set<EvidenceRecord.Reference> evidenceRefs,
                               PreferenceVersion preferenceVersion) {
    /** 校验同用户偏好和单一证据版本；不把校验／审核状态写回快照。 */
    public ItineraryVersion {
        DomainViolation.require(snapshotId != null && scope != null && schemaVersion > 0 && payloadHash != null
                && payloadHash.matches("[0-9a-fA-F]{64}") && constraintRevision > 0 && evidenceRefs != null
                && !snapshotId.equals(baseVersionId) && (preferenceVersion == null || preferenceVersion.userId().equals(scope.userId())), INVALID_INPUT);
        DomainViolation.require(evidenceRefs.stream().allMatch(java.util.Objects::nonNull), INVALID_INPUT);
        evidenceRefs = Set.copyOf(evidenceRefs);
        DomainViolation.require(evidenceRefs.stream().map(EvidenceRecord.Reference::id).distinct().count() == evidenceRefs.size(), INVALID_INPUT);
        payloadHash = payloadHash.toLowerCase(java.util.Locale.ROOT);
    }
}
