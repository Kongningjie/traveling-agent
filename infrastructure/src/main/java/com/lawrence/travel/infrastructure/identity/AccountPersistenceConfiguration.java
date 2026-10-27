package com.lawrence.travel.infrastructure.identity;

import com.lawrence.travel.application.port.AccountStore;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 仅 local 扫描账号 Mapper，离线 smoke 不连接数据库。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@MapperScan(basePackageClasses = AccountMapper.class)
public class AccountPersistenceConfiguration {
    /**
     * 装配账号端口。
     * @param mapper 本模块 Mapper
     * @return PostgreSQL 账号仓储
     */
    @Bean
    AccountStore accountStore(AccountMapper mapper) {
        return new PostgresAccountStore(mapper);
    }
}
