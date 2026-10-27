package com.lawrence.travel.infrastructure.travel;

import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.application.interaction.DialogueDecodeFailure;
import com.lawrence.travel.application.interaction.DialogueDecodeFailure.Kind;
import com.lawrence.travel.application.interaction.DialogueDecodeFailure.Field;
import com.lawrence.travel.domain.itinerary.ItineraryVersion;
import com.lawrence.travel.domain.travel.TravelTask;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.INVALID_INPUT;

/**
 * 后端结构化数据的严格编解码器，连接模型业务JSON、领域对象和PG JSONB。
 *
 * <p>bootstrap通过InteractionCodec把聊天Result解码路由到decodeDialogue；拒绝重复键、尾随JSON、
 * 非法枚举/形状和领域构造约束，不用字符串修复伪造有效模型产物。编码递归排序对象键以便
 * 生成一致哈希，但保留数组顺序。序列化成功不代表旅行可行或业务已提交。
 * 诊断仅取固定类别/白名单字段路径，不存解析器异常原文、非法字段值或模型全文。
 */
public final class TravelJson {
    private final JsonMapper mapper =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(
                            tools.jackson.databind.DeserializationFeature
                                    .USE_BIG_DECIMAL_FOR_FLOATS)
                    .enable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_WITH_ZONE_ID)
                    .disable(
                            tools.jackson.databind.cfg.DateTimeFeature
                                    .ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                    .disable(
                            tools.jackson.databind.cfg.DateTimeFeature
                                    .WRITE_DATES_WITH_CONTEXT_TIME_ZONE)
                    .build();

    /**
     * 创建线程安全的固定格式编解码器，不复用含供应商配置的可变 Mapper。
     */
    public TravelJson() {}

    /**
     * @param value 可序列化内容
     * @return 对象键递归排序后的 JSON，不改变数组顺序
     */
    public String encode(Object value) {
        try {
            return mapper.writeValueAsString(
                    normalize(mapper.readValue(mapper.writeValueAsString(value), Object.class)));
        } catch (RuntimeException failure) {
            throw new TravelStorageFailure(INVALID_INPUT);
        }
    }

    /**
     * @param text JSON
     * @param type 目标类型
     * @param <T> 结果类型
     * @return 固定类型结果；错误不携带正文
     */
    public <T> T decode(String text, Class<T> type) {
        try {
            return mapper.readValue(text, type);
        } catch (RuntimeException failure) {
            throw new TravelStorageFailure(INVALID_INPUT);
        }
    }

    /**
     * 将外层answer内的业务JSON解码为DialogueIntent.Result，并生成可脱敏定位的协议错误。
     *
     * <p>DialogueIntentInterpreter通过装配后的InteractionCodec调用本方法；语法、数组/对象形状、
     * 枚举、UUID和领域约束分别分类。Jackson异常只用于读取可信类型及白名单路径，不读取message/原值。
     * 成功产物仍需process.validateSources和route检查，不能仅凭JSON可解析就执行模型建议。
     *
     * @param text 不可信模型业务JSON
     * @return 严格领域结果，不修复或容忍非法结构
     * @throws DialogueDecodeFailure 只含固定类别和白名单路径
     */
    public com.lawrence.travel.domain.interaction.DialogueIntent.Result decodeDialogue(
            String text) {
        try {
            var result =
                    mapper.readValue(
                            text,
                            com.lawrence.travel.domain.interaction.DialogueIntent.Result.class);
            if (result == null) throw new DialogueDecodeFailure(Kind.SHAPE, Field.ROOT);
            return result;
        } catch (tools.jackson.core.JacksonException failure) {
            // 不读取message、source、非法值或cause；Jackson路径只用于匹配编译期字段白名单。
            var kind = Kind.UNKNOWN;
            if (failure instanceof tools.jackson.core.exc.StreamReadException) kind = Kind.SYNTAX;
            else if (failure instanceof tools.jackson.databind.exc.InvalidFormatException format
                    && format.getTargetType() != null
                    && format.getTargetType().isEnum()) kind = Kind.ENUM;
            else if (failure instanceof tools.jackson.databind.exc.InvalidFormatException format
                    && format.getTargetType() == UUID.class) kind = Kind.UUID;
            else if (failure instanceof tools.jackson.databind.exc.ValueInstantiationException)
                kind = Kind.CONSTRAINT;
            else if (failure instanceof tools.jackson.databind.exc.MismatchedInputException)
                kind = Kind.SHAPE;
            throw new DialogueDecodeFailure(kind, dialogueField(failure));
        }
    }

    /**
     * @param failure 仅在栈内读取路径，不保存解析器引用
     * @return 未知或过深路径一律ROOT，不输出动态属性名
     */
    private static Field dialogueField(tools.jackson.core.JacksonException failure) {
        if (failure.getPath().size() > 8) return Field.ROOT;
        // 动态键中若含点号等分隔符，不能伪装成facts.operation一类嵌套路径。
        if (failure.getPath().stream()
                .map(tools.jackson.core.JacksonException.Reference::getPropertyName)
                .filter(java.util.Objects::nonNull)
                .anyMatch(name -> !name.matches("[A-Za-z]{1,32}"))) return Field.ROOT;
        String key =
                failure.getPath().stream()
                        .map(tools.jackson.core.JacksonException.Reference::getPropertyName)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.joining("."));
        return switch (key) {
            case "intents" -> Field.INTENTS;
            case "intents.kind" -> Field.INTENT_KIND;
            case "intents.action" -> Field.INTENT_ACTION;
            case "intents.quote" -> Field.INTENT_QUOTE;
            case "facts" -> Field.FACTS;
            case "facts.field" -> Field.FACT_FIELD;
            case "facts.value" -> Field.FACT_VALUE;
            case "facts.quote" -> Field.FACT_QUOTE;
            case "facts.operation" -> Field.FACT_OPERATION;
            case "reply" -> Field.REPLY;
            case "clarification" -> Field.CLARIFICATION;
            case "targetId" -> Field.TARGET_ID;
            case "category" -> Field.CATEGORY;
            case "value" -> Field.VALUE;
            case "city" -> Field.CITY;
            case "resolvedInputs" -> Field.RESOLVED_INPUTS;
            case "advice" -> Field.ADVICE;
            default -> Field.ROOT;
        };
    }

    /**
     * 直接读取带 UUID 键的待处理状态，不通过临时任务 JSON 拼接重建领域对象。
     * @param text 数据库 JSONB 对象
     * @return 已解析输入映射，领域构造器继续检查顺序和分类
     */
    public Map<UUID, TravelTask.PendingInput> pendingInputs(String text) {
        try {
            return mapper.readValue(
                    text,
                    mapper.getTypeFactory()
                            .constructMapType(
                                    Map.class, UUID.class, TravelTask.PendingInput.class));
        } catch (RuntimeException failure) {
            throw new TravelStorageFailure(INVALID_INPUT);
        }
    }

    /**
     * @param text UTF-8 正文
     * @return SHA256 小写摘要
     */
    public String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA256_UNAVAILABLE");
        }
    }

    /**
     * 检查存储信封的版本、引用与必要数组；不判断时间冲突、费用或证据真实性。
     * @param version 不可变元信息
     * @param text 原始正文，不超过 2 MiB；仅限制单份载荷，不自动迁到文件
     * @return 摘要一致的规范 JSON
     */
    public String snapshot(ItineraryVersion version, String text) {
        require(text != null && text.length() <= 2 * 1024 * 1024);
        require(text.getBytes(StandardCharsets.UTF_8).length <= 2 * 1024 * 1024);
        Object decoded = decode(text, Object.class);
        require(decoded instanceof Map<?, ?>);
        Map<?, ?> payload = (Map<?, ?>) decoded;
        require(
                Integer.valueOf(version.schemaVersion()).equals(payload.get("schemaVersion"))
                        && version.schemaVersion() == 1);
        require(version.scope().taskId().toString().equals(payload.get("taskId")));
        require(
                Long.toString(version.constraintRevision())
                        .equals(String.valueOf(payload.get("constraintRevision"))));
        require(
                java.util.Objects.equals(
                                version.baseVersionId() == null
                                        ? null
                                        : version.baseVersionId().toString(),
                                payload.get("baseVersionId"))
                        && payload.containsKey("baseVersionId"));
        for (String field :
                List.of(
                        "cityStays",
                        "days",
                        "items",
                        "transportLegs",
                        "accommodationStays",
                        "costEntries",
                        "alternatives",
                        "evidenceRefs")) {
            require(payload.get(field) instanceof List<?>);
        }
        var references =
                new java.util.HashSet<
                        com.lawrence.travel.domain.knowledge.EvidenceRecord.Reference>();
        for (Object value : (List<?>) payload.get("evidenceRefs"))
            require(
                    references.add(
                            decode(
                                    encode(value),
                                    com.lawrence.travel.domain.knowledge.EvidenceRecord.Reference
                                            .class)));
        require(references.equals(version.evidenceRefs()));
        String canonical = encode(decoded);
        require(hash(canonical).equals(version.payloadHash()));
        return canonical;
    }

    /**
     * @param value 通用 JSON 值
     * @return 排序对象，标量与数组语义保持原样
     */
    private Object normalize(Object value) {
        if (value instanceof Map<?, ?> object) {
            var sorted = new TreeMap<String, Object>();
            object.forEach(
                    (key, element) -> {
                        // targetIds 是集合语义；重试时不应受 Set 的 JVM 迭代顺序影响。
                        Object normalized = normalize(element);
                        if (setKey(key) && normalized instanceof List<?> ids) {
                            normalized =
                                    ids.stream()
                                            .sorted(
                                                    java.util.Comparator.comparing(
                                                            Object::toString))
                                            .toList();
                        }
                        sorted.put(key.toString(), normalized);
                    });
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::normalize).toList();
        return value;
    }

    /**
     * @param key JSON集合语义字段
     * @return 是否固定排序，防止同一证据集合因 JVM 迭代顺序造成假冲突
     */
    private static boolean setKey(Object key) {
        return "targetIds".equals(key) || "evidenceRefs".equals(key);
    }

    /**
     * @param condition 格式条件；失败只输出固定分类
     */
    private static void require(boolean condition) {
        if (!condition) throw new TravelStorageFailure(INVALID_INPUT);
    }
}
