package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.application.memory.ContextAssembler;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.ContextStore;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.infrastructure.memory.ContextMapper;
import com.lawrence.travel.infrastructure.memory.PostgresContextStore;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 本地上下文和摘要装配；没有模型调用或第二套会话调度。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class ContextConfiguration {
    /**
     * @param mapper 会话SQL
     * @param travel 旅行归属
     * @param authority 原权威端口
     * @param tx 短事务
     * @param json JSON
     * @param diagnostics 固定分类问题记录
     * @return 统一消息与摘要端口
     */
    @Bean
    com.lawrence.travel.application.port.SessionContextStore sessionContextStore(
            com.lawrence.travel.infrastructure.memory.SessionContextMapper mapper,
            ContextMapper travel,
            ContextStore authority,
            BusinessTransactions tx,
            TravelJson json,
            com.lawrence.travel.infrastructure.execution.jobs.DiagnosticMapper diagnostics) {
        return new com.lawrence.travel.infrastructure.memory.PostgresSessionContextStore(
                mapper, travel, authority, tx, json, diagnostics);
    }

    /**
     * @param store 会话来源
     * @param json JSON
     * @param safety 安全门禁
     * @return 单一压缩用例
     */
    @Bean
    com.lawrence.travel.application.memory.SessionContextService sessionContextService(
            com.lawrence.travel.application.port.SessionContextStore store,
            InteractionCodec json,
            com.lawrence.travel.application.safety.SafetyGate safety) {
        return new com.lawrence.travel.application.memory.SessionContextService(
                store, json, safety);
    }

    /**
     * @param mapper 已由memory包扫描的SQL
     * @param json JSON
     * @param transactions 短事务
     * @return 唯一上下文权威端口
     */
    @Bean
    ContextStore contextStore(
            ContextMapper mapper, TravelJson json, BusinessTransactions transactions) {
        return new PostgresContextStore(mapper, json, transactions);
    }

    /**
     * @param store 权威状态
     * @param json 严格编解码
     * @return 不依赖框架的装配用例
     */
    @Bean
    ContextAssembler contextAssembler(ContextStore store, InteractionCodec json) {
        return new ContextAssembler(store, json);
    }
}
