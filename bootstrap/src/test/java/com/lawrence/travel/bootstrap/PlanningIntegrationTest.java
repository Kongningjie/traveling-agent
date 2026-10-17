package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.execution.StageOutput;
import com.lawrence.travel.application.execution.StagePlan;
import com.lawrence.travel.application.execution.TravelToolService;
import com.lawrence.travel.application.interaction.Negotiation;
import com.lawrence.travel.application.interaction.NegotiationHandler;
import com.lawrence.travel.application.interaction.NegotiationService;
import com.lawrence.travel.application.itinerary.ItineraryService;
import com.lawrence.travel.application.itinerary.Planning;
import com.lawrence.travel.application.itinerary.PlanningHandler;
import com.lawrence.travel.application.knowledge.Research;
import com.lawrence.travel.application.knowledge.ResearchHandler;
import com.lawrence.travel.application.knowledge.EvidenceService;
import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.CandidateStore;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.PlanningAdvisor;
import com.lawrence.travel.application.port.ResearchAdvisor;
import com.lawrence.travel.application.port.StageCommitPort;
import com.lawrence.travel.application.port.TravelToolPort;
import com.lawrence.travel.application.travel.TravelStorageService;
import com.lawrence.travel.domain.execution.StageType;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.itinerary.TripSnapshot;
import com.lawrence.travel.domain.itinerary.ItineraryReport;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.domain.travel.TripWindow;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

