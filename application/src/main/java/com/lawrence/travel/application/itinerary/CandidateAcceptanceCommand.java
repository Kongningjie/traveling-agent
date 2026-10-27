package com.lawrence.travel.application.itinerary;

import com.lawrence.travel.domain.rule.DomainViolation;
import com.lawrence.travel.domain.travel.TaskScope;
import java.util.UUID;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.INVALID_INPUT;

/**
 * 显式候选接受契约；身份从服务端认证绑定，不允许 Controller 直接信任浏览器 userId。
 * @param scope 受信归属
 * @param acceptanceRequestId 接受幂等 ID
 * @param candidateId 目标候选
 * @param expectedCurrentVersionId 前置生效指针，初始可空
 * @param expectedConstraintRevision 预期需求版本
 * @param expectedGeneration 预期执行代次
 * @param expectedSnapshotHash 预期完整内容 SHA256
 */
public record CandidateAcceptanceCommand(
        TaskScope scope,
        UUID acceptanceRequestId,
        UUID candidateId,
        UUID expectedCurrentVersionId,
        long expectedConstraintRevision,
        long expectedGeneration,
        String expectedSnapshotHash) {
    /** 构造仅检查形状，DEV-0013 在短事务中重新校验全部报告及当前状态。 */
    public CandidateAcceptanceCommand {
        DomainViolation.require(
                scope != null
                        && acceptanceRequestId != null
                        && candidateId != null
                        && expectedConstraintRevision > 0
                        && expectedGeneration > 0
                        && expectedSnapshotHash != null
                        && expectedSnapshotHash.matches("[0-9a-fA-F]{64}"),
                INVALID_INPUT);
        expectedSnapshotHash = expectedSnapshotHash.toLowerCase(java.util.Locale.ROOT);
    }
}
