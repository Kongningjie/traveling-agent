package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.application.port.TravelStore;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.TaskChangeObserver;
import com.lawrence.travel.application.travel.TravelStorageService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 旅行存储用例装配；变更与旧执行失效同事务，旅行 HTTP 接口在对应业务阶段接入。
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class TravelStorageConfiguration {
    /**
     * @param store 存储端口
     * @param transactions 同源事务
     * @param changes 失效旧执行
     * @return 从服务端身份派生归属的用例
     */
    @Bean
    TravelStorageService travelStorageService(
            TravelStore store, BusinessTransactions transactions, TaskChangeObserver changes) {
        return new TravelStorageService(store, transactions, changes);
    }
}
