package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.execution.ModelCallAdmission;
import com.lawrence.travel.application.memory.AgentContext;
import com.lawrence.travel.application.memory.ContextAssembler;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.application.port.ContextStore;
import com.lawrence.travel.application.port.ExecutionTrace;
import com.lawrence.travel.application.safety.SafetyGate;
import com.lawrence.travel.domain.execution.control.RunBudget;
import com.lawrence.travel.domain.execution.control.RunLimits;
import com.lawrence.travel.domain.execution.control.TokenUsage;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.infrastructure.execution.SemaphoreModelRequestSlots;
import com.lawrence.travel.infrastructure.interaction.InteractionJson;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 本机HTTP脚本服务；SDK/Harness/传输/准入/上下文均走生产实现，只替换持久端口与连接目的地。 */
final class AdvisorLoopbackFixture implements AutoCloseable {
    final InteractionJson json = new InteractionJson(new TravelJson());
    final TaskScope scope = new TaskScope(UUID.randomUUID(), UUID.randomUUID());
    final UUID run = UUID.randomUUID();
    final List<Map<?, ?>> requests = new CopyOnWriteArrayList<>();
    final List<TokenUsage> usage = new CopyOnWriteArrayList<>();
    final AtomicInteger reservations = new AtomicInteger();
    final HarnessAgentRuntimeAdapter runtime;
    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpServer server;
    private final OkHttpClient client =
            new OkHttpClient.Builder().retryOnConnectionFailure(false).build();
    private final SemaphoreModelRequestSlots slots = new SemaphoreModelRequestSlots(1);

    /**
     * 启动随机回环端口，使用占位凭据和合成权威上下文，不读取.env。
     *
     * @param root JUnit临时工作区
     * @throws IOException 本机端口绑定失败
     */
    AdvisorLoopbackFixture(Path root) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/model", this::serve);
        server.start();
        var ledger = mock(BudgetLedger.class);
        when(ledger.reserve(any(), any(), any(), any(), anyLong(), any()))
                .thenAnswer(
                        call ->
                                new BudgetLedger.Reservation(
                                        UUID.randomUUID(),
                                        new RunBudget(
                                                reservations.incrementAndGet(),
                                                0,
                                                0,
                                                Duration.ZERO)));
        doAnswer(
                        call -> {
                            // CopyOnWriteArrayList允许null，null明确代表当前请求用量未知。
                            usage.add(call.getArgument(1));
                            return null;
                        })
                .when(ledger)
                .finish(any(), any(), any());
        var models =
                Map.of(
                        "coordinator",
                        "local-model",
                        "research",
                        "local-model",
                        "planner",
                        "local-model",
                        "reviewer",
                        "local-reviewer");
        var gate =
                new ModelCallAdmission(
                        true,
                        models,
                        Map.of(),
                        new RunLimits(30, 60, 2, Duration.ofSeconds(10), Duration.ofMinutes(15)),
                        ledger,
                        slots,
                        Clock.systemUTC());
        var store = mock(ContextStore.class);
        when(store.load(any(), any()))
                .thenAnswer(
                        call ->
                                new AgentContext.Source(
                                        scope,
                                        run,
                                        call.getArgument(1),
                                        1,
                                        0,
                                        1,
                                        0,
                                        "{}",
                                        "a".repeat(64),
                                        List.of()));
        Call.Factory connections =
                request -> {
                    assertThat(request.url().toString())
                            .isEqualTo(GuardedDashScopeTransport.ENDPOINT);
                    return client.newCall(
                            request.newBuilder()
                                    .url(
                                            "http://127.0.0.1:"
                                                    + server.getAddress().getPort()
                                                    + "/model")
                                    .removeHeader("Authorization")
                                    .build());
                };
        runtime =
                new HarnessAgentRuntimeAdapter(
                        gate,
                        models,
                        () -> "local-placeholder",
                        root,
                        (id, role) -> new Toolkit(),
                        new ContextAssembler(store, json),
                        json,
                        null,
                        SafetyGate.local(),
                        null,
                        null,
                        null,
                        ExecutionTrace.disabled(),
                        connections);
    }

    /**
     * 排入一次合法SSE响应，工具模式走generate_response，正文模式走严格JSON兜底。
     *
     * @param body 只在内存使用的合成业务JSON
     * @param tool 是否使用框架结构化工具
     */
    void answer(String body, boolean tool) {
        Map<String, Object> message =
                tool
                        ? Map.of(
                                "role",
                                "assistant",
                                "content",
                                List.of(),
                                "tool_calls",
                                List.of(
                                        Map.of(
                                                "index",
                                                0,
                                                "id",
                                                "local-result",
                                                "type",
                                                "function",
                                                "function",
                                                Map.of(
                                                        "name",
                                                        "generate_response",
                                                        "arguments",
                                                        json.encode(
                                                                Map.of(
                                                                        "response",
                                                                        json.decode(
                                                                                body,
                                                                                Map.class)))))))
                        : Map.of("role", "assistant", "content", List.of(Map.of("text", body)));
        String frame =
                json.encode(
                        Map.of(
                                "output",
                                Map.of(
                                        "choices",
                                        List.of(
                                                Map.of(
                                                        "finish_reason",
                                                        tool ? "tool_calls" : "stop",
                                                        "message",
                                                        message))),
                                "usage",
                                Map.of("input_tokens", 17, "output_tokens", 3)));
        replies.add(new Reply(200, "data: " + frame + "\n\ndata: [DONE]\n\n"));
    }

    /**
     * 排入固定HTTP故障，不由替身执行重试。
     *
     * @param status 状态码
     * @param body 合成错误JSON，可有明确零用量
     */
    void failure(int status, String body) {
        replies.add(new Reply(status, body));
    }

    /**
     * 验证请求、预占、结束计量次数一致，脚本全部消费且并发槽已释放。
     *
     * @param expected 本地模型HTTP请求数，不是旅行场景数
     */
    void assertCalls(int expected) {
        assertThat(requests).hasSize(expected);
        assertThat(reservations.get()).isEqualTo(expected);
        assertThat(usage).hasSize(expected);
        assertThat(replies).isEmpty();
        try (var lease = slots.tryAcquire()) {
            assertThat(lease).isNotNull();
        }
    }

    /**
     * 读取合成SDK请求并返回脚本响应；不保存认证头，不输出请求或响应正文。
     *
     * @param exchange 本机连接
     * @throws IOException 回环连接提前断开
     */
    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] input = exchange.getRequestBody().readNBytes(262145);
            if (input.length > 262144) throw new IOException("LOCAL_REQUEST_TOO_LARGE");
            requests.add(json.decode(new String(input, StandardCharsets.UTF_8), Map.class));
            var reply = replies.poll();
            if (reply == null) reply = new Reply(500, "{\"code\":\"LOCAL_SCRIPT_EXHAUSTED\"}");
            exchange.getResponseHeaders()
                    .set(
                            "Content-Type",
                            reply.status() == 200 ? "text/event-stream" : "application/json");
            byte[] output = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), output.length);
            exchange.getResponseBody().write(output);
        }
    }

    /** {@inheritDoc} 停止回环服务与连接池，不操作项目容器、业务数据库或用户文件。 */
    @Override
    public void close() {
        server.stop(0);
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
        workers.close();
    }

    /**
     * 一次本地HTTP响应；不接受真实供应商数据。
     *
     * @param status HTTP状态
     * @param body 合成响应
     */
    private record Reply(int status, String body) {}
}
