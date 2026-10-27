package com.lawrence.travel.infrastructure.event;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 仅扫描本地事件读取 SQL。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@MapperScan(basePackageClasses = EventMapper.class)
public class EventPersistenceConfiguration {}
