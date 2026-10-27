package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.application.execution.ExecutionProbe;
import com.lawrence.travel.application.port.ExecutionResources;
import com.lawrence.travel.infrastructure.execution.BoundedExecutionResources;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 装配单实例执行资源；不建立第二套调度或自动开始业务任务。 */
@Configuration(proxyBeanMethods = false)
public class ExecutionResourcesConfiguration {
    /**
     * 将已确认限制交给基础设施，并由容器停止时关闭资源。
     * @param properties 活跃运行及每运行调查并发配置
     * @return 单实例共享执行资源
     */
    @Bean(destroyMethod = "close")
    BoundedExecutionResources executionResources(ExecutionControlProperties properties) {
        return new BoundedExecutionResources(
                properties.limits().activeRuns(), properties.limits().researchConcurrencyPerRun());
    }

    /**
     * 仅 smoke 创建离线诊断用例；local 不暴露启动探针能力。
     * @param resources 单实例执行资源
     * @return 固定任务诊断服务
     */
    @Bean
    @Profile("smoke")
    ExecutionProbe executionProbe(ExecutionResources resources) {
        return new ExecutionProbe(resources);
    }
}
