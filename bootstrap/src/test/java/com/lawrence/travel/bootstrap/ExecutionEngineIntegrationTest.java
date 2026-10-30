package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.execution.AgentCallFailure;
import com.lawrence.travel.application.execution.RunService;
import com.lawrence.travel.application.execution.StageCommitCommand;
import com.lawrence.travel.application.execution.StageExecutor;
import com.lawrence.travel.application.execution.StageOutput;
import com.lawrence.travel.application.execution.StagePlan;
import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.StageCommitPort;
import com.lawrence.travel.application.port.StageHandler;
import com.lawrence.travel.application.port.TravelStore;
import com.lawrence.travel.application.travel.TravelStorageService;
import com.lawrence.travel.domain.execution.ExecutionRun;
import com.lawrence.travel.domain.execution.ExecutionStep;
import com.lawrence.travel.domain.execution.PlanningMode;
import com.lawrence.travel.domain.execution.StageType;
import com.lawrence.travel.domain.execution.control.CallKind;
import com.lawrence.travel.domain.execution.control.RunLimits;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.interaction.DecisionAnswer;
import com.lawrence.travel.domain.interaction.DecisionRequest;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.infrastructure.execution.BoundedExecutionResources;
import com.lawrence.travel.infrastructure.execution.PersistentStageWorker;
import com.lawrence.travel.infrastructure.execution.persistence.EngineMapper;
import com.lawrence.travel.infrastructure.execution.persistence.PostgresExecutionEngine;
import com.lawrence.travel.infrastructure.persistence.control.BudgetMapper;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import com.lawrence.travel.infrastructure.travel.TravelMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实本地 PostgreSQL 阶段事务、租约、并发与进程失联恢复；处理器均为本地替身，不调用供应商。
 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "travel.engine.enabled=false")
class ExecutionEngineIntegrationTest {
    /**
     * R2定向验证可绑定独立临时库；未指定时保持历史引擎测试的连接配置。
     *
     * @param registry 测试动态属性
     */
    @org.springframework.test.context.DynamicPropertySource
    static void isolatedModelPolicyDatabase(
            org.springframework.test.context.DynamicPropertyRegistry registry) {
        ModelPolicyDatabase.bind(registry);
    }

