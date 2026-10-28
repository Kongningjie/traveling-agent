package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.infrastructure.evaluation.LangfuseMetadataExport;
import com.lawrence.travel.infrastructure.evaluation.LangfuseMetadataMapper;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 仅显式验证profile装配；LF-3治理完成前不提供生产采集开关。 */
@Configuration(proxyBeanMethods = false)
@Profile("local & langfuse-validation")
public class LangfuseValidationConfiguration {
    /**
     * 创建受合成用户和本轮时间约束的本机导出器，凭据只从进程环境读取。
     *
     * @param mapper 事实投影SQL
     * @param syntheticUser 已建立的lf2_test_前缀随机用户
     * @return 独立有界导出器；正常local profile不注册
     * @throws IllegalStateException 合成身份过期、配置缺失或密钥形状不合法
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    LangfuseMetadataExport langfuseMetadataExport(
            LangfuseMetadataMapper mapper,
            @Value("${travel.langfuse.synthetic-user}") UUID syntheticUser) {
        var startedAt = mapper.validationStartedAt(syntheticUser);
        if (startedAt == null) throw new IllegalStateException("LANGFUSE_SYNTHETIC_USER_REQUIRED");
        String publicKey = required("LF_PUBLIC_KEY");
        String secretKey = required("LF_SECRET_KEY");
        String salt = required("LF_SALT");
        if (!salt.matches("[0-9a-fA-F]{64}"))
            throw new IllegalStateException("LANGFUSE_SALT_INVALID");
        String auth =
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        (publicKey + ":" + secretKey)
                                                .getBytes(StandardCharsets.UTF_8));
        var exporter =
                OtlpHttpSpanExporter.builder()
                        .setEndpoint("http://127.0.0.1:3100/api/public/otel/v1/traces")
                        // 重试统一由PG计数，关闭SDK内层重试，避免次数叠加。
                        .setRetryPolicy(null)
                        .setTimeout(Duration.ofSeconds(3))
                        .addHeader("Authorization", auth)
                        .addHeader("x-langfuse-ingestion-version", "4")
                        .build();
        return new LangfuseMetadataExport(
                mapper,
                syntheticUser,
                startedAt,
                HexFormat.of().parseHex(salt),
                "local-development",
                exporter,
                128);
    }

    /**
     * 读取本地验证秘密，缺失只报配置名，不附原值。
     *
     * @param name 固定环境变量名
     * @return 非空本地配置
     * @throws IllegalStateException 配置缺失；异常不包含配置值
     */
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank())
            throw new IllegalStateException("LANGFUSE_LOCAL_CONFIG_REQUIRED: " + name);
        return value;
    }
}
