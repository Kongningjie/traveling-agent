package com.lawrence.travel.domain.itinerary;

import com.lawrence.travel.domain.travel.ConstraintRevision;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 基础确定性规则；无法解释的硬约束和缺少事实明确 UNKNOWN，不依靠模型记忆补事实。 */
public final class ItineraryValidator {
    /** 无状态规则，可并发调用。 */
    public ItineraryValidator() { }
    /**
     * @param snapshot 已通过类型解码的完整快照
     * @param requirements 已确认需求
     * @return 问题列表；真实候选还必须通过独立审核
     */
    public List<ItineraryReport.Issue> validate(TripSnapshot snapshot, ConstraintRevision requirements) {
        return validate(snapshot, requirements, List.of(), java.time.Instant.EPOCH);
    }
    /** @param snapshot 完整草案 @param requirements 已确认需求 @param facts 精确引用且当前有效的事实 @param now 权威时间 @return 确定性问题；模型不能提供通过标记 */
    public List<ItineraryReport.Issue> validate(TripSnapshot snapshot, ConstraintRevision requirements, List<com.lawrence.travel.domain.knowledge.FieldEvidence> facts, java.time.Instant now) {
        return validate(snapshot, requirements, facts, now, new EvidenceRules.TravelFacts(List.of(), List.of()));
    }
    /** @param snapshot 完整内容 @param requirements 当前需求 @param facts 精确有效字段 @param now 权威时间 @param travelFacts 供应商结构解析结果 @return 确定性问题；交通必须证明端点、地点身份、日期与单位 */
    public List<ItineraryReport.Issue> validate(TripSnapshot snapshot, ConstraintRevision requirements, List<com.lawrence.travel.domain.knowledge.FieldEvidence> facts, java.time.Instant now, EvidenceRules.TravelFacts travelFacts) {
        var issues = new ArrayList<ItineraryReport.Issue>();
        if (snapshot.schemaVersion() != 1 || !snapshot.taskId().equals(requirements.scope().taskId()) || snapshot.constraintRevision() != requirements.revision()) bad(issues, "SCHEMA_BINDING", null);
        var ids = new HashSet<UUID>();
        var cities = new HashMap<UUID, TripSnapshot.CityStay>();
        for (var stay : snapshot.cityStays()) {
            id(ids, stay.id(), issues); cities.put(stay.id(), stay);
            if (!range(stay.startsAt(), stay.endsAt()) || stay.cityId() == null || !snapshot.window().cityIds().contains(stay.cityId())) bad(issues, "CITY_STAY_INVALID", stay.id());
        }
        if (cities.isEmpty()) bad(issues, "CITY_STAY_MISSING", null);
        if (!snapshot.cityStays().stream().map(TripSnapshot.CityStay::cityId).collect(java.util.stream.Collectors.toSet()).equals(snapshot.window().cityIds())) bad(issues, "CITY_COVERAGE", null);
        var dates = new HashSet<java.time.LocalDate>();
        for (var day : snapshot.days()) { id(ids, day.id(), issues); if (day.date() == null || !dates.add(day.date())) bad(issues, "DAY_INVALID", day.id()); }
        if (!dates.equals(snapshot.window().startsOn().datesUntil(snapshot.window().endsOn().plusDays(1)).collect(java.util.stream.Collectors.toSet()))) bad(issues, "DAY_COVERAGE", null);
        var items = new HashMap<UUID, TripSnapshot.Item>();
        for (var item : snapshot.items()) {
            id(ids, item.id(), issues); items.put(item.id(), item);
            var city = cities.get(item.cityStayId());
            if (item.type() == null || item.title() == null || item.title().isBlank() || !range(item.startsAt(), item.endsAt()) || city == null) { bad(issues, "ITEM_INVALID", item.id()); continue; }
            if (!range(city.startsAt(), city.endsAt()) || item.startsAt().toInstant().isBefore(city.startsAt().toInstant()) || item.endsAt().toInstant().isAfter(city.endsAt().toInstant())
                    || item.startsAt().toLocalDate().isBefore(snapshot.window().startsOn()) || item.endsAt().minusNanos(1).toLocalDate().isAfter(snapshot.window().endsOn())) bad(issues, "ITEM_OUTSIDE_TRIP", item.id());
            if (item.type() == TripSnapshot.ItemType.ACTIVITY) {
                var fixed = requirements.fixedArrangements().stream().filter(value -> value.id().equals(item.fixedArrangementId())).findFirst();
                if (fixed.isEmpty() && !EvidenceRules.opening(snapshot, facts, now, item)) unknown(issues, "OPENING_UNVERIFIED", item.id());
                else if (fixed.isPresent() && (!fixed.get().startsAt().toInstant().equals(item.startsAt().toInstant()) || !fixed.get().endsAt().toInstant().equals(item.endsAt().toInstant()))) bad(issues, "FIXED_TIME_CHANGED", item.id());
                if (fixed.isEmpty() && EvidenceRules.matching(snapshot, facts, now, item.id(), "reservation", item.locationKey(), item.startsAt().toLocalDate()).stream().noneMatch(f -> f.sourceType() == com.lawrence.travel.domain.knowledge.FieldEvidence.SourceType.OFFICIAL && f.value().equals("无需预约"))) unknown(issues, "RESERVATION_UNVERIFIED", item.id());
            }
        }
        if (items.isEmpty()) bad(issues, "ITEM_MISSING", null);
        var ordered = snapshot.items().stream().filter(item -> range(item.startsAt(), item.endsAt())).sorted(Comparator.comparing(item -> item.startsAt().toInstant())).toList();
        for (int i = 1; i < ordered.size(); i++) {
            var before = ordered.get(i - 1); var after = ordered.get(i);
            if (before.endsAt().toInstant().isAfter(after.startsAt().toInstant())) bad(issues, "TIME_OVERLAP", after.id());
            if (!Objects.equals(before.locationKey(), after.locationKey()) || before.locationKey() == null || !Objects.equals(before.cityStayId(), after.cityStayId())) {
                if (snapshot.transportLegs().stream().noneMatch(leg -> before.id().equals(leg.fromItemId()) && after.id().equals(leg.toItemId()))) unknown(issues, "TRANSPORT_MISSING", after.id());
            }
        }
        for (var leg : snapshot.transportLegs()) {
            id(ids, leg.id(), issues); var from = items.get(leg.fromItemId()); var to = items.get(leg.toItemId());
            if (from == null || to == null || !range(leg.startsAt(), leg.endsAt())) { bad(issues, "TRANSPORT_INVALID", leg.id()); continue; }
            if (!range(from.startsAt(), from.endsAt()) || !range(to.startsAt(), to.endsAt()) || leg.startsAt().toInstant().isBefore(from.endsAt().toInstant()) || leg.endsAt().toInstant().isAfter(to.startsAt().toInstant())) bad(issues, "TRANSPORT_CONNECTION", leg.id());
            if (leg.durationMinutes() == null) unknown(issues, "TRANSPORT_DURATION_UNKNOWN", leg.id());
            else if (leg.durationMinutes() < 0 || Duration.between(leg.startsAt(), leg.endsAt()).toMinutes() < leg.durationMinutes()) bad(issues, "TRANSPORT_DURATION", leg.id());
            if (leg.durationMinutes() != null && !confirmedField(requirements, "transportMinutes:" + leg.id(), Long.toString(leg.durationMinutes()))) {
                if (!EvidenceRules.route(snapshot, travelFacts, now, leg)) unknown(issues, "TRANSPORT_UNVERIFIED", leg.id());
                else issues.add(new ItineraryReport.Issue("ROUTE_ESTIMATE", leg.id(), false, false));
            }
        }
        var accommodations = new HashMap<UUID, TripSnapshot.Accommodation>();
        for (var stay : snapshot.accommodationStays()) {
            id(ids, stay.id(), issues); accommodations.put(stay.id(), stay);
            if (!cities.containsKey(stay.cityStayId()) || !range(stay.checkIn(), stay.checkOut()) || stay.rooms() < 1) bad(issues, "ACCOMMODATION_INVALID", stay.id());
        }
        for (var fixed : requirements.fixedArrangements()) if (snapshot.items().stream().noneMatch(item -> fixed.id().equals(item.fixedArrangementId())
                && range(item.startsAt(), item.endsAt()) && fixed.startsAt().toInstant().equals(item.startsAt().toInstant()) && fixed.endsAt().toInstant().equals(item.endsAt().toInstant()))) unknown(issues, "FIXED_ARRANGEMENT_UNMAPPED", fixed.id());
        Set<UUID> billable = new HashSet<>(items.keySet());
        snapshot.transportLegs().forEach(leg -> billable.add(leg.id())); billable.addAll(accommodations.keySet());
        BigDecimal upper = BigDecimal.ZERO; var billed = new HashSet<UUID>(); var chargeKeys = new HashSet<String>(); boolean costUnknown = false;
        for (var cost : snapshot.costEntries()) {
            id(ids, cost.id(), issues);
            billed.add(cost.relatedId());
            if (!billable.contains(cost.relatedId()) || cost.category() == null || !cost.category().matches("[A-Z][A-Z0-9_]{1,47}")
                    || !chargeKeys.add(cost.relatedId() + ":" + cost.category()) || cost.unit() == null || cost.quantity() == null || cost.quantity().signum() <= 0
                    || cost.lower() == null || cost.lower().signum() < 0 || !"CNY".equals(cost.currency())) { bad(issues, "COST_INVALID_OR_DUPLICATED", cost.id()); continue; }
            if (cost.upper() != null && cost.upper().compareTo(cost.lower()) < 0) { bad(issues, "COST_RANGE", cost.id()); continue; }
            // 先验证费用范围授权，再处理未知价格，避免提前 continue 让已付款或排除项绕过确认。
            if (cost.paid() && !confirmedField(requirements, "paidIncluded:" + cost.id(), Boolean.toString(cost.includedInBudget()))) unknown(issues, "PAID_SCOPE_UNCONFIRMED", cost.id());
            if (!cost.paid() && !cost.includedInBudget() && !confirmedField(requirements, "budgetIncluded:" + cost.id(), "false") && !excludedByScope(requirements, cost.category())) unknown(issues, "BUDGET_EXCLUSION_UNCONFIRMED", cost.id());
            if (cost.upper() == null) { costUnknown = true; unknown(issues, "COST_UNKNOWN", cost.id()); continue; }
            if (!confirmedField(requirements, "costCny:" + cost.id(), costConfirmation(cost)) && !EvidenceRules.price(snapshot, facts, now, cost)) {
                costUnknown = true; unknown(issues, "COST_UNVERIFIED", cost.id());
            }
            BigDecimal quantity = cost.quantity();
            if (cost.unit() == TripSnapshot.CostUnit.PER_PERSON) quantity = quantity.multiply(BigDecimal.valueOf(snapshot.window().travelers()));
            if (cost.unit() == TripSnapshot.CostUnit.PER_ROOM_NIGHT) {
                var stay = accommodations.get(cost.relatedId());
                if (stay == null || !range(stay.checkIn(), stay.checkOut())) { bad(issues, "ROOM_NIGHT_INVALID", cost.id()); continue; }
                quantity = BigDecimal.valueOf(ChronoUnit.DAYS.between(stay.checkIn().toLocalDate(), stay.checkOut().toLocalDate()) * stay.rooms());
                if (quantity.signum() <= 0) { bad(issues, "ROOM_NIGHT_INVALID", cost.id()); continue; }
            }
            if (cost.includedInBudget()) upper = upper.add(cost.upper().multiply(quantity));
        }
        for (var item : snapshot.items()) if ((item.type() == TripSnapshot.ItemType.ACTIVITY || item.type() == TripSnapshot.ItemType.MEAL) && !billed.contains(item.id())) { costUnknown = true; unknown(issues, "COST_ITEM_MISSING", item.id()); }
        for (UUID related : billable) if (!items.containsKey(related) && !billed.contains(related)) { costUnknown = true; unknown(issues, "COST_ITEM_MISSING", related); }
        for (var alternative : snapshot.alternatives()) { id(ids, alternative.id(), issues); if (!items.containsKey(alternative.replacesItemId()) || alternative.description() == null || alternative.description().isBlank()) bad(issues, "ALTERNATIVE_INVALID", alternative.id()); }
        // 初始规则只解释明确字段；未知硬约束必须阻断，不能因未实现规则而静默 PASS。
        for (var requirement : requirements.requirements()) if (requirement.kind() == ConstraintRevision.Kind.HARD) {
            if (!requirement.targetIds().isEmpty()) { unknown(issues, "HARD_SCOPE_UNSUPPORTED", requirement.id()); continue; }
            try {
                switch (requirement.field()) {
                    case "budgetCny" -> { var cap = new BigDecimal(requirement.value()); if (requirements.requirements().stream().anyMatch(r -> r.field().equals("budgetRule") && r.value().equals("PER_PERSON"))) cap = cap.multiply(BigDecimal.valueOf(snapshot.window().travelers())); if (upper.compareTo(cap) > 0) bad(issues, "HARD_BUDGET_EXCEEDED", requirement.id()); else if (costUnknown) unknown(issues, "HARD_BUDGET_UNKNOWN", requirement.id()); }
                    case "travelers" -> { if (snapshot.window().travelers() != Integer.parseInt(requirement.value())) bad(issues, "TRAVELERS_MISMATCH", requirement.id()); }
                    case "cities" -> { if (!snapshot.window().cityIds().equals(Set.of(requirement.value().split(",")))) bad(issues, "CITIES_MISMATCH", requirement.id()); }
                    case "startDate" -> { if (!snapshot.window().startsOn().toString().equals(requirement.value())) bad(issues, "START_DATE_MISMATCH", requirement.id()); }
                    case "endDate" -> { if (!snapshot.window().endsOn().toString().equals(requirement.value())) bad(issues, "END_DATE_MISMATCH", requirement.id()); }
                    case "budgetRule" -> { if (!Set.of("NONE", "PER_PERSON", "PER_GROUP").contains(requirement.value())) unknown(issues, "BUDGET_RULE_INVALID", requirement.id()); }
                    case "budgetScope" -> { if (!Set.of("ACTIVITIES", "MEALS", "LODGING", "INTERCITY", "ROUNDTRIP", "LOCAL_TRANSPORT").containsAll(List.of(requirement.value().split(",")))) unknown(issues, "BUDGET_SCOPE_INVALID", requirement.id()); }
                    case "fixedStatus" -> { if (!Set.of("NONE", "UNKNOWN", "PROVIDED").contains(requirement.value()) || (requirement.value().equals("PROVIDED") && requirements.fixedArrangements().isEmpty())) unknown(issues, "FIXED_INFORMATION_MISSING", requirement.id()); if (requirement.value().equals("NONE") && !requirements.fixedArrangements().isEmpty()) bad(issues, "FIXED_STATUS_CONFLICT", requirement.id()); }
                    case "lockedIds" -> { if (!ids.containsAll(ImpactAnalyzer.ids(requirement.value()))) bad(issues, "LOCK_TARGET_UNKNOWN", requirement.id()); }
                    case "fixedArrangements" -> { if (requirements.fixedArrangements().isEmpty()) unknown(issues, "FIXED_INFORMATION_MISSING", requirement.id()); }
                    default -> { if (!recognizedConfirmation(snapshot, requirement)) unknown(issues, "HARD_CONSTRAINT_UNSUPPORTED", requirement.id()); }
                }
            } catch (NumberFormatException invalid) { unknown(issues, "HARD_CONSTRAINT_UNPARSABLE", requirement.id()); }
        }
        for (var use : snapshot.evidenceUses()) if (!ids.contains(use.objectId()) || !snapshot.evidenceRefs().contains(use.reference())) bad(issues, "EVIDENCE_OBJECT_BINDING", use.objectId());
        for (var item : snapshot.items()) if (item.startsAt() != null && !item.startsAt().getOffset().equals(java.time.ZoneOffset.ofHours(8))) bad(issues, "TRIP_TIME_ZONE", item.id());
        for (var leg : snapshot.transportLegs()) if (leg.startsAt() != null && !leg.startsAt().getOffset().equals(java.time.ZoneOffset.ofHours(8))) bad(issues, "TRIP_TIME_ZONE", leg.id());
        for (var date : snapshot.window().startsOn().datesUntil(snapshot.window().endsOn()).toList()) if (snapshot.accommodationStays().stream().noneMatch(s -> range(s.checkIn(), s.checkOut()) && !s.checkIn().toLocalDate().isAfter(date) && s.checkOut().toLocalDate().isAfter(date))) unknown(issues, "LODGING_MISSING", null);
        for (var requirement : requirements.requirements()) if (requirement.field().equals("budgetScope")) for (String category : requirement.value().split(",")) {
            boolean required = category.equals("ROUNDTRIP") || category.equals("MEALS") || category.equals("LODGING") && snapshot.window().endsOn().isAfter(snapshot.window().startsOn()) || category.equals("INTERCITY") && snapshot.window().cityIds().size() > 1 || category.equals("LOCAL_TRANSPORT") && !snapshot.transportLegs().isEmpty();
            if (required && snapshot.costEntries().stream().noneMatch(cost -> category.equals(cost.category()))) unknown(issues, "BUDGET_CATEGORY_MISSING", requirement.id());
        }
        return List.copyOf(issues);
    }
    /** @param requirements 已确认费用口径 @param category 费用类别 @return 用户明确的范围排除；任意自造类别不能排除预算 */
    private static boolean excludedByScope(ConstraintRevision requirements, String category) {
        return Set.of("ACTIVITIES", "MEALS", "LODGING", "INTERCITY", "ROUNDTRIP", "LOCAL_TRANSPORT").contains(category)
                && requirements.requirements().stream().anyMatch(r -> r.field().equals("budgetScope") && confirmed(r) && !Set.of(r.value().split(",")).contains(category));
    }
    /**
     * @param snapshot 完整草案
     * @param value 已确认事实
     * @return 已解释的对象级字段；拼写或对象错误不能绕过硬约束
     */
    private static boolean recognizedConfirmation(TripSnapshot snapshot, ConstraintRevision.Requirement value) {
        return snapshot.costEntries().stream().anyMatch(cost -> value.field().equals("paidIncluded:" + cost.id())
                && value.value().equals(Boolean.toString(cost.includedInBudget()))
                || value.field().equals("budgetIncluded:" + cost.id()) && value.value().equals(Boolean.toString(cost.includedInBudget()))
                || value.field().equals("costCny:" + cost.id()) && cost.lower() != null && cost.upper() != null && cost.quantity() != null
                && value.value().equals(costConfirmation(cost)))
                || snapshot.transportLegs().stream().anyMatch(leg -> value.field().equals("transportMinutes:" + leg.id()) && leg.durationMinutes() != null && value.value().equals(Long.toString(leg.durationMinutes())));
    }
    /**
     * @param requirements 需求权威
     * @param field 精确对象字段
     * @param expected 计算口径
     * @return 非假设来源是否已经确认
     */
    private static boolean confirmedField(ConstraintRevision requirements, String field, String expected) { return requirements.requirements().stream().anyMatch(value -> value.field().equals(field) && value.value().equals(expected) && value.targetIds().isEmpty() && confirmed(value)); }
    /**
     * 确认的是完整费用口径，不能只确认单价后由草案把“每人”改成“每组”来降低总费用。
     * @param cost 已具备必需字段和已知上界的费用
     * @return 对象、分类、单位、数量、区间、付款及预算范围的稳定确认值
     */
    public static String costConfirmation(TripSnapshot.Cost cost) {
        return cost.relatedId() + "|" + cost.category() + "|" + cost.unit() + "|" + cost.quantity().stripTrailingZeros().toPlainString()
                + "|" + cost.lower().stripTrailingZeros().toPlainString() + ":" + cost.upper().stripTrailingZeros().toPlainString()
                + "|" + cost.currency() + "|" + cost.paid() + "|" + cost.includedInBudget();
    }
    /**
     * @param value 已记录需求
     * @return 是否为明确确认来源
     */
    private static boolean confirmed(ConstraintRevision.Requirement value) { return value.source() != ConstraintRevision.Source.ASSUMPTION; }
    /**
     * @param ids 已出现身份
     * @param id 稳定身份
     * @param issues 输出；所有内部对象禁止复用 UUID
     */
    private static void id(Set<UUID> ids, UUID id, List<ItineraryReport.Issue> issues) { if (id == null || !ids.add(id)) bad(issues, "STABLE_ID_INVALID", id); }
    /**
     * @param from 起始
     * @param to 结束
     * @return 按实际时间线合法，不以 HH:mm 判断
     */
    private static boolean range(java.time.ZonedDateTime from, java.time.ZonedDateTime to) { return from != null && to != null && to.toInstant().isAfter(from.toInstant()); }
    /**
     * @param issues 输出
     * @param code 固定码
     * @param id 对象
     */
    private static void bad(List<ItineraryReport.Issue> issues, String code, UUID id) { issues.add(new ItineraryReport.Issue(code, id, true, false)); }
    /**
     * @param issues 输出
     * @param code 固定码
     * @param id 对象
     */
    private static void unknown(List<ItineraryReport.Issue> issues, String code, UUID id) { issues.add(new ItineraryReport.Issue(code, id, true, true)); }
}
