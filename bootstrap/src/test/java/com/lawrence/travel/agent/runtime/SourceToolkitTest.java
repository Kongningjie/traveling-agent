package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.document.SourceReadService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.ReasoningInput;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实Harness原生卸载组件配合本地应用替身；无任何模型或网络服务调用。 */
class SourceToolkitTest {
    @TempDir Path root;

    /** 页长随最后完整SDK请求的窗口缩小，非法长度不被悄悄修正；原文分页定位仍由应用服务生成。 */
    @Test void pagedToolsShareObservedWindowWithoutChangingOffsets() {
        var service = mock(SourceReadService.class);
        var ledger = mock(com.lawrence.travel.application.port.BudgetLedger.class);
        when(ledger.reserve(any(), any(), any(), any(), anyLong(), any())).thenReturn(
                new com.lawrence.travel.application.port.BudgetLedger.Reservation(UUID.randomUUID(), null));
        var toolkit = new SourceToolkit(service, UUID.randomUUID(), 1, ledger, null);
        assertThat(toolkit.pageLength(4000)).isEqualTo(4000);
        toolkit.contextHeadroom(3424); assertThat(toolkit.pageLength(4000)).isEqualTo(200);
        assertThat(toolkit.pageLength(100)).isEqualTo(100);
        assertThat(toolkit.pageLength(0)).isZero(); assertThat(toolkit.pageLength(4001)).isEqualTo(4001);
        var tools = new SourceTools(service, UUID.randomUUID(), 1, ledger, null, toolkit);
        UUID document = UUID.randomUUID(); tools.section(document.toString(), 2, 137, 4000);
        tools.file("/work/coordinator/source", 225, 4000);
        org.mockito.Mockito.verify(service).section(any(), anyLong(), org.mockito.ArgumentMatchers.eq(document), org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.eq(137), org.mockito.ArgumentMatchers.eq(200));
        org.mockito.Mockito.verify(service).file(any(), anyLong(), org.mockito.ArgumentMatchers.eq("/work/coordinator/source"), org.mockito.ArgumentMatchers.eq(225), org.mockito.ArgumentMatchers.eq(200));
        toolkit.contextHeadroom(0); assertThat(toolkit.pageLength(4000)).isEqualTo(64);
    }

