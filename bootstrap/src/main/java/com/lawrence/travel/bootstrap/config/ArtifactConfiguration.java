package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.application.artifact.ArtifactService;
import com.lawrence.travel.application.port.ArtifactFiles;
import com.lawrence.travel.application.port.ArtifactStore;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.infrastructure.artifact.ArtifactMapper;
import com.lawrence.travel.infrastructure.artifact.PostgresArtifactStore;
import com.lawrence.travel.infrastructure.artifact.VolumeArtifactFiles;
import java.nio.file.Path;
import java.time.Clock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** 必要产物唯一装配；文件根与可重建Harness工作区明确分离。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@MapperScan(basePackageClasses = ArtifactMapper.class)
public class ArtifactConfiguration {
    /** @param mapper SQL @return PostgreSQL索引端口 */
    @Bean ArtifactStore artifactStore(ArtifactMapper mapper) { return new PostgresArtifactStore(mapper); }
    /** @param environment 服务端受信配置 @return 独立文件卷适配器 */
    @Bean ArtifactFiles artifactFiles(Environment environment) { return new VolumeArtifactFiles(Path.of(environment.getProperty("travel.artifacts.root", ".runtime/artifacts"))); }
    /** @param store 索引 @param files 文件 @param transactions 短事务 @return 不依赖框架的产物用例 */
    @Bean ArtifactService artifactService(ArtifactStore store, ArtifactFiles files, BusinessTransactions transactions) { return new ArtifactService(store, files, transactions, Clock.systemUTC()); }
}
