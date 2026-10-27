package com.lawrence.travel.domain.execution.control;

import java.time.Duration;
import java.util.Objects;

/**
 * 一次运行的已确认上限，不包含部署实例内的并发槽。
 * @param modelCalls 模型请求上限，必须大于零
 * @param toolCalls 外部工具请求上限，必须大于零
 * @param repairRounds 修订上限，可为零
 * @param modelTimeout 单模型请求超时，必须为正且至少一毫秒
 * @param activeTime 主动执行累计上限，必须为正且至少一毫秒
 */
public record RunLimits(
        long modelCalls,
        long toolCalls,
        int repairRounds,
        Duration modelTimeout,
        Duration activeTime) {
    /**
     * 检查配置合法性，拒绝无界、负数或无法按毫秒执行的预算。
     * @throws IllegalArgumentException 次数或时间配置不合法
     */
    public RunLimits {
        Objects.requireNonNull(modelTimeout);
        Objects.requireNonNull(activeTime);
        if (modelCalls <= 0
                || toolCalls <= 0
                || repairRounds < 0
                || modelTimeout.toMillis() <= 0
                || activeTime.toMillis() <= 0
                || !modelTimeout.equals(Duration.ofMillis(modelTimeout.toMillis()))
                || !activeTime.equals(Duration.ofMillis(activeTime.toMillis()))) {
            throw new IllegalArgumentException("Invalid execution limits");
        }
    }

    /**
     * 根据已累计主动时间收紧本次模型超时，不延长运行总预算。
     * @param usage 同一运行的已持久化用量
     * @return 单请求上限与剩余主动时间的较小值
     * @throws CallRejectedException 主动时间已耗尽
     */
    public Duration requestTimeout(RunBudget usage) {
        Duration remaining = activeTime.minus(usage.activeTime());
        if (remaining.isZero() || remaining.isNegative()) {
            throw new CallRejectedException(CallRejectedException.Reason.ACTIVE_TIME_EXHAUSTED);
        }
        return remaining.compareTo(modelTimeout) < 0 ? remaining : modelTimeout;
    }
}
