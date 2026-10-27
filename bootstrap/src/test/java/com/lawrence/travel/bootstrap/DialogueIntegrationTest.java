package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.interaction.Dialogue;
import com.lawrence.travel.application.interaction.DialogueService;
import com.lawrence.travel.application.interaction.NegotiationHandler;
import com.lawrence.travel.application.memory.PreferenceService;
import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.DialogueInterpreter;
import com.lawrence.travel.application.port.DialogueStore;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.interaction.DialogueIntent;
import com.lawrence.travel.domain.travel.TaskScope;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 真实本机PostgreSQL验证聊天、租约与事务；理解端口是显式替身，不调用外部模型。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "travel.engine.enabled=false")
class DialogueIntegrationTest {
    /** 评测账本读取真实PG且按用户/聊天隔离；未知请求与替身型号不能变成真实完整计量。 */
    @Test
    void evaluationEvidenceKeepsUnknownUsageAndOwnerIsolation() {
        var user = actor();
        UUID chat = UUID.randomUUID();
        service.send(user, chat, UUID.randomUUID(), "开始规划杭州旅行");
        UUID budget =
                jdbc.queryForObject(
                        "SELECT budget_id FROM operation_context WHERE user_id=? AND chat_id=?",
                        UUID.class,
                        user.id(),
                        chat);
        jdbc.update(
                "INSERT INTO call_meter(id,run_id,kind,model_id,region,api,token_upper_bound,started_at,status,input_tokens,output_tokens) VALUES(?,?,'MODEL','LOCAL_PROTOCOL_MODEL','LOCAL','LOCAL_FAKE',30,clock_timestamp(),'COMPLETED',11,7)",
                UUID.randomUUID(),
                budget);
        jdbc.update(
                "INSERT INTO call_meter(id,run_id,kind,model_id,region,api,token_upper_bound,started_at,status) VALUES(?,?,'MODEL','LOCAL_PROTOCOL_MODEL','LOCAL','LOCAL_FAKE',30,clock_timestamp(),'UNKNOWN')",
                UUID.randomUUID(),
                budget);
        jdbc.update(
                "INSERT INTO call_meter(id,run_id,kind,model_id,region,api,token_upper_bound,started_at,status) VALUES(?,?,'TOOL','LOCAL_TOOL','LOCAL','LOCAL_FAKE',0,clock_timestamp(),'RESERVED')",
                UUID.randomUUID(),
                budget);
        var reader =
                new com.lawrence.travel.infrastructure.evaluation.JdbcSessionEvaluationEvidence(
                        jdbc, manager, java.util.Set.of("LOCAL_PROTOCOL_MODEL"));
        var capture = reader.read(user.id(), chat, 1);
        assertThat(capture.metrics())
                .containsEntry("actualModelRequests", 1L)
                .containsEntry("unknownModelRequests", 1L)
                .containsEntry("actualInputTokens", 11L)
                .containsEntry("actualOutputTokens", 7L);
        assertThat(capture.metrics())
                .containsEntry("actualToolRequests", 0L)
                .containsEntry("unknownToolRequests", 1L);
        assertThat(capture.outcome()).isEqualTo("PAUSED_SESSION_ACTIVE");
        assertThat(reader.read(UUID.randomUUID(), chat, 1).metrics().get("actualModelRequests"))
                .isZero();
        var mismatch =
                new com.lawrence.travel.infrastructure.evaluation.JdbcSessionEvaluationEvidence(
                                jdbc, manager, java.util.Set.of("OTHER_MODEL"))
                        .read(user.id(), chat, 1);
        assertThat(mismatch.metrics())
                .containsEntry("actualModelRequests", 0L)
                .containsEntry("unrecognizedModelRequests", 2L);
        assertThat(capture.state()).doesNotContainKey("text").doesNotContainKey("reply");
        process(
                result(
                        DialogueIntent.Kind.PLANNING,
                        DialogueIntent.Action.START_PLAN,
                        "开始规划杭州旅行",
                        List.of(new DialogueIntent.Fact("cities", "杭州", "杭州"))));
        assertThat(service.read(user, chat).chat().taskId()).isNotNull();
        assertThat(reader.read(user.id(), chat, 1).outcome()).isEqualTo("PAUSED_SESSION_ACTIVE");
    }

