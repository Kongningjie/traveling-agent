package com.lawrence.travel.bootstrap;

import com.lawrence.travel.domain.itinerary.ImpactAnalyzer;
import com.lawrence.travel.domain.itinerary.ImpactScope;
import com.lawrence.travel.domain.itinerary.ItineraryPatch;
import com.lawrence.travel.domain.itinerary.PatchService;
import com.lawrence.travel.domain.itinerary.TripSnapshot;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.domain.travel.TripWindow;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 局部修改的确定性边界，包括跨城依赖和不可变性；不依赖模型评判。 */
class ReplanningRulesTest {
    private final TaskScope task = new TaskScope(UUID.randomUUID(), UUID.randomUUID());
    private final UUID baseId = UUID.randomUUID();
    private final String hash = "a".repeat(64);
    private final PatchService patches = new PatchService();

    /**
     * @return 两城市两天，中间交通与跨夜住宿均有明确身份的测试快照
     */
    private TripSnapshot trip() {
        var b = ItineraryRulesTest.minimal(task.taskId(), null);
        var a = b.items().getFirst();
        var city = b.cityStays().getFirst();
        var nextCity =
                new TripSnapshot.CityStay(
                        UUID.randomUUID(),
                        "shanghai",
                        city.startsAt().plusDays(1),
                        city.endsAt().plusDays(1));
        var next =
                new TripSnapshot.Item(
                        UUID.randomUUID(),
                        nextCity.id(),
                        a.type(),
                        "次城休息",
                        "next",
                        a.startsAt().plusDays(1),
                        a.endsAt().plusDays(1),
                        null);
        var leg =
                new TripSnapshot.Transport(
                        UUID.randomUUID(),
                        a.id(),
                        next.id(),
                        a.endsAt().plusHours(1),
                        next.startsAt().minusHours(1),
                        60L,
                        true);
        var lodging =
                new TripSnapshot.Accommodation(
                        UUID.randomUUID(),
                        city.id(),
                        city.startsAt().plusHours(18),
                        city.startsAt().plusDays(1).plusHours(7),
                        1,
                        "hotel");
        return new TripSnapshot(
                1,
                task.taskId(),
                1,
                null,
                new TripWindow(
                        Set.of("beijing", "shanghai"),
                        b.window().startsOn(),
                        b.window().endsOn().plusDays(1),
                        2),
                List.of(city, nextCity),
                List.of(
                        b.days().getFirst(),
                        new TripSnapshot.Day(UUID.randomUUID(), next.startsAt().toLocalDate())),
                List.of(a, next),
                List.of(leg),
                List.of(lodging),
                List.of(),
                List.of(),
                Set.of());
    }

    /**
     * @param trip 快照
     * @param locks 锁定对象
     * @param histories 扩大记录
     * @return 显式定位首活动的Java范围
     */
    private ImpactScope scope(TripSnapshot trip, Set<UUID> locks, List<String> histories) {
        var target =
                new ConstraintRevision.Requirement(
                        UUID.randomUUID(),
                        "changeTargets",
                        trip.items().getFirst().id().toString(),
                        ConstraintRevision.Kind.SOFT,
                        ConstraintRevision.Source.USER,
                        Set.of());
        var reqs = new java.util.ArrayList<>(List.of(target));
        if (!locks.isEmpty())
            reqs.add(
                    new ConstraintRevision.Requirement(
                            UUID.randomUUID(),
                            "lockedIds",
                            locks.stream()
                                    .map(UUID::toString)
                                    .collect(java.util.stream.Collectors.joining(",")),
                            ConstraintRevision.Kind.HARD,
                            ConstraintRevision.Source.USER,
                            Set.of()));
        return new ImpactAnalyzer()
                .analyze(
                        trip,
                        new ConstraintRevision(task, 1, List.of(), List.of()),
                        new ConstraintRevision(task, 2, reqs, List.of()),
                        histories);
    }

