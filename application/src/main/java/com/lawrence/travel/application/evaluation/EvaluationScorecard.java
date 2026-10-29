package com.lawrence.travel.application.evaluation;

import com.lawrence.travel.application.artifact.ArtifactService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** 分开计算程序门禁、人工评分和完成状态；不执行模型、不鉴权、不发布成绩。 */
public final class EvaluationScorecard {
    public static final String RUBRIC_VERSION = "lf-quality-v1";
    public static final Set<String> PILOT_CASES = Set.of("P01", "P02", "R01", "R02", "R03");

    /** 工具类不可实例化。 */
    private EvaluationScorecard() {}

    /** 执行、门禁及最终结论的固定状态，PENDING不等于通过。 */
    public enum Status {
        /** 已满足本层判据，仍须结合执行模式。 */
        PASS,
        /** 本层存在已确认失败。 */
        FAIL,
        /** 缺少人工评审。 */
        PENDING,
        /** 实际未运行。 */
        NOT_RUN
    }

    /**
     * 来自可信运行记录的身份；不能由外部请求自行指定以延长到期或复用评审。
     *
     * @param batchId 同配置批次
     * @param itemRunId 本例独立运行
     * @param startedAt 原始运行开始时间
     * @param datasetHash 用例/裁判指纹
     * @param configurationHash 代码/模型/Prompt等配置指纹
     * @param executionEvidenceHash 全轮回复、工具与程序审计证据的整体指纹；无运行证据时可空
     */
    public record Context(
            UUID batchId,
            UUID itemRunId,
            Instant startedAt,
            String datasetHash,
            String configurationHash,
            String executionEvidenceHash) {
        /** 校验身份及指纹形状，不替代持久记录的归属验证。 */
        public Context {
            require(
                    batchId != null
                            && itemRunId != null
                            && startedAt != null
                            && startedAt.isBefore(Instant.MAX.minus(Duration.ofDays(30)))
                            && hash(datasetHash)
                            && hash(configurationHash)
                            && (executionEvidenceHash == null || hash(executionEvidenceHash)));
        }

        /**
         * 计算固定30天到期点；评审和重新导出不得重置。
         *
         * @return 原始开始时间加30天
         */
        public Instant expiresAt() {
            return startedAt.plus(Duration.ofDays(30));
        }
    }

    /**
     * 人工评分输入；回执仅为证据引用，不证明调用者是人工，也不授予写入权限。
     *
     * @param rowFingerprint 对应完整运行行及程序证据的指纹
     * @param evidenceHash 已确认人工记录的引用哈希，不能由代理伪造真实确认
     * @param rubricVersion 固定评分规则
     * @param conditions 条件保留0～2
     * @param facts 事实与引用0～2
     * @param clarification 澄清合理性0～2
     * @param usability 回复可用性0～2
     * @param taskCompleted 人工判断任务是否完成
     * @param majorViolation 是否有重大违规
     */
    public record Review(
            String rowFingerprint,
            String evidenceHash,
            String rubricVersion,
            int conditions,
            int facts,
            int clarification,
            int usability,
            boolean taskCompleted,
            boolean majorViolation) {
        /** 校验评分形状；真实评审身份与确认须由未来写入口单独验证。 */
        public Review {
            require(
                    hash(rowFingerprint)
                            && hash(evidenceHash)
                            && RUBRIC_VERSION.equals(rubricVersion));
            require(
                    conditions >= 0
                            && conditions <= 2
                            && facts >= 0
                            && facts <= 2
                            && clarification >= 0
                            && clarification <= 2
                            && usability >= 0
                            && usability <= 2);
        }
    }

    /**
     * 各层结果独立保留，不用总分抵消门禁失败。
     *
     * @param caseId 原场景
     * @param context 原始运行身份与指纹
     * @param mode 原执行模式
     * @param execution 原报告状态，含程序判据失败，不等于供应商调用状态
     * @param program 程序门禁
     * @param review 人工输入；null为待评
     * @param overall 综合结论，不改变原报告
     * @param failedChecks 缺失或失败的程序项
     */
    public record Assessment(
            String caseId,
            Context context,
            Evaluation.Mode mode,
            Status execution,
            Status program,
            Review review,
            Status overall,
            List<String> failedChecks) {
        /** 防止调用者修改检查项，或直接构造与门禁不一致的综合通过。 */
        public Assessment {
            require(
                    caseId != null
                            && PILOT_CASES.contains(caseId)
                            && context != null
                            && mode != null
                            && execution != null
                            && execution != Status.PENDING
                            && program != null
                            && overall != null
                            && failedChecks != null);
            if (execution == Status.NOT_RUN) {
                require(
                        program == Status.NOT_RUN
                                && review == null
                                && overall == Status.NOT_RUN
                                && failedChecks.isEmpty());
            } else {
                require(program == Status.PASS || program == Status.FAIL);
                require(
                        program != Status.PASS
                                || execution == Status.PASS && failedChecks.isEmpty());
                Status expected =
                        program == Status.FAIL
                                ? Status.FAIL
                                : review == null
                                        ? Status.PENDING
                                        : !review.taskCompleted() || review.majorViolation()
                                                ? Status.FAIL
                                                : Status.PASS;
                require(overall == expected);
            }
            failedChecks = List.copyOf(failedChecks);
        }
    }

