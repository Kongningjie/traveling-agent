package com.lawrence.travel.domain.execution.control;

import java.time.Duration;
import java.util.Objects;

/**
 * 持久化预算快照；本类型是纯规则，调用者必须在数据库锁内原子保存新快照。
 * @param modelCalls 已预占模型次数，包含失败和未知请求
 * @param toolCalls 已预占外部工具次数
 * @param repairRounds 已预占修订轮数
 * @param activeTime 全运行活跃区间累计时间，不按并行子请求相加
 */
public record RunBudget(long modelCalls, long toolCalls, int repairRounds, Duration activeTime) {
    /**
     * 拒绝负数快照，避免恢复或反序列化后意外获得更多额度。
     * @throws IllegalArgumentException 计数或累计时间为负
     */
    public RunBudget {
        Objects.requireNonNull(activeTime);
        if (modelCalls < 0 || toolCalls < 0 || repairRounds < 0 || activeTime.isNegative()
                || !activeTime.equals(Duration.ofMillis(activeTime.toMillis()))) {
            throw new IllegalArgumentException("Invalid budget snapshot");
        }
    }

    /**
     * 计算调用前预占结果，不修改当前快照，也不因请求失败退还次数。
     * @param kind 本次操作类别，不可为 null
     * @param limits 同一运行的执行限制
     * @return 增加一次对应计数的新快照
     * @throws CallRejectedException 本次新增操作会超过次数或主动时间上限
     */
    public RunBudget reserve(CallKind kind, RunLimits limits) {
        Objects.requireNonNull(kind);
        limits.requestTimeout(this);
        boolean exhausted = switch (kind) {
            case MODEL -> modelCalls >= limits.modelCalls();
            case TOOL -> toolCalls >= limits.toolCalls();
            case REPAIR -> repairRounds >= limits.repairRounds();
        };
        if (exhausted) {
            throw new CallRejectedException(CallRejectedException.Reason.CALL_BUDGET_EXHAUSTED);
        }
        // 先检查再递增，第 30／60 次允许完成；只有新增第 31／61 次时才拒绝。
        return new RunBudget(modelCalls + (kind == CallKind.MODEL ? 1 : 0),
                toolCalls + (kind == CallKind.TOOL ? 1 : 0),
                repairRounds + (kind == CallKind.REPAIR ? 1 : 0), activeTime);
    }
}
