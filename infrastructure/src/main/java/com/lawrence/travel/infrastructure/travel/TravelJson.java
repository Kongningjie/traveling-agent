package com.lawrence.travel.infrastructure.travel;

import com.lawrence.travel.application.travel.TravelStorageFailure;
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
 * JSONB 编解码与键排序摘要；数组顺序保留，拒绝重复键及尾随第二份 JSON。
 */
public final class TravelJson {
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_WITH_ZONE_ID)
            .disable(tools.jackson.databind.cfg.DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
            .disable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_WITH_CONTEXT_TIME_ZONE).build();

    /**
     * 创建线程安全的固定格式编解码器，不复用含供应商配置的可变 Mapper。
     */
    public TravelJson() { }

    /**
     * @param value 可序列化内容
     * @return 对象键递归排序后的 JSON，不改变数组顺序
     */
    public String encode(Object value) {
        try { return mapper.writeValueAsString(normalize(mapper.readValue(mapper.writeValueAsString(value), Object.class))); }
        catch (RuntimeException failure) { throw new TravelStorageFailure(INVALID_INPUT); }
    }

    /**
     * @param text JSON
     * @param type 目标类型
     * @param <T> 结果类型
     * @return 固定类型结果；错误不携带正文
     */
    public <T> T decode(String text, Class<T> type) {
        try { return mapper.readValue(text, type); }
        catch (RuntimeException failure) { throw new TravelStorageFailure(INVALID_INPUT); }
    }

    /**
     * 直接读取带 UUID 键的待处理状态，不通过临时任务 JSON 拼接重建领域对象。
     * @param text 数据库 JSONB 对象
     * @return 已解析输入映射，领域构造器继续检查顺序和分类
     */
    public Map<UUID, TravelTask.PendingInput> pendingInputs(String text) {
        try {
            return mapper.readValue(text, mapper.getTypeFactory().constructMapType(Map.class, UUID.class, TravelTask.PendingInput.class));
        } catch (RuntimeException failure) { throw new TravelStorageFailure(INVALID_INPUT); }
    }

    /**
     * @param text UTF-8 正文
     * @return SHA256 小写摘要
     */
    public String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
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
        require(Integer.valueOf(version.schemaVersion()).equals(payload.get("schemaVersion")) && version.schemaVersion() == 1);
        require(version.scope().taskId().toString().equals(payload.get("taskId")));
        require(Long.toString(version.constraintRevision()).equals(String.valueOf(payload.get("constraintRevision"))));
        require(java.util.Objects.equals(version.baseVersionId() == null ? null : version.baseVersionId().toString(), payload.get("baseVersionId"))
                && payload.containsKey("baseVersionId"));
        for (String field : List.of("cityStays", "days", "items", "transportLegs", "accommodationStays", "costEntries", "alternatives", "evidenceRefs")) {
            require(payload.get(field) instanceof List<?>);
        }
        var references = new java.util.HashSet<com.lawrence.travel.domain.knowledge.EvidenceRecord.Reference>();
        for (Object value : (List<?>) payload.get("evidenceRefs")) require(references.add(decode(encode(value), com.lawrence.travel.domain.knowledge.EvidenceRecord.Reference.class)));
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
            object.forEach((key, element) -> {
                // targetIds 是集合语义；重试时不应受 Set 的 JVM 迭代顺序影响。
                Object normalized = normalize(element);
                if (setKey(key) && normalized instanceof List<?> ids) {
                    normalized = ids.stream().sorted(java.util.Comparator.comparing(Object::toString)).toList();
                }
                sorted.put(key.toString(), normalized);
            });
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::normalize).toList();
        return value;
    }
    /** @param key JSON集合语义字段 @return 是否固定排序，防止同一证据集合因 JVM 迭代顺序造成假冲突 */
    private static boolean setKey(Object key) { return "targetIds".equals(key) || "evidenceRefs".equals(key); }

    /**
     * @param condition 格式条件；失败只输出固定分类
     */
    private static void require(boolean condition) { if (!condition) throw new TravelStorageFailure(INVALID_INPUT); }
}