    /**
     * 五例同模式的统计；零分母用null表示不可计算。
     *
     * @param mode 执行模式，不允许混算
     * @param planned 固定计划数5
     * @param completed 已执行数，包含程序失败
     * @param notRun 未运行数
     * @param pendingReview 已执行但未评审数
     * @param reviewed 实际收到评审的数量
     * @param succeeded 综合通过数量
     * @param coverage 已执行/计划
     * @param reviewedSuccessRate 通过/已评审；零分母为null
     * @param expiresAt 汇总取成员最早到期时间，不借汇总续期
     */
    public record Summary(
            Evaluation.Mode mode,
            int planned,
            int completed,
            int notRun,
            int pendingReview,
            int reviewed,
            int succeeded,
            double coverage,
            Double reviewedSuccessRate,
            Instant expiresAt) {}

    /**
     * 校验实际程序证据，人工记录必须绑定同一运行行和检查结果。
     *
     * @param context 原始身份与时间，来自可信运行记录
     * @param row 原执行器报告，不改写历史状态
     * @param oracle 原用例判据，不传给模型
     * @param checks 实际程序裁判返回的全部检查项；缺失拒绝评分，NOT_RUN必须为空
     * @param review 人工记录，可空；本方法不负责确认其身份来源
     * @param now 服务端当前时间；到期拒绝，不能使用客户端时间
     * @return 分层结论；本地契约结论不得改名为真实模型质量
     * @throws IllegalArgumentException 协议、模式或人工证据绑定无效
     */
    public static Assessment assess(
            Context context,
            Evaluation.Row row,
            Evaluation.Oracle oracle,
            Map<String, Boolean> checks,
            Review review,
            Instant now) {
        require(
                context != null
                        && now != null
                        && !now.isBefore(context.startedAt())
                        && now.isBefore(context.expiresAt()));
        require(
                row != null
                        && row.mode() != null
                        && PILOT_CASES.contains(row.caseId())
                        && row.repetition() == 1
                        && "DEVELOPMENT".equals(row.partition())
                        && oracle != null
                        && oracle.outcome() != null
                        && !oracle.outcome().isBlank()
                        && oracle.required() != null
                        && !oracle.required().isEmpty()
                        && checks != null);
        Status execution;
        try {
            execution = Status.valueOf(row.status());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("EVALUATION_SCORECARD_INVALID");
        }
        require(execution != Status.PENDING);
        if (execution == Status.NOT_RUN) {
            require(review == null && checks.isEmpty());
            return new Assessment(
                    row.caseId(),
                    context,
                    row.mode(),
                    execution,
                    Status.NOT_RUN,
                    null,
                    Status.NOT_RUN,
                    List.of());
        }
        require(
                checks.keySet().equals(oracle.required())
                        && checks.values().stream().noneMatch(java.util.Objects::isNull)
                        && hash(context.executionEvidenceHash()));
        var failed = new TreeSet<String>();
        checks.forEach(
                (name, value) -> {
                    if (!Boolean.TRUE.equals(value)) failed.add(name);
                });
        if (row.outcome() == null
                || !Arrays.asList(oracle.outcome().split("\\|", -1)).contains(row.outcome()))
            failed.add("OUTCOME");
        if (row.failedChecks() == null || !row.failedChecks().isEmpty())
            failed.add("REPORTED_FAILURE");
        Status program = execution == Status.PASS && failed.isEmpty() ? Status.PASS : Status.FAIL;
        if (review != null)
            require(review.rowFingerprint().equals(fingerprint(context, row, checks)));
        Status overall =
                program == Status.FAIL
                        ? Status.FAIL
                        : review == null
                                ? Status.PENDING
                                : !review.taskCompleted() || review.majorViolation()
                                        ? Status.FAIL
                                        : Status.PASS;
        return new Assessment(
                row.caseId(),
                context,
                row.mode(),
                execution,
                program,
                review,
                overall,
                List.copyOf(failed));
    }

