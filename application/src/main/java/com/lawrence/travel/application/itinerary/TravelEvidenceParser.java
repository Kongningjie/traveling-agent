package com.lawrence.travel.application.itinerary;

import com.lawrence.travel.domain.itinerary.EvidenceRules;
import com.lawrence.travel.domain.knowledge.FieldEvidence;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 只解析现有证据的供应商结构，领域再验证关联；不执行网络、不把解析成功当已核实。 */
public final class TravelEvidenceParser {
    /** 工具类。 */
    private TravelEvidenceParser() {}

    /**
     * @param facts 已通过引用和当前状态过滤的资料
     * @param decode 规范JSON解码
     * @return 有明确单位的GCJ-02地点及保守路线时长，缺失项不补零
     */
    public static EvidenceRules.TravelFacts parse(
            List<FieldEvidence> facts, Function<String, Object> decode) {
        var places = new java.util.ArrayList<EvidenceRules.Place>();
        var routes = new java.util.ArrayList<EvidenceRules.Route>();
        for (var fact : facts) {
            if (!List.of("poi", "route").contains(fact.fieldPath())) continue;
            try {
                Object value = decode.apply(fact.value());
                if (fact.fieldPath().equals("poi")
                        && fact.sourceType() == FieldEvidence.SourceType.MAP_PROVIDER
                        && value instanceof List<?> pois)
                    for (Object poi : pois) {
                        if (poi instanceof Map<?, ?> p
                                && p.get("id") instanceof String id
                                && p.get("cityname") instanceof String city
                                && p.get("location") instanceof String location)
                            places.add(
                                    new EvidenceRules.Place(
                                            fact.reference(), id, city, coordinate(location)));
                    }
                if (fact.fieldPath().equals("route")
                        && value instanceof Map<?, ?> route
                        && route.get("origin") instanceof String origin
                        && route.get("destination") instanceof String destination) {
                    Object choices =
                            route.get("paths") instanceof List<?>
                                    ? route.get("paths")
                                    : route.get("transits");
                    long maximum = -1;
                    if (choices instanceof List<?> paths)
                        for (Object path : paths)
                            if (path instanceof Map<?, ?> p) {
                                Object seconds =
                                        p.get("cost") instanceof Map<?, ?> cost
                                                ? cost.get("duration")
                                                : p.get("duration");
                                if (seconds == null) continue;
                                var duration = new java.math.BigDecimal(seconds.toString());
                                if (duration.signum() <= 0) continue;
                                maximum =
                                        Math.max(
                                                maximum,
                                                duration.divide(
                                                                java.math.BigDecimal.valueOf(60),
                                                                0,
                                                                java.math.RoundingMode.CEILING)
                                                        .longValueExact());
                            }
                    if (maximum > 0)
                        routes.add(
                                new EvidenceRules.Route(
                                        fact,
                                        coordinate(origin),
                                        coordinate(destination),
                                        maximum));
                }
            } catch (IllegalArgumentException | ArithmeticException invalid) {
                /* 坏结构保留未知，禁止把缺失时长当作零。 */
            }
        }
        return new EvidenceRules.TravelFacts(List.copyOf(places), List.copyOf(routes));
    }

    /**
     * @param text GCJ-02经纬度
     * @return 规范十进制，数值相同才归为相同端点
     */
    private static String coordinate(String text) {
        var parts = text.split(",", -1);
        if (parts.length != 2) throw new IllegalArgumentException("COORDINATE_INVALID");
        var lon = new java.math.BigDecimal(parts[0]);
        var lat = new java.math.BigDecimal(parts[1]);
        if (lon.abs().compareTo(new java.math.BigDecimal("180")) > 0
                || lat.abs().compareTo(new java.math.BigDecimal("90")) > 0)
            throw new IllegalArgumentException("COORDINATE_INVALID");
        return lon.stripTrailingZeros().toPlainString()
                + ","
                + lat.stripTrailingZeros().toPlainString();
    }
}