    /** R2计量用例仅在独立临时库运行，避免与开发中的后台Worker争抢任务。 */
    private void requireModelPolicyDatabase() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("TRAVEL_MODEL_POLICY_DATABASE") != null);
    }

    @Autowired private ExecutionEngineStore engine;
    @Autowired private EngineMapper mapper;
    @Autowired private TravelStore travel;
    @Autowired private TravelStorageService service;
    @Autowired private AccountStore accounts;
    @Autowired private BusinessTransactions transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TravelJson json;
    @Autowired private TravelMapper tasks;
    @Autowired private BudgetMapper budgets;
    @Autowired private BudgetLedger ledger;
    @Autowired private PlatformTransactionManager manager;
    private final List<UUID> users = new ArrayList<>();
    private final RunLimits limits =
            new RunLimits(30, 60, 2, Duration.ofSeconds(180), Duration.ofMinutes(15));

    /**
     * 只清理当前用例随机账号；先断开指针，再在事务中按外键删除自有测试记录。
     */
    @AfterEach
    void cleanOwnRecords() {
        transactions.required(
                () -> {
                    for (UUID user : users) {
                        var runIds =
                                jdbc.queryForList(
                                        "SELECT id FROM execution_run WHERE user_id=?",
                                        UUID.class,
                                        user);
                        jdbc.update(
                                "UPDATE travel_task SET current_run_id=NULL WHERE user_id=?", user);
                        for (String table :
                                List.of(
                                        "negotiation_card",
                                        "conversation_message",
                                        "decision_response",
                                        "decision_request",
                                        "run_event",
                                        "execution_start_request"))
                            jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
                        jdbc.update(
                                "DELETE FROM artifact_content WHERE artifact_id IN (SELECT id FROM artifact_record WHERE user_id=?)",
                                user);
                        for (String table :
                                List.of(
                                        "artifact_record",
                                        "step_attempt",
                                        "outbox_event",
                                        "execution_step",
                                        "execution_run"))
                            jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
                        for (UUID run : runIds) {
                            jdbc.update("DELETE FROM call_meter WHERE run_id=?", run);
                            jdbc.update("DELETE FROM execution_budget WHERE run_id=?", run);
                        }
                        for (String table :
                                List.of(
                                        "change_request",
                                        "travel_constraint_revision",
                                        "travel_task",
                                        "travel_idempotency"))
                            jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
                        jdbc.update("DELETE FROM app_user WHERE id=?", user);
                    }
                    return true;
                });
    }

    /**
     * @return 独立测试身份，不创建可登录密码
     */
    private UserIdentity actor() {
        UUID id = UUID.randomUUID();
        String name = "e_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        users.add(id);
        accounts.insert(id, name, "__NON_LOGIN_TEST_HASH__");
        return new UserIdentity(id, name);
    }

    /**
     * @return 未收集需求的初始正文，不伪造用户已确认偏好
     */
    private TravelStore.RevisionContent emptyContent() {
        return new TravelStore.RevisionContent(List.of(), List.of(), "用户新建", "");
    }

    /**
     * @param actor 认证主体
     * @return 独立私有任务
     */
    private TaskScope task(UserIdentity actor) {
        return service.create(actor, UUID.randomUUID().toString(), "引擎测试", emptyContent())
                .task()
                .scope();
    }

    /**
     * @param type 类型
     * @param round 轮次
     * @param branch 分支
     * @param dependencies 同运行前置
     * @return 新阶段
     */
    private StagePlan plan(StageType type, int round, String branch, UUID... dependencies) {
        return new StagePlan(
                UUID.randomUUID(),
                new ExecutionStep.Key(type, round, branch),
                type == StageType.PLAN ? PlanningMode.INITIAL : null,
                "{}",
                List.of(dependencies));
    }

    /**
     * @param scope 私有任务
     * @param first 初始阶段
     * @return 咨询目标，仅本地替身能够合法完成咨询
     */
    private ExecutionRun start(TaskScope scope, StagePlan first) {
        return engine.start(
                scope, UUID.randomUUID(), first, ExecutionEngineStore.Goal.CONSULTATION);
    }

    /**
     * @param run 运行
     * @param step 具体阶段
     * @return 当前待领提示
     */
    private ExecutionEngineStore.Hint hint(UUID run, UUID step) {
        return engine.candidates(100).stream()
                .filter(h -> h.runId().equals(run) && h.stepId().equals(step))
                .findFirst()
                .orElseThrow();
    }

    /**
     * @param run 运行
     * @param step 阶段
     * @return 真实事务领取资格
     */
    private ExecutionEngineStore.Claim claim(UUID run, UUID step) {
        return engine.claim(hint(run, step), "local_test").orElseThrow();
    }

    /**
     * @param next 后续阶段
     * @return 继续或纯咨询完成产物
     */
    private StageOutput output(StagePlan... next) {
        return new StageOutput(
                "本地测试结果",
                "{\"saved\":true}",
                List.of(next),
                next.length == 0
                        ? StageOutput.Outcome.CONSULTATION_COMPLETE
                        : StageOutput.Outcome.CONTINUE,
                null);
    }

    /**
     * @param run 运行
     * @param step 阶段
     * @return 领域状态
     */
    private ExecutionStep step(ExecutionRun run, UUID step) {
        return json.decode(mapper.step(run.scope(), run.id(), step).body(), ExecutionStep.class);
    }

    /**
     * @param claim 领取
     * @return 准备并提交的命令，调用方可以重复提交检查幂等
     */
    private StageCommitCommand complete(ExecutionEngineStore.Claim claim) {
        var command = engine.prepare(claim, output());
        assertThat(engine.commit(command)).isEqualTo(StageCommitPort.Result.COMMITTED);
        return command;
    }

    /**
     * @param claim 领取；只更改本例的到期时间，不等待实际六十秒
     */
    private void expire(ExecutionEngineStore.Claim claim) {
        jdbc.update(
                "UPDATE outbox_event SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",
                claim.fence().claim().eventId());
    }

    /**
     * @param run 运行；只提前本例已存在的重试事件到期时刻
     */
    private void due(UUID run) {
        jdbc.update(
                "UPDATE outbox_event SET available_at=clock_timestamp()-interval '1 second' WHERE run_id=? AND status='RETRY_WAIT'",
                run);
    }

    /**
     * @param mapper 执行 SQL 或委托故障替身
     * @return 新进程等价的无内存状态仓储
     */
    private ExecutionEngineStore fresh(EngineMapper mapper) {
        return new PostgresExecutionEngine(
                mapper, tasks, budgets, json, manager, limits, 2, 2, Duration.ofSeconds(60), 3);
    }

    /**
     * @param condition 状态断言
     * @throws InterruptedException 测试被取消；有界等待且不打印线程私有数据
     */
    private void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    /**
     * 同输入启动幂等；预算、状态和首事件不存在半提交窗口。
     */
    @Test
    void startIsAtomicAndIdempotent() {
        var actor = actor();
        var scope = task(actor);
        var first = plan(StageType.UNDERSTAND, 0, "main");
        UUID input = UUID.randomUUID();
        var run = engine.start(scope, input, first);
        assertThat(engine.start(scope, input, first)).isEqualTo(run);
        assertThatThrownBy(() -> engine.start(scope, input, plan(StageType.PLAN, 0, "main")))
                .hasMessage("IDEMPOTENCY_CONFLICT");
        assertThat(ledger.read(run.id()).modelCalls()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM outbox_event WHERE run_id=?",
                                Integer.class,
                                run.id()))
                .isEqualTo(1);
        var before = service.tasks(actor, 0, 100).size();
        new org.springframework.transaction.support.TransactionTemplate(manager)
                .executeWithoutResult(
                        status -> {
                            var created = task(actor);
                            engine.start(
                                    created,
                                    UUID.randomUUID(),
                                    plan(StageType.UNDERSTAND, 0, "rollback"));
                            status.setRollbackOnly();
                        });
        assertThat(service.tasks(actor, 0, 100)).hasSize(before);
    }

    /**
     * 同一提示八个线程竞争只有一个取得执行权；重复领取不增加尝试。
     * @throws Exception 线程任务失败
     */
    @Test
    void concurrentClaimHasOneWinner() throws Exception {
        var scope = task(actor());
        var first = plan(StageType.RESEARCH, 0, "one");
        var run = start(scope, first);
        var hint = hint(run.id(), first.id());
        var calls = new ArrayList<Callable<Boolean>>();
        for (int i = 0; i < 8; i++) {
            String owner = "worker_" + i;
            calls.add(() -> engine.claim(hint, owner).isPresent());
        }
        int won = 0;
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (var result : pool.invokeAll(calls)) if (result.get()) won++;
        }
        assertThat(won).isEqualTo(1);
        assertThat(step(run, first.id()).attemptCount()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM step_attempt WHERE run_id=?",
                                Integer.class,
                                run.id()))
                .isEqualTo(1);
    }

    /**
     * 准备产物不等于阶段完成；完整检查点、后继、可见事件和完成回执原子提交且可重放。
     */
    @Test
    void commitPersistsCheckpointAndCausalNextExactlyOnce() {
        var scope = task(actor());
        var first = plan(StageType.UNDERSTAND, 0, "main");
        var run = start(scope, first);
        var claim = claim(run.id(), first.id());
        var next = plan(StageType.RESEARCH, 0, "official", first.id());
        var command = engine.prepare(claim, output(next));
        assertThat(step(run, first.id()).state()).isEqualTo(ExecutionStep.State.RUNNING);
        assertThat(engine.commit(command)).isEqualTo(StageCommitPort.Result.COMMITTED);
        assertThat(engine.commit(command)).isEqualTo(StageCommitPort.Result.ALREADY_COMMITTED);
        assertThat(step(run, first.id()).state()).isEqualTo(ExecutionStep.State.COMPLETED);
        assertThat(fresh(mapper).run(scope, run.id()).state()).isEqualTo(ExecutionRun.State.QUEUED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM outbox_event WHERE run_id=?",
                                Integer.class,
                                run.id()))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT body->>'checkpoint' FROM artifact_content WHERE artifact_id=?",
                                String.class,
                                command.artifactId()))
                .isEqualTo("{\"saved\":true}");
        assertThat(
                        jdbc.queryForList(
                                "SELECT sequence FROM run_event WHERE task_id=? ORDER BY sequence",
                                Long.class,
                                scope.taskId()))
                .containsExactly(1L, 2L, 3L);
        complete(claim(run.id(), next.id()));
        assertThat(engine.run(scope, run.id()).state()).isEqualTo(ExecutionRun.State.SUCCEEDED);
    }

    /**
     * 事务 C 最后写进度失败会回滚检查点、下一事件和完成状态，已准备产物可用于安全重试。
     */
    @Test
    void failureAtEndOfCommitRollsBackEverything() {
        var first = plan(StageType.UNDERSTAND, 0, "main");
        var run = start(task(actor()), first);
        var claim = claim(run.id(), first.id());
        var command =
                engine.prepare(claim, output(plan(StageType.RESEARCH, 0, "next", first.id())));
        var broken =
                org.mockito.Mockito.mock(
                        EngineMapper.class, org.mockito.AdditionalAnswers.delegatesTo(mapper));
        org.mockito.Mockito.doThrow(
                        new org.springframework.dao.DataIntegrityViolationException(
                                "TEST_COMMIT_FAILURE"))
                .when(broken)
                .event(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString());
        assertThatThrownBy(() -> fresh(broken).commit(command))
                .hasMessage("DEPENDENCY_UNAVAILABLE");
        assertThat(step(run, first.id()).state()).isEqualTo(ExecutionStep.State.RUNNING);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM outbox_event WHERE run_id=?",
                                Integer.class,
                                run.id()))
                .isEqualTo(1);
        assertThat(mapper.step(run.scope(), run.id(), first.id()).artifactId()).isNull();
        assertThat(engine.commit(command)).isEqualTo(StageCommitPort.Result.COMMITTED);
    }

    /**
     * 租约已过期但未恢复时也拒绝迟到提交和续租；恢复复用原阶段但更换尝试与令牌。
     */
    @Test
    void expiredAuthorityCannotCommitBeforeOrAfterRecovery() {
        var first = plan(StageType.RESEARCH, 0, "main");
        var run = start(task(actor()), first);
        var old = claim(run.id(), first.id());
        ledger.reserve(
                run.id(),
                CallKind.MODEL,
                limits,
                new BudgetLedger.Target("local_fake", "local", "TEST"),
                10,
                Instant.now());
        var command = engine.prepare(old, output());
        expire(old);
        assertThat(engine.renew(old.fence())).isFalse();
        assertThat(engine.commit(command)).isEqualTo(StageCommitPort.Result.STALE);
        assertThat(fresh(mapper).recover(100)).isEqualTo(1);
        due(run.id());
        var next = claim(run.id(), first.id());
        assertThat(next.step().id()).isEqualTo(first.id());
        assertThat(next.fence().attemptId()).isNotEqualTo(old.fence().attemptId());
        assertThat(next.fence().claim().fencingToken())
                .isGreaterThan(old.fence().claim().fencingToken());
        assertThat(engine.commit(command)).isEqualTo(StageCommitPort.Result.STALE);
        assertThat(ledger.read(run.id()).modelCalls()).isEqualTo(1);
        complete(next);
    }

    /** 在 Java 检查通过后才注入到期，SQL 续租及完成比较仍须拒绝，不能留下检查点或后续请求。 */
    @Test
    void finalSqlClockComparisonRejectsExpiryAfterJavaCheck() {
        var first = plan(StageType.UNDERSTAND, 0, "renew");
        var run = start(task(actor()), first);
        var one = claim(run.id(), first.id());
        var broken =
                org.mockito.Mockito.mock(
                        EngineMapper.class, org.mockito.AdditionalAnswers.delegatesTo(mapper));
        org.mockito.Mockito.doAnswer(
                        call -> {
                            expire(one);
                            return mapper.renewEvent(call.getArgument(0), call.getArgument(1));
                        })
                .when(broken)
                .renewEvent(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(fresh(broken).renew(one.fence())).isFalse();
        var second = plan(StageType.UNDERSTAND, 0, "commit");
        var other = start(task(actor()), second);
        var two = claim(other.id(), second.id());
        var command = engine.prepare(two, output(plan(StageType.RESEARCH, 0, "next")));
        org.mockito.Mockito.doAnswer(
                        call -> {
                            expire(two);
                            return mapper.completeEvent(call.getArgument(0));
                        })
                .when(broken)
                .completeEvent(org.mockito.ArgumentMatchers.any());
        assertThat(fresh(broken).commit(command)).isEqualTo(StageCommitPort.Result.STALE);
        assertThat(step(other, second.id()).state()).isEqualTo(ExecutionStep.State.RUNNING);
        assertThat(mapper.step(other.scope(), other.id(), second.id()).artifactId()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM outbox_event WHERE run_id=?",
                                Integer.class,
                                other.id()))
                .isEqualTo(1);
    }

    /**
     * 必要阶段三次失败后 DEAD 与运行失败同事务，不留下无工作 RUNNING。
     */
    @Test
    void exhaustedRetriesFailTheRun() {
        var first = plan(StageType.RESEARCH, 0, "main");
        var run = start(task(actor()), first);
        for (int i = 0; i < 3; i++) {
            var claim = claim(run.id(), first.id());
            engine.fail(claim.fence(), ExecutionEngineStore.Failure.RETRYABLE);
            due(run.id());
        }
        assertThat(engine.run(run.scope(), run.id()).state()).isEqualTo(ExecutionRun.State.FAILED);
        assertThat(engine.run(run.scope(), run.id()).failureKind())
                .isEqualTo(ExecutionRun.FailureKind.RETRYABLE);
        assertThat(step(run, first.id()).attemptCount()).isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM outbox_event WHERE run_id=?",
                                String.class,
                                run.id()))
                .isEqualTo("DEAD");
    }

    /** 模型错误在真实PG中停止/暂停/有限重试，次数与预算一致且固定码不丢失。 */
    @Test
    void modelFailurePolicyKeepsCodesAndOriginalBudget() throws Exception {
        requireModelPolicyDatabase();
        var failures =
                List.of(
                        new AgentCallFailure(AgentCallFailure.Code.MODEL_OUTPUT_SCHEMA),
                        new AgentCallFailure(AgentCallFailure.Code.MODEL_ACCESS_DENIED),
                        new AgentCallFailure(AgentCallFailure.Code.MODEL_TIMEOUT),
                        new AgentCallFailure("MODEL_RATE_LIMITED", false),
                        new AgentCallFailure("MODEL_RATE_LIMITED", true),
                        new AgentCallFailure("MODEL_PROVIDER_HTTP_503", true),
                        new AgentCallFailure(AgentCallFailure.Code.MODEL_CONTEXT_LIMIT));
        for (var failure : failures) {
            var first = plan(StageType.RESEARCH, 0, "model_policy");
            var run = start(task(actor()), first);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var handler = org.mockito.Mockito.mock(StageHandler.class);
            org.mockito.Mockito.when(handler.supports(StageType.RESEARCH)).thenReturn(true);
            org.mockito.Mockito.when(handler.execute(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(
                            call -> {
                                calls.incrementAndGet();
                                var reservation =
                                        ledger.reserve(
                                                run.id(),
                                                CallKind.MODEL,
                                                limits,
                                                new BudgetLedger.Target("fake", "local", "TEST"),
                                                10,
                                                Instant.now());
                                ledger.finish(
                                        reservation.id(),
                                        failure.knownZeroUsage()
                                                ? new com.lawrence.travel.domain.execution.control
                                                        .TokenUsage(0, 0)
                                                : failure.recovery()
                                                                == AgentCallFailure.Recovery.STOP
                                                        ? new com.lawrence.travel.domain.execution
                                                                .control.TokenUsage(2, 1)
                                                        : null,
                                        Duration.ofMillis(1));
                                throw failure;
                            });
            var executor = new StageExecutor(engine, List.of(handler));
            int expected = failure.recovery() == AgentCallFailure.Recovery.RETRY ? 3 : 1;
            for (int i = 0; i < expected; i++) {
                executor.execute(claim(run.id(), first.id()), localControl());
                if (failure.recovery() == AgentCallFailure.Recovery.RETRY) due(run.id());
            }
            var state =
                    switch (failure.recovery()) {
                        case STOP, RETRY -> ExecutionRun.State.FAILED;
                        case PAUSE -> ExecutionRun.State.PAUSED_QUOTA;
                        case PAUSE_BUDGET -> ExecutionRun.State.PAUSED_BUDGET;
                    };
            assertThat(engine.run(run.scope(), run.id()).state()).isEqualTo(state);
            assertThat(calls.get()).isEqualTo(expected);
            assertThat(step(run, first.id()).attemptCount()).isEqualTo(expected);
            assertThat(ledger.read(run.id()).modelCalls()).isEqualTo(expected);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT last_error_code FROM outbox_event WHERE run_id=?",
                                    String.class,
                                    run.id()))
                    .isEqualTo(failure.diagnosticCode());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM execution_run WHERE task_id=?",
                                    Integer.class,
                                    run.scope().taskId()))
                    .isEqualTo(1);
            assertThat(engine.candidates(100).stream().noneMatch(h -> h.runId().equals(run.id())))
                    .isTrue();
        }
    }

    /** 暂时错误可在原运行内成功重试，已失败请求仍计入次数，没有重置预算。 */
    @Test
    void transientModelFailureCanSucceedOnSecondAttempt() throws Exception {
        requireModelPolicyDatabase();
        var first = plan(StageType.RESEARCH, 0, "model_recovers");
        var run = start(task(actor()), first);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var handler = org.mockito.Mockito.mock(StageHandler.class);
        org.mockito.Mockito.when(handler.supports(StageType.RESEARCH)).thenReturn(true);
        org.mockito.Mockito.when(handler.execute(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        call -> {
                            int count = calls.incrementAndGet();
                            var reservation =
                                    ledger.reserve(
                                            run.id(),
                                            CallKind.MODEL,
                                            limits,
                                            new BudgetLedger.Target("fake", "local", "TEST"),
                                            10,
                                            Instant.now());
                            ledger.finish(
                                    reservation.id(),
                                    new com.lawrence.travel.domain.execution.control.TokenUsage(
                                            0, 0),
                                    Duration.ZERO);
                            if (count == 1) throw new AgentCallFailure("MODEL_RATE_LIMITED", true);
                            return output();
                        });
        var executor = new StageExecutor(engine, List.of(handler));
        executor.execute(claim(run.id(), first.id()), localControl());
        assertThat(engine.run(run.scope(), run.id()).state()).isEqualTo(ExecutionRun.State.QUEUED);
        assertThat(step(run, first.id()).state()).isEqualTo(ExecutionStep.State.RETRY_WAIT);
        due(run.id());
        executor.execute(claim(run.id(), first.id()), localControl());
        assertThat(engine.run(run.scope(), run.id()).state())
                .isEqualTo(ExecutionRun.State.SUCCEEDED);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(ledger.read(run.id()).modelCalls()).isEqualTo(2);
    }

    /** 用户取消后模型迟到报错不能把终态改成失败或暂停。 */
    @Test
    void lateModelFailureCannotOverwriteCancellation() {
        requireModelPolicyDatabase();
        var first = plan(StageType.RESEARCH, 0, "model_late");
        var run = start(task(actor()), first);
        var claim = claim(run.id(), first.id());
        engine.cancel(run.scope(), run.id());
        String before =
                jdbc.queryForObject(
                        "SELECT last_error_code FROM outbox_event WHERE run_id=?",
                        String.class,
                        run.id());
        engine.failModel(
                claim.fence(), new AgentCallFailure(AgentCallFailure.Code.MODEL_OUTPUT_SCHEMA));
        assertThat(engine.run(run.scope(), run.id()).state())
                .isEqualTo(ExecutionRun.State.CANCELLED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT last_error_code FROM outbox_event WHERE run_id=?",
                                String.class,
                                run.id()))
                .isEqualTo(before);
    }

    /**
     * 提供不模拟中断的本地执行资格，实际提交仍由数据库围栏裁定。
     *
     * @return 本地单线程执行控制
     */
    private StageExecutor.Control localControl() {
        return new StageExecutor.Control() {
            /** {@inheritDoc} 本测试没有并发取消。 */
            @Override
            public boolean enter() {
                return true;
            }

            /** {@inheritDoc} 不持有外部资源。 */
            @Override
            public void leave() {}

            /** {@inheritDoc} 取消围栏由独立用例覆盖。 */
            @Override
            public boolean cancelled() {
                return false;
            }
        };
    }

    /**
     * 全局同时两个活动运行；同运行最多两个调查分支，容量不够保持可领取而不消耗尝试。
     */
    @Test
    void databaseLimitsRunsAndParallelResearch() {
        var first = plan(StageType.UNDERSTAND, 0, "main");
        var run = start(task(actor()), first);
        var root = claim(run.id(), first.id());
        var a = plan(StageType.RESEARCH, 0, "a", first.id());
        var b = plan(StageType.RESEARCH, 0, "b", first.id());
        var c = plan(StageType.RESEARCH, 0, "c", first.id());
        engine.commit(engine.prepare(root, output(a, b, c)));
        claim(run.id(), a.id());
        claim(run.id(), b.id());
        assertThat(engine.claim(hint(run.id(), c.id()), "worker_three")).isEmpty();
        var second = plan(StageType.UNDERSTAND, 0, "other");
        var secondRun = start(task(actor()), second);
        claim(secondRun.id(), second.id());
        var third = plan(StageType.UNDERSTAND, 0, "third");
        var thirdRun = start(task(actor()), third);
        assertThat(engine.claim(hint(thirdRun.id(), third.id()), "worker_four")).isEmpty();
        assertThat(step(thirdRun, third.id()).attemptCount()).isZero();
    }

    /**
     * 并行分支可以各自完成而不冒充运行成功；汇合阶段只在全部同运行依赖完成后领取。
     */
    @Test
    void parallelBranchesJoinOnlyAfterAllDependenciesComplete() {
        var first = plan(StageType.UNDERSTAND, 0, "main");
        var run = start(task(actor()), first);
        var root = claim(run.id(), first.id());
        var a = plan(StageType.RESEARCH, 0, "a");
        var b = plan(StageType.RESEARCH, 0, "b");
        var join = plan(StageType.UNDERSTAND, 1, "join", a.id(), b.id());
        engine.commit(engine.prepare(root, output(a, b, join)));
        var one = claim(run.id(), a.id());
        var two = claim(run.id(), b.id());
        assertThat(engine.claim(hint(run.id(), join.id()), "join_worker")).isEmpty();
        var branch =
                new StageOutput(
                        "已保存调查结果", "{}", List.of(), StageOutput.Outcome.BRANCH_COMPLETE, null);
        engine.commit(engine.prepare(one, branch));
        assertThat(engine.run(run.scope(), run.id()).state()).isEqualTo(ExecutionRun.State.RUNNING);
        engine.commit(engine.prepare(two, branch));
        assertThat(engine.run(run.scope(), run.id()).state()).isEqualTo(ExecutionRun.State.QUEUED);
        complete(claim(run.id(), join.id()));
    }

    /**
     * 新修改与取代处于同一事务，旧候选提交被封锁；取消重放不再提升代次。
     */
    @Test
    void changesAndCancellationFenceLateResults() {
        var actor = actor();
        var scope = task(actor);
        var first = plan(StageType.UNDERSTAND, 0, "main");
        var run = start(scope, first);
        var claim = claim(run.id(), first.id());
        var command = engine.prepare(claim, output());
        service.registerChange(
                actor,
                scope.taskId(),
                "change",
                travel.task(scope).lockVersion(),
                UUID.randomUUID(),
                1);
        assertThat(engine.run(scope, run.id()).state()).isEqualTo(ExecutionRun.State.SUPERSEDED);
        assertThat(engine.commit(command)).isEqualTo(StageCommitPort.Result.STALE);
        var nextPlan = plan(StageType.UNDERSTAND, 0, "new");
        var next = start(scope, nextPlan);
        var cancelled = engine.cancel(scope, next.id());
        long generation = travel.task(scope).task().executionGeneration();
        assertThat(engine.cancel(scope, next.id())).isEqualTo(cancelled);
        assertThat(travel.task(scope).task().executionGeneration()).isEqualTo(generation);
    }

    /**
     * 输入保存后失效旧执行失败时，输入身份、代次和旧运行全部回滚，不能出现半提交。
     */
    @Test
    void inputAndInvalidationShareOneTransaction() {
        var actor = actor();
        var scope = task(actor);
        var first = plan(StageType.UNDERSTAND, 0, "main");
        var run = start(scope, first);
        claim(run.id(), first.id());
        var before = travel.task(scope);
        var broken =
                new TravelStorageService(
                        travel,
                        transactions,
                        changed -> {
                            engine.supersede(changed);
                            throw new com.lawrence.travel.application.travel.TravelStorageFailure(
                                    com.lawrence.travel.application.travel.TravelStorageFailure.Code
                                            .DEPENDENCY_UNAVAILABLE);
                        });
        assertThatThrownBy(
                        () ->
                                broken.registerChange(
                                        actor,
                                        scope.taskId(),
                                        "rolled-input",
                                        before.lockVersion(),
                                        UUID.randomUUID(),
                                        1))
                .hasMessage("DEPENDENCY_UNAVAILABLE");
        assertThat(travel.task(scope)).isEqualTo(before);
        assertThat(engine.run(scope, run.id()).state()).isEqualTo(ExecutionRun.State.RUNNING);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM change_request WHERE task_id=?",
                                Integer.class,
                                scope.taskId()))
                .isZero();
    }

    /**
     * 第三次失联恢复直接结束必要分支，不能绕过总尝试上限再次领取。
     */
    @Test
    void repeatedLeaseLossReachesDeadLetterState() {
        var first = plan(StageType.RESEARCH, 0, "main");
        var run = start(task(actor()), first);
        for (int i = 0; i < 3; i++) {
            var attempt = claim(run.id(), first.id());
            expire(attempt);
            engine.recover(100);
            due(run.id());
        }
        assertThat(engine.run(run.scope(), run.id()).state()).isEqualTo(ExecutionRun.State.FAILED);
        assertThat(step(run, first.id()).state()).isEqualTo(ExecutionStep.State.FAILED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM outbox_event WHERE run_id=?",
                                String.class,
                                run.id()))
                .isEqualTo("DEAD");
    }

    /**
     * 显式继续保留旧取消终态和预算，两个并发请求只建立一个直接后继。
     * @throws Exception 线程任务失败
     */
    @Test
    void continuationCreatesOneSuccessor() throws Exception {
        var first = plan(StageType.UNDERSTAND, 0, "main");
        var run = start(task(actor()), first);
        claim(run.id(), first.id());
        ledger.reserve(
                run.id(),
                CallKind.TOOL,
                limits,
                new BudgetLedger.Target("fake", "local", "TEST"),
                0,
                Instant.now());
        engine.cancel(run.scope(), run.id());
        var next = plan(StageType.UNDERSTAND, 0, "again");
        var requests = new ArrayList<Callable<ExecutionRun>>();
        for (int i = 0; i < 4; i++)
            requests.add(() -> engine.continueRun(run.scope(), run.id(), UUID.randomUUID(), next));
        try (var pool = Executors.newFixedThreadPool(4)) {
            var results = pool.invokeAll(requests);
            var successor = results.getFirst().get();
            for (var result : results) assertThat(result.get().id()).isEqualTo(successor.id());
            assertThat(successor.continuedFromRunId()).isEqualTo(run.id());
            assertThat(successor.budget().toolCalls()).isZero();
        }
        assertThat(engine.run(run.scope(), run.id()).state())
                .isEqualTo(ExecutionRun.State.CANCELLED);
        assertThat(ledger.read(run.id()).toolCalls()).isEqualTo(1);
    }

    /**
     * 部分回答保留等待，全部确认后写唯一继续事件并沿用预算；未明确回答不会自动推进。
     */
    @Test
    void waitAndPartialAnswersResumeOriginalBudget() {
        var first = plan(StageType.UNDERSTAND, 0, "main");
        var run = start(task(actor()), first);
        var claim = claim(run.id(), first.id());
        UUID q1 = UUID.randomUUID();
        UUID q2 = UUID.randomUUID();
        var questions =
                Map.of(
                        q1,
                        new DecisionRequest.Question(q1, "节奏？", Map.of(), null),
                        q2,
                        new DecisionRequest.Question(q2, "交通？", Map.of(), null));
        var decision =
                new DecisionRequest(
                        UUID.randomUUID(),
                        run.scope(),
                        run.id(),
                        run.generation(),
                        1,
                        1,
                        questions,
                        true,
                        Set.of(),
                        DecisionRequest.State.OPEN);
        ledger.reserve(
                run.id(),
                CallKind.MODEL,
                limits,
                new BudgetLedger.Target("fake", "local", "TEST"),
                1,
                Instant.now());
        engine.commit(
                engine.prepare(
                        claim,
                        new StageOutput(
                                "请确认", "{}", List.of(), StageOutput.Outcome.WAIT_USER, decision)));
        assertThat(engine.run(run.scope(), run.id()).state())
                .isEqualTo(ExecutionRun.State.WAITING_USER);
        var next = plan(StageType.UNDERSTAND, 1, "answer", first.id());
        var a1 =
                new DecisionAnswer(
                        UUID.randomUUID(),
                        decision.id(),
                        1,
                        1,
                        DecisionAnswer.Kind.ANSWER,
                        Map.of(),
                        "宽松",
                        Set.of(),
                        DecisionAnswer.State.INTERPRETED);
        assertThat(
                        engine.resume(run.scope(), run.id(), a1, Set.of(q1), next, emptyContent())
                                .state())
                .isEqualTo(ExecutionRun.State.WAITING_USER);
        assertThat(travel.task(run.scope()).task().constraintRevision()).isEqualTo(2);
        assertThat(engine.run(run.scope(), run.id()).constraintRevision()).isEqualTo(2);
        assertThat(engine.run(run.scope(), run.id()).generation()).isEqualTo(run.generation());
        var a2 =
                new DecisionAnswer(
                        UUID.randomUUID(),
                        decision.id(),
                        2,
                        2,
                        DecisionAnswer.Kind.ANSWER,
                        Map.of(),
                        "公共交通",
                        Set.of(),
                        DecisionAnswer.State.INTERPRETED);
        assertThat(engine.resume(run.scope(), run.id(), a2, Set.of(q2), next).state())
                .isEqualTo(ExecutionRun.State.QUEUED);
        engine.resume(run.scope(), run.id(), a2, Set.of(q2), next);
        assertThat(ledger.read(run.id()).modelCalls()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM outbox_event WHERE run_id=?",
                                Integer.class,
                                run.id()))
                .isEqualTo(2);
        complete(claim(run.id(), next.id()));
    }

    /** 需求推进使其他旧卡片失效，保留原问题而不重绑授权；不能被永远无法回答的旧卡片卡住。 */
    @Test
    void otherCardsBecomeStaleWithoutLosingTheirQuestionHistory() {
        var first = plan(StageType.UNDERSTAND, 0, "main");
        var run = start(task(actor()), first);
        var root = claim(run.id(), first.id());
        var a = plan(StageType.RESEARCH, 0, "a");
        var b = plan(StageType.RESEARCH, 0, "b");
        engine.commit(engine.prepare(root, output(a, b)));
        var one = claim(run.id(), a.id());
        var two = claim(run.id(), b.id());
        UUID question = UUID.randomUUID();
        var questions =
                Map.of(question, new DecisionRequest.Question(question, "待确认信息", Map.of(), null));
        var cardA =
                new DecisionRequest(
                        UUID.randomUUID(),
                        run.scope(),
                        run.id(),
                        run.generation(),
                        1,
                        1,
                        questions,
                        true,
                        Set.of(),
                        DecisionRequest.State.OPEN);
        var cardB =
                new DecisionRequest(
                        UUID.randomUUID(),
                        run.scope(),
                        run.id(),
                        run.generation(),
                        1,
                        1,
                        questions,
                        true,
                        Set.of(),
                        DecisionRequest.State.OPEN);
        engine.commit(
                engine.prepare(
                        one,
                        new StageOutput(
                                "提问一", "{}", List.of(), StageOutput.Outcome.WAIT_USER, cardA)));
        engine.commit(
                engine.prepare(
                        two,
                        new StageOutput(
                                "提问二", "{}", List.of(), StageOutput.Outcome.WAIT_USER, cardB)));
        var answer =
                new DecisionAnswer(
                        UUID.randomUUID(),
                        cardA.id(),
                        1,
                        1,
                        DecisionAnswer.Kind.ANSWER,
                        Map.of(),
                        "确认",
                        Set.of(),
                        DecisionAnswer.State.INTERPRETED);
        var next = plan(StageType.UNDERSTAND, 1, "refresh");
        assertThat(
                        engine.resume(
                                        run.scope(),
                                        run.id(),
                                        answer,
                                        Set.of(question),
                                        next,
                                        emptyContent())
                                .state())
                .isEqualTo(ExecutionRun.State.QUEUED);
        String body =
                jdbc.queryForObject(
                        "SELECT body::text FROM decision_request WHERE id=?",
                        String.class,
                        cardB.id());
        var stale = json.decode(body, DecisionRequest.class);
        assertThat(stale.state()).isEqualTo(DecisionRequest.State.SUPERSEDED);
        assertThat(stale.remaining()).containsKey(question);
    }

    /**
     * 旧启动请求的幂等重放只会通知旧代次，不得中断同任务更晚启动的处理器。
     * @throws Exception 本地有界等待失败
     */
    @Test
    void replayOfOldStartCannotInterruptNewerExecution() throws Exception {
        var actor = actor();
        var scope = task(actor);
        UUID oldInput = UUID.randomUUID();
        var first = plan(StageType.UNDERSTAND, 0, "old");
        var second = plan(StageType.UNDERSTAND, 0, "new");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        StageHandler handler =
                new StageHandler() {
                    /** 仅测试明确的理解阶段。 */
                    @Override
                    public boolean supports(StageType type) {
                        return type == StageType.UNDERSTAND;
                    }

                    /** 新运行阻塞到测试释放；记录实际中断，既不访问供应商也不生成候选。 */
                    @Override
                    public StageOutput execute(ExecutionEngineStore.Claim claim) {
                        entered.countDown();
                        boolean done = false;
                        while (!done) {
                            try {
                                release.await();
                                done = true;
                            } catch (InterruptedException cancelled) {
                                interrupted.countDown();
                            }
                        }
                        return new StageOutput(
                                "测试退出", "{}", List.of(), StageOutput.Outcome.BRANCH_COMPLETE, null);
                    }
                };
        try (var resources = new BoundedExecutionResources(2, 2);
                var worker =
                        new PersistentStageWorker(
                                engine,
                                new StageExecutor(engine, List.of(handler)),
                                resources,
                                2,
                                Duration.ofSeconds(15))) {
            var runs = new RunService(engine, transactions, worker);
            runs.start(actor, scope.taskId(), oldInput, first);
            var newer = runs.start(actor, scope.taskId(), UUID.randomUUID(), second);
            worker.tick();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            runs.start(actor, scope.taskId(), oldInput, first);
            assertThat(interrupted.await(150, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(engine.run(scope, newer.id()).state()).isEqualTo(ExecutionRun.State.RUNNING);
            runs.cancel(actor, scope.taskId(), newer.id());
            release.countDown();
        } finally {
            release.countDown();
        }
    }

    /**
     * 额度暂停需应用明确核实才能恢复原运行；预算暂停只允许显式新运行。
     */
    @Test
    void quotaAndBudgetPausesDoNotResetUsage() {
        var first = plan(StageType.UNDERSTAND, 0, "main");
        var run = start(task(actor()), first);
        var claim = claim(run.id(), first.id());
        ledger.reserve(
                run.id(),
                CallKind.MODEL,
                limits,
                new BudgetLedger.Target("fake", "local", "TEST"),
                1,
                Instant.now());
        engine.fail(claim.fence(), ExecutionEngineStore.Failure.QUOTA);
        assertThat(engine.run(run.scope(), run.id()).state())
                .isEqualTo(ExecutionRun.State.PAUSED_QUOTA);
        assertThatThrownBy(() -> engine.resumeQuota(run.scope(), run.id(), false))
                .hasMessage("ILLEGAL_TRANSITION");
        engine.resumeQuota(run.scope(), run.id(), true);
        var again = claim(run.id(), first.id());
        engine.fail(again.fence(), ExecutionEngineStore.Failure.BUDGET);
        assertThat(engine.run(run.scope(), run.id()).state())
                .isEqualTo(ExecutionRun.State.PAUSED_BUDGET);
        assertThat(ledger.read(run.id()).modelCalls()).isEqualTo(1);
        assertThat(
                        engine.continueRun(
                                        run.scope(),
                                        run.id(),
                                        UUID.randomUUID(),
                                        plan(StageType.UNDERSTAND, 0, "new"))
                                .id())
                .isNotEqualTo(run.id());
    }

    /**
     * 反复额度暂停并恢复仍受总尝试数约束，不能把旧尝试从 ABANDONED 改为新执行。
     */
    @Test
    void quotaResumeCannotCreateAnExtraAttempt() {
        var first = plan(StageType.UNDERSTAND, 0, "main");
        var run = start(task(actor()), first);
        for (int i = 0; i < 3; i++) {
            var attempt = claim(run.id(), first.id());
            engine.fail(attempt.fence(), ExecutionEngineStore.Failure.QUOTA);
            engine.resumeQuota(run.scope(), run.id(), true);
        }
        assertThat(engine.claim(hint(run.id(), first.id()), "too_many")).isEmpty();
        assertThat(step(run, first.id()).state()).isEqualTo(ExecutionStep.State.FAILED);
        assertThat(step(run, first.id()).attemptCount()).isEqualTo(3);
        assertThat(engine.run(run.scope(), run.id()).state()).isEqualTo(ExecutionRun.State.FAILED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM step_attempt WHERE run_id=?",
                                Integer.class,
                                run.id()))
                .isEqualTo(3);
    }

    /**
     * 并行阶段共享一个活跃区间；超过原主动时间预算时心跳先持久暂停再拒绝执行。
     */
    @Test
    void activeTimeIsSharedAndBudgetPauseIsDurable() {
        var first = plan(StageType.UNDERSTAND, 0, "main");
        var run = start(task(actor()), first);
        var root = claim(run.id(), first.id());
        var a = plan(StageType.RESEARCH, 0, "a");
        var b = plan(StageType.RESEARCH, 0, "b");
        engine.commit(engine.prepare(root, output(a, b)));
        var one = claim(run.id(), a.id());
        var two = claim(run.id(), b.id());
        jdbc.update(
                "UPDATE execution_run SET active_since=clock_timestamp()-interval '5 seconds' WHERE id=?",
                run.id());
        assertThat(engine.renew(one.fence())).isTrue();
        assertThat(engine.renew(two.fence())).isTrue();
        assertThat(ledger.read(run.id()).activeTime().toMillis()).isBetween(4500L, 7500L);
        jdbc.update("UPDATE execution_budget SET active_millis=900000 WHERE run_id=?", run.id());
        assertThat(engine.renew(one.fence())).isFalse();
        assertThat(engine.run(run.scope(), run.id()).state())
                .isEqualTo(ExecutionRun.State.PAUSED_BUDGET);
        assertThat(engine.renew(two.fence())).isFalse();
    }

    /**
     * 行程目标不得使用咨询完成跳过用户确认；基础依赖及产物必须属于同用户同运行。
     */
    @Test
    void goalAndOwnershipChecksRejectInvalidCommit() {
        var actor = actor();
        var scope = task(actor);
        var first = plan(StageType.UNDERSTAND, 0, "main");
        var run = engine.start(scope, UUID.randomUUID(), first);
        var own = claim(run.id(), first.id());
        var command = engine.prepare(own, output());
        assertThatThrownBy(() -> engine.commit(command)).hasMessage("INVALID_INPUT");
        assertThat(engine.run(scope, run.id()).state()).isEqualTo(ExecutionRun.State.RUNNING);
        var foreign = task(actor());
        assertThatThrownBy(() -> engine.run(foreign, run.id())).hasMessage("NOT_FOUND");
        var bad = plan(StageType.RESEARCH, 0, "wrong", UUID.randomUUID());
        assertThatThrownBy(() -> engine.commit(engine.prepare(own, output(bad))))
                .hasMessage("NOT_FOUND");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM outbox_event WHERE run_id=?",
                                Integer.class,
                                run.id()))
                .isEqualTo(1);
    }

    /**
     * 修订预算按逻辑阶段预占一次，重试不会再次扣同一修订。
     */
    @Test
    void repairBudgetIsSharedAcrossRetries() {
        var first = plan(StageType.PLAN, 1, "repair");
        var run = start(task(actor()), first);
        var claim = claim(run.id(), first.id());
        assertThat(ledger.read(run.id()).repairRounds()).isEqualTo(1);
        engine.fail(claim.fence(), ExecutionEngineStore.Failure.RETRYABLE);
        due(run.id());
        claim(run.id(), first.id());
        assertThat(ledger.read(run.id()).repairRounds()).isEqualTo(1);
    }

    /**
     * 未注册处理器时不领取，不将工程占位伪装为实际规划。
     */
    @Test
    void workerWithoutHandlersLeavesRequestQueued() {
        var first = plan(StageType.UNDERSTAND, 0, "main");
        var run = start(task(actor()), first);
        try (var resources = new BoundedExecutionResources(2, 2);
                var worker =
                        new PersistentStageWorker(
                                engine,
                                new StageExecutor(engine, List.of()),
                                resources,
                                2,
                                Duration.ofSeconds(15))) {
            worker.tick();
        }
        assertThat(engine.run(run.scope(), run.id()).state()).isEqualTo(ExecutionRun.State.QUEUED);
        assertThat(step(run, first.id()).attemptCount()).isZero();
    }

    /**
     * 已取消但忽略中断的处理器继续占物理容量，实际退出后才能派发新运行。
     * @throws Exception 本地有界等待失败
     */
    @Test
    void cancelledWorkHoldsCapacityUntilActualExit() throws Exception {
        var actor = actor();
        var scope = task(actor);
        var first = plan(StageType.UNDERSTAND, 0, "blocked");
        var run = start(scope, first);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        StageHandler handler =
                new StageHandler() {
                    /**
                     * 本地替身仅处理理解阶段。
                     */
                    @Override
                    public boolean supports(StageType type) {
                        return type == StageType.UNDERSTAND;
                    }

                    /**
                     * 模拟暂不可取消的请求，只有测试明确释放才退出。
                     */
                    @Override
                    public StageOutput execute(ExecutionEngineStore.Claim claim) {
                        if (claim.step().id().equals(first.id())) {
                            entered.countDown();
                            boolean done = false;
                            while (!done) {
                                try {
                                    release.await();
                                    done = true;
                                } catch (InterruptedException ignored) {
                                    /* 模拟实际请求尚未结束。 */
                                }
                            }
                        }
                        return output();
                    }
                };
        try (var resources = new BoundedExecutionResources(1, 2);
                var worker =
                        new PersistentStageWorker(
                                engine,
                                new StageExecutor(engine, List.of(handler)),
                                resources,
                                2,
                                Duration.ofSeconds(15))) {
            worker.tick();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            new RunService(engine, transactions, worker).cancel(actor, scope.taskId(), run.id());
            var second = plan(StageType.UNDERSTAND, 0, "next");
            var next = start(task(actor), second);
            worker.tick();
            assertThat(step(next, second.id()).attemptCount()).isZero();
            release.countDown();
            await(
                    () -> {
                        worker.tick();
                        return engine.run(next.scope(), next.id()).state()
                                == ExecutionRun.State.SUCCEEDED;
                    });
            assertThat(engine.run(scope, run.id()).state()).isEqualTo(ExecutionRun.State.CANCELLED);
        } finally {
            release.countDown();
        }
    }

    /**
     * 独立 JVM 领取后硬退出；父进程重新建仓储，失联租约恢复为同阶段的新尝试。
     * @throws Exception 子进程或等待失败
     */
    @Test
    void hardProcessExitRecoversFromPostgres() throws Exception {
        var first = plan(StageType.RESEARCH, 0, "crash");
        var run = start(task(actor()), first);
        crash(run.id().toString(), "CLAIM");
        assertThat(step(run, first.id()).attemptCount()).isEqualTo(1);
        jdbc.update(
                "UPDATE outbox_event SET lease_until=clock_timestamp()-interval '1 second' WHERE run_id=?",
                run.id());
        assertThat(fresh(mapper).recover(100)).isEqualTo(1);
        due(run.id());
        var recovered = claim(run.id(), first.id());
        assertThat(recovered.step().attemptCount()).isEqualTo(2);
        complete(recovered);
    }

    /**
     * @param arguments 仅随机测试身份及固定故障模式；不传凭据
     * @throws Exception 子进程未按预期到达故障窗口
     */
    private void crash(String... arguments) throws Exception {
        Path argsFile = Files.createTempFile(Path.of("target"), "stage-crash-", ".args");
        Process child = null;
        try {
            String classpath =
                    System.getProperty("java.class.path")
                            .replace("\\", "\\\\")
                            .replace("\"", "\\\"");
            Files.writeString(argsFile, "-cp\n\"" + classpath + "\"\n");
            String executable =
                    System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                            ? "java.exe"
                            : "java";
            var command =
                    new ArrayList<>(
                            List.of(
                                    Path.of(System.getProperty("java.home"), "bin", executable)
                                            .toString(),
                                    "@" + argsFile.toAbsolutePath(),
                                    ExecutionCrashProbe.class.getName()));
            command.addAll(List.of(arguments));
            var process =
                    new ProcessBuilder(command)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD);
            ModelPolicyDatabase.bindChild(process);
            child = process.start();
            assertThat(child.waitFor(45, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isEqualTo(23);
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
            Files.deleteIfExists(argsFile);
        }
    }

    /** 输入提交前硬退出无半条消息或启动事件；提交后硬退出保留唯一收件与唯一运行。 */
    @Test
    void inputTransactionsSurviveActualProcessExit() throws Exception {
        for (String mode : List.of("INPUT_BEFORE", "INPUT_AFTER")) {
            var actor = actor();
            UUID input = UUID.randomUUID();
            crash(actor.id().toString(), mode, actor.loginName(), input.toString());
            int expected = mode.equals("INPUT_BEFORE") ? 0 : 1;
            for (String table :
                    List.of(
                            "travel_task",
                            "conversation_message",
                            "execution_run",
                            "execution_start_request",
                            "outbox_event"))
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM " + table + " WHERE user_id=?",
                                        Integer.class,
                                        actor.id()))
                        .as(table)
                        .isEqualTo(expected);
            if (expected == 1) {
                UUID run =
                        jdbc.queryForObject(
                                "SELECT id FROM execution_run WHERE user_id=?",
                                UUID.class,
                                actor.id());
                var hint =
                        fresh(mapper).candidates(100).stream()
                                .filter(h -> h.runId().equals(run))
                                .findFirst()
                                .orElseThrow();
                assertThat(fresh(mapper).claim(hint, "new-process")).isPresent();
            }
        }
    }

    /** 产物独立保存后硬退出，不能把有正文当作阶段完成；恢复仍使用原预算并产生新尝试。 */
    @Test
    void preparedResultWithoutCommitDoesNotSkipStageAfterCrash() throws Exception {
        var first = plan(StageType.RESEARCH, 0, "prepare-crash");
        var run = start(task(actor()), first);
        var metered =
                ledger.reserve(
                        run.id(),
                        CallKind.MODEL,
                        limits,
                        new BudgetLedger.Target("fake", "local", "TEST"),
                        10,
                        Instant.now());
        ledger.finish(
                metered.id(),
                new com.lawrence.travel.domain.execution.control.TokenUsage(1, 1),
                Duration.ofMillis(1));
        UUID next = UUID.randomUUID();
        crash(run.id().toString(), "PREPARE", next.toString());
        assertThat(mapper.step(run.scope(), run.id(), first.id()).artifactId()).isNull();
        assertThat(step(run, first.id()).state()).isEqualTo(ExecutionStep.State.RUNNING);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM artifact_record WHERE run_id=?",
                                Integer.class,
                                run.id()))
                .isOne();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM execution_step WHERE id=?",
                                Integer.class,
                                next))
                .isZero();
        jdbc.update(
                "UPDATE outbox_event SET lease_until=clock_timestamp()-interval '1 second' WHERE run_id=?",
                run.id());
        fresh(mapper).recover(100);
        due(run.id());
        var recovered = claim(run.id(), first.id());
        assertThat(recovered.step().attemptCount()).isEqualTo(2);
        complete(recovered);
        assertThat(ledger.read(run.id()).modelCalls()).isOne();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM artifact_record WHERE run_id=?",
                                Integer.class,
                                run.id()))
                .isEqualTo(2);
        assertThat(engine.run(run.scope(), run.id()).state())
                .isEqualTo(ExecutionRun.State.SUCCEEDED);
    }

    /** 阶段和下一事件提交后硬退出，下游仍可领取，已完成阶段和因果事件均不重复。 */
    @Test
    void committedStageSurvivesCrashWithExactlyOneNextEvent() throws Exception {
        var first = plan(StageType.RESEARCH, 0, "committed-crash");
        var run = start(task(actor()), first);
        UUID next = UUID.randomUUID();
        crash(run.id().toString(), "COMMIT", next.toString());
        assertThat(step(run, first.id()).state()).isEqualTo(ExecutionStep.State.COMPLETED);
        assertThat(step(run, first.id()).attemptCount()).isOne();
        assertThat(mapper.step(run.scope(), run.id(), first.id()).artifactId()).isNotNull();
        assertThat(fresh(mapper).recover(100)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM outbox_event WHERE run_id=?",
                                Integer.class,
                                run.id()))
                .isEqualTo(2);
        complete(claim(run.id(), next));
        assertThat(step(run, first.id()).attemptCount()).isOne();
        assertThat(engine.run(run.scope(), run.id()).state())
                .isEqualTo(ExecutionRun.State.SUCCEEDED);
    }

    /** 类型化免费耗尽由实际执行器直接持久化为额度暂停，不生成重试或新预算。 */
    @Test
    void quotaRejectionPausesAtExecutorBoundaryWithoutRetry() {
        var first = plan(StageType.UNDERSTAND, 0, "quota-boundary");
        var run = start(task(actor()), first);
        var claim = claim(run.id(), first.id());
        new StageExecutor(
                        engine,
                        List.of(
                                failingHandler(
                                        new com.lawrence.travel.domain.execution.control
                                                .CallRejectedException(
                                                com.lawrence.travel.domain.execution.control
                                                        .CallRejectedException.Reason
                                                        .QUOTA_INSUFFICIENT))))
                .execute(claim, control());
        assertThat(engine.run(run.scope(), run.id()).state())
                .isEqualTo(ExecutionRun.State.PAUSED_QUOTA);
        assertThat(step(run, first.id()).attemptCount()).isOne();
        assertThat(engine.candidates(100).stream().noneMatch(h -> h.runId().equals(run.id())))
                .isTrue();
    }

    /** 超时和限流的固定失败类别在执行器内有界重试，耗尽后DEAD及FAILED，不会无事件永久运行。 */
    @Test
    void transientFailuresStopAfterBoundedAttempts() {
        for (String code : List.of("MODEL_TRANSPORT_FAILED", "MODEL_RATE_LIMITED")) {
            var first = plan(StageType.UNDERSTAND, 0, "bounded-failure");
            var run = start(task(actor()), first);
            var executor =
                    new StageExecutor(
                            engine, List.of(failingHandler(new IllegalStateException(code))));
            for (int i = 0; i < 3; i++) {
                executor.execute(claim(run.id(), first.id()), control());
                due(run.id());
            }
            assertThat(engine.run(run.scope(), run.id()).state())
                    .isEqualTo(ExecutionRun.State.FAILED);
            assertThat(step(run, first.id()).attemptCount()).isEqualTo(3);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM outbox_event WHERE run_id=? AND status='DEAD'",
                                    Integer.class,
                                    run.id()))
                    .isOne();
        }
    }

    /**
     * @param failure 固定本地失败，无网络请求
     * @return 可控理解阶段处理器
     */
    private StageHandler failingHandler(RuntimeException failure) {
        return new StageHandler() {
            /** {@inheritDoc} 仅接受本用例理解阶段。 */
            @Override
            public boolean supports(StageType type) {
                return type == StageType.UNDERSTAND;
            }

            /** {@inheritDoc} 固定异常用于检查分类，不伪造供应商真实请求。 */
            @Override
            public StageOutput execute(ExecutionEngineStore.Claim claim) {
                throw failure;
            }
        };
    }

    /**
     * @return 始终允许进入的本地执行控制；取消与物理资源另由既有并发测试验证
     */
    private StageExecutor.Control control() {
        return new StageExecutor.Control() {
            /** {@inheritDoc} 本测试没有并发取消。 */
            @Override
            public boolean enter() {
                return true;
            }

            /** {@inheritDoc} 本地失败不留下中断。 */
            @Override
            public void leave() {
                Thread.interrupted();
            }

            /** {@inheritDoc} 原运行未被用户取消。 */
            @Override
            public boolean cancelled() {
                return false;
            }
        };
    }
}
