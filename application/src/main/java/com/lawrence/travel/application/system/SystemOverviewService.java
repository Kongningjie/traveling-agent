package com.lawrence.travel.application.system;

import com.lawrence.travel.application.port.AgentRuntimePort;

import java.util.List;

/** 通过应用端口提供骨架概览；无数据库事务、模型调用和状态修改副作用。 */
public final class SystemOverviewService {
    private final AgentRuntimePort agentRuntime;
    private final String mode;

    /**
     * 保存运行适配器与配置模式，避免应用服务依赖具体框架或环境 API。
     *
     * @param agentRuntime 可读取接线标识的适配端口，不可为 null
     * @param mode 由装配层提供的运行模式，不可为 null
     */
    public SystemOverviewService(AgentRuntimePort agentRuntime, String mode) {
        this.agentRuntime = agentRuntime;
        this.mode = mode;
    }

    /**
     * 生成只读工程概览；调用不探测数据库，也不创建 Agent 或真实外部请求。
     *
     * @return 包含固定模块清单、接线标识及存储待验证占位值的概览
     */
    public SystemOverview describe() {
        // NOT_VERIFIED 与 false 是骨架的显式占位，不根据已有容器测试自动改为成功。
        // 实际依赖健康看 Actuator，业务能力验收看阶段记录，避免混淆三种状态。
        return new SystemOverview(
                "traveling-agent",
                mode,
                List.of("api", "application", "domain", "agent", "infrastructure", "bootstrap", "web"),
                agentRuntime.integrationStatus(),
                "NOT_VERIFIED",
                false);
    }
}
