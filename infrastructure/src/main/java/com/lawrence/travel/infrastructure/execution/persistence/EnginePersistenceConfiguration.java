package com.lawrence.travel.infrastructure.execution.persistence;

import com.lawrence.travel.application.port.BusinessTransactions;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 执行持久化扫描；实际预算和执行配置由 bootstrap 唯一装配。
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@MapperScan(basePackageClasses = EngineMapper.class)
public class EnginePersistenceConfiguration {
    /**
     * @param manager PostgreSQL 事务管理
     * @return 同源事务边界
     */
    @Bean
    BusinessTransactions businessTransactions(PlatformTransactionManager manager) {
        return new SpringBusinessTransactions(manager);
    }
}
