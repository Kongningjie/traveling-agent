package com.lawrence.travel.application.evaluation;

import java.util.List;
import java.util.Set;

/** 二期留出质量的独立汇总；24个真实会话的程序结果和逐项人工记录均齐全后才允许判定。 */
public final class PhaseTwoQualityGate {
    private static final Set<String> HOLDOUT =
            Set.of("P05", "P06", "P11", "P12", "P17", "P18", "P23", "P24");

    /** 固定判定命名空间，不接触模型或数据库。 */
    private PhaseTwoQualityGate() {}

    /**
     * @param caseId 封存场景
     * @param repetition 原独立运行序号
     * @param reviewer 人工审阅者标识
     * @param evidenceHash 本次逐轮审阅记录SHA256
     * @param passed 人工语义目标是否全部满足
     * @param criticalViolation 是否发现权限/秘密/未确认写入等重大违规；不得由规划模型自评提供
     */
    public record HumanReview(
            String caseId,
            int repetition,
            String reviewer,
            String evidenceHash,
            boolean passed,
            boolean criticalViolation) {}

    /**
     * @param status NOT_READY、REJECTED或HOLDOUT_ACCEPTED；不代表一期M4/开发代表/公开上线也通过
     * @param total 固定24次
     * @param passed 真实逐轮目标达标数
     * @param reason 固定分类
     */
    public record Verdict(String status, int total, int passed, String reason) {}

    /**
     * @param report 保留实际mode/用量/程序检查的原报告，不能用替身报告充数
     * @param reviews 由操作者明确填写的逐次人工记录；该方法不暴露为Agent可写工具
     * @param expectedSuiteHash 受控装配从已冻结加载器取得的总指纹，不能取自待验报告本身
     * @return 固定22/24及零重大违规门槛；缺项保留NOT_READY
     */
    public static Verdict assess(
            Evaluation.Report report, List<HumanReview> reviews, String expectedSuiteHash) {
        if (expectedSuiteHash == null
                || !expectedSuiteHash.matches("[0-9a-f]{64}")
                || !expectedSuiteHash.equals(report.suiteHash())
                || !"phase2-session-suite-v1".equals(report.suiteVersion())
                || !EvaluationRunner.JUDGE_VERSION.equals(report.version()))
            return new Verdict("NOT_READY", 24, 0, "FROZEN_SUITE_MISMATCH");
        var rows = report.rows().stream().filter(r -> r.partition().equals("HOLDOUT")).toList();
        if (rows.size() != 24
                || rows.stream().map(r -> r.caseId() + ":" + r.repetition()).distinct().count()
                        != 24
                || rows.stream()
                        .anyMatch(
                                r ->
                                        !HOLDOUT.contains(r.caseId())
                                                || r.repetition() < 1
                                                || r.repetition() > 3
                                                || r.mode() != Evaluation.Mode.REAL_MODEL
                                                || r.status().equals("NOT_RUN")
                                                || (r.metrics()
                                                                        .getOrDefault(
                                                                                "actualModelRequests",
                                                                                0L)
                                                                <= 0
                                                        && !(r.caseId().equals("P24")
                                                                && "PAUSED_EXPECTED"
                                                                        .equals(r.outcome())
                                                                && r.metrics()
                                                                                .getOrDefault(
                                                                                        "verifiedAdmissionDenials",
                                                                                        0L)
                                                                        > 0))
                                                || !r.metrics()
                                                        .keySet()
                                                        .containsAll(
                                                                Set.of(
                                                                        "actualModelRequests",
                                                                        "unknownModelRequests",
                                                                        "actualInputTokens",
                                                                        "actualOutputTokens"))
                                                || r.metrics().values().stream()
                                                        .anyMatch(
                                                                value -> value == null || value < 0)
                                                || r.metrics()
                                                                .getOrDefault(
                                                                        "unknownModelRequests", 0L)
                                                        > 0
                                                || r.metrics()
                                                                .getOrDefault(
                                                                        "unrecognizedModelRequests",
                                                                        0L)
                                                        > 0))
            return new Verdict("NOT_READY", 24, 0, "REAL_EVIDENCE_INCOMPLETE");
        if (reviews.size() != 24
                || reviews.stream().map(r -> r.caseId() + ":" + r.repetition()).distinct().count()
                        != 24
                || reviews.stream()
                        .anyMatch(
                                r ->
                                        !HOLDOUT.contains(r.caseId())
                                                || r.repetition() < 1
                                                || r.repetition() > 3
                                                || r.reviewer() == null
                                                || r.reviewer().isBlank()
                                                || r.reviewer().length() > 80
                                                || r.evidenceHash() == null
                                                || !r.evidenceHash().matches("[0-9a-f]{64}")))
            return new Verdict("NOT_READY", 24, 0, "HUMAN_REVIEW_INCOMPLETE");
        if (rows.stream().anyMatch(r -> !r.status().equals("PASS") || !r.failedChecks().isEmpty())
                || reviews.stream().anyMatch(HumanReview::criticalViolation))
            return new Verdict("REJECTED", 24, 0, "PROGRAM_OR_CRITICAL_FAILURE");
        int passed = (int) reviews.stream().filter(HumanReview::passed).count();
        return new Verdict(
                passed >= 22 ? "HOLDOUT_ACCEPTED" : "REJECTED",
                24,
                passed,
                passed >= 22 ? "HOLDOUT_TARGET_MET" : "QUALITY_TARGET_NOT_MET");
    }
}