    /**
     * 用户停止运行中的当前回复后，即使理解器迟到返回规划诉求，也不能创建旅行或写回回复。
     * @throws Exception 本地线程/围栏验证失败
     */
    @Test
    void stoppingCurrentReplyRejectsLatePlanningResult() throws Exception {
        var user = actor();
        UUID chat = UUID.randomUUID(), input = UUID.randomUUID();
        String text = "开始规划杭州旅行";
        service.send(user, chat, input, text);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(interpreter.interpret(any()))
                .thenAnswer(
                        invocation -> {
                            entered.countDown();
                            release.await();
                            return result(
                                    DialogueIntent.Kind.PLANNING,
                                    DialogueIntent.Action.START_PLAN,
                                    text,
                                    List.of());
                        });
        var work = service.claimWork().orElseThrow();
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var future = pool.submit(work);
            try {
                assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                service.act(user, chat, input, "WITHDRAW");
            } finally {
                release.countDown();
            }
            future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        var persisted = service.read(user, chat);
        assertThat(persisted.messages().getFirst().state()).isEqualTo("WITHDRAWN");
        assertThat(persisted.messages().getFirst().reply()).isEmpty();
        assertThat(persisted.chat().taskId()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM travel_task WHERE user_id=?",
                                Integer.class,
                                user.id()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM operation_context WHERE user_id=?",
                                Integer.class,
                                user.id()))
                .isEqualTo(1);
    }

    /** 断连后的读取与同消息重放沿用原输入/作业/预算，不重复调用理解器或隐式取消已提交处理。 */
    @Test
    void reconnectReadAndReplayKeepOriginalInputAndBudget() {
        var user = actor();
        UUID chat = UUID.randomUUID(), input = UUID.randomUUID();
        String text = "只咨询一下旅行方式";
        service.send(user, chat, input, text);
        UUID budget =
                jdbc.queryForObject(
                        "SELECT budget_id FROM operation_context WHERE user_id=?",
                        UUID.class,
                        user.id());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(interpreter.interpret(any()))
                .thenAnswer(
                        invocation -> {
                            calls.incrementAndGet();
                            return result(
                                    DialogueIntent.Kind.CONVERSATION,
                                    DialogueIntent.Action.TRAVEL_QA,
                                    text,
                                    List.of());
                        });
        // 没有浏览器连接也会由同一Work推进；重新读取不产生控制命令。
        service.claimWork().orElseThrow().run();
        service.read(user, chat);
        service.send(user, chat, input, text);
        service.read(user, chat);
        assertThat(calls).hasValue(1);
        assertThat(service.claimWork()).isEmpty();
        assertThat(service.read(user, chat).messages()).hasSize(1);
        assertThat(service.read(user, chat).messages().getFirst().state()).isEqualTo("DONE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT budget_id FROM operation_context WHERE user_id=?",
                                UUID.class,
                                user.id()))
                .isEqualTo(budget);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM background_job WHERE user_id=?",
                                Integer.class,
                                user.id()))
                .isEqualTo(1);
    }

    @Autowired private DialogueService service;
    @Autowired private DialogueStore store;
    @Autowired private AccountStore accounts;
    @Autowired private BusinessTransactions tx;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExecutionEngineStore engine;
    @Autowired private com.lawrence.travel.application.port.BudgetLedger ledger;
    @Autowired private NegotiationHandler handler;
    @Autowired private PreferenceService preferences;
    @Autowired private org.springframework.transaction.PlatformTransactionManager manager;
    @MockitoBean private DialogueInterpreter interpreter;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.lawrence.travel.application.interaction.NegotiationService negotiation;

    private final List<UUID> users = new ArrayList<>();

    @Autowired
    private org.springframework.session.SessionRepository<
                    ? extends org.springframework.session.Session>
            sessions;

    @Autowired private com.lawrence.travel.infrastructure.travel.TravelJson json;
    @org.springframework.boot.test.web.server.LocalServerPort private int port;
    private final List<String> sessionIds = new ArrayList<>();

    /** 模型、引用与需求预检固定码实际写入输入和问题事件；不保留底层合成错误正文。 */
    @Test
    void diagnosticCodesPersistWithoutExceptionBody() {
        var user = actor();
        UUID chat = UUID.randomUUID();
        String text = "开始规划北京旅行";
        service.send(user, chat, UUID.randomUUID(), text);
        when(interpreter.interpret(any()))
                .thenThrow(
                        new com.lawrence.travel.application.interaction
                                .DialogueInterpretationFailure(
                                com.lawrence.travel.application.interaction
                                        .DialogueInterpretationFailure.Code.RESULT_DECODE_FAILED));
        service.claimWork().orElseThrow().run();
        assertThat(service.read(user, chat).messages().getLast().error())
                .isEqualTo("DIALOGUE_RESULT_DECODE_FAILED");
        assertThat(
                        jdbc.queryForList(
                                "SELECT code FROM problem_event WHERE user_id=?",
                                String.class,
                                user.id()))
                .contains("DIALOGUE_RESULT_DECODE_FAILED");
        UUID detailed = UUID.randomUUID();
        service.send(user, detailed, UUID.randomUUID(), text);
        org.mockito.Mockito.doThrow(
                        new com.lawrence.travel.application.interaction.DialogueDecodeFailure(
                                com.lawrence.travel.application.interaction.DialogueDecodeFailure
                                        .Kind.ENUM,
                                com.lawrence.travel.application.interaction.DialogueDecodeFailure
                                        .Field.FACT_OPERATION))
                .when(interpreter)
                .interpret(any());
        service.claimWork().orElseThrow().run();
        assertThat(service.read(user, detailed).messages().getLast().error())
                .isEqualTo("DIALOGUE_DECODE_ENUM_FACT_OPERATION");
        assertThat(
                        jdbc.queryForList(
                                "SELECT code FROM problem_event WHERE user_id=?",
                                String.class,
                                user.id()))
                .contains("DIALOGUE_DECODE_ENUM_FACT_OPERATION");
        org.mockito.Mockito.reset(interpreter);
        UUID other = UUID.randomUUID();
        service.send(user, other, UUID.randomUUID(), text);
        process(
                result(
                        DialogueIntent.Kind.PLANNING,
                        DialogueIntent.Action.START_PLAN,
                        "不在本轮原文的依据",
                        List.of()));
        assertThat(service.read(user, other).messages().getLast().error())
                .isEqualTo("DIALOGUE_SOURCE_FAILED");
        UUID invalid = UUID.randomUUID();
        String badDates = "开始规划北京旅行，2026-12-03到2026-12-01";
        service.send(user, invalid, UUID.randomUUID(), badDates);
        process(
                result(
                        DialogueIntent.Kind.PLANNING,
                        DialogueIntent.Action.START_PLAN,
                        badDates,
                        List.of(
                                new DialogueIntent.Fact("startDate", "2026-12-03", "2026-12-03"),
                                new DialogueIntent.Fact("endDate", "2026-12-01", "2026-12-01"))));
        assertThat(service.read(user, invalid).messages().getLast().error())
                .isEqualTo("DIALOGUE_REQUIREMENTS_FAILED");
        assertThat(service.read(user, invalid).chat().taskId()).isNull();
    }

    /** 提交内真实创建任务后注入存储失败，事务必须回滚；错误码可辨别阶段但不回显SQL或异常文本。 */
    @Test
    void commitFailureRollsBackAndRecordsSafeCode() {
        var user = actor();
        UUID chat = UUID.randomUUID();
        String text = "开始规划北京旅行";
        service.send(user, chat, UUID.randomUUID(), text);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            invocation.callRealMethod();
                            throw new com.lawrence.travel.application.travel.TravelStorageFailure(
                                    com.lawrence.travel.application.travel.TravelStorageFailure.Code
                                            .DEPENDENCY_UNAVAILABLE);
                        })
                .when(negotiation)
                .applyInterpreted(any(), any(), any(), any(), any());
        process(
                result(
                        DialogueIntent.Kind.PLANNING,
                        DialogueIntent.Action.START_PLAN,
                        text,
                        List.of(new DialogueIntent.Fact("cities", "北京", "北京"))));
        var message = service.read(user, chat).messages().getLast();
        assertThat(message.error()).isEqualTo("DIALOGUE_COMMIT_DEPENDENCY_UNAVAILABLE");
        assertThat(message.state()).isEqualTo("PAUSED");
        assertThat(service.read(user, chat).chat().taskId()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM travel_task WHERE user_id=?",
                                Integer.class,
                                user.id()))
                .isZero();
    }

    /** 聊天到旅行阶段交接保持trace，实际调用只归旅行预算及唯一日配额。 */
    @Test
    void travelHandoffKeepsTraceAndUniqueCallOwnership() {
        var user = actor();
        UUID chat = UUID.randomUUID();
        service.send(user, chat, UUID.randomUUID(), "去杭州");
        process(
                result(
                        DialogueIntent.Kind.PLANNING,
                        DialogueIntent.Action.START_PLAN,
                        "去杭州",
                        List.of(new DialogueIntent.Fact("cities", "杭州", "杭州"))));
        UUID task = service.read(user, chat).chat().taskId();
        var hint =
                engine.candidates(100).stream()
                        .filter(h -> h.scope().taskId().equals(task))
                        .findFirst()
                        .orElseThrow();
        var claim = engine.claim(hint, "trace_local_test").orElseThrow();
        var ids = engine.correlation(claim);
        com.lawrence.travel.application.port.BudgetLedger.Reservation meter;
        try (var correlated =
                        com.lawrence.travel.application.execution.ExecutionCorrelation.bind(ids);
                var span =
                        com.lawrence.travel.application.execution.ExecutionCorrelation.bindSpan(
                                claim.fence().attemptId())) {
            meter =
                    ledger.reserve(
                            hint.runId(),
                            com.lawrence.travel.domain.execution.control.CallKind.MODEL,
                            new com.lawrence.travel.domain.execution.control.RunLimits(
                                    30,
                                    60,
                                    2,
                                    java.time.Duration.ofSeconds(180),
                                    java.time.Duration.ofMinutes(15)),
                            new com.lawrence.travel.application.port.BudgetLedger.Target(
                                    "LOCAL_FAKE_MODEL", "LOCAL", "LOCAL_FAKE"),
                            100,
                            java.time.Instant.now());
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT parent_span_id FROM call_ownership WHERE id=?",
                                UUID.class,
                                meter.id()))
                .isEqualTo(claim.fence().attemptId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT trace_id FROM execution_trace_span WHERE id=?",
                                UUID.class,
                                meter.id()))
                .isEqualTo(ids.traceId());
        assertThat(ledger.read(hint.runId()).modelCalls()).isEqualTo(1);
        UUID inputBudget =
                jdbc.queryForObject(
                        "SELECT budget_id FROM operation_context WHERE user_id=?",
                        UUID.class,
                        user.id());
        assertThat(ledger.read(inputBudget).modelCalls()).isZero();
        ledger.finish(
                meter.id(),
                new com.lawrence.travel.domain.execution.control.TokenUsage(5, 3),
                java.time.Duration.ZERO);
        engine.fail(claim.fence(), ExecutionEngineStore.Failure.PERMANENT);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT code FROM problem_event WHERE user_id=? AND component='STAGE'",
                                String.class,
                                user.id()))
                .isEqualTo("STAGE_FAILED");
    }

    /** 仅清理测试随机账号，先解除循环引用，再按FK依赖清理；不删除用户真实数据。 */
    @AfterEach
    void clean() {
        sessionIds.forEach(sessions::deleteById);
        tx.required(
                () -> {
                    for (UUID user : users) {
                        var budgetIds =
                                jdbc.queryForList(
                                        "SELECT id FROM execution_run WHERE user_id=? UNION SELECT budget_id FROM dialogue_chat WHERE user_id=? UNION SELECT budget_id FROM operation_context WHERE user_id=?",
                                        UUID.class,
                                        user,
                                        user,
                                        user);
                        jdbc.update("DELETE FROM dialogue_input WHERE user_id=?", user);
                        JobTestCleanup.remove(jdbc, user);
                        jdbc.update("DELETE FROM dialogue_chat WHERE user_id=?", user);
                        jdbc.update(
                                "UPDATE travel_task SET current_run_id=NULL,current_version_id=NULL WHERE user_id=?",
                                user);
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
                                "DELETE FROM artifact_content WHERE artifact_id IN(SELECT id FROM artifact_record WHERE user_id=?)",
                                user);
                        for (String table :
                                List.of(
                                        "artifact_record",
                                        "step_attempt",
                                        "outbox_event",
                                        "execution_step",
                                        "execution_run",
                                        "change_request",
                                        "travel_constraint_revision",
                                        "travel_task",
                                        "travel_idempotency"))
                            jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
                        for (UUID budget : budgetIds) {
                            jdbc.update("DELETE FROM call_meter WHERE run_id=?", budget);
                            jdbc.update("DELETE FROM execution_budget WHERE run_id=?", budget);
                        }
                        for (String table : List.of("preference_request", "user_preference"))
                            jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
                        jdbc.update("DELETE FROM app_user WHERE id=?", user);
                    }
                    return null;
                });
    }

    /**
     * @return 随机不可登录测试账号，不记录真实凭证。
     */
    private UserIdentity actor() {
        UUID id = UUID.randomUUID();
        String name = "di_" + id.toString().replace("-", "").substring(0, 20);
        accounts.insert(id, name, "__NON_LOGIN_TEST_HASH__");
        users.add(id);
        return new UserIdentity(id, name);
    }

    /**
     * @param kind 一级意图
     * @param action 动作
     * @param text 原文依据
     * @param facts 提取
     * @return 显式人工替身结果
     */
    private DialogueIntent.Result result(
            DialogueIntent.Kind kind,
            DialogueIntent.Action action,
            String text,
            List<DialogueIntent.Fact> facts) {
        return new DialogueIntent.Result(
                List.of(new DialogueIntent.Intent(kind, action, text)),
                facts,
                "本地替身回复",
                "",
                null,
                "",
                "",
                "");
    }

    /**
     * @param output 本例替身结果，手动推进唯一Worker所用应用端口
     */
    private void process(DialogueIntent.Result output) {
        when(interpreter.interpret(any())).thenReturn(output);
        service.claimWork().orElseThrow().run();
    }

    /** 模型只在回复承诺修改但无字段时保留澄清，不能生成空需求版本或解除候选屏障。 */
    @Test
    void planningWithoutFactsCannotPretendRequirementsWereSaved() {
        for (var action :
                List.of(
                        DialogueIntent.Action.SUPPLEMENT,
                        DialogueIntent.Action.CHANGE_REQUIREMENTS,
                        DialogueIntent.Action.REVISE_ITINERARY)) {
            var user = actor();
            UUID chat = UUID.randomUUID();
            service.send(user, chat, UUID.randomUUID(), "开始规划杭州旅行");
            process(
                    result(
                            DialogueIntent.Kind.PLANNING,
                            DialogueIntent.Action.START_PLAN,
                            "开始规划杭州旅行",
                            List.of(new DialogueIntent.Fact("cities", "杭州", "杭州"))));
            UUID task = service.read(user, chat).chat().taskId();
            long revision =
                    jdbc.queryForObject(
                            "SELECT constraint_revision FROM travel_task WHERE id=?",
                            Long.class,
                            task);
            long generation =
                    jdbc.queryForObject(
                            "SELECT execution_generation FROM travel_task WHERE id=?",
                            Long.class,
                            task);
            String text = "想多体验当地生活，减少来回赶路";
            service.send(user, chat, UUID.randomUUID(), text);
            var misleading =
                    new DialogueIntent.Result(
                            List.of(
                                    new DialogueIntent.Intent(
                                            DialogueIntent.Kind.PLANNING, action, text)),
                            List.of(),
                            "我会纳入规划",
                            "",
                            null,
                            "",
                            "",
                            "");
            process(misleading);
            var message = service.read(user, chat).messages().getLast();
            assertThat(message.state()).isEqualTo("CLARIFY");
            assertThat(message.reply()).contains("未更新旅行需求").doesNotContain("我会纳入规划");
            assertThat(store.blocked(user.id(), task)).isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT constraint_revision FROM travel_task WHERE id=?",
                                    Long.class,
                                    task))
                    .isEqualTo(revision);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT execution_generation FROM travel_task WHERE id=?",
                                    Long.class,
                                    task))
                    .isEqualTo(generation);
        }
    }

    /** 本地解释替身验证节奏软偏好的真实落库，P02裁判拒绝把同一愿望硬化；不声称模型分类已实测通过。 */
    @Test
    void softPaceIsPersistedAndAcceptanceRejectsHardenedWish() {
        for (String field : List.of("pace", "hardRequirements")) {
            var user = actor();
            UUID chat = UUID.randomUUID();
            String initial = "去北京，2026-11-01至2026-11-03，2人";
            service.send(user, chat, UUID.randomUUID(), initial);
            process(
                    result(
                            DialogueIntent.Kind.PLANNING,
                            DialogueIntent.Action.START_PLAN,
                            initial,
                            List.of(
                                    new DialogueIntent.Fact("cities", "北京", "北京"),
                                            new DialogueIntent.Fact(
                                                    "startDate", "2026-11-01", "2026-11-01"),
                                    new DialogueIntent.Fact("endDate", "2026-11-03", "2026-11-03"),
                                            new DialogueIntent.Fact("travelers", "2", "2人"))));
            UUID task = service.read(user, chat).chat().taskId();
            commitUnderstanding(task);
            String wish = "收尾那天想少赶路，多留些休息时间";
            service.send(user, chat, UUID.randomUUID(), wish);
            process(
                    result(
                            DialogueIntent.Kind.PLANNING,
                            DialogueIntent.Action.SUPPLEMENT,
                            wish,
                            List.of(new DialogueIntent.Fact(field, wish, wish))));
            commitUnderstanding(task);
            var persisted =
                    jdbc.queryForMap(
                            """
                    SELECT q->>'kind' AS kind,q->>'value' AS value FROM travel_task t
                    JOIN travel_constraint_revision r ON r.task_id=t.id AND r.user_id=t.user_id AND r.revision=t.constraint_revision,
                    jsonb_array_elements(r.payload->'requirements') q WHERE t.id=? AND q->>'field'=?
                    """,
                            task,
                            field);
            assertThat(persisted)
                    .containsEntry("kind", field.equals("pace") ? "SOFT" : "HARD")
                    .containsEntry("value", wish);
            var evidence =
                    new com.lawrence.travel.infrastructure.evaluation.JdbcSessionEvaluationEvidence(
                            jdbc, manager, java.util.Set.of("LOCAL_PROTOCOL_MODEL"));
            var judge =
                    new com.lawrence.travel.infrastructure.evaluation
                            .JdbcConversationAcceptanceJudge(
                            jdbc,
                            manager,
                            new com.lawrence.travel.application.safety.SafetyGate(
                                    List::of, (point, result) -> {}),
                            "LOCAL_PROTOCOL_MODEL",
                            false);
            assertThat(judge.check("P02", evidence.read(user.id(), chat, 2)))
                    .containsEntry("CURRENT_REQUIREMENTS_PRESERVED", field.equals("pace"));
        }
    }

    /**
     * @param task 本测试私有旅行；手动提交一个UNDERSTAND阶段，不调用模型或推进资料调查。
     */
    private void commitUnderstanding(UUID task) {
        var hint =
                engine.candidates(100).stream()
                        .filter(h -> h.scope().taskId().equals(task))
                        .findFirst()
                        .orElseThrow();
        var claim = engine.claim(hint, "soft_pace_test").orElseThrow();
        engine.commit(engine.prepare(claim, handler.execute(claim)));
    }

    /** 4000字规划从聊天到需求提交保留完整原文，超长在理解前拒绝。 */
    @Test
    void longPlanningInputUsesTheSameLimitAcrossBothInboxes() {
        var user = actor();
        UUID chat = UUID.randomUUID(), input = UUID.randomUUID();
        String text = "去杭州" + "补".repeat(3997);
        service.send(user, chat, input, text);
        process(
                result(
                        DialogueIntent.Kind.PLANNING,
                        DialogueIntent.Action.START_PLAN,
                        text,
                        List.of(new DialogueIntent.Fact("cities", "杭州", "杭州"))));
        var view = service.read(user, chat);
        assertThat(view.messages().getFirst().state()).isEqualTo("DONE");
        commitUnderstanding(view.chat().taskId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT body->>'text' FROM conversation_message WHERE user_id=? AND id=?",
                                String.class,
                                user.id(),
                                input))
                .isEqualTo(text);
        assertThatThrownBy(() -> service.send(user, chat, UUID.randomUUID(), text + "补"))
                .isInstanceOf(RuntimeException.class);
        assertThat(service.read(user, chat).messages()).hasSize(1);
    }

    /** ADD保留有效要求，REPLACE经过对话解释转换、可靠收件及阶段提交仍保持替换语义。 */
    @Test
    void dialoguePreservesPreferenceOperationThroughStageCommit() {
        var user = actor();
        UUID chat = UUID.randomUUID();
        String initial = "去杭州，每天午后休息";
        service.send(user, chat, UUID.randomUUID(), initial);
        process(
                result(
                        DialogueIntent.Kind.PLANNING,
                        DialogueIntent.Action.START_PLAN,
                        initial,
                        List.of(
                                new DialogueIntent.Fact("cities", "杭州", "杭州"),
                                new DialogueIntent.Fact("pace", "每天午后休息", "每天午后休息"))));
        UUID task = service.read(user, chat).chat().taskId();
        commitUnderstanding(task);
        String addition = "最后一天早点结束";
        service.send(user, chat, UUID.randomUUID(), addition);
        process(
                result(
                        DialogueIntent.Kind.PLANNING,
                        DialogueIntent.Action.SUPPLEMENT,
                        addition,
                        List.of(new DialogueIntent.Fact("pace", addition, addition))));
        commitUnderstanding(task);
        assertThat(currentPace(user.id(), task)).isEqualTo("每天午后休息\n最后一天早点结束");
        String replacement = "之前节奏偏好全部改为紧凑";
        service.send(user, chat, UUID.randomUUID(), replacement);
        process(
                result(
                        DialogueIntent.Kind.PLANNING,
                        DialogueIntent.Action.CHANGE_REQUIREMENTS,
                        replacement,
                        List.of(
                                new DialogueIntent.Fact(
                                        "pace",
                                        "紧凑",
                                        replacement,
                                        com.lawrence.travel.domain.interaction.RequirementFields
                                                .PreferenceOperation.REPLACE))));
        commitUnderstanding(task);
        assertThat(currentPace(user.id(), task)).isEqualTo("紧凑");
    }

    /**
     * @param user 本测试私有用户
     * @param task 私有旅行
     * @return 当前权威版本的节奏偏好
     */
    private String currentPace(UUID user, UUID task) {
        return jdbc.queryForObject(
                "SELECT q->>'value' FROM travel_task t JOIN travel_constraint_revision r ON r.user_id=t.user_id AND r.task_id=t.id AND r.revision=t.constraint_revision, jsonb_array_elements(r.payload->'requirements') q WHERE t.user_id=? AND t.id=? AND q->>'field'='pace'",
                String.class,
                user,
                task);
    }

    /** 独立咨询不创建旅行，原文及回复跨读取恢复，重复输入不会产生第二条。 */
    @Test
    void conversationDoesNotCreateTripAndReceiveIsIdempotent() {
        var user = actor();
        UUID chat = UUID.randomUUID(), input = UUID.randomUUID();
        new org.springframework.transaction.support.TransactionTemplate(manager)
                .execute(
                        status -> {
                            store.create(user.id(), chat, "预算六千够吗");
                            return store.receive(user.id(), chat, input, "预算六千够吗");
                        });
        service.send(user, chat, input, "预算六千够吗");
        service.send(user, chat, input, "预算六千够吗");
        process(
                result(
                        DialogueIntent.Kind.CONVERSATION,
                        DialogueIntent.Action.TRAVEL_QA,
                        "预算六千够吗",
                        List.of()));
        var view = service.read(user, chat);
        assertThat(view.chat().taskId()).isNull();
        assertThat(view.messages()).hasSize(1);
        assertThat(view.messages().getFirst().state()).isEqualTo("DONE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM travel_task WHERE user_id=?",
                                Integer.class,
                                user.id()))
                .isZero();
        assertThatThrownBy(() -> service.send(user, chat, input, "改为七千"))
                .isInstanceOf(RuntimeException.class);
    }

    /** 明确规划才建立一对一任务，已提取字段直接进入UNDERSTAND，不进行第二次模型理解。 */
    @Test
    void planningLinksOneTripAndConsultingDoesNotSupersedeIt() {
        var user = actor();
        UUID chat = UUID.randomUUID();
        service.send(user, chat, UUID.randomUUID(), "去杭州");
        process(
                result(
                        DialogueIntent.Kind.PLANNING,
                        DialogueIntent.Action.START_PLAN,
                        "去杭州",
                        List.of(new DialogueIntent.Fact("cities", "杭州", "杭州"))));
        UUID task = service.read(user, chat).chat().taskId();
        assertThat(task).isNotNull();
        UUID trace =
                jdbc.queryForObject(
                        "SELECT trace_id FROM operation_context WHERE user_id=?",
                        UUID.class,
                        user.id());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT trace_id FROM execution_run WHERE user_id=?",
                                UUID.class,
                                user.id()))
                .isEqualTo(trace);
        var hint =
                engine.candidates(100).stream()
                        .filter(h -> h.scope().taskId().equals(task))
                        .findFirst()
                        .orElseThrow();
        var claim = engine.claim(hint, "dialogue_test").orElseThrow();
        var output = handler.execute(claim);
        engine.commit(engine.prepare(claim, output));
        long generation =
                jdbc.queryForObject(
                        "SELECT execution_generation FROM travel_task WHERE id=?",
                        Long.class,
                        task);
        service.send(user, chat, UUID.randomUUID(), "预算六千够吗");
        assertThat(store.blocked(user.id(), task)).isTrue();
        process(
                result(
                        DialogueIntent.Kind.CONVERSATION,
                        DialogueIntent.Action.TRAVEL_QA,
                        "预算六千够吗",
                        List.of()));
        assertThat(store.blocked(user.id(), task)).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT execution_generation FROM travel_task WHERE id=?",
                                Long.class,
                                task))
                .isEqualTo(generation);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM travel_task WHERE user_id=?",
                                Integer.class,
                                user.id()))
                .isEqualTo(1);
    }

    /** 失败和未知用量不自动清零；显式重试使用同一预算，撤回保留原文并阻止迟到结果。 */
    @Test
    void failureRetryAndWithdrawalKeepOriginalBudgetAndFence() {
        var user = actor();
        UUID chat = UUID.randomUUID(), input = UUID.randomUUID();
        service.send(user, chat, input, "你好");
        when(interpreter.interpret(any()))
                .thenThrow(new IllegalStateException("SYNTHETIC_FAILURE"));
        service.claimWork().orElseThrow().run();
        assertThat(service.read(user, chat).messages().getFirst().state()).isEqualTo("PAUSED");
        UUID budget =
                jdbc.queryForObject(
                        "SELECT budget_id FROM operation_context WHERE user_id=?",
                        UUID.class,
                        user.id());
        jdbc.update("UPDATE execution_budget SET model_calls=7 WHERE run_id=?", budget);
        service.act(user, chat, input, "RETRY");
        var claim = tx.required(store::claim).orElseThrow();
        assertThat(claim.budgetId()).isEqualTo(budget);
        service.act(user, chat, input, "WITHDRAW");
        assertThat(tx.required(() -> store.guard(claim))).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT model_calls FROM execution_budget WHERE run_id=?",
                                Long.class,
                                budget))
                .isEqualTo(7);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT active_millis FROM execution_budget WHERE run_id=?",
                                Long.class,
                                budget))
                .isGreaterThanOrEqualTo(239000L);
        assertThat(service.read(user, chat).messages().getFirst().text()).isEqualTo("你好");
    }

    /** 同聊天只有一条有效租约，失联重领递增令牌，旧持有者不能提交。 */
    @Test
    void expiredLeaseRecoveryRejectsOldClaim() {
        var user = actor();
        UUID chat = UUID.randomUUID();
        service.send(user, chat, UUID.randomUUID(), "你好");
        var old = tx.required(store::claim).orElseThrow();
        assertThat(tx.required(store::claim)).isEmpty();
        jdbc.update(
                "UPDATE dialogue_input SET active_since=clock_timestamp()-interval '10 seconds',lease_until=clock_timestamp()-interval '1 second' WHERE user_id=?",
                user.id());
        jdbc.update(
                "UPDATE background_job SET active_since=clock_timestamp()-interval '10 seconds',lease_until=clock_timestamp()-interval '1 second' WHERE user_id=?",
                user.id());
        var next = tx.required(store::claim).orElseThrow();
        assertThat(next.token()).isGreaterThan(old.token());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT active_millis FROM execution_budget WHERE run_id=?",
                                Long.class,
                                old.budgetId()))
                .isGreaterThanOrEqualTo(9000L);
        assertThat(tx.required(() -> store.guard(old))).isFalse();
        assertThat(tx.required(() -> store.guard(next))).isTrue();
    }

    /** 保存偏好必须确认，重复确认只产生一条；后来删除同样先确认。 */
    @Test
    void preferenceOperationsRequireExplicitConfirmation() {
        var user = actor();
        UUID chat = UUID.randomUUID(), input = UUID.randomUUID();
        service.send(user, chat, input, "记住我不吃辣");
        var save =
                new DialogueIntent.Result(
                        List.of(
                                new DialogueIntent.Intent(
                                        DialogueIntent.Kind.OPERATION,
                                        DialogueIntent.Action.SAVE_PREFERENCE,
                                        "记住我不吃辣")),
                        List.of(),
                        "请核对",
                        "",
                        null,
                        "diet",
                        "不吃辣",
                        "");
        process(save);
        assertThat(preferences.read(user).entries()).isEmpty();
        service.act(user, chat, input, "CONFIRM");
        service.act(user, chat, input, "CONFIRM");
        assertThat(preferences.read(user).entries()).hasSize(1);
        assertThat(service.read(user, chat).messages().getFirst().reply())
                .isEqualTo("已保存你确认的长期偏好。");
        var pref = preferences.read(user).entries().getFirst();
        UUID remove = UUID.randomUUID();
        service.send(user, chat, remove, "删除不吃辣偏好");
        process(
                new DialogueIntent.Result(
                        List.of(
                                new DialogueIntent.Intent(
                                        DialogueIntent.Kind.OPERATION,
                                        DialogueIntent.Action.DELETE_PREFERENCE,
                                        "删除不吃辣偏好")),
                        List.of(),
                        "请核对",
                        "",
                        pref.id(),
                        "",
                        "",
                        ""));
        assertThat(preferences.read(user).entries()).hasSize(1);
        service.act(user, chat, remove, "CONFIRM");
        assertThat(preferences.read(user).entries()).isEmpty();
    }

    /** 跨用户读取、确认和同名ID收件都不允许访问别人数据。 */
    @Test
    void ownershipAndInvalidInterpretationsCannotWriteRequirements() {
        var user = actor();
        var foreign = actor();
        UUID chat = UUID.randomUUID(), input = UUID.randomUUID();
        service.send(user, chat, input, "你好");
        assertThatThrownBy(() -> service.read(foreign, chat)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.act(foreign, chat, input, "WITHDRAW"))
                .isInstanceOf(RuntimeException.class);
        process(
                result(
                        DialogueIntent.Kind.PLANNING,
                        DialogueIntent.Action.START_PLAN,
                        "原文不存在",
                        List.of()));
        assertThat(service.read(user, chat).messages().getFirst().state()).isEqualTo("PAUSED");
        assertThat(service.read(user, chat).chat().taskId()).isNull();
    }

    /** 无关闲聊不清除旧澄清；明确回答后可继承原消息事实，原文和两轮消息均保留。 */
    @Test
    void clarificationPreservesPendingFactsUntilExplicitlyResolved() {
        var user = actor();
        UUID chat = UUID.randomUUID(), old = UUID.randomUUID();
        service.send(user, chat, old, "预算改成六千");
        process(
                new DialogueIntent.Result(
                        List.of(
                                new DialogueIntent.Intent(
                                        DialogueIntent.Kind.UNCLEAR,
                                        DialogueIntent.Action.CLARIFY,
                                        "预算改成六千")),
                        List.of(),
                        "预算按每人还是全组？",
                        "请明确口径",
                        null,
                        "",
                        "",
                        ""));
        service.send(user, chat, UUID.randomUUID(), "你好");
        process(
                result(
                        DialogueIntent.Kind.CONVERSATION,
                        DialogueIntent.Action.CHAT,
                        "你好",
                        List.of()));
        assertThat(service.read(user, chat).messages().getFirst().state()).isEqualTo("CLARIFY");
        service.send(user, chat, UUID.randomUUID(), "按全组计算");
        process(
                new DialogueIntent.Result(
                        List.of(
                                new DialogueIntent.Intent(
                                        DialogueIntent.Kind.PLANNING,
                                        DialogueIntent.Action.SUPPLEMENT,
                                        "按全组计算")),
                        List.of(
                                new DialogueIntent.Fact("budgetCny", "6000", "六千"),
                                new DialogueIntent.Fact("budgetRule", "PER_GROUP", "按全组计算")),
                        "已明确口径",
                        "",
                        null,
                        "",
                        "",
                        "",
                        List.of(old)));
        var view = service.read(user, chat);
        assertThat(view.messages().getFirst().state()).isEqualTo("DONE");
        assertThat(view.chat().taskId()).isNotNull();
        assertThat(view.messages()).hasSize(3);
    }

    /** 新修改在识别完成后登记，撤销原运行的提交资格；咨询不发生这种转换。 */
    @Test
    void recognizedChangeSupersedesOldExecutionAndStopIsImmediate() {
        var user = actor();
        UUID chat = UUID.randomUUID();
        service.send(user, chat, UUID.randomUUID(), "去杭州");
        process(
                result(
                        DialogueIntent.Kind.PLANNING,
                        DialogueIntent.Action.START_PLAN,
                        "去杭州",
                        List.of(new DialogueIntent.Fact("cities", "杭州", "杭州"))));
        var task = service.read(user, chat).chat().taskId();
        UUID oldRun =
                jdbc.queryForObject(
                        "SELECT current_run_id FROM travel_task WHERE id=?", UUID.class, task);
        service.send(user, chat, UUID.randomUUID(), "改为苏州");
        process(
                result(
                        DialogueIntent.Kind.PLANNING,
                        DialogueIntent.Action.CHANGE_REQUIREMENTS,
                        "改为苏州",
                        List.of(new DialogueIntent.Fact("cities", "苏州", "苏州"))));
        assertThat(engine.run(new TaskScope(user.id(), task), oldRun).state().name())
                .isEqualTo("SUPERSEDED");
        var newRun =
                jdbc.queryForObject(
                        "SELECT current_run_id FROM travel_task WHERE id=?", UUID.class, task);
        service.send(user, chat, UUID.randomUUID(), "停止规划");
        assertThat(service.claimWork()).isEmpty();
        assertThat(engine.run(new TaskScope(user.id(), task), newRun).state().name())
                .isEqualTo("CANCELLED");
    }

    /** 两个真实数据库领取事务竞争时，同一聊天仅一方获得租约。 */
    @Test
    void concurrentClaimsOnlyHaveOneOwner() throws Exception {
        var user = actor();
        UUID chat = UUID.randomUUID();
        service.send(user, chat, UUID.randomUUID(), "你好");
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> work =
                    () -> {
                        gate.await();
                        return tx.required(store::claim).isPresent();
                    };
            var first = threads.submit(work);
            var second = threads.submit(work);
            gate.countDown();
            assertThat(
                            List.of(
                                    first.get(5, java.util.concurrent.TimeUnit.SECONDS),
                                    second.get(5, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    /** 旧任务关联幂等返回原聊天，不创建第二个旅行、会话或预算。 */
    @Test
    void legacyTaskAttachReusesExistingChat() {
        var user = actor();
        UUID chat = UUID.randomUUID();
        service.send(user, chat, UUID.randomUUID(), "去杭州");
        process(
                result(
                        DialogueIntent.Kind.PLANNING,
                        DialogueIntent.Action.START_PLAN,
                        "去杭州",
                        List.of(new DialogueIntent.Fact("cities", "杭州", "杭州"))));
        UUID task = service.read(user, chat).chat().taskId();
        assertThat(service.attach(user, UUID.randomUUID(), task).chat().id()).isEqualTo(chat);
        assertThat(service.list(user, 0)).hasSize(1);
    }

    /** 超出最近100条窗口的失败输入仍可见并可撤回，不能留下用户无法解除的屏障。 */
    @Test
    void unresolvedMessagesSurviveHistoryWindow() {
        var user = actor();
        UUID chat = UUID.randomUUID(), input = UUID.randomUUID();
        service.send(user, chat, input, "未完成输入");
        jdbc.update("UPDATE dialogue_input SET state='PAUSED' WHERE user_id=?", user.id());
        jdbc.update(
                "INSERT INTO dialogue_input(id,user_id,chat_id,text,state) SELECT gen_random_uuid(),?,?,'本地已完成历史','DONE' FROM generate_series(1,105)",
                user.id(),
                chat);
        var view = service.read(user, chat);
        assertThat(view.messages()).hasSize(101);
        assertThat(view.messages().getFirst().id()).isEqualTo(input);
        service.act(user, chat, input, "WITHDRAW");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM dialogue_input WHERE user_id=? AND id=?",
                                String.class,
                                user.id(),
                                input))
                .isEqualTo("WITHDRAWN");
    }

    /**
     * @param actor 测试主体
     * @param repository 本机会话
     * @param <S> 会话实现
     * @return 仅进程内使用的Cookie，不打印、不写日志
     */
    private <S extends org.springframework.session.Session> String cookie(
            UserIdentity actor, org.springframework.session.SessionRepository<S> repository) {
        var session = repository.createSession();
        var context =
                org.springframework.security.core.context.SecurityContextHolder
                        .createEmptyContext();
        context.setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                        .authenticated(
                                actor,
                                null,
                                List.of(
                                        new org.springframework.security.core.authority
                                                .SimpleGrantedAuthority("ROLE_USER"))));
        session.setAttribute(
                org.springframework.security.web.context.HttpSessionSecurityContextRepository
                        .SPRING_SECURITY_CONTEXT_KEY,
                context);
        repository.save(session);
        sessionIds.add(session.getId());
        return "SESSION="
                + java.util.Base64.getEncoder()
                        .encodeToString(
                                session.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 新接口保持认证、CSRF及跨用户归属，不因聊天入口新增而匿名放行。 */
    @Test
    void chatHttpRequiresAuthenticationCsrfAndOwnership() throws Exception {
        var user = actor();
        UUID chat = UUID.randomUUID();
        String root = "http://127.0.0.1:" + port;
        var client = java.net.http.HttpClient.newHttpClient();
        var endpoint = java.net.URI.create(root + "/api/v1/chats/" + chat + "/messages");
        assertThat(
                        client.send(
                                        java.net.http.HttpRequest.newBuilder(
                                                        java.net.URI.create(root + "/api/v1/chats"))
                                                .GET()
                                                .build(),
                                        java.net.http.HttpResponse.BodyHandlers.discarding())
                                .statusCode())
                .isEqualTo(401);
        String auth = cookie(user, sessions);
        String body = json.encode(java.util.Map.of("inputId", UUID.randomUUID(), "text", "你好"));
        assertThat(
                        client.send(
                                        java.net.http.HttpRequest.newBuilder(endpoint)
                                                .header("Cookie", auth)
                                                .header("Content-Type", "application/json")
                                                .POST(
                                                        java.net.http.HttpRequest.BodyPublishers
                                                                .ofString(body))
                                                .build(),
                                        java.net.http.HttpResponse.BodyHandlers.discarding())
                                .statusCode())
                .isEqualTo(403);
        var csrf =
                client.send(
                        java.net.http.HttpRequest.newBuilder(
                                        java.net.URI.create(root + "/api/v1/auth/csrf"))
                                .header("Cookie", auth)
                                .GET()
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
        var token = json.decode(csrf.body(), java.util.Map.class);
        assertThat(
                        client.send(
                                        java.net.http.HttpRequest.newBuilder(endpoint)
                                                .header("Cookie", auth)
                                                .header("Content-Type", "application/json")
                                                .header(
                                                        (String) token.get("headerName"),
                                                        (String) token.get("token"))
                                                .POST(
                                                        java.net.http.HttpRequest.BodyPublishers
                                                                .ofString(body))
                                                .build(),
                                        java.net.http.HttpResponse.BodyHandlers.discarding())
                                .statusCode())
                .isEqualTo(200);
        String foreign = cookie(actor(), sessions);
        assertThat(
                        client.send(
                                        java.net.http.HttpRequest.newBuilder(
                                                        java.net.URI.create(
                                                                root + "/api/v1/chats/" + chat))
                                                .header("Cookie", foreign)
                                                .GET()
                                                .build(),
                                        java.net.http.HttpResponse.BodyHandlers.discarding())
                                .statusCode())
                .isEqualTo(404);
    }
}
