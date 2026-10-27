package com.lawrence.travel.domain.itinerary;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 纯内存原子合并；任一越界失败均不修改旧快照，结果必须再通过全程校验和审核。 */
public final class PatchService {
    /** 无共享可变状态。 */
    public PatchService() {}

    /**
     * @param base 原生效内容
     * @param baseId 原生效身份
     * @param hash 权威摘要
     * @param revision 当前需求
     * @param scope Java范围
     * @param patch 不可信补丁
     * @return 新完整候选；不移动生效指针
     */
    public TripSnapshot apply(
            TripSnapshot base,
            UUID baseId,
            String hash,
            long revision,
            ImpactScope scope,
            ItineraryPatch patch) {
        if (!hash.equals(patch.expectedBaseHash()))
            throw new IllegalArgumentException("PATCH_BASE_STALE");
        if (patch.window() != null && !scope.windowEditable())
            throw new IllegalArgumentException("PATCH_WINDOW_OUTSIDE_SCOPE");
        var original = ImpactAnalyzer.objects(base);
        var values = new java.util.LinkedHashMap<>(original);
        var seen = new HashSet<UUID>();
        var uses = new ArrayList<>(base.evidenceUses());
        for (var op : patch.operations()) {
            if (!seen.add(op.id())) throw new IllegalArgumentException("PATCH_DUPLICATE_OPERATION");
            Object old = values.get(op.id());
            checkDependencies(base, op, old, scope);
            if (scope.lockedIds().contains(op.id()))
                throw new IllegalArgumentException("PATCH_LOCKED");
            if (op.action() == ItineraryPatch.Action.ADD) {
                if (old != null || original.containsKey(op.id()) || !addAllowed(op, scope))
                    throw new IllegalArgumentException("PATCH_ADD_OUTSIDE_SCOPE");
            } else if (old == null
                    || !scope.allowedIds().contains(op.id())
                    || !matches(old, op.kind()))
                throw new IllegalArgumentException("PATCH_OUTSIDE_SCOPE");
            if (op.value() != null && !Objects.equals(ImpactAnalyzer.id(op.value()), op.id()))
                throw new IllegalArgumentException("PATCH_ID_CHANGED");
            if (op.action() == ItineraryPatch.Action.REMOVE) values.remove(op.id());
            else values.put(op.id(), op.value());
            uses.removeIf(u -> u.objectId().equals(op.id()));
            uses.addAll(op.evidenceUses());
        }
        // 不允许通过改父对象、删除端点或改变固定安排引用间接绕过锁；图结构先检查，时间与费用留给全程规则。
        var cities = select(values, TripSnapshot.CityStay.class);
        var items = select(values, TripSnapshot.Item.class);
        var legs = select(values, TripSnapshot.Transport.class);
        var stays = select(values, TripSnapshot.Accommodation.class);
        for (var old : base.items())
            if (scope.lockedIds().contains(old.id())) {
                if (!Objects.equals(values.get(old.id()), old)
                        || !Objects.equals(
                                values.get(old.cityStayId()), original.get(old.cityStayId()))
                        || patch.window() != null && !patch.window().equals(base.window()))
                    throw new IllegalArgumentException("PATCH_LOCKED_DEPENDENCY");
            }
        for (var old : base.accommodationStays())
            if (scope.lockedIds().contains(old.id())
                    && !Objects.equals(
                            values.get(old.cityStayId()), original.get(old.cityStayId())))
                throw new IllegalArgumentException("PATCH_LOCKED_DEPENDENCY");
        var cityIds =
                cities.stream()
                        .map(TripSnapshot.CityStay::id)
                        .collect(java.util.stream.Collectors.toSet());
        var itemIds =
                items.stream()
                        .map(TripSnapshot.Item::id)
                        .collect(java.util.stream.Collectors.toSet());
        if (items.stream().anyMatch(i -> !cityIds.contains(i.cityStayId()))
                || stays.stream().anyMatch(s -> !cityIds.contains(s.cityStayId()))
                || legs.stream()
                        .anyMatch(
                                l ->
                                        !itemIds.contains(l.fromItemId())
                                                || !itemIds.contains(l.toItemId()))
                || select(values, TripSnapshot.Cost.class).stream()
                        .anyMatch(c -> !values.containsKey(c.relatedId()))
                || select(values, TripSnapshot.Alternative.class).stream()
                        .anyMatch(a -> !itemIds.contains(a.replacesItemId())))
            throw new IllegalArgumentException("PATCH_DANGLING_REFERENCE");
        // 保留原独立天气等全局证据；删除仅由被修改对象使用的旧引用，不能改动范围外绑定。
        var formerlyBound =
                base.evidenceUses().stream()
                        .map(TripSnapshot.EvidenceUse::reference)
                        .collect(java.util.stream.Collectors.toSet());
        var refs = new HashSet<>(base.evidenceRefs());
        refs.removeAll(formerlyBound);
        uses.forEach(u -> refs.add(u.reference()));
        return new TripSnapshot(
                base.schemaVersion(),
                base.taskId(),
                revision,
                baseId,
                patch.window() == null ? base.window() : patch.window(),
                cities,
                select(values, TripSnapshot.Day.class),
                items,
                legs,
                stays,
                select(values, TripSnapshot.Cost.class),
                select(values, TripSnapshot.Alternative.class),
                refs,
                uses);
    }

