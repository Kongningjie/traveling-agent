package com.lawrence.travel.infrastructure.evaluation;

import com.lawrence.travel.application.artifact.ArtifactService;
import com.lawrence.travel.application.evaluation.Evaluation;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** 二期会话评测集加载器；输入脚本与程序/人工裁判分开封存，不修改一期数据。 */
public final class PhaseTwoEvaluationSuite {
    public static final String LOCK_HASH =
            "5f2939ef31ed81c1f66f5e5a0116e08e2d5a0596c9ea61fe6e0c66992b271093";
    private final Function<String, byte[]> resources;
    private final TravelJson json = new TravelJson();

    /**
     * @param resources 仅evaluation/v2固定资源读取器，不接受用户路径
     */
    public PhaseTwoEvaluationSuite(Function<String, byte[]> resources) {
        this.resources = resources;
    }

    /**
     * @param includeHoldout 显式包含8个留出；结构核验不能作为质量运行或Prompt调参
     * @return 16开发或完整24场景，执行器看不到程序及人工裁判
     */
    public Evaluation.Suite load(boolean includeHoldout) {
        byte[] lockBytes = read("suite-lock.json");
        if (!ArtifactService.hash(lockBytes).equals(LOCK_HASH))
            throw new IllegalArgumentException("PHASE2_LOCK_CHANGED");
        var lock = json.decode(text(lockBytes), FixedEvaluationSuite.Lock.class);
        if (!lock.files()
                .keySet()
                .equals(
                        Set.of(
                                "development.json",
                                "holdout.json",
                                "oracle.json",
                                "manual-oracle.json")))
            throw new IllegalArgumentException("PHASE2_FILES_INVALID");
        var bytes = new HashMap<String, byte[]>();
        lock.files()
                .forEach(
                        (name, expected) -> {
                            var value = read(name);
                            if (!ArtifactService.hash(value).equals(expected))
                                throw new IllegalArgumentException("PHASE2_FIXTURE_CHANGED");
                            bytes.put(name, value);
                        });
        var cases = new ArrayList<Evaluation.Case>();
        add(cases, bytes.get("development.json"), "DEVELOPMENT", 16);
        if (includeHoldout) add(cases, bytes.get("holdout.json"), "HOLDOUT", 8);
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = json.decode(text(bytes.get("oracle.json")), Map.class);
        var oracles = new HashMap<String, Evaluation.Oracle>();
        for (var c : cases)
            oracles.put(c.id(), json.decode(json.encode(raw.get(c.id())), Evaluation.Oracle.class));
        return new Evaluation.Suite(
                lock.version(), LOCK_HASH, List.copyOf(cases), Map.copyOf(oracles));
    }

    /**
     * @param target 所选输入
     * @param data 封存字节
     * @param partition 精确分区
     * @param count 固定数量
     */
    private void add(List<Evaluation.Case> target, byte[] data, String partition, int count) {
        var values = json.decode(text(data), Evaluation.Case[].class);
        if (values.length != count) throw new IllegalArgumentException("PHASE2_PARTITION_INVALID");
        for (var c : values) {
            if (!c.id().matches("P(0[1-9]|1[0-9]|2[0-4])")
                    || !partition.equals(c.partition())
                    || !Set.of("CONTEXT", "DOCUMENT", "WORKFLOW", "SAFETY_RECOVERY")
                            .contains(c.scenario())
                    || !"SYNTHETIC_TEST_DATA".equals(c.seed().get("source")))
                throw new IllegalArgumentException("PHASE2_CASE_INVALID");
            target.add(c);
        }
        for (String group : Set.of("CONTEXT", "DOCUMENT", "WORKFLOW", "SAFETY_RECOVERY"))
            if (List.of(values).stream().filter(c -> group.equals(c.scenario())).count()
                    != count / 4) throw new IllegalArgumentException("PHASE2_BALANCE_INVALID");
        if (target.stream().map(Evaluation.Case::id).distinct().count() != target.size())
            throw new IllegalArgumentException("PHASE2_ID_DUPLICATED");
    }

    /**
     * @param name 已知资源名
     * @return 有界字节，不访问模型及外部URL
     */
    private byte[] read(String name) {
        byte[] value = resources.apply(name);
        if (value == null || value.length > 1024 * 1024)
            throw new IllegalArgumentException("PHASE2_SIZE_INVALID");
        return value;
    }

    /**
     * @param bytes 固定UTF8字节
     * @return 解析文本
     */
    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
