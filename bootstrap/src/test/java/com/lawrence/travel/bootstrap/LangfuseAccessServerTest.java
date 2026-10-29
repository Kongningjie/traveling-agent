package com.lawrence.travel.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** 本机控制HTTP测试；资格使用受控替身，真实PG期限另由集成测试验证。 */
class LangfuseAccessServerTest {
    private final Instant now = Instant.parse("2026-10-29T04:00:00Z");
    private final String token = "1".repeat(64);

    /**
     * 正确令牌才查资格；控制查询不返回正文或允许任意路径。
     *
     * @throws Exception 本机HTTP通信失败
     */
    @Test
    void authenticatedControlRequestReturnsShortDeadlineOnly() throws Exception {
        var store = mock(EvaluationPlatformAccess.Store.class);
        when(store.decision())
                .thenReturn(new EvaluationPlatformAccess.Decision(true, now.plusSeconds(1)));
        try (var server = server(store);
                var client = HttpClient.newHttpClient()) {
            server.start();
            verify(store).closeAccess();
            assertEquals(403, get(client, server, "/access", null).statusCode());
            assertEquals(403, get(client, server, "/access", "2".repeat(64)).statusCode());
            assertEquals(403, get(client, server, "/access?ignored=true", token).statusCode());
            var result = get(client, server, "/access", token);
            assertEquals(204, result.statusCode());
            assertEquals("no-store", result.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(
                    Long.toString(now.plusSeconds(1).toEpochMilli()),
                    result.headers().firstValue("X-Access-Not-After").orElseThrow());
            assertTrue(result.body().isEmpty());
        }
    }

    /**
     * 未核验、已到期、空台账和存储故障均拒绝，不回显内部诊断。
     *
     * @throws Exception 本机HTTP通信失败
     */
    @Test
    void expiryMissingStateAndStorageFailureAllFailClosed() throws Exception {
        var store = mock(EvaluationPlatformAccess.Store.class);
        when(store.decision())
                .thenReturn(new EvaluationPlatformAccess.Decision(false, now.plusSeconds(1)))
                .thenReturn(new EvaluationPlatformAccess.Decision(true, now))
                .thenReturn(null)
                .thenThrow(new IllegalStateException("synthetic-private-diagnostic"));
        try (var server = server(store);
                var client = HttpClient.newHttpClient()) {
            server.start();
            for (int attempt = 0; attempt < 4; attempt++) {
                var result = get(client, server, "/access", token);
                assertEquals(503, result.statusCode());
                assertTrue(result.body().isEmpty());
                assertTrue(result.headers().firstValue("X-Access-Not-After").isEmpty());
            }
        }
    }

    /**
     * 服务重启先撤销备份或旧进程留下的开放状态，不能自动沿用。
     *
     * @throws Exception 本机HTTP通信失败
     */
    @Test
    void startingControlServerClosesPreviouslyEnabledAccess() throws Exception {
        var state = new AtomicBoolean(true);
        var store = mock(EvaluationPlatformAccess.Store.class);
        doAnswer(
                        call -> {
                            state.set(false);
                            return null;
                        })
                .when(store)
                .closeAccess();
        when(store.decision())
                .thenAnswer(
                        call ->
                                new EvaluationPlatformAccess.Decision(
                                        state.get(), now.plusSeconds(1)));
        try (var server = server(store);
                var client = HttpClient.newHttpClient()) {
            server.start();
            assertEquals(503, get(client, server, "/access", token).statusCode());
        }
    }

    /**
     * 绑定回环随机端口，测试令牌与真实.env无关。
     *
     * @param store 资格替身
     * @return 尚未启动的控制服务
     * @throws Exception 无法绑定本机端口
     */
    private LangfuseAccessServer server(EvaluationPlatformAccess.Store store) throws Exception {
        return new LangfuseAccessServer(
                store,
                Clock.fixed(now, ZoneOffset.UTC),
                new InetSocketAddress("127.0.0.1", 0),
                token);
    }

    /**
     * 发送受控查询，不读取登录会话或.env。
     *
     * @param client 本机HTTP客户端
     * @param server 控制服务
     * @param path 受控测试路径
     * @param suppliedToken 合成令牌；null表示不提供
     * @return 完整控制响应
     * @throws Exception 本机HTTP失败
     */
    private HttpResponse<String> get(
            HttpClient client, LangfuseAccessServer server, String path, String suppliedToken)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                        .GET();
        if (suppliedToken != null) builder.header("X-Evaluation-Gate", suppliedToken);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
