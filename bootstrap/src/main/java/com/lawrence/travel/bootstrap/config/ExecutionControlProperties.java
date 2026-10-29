package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.domain.execution.control.FreeQuotaEvidence;
import com.lawrence.travel.domain.execution.control.RunLimits;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 执行控制配置，仅接收非凭证信息；真实模型总开关默认关闭。
 *
 * @param externalCallsEnabled 真实调用总开关
 * @param models 角色到精确型号的白名单
 * @param quotaEvidence 兼容旧配置的历史快照，不再参与调用准入
 * @param limits 已确认执行上限
 */
@ConfigurationProperties("travel")
public record ExecutionControlProperties(
        boolean externalCallsEnabled,
        Map<String, String> models,
        Map<String, FreeQuotaEvidence> quotaEvidence,
        Limits limits) {
    /** 校验配置结构并复制映射，防止运行时修改白名单绕过核实。 */
    public ExecutionControlProperties {
        models = Map.copyOf(Objects.requireNonNullElse(models, Map.of()));
        quotaEvidence = Map.copyOf(Objects.requireNonNullElse(quotaEvidence, Map.of()));
        Objects.requireNonNull(limits).runLimits();
    }

    /**
     * 保留冻结配置项；活跃运行与调查分支调度在相应阶段实现。
     *
     * @param activeRuns 全局活跃运行上限
     * @param modelConcurrency 实际模型在途上限
     * @param researchConcurrencyPerRun 每运行调查并发上限
     * @param modelCallsPerRun 每运行模型次数
     * @param toolCallsPerRun 每运行外部工具次数
     * @param repairRounds 每运行修订轮数
     * @param modelTimeout 单模型请求超时
     * @param activeTimePerRun 全运行累计主动时间
     */
    public record Limits(
            int activeRuns,
            int modelConcurrency,
            int researchConcurrencyPerRun,
            long modelCallsPerRun,
            long toolCallsPerRun,
            int repairRounds,
            Duration modelTimeout,
            Duration activeTimePerRun) {
        /**
         * 拒绝无界并发配置；其余限制由领域类型继续检查。
         * @throws IllegalArgumentException 任一并发上限小于一
         */
        public Limits {
            if (activeRuns <= 0 || modelConcurrency <= 0 || researchConcurrencyPerRun <= 0) {
                throw new IllegalArgumentException("Execution concurrency must be positive");
            }
        }

        /**
         * 将配置转为不依赖 Spring 的运行预算规则。
         * @return 经领域约束检查的限制
         */
        public RunLimits runLimits() {
            return new RunLimits(
                    modelCallsPerRun,
                    toolCallsPerRun,
                    repairRounds,
                    modelTimeout,
                    activeTimePerRun);
        }
    }
}
