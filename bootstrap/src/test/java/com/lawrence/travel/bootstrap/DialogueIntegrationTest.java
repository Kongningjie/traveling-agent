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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "travel.engine.enabled=false")
class DialogueIntegrationTest {
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
    private final List<UUID> users = new ArrayList<>();
    @Autowired private org.springframework.session.SessionRepository<? extends org.springframework.session.Session> sessions;
    @Autowired private com.lawrence.travel.infrastructure.travel.TravelJson json;
    @org.springframework.boot.test.web.server.LocalServerPort private int port;
    private final List<String> sessionIds = new ArrayList<>();
    /** 聊天到旅行阶段交接保持trace，实际调用只归旅行预算及唯一日配额。 */
    @Test void travelHandoffKeepsTraceAndUniqueCallOwnership() {
        var user=actor(); UUID chat=UUID.randomUUID(); service.send(user,chat,UUID.randomUUID(),"去杭州");
        process(result(DialogueIntent.Kind.PLANNING,DialogueIntent.Action.START_PLAN,"去杭州",List.of(new DialogueIntent.Fact("cities","杭州","杭州"))));
        UUID task=service.read(user,chat).chat().taskId(); var hint=engine.candidates(100).stream().filter(h -> h.scope().taskId().equals(task)).findFirst().orElseThrow();
        var claim=engine.claim(hint,"trace_local_test").orElseThrow(); var ids=engine.correlation(claim);
        com.lawrence.travel.application.port.BudgetLedger.Reservation meter;
        try(var correlated=com.lawrence.travel.application.execution.ExecutionCorrelation.bind(ids); var span=com.lawrence.travel.application.execution.ExecutionCorrelation.bindSpan(claim.fence().attemptId())) {
            meter=ledger.reserve(hint.runId(),com.lawrence.travel.domain.execution.control.CallKind.MODEL,new com.lawrence.travel.domain.execution.control.RunLimits(30,60,2,java.time.Duration.ofSeconds(180),java.time.Duration.ofMinutes(15)),new com.lawrence.travel.application.port.BudgetLedger.Target("LOCAL_FAKE_MODEL","LOCAL","LOCAL_FAKE"),100,java.time.Instant.now());
        }
        assertThat(jdbc.queryForObject("SELECT parent_span_id FROM call_ownership WHERE id=?",UUID.class,meter.id())).isEqualTo(claim.fence().attemptId());
        assertThat(jdbc.queryForObject("SELECT trace_id FROM execution_trace_span WHERE id=?",UUID.class,meter.id())).isEqualTo(ids.traceId());
        assertThat(ledger.read(hint.runId()).modelCalls()).isEqualTo(1);
        UUID inputBudget=jdbc.queryForObject("SELECT budget_id FROM operation_context WHERE user_id=?",UUID.class,user.id()); assertThat(ledger.read(inputBudget).modelCalls()).isZero();
        ledger.finish(meter.id(),new com.lawrence.travel.domain.execution.control.TokenUsage(5,3),java.time.Duration.ZERO);
        engine.fail(claim.fence(),ExecutionEngineStore.Failure.PERMANENT);
        assertThat(jdbc.queryForObject("SELECT code FROM problem_event WHERE user_id=? AND component='STAGE'",String.class,user.id())).isEqualTo("STAGE_FAILED");
    }
    /** 仅清理测试随机账号，先解除循环引用，再按FK依赖清理；不删除用户真实数据。 */
    @AfterEach void clean() {
        sessionIds.forEach(sessions::deleteById);
        tx.required(() -> {
            for (UUID user : users) {
                var budgetIds = jdbc.queryForList("SELECT id FROM execution_run WHERE user_id=? UNION SELECT budget_id FROM dialogue_chat WHERE user_id=? UNION SELECT budget_id FROM operation_context WHERE user_id=?", UUID.class, user, user, user);
                jdbc.update("DELETE FROM dialogue_input WHERE user_id=?", user);
                JobTestCleanup.remove(jdbc, user);
                jdbc.update("DELETE FROM dialogue_chat WHERE user_id=?", user);
                jdbc.update("UPDATE travel_task SET current_run_id=NULL,current_version_id=NULL WHERE user_id=?", user);
                for (String table : List.of("negotiation_card", "conversation_message", "decision_response", "decision_request", "run_event", "execution_start_request")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
                jdbc.update("DELETE FROM artifact_content WHERE artifact_id IN(SELECT id FROM artifact_record WHERE user_id=?)", user);
                for (String table : List.of("artifact_record", "step_attempt", "outbox_event", "execution_step", "execution_run", "change_request", "travel_constraint_revision", "travel_task", "travel_idempotency")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
                for (UUID budget : budgetIds) { jdbc.update("DELETE FROM call_meter WHERE run_id=?", budget); jdbc.update("DELETE FROM execution_budget WHERE run_id=?", budget); }
                for (String table : List.of("preference_request", "user_preference")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
                jdbc.update("DELETE FROM app_user WHERE id=?", user);
            }
            return null;
        });
    }
    /** @return 随机不可登录测试账号，不记录真实凭证。 */
    private UserIdentity actor() { UUID id = UUID.randomUUID(); String name = "di_" + id.toString().replace("-", "").substring(0, 20); accounts.insert(id, name, "__NON_LOGIN_TEST_HASH__"); users.add(id); return new UserIdentity(id, name); }
    /** @param kind 一级意图 @param action 动作 @param text 原文依据 @param facts 提取 @return 显式人工替身结果 */
    private DialogueIntent.Result result(DialogueIntent.Kind kind, DialogueIntent.Action action, String text, List<DialogueIntent.Fact> facts) { return new DialogueIntent.Result(List.of(new DialogueIntent.Intent(kind, action, text)), facts, "本地替身回复", "", null, "", "", ""); }
    /** @param output 本例替身结果，手动推进唯一Worker所用应用端口 */
    private void process(DialogueIntent.Result output) { when(interpreter.interpret(any())).thenReturn(output); service.claimWork().orElseThrow().run(); }
    /** 独立咨询不创建旅行，原文及回复跨读取恢复，重复输入不会产生第二条。 */
    @Test void conversationDoesNotCreateTripAndReceiveIsIdempotent() {
        var user = actor(); UUID chat = UUID.randomUUID(), input = UUID.randomUUID();
        new org.springframework.transaction.support.TransactionTemplate(manager).execute(status -> { store.create(user.id(), chat, "预算六千够吗"); return store.receive(user.id(), chat, input, "预算六千够吗"); });
        service.send(user, chat, input, "预算六千够吗"); service.send(user, chat, input, "预算六千够吗");
        process(result(DialogueIntent.Kind.CONVERSATION, DialogueIntent.Action.TRAVEL_QA, "预算六千够吗", List.of()));
        var view = service.read(user, chat); assertThat(view.chat().taskId()).isNull(); assertThat(view.messages()).hasSize(1); assertThat(view.messages().getFirst().state()).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM travel_task WHERE user_id=?", Integer.class, user.id())).isZero();
        assertThatThrownBy(() -> service.send(user, chat, input, "改为七千")).isInstanceOf(RuntimeException.class);
    }
    /** 明确规划才建立一对一任务，已提取字段直接进入UNDERSTAND，不进行第二次模型理解。 */
    @Test void planningLinksOneTripAndConsultingDoesNotSupersedeIt() {
        var user = actor(); UUID chat = UUID.randomUUID(); service.send(user, chat, UUID.randomUUID(), "去杭州");
        process(result(DialogueIntent.Kind.PLANNING, DialogueIntent.Action.START_PLAN, "去杭州", List.of(new DialogueIntent.Fact("cities", "杭州", "杭州"))));
        UUID task = service.read(user, chat).chat().taskId(); assertThat(task).isNotNull();
        UUID trace=jdbc.queryForObject("SELECT trace_id FROM operation_context WHERE user_id=?",UUID.class,user.id());
        assertThat(jdbc.queryForObject("SELECT trace_id FROM execution_run WHERE user_id=?",UUID.class,user.id())).isEqualTo(trace);
        var hint = engine.candidates(100).stream().filter(h -> h.scope().taskId().equals(task)).findFirst().orElseThrow();
        var claim = engine.claim(hint, "dialogue_test").orElseThrow();
        var output = handler.execute(claim); engine.commit(engine.prepare(claim, output));
        long generation = jdbc.queryForObject("SELECT execution_generation FROM travel_task WHERE id=?", Long.class, task);
        service.send(user, chat, UUID.randomUUID(), "预算六千够吗"); assertThat(store.blocked(user.id(), task)).isTrue();
        process(result(DialogueIntent.Kind.CONVERSATION, DialogueIntent.Action.TRAVEL_QA, "预算六千够吗", List.of()));
        assertThat(store.blocked(user.id(), task)).isFalse();
        assertThat(jdbc.queryForObject("SELECT execution_generation FROM travel_task WHERE id=?", Long.class, task)).isEqualTo(generation);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM travel_task WHERE user_id=?", Integer.class, user.id())).isEqualTo(1);
    }
    /** 失败和未知用量不自动清零；显式重试使用同一预算，撤回保留原文并阻止迟到结果。 */
    @Test void failureRetryAndWithdrawalKeepOriginalBudgetAndFence() {
        var user = actor(); UUID chat = UUID.randomUUID(), input = UUID.randomUUID(); service.send(user, chat, input, "你好");
        when(interpreter.interpret(any())).thenThrow(new IllegalStateException("SYNTHETIC_FAILURE")); service.claimWork().orElseThrow().run();
        assertThat(service.read(user, chat).messages().getFirst().state()).isEqualTo("PAUSED");
        UUID budget = jdbc.queryForObject("SELECT budget_id FROM operation_context WHERE user_id=?", UUID.class, user.id());
        jdbc.update("UPDATE execution_budget SET model_calls=7 WHERE run_id=?", budget);
        service.act(user, chat, input, "RETRY");
        var claim = tx.required(store::claim).orElseThrow(); assertThat(claim.budgetId()).isEqualTo(budget);
        service.act(user, chat, input, "WITHDRAW"); assertThat(tx.required(() -> store.guard(claim))).isFalse();
        assertThat(jdbc.queryForObject("SELECT model_calls FROM execution_budget WHERE run_id=?", Long.class, budget)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT active_millis FROM execution_budget WHERE run_id=?", Long.class, budget)).isGreaterThanOrEqualTo(239000L);
        assertThat(service.read(user, chat).messages().getFirst().text()).isEqualTo("你好");
    }
    /** 同聊天只有一条有效租约，失联重领递增令牌，旧持有者不能提交。 */
    @Test void expiredLeaseRecoveryRejectsOldClaim() {
        var user = actor(); UUID chat = UUID.randomUUID(); service.send(user, chat, UUID.randomUUID(), "你好");
        var old = tx.required(store::claim).orElseThrow(); assertThat(tx.required(store::claim)).isEmpty();
        jdbc.update("UPDATE dialogue_input SET active_since=clock_timestamp()-interval '10 seconds',lease_until=clock_timestamp()-interval '1 second' WHERE user_id=?", user.id());
        jdbc.update("UPDATE background_job SET active_since=clock_timestamp()-interval '10 seconds',lease_until=clock_timestamp()-interval '1 second' WHERE user_id=?", user.id());
        var next = tx.required(store::claim).orElseThrow(); assertThat(next.token()).isGreaterThan(old.token());
        assertThat(jdbc.queryForObject("SELECT active_millis FROM execution_budget WHERE run_id=?", Long.class, old.budgetId())).isGreaterThanOrEqualTo(9000L);
        assertThat(tx.required(() -> store.guard(old))).isFalse(); assertThat(tx.required(() -> store.guard(next))).isTrue();
    }
    /** 保存偏好必须确认，重复确认只产生一条；后来删除同样先确认。 */
    @Test void preferenceOperationsRequireExplicitConfirmation() {
        var user = actor(); UUID chat = UUID.randomUUID(), input = UUID.randomUUID(); service.send(user, chat, input, "记住我不吃辣");
        var save = new DialogueIntent.Result(List.of(new DialogueIntent.Intent(DialogueIntent.Kind.OPERATION, DialogueIntent.Action.SAVE_PREFERENCE, "记住我不吃辣")), List.of(), "请核对", "", null, "diet", "不吃辣", "");
        process(save); assertThat(preferences.read(user).entries()).isEmpty();
        service.act(user, chat, input, "CONFIRM"); service.act(user, chat, input, "CONFIRM"); assertThat(preferences.read(user).entries()).hasSize(1);
        assertThat(service.read(user, chat).messages().getFirst().reply()).isEqualTo("已保存你确认的长期偏好。");
        var pref = preferences.read(user).entries().getFirst(); UUID remove = UUID.randomUUID(); service.send(user, chat, remove, "删除不吃辣偏好");
        process(new DialogueIntent.Result(List.of(new DialogueIntent.Intent(DialogueIntent.Kind.OPERATION, DialogueIntent.Action.DELETE_PREFERENCE, "删除不吃辣偏好")), List.of(), "请核对", "", pref.id(), "", "", ""));
        assertThat(preferences.read(user).entries()).hasSize(1); service.act(user, chat, remove, "CONFIRM"); assertThat(preferences.read(user).entries()).isEmpty();
    }
    /** 跨用户读取、确认和同名ID收件都不允许访问别人数据。 */
    @Test void ownershipAndInvalidInterpretationsCannotWriteRequirements() {
        var user = actor(); var foreign = actor(); UUID chat = UUID.randomUUID(), input = UUID.randomUUID(); service.send(user, chat, input, "你好");
        assertThatThrownBy(() -> service.read(foreign, chat)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.act(foreign, chat, input, "WITHDRAW")).isInstanceOf(RuntimeException.class);
        process(result(DialogueIntent.Kind.PLANNING, DialogueIntent.Action.START_PLAN, "原文不存在", List.of()));
        assertThat(service.read(user, chat).messages().getFirst().state()).isEqualTo("PAUSED"); assertThat(service.read(user, chat).chat().taskId()).isNull();
    }
    /** 无关闲聊不清除旧澄清；明确回答后可继承原消息事实，原文和两轮消息均保留。 */
    @Test void clarificationPreservesPendingFactsUntilExplicitlyResolved() {
        var user = actor(); UUID chat = UUID.randomUUID(), old = UUID.randomUUID(); service.send(user, chat, old, "预算改成六千");
        process(new DialogueIntent.Result(List.of(new DialogueIntent.Intent(DialogueIntent.Kind.UNCLEAR, DialogueIntent.Action.CLARIFY, "预算改成六千")), List.of(), "预算按每人还是全组？", "请明确口径", null, "", "", ""));
        service.send(user, chat, UUID.randomUUID(), "你好"); process(result(DialogueIntent.Kind.CONVERSATION, DialogueIntent.Action.CHAT, "你好", List.of()));
        assertThat(service.read(user, chat).messages().getFirst().state()).isEqualTo("CLARIFY");
        service.send(user, chat, UUID.randomUUID(), "按全组计算");
        process(new DialogueIntent.Result(List.of(new DialogueIntent.Intent(DialogueIntent.Kind.PLANNING, DialogueIntent.Action.SUPPLEMENT, "按全组计算")),
                List.of(new DialogueIntent.Fact("budgetCny", "6000", "六千"), new DialogueIntent.Fact("budgetRule", "PER_GROUP", "按全组计算")), "已明确口径", "", null, "", "", "", List.of(old)));
        var view = service.read(user, chat); assertThat(view.messages().getFirst().state()).isEqualTo("DONE"); assertThat(view.chat().taskId()).isNotNull(); assertThat(view.messages()).hasSize(3);
    }
    /** 新修改在识别完成后登记，撤销原运行的提交资格；咨询不发生这种转换。 */
    @Test void recognizedChangeSupersedesOldExecutionAndStopIsImmediate() {
        var user = actor(); UUID chat = UUID.randomUUID(); service.send(user, chat, UUID.randomUUID(), "去杭州");
        process(result(DialogueIntent.Kind.PLANNING, DialogueIntent.Action.START_PLAN, "去杭州", List.of(new DialogueIntent.Fact("cities", "杭州", "杭州"))));
        var task = service.read(user, chat).chat().taskId(); UUID oldRun = jdbc.queryForObject("SELECT current_run_id FROM travel_task WHERE id=?", UUID.class, task);
        service.send(user, chat, UUID.randomUUID(), "改为苏州"); process(result(DialogueIntent.Kind.PLANNING, DialogueIntent.Action.CHANGE_REQUIREMENTS, "改为苏州", List.of(new DialogueIntent.Fact("cities", "苏州", "苏州"))));
        assertThat(engine.run(new TaskScope(user.id(), task), oldRun).state().name()).isEqualTo("SUPERSEDED");
        var newRun = jdbc.queryForObject("SELECT current_run_id FROM travel_task WHERE id=?", UUID.class, task);
        service.send(user, chat, UUID.randomUUID(), "停止规划"); assertThat(service.claimWork()).isEmpty();
        assertThat(engine.run(new TaskScope(user.id(), task), newRun).state().name()).isEqualTo("CANCELLED");
    }
    /** 两个真实数据库领取事务竞争时，同一聊天仅一方获得租约。 */
    @Test void concurrentClaimsOnlyHaveOneOwner() throws Exception {
        var user = actor(); UUID chat = UUID.randomUUID(); service.send(user, chat, UUID.randomUUID(), "你好");
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> work = () -> { gate.await(); return tx.required(store::claim).isPresent(); };
            var first = threads.submit(work); var second = threads.submit(work); gate.countDown();
            assertThat(List.of(first.get(5, java.util.concurrent.TimeUnit.SECONDS), second.get(5, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
    }
    /** 旧任务关联幂等返回原聊天，不创建第二个旅行、会话或预算。 */
    @Test void legacyTaskAttachReusesExistingChat() {
        var user = actor(); UUID chat = UUID.randomUUID(); service.send(user, chat, UUID.randomUUID(), "去杭州");
        process(result(DialogueIntent.Kind.PLANNING, DialogueIntent.Action.START_PLAN, "去杭州", List.of(new DialogueIntent.Fact("cities", "杭州", "杭州"))));
        UUID task = service.read(user, chat).chat().taskId(); assertThat(service.attach(user, UUID.randomUUID(), task).chat().id()).isEqualTo(chat);
        assertThat(service.list(user, 0)).hasSize(1);
    }
    /** 超出最近100条窗口的失败输入仍可见并可撤回，不能留下用户无法解除的屏障。 */
    @Test void unresolvedMessagesSurviveHistoryWindow() {
        var user = actor(); UUID chat = UUID.randomUUID(), input = UUID.randomUUID(); service.send(user, chat, input, "未完成输入");
        jdbc.update("UPDATE dialogue_input SET state='PAUSED' WHERE user_id=?", user.id());
        jdbc.update("INSERT INTO dialogue_input(id,user_id,chat_id,text,state) SELECT gen_random_uuid(),?,?,'本地已完成历史','DONE' FROM generate_series(1,105)", user.id(), chat);
        var view = service.read(user, chat); assertThat(view.messages()).hasSize(101); assertThat(view.messages().getFirst().id()).isEqualTo(input);
        service.act(user, chat, input, "WITHDRAW");
        assertThat(jdbc.queryForObject("SELECT state FROM dialogue_input WHERE user_id=? AND id=?", String.class, user.id(), input)).isEqualTo("WITHDRAWN");
    }
    /** @param actor 测试主体 @param repository 本机会话 @param <S> 会话实现 @return 仅进程内使用的Cookie，不打印、不写日志 */
    private <S extends org.springframework.session.Session> String cookie(UserIdentity actor, org.springframework.session.SessionRepository<S> repository) {
        var session = repository.createSession(); var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));
        session.setAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context); repository.save(session); sessionIds.add(session.getId());
        return "SESSION=" + java.util.Base64.getEncoder().encodeToString(session.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    /** 新接口保持认证、CSRF及跨用户归属，不因聊天入口新增而匿名放行。 */
    @Test void chatHttpRequiresAuthenticationCsrfAndOwnership() throws Exception {
        var user = actor(); UUID chat = UUID.randomUUID(); String root = "http://127.0.0.1:" + port; var client = java.net.http.HttpClient.newHttpClient();
        var endpoint = java.net.URI.create(root + "/api/v1/chats/" + chat + "/messages");
        assertThat(client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(root + "/api/v1/chats")).GET().build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
        String auth = cookie(user, sessions); String body = json.encode(java.util.Map.of("inputId", UUID.randomUUID(), "text", "你好"));
        assertThat(client.send(java.net.http.HttpRequest.newBuilder(endpoint).header("Cookie", auth).header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        var csrf = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(root + "/api/v1/auth/csrf")).header("Cookie", auth).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        var token = json.decode(csrf.body(), java.util.Map.class);
        assertThat(client.send(java.net.http.HttpRequest.newBuilder(endpoint).header("Cookie", auth).header("Content-Type", "application/json").header((String) token.get("headerName"), (String) token.get("token")).POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
        String foreign = cookie(actor(), sessions);
        assertThat(client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(root + "/api/v1/chats/" + chat)).header("Cookie", foreign).GET().build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
    }
}
