package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.execution.AgentCallFailure;
import com.lawrence.travel.application.interaction.Dialogue;
import com.lawrence.travel.application.interaction.Negotiation;
import com.lawrence.travel.application.itinerary.Planning;
import com.lawrence.travel.application.knowledge.Research;
import com.lawrence.travel.application.port.AgentRuntimePort;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.TravelToolPort;
import com.lawrence.travel.domain.itinerary.ItineraryReport;
import com.lawrence.travel.domain.itinerary.TripSnapshot;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TaskScope;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

/** 验证七类真实Advisor保留失败分类并关闭原句柄，不在适配器中重发。 */
class AgentFailurePropagationTest {
    /** 异步多层包装不改变固定错误及恢复策略，各入口只启动一次句柄。 */
    @Test
    void allAdvisorsPreserveTypedFailuresWithoutRetrying() {
        for (String code :
                List.of(
                        "MODEL_OUTPUT_SCHEMA",
                        "MODEL_ACCESS_DENIED",
                        "MODEL_RATE_LIMITED",
                        "MODEL_PROVIDER_HTTP_503",
                        "MODEL_TRANSPORT_FAILED")) {
            var expected =
                    new SanitizedAgentFailure(
                            code,
                            new ProviderCallFailure(
                                    code,
                                    new com.lawrence.travel.domain.execution.control.TokenUsage(
                                            0, 0)));
            var runtime = mock(AgentRuntimePort.class);
            var invocation = mock(AgentRuntimePort.Invocation.class);
            when(invocation.result())
                    .thenReturn(CompletableFuture.failedFuture(new CompletionException(expected)));
            when(runtime.startContextual(any(), anyString(), anyString(), any(), any()))
                    .thenReturn(invocation);
            for (Runnable call : calls(runtime)) {
                assertThatThrownBy(call::run).isSameAs(expected);
            }
            verify(runtime, times(7))
                    .startContextual(any(), anyString(), anyString(), any(), any());
            verify(invocation, times(7)).close();
        }
    }

    /** 超时和中断不等待真实180秒；一次调用即暂停，中断标记保留到调用方处理。 */
    @Test
    @SuppressWarnings("unchecked")
    void timeoutAndInterruptionArePreservedWithoutExtraInvocations() throws Exception {
        for (boolean interrupted : List.of(false, true)) {
            var runtime = mock(AgentRuntimePort.class);
            var invocation = mock(AgentRuntimePort.Invocation.class);
            CompletableFuture<String> future = mock(CompletableFuture.class);
            when(future.toCompletableFuture()).thenReturn(future);
            when(future.get(anyLong(), any(TimeUnit.class)))
                    .thenThrow(
                            interrupted
                                    ? new InterruptedException("SYNTHETIC_PRIVATE")
                                    : new java.util.concurrent.TimeoutException(
                                            "SYNTHETIC_PRIVATE"));
            when(invocation.result()).thenReturn(future);
            when(runtime.startContextual(any(), anyString(), anyString(), any(), any()))
                    .thenReturn(invocation);
            for (Runnable call : calls(runtime)) {
                try {
                    assertThatThrownBy(call::run)
                            .isInstanceOf(AgentCallFailure.class)
                            .hasMessage(interrupted ? "MODEL_INTERRUPTED" : "MODEL_TIMEOUT");
                    assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interrupted);
                } finally {
                    Thread.interrupted();
                }
            }
            verify(runtime, times(7))
                    .startContextual(any(), anyString(), anyString(), any(), any());
            verify(invocation, times(7)).close();
        }
    }

    /**
     * 创建七类生产入口；仅替换运行句柄，提示组装及异常处理使用生产代码。
     *
     * @param runtime 完全本地运行端口
     * @return 不访问外部服务的调用列表
     */
    private List<Runnable> calls(AgentRuntimePort runtime) {
        var codec = mock(InteractionCodec.class);
        when(codec.encode(any())).thenReturn("{}");
        when(codec.decode(anyString(), eq(Object.class))).thenReturn(Map.of());
        when(codec.decode(anyString(), eq(Map.class))).thenReturn(Map.of());
        UUID run = UUID.randomUUID();
        var scope = new TaskScope(UUID.randomUUID(), UUID.randomUUID());
        var revision = new ConstraintRevision(scope, 1, List.of(), List.of());
        Instant now = Instant.parse("2026-10-29T00:00:00Z");
        var dialogue =
                new Dialogue.Claim(
                        scope.userId(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1,
                        run,
                        "北京",
                        now,
                        new Dialogue.Basis(null, null, 0, 0, null),
                        "{}");
        var negotiation = mock(Negotiation.Context.class);
        var message = mock(Negotiation.Message.class);
        when(message.text()).thenReturn("补充旅行要求");
        when(negotiation.message()).thenReturn(message);
        when(negotiation.revision()).thenReturn(revision);
        when(negotiation.receivedAt()).thenReturn(now);
        var research = new HarnessResearchAdvisor(runtime, codec, true, Set.of("official.example"));
        var planner = new HarnessPlanningAdvisor(runtime, codec, true);
        var context = new Planning.Context(scope, revision, null, List.of(), now, null);
        var candidate = mock(Planning.Candidate.class);
        when(candidate.snapshot()).thenReturn(mock(TripSnapshot.class));
        when(candidate.validation()).thenReturn(mock(ItineraryReport.class));
        return List.of(
                () -> new DialogueIntentInterpreter(runtime, codec, true).interpret(dialogue),
                () -> new NegotiationInterpreter(runtime, codec, true).interpret(run, negotiation),
                () -> research.plan(run, new Research.Context(revision, List.of(), now)),
                () ->
                        research.extract(
                                run,
                                new Research.Query(
                                        "place",
                                        "price",
                                        TravelToolPort.Operation.EXTRACT,
                                        Map.of("url", "https://official.example/tickets"),
                                        null),
                                new TravelToolPort.Evidence(
                                        URI.create("https://official.example/tickets"), now, "{}")),
                () -> planner.draft(run, context, null, List.of()),
                () -> planner.patch(run, context, null, null, null, List.of()),
                () -> planner.review(run, context, candidate));
    }
}
