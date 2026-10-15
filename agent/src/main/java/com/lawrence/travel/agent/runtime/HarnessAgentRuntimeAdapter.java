package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.port.AgentRuntimePort;
import io.agentscope.harness.agent.HarnessAgent;

/** 仅验证框架依赖可被装配；不创建模型客户端，不触发真实调用。 */
public final class HarnessAgentRuntimeAdapter implements AgentRuntimePort {
    public Class<?> frameworkType() {
        return HarnessAgent.class;
    }

    @Override
    public String integrationStatus() {
        return "HARNESS_NOT_VALIDATED";
    }
}
