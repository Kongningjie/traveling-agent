package com.lawrence.travel.application.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 有界固定评测；默认只运行本地契约，外部评测无授权或实现时逐项NOT_RUN，不制造质量分。 */
public final class EvaluationRunner {
    public static final String JUDGE_VERSION = "program-judge-v1";
    private final Evaluation.Executor local;
    /** @param local 明确的本地探针；此Runner没有真实外部执行适配器 */
    public EvaluationRunner(Evaluation.Executor local) {
        if (local == null || local.mode() != Evaluation.Mode.LOCAL_CONTRACT) throw new IllegalArgumentException("LOCAL_EXECUTOR_REQUIRED");
        this.local = local;
    }
    /** 仅允许通过modelsOnly构造没有本地回退的真实评测入口。 */
    private EvaluationRunner() { this.local = null; }
    /** @return 无本地回退的真实评测Runner；执行仍需runModels显式授权，构造不发请求 */
    public static EvaluationRunner modelsOnly() { return new EvaluationRunner(); }
    /** @param suite 已验证指纹的选择集 @param representatives 是否对4个固定代表场景重复3次；只作用于本地层次 @param configuration 无凭据的复现信息 @return 本地记录及每场景一条真实模型／工具未运行记录，不推断任何质量百分比 */
    public Evaluation.Report run(Evaluation.Suite suite, boolean representatives, Map<String, String> configuration) {
        if (local == null) throw new IllegalArgumentException("LOCAL_EXECUTOR_NOT_BOUND");
        requireSuite(suite);
        var rows = new ArrayList<Evaluation.Row>();
        for (var input : suite.cases()) {
            var oracle = suite.oracles().get(input.id());
            if (oracle == null || oracle.required().isEmpty()) throw new IllegalArgumentException("ORACLE_MISSING");
            int count = representatives && Set.of("E01", "E03", "E06", "E11", "P01", "P07", "P13", "P19").contains(input.id()) ? 3 : 1;
            for (int repeat = 1; repeat <= count; repeat++) rows.add(execute(input, oracle, repeat, local));
            // 无外部执行适配器，也没有从“继续开发”推断授权；真实结果明确缺失。
            for (var mode : List.of(Evaluation.Mode.REAL_MODEL, Evaluation.Mode.REAL_TOOL)) rows.add(new Evaluation.Row(input.id(), input.partition(), 1, mode, "NOT_RUN", null, List.of(), Map.of(), List.of(), "EXTERNAL_NOT_AUTHORIZED"));
        }
        return new Evaluation.Report(JUDGE_VERSION, suite.version(), suite.fingerprint(), Map.copyOf(configuration), List.copyOf(rows));
    }
    /** @param suite 固定选择集 @param executor 明确真实模型适配器 @param authorized 当前执行范围是否已由操作者明确授权 @param configuration 无凭据配置和审计引用 @return 每例先一次；此入口不自动重复、不调用动态地图／检索。未授权绝不进入执行器 */
    public Evaluation.Report runModels(Evaluation.Suite suite, Evaluation.Executor executor, boolean authorized, Map<String, String> configuration) {
        return runModels(suite, executor, authorized, 1, configuration);
    }
    /** @param suite 显式选择的开发或留出输入 @param executor 真实会话适配 @param authorized 本次范围授权
     * @param repetitions 每例独立运行1～3次，不把首轮额外重复计数 @param configuration 无凭据配置
     * @return 每次独立会话的记录；任何额度/预算暂停阻止其余重复及后续场景 */
    public Evaluation.Report runModels(Evaluation.Suite suite, Evaluation.Executor executor, boolean authorized, int repetitions, Map<String, String> configuration) {
        if (repetitions < 1 || repetitions > 3) throw new IllegalArgumentException("EVALUATION_REPETITIONS_INVALID");
        if (executor == null || executor.mode() != Evaluation.Mode.REAL_MODEL) throw new IllegalArgumentException("MODEL_EXECUTOR_REQUIRED");
        requireSuite(suite);
        var rows = new ArrayList<Evaluation.Row>();
        boolean paused = false;
        for (var input : suite.cases()) {
            for (int repetition = 1; repetition <= repetitions; repetition++) {
                if (!authorized) rows.add(new Evaluation.Row(input.id(), input.partition(), repetition, Evaluation.Mode.REAL_MODEL, "NOT_RUN", null, List.of(), Map.of(), List.of(), "EXTERNAL_NOT_AUTHORIZED"));
                else if (paused) rows.add(new Evaluation.Row(input.id(), input.partition(), repetition, Evaluation.Mode.REAL_MODEL, "NOT_RUN", null, List.of(), Map.of(), List.of(), "PREVIOUS_CASE_PAUSED"));
                else {
                    var row = execute(input, suite.oracles().get(input.id()), repetition, executor); rows.add(row);
                    paused = row.status().equals("NOT_RUN");
                }
            }
        }
        return new Evaluation.Report(JUDGE_VERSION, suite.version(), suite.fingerprint(), Map.copyOf(configuration), List.copyOf(rows));
    }

