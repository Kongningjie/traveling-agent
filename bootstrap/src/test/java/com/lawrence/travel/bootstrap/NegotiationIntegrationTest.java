package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.execution.StageOutput;
import com.lawrence.travel.application.execution.StagePlan;
import com.lawrence.travel.application.interaction.Negotiation;
import com.lawrence.travel.application.interaction.NegotiationHandler;
import com.lawrence.travel.application.interaction.NegotiationService;
import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.InputInterpreter;
import com.lawrence.travel.application.port.StageCommitPort;
import com.lawrence.travel.application.travel.TravelStorageService;
import com.lawrence.travel.domain.execution.StageType;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.domain.interaction.RequirementFields;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

/** 真实本地数据库、会话和 HTTP；文本提取端口使用明确替身，不调用供应商。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "travel.engine.enabled=false")
class NegotiationIntegrationTest {
    @Autowired private NegotiationService service;
    @Autowired private NegotiationHandler handler;
    @Autowired private ExecutionEngineStore engine;
    @Autowired private TravelStorageService travel;
    @Autowired private AccountStore accounts;
    @Autowired private BusinessTransactions transactions;
    @Autowired private TravelJson json;
    @Autowired private com.lawrence.travel.application.port.NegotiationStore negotiation;
    @Autowired private com.lawrence.travel.application.port.InteractionCodec codec;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SessionRepository<? extends Session> sessions;
    @MockitoBean private InputInterpreter interpreter;
    @LocalServerPort private int port;
    private final List<UserIdentity> users = new ArrayList<>();
    private final List<String> sessionIds = new ArrayList<>();
    /**
     * @param actor 随机主体
     * @param taskId 私有任务
     * @param runId 初次运行
     */
    private record Fixture(UserIdentity actor, UUID taskId, UUID runId) { }
    /** 只清理本例账号，先断开运行指针，再按 FK 顺序删除可审计产物。 */
    @AfterEach void clean() {
        sessionIds.forEach(sessions::deleteById);
        transactions.required(() -> {
            for (var user : users) {
                var runs = jdbc.queryForList("SELECT id FROM execution_run WHERE user_id=?", UUID.class, user.id());
                jdbc.update("UPDATE travel_task SET current_run_id=NULL WHERE user_id=?", user.id());
                for (String table : List.of("negotiation_card", "conversation_message", "decision_response", "decision_request", "run_event", "execution_start_request")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user.id());
                jdbc.update("DELETE FROM artifact_content WHERE artifact_id IN(SELECT id FROM artifact_record WHERE user_id=?)", user.id());
                for (String table : List.of("artifact_record", "step_attempt", "outbox_event", "execution_step", "execution_run")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user.id());
                for (UUID run : runs) { jdbc.update("DELETE FROM call_meter WHERE run_id=?", run); jdbc.update("DELETE FROM execution_budget WHERE run_id=?", run); }
                for (String table : List.of("change_request", "travel_constraint_revision", "travel_task", "travel_idempotency")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user.id());
                jdbc.update("DELETE FROM app_user WHERE id=?", user.id());
            }
            return true;
        });
    }
    /**
     * @return 不可密码登录的随机身份
     */
    private UserIdentity actor() { UUID id = UUID.randomUUID(); String name = "n_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20); accounts.insert(id, name, "__NON_LOGIN_TEST_HASH__"); var actor = new UserIdentity(id, name); users.add(actor); return actor; }
    /**
     * @param field 业务字段
     * @param value 用户明确值
     * @return 结构化输入，无伪造原文依据
     */
    private Negotiation.Fact fact(String field, String value) { return new Negotiation.Fact(field, value, null); }
    /**
     * @return 只缺预算规则的明确需求，不按人群标签推断
     */
    private List<Negotiation.Fact> basic() { return List.of(fact("cities", "北京,天津"), fact("startDate", "2026-11-01"), fact("endDate", "2026-11-03"), fact("travelers", "2"), fact("fixedStatus", "NONE")); }
    /**
     * @return 未解释的私有任务
     */
    private Fixture fixture() { var actor = actor(); var created = service.create(actor, UUID.randomUUID(), "本地协商", "", basic()); return new Fixture(actor, created.taskId(), created.receipt().runId()); }
    /**
     * @param f 任务
     * @param run 当前运行
     * @return 本次领取；处理阶段不调用真实模型
     */
    private ExecutionEngineStore.Claim claim(Fixture f, UUID run) { var hint = engine.candidates(100).stream().filter(value -> value.scope().taskId().equals(f.taskId()) && value.runId().equals(run) && value.type() == StageType.UNDERSTAND).findFirst().orElseThrow(); return engine.claim(hint, "negotiation-test").orElseThrow(); }
    /**
     * @param f 任务
     * @param run 运行；真实阶段 C 事务提交
     */
    private void advance(Fixture f, UUID run) { var claim = claim(f, run); assertThat(engine.commit(engine.prepare(claim, handler.execute(claim)))).isEqualTo(StageCommitPort.Result.COMMITTED); }
    /**
     * @param f 任务
     * @return 原卡片中的预算问题身份
     */
    private UUID budgetQuestion(Fixture f) { return service.card(f.actor(), f.taskId()).specs().entrySet().stream().filter(e -> e.getValue().field().equals("budgetRule")).map(Map.Entry::getKey).findFirst().orElseThrow(); }
    /**
     * @param f 任务
     * @param rule 规范值
     * @param action 明确动作
     * @param authorization 授权范围
     * @return 原卡片绑定的选项回答
     */
    private Negotiation.Response choice(Fixture f, String rule, String action, Set<UUID> authorization) {
        var card = service.card(f.actor(), f.taskId()); UUID question = budgetQuestion(f);
        UUID option = card.specs().get(question).options().entrySet().stream().filter(e -> e.getValue().equals(rule)).map(Map.Entry::getKey).findFirst().orElseThrow();
        return new Negotiation.Response(card.decision().id(), card.decision().revision(), card.decision().constraintRevision(), action, Map.of(question, option), "", authorization);
    }
    /**
     * @param f 任务
     * @return 当前不可变需求的字段值
     */
    private Map<String, String> fields(Fixture f) { var task = travel.task(f.actor(), f.taskId()).task(); return RequirementFields.fields(travel.revision(f.actor(), f.taskId(), task.constraintRevision()).requirements()); }
    /** 接收不采纳，推荐未选择也不写入需求。 */
    @Test void receiptAndRecommendationDoNotImplyAcceptance() {
        var f = fixture(); assertThat(fields(f)).isEmpty(); assertThat(service.messages(f.actor(), f.taskId()).getFirst().state()).isEqualTo("RECEIVED");
        advance(f, f.runId()); assertThat(fields(f)).doesNotContainKey("budgetRule"); assertThat(service.card(f.actor(), f.taskId()).decision().remaining()).hasSize(1); verifyNoInteractions(interpreter);
    }
    /** 部分回答保留原运行和预算，剩余卡片绑定最新需求；旧响应不能覆盖新需求。 */
    @Test void partialAnswerUpdatesBindingsAndRejectsStaleReply() {
        var f = fixture(); advance(f, f.runId()); var response = choice(f, "PER_GROUP", "ANSWER", Set.of()); UUID input = UUID.randomUUID();
        var before = engine.run(new TaskScope(f.actor().id(), f.taskId()), f.runId());
        var receipt = service.answer(f.actor(), f.taskId(), input, response); assertThat(receipt.status()).isEqualTo("RECEIVED"); assertThat(fields(f)).doesNotContainKey("budgetRule");
        advance(f, f.runId()); var card = service.card(f.actor(), f.taskId()); assertThat(card.specs().values()).extracting(Negotiation.QuestionSpec::field).containsExactlyInAnyOrder("budgetCny", "budgetScope");
        assertThat(card.decision().revision()).isEqualTo(response.revision() + 1); assertThat(card.decision().constraintRevision()).isEqualTo(response.constraints() + 1);
        assertThat(engine.run(new TaskScope(f.actor().id(), f.taskId()), f.runId()).generation()).isEqualTo(before.generation());
        assertThat(service.answer(f.actor(), f.taskId(), input, response)).isEqualTo(receipt);
        assertThatThrownBy(() -> service.answer(f.actor(), f.taskId(), UUID.randomUUID(), response)).hasMessage("STALE_VERSION");
    }
    /** 拒绝及暂未决定只作记录，不会因推荐预选隐含采纳。 */
    @Test void rejectsAndUndecidedRemainWaitingWithoutModelCall() {
        var f = fixture(); advance(f, f.runId()); var response = choice(f, "PER_GROUP", "REJECT", Set.of());
        assertThat(service.answer(f.actor(), f.taskId(), UUID.randomUUID(), response).status()).isEqualTo("RECORDED");
        var card = service.card(f.actor(), f.taskId()); assertThat(card.rejected()).containsAll(response.selected().values());
        assertThat(card.decision().remaining().get(budgetQuestion(f)).recommendedOption()).isNull();
        var undecided = new Negotiation.Response(card.decision().id(), card.decision().revision(), card.decision().constraintRevision(), "UNDECIDED", Map.of(), "", Set.of());
        service.answer(f.actor(), f.taskId(), UUID.randomUUID(), undecided); assertThat(fields(f)).doesNotContainKey("budgetRule"); verifyNoInteractions(interpreter);
    }
    /** 有限授权只能覆盖卡片明确允许且实际选定的问题，不能自动接受行程。 */
    @Test void authorizationIsScopedAndDoesNotAcceptItinerary() {
        var f = fixture(); advance(f, f.runId());
        assertThatThrownBy(() -> service.answer(f.actor(), f.taskId(), UUID.randomUUID(), choice(f, "NONE", "AUTHORIZE", Set.of(UUID.randomUUID())))).hasMessage("INVALID_INPUT");
        service.answer(f.actor(), f.taskId(), UUID.randomUUID(), choice(f, "NONE", "AUTHORIZE", Set.of(budgetQuestion(f)))); advance(f, f.runId());
        var task = travel.task(f.actor(), f.taskId()).task(); assertThat(task.currentVersionId()).isNull();
        assertThat(travel.revision(f.actor(), f.taskId(), task.constraintRevision()).requirements()).anyMatch(value -> value.field().equals("budgetRule") && value.source().name().equals("AUTHORIZED_SYSTEM"));
        verifyNoInteractions(interpreter);
    }
    /** 原问题自由回答用本地解释替身，确认增量与继续事件同事务，预算不清零。 */
    @Test void freeAnswerUsesOriginalRunAndPreservesBudget() {
        var f = fixture(); advance(f, f.runId()); service.answer(f.actor(), f.taskId(), UUID.randomUUID(), choice(f, "PER_GROUP", "ANSWER", Set.of())); advance(f, f.runId());
        jdbc.update("UPDATE execution_budget SET model_calls=3 WHERE run_id=?", f.runId()); var card = service.card(f.actor(), f.taskId());
        when(interpreter.interpret(any(), any())).thenReturn(new Negotiation.Interpretation(List.of(new Negotiation.Fact("budgetCny", "5000", "5000"), new Negotiation.Fact("budgetScope", "LODGING,ACTIVITIES", "住宿和活动")), false, ""));
        var response = new Negotiation.Response(card.decision().id(), card.decision().revision(), card.decision().constraintRevision(), "ANSWER", Map.of(), "5000，包含住宿和活动", Set.of());
        service.answer(f.actor(), f.taskId(), UUID.randomUUID(), response); advance(f, f.runId());
        assertThat(fields(f)).containsEntry("budgetCny", "5000"); assertThat(service.card(f.actor(), f.taskId())).isNull();
        assertThat(engine.run(new TaskScope(f.actor().id(), f.taskId()), f.runId()).budget().modelCalls()).isEqualTo(3);
    }
    /** 自由回答中原问题之外的新修改被可靠拆分，取代旧运行而非扩大原问题授权。 */
    @Test void outsideQuestionChangeReplacesRunAndPreservesOtherRequirements() {
        var f = fixture(); advance(f, f.runId()); var card = service.card(f.actor(), f.taskId());
        when(interpreter.interpret(any(), any())).thenReturn(new Negotiation.Interpretation(List.of(new Negotiation.Fact("travelers", "3", "3人")), false, ""));
        var response = new Negotiation.Response(card.decision().id(), card.decision().revision(), card.decision().constraintRevision(), "ANSWER", Map.of(), "另外改成3人", Set.of());
        service.answer(f.actor(), f.taskId(), UUID.randomUUID(), response); advance(f, f.runId());
        var hint = engine.candidates(100).stream().filter(value -> value.scope().taskId().equals(f.taskId())).findFirst().orElseThrow(); assertThat(hint.runId()).isNotEqualTo(f.runId());
        advance(f, hint.runId()); assertThat(fields(f)).containsEntry("travelers", "3").containsEntry("cities", "北京,天津");
        assertThat(engine.run(new TaskScope(f.actor().id(), f.taskId()), f.runId()).state().name()).isEqualTo("SUPERSEDED");
    }
    /** 两条未完成修改按原序处理，新消息不能丢掉旧输入中的有效字段。 */
    @Test void newerMessageDoesNotDropEarlierPendingRequirements() {
        var f = fixture(); var next = service.send(f.actor(), f.taskId(), UUID.randomUUID(), "", List.of(fact("pace", "RELAXED")));
        advance(f, next.runId()); advance(f, next.runId()); assertThat(fields(f)).containsEntry("cities", "北京,天津").containsEntry("pace", "RELAXED");
    }
    /** 旧模型结果已准备但新修改到来后，提交资格必须失效且不覆盖最新输入。 */
    @Test void lateStageCannotOverwriteNewInput() {
        var f = fixture(); var claim = claim(f, f.runId()); var prepared = engine.prepare(claim, handler.execute(claim));
        service.send(f.actor(), f.taskId(), UUID.randomUUID(), "", List.of(fact("travelers", "3")));
        assertThat(engine.commit(prepared)).isEqualTo(StageCommitPort.Result.STALE); assertThat(fields(f)).isEmpty();
    }
    /** 后继唯一冲突使需求、卡片、消息处理状态一起回滚，不出现半次采纳。 */
    @Test void failedSuccessorRollsBackNegotiationProjection() {
        var f = fixture(); var claim = claim(f, f.runId()); var output = handler.execute(claim);
        var duplicate = new StagePlan(UUID.randomUUID(), claim.step().key(), null, claim.input(), List.of());
        var broken = new StageOutput(output.text(), output.checkpoint(), List.of(duplicate), StageOutput.Outcome.CONTINUE, null);
        assertThatThrownBy(() -> engine.commit(engine.prepare(claim, broken))).isInstanceOf(com.lawrence.travel.application.travel.TravelStorageFailure.class);
        assertThat(fields(f)).isEmpty(); assertThat(service.card(f.actor(), f.taskId())).isNull(); assertThat(service.messages(f.actor(), f.taskId()).getFirst().state()).isEqualTo("RECEIVED");
    }
    /** 同键异文冲突和跨账号读取都明确拒绝。 */
    @Test void idempotencyAndUserOwnershipAreEnforced() {
        var f = fixture(); UUID input = UUID.randomUUID(); var receipt = service.send(f.actor(), f.taskId(), input, "", List.of(fact("pace", "RELAXED")));
        assertThat(service.send(f.actor(), f.taskId(), input, "", List.of(fact("pace", "RELAXED")))).isEqualTo(receipt);
        assertThatThrownBy(() -> service.send(f.actor(), f.taskId(), input, "", List.of(fact("pace", "FAST")))).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(() -> service.messages(actor(), f.taskId())).hasMessage("NOT_FOUND");
    }
    /** 新修改取代正在解释的回答时，其有效选项必须接管处理且保留原始回答及确认来源。 */
    @Test void supersessionPreservesPendingAnswerAndItsProvenance() {
        var f = fixture(); advance(f, f.runId()); var response = choice(f, "NONE", "ANSWER", Set.of()); UUID answerId = UUID.randomUUID();
        var receipt = service.answer(f.actor(), f.taskId(), answerId, response);
        var next = service.send(f.actor(), f.taskId(), UUID.randomUUID(), "", List.of(fact("pace", "RELAXED")));
        advance(f, next.runId()); advance(f, next.runId());
        assertThat(fields(f)).containsEntry("budgetRule", "NONE").containsEntry("pace", "RELAXED");
        var task = travel.task(f.actor(), f.taskId()).task();
        assertThat(travel.revision(f.actor(), f.taskId(), task.constraintRevision()).requirements()).anyMatch(value -> value.field().equals("budgetRule") && value.source().name().equals("ACCEPTED_SUGGESTION"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversation_message WHERE user_id=? AND id=? AND original_answer IS NOT NULL", Integer.class, f.actor().id(), answerId)).isOne();
        assertThat(service.answer(f.actor(), f.taskId(), answerId, response)).isEqualTo(receipt);
    }
    /** 已订节点保留带时区时间及真实用户消息来源；普通偏好修改不会清空固定安排。 */
    @Test void fixedArrangementsKeepTimeZoneAndSurviveOtherChanges() {
        var actor = actor(); var facts = new ArrayList<>(basic().stream().filter(value -> !value.field().equals("fixedStatus")).toList());
        facts.add(fact("fixedStatus", "PROVIDED")); facts.add(fact("fixedArrangements", "TRAIN|2026-11-01T23:00+08:00[Asia/Shanghai]|2026-11-02T01:00+08:00[Asia/Shanghai]"));
        var created = service.create(actor, UUID.randomUUID(), "固定节点", "", facts); var f = new Fixture(actor, created.taskId(), created.receipt().runId()); advance(f, f.runId());
        var task = travel.task(actor, f.taskId()).task(); var fixed = travel.revision(actor, f.taskId(), task.constraintRevision()).fixedArrangements(); assertThat(fixed).hasSize(1); assertThat(fixed.getFirst().startsAt().getZone().getId()).isEqualTo("Asia/Shanghai");
        var next = service.send(actor, f.taskId(), UUID.randomUUID(), "", List.of(fact("pace", "RELAXED"))); advance(f, next.runId()); task = travel.task(actor, f.taskId()).task();
        assertThat(travel.revision(actor, f.taskId(), task.constraintRevision()).fixedArrangements()).isEqualTo(fixed);
    }
    /** 主动追问达到可配置上限后停止建议，保留缺项和等待状态，用户可自行补充。 */
    @Test void clarificationLimitStopsProactiveSuggestions() {
        var f = fixture(); advance(f, f.runId()); service.answer(f.actor(), f.taskId(), UUID.randomUUID(), choice(f, "PER_GROUP", "ANSWER", Set.of()));
        var claim = claim(f, f.runId()); var capped = new NegotiationHandler(negotiation, codec, interpreter, 1);
        assertThat(engine.commit(engine.prepare(claim, capped.execute(claim)))).isEqualTo(StageCommitPort.Result.COMMITTED);
        assertThat(service.card(f.actor(), f.taskId()).decision().remaining().values()).allMatch(question -> question.prompt().startsWith("待补充字段") && question.recommendedOption() == null);
        assertThat(engine.run(new TaskScope(f.actor().id(), f.taskId()), f.runId()).state().name()).isEqualTo("WAITING_USER");
    }
    /** 授权动作中的选项不能超出授权问题，即使两项都存在于同一张卡片。 */
    @Test void selectedQuestionsCannotExceedAuthorization() {
        var actor = actor(); var created = service.create(actor, UUID.randomUUID(), "有限授权", "", basic().stream().filter(value -> !value.field().equals("fixedStatus")).toList());
        var f = new Fixture(actor, created.taskId(), created.receipt().runId()); advance(f, f.runId()); var card = service.card(actor, f.taskId());
        var selected = new java.util.LinkedHashMap<UUID, UUID>();
        for (var spec : card.specs().entrySet()) selected.put(spec.getKey(), spec.getValue().options().keySet().iterator().next());
        var response = new Negotiation.Response(card.decision().id(), card.decision().revision(), card.decision().constraintRevision(), "AUTHORIZE", selected, "", Set.of(budgetQuestion(f)));
        assertThatThrownBy(() -> service.answer(actor, f.taskId(), UUID.randomUUID(), response)).hasMessage("INVALID_INPUT");
        assertThat(fields(f)).doesNotContainKeys("budgetRule", "fixedStatus");
    }
    /**
     * @param actor 认证主体
     * @param repository 真实会话
     * @param <S> 会话类型
     * @return 仅内存使用，不打印或断言 Cookie
     */
    private <S extends Session> String session(UserIdentity actor, SessionRepository<S> repository) {
        var value = repository.createSession(); var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        value.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context); repository.save(value); sessionIds.add(value.getId()); return "SESSION=" + Base64.getEncoder().encodeToString(value.getId().getBytes(StandardCharsets.UTF_8));
    }
    /** HTTP 写入保留 CSRF，匿名及跨用户不能读取协商消息。 */
    @Test void httpWritesRequireCsrfAndReadsRequireOwnership() throws Exception {
        var f = fixture(); String cookie = session(f.actor(), sessions); var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(); String root = "http://127.0.0.1:" + port;
        var body = new NegotiationControllerBody(UUID.randomUUID(), "", List.of(fact("pace", "RELAXED")));
        var noCsrf = HttpRequest.newBuilder(URI.create(root + "/api/v1/tasks/" + f.taskId() + "/messages")).header("Cookie", cookie).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.encode(body))).build();
        assertThat(http.send(noCsrf, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        var csrf = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/csrf")).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString()); var token = json.decode(csrf.body(), Map.class);
        var allowed = HttpRequest.newBuilder(noCsrf.uri()).header("Cookie", cookie).header("Content-Type", "application/json").header((String) token.get("headerName"), (String) token.get("token")).POST(HttpRequest.BodyPublishers.ofString(json.encode(body))).build();
        assertThat(http.send(allowed, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(202);
        var foreign = HttpRequest.newBuilder(URI.create(root + "/api/v1/tasks/" + f.taskId() + "/messages")).header("Cookie", session(actor(), sessions)).GET().build();
        assertThat(http.send(foreign, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
    }
    /**
     * @param inputId 幂等身份
     * @param text 原文
     * @param facts 明确字段
     */
    private record NegotiationControllerBody(UUID inputId, String text, List<Negotiation.Fact> facts) { }
}
