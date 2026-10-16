package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.application.port.TravelStore;
import com.lawrence.travel.application.travel.TravelStorageService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 旅行存储用例装配；当前不开放未接入执行引擎的变更 HTTP 写接口。
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class TravelStorageConfiguration {
    /**
     * @param store 存储端口
     * @return 从服务端身份派生归属的用例
     */
    @Bean TravelStorageService travelStorageService(TravelStore store) { return new TravelStorageService(store); }
}
