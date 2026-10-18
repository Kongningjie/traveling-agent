package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.execution.StageOutput;
import com.lawrence.travel.application.execution.StagePlan;
import com.lawrence.travel.application.itinerary.ItineraryService;
import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.CandidateStore;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.StageCommitPort;
import com.lawrence.travel.application.port.TravelStore;
import com.lawrence.travel.application.travel.TravelStorageService;
import com.lawrence.travel.domain.execution.ExecutionRun;
import com.lawrence.travel.domain.execution.ExecutionStep;
import com.lawrence.travel.domain.execution.PlanningMode;
import com.lawrence.travel.domain.execution.StageType;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.itinerary.ItineraryReport;
import com.lawrence.travel.domain.itinerary.ItineraryVersion;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.infrastructure.execution.persistence.EngineMapper;
import com.lawrence.travel.infrastructure.itinerary.CandidateMapper;
import com.lawrence.travel.infrastructure.itinerary.PostgresCandidateStore;
import com.lawrence.travel.infrastructure.persistence.control.BudgetMapper;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import com.lawrence.travel.infrastructure.travel.TravelMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实 PostgreSQL 报告、接受及可靠重校验事务；审核产物为显式本地替身，没有供应商调用。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "travel.engine.enabled=false")
class CandidateAcceptanceIntegrationTest {
    @Autowired private ExecutionEngineStore engine;
    @Autowired private TravelStorageService travel;
    @Autowired private ItineraryService itineraries;
    @Autowired private CandidateStore candidates;
    @Autowired private CandidateMapper mapper;
    @Autowired private EngineMapper engines;
    @Autowired private TravelMapper tasks;
    @Autowired private BudgetMapper budgets;
    @Autowired private TravelJson json;
    @Autowired private AccountStore accounts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BusinessTransactions transactions;
    private final List<UUID> users = new ArrayList<>();
    /**
     * @param actor 身份
     * @param scope 私有任务
     * @param run 运行
     * @param version 快照
     * @param candidate 候选
     * @param stages 明确阶段链
     */
    private record Fixture(UserIdentity actor, TaskScope scope, ExecutionRun run, ItineraryVersion version, UUID candidate, List<StagePlan> stages) { }
    /** 清理只涉及本例随机账号；循环 FK 在事务末检查，不修改不可变报告正文。 */
    @AfterEach void cleanOwnRecords() {
        transactions.required(() -> {
            for (UUID user : users) {
                var runIds = jdbc.queryForList("SELECT id FROM execution_run WHERE user_id=?", UUID.class, user);
                jdbc.update("UPDATE travel_task SET current_run_id=NULL,current_version_id=NULL WHERE user_id=?", user);
                for (String table : List.of("itinerary_acceptance_request", "itinerary_acceptance", "itinerary_report", "itinerary_candidate",
                        "decision_response", "decision_request", "run_event", "execution_start_request")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
                jdbc.update("DELETE FROM artifact_content WHERE artifact_id IN (SELECT id FROM artifact_record WHERE user_id=?)", user);
                for (String table : List.of("artifact_record", "step_attempt", "outbox_event", "execution_step", "execution_run")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
                for (UUID run : runIds) { jdbc.update("DELETE FROM call_meter WHERE run_id=?", run); jdbc.update("DELETE FROM execution_budget WHERE run_id=?", run); }
                for (String table : List.of("change_request", "itinerary_snapshot", "travel_constraint_revision", "travel_task", "travel_idempotency")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
                jdbc.update("DELETE FROM app_user WHERE id=?", user);
            }
            return true;
        });
    }
    /**
     * @return 不可登录的独立测试账号
     */
    private UserIdentity actor() {
        UUID id = UUID.randomUUID(); String name = "c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        users.add(id); accounts.insert(id, name, "__NON_LOGIN_TEST_HASH__"); return new UserIdentity(id, name);
    }
    /**
     * @param malformed 是否存入仅通过存储信封的坏业务格式
     * @return 尚未推进阶段的候选夹具
     */
    private Fixture fixture(boolean malformed) {
        var actor = actor(); var scope = travel.create(actor, UUID.randomUUID().toString(), "候选门禁测试",
                new TravelStore.RevisionContent(List.of(), List.of(), "测试确认", "")).task().scope();
        return fixture(actor, scope, null, malformed);
    }
    /**
     * @param actor 身份
     * @param scope 已有任务
     * @param base 基础版本
     * @param malformed 业务坏格式
     * @return 新运行夹具
     */
    private Fixture fixture(UserIdentity actor, TaskScope scope, UUID base, boolean malformed) {
        var snapshot = ItineraryRulesTest.minimal(scope.taskId(), base);
        if (base != null) {
            var original = candidates.base(scope, base); var revision = travel.revision(actor, scope.taskId(), 1);
            var impact = new com.lawrence.travel.domain.itinerary.ImpactAnalyzer().analyze(original.snapshot(), original.revision(), revision, List.of());
            snapshot = new com.lawrence.travel.domain.itinerary.PatchService().apply(original.snapshot(), base, original.hash(), 1, impact, new com.lawrence.travel.domain.itinerary.ItineraryPatch(original.hash(), List.of(), null));
        }
        String payload = json.encode(snapshot);
        if (malformed) {
            @SuppressWarnings("unchecked") var body = new java.util.LinkedHashMap<>((Map<String, Object>) json.decode(payload, Object.class));
            body.remove("window"); payload = json.encode(body);
        }
        var version = new ItineraryVersion(UUID.randomUUID(), scope, 1, json.hash(payload), 1, base, Set.of(), null);
        if (base == null) travel.saveDraft(actor, version, payload);
        var plans = new ArrayList<StagePlan>(); UUID prior = null;
        for (StageType type : List.of(StageType.PLAN, StageType.VALIDATE, StageType.REVIEW, StageType.PRESENT)) {
            UUID id = UUID.randomUUID(); plans.add(new StagePlan(id, new ExecutionStep.Key(type, 0, "main"), type == StageType.PLAN ? base == null ? PlanningMode.INITIAL : PlanningMode.REPLAN : null,
                    "{}", prior == null ? List.of() : List.of(prior))); prior = id;
        }
        var run = engine.start(scope, UUID.randomUUID(), plans.getFirst());
        return new Fixture(actor, scope, run, version, UUID.randomUUID(), plans);
    }
    /**
     * @param f 运行夹具
     * @param index 明确阶段位置
     * @param reviewIssues 本地独立审核替身问题
     * @return 本次领取资格
     */
    private ExecutionEngineStore.Claim advance(Fixture f, int index, List<ItineraryReport.Issue> reviewIssues) {
        var plan = f.stages().get(index);
        var hint = engine.candidates(100).stream().filter(value -> value.runId().equals(f.run().id()) && value.stepId().equals(plan.id())).findFirst().orElseThrow();
        var claim = engine.claim(hint, "candidate-test").orElseThrow();
        var body = new java.util.LinkedHashMap<String, Object>(); body.put("candidateId", f.candidate());
        body.put("operation", List.of("SAVE_CANDIDATE", "VALIDATE_ITINERARY", "REVIEW_ITINERARY", "PRESENT_ITINERARY").get(index));
        if (index == 0) {
            body.put("snapshotId", f.version().snapshotId());
            if (f.version().baseVersionId() != null) {
                var original = candidates.base(f.scope(), f.version().baseVersionId());
                var impact = new com.lawrence.travel.domain.itinerary.ImpactAnalyzer().analyze(original.snapshot(), original.revision(), candidates.context(claim.fence()).revision(), List.of());
                var patch = new com.lawrence.travel.domain.itinerary.ItineraryPatch(original.hash(), List.of(), null);
                body.put("patch", patch); body.put("impactScope", impact);
                body.put("snapshot", new com.lawrence.travel.domain.itinerary.PatchService().apply(original.snapshot(), f.version().baseVersionId(), original.hash(), 1, impact, patch));
            }
        }
        if (index == 2) {
            body.put("issues", reviewIssues);
            var validation = json.decode(mapper.report(f.scope(), mapper.candidate(f.scope(), f.candidate()).validationId()).body(), ItineraryReport.class);
            body.put("validationId", validation.id()); body.put("binding", validation.binding());
        }
        var output = new StageOutput("本地阶段替身", json.encode(body), index < 3 ? List.of(f.stages().get(index + 1)) : List.of(),
                index == 3 ? StageOutput.Outcome.PRESENT_CANDIDATE : StageOutput.Outcome.CONTINUE, null);
        assertThat(engine.commit(engine.prepare(claim, output))).isEqualTo(StageCommitPort.Result.COMMITTED); return claim;
    }
    /**
     * @param f 夹具；所有阶段均经真实引擎提交
     */
    private void present(Fixture f) {
        for (int i = 0; i < 4; i++) {
            advance(f, i, List.of());
            if (i == 1) assertThat(json.decode(mapper.report(f.scope(), mapper.candidate(f.scope(), f.candidate()).validationId()).body(), ItineraryReport.class).issues()).isEmpty();
        }
    }
    /**
     * @param service 接受用例
     * @param f 夹具
     * @param request 传输幂等键
     * @return 原始预期版本的响应
     */
    private CandidateStore.Acceptance accept(ItineraryService service, Fixture f, UUID request) {
        return service.accept(f.actor(), f.scope().taskId(), request, f.candidate(), f.version().baseVersionId(), 1, f.run().generation(), f.version().payloadHash());
    }
    /**
     * @param f 夹具
     * @return 当前生效指针，可空
     */
    private UUID current(Fixture f) { return travel.task(f.actor(), f.scope().taskId()).task().currentVersionId(); }
    /**
     * @param f 来源夹具
     * @param mapperOverride 时间／写入故障替身；其余方法仍由真实 MyBatis 执行
     * @return 使用相同规则的候选仓储
     */
    private CandidateStore store(Fixture f, CandidateMapper mapperOverride) {
        var report = mapper.report(f.scope(), mapper.candidate(f.scope(), f.candidate()).validationId());
        return new PostgresCandidateStore(mapperOverride, tasks, engines, budgets, json, json.decode(report.body(), ItineraryReport.class).binding().ruleVersion(),
                json.decode(report.body(), ItineraryReport.class).binding().reviewConfiguration(), Duration.ofMinutes(10));
    }
    /**
     * @param offset 报告判断偏移；不会改真实数据库记录
     * @param failSave 是否在事务末注入失败
     * @return 仅覆盖指定方法的本地代理
     */
    private CandidateMapper faultMapper(Duration offset, boolean failSave) {
        return (CandidateMapper) Proxy.newProxyInstance(CandidateMapper.class.getClassLoader(), new Class<?>[]{CandidateMapper.class}, (proxy, method, args) -> {
            if (method.getName().equals("now")) return mapper.now().plus(offset);
            if (failSave && method.getName().equals("saveRequest")) throw new IllegalStateException("LOCAL_TRANSACTION_FAILURE");
            try { return method.invoke(mapper, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
    }
    /** 展示与接受严格分开，成功接受同事务推进指针、运行及事件。 */
    @Test void presentThenExplicitAcceptanceCommitsAtomically() {
        var f = fixture(false); present(f);
        assertThat(current(f)).isNull(); assertThat(engine.run(f.scope(), f.run().id()).state()).isEqualTo(ExecutionRun.State.WAITING_ACCEPTANCE);
        var accepted = accept(itineraries, f, UUID.randomUUID()); assertThat(accepted.status()).isEqualTo(CandidateStore.Status.ACCEPTED);
        assertThat(current(f)).isEqualTo(f.version().snapshotId()); assertThat(engine.run(f.scope(), f.run().id()).state()).isEqualTo(ExecutionRun.State.SUCCEEDED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM run_event WHERE user_id=? AND type='ITINERARY_ACCEPTED'", Integer.class, f.actor().id())).isOne();
    }
    /** 两个并发请求即便使用不同键，也只记录一个接受事实。 */
    @Test void concurrentDifferentKeysShareOnePermanentAcceptance() throws Exception {
        var f = fixture(false); present(f); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return accept(itineraries, f, UUID.randomUUID()); });
            var b = pool.submit(() -> { start.await(); return accept(itineraries, f, UUID.randomUUID()); }); start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(b.get(10, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM itinerary_acceptance WHERE user_id=?", Integer.class, f.actor().id())).isOne();
    }
    /** 原请求或新键重试旧接受不回退后继生效版本。 */
    @Test void replayNeverRestoresAnOlderCurrentPointer() {
        var f = fixture(false); present(f); UUID key = UUID.randomUUID(); var original = accept(itineraries, f, key);
        var next = fixture(f.actor(), f.scope(), f.version().snapshotId(), false); present(next); accept(itineraries, next, UUID.randomUUID());
        assertThat(accept(itineraries, f, key)).isEqualTo(original); assertThat(accept(itineraries, f, UUID.randomUUID())).isEqualTo(original);
        assertThat(current(f)).isEqualTo(next.version().snapshotId());
    }
    /** 同键改语义冲突，不能借幂等响应接受另一份内容。 */
    @Test void sameKeyDifferentHashConflicts() {
        var f = fixture(false); present(f); UUID key = UUID.randomUUID();
        assertThatThrownBy(() -> itineraries.accept(f.actor(), f.scope().taskId(), UUID.randomUUID(), f.candidate(), null, 1, f.run().generation(), "f".repeat(64))).hasMessage("STALE_VERSION");
        assertThatThrownBy(() -> itineraries.accept(f.actor(), f.scope().taskId(), UUID.randomUUID(), f.candidate(), UUID.randomUUID(), 1, f.run().generation(), f.version().payloadHash())).hasMessage("STALE_VERSION");
        assertThatThrownBy(() -> itineraries.accept(f.actor(), f.scope().taskId(), UUID.randomUUID(), f.candidate(), null, 1, f.run().generation() + 1, f.version().payloadHash())).hasMessage("STALE_VERSION");
        accept(itineraries, f, key);
        assertThatThrownBy(() -> itineraries.accept(f.actor(), f.scope().taskId(), key, f.candidate(), null, 1, f.run().generation(), "f".repeat(64)))
                .hasMessage("IDEMPOTENCY_CONFLICT");
    }
    /** 别人任务和候选均不暴露；未展示候选也不能接受。 */
    @Test void foreignUserAndUnpresentedCandidateAreRejected() {
        var f = fixture(false); advance(f, 0, List.of()); var other = actor();
        assertThatThrownBy(() -> itineraries.accept(other, f.scope().taskId(), UUID.randomUUID(), f.candidate(), null, 1, 1, f.version().payloadHash())).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> accept(itineraries, f, UUID.randomUUID())).hasMessage("STALE_VERSION"); assertThat(current(f)).isNull();
    }
    /** 坏业务格式产生真实 FAIL 报告，不能提交 REVIEW 或自动生效。 */
    @Test void malformedSnapshotProducesFailAndBlocksReview() {
        var f = fixture(true); advance(f, 0, List.of()); advance(f, 1, List.of());
        var report = json.decode(mapper.report(f.scope(), mapper.candidate(f.scope(), f.candidate()).validationId()).body(), ItineraryReport.class);
        assertThat(report.conclusion()).isEqualTo(ItineraryReport.Conclusion.FAIL);
        assertThatThrownBy(() -> advance(f, 2, List.of())).hasMessage("INVALID_INPUT"); assertThat(current(f)).isNull();
    }
    /** 独立审核 FAIL 和阻断 UNKNOWN 都无法展示为可接受候选。 */
    @Test void blockingReviewUnknownCannotBePresented() {
        var f = fixture(false); advance(f, 0, List.of()); advance(f, 1, List.of());
        advance(f, 2, List.of(new ItineraryReport.Issue("REVIEW_FACT_UNKNOWN", null, true, true)));
        assertThatThrownBy(() -> advance(f, 3, List.of())).hasMessage("INVALID_INPUT"); assertThat(current(f)).isNull();
    }
    /** 新修改立即撤销旧候选接受资格，尚未解释输入也不能漏过。 */
    @Test void newInputSupersedesWaitingCandidate() {
        var f = fixture(false); present(f); var task = travel.task(f.actor(), f.scope().taskId());
        travel.registerChange(f.actor(), f.scope().taskId(), UUID.randomUUID().toString(), task.lockVersion(), UUID.randomUUID(), 1);
        assertThatThrownBy(() -> accept(itineraries, f, UUID.randomUUID())).hasMessage("STALE_VERSION"); assertThat(current(f)).isNull();
    }
    /** 用户修改与接受同时争锁，旧lockVersion和候选依赖只能允许一个有效转移，无死锁或旧指针覆盖。 */
    @Test void userChangeAndAcceptanceRaceHasOneWinner() throws Exception {
        var f = fixture(false); present(f); long expected = travel.task(f.actor(), f.scope().taskId()).lockVersion(); var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var accepting = workers.submit(() -> { ready.countDown(); start.await(); try { accept(itineraries, f, UUID.randomUUID()); return true; } catch (com.lawrence.travel.application.travel.TravelStorageFailure stale) { assertThat(stale.getMessage()).isEqualTo("STALE_VERSION"); return false; } });
            var changing = workers.submit(() -> { ready.countDown(); start.await(); try { travel.registerChange(f.actor(), f.scope().taskId(), UUID.randomUUID().toString(), expected, UUID.randomUUID(), 1); return true; } catch (com.lawrence.travel.application.travel.TravelStorageFailure stale) { assertThat(stale.getMessage()).isEqualTo("STALE_VERSION"); return false; } });
            assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue(); start.countDown(); boolean accepted = accepting.get(8, TimeUnit.SECONDS), changed = changing.get(8, TimeUnit.SECONDS);
            assertThat(accepted ^ changed).isTrue(); assertThat(current(f)).isEqualTo(accepted ? f.version().snapshotId() : null);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM itinerary_acceptance WHERE user_id=?", Integer.class, f.actor().id())).isEqualTo(accepted ? 1 : 0);
        } finally { start.countDown(); }
    }

    /** 已取消运行不能被接受路径复活。 */
    @Test void cancelledRunCannotBeAccepted() {
        var f = fixture(false); present(f); engine.cancel(f.scope(), f.run().id());
        assertThatThrownBy(() -> accept(itineraries, f, UUID.randomUUID())).hasMessage("STALE_VERSION");
    }
    /** 模拟报告判断时间推进；真实事务仅排一次 VALIDATE，原预算不变，重新展示后仍需新确认。 */
    @Test void expiredReportQueuesOneRevalidationWithoutAcceptance() {
        var f = fixture(false); present(f); var before = engine.run(f.scope(), f.run().id()).budget();
        var expired = new ItineraryService(store(f, faultMapper(Duration.ofMinutes(11), false)), engine, transactions); UUID key = UUID.randomUUID();
        var result = accept(expired, f, key); assertThat(result.status()).isEqualTo(CandidateStore.Status.REVALIDATION_REQUIRED);
        assertThat(accept(expired, f, key)).isEqualTo(result); assertThat(current(f)).isNull();
        assertThat(engine.run(f.scope(), f.run().id()).state()).isEqualTo(ExecutionRun.State.QUEUED);
        assertThat(engine.run(f.scope(), f.run().id()).budget()).isEqualTo(before);
        assertThat(engine.candidates(100)).filteredOn(value -> value.runId().equals(f.run().id())).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM itinerary_acceptance WHERE user_id=?", Integer.class, f.actor().id())).isZero();
    }
    /** 事务末写入失败时，永久事实、指针和运行状态全部回滚。 */
    @Test void acceptanceFailureRollsBackAllBusinessChanges() {
        var f = fixture(false); present(f); var failing = new ItineraryService(store(f, faultMapper(Duration.ZERO, true)), engine, transactions);
        assertThatThrownBy(() -> accept(failing, f, UUID.randomUUID())).hasMessage("LOCAL_TRANSACTION_FAILURE");
        assertThat(current(f)).isNull(); assertThat(engine.run(f.scope(), f.run().id()).state()).isEqualTo(ExecutionRun.State.WAITING_ACCEPTANCE);
        assertThat(mapper.accepted(f.scope(), f.candidate())).isNull();
        assertThat(accept(itineraries, f, UUID.randomUUID()).status()).isEqualTo(CandidateStore.Status.ACCEPTED);
    }
    /** 重校验入队失败必须回滚候选失效标记与接受请求缓存，不留下不可恢复的半次提交。 */
    @Test void revalidationEnqueueFailureRollsBackStaleMarkerAndReceipt() {
        var f = fixture(false); present(f); UUID key = UUID.randomUUID();
        var broken = org.mockito.Mockito.mock(ExecutionEngineStore.class, org.mockito.AdditionalAnswers.delegatesTo(engine));
        org.mockito.Mockito.doThrow(new IllegalStateException("LOCAL_ENQUEUE_FAILURE")).when(broken)
                .revalidateCandidate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        var expiredStore = store(f, faultMapper(Duration.ofMinutes(11), false));
        assertThatThrownBy(() -> accept(new ItineraryService(expiredStore, broken, transactions), f, key)).hasMessage("LOCAL_ENQUEUE_FAILURE");
        assertThat(mapper.candidate(f.scope(), f.candidate()).state()).isEqualTo("PRESENTED"); assertThat(mapper.request(f.scope(), key)).isNull();
        assertThat(engine.run(f.scope(), f.run().id()).state()).isEqualTo(ExecutionRun.State.WAITING_ACCEPTANCE);
        assertThat(accept(new ItineraryService(expiredStore, engine, transactions), f, key).status()).isEqualTo(CandidateStore.Status.REVALIDATION_REQUIRED);
    }
    /** 用户等待期间耗尽主动预算后，失效报告只进入原预算暂停，仍可靠保存重校验请求。 */
    @Test void revalidationPreservesExhaustedBudgetAndPauses() {
        var f = fixture(false); present(f); jdbc.update("UPDATE execution_budget SET active_millis=? WHERE run_id=?", Duration.ofMinutes(15).toMillis(), f.run().id());
        var expired = new ItineraryService(store(f, faultMapper(Duration.ofMinutes(11), false)), engine, transactions);
        assertThat(accept(expired, f, UUID.randomUUID()).status()).isEqualTo(CandidateStore.Status.REVALIDATION_REQUIRED);
        assertThat(engine.run(f.scope(), f.run().id()).state()).isEqualTo(ExecutionRun.State.PAUSED_BUDGET);
        assertThat(engine.run(f.scope(), f.run().id()).budget().activeTime()).isEqualTo(Duration.ofMinutes(15));
        assertThat(current(f)).isNull();
    }
    /** 审核不能借提交时最新信息重绑其旧产物，必须携带实际读取的精确绑定。 */
    @Test void reviewCannotSilentlyRebindDeclaredDependencies() {
        var f = fixture(false); advance(f, 0, List.of()); advance(f, 1, List.of());
        var validation = json.decode(mapper.report(f.scope(), mapper.candidate(f.scope(), f.candidate()).validationId()).body(), ItineraryReport.class);
        var hint = engine.candidates(100).stream().filter(value -> value.runId().equals(f.run().id()) && value.type() == StageType.REVIEW).findFirst().orElseThrow();
        var claim = engine.claim(hint, "stale-review-test").orElseThrow();
        var b = validation.binding(); var old = new ItineraryReport.Binding(b.snapshotId(), b.schemaVersion(), "f".repeat(64), b.constraintRevision(), b.evidenceRefs(), b.preferenceVersion(), b.ruleVersion(), b.reviewConfiguration());
        var output = new StageOutput("旧上下文审核替身", json.encode(Map.of("operation", "REVIEW_ITINERARY", "candidateId", f.candidate(), "validationId", validation.id(), "binding", old, "issues", List.of())),
                List.of(f.stages().get(3)), StageOutput.Outcome.CONTINUE, null);
        var prepared = engine.prepare(claim, output);
        assertThatThrownBy(() -> engine.commit(prepared)).hasMessage("STALE_VERSION");
        assertThat(mapper.candidate(f.scope(), f.candidate()).reviewId()).isNull();
        assertThat(engine.candidates(100)).noneMatch(value -> value.runId().equals(f.run().id()) && value.type() == StageType.PRESENT);
    }
    /** PostgreSQL 本身拒绝报告原地改写，不能在审核后改 PASS 正文。 */
    @Test void immutableReportCannotBeRewritten() {
        var f = fixture(false); present(f);
        assertThatThrownBy(() -> jdbc.update("UPDATE itinerary_report SET body=body WHERE user_id=?", f.actor().id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
