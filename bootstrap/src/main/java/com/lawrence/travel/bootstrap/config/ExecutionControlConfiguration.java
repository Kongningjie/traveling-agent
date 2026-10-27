package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.application.execution.ModelCallAdmission;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.application.port.ModelRequestSlots;
import com.lawrence.travel.infrastructure.execution.SemaphoreModelRequestSlots;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 装配持久化准入服务；不创建 SDK、不读取 API Key，也不主动执行模型请求。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExecutionControlProperties.class)
public class ExecutionControlConfiguration {
    /**
     * 为整个后端实例提供唯一实际模型并发池。
     * @param properties 经验证的控制配置
     * @return 单实例共享请求槽
     */
    @Bean
    ModelRequestSlots modelRequestSlots(ExecutionControlProperties properties) {
        return new SemaphoreModelRequestSlots(properties.limits().modelConcurrency());
    }

    /**
     * 仅在有真实仓储的 local 模式装配门禁；smoke 没有可放行入口。
     * @param properties 不含凭证的模型及额度配置
     * @param ledger PostgreSQL 计量仓储
     * @param slots 全局实际请求槽
     * @return 默认拒绝真实调用的准入服务
     */
    @Bean
    @Profile("local")
    ModelCallAdmission modelCallAdmission(
            ExecutionControlProperties properties, BudgetLedger ledger, ModelRequestSlots slots) {
        return new ModelCallAdmission(
                properties.externalCallsEnabled(),
                properties.models(),
                properties.quotaEvidence(),
                properties.limits().runLimits(),
                ledger,
                slots,
                Clock.systemUTC());
    }
}
