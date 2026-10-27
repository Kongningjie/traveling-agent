package com.lawrence.travel.domain.itinerary;

import com.lawrence.travel.domain.travel.ConstraintRevision;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 从确认需求差异和行程依赖构造范围；没有可定位目标时保守纳入全程，不猜测文本含义。 */
public final class ImpactAnalyzer {
    /** 无状态规则，可跨线程复用。 */
    public ImpactAnalyzer() {}

    /**
     * @param base 原生效快照
     * @param previous 原需求
     * @param current 当前需求
     * @param history 有限扩大理由
     * @return 确定性范围，最多两轮扩大
     */
    public ImpactScope analyze(
            TripSnapshot base,
            ConstraintRevision previous,
            ConstraintRevision current,
            List<String> history) {
        var objects = objects(base);
        var changed = new LinkedHashSet<UUID>();
        var seeds = new LinkedHashSet<UUID>();
        Map<UUID, ConstraintRevision.Requirement> old = new HashMap<>();
        previous.requirements().forEach(r -> old.put(r.id(), r));
        boolean global = !previous.fixedArrangements().equals(current.fixedArrangements());
        for (var r : current.requirements())
            if (!r.equals(old.remove(r.id()))) {
                changed.add(r.id());
                if (r.field().equals("changeTargets")) seeds.addAll(ids(r.value()));
                else if (!r.field().equals("lockedIds")) {
                    seeds.addAll(r.targetIds());
                    if (r.targetIds().isEmpty()) global = true;
                }
            }
        changed.addAll(old.keySet());
        if (!old.isEmpty()) global = true;
        // 显式对象定位用于本次修改；预算、日期、人数等全局字段仍必须影响全程。
        var targets =
                current.requirements().stream()
                        .filter(r -> r.field().equals("changeTargets"))
                        .findFirst();
        if (targets.isPresent()
                && changed.stream()
                        .anyMatch(
                                id ->
                                        current.requirements().stream()
                                                .anyMatch(
                                                        r ->
                                                                r.id().equals(id)
                                                                        && Set.of(
                                                                                        "pace",
                                                                                        "diet",
                                                                                        "interests",
                                                                                        "hardRequirements",
                                                                                        "changeTargets")
                                                                                .contains(
                                                                                        r
                                                                                                .field())))) {
            boolean globalChange =
                    current.requirements().stream()
                            .anyMatch(
                                    r ->
                                            changed.contains(r.id())
                                                    && !Set.of(
                                                                    "pace",
                                                                    "diet",
                                                                    "interests",
                                                                    "hardRequirements",
                                                                    "changeTargets",
                                                                    "lockedIds")
                                                            .contains(r.field()));
            if (!globalChange
                    && old.isEmpty()
                    && previous.fixedArrangements().equals(current.fixedArrangements()))
                global = false;
            seeds.addAll(ids(targets.get().value()));
        }
        if (!objects.keySet().containsAll(seeds))
            throw new IllegalArgumentException("PATCH_TARGET_UNKNOWN");
        if (seeds.isEmpty()) global = true;
        int level = global ? 4 : 0;
        var allowed = allowed(base, seeds, level);
        // 逐层检查当日、城市、相邻城市、全程；没有增加对象的层级不消耗一次修订。
        for (String ignored : history)
            if (level < 4) {
                var previousAllowed = allowed;
                do {
                    level++;
                    allowed = allowed(base, seeds, level);
                } while (level < 4 && allowed.equals(previousAllowed));
            }
        var locked = new LinkedHashSet<UUID>();
        current.requirements().stream()
                .filter(r -> r.field().equals("lockedIds"))
                .forEach(r -> locked.addAll(ids(r.value())));
        if (!objects.keySet().containsAll(locked))
            throw new IllegalArgumentException("LOCK_TARGET_UNKNOWN");
        for (var i : base.items())
            if (i.fixedArrangementId() != null
                            && current.fixedArrangements().stream()
                                    .anyMatch(f -> f.id().equals(i.fixedArrangementId()))
                    || locked.contains(i.cityStayId())
                    || base.days().stream()
                            .anyMatch(
                                    d ->
                                            locked.contains(d.id())
                                                    && d.date().equals(i.startsAt().toLocalDate())))
                locked.add(i.id());
        for (var stay : base.accommodationStays())
            if (locked.contains(stay.cityStayId())) locked.add(stay.id());
        for (var cost : base.costEntries())
            if (locked.contains(cost.relatedId())) locked.add(cost.id());
        var reasons = new HashMap<UUID, String>();
        for (UUID id : allowed)
            reasons.put(
                    id,
                    seeds.contains(id)
                            ? "USER_CHANGE_TARGET"
                            : global ? "GLOBAL_REQUIREMENT" : "TRAVEL_DEPENDENCY_LEVEL_" + level);
        return new ImpactScope(
                allowed, changed, reasons, locked, allowed, allowed, history, level, global);
    }

