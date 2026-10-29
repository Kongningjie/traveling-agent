package com.lawrence.travel.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lawrence.travel.application.evaluation.Evaluation;
import com.lawrence.travel.application.evaluation.EvaluationScorecard;
import com.lawrence.travel.application.evaluation.EvaluationScorecard.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 纯本地评分协议检查；人工分数仅合成测试输入，不代表用户已实际评分。 */
class EvaluationScorecardTest {
    private final Instant now = Instant.parse("2026-10-28T10:00:00Z");
    private final UUID batch = UUID.randomUUID();
    private final Evaluation.Oracle oracle =
            new Evaluation.Oracle("COMPLETED", Set.of("OWNER_ISOLATED"));

    /** 未运行保留NOT_RUN，未收到真实人工评审时不自动填分或通过。 */
    @Test
    void separatesNotRunAndPendingHumanReview() {
        var context = context();
        var notRun =
                EvaluationScorecard.assess(
                        context,
                        row("P01", "NOT_RUN", Evaluation.Mode.REAL_MODEL),
                        oracle,
                        Map.of(),
                        null,
                        now);
        assertEquals(Status.NOT_RUN, notRun.overall());
        assertNull(notRun.review());
        var pending =
                EvaluationScorecard.assess(
                        context,
                        row("P01", "PASS", Evaluation.Mode.LOCAL_CONTRACT),
                        oracle,
                        Map.of("OWNER_ISOLATED", true),
                        null,
                        now);
        assertEquals(Status.PASS, pending.program());
        assertEquals(Status.PENDING, pending.overall());
    }

    /** 人工高分不能抵消程序权限门禁失败。 */
    @Test
    void manualHighScoresCannotOverrideHardGate() {
        var context = context();
        var row = row("P01", "PASS", Evaluation.Mode.LOCAL_CONTRACT);
        var checks = Map.of("OWNER_ISOLATED", false);
        var result =
                EvaluationScorecard.assess(
                        context,
                        row,
                        oracle,
                        checks,
                        review(context, row, checks, true, false),
                        now);
        assertEquals(Status.FAIL, result.program());
        assertEquals(Status.FAIL, result.overall());
    }

