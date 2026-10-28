package com.lawrence.travel.infrastructure.evaluation;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 仅验证本机 Langfuse 的合成父子链路，不注册全局 SDK、不读取业务上下文。 */
public final class LangfuseProbe {
    /** 禁止实例化无状态探针。 */
    private LangfuseProbe() {}

    /**
     * 发送固定合成节点并等待导出结束；生产链路不调用此方法。
     *
     * @param endpoint 本机 IPv4 回环 OTLP HTTP 地址，必须包含 /api/public/otel/v1/traces
     * @param publicKey 本地项目公钥，不写入节点属性
     * @param secretKey 本地项目密钥，不写入节点属性
     * @return 导出结果及父子标识；成功仅代表接收，仍须查询平台确认入库
     * @throws IllegalArgumentException 地址或认证参数不合法
     */
    public static Receipt send(URI endpoint, String publicKey, String secretKey) {
        if (endpoint == null
                || !"http".equals(endpoint.getScheme())
                || !"127.0.0.1".equals(endpoint.getHost())
                || endpoint.getPort() < 1
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || !"/api/public/otel/v1/traces".equals(endpoint.getPath())
                || publicKey == null
                || publicKey.isBlank()
                || secretKey == null
                || secretKey.isBlank()) {
            throw new IllegalArgumentException("LANGFUSE_PROBE_CONFIG_INVALID");
        }
        String authentication =
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        (publicKey + ":" + secretKey)
                                                .getBytes(StandardCharsets.UTF_8));
        // 探针只生成两个固定节点，使用独立 provider，避免自动采集真实请求和正文。
        var exporter =
                OtlpHttpSpanExporter.builder()
                        .setEndpoint(endpoint.toString())
                        .setTimeout(Duration.ofSeconds(3))
                        .addHeader("Authorization", authentication)
                        .addHeader("x-langfuse-ingestion-version", "4")
                        .build();
        var failed = new AtomicBoolean();
        // flush 成功不等于此前 export 成功，单独汇总每次请求结果。
        SpanExporter checked =
                new SpanExporter() {
                    /** {@inheritDoc} 记录请求失败，不保存响应正文。 */
                    @Override
                    public CompletableResultCode export(Collection<SpanData> spans) {
                        var result = exporter.export(spans);
                        result.whenComplete(
                                () -> {
                                    if (!result.isSuccess()) failed.set(true);
                                });
                        return result;
                    }

                    /** {@inheritDoc} 刷新探针导出器。 */
                    @Override
                    public CompletableResultCode flush() {
                        return exporter.flush();
                    }

                    /** {@inheritDoc} 关闭探针专属连接。 */
                    @Override
                    public CompletableResultCode shutdown() {
                        return exporter.shutdown();
                    }
                };
        try (var provider =
                SdkTracerProvider.builder()
                        .setResource(
                                Resource.create(
                                        Attributes.of(
                                                AttributeKey.stringKey("service.name"),
                                                "traveling-agent-lf1-probe")))
                        .addSpanProcessor(SimpleSpanProcessor.create(checked))
                        .build()) {
            var tracer = provider.get("traveling-agent.langfuse.probe");
            Span root =
                    tracer.spanBuilder("lf1.synthetic.probe")
                            .setNoParent()
                            .setAttribute("langfuse.trace.name", "LF-1 synthetic probe")
                            .setAttribute("langfuse.environment", "local-contract")
                            .startSpan();
            Span child =
                    tracer.spanBuilder("lf1.synthetic.child")
                            .setParent(Context.root().with(root))
                            .setAttribute("langfuse.trace.name", "LF-1 synthetic probe")
                            .setAttribute("langfuse.environment", "local-contract")
                            .startSpan();
            child.end();
            root.end();
            boolean accepted =
                    provider.forceFlush().join(5, TimeUnit.SECONDS).isSuccess() && !failed.get();
            return new Receipt(
                    root.getSpanContext().getTraceId(),
                    root.getSpanContext().getSpanId(),
                    child.getSpanContext().getSpanId(),
                    accepted);
        }
    }

    /**
     * 合成探针的非敏感标识；不包含认证或输入输出。
     *
     * @param traceId OTEL 128 位轨迹 ID
     * @param parentSpanId 合成父节点 64 位 ID
     * @param childSpanId 合成子节点 64 位 ID
     * @param accepted 两次导出及刷新是否成功，不代表平台已入库
     */
    public record Receipt(
            String traceId, String parentSpanId, String childSpanId, boolean accepted) {}
}
