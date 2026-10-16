package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.agent.runtime.HarnessAgentRuntimeAdapter;
import com.lawrence.travel.application.port.AgentRuntimePort;
import com.lawrence.travel.application.system.SystemOverviewService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.ObjectProvider;
import com.lawrence.travel.application.execution.ModelCallAdmission;
import java.nio.file.Path;

/** 统一绑定应用端口及运行配置，使 api 和 application 不依赖具体 Agent 实现。 */
@Configuration(proxyBeanMethods = false)
public class ApplicationAssembly {
    /**
     * 为 local 绑定受控 Harness，smoke 继续使用不可调用适配器。
     *
     * @param admission local 门禁，smoke 中不存在
     * @param properties 固定模型配置
     * @param environment 仅用于按需读取本机密钥
     * @return 不主动调用外部服务的运行端口
     */
    @Bean
    AgentRuntimePort agentRuntimePort(ObjectProvider<ModelCallAdmission> admission,
                                     ExecutionControlProperties properties, Environment environment) {
        var gate = admission.getIfAvailable();
        return gate == null ? new HarnessAgentRuntimeAdapter()
                : new HarnessAgentRuntimeAdapter(gate, properties.models(),
                    () -> environment.getProperty("DASHSCOPE_API_KEY"), Path.of(".runtime/harness"));
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
