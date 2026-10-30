package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.execution.ModelCallAdmission;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.domain.execution.control.FreeQuotaEvidence;
import com.lawrence.travel.domain.execution.control.RunBudget;
import com.lawrence.travel.domain.execution.control.RunLimits;
import com.lawrence.travel.domain.execution.control.TokenUsage;
import com.lawrence.travel.infrastructure.execution.SemaphoreModelRequestSlots;
import io.agentscope.core.model.transport.HttpRequest;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Timeout;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 使用真实门禁及可控连接验证每次 HTTP 的预占、异常与实际取消边界。 */
class GuardedTransportTest {
    /** 429和503只有明确零用量时才携带可重试证据，传输层本身不重发。 */
    @Test
    void rejectedProviderCallsKeepExplicitZeroUsageWithoutRetrying() throws Exception {
        for (int status : new int[] {429, 503}) {
            when(call.execute())
                    .thenReturn(
                            response(
                                    status,
                                    "{\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}"));
            try (var transport = transport(true)) {
                assertThatThrownBy(() -> transport.execute(request()))
                        .isInstanceOf(ProviderCallFailure.class)
                        .satisfies(
                                error -> {
                                    var failure =
                                            new SanitizedAgentFailure(error.getMessage(), error);
                                    assertThat(failure.knownZeroUsage()).isTrue();
                                    assertThat(failure.recovery())
                                            .isEqualTo(
                                                    com.lawrence.travel.application.execution
                                                            .AgentCallFailure.Recovery.RETRY);
                                });
            }
        }
        verify(call, org.mockito.Mockito.times(2)).execute();
        verify(ledger, org.mockito.Mockito.times(2))
                .finish(eq(reservationId), eq(new TokenUsage(0, 0)), any());
    }

    /** 限流响应没有用量时保持UNKNOWN语义，不能伪造零用量再自动重试。 */
    @Test
    void rateLimitWithoutUsageRequiresPause() throws Exception {
        when(call.execute()).thenReturn(response(429, "{\"message\":\"SYNTHETIC_PRIVATE\"}"));
        try (var transport = transport(true)) {
            assertThatThrownBy(() -> transport.execute(request()))
                    .hasMessage("MODEL_RATE_LIMITED")
                    .satisfies(
                            error -> {
                                var failure = new SanitizedAgentFailure(error.getMessage(), error);
                                assertThat(failure.knownZeroUsage()).isFalse();
                                assertThat(failure.recovery())
                                        .isEqualTo(
                                                com.lawrence.travel.application.execution
                                                        .AgentCallFailure.Recovery.PAUSE);
                                assertThat(failure.toString()).doesNotContain("SYNTHETIC_PRIVATE");
                            });
        }
        verify(call).execute();
        verify(ledger).finish(eq(reservationId), isNull(), any());
    }

    /** 小数、负数或溢出计量不是零用量证据，不能触发自动重试。 */
    @Test
    void malformedUsageNeverBecomesZeroUsage() throws Exception {
        for (String input : java.util.List.of("0.5", "-1", "18446744073709551616")) {
            when(call.execute())
                    .thenReturn(
                            response(
                                    429,
                                    "{\"usage\":{\"input_tokens\":"
                                            + input
                                            + ",\"output_tokens\":0}}"));
            try (var transport = transport(true)) {
                assertThatThrownBy(() -> transport.execute(request()))
                        .satisfies(
                                error -> {
                                    var failure =
                                            new SanitizedAgentFailure(error.getMessage(), error);
                                    assertThat(failure.knownZeroUsage()).isFalse();
                                    assertThat(failure.recovery())
                                            .isEqualTo(
                                                    com.lawrence.travel.application.execution
                                                            .AgentCallFailure.Recovery.PAUSE);
                                });
            }
        }
        verify(call, org.mockito.Mockito.times(3)).execute();
        verify(ledger, org.mockito.Mockito.times(3)).finish(eq(reservationId), isNull(), any());
    }

