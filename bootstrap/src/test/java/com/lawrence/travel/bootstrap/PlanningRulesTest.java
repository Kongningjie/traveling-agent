package com.lawrence.travel.bootstrap;

import com.lawrence.travel.agent.runtime.HarnessPlanningAdvisor;
import com.lawrence.travel.application.port.AgentRuntimePort;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.domain.itinerary.CostSummary;
import com.lawrence.travel.domain.itinerary.EvidenceRules;
import com.lawrence.travel.domain.itinerary.ItineraryValidator;
import com.lawrence.travel.domain.itinerary.TripSnapshot;
import com.lawrence.travel.domain.knowledge.EvidenceRecord;
import com.lawrence.travel.domain.knowledge.FieldEvidence;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 用确定性断言验证对象证据、预算和未知；不访问供应商或让模型自评。 */
class PlanningRulesTest {
    private final TaskScope scope = new TaskScope(UUID.randomUUID(), UUID.randomUUID());
    private final Instant now = Instant.parse("2026-10-17T00:00:00Z");
    /** @param value 字面证据 @param field 字段 @return 有明确来源的测试事实，不是实际旅行资料 */
    private FieldEvidence fact(String value, String field) { return new FieldEvidence(new EvidenceRecord.Reference(UUID.randomUUID(), 1, "a".repeat(64)), "same", field, value, FieldEvidence.SourceType.OFFICIAL, URI.create("https://official.example/" + field), null, now.minusSeconds(1), null, java.time.LocalDate.parse("2026-11-01"), now.plusSeconds(60), "2026-11-01 " + value, UUID.randomUUID(), EvidenceRecord.Status.KNOWN); }
    /** @param trip 夹具 @param facts 实际引用 @return 给对应活动／费用绑定字段，不引入第二份可编辑数据 */
    private TripSnapshot bound(TripSnapshot trip, List<FieldEvidence> facts) {
        var item = trip.items().getFirst(); UUID cost = trip.costEntries().isEmpty() ? item.id() : trip.costEntries().getFirst().id();
        return new TripSnapshot(1, trip.taskId(), 1, null, trip.window(), trip.cityStays(), trip.days(), trip.items(), trip.transportLegs(), trip.accommodationStays(), trip.costEntries(), trip.alternatives(), facts.stream().map(FieldEvidence::reference).collect(java.util.stream.Collectors.toSet()), facts.stream().map(f -> new TripSnapshot.EvidenceUse(f.fieldPath().equals("price") ? cost : item.id(), f.fieldPath(), f.reference())).toList());
    }
    /** 精确日期、对象和来源才支持开放窗口；超时引用不能被接受时继续使用。 */
    @Test void openingEvidenceMustCoverExactObjectDateAndTime() {
        var trip = ItineraryRulesTest.minimal(scope.taskId(), null); var evidence = fact("09:00-17:00", "openingHours"); trip = bound(trip, List.of(evidence));
        assertThat(EvidenceRules.opening(trip, List.of(evidence), now, trip.items().getFirst())).isTrue();
        assertThat(EvidenceRules.opening(trip, List.of(evidence), evidence.expiresAt(), trip.items().getFirst())).isFalse();
        var wrong = new FieldEvidence(evidence.reference(), "different-poi", evidence.fieldPath(), evidence.value(), evidence.sourceType(), evidence.sourceUrl(), null, evidence.retrievedAt(), null, evidence.validForDate(), evidence.expiresAt(), evidence.quote(), evidence.artifactId(), evidence.status());
        assertThat(EvidenceRules.opening(trip, List.of(wrong), now, trip.items().getFirst())).isFalse();
    }
    /** 优惠票、模糊时段和关闭例外不能通过简单窗口规则。 */
    @Test void complexCalendarRemainsUnknown() {
        var trip = ItineraryRulesTest.minimal(scope.taskId(), null); var evidence = fact("09:00-17:00", "openingHours");
        evidence = new FieldEvidence(evidence.reference(), evidence.targetId(), evidence.fieldPath(), evidence.value(), evidence.sourceType(), evidence.sourceUrl(), null, evidence.retrievedAt(), null, evidence.validForDate(), evidence.expiresAt(), "2026-11-01 09:00-17:00，节假日关闭", evidence.artifactId(), evidence.status()); trip = bound(trip, List.of(evidence));
        assertThat(EvidenceRules.opening(trip, List.of(evidence), now, trip.items().getFirst())).isFalse();
    }
    /** 每人不能换成每组，乘数也不能由模型减少以绕过硬预算。 */
    @Test void priceEvidenceConfirmsUnitAndQuantityTogether() {
        var base = ItineraryRulesTest.minimal(scope.taskId(), null); var item = base.items().getFirst(); var cost = new TripSnapshot.Cost(UUID.randomUUID(), item.id(), "ACTIVITIES", TripSnapshot.CostUnit.PER_PERSON, BigDecimal.ONE, new BigDecimal("20"), new BigDecimal("20"), false, true, "CNY");
        var trip = new TripSnapshot(1, base.taskId(), 1, null, base.window(), base.cityStays(), base.days(), base.items(), List.of(), List.of(), List.of(cost), List.of(), Set.of()); var evidence = fact("20元/人", "price"); trip = bound(trip, List.of(evidence));
        assertThat(EvidenceRules.price(trip, List.of(evidence), now, cost)).isTrue();
        var wrong = new TripSnapshot.Cost(cost.id(), cost.relatedId(), cost.category(), TripSnapshot.CostUnit.PER_GROUP, BigDecimal.ONE, cost.lower(), cost.upper(), false, true, "CNY");
        assertThat(EvidenceRules.price(trip, List.of(evidence), now, wrong)).isFalse(); assertThat(CostSummary.calculate(trip).upperCny()).isEqualByComparingTo("40");
    }
    /** 房晚按入住区间和房数计算，未知上界保持null，不当作0元。 */
    @Test void roomNightsAndUnknownCostsAreComputedByJava() {
        var base = ItineraryRulesTest.minimal(scope.taskId(), null); var city = base.cityStays().getFirst(); var stay = new TripSnapshot.Accommodation(UUID.randomUUID(), city.id(), city.startsAt(), city.startsAt().plusDays(3), 2);
        var cost = new TripSnapshot.Cost(UUID.randomUUID(), stay.id(), "LODGING", TripSnapshot.CostUnit.PER_ROOM_NIGHT, BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("120"), false, true, "CNY");
        var trip = new TripSnapshot(1, base.taskId(), 1, null, base.window(), base.cityStays(), base.days(), base.items(), List.of(), List.of(stay), List.of(cost), List.of(), Set.of());
        assertThat(CostSummary.calculate(trip).upperCny()).isEqualByComparingTo("720");
        var unknown = new TripSnapshot.Cost(cost.id(), cost.relatedId(), cost.category(), cost.unit(), cost.quantity(), cost.lower(), null, false, true, "CNY");
        trip = new TripSnapshot(1, base.taskId(), 1, null, base.window(), base.cityStays(), base.days(), base.items(), List.of(), List.of(stay), List.of(unknown), List.of(), Set.of()); assertThat(CostSummary.calculate(trip).upperCny()).isNull();
    }
    /** 用户的人数、日期、城市与未形式化硬限制都不能因为有草案而消失。 */
    @Test void confirmedCoreRequirementsAndUnsupportedHardLimitsAreChecked() {
        var base = ItineraryRulesTest.minimal(scope.taskId(), null); var requirements = new ConstraintRevision(scope, 1, List.of(
                new ConstraintRevision.Requirement(UUID.randomUUID(), "cities", "天津", ConstraintRevision.Kind.HARD, ConstraintRevision.Source.USER, Set.of()),
                new ConstraintRevision.Requirement(UUID.randomUUID(), "hardRequirements", "不得走楼梯", ConstraintRevision.Kind.HARD, ConstraintRevision.Source.USER, Set.of())), List.of());
        var issues = new ItineraryValidator().validate(base, requirements); assertThat(issues).anyMatch(i -> i.code().equals("CITIES_MISMATCH") && !i.unknown()).anyMatch(i -> i.code().equals("HARD_CONSTRAINT_UNSUPPORTED") && i.unknown());
    }
    /** 默认关闭首先生效，不触碰框架、JSON或额度查询。 */
    @Test void disabledModelsNeverReadContextOrInvokeRuntime() {
        var runtime = mock(AgentRuntimePort.class); var codec = mock(InteractionCodec.class); var advisor = new HarnessPlanningAdvisor(runtime, codec, false);
        assertThatThrownBy(() -> advisor.draft(UUID.randomUUID(), null, null, List.of())).hasMessage("EXTERNAL_DISABLED"); assertThatThrownBy(() -> advisor.review(UUID.randomUUID(), null, null)).hasMessage("EXTERNAL_DISABLED"); verifyNoInteractions(runtime, codec);
    }
    /** 旧Schema1载荷仍可读取；集合顺序不同不能产生两个内容身份。 */
    @Test void oldSnapshotHasEmptyObjectBindingsAndEvidenceSetsAreCanonical() {
        var json = new TravelJson(); var base = ItineraryRulesTest.minimal(scope.taskId(), null); var payload = json.encode(base).replace("\"evidenceUses\":[],", "");
        assertThat(json.decode(payload, TripSnapshot.class).evidenceUses()).isEmpty(); var a = fact("09:00-17:00", "openingHours"); var b = fact("20元/人", "price");
        assertThat(json.encode(java.util.Map.of("evidenceRefs", new java.util.LinkedHashSet<>(List.of(a.reference(), b.reference()))))).isEqualTo(json.encode(java.util.Map.of("evidenceRefs", new java.util.LinkedHashSet<>(List.of(b.reference(), a.reference())))));
    }
    /** 地图路线通过实际供应商端点与引用地点匹配，模型不能用另一条路线或过短时长替代。 */
    @Test void routeProofRequiresBoundPlaceIdentityAndActualCoordinates() {
        var base = ItineraryRulesTest.minimal(scope.taskId(), null); var first = base.items().getFirst(); var second = new TripSnapshot.Item(UUID.randomUUID(), first.cityStayId(), TripSnapshot.ItemType.REST, "休息", "B", first.endsAt().plusMinutes(30), first.endsAt().plusHours(1), null);
        var leg = new TripSnapshot.Transport(UUID.randomUUID(), first.id(), second.id(), first.endsAt(), first.endsAt().plusMinutes(20), 20L, true);
        var p1 = new FieldEvidence(new EvidenceRecord.Reference(UUID.randomUUID(), 1, "b".repeat(64)), "北京", "poi", "[{\"id\":\"same\",\"cityname\":\"beijing\",\"location\":\"116.0,40\"},{\"id\":\"B\",\"cityname\":\"beijing\",\"location\":\"117,40\"}]", FieldEvidence.SourceType.MAP_PROVIDER, URI.create("https://restapi.amap.com/v3/place/text"), null, now.minusSeconds(1), null, null, now.plusSeconds(60), "地图地点原文", null, EvidenceRecord.Status.KNOWN);
        var r = new FieldEvidence(new EvidenceRecord.Reference(UUID.randomUUID(), 1, "c".repeat(64)), "same->B", "route", "{\"origin\":\"116,40\",\"destination\":\"117,40\",\"paths\":[{\"cost\":{\"duration\":\"1200\"}}]}", FieldEvidence.SourceType.ESTIMATE, URI.create("https://restapi.amap.com/v5/direction/walking"), null, now.minusSeconds(1), null, first.startsAt().toLocalDate(), now.plusSeconds(60), "路线原文", null, EvidenceRecord.Status.KNOWN);
        var trip = new TripSnapshot(1, base.taskId(), 1, null, base.window(), base.cityStays(), base.days(), List.of(first, second), List.of(leg), List.of(), List.of(), List.of(), Set.of(p1.reference(), r.reference()), List.of(new TripSnapshot.EvidenceUse(first.id(), "poi", p1.reference()), new TripSnapshot.EvidenceUse(second.id(), "poi", p1.reference()), new TripSnapshot.EvidenceUse(leg.id(), "route", r.reference())));
        var json = new TravelJson(); var parsed = com.lawrence.travel.application.itinerary.TravelEvidenceParser.parse(List.of(p1, r), value -> json.decode(value, Object.class));
        assertThat(EvidenceRules.route(trip, parsed, now, leg)).isTrue(); var shortLeg = new TripSnapshot.Transport(leg.id(), leg.fromItemId(), leg.toItemId(), leg.startsAt(), leg.endsAt(), 1L, true); assertThat(EvidenceRules.route(trip, parsed, now, shortLeg)).isFalse();
        var fabricated = new com.lawrence.travel.domain.itinerary.EvidenceRules.TravelFacts(parsed.places(), List.of(new EvidenceRules.Route(r, "118,40", "117,40", 20))); assertThat(EvidenceRules.route(trip, fabricated, now, leg)).isFalse();
    }
    /** 首版三城市十天可以形成草案；缺转场与住宿明确未知，重复地点标签不能绕过城际衔接。 */
    @Test void threeCitiesTenDaysStillRequireTransfersAndLodging() {
        var start = java.time.LocalDate.parse("2026-11-01").atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")); var cities = new java.util.ArrayList<TripSnapshot.CityStay>(); var items = new java.util.ArrayList<TripSnapshot.Item>();
        var names = List.of("北京", "天津", "济南");
        for (int i = 0; i < 3; i++) { int day = i * 3; var city = new TripSnapshot.CityStay(UUID.randomUUID(), names.get(i), start.plusDays(day), start.plusDays(i == 2 ? 10 : day + 3)); cities.add(city); items.add(new TripSnapshot.Item(UUID.randomUUID(), city.id(), TripSnapshot.ItemType.REST, "休息", "same", city.startsAt().plusHours(10), city.startsAt().plusHours(11), null)); }
        var window = new com.lawrence.travel.domain.travel.TripWindow(Set.copyOf(names), start.toLocalDate(), start.plusDays(9).toLocalDate(), 2);
        var trip = new TripSnapshot(1, scope.taskId(), 1, null, window, cities, start.toLocalDate().datesUntil(start.plusDays(10).toLocalDate()).map(d -> new TripSnapshot.Day(UUID.randomUUID(), d)).toList(), items, List.of(), List.of(), List.of(), List.of(), Set.of());
        var issues = new ItineraryValidator().validate(trip, new ConstraintRevision(scope, 1, List.of(), List.of())); assertThat(issues).anyMatch(i -> i.code().equals("TRANSPORT_MISSING") && i.unknown()).anyMatch(i -> i.code().equals("LODGING_MISSING") && i.unknown());
    }
    /** 明确包含的往返费用不能凭缺项当作零，已有领域契约直接拒绝把假设升级成硬约束。 */
    @Test void missingBudgetCategoriesNeverBecomeZeroAndHardAssumptionsAreRejected() {
        var base = ItineraryRulesTest.minimal(scope.taskId(), null);
        var requirements = new ConstraintRevision(scope, 1, List.of(new ConstraintRevision.Requirement(UUID.randomUUID(), "budgetScope", "ROUNDTRIP", ConstraintRevision.Kind.HARD, ConstraintRevision.Source.USER, Set.of())), List.of());
        var issues = new ItineraryValidator().validate(base, requirements); assertThat(issues).anyMatch(i -> i.code().equals("BUDGET_CATEGORY_MISSING") && i.unknown());
        assertThatThrownBy(() -> new ConstraintRevision.Requirement(UUID.randomUUID(), "travelers", "2", ConstraintRevision.Kind.HARD, ConstraintRevision.Source.ASSUMPTION, Set.of())).hasMessage("INVALID_INPUT");
    }
    /** 交通价格必须精确绑定线路、日期、官方来源和单位，不能把地图或过期价格当作已核实。 */
    @Test void transportPriceNeedsExactOfficialRouteDateAndUnit() {
        var base = ItineraryRulesTest.minimal(scope.taskId(), null); var from = base.items().getFirst();
        var to = new TripSnapshot.Item(UUID.randomUUID(), from.cityStayId(), from.type(), "目的地点", "to", from.startsAt().plusHours(3), from.endsAt().plusHours(3), null);
        var leg = new TripSnapshot.Transport(UUID.randomUUID(), from.id(), to.id(), from.endsAt(), to.startsAt(), 60L, true);
        var cost = new TripSnapshot.Cost(UUID.randomUUID(), leg.id(), "INTERCITY", TripSnapshot.CostUnit.PER_PERSON, BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"), false, true, "CNY");
        var seed = fact("100元/人", "price");
        var proof = new FieldEvidence(seed.reference(), "same->to", "price", seed.value(), seed.sourceType(), seed.sourceUrl(), null, seed.retrievedAt(), null, seed.validForDate(), seed.expiresAt(), seed.quote(), seed.artifactId(), seed.status());
        var trip = new TripSnapshot(1, base.taskId(), 1, null, base.window(), base.cityStays(), base.days(), List.of(from, to), List.of(leg), List.of(), List.of(cost), List.of(), Set.of(proof.reference()), List.of(new TripSnapshot.EvidenceUse(cost.id(), "price", proof.reference())));
        assertThat(EvidenceRules.price(trip, List.of(proof), now, cost)).isTrue();
        assertThat(EvidenceRules.price(trip, List.of(proof), proof.expiresAt(), cost)).isFalse();
        var wrongUnit = new TripSnapshot.Cost(cost.id(), leg.id(), "INTERCITY", TripSnapshot.CostUnit.PER_GROUP, BigDecimal.ONE, cost.lower(), cost.upper(), false, true, "CNY");
        assertThat(EvidenceRules.price(trip, List.of(proof), now, wrongUnit)).isFalse();
        for (int i = 0; i < 3; i++) {
            var wrong = new FieldEvidence(proof.reference(), i == 0 ? "to->same" : proof.targetId(), "price", proof.value(), i == 1 ? FieldEvidence.SourceType.MAP_PROVIDER : proof.sourceType(), proof.sourceUrl(), null, proof.retrievedAt(), null, i == 2 ? proof.validForDate().plusDays(1) : proof.validForDate(), proof.expiresAt(), proof.quote(), proof.artifactId(), proof.status());
            assertThat(EvidenceRules.price(trip, List.of(wrong), now, cost)).isFalse();
        }
    }
    /** 每人上限按实际人数转换成全组上限，不能误用每组口径或将人数乘两次。 */
    @Test void perPersonBudgetUsesExactlyOneTravelerMultiplier() {
        var base = ItineraryRulesTest.minimal(scope.taskId(), null); var cost = new TripSnapshot.Cost(UUID.randomUUID(), base.items().getFirst().id(), "ACTIVITIES", TripSnapshot.CostUnit.PER_PERSON, BigDecimal.ONE, new BigDecimal("20"), new BigDecimal("20"), false, true, "CNY"); var proof = fact("20元/人", "price");
        var trip = bound(new TripSnapshot(1, base.taskId(), 1, null, base.window(), base.cityStays(), base.days(), base.items(), List.of(), List.of(), List.of(cost), List.of(), Set.of()), List.of(proof));
        var rule = new ConstraintRevision.Requirement(UUID.randomUUID(), "budgetRule", "PER_PERSON", ConstraintRevision.Kind.HARD, ConstraintRevision.Source.USER, Set.of());
        for (String limit : List.of("20", "19")) { var amount = new ConstraintRevision.Requirement(UUID.randomUUID(), "budgetCny", limit, ConstraintRevision.Kind.HARD, ConstraintRevision.Source.USER, Set.of()); var issues = new ItineraryValidator().validate(trip, new ConstraintRevision(scope, 1, List.of(rule, amount), List.of()), List.of(proof), now); assertThat(issues.stream().anyMatch(i -> i.code().equals("HARD_BUDGET_EXCEEDED"))).isEqualTo(limit.equals("19")); }
    }
}
