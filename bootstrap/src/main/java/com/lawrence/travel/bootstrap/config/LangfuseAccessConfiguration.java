package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.infrastructure.evaluation.EvaluationPlatformAccessMapper;
import com.lawrence.travel.infrastructure.evaluation.LangfuseAccessServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 本机治理专用profile；普通旅行助手启动不监听控制端口，不自动开放Langfuse。 */
@Configuration(proxyBeanMethods = false)
@Profile("local & langfuse-governance")
public class LangfuseAccessConfiguration {
    /**
     * 创建无正文的控制服务，启动时先关闭旧访问资格。
     *
     * @param store PG资格投影
     * @return 固定8093端口控制服务
     * @throws IOException 端口不可用
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    @DependsOnDatabaseInitialization
    LangfuseAccessServer langfuseAccessServer(EvaluationPlatformAccessMapper store)
            throws IOException {
        return new LangfuseAccessServer(
                store,
                Clock.systemUTC(),
                new InetSocketAddress("0.0.0.0", 8093),
                System.getenv("LF_GATE_TOKEN"));
    }
}
