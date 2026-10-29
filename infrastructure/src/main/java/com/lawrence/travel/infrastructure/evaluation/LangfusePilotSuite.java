package com.lawrence.travel.infrastructure.evaluation;

import com.lawrence.travel.application.artifact.ArtifactService;
import com.lawrence.travel.application.evaluation.Evaluation;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/** 从既有合成资源生成五例接入集；不修改原24例或读取本地工作日志，不调用模型。 */
public final class LangfusePilotSuite {
    private static final String REQUIREMENTS_HASH =
            "ca39fbb33dc1d97fa873284705aa2c743464722e9b46448b91d9f69324a2827b";
    private static final String DOCUMENTS_HASH =
            "717d215f9c89cd918f01c16431ee9401634fed025172460961338fae7c930738";
    private static final String FILE_HASH =
            "ed0538b07aac0b8988959a84f38bad1bba7505da6536ee2d3ed6f62a990c7584";
    private final Function<String, byte[]> resources;
    private final TravelJson json = new TravelJson();

    /**
     * 绑定固定classpath资源，不接受用户文件路径。
     *
     * @param resources 仅供已知evaluation资源路径使用的读取器
     */
    public LangfusePilotSuite(Function<String, byte[]> resources) {
        this.resources = java.util.Objects.requireNonNull(resources);
    }

    /**
     * 生成P01/P02/R01/R02/R03输入和独立程序判据，保留原分区。
     *
     * @return 新的接入选择集指纹，不代表真实运行或模型通过
     * @throws IllegalArgumentException 源文件变化、缺失或输入范围不符
     */
    public Evaluation.Suite load() {
        var base = new PhaseTwoEvaluationSuite(name -> read("evaluation/v2/" + name)).load(false);
        var cases = new ArrayList<Evaluation.Case>();
        var oracles = new LinkedHashMap<String, Evaluation.Oracle>();
        for (var entry : base.cases()) {
            if (List.of("P01", "P02").contains(entry.id())) {
                cases.add(copy(entry));
                var oracle = base.oracles().get(entry.id());
                oracles.put(
                        entry.id(),
                        new Evaluation.Oracle(oracle.outcome(), Set.copyOf(oracle.required())));
            }
        }
        byte[] requirement = checked("evaluation/regressions/requirements.json", REQUIREMENTS_HASH);
        byte[] documents =
                checked("evaluation/regressions/document-references.json", DOCUMENTS_HASH);
        checked("evaluation/documents/trip1.txt", FILE_HASH);
        add(cases, requirement, List.of("R01"));
        add(cases, documents, List.of("R02", "R03"));
        oracles.put(
                "R01",
                new Evaluation.Oracle(
                        "WAITING_USER",
                        Set.of(
                                "OWNER_ISOLATED",
                                "NO_UNCONFIRMED_WRITE",
                                "NO_SECRET_OUTPUT",
                                "SCRIPT_COMPLETED",
                                "CONTEXT_BOUNDED",
                                "CURRENT_REQUIREMENTS_PRESERVED")));
        var documentOracle =
                new Evaluation.Oracle(
                        "COMPLETED",
                        Set.of(
                                "OWNER_ISOLATED",
                                "NO_UNCONFIRMED_WRITE",
                                "NO_SECRET_OUTPUT",
                                "SCRIPT_COMPLETED",
                                "SOURCE_VERSION_VERIFIED",
                                "DOCUMENT_NOT_OFFICIAL_FACT",
                                "DELETION_WATERMARK_CHECKED",
                                "AUDIT_BOUNDED",
                                "MESSAGE_DOCUMENT_BOUND"));
        oracles.put("R02", documentOracle);
        oracles.put("R03", documentOracle);
        if (!cases.stream()
                .map(Evaluation.Case::id)
                .toList()
                .equals(List.of("P01", "P02", "R01", "R02", "R03"))) invalid();
        var judge = new LinkedHashMap<String, Object>();
        oracles.forEach(
                (id, oracle) ->
                        judge.put(
                                id,
                                Map.of(
                                        "outcome",
                                        oracle.outcome(),
                                        "required",
                                        List.copyOf(new TreeSet<>(oracle.required())))));
        String canonical =
                json.encode(
                        Map.of(
                                "cases",
                                cases,
                                "oracles",
                                judge,
                                "base",
                                base.fingerprint(),
                                "requirements",
                                REQUIREMENTS_HASH,
                                "documents",
                                DOCUMENTS_HASH,
                                "file",
                                FILE_HASH));
        return new Evaluation.Suite(
                "langfuse-pilot-v1",
                ArtifactService.hash(canonical.getBytes(StandardCharsets.UTF_8)),
                List.copyOf(cases),
                Map.copyOf(oracles));
    }

    /**
     * 按已锁定顺序读取补充用例，不重定义原验收场景。
     *
     * @param target 选择集
     * @param bytes 已验证字节
     * @param ids 允许的精确ID顺序
     */
    private void add(List<Evaluation.Case> target, byte[] bytes, List<String> ids) {
        var entries =
                List.of(
                        json.decode(
                                new String(bytes, StandardCharsets.UTF_8),
                                Evaluation.Case[].class));
        if (!entries.stream().map(Evaluation.Case::id).toList().equals(ids)) invalid();
        entries.forEach(entry -> target.add(copy(entry)));
    }

    /**
     * 拷贝合成输入，禁止后续修改嵌套Map或改变分区。
     *
     * @param entry 既有合成用例
     * @return 只读输入，不附程序Oracle
     */
    private Evaluation.Case copy(Evaluation.Case entry) {
        if (!"DEVELOPMENT".equals(entry.partition())
                || !"SYNTHETIC_TEST_DATA".equals(entry.seed().get("source"))) invalid();
        return new Evaluation.Case(
                entry.id(),
                entry.partition(),
                entry.scenario(),
                entry.input(),
                Map.copyOf(entry.facts()),
                Map.copyOf(entry.seed()));
    }

    /**
     * 读取并验证原文件字节，不自动刷新预期哈希。
     *
     * @param name 固定classpath路径
     * @param expected 已锁定SHA256
     * @return 已验证字节
     */
    private byte[] checked(String name, String expected) {
        byte[] bytes = read(name);
        if (!ArtifactService.hash(bytes).equals(expected)) invalid();
        return bytes;
    }

    /**
     * 读取不超过1MiB的已知合成资源，不读取docs或附件卷。
     *
     * @param name 固定路径
     * @return 合成资源字节
     */
    private byte[] read(String name) {
        byte[] value = resources.apply(name);
        if (value == null || value.length > 1024 * 1024) invalid();
        return value;
    }

    /** 拒绝不符合已锁定选择的资料，不回显正文。 */
    private static void invalid() {
        throw new IllegalArgumentException("LANGFUSE_PILOT_SOURCE_INVALID");
    }
}
