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
import java.io.StringReader;
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
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 真实门禁和传输路径配可控 SSE 响应；不读密钥、不访问供应商。 */
class GuardedStreamTest {
    /** 运行句柄关闭后的drain必须等实际请求退出，不能只等待Future取消。 */
    @Test void drainWaitsForActualTransportExitAfterCancellation() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var finished=new CountDownLatch(1);
        try(var transport=transport(true)) {
            when(call.execute()).thenAnswer(invocation -> { entered.countDown(); if(!release.await(3,TimeUnit.SECONDS)) throw new IOException("LOCAL_TEST_TIMEOUT"); throw new IOException("LOCAL_CANCELLED"); });
            var subscription=transport.stream(request()).subscribe(data -> { },error -> { }); assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            transport.close();var drain=Thread.ofVirtual().start(() -> { transport.awaitIdle();finished.countDown(); });
            try { assertThat(finished.await(100,TimeUnit.MILLISECONDS)).isFalse(); }
            finally { release.countDown(); }
            assertThat(finished.await(3,TimeUnit.SECONDS)).isTrue(); drain.join();subscription.dispose();verify(ledger).finish(eq(reservation),isNull(),any());
        } finally { release.countDown(); }
    }
    /** 明确零用量超限在响应关闭后结算，才可向上层报告一次恢复资格。 */
    @Test void zeroUsageContextOverflowHasExplicitRecoveryCode() throws Exception {
        try(var transport=transport(true)) {
            when(call.execute()).thenReturn(response(ResponseBody.create("{\"code\":\"context_length_exceeded\",\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}",MediaType.get("application/json"))).newBuilder().code(400).build());
            assertThatThrownBy(() -> transport.stream(request()).blockLast(Duration.ofSeconds(3))).hasMessage("MODEL_CONTEXT_LIMIT_SAFE");
            verify(ledger).finish(eq(reservation),eq(new TokenUsage(0,0)),any());
        }
    }
    /** 同样超限没有用量证明时保留UNKNOWN，不能自动压缩重试。 */
    @Test void unknownUsageOverflowIsNotRecoverable() throws Exception {
        try(var transport=transport(true)) {
            when(call.execute()).thenReturn(response(ResponseBody.create("{\"code\":\"context_length_exceeded\"}",MediaType.get("application/json"))).newBuilder().code(400).build());
            assertThatThrownBy(() -> transport.stream(request()).blockLast(Duration.ofSeconds(3))).hasMessage("MODEL_CONTEXT_LIMIT"); verify(ledger).finish(eq(reservation),isNull(),any());
        }
    }
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace;
    private final BudgetLedger ledger = mock(BudgetLedger.class);
    private final SemaphoreModelRequestSlots slots = new SemaphoreModelRequestSlots(1);
    private final Call call = mock(Call.class);
    private final UUID reservation = UUID.randomUUID();
    private static final String FIRST = "{\"output\":{\"choices\":[{\"finish_reason\":\"null\",\"message\":{\"role\":\"assistant\",\"content\":[{\"text\":\"O\"}]}}]},\"usage\":{\"input_tokens\":17,\"output_tokens\":1}}";
    private static final String LAST = "{\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":[{\"text\":\"OK\"}]}}]},\"usage\":{\"input_tokens\":17,\"output_tokens\":3}}";

    /**
     * 创建独立传输，令真实计量预占仅操作 Mockito 仓储。
     * @param enabled 是否放行本地替身请求
     * @return 单次测试资源
     */
    private GuardedDashScopeTransport transport(boolean enabled) {
        var now = Instant.now();
        var evidence = new FreeQuotaEvidence("test-model", "cn-beijing", "DASHSCOPE_NATIVE_REALTIME",
                100000, now.minusSeconds(1), now.plusSeconds(60), now.plusSeconds(120), true);
        when(ledger.reserve(any(), any(), any(), any(), anyLong(), any())).thenReturn(
                new BudgetLedger.Reservation(reservation, new RunBudget(1, 0, 0, Duration.ZERO)));
        when(call.timeout()).thenReturn(new Timeout());
        var gate = new ModelCallAdmission(enabled, Map.of("planner", "test-model"), Map.of("test-model", evidence),
                new RunLimits(30, 60, 2, Duration.ofSeconds(3), Duration.ofMinutes(15)), ledger, slots, Clock.systemUTC());
        return new GuardedDashScopeTransport(gate, UUID.randomUUID(), "planner", (Call.Factory) outgoing -> call);
    }

    /** @return SDK 形状的固定请求，没有任何真实凭证 */
    private HttpRequest request() {
        return HttpRequest.builder().url(GuardedDashScopeTransport.ENDPOINT).method("POST")
                .body("{\"model\":\"test-model\",\"parameters\":{\"max_tokens\":128},\"input\":{\"messages\":[]}}")
                .build();
    }

    /**
     * 包装指定 SSE 响应。
     * @param body 可读取且可关闭的本地正文
     * @return 固定成功状态与 SSE 类型
     */
    private Response response(ResponseBody body) {
        return new Response.Builder().request(new Request.Builder().url(GuardedDashScopeTransport.ENDPOINT).build())
                .protocol(Protocol.HTTP_1_1).code(200).message("test").header("Content-Type", "text/event-stream")
                .body(body).build();
    }

    /**
     * 封装两个完整事件，包含 CRLF、注释及结束标记。
     * @return 可控 SSE 字符串
     */
    private String completeFrames() { return ": heartbeat\r\ndata: " + FIRST + "\r\n\r\ndata:" + LAST + "\n\ndata: [DONE]\n\n"; }

    /** 正常流不累加各帧 usage，关闭响应后结算终帧，单槽能立即复用。 */
    @Test
    void settlesFinalCumulativeUsageOnceAndReleasesSlot() throws Exception {
        try (var transport = transport(true)) {
            when(call.execute()).thenReturn(response(ResponseBody.create(completeFrames(), MediaType.get("text/event-stream"))));
            assertThat(transport.stream(request()).collectList().block(Duration.ofSeconds(3))).containsExactly(FIRST, LAST);
            verify(ledger).finish(eq(reservation), eq(new TokenUsage(17, 3)), any());
            assertThat(call.timeout().timeoutNanos()).isEqualTo(Duration.ofSeconds(3).toNanos());
            try (var lease = slots.tryAcquire()) { assertThat(lease).isNotNull(); }
        }
    }

    /** 懒订阅且默认门禁关闭时不实际发送或预占预算。 */
    @Test
    void disabledStreamNeverSends() throws Exception {
        try (var transport = transport(false)) {
            var stream = transport.stream(request());
            verify(call, never()).execute();
            assertThatThrownBy(() -> stream.blockLast(Duration.ofSeconds(3))).hasMessage("EXTERNAL_DISABLED");
            verify(call, never()).execute();
            verify(ledger, never()).reserve(any(), any(), any(), any(), anyLong(), any());
        }
    }

    /** 截断不能凭中间 usage 结算，错误分类固定且保留未知用量。 */
    @Test
    void truncatedStreamRemainsUnknown() throws Exception {
        try (var transport = transport(true)) {
            when(call.execute()).thenReturn(response(ResponseBody.create("data:" + FIRST + "\n\n", MediaType.get("text/event-stream"))));
            assertThatThrownBy(() -> transport.stream(request()).blockLast(Duration.ofSeconds(3)))
                    .hasMessage("MODEL_STREAM_INCOMPLETE");
            verify(ledger).finish(eq(reservation), isNull(), any());
        }
    }

    /** 供应商流内错误不能把自由文本交给 SDK、异常链或日志。 */
    @Test
    void sanitizesProviderErrorFrame() throws Exception {
        try (var transport = transport(true)) {
            when(call.execute()).thenReturn(response(ResponseBody.create(
                    "data:{\"code\":\"AllocationQuota.FreeTierOnly\",\"message\":\"private-test-data\"}\n\n",
                    MediaType.get("text/event-stream"))));
            assertThatThrownBy(() -> transport.stream(request()).blockLast(Duration.ofSeconds(3)))
                    .isInstanceOf(com.lawrence.travel.domain.execution.control.CallRejectedException.class).hasMessage("QUOTA_INSUFFICIENT").hasNoCause();
            verify(ledger).finish(eq(reservation), isNull(), any());
        }
    }

    /** 首帧先交给下游；取消尚未退出的响应时依然占槽，实际退出后只记 UNKNOWN。 */
    @Test
    void emitsBeforeEofAndCancellationRetainsSlotUntilExit() throws Exception {
        var received = new CountDownLatch(1);
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = "data:" + FIRST + "\n\n";
        var body = mock(ResponseBody.class);
        when(body.charStream()).thenReturn(new StringReader(first + "data:" + LAST + "\n\n") {
            private int position;
            /** {@inheritDoc} 模拟首帧之后暂时无法被取消打断的底层读取。 */
            @Override
            public int read() {
                if (position++ == first.length()) {
                    blocked.countDown();
                    try {
                        if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("TEST_TIMEOUT");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("TEST_INTERRUPTED");
                    }
                }
                try { return super.read(); }
                catch (IOException failure) { throw new IllegalStateException("TEST_READ_FAILED"); }
            }
        });
        try (var transport = transport(true)) {
            when(call.execute()).thenReturn(response(body));
            var subscription = transport.stream(request()).subscribe(data -> received.countDown());
            try {
                assertThat(received.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(blocked.await(3, TimeUnit.SECONDS)).isTrue();
                subscription.dispose();
                verify(call).cancel();
                assertThat(slots.tryAcquire()).isNull();
            } finally { release.countDown(); }
            verify(ledger, timeout(3000)).finish(eq(reservation), isNull(), any());
            verify(body, timeout(3000)).close();
        }
    }

    /** 非 JSON 数据及过大单行均固定失败，不交给下游解析或无限缓冲。 */
    @Test
    void rejectsMalformedAndOversizedEvents() throws Exception {
        for (String data : java.util.List.of("data:private-invalid-json\n\n", "data:" + "x".repeat(65537))) {
            // 每轮使用独立传输订阅；此处只断言解析边界，不宣称覆盖数据库准入。
            try (var transport = transport(true)) {
                when(call.execute()).thenReturn(response(ResponseBody.create(data, MediaType.get("text/event-stream"))));
                assertThatThrownBy(() -> transport.stream(request()).blockLast(Duration.ofSeconds(3)))
                        .hasMessage(data.length() > 65536 ? "MODEL_STREAM_TOO_LARGE" : "MODEL_STREAM_INVALID");
            }
        }
    }

    /** 接收完整终帧但没有供应商用量时，产物可返回，计量仍不能伪造为零。 */
    @Test
    void missingFinalUsageRemainsUnknown() throws Exception {
        try (var transport = transport(true)) {
            String noUsage = "{\"output\":{\"choices\":[{\"finish_reason\":\"stop\"}]}}";
            when(call.execute()).thenReturn(response(ResponseBody.create("data:" + noUsage + "\n\n", MediaType.get("text/event-stream"))));
            assertThat(transport.stream(request()).blockLast(Duration.ofSeconds(3))).isEqualTo(noUsage);
            verify(ledger).finish(eq(reservation), isNull(), any());
        }
    }
    /** 真正的 DashScope SDK 消费本地替身流，验证不只是在字符串层通过解析。 */
    @Test
    void sdkConsumesStreamWithGuardedAccounting() throws Exception {
        try (var transport = transport(true)) {
            when(call.execute()).thenReturn(response(ResponseBody.create(completeFrames(), MediaType.get("text/event-stream"))));
            var model = io.agentscope.extensions.model.dashscope.DashScopeChatModel.builder()
                    .apiKey("test-placeholder").modelName("test-model").baseUrl("https://dashscope.aliyuncs.com")
                    .endpointType(io.agentscope.extensions.model.dashscope.EndpointType.MULTIMODAL)
                    .stream(true).enableThinking(false).enableSearch(false).httpTransport(transport).build();
            var messages = java.util.List.of(io.agentscope.core.message.Msg.builder().name("user")
                    .role(io.agentscope.core.message.MsgRole.USER)
                    .content(io.agentscope.core.message.TextBlock.builder().text("test").build()).build());
            var options = io.agentscope.core.model.GenerateOptions.builder().maxTokens(128)
                    .executionConfig(io.agentscope.core.model.ExecutionConfig.builder().maxAttempts(1).build()).build();
            var responses = model.stream(messages, java.util.List.of(), options).collectList().block(Duration.ofSeconds(3));
            assertThat(responses).isNotEmpty();
            verify(ledger).finish(eq(reservation), eq(new TokenUsage(17, 3)), any());
        }
    }

    /** 真实DashScope SDK收到SSE免费耗尽时保留类型化暂停原因，HTTP替身没有隐藏重试。 */
    @Test void quotaFailureSurvivesActualSdk() throws Exception {
        try (var transport = transport(true)) {
            when(call.execute()).thenReturn(response(ResponseBody.create("data:{\"code\":\"AllocationQuota.FreeTierOnly\",\"message\":\"private-local-data\"}\n\n", MediaType.get("text/event-stream"))));
            var model = io.agentscope.extensions.model.dashscope.DashScopeChatModel.builder().apiKey("test-placeholder").modelName("test-model").baseUrl("https://dashscope.aliyuncs.com").endpointType(io.agentscope.extensions.model.dashscope.EndpointType.MULTIMODAL).stream(true).enableThinking(false).enableSearch(false).httpTransport(transport).build();
            var messages = java.util.List.of(io.agentscope.core.message.Msg.builder().name("user").role(io.agentscope.core.message.MsgRole.USER).content(io.agentscope.core.message.TextBlock.builder().text("local error").build()).build());
            var options = io.agentscope.core.model.GenerateOptions.builder().maxTokens(128).executionConfig(io.agentscope.core.model.ExecutionConfig.builder().maxAttempts(1).build()).build();
            assertThatThrownBy(() -> model.stream(messages, java.util.List.of(), options).collectList().block(Duration.ofSeconds(3)))
                    .satisfies(failure -> { Throwable root = failure; while (root.getCause() != null) root = root.getCause(); assertThat(root).isInstanceOf(com.lawrence.travel.domain.execution.control.CallRejectedException.class).hasMessage("QUOTA_INSUFFICIENT"); });
            org.mockito.Mockito.verify(call, org.mockito.Mockito.times(1)).execute(); verify(ledger).finish(eq(reservation), isNull(), any());
        }
    }

    /** 真实 SDK 与 Harness 消费工具调用流及结构化响应，HTTP 仅为内存替身。 */
    @Test
    void harnessConsumesStreamedToolAndStructuredReply() throws Exception {
        var rounds = new java.util.concurrent.atomic.AtomicInteger();
        try (var transport = transport(true)) {
            when(call.execute()).thenAnswer(invocation -> {
                boolean first = rounds.getAndIncrement() == 0;
                var function = Map.of("name", first ? "lookup_validation_value" : "generate_response",
                        "arguments", first ? "{}" : "{\"response\":{\"answer\":\"7\"}}");
                var message = Map.of("role", "assistant", "content", java.util.List.of(), "tool_calls",
                        java.util.List.of(Map.of("index", 0, "id", first ? "tool1" : "tool2", "type", "function", "function", function)));
                String frame = io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(Map.of(
                        "output", Map.of("choices", java.util.List.of(Map.of("finish_reason", "tool_calls", "message", message))),
                        "usage", Map.of("input_tokens", 17, "output_tokens", 3)));
                return response(ResponseBody.create("data:" + frame + "\n\n", MediaType.get("text/event-stream")));
            });
            var model = io.agentscope.extensions.model.dashscope.DashScopeChatModel.builder()
                    .apiKey("test-placeholder").modelName("test-model").baseUrl("https://dashscope.aliyuncs.com")
                    .endpointType(io.agentscope.extensions.model.dashscope.EndpointType.MULTIMODAL)
                    .stream(true).enableThinking(false).enableSearch(false).httpTransport(transport).build();
            var toolkit = new io.agentscope.core.tool.Toolkit();
            var probe = new HarnessContractTest.ProbeTool();
            toolkit.registerTool(probe);
            var directory = workspace.resolve("harness");
            try (var harness = RestrictedHarnessFactory.create(model, toolkit, directory, "planner")) {
                var result = harness.call(java.util.List.of(io.agentscope.core.message.Msg.builder().name("user")
                                .role(io.agentscope.core.message.MsgRole.USER)
                                .content(io.agentscope.core.message.TextBlock.builder().text("test").build()).build()),
                        RestrictedHarnessFactory.StructuredReply.class).block(Duration.ofSeconds(5));
                assertThat(result.getStructuredData(RestrictedHarnessFactory.StructuredReply.class).answer()).isEqualTo("7");
                assertThat(probe.calls.get()).isEqualTo(1);
                verify(ledger, org.mockito.Mockito.times(2)).finish(eq(reservation), eq(new TokenUsage(17, 3)), any());
            }
        }
    }

    /** HTTP 非成功响应只返回固定保护码，响应正文不可成为下游流数据。 */
    @Test
    void sanitizesHttpFailureBeforeAnyFrame() throws Exception {
        try (var transport = transport(true)) {
            var failure = response(ResponseBody.create(
                    "{\"code\":\"AllocationQuota.FreeTierOnly\",\"message\":\"private-test-data\"}",
                    MediaType.get("application/json"))).newBuilder().code(403).build();
            when(call.execute()).thenReturn(failure);
            assertThatThrownBy(() -> transport.stream(request()).blockLast(Duration.ofSeconds(3)))
                    .isInstanceOf(com.lawrence.travel.domain.execution.control.CallRejectedException.class).hasMessage("QUOTA_INSUFFICIENT").hasNoCause();
            verify(ledger).finish(eq(reservation), isNull(), any());
        }
    }

    /** 长度终帧即使带合法JSON前缀也必须拒绝，不把截断的方案交给SDK完成提交。 */
    @Test void lengthFinishedStreamIsRejected() throws Exception {
        try (var transport = transport(true)) {
            String frames = "data: " + LAST.replace("stop", "length") + "\n\ndata: [DONE]\n\n";
            when(call.execute()).thenReturn(response(ResponseBody.create(frames, MediaType.get("text/event-stream"))));
            assertThatThrownBy(() -> transport.stream(request()).collectList().block(Duration.ofSeconds(3))).hasMessage("MODEL_OUTPUT_TRUNCATED"); verify(ledger).finish(eq(reservation), isNull(), any());
        }
    }

}
