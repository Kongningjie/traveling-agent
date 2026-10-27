package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.evaluation.Evaluation;
import com.lawrence.travel.application.evaluation.EvaluationRunner;
import com.lawrence.travel.infrastructure.evaluation.PhaseTwoEvaluationSuite;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 二期数据封存与评测器负例；仅验证协议，不将模拟观察计为模型质量或完整24场景。 */
class PhaseTwoEvaluationTest {
    /** 补充文件用例指纹不受集合迭代顺序影响，判据改变必须产生不同身份。 */
    @org.junit.jupiter.api.Test
    void documentReferenceFingerprintIncludesCanonicalOracle() {
        byte[] source = "合成补充输入".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var one =
                new com.lawrence.travel.application.evaluation.Evaluation.Oracle(
                        "COMPLETED", new java.util.LinkedHashSet<>(java.util.List.of("A", "B")));
        var reordered =
                new com.lawrence.travel.application.evaluation.Evaluation.Oracle(
                        "COMPLETED", new java.util.LinkedHashSet<>(java.util.List.of("B", "A")));
        var changed =
                new com.lawrence.travel.application.evaluation.Evaluation.Oracle(
                        "COMPLETED", java.util.Set.of("A"));
        org.assertj.core.api.Assertions.assertThat(
                        Dev35HttpAcceptanceTest.referenceSuiteFingerprint(source, one))
                .isEqualTo(Dev35HttpAcceptanceTest.referenceSuiteFingerprint(source, reordered))
                .isNotEqualTo(Dev35HttpAcceptanceTest.referenceSuiteFingerprint(source, changed));
    }

    /** 默认16开发；显式24场景四类各6、每类2留出，加载结构不消耗留出质量成绩。 */
    @Test
    void balancedLockedPartitions() {
        var loader = new PhaseTwoEvaluationSuite(PhaseTwoEvaluationTest::resource);
        assertThat(loader.load(false).cases()).hasSize(16);
        var all = loader.load(true);
        assertThat(all.cases()).hasSize(24);
        all.cases().stream()
                .map(Evaluation.Case::scenario)
                .distinct()
                .forEach(
                        category -> {
                            assertThat(
                                            all.cases().stream()
                                                    .filter(c -> c.scenario().equals(category)))
                                    .hasSize(6);
                            assertThat(
                                            all.cases().stream()
                                                    .filter(
                                                            c ->
                                                                    c.scenario().equals(category)
                                                                            && c.partition()
                                                                                    .equals(
                                                                                            "HOLDOUT")))
                                    .hasSize(2);
                        });
        assertThat(all.cases()).allSatisfy(c -> assertThat(c.facts()).containsKey("script"));
    }

    /** 输入/程序/人工裁判任何字节变化均拒绝，不自动修改封存指纹。 */
    @Test
    void everyFileIsPinned() {
        for (String file :
                List.of(
                        "suite-lock.json",
                        "development.json",
                        "holdout.json",
                        "oracle.json",
                        "manual-oracle.json")) {
            var loader =
                    new PhaseTwoEvaluationSuite(
                            name -> {
                                var value = resource(name);
                                if (name.equals(file)) value[0] ^= 1;
                                return value;
                            });
            assertThatThrownBy(() -> loader.load(false))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** 本地替身没有独立检查时失败；所有真实层次未运行，不能靠脚本读入就通过场景。 */
    @Test
    void localFixturesCannotInventQuality() {
        var local =
                new Evaluation.Executor() {
                    /** {@inheritDoc} 无供应商调用。 */
                    @Override
                    public Evaluation.Mode mode() {
                        return Evaluation.Mode.LOCAL_CONTRACT;
                    }

                    /** {@inheritDoc} 故意缺检查，证明runner不把固定结果当成绩。 */
                    @Override
                    public Evaluation.Observation execute(Evaluation.Case input) {
                        return new Evaluation.Observation(
                                "COMPLETED", Map.of(), Map.of(), List.of());
                    }
                };
        var report =
                new EvaluationRunner(local)
                        .run(
                                new PhaseTwoEvaluationSuite(PhaseTwoEvaluationTest::resource)
                                        .load(false),
                                false,
                                Map.of("humanReview", "NOT_RUN"));
        assertThat(report.rows().stream().filter(r -> r.mode() == Evaluation.Mode.LOCAL_CONTRACT))
                .hasSize(16)
                .allMatch(r -> r.status().equals("FAIL"));
        assertThat(report.rows().stream().filter(r -> r.mode() != Evaluation.Mode.LOCAL_CONTRACT))
                .hasSize(32)
                .allMatch(r -> r.status().equals("NOT_RUN") && r.metrics().isEmpty());
    }

    /**
     * @param name 固定二期资源名
     * @return 用例字节
     * @throws IllegalStateException 缺资源或IO失败，仅固定分类
     */
    static byte[] resource(String name) {
        try (var stream =
                PhaseTwoEvaluationTest.class.getResourceAsStream("/evaluation/v2/" + name)) {
            if (stream == null) throw new IllegalStateException("FIXTURE_MISSING");
            return stream.readAllBytes();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("FIXTURE_UNREADABLE");
        }
    }
}
