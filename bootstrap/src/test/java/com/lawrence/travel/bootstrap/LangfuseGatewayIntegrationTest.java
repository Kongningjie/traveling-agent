package com.lawrence.travel.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lawrence.travel.application.evaluation.EvaluationPlatformAccess;
import com.lawrence.travel.infrastructure.evaluation.LangfuseAccessServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 显式启用才验证本机Compose网关到宿主控制服务的连接；全程拒绝资格，不开放平台正文。 */
@EnabledIfEnvironmentVariable(named = "LANGFUSE_GATE_VERIFY", matches = "true")
class LangfuseGatewayIntegrationTest {
    /**
     * 实际Docker请求到达控制器仍拒绝；存储异常及控制器关闭同样不能绕过网关。
     *
     * @throws Exception 本机HTTP或控制端口不可用
     */
    @Test
    void actualContainerIngressStaysClosedForUnverifiedUnavailableAndStoppedControl()
            throws Exception {
        String token = System.getenv("LF_GATE_TOKEN");
        var calls = new AtomicInteger();
        var mode = new AtomicInteger();
        var store = mock(EvaluationPlatformAccess.Store.class);
        when(store.decision())
                .thenAnswer(
                        call -> {
                            calls.incrementAndGet();
                            if (mode.get() == 1)
                                throw new IllegalStateException("synthetic-not-for-output");
                            return new EvaluationPlatformAccess.Decision(
                                    false, Instant.now().plusSeconds(1));
                        });
        try (var client = HttpClient.newHttpClient()) {
            try (var server =
                    new LangfuseAccessServer(
                            store,
                            Clock.systemUTC(),
                            new InetSocketAddress("0.0.0.0", 8093),
                            token)) {
                server.start();
                assertClosed(client);
                assertTrue(calls.get() > 0, "DOCKER_DID_NOT_REACH_AUTHENTICATED_CONTROL");
                int before = calls.get();
                mode.set(1);
                assertClosed(client);
                assertTrue(calls.get() > before, "STORAGE_FAILURE_DID_NOT_REACH_CONTROL");
            }
            assertClosed(client);
        }
    }

    /**
     * 从真实宿主入口读取固定状态，不向内部Web发写入或查询记录的请求。
     *
     * @param client 本机HTTP客户端
     * @throws Exception 本机连接失败
     */
    private void assertClosed(HttpClient client) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:3100/api/public/health"))
                        .timeout(java.time.Duration.ofSeconds(5))
                        .GET()
                        .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(503, response.statusCode());
        assertEquals("LANGFUSE_ACCESS_CLOSED", response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }
}
