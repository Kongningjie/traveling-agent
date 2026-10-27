package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.application.execution.RunService;
import com.lawrence.travel.application.execution.StageExecutor;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.ExecutionResources;
import com.lawrence.travel.application.port.StageHandler;
import com.lawrence.travel.infrastructure.execution.PersistentStageWorker;
import com.lawrence.travel.infrastructure.execution.persistence.EngineMapper;
import com.lawrence.travel.infrastructure.execution.persistence.PostgresExecutionEngine;
import com.lawrence.travel.infrastructure.persistence.control.BudgetMapper;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import com.lawrence.travel.infrastructure.travel.TravelMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.util.List;

/**
 * 本地单实例执行引擎；没有注册业务处理器时不派发阶段，外部调用开关仍独立关闭。
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class EngineConfiguration {
    /**
     * @param mapper 诊断
     * @param ownership 归属
     * @param tx 事务
     * @param json 元数据
     * @return 只存执行事实的PG轨迹
     */
    @Bean
    com.lawrence.travel.application.port.ExecutionTrace executionTrace(
            com.lawrence.travel.infrastructure.execution.jobs.DiagnosticMapper mapper,
            com.lawrence.travel.infrastructure.execution.jobs.QuotaMapper ownership,
            BusinessTransactions tx,
            TravelJson json) {
        return new com.lawrence.travel.infrastructure.execution.jobs.PostgresExecutionTrace(
                mapper, ownership, tx, json);
    }

    /**
     * @param mapper 执行 SQL
     * @param tasks 任务 SQL
     * @param budgets 权威预算
     * @param json JSON
     * @param manager 事务
     * @param controls 已确认预算
     * @param projection 同事务候选报告投影
     * @return 唯一业务执行仓储
     */
    @Bean
    ExecutionEngineStore executionEngineStore(
            EngineMapper mapper,
            TravelMapper tasks,
            BudgetMapper budgets,
            TravelJson json,
            org.springframework.transaction.PlatformTransactionManager manager,
            ExecutionControlProperties controls,
            com.lawrence.travel.application.port.StageProjection projection) {
        return new PostgresExecutionEngine(
                mapper,
                tasks,
                budgets,
                json,
                manager,
                controls.limits().runLimits(),
                controls.limits().activeRuns(),
                controls.limits().researchConcurrencyPerRun(),
                Duration.ofSeconds(60),
                3,
                projection);
    }

    /**
     * @param store 执行权威
     * @param handlers 显式业务处理器，当前允许为空
     * @param trace 受控事实轨迹
     * @return 唯一阶段执行器
     */
    @Bean
    StageExecutor stageExecutor(
            ExecutionEngineStore store,
            List<StageHandler> handlers,
            com.lawrence.travel.application.port.ExecutionTrace trace) {
        return new StageExecutor(store, handlers, trace);
    }

    /**
     * @param store 执行权威
     * @param executor 阶段处理
     * @param resources 物理容量
     * @param controls 初始上限
     * @param enabled 是否启动轮询
     * @param lifecycle 最新删除清单恢复屏障
     * @param storage 部署容量水位
     * @return 后台阶段调度，关闭时停止领取
     */
    @org.springframework.context.annotation.DependsOn("lifecycleReady")
    @Bean(destroyMethod = "close")
    PersistentStageWorker persistentStageWorker(
            ExecutionEngineStore store,
            StageExecutor executor,
            ExecutionResources resources,
            ExecutionControlProperties controls,
            @Value("${travel.engine.enabled:true}") boolean enabled,
            com.lawrence.travel.application.lifecycle.LifecycleService lifecycle,
            com.lawrence.travel.application.port.StorageAdmission storage) {
        var worker =
                new PersistentStageWorker(
                        store,
                        executor,
                        resources,
                        controls.limits().researchConcurrencyPerRun(),
                        Duration.ofSeconds(15));
        worker.readiness(() -> lifecycle.ready() && storage.accepting());
        if (enabled) worker.start();
        return worker;
    }

    /**
     * @param store 执行权威
     * @param transactions 同源事务
     * @param worker 尽力取消句柄
     * @return 运行用例与任务变更观察者
     */
    @Bean
    RunService runService(
            ExecutionEngineStore store,
            BusinessTransactions transactions,
            PersistentStageWorker worker) {
        return new RunService(store, transactions, worker);
    }
}
