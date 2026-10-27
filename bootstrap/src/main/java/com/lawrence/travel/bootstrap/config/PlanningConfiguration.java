package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.agent.runtime.HarnessPlanningAdvisor;
import com.lawrence.travel.application.itinerary.PlanningHandler;
import com.lawrence.travel.application.port.AgentRuntimePort;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.application.port.CandidateStore;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.PlanningAdvisor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 装配初始及局部重规划闭环，复用唯一执行器和阶段恢复。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class PlanningConfiguration {
    /**
     * @param runtime 计量框架
     * @param json JSON
     * @param controls 真实开关
     * @return 无工具独立模型角色
     */
    @Bean
    PlanningAdvisor planningAdvisor(
            AgentRuntimePort runtime, InteractionCodec json, ExecutionControlProperties controls) {
        return new HarnessPlanningAdvisor(runtime, json, controls.externalCallsEnabled());
    }

    /**
     * @param store 业务权威
     * @param advisor 模型策略
     * @param json JSON
     * @param budgets 原预算
     * @return 共享校验审核门禁的规划阶段处理器
     */
    @Bean
    PlanningHandler planningHandler(
            CandidateStore store,
            PlanningAdvisor advisor,
            InteractionCodec json,
            BudgetLedger budgets) {
        return new PlanningHandler(store, advisor, json, budgets);
    }
}
