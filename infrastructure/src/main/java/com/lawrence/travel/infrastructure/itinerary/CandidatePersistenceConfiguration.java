package com.lawrence.travel.infrastructure.itinerary;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 仅在本地持久化配置启用候选 SQL，不扫描应用端口或领域接口。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@MapperScan(basePackageClasses = CandidateMapper.class)
public class CandidatePersistenceConfiguration { }
