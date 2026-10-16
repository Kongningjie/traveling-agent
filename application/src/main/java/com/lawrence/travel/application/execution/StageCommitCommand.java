package com.lawrence.travel.application.execution;

import com.lawrence.travel.domain.execution.ExecutionFence;
import com.lawrence.travel.domain.execution.StageDispatch;
import com.lawrence.travel.domain.rule.DomainViolation;
import java.util.List;
import java.util.UUID;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.INVALID_INPUT;

/**
 * 原子阶段提交契约；产物选用、后继请求与事件完成必须由引擎一次事务保存。
 * @param fence 本次执行资格
 * @param artifactId 已准备的文本／JSON 业务产物 ID，不要求独立文件落盘
 * @param contentHash 业务产物 SHA256
 * @param nextStages 后续执行请求，等待用户时可为空
 */
public record StageCommitCommand(ExecutionFence fence, UUID artifactId, String contentHash, List<StageDispatch> nextStages) {
    /** 后继必须由本次逻辑阶段触发且属于同一运行，重复分支键不能含糊提交。 */
    public StageCommitCommand {
        DomainViolation.require(fence != null && artifactId != null && contentHash != null && contentHash.matches("[0-9a-fA-F]{64}")
                && nextStages != null, INVALID_INPUT);
        DomainViolation.require(nextStages.stream().allMatch(java.util.Objects::nonNull), INVALID_INPUT);
        nextStages = List.copyOf(nextStages);
        DomainViolation.require(nextStages.stream().map(StageDispatch::key).distinct().count() == nextStages.size()
                && nextStages.stream().map(StageDispatch::stepId).distinct().count() == nextStages.size(), INVALID_INPUT);
        for (var next : nextStages) {
            DomainViolation.require(next.key().runId().equals(fence.runId()) && next.key().causationId().equals(fence.stepId())
                    && !next.stepId().equals(fence.stepId()), INVALID_INPUT);
        }
        contentHash = contentHash.toLowerCase(java.util.Locale.ROOT);
    }
}
