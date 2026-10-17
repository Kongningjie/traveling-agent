package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.application.execution.RunService;
import com.lawrence.travel.application.itinerary.VersionService;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.TravelStore;
import com.lawrence.travel.application.port.VersionStore;
import com.lawrence.travel.infrastructure.itinerary.CandidateMapper;
import com.lawrence.travel.infrastructure.itinerary.PostgresVersionStore;
import com.lawrence.travel.infrastructure.itinerary.VersionMapper;
import com.lawrence.travel.infrastructure.execution.persistence.EngineMapper;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import com.lawrence.travel.infrastructure.travel.TravelMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 装配版本浏览与恢复，继续复用唯一执行和事务边界。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class VersionConfiguration {
    /** @param versions 版本SQL @param tasks 任务SQL @param candidates 用户锁 @param engine 执行SQL @param json JSON @return 私有历史端口 */
    @Bean VersionStore versionStore(VersionMapper versions, TravelMapper tasks, CandidateMapper candidates, EngineMapper engine, TravelJson json) { return new PostgresVersionStore(versions, tasks, candidates, engine, json); }
    /** @param versions 历史 @param travel 任务 @param runs 执行 @param transactions 事务 @param json JSON @return 恢复与比较用例 */
    @Bean VersionService versionService(VersionStore versions, TravelStore travel, RunService runs, BusinessTransactions transactions, InteractionCodec json) { return new VersionService(versions, travel, runs, transactions, json); }
}
