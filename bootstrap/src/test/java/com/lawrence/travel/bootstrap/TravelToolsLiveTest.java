package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.execution.TravelToolService;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.application.port.TravelToolPort;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

/** 明确启用才进行小样本真实工具验证，额度和数据记录保留，不自动重试。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_LIVE_TOOL_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(properties = "travel.tools.enabled=true", webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TravelToolsLiveTest {
    @Autowired TravelToolService tools;
    @Autowired BudgetLedger ledger;
    @Autowired JdbcTemplate jdbc;

    /** 地点、三种路线、天气覆盖／超期、搜索和原文均有来源及 PostgreSQL 证据。 */
    @Test
    void verifiesFiveToolsWithoutCallingModels() {
        UUID run = UUID.randomUUID();
        ledger.create(run);
        String date = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        var place = tools.query(run, TravelToolPort.Operation.PLACE, Map.of("city", "北京", "keywords", "故宫"));
        assertThat(place.payload()).contains("故宫", "pois");
        for (String mode : java.util.List.of("walking", "driving", "transit")) {
            var route = tools.query(run, TravelToolPort.Operation.ROUTE, Map.of("origin", "116.397026,39.918058",
                    "destination", "116.3967,39.9237", "city", "110000", "destinationCity", "110000", "mode", mode, "date", date));
            assertThat(route.payload()).contains("route", "\"estimate\":true", "GCJ-02");
        }
        var weather = tools.query(run, TravelToolPort.Operation.WEATHER, Map.of("city", "110101", "date", date));
        assertThat(weather.payload()).contains("casts", "reporttime");
        var unknown = tools.query(run, TravelToolPort.Operation.WEATHER,
                Map.of("city", "110101", "date", LocalDate.parse(date).plusDays(10).toString()));
        assertThat(unknown.payload()).contains("UNKNOWN_OUTSIDE_RETURNED_FORECAST");
        var search = tools.query(run, TravelToolPort.Operation.SEARCH, Map.of("query", "故宫博物院 开放时间 site:dpm.org.cn"));
        assertThat(search.payload()).contains("results", "SEARCH_LEAD_NOT_VERIFIED");
        var extract = tools.query(run, TravelToolPort.Operation.EXTRACT, Map.of("url", "https://www.dpm.org.cn/"));
        assertThat(extract.payload()).contains("raw_content").doesNotContain("UNKNOWN_EXTRACTION_FAILED");
        assertThat(extract.source().getHost()).isEqualTo("www.dpm.org.cn");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tool_evidence WHERE run_id=? AND status='COMPLETED' AND length(content_hash)=64",
                Integer.class, run)).isEqualTo(8);
        assertThat(ledger.read(run).toolCalls()).isEqualTo(8);
        assertThat(ledger.read(run).modelCalls()).isZero();
        System.out.println("LIVE_TRAVEL_TOOLS_PASS run=" + run);
    }

    /** 仅复测真实空公交结果的未知分类，不重复已通过的整套外部调用。 */
    @Test
    void verifiesEmptyTransitResult() {
        UUID run = UUID.randomUUID();
        ledger.create(run);
        var result = tools.query(run, TravelToolPort.Operation.ROUTE, Map.of("origin", "116.397026,39.918058",
                "destination", "116.3967,39.9237", "city", "110000", "destinationCity", "110000", "mode", "transit",
                "date", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()));
        assertThat(result.payload()).contains("UNKNOWN_NO_ROUTE");
        System.out.println("LIVE_EMPTY_ROUTE_PASS run=" + run);
    }
}
