package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.execution.TravelToolService;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.application.port.ToolEvidenceJournal;
import com.lawrence.travel.application.port.TravelToolPort;
import com.lawrence.travel.domain.execution.control.RunLimits;
import com.lawrence.travel.infrastructure.tools.TravelHttpTools;
import java.time.Clock;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 无网络验证工具免费门禁、原文域名权限及按实际预报日期匹配。 */
class TravelToolContractTest {
    /** 缺少或过期的免费快照不影响调用；成功证据与执行计量仍保存。 */
    @Test
    void callsWithoutFreeQuotaEvidence() {
        var tools = mock(TravelToolPort.class);
        var journal = mock(ToolEvidenceJournal.class);
        var ledger = mock(BudgetLedger.class);
        var call = UUID.randomUUID();
        when(journal.reserve(any(), any())).thenReturn(call);
        when(ledger.reserve(any(), any(), any(), any(), anyLong(), any()))
                .thenReturn(new BudgetLedger.Reservation(UUID.randomUUID(), null));
        var evidence =
                new TravelToolPort.Evidence(
                        URI.create("https://example.invalid/evidence"),
                        Instant.now(),
                        "{\"status\":\"AVAILABLE\"}");
        when(tools.query(any(), any())).thenReturn(evidence);
        var limits = new RunLimits(30, 60, 2, Duration.ofSeconds(180), Duration.ofMinutes(15));
        var expired = new TravelToolService.Grant(Instant.EPOCH, Instant.EPOCH, 0, false);
        for (Map<String, TravelToolService.Grant> grants :
                List.of(Map.<String, TravelToolService.Grant>of(), Map.of("tavily", expired))) {
            var service =
                    new TravelToolService(
                            tools, journal, ledger, limits, grants, true, Clock.systemUTC());
            assertThat(
                            service.query(
                                    UUID.randomUUID(),
                                    TravelToolPort.Operation.SEARCH,
                                    Map.of("query", "旅行")))
                    .isEqualTo(evidence);
        }
        org.mockito.Mockito.verify(tools, org.mockito.Mockito.times(2)).query(any(), any());
        org.mockito.Mockito.verify(journal, org.mockito.Mockito.times(2)).finish(call, evidence);
    }

    /** 直接应用调查路径也要在预占前拒绝危险参数，不能只保护Agent包装。 */
    @Test
    void unsafeToolInputNeverReservesOrCalls() {
        var tools = mock(TravelToolPort.class);
        var journal = mock(ToolEvidenceJournal.class);
        var ledger = mock(BudgetLedger.class);
        var service =
                new TravelToolService(
                        tools,
                        journal,
                        ledger,
                        new RunLimits(30, 60, 2, Duration.ofSeconds(180), Duration.ofMinutes(15)),
                        Map.of(),
                        true,
                        Clock.systemUTC());
        assertThatThrownBy(
                        () ->
                                service.query(
                                        UUID.randomUUID(),
                                        TravelToolPort.Operation.SEARCH,
                                        Map.of("query", "<script>unsafe</script>")))
                .isInstanceOf(com.lawrence.travel.application.safety.SafetyGate.Failure.class);
        verifyNoInteractions(tools, journal, ledger);
    }

    /** 工具调用已发生但结果拒绝时不能保存为可用证据；仍保留原预算结算，不自动重试。 */
    @Test
    void unsafeToolOutputCannotBecomeUsableEvidence() {
        var tools = mock(TravelToolPort.class);
        var journal = mock(ToolEvidenceJournal.class);
        var ledger = mock(BudgetLedger.class);
        UUID call = UUID.randomUUID(), meter = UUID.randomUUID();
        when(journal.reserve(any(), any())).thenReturn(call);
        when(ledger.reserve(any(), any(), any(), any(), anyLong(), any()))
                .thenReturn(new BudgetLedger.Reservation(meter, null));
        when(tools.query(any(), any()))
                .thenReturn(
                        new TravelToolPort.Evidence(
                                java.net.URI.create("https://example.invalid/evidence"),
                                Instant.now(),
                                "<script>unsafe</script>"));
        var grant =
                new TravelToolService.Grant(
                        Instant.now().minusSeconds(10), Instant.now().plusSeconds(60), 1, true);
        var service =
                new TravelToolService(
                        tools,
                        journal,
                        ledger,
                        new RunLimits(30, 60, 2, Duration.ofSeconds(180), Duration.ofMinutes(15)),
                        Map.of("tavily", grant),
                        true,
                        Clock.systemUTC());
        assertThatThrownBy(
                        () ->
                                service.query(
                                        UUID.randomUUID(),
                                        TravelToolPort.Operation.SEARCH,
                                        Map.of("query", "风景")))
                .isInstanceOf(com.lawrence.travel.application.safety.SafetyGate.Failure.class);
        org.mockito.Mockito.verify(journal).reserve(any(), any());
        org.mockito.Mockito.verify(journal).finish(call, null);
        org.mockito.Mockito.verifyNoMoreInteractions(journal);
        org.mockito.Mockito.verify(ledger)
                .finish(
                        org.mockito.ArgumentMatchers.eq(meter),
                        org.mockito.ArgumentMatchers.isNull(),
                        any());
    }

