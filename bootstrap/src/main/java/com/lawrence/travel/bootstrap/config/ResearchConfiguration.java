package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.agent.runtime.HarnessResearchAdvisor;
import com.lawrence.travel.application.execution.TravelToolService;
import com.lawrence.travel.application.knowledge.EvidencePolicy;
import com.lawrence.travel.application.knowledge.ResearchHandler;
import com.lawrence.travel.application.port.AgentRuntimePort;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.ResearchAdvisor;
import com.lawrence.travel.application.port.ResearchStore;
import com.lawrence.travel.domain.knowledge.FieldEvidence;
import com.lawrence.travel.infrastructure.execution.persistence.EngineMapper;
import com.lawrence.travel.infrastructure.knowledge.EvidenceMapper;
import com.lawrence.travel.infrastructure.knowledge.PostgresResearchStore;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import com.lawrence.travel.infrastructure.travel.TravelMapper;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 复用工具及 Harness 端口；人工种子通过本地配置提供，无默认虚构旅行事实。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@EnableConfigurationProperties(ResearchConfiguration.Properties.class)
public class ResearchConfiguration {
    /**
     * @param store 证据端口
     * @param transactions 短事务
     * @return 只读及显式撤回用例
     */
    @Bean
    com.lawrence.travel.application.knowledge.EvidenceService evidenceService(
            ResearchStore store,
            com.lawrence.travel.application.port.BusinessTransactions transactions) {
        return new com.lawrence.travel.application.knowledge.EvidenceService(store, transactions);
    }

    /**
     * @param seeds 人工核对的来源、更新时间、字段和日期，缺失不补造
     * @param cacheTtls 工具类别的正 TTL 覆盖，不改变日期适用或来源规则
     */
    @ConfigurationProperties("travel.research")
    public record Properties(
            List<FieldEvidence> seeds,
            java.util.Map<
                            com.lawrence.travel.application.port.TravelToolPort.Operation,
                            java.time.Duration>
                    cacheTtls) {
        /** 限制本地种子规模，避免无限上下文；生产资料不从模型生成配置。 */
        public Properties {
            seeds = seeds == null ? List.of() : List.copyOf(seeds);
            cacheTtls = cacheTtls == null ? java.util.Map.of() : java.util.Map.copyOf(cacheTtls);
            if (seeds.size() > 50) throw new IllegalArgumentException("SEED_LIMIT");
        }
    }

    /**
     * @param mapper 证据 SQL
     * @param tasks 任务 SQL
     * @param engine 引擎 SQL
     * @param json JSON
     * @return 字段权威存储
     */
    @Bean
    ResearchStore researchStore(
            EvidenceMapper mapper, TravelMapper tasks, EngineMapper engine, TravelJson json) {
        return new PostgresResearchStore(mapper, tasks, engine, json);
    }

    /**
     * @param runtime 原预算框架
     * @param json JSON
     * @param controls 默认关闭的真实开关
     * @param tools 官方域名
     * @return 独立调查策略端口
     */
    @Bean
    ResearchAdvisor researchAdvisor(
            AgentRuntimePort runtime,
            InteractionCodec json,
            ExecutionControlProperties controls,
            TravelToolsConfiguration.Properties tools) {
        return new HarnessResearchAdvisor(
                runtime, json, controls.externalCallsEnabled(), tools.evidenceHosts());
    }

    /**
     * @param json JSON
     * @param tools 已筛选域名
     * @param properties 刷新策略
     * @return 来源及时效规则
     */
    @Bean
    EvidencePolicy evidencePolicy(
            InteractionCodec json,
            TravelToolsConfiguration.Properties tools,
            Properties properties) {
        return new EvidencePolicy(json, tools.evidenceHosts(), properties.cacheTtls());
    }

    /**
     * @param store 证据存储
     * @param advisor 策略
     * @param tools 工具用例
     * @param json JSON
     * @param policy 确定性规则
     * @param properties 本地种子
     * @return 唯一调查处理器
     */
    @Bean
    ResearchHandler researchHandler(
            ResearchStore store,
            ResearchAdvisor advisor,
            TravelToolService tools,
            InteractionCodec json,
            EvidencePolicy policy,
            Properties properties) {
        return new ResearchHandler(store, advisor, tools, json, policy, properties.seeds());
    }
}