    /** 缺少程序项、非法分值或把未运行项伪造成程序通过均拒绝。 */
    @Test
    void rejectsMissingChecksAndForgedOverallStatus() {
        var context = context();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        EvaluationScorecard.assess(
                                context,
                                row("P01", "PASS", Evaluation.Mode.LOCAL_CONTRACT),
                                oracle,
                                Map.of(),
                                null,
                                now));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new EvaluationScorecard.Review(
                                "1".repeat(64),
                                "2".repeat(64),
                                EvaluationScorecard.RUBRIC_VERSION,
                                3,
                                2,
                                2,
                                2,
                                true,
                                false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new EvaluationScorecard.Assessment(
                                "P01",
                                context,
                                Evaluation.Mode.REAL_MODEL,
                                Status.NOT_RUN,
                                Status.PASS,
                                null,
                                Status.PASS,
                                List.of()));
    }

    /** 相同行内容在另一独立运行、模式或配置中不能复用人工回执。 */
    @Test
    void bindsReviewToRunModeAndConfiguration() {
        var context = context();
        var row = row("P01", "PASS", Evaluation.Mode.LOCAL_CONTRACT);
        var checks = Map.of("OWNER_ISOLATED", true);
        var review = review(context, row, checks, true, false);
        assertThrows(
                IllegalArgumentException.class,
                () -> EvaluationScorecard.assess(context(), row, oracle, checks, review, now));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        EvaluationScorecard.assess(
                                context,
                                row("P01", "PASS", Evaluation.Mode.REAL_MODEL),
                                oracle,
                                checks,
                                review,
                                now));
        var changed =
                new EvaluationScorecard.Context(
                        batch,
                        context.itemRunId(),
                        now,
                        "a".repeat(64),
                        "c".repeat(64),
                        "e".repeat(64));
        assertThrows(
                IllegalArgumentException.class,
                () -> EvaluationScorecard.assess(changed, row, oracle, checks, review, now));
        var changedEvidence =
                new EvaluationScorecard.Context(
                        batch,
                        context.itemRunId(),
                        now,
                        "a".repeat(64),
                        "b".repeat(64),
                        "f".repeat(64));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        EvaluationScorecard.assess(
                                changedEvidence, row, oracle, checks, review, now));
    }

    /** 明确任务未完成或有重大违规，即使各项2分也不能通过。 */
    @Test
    void rejectsIncompleteTaskAndMajorViolation() {
        var context = context();
        var row = row("P01", "PASS", Evaluation.Mode.LOCAL_CONTRACT);
        var checks = Map.of("OWNER_ISOLATED", true);
        assertEquals(
                Status.FAIL,
                EvaluationScorecard.assess(
                                context,
                                row,
                                oracle,
                                checks,
                                review(context, row, checks, false, false),
                                now)
                        .overall());
        assertEquals(
                Status.FAIL,
                EvaluationScorecard.assess(
                                context,
                                row,
                                oracle,
                                checks,
                                review(context, row, checks, true, true),
                                now)
                        .overall());
    }

    /** 五例未运行时覆盖率为0，已评成功比例不可计算；不排除未运行项。 */
    @Test
    void reportsCoverageAndUndefinedSuccessRate() {
        var rows =
                List.of("P01", "P02", "R01", "R02", "R03").stream()
                        .map(
                                id ->
                                        EvaluationScorecard.assess(
                                                context(),
                                                row(id, "NOT_RUN", Evaluation.Mode.REAL_MODEL),
                                                oracle,
                                                Map.of(),
                                                null,
                                                now))
                        .toList();
        var summary = EvaluationScorecard.summarize(rows, now);
        assertEquals(5, summary.planned());
        assertEquals(5, summary.notRun());
        assertEquals(0, summary.coverage());
        assertNull(summary.reviewedSuccessRate());
        assertThrows(
                IllegalArgumentException.class,
                () -> EvaluationScorecard.summarize(rows.subList(0, 4), now));
    }

    /** 本地契约与真实模型结果不混算，重复使用同一运行身份也拒绝。 */
    @Test
    void rejectsMixedModesAndSharedRunIdentity() {
        var shared = context();
        var rows =
                List.of("P01", "P02", "R01", "R02", "R03").stream()
                        .map(
                                id ->
                                        EvaluationScorecard.assess(
                                                shared,
                                                row(id, "NOT_RUN", Evaluation.Mode.REAL_MODEL),
                                                oracle,
                                                Map.of(),
                                                null,
                                                now))
                        .toList();
        assertThrows(
                IllegalArgumentException.class, () -> EvaluationScorecard.summarize(rows, now));
        var unique =
                List.of("P01", "P02", "R01", "R02", "R03").stream()
                        .map(
                                id ->
                                        EvaluationScorecard.assess(
                                                context(),
                                                row(id, "NOT_RUN", Evaluation.Mode.REAL_MODEL),
                                                oracle,
                                                Map.of(),
                                                null,
                                                now))
                        .toList();
        var mixed = new java.util.ArrayList<>(unique);
        mixed.set(
                0,
                EvaluationScorecard.assess(
                        context(),
                        row("P01", "NOT_RUN", Evaluation.Mode.LOCAL_CONTRACT),
                        oracle,
                        Map.of(),
                        null,
                        now));
        assertThrows(
                IllegalArgumentException.class, () -> EvaluationScorecard.summarize(mixed, now));
    }

    /** 到期边界立即拒绝读取/汇总，重新评审不延长到期时间；不代表平台清理已实现。 */
    @Test
    void expiresFromOriginalStartWithoutRefresh() {
        var context = context();
        assertEquals(now.plus(Duration.ofDays(30)), context.expiresAt());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        EvaluationScorecard.assess(
                                context,
                                row("P01", "NOT_RUN", Evaluation.Mode.REAL_MODEL),
                                oracle,
                                Map.of(),
                                null,
                                context.expiresAt()));
        var rows =
                List.of("P01", "P02", "R01", "R02", "R03").stream()
                        .map(
                                id ->
                                        EvaluationScorecard.assess(
                                                context(),
                                                row(id, "NOT_RUN", Evaluation.Mode.REAL_MODEL),
                                                oracle,
                                                Map.of(),
                                                null,
                                                now))
                        .toList();
        assertThrows(
                IllegalArgumentException.class,
                () -> EvaluationScorecard.summarize(rows, context.expiresAt()));
    }

    /** 集合文本表示相同的不同轨迹不能复用评审；null也不能混同字符串null。 */
    @Test
    void fingerprintsDoNotUseAmbiguousCollectionStrings() {
        var context = context();
        var checks = Map.of("OWNER_ISOLATED", true);
        var one =
                new Evaluation.Row(
                        "P01",
                        "DEVELOPMENT",
                        1,
                        Evaluation.Mode.LOCAL_CONTRACT,
                        "PASS",
                        "COMPLETED",
                        List.of(),
                        Map.of(),
                        List.of("A, B"),
                        null);
        var two =
                new Evaluation.Row(
                        "P01",
                        "DEVELOPMENT",
                        1,
                        Evaluation.Mode.LOCAL_CONTRACT,
                        "PASS",
                        "COMPLETED",
                        List.of(),
                        Map.of(),
                        List.of("A", "B"),
                        null);
        var literal =
                new Evaluation.Row(
                        "P01",
                        "DEVELOPMENT",
                        1,
                        Evaluation.Mode.LOCAL_CONTRACT,
                        "PASS",
                        "COMPLETED",
                        List.of(),
                        Map.of(),
                        List.of("A, B"),
                        "null");
        var review = review(context, one, checks, true, false);
        assertThrows(
                IllegalArgumentException.class,
                () -> EvaluationScorecard.assess(context, two, oracle, checks, review, now));
        assertThrows(
                IllegalArgumentException.class,
                () -> EvaluationScorecard.assess(context, literal, oracle, checks, review, now));
    }

    /**
     * 构造同批次独立合成运行，指纹不对应任何真实评测报告。
     *
     * @return 测试身份
     */
    private EvaluationScorecard.Context context() {
        return new EvaluationScorecard.Context(
                batch, UUID.randomUUID(), now, "a".repeat(64), "b".repeat(64), "e".repeat(64));
    }

    /**
     * 构造原执行器形状的纯合成行。
     *
     * @param id 所选场景
     * @param status 合成状态
     * @param mode 明确模式
     * @return 测试行，不表示已执行模型
     */
    private Evaluation.Row row(String id, String status, Evaluation.Mode mode) {
        return new Evaluation.Row(
                id,
                "DEVELOPMENT",
                1,
                mode,
                status,
                status.equals("NOT_RUN") ? null : "COMPLETED",
                List.of(),
                Map.of(),
                List.of(),
                "");
    }

    /**
     * 构造测试专用评分；不是实际人工确认记录，不上传平台。
     *
     * @param context 合成运行
     * @param row 合成行
     * @param checks 合成程序证据
     * @param completed 合成完成标记
     * @param major 合成违规标记
     * @return 测试评分
     */
    private EvaluationScorecard.Review review(
            EvaluationScorecard.Context context,
            Evaluation.Row row,
            Map<String, Boolean> checks,
            boolean completed,
            boolean major) {
        return new EvaluationScorecard.Review(
                EvaluationScorecard.fingerprint(context, row, checks),
                "d".repeat(64),
                EvaluationScorecard.RUBRIC_VERSION,
                2,
                2,
                2,
                2,
                completed,
                major);
    }
}