    /** 断线无法确认供应商是否执行，结束一次请求后暂停，不在传输层重放。 */
    @Test
    void networkFailureDoesNotClaimZeroUsage() throws Exception {
        when(call.execute()).thenThrow(new java.io.IOException("SYNTHETIC_PRIVATE"));
        try (var transport = transport(true)) {
            assertThatThrownBy(() -> transport.execute(request()))
                    .hasMessage("MODEL_TRANSPORT_FAILED")
                    .satisfies(
                            error ->
                                    assertThat(
                                                    new SanitizedAgentFailure(
                                                                    error.getMessage(), error)
                                                            .recovery())
                                            .isEqualTo(
                                                    com.lawrence.travel.application.execution
                                                            .AgentCallFailure.Recovery.PAUSE));
        }
        verify(call).execute();
        verify(ledger).finish(eq(reservationId), isNull(), any());
    }

    /** 扩容协调输入允许原16K以上请求，但超过新48K仍在预占及HTTP前拒绝，输出上限不变。 */
    @Test
    void coordinatorAllowsLargerInputAndStillRejectsBeyond48K() throws Exception {
        when(call.execute())
                .thenReturn(
                        response(200, "{\"usage\":{\"input_tokens\":10,\"output_tokens\":12}}"));
        try (var transport = transport(true, "coordinator")) {
            var json = new com.lawrence.travel.infrastructure.travel.TravelJson();
            String template =
                    json.encode(
                            Map.of(
                                    "model",
                                    "test-model",
                                    "parameters",
                                    Map.of("max_tokens", 128),
                                    "input",
                                    Map.of(
                                            "messages",
                                            java.util.List.of(
                                                    Map.of(
                                                            "role",
                                                            "user",
                                                            "content",
                                                            java.util.List.of(
                                                                    Map.of(
                                                                            "text",
                                                                            "TEXT_PLACEHOLDER")))))));
            var accepted =
                    HttpRequest.builder()
                            .url(GuardedDashScopeTransport.ENDPOINT)
                            .method("POST")
                            .body(template.replace("TEXT_PLACEHOLDER", "x".repeat(40000)))
                            .build();
            transport.execute(accepted);
            var rejected =
                    HttpRequest.builder()
                            .url(GuardedDashScopeTransport.ENDPOINT)
                            .method("POST")
                            .body(template.replace("TEXT_PLACEHOLDER", "x".repeat(50000)))
                            .build();
            assertThatThrownBy(() -> transport.execute(rejected))
                    .hasMessage("CONTEXT_BUDGET_EXHAUSTED")
                    .satisfies(
                            failure ->
                                    assertThat(
                                                    ((com.lawrence.travel.domain.execution.control
                                                                            .ContextBudgetDiagnostic)
                                                                    failure.getCause())
                                                            .limit())
                                            .isEqualTo(49152));
        }
        verify(call).execute();
        verify(ledger).reserve(any(), any(), any(), any(), anyLong(), any());
    }

    /** 合法完整请求才更新工具页剩余量；计数来自最终SDK正文，不包含正文或供应商数据。 */
    @Test
    void acceptedRequestPublishesExactConservativeHeadroom() throws Exception {
        when(call.execute())
                .thenReturn(
                        response(200, "{\"usage\":{\"input_tokens\":10,\"output_tokens\":12}}"));
        var remaining = new java.util.concurrent.atomic.AtomicLong(-1);
        try (var transport = transport(true)) {
            transport.contextHeadroom(remaining::set);
            var request = request();
            transport.execute(request);
            assertThat(remaining.get())
                    .isEqualTo(
                            com.lawrence.travel.application.memory.ContextAssembler.limit("planner")
                                    - HarnessContextCompression.estimateInput(request.getBody()));
        }
    }

