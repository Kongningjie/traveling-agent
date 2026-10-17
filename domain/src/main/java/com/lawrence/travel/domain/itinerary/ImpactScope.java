package com.lawrence.travel.domain.itinerary;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Java计算的修改边界；范围扩大只增加可调整对象，永不解除锁定或改变需求。
 * @param allowedIds 可修改已有对象
 * @param affectedConstraintIds 变化的需求身份
 * @param dependencyReasons 对象纳入原因
 * @param lockedIds 不可修改对象
 * @param mustRecalculate 合并后需重算的关联对象
 * @param evidenceToRefresh 需核对证据的对象
 * @param expansionHistory 每轮扩大的理由
 * @param level 局部、同日、城市、相邻城市、全程，0至4
 * @param windowEditable 是否因全程需求变化允许调整旅行窗口
 */
public record ImpactScope(Set<UUID> allowedIds, Set<UUID> affectedConstraintIds,
                          Map<UUID, String> dependencyReasons, Set<UUID> lockedIds,
                          Set<UUID> mustRecalculate, Set<UUID> evidenceToRefresh,
                          List<String> expansionHistory, int level, boolean windowEditable) {
    /** 防御复制，拒绝无理由扩大和越界层级；锁定对象仍可作为只读依赖。 */
    public ImpactScope {
        allowedIds = Set.copyOf(allowedIds); affectedConstraintIds = Set.copyOf(affectedConstraintIds);
        dependencyReasons = Map.copyOf(dependencyReasons); lockedIds = Set.copyOf(lockedIds);
        mustRecalculate = Set.copyOf(mustRecalculate); evidenceToRefresh = Set.copyOf(evidenceToRefresh);
        expansionHistory = List.copyOf(expansionHistory);
        if (level < 0 || level > 4 || expansionHistory.size() > 2 || expansionHistory.stream().anyMatch(v -> v == null || v.isBlank() || v.length() > 500)) throw new IllegalArgumentException("IMPACT_SCOPE_INVALID");
    }
}
