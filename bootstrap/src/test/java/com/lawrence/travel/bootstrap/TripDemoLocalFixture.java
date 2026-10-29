package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.execution.TravelToolService;
import com.lawrence.travel.application.itinerary.Planning;
import com.lawrence.travel.application.knowledge.Research;
import com.lawrence.travel.application.port.PlanningAdvisor;
import com.lawrence.travel.application.port.ResearchAdvisor;
import com.lawrence.travel.application.port.TravelToolPort;
import com.lawrence.travel.domain.itinerary.TripSnapshot;
import com.lawrence.travel.domain.travel.TripWindow;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.any;

/** 单条演示的本地模型与资料替身；仅本地模式安装，不构成真实城市事实。 */
final class TripDemoLocalFixture {
    private final LocalDate date = LocalDate.of(2026, 11, 7);
    private final TravelJson json = new TravelJson();
    private final ResearchAdvisor researcher;
    private final PlanningAdvisor advisor;
    private final TravelToolService tools;

    /**
     * 绑定本地测试替身端口，不修改生产配置。
     *
     * @param researcher 调查模型端口
     * @param advisor 规划及审核端口
     * @param tools 资料工具端口
     */
    TripDemoLocalFixture(
            ResearchAdvisor researcher, PlanningAdvisor advisor, TravelToolService tools) {
        this.researcher = researcher;
        this.advisor = advisor;
        this.tools = tools;
    }

