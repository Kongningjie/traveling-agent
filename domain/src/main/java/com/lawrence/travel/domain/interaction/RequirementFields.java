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
    private static final Set<String> SUPPORTED = Set.of("cities", "startDate", "endDate", "travelers", "budgetRule", "budgetCny", "budgetScope", "fixedStatus", "fixedArrangements", "pace", "diet", "interests", "hardRequirements");
    /** 无状态规则。 */
    private RequirementFields() { }
    /**
     * @param current 已确认内容
     * @param updates 明确增量
     * @param source 明确来源
     * @return 合并且校验后的全量；同字段保持稳定 ID
     */
    public static List<ConstraintRevision.Requirement> merge(List<ConstraintRevision.Requirement> current, Map<String, String> updates, ConstraintRevision.Source source) {
        var values = new LinkedHashMap<String, ConstraintRevision.Requirement>();
        for (var value : current) if (values.putIfAbsent(value.field(), value) != null) throw new IllegalArgumentException("AMBIGUOUS_REQUIREMENT_SCOPE");
        if ("NONE".equals(updates.get("budgetRule")) && (updates.containsKey("budgetCny") || updates.containsKey("budgetScope"))) throw new IllegalArgumentException("BUDGET_CONFLICT");
        if (updates.containsKey("budgetCny") && !updates.containsKey("budgetRule") && values.containsKey("budgetRule") && values.get("budgetRule").value().equals("NONE")) values.remove("budgetRule");
        for (var entry : updates.entrySet()) {
            if (!SUPPORTED.contains(entry.getKey()) || entry.getValue() == null || entry.getValue().isBlank() || entry.getValue().length() > 500) throw new IllegalArgumentException("REQUIREMENT_INVALID");
            var old = values.get(entry.getKey());
            if (old != null && !old.targetIds().isEmpty()) throw new IllegalArgumentException("SCOPED_REQUIREMENT_REQUIRES_CLARIFICATION");
            // 尚未形式化的硬限制必须保留为 HARD；新增限制不能覆盖前面仍有效的要求，更不能降成节奏偏好。
            String value = entry.getValue();
            if (entry.getKey().equals("hardRequirements") && old != null) {
                value = old.value();
                if (!value.contains(entry.getValue())) value = value + "；" + entry.getValue();
            }
            values.put(entry.getKey(), new ConstraintRevision.Requirement(old == null ? UUID.randomUUID() : old.id(), entry.getKey(), value,
                    Set.of("pace", "diet", "interests").contains(entry.getKey()) ? ConstraintRevision.Kind.SOFT : ConstraintRevision.Kind.HARD, source, Set.of()));
        }
        if (updates.containsKey("fixedArrangements")) {
            if ("NONE".equals(updates.get("fixedStatus"))) throw new IllegalArgumentException("FIXED_STATUS_CONFLICT");
            var oldStatus = values.get("fixedStatus");
            values.put("fixedStatus", new ConstraintRevision.Requirement(oldStatus == null ? UUID.randomUUID() : oldStatus.id(), "fixedStatus", "PROVIDED", ConstraintRevision.Kind.HARD, source, Set.of()));
        }
        if ("NONE".equals(updates.get("budgetRule"))) { values.remove("budgetCny"); values.remove("budgetScope"); }
        if ("NONE".equals(updates.get("fixedStatus"))) values.remove("fixedArrangements");
        Map<String, String> fields = fields(List.copyOf(values.values()));
        try {
            if (fields.containsKey("cities")) { var cities = fields.get("cities").split(",", -1); if (cities.length < 1 || cities.length > 3 || java.util.Arrays.stream(cities).anyMatch(String::isBlank) || Set.of(cities).size() != cities.length) throw new IllegalArgumentException(); }
            if (fields.containsKey("travelers") && Integer.parseInt(fields.get("travelers")) <= 0) throw new IllegalArgumentException();
            if (fields.containsKey("startDate")) LocalDate.parse(fields.get("startDate"));
            if (fields.containsKey("endDate")) LocalDate.parse(fields.get("endDate"));
            if (fields.containsKey("startDate") && fields.containsKey("endDate")) { long days = ChronoUnit.DAYS.between(LocalDate.parse(fields.get("startDate")), LocalDate.parse(fields.get("endDate"))); if (days < 0 || days > 9) throw new IllegalArgumentException(); }
            if (fields.containsKey("budgetRule") && !Set.of("NONE", "PER_PERSON", "PER_GROUP").contains(fields.get("budgetRule"))) throw new IllegalArgumentException();
            if (fields.containsKey("budgetCny") && new BigDecimal(fields.get("budgetCny")).signum() <= 0) throw new IllegalArgumentException();
            if (fields.containsKey("budgetScope") && !Set.of("ACTIVITIES", "MEALS", "LODGING", "INTERCITY", "ROUNDTRIP", "LOCAL_TRANSPORT").containsAll(List.of(fields.get("budgetScope").split(",", -1)))) throw new IllegalArgumentException();
            if (fields.containsKey("fixedStatus") && !Set.of("NONE", "UNKNOWN", "PROVIDED").contains(fields.get("fixedStatus"))) throw new IllegalArgumentException();
            if (fields.containsKey("fixedArrangements")) fixed(fields.get("fixedArrangements"), new UUID(0, 0));
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("REQUIREMENT_CONFLICT"); }
        return List.copyOf(values.values());
    }
    /**
     * @param current 已确认内容
     * @return 按业务字段索引
     */
    public static Map<String, String> fields(List<ConstraintRevision.Requirement> current) { var values = new LinkedHashMap<String, String>(); current.forEach(value -> values.put(value.field(), value.value())); return Map.copyOf(values); }
    /**
     * @param current 已确认内容
     * @return 只追问关键缺项，预算无限制时不再追问数额
     */
    public static List<String> missing(List<ConstraintRevision.Requirement> current) {
        var fields = fields(current); var required = new java.util.ArrayList<>(List.of("cities", "startDate", "endDate", "travelers", "budgetRule", "fixedStatus"));
        if (fields.containsKey("budgetRule") && !fields.get("budgetRule").equals("NONE")) required.addAll(List.of("budgetCny", "budgetScope"));
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
            var parts = line.split("\\|", -1); if (parts.length != 3) throw new IllegalArgumentException("FIXED_ARRANGEMENT_INVALID");
            var start = java.time.ZonedDateTime.parse(parts[1]); var end = java.time.ZonedDateTime.parse(parts[2]);
            result.add(new ConstraintRevision.FixedArrangement(UUID.nameUUIDFromBytes((source + line).getBytes(java.nio.charset.StandardCharsets.UTF_8)), parts[0], start, end, source));
        }
        return List.copyOf(result);
    }
}