    /** 最终SDK正文超限在计量及网络前拒绝，只附带固定边界和量测，不泄露合成正文。 */
    @Test
    void oversizedInputRetainsOnlySafeBudgetDiagnostic() {
        String body =
                "{\"model\":\"test-model\",\"parameters\":{\"max_tokens\":128},\"input\":{\"messages\":[{\"role\":\"user\",\"content\":[{\"text\":\""
                        + "x".repeat(40000)
                        + "\"}]}]}}";
        try (var transport = transport(true)) {
            assertThatThrownBy(
                            () ->
                                    transport.execute(
                                            HttpRequest.builder()
                                                    .url(GuardedDashScopeTransport.ENDPOINT)
                                                    .method("POST")
                                                    .body(body)
                                                    .build()))
                    .isInstanceOf(
                            com.lawrence.travel.domain.execution.control.CallRejectedException
                                    .class)
                    .hasMessage("CONTEXT_BUDGET_EXHAUSTED")
                    .satisfies(
                            failure -> {
                                var detail =
                                        (com.lawrence.travel.domain.execution.control
                                                        .ContextBudgetDiagnostic)
                                                failure.getCause();
                                assertThat(detail.boundary()).isEqualTo("TRANSPORT_BODY");
                                assertThat(detail.estimated()).isGreaterThan(detail.limit());
                                assertThat(detail.getMessage())
                                        .isEqualTo("CONTEXT_BUDGET_EXHAUSTED");
                                assertThat(detail.getCause()).isNull();
                            });
        }
    }

    private final BudgetLedger ledger = mock(BudgetLedger.class);
    private final SemaphoreModelRequestSlots slots = new SemaphoreModelRequestSlots(1);
    private final Call call = mock(Call.class);
    private final UUID reservationId = UUID.randomUUID();

    /**
     * 建立当下有效的测试额度，不访问任何真实供应商。
     * @param enabled 是否允许测试请求
     * @return 仅测试使用的传输
     */
    private GuardedDashScopeTransport transport(boolean enabled) {
        return transport(enabled, "planner");
    }

    /**
     * @param enabled 测试准入
     * @param role 实际角色预算
     * @return 同一受控连接，不访问供应商
     */
    private GuardedDashScopeTransport transport(boolean enabled, String role) {
        var now = Instant.now();
        var evidence =
                new FreeQuotaEvidence(
                        "test-model",
                        "cn-beijing",
                        "DASHSCOPE_NATIVE_REALTIME",
                        100000,
                        now.minusSeconds(5),
                        now.plusSeconds(60),
                        now.plusSeconds(120),
                        true);
        when(ledger.reserve(any(), any(), any(), any(), anyLong(), any()))
                .thenReturn(
                        new BudgetLedger.Reservation(
                                reservationId, new RunBudget(1, 0, 0, Duration.ZERO)));
        when(call.timeout()).thenReturn(new Timeout());
        var gate =
                new ModelCallAdmission(
                        enabled,
                        Map.of(role, "test-model"),
                        Map.of("test-model", evidence),
                        new RunLimits(30, 60, 2, Duration.ofMillis(100), Duration.ofMinutes(15)),
                        ledger,
                        slots,
                        Clock.systemUTC());
        return new GuardedDashScopeTransport(
                gate, UUID.randomUUID(), role, (Call.Factory) request -> call);
    }

    /**
     * @return 合法但不含真实凭证的请求
     */
    private HttpRequest request() {
        return HttpRequest.builder()
                .url(GuardedDashScopeTransport.ENDPOINT)
                .method("POST")
                .body(
                        "{\"model\":\"test-model\",\"parameters\":{\"max_tokens\":128},\"input\":{\"messages\":[]}}")
                .build();
    }

    /**
     * 创建可控响应。
     * @param status HTTP 状态
     * @param body 纯测试正文
     * @return 可关闭响应
     */
    private Response response(int status, String body) {
        return new Response.Builder()
                .request(new Request.Builder().url(GuardedDashScopeTransport.ENDPOINT).build())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("test")
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build();
    }

    /** 默认拒绝在实际连接执行前生效。 */
    @Test
    void disabledNeverSends() throws Exception {
        try (var transport = transport(false)) {
            assertThatThrownBy(() -> transport.execute(request())).hasMessage("EXTERNAL_DISABLED");
            verify(call, never()).execute();
        }
    }

