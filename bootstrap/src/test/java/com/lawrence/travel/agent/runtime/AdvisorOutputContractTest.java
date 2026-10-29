package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.interaction.Dialogue;
import com.lawrence.travel.application.interaction.Negotiation;
import com.lawrence.travel.application.itinerary.Planning;
import com.lawrence.travel.application.knowledge.Research;
import com.lawrence.travel.application.port.AgentRuntimePort;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.TravelToolPort;
import com.lawrence.travel.domain.interaction.DialogueIntent;
import com.lawrence.travel.domain.itinerary.ItineraryReport;
import com.lawrence.travel.domain.itinerary.TripSnapshot;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TaskScope;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 截获生产Advisor的调用边界，确认七类输出均显式传DTO，不创建模型句柄。 */
class AdvisorOutputContractTest {
    /** 相同角色也须按调用区分意图、澄清和调查计划，规划与审核使用各自类型。 */
    @Test
    void productionCallersPassTheirActualDecodeTypes() {
        var runtime = mock(AgentRuntimePort.class);
        var codec = mock(InteractionCodec.class);
        when(codec.encode(any())).thenReturn("{}");
        when(codec.decode(anyString(), eq(Object.class))).thenReturn(Map.of());
        when(codec.decode(anyString(), eq(Map.class))).thenReturn(Map.of());
        var types = new ArrayList<Class<?>>();
        when(runtime.startContextual(any(), anyString(), anyString(), any(), any()))
                .thenAnswer(
                        call -> {
                            types.add(call.getArgument(4));
                            throw new IllegalStateException("LOCAL_CONTRACT_CAPTURE");
                        });
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
        capture(() -> new DialogueIntentInterpreter(runtime, codec, true).interpret(dialogue));

        var negotiation = mock(Negotiation.Context.class);
        var message = mock(Negotiation.Message.class);
        when(message.text()).thenReturn("补充旅行要求");
        when(negotiation.message()).thenReturn(message);
        when(negotiation.revision()).thenReturn(revision);
        when(negotiation.receivedAt()).thenReturn(now);
        capture(() -> new NegotiationInterpreter(runtime, codec, true).interpret(run, negotiation));

        var research = new HarnessResearchAdvisor(runtime, codec, true, Set.of("official.example"));
        capture(() -> research.plan(run, new Research.Context(revision, List.of(), now)));
        capture(
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
                                        URI.create("https://official.example/tickets"),
                                        now,
                                        "{}")));

        var planner = new HarnessPlanningAdvisor(runtime, codec, true);
        var context = new Planning.Context(scope, revision, null, List.of(), now, null);
        capture(() -> planner.draft(run, context, null, List.of()));
        capture(() -> planner.patch(run, context, null, null, null, List.of()));
        var candidate = mock(Planning.Candidate.class);
        when(candidate.snapshot()).thenReturn(mock(TripSnapshot.class));
        when(candidate.validation()).thenReturn(mock(ItineraryReport.class));
        capture(() -> planner.review(run, context, candidate));

        assertThat(types)
                .containsExactly(
                        DialogueIntent.Result.class,
                        Negotiation.Interpretation.class,
                        Research.Plan.class,
                        Research.Extraction.class,
                        Planning.Draft.class,
                        Planning.PatchReply.class,
                        Planning.Review.class);
        verify(runtime, never()).startContextual(any(), any(), any(), any());
        verify(runtime, never()).start(any(), any(), any());
        verify(runtime, never()).startWithoutTools(any(), any(), any());
    }

    /**
     * 在调用边界主动停止，后续通过捕获的类型序列检查是否确实到达边界。
     *
     * @param call 本地生产入口调用
     */
    private void capture(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(RuntimeException.class);
    }
}
