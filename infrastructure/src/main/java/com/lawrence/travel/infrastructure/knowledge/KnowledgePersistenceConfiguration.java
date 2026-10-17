package com.lawrence.travel.infrastructure.knowledge;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 字段证据 SQL 的精确扫描，不建立独立业务调度。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@MapperScan(basePackageClasses = EvidenceMapper.class)
public class KnowledgePersistenceConfiguration { }
