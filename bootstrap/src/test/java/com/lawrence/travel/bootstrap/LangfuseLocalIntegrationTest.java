package com.lawrence.travel.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.lawrence.travel.infrastructure.evaluation.LangfuseProbe;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/** 显式启用才访问独立本地 Langfuse，只上传两个固定合成节点。 */
@EnabledIfEnvironmentVariable(named = "LANGFUSE_LOCAL_VERIFY", matches = "true")
class LangfuseLocalIntegrationTest {
    /**
     * 查询平台 v2 API 确认两个节点的名称、父子关系和环境，不凭接收状态判成功。
     *
     * @throws Exception 本机服务不可用、响应无法解析或轮询被中断
     */
    @Test
    void syntheticTraceIsQueryable() throws Exception {
        String publicKey = System.getenv("LF_PUBLIC_KEY");
        String secretKey = System.getenv("LF_SECRET_KEY");
        assertTrue(publicKey != null && secretKey != null, "LOCAL_CREDENTIALS_REQUIRED");
        Instant from = Instant.now().minusSeconds(60);
        var receipt =
                LangfuseProbe.send(
                        URI.create("http://127.0.0.1:3100/api/public/otel/v1/traces"),
                        publicKey,
                        secretKey);
        assertTrue(receipt.accepted(), "OTLP_EXPORT_FAILED");
        String auth =
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        (publicKey + ":" + secretKey)
                                                .getBytes(StandardCharsets.UTF_8));
        String query =
                "http://127.0.0.1:3100/api/public/v2/observations?traceId="
                        + receipt.traceId()
                        + "&fromStartTime="
                        + URLEncoder.encode(from.toString(), StandardCharsets.UTF_8)
                        + "&toStartTime="
                        + URLEncoder.encode(
                                Instant.now().plusSeconds(60).toString(), StandardCharsets.UTF_8)
                        + "&fields=core,basic&limit=10";
        var request =
                HttpRequest.newBuilder(URI.create(query))
                        .timeout(Duration.ofSeconds(5))
                        .header("Authorization", auth)
                        .GET()
                        .build();
        var json = JsonMapper.builder().build();
        try (var client =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(3))
                        .build()) {
            for (int attempt = 0; attempt < 30; attempt++) {
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), "LANGFUSE_QUERY_FAILED");
                var rows = json.readTree(response.body()).path("data");
                if (rows.size() == 2) {
                    boolean parent = false;
                    boolean child = false;
                    for (var row : rows) {
                        assertEquals(receipt.traceId(), row.path("traceId").asString());
                        assertEquals("local-contract", row.path("environment").asString());
                        if (receipt.parentSpanId().equals(row.path("id").asString())) {
                            parent = true;
                            assertEquals("lf1.synthetic.probe", row.path("name").asString());
                            assertTrue(
                                    row.path("parentObservationId").isNull()
                                            || row.path("parentObservationId")
                                                    .asString()
                                                    .isEmpty());
                        } else if (receipt.childSpanId().equals(row.path("id").asString())) {
                            child = true;
                            assertEquals("lf1.synthetic.child", row.path("name").asString());
                            assertEquals(
                                    receipt.parentSpanId(),
                                    row.path("parentObservationId").asString());
                        }
                    }
                    assertTrue(parent && child, "PARENT_CHILD_NOT_FOUND");
                    return;
                }
                Thread.sleep(1000);
            }
        }
        fail("LANGFUSE_SYNTHETIC_TRACE_NOT_QUERYABLE");
    }
}
