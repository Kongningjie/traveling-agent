package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.agent.runtime.HarnessAgentRuntimeAdapter;
import com.lawrence.travel.application.port.AgentRuntimePort;
import com.lawrence.travel.application.system.SystemOverviewService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class ApplicationAssembly {
    @Bean
    AgentRuntimePort agentRuntimePort() {
        return new HarnessAgentRuntimeAdapter();
    }

    @Bean
    SystemOverviewService systemOverviewService(AgentRuntimePort agentRuntime, Environment environment) {
        return new SystemOverviewService(agentRuntime, environment.getProperty("travel.runtime.mode", "local"));
    }
}
