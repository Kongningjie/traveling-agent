package com.lawrence.travel.domain.itinerary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 按稳定业务ID比较两个不可变版本，费用、时间和锁定均显式列出，不推断变更原因。 */
public final class VersionDiff {
    /** 无共享状态。 */
    private VersionDiff() {}

    /**
     * @param kind 对象类型
     * @param id 对象身份
     * @param action 新增、删除或修改
     * @param before 原字段
     * @param after 新字段
     */
    public record Change(
            String kind,
            UUID id,
            String action,
            Map<String, String> before,
            Map<String, String> after) {}

    /**
     * @param before 原版本
     * @param after 新版本
     * @param oldLocks 原锁定
     * @param newLocks 新锁定
     * @return 对象级差异和窗口、锁定差异，不写入快照
     */
    public static List<Change> compare(
            TripSnapshot before, TripSnapshot after, Set<UUID> oldLocks, Set<UUID> newLocks) {
        var result = new ArrayList<Change>();
        var old = ImpactAnalyzer.objects(before);
        var next = ImpactAnalyzer.objects(after);
        var ids = new java.util.LinkedHashSet<>(old.keySet());
        ids.addAll(next.keySet());
        for (UUID id : ids)
            if (!Objects.equals(old.get(id), next.get(id))) {
                Object a = old.get(id), b = next.get(id);
                result.add(
                        new Change(
                                (a == null ? b : a).getClass().getSimpleName(),
                                id,
                                a == null ? "ADDED" : b == null ? "REMOVED" : "CHANGED",
                                fields(a),
                                fields(b)));
            }
        if (!before.window().equals(after.window()))
            result.add(
                    new Change(
                            "WINDOW",
                            before.taskId(),
                            "CHANGED",
                            fields(before.window()),
                            fields(after.window())));
        var locks = new java.util.HashSet<>(oldLocks);
        locks.addAll(newLocks);
        for (UUID id : locks.stream().sorted().toList())
            if (oldLocks.contains(id) != newLocks.contains(id))
                result.add(
                        new Change(
                                "LOCK",
                                id,
                                "CHANGED",
                                Map.of("locked", Boolean.toString(oldLocks.contains(id))),
                                Map.of("locked", Boolean.toString(newLocks.contains(id)))));
        return List.copyOf(result);
    }

    /**
     * @param value 受控领域record或空
     * @return 仅业务字段文字，供转义展示，不包含可执行HTML
     */
    private static Map<String, String> fields(Object value) {
        if (value == null) return Map.of();
        var result = new java.util.LinkedHashMap<String, String>();
        for (var component : value.getClass().getRecordComponents()) {
            try {
                result.put(
                        component.getName(),
                        Objects.toString(component.getAccessor().invoke(value), "未知"));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalArgumentException("VERSION_DIFF_INVALID");
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }
}
