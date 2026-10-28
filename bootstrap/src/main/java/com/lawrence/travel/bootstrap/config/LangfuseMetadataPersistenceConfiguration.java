package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.infrastructure.evaluation.LangfuseMetadataMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 注册元数据投影SQL；不创建导出线程，默认local启动仍不采集。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@MapperScan(basePackageClasses = LangfuseMetadataMapper.class)
public class LangfuseMetadataPersistenceConfiguration {}
