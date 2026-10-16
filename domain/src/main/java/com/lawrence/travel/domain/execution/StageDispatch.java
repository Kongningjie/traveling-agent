package com.lawrence.travel.domain.execution;

import com.lawrence.travel.domain.rule.DomainViolation;
import java.util.UUID;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.INVALID_INPUT;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.IDEMPOTENCY_CONFLICT;

/**
 * 后续阶段执行请求，不是 SSE 展示事件；数据库须落实同键唯一及内容一致性。
 * @param key 因果唯一键
 * @param stepId 目标逻辑阶段 ID，重投不能另造身份
 * @param payloadHash 目标输入 SHA256
 */
public record StageDispatch(Key key, UUID stepId, String payloadHash) {
    /**
     * 原因可以是输入、用户回答或已完成 stepId，不能仅取 StageType。
     * @param runId 父运行
     * @param causationId 唯一触发原因
     * @param eventType 明确请求类型
     * @param branchKey 目标分支
     */
    public record Key(UUID runId, UUID causationId, String eventType, String branchKey) {
        /** 所有维度均须明确，防止不同回答或补查被错误去重。 */
        public Key {
            DomainViolation.require(runId != null && causationId != null && eventType != null && !eventType.isBlank()
                    && branchKey != null && !branchKey.isBlank(), INVALID_INPUT);
        }
    }
    /** 稳定请求不能缺失内容摘要。 */
    public StageDispatch {
        DomainViolation.require(key != null && stepId != null && payloadHash != null && payloadHash.matches("[0-9a-fA-F]{64}"), INVALID_INPUT);
        payloadHash = payloadHash.toLowerCase(java.util.Locale.ROOT);
    }
    /**
     * 相同原因重投必须保持相同目标与输入，不静默覆盖冲突。
     * @param replay 同一因果键的另一请求
     */
    public void requireSameReplay(StageDispatch replay) {
        DomainViolation.require(replay != null && key.equals(replay.key), INVALID_INPUT);
        DomainViolation.require(stepId.equals(replay.stepId) && payloadHash.equals(replay.payloadHash), IDEMPOTENCY_CONFLICT);
    }
}
