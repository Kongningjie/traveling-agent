package com.lawrence.travel.infrastructure.persistence.control;

import com.lawrence.travel.application.port.BudgetLedger;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

/** local 模式显式扫描计量 Mapper，smoke 不初始化数据库仓储。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@MapperScan(basePackageClasses = BudgetMapper.class)
public class BudgetPersistenceConfiguration {
    /**
     * 装配真实事务计量仓储。
     * @param mapper 本模块计量 Mapper
     * @param manager PostgreSQL 事务管理器
     * @return 应用层持久化端口
     */
    @Bean
    BudgetLedger budgetLedger(BudgetMapper mapper, PlatformTransactionManager manager) {
        return new PostgresBudgetLedger(mapper, manager);
    }
}