    /** 规划输出上限扩展仍进入原配额与计量；超出角色上限在连接前拒绝。 */
    @Test
    void plannerOutputLimitStillReservesConservativeTokens() throws Exception {
        try (var transport = transport(true)) {
            when(call.execute())
                    .thenReturn(
                            response(
                                    200, "{\"usage\":{\"input_tokens\":10,\"output_tokens\":12}}"));
            var request =
                    HttpRequest.builder()
                            .url(GuardedDashScopeTransport.ENDPOINT)
                            .method("POST")
                            .body(
                                    "{\"model\":\"test-model\",\"parameters\":{\"max_tokens\":12288},\"input\":{\"messages\":[]}}")
                            .build();
            assertThat(transport.execute(request).isSuccessful()).isTrue();
            var bound = org.mockito.ArgumentCaptor.forClass(Long.class);
            verify(ledger).reserve(any(), any(), any(), any(), bound.capture(), any());
            assertThat(bound.getValue()).isGreaterThan(12288L);
        }
        try (var transport = transport(true)) {
            var bad =
                    HttpRequest.builder()
                            .url(GuardedDashScopeTransport.ENDPOINT)
                            .method("POST")
                            .body(
                                    "{\"model\":\"test-model\",\"parameters\":{\"max_tokens\":12289},\"input\":{\"messages\":[]}}")
                            .build();
            assertThatThrownBy(() -> transport.execute(bad)).hasMessage("MODEL_OPTIONS_REJECTED");
        }
    }

    /** 成功读取供应商用量且将预算剩余超时传给实际连接。 */
    @Test
    void recordsUsageAndEnforcesCallTimeout() throws Exception {
        try (var transport = transport(true)) {
            when(call.execute())
                    .thenReturn(
                            response(200, "{\"usage\":{\"input_tokens\":17,\"output_tokens\":3}}"));
            assertThat(transport.execute(request()).isSuccessful()).isTrue();
            verify(ledger).finish(eq(reservationId), eq(new TokenUsage(17, 3)), any());
            assertThat(call.timeout().timeoutNanos()).isEqualTo(Duration.ofMillis(100).toNanos());
            try (var lease = slots.tryAcquire()) {
                assertThat(lease).isNotNull();
            }
        }
    }

    /** 免费耗尽保留固定错误类别，不回传供应商原始正文，未知用量不补零。 */
    @Test
    void quotaErrorIsSanitizedAndUnknown() throws Exception {
        try (var transport = transport(true)) {
            when(call.execute())
                    .thenReturn(
                            response(
                                    403,
                                    "{\"code\":\"AllocationQuota.FreeTierOnly\",\"message\":\"private-test-data\"}"));
            assertThatThrownBy(() -> transport.execute(request()))
                    .isInstanceOf(
                            com.lawrence.travel.domain.execution.control.CallRejectedException
                                    .class)
                    .hasMessage("QUOTA_INSUFFICIENT");
            verify(ledger).finish(eq(reservationId), isNull(), any());
        }
    }

