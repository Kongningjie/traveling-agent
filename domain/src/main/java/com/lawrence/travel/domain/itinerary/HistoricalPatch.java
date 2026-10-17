package com.lawrence.travel.domain.itinerary;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 历史恢复只生成业务Patch；不恢复旧需求、旧报告、执行代次或生效指针。 */
public final class HistoricalPatch {
    /** 纯函数命名空间。 */
    private HistoricalPatch() { }
    /** @param current 当前内容 @param historical 用户选定历史内容 @param hash 当前权威摘要 @return 完整对象差异，之后仍受锁定、全程校验和审核限制 */
    public static ItineraryPatch create(TripSnapshot current, TripSnapshot historical, String hash) {
        if (!current.taskId().equals(historical.taskId())) throw new IllegalArgumentException("RESTORE_SCOPE_INVALID");
        var old = ImpactAnalyzer.objects(current); var target = ImpactAnalyzer.objects(historical); var ops = new ArrayList<ItineraryPatch.Operation>();
        for (var entry : old.entrySet()) if (!target.containsKey(entry.getKey())) ops.add(operation(entry.getKey(), entry.getValue(), ItineraryPatch.Action.REMOVE, List.of()));
        for (var entry : target.entrySet()) {
            var uses = historical.evidenceUses().stream().filter(u -> u.objectId().equals(entry.getKey())).toList();
            var previousUses = current.evidenceUses().stream().filter(u -> u.objectId().equals(entry.getKey())).toList();
            if (!Objects.equals(old.get(entry.getKey()), entry.getValue()) || !uses.equals(previousUses)) ops.add(operation(entry.getKey(), entry.getValue(), old.containsKey(entry.getKey()) ? ItineraryPatch.Action.REPLACE : ItineraryPatch.Action.ADD, uses));
        }
        return new ItineraryPatch(hash, ops, current.window().equals(historical.window()) ? null : historical.window());
    }
    /** @param id 稳定ID @param value 封闭业务对象 @param action 动作 @param uses 对应对象证据 @return 严格类型操作，无任意JSON路径 */
    private static ItineraryPatch.Operation operation(UUID id, Object value, ItineraryPatch.Action action, List<TripSnapshot.EvidenceUse> uses) {
        var kind = switch (value) { case TripSnapshot.CityStay ignored -> ItineraryPatch.Kind.CITY; case TripSnapshot.Day ignored -> ItineraryPatch.Kind.DAY; case TripSnapshot.Item ignored -> ItineraryPatch.Kind.ITEM; case TripSnapshot.Transport ignored -> ItineraryPatch.Kind.TRANSPORT; case TripSnapshot.Accommodation ignored -> ItineraryPatch.Kind.ACCOMMODATION; case TripSnapshot.Cost ignored -> ItineraryPatch.Kind.COST; case TripSnapshot.Alternative ignored -> ItineraryPatch.Kind.ALTERNATIVE; default -> throw new IllegalArgumentException("RESTORE_OBJECT_INVALID"); };
        boolean remove = action == ItineraryPatch.Action.REMOVE;
        return new ItineraryPatch.Operation(kind, action, id, !remove && value instanceof TripSnapshot.CityStay v ? v : null, !remove && value instanceof TripSnapshot.Day v ? v : null, !remove && value instanceof TripSnapshot.Item v ? v : null, !remove && value instanceof TripSnapshot.Transport v ? v : null, !remove && value instanceof TripSnapshot.Accommodation v ? v : null, !remove && value instanceof TripSnapshot.Cost v ? v : null, !remove && value instanceof TripSnapshot.Alternative v ? v : null, uses);
    }
}
