package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.application.execution.TravelToolService;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.application.port.ToolEvidenceJournal;
import com.lawrence.travel.application.port.TravelToolPort;
import com.lawrence.travel.infrastructure.persistence.control.PostgresToolEvidenceJournal;
import com.lawrence.travel.infrastructure.persistence.control.ToolEvidenceMapper;
import com.lawrence.travel.infrastructure.tools.TravelHttpTools;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

/** local 装配受控旅行工具，smoke 不连接数据库及供应商。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@EnableConfigurationProperties(TravelToolsConfiguration.Properties.class)
public class TravelToolsConfiguration {
    /**
     * 不含密钥的工具配置，缺失时默认关闭。
     * @param enabled 外部工具开关
     * @param grants 已确认服务组资源
     * @param evidenceHosts 已筛选原文域名
     */
    @ConfigurationProperties("travel.tools")
    public record Properties(boolean enabled, Map<String, TravelToolService.Grant> grants, Set<String> evidenceHosts) {
        /** 缺失配置不能扩大资源或域名权限。 */
        public Properties {
            grants = grants == null ? Map.of() : Map.copyOf(grants);
            evidenceHosts = evidenceHosts == null ? Set.of() : Set.copyOf(evidenceHosts);
        }
    }

    /**
     * 提供只在调用时读取凭证的适配器。
     * @param environment 本机环境，不序列化其内容
     * @param properties 域名白名单
     * @return 工具端口
     */
    @Bean
    TravelToolPort travelToolPort(Environment environment, Properties properties) {
        return new TravelHttpTools(() -> environment.getProperty("AMAP_API_KEY"),
                () -> environment.getProperty("TAVILY_API_KEY"), properties.evidenceHosts());
    }

    /**
     * 绑定证据权威仓储。
     * @param mapper 已被同包 MapperScan 扫描的 SQL
     * @param manager 数据库事务管理器
     * @return 工具配额及证据仓储
     */
    @Bean
    ToolEvidenceJournal toolEvidenceJournal(ToolEvidenceMapper mapper, PlatformTransactionManager manager) {
        return new PostgresToolEvidenceJournal(mapper, manager);
    }

    /**
     * 装配原运行预算与工具服务组保护。
     * @param tools 供应商工具
     * @param journal 证据仓储
     * @param ledger 原运行计量
     * @param execution 冻结预算配置
     * @param properties 工具免费资源配置
     * @param safety 共用输入输出基础检查
     * @return 默认关闭真实调用的应用服务
     */
    @Bean
    TravelToolService travelToolService(TravelToolPort tools, ToolEvidenceJournal journal, BudgetLedger ledger,
                                      ExecutionControlProperties execution, Properties properties, com.lawrence.travel.application.safety.SafetyGate safety) {
        return new TravelToolService(tools, journal, ledger, execution.limits().runLimits(),
                properties.grants(), properties.enabled(), Clock.systemUTC(),safety);
    }
}
