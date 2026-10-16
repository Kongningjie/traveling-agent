package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.execution.TravelToolService;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.application.port.ToolEvidenceJournal;
import com.lawrence.travel.application.port.TravelToolPort;
import com.lawrence.travel.domain.execution.control.RunLimits;
import com.lawrence.travel.infrastructure.tools.TravelHttpTools;
import java.time.Clock;
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
    /** 错误协议、相似域名、认证信息和任意目标不能借原文提取访问。 */
    @Test
    void onlySelectedHttpsEvidenceHostsAreAllowed() {
        var tools = new TravelHttpTools(() -> "", () -> "", Set.of("www.dpm.org.cn"));
        assertThat(tools.requireEvidenceUrl("https://www.dpm.org.cn/").getHost()).isEqualTo("www.dpm.org.cn");
        for (String address : List.of("http://www.dpm.org.cn/", "https://127.0.0.1/", "https://localhost/",
                "https://www.dpm.org.cn.evil.example/", "https://user@www.dpm.org.cn/",
                "https://www.dpm.org.cn:443/", "https://www.dpm.org.cn/?key=example", "https://www.dpm.org.cn/#x")) {
            assertThatThrownBy(() -> tools.requireEvidenceUrl(address)).hasMessage("TOOL_EVIDENCE_URL_REJECTED");
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
        assertThat(TravelHttpTools.hasRoute(Map.of("route", Map.of("transits", List.of())))).isFalse();
        assertThat(TravelHttpTools.hasRoute(Map.of("route", Map.of("paths", List.of(Map.of("distance", "100")))))).isTrue();
    }

    /** 默认关闭或无额度证据均不能触碰仓储和真实传输。 */
    @Test
    void disabledAndUnverifiedCallsNeverReachProviders() {
        var tools = mock(TravelToolPort.class);
        var journal = mock(ToolEvidenceJournal.class);
        var ledger = mock(BudgetLedger.class);
        var limits = new RunLimits(30, 60, 2, Duration.ofSeconds(180), Duration.ofMinutes(15));
        var disabled = new TravelToolService(tools, journal, ledger, limits, Map.of(), false, Clock.systemUTC());
        assertThatThrownBy(() -> disabled.query(UUID.randomUUID(), TravelToolPort.Operation.PLACE, Map.of())).hasMessage("TOOLS_DISABLED");
        var unverified = new TravelToolService(tools, journal, ledger, limits, Map.of(), true, Clock.systemUTC());
        assertThatThrownBy(() -> unverified.query(UUID.randomUUID(), TravelToolPort.Operation.SEARCH, Map.of())).hasMessage("TOOL_QUOTA_UNVERIFIED");
        verifyNoInteractions(tools, journal, ledger);
    }

    /** 已耗尽服务组配额时，不申请运行预算或发送网络请求。 */
    @Test
    void exhaustedProviderQuotaStopsBeforeSending() {
        var tools = mock(TravelToolPort.class);
        var journal = mock(ToolEvidenceJournal.class);
        var ledger = mock(BudgetLedger.class);
        when(journal.reserve(any(), any(), any(), any(), anyLong())).thenThrow(new IllegalStateException("TOOL_QUOTA_EXHAUSTED"));
        var grant = new TravelToolService.Grant(Instant.now().minusSeconds(10), Instant.now().plusSeconds(60), 1, true);
        var service = new TravelToolService(tools, journal, ledger,
                new RunLimits(30, 60, 2, Duration.ofSeconds(180), Duration.ofMinutes(15)),
                Map.of("tavily", grant), true, Clock.systemUTC());
        assertThatThrownBy(() -> service.query(UUID.randomUUID(), TravelToolPort.Operation.SEARCH, Map.of())).hasMessage("TOOL_QUOTA_EXHAUSTED");
        verifyNoInteractions(tools, ledger);
    }
}
