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
    /** @param mapper 已由memory包扫描的SQL @param json JSON @param transactions 短事务 @return 唯一上下文权威端口 */
    @Bean ContextStore contextStore(ContextMapper mapper, TravelJson json, BusinessTransactions transactions) { return new PostgresContextStore(mapper, json, transactions); }
    /** @param store 权威状态 @param json 严格编解码 @return 不依赖框架的装配用例 */
    @Bean ContextAssembler contextAssembler(ContextStore store, InteractionCodec json) { return new ContextAssembler(store, json); }
}
