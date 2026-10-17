package com.lawrence.travel.domain.itinerary;

import com.lawrence.travel.domain.knowledge.FieldEvidence;
import com.lawrence.travel.domain.knowledge.EvidenceRecord;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 对象、来源和日期共同决定是否支持字段；无法解释的原文保留 UNKNOWN，不用正则猜复杂营业规则。 */
public final class EvidenceRules {
    /** @param reference 地图地点原始引用 @param placeId 供应商地点身份 @param city 供应商城市 @param coordinates 规范GCJ-02坐标 */
    public record Place(EvidenceRecord.Reference reference, String placeId, String city, String coordinates) { }
    /** @param evidence 原始路线字段 @param origin 供应商起点 @param destination 供应商终点 @param minutes 保守路径时长，已向上取整 */
    public record Route(FieldEvidence evidence, String origin, String destination, long minutes) { }
    /** @param places 结构化地图地点 @param routes 结构化路线；解析不提升认知状态 */
    public record TravelFacts(List<Place> places, List<Route> routes) { }
    /** 无状态规则。 */
    private EvidenceRules() { }
    /** @param trip 完整快照 @param facts 已通过精确引用和状态过滤的事实 @param now 权威时间 @param object 对象 @param field 字段 @param target 实际地点 @param date 旅行日期 @return 明确同一对象与日期的事实 */
    public static List<FieldEvidence> matching(TripSnapshot trip, List<FieldEvidence> facts, Instant now, java.util.UUID object, String field, String target, java.time.LocalDate date) {
        return trip.evidenceUses().stream().filter(u -> u.objectId().equals(object) && u.fieldPath().equals(field) && trip.evidenceRefs().contains(u.reference()))
                .flatMap(u -> facts.stream().filter(f -> f.reference().equals(u.reference()) && f.fieldPath().equals(field) && Objects.equals(f.targetId(), target)
                        && f.status() == EvidenceRecord.Status.KNOWN && f.reusableAt(now, date) && f.sourceType() != FieldEvidence.SourceType.SEARCH_LEAD && f.sourceType() != FieldEvidence.SourceType.UNKNOWN)).toList();
    }
    /** @param trip 完整快照 @param facts 当前事实 @param now 权威时间 @param item 活动 @return 简单且明确的当日开放窗口覆盖；预约与复杂公告仍需单独校验 */
    public static boolean opening(TripSnapshot trip, List<FieldEvidence> facts, Instant now, TripSnapshot.Item item) {
        return matching(trip, facts, now, item.id(), "openingHours", item.locationKey(), item.startsAt().toLocalDate()).stream().anyMatch(f -> {
            if (f.sourceType() != FieldEvidence.SourceType.OFFICIAL || f.quote().matches("(?s).*(关闭|除外|例外|仅限|节假日).*")) return false;
            var matcher = java.util.regex.Pattern.compile("(?:[0-9]{4}-[0-9]{2}-[0-9]{2}\\s+)?([0-9]{2}:[0-9]{2})-([0-9]{2}:[0-9]{2})").matcher(f.value().trim());
            if (!matcher.matches() || !item.startsAt().toLocalDate().equals(item.endsAt().toLocalDate())) return false;
            try { var start = java.time.LocalTime.parse(matcher.group(1)); var end = java.time.LocalTime.parse(matcher.group(2)); return !item.startsAt().toLocalTime().isBefore(start) && !item.endsAt().toLocalTime().isAfter(end) && end.isAfter(start); }
            catch (RuntimeException invalid) { return false; }
        });
    }
    /** @param trip 行程 @param facts 当前事实 @param now 时间 @param cost 费用 @return 字面单价、单位、关联地点和数量完全匹配；不能把优惠价当普适票价 */
    public static boolean price(TripSnapshot trip, List<FieldEvidence> facts, Instant now, TripSnapshot.Cost cost) {
        var item = trip.items().stream().filter(i -> i.id().equals(cost.relatedId())).findFirst().orElse(null);
        if (cost.upper() == null || cost.lower() == null || cost.unit() == null || cost.quantity() == null || cost.quantity().compareTo(java.math.BigDecimal.ONE) != 0 || cost.lower().compareTo(cost.upper()) != 0) return false;
        String expected = cost.lower().stripTrailingZeros().toPlainString() + "元/" + switch (cost.unit()) { case PER_PERSON -> "人"; case PER_GROUP -> "组"; case PER_ROOM_NIGHT -> "房晚"; };
        if (item != null) return matching(trip, facts, now, cost.id(), "price", item.locationKey(), item.startsAt().toLocalDate()).stream().anyMatch(f -> priceValue(f, expected));
        var hotel = trip.accommodationStays().stream().filter(s -> s.id().equals(cost.relatedId())).findFirst().orElse(null);
        if (hotel == null || hotel.locationKey() == null || hotel.checkIn() == null || hotel.checkOut() == null || !hotel.checkOut().toLocalDate().isAfter(hotel.checkIn().toLocalDate()) || cost.unit() != TripSnapshot.CostUnit.PER_ROOM_NIGHT || !cost.category().equals("LODGING")) return false;
        return hotel.checkIn().toLocalDate().datesUntil(hotel.checkOut().toLocalDate()).allMatch(date -> matching(trip, facts, now, cost.id(), "price", hotel.locationKey(), date).stream().anyMatch(f -> priceValue(f, expected)));
    }
    /** @param fact 原文事实 @param expected 完整单价单位 @return 无优惠资格等例外时才支持通用费用；房晚逐日验证 */
    private static boolean priceValue(FieldEvidence fact, String expected) { return fact.sourceType() == FieldEvidence.SourceType.OFFICIAL && fact.value().trim().equals(expected) && !fact.quote().matches("(?s).*(优惠|起价|起售|儿童|老人|学生|仅限|会员|不含).*"); }
    /** @param trip 行程 @param facts 精确来源的结构化地图数据 @param now 当前时间 @param leg 路线 @return 地点身份、城市、GCJ-02起终点、日期及保守时长同时匹配；仍是估算 */
    public static boolean route(TripSnapshot trip, TravelFacts facts, Instant now, TripSnapshot.Transport leg) {
        var from = trip.items().stream().filter(i -> i.id().equals(leg.fromItemId())).findFirst().orElse(null); var to = trip.items().stream().filter(i -> i.id().equals(leg.toItemId())).findFirst().orElse(null);
        if (from == null || to == null || !leg.estimated() || leg.durationMinutes() == null) return false;
        for (var route : facts.routes()) {
            var f = route.evidence();
            if (f.sourceType() != FieldEvidence.SourceType.ESTIMATE || f.status() != EvidenceRecord.Status.KNOWN || !f.reusableAt(now, leg.startsAt().toLocalDate()) || route.minutes() < 1 || leg.durationMinutes() < route.minutes()
                    || trip.evidenceUses().stream().noneMatch(u -> u.objectId().equals(leg.id()) && u.fieldPath().equals("route") && u.reference().equals(f.reference()) && trip.evidenceRefs().contains(u.reference()))) continue;
            if (place(trip, facts, from, route.origin()) && place(trip, facts, to, route.destination())) return true;
        }
        return false;
    }
    /** @param trip 行程 @param facts 原始地点解析 @param item 实际对象 @param coordinates 路线端点 @return 供应商地点身份与城市同时匹配；同名地点或随意坐标不能偷换 */
    private static boolean place(TripSnapshot trip, TravelFacts facts, TripSnapshot.Item item, String coordinates) {
        var stay = trip.cityStays().stream().filter(s -> s.id().equals(item.cityStayId())).findFirst().orElse(null); if (stay == null || item.locationKey() == null) return false;
        return facts.places().stream().anyMatch(p -> (p.placeId().equals(item.locationKey()) || ("poi:" + p.placeId()).equals(item.locationKey())) && p.coordinates().equals(coordinates)
                && (p.city().equals(stay.cityId()) || p.city().equals(stay.cityId() + "市")) && trip.evidenceUses().stream().anyMatch(u -> u.objectId().equals(item.id()) && u.fieldPath().equals("poi") && u.reference().equals(p.reference()) && trip.evidenceRefs().contains(u.reference())));
    }
}