    /** 安装有原文证据的初始规划和稳定ID局部修改替身。 */
    void install() {
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            Research.Context context = invocation.getArgument(1);
                            return new Research.Plan(
                                    context.baseVersionId() == null
                                            ? List.of(
                                                    query("openingHours"),
                                                    query("price"),
                                                    query("reservation"))
                                            : List.of());
                        })
                .when(researcher)
                .plan(any(), any());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            if (invocation.getArgument(1) == TravelToolPort.Operation.WEATHER)
                                return new TravelToolPort.Evidence(
                                        URI.create(
                                                "https://restapi.amap.com/v3/weather/weatherInfo"),
                                        Instant.now().minusSeconds(1740),
                                        json.encode(
                                                Map.of(
                                                        "operation",
                                                        "WEATHER",
                                                        "status",
                                                        "UNKNOWN_OUTSIDE_RETURNED_FORECAST",
                                                        "targetDate",
                                                        date.toString(),
                                                        "data",
                                                        Map.of())));
                            @SuppressWarnings("unchecked")
                            var arguments = (Map<String, String>) invocation.getArgument(2);
                            String url = arguments.get("url");
                            String field = url.substring(url.lastIndexOf('/') + 1);
                            String value = value(field);
                            return new TravelToolPort.Evidence(
                                    URI.create(url),
                                    Instant.now(),
                                    json.encode(
                                            Map.of(
                                                    "operation",
                                                    "EXTRACT",
                                                    "status",
                                                    "AVAILABLE",
                                                    "data",
                                                    Map.of(
                                                            "results",
                                                            List.of(
                                                                    Map.of(
                                                                            "url",
                                                                            url,
                                                                            "raw_content",
                                                                            date + " "
                                                                                    + value))))));
                        })
                .when(tools)
                .query(any(), any(), any());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            Research.Query q = invocation.getArgument(1);
                            return new Research.Extraction(
                                    value(q.fieldPath()),
                                    date + " " + value(q.fieldPath()),
                                    null,
                                    date);
                        })
                .when(researcher)
                .extract(any(), any(), any());
        org.mockito.Mockito.doAnswer(
                        invocation -> new Planning.Draft(draft(invocation.getArgument(1))))
                .when(advisor)
                .draft(any(), any(), any(), any());
        org.mockito.Mockito.doReturn(new Planning.Review(List.of()))
                .when(advisor)
                .review(any(), any(), any());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            Planning.Base base = invocation.getArgument(2);
                            var item = base.snapshot().items().getFirst();
                            var replacement =
                                    new TripSnapshot.Item(
                                            item.id(),
                                            item.cityStayId(),
                                            item.type(),
                                            "景点说明：慢慢游览，可随时休息",
                                            item.locationKey(),
                                            item.startsAt(),
                                            item.endsAt(),
                                            item.fixedArrangementId());
                            var uses =
                                    base.snapshot().evidenceUses().stream()
                                            .filter(u -> u.objectId().equals(item.id()))
                                            .toList();
                            var operation =
                                    new com.lawrence.travel.domain.itinerary.ItineraryPatch
                                            .Operation(
                                            com.lawrence.travel.domain.itinerary.ItineraryPatch.Kind
                                                    .ITEM,
                                            com.lawrence.travel.domain.itinerary.ItineraryPatch
                                                    .Action.REPLACE,
                                            item.id(),
                                            null,
                                            null,
                                            replacement,
                                            null,
                                            null,
                                            null,
                                            null,
                                            uses);
                            return new Planning.PatchReply(
                                    new com.lawrence.travel.domain.itinerary.ItineraryPatch(
                                            base.hash(), List.of(operation), null),
                                    null);
                        })
                .when(advisor)
                .patch(any(), any(), any(), any(), any(), any());
    }

    /**
     * 构造本地字段查询。
     *
     * @param field 待查字段
     * @return 本地原文精确地址
     */
    private Research.Query query(String field) {
        return new Research.Query(
                "poi:123",
                field,
                TravelToolPort.Operation.EXTRACT,
                Map.of("url", "https://official.example/" + field),
                date);
    }

    /**
     * 返回本地示例字段值。
     *
     * @param field 字段
     * @return 与连续原文一致的测试值，非真实城市事实
     */
    private String value(String field) {
        return switch (field) {
            case "openingHours" -> "09:00-17:00";
            case "price" -> "20元/人";
            default -> "无需预约";
        };
    }

    /**
     * 构造本地推荐草案及备选。
     *
     * @param context 当前权威需求和证据
     * @return 一个推荐和局部备选，Java计算40元；不是模型评分证明
     */
    private TripSnapshot draft(Planning.Context context) {
        var start = date.atStartOfDay(ZoneId.of("Asia/Shanghai"));
        UUID city = UUID.randomUUID(), item = UUID.randomUUID(), cost = UUID.randomUUID();
        var refs =
                context.evidence().stream()
                        .filter(v -> v.reason().equals("ORIGINAL"))
                        .map(v -> v.evidence().reference())
                        .collect(java.util.stream.Collectors.toSet());
        var uses =
                context.evidence().stream()
                        .filter(v -> refs.contains(v.evidence().reference()))
                        .map(
                                v ->
                                        new TripSnapshot.EvidenceUse(
                                                v.evidence().fieldPath().equals("price")
                                                        ? cost
                                                        : item,
                                                v.evidence().fieldPath(),
                                                v.evidence().reference()))
                        .toList();
        return new TripSnapshot(
                1,
                context.scope().taskId(),
                context.revision().revision(),
                context.baseVersionId(),
                new TripWindow(Set.of("北京"), date, date, 2),
                List.of(new TripSnapshot.CityStay(city, "北京", start, start.plusDays(1))),
                List.of(new TripSnapshot.Day(UUID.randomUUID(), date)),
                List.of(
                        new TripSnapshot.Item(
                                item,
                                city,
                                TripSnapshot.ItemType.ACTIVITY,
                                "本地资料景点",
                                "poi:123",
                                start.plusHours(10),
                                start.plusHours(11),
                                null)),
                List.of(),
                List.of(),
                List.of(
                        new TripSnapshot.Cost(
                                cost,
                                item,
                                "ACTIVITIES",
                                TripSnapshot.CostUnit.PER_PERSON,
                                BigDecimal.ONE,
                                new BigDecimal("20"),
                                new BigDecimal("20"),
                                false,
                                true,
                                "CNY")),
                List.of(
                        new TripSnapshot.Alternative(
                                UUID.randomUUID(), item, "雨天保留休息备选，不承诺未经核实的场馆")),
                refs,
                uses);
    }
}