    /** @param suite 运行前验证；坏裁判或超限案例不得先发送任何真实请求 */
    private static void requireSuite(Evaluation.Suite suite) {
        if (suite == null || suite.cases().isEmpty() || suite.cases().size() > 24 || suite.cases().stream().map(Evaluation.Case::id).distinct().count() != suite.cases().size()) throw new IllegalArgumentException("SUITE_INVALID");
        for (var input : suite.cases()) {
            var oracle = suite.oracles().get(input.id());
            if (!input.id().matches("E(0[1-9]|1[0-2])|P(0[1-9]|1[0-9]|2[0-4])") || !Set.of("DEVELOPMENT", "HOLDOUT").contains(input.partition()) || oracle == null || oracle.outcome() == null || !oracle.outcome().matches("[A-Z][A-Z0-9_]{0,79}(\\|[A-Z][A-Z0-9_]{0,79}){0,7}") || oracle.required().isEmpty() || oracle.required().size() > 32 || oracle.required().stream().anyMatch(key -> !key.matches("[A-Z][A-Z0-9_]{0,79}"))) throw new IllegalArgumentException("ORACLE_INVALID");
        }
    }

    /** @param input 不含预期的输入 @param oracle 仅在执行后读取 @param repetition 序号 @param executor 已验证层次的执行器 @return 非模型自评的检查结果；异常不写原始正文 */
    private Evaluation.Row execute(Evaluation.Case input, Evaluation.Oracle oracle, int repetition, Evaluation.Executor executor) {
        try {
            var observation = executor.execute(input);
            if (observation == null || observation.outcome() == null || !observation.outcome().matches("[A-Z][A-Z0-9_]{0,79}") || observation.checks().size() > 32 || observation.metrics().size() > 32 || observation.trace().size() > 64
                    || observation.metrics().entrySet().stream().anyMatch(e -> !e.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,79}") || e.getValue() == null || e.getValue() < 0)
                    || observation.trace().stream().anyMatch(s -> !s.matches("[A-Z][A-Z0-9_]{0,79}"))) throw new IllegalArgumentException("OBSERVATION_INVALID");
            if (executor.mode() != Evaluation.Mode.LOCAL_CONTRACT && observation.outcome().startsWith("PAUSED_") && !observation.outcome().equals("PAUSED_EXPECTED")) return new Evaluation.Row(input.id(), input.partition(), repetition, executor.mode(), "NOT_RUN", observation.outcome(), List.of(), Map.copyOf(observation.metrics()), List.copyOf(observation.trace()), observation.outcome());
            var failed = new ArrayList<String>();
            if (!List.of(oracle.outcome().split("\\|")).contains(observation.outcome())) failed.add("OUTCOME");
            oracle.required().stream().sorted().filter(key -> !Boolean.TRUE.equals(observation.checks().get(key))).forEach(failed::add);
            return new Evaluation.Row(input.id(), input.partition(), repetition, executor.mode(), failed.isEmpty() ? "PASS" : "FAIL", observation.outcome(), List.copyOf(failed), Map.copyOf(observation.metrics()), List.copyOf(observation.trace()), null);
        } catch (RuntimeException failure) {
            return new Evaluation.Row(input.id(), input.partition(), repetition, executor.mode(), "FAIL", null, List.of("EXECUTION"), Map.of(), List.of("EXECUTION_FAILED"), "EXECUTION_FAILED");
        }
    }
}
