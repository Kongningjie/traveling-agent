package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.evaluation.Evaluation;
import com.lawrence.travel.application.evaluation.EvaluationRunner;
import com.lawrence.travel.infrastructure.evaluation.FixedEvaluationSuite;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 评测协议及数据冻结的确定性检查；不连接数据库、模型或任何HTTP。 */
class EvaluationRunnerTest {
    /** 独立回归保留R01身份，不冒充封存场景；未注册的R02在调用执行器前拒绝。 */
    @Test void supplementalRegressionKeepsItsOwnIdentity() {
        var calls = new AtomicInteger();
        var runner = new EvaluationRunner(executor(input -> {
            calls.incrementAndGet(); return new Evaluation.Observation("READY", Map.of("KNOWN", true), Map.of(), List.of());
        }));
        for (String id : List.of("R01", "R02")) {
            var input = new Evaluation.Case(id, "DEVELOPMENT", "CONTEXT", "本地补充回归", Map.of(), Map.of());
            var selected = new Evaluation.Suite("supplement-v1", "0".repeat(64), List.of(input), Map.of(id, new Evaluation.Oracle("READY", Set.of("KNOWN"))));
            if (id.equals("R01")) assertThat(runner.run(selected, true, Map.of()).rows().getFirst()).satisfies(row -> {
                assertThat(row.caseId()).isEqualTo("R01"); assertThat(row.status()).isEqualTo("PASS");
            });
            else assertThatThrownBy(() -> runner.run(selected, false, Map.of())).hasMessage("ORACLE_INVALID");
        }
        assertThat(calls).hasValue(1);
    }
    /** 默认选择8个开发样本，留出仅显式选择；原始输入和Oracle不混在执行端口里。 */
    @Test void partitionsAreExplicitAndLocked() {
        var loader = new FixedEvaluationSuite(EvaluationBaselineTest::resource); var dev = loader.load(false); var all = loader.load(true);
        assertThat(dev.cases()).hasSize(8); assertThat(dev.oracles()).hasSize(8); assertThat(all.cases()).hasSize(12); assertThat(all.cases().stream().filter(c -> c.partition().equals("HOLDOUT"))).hasSize(4); assertThat(dev.fingerprint()).isEqualTo(all.fingerprint());
    }
    /** 修改种子字节或锁文件都必须在执行前失败，不能自动重写预期以掩盖差异。 */
    @Test void mutatedSeedOrLockCannotRun() {
        for (String name : List.of("development.json", "holdout.json", "oracle.json", "suite-lock.json")) {
            var loader = new FixedEvaluationSuite(file -> { byte[] value = EvaluationBaselineTest.resource(file); if (file.equals(name)) value[0] ^= 1; return value; });
            assertThatThrownBy(() -> loader.load(false)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    /** 本地重复不制造真实模型重复成绩，缺失检查项即失败；未知真实指标不补零。 */
    @Test void repeatsAndExternalNotRunAreSeparated() {
        var count = new AtomicInteger(); var runner = new EvaluationRunner(executor(input -> { count.incrementAndGet(); return new Evaluation.Observation("READY", Map.of("KNOWN", true), Map.of("actualModelRequests", 0L), List.of("LOCAL_ONLY")); }));
        var report = runner.run(suite(), true, Map.of("coverage", "TEST_ONLY"));
        assertThat(count.get()).isEqualTo(3); assertThat(report.rows().stream().filter(r -> r.mode() == Evaluation.Mode.LOCAL_CONTRACT)).allMatch(r -> r.status().equals("PASS")); assertThat(report.rows().stream().filter(r -> r.mode() != Evaluation.Mode.LOCAL_CONTRACT)).hasSize(2).allMatch(r -> r.status().equals("NOT_RUN") && r.outcome() == null && r.metrics().isEmpty());
    }
    /** 无观察或模型自报一个结果都不能代替必需的独立程序检查。 */
    @Test void missingChecksAndWrongOutcomeFail() {
        var runner = new EvaluationRunner(executor(input -> new Evaluation.Observation("WRONG", Map.of(), Map.of(), List.of()))); var row = runner.run(suite(), false, Map.of()).rows().getFirst();
        assertThat(row.status()).isEqualTo("FAIL"); assertThat(row.failedChecks()).containsExactly("OUTCOME", "KNOWN");
    }
    /** 异常正文、原始供应商消息和堆栈不进入失败轨迹。 */
    @Test void failuresAreFixedCodesWithoutPrivateMessage() {
        var runner = new EvaluationRunner(executor(input -> { throw new IllegalStateException("PRIVATE_SYNTHETIC_FAILURE_TEXT"); })); var report = runner.run(suite(), false, Map.of());
        assertThat(report.rows().getFirst().status()).isEqualTo("FAIL"); assertThat(new TravelJson().encode(report)).doesNotContain("PRIVATE_SYNTHETIC_FAILURE_TEXT");
    }
    /** 外部模式的实现不能被误注册到本地执行入口，不存在隐式真实调用分支。 */
    @Test void nonlocalExecutorIsRejected() {
        var external = new Evaluation.Executor() {
            /** {@inheritDoc} 此对象仅测试模式错误，不发送请求。 */ @Override public Evaluation.Mode mode() { return Evaluation.Mode.REAL_MODEL; }
            /** {@inheritDoc} 被拒绝后绝不应进入。 */ @Override public Evaluation.Observation execute(Evaluation.Case input) { throw new AssertionError("MUST_NOT_CALL"); }
        }; assertThatThrownBy(() -> new EvaluationRunner(external)).hasMessage("LOCAL_EXECUTOR_REQUIRED");
    }
    /** 默认关闭的模型评测不进入适配器；显式布尔门禁只表达调用方授权，不推断人类授权。 */
    @Test void unauthorizedModelsAreNeverCalled() {
        var calls = new AtomicInteger(); var external = modelExecutor(input -> { calls.incrementAndGet(); throw new AssertionError("MUST_NOT_CALL"); });
        var report = EvaluationRunner.modelsOnly().runModels(suite(), external, false, Map.of());
        assertThat(calls).hasValue(0); assertThat(report.rows()).hasSize(1).allMatch(row -> row.status().equals("NOT_RUN") && row.metrics().isEmpty());
        assertThatThrownBy(() -> EvaluationRunner.modelsOnly().run(suite(), false, Map.of())).hasMessage("LOCAL_EXECUTOR_NOT_BOUND");
    }
    /** 首例额度／预算暂停保留实际计量，后续案例不再尝试，防止新任务绕过暂停。 */
    @Test void modelPauseStopsRemainingCases() {
        var calls = new AtomicInteger(); var one = suite(); var second = new Evaluation.Case("E02", "DEVELOPMENT", "TEST", "本地", Map.of(), Map.of());
        var two = new Evaluation.Suite(one.version(), one.fingerprint(), List.of(one.cases().getFirst(), second), Map.of("E01", one.oracles().get("E01"), "E02", one.oracles().get("E01")));
        var report = EvaluationRunner.modelsOnly().runModels(two, modelExecutor(input -> { calls.incrementAndGet(); return new Evaluation.Observation("PAUSED_QUOTA", Map.of(), Map.of("actualModelRequests", 1L), List.of("UNDERSTAND")); }), true, Map.of());
        assertThat(calls).hasValue(1); assertThat(report.rows().getFirst().status()).isEqualTo("NOT_RUN"); assertThat(report.rows().getFirst().metrics()).containsEntry("actualModelRequests", 1L);
        assertThat(report.rows().getLast().reason()).isEqualTo("PREVIOUS_CASE_PAUSED"); assertThat(report.rows().getLast().metrics()).isEmpty();
    }
    /** 模型裁判与本地裁判分别冻结；输入完全相同，任何新裁判字节变动也会被拒绝。 */
    @Test void modelRubricIsSeparateAndPinned() {
        var loader = new FixedEvaluationSuite(EvaluationBaselineTest::resource); var local = loader.load(true); var models = loader.loadModels(true);
        assertThat(models.cases()).isEqualTo(local.cases()); assertThat(models.oracles()).hasSize(12).isNotEqualTo(local.oracles()); assertThat(models.fingerprint()).isNotEqualTo(local.fingerprint());
        var mutated = new FixedEvaluationSuite(name -> { byte[] value = EvaluationBaselineTest.resource(name); if (name.equals("model-oracle.json")) value[0] ^= 1; return value; });
        assertThatThrownBy(() -> mutated.loadModels(false)).hasMessage("MODEL_ORACLE_CHANGED");
    }
    /** 必需检查、指标和轨迹不得通过原始异常或负计量伪造通过；坏预期在进入执行器前拒绝。 */
    @Test void malformedObservationsAndOracleAreRejected() {
        for (var observation : List.of(new Evaluation.Observation("PRIVATE BODY", Map.of(), Map.of(), List.of()), new Evaluation.Observation("READY", Map.of("KNOWN", true), Map.of("requests", -1L), List.of()), new Evaluation.Observation("READY", Map.of("KNOWN", true), Map.of(), List.of("raw body")))) {
            var row = new EvaluationRunner(executor(input -> observation)).run(suite(), false, Map.of()).rows().getFirst(); assertThat(row.status()).isEqualTo("FAIL"); assertThat(row.reason()).isEqualTo("EXECUTION_FAILED");
        }
        var one = suite(); var invalid = new Evaluation.Suite(one.version(), one.fingerprint(), one.cases(), Map.of("E01", new Evaluation.Oracle("private body", Set.of("KNOWN"))));
        assertThatThrownBy(() -> EvaluationRunner.modelsOnly().runModels(invalid, modelExecutor(input -> { throw new AssertionError("MUST_NOT_CALL"); }), true, Map.of())).hasMessage("ORACLE_INVALID");
    }
    /** 一个模型业务结果可允许等待澄清或等待接受；接受其中之一仍须满足所有程序检查。 */
    @Test void alternativeOutcomesStillRequireEveryCheck() {
        var one = suite(); var alternatives = new Evaluation.Suite(one.version(), one.fingerprint(), one.cases(), Map.of("E01", new Evaluation.Oracle("WAITING_USER|WAITING_ACCEPTANCE", Set.of("KNOWN"))));
        var report = EvaluationRunner.modelsOnly().runModels(alternatives, modelExecutor(input -> new Evaluation.Observation("WAITING_USER", Map.of("KNOWN", true), Map.of(), List.of())), true, Map.of());
        assertThat(report.rows().getFirst().status()).isEqualTo("PASS");
    }
    /** @param work 不发HTTP的受控观察 @return 仅用于验证真实层次协议的测试实现，绝不作为真实调用成绩 */
    private Evaluation.Executor modelExecutor(java.util.function.Function<Evaluation.Case, Evaluation.Observation> work) { return new Evaluation.Executor() {
        /** {@inheritDoc} 用于门禁单测的模式声明。 */ @Override public Evaluation.Mode mode() { return Evaluation.Mode.REAL_MODEL; }
        /** {@inheritDoc} 仅运行内存函数，不连接供应商。 */ @Override public Evaluation.Observation execute(Evaluation.Case input) { return work.apply(input); }
    }; }
    /** @param work 可控纯本地观察函数 @return 不包含Oracle的执行器 */
    private Evaluation.Executor executor(java.util.function.Function<Evaluation.Case, Evaluation.Observation> work) { return new Evaluation.Executor() {
        /** {@inheritDoc} 明确本地层次。 */ @Override public Evaluation.Mode mode() { return Evaluation.Mode.LOCAL_CONTRACT; }
        /** {@inheritDoc} 只传原始案例。 */ @Override public Evaluation.Observation execute(Evaluation.Case input) { return work.apply(input); }
    }; }
    /** @return 最小合法协议夹具；不是完整12例，也不写基线成绩 */
    private Evaluation.Suite suite() { var input = new Evaluation.Case("E01", "DEVELOPMENT", "TEST", "本地", Map.of(), Map.of()); return new Evaluation.Suite("unit-v1", "0".repeat(64), List.of(input), Map.of("E01", new Evaluation.Oracle("READY", Set.of("KNOWN")))); }
}
