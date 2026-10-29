package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.interaction.Negotiation;
import com.lawrence.travel.application.itinerary.Planning;
import com.lawrence.travel.application.knowledge.Research;
import com.lawrence.travel.domain.interaction.DialogueIntent;
import com.lawrence.travel.domain.itinerary.ItineraryPatch;
import com.lawrence.travel.domain.itinerary.ItineraryReport;
import com.lawrence.travel.domain.itinerary.TripSnapshot;
import io.agentscope.core.util.JsonSchemaUtils;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 从实际业务DTO生成模型Schema；可空/兼容缺省字段保持，业务不变量仍由Java校验。 */
final class BusinessOutputSchema {
    private static final Set<Class<?>> OUTPUTS =
            Set.of(
                    DialogueIntent.Result.class,
                    Negotiation.Interpretation.class,
                    Research.Plan.class,
                    Research.Extraction.class,
                    Planning.Draft.class,
                    Planning.PatchReply.class,
                    Planning.Review.class);

    // 仅列已有可空或缺省契约；其余record字段必须显式返回，不能默认缺失为false/0。
    private static final Map<Class<?>, Set<String>> OPTIONAL =
            Map.ofEntries(
                    Map.entry(
                            DialogueIntent.Result.class,
                            Set.of("targetId", "resolvedInputs", "advice")),
                    Map.entry(DialogueIntent.Fact.class, Set.of("operation")),
                    Map.entry(Negotiation.Fact.class, Set.of("operation")),
                    Map.entry(Research.Query.class, Set.of("date", "arguments")),
                    Map.entry(Research.Extraction.class, Set.of("publishedAt", "validForDate")),
                    Map.entry(Planning.PatchReply.class, Set.of("patch", "expansionReason")),
                    Map.entry(TripSnapshot.class, Set.of("baseVersionId", "evidenceUses")),
                    Map.entry(TripSnapshot.Item.class, Set.of("fixedArrangementId")),
                    Map.entry(TripSnapshot.Transport.class, Set.of("durationMinutes")),
                    Map.entry(TripSnapshot.Accommodation.class, Set.of("locationKey")),
                    Map.entry(TripSnapshot.Cost.class, Set.of("upper")),
                    Map.entry(ItineraryPatch.class, Set.of("window")),
                    Map.entry(
                            ItineraryPatch.Operation.class,
                            Set.of(
                                    "city",
                                    "day",
                                    "item",
                                    "transport",
                                    "accommodation",
                                    "cost",
                                    "alternative")),
                    Map.entry(ItineraryReport.Issue.class, Set.of("objectId")));

    /** 工具类不持有可变的共享Schema。 */
    private BusinessOutputSchema() {}

    /**
     * 为本次调用创建独立Schema，不让上一次调用或其他角色修改它。
     *
     * @param outputType 应用显式指定的七类业务输出之一
     * @return SDK生成并补齐必填/可空/字典值类型的对象Schema
     * @throws IllegalArgumentException 类型未登记或SDK结构无法对应DTO
     */
    static Map<String, Object> create(Class<?> outputType) {
        requireType(outputType);
        var schema = JsonSchemaUtils.generateSchemaFromClass(outputType);
        enrich(schema, outputType, schema, Collections.newSetFromMap(new IdentityHashMap<>()));
        return schema;
    }

    /**
     * 在创建运行句柄和读取凭据前检查调用方声明的输出类型。
     *
     * @param outputType 固定业务DTO
     */
    static void requireType(Class<?> outputType) {
        if (outputType == null || !OUTPUTS.contains(outputType)) {
            throw new IllegalArgumentException("BUSINESS_OUTPUT_TYPE_INVALID");
        }
    }

    /**
     * 保留SDK的枚举、格式与引用结构，按record及泛型信息补充形状约束。
     *
     * @param node 当前Schema节点
     * @param type 对应Java字段类型
     * @param root 引用解析根
     * @param visited 已处理的节点，避免共享定义重复包装或循环引用
     */
    @SuppressWarnings("unchecked")
    private static void enrich(
            Map<String, Object> node,
            Type type,
            Map<String, Object> root,
            Set<Map<String, Object>> visited) {
        if (!visited.add(node)) {
            return;
        }
        if (node.get("$ref") instanceof String reference) {
            enrich(resolve(root, reference), type, root, visited);
            return;
        }
        if (type instanceof ParameterizedType generic) {
            var raw = (Class<?>) generic.getRawType();
            if (Map.class.isAssignableFrom(raw)) {
                Type valueType = generic.getActualTypeArguments()[1];
                var values = JsonSchemaUtils.generateSchemaFromType(valueType);
                enrich(
                        values,
                        valueType,
                        values,
                        Collections.newSetFromMap(new IdentityHashMap<>()));
                node.put("additionalProperties", values);
                return;
            }
            if (Iterable.class.isAssignableFrom(raw)
                    && node.get("items") instanceof Map<?, ?> items) {
                enrich(
                        (Map<String, Object>) items,
                        generic.getActualTypeArguments()[0],
                        root,
                        visited);
                return;
            }
        }
        if (!(type instanceof Class<?> record) || !record.isRecord()) {
            return;
        }
        if (!(node.get("properties") instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("BUSINESS_OUTPUT_SCHEMA_INVALID");
        }
        var properties = (Map<String, Object>) node.get("properties");
        var required = new ArrayList<String>();
        var optional = OPTIONAL.getOrDefault(record, Set.of());
        for (var component : record.getRecordComponents()) {
            var property = (Map<String, Object>) properties.get(component.getName());
            if (property == null) {
                throw new IllegalArgumentException("BUSINESS_OUTPUT_SCHEMA_INVALID");
            }
            enrich(property, component.getGenericType(), root, visited);
            if (optional.contains(component.getName())) {
                properties.put(
                        component.getName(),
                        Map.of("anyOf", List.of(property, Map.of("type", "null"))));
            } else {
                required.add(component.getName());
            }
        }
        node.put("required", required);
        node.put("additionalProperties", false);
        if (record == Planning.PatchReply.class) {
            node.put(
                    "anyOf",
                    List.of(
                            Map.of("required", List.of("patch")),
                            Map.of("required", List.of("expansionReason"))));
        }
    }

    /**
     * 解析SDK生成的本地JSON Pointer，不接受外部Schema地址。
     *
     * @param root 本次Schema
     * @param reference 本地引用
     * @return 对应定义对象
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolve(Map<String, Object> root, String reference) {
        if (!reference.startsWith("#/")) {
            throw new IllegalArgumentException("BUSINESS_OUTPUT_SCHEMA_INVALID");
        }
        Object current = root;
        for (String key : reference.substring(2).split("/")) {
            if (!(current instanceof Map<?, ?> values)) {
                throw new IllegalArgumentException("BUSINESS_OUTPUT_SCHEMA_INVALID");
            }
            current = values.get(key.replace("~1", "/").replace("~0", "~"));
        }
        if (!(current instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("BUSINESS_OUTPUT_SCHEMA_INVALID");
        }
        return (Map<String, Object>) current;
    }
}
