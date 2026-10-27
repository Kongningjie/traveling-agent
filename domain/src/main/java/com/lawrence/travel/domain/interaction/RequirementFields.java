package com.lawrence.travel.domain.interaction;

import com.lawrence.travel.domain.travel.ConstraintRevision;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 初始协商字段及确定性冲突；不根据老人、儿童等标签推断人数或体力。 */
public final class RequirementFields {
    private static final Set<String> PREFERENCES = Set.of("pace", "diet", "interests");

    /** 软偏好增量语义；替换必须有明确用户依据，不能用于放宽硬限制。 */
    public enum PreferenceOperation {
        /** 保留已有偏好并补充。 */
        ADD,
        /** 用户明确替换该类偏好的完整内容。 */
        REPLACE
    }

    /**
     * @param field 规范字段
     * @return 是否允许显式替换软偏好，不含硬限制或固定安排
     */
    public static boolean isPreference(String field) {
        return PREFERENCES.contains(field);
    }

    private static final Set<String> SUPPORTED =
            Set.of(
                    "cities",
                    "startDate",
                    "endDate",
                    "travelers",
                    "budgetRule",
                    "budgetCny",
                    "budgetScope",
                    "fixedStatus",
                    "fixedArrangements",
                    "pace",
                    "diet",
                    "interests",
                    "hardRequirements",
                    "changeTargets",
                    "lockedIds");

    /** 无状态规则。 */
    private RequirementFields() {}

    /**
     * @param current 已确认内容
     * @param updates 明确增量
     * @param source 明确来源
     * @return 合并且校验后的全量；同字段保持稳定 ID
     */
    public static List<ConstraintRevision.Requirement> merge(
            List<ConstraintRevision.Requirement> current,
            Map<String, String> updates,
            ConstraintRevision.Source source) {
        return merge(current, updates, source, Set.of());
    }