    /** 错误协议、相似域名、认证信息和任意目标不能借原文提取访问。 */
    @Test
    void onlySelectedHttpsEvidenceHostsAreAllowed() {
        var tools = new TravelHttpTools(() -> "", () -> "", Set.of("www.dpm.org.cn"));
        assertThat(tools.requireEvidenceUrl("https://www.dpm.org.cn/").getHost())
                .isEqualTo("www.dpm.org.cn");
        for (String address :
                List.of(
                        "http://www.dpm.org.cn/",
                        "https://127.0.0.1/",
                        "https://localhost/",
                        "https://www.dpm.org.cn.evil.example/",
                        "https://user@www.dpm.org.cn/",
                        "https://www.dpm.org.cn:443/",
                        "https://www.dpm.org.cn/?key=example",
                        "https://www.dpm.org.cn/#x")) {
            assertThatThrownBy(() -> tools.requireEvidenceUrl(address))
                    .hasMessage("TOOL_EVIDENCE_URL_REJECTED");
        }
    }

    /** 不凭固定天数推断天气，覆盖日期缺失和空预报。 */
    @Test
    void weatherUsesReturnedDateRatherThanAssumedHorizon() {
        Map<String, Object> cast = Map.of("date", "2026-10-17", "dayweather", "晴");
        Map<String, Object> data = Map.of("forecasts", List.of(Map.of("casts", List.of(cast))));
        assertThat(TravelHttpTools.forecastFor(data, "2026-10-17")).isEqualTo(cast);
        assertThat(TravelHttpTools.forecastFor(data, "2026-10-26")).isNull();
        assertThat(TravelHttpTools.forecastFor(Map.of(), "2026-10-17")).isNull();
    }

    /** API 成功但无公交线路时必须返回未知，而非可行路线。 */
    @Test
    void emptyRouteIsNotAUsableJourney() {
        assertThat(TravelHttpTools.hasRoute(Map.of("route", Map.of("transits", List.of()))))
                .isFalse();
        assertThat(
                        TravelHttpTools.hasRoute(
                                Map.of(
                                        "route",
                                        Map.of("paths", List.of(Map.of("distance", "100"))))))
                .isTrue();
    }

    /** 总开关关闭时不能触碰仓储和真实传输。 */
    @Test
    void disabledCallsNeverReachProviders() {
        var tools = mock(TravelToolPort.class);
        var journal = mock(ToolEvidenceJournal.class);
        var ledger = mock(BudgetLedger.class);
        var limits = new RunLimits(30, 60, 2, Duration.ofSeconds(180), Duration.ofMinutes(15));
        var disabled =
                new TravelToolService(
                        tools, journal, ledger, limits, Map.of(), false, Clock.systemUTC());
        assertThatThrownBy(
                        () ->
                                disabled.query(
                                        UUID.randomUUID(),
                                        TravelToolPort.Operation.PLACE,
                                        Map.of()))
                .hasMessage("TOOLS_DISABLED");
        verifyNoInteractions(tools, journal, ledger);
    }

    /** 调用记录写入失败时，不申请运行预算或发送网络请求。 */
    @Test
    void journalFailureStopsBeforeSending() {
        var tools = mock(TravelToolPort.class);
        var journal = mock(ToolEvidenceJournal.class);
        var ledger = mock(BudgetLedger.class);
        when(journal.reserve(any(), any()))
                .thenThrow(new IllegalStateException("TOOL_JOURNAL_UNAVAILABLE"));
        var grant =
                new TravelToolService.Grant(
                        Instant.now().minusSeconds(10), Instant.now().plusSeconds(60), 1, true);
        var service =
                new TravelToolService(
                        tools,
                        journal,
                        ledger,
                        new RunLimits(30, 60, 2, Duration.ofSeconds(180), Duration.ofMinutes(15)),
                        Map.of("tavily", grant),
                        true,
                        Clock.systemUTC());
        assertThatThrownBy(
                        () ->
                                service.query(
                                        UUID.randomUUID(),
                                        TravelToolPort.Operation.SEARCH,
                                        Map.of()))
                .hasMessage("TOOL_JOURNAL_UNAVAILABLE");
        verifyNoInteractions(tools, ledger);
    }
}
