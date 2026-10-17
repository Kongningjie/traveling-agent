package com.lawrence.travel.bootstrap;

import com.lawrence.travel.domain.itinerary.ItineraryReport;
import com.lawrence.travel.domain.itinerary.ItineraryValidator;
import com.lawrence.travel.domain.itinerary.ReviewGate;
import com.lawrence.travel.domain.itinerary.TripSnapshot;
import com.lawrence.travel.domain.knowledge.EvidenceRecord;
import com.lawrence.travel.domain.memory.PreferenceVersion;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.domain.travel.TripWindow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 纯规则边界；明确金额口径、未知阻断和精确报告绑定，不以模型自评分验收。 */
class ItineraryRulesTest {
    private final ItineraryValidator validator = new ItineraryValidator();
    private final ReviewGate gate = new ReviewGate();
    private final TaskScope scope = new TaskScope(UUID.randomUUID(), UUID.randomUUID());
    private final Instant now = Instant.parse("2026-10-16T00:00:00Z");
    /**
     * @param task 任务
     * @param base 基础版本
     * @return 一天同地休息替身，不含虚构外部事实
     */
    static TripSnapshot minimal(UUID task, UUID base) {
        var start = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneId.of("Asia/Shanghai")); UUID city = UUID.randomUUID();
        return new TripSnapshot(1, task, 1, base, new TripWindow(Set.of("beijing"), start.toLocalDate(), start.toLocalDate(), 2),
                List.of(new TripSnapshot.CityStay(city, "beijing", start, start.plusDays(1))), List.of(new TripSnapshot.Day(UUID.randomUUID(), start.toLocalDate())),
                List.of(new TripSnapshot.Item(UUID.randomUUID(), city, TripSnapshot.ItemType.REST, "本地休息替身", "same", start.plusHours(10), start.plusHours(11), null)),
                List.of(), List.of(), List.of(), List.of(), Set.of());
    }
    /**
     * @param snapshot 基本快照
     * @param items 活动
     * @param legs 交通
     * @param stays 住宿
     * @param costs 费用
     * @return 替换用例所需集合
     */
    private TripSnapshot with(TripSnapshot snapshot, List<TripSnapshot.Item> items, List<TripSnapshot.Transport> legs,
                              List<TripSnapshot.Accommodation> stays, List<TripSnapshot.Cost> costs) {
        return new TripSnapshot(1, snapshot.taskId(), 1, null, snapshot.window(), snapshot.cityStays(), snapshot.days(), items, legs, stays, costs, List.of(), Set.of());
    }
    /**
     * @param requirements 已确认字段
     * @return 同归属需求版本
     */
    private ConstraintRevision requirements(ConstraintRevision.Requirement... requirements) { return new ConstraintRevision(scope, 1, List.of(requirements), List.of()); }
    /**
     * @param field 规则字段
     * @param value 已确认值
     * @return 用户硬约束
     */
    private ConstraintRevision.Requirement requirement(String field, String value) { return new ConstraintRevision.Requirement(UUID.randomUUID(), field, value, ConstraintRevision.Kind.HARD, ConstraintRevision.Source.USER, Set.of()); }
    /**
     * @param kind 来源
     * @param binding 精确绑定
     * @param issues 问题
     * @return 不同阶段及尝试的报告
     */
    private ItineraryReport report(ItineraryReport.Kind kind, ItineraryReport.Binding binding, List<ItineraryReport.Issue> issues) {
        return new ItineraryReport(UUID.randomUUID(), binding, kind, issues, now.minusSeconds(1), now.plusSeconds(60), UUID.randomUUID(), UUID.randomUUID());
    }
    /**
     * @return 固定规则及审核指纹的完整绑定
     */
    private ItineraryReport.Binding binding() { return new ItineraryReport.Binding(UUID.randomUUID(), 1, "a".repeat(64), 1, Set.of(), null, "rules-v1", "review-v1"); }
    /** 不含外部事实的最小测试快照可由确定性规则解释。 */
    @Test void deterministicMinimalFixturePasses() { assertThat(validator.validate(minimal(scope.taskId(), null), requirements())).isEmpty(); }
    /** 已知违反优先 UNKNOWN；建议不能把阻断改成可接受。 */
    @Test void failHasPriorityAndUnknownCannotPass() {
        var b = binding(); var pass = report(ItineraryReport.Kind.REVIEW, b, List.of());
        var unknown = new ItineraryReport.Issue("FACT_UNKNOWN", null, true, true);
        var fail = new ItineraryReport.Issue("TIME_CONFLICT", null, true, false);
        var uncertain = report(ItineraryReport.Kind.VALIDATION, b, List.of(unknown));
        assertThat(uncertain.conclusion()).isEqualTo(ItineraryReport.Conclusion.UNKNOWN);
        assertThat(gate.evaluate(b, uncertain, pass, now, true)).isEqualTo(ReviewGate.Result.BLOCKED);
        assertThat(report(ItineraryReport.Kind.VALIDATION, b, List.of(unknown, fail)).conclusion()).isEqualTo(ItineraryReport.Conclusion.FAIL);
    }
    /** 每个绑定维度变化都使旧报告失效；不能只比较候选 ID。 */
    @Test void everyBindingDimensionMustMatch() {
        var b = binding(); var validation = report(ItineraryReport.Kind.VALIDATION, b, List.of()); var review = report(ItineraryReport.Kind.REVIEW, b, List.of());
        var alternatives = List.of(
                new ItineraryReport.Binding(UUID.randomUUID(), 1, b.snapshotHash(), 1, Set.of(), null, b.ruleVersion(), b.reviewConfiguration()),
                new ItineraryReport.Binding(b.snapshotId(), 2, b.snapshotHash(), 1, Set.of(), null, b.ruleVersion(), b.reviewConfiguration()),
                new ItineraryReport.Binding(b.snapshotId(), 1, "b".repeat(64), 1, Set.of(), null, b.ruleVersion(), b.reviewConfiguration()),
                new ItineraryReport.Binding(b.snapshotId(), 1, b.snapshotHash(), 2, Set.of(), null, b.ruleVersion(), b.reviewConfiguration()),
                new ItineraryReport.Binding(b.snapshotId(), 1, b.snapshotHash(), 1, Set.of(new EvidenceRecord.Reference(UUID.randomUUID(), 1, "c".repeat(64))), null, b.ruleVersion(), b.reviewConfiguration()),
                new ItineraryReport.Binding(b.snapshotId(), 1, b.snapshotHash(), 1, Set.of(), new PreferenceVersion(scope.userId(), 1), b.ruleVersion(), b.reviewConfiguration()),
                new ItineraryReport.Binding(b.snapshotId(), 1, b.snapshotHash(), 1, Set.of(), null, "rules-v2", b.reviewConfiguration()),
                new ItineraryReport.Binding(b.snapshotId(), 1, b.snapshotHash(), 1, Set.of(), null, b.ruleVersion(), "review-v2"));
        for (var changed : alternatives) assertThat(gate.evaluate(changed, validation, review, now, true)).isEqualTo(ReviewGate.Result.REVALIDATE);
        assertThat(gate.evaluate(b, validation, review, now.plusSeconds(60), true)).isEqualTo(ReviewGate.Result.REVALIDATE);
        assertThat(gate.evaluate(b, validation, review, now, false)).isEqualTo(ReviewGate.Result.REVALIDATE);
    }
    /** 同一阶段或尝试自审不能成为独立审核。 */
    @Test void sameProducerCannotSelfReview() {
        var b = binding(); var v = report(ItineraryReport.Kind.VALIDATION, b, List.of());
        var r = new ItineraryReport(UUID.randomUUID(), b, ItineraryReport.Kind.REVIEW, List.of(), v.checkedAt(), v.validUntil(), v.producerStepId(), UUID.randomUUID());
        assertThat(gate.evaluate(b, v, r, now, true)).isEqualTo(ReviewGate.Result.BLOCKED);
    }
    /** 活动按 Instant 检查重叠，不依据本地小时字符串；住宿单独校验。 */
    @Test void overlappingInstantsAndUnsupportedHardConstraintBlock() {
        var snapshot = minimal(scope.taskId(), null); var first = snapshot.items().getFirst();
        var second = new TripSnapshot.Item(UUID.randomUUID(), first.cityStayId(), TripSnapshot.ItemType.REST, "重叠", "same", first.startsAt().withZoneSameInstant(ZoneId.of("UTC")), first.endsAt().plusMinutes(1), null);
        var issues = validator.validate(with(snapshot, List.of(first, second), List.of(), List.of(), List.of()), requirements(requirement("unknownHard", "x")));
        assertThat(issues).extracting(ItineraryReport.Issue::code).contains("TIME_OVERLAP", "HARD_CONSTRAINT_UNSUPPORTED");
    }
    /** 费用按人计算一次；未知金额及未确认事实不能当作零。 */
    @Test void perPersonCostsUseTravelersAndUnknownPricesBlock() {
        var snapshot = minimal(scope.taskId(), null); UUID costId = UUID.randomUUID();
        var cost = new TripSnapshot.Cost(costId, snapshot.items().getFirst().id(), "ADMISSION", TripSnapshot.CostUnit.PER_PERSON, BigDecimal.ONE, new BigDecimal("10.1"), new BigDecimal("10.1"), false, true, "CNY");
        var value = with(snapshot, snapshot.items(), List.of(), List.of(), List.of(cost));
        assertThat(validator.validate(value, requirements(requirement("budgetCny", "20"), requirement("costCny:" + costId, ItineraryValidator.costConfirmation(cost)))))
                .extracting(ItineraryReport.Issue::code).contains("HARD_BUDGET_EXCEEDED").doesNotContain("COST_UNVERIFIED");
        assertThat(validator.validate(value, requirements())).extracting(ItineraryReport.Issue::code).contains("COST_UNVERIFIED");
    }
    /** 交通缺失和未核实开放时间均为阻断未知，不接受规划自报已核实。 */
    @Test void missingTravelAndOpeningFactsAreUnknown() {
        var s = minimal(scope.taskId(), null); var first = s.items().getFirst();
        var second = new TripSnapshot.Item(UUID.randomUUID(), first.cityStayId(), TripSnapshot.ItemType.ACTIVITY, "景点", "other", first.endsAt().plusHours(1), first.endsAt().plusHours(2), null);
        assertThat(validator.validate(with(s, List.of(first, second), List.of(), List.of(), List.of()), requirements()))
                .extracting(ItineraryReport.Issue::code).contains("TRANSPORT_MISSING", "OPENING_UNVERIFIED", "COST_ITEM_MISSING");
    }
    /** 无法对应费用对象的确认字段不能借字段前缀绕过硬约束。 */
    @Test void orphanConfirmationDoesNotBypassRules() {
        assertThat(validator.validate(minimal(scope.taskId(), null), requirements(requirement("paidIncluded:" + UUID.randomUUID(), "true"))))
                .extracting(ItineraryReport.Issue::code).contains("HARD_CONSTRAINT_UNSUPPORTED");
    }
    /** 房晚由实际入住日差及房间数计算，住宿区间不参与白天活动重叠。 */
    @Test void roomNightsUseRoomsAndDatesWithoutActivityOverlap() {
        var s = minimal(scope.taskId(), null); var city = s.cityStays().getFirst(); var start = city.startsAt();
        var hotel = new TripSnapshot.Accommodation(UUID.randomUUID(), city.id(), start.plusHours(8), start.plusDays(1).plusHours(8), 2);
        UUID id = UUID.randomUUID(); var cost = new TripSnapshot.Cost(id, hotel.id(), "ROOM", TripSnapshot.CostUnit.PER_ROOM_NIGHT,
                BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"), false, true, "CNY");
        var value = new TripSnapshot(1, scope.taskId(), 1, null, new TripWindow(Set.of("beijing"), start.toLocalDate(), start.toLocalDate().plusDays(1), 2),
                List.of(new TripSnapshot.CityStay(city.id(), "beijing", start, start.plusDays(2))),
                List.of(s.days().getFirst(), new TripSnapshot.Day(UUID.randomUUID(), start.toLocalDate().plusDays(1))),
                s.items(), List.of(), List.of(hotel), List.of(cost), List.of(), Set.of());
        var issues = validator.validate(value, requirements(requirement("budgetCny", "199"), requirement("costCny:" + id, ItineraryValidator.costConfirmation(cost))));
        assertThat(issues).extracting(ItineraryReport.Issue::code).containsExactly("HARD_BUDGET_EXCEEDED");
    }
    /** 付款但排除未知价格时仍须确认口径；不能把空价格当作零或绕过授权。 */
    @Test void excludedUnknownPaidCostStillRequiresConfirmation() {
        var s = minimal(scope.taskId(), null); var cost = new TripSnapshot.Cost(UUID.randomUUID(), s.items().getFirst().id(), "ADMISSION", TripSnapshot.CostUnit.PER_GROUP,
                BigDecimal.ONE, BigDecimal.ZERO, null, true, false, "CNY");
        assertThat(validator.validate(with(s, s.items(), List.of(), List.of(), List.of(cost)), requirements()))
                .extracting(ItineraryReport.Issue::code).contains("COST_UNKNOWN", "PAID_SCOPE_UNCONFIRMED");
    }
    /** 同对象不同收费分类可以合计，同分类重复记录则拒绝，备选不加入当前费用。 */
    @Test void distinctChargesAreAllowedButDuplicateCategoriesFail() {
        var s = minimal(scope.taskId(), null); UUID related = s.items().getFirst().id();
        var a = new TripSnapshot.Cost(UUID.randomUUID(), related, "ADMISSION", TripSnapshot.CostUnit.PER_GROUP, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, false, true, "CNY");
        var b = new TripSnapshot.Cost(UUID.randomUUID(), related, "INSURANCE", TripSnapshot.CostUnit.PER_GROUP, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, false, true, "CNY");
        var requirements = requirements(requirement("costCny:" + a.id(), ItineraryValidator.costConfirmation(a)), requirement("costCny:" + b.id(), ItineraryValidator.costConfirmation(b)));
        assertThat(validator.validate(with(s, s.items(), List.of(), List.of(), List.of(a, b)), requirements)).isEmpty();
        var duplicate = new TripSnapshot.Cost(UUID.randomUUID(), related, "ADMISSION", b.unit(), b.quantity(), b.lower(), b.upper(), false, true, "CNY");
        assertThat(validator.validate(with(s, s.items(), List.of(), List.of(), List.of(a, duplicate)), requirements))
                .extracting(ItineraryReport.Issue::code).contains("COST_INVALID_OR_DUPLICATED");
    }
    /** 单价不变但费用单位被草案改写时，旧确认失效，不能以较低金额绕过预算。 */
    @Test void priceConfirmationIncludesUnitAndQuantity() {
        var s = minimal(scope.taskId(), null); UUID id = UUID.randomUUID(); UUID related = s.items().getFirst().id();
        var original = new TripSnapshot.Cost(id, related, "ADMISSION", TripSnapshot.CostUnit.PER_PERSON, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, false, true, "CNY");
        var changed = new TripSnapshot.Cost(id, related, "ADMISSION", TripSnapshot.CostUnit.PER_GROUP, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, false, true, "CNY");
        assertThat(validator.validate(with(s, s.items(), List.of(), List.of(), List.of(changed)), requirements(requirement("costCny:" + id, ItineraryValidator.costConfirmation(original)))))
                .extracting(ItineraryReport.Issue::code).contains("COST_UNVERIFIED", "HARD_CONSTRAINT_UNSUPPORTED");
    }
}