    /**
     * @param current 已确认需求
     * @param updates 完整变更
     * @param source 新字段来源
     * @param replacements 用户明确替换完整内容的软偏好字段；其余软偏好默认追加
     * @return 校验后的不可变需求，不允许将替换语义用于硬限制
     */
    public static List<ConstraintRevision.Requirement> merge(
            List<ConstraintRevision.Requirement> current,
            Map<String, String> updates,
            ConstraintRevision.Source source,
            Set<String> replacements) {
        if (!PREFERENCES.containsAll(replacements) || !updates.keySet().containsAll(replacements))
            throw new IllegalArgumentException("PREFERENCE_OPERATION_INVALID");
        var values = new LinkedHashMap<String, ConstraintRevision.Requirement>();
        for (var value : current)
            if (values.putIfAbsent(value.field(), value) != null)
                throw new IllegalArgumentException("AMBIGUOUS_REQUIREMENT_SCOPE");
        if ("NONE".equals(updates.get("budgetRule"))
                && (updates.containsKey("budgetCny") || updates.containsKey("budgetScope")))
            throw new IllegalArgumentException("BUDGET_CONFLICT");
        if (updates.containsKey("budgetCny")
                && !updates.containsKey("budgetRule")
                && values.containsKey("budgetRule")
                && values.get("budgetRule").value().equals("NONE")) values.remove("budgetRule");
        for (var entry : updates.entrySet()) {
            if (!SUPPORTED.contains(entry.getKey())
                    || entry.getValue() == null
                    || entry.getValue().isBlank()
                    || entry.getValue().length() > 500)
                throw new IllegalArgumentException("REQUIREMENT_INVALID");
            var old = values.get(entry.getKey());
            if (old != null && !old.targetIds().isEmpty())
                throw new IllegalArgumentException("SCOPED_REQUIREMENT_REQUIRES_CLARIFICATION");
            if (old != null
                    && PREFERENCES.contains(entry.getKey())
                    && old.kind() == ConstraintRevision.Kind.HARD)
                throw new IllegalArgumentException("HARD_REQUIREMENT_REQUIRES_CLARIFICATION");
            // 尚未形式化的硬限制必须保留为 HARD；新增限制不能覆盖前面仍有效的要求，更不能降成节奏偏好。
            String value = entry.getValue();
            if (PREFERENCES.contains(entry.getKey())
                    && old != null
                    && !replacements.contains(entry.getKey())) {
                var items = new java.util.LinkedHashSet<String>(List.of(old.value().split("\n")));
                items.addAll(List.of(value.split("\n")));
                value = String.join("\n", items);
            }
            if (entry.getKey().equals("hardRequirements") && old != null) {
                value = old.value();
                if (!value.contains(entry.getValue())) value = value + "；" + entry.getValue();
            }
            // 固定安排字段表示新增事实；复述旧预订不能替换旧对象或删除未提及预订。
            if (entry.getKey().equals("fixedArrangements") && old != null) {
                value =
                        mergeFixed(fixed(old.value(), new UUID(0, 0)), fixed(value, new UUID(0, 0)))
                                .stream()
                                .map(
                                        item ->
                                                item.type()
                                                        + "|"
                                                        + item.startsAt()
                                                        + "|"
                                                        + item.endsAt())
                                .collect(java.util.stream.Collectors.joining("\n"));
            }
            values.put(
                    entry.getKey(),
                    new ConstraintRevision.Requirement(
                            old == null ? UUID.randomUUID() : old.id(),
                            entry.getKey(),
                            value,
                            Set.of("pace", "diet", "interests", "changeTargets")
                                            .contains(entry.getKey())
                                    ? ConstraintRevision.Kind.SOFT
                                    : ConstraintRevision.Kind.HARD,
                            source,
                            Set.of()));
        }
        if (updates.containsKey("fixedArrangements")) {
            if ("NONE".equals(updates.get("fixedStatus")))
                throw new IllegalArgumentException("FIXED_STATUS_CONFLICT");
            var oldStatus = values.get("fixedStatus");
            values.put(
                    "fixedStatus",
                    new ConstraintRevision.Requirement(
                            oldStatus == null ? UUID.randomUUID() : oldStatus.id(),
                            "fixedStatus",
                            "PROVIDED",
                            ConstraintRevision.Kind.HARD,
                            source,
                            Set.of()));
        }
        if ("NONE".equals(updates.get("budgetRule"))) {
            values.remove("budgetCny");
            values.remove("budgetScope");
        }
        if ("NONE".equals(updates.get("fixedStatus"))) values.remove("fixedArrangements");
        Map<String, String> fields = fields(List.copyOf(values.values()));
        try {
            if (fields.containsKey("cities")) {
                var cities = fields.get("cities").split(",", -1);
                if (cities.length < 1
                        || cities.length > 3
                        || java.util.Arrays.stream(cities).anyMatch(String::isBlank)
                        || Set.of(cities).size() != cities.length)
                    throw new IllegalArgumentException();
            }
            for (String field : List.of("changeTargets", "lockedIds"))
                if (fields.containsKey(field))
                    com.lawrence.travel.domain.itinerary.ImpactAnalyzer.ids(fields.get(field));
            if (fields.containsKey("travelers") && Integer.parseInt(fields.get("travelers")) <= 0)
                throw new IllegalArgumentException();
            if (fields.containsKey("startDate")) LocalDate.parse(fields.get("startDate"));
            if (fields.containsKey("endDate")) LocalDate.parse(fields.get("endDate"));
            if (fields.containsKey("startDate") && fields.containsKey("endDate")) {
                long days =
                        ChronoUnit.DAYS.between(
                                LocalDate.parse(fields.get("startDate")),
                                LocalDate.parse(fields.get("endDate")));
                if (days < 0 || days > 9) throw new IllegalArgumentException();
            }
            if (fields.containsKey("budgetRule")
                    && !Set.of("NONE", "PER_PERSON", "PER_GROUP")
                            .contains(fields.get("budgetRule")))
                throw new IllegalArgumentException();
            if (fields.containsKey("budgetCny")
                    && new BigDecimal(fields.get("budgetCny")).signum() <= 0)
                throw new IllegalArgumentException();
            if (fields.containsKey("budgetScope")
                    && !Set.of(
                                    "ACTIVITIES",
                                    "MEALS",
                                    "LODGING",
                                    "INTERCITY",
                                    "ROUNDTRIP",
                                    "LOCAL_TRANSPORT")
                            .containsAll(List.of(fields.get("budgetScope").split(",", -1))))
                throw new IllegalArgumentException();
            if (fields.containsKey("fixedStatus")
                    && !Set.of("NONE", "UNKNOWN", "PROVIDED").contains(fields.get("fixedStatus")))
                throw new IllegalArgumentException();
            if (fields.containsKey("fixedArrangements"))
                fixed(fields.get("fixedArrangements"), new UUID(0, 0));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("REQUIREMENT_CONFLICT");
        }
        return List.copyOf(values.values());
    }

