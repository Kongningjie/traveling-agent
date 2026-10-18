package com.lawrence.travel.application.execution;

import com.lawrence.travel.domain.execution.control.RunLimits;
import java.time.Duration;

/** 本地验证初始值；部署可配置，不改变旅行运行原有预算。
 * @param inputLimits 每条输入的持久化限制快照
 * @param dailyModels 每用户自然日模型请求上限
 * @param dailyTools 每用户自然日工具请求上限
 * @param dailyParses 每用户自然日解析尝试上限
 */
public record JobPolicy(RunLimits inputLimits, long dailyModels, long dailyTools, long dailyParses) {
    /** 拒绝无界或非正配额。 */
    public JobPolicy {
        if (inputLimits == null || dailyModels <= 0 || dailyTools <= 0 || dailyParses <= 0) throw new IllegalArgumentException("INVALID_JOB_POLICY");
    }
    /** @return 二期已确认初始预算，重试不重新建账 */
    public static JobPolicy defaults() { return new JobPolicy(new RunLimits(8, 12, 0, Duration.ofSeconds(180), Duration.ofMinutes(5)), 100, 200, 50); }
}
