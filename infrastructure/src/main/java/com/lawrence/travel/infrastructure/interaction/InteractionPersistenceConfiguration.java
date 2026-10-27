package com.lawrence.travel.infrastructure.interaction;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 只扫描协商 SQL，业务装配由 bootstrap 完成。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@MapperScan(basePackageClasses = NegotiationMapper.class)
public class InteractionPersistenceConfiguration {}
