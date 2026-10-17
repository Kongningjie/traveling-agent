package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.application.itinerary.ItineraryService;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.CandidateStore;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.infrastructure.execution.persistence.EngineMapper;
import com.lawrence.travel.infrastructure.itinerary.CandidateMapper;
import com.lawrence.travel.infrastructure.itinerary.PostgresCandidateStore;
import com.lawrence.travel.infrastructure.persistence.control.BudgetMapper;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import com.lawrence.travel.infrastructure.travel.TravelMapper;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 候选门禁本地装配；审核配置变化自动使旧报告绑定失效，不调用真实服务。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class CandidateConfiguration {
    /**
     * @param mapper 候选 SQL
     * @param tasks 任务 SQL
     * @param engine 执行 SQL
     * @param budgets 权威预算
     * @param json 规范 JSON
     * @param controls 角色配置
     * @param evidence 字段证据权威端口
     * @return 唯一业务报告投影
     */
    @Bean CandidateStore candidateStore(CandidateMapper mapper, TravelMapper tasks, EngineMapper engine, BudgetMapper budgets,
                                       TravelJson json, ExecutionControlProperties controls, com.lawrence.travel.application.port.ResearchStore evidence) {
        return new PostgresCandidateStore(mapper, tasks, engine, budgets, json, "itinerary-rules-v2",
                json.hash(json.encode(List.of("review-prompt-v2", controls.models().get("reviewer")))), Duration.ofMinutes(10), evidence);
    }
    /**
     * @param store 候选权威
     * @param engine 同源可靠执行
     * @param transactions 业务事务
     * @return 显式接受用例，无 HTTP 写入口
     */
    @Bean ItineraryService itineraryService(CandidateStore store, ExecutionEngineStore engine, BusinessTransactions transactions) {
        return new ItineraryService(store, engine, transactions);
    }
}
