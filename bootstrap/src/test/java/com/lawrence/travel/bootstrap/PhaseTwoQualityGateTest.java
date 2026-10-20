package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.evaluation.Evaluation;
import com.lawrence.travel.application.evaluation.EvaluationRunner;
import com.lawrence.travel.application.evaluation.PhaseTwoQualityGate;
import com.lawrence.travel.infrastructure.evaluation.PhaseTwoEvaluationSuite;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 仅判定器边界夹具；REAL_MODEL枚举用于验证协议，不执行请求、不生成真实成绩报告。 */
class PhaseTwoQualityGateTest {
    /** 经独立门禁审计的P24零调用属于任务拒绝验收；没有证明时仍拒绝，不能当模型生成成功。 */
    @Test void verifiedNegativePathKeepsZeroActualUsage() {
        var base = report(Evaluation.Mode.REAL_MODEL); var rows = new ArrayList<>(base.rows());
        for (int i = 21; i < 24; i++) { var row = rows.get(i); rows.set(i, new Evaluation.Row(row.caseId(), row.partition(), row.repetition(), row.mode(), "PASS", "PAUSED_EXPECTED", List.of(), Map.of("actualModelRequests", 0L, "unknownModelRequests", 0L, "actualInputTokens", 0L, "actualOutputTokens", 0L, "verifiedAdmissionDenials", 1L), List.of("ADMISSION_DENIED"), null)); }
        assertThat(assess(new Evaluation.Report(base.version(), base.suiteVersion(), base.suiteHash(), base.configuration(), rows), reviews(24, false)).status()).isEqualTo("HOLDOUT_ACCEPTED");
        var row = rows.get(21); rows.set(21, new Evaluation.Row(row.caseId(), row.partition(), row.repetition(), row.mode(), "PASS", row.outcome(), List.of(), Map.of("actualModelRequests", 0L, "unknownModelRequests", 0L, "actualInputTokens", 0L, "actualOutputTokens", 0L), List.of(), null));
        assertThat(assess(new Evaluation.Report(base.version(), base.suiteVersion(), base.suiteHash(), base.configuration(), rows), reviews(24, false)).status()).isEqualTo("NOT_READY");
    }
    /** 22/24可过留出目标，21/24不可过；不将开发代表或一期M4一并宣称完成。 */
    @Test void fixedThresholdCannotBeReduced() {
        assertThat(assess(report(Evaluation.Mode.REAL_MODEL), reviews(22, false)).status()).isEqualTo("HOLDOUT_ACCEPTED");
        assertThat(assess(report(Evaluation.Mode.REAL_MODEL), reviews(21, false)).status()).isEqualTo("REJECTED");
    }
    /** 程序通过不能替代人工逐轮检查，缺审阅保持待验收。 */
    @Test void missingHumanEvidenceIsNotReady() { assertThat(assess(report(Evaluation.Mode.REAL_MODEL), List.of()).reason()).isEqualTo("HUMAN_REVIEW_INCOMPLETE"); }
    /** 本地替身即使24项都写PASS，仍不能被最终真实质量门禁接受。 */
    @Test void localResultsCannotBecomeRealQuality() { assertThat(assess(report(Evaluation.Mode.LOCAL_CONTRACT), reviews(24, false)).status()).isEqualTo("NOT_READY"); }
    /** 有重大安全问题时即使语义24/24也拒绝，不能用平均成功率掩盖。 */
    @Test void criticalViolationIsZeroTolerance() { assertThat(assess(report(Evaluation.Mode.REAL_MODEL), reviews(24, true)).reason()).isEqualTo("PROGRAM_OR_CRITICAL_FAILURE"); }
    /** 重复同一次运行不能充当另一轮独立会话。 */
    @Test void duplicateRunCannotFillMissingEvidence() {
        var base = report(Evaluation.Mode.REAL_MODEL); var rows = new ArrayList<>(base.rows()); rows.set(23, rows.getFirst());
        assertThat(assess(new Evaluation.Report(base.version(), base.suiteVersion(), base.suiteHash(), base.configuration(), rows), reviews(24, false)).status()).isEqualTo("NOT_READY");
    }
    /** 三次模型协议重复均独立进入执行器；当前测试只有内存函数，不落REAL_MODEL成绩。 */
    @Test void runnerKeepsThreeIndependentRepetitions() {
        var all = new PhaseTwoEvaluationSuite(PhaseTwoEvaluationTest::resource).load(true); var held = all.cases().stream().filter(c -> c.partition().equals("HOLDOUT")).toList();
        var suite = new Evaluation.Suite(all.version(), all.fingerprint(), held, all.oracles()); var calls = new java.util.concurrent.atomic.AtomicInteger();
        var executor = new Evaluation.Executor() {
            /** {@inheritDoc} 仅检验协议枚举，绝不发网络请求。 */
            @Override public Evaluation.Mode mode() { return Evaluation.Mode.REAL_MODEL; }
            /** {@inheritDoc} 故意缺少必要检查，不能形成质量通过。 */
            @Override public Evaluation.Observation execute(Evaluation.Case input) { calls.incrementAndGet(); return new Evaluation.Observation("COMPLETED", Map.of(), Map.of(), List.of()); }
        };
        var result = EvaluationRunner.modelsOnly().runModels(suite, executor, true, 3, Map.of("coverage", "UNIT_PROTOCOL_ONLY"));
        assertThat(calls).hasValue(24); assertThat(result.rows()).hasSize(24).allMatch(row -> row.status().equals("FAIL"));
        assertThat(result.rows().stream().map(row -> row.caseId() + ":" + row.repetition()).distinct()).hasSize(24);
    }
    /** @param report 内存协议夹具 @param reviews 内存审阅夹具 @return 固定封存指纹下的判定，不执行外部请求 */
    private static PhaseTwoQualityGate.Verdict assess(Evaluation.Report report, List<PhaseTwoQualityGate.HumanReview> reviews) { return PhaseTwoQualityGate.assess(report, reviews, PhaseTwoEvaluationSuite.LOCK_HASH); }
    /** 其他数据集的完整24行不能顶替已冻结场景。 */
    @Test void wrongSuiteCannotPass() {
        var base = report(Evaluation.Mode.REAL_MODEL);
        var wrong = new Evaluation.Report(base.version(), base.suiteVersion(), "0".repeat(64), base.configuration(), base.rows());
        assertThat(assess(wrong, reviews(24, false)).reason()).isEqualTo("FROZEN_SUITE_MISMATCH");
    }
    /** @param mode 协议枚举 @return 测试内存行，不代表真实执行 */
    private static Evaluation.Report report(Evaluation.Mode mode) {
        var rows = new ArrayList<Evaluation.Row>();
        for (String id : List.of("P05", "P06", "P11", "P12", "P17", "P18", "P23", "P24")) for (int repeat = 1; repeat <= 3; repeat++) rows.add(new Evaluation.Row(id, "HOLDOUT", repeat, mode, "PASS", "COMPLETED", List.of(), Map.of("actualModelRequests", 1L, "unknownModelRequests", 0L, "actualInputTokens", 1L, "actualOutputTokens", 1L), List.of(), null));
        return new Evaluation.Report(EvaluationRunner.JUDGE_VERSION, "phase2-session-suite-v1", PhaseTwoEvaluationSuite.LOCK_HASH, Map.of("coverage", "UNIT_ONLY"), rows);
    }
    /** @param passCount 边界数量 @param critical 单项重大违规 @return 内存人工记录夹具，不是真实审阅签字 */
    private static List<PhaseTwoQualityGate.HumanReview> reviews(int passCount, boolean critical) {
        var rows = report(Evaluation.Mode.REAL_MODEL).rows(); var result = new ArrayList<PhaseTwoQualityGate.HumanReview>();
        for (int i = 0; i < rows.size(); i++) { var row = rows.get(i); result.add(new PhaseTwoQualityGate.HumanReview(row.caseId(), row.repetition(), "UNIT_TEST_NOT_REAL_REVIEW", "0".repeat(64), i < passCount, critical && i == 0)); }
        return result;
    }
}
