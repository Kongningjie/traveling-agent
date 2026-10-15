package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.agent.runtime.HarnessAgentRuntimeAdapter;
import com.lawrence.travel.application.port.AgentRuntimePort;
import com.lawrence.travel.application.system.SystemOverviewService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 统一绑定应用端口及运行配置，使 api 和 application 不依赖具体 Agent 实现。 */
@Configuration(proxyBeanMethods = false)
public class ApplicationAssembly {
    /**
     * 为应用层绑定当前仅暴露接线标识的 Harness 适配器。
     *
     * @return 不创建模型客户端、不调用外部服务的运行端口
     */
    @Bean
    AgentRuntimePort agentRuntimePort() {
        return new HarnessAgentRuntimeAdapter();
    }

    /**
     * 将 Spring 环境读取限制在装配层，再向应用服务传递普通配置值。
     *
     * @param agentRuntime 已装配的 Agent 端口，不可为 null
     * @param environment Spring 环境，只读取不含凭证的运行模式
     * @return 供系统信息 API 使用的只读应用服务
     */
    @Bean
    SystemOverviewService systemOverviewService(AgentRuntimePort agentRuntime, Environment environment) {
        return new SystemOverviewService(agentRuntime, environment.getProperty("travel.runtime.mode", "local"));
    }
}
