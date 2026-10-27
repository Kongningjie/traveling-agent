package com.lawrence.travel.infrastructure.travel;

import com.lawrence.travel.application.port.TravelStore;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 仅 local 装配真实旅行仓储；smoke 不启用数据库。
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@MapperScan(basePackageClasses = TravelMapper.class)
public class TravelPersistenceConfiguration {
    /**
     * @return 固定 JSON 编码器，不带供应商凭据
     */
    @Bean
    TravelJson travelJson() {
        return new TravelJson();
    }

    /**
     * @param mapper 旅行 SQL
     * @param manager PostgreSQL 事务
     * @param json 编解码
     * @param evidence 字段归属检查端口
     * @return 原子存储端口
     */
    @Bean
    TravelStore travelStore(
            TravelMapper mapper,
            PlatformTransactionManager manager,
            TravelJson json,
            com.lawrence.travel.application.port.ResearchStore evidence) {
        return new PostgresTravelStore(mapper, manager, json, evidence);
    }
}
