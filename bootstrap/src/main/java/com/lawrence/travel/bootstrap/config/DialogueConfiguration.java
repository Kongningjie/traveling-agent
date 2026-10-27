package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.agent.runtime.DialogueIntentInterpreter;
import com.lawrence.travel.application.execution.RunService;
import com.lawrence.travel.application.interaction.DialogueService;
import com.lawrence.travel.application.interaction.NegotiationService;
import com.lawrence.travel.application.itinerary.ItineraryService;
import com.lawrence.travel.application.itinerary.VersionService;
import com.lawrence.travel.application.memory.PreferenceService;
import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.AgentRuntimePort;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.DialogueInterpreter;
import com.lawrence.travel.application.port.DialogueStore;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.TravelStore;
import com.lawrence.travel.infrastructure.execution.PersistentStageWorker;
import com.lawrence.travel.infrastructure.interaction.DialogueMapper;
import com.lawrence.travel.infrastructure.interaction.PostgresDialogueStore;
import com.lawrence.travel.infrastructure.persistence.control.BudgetMapper;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import com.lawrence.travel.infrastructure.travel.TravelMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 装配聊天服务，并注册到后台Worker。
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class DialogueConfiguration {
    /**
     * @param mapper 聊天SQL
     * @param tasks 任务SQL
     * @param budgets 持久预算
     * @param json 编解码
     * @param jobs 唯一作业端口
     * @return 权威对话存储
     */
    @Bean
    DialogueStore dialogueStore(
            DialogueMapper mapper,
            TravelMapper tasks,
            BudgetMapper budgets,
            TravelJson json,
            com.lawrence.travel.application.port.JobStore jobs) {
        return new PostgresDialogueStore(mapper, tasks, budgets, json, jobs);
    }

    /**
     * @param runtime Harness
     * @param json JSON
     * @param controls 已有调用门禁
     * @return 无工具的一次协调理解
     */
    @Bean
    DialogueInterpreter dialogueInterpreter(
            AgentRuntimePort runtime, InteractionCodec json, ExecutionControlProperties controls) {
        return new DialogueIntentInterpreter(runtime, json, controls.externalCallsEnabled());
    }

    /**
     * @param store 聊天
     * @param interpreter 理解
     * @param tx 事务
     * @param accounts 身份
     * @param negotiation 协商
     * @param runs 运行
     * @param itineraries 候选
     * @param versions 历史
     * @param preferences 偏好
     * @param travel 需求
     * @param json 编解码
     * @param engine 原运行
     * @param controls 原调用许可
     * @param safety 输入输出基础检查
     * @param advice 原计划建议用例
     * @return 应用服务
     */
    @Bean
    DialogueService dialogueService(
            DialogueStore store,
            DialogueInterpreter interpreter,
            BusinessTransactions tx,
            AccountStore accounts,
            NegotiationService negotiation,
            RunService runs,
            ItineraryService itineraries,
            VersionService versions,
            PreferenceService preferences,
            TravelStore travel,
            InteractionCodec json,
            com.lawrence.travel.application.port.ExecutionEngineStore engine,
            ExecutionControlProperties controls,
            com.lawrence.travel.application.safety.SafetyGate safety,
            com.lawrence.travel.application.document.AdviceService advice) {
        return new DialogueService(
                store,
                interpreter,
                tx,
                accounts,
                negotiation,
                runs,
                itineraries,
                versions,
                preferences,
                travel,
                json,
                engine,
                controls.externalCallsEnabled(),
                safety,
                advice);
    }

    /**
     * @param mapper 建议SQL
     * @param chats 聊天SQL
     * @param travel 旅行基础
     * @param json JSON
     * @return 仅PG建议与回执
     */
    @Bean
    com.lawrence.travel.application.port.AdviceStore adviceStore(
            com.lawrence.travel.infrastructure.document.AdviceMapper mapper,
            DialogueMapper chats,
            TravelMapper travel,
            TravelJson json) {
        return new com.lawrence.travel.infrastructure.document.PostgresAdviceStore(
                mapper, chats, travel, json);
    }

    /**
     * @param store 建议
     * @param sources 真实引用
     * @param chats 聊天关联
     * @param negotiation 原变更
     * @param travel 原需求
     * @param tx 短事务
     * @param safety 内容检查
     * @return 明确逐项确认用例
     */
    @Bean
    com.lawrence.travel.application.document.AdviceService adviceService(
            com.lawrence.travel.application.port.AdviceStore store,
            com.lawrence.travel.application.document.SourceReadService sources,
            DialogueStore chats,
            NegotiationService negotiation,
            TravelStore travel,
            BusinessTransactions tx,
            com.lawrence.travel.application.safety.SafetyGate safety) {
        return new com.lawrence.travel.application.document.AdviceService(
                store, sources, chats, negotiation, travel, tx, safety);
    }

    /**
     * @param worker 唯一轮询器
     * @param service 应用端口
     * @return 全部单例完成后注册，避免运行控制与Worker装配循环
     */
    @Bean
    org.springframework.beans.factory.SmartInitializingSingleton dialogueRegistration(
            PersistentStageWorker worker, DialogueService service) {
        return () -> worker.register(service);
    }
}