    /**
     * @param base 基础内容
     * @param targets 修改目标
     * @param level 确定性层级
     * @return 对象与相邻交通住宿依赖，不沿交通链无界传播
     */
    private Set<UUID> allowed(TripSnapshot base, Set<UUID> targets, int level) {
        var result = new LinkedHashSet<>(targets);
        if (level == 4) {
            result.addAll(objects(base).keySet());
            return result;
        }
        for (var leg : base.transportLegs())
            if (targets.contains(leg.id())) {
                result.add(leg.fromItemId());
                result.add(leg.toItemId());
            }
        for (var cost : base.costEntries())
            if (targets.contains(cost.id())) result.add(cost.relatedId());
        for (var alternative : base.alternatives())
            if (targets.contains(alternative.id())) result.add(alternative.replacesItemId());
        Set<java.time.LocalDate> dates = new HashSet<>();
        Set<UUID> cities = new HashSet<>();
        for (var i : base.items())
            if (result.contains(i.id())) {
                dates.add(i.startsAt().toLocalDate());
                cities.add(i.cityStayId());
            }
        for (var c : base.cityStays()) if (targets.contains(c.id())) cities.add(c.id());
        for (var d : base.days()) if (targets.contains(d.id())) dates.add(d.date());
        for (var stay : base.accommodationStays())
            if (result.contains(stay.id())) {
                cities.add(stay.cityStayId());
                dates.add(stay.checkIn().toLocalDate());
                dates.add(stay.checkOut().toLocalDate());
            }
        if (level >= 1) {
            for (var d : base.days()) if (dates.contains(d.date())) result.add(d.id());
            for (var i : base.items())
                if (dates.contains(i.startsAt().toLocalDate())) result.add(i.id());
        }
        if (level >= 2) {
            for (var i : base.items()) if (result.contains(i.id())) cities.add(i.cityStayId());
            if (level >= 3) {
                var itemMap = new HashMap<UUID, TripSnapshot.Item>();
                base.items().forEach(i -> itemMap.put(i.id(), i));
                var adjacent = new HashSet<UUID>();
                for (var leg : base.transportLegs()) {
                    var from = itemMap.get(leg.fromItemId());
                    var to = itemMap.get(leg.toItemId());
                    if (from != null
                            && to != null
                            && (cities.contains(from.cityStayId())
                                    || cities.contains(to.cityStayId()))) {
                        adjacent.add(from.cityStayId());
                        adjacent.add(to.cityStayId());
                    }
                }
                cities.addAll(adjacent);
            }
            result.addAll(cities);
            for (var i : base.items())
                if (cities.contains(i.cityStayId())) {
                    result.add(i.id());
                    dates.add(i.startsAt().toLocalDate());
                }
            for (var d : base.days()) if (dates.contains(d.date())) result.add(d.id());
        }
        for (var leg : base.transportLegs())
            if (result.contains(leg.fromItemId()) || result.contains(leg.toItemId()))
                result.add(leg.id());
        for (var stay : base.accommodationStays())
            if (cities.contains(stay.cityStayId())
                    && (level >= 2
                            || dates.stream()
                                    .anyMatch(
                                            d ->
                                                    !d.isBefore(stay.checkIn().toLocalDate())
                                                            && !d.isAfter(
                                                                    stay.checkOut()
                                                                            .toLocalDate()))))
                result.add(stay.id());
        for (var cost : base.costEntries())
            if (result.contains(cost.relatedId())) result.add(cost.id());
        for (var alternative : base.alternatives())
            if (result.contains(alternative.replacesItemId())) result.add(alternative.id());
        return result;
    }

    /**
     * @param value UUID逗号列表，NONE表示用户明确清空锁定
     * @return 唯一身份集合；格式错误拒绝
     */
    public static Set<UUID> ids(String value) {
        if (value.equals("NONE")) return Set.of();
        return java.util.Arrays.stream(value.split(",", -1))
                .map(UUID::fromString)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * @param snapshot 快照
     * @return 全部业务身份及载荷，重复身份立即拒绝
     */
    public static Map<UUID, Object> objects(TripSnapshot snapshot) {
        var result = new java.util.LinkedHashMap<UUID, Object>();
        java.util.stream.Stream.of(
                        snapshot.cityStays(),
                        snapshot.days(),
                        snapshot.items(),
                        snapshot.transportLegs(),
                        snapshot.accommodationStays(),
                        snapshot.costEntries(),
                        snapshot.alternatives())
                .flatMap(List::stream)
                .forEach(
                        value -> {
                            if (result.putIfAbsent(id(value), value) != null)
                                throw new IllegalArgumentException("PATCH_DUPLICATE_ID");
                        });
        return result;
    }

    /**
     * @param value 封闭的业务对象类型
     * @return 稳定身份
     */
    public static UUID id(Object value) {
        return switch (value) {
            case TripSnapshot.CityStay v -> v.id();
            case TripSnapshot.Day v -> v.id();
            case TripSnapshot.Item v -> v.id();
            case TripSnapshot.Transport v -> v.id();
            case TripSnapshot.Accommodation v -> v.id();
            case TripSnapshot.Cost v -> v.id();
            case TripSnapshot.Alternative v -> v.id();
            default -> throw new IllegalArgumentException("PATCH_OBJECT_INVALID");
        };
    }
}