    /** 真SDK格式化加真实发送门禁复现工具追加超限，受控HTTP替身仅收到两次合法请求；无外网。 */
    @Test void serializedRequestOverflowRecoversBeforeLedgerAndNetwork() {
        var ledger = mock(com.lawrence.travel.application.port.BudgetLedger.class);
        when(ledger.reserve(any(), any(), any(), any(), anyLong(), any())).thenAnswer(c ->
                new com.lawrence.travel.application.port.BudgetLedger.Reservation(UUID.randomUUID(),
                        new com.lawrence.travel.domain.execution.control.RunBudget(1, 0, 0, Duration.ZERO)));
        var now = java.time.Instant.now(); var run = UUID.randomUUID();
        var evidence = new com.lawrence.travel.domain.execution.control.FreeQuotaEvidence("test-model", "cn-beijing", "DASHSCOPE_NATIVE_REALTIME",
                1000000, now.minusSeconds(5), now.plusSeconds(600), now.plusSeconds(1200), true);
        var limits = new com.lawrence.travel.domain.execution.control.RunLimits(8, 8, 2, Duration.ofSeconds(5), Duration.ofMinutes(1));
        var admission = new com.lawrence.travel.application.execution.ModelCallAdmission(true, java.util.Map.of("coordinator", "test-model"),
                java.util.Map.of("test-model", evidence), limits, ledger,
                new com.lawrence.travel.infrastructure.execution.SemaphoreModelRequestSlots(1), java.time.Clock.systemUTC());
        var service = mock(SourceReadService.class);
        when(service.history(any(), anyLong(), anyString())).thenReturn("中".repeat(1000));
        when(service.offload(any(), anyLong(), anyString(), anyString())).thenAnswer(c -> c.getArgument(2));
        var toolkit = new SourceToolkit(service, run, 1, ledger, limits);
        var requests = new java.util.ArrayList<String>();
        okhttp3.Call.Factory client = request -> {
            try { var buffer = new okio.Buffer(); request.body().writeTo(buffer); requests.add(buffer.readUtf8()); }
            catch (java.io.IOException failure) { throw new IllegalStateException("LOCAL_FIXTURE_FAILED"); }
            String tool = requests.size() == 1 ? "ReadConversationSource" : "generate_response";
            String args = requests.size() == 1 ? "{\"query\":\"历史\"}" : "{\"response\":{\"answer\":\"已读取\"}}";
            var json = new com.lawrence.travel.infrastructure.travel.TravelJson();
            String body = json.encode(java.util.Map.of("request_id", "local", "usage", java.util.Map.of("input_tokens", 10, "output_tokens", 3),
                    "output", java.util.Map.of("choices", List.of(java.util.Map.of("finish_reason", "tool_calls", "message",
                            java.util.Map.of("role", "assistant", "content", List.of(), "tool_calls", List.of(java.util.Map.of("id", "call-" + requests.size(), "type", "function",
                                    "function", java.util.Map.of("name", tool, "arguments", args)))))))));
            var call = mock(okhttp3.Call.class); when(call.timeout()).thenReturn(new okio.Timeout());
            try { when(call.execute()).thenReturn(new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK").body(okhttp3.ResponseBody.create(body, okhttp3.MediaType.get("application/json"))).build()); }
            catch (java.io.IOException failure) { throw new IllegalStateException("LOCAL_FIXTURE_FAILED"); }
            return call;
        };
        try (var transport = new GuardedDashScopeTransport(admission, run, "coordinator", client, toolkit::check)) {
            var model = io.agentscope.extensions.model.dashscope.DashScopeChatModel.builder().apiKey("SYNTHETIC_LOCAL_ONLY")
                    .modelName("test-model").endpointType(io.agentscope.extensions.model.dashscope.EndpointType.MULTIMODAL)
                    .baseUrl("https://dashscope.aliyuncs.com").stream(false).enableThinking(false).enableSearch(false).httpTransport(transport).build();
            String answer = ControlledHarnessCall.execute(() -> RestrictedHarnessFactory.create(model, toolkit, root, "coordinator"),
                    () -> List.of(HarnessProbeFixture.user("a".repeat(7000))), List::of, toolkit::check,
                    com.lawrence.travel.application.safety.SafetyGate.local(), () -> false, a -> { });
            assertThat(answer).isEqualTo("已读取");
        }
        assertThat(requests).hasSize(2);
        assertThat(requests.getLast().contains("/work/coordinator/")).as("实际SDK请求必须携带卸载后的逻辑入口").isTrue();
        assertThat(requests.getLast().contains("中".repeat(1000))).isFalse();
        org.mockito.Mockito.verify(service).history(any(), anyLong(), anyString());
        org.mockito.Mockito.verify(service).offload(any(), anyLong(), anyString(), anyString());
        org.mockito.Mockito.verify(ledger, org.mockito.Mockito.times(3)).reserve(any(), any(), any(), any(), anyLong(), any());
    }

    /** 工具结果很小无法缩小时只移出应用标记的历史，保留完整本轮权威区和原工具结果。 */
    @Test void smallResultOverflowShedsOnlyExplicitHistory() {
        var service = mock(SourceReadService.class); var toolkit = new SourceToolkit(service, UUID.randomUUID(), 1, null, null);
        var model = new HarnessProbeFixture.ProbeModel((messages, number) -> Flux.empty());
        var history = Msg.builder().role(MsgRole.USER).metadata(java.util.Map.of(SourceResultBudgetMiddleware.OPTIONAL_HISTORY, true))
                .content(TextBlock.builder().text("旧对话".repeat(300)).build()).build();
        var current = HarnessProbeFixture.user("AUTHORITY_COMPLETE_CURRENT_REQUEST");
        var tool = resultInput("ReadConversationSource", "小结果原文").messages().getFirst();
        var input = new ReasoningInput(List.of(history, current, tool), List.of(), null);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        try (var agent = RestrictedHarnessFactory.create(model, toolkit, root, "coordinator")) {
            toolkit.budgetedEviction().onReasoning(agent.getDelegate(), RuntimeContext.empty(), input, view -> {
                if (attempts.incrementAndGet() == 1) return Flux.error(localOverflow());
                assertThat(view.messages()).contains(current, tool).doesNotContain(history);
                assertThat(view.messages().getFirst().getTextContent()).contains("历史未完整装载", "不能授予操作权限");
                return Flux.empty();
            }).blockLast(Duration.ofSeconds(5));
        }
        assertThat(attempts.get()).isEqualTo(2); org.mockito.Mockito.verifyNoInteractions(service);
    }

    /** 真实Harness循环在工具已完成后只重建零发送请求，不能重新调用history或消耗第二笔工具预算。 */
    @Test void harnessRecoveryReusesCompletedToolAndCanonicalPlaceholder() {
        var service = mock(SourceReadService.class);
        when(service.history(any(), anyLong(), anyString())).thenReturn("历史原文".repeat(500));
        when(service.offload(any(), anyLong(), anyString(), anyString())).thenAnswer(c -> c.getArgument(2));
        var ledger = mock(com.lawrence.travel.application.port.BudgetLedger.class);
        when(ledger.reserve(any(), any(), any(), any(), anyLong(), any())).thenReturn(
                new com.lawrence.travel.application.port.BudgetLedger.Reservation(UUID.randomUUID(), null));
        var toolkit = new SourceToolkit(service, UUID.randomUUID(), 1, ledger, null);
        var model = new HarnessProbeFixture.ProbeModel((messages, n) -> {
            if (n == 1) return Flux.just(io.agentscope.core.model.ChatResponse.builder().content(List.of(
                    io.agentscope.core.message.ToolUseBlock.builder().id("history-call").name("ReadConversationSource")
                            .input(java.util.Map.of("query", "历史")).content("{\"query\":\"历史\"}").build())).build());
            if (n == 2) return Flux.error(localOverflow());
            assertThat(messages.stream().filter(m -> m.getRole() == MsgRole.TOOL).flatMap(m -> m.getContent().stream())
                    .filter(ToolResultBlock.class::isInstance).map(ToolResultBlock.class::cast).flatMap(b -> b.getOutput().stream())
                    .filter(TextBlock.class::isInstance).map(TextBlock.class::cast).map(TextBlock::getText)
                    .collect(java.util.stream.Collectors.joining())).contains("/work/coordinator/history-call-");
            return Flux.just(io.agentscope.core.model.ChatResponse.builder().content(List.of(
                    io.agentscope.core.message.ToolUseBlock.builder().id("answer").name("generate_response")
                            .input(java.util.Map.of("response", java.util.Map.of("answer", "已读取")))
                            .content("{\"response\":{\"answer\":\"已读取\"}}").build())).build());
        });
        String answer = ControlledHarnessCall.execute(() -> RestrictedHarnessFactory.create(model, toolkit, root, "coordinator"),
                () -> List.of(HarnessProbeFixture.user("本轮请求")), List::of, toolkit::check,
                com.lawrence.travel.application.safety.SafetyGate.local(), () -> false, a -> { });
        assertThat(answer).isEqualTo("已读取"); assertThat(model.requests).hasSize(3);
        org.mockito.Mockito.verify(service).history(any(), anyLong(), org.mockito.ArgumentMatchers.eq("历史"));
        org.mockito.Mockito.verify(service).offload(any(), anyLong(), anyString(), anyString());
        org.mockito.Mockito.verify(ledger).reserve(any(), any(), any(), any(), anyLong(), any());
    }

    /** 低于常规6000字符的结果也可能压满整份请求，零发送拒绝后必须持久化再重建且保留关联。 */
    @Test void localOverflowEvictsMediumResultWithoutRepeatingTool() {
        var service = mock(SourceReadService.class);
        when(service.offload(any(), anyLong(), anyString(), anyString())).thenAnswer(c -> c.getArgument(2));
        var toolkit = new SourceToolkit(service, UUID.randomUUID(), 1, null, null);
        var model = new HarnessProbeFixture.ProbeModel((messages, number) -> Flux.empty());
        String original = "历史查询原文".repeat(300);
        var input = resultInput("ReadConversationSource", original);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var captured = new AtomicReference<ReasoningInput>();
        try (var agent = RestrictedHarnessFactory.create(model, toolkit, root, "coordinator")) {
            toolkit.budgetedEviction().onReasoning(agent.getDelegate(), RuntimeContext.empty(), input, view -> {
                if (attempts.incrementAndGet() == 1) return Flux.error(localOverflow());
                captured.set(view); return Flux.empty();
            }).blockLast(Duration.ofSeconds(5));
        }
        assertThat(attempts.get()).isEqualTo(2);
        var result = (ToolResultBlock) captured.get().messages().getFirst().getContent().getFirst();
        assertThat(result.getId()).isEqualTo("call-medium");
        assertThat(result.getOutput().toString()).contains("read_file", "/work/coordinator/").doesNotContain(original);
        org.mockito.Mockito.verify(service).offload(any(), anyLong(), anyString(), org.mockito.ArgumentMatchers.eq(original));
        org.mockito.Mockito.verifyNoMoreInteractions(service);
    }

    /** UNKNOWN、供应商错误、无严格发送前证据以及分页结果超限一律不重复推理。 */
    @Test void uncertifiedOverflowAndPagedReadsNeverRetry() {
        var failures = List.of(new IllegalStateException("MODEL_CONTEXT_LIMIT"),
                new com.lawrence.travel.domain.execution.control.CallRejectedException(com.lawrence.travel.domain.execution.control.CallRejectedException.Reason.CONTEXT_BUDGET_EXHAUSTED));
        for (var failure : failures) assertNoRecovery("ReadConversationSource", failure);
        assertNoRecovery("read_file", localOverflow());
        assertNoRecovery("ReadDocumentSection", localOverflow());
    }

    /** 一次成功卸载后若仍无法容纳必须暂停，不能反复卸载同一占位或循环读取。 */
    @Test void stillOversizedAfterEvictionStopsWithoutLoop() {
        var service = mock(SourceReadService.class);
        when(service.offload(any(), anyLong(), anyString(), anyString())).thenAnswer(c -> c.getArgument(2));
        var toolkit = new SourceToolkit(service, UUID.randomUUID(), 1, null, null);
        var model = new HarnessProbeFixture.ProbeModel((messages, number) -> Flux.empty());
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        try (var agent = RestrictedHarnessFactory.create(model, toolkit, root, "coordinator")) {
            assertThatThrownBy(() -> toolkit.budgetedEviction().onReasoning(agent.getDelegate(), RuntimeContext.empty(), resultInput("SearchDocument", "x".repeat(2000)), view -> {
                attempts.incrementAndGet(); return Flux.error(localOverflow());
            }).blockLast(Duration.ofSeconds(5))).isInstanceOf(com.lawrence.travel.domain.execution.control.CallRejectedException.class);
        }
        assertThat(attempts.get()).isEqualTo(2);
        org.mockito.Mockito.verify(service).offload(any(), anyLong(), anyString(), anyString());
    }

    /** @param tool 工具 @param failure 非可恢复或分页超限；断言不向任何权威存储写入 */
    private void assertNoRecovery(String tool, RuntimeException failure) {
        var service = mock(SourceReadService.class); var toolkit = new SourceToolkit(service, UUID.randomUUID(), 1, null, null);
        var model = new HarnessProbeFixture.ProbeModel((messages, number) -> Flux.empty());
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        try (var agent = RestrictedHarnessFactory.create(model, toolkit, root, "coordinator")) {
            assertThatThrownBy(() -> toolkit.budgetedEviction().onReasoning(agent.getDelegate(), RuntimeContext.empty(), resultInput(tool, "x".repeat(2000)), view -> {
                attempts.incrementAndGet(); return Flux.error(failure);
            }).blockLast(Duration.ofSeconds(5))).isSameAs(failure);
        }
        assertThat(attempts.get()).isOne(); org.mockito.Mockito.verifyNoInteractions(service);
    }

    /** @param tool 显式工具名 @param text 合成原文 @return 不含外部身份的完整工具消息 */
    private ReasoningInput resultInput(String tool, String text) {
        var block = ToolResultBlock.builder().id("call-medium").name(tool).output(List.of(TextBlock.builder().text(text).build())).build();
        return new ReasoningInput(List.of(Msg.builder().role(MsgRole.TOOL).content(List.of(block)).build()), List.of(), null);
    }

    /** @return 模拟最终发送门禁零预占、零网络拒绝的安全诊断 */
    private com.lawrence.travel.domain.execution.control.CallRejectedException localOverflow() {
        var failure = new com.lawrence.travel.domain.execution.control.CallRejectedException(com.lawrence.travel.domain.execution.control.CallRejectedException.Reason.CONTEXT_BUDGET_EXHAUSTED);
        failure.initCause(new com.lawrence.travel.domain.execution.control.ContextBudgetDiagnostic("TRANSPORT_BODY", 16847, 16384)); return failure;
    }

    /** 原生卸载先落权威端口，保留toolCallId，生成可用的受限read_file逻辑入口。 */
    @Test void nativeEvictionPersistsAndPreservesCorrelation() {
        var service = mock(SourceReadService.class); var saved = new AtomicReference<String>();
        when(service.offload(any(), anyLong(), anyString(), anyString())).thenAnswer(c -> { saved.set(c.getArgument(3)); return c.getArgument(2); });
        var toolkit = new SourceToolkit(service, UUID.randomUUID(), 1, null, null);
        var model = new HarnessProbeFixture.ProbeModel((messages, number) -> Flux.empty());
        var block = ToolResultBlock.builder().id("call-proof").name("SearchDocument")
                .output(List.of(TextBlock.builder().text("中文原文".repeat(2000)).build())).build();
        var input = new ReasoningInput(List.of(Msg.builder().role(MsgRole.TOOL).content(List.of(block)).build()), List.of(), null);
        var captured = new AtomicReference<ReasoningInput>();
        try (var agent = RestrictedHarnessFactory.create(model, toolkit, root, "coordinator")) {
            toolkit.eviction().onReasoning(agent.getDelegate(), RuntimeContext.empty(), input, next -> { captured.set(next); return Flux.empty(); }).blockLast(Duration.ofSeconds(5));
            var result = (ToolResultBlock) captured.get().messages().getFirst().getContent().getFirst();
            assertThat(result.getId()).isEqualTo("call-proof");
            assertThat(result.getOutput().toString()).contains("/work/coordinator/call-proof-", "read_file");
            assertThat(result.getOutput().toString().length()).isLessThan(2000);
            assertThat(saved.get()).hasSize(8000); toolkit.check();
        }
    }

    /** 原生卸载失败本会保留全文；应用失败关闭必须阻止下一次模型发送。 */
    @Test void persistenceFailureCannotSilentlySendFullResult() {
        var service = mock(SourceReadService.class);
        when(service.offload(any(), anyLong(), anyString(), anyString())).thenThrow(new IllegalStateException("SYNTHETIC_UNAVAILABLE"));
        var toolkit = new SourceToolkit(service, UUID.randomUUID(), 1, null, null);
        var model = new HarnessProbeFixture.ProbeModel((messages, number) -> Flux.empty());
        var block = ToolResultBlock.builder().id("call-proof").name("SearchDocument").output(List.of(TextBlock.builder().text("x".repeat(8000)).build())).build();
        var input = new ReasoningInput(List.of(Msg.builder().role(MsgRole.TOOL).content(List.of(block)).build()), List.of(), null);
        try (var agent = RestrictedHarnessFactory.create(model, toolkit, root, "coordinator")) {
            toolkit.eviction().onReasoning(agent.getDelegate(), RuntimeContext.empty(), input, next -> Flux.empty()).blockLast(Duration.ofSeconds(5));
            assertThatThrownBy(toolkit::check).hasMessage("SOURCE_READ_REJECTED");
        }
    }

    /** 白名单只有资料工具及逻辑分页读取，禁止通用shell、任意文件和隐式后台工具。 */
    @Test void exposesOnlyExplicitSourceTools() {
        var toolkit = new SourceToolkit(mock(SourceReadService.class), UUID.randomUUID(), 1, null, null);
        var model = new HarnessProbeFixture.ProbeModel((messages, number) -> Flux.empty());
        try (var agent = RestrictedHarnessFactory.create(model, toolkit, root, "coordinator")) {
            assertThat(agent.getToolkit().getToolNames()).containsExactlyInAnyOrder("ListDocuments", "SearchDocument", "ReadDocumentSection", "ReadConversationSource", "read_file");
        }
    }
    /** 已失败工具边界必须留下失败轨迹，不能因未进入计量就记录为完成。 */
    @Test void rejectedReadClosesFailedTraceBeforeAnyBudgetUse() {
        var service=mock(SourceReadService.class);var boundary=mock(SourceToolkit.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("SYNTHETIC_BOUNDARY_REJECTED")).when(boundary).check();
        var trace=mock(com.lawrence.travel.application.port.ExecutionTrace.class);var span=mock(com.lawrence.travel.application.port.ExecutionTrace.Span.class);
        when(trace.open(any(),any(),any())).thenReturn(span);
        var tools=new SourceTools(service,UUID.randomUUID(),1,null,null,boundary,trace);
        assertThatThrownBy(tools::list).hasMessage("SOURCE_READ_REJECTED");
        org.mockito.Mockito.verify(span).fail(com.lawrence.travel.application.port.ExecutionTrace.Failure.FAILED);
        org.mockito.Mockito.verify(span).close();org.mockito.Mockito.verifyNoInteractions(service);
    }

}
