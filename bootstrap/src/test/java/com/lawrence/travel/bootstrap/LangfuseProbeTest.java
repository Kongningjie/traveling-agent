package com.lawrence.travel.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lawrence.travel.infrastructure.evaluation.LangfuseProbe;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 用本机 HTTP 替身验证 OTLP 协议与地址限制，不访问模型或真实会话。 */
class LangfuseProbeTest {
    /**
     * 两个节点使用 OTLP protobuf，携带项目认证及 v4 入库头。
     *
     * @throws Exception 本机 HTTP 替身无法启动
     */
    @Test
    void exportsTwoSyntheticSpansWithAuthentication() throws Exception {
        var requests = new AtomicInteger();
        var valid = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/public/otel/v1/traces",
                exchange -> {
                    var headers = exchange.getRequestHeaders();
                    String expected =
                            "Basic "
                                    + Base64.getEncoder()
                                            .encodeToString(
                                                    "synthetic-public:synthetic-secret"
                                                            .getBytes(StandardCharsets.UTF_8));
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    if (expected.equals(headers.getFirst("Authorization"))
                            && "4".equals(headers.getFirst("x-langfuse-ingestion-version"))
                            && headers.getFirst("Content-Type").contains("protobuf")
                            && body.length > 0) valid.incrementAndGet();
                    requests.incrementAndGet();
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        server.start();
        try {
            var result =
                    LangfuseProbe.send(
                            URI.create(
                                    "http://127.0.0.1:"
                                            + server.getAddress().getPort()
                                            + "/api/public/otel/v1/traces"),
                            "synthetic-public",
                            "synthetic-secret");
            assertTrue(result.accepted());
            assertEquals(2, requests.get());
            assertEquals(2, valid.get());
            assertEquals(32, result.traceId().length());
            assertEquals(16, result.parentSpanId().length());
            assertNotEquals(result.parentSpanId(), result.childSpanId());
        } finally {
            server.stop(0);
        }
    }

    /** 外网、认证嵌入 URL、查询参数和错误路径均在发送前拒绝。 */
    @Test
    void rejectsNonLocalOrAmbiguousEndpoints() {
        for (String endpoint :
                new String[] {
                    "https://example.com/api/public/otel/v1/traces",
                    "http://localhost:3100/api/public/otel/v1/traces",
                    "http://user@127.0.0.1:3100/api/public/otel/v1/traces",
                    "http://127.0.0.1:3100/api/public/otel/v1/traces?secret=value",
                    "http://127.0.0.1:3100/wrong"
                }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> LangfuseProbe.send(URI.create(endpoint), "synthetic", "synthetic"));
        }
    }

    /**
     * 认证失败不能被成功 flush 掩盖成已接收。
     *
     * @throws Exception 本机 HTTP 替身无法启动
     */
    @Test
    void reportsRejectedExports() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/public/otel/v1/traces",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(401, -1);
                    exchange.close();
                });
        server.start();
        try {
            var result =
                    LangfuseProbe.send(
                            URI.create(
                                    "http://127.0.0.1:"
                                            + server.getAddress().getPort()
                                            + "/api/public/otel/v1/traces"),
                            "synthetic-public",
                            "synthetic-secret");
            assertFalse(result.accepted());
        } finally {
            server.stop(0);
        }
    }
}
