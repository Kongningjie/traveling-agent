package com.lawrence.travel.infrastructure.evaluation;

import com.lawrence.travel.application.artifact.ArtifactService;
import com.lawrence.travel.application.evaluation.Evaluation;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 固定输入和裁判加载器；先核对全套字节指纹，再解析明确选择的分区，开发执行端看不到Oracle。 */
public final class FixedEvaluationSuite {
    public static final String LOCK_HASH = "6844a0bd396f17e5926a6cbeaa0558c51eeebe97326fcea3200a65bc8970be15";
    public static final String MODEL_ORACLE_HASH = "ad44cc40784c6754c99296f95a0ba2526b3698dcd11a12a10aaebcf93f2f5a41";
    private final Function<String, byte[]> resources;
    private final TravelJson json = new TravelJson();
    /** @param resources 固定资源读取函数；仅访问evaluation/v1下5个已知文件，不接收任意路径 */
    public FixedEvaluationSuite(Function<String, byte[]> resources) { this.resources = resources; }
    /** @param version 数据集版本 @param files 精确文件指纹 */
    public record Lock(String version, Map<String, String> files) { }
    /** @param includeHoldout 明确选择留出，默认false @return 经过指纹和结构核对的选择集；不修改或重新生成预期 */
    public Evaluation.Suite load(boolean includeHoldout) {
        byte[] lockBytes = resources.apply("suite-lock.json");
        if (!ArtifactService.hash(lockBytes).equals(LOCK_HASH)) throw new IllegalArgumentException("EVALUATION_LOCK_MISMATCH");
        var lock = json.decode(text(lockBytes), Lock.class);
        if (!lock.files().keySet().equals(java.util.Set.of("development.json", "holdout.json", "oracle.json"))) throw new IllegalArgumentException("EVALUATION_FILES_INVALID");
        var bytes = new HashMap<String, byte[]>();
        lock.files().forEach((name, hash) -> { byte[] value = resources.apply(name); if (!ArtifactService.hash(value).equals(hash)) throw new IllegalArgumentException("EVALUATION_FIXTURE_CHANGED"); bytes.put(name, value); });
        var cases = new ArrayList<Evaluation.Case>();
        add(cases, bytes.get("development.json"), "DEVELOPMENT", 1, 8);
        if (includeHoldout) add(cases, bytes.get("holdout.json"), "HOLDOUT", 9, 4);
        @SuppressWarnings("unchecked") Map<String, Object> raw = json.decode(text(bytes.get("oracle.json")), Map.class);
        var oracles = new HashMap<String, Evaluation.Oracle>();
        for (var input : cases) { var oracle = json.decode(json.encode(raw.get(input.id())), Evaluation.Oracle.class); if (oracle == null || oracle.required().isEmpty() || oracle.required().stream().anyMatch(key -> !key.matches("[A-Z][A-Z0-9_]{0,79}"))) throw new IllegalArgumentException("ORACLE_INVALID"); oracles.put(input.id(), oracle); }
        return new Evaluation.Suite(lock.version(), LOCK_HASH, List.copyOf(cases), Map.copyOf(oracles));
    }
    /** @param includeHoldout 显式选择留出 @return 同一固定输入配独立真实模型程序裁判；不把本地探针裁判当完整流程判据 */
    public Evaluation.Suite loadModels(boolean includeHoldout) {
        var base = load(includeHoldout); byte[] bytes = resources.apply("model-oracle.json");
        if (!ArtifactService.hash(bytes).equals(MODEL_ORACLE_HASH)) throw new IllegalArgumentException("MODEL_ORACLE_CHANGED");
        @SuppressWarnings("unchecked") Map<String, Object> raw = json.decode(text(bytes), Map.class);
        var oracles = new HashMap<String, Evaluation.Oracle>();
        base.cases().forEach(input -> oracles.put(input.id(), json.decode(json.encode(raw.get(input.id())), Evaluation.Oracle.class)));
        return new Evaluation.Suite(base.version() + "/model-rubric-v1", ArtifactService.hash((LOCK_HASH + MODEL_ORACLE_HASH).getBytes(StandardCharsets.UTF_8)), base.cases(), Map.copyOf(oracles));
    }
    /** @param target 选择结果 @param bytes 该分区原始字节 @param partition 预期分区 @param first 起始编号 @param size 精确数量 */
    private void add(List<Evaluation.Case> target, byte[] bytes, String partition, int first, int size) {
        var values = json.decode(text(bytes), Evaluation.Case[].class);
        if (values.length != size) throw new IllegalArgumentException("PARTITION_SIZE_INVALID");
        for (int i = 0; i < values.length; i++) {
            var input = values[i];
            if (!input.id().equals("E%02d".formatted(first + i)) || !input.partition().equals(partition) || input.input().isBlank() || input.input().length() > 4000 || input.facts().size() > 32 || !"SYNTHETIC_TEST_DATA".equals(input.seed().get("source"))) throw new IllegalArgumentException("CASE_INVALID");
            target.add(input);
        }
    }
    /** @param bytes UTF8固定资源 @return 严格大小内文本；不访问环境配置或真实凭据 */
    private static String text(byte[] bytes) { if (bytes == null || bytes.length > 1024 * 1024) throw new IllegalArgumentException("FIXTURE_SIZE_INVALID"); return new String(bytes, StandardCharsets.UTF_8); }
}