/** 真实PostgreSQL／Redis／HTTP完整初始闭环，模型和供应商为明确本地替身。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"travel.engine.enabled=false", "travel.tools.evidence-hosts[0]=official.example"})
class PlanningIntegrationTest {
    @Autowired private ExecutionEngineStore engine;
    @Autowired private NegotiationService negotiation;
    @Autowired private NegotiationHandler understand;
    @Autowired private ResearchHandler research;
    @Autowired private PlanningHandler planning;
    @Autowired private ItineraryService itineraries;
    @Autowired private CandidateStore candidates;
    @Autowired private EvidenceService evidence;
    @Autowired private BudgetLedger budgets;
    @Autowired private TravelStorageService travel;
    @Autowired private AccountStore accounts;
    @Autowired private BusinessTransactions transactions;
    @Autowired private TravelJson json;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private PlanningAdvisor advisor;
    @MockitoBean private ResearchAdvisor researcher;
    @MockitoBean private TravelToolService tools;
    @org.springframework.boot.test.web.server.LocalServerPort private int port;
    @Autowired private org.springframework.session.SessionRepository<? extends org.springframework.session.Session> sessions;
    private final List<UUID> users = new ArrayList<>();
    private final List<String> sessionIds = new ArrayList<>();
    private final LocalDate date = LocalDate.parse("2026-11-01");
    /** @param actor 主体 @param scope 私有任务 @param run 原运行 */
    private record Fixture(UserIdentity actor, TaskScope scope, UUID run) { }
    /** 仅清理随机测试账号，按关联顺序删除，不重建卷或保存容器输出。 */
    @AfterEach void clean() {
        sessionIds.forEach(sessions::deleteById);
        transactions.required(() -> {
            for (UUID user : users) {
                var runs = jdbc.queryForList("SELECT id FROM execution_run WHERE user_id=?", UUID.class, user); jdbc.update("UPDATE travel_task SET current_run_id=NULL,current_version_id=NULL WHERE user_id=?", user);
                for (String table : List.of("evidence_notice", "field_evidence", "itinerary_acceptance_request", "itinerary_acceptance", "itinerary_report", "itinerary_candidate", "negotiation_card", "conversation_message", "decision_response", "decision_request", "run_event", "execution_start_request")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
                jdbc.update("DELETE FROM artifact_content WHERE artifact_id IN(SELECT id FROM artifact_record WHERE user_id=?)", user);
                for (String table : List.of("artifact_record", "step_attempt", "outbox_event", "execution_step", "execution_run")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
                for (UUID run : runs) { jdbc.update("DELETE FROM call_meter WHERE run_id=?", run); jdbc.update("DELETE FROM execution_budget WHERE run_id=?", run); }
                for (String table : List.of("change_request", "itinerary_snapshot", "travel_constraint_revision", "travel_task", "travel_idempotency")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user); jdbc.update("DELETE FROM app_user WHERE id=?", user);
            }
            return true;
        });
    }
    /** @return 有明确预算及日期的任务；所有外部端口返回可追溯本地原文。 */
    private Fixture fixture() {
        UUID user = UUID.randomUUID(); String name = "p_" + user.toString().replace("-", "").substring(0, 20); accounts.insert(user, name, "__NON_LOGIN_TEST_HASH__"); users.add(user); var actor = new UserIdentity(user, name);
        var facts = Map.of("cities", "北京", "startDate", date.toString(), "endDate", date.toString(), "travelers", "2", "budgetRule", "PER_GROUP", "budgetCny", "100", "budgetScope", "ACTIVITIES", "fixedStatus", "NONE");
        var created = negotiation.create(actor, UUID.randomUUID(), "本地初始规划", "", facts.entrySet().stream().map(e -> new Negotiation.Fact(e.getKey(), e.getValue(), null)).toList());
        when(researcher.plan(any(), any())).thenReturn(new Research.Plan(List.of(query("openingHours"), query("price"), query("reservation"))));
        org.mockito.Mockito.doAnswer(invocation -> {
            if (invocation.getArgument(1) == TravelToolPort.Operation.WEATHER) return new TravelToolPort.Evidence(URI.create("https://restapi.amap.com/v3/weather/weatherInfo"), Instant.now().minusSeconds(1740), json.encode(Map.of("operation", "WEATHER", "status", "UNKNOWN_OUTSIDE_RETURNED_FORECAST", "targetDate", date.toString(), "data", Map.of())));
            @SuppressWarnings("unchecked") var arguments = (Map<String, String>) invocation.getArgument(2); String url = arguments.get("url"); String field = url.substring(url.lastIndexOf('/') + 1); String value = value(field);
            return new TravelToolPort.Evidence(URI.create(url), Instant.now(), json.encode(Map.of("operation", "EXTRACT", "status", "AVAILABLE", "data", Map.of("results", List.of(Map.of("url", url, "raw_content", date + " " + value))))));
        }).when(tools).query(any(), any(), any());
        org.mockito.Mockito.doAnswer(invocation -> { Research.Query q = invocation.getArgument(1); return new Research.Extraction(value(q.fieldPath()), date + " " + value(q.fieldPath()), null, date); }).when(researcher).extract(any(), any(), any());
        org.mockito.Mockito.doAnswer(invocation -> new Planning.Draft(draft(invocation.getArgument(1)))).when(advisor).draft(any(), any(), any(), any());
        when(advisor.review(any(), any(), any())).thenReturn(new Planning.Review(List.of()));
        return new Fixture(actor, new TaskScope(user, created.taskId()), created.receipt().runId());
    }
    /** @param field 待查字段 @return 本地原文精确地址 */
    private Research.Query query(String field) { return new Research.Query("poi:123", field, TravelToolPort.Operation.EXTRACT, Map.of("url", "https://official.example/" + field), date); }
    /** @param field 字段 @return 与连续原文一致的测试值，非真实城市事实 */
    private String value(String field) { return switch (field) { case "openingHours" -> "09:00-17:00"; case "price" -> "20元/人"; default -> "无需预约"; }; }
    /** @param context 当前权威需求和证据 @return 一个推荐和局部备选，Java计算40元；不是模型评分证明 */
    private TripSnapshot draft(Planning.Context context) {
        var start = date.atStartOfDay(ZoneId.of("Asia/Shanghai")); UUID city = UUID.randomUUID(), item = UUID.randomUUID(), cost = UUID.randomUUID();
        var refs = context.evidence().stream().filter(v -> v.reason().equals("ORIGINAL")).map(v -> v.evidence().reference()).collect(java.util.stream.Collectors.toSet());
        var uses = context.evidence().stream().filter(v -> refs.contains(v.evidence().reference())).map(v -> new TripSnapshot.EvidenceUse(v.evidence().fieldPath().equals("price") ? cost : item, v.evidence().fieldPath(), v.evidence().reference())).toList();
        return new TripSnapshot(1, context.scope().taskId(), context.revision().revision(), context.baseVersionId(), new TripWindow(Set.of("北京"), date, date, 2), List.of(new TripSnapshot.CityStay(city, "北京", start, start.plusDays(1))), List.of(new TripSnapshot.Day(UUID.randomUUID(), date)),
                List.of(new TripSnapshot.Item(item, city, TripSnapshot.ItemType.ACTIVITY, "本地资料景点", "poi:123", start.plusHours(10), start.plusHours(11), null)), List.of(), List.of(), List.of(new TripSnapshot.Cost(cost, item, "ACTIVITIES", TripSnapshot.CostUnit.PER_PERSON, BigDecimal.ONE, new BigDecimal("20"), new BigDecimal("20"), false, true, "CNY")), List.of(new TripSnapshot.Alternative(UUID.randomUUID(), item, "雨天保留休息备选，不承诺未经核实的场馆")), refs, uses);
    }
    /** @param f 运行 @return 下一个可执行阶段 */
    private ExecutionEngineStore.Claim claim(Fixture f) { return engine.claim(engine.candidates(100).stream().filter(h -> h.runId().equals(f.run())).findFirst().orElseThrow(), "planning-test").orElseThrow(); }
    /** @param f 运行 @return 本次已提交阶段类型 */
    private StageType advance(Fixture f) {
        var c = claim(f); StageOutput out = c.step().key().type() == StageType.UNDERSTAND ? understand.execute(c) : c.step().key().type() == StageType.RESEARCH ? research.execute(c) : planning.execute(c);
        assertThat(engine.commit(engine.prepare(c, out))).isEqualTo(StageCommitPort.Result.COMMITTED); return c.step().key().type();
    }
    /** @param f 运行 @param type 推进到该阶段已完成；上限保护测试不能无限循环 */
    private void through(Fixture f, StageType type) { for (int i = 0; i < 30; i++) if (advance(f) == type) return; throw new AssertionError("LOCAL_STAGE_LIMIT"); }
    /** @param f 运行 @return 显式用户接受请求 */
    private CandidateStore.Acceptance accept(Fixture f) { var view = itineraries.latest(f.actor(), f.scope().taskId()); var version = view.candidate().version(); return itineraries.accept(f.actor(), f.scope().taskId(), UUID.randomUUID(), view.candidate().candidateId(), version.baseVersionId(), version.constraintRevision(), engine.run(f.scope(), f.run()).generation(), version.payloadHash()); }
    /** 全链路由真实阶段事务推进，报告精确引用且当前版本只在显式接受后改变。 */
    @Test void fullInitialWorkflowRequiresExplicitAcceptance() {
        var f = fixture(); through(f, StageType.PRESENT); var view = itineraries.latest(f.actor(), f.scope().taskId());
        assertThat(view.acceptable()).isTrue(); assertThat(view.costs().upperCny()).isEqualByComparingTo("40"); assertThat(view.candidate().snapshot().alternatives()).hasSize(1);
        assertThat(view.candidate().validation().conclusion()).isEqualTo(ItineraryReport.Conclusion.PASS); assertThat(view.candidate().review().conclusion()).isEqualTo(ItineraryReport.Conclusion.PASS); assertThat(view.candidate().version().evidenceRefs()).hasSize(3);
        assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId()).isNull(); assertThat(accept(f).status()).isEqualTo(CandidateStore.Status.ACCEPTED); assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId()).isEqualTo(view.candidate().version().snapshotId());
    }
    /** 审核保留独立上下文，只在Java PASS后调用；审核建议不能自动采纳为约束。 */
    @Test void reviewIsIndependentAndAdvisoryIssuesRemainVisible() {
        var f = fixture(); when(advisor.review(any(), any(), any())).thenReturn(new Planning.Review(List.of(new ItineraryReport.Issue("PACE_SUGGESTION", null, false, false)))); through(f, StageType.PRESENT);
        var captor = org.mockito.ArgumentCaptor.forClass(Planning.Candidate.class); verify(advisor).review(any(), any(), captor.capture()); assertThat(captor.getValue().review()).isNull(); assertThat(captor.getValue().validation().conclusion()).isEqualTo(ItineraryReport.Conclusion.PASS);
        assertThat(itineraries.latest(f.actor(), f.scope().taskId()).candidate().review().issues()).hasSize(1); assertThat(accept(f).status()).isEqualTo(CandidateStore.Status.ACCEPTED);
    }
    /** 相同阻断两轮修订后WAIT_USER，草案与报告可读，不隐式放宽或提交。 */
    @Test void blockingReviewStopsAfterTwoRepairsAndKeepsDraft() {
        var f = fixture(); when(advisor.review(any(), any(), any())).thenReturn(new Planning.Review(List.of(new ItineraryReport.Issue("REST_INSUFFICIENT", null, true, false))));
        for (int i = 0; i < 25 && !engine.run(f.scope(), f.run()).state().name().equals("WAITING_USER"); i++) advance(f);
        assertThat(engine.run(f.scope(), f.run()).state().name()).isEqualTo("WAITING_USER"); assertThat(budgets.read(f.run()).repairRounds()).isEqualTo(2); verify(advisor, times(3)).draft(any(), any(), any(), any());
        assertThat(itineraries.latest(f.actor(), f.scope().taskId()).acceptable()).isFalse(); assertThat(negotiation.card(f.actor(), f.scope().taskId()).decision().remaining()).hasSize(1); assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId()).isNull();
    }
    /** 确定性校验不通过不得先调用审核；资料不足仍保留UNKNOWN草案。 */
    @Test void javaFailureNeverInvokesReview() {
        var f = fixture(); org.mockito.Mockito.doAnswer(invocation -> { var trip = draft(invocation.getArgument(1)); return new Planning.Draft(new TripSnapshot(1, trip.taskId(), trip.constraintRevision(), null, trip.window(), trip.cityStays(), trip.days(), trip.items(), trip.transportLegs(), trip.accommodationStays(), trip.costEntries(), trip.alternatives(), Set.of())); }).when(advisor).draft(any(), any(), any(), any());
        for (int i = 0; i < 25 && !engine.run(f.scope(), f.run()).state().name().equals("WAITING_USER"); i++) advance(f);
        assertThat(itineraries.latest(f.actor(), f.scope().taskId()).candidate().validation().conclusion()).isEqualTo(ItineraryReport.Conclusion.UNKNOWN); verify(advisor, org.mockito.Mockito.never()).review(any(), any(), any());
    }
    /** 相关证据撤回后接受只可靠排队重校验，不沿用用户先前接受意图。 */
    @Test void withdrawnEvidenceCannotBeAccepted() {
        var f = fixture(); through(f, StageType.PRESENT); var view = itineraries.latest(f.actor(), f.scope().taskId()); var ref = view.candidate().version().evidenceRefs().iterator().next(); evidence.withdraw(f.actor(), f.scope().taskId(), ref, UUID.randomUUID());
        assertThat(accept(f).status()).isEqualTo(CandidateStore.Status.REVALIDATION_REQUIRED); assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId()).isNull(); assertThat(engine.candidates(100)).anyMatch(h -> h.runId().equals(f.run()) && h.type() == StageType.VALIDATE);
    }
    /** 新修改在模型返回后到达，旧草案提交不能追加快照或候选。 */
    @Test void supersededPlanCannotPublishSnapshot() {
        var f = fixture(); for (int i = 0; i < 5; i++) advance(f); var c = claim(f); assertThat(c.step().key().type()).isEqualTo(StageType.PLAN); var prepared = engine.prepare(c, planning.execute(c));
        negotiation.send(f.actor(), f.scope().taskId(), UUID.randomUUID(), "", List.of(new Negotiation.Fact("travelers", "3", null))); assertThat(engine.commit(prepared)).isEqualTo(StageCommitPort.Result.STALE); assertThat(candidates.latest(f.scope())).isNull();
    }
    /** 候选、快照和后继事件同事务；后继冲突不能留下半个版本。 */
    @Test void failedSuccessorRollsBackSnapshotAndCandidate() {
        var f = fixture(); for (int i = 0; i < 5; i++) advance(f); var c = claim(f); var out = planning.execute(c); var duplicate = new StagePlan(UUID.randomUUID(), c.step().key(), c.step().mode(), c.input(), List.of());
        var broken = new StageOutput(out.text(), out.checkpoint(), List.of(duplicate), out.outcome(), null); assertThatThrownBy(() -> engine.commit(engine.prepare(c, broken))).isInstanceOf(com.lawrence.travel.application.travel.TravelStorageFailure.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM itinerary_snapshot WHERE user_id=?", Integer.class, f.actor().id())).isZero();
    }
    /** 数据库报告有效期不超过本次实际使用证据最早截止。 */
    @Test void reportsNeverOutliveTheirEvidence() {
        var f = fixture(); through(f, StageType.PRESENT); var view = itineraries.latest(f.actor(), f.scope().taskId()); var expires = evidence.list(f.actor(), f.scope().taskId()).stream().map(v -> v.evidence().expiresAt()).min(Instant::compareTo).orElseThrow();
        assertThat(view.candidate().validation().validUntil()).isBeforeOrEqualTo(expires); assertThat(view.candidate().review().validUntil()).isBeforeOrEqualTo(expires);
        var other = fixture(); assertThatThrownBy(() -> itineraries.latest(other.actor(), f.scope().taskId())).hasMessage("NOT_FOUND");
    }
    /** 预报覆盖期之外的天气是可见未知风险，没有天气硬限制时不强制阻断所有行程。 */
    @Test void unknownWeatherIsVisibleRiskAndCapsReportTime() {
        var f = fixture(); when(researcher.plan(any(), any())).thenReturn(new Research.Plan(List.of(query("openingHours"), query("price"), query("reservation"), new Research.Query("北京", "weather", TravelToolPort.Operation.WEATHER, Map.of("city", "110000", "date", date.toString()), date))));
        through(f, StageType.PRESENT); var view = itineraries.latest(f.actor(), f.scope().taskId());
        assertThat(view.acceptable()).isTrue(); assertThat(view.candidate().validation().issues()).anyMatch(i -> i.code().equals("WEATHER_UNKNOWN") && !i.blocking() && i.unknown());
        var weather = evidence.list(f.actor(), f.scope().taskId()).stream().filter(v -> v.evidence().fieldPath().equals("weather")).findFirst().orElseThrow(); assertThat(view.candidate().validation().validUntil()).isBeforeOrEqualTo(weather.evidence().expiresAt());
        assertThat(accept(f).status()).isEqualTo(CandidateStore.Status.ACCEPTED);
    }
    /** @param actor 主体 @param repository 会话仓储 @param <S> 会话类型 @return 仅内存Cookie，不打印会话值 */
    private <S extends org.springframework.session.Session> String session(UserIdentity actor, org.springframework.session.SessionRepository<S> repository) {
        var value = repository.createSession(); var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext(); context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));
        value.setAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context); repository.save(value); sessionIds.add(value.getId()); return "SESSION=" + java.util.Base64.getEncoder().encodeToString(value.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    /** 接受HTTP保留CSRF、身份和归属门禁；没有显式POST不能生效。 */
    @Test void httpAcceptanceRequiresCsrfAndOwner() throws Exception {
        var f = fixture(); through(f, StageType.PRESENT); var view = itineraries.latest(f.actor(), f.scope().taskId()); var v = view.candidate().version(); String cookie = session(f.actor(), sessions);
        var body = new com.lawrence.travel.api.itinerary.ItineraryController.Accept(UUID.randomUUID(), view.candidate().candidateId(), v.baseVersionId(), v.constraintRevision(), engine.run(f.scope(), f.run()).generation(), v.payloadHash());
        var http = java.net.http.HttpClient.newHttpClient(); String root = "http://127.0.0.1:" + port; var uri = URI.create(root + "/api/v1/tasks/" + f.scope().taskId() + "/accept");
        var denied = java.net.http.HttpRequest.newBuilder(uri).header("Cookie", cookie).header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.encode(body))).build(); assertThat(http.send(denied, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        var csrf = http.send(java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/csrf")).header("Cookie", cookie).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString()); var token = json.decode(csrf.body(), Map.class);
        var allowed = java.net.http.HttpRequest.newBuilder(uri).header("Cookie", cookie).header("Content-Type", "application/json").header((String) token.get("headerName"), (String) token.get("token")).POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.encode(body))).build(); assertThat(http.send(allowed, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
    }
    /** @param f 原运行 @param facts 明确修改 @return 已接受旧版本后的新运行；空调查计划复用已有字段事实 */
    private Fixture replan(Fixture f, Map<String, String> facts) {
        through(f, StageType.PRESENT); accept(f);
        when(researcher.plan(any(), any())).thenReturn(new Research.Plan(List.of()));
        org.mockito.Mockito.doAnswer(invocation -> {
            Planning.Base base = invocation.getArgument(2); var item = base.snapshot().items().getFirst();
            var replacement = new TripSnapshot.Item(item.id(), item.cityStayId(), item.type(), "修改后的景点说明", item.locationKey(), item.startsAt(), item.endsAt(), item.fixedArrangementId());
            var uses = base.snapshot().evidenceUses().stream().filter(u -> u.objectId().equals(item.id())).toList();
            var operation = new com.lawrence.travel.domain.itinerary.ItineraryPatch.Operation(com.lawrence.travel.domain.itinerary.ItineraryPatch.Kind.ITEM, com.lawrence.travel.domain.itinerary.ItineraryPatch.Action.REPLACE, item.id(), null, null, replacement, null, null, null, null, uses);
            return new Planning.PatchReply(new com.lawrence.travel.domain.itinerary.ItineraryPatch(base.hash(), List.of(operation), null), null);
        }).when(advisor).patch(any(), any(), any(), any(), any(), any());
        var receipt = negotiation.send(f.actor(), f.scope().taskId(), UUID.randomUUID(), "", facts.entrySet().stream().map(e -> new Negotiation.Fact(e.getKey(), e.getValue().equals("@first") ? candidates.base(f.scope(), travel.task(f.actor(), f.scope().taskId()).task().currentVersionId()).snapshot().items().getFirst().id().toString() : e.getValue(), null)).toList());
        return new Fixture(f.actor(), f.scope(), receipt.runId());
    }
    /** 已生效版本始终保留，Patch复用稳定ID和证据，全部重新校验审核并等待显式接受。 */
    @Test void replanKeepsBaseUntilExplicitAcceptance() {
        var f = replan(fixture(), Map.of("interests", "希望说明更清楚")); var old = travel.task(f.actor(), f.scope().taskId()).task().currentVersionId(); var original = candidates.base(f.scope(), old);
        through(f, StageType.PLAN);
        var current = itineraries.latest(f.actor(), f.scope().taskId()); assertThat(current.acceptable()).isFalse();
        assertThat(current.candidate().snapshot().items().getFirst().id()).isEqualTo(original.snapshot().items().getFirst().id());
        assertThat(current.candidate().snapshot().cityStays()).isEqualTo(original.snapshot().cityStays()); assertThat(current.candidate().snapshot().costEntries()).isEqualTo(original.snapshot().costEntries());
        assertThat(current.candidate().snapshot().evidenceUses()).containsExactlyInAnyOrderElementsOf(original.snapshot().evidenceUses());
        through(f, StageType.PRESENT); assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId()).isEqualTo(old);
        assertThat(accept(f).status()).isEqualTo(CandidateStore.Status.ACCEPTED); assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId()).isNotEqualTo(old);
        verify(advisor, times(1)).draft(any(), any(), any(), any()); verify(advisor, times(2)).review(any(), any(), any());
    }
    /** C事务重新执行Patch合并，篡改已准备的完整快照不能登记候选或后继。 */
    @Test void replanCommitRejectsTamperedMergedSnapshot() {
        var f = replan(fixture(), Map.of("interests", "更新说明")); through(f, StageType.RESEARCH); var c = claim(f); assertThat(c.step().mode()).isEqualTo(com.lawrence.travel.domain.execution.PlanningMode.REPLAN); var out = planning.execute(c);
        var body = new java.util.LinkedHashMap<>(json.decode(out.checkpoint(), Map.class)); var snapshot = json.decode(json.encode(body.get("snapshot")), TripSnapshot.class);
        body.put("snapshot", new TripSnapshot(1, snapshot.taskId(), snapshot.constraintRevision(), snapshot.baseVersionId(), snapshot.window(), snapshot.cityStays(), snapshot.days(), List.of(), snapshot.transportLegs(), snapshot.accommodationStays(), snapshot.costEntries(), snapshot.alternatives(), snapshot.evidenceRefs(), snapshot.evidenceUses()));
        var forged = new StageOutput(out.text(), json.encode(body), out.next(), out.outcome(), null);
        assertThatThrownBy(() -> engine.commit(engine.prepare(c, forged))).isInstanceOf(com.lawrence.travel.application.travel.TravelStorageFailure.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM itinerary_candidate WHERE run_id=?", Integer.class, f.run())).isZero();
    }
    /** 不合法Patch共享两轮修订预算，最终等待用户，不重置预算或调用初始整份规划。 */
    @Test void invalidPatchStopsAfterSharedRepairLimit() {
        var f = replan(fixture(), Map.of("interests", "调整"));
        org.mockito.Mockito.doAnswer(invocation -> new Planning.PatchReply(new com.lawrence.travel.domain.itinerary.ItineraryPatch("0".repeat(64), List.of(), null), null)).when(advisor).patch(any(), any(), any(), any(), any(), any());
        through(f, StageType.RESEARCH); advance(f); advance(f); advance(f);
        assertThat(engine.run(f.scope(), f.run()).state()).isEqualTo(com.lawrence.travel.domain.execution.ExecutionRun.State.WAITING_USER); assertThat(budgets.read(f.run()).repairRounds()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM itinerary_candidate WHERE run_id=?", Integer.class, f.run())).isZero();
    }
    /** 新需求即刻废止旧领取，已经算好的Patch不能覆盖新运行或基础版本。 */
    @Test void supersededReplanCannotCommit() {
        var f = replan(fixture(), Map.of("interests", "调整")); through(f, StageType.RESEARCH); var c = claim(f); var out = planning.execute(c);
        negotiation.send(f.actor(), f.scope().taskId(), UUID.randomUUID(), "", List.of(new Negotiation.Fact("pace", "慢一些", null)));
        assertThat(engine.commit(engine.prepare(c, out))).isEqualTo(StageCommitPort.Result.STALE); assertThat(jdbc.queryForObject("SELECT count(*) FROM itinerary_candidate WHERE run_id=?", Integer.class, f.run())).isZero();
    }
    /** 无额外需求的扩大请求在已是全程范围时直接暂停，不授予解除锁定权限。 */
    @Test void expansionAtWholeTripBoundaryWaitsForUser() {
        var f = replan(fixture(), Map.of("interests", "调整")); org.mockito.Mockito.doReturn(new Planning.PatchReply(null, "需要解除固定安排")).when(advisor).patch(any(), any(), any(), any(), any(), any());
        through(f, StageType.PLAN); assertThat(engine.run(f.scope(), f.run()).state()).isEqualTo(com.lawrence.travel.domain.execution.ExecutionRun.State.WAITING_USER);
        assertThat(budgets.read(f.run()).repairRounds()).isZero(); assertThat(negotiation.card(f.actor(), f.scope().taskId())).isNotNull();
    }

    /** 扩大理由和范围由同一阶段事务保存，下一阶段领取恢复后仍使用它并累计原修订预算。 */
    @Test void localExpansionSurvivesDurableStageHandoff() {
        var f = replan(fixture(), Map.of("changeTargets", "@first", "interests", "更新局部说明"));
        var base = candidates.base(f.scope(), travel.task(f.actor(), f.scope().taskId()).task().currentVersionId());
        org.mockito.Mockito.doReturn(new Planning.PatchReply(null, "需要同日缓冲联动"), new Planning.PatchReply(new com.lawrence.travel.domain.itinerary.ItineraryPatch(base.hash(), List.of(), null), null)).when(advisor).patch(any(), any(), any(), any(), any(), any());
        through(f, StageType.RESEARCH); var firstClaim = claim(f);
        var calculated = new com.lawrence.travel.domain.itinerary.ImpactAnalyzer().analyze(base.snapshot(), base.revision(), candidates.context(firstClaim.fence()).revision(), List.of());
        assertThat(calculated.level()).isZero(); var firstOutput = planning.execute(firstClaim); assertThat(firstOutput.outcome()).isEqualTo(StageOutput.Outcome.CONTINUE);
        assertThat(engine.commit(engine.prepare(firstClaim, firstOutput))).isEqualTo(StageCommitPort.Result.COMMITTED); var c = claim(f); var body = json.decode(c.input(), Map.class);
        assertThat((List<?>) body.get("expansionHistory")).hasSize(1); assertThat(c.step().mode()).isEqualTo(com.lawrence.travel.domain.execution.PlanningMode.REPLAN);
        var out = planning.execute(c); assertThat(engine.commit(engine.prepare(c, out))).isEqualTo(StageCommitPort.Result.COMMITTED);
        through(f, StageType.PRESENT); assertThat(itineraries.latest(f.actor(), f.scope().taskId()).acceptable()).isTrue(); assertThat(budgets.read(f.run()).repairRounds()).isEqualTo(1);
    }
    /** 用户锁定的活动不能由规划模型改写，即使自动修订用完也不改变生效版本。 */
    @Test void lockedItemCannotBeChangedByReplanning() {
        var f = replan(fixture(), Map.of("changeTargets", "@first", "lockedIds", "@first", "interests", "更新说明")); var old = travel.task(f.actor(), f.scope().taskId()).task().currentVersionId();
        through(f, StageType.RESEARCH); advance(f); advance(f); advance(f);
        assertThat(engine.run(f.scope(), f.run()).state()).isEqualTo(com.lawrence.travel.domain.execution.ExecutionRun.State.WAITING_USER);
        assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId()).isEqualTo(old); assertThat(jdbc.queryForObject("SELECT count(*) FROM itinerary_candidate WHERE run_id=?", Integer.class, f.run())).isZero();
    }
    /** 审核反馈修订继续以REPLAN累计Patch，不退回整份初始规划或重置运行预算。 */
    @Test void reviewRepairRetainsReplanMode() {
        var f = replan(fixture(), Map.of("interests", "更新说明")); org.mockito.Mockito.doReturn(new Planning.Review(List.of(new ItineraryReport.Issue("REVIEW_RHYTHM", null, true, false)))).when(advisor).review(any(), any(), any());
        through(f, StageType.REVIEW); var c = claim(f); assertThat(c.step().mode()).isEqualTo(com.lawrence.travel.domain.execution.PlanningMode.REPLAN);
        assertThat(engine.commit(engine.prepare(c, planning.execute(c)))).isEqualTo(StageCommitPort.Result.COMMITTED); assertThat(budgets.read(f.run()).repairRounds()).isEqualTo(1);
        verify(advisor, times(1)).draft(any(), any(), any(), any());
    }

    /** 集合序列化顺序可在进程间变化，范围按类型化集合比较，不把顺序差异误判为越界。 */
    @Test void scopeSetOrderDoesNotChangeCommitEligibility() {
        var f = replan(fixture(), Map.of("interests", "更新说明")); through(f, StageType.RESEARCH); var c = claim(f); var out = planning.execute(c);
        var body = new java.util.LinkedHashMap<>(json.decode(out.checkpoint(), Map.class)); var scope = new java.util.LinkedHashMap<>(json.decode(json.encode(body.get("impactScope")), Map.class));
        var ids = new java.util.ArrayList<>((List<?>) scope.get("allowedIds")); java.util.Collections.reverse(ids); scope.put("allowedIds", ids); body.put("impactScope", scope);
        assertThat(engine.commit(engine.prepare(c, new StageOutput(out.text(), json.encode(body), out.next(), out.outcome(), null)))).isEqualTo(StageCommitPort.Result.COMMITTED);
    }

}