    /**
     * @param base 原内容
     * @param op 当前操作
     * @param old 原对象，可空
     * @param scope 边界
     * @throws IllegalArgumentException 禁止通过移动日期或更换引用绕过范围
     */
    private void checkDependencies(
            TripSnapshot base, ItineraryPatch.Operation op, Object old, ImpactScope scope) {
        if (op.item() != null) {
            var item = op.item();
            if (item.startsAt() == null || item.endsAt() == null || item.cityStayId() == null)
                throw new IllegalArgumentException("PATCH_PAYLOAD_INVALID");
            var previous = old instanceof TripSnapshot.Item i ? i : null;
            if (scope.lockedIds().contains(item.cityStayId())
                    || previous != null
                            && !previous.cityStayId().equals(item.cityStayId())
                            && !scope.allowedIds().contains(item.cityStayId()))
                throw new IllegalArgumentException("PATCH_DEPENDENCY_OUTSIDE_SCOPE");
            if (previous != null
                    && (!previous.startsAt().toLocalDate().equals(item.startsAt().toLocalDate())
                            || !previous.endsAt()
                                    .toLocalDate()
                                    .equals(item.endsAt().toLocalDate()))) {
                if (!dateAllowed(base, scope, item.startsAt().toLocalDate())
                        || !dateAllowed(base, scope, item.endsAt().toLocalDate()))
                    throw new IllegalArgumentException("PATCH_DATE_OUTSIDE_SCOPE");
            }
        }
        if (op.transport() != null && old instanceof TripSnapshot.Transport previous) {
            if (!previous.fromItemId().equals(op.transport().fromItemId())
                            && !scope.allowedIds().contains(op.transport().fromItemId())
                    || !previous.toItemId().equals(op.transport().toItemId())
                            && !scope.allowedIds().contains(op.transport().toItemId()))
                throw new IllegalArgumentException("PATCH_DEPENDENCY_OUTSIDE_SCOPE");
        }
        if (op.accommodation() != null
                && scope.lockedIds().contains(op.accommodation().cityStayId()))
            throw new IllegalArgumentException("PATCH_LOCKED_DEPENDENCY");
        if (op.cost() != null
                && old instanceof TripSnapshot.Cost previous
                && !Objects.equals(previous.relatedId(), op.cost().relatedId())
                && !scope.allowedIds().contains(op.cost().relatedId()))
            throw new IllegalArgumentException("PATCH_DEPENDENCY_OUTSIDE_SCOPE");
    }

    /**
     * @param base 旅行日历
     * @param scope 当前范围
     * @param date 目标日期
     * @return 全程或明确纳入修改的日期才可移动到该日
     */
    private boolean dateAllowed(TripSnapshot base, ImpactScope scope, java.time.LocalDate date) {
        return scope.level() == 4
                || base.days().stream()
                        .anyMatch(
                                d ->
                                        d.date().equals(date)
                                                && scope.allowedIds().contains(d.id())
                                                && !scope.lockedIds().contains(d.id()));
    }

    /**
     * @param op 新对象
     * @param scope 可信范围
     * @return 新对象必须依附允许修改的已有对象；全程扩大仍不授予改需求权限
     */
    private boolean addAllowed(ItineraryPatch.Operation op, ImpactScope scope) {
        if (scope.level() == 4) return true;
        return switch (op.kind()) {
            case ITEM -> scope.allowedIds().contains(op.item().cityStayId());
            case TRANSPORT ->
                    scope.allowedIds().contains(op.transport().fromItemId())
                            || scope.allowedIds().contains(op.transport().toItemId());
            case ACCOMMODATION -> scope.allowedIds().contains(op.accommodation().cityStayId());
            case COST -> scope.allowedIds().contains(op.cost().relatedId());
            case ALTERNATIVE -> scope.allowedIds().contains(op.alternative().replacesItemId());
            default -> false;
        };
    }

    /**
     * @param value 原类型
     * @param kind 操作类别
     * @return 禁止跨类型复用ID
     */
    private boolean matches(Object value, ItineraryPatch.Kind kind) {
        return switch (kind) {
            case CITY -> value instanceof TripSnapshot.CityStay;
            case DAY -> value instanceof TripSnapshot.Day;
            case ITEM -> value instanceof TripSnapshot.Item;
            case TRANSPORT -> value instanceof TripSnapshot.Transport;
            case ACCOMMODATION -> value instanceof TripSnapshot.Accommodation;
            case COST -> value instanceof TripSnapshot.Cost;
            case ALTERNATIVE -> value instanceof TripSnapshot.Alternative;
        };
    }

    /**
     * @param values 合并工作副本
     * @param type 领域类型
     * @param <T> 结果类型
     * @return 保持原序及追加顺序的不可变列表
     */
    private <T> List<T> select(java.util.Map<UUID, Object> values, Class<T> type) {
        return values.values().stream().filter(type::isInstance).map(type::cast).toList();
    }
}