    /**
     * @param item 原活动
     * @param title 新说明
     * @return 保留身份的替换操作
     */
    private ItineraryPatch.Operation replace(TripSnapshot.Item item, String title) {
        return new ItineraryPatch.Operation(
                ItineraryPatch.Kind.ITEM,
                ItineraryPatch.Action.REPLACE,
                item.id(),
                null,
                null,
                new TripSnapshot.Item(
                        item.id(),
                        item.cityStayId(),
                        item.type(),
                        title,
                        item.locationKey(),
                        item.startsAt(),
                        item.endsAt(),
                        item.fixedArrangementId()),
                null,
                null,
                null,
                null,
                List.of());
    }

    /** 局部节点自动带上相邻跨城交通与跨夜住宿，但不直接改另一城市活动。 */
    @Test
    void localScopeIncludesIntercityLegAndOvernightDependency() {
        var trip = trip();
        var scope = scope(trip, Set.of(), List.of());
        assertThat(scope.allowedIds())
                .contains(
                        trip.items().getFirst().id(),
                        trip.transportLegs().getFirst().id(),
                        trip.accommodationStays().getFirst().id())
                .doesNotContain(trip.items().getLast().id());
        assertThat(scope.mustRecalculate()).contains(trip.transportLegs().getFirst().id());
        assertThat(scope.evidenceToRefresh()).contains(trip.items().getFirst().id());
    }

    /** 未改对象载荷逐项保持不变；新的快照绑定当前需求及基础版本。 */
    @Test
    void validPatchPreservesUnaffectedObjectsAndOriginal() {
        var trip = trip();
        var result =
                patches.apply(
                        trip,
                        baseId,
                        hash,
                        2,
                        scope(trip, Set.of(), List.of()),
                        new ItineraryPatch(
                                hash, List.of(replace(trip.items().getFirst(), "新说明")), null));
        assertThat(result.items().getLast()).isSameAs(trip.items().getLast());
        assertThat(result.transportLegs()).isEqualTo(trip.transportLegs());
        assertThat(result.items().getFirst().id()).isEqualTo(trip.items().getFirst().id());
        assertThat(trip.items().getFirst().title()).isNotEqualTo("新说明");
        assertThat(result.baseVersionId()).isEqualTo(baseId);
        assertThat(result.constraintRevision()).isEqualTo(2);
    }

    /** 邻接依赖不授权直接改动另一城市活动，拒绝整份越界重写。 */
    @Test
    void changingUnrelatedCityIsRejected() {
        var trip = trip();
        assertThatThrownBy(
                        () ->
                                patches.apply(
                                        trip,
                                        baseId,
                                        hash,
                                        2,
                                        scope(trip, Set.of(), List.of()),
                                        new ItineraryPatch(
                                                hash,
                                                List.of(replace(trip.items().getLast(), "越界")),
                                                null)))
                .hasMessage("PATCH_OUTSIDE_SCOPE");
    }

    /** 扩大至全程也不能改动锁定对象，范围和授权是不同边界。 */
    @Test
    void expandingNeverUnlocksObjects() {
        var trip = trip();
        var locked = trip.items().getFirst();
        var scope = scope(trip, Set.of(locked.id()), List.of("需要全日衔接", "跨城市联动"));
        assertThatThrownBy(
                        () ->
                                patches.apply(
                                        trip,
                                        baseId,
                                        hash,
                                        2,
                                        scope,
                                        new ItineraryPatch(
                                                hash, List.of(replace(locked, "越权")), null)))
                .hasMessage("PATCH_LOCKED");
    }

    /** 不同哈希意味着基础版本已变，拒绝补丁而不合并。 */
    @Test
    void staleHashCannotMerge() {
        var trip = trip();
        assertThatThrownBy(
                        () ->
                                patches.apply(
                                        trip,
                                        baseId,
                                        hash,
                                        2,
                                        scope(trip, Set.of(), List.of()),
                                        new ItineraryPatch("b".repeat(64), List.of(), null)))
                .hasMessage("PATCH_BASE_STALE");
    }

