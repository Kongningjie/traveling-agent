package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.port.AgentRuntimePort;
import io.agentscope.harness.agent.HarnessAgent;

/** 仅验证框架依赖可被装配；不创建模型客户端，不触发真实调用。 */
public final class HarnessAgentRuntimeAdapter implements AgentRuntimePort {
    /**
     * 暴露可解析的 Harness 类型，用于确认固定版本框架依赖存在；不实例化 Agent。
     *
     * @return HarnessAgent 的类型对象，不包含模型客户端或执行状态
     */
    public Class<?> frameworkType() {
        return HarnessAgent.class;
    }

    /**
     * {@inheritDoc}
     * 当前仅有类依赖接线，始终返回待真实调用验证状态，不检查供应商权限或额度。
     *
     * @return 固定标识 HARNESS_NOT_VALIDATED
     */
    @Override
    public String integrationStatus() {
        return "HARNESS_NOT_VALIDATED";
    }
}
