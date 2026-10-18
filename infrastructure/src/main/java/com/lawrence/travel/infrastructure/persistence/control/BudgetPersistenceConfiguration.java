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
     * @param quotas 用户每日配额SQL
     * @param diagnostics 固定分类追踪SQL
     * @param policy 输入初始策略，创建后按持久快照执行
     * @return 应用层持久化端口
     */
    @Bean
    BudgetLedger budgetLedger(BudgetMapper mapper, PlatformTransactionManager manager, com.lawrence.travel.infrastructure.execution.jobs.QuotaMapper quotas, com.lawrence.travel.infrastructure.execution.jobs.DiagnosticMapper diagnostics, com.lawrence.travel.application.execution.JobPolicy policy) {
        return new PostgresBudgetLedger(mapper, manager, quotas, diagnostics, policy);
    }
}