    /** 取消信号发出后，实际连接尚未退出时不能提前放出模型槽。 */
    @Test
    void cancellationRetainsSlotUntilConnectionExits() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var transport = transport(true)) {
            when(call.execute())
                    .thenAnswer(
                            invocation -> {
                                entered.countDown();
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new IOException("test timeout");
                                throw new IOException("test cancelled");
                            });
            var task =
                    java.util.concurrent.CompletableFuture.runAsync(
                            () ->
                                    assertThatThrownBy(() -> transport.execute(request()))
                                            .hasMessage("MODEL_CANCELLED"));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                transport.close();
                verify(call).cancel();
                assertThat(slots.tryAcquire()).isNull();
            } finally {
                release.countDown();
            }
            task.get(5, TimeUnit.SECONDS);
            verify(ledger).finish(eq(reservationId), isNull(), any());
            try (var lease = slots.tryAcquire()) {
                assertThat(lease).isNotNull();
            }
        }
    }

    /** 非白名单端点无法发送或预占额度。 */
    @Test
    void rejectsEndpointOverride() throws Exception {
        try (var transport = transport(true)) {
            assertThatThrownBy(
                            () ->
                                    transport.execute(
                                            HttpRequest.builder()
                                                    .url("https://example.com")
                                                    .build()))
                    .hasMessage("MODEL_ENDPOINT_REJECTED");
            verify(call, never()).execute();
        }
    }

    /** 多模态端点仍严格拒绝媒体输入，不能套用文本 Token 上界。 */
    @Test
    void rejectsMediaBeforeAdmission() throws Exception {
        try (var transport = transport(true)) {
            var media =
                    HttpRequest.builder()
                            .url(GuardedDashScopeTransport.ENDPOINT)
                            .method("POST")
                            .body(
                                    "{\"input\":{\"messages\":[{\"content\":[{\"image\":\"https://example.com/image\"}]}]}}")
                            .build();
            assertThatThrownBy(() -> transport.execute(media))
                    .hasMessage("MODEL_MEDIA_NOT_ALLOWED");
            verify(ledger, never()).reserve(any(), any(), any(), any(), anyLong(), any());
            verify(call, never()).execute();
        }
    }

    /** 单个模型槽即可执行父请求与后续调查请求，父流程不会跨工具委派占用请求许可。 */
    @Test
    void releasesParentRequestBeforeDispatchingChild() throws Exception {
        try (var transport = transport(true);
                var resources =
                        new com.lawrence.travel.infrastructure.execution.BoundedExecutionResources(
                                1, 1);
                var scope = resources.openRun()) {
            when(call.execute())
                    .thenAnswer(
                            invocation ->
                                    response(
                                            200,
                                            "{\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}"));
            assertThat(transport.execute(request()).isSuccessful()).isTrue();
            var child = scope.forkResearch(() -> transport.execute(request()).isSuccessful());
            assertThat(child.toCompletableFuture().get(3, TimeUnit.SECONDS)).isTrue();
            verify(call, org.mockito.Mockito.times(2)).execute();
            try (var lease = slots.tryAcquire()) {
                assertThat(lease).isNotNull();
            }
        }
    }

    /** 完整SDK正文超出输入预算时在预占和网络之前拒绝，不能只检查调用方字符串。 */
    @Test
    void oversizedInputStopsBeforeReservationAndNetwork() throws Exception {
        try (var transport = transport(true)) {
            String body =
                    "{\"model\":\"test-model\",\"parameters\":{\"max_tokens\":128},\"input\":{\"messages\":[{\"role\":\"user\",\"content\":[{\"text\":\""
                            + "x".repeat(30000)
                            + "\"}]}]}}";
            var request =
                    HttpRequest.builder()
                            .url(GuardedDashScopeTransport.ENDPOINT)
                            .method("POST")
                            .body(body)
                            .build();
            assertThatThrownBy(() -> transport.execute(request))
                    .hasMessage("CONTEXT_BUDGET_EXHAUSTED");
            verify(call, never()).execute();
            verify(ledger, never()).reserve(any(), any(), any(), any(), anyLong(), any());
        }
    }

    /** 供应商按输出上限结束时，即使前缀是合法JSON也不能作为完整草案返回。 */
    @Test
    void lengthFinishedOutputIsNeverReturnedAsComplete() throws Exception {
        try (var transport = transport(true)) {
            when(call.execute())
                    .thenReturn(
                            response(
                                    200,
                                    "{\"output\":{\"choices\":[{\"finish_reason\":\"length\"}]},\"usage\":{\"input_tokens\":17,\"output_tokens\":3}}"));
            assertThatThrownBy(() -> transport.execute(request()))
                    .hasMessage("MODEL_OUTPUT_TRUNCATED");
            verify(ledger).finish(eq(reservationId), eq(new TokenUsage(17, 3)), any());
        }
    }

    /** 上下文已失效时检查先于预占及连接创建，不能在旧记忆已发送后才拒绝保存。 */
    @Test
    void contextRevisionCheckRunsBeforeReservationAndConnection() {
        var connections = new java.util.concurrent.atomic.AtomicInteger();
        try (var transport =
                new GuardedDashScopeTransport(
                        null,
                        UUID.randomUUID(),
                        "planner",
                        (Call.Factory)
                                request -> {
                                    connections.incrementAndGet();
                                    throw new AssertionError("MUST_NOT_CONNECT");
                                },
                        () -> {
                            throw new com.lawrence.travel.application.memory
                                    .PreferenceChangedException();
                        })) {
            assertThatThrownBy(() -> transport.execute(request())).hasMessage("PREFERENCE_CHANGED");
            assertThat(connections.get()).isZero();
            verify(ledger, never()).reserve(any(), any(), any(), any(), anyLong(), any());
        }
    }
}
