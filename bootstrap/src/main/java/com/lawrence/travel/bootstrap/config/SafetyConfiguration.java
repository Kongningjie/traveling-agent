package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.application.execution.ExecutionCorrelation;
import com.lawrence.travel.application.safety.SafetyGate;
import com.lawrence.travel.infrastructure.execution.jobs.DiagnosticMapper;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** 本地基础门禁：凭据仅在当前进程匹配，不传给模型、数据库或诊断。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class SafetyConfiguration {
    /**
     * @param environment 本机凭据提供器
     * @param diagnostics 固定问题记录
     * @return 检查异常失败关闭的门禁
     */
    @Bean
    SafetyGate safetyGate(Environment environment, DiagnosticMapper diagnostics) {
        return new SafetyGate(
                () ->
                        List.of(
                                        "DASHSCOPE_API_KEY",
                                        "AMAP_API_KEY",
                                        "TAVILY_API_KEY",
                                        "DOCUMENT_PARSER_SECRET",
                                        "POSTGRES_PASSWORD",
                                        "REDIS_PASSWORD")
                                .stream()
                                .map(k -> environment.getProperty(k, ""))
                                .toList(),
                (point, result) -> {
                    var ids = ExecutionCorrelation.current();
                    diagnostics.safety(
                            result.problemId(),
                            ids.traceId(),
                            ids.requestId(),
                            result.reason(),
                            result.policyVersion(),
                            point);
                });
    }
}
