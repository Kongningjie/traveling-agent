package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.execution.ModelCallAdmission;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.domain.execution.control.FreeQuotaEvidence;
import com.lawrence.travel.domain.execution.control.RunBudget;
import com.lawrence.travel.domain.execution.control.RunLimits;
import com.lawrence.travel.domain.execution.control.TokenUsage;
import com.lawrence.travel.infrastructure.execution.SemaphoreModelRequestSlots;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.model.transport.HttpRequest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 回环HTTP真实超时与故障，包内工厂只将测试连接导向127.0.0.1，不放宽生产端点白名单。 */
class LocalModelFaultTest {
    private final BudgetLedger ledger = mock(BudgetLedger.class);
    private final SemaphoreModelRequestSlots slots = new SemaphoreModelRequestSlots(1);
    private final java.util.concurrent.atomic.AtomicBoolean uncertain = new java.util.concurrent.atomic.AtomicBoolean();
    private final UUID reservation = UUID.randomUUID();
    /** @param client 仅连接回环的测试工厂 @return 测试额度及700毫秒真实Call超时，不使用任何账号资料或真实Key */
    private GuardedDashScopeTransport transport(Call.Factory client) {
        var now = Instant.now(); var quota = new FreeQuotaEvidence("local-model", "cn-beijing", "DASHSCOPE_NATIVE_REALTIME", 1000000, now.minusSeconds(1), now.plusSeconds(60), now.plusSeconds(120), true);
        when(ledger.reserve(any(), any(), any(), any(), anyLong(), any())).thenReturn(new BudgetLedger.Reservation(reservation, new RunBudget(1, 0, 0, Duration.ZERO)));
        when(ledger.hasUncertainUsage(any(), any(), any())).thenAnswer(invocation -> uncertain.get());
        org.mockito.Mockito.doAnswer(invocation -> { if (invocation.getArgument(1) == null) uncertain.set(true); return null; }).when(ledger).finish(any(), any(), any());
        var gate = new ModelCallAdmission(true, Map.of("planner", "local-model"), Map.of("local-model", quota), new RunLimits(30, 60, 2, Duration.ofMillis(700), Duration.ofMinutes(15)), ledger, slots, Clock.systemUTC());
        return new GuardedDashScopeTransport(gate, UUID.randomUUID(), "planner", client);
    }
    /** @return 不携带认证头的合法SDK请求；实际连接由测试工厂导向回环 */
    private HttpRequest request() { return HttpRequest.builder().url(GuardedDashScopeTransport.ENDPOINT).method("POST").body("{\"model\":\"local-model\",\"parameters\":{\"max_tokens\":128},\"input\":{\"messages\":[]}}").build(); }
    /** @param server 已绑定回环的本地服务 @param client 真实OkHttp @return 固定回环URL工厂，不采用原请求的外部地址 */
    private Call.Factory loopback(HttpServer server, OkHttpClient client) { return request -> client.newCall(request.newBuilder().url("http://127.0.0.1:" + server.getAddress().getPort() + "/fault").build()); }

    /** 回环超时不自动重试；UNKNOWN保留后，显式第二次请求可用原型号和释放的槽继续。 */
    @Test void actualTimeoutRecordsUnknownAndAllowsExplicitNextSend() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(workers); server.createContext("/fault", exchange -> { calls.incrementAndGet(); entered.countDown(); try { release.await(4, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } finally { exchange.close(); } }); server.start();
            var client = new OkHttpClient.Builder().retryOnConnectionFailure(false).build();
            try (var transport = transport(loopback(server, client))) {
                long started = System.nanoTime(); assertThatThrownBy(() -> transport.execute(request())).hasMessage("MODEL_TRANSPORT_FAILED").hasNoCause();
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue(); assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
                verify(ledger).finish(eq(reservation), isNull(), any()); assertThat(calls.get()).isOne();
                assertThat(uncertain.get()).isTrue();
                assertThatThrownBy(() -> transport.execute(request())).hasMessage("MODEL_TRANSPORT_FAILED");
                assertThat(calls.get()).isEqualTo(2); verify(ledger, times(2)).reserve(any(), any(), any(), any(), anyLong(), any());
                try (var lease = slots.tryAcquire()) { assertThat(lease).isNotNull(); }
            } finally { release.countDown(); server.stop(0); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown(); }
        }
    }

    /** 真实本地HTTP429只保留固定分类，有已知用量就结算；HTTP层不重试，不泄露错误正文。 */
    @Test void rateLimitIsSanitizedAndNotRetriedByTransport() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); var calls = new AtomicInteger();
        server.createContext("/fault", exchange -> { calls.incrementAndGet(); byte[] body = "{\"message\":\"PRIVATE_LOCAL_FAULT_TEXT\",\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(429, body.length); try (var output = exchange.getResponseBody()) { output.write(body); } finally { exchange.close(); } }); server.start();
        var client = new OkHttpClient.Builder().retryOnConnectionFailure(false).build();
        try (var transport = transport(loopback(server, client))) {
            assertThatThrownBy(() -> transport.execute(request())).hasMessage("MODEL_RATE_LIMITED").hasNoCause(); assertThat(calls.get()).isOne(); verify(ledger).finish(eq(reservation), eq(new TokenUsage(3, 1)), any());
        } finally { server.stop(0); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown(); }
    }
}
