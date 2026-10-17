package com.lawrence.travel.domain.memory;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 有来源和范围的长期软偏好；待确认和已删除条目均不得召回。
 * @param id 稳定身份 @param category 受控类别 @param value 有界用户数据 @param city 空表示通用，否则仅适用于该城市
 * @param source 用户直接保存或系统建议 @param state 生命周期 @param updatedAt 数据库更新时间 */
public record Preference(UUID id, String category, String value, String city, Source source, State state, Instant updatedAt) {
    /** 用户明确要求保存与推断建议必须区分；有限旅行授权不能代替长期确认。 */
    public enum Source { /** 主动保存。 */ USER, /** 推断建议。 */ SUGGESTION }
    /** 删除保留稳定身份，旧确认不能使其复活。 */
    public enum State { /** 不参与召回。 */ PENDING, /** 已明确确认。 */ CONFIRMED, /** 墓碑。 */ DELETED }
    /** 校验形状；墓碑清除原值，但保留类别、范围和身份防止旧写回。 */
    public Preference {
        if (id == null || !Set.of("pace", "diet", "interests", "transport").contains(category == null ? "" : category)
                || value == null || value.length() > 500 || (state != State.DELETED && value.isBlank())
                || city == null || city.length() > 80 || source == null || state == null || updatedAt == null) throw new IllegalArgumentException("PREFERENCE_INVALID");
    }
    /** @param version 用户级稳定修订 @param entries 仅当前有效或待确认条目；不能来自摘要 */
    public record View(PreferenceVersion version, List<Preference> entries) {
        /** 防止共享可变集合或已删除文本通过查询流出。 */
        public View { if (version == null || entries == null || entries.stream().anyMatch(p -> p.state() == State.DELETED)) throw new IllegalArgumentException("PREFERENCE_VIEW_INVALID"); entries = List.copyOf(entries); }
    }
    /** @param view 权威快照 @param cities 本次明确城市 @param overridden 本次已明确字段 @return 仅确认且相关的软偏好；城市范围保留，不合并成互斥全局事实 */
    public static View recall(View view, Set<String> cities, Set<String> overridden) {
        var relevant = view.entries().stream().filter(p -> p.state() == State.CONFIRMED && !overridden.contains(p.category()) && (p.city().isEmpty() || cities.contains(p.city()))).toList();
        return new View(view.version(), relevant.stream().filter(p -> !p.city().isEmpty() || cities.size() != 1 || relevant.stream().noneMatch(s -> s.category().equals(p.category()) && cities.contains(s.city()))).toList());
    }
}
