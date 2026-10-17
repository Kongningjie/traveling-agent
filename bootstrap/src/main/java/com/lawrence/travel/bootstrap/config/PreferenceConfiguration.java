package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.application.memory.PreferenceService;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.PreferenceStore;
import com.lawrence.travel.infrastructure.memory.PostgresPreferenceStore;
import com.lawrence.travel.infrastructure.memory.PreferenceMapper;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 偏好唯一装配入口；不启用框架默认memory文件。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@org.mybatis.spring.annotation.MapperScan(basePackageClasses = PreferenceMapper.class)
public class PreferenceConfiguration {
    /** @param mapper SQL @param json 规范JSON @return 权威仓储 */
    @Bean PreferenceStore preferenceStore(PreferenceMapper mapper, TravelJson json) { return new PostgresPreferenceStore(mapper, json); }
    /** @param store 权威端口 @param transactions 短事务 @return 用户偏好用例 */
    @Bean PreferenceService preferenceService(PreferenceStore store, BusinessTransactions transactions) { return new PreferenceService(store, transactions); }
}