    /** 删除交通端点必须同步处理依赖，否则新快照结构不完整。 */
    @Test
    void deletingReferencedItemIsAtomicFailure() {
        var trip = trip();
        var remove =
                new ItineraryPatch.Operation(
                        ItineraryPatch.Kind.ITEM,
                        ItineraryPatch.Action.REMOVE,
                        trip.items().getFirst().id(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of());
        assertThatThrownBy(
                        () ->
                                patches.apply(
                                        trip,
                                        baseId,
                                        hash,
                                        2,
                                        scope(trip, Set.of(), List.of()),
                                        new ItineraryPatch(hash, List.of(remove), null)))
                .hasMessage("PATCH_DANGLING_REFERENCE");
        assertThat(trip.items()).hasSize(2);
    }

    /** 未定位到真实对象时拒绝，不能将无效用户目标转换为全程授权。 */
    @Test
    void unknownTargetsAndDuplicateOperationsAreRejected() {
        var trip = trip();
        var operation = replace(trip.items().getFirst(), "新");
        assertThatThrownBy(
                        () ->
                                patches.apply(
                                        trip,
                                        baseId,
                                        hash,
                                        2,
                                        scope(trip, Set.of(), List.of()),
                                        new ItineraryPatch(
                                                hash, List.of(operation, operation), null)))
                .hasMessage("PATCH_DUPLICATE_OPERATION");
        var bad =
                new ConstraintRevision.Requirement(
                        UUID.randomUUID(),
                        "changeTargets",
                        UUID.randomUUID().toString(),
                        ConstraintRevision.Kind.SOFT,
                        ConstraintRevision.Source.USER,
                        Set.of());
        assertThatThrownBy(
                        () ->
                                new ImpactAnalyzer()
                                        .analyze(
                                                trip,
                                                new ConstraintRevision(
                                                        task, 1, List.of(), List.of()),
                                                new ConstraintRevision(
                                                        task, 2, List.of(bad), List.of()),
                                                List.of()))
                .hasMessage("PATCH_TARGET_UNKNOWN");
    }

    /** 预算变化影响全程；显式局部定位不能缩小全局费用约束的计算范围。 */
    @Test
    void globalBudgetChangeIncludesWholeTrip() {
        var trip = trip();
        var cap =
                new ConstraintRevision.Requirement(
                        UUID.randomUUID(),
                        "budgetCny",
                        "500",
                        ConstraintRevision.Kind.HARD,
                        ConstraintRevision.Source.USER,
                        Set.of());
        var scope =
                new ImpactAnalyzer()
                        .analyze(
                                trip,
                                new ConstraintRevision(task, 1, List.of(), List.of()),
                                new ConstraintRevision(task, 2, List.of(cap), List.of()),
                                List.of());
        assertThat(scope.allowedIds()).containsAll(ImpactAnalyzer.objects(trip).keySet());
        assertThat(scope.windowEditable()).isTrue();
    }

    /** Java从当日扩到城市，保留具体理由；扩大不会直接授予不相邻城市对象的修改权限。 */
    @Test
    void expansionProgressesThroughCalendarAndCity() {
        var trip = trip();
        var first = scope(trip, Set.of(), List.of("需要同日安排联动"));
        var second = scope(trip, Set.of(), List.of("需要同日安排联动", "需要调整当前城市停留"));
        assertThat(first.level()).isEqualTo(1);
        assertThat(first.allowedIds())
                .contains(trip.days().getFirst().id())
                .doesNotContain(trip.cityStays().getFirst().id());
        assertThat(second.level()).isEqualTo(2);
        assertThat(second.allowedIds())
                .contains(trip.cityStays().getFirst().id())
                .doesNotContain(trip.items().getLast().id());
        assertThat(second.expansionHistory()).hasSize(2);
    }

    /** 不能保留ID却把活动移动到范围外城市或日期，避免间接越界。 */
    @Test
    void movingActivityToUnrelatedDayOrCityIsRejected() {
        var trip = trip();
        var item = trip.items().getFirst();
        var other = trip.items().getLast();
        var moved =
                new TripSnapshot.Item(
                        item.id(),
                        other.cityStayId(),
                        item.type(),
                        item.title(),
                        other.locationKey(),
                        other.startsAt(),
                        other.endsAt(),
                        null);
        var op =
                new ItineraryPatch.Operation(
                        ItineraryPatch.Kind.ITEM,
                        ItineraryPatch.Action.REPLACE,
                        item.id(),
                        null,
                        null,
                        moved,
                        null,
                        null,
                        null,
                        null,
                        List.of());
        assertThatThrownBy(
                        () ->
                                patches.apply(
                                        trip,
                                        baseId,
                                        hash,
                                        2,
                                        scope(trip, Set.of(), List.of()),
                                        new ItineraryPatch(hash, List.of(op), null)))
                .hasMessage("PATCH_DEPENDENCY_OUTSIDE_SCOPE");
    }

    /** 交通可新增到修改点并保留另一端；无关新对象不能混入补丁。 */
    @Test
    void addingConnectedTransportIsAllowedButUnrelatedItemIsNot() {
        var trip = trip();
        var original = trip.transportLegs().getFirst();
        var leg =
                new TripSnapshot.Transport(
                        UUID.randomUUID(),
                        original.fromItemId(),
                        original.toItemId(),
                        original.startsAt(),
                        original.endsAt(),
                        original.durationMinutes(),
                        true);
        var op =
                new ItineraryPatch.Operation(
                        ItineraryPatch.Kind.TRANSPORT,
                        ItineraryPatch.Action.ADD,
                        leg.id(),
                        null,
                        null,
                        null,
                        leg,
                        null,
                        null,
                        null,
                        List.of());
        var merged =
                patches.apply(
                        trip,
                        baseId,
                        hash,
                        2,
                        scope(trip, Set.of(), List.of()),
                        new ItineraryPatch(hash, List.of(op), null));
        assertThat(merged.transportLegs()).hasSize(2);
        var other = trip.items().getLast();
        var item =
                new TripSnapshot.Item(
                        UUID.randomUUID(),
                        other.cityStayId(),
                        other.type(),
                        "无关新增",
                        other.locationKey(),
                        other.startsAt(),
                        other.endsAt(),
                        null);
        var wrong =
                new ItineraryPatch.Operation(
                        ItineraryPatch.Kind.ITEM,
                        ItineraryPatch.Action.ADD,
                        item.id(),
                        null,
                        null,
                        item,
                        null,
                        null,
                        null,
                        null,
                        List.of());
        assertThatThrownBy(
                        () ->
                                patches.apply(
                                        trip,
                                        baseId,
                                        hash,
                                        2,
                                        scope(trip, Set.of(), List.of()),
                                        new ItineraryPatch(hash, List.of(wrong), null)))
                .hasMessage("PATCH_ADD_OUTSIDE_SCOPE");
    }

    /** 补丁只允许重绑当前操作对象的证据，不能篡改未修改活动的字段引用。 */
    @Test
    void evidenceBindingCannotTargetUnmodifiedObject() {
        var trip = trip();
        var item = trip.items().getFirst();
        var use =
                new TripSnapshot.EvidenceUse(
                        trip.items().getLast().id(),
                        "openingHours",
                        new com.lawrence.travel.domain.knowledge.EvidenceRecord.Reference(
                                UUID.randomUUID(), 1, hash));
        assertThatThrownBy(
                        () ->
                                new ItineraryPatch.Operation(
                                        ItineraryPatch.Kind.ITEM,
                                        ItineraryPatch.Action.REPLACE,
                                        item.id(),
                                        null,
                                        null,
                                        item,
                                        null,
                                        null,
                                        null,
                                        null,
                                        List.of(use)))
                .hasMessage("PATCH_EVIDENCE_SCOPE");
    }
}