    /**
     * 汇总固定五例，不能用删掉NOT_RUN或混入其他模式提高成功率。
     *
     * @param rows 同配置、同模式的五例结果
     * @param now 服务端当前时间，已到期的缓存结果也拒绝汇总
     * @return 覆盖率、待评数和已评成功比例，不计算可抵消硬失败的平均分
     * @throws IllegalArgumentException 场景缺失、重复或混合模式
     */
    public static Summary summarize(List<Assessment> rows, Instant now) {
        require(
                rows != null
                        && rows.size() == 5
                        && rows.stream().noneMatch(java.util.Objects::isNull)
                        && rows.stream()
                                .map(Assessment::caseId)
                                .collect(java.util.stream.Collectors.toSet())
                                .equals(PILOT_CASES)
                        && rows.stream().map(Assessment::mode).distinct().count() == 1
                        && rows.stream().map(r -> r.context().batchId()).distinct().count() == 1
                        && rows.stream()
                                        .map(r -> r.context().configurationHash())
                                        .distinct()
                                        .count()
                                == 1
                        && rows.stream().map(r -> r.context().datasetHash()).distinct().count() == 1
                        && rows.stream().map(r -> r.context().itemRunId()).distinct().count() == 5
                        && now != null
                        && rows.stream()
                                .allMatch(
                                        r ->
                                                !now.isBefore(r.context().startedAt())
                                                        && now.isBefore(r.context().expiresAt())));
        int notRun = (int) rows.stream().filter(r -> r.execution() == Status.NOT_RUN).count();
        int reviewed = (int) rows.stream().filter(r -> r.review() != null).count();
        int succeeded = (int) rows.stream().filter(r -> r.overall() == Status.PASS).count();
        return new Summary(
                rows.getFirst().mode(),
                5,
                5 - notRun,
                notRun,
                5 - notRun - reviewed,
                reviewed,
                succeeded,
                (5 - notRun) / 5.0,
                reviewed == 0 ? null : succeeded / (double) reviewed,
                rows.stream()
                        .map(r -> r.context().expiresAt())
                        .min(Instant::compareTo)
                        .orElseThrow());
    }

    /**
     * 对运行行和实际检查项生成稳定绑定指纹，重评不能沿用另一模式/轮次的回执。
     *
     * @param context 原始运行与配置，防止相同行内容跨运行复用回执
     * @param row 原始报告行
     * @param checks 实际检查结果
     * @return SHA256，不保存或返回原文
     */
    public static String fingerprint(
            Context context, Evaluation.Row row, Map<String, Boolean> checks) {
        require(
                context != null
                        && row != null
                        && row.mode() != null
                        && row.failedChecks() != null
                        && row.metrics() != null
                        && row.trace() != null
                        && checks != null);
        var fields = new LinkedHashMap<String, String>();
        fields.put("batch", context.batchId().toString());
        fields.put("run", context.itemRunId().toString());
        fields.put("startedAt", context.startedAt().toString());
        fields.put("dataset", context.datasetHash());
        fields.put("configuration", context.configurationHash());
        fields.put("evidence", context.executionEvidenceHash());
        fields.put("case", row.caseId());
        fields.put("partition", row.partition());
        fields.put("repetition", Integer.toString(row.repetition()));
        fields.put("mode", row.mode().name());
        fields.put("status", row.status());
        fields.put("outcome", row.outcome());
        fields.put("failed", sequence(new TreeSet<>(row.failedChecks())));
        var metrics = new java.util.ArrayList<String>();
        new TreeMap<>(row.metrics())
                .forEach(
                        (name, value) -> {
                            metrics.add(name);
                            metrics.add(value == null ? null : value.toString());
                        });
        fields.put("metrics", sequence(metrics));
        fields.put("trace", sequence(row.trace()));
        fields.put("reason", row.reason());
        var flags = new java.util.ArrayList<String>();
        new TreeMap<>(checks)
                .forEach(
                        (name, value) -> {
                            flags.add(name);
                            flags.add(value == null ? null : value.toString());
                        });
        fields.put("checks", sequence(flags));
        var canonical = new StringBuilder();
        fields.forEach((name, value) -> canonical.append(sequence(Arrays.asList(name, value))));
        return ArtifactService.hash(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 用长度前缀编码每个成员，区分逗号、括号、null和字符串“null”。
     *
     * @param values 已按协议确定顺序的成员
     * @return 无集合toString歧义的稳定编码
     */
    private static String sequence(Iterable<String> values) {
        var result = new StringBuilder();
        for (var value : values) {
            if (value == null) result.append("-1:");
            else result.append(value.length()).append(':').append(value);
        }
        return result.toString();
    }

    /**
     * 判断是否为明确哈希，不把任意正文当作证据引用。
     *
     * @param value 哈希
     * @return 是否64位小写十六进制
     */
    private static boolean hash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    /**
     * 统一拒绝非法协议，不回显输入。
     *
     * @param valid 是否满足契约
     */
    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("EVALUATION_SCORECARD_INVALID");
    }
}
