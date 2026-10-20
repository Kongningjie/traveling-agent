package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.application.execution.BackgroundJobService;
import com.lawrence.travel.application.execution.JobPolicy;
import com.lawrence.travel.application.port.JobHandler;
import com.lawrence.travel.application.port.JobStore;
import com.lawrence.travel.infrastructure.execution.PersistentStageWorker;
import com.lawrence.travel.infrastructure.execution.jobs.DiagnosticMapper;
import com.lawrence.travel.infrastructure.execution.jobs.JobMapper;
import com.lawrence.travel.infrastructure.execution.jobs.PostgresJobStore;
import com.lawrence.travel.infrastructure.execution.jobs.QuotaMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.List;

/** 唯一Worker装配独立作业，无附件处理器时只提供可靠登记能力。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@MapperScan(basePackageClasses = JobMapper.class)
public class JobConfiguration {
    /** @param e 非敏感配额配置 @return 输入和用户每日上限 */
    @Bean JobPolicy jobPolicy(Environment e) {
        return new JobPolicy(new com.lawrence.travel.domain.execution.control.RunLimits(
                e.getProperty("travel.jobs.model-limit", Long.class, 8L), e.getProperty("travel.jobs.tool-limit", Long.class, 12L), 0,
                Duration.ofSeconds(180), Duration.ofSeconds(e.getProperty("travel.jobs.active-seconds", Long.class, 300L))),
                e.getProperty("travel.jobs.daily-models", Long.class, 100L), e.getProperty("travel.jobs.daily-tools", Long.class, 200L), e.getProperty("travel.jobs.daily-parses", Long.class, 50L));
    }
    /** @param mapper SQL @param diagnostics 分类追踪 @param quotas 日配额 @param policy 限额 @param manager 事务 @param controls 共享模型工作上限 @return 持久端口 */
    @Bean JobStore jobStore(JobMapper mapper, DiagnosticMapper diagnostics, QuotaMapper quotas, JobPolicy policy, PlatformTransactionManager manager,
            ExecutionControlProperties controls) { return new PostgresJobStore(mapper, diagnostics, quotas, policy, manager, controls.limits().activeRuns()); }
    /** @param worker 既有轮询器 @param store 作业 @param handlers 当前真实实现 @param diagnostics 脱敏问题记录 @return 单例完成后注册两个容量类别，零处理器不领取 */
    @Bean org.springframework.beans.factory.SmartInitializingSingleton jobRegistration(PersistentStageWorker worker, JobStore store, List<JobHandler> handlers, DiagnosticMapper diagnostics) {
        return () -> { worker.problems((component, code) -> { var ids = com.lawrence.travel.application.execution.ExecutionCorrelation.current(); diagnostics.problem(java.util.UUID.randomUUID(), null, ids.traceId(), null, ids.requestId(), component, code); }); worker.register(new BackgroundJobService(store, handlers, false)); worker.register(new BackgroundJobService(store, handlers, true)); };
    }
}