    /**
     * @param current 已确认需求
     * @param fields 本次完整输入字段集合
     * @return 仅在输入边界清除上次目标，逐字段合并不能清除本次目标
     */
    public static List<ConstraintRevision.Requirement> inputBase(
            List<ConstraintRevision.Requirement> current, Set<String> fields) {
        return fields.contains("changeTargets")
                ? current
                : current.stream().filter(r -> !r.field().equals("changeTargets")).toList();
    }

    /**
     * @param current 已确认内容
     * @return 按业务字段索引
     */
    public static Map<String, String> fields(List<ConstraintRevision.Requirement> current) {
        var values = new LinkedHashMap<String, String>();
        current.forEach(value -> values.put(value.field(), value.value()));
        return Map.copyOf(values);
    }

    /**
     * @param current 已确认内容
     * @return 只追问关键缺项，预算无限制时不再追问数额
     */
    public static List<String> missing(List<ConstraintRevision.Requirement> current) {
        var fields = fields(current);
        var required =
                new java.util.ArrayList<>(
                        List.of(
                                "cities",
                                "startDate",
                                "endDate",
                                "travelers",
                                "budgetRule",
                                "fixedStatus"));
        if (fields.containsKey("budgetRule") && !fields.get("budgetRule").equals("NONE"))
            required.addAll(List.of("budgetCny", "budgetScope"));
        if ("PROVIDED".equals(fields.get("fixedStatus"))) required.add("fixedArrangements");
        return required.stream().filter(field -> !fields.containsKey(field)).toList();
    }

    /**
     * @param value 每行 类型|完整起时|完整止时
     * @param source 当前用户消息
     * @return 固定安排，时区及跨午夜按实际时间线处理
     */
    public static List<ConstraintRevision.FixedArrangement> fixed(String value, UUID source) {
        var result = new java.util.ArrayList<ConstraintRevision.FixedArrangement>();
        for (String line : value.split("\n")) {
            var parts = line.split("\\|", -1);
            if (parts.length != 3) throw new IllegalArgumentException("FIXED_ARRANGEMENT_INVALID");
            var start = java.time.ZonedDateTime.parse(parts[1]);
            var end = java.time.ZonedDateTime.parse(parts[2]);
            result.add(
                    new ConstraintRevision.FixedArrangement(
                            UUID.nameUUIDFromBytes(
                                    (source + line)
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            parts[0],
                            start,
                            end,
                            source));
        }
        return List.copyOf(result);
    }

    /**
     * @param current 已确认固定安排，稳定ID及来源必须保留
     * @param additions 本次新增或复述的安排，不能据此取消、替换旧安排
     * @return 按类型及实际起止时刻去重的不可变并集；同一安排优先保留原对象
     */
    public static List<ConstraintRevision.FixedArrangement> mergeFixed(
            List<ConstraintRevision.FixedArrangement> current,
            List<ConstraintRevision.FixedArrangement> additions) {
        var result = new java.util.ArrayList<>(current);
        for (var item : additions) {
            boolean present =
                    result.stream()
                            .anyMatch(
                                    old ->
                                            old.type().equals(item.type())
                                                    && old.startsAt()
                                                            .toInstant()
                                                            .equals(item.startsAt().toInstant())
                                                    && old.endsAt()
                                                            .toInstant()
                                                            .equals(item.endsAt().toInstant()));
            if (!present) result.add(item);
        }
        return List.copyOf(result);
    }
}
