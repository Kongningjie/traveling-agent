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
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "travel.engine.enabled=false",
            "travel.tools.evidence-hosts[0]=official.example"
        })
class PlanningIntegrationTest {
    @Autowired private ExecutionEngineStore engine;
    @Autowired private NegotiationService negotiation;
    @Autowired private NegotiationHandler understand;
    @Autowired private ResearchHandler research;
    @Autowired private PlanningHandler planning;
    @Autowired private ItineraryService itineraries;
    @Autowired private com.lawrence.travel.application.itinerary.VersionService versionService;
    @Autowired private CandidateStore candidates;
    @Autowired private EvidenceService evidence;
    @Autowired private BudgetLedger budgets;
    @Autowired private TravelStorageService travel;
    @Autowired private AccountStore accounts;
    @Autowired private BusinessTransactions transactions;
    @Autowired private TravelJson json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.lawrence.travel.application.document.DocumentService documents;
    @Autowired private com.lawrence.travel.application.port.DocumentStore documentStore;
    @Autowired private com.lawrence.travel.application.port.JobStore jobs;
    @Autowired private com.lawrence.travel.application.interaction.DialogueService dialogue;
    @Autowired private com.lawrence.travel.application.document.SourceReadService sources;
    @Autowired private com.lawrence.travel.application.document.AdviceService advice;
    @MockitoBean private com.lawrence.travel.application.port.ParserProcessPort parser;
    @MockitoBean private com.lawrence.travel.application.port.DialogueInterpreter interpreter;
    private static final java.nio.file.Path DOCUMENT_ROOT = documentRoot();

    /**
     * @return 仅本地合成文档目录，不触及真实附件
     */
    private static java.nio.file.Path documentRoot() {
        try {
            return java.nio.file.Files.createTempDirectory("planning-advice-test-");
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("TEST_ROOT_FAILED");
        }
    }

    /**
     * @param registry 隔离本例原件根与清理开关
     */
    @org.springframework.test.context.DynamicPropertySource
    static void documentProperties(
            org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("travel.documents.root", DOCUMENT_ROOT::toString);
        registry.add("travel.documents.cleanup-enabled", () -> false);
    }

    @MockitoBean private PlanningAdvisor advisor;
    @MockitoBean private ResearchAdvisor researcher;
    @MockitoBean private TravelToolService tools;
    @org.springframework.boot.test.web.server.LocalServerPort private int port;

    @Autowired
    private org.springframework.session.SessionRepository<
                    ? extends org.springframework.session.Session>
            sessions;

    private final List<UUID> users = new ArrayList<>();
    private final List<String> sessionIds = new ArrayList<>();
    private final LocalDate date = LocalDate.parse("2026-11-01");

    /**
     * @param actor 主体
     * @param scope 私有任务
     * @param run 原运行
     */
    private record Fixture(UserIdentity actor, TaskScope scope, UUID run) {}

    /** 仅清理随机测试账号，按关联顺序删除，不重建卷或保存容器输出。 */
    @AfterEach
    void clean() {
        sessionIds.forEach(sessions::deleteById);
        transactions.required(
                () -> {
                    for (UUID user : users) {
                        var operationBudgets =
                                jdbc.queryForList(
                                        "SELECT budget_id FROM operation_context WHERE user_id=? UNION SELECT budget_id FROM dialogue_chat WHERE user_id=?",
                                        UUID.class,
                                        user,
                                        user);
                        for (String key :
                                jdbc.queryForList(
                                        "SELECT file_key FROM uploaded_document WHERE user_id=?",
                                        String.class,
                                        user)) {
                            try {
                                java.nio.file.Files.deleteIfExists(DOCUMENT_ROOT.resolve(key));
                            } catch (java.io.IOException failure) {
                                throw new IllegalStateException("TEST_CLEAN_FAILED");
                            }
                        }
                        jdbc.update("DELETE FROM uploaded_document WHERE user_id=?", user);
                        jdbc.update("DELETE FROM dialogue_input WHERE user_id=?", user);
                        JobTestCleanup.remove(jdbc, user);
                        jdbc.update("DELETE FROM dialogue_chat WHERE user_id=?", user);
                        for (UUID budget : operationBudgets) {
                            jdbc.update("DELETE FROM call_meter WHERE run_id=?", budget);
                            jdbc.update("DELETE FROM execution_budget WHERE run_id=?", budget);
                        }
                        var runs =
                                jdbc.queryForList(
                                        "SELECT id FROM execution_run WHERE user_id=?",
                                        UUID.class,
                                        user);
                        jdbc.update(
                                "UPDATE travel_task SET current_run_id=NULL,current_version_id=NULL WHERE user_id=?",
                                user);
                        for (String table :
                                List.of(
                                        "evidence_notice",
                                        "field_evidence",
                                        "itinerary_acceptance_request",
                                        "itinerary_acceptance",
                                        "itinerary_report",
                                        "itinerary_candidate",
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
                                        "execution_run"))
                            jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
                        for (UUID run : runs) {
                            jdbc.update("DELETE FROM call_meter WHERE run_id=?", run);
                            jdbc.update("DELETE FROM execution_budget WHERE run_id=?", run);
                        }
                        for (String table :
                                List.of(
                                        "change_request",
                                        "itinerary_snapshot",
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
     * @return 有明确预算及日期的任务；所有外部端口返回可追溯本地原文。
     */
    private Fixture fixture() {
        UUID user = UUID.randomUUID();
        String name = "p_" + user.toString().replace("-", "").substring(0, 20);
        accounts.insert(user, name, "__NON_LOGIN_TEST_HASH__");
        users.add(user);
        var actor = new UserIdentity(user, name);
        var facts =
                Map.of(
                        "cities",
                        "北京",
                        "startDate",
                        date.toString(),
                        "endDate",
                        date.toString(),
                        "travelers",
                        "2",
                        "budgetRule",
                        "PER_GROUP",
                        "budgetCny",
                        "100",
                        "budgetScope",
                        "ACTIVITIES",
                        "fixedStatus",
                        "NONE");
        var created =
                negotiation.create(
                        actor,
                        UUID.randomUUID(),
                        "本地初始规划",
                        "",
                        facts.entrySet().stream()
                                .map(e -> new Negotiation.Fact(e.getKey(), e.getValue(), null))
                                .toList());
        when(researcher.plan(any(), any()))
                .thenReturn(
                        new Research.Plan(
                                List.of(
                                        query("openingHours"),
                                        query("price"),
                                        query("reservation"))));
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            if (invocation.getArgument(1) == TravelToolPort.Operation.WEATHER)
                                return new TravelToolPort.Evidence(
                                        URI.create(
                                                "https://restapi.amap.com/v3/weather/weatherInfo"),
                                        Instant.now().minusSeconds(1740),
                                        json.encode(
                                                Map.of(
                                                        "operation",
                                                        "WEATHER",
                                                        "status",
                                                        "UNKNOWN_OUTSIDE_RETURNED_FORECAST",
                                                        "targetDate",
                                                        date.toString(),
                                                        "data",
                                                        Map.of())));
                            @SuppressWarnings("unchecked")
                            var arguments = (Map<String, String>) invocation.getArgument(2);
                            String url = arguments.get("url");
                            String field = url.substring(url.lastIndexOf('/') + 1);
                            String value = value(field);
                            return new TravelToolPort.Evidence(
                                    URI.create(url),
                                    Instant.now(),
                                    json.encode(
                                            Map.of(
                                                    "operation",
                                                    "EXTRACT",
                                                    "status",
                                                    "AVAILABLE",
                                                    "data",
                                                    Map.of(
                                                            "results",
                                                            List.of(
                                                                    Map.of(
                                                                            "url",
                                                                            url,
                                                                            "raw_content",
                                                                            date + " "
                                                                                    + value))))));
                        })
                .when(tools)
                .query(any(), any(), any());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            Research.Query q = invocation.getArgument(1);
                            return new Research.Extraction(
                                    value(q.fieldPath()),
                                    date + " " + value(q.fieldPath()),
                                    null,
                                    date);
                        })
                .when(researcher)
                .extract(any(), any(), any());
        org.mockito.Mockito.doAnswer(
                        invocation -> new Planning.Draft(draft(invocation.getArgument(1))))
                .when(advisor)
                .draft(any(), any(), any(), any());
        when(advisor.review(any(), any(), any())).thenReturn(new Planning.Review(List.of()));
        return new Fixture(actor, new TaskScope(user, created.taskId()), created.receipt().runId());
    }

    /**
     * @param field 待查字段
     * @return 本地原文精确地址
     */
    private Research.Query query(String field) {
        return new Research.Query(
                "poi:123",
                field,
                TravelToolPort.Operation.EXTRACT,
                Map.of("url", "https://official.example/" + field),
                date);
    }

    /**
     * @param field 字段
     * @return 与连续原文一致的测试值，非真实城市事实
     */
    private String value(String field) {
        return switch (field) {
            case "openingHours" -> "09:00-17:00";
            case "price" -> "20元/人";
            default -> "无需预约";
        };
    }

    /**
     * @param context 当前权威需求和证据
     * @return 一个推荐和局部备选，Java计算40元；不是模型评分证明
     */
    private TripSnapshot draft(Planning.Context context) {
        var start = date.atStartOfDay(ZoneId.of("Asia/Shanghai"));
        UUID city = UUID.randomUUID(), item = UUID.randomUUID(), cost = UUID.randomUUID();
        var refs =
                context.evidence().stream()
                        .filter(v -> v.reason().equals("ORIGINAL"))
                        .map(v -> v.evidence().reference())
                        .collect(java.util.stream.Collectors.toSet());
        var uses =
                context.evidence().stream()
                        .filter(v -> refs.contains(v.evidence().reference()))
                        .map(
                                v ->
                                        new TripSnapshot.EvidenceUse(
                                                v.evidence().fieldPath().equals("price")
                                                        ? cost
                                                        : item,
                                                v.evidence().fieldPath(),
                                                v.evidence().reference()))
                        .toList();
        return new TripSnapshot(
                1,
                context.scope().taskId(),
                context.revision().revision(),
                context.baseVersionId(),
                new TripWindow(Set.of("北京"), date, date, 2),
                List.of(new TripSnapshot.CityStay(city, "北京", start, start.plusDays(1))),
                List.of(new TripSnapshot.Day(UUID.randomUUID(), date)),
                List.of(
                        new TripSnapshot.Item(
                                item,
                                city,
                                TripSnapshot.ItemType.ACTIVITY,
                                "本地资料景点",
                                "poi:123",
                                start.plusHours(10),
                                start.plusHours(11),
                                null)),
                List.of(),
                List.of(),
                List.of(
                        new TripSnapshot.Cost(
                                cost,
                                item,
                                "ACTIVITIES",
                                TripSnapshot.CostUnit.PER_PERSON,
                                BigDecimal.ONE,
                                new BigDecimal("20"),
                                new BigDecimal("20"),
                                false,
                                true,
                                "CNY")),
                List.of(
                        new TripSnapshot.Alternative(
                                UUID.randomUUID(), item, "雨天保留休息备选，不承诺未经核实的场馆")),
                refs,
                uses);
    }

    /**
     * @param f 运行
     * @return 下一个可执行阶段
     */
    private ExecutionEngineStore.Claim claim(Fixture f) {
        return engine.claim(
                        engine.candidates(100).stream()
                                .filter(h -> h.runId().equals(f.run()))
                                .findFirst()
                                .orElseThrow(),
                        "planning-test")
                .orElseThrow();
    }

    /**
     * @param f 运行
     * @return 本次已提交阶段类型
     */
    private StageType advance(Fixture f) {
        var c = claim(f);
        StageOutput out =
                c.step().key().type() == StageType.UNDERSTAND
                        ? understand.execute(c)
                        : c.step().key().type() == StageType.RESEARCH
                                ? research.execute(c)
                                : planning.execute(c);
        assertThat(engine.commit(engine.prepare(c, out)))
                .isEqualTo(StageCommitPort.Result.COMMITTED);
        return c.step().key().type();
    }

    /**
     * @param f 运行
     * @param type 推进到该阶段已完成；上限保护测试不能无限循环
     */
    private void through(Fixture f, StageType type) {
        for (int i = 0; i < 30; i++) if (advance(f) == type) return;
        throw new AssertionError("LOCAL_STAGE_LIMIT");
    }

    /**
     * @param f 运行
     * @return 显式用户接受请求
     */
    private CandidateStore.Acceptance accept(Fixture f) {
        var view = itineraries.latest(f.actor(), f.scope().taskId());
        var version = view.candidate().version();
        return itineraries.accept(
                f.actor(),
                f.scope().taskId(),
                UUID.randomUUID(),
                view.candidate().candidateId(),
                version.baseVersionId(),
                version.constraintRevision(),
                engine.run(f.scope(), f.run()).generation(),
                version.payloadHash());
    }

    /** 全链路由真实阶段事务推进，报告精确引用且当前版本只在显式接受后改变。 */
    @Test
    void fullInitialWorkflowRequiresExplicitAcceptance() {
        var f = fixture();
        through(f, StageType.PRESENT);
        var view = itineraries.latest(f.actor(), f.scope().taskId());
        assertThat(view.acceptable()).isTrue();
        assertThat(view.costs().upperCny()).isEqualByComparingTo("40");
        assertThat(view.candidate().snapshot().alternatives()).hasSize(1);
        assertThat(view.candidate().validation().conclusion())
                .isEqualTo(ItineraryReport.Conclusion.PASS);
        assertThat(view.candidate().review().conclusion())
                .isEqualTo(ItineraryReport.Conclusion.PASS);
        assertThat(view.candidate().version().evidenceRefs()).hasSize(3);
        assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId()).isNull();
        assertThat(accept(f).status()).isEqualTo(CandidateStore.Status.ACCEPTED);
        assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId())
                .isEqualTo(view.candidate().version().snapshotId());
    }

    /** 审核保留独立上下文，只在Java PASS后调用；审核建议不能自动采纳为约束。 */
    @Test
    void reviewIsIndependentAndAdvisoryIssuesRemainVisible() {
        var f = fixture();
        when(advisor.review(any(), any(), any()))
                .thenReturn(
                        new Planning.Review(
                                List.of(
                                        new ItineraryReport.Issue(
                                                "PACE_SUGGESTION", null, false, false))));
        through(f, StageType.PRESENT);
        var captor = org.mockito.ArgumentCaptor.forClass(Planning.Candidate.class);
        verify(advisor).review(any(), any(), captor.capture());
        assertThat(captor.getValue().review()).isNull();
        assertThat(captor.getValue().validation().conclusion())
                .isEqualTo(ItineraryReport.Conclusion.PASS);
        assertThat(itineraries.latest(f.actor(), f.scope().taskId()).candidate().review().issues())
                .hasSize(1);
        assertThat(accept(f).status()).isEqualTo(CandidateStore.Status.ACCEPTED);
    }

    /** 相同阻断两轮修订后WAIT_USER，草案与报告可读，不隐式放宽或提交。 */
    @Test
    void blockingReviewStopsAfterTwoRepairsAndKeepsDraft() {
        var f = fixture();
        when(advisor.review(any(), any(), any()))
                .thenReturn(
                        new Planning.Review(
                                List.of(
                                        new ItineraryReport.Issue(
                                                "REST_INSUFFICIENT", null, true, false))));
        for (int i = 0;
                i < 25 && !engine.run(f.scope(), f.run()).state().name().equals("WAITING_USER");
                i++) advance(f);
        assertThat(engine.run(f.scope(), f.run()).state().name()).isEqualTo("WAITING_USER");
        assertThat(budgets.read(f.run()).repairRounds()).isEqualTo(2);
        verify(advisor, times(3)).draft(any(), any(), any(), any());
        assertThat(itineraries.latest(f.actor(), f.scope().taskId()).acceptable()).isFalse();
        assertThat(negotiation.card(f.actor(), f.scope().taskId()).decision().remaining())
                .hasSize(1);
        assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId()).isNull();
    }

    /** 确定性校验不通过不得先调用审核；资料不足仍保留UNKNOWN草案。 */
    @Test
    void javaFailureNeverInvokesReview() {
        var f = fixture();
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            var trip = draft(invocation.getArgument(1));
                            return new Planning.Draft(
                                    new TripSnapshot(
                                            1,
                                            trip.taskId(),
                                            trip.constraintRevision(),
                                            null,
                                            trip.window(),
                                            trip.cityStays(),
                                            trip.days(),
                                            trip.items(),
                                            trip.transportLegs(),
                                            trip.accommodationStays(),
                                            trip.costEntries(),
                                            trip.alternatives(),
                                            Set.of()));
                        })
                .when(advisor)
                .draft(any(), any(), any(), any());
        for (int i = 0;
                i < 25 && !engine.run(f.scope(), f.run()).state().name().equals("WAITING_USER");
                i++) advance(f);
        assertThat(
                        itineraries
                                .latest(f.actor(), f.scope().taskId())
                                .candidate()
                                .validation()
                                .conclusion())
                .isEqualTo(ItineraryReport.Conclusion.UNKNOWN);
        verify(advisor, org.mockito.Mockito.never()).review(any(), any(), any());
    }

    /** 相关证据撤回后接受只可靠排队重校验，不沿用用户先前接受意图。 */
    @Test
    void withdrawnEvidenceCannotBeAccepted() {
        var f = fixture();
        through(f, StageType.PRESENT);
        var view = itineraries.latest(f.actor(), f.scope().taskId());
        var ref = view.candidate().version().evidenceRefs().iterator().next();
        evidence.withdraw(f.actor(), f.scope().taskId(), ref, UUID.randomUUID());
        assertThat(accept(f).status()).isEqualTo(CandidateStore.Status.REVALIDATION_REQUIRED);
        assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId()).isNull();
        assertThat(engine.candidates(100))
                .anyMatch(h -> h.runId().equals(f.run()) && h.type() == StageType.VALIDATE);
    }

    /** 新修改在模型返回后到达，旧草案提交不能追加快照或候选。 */
    @Test
    void supersededPlanCannotPublishSnapshot() {
        var f = fixture();
        for (int i = 0; i < 5; i++) advance(f);
        var c = claim(f);
        assertThat(c.step().key().type()).isEqualTo(StageType.PLAN);
        var prepared = engine.prepare(c, planning.execute(c));
        negotiation.send(
                f.actor(),
                f.scope().taskId(),
                UUID.randomUUID(),
                "",
                List.of(new Negotiation.Fact("travelers", "3", null)));
        assertThat(engine.commit(prepared)).isEqualTo(StageCommitPort.Result.STALE);
        assertThat(candidates.latest(f.scope())).isNull();
    }

    /** 候选、快照和后继事件同事务；后继冲突不能留下半个版本。 */
    @Test
    void failedSuccessorRollsBackSnapshotAndCandidate() {
        var f = fixture();
        for (int i = 0; i < 5; i++) advance(f);
        var c = claim(f);
        var out = planning.execute(c);
        var duplicate =
                new StagePlan(
                        UUID.randomUUID(), c.step().key(), c.step().mode(), c.input(), List.of());
        var broken =
                new StageOutput(
                        out.text(), out.checkpoint(), List.of(duplicate), out.outcome(), null);
        assertThatThrownBy(() -> engine.commit(engine.prepare(c, broken)))
                .isInstanceOf(com.lawrence.travel.application.travel.TravelStorageFailure.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM itinerary_snapshot WHERE user_id=?",
                                Integer.class,
                                f.actor().id()))
                .isZero();
    }

    /** 数据库报告有效期不超过本次实际使用证据最早截止。 */
    @Test
    void reportsNeverOutliveTheirEvidence() {
        var f = fixture();
        through(f, StageType.PRESENT);
        var view = itineraries.latest(f.actor(), f.scope().taskId());
        var expires =
                evidence.list(f.actor(), f.scope().taskId()).stream()
                        .map(v -> v.evidence().expiresAt())
                        .min(Instant::compareTo)
                        .orElseThrow();
        assertThat(view.candidate().validation().validUntil()).isBeforeOrEqualTo(expires);
        assertThat(view.candidate().review().validUntil()).isBeforeOrEqualTo(expires);
        var other = fixture();
        assertThatThrownBy(() -> itineraries.latest(other.actor(), f.scope().taskId()))
                .hasMessage("NOT_FOUND");
    }

    /** 预报覆盖期之外的天气是可见未知风险，没有天气硬限制时不强制阻断所有行程。 */
    @Test
    void unknownWeatherIsVisibleRiskAndCapsReportTime() {
        var f = fixture();
        when(researcher.plan(any(), any()))
                .thenReturn(
                        new Research.Plan(
                                List.of(
                                        query("openingHours"),
                                        query("price"),
                                        query("reservation"),
                                        new Research.Query(
                                                "北京",
                                                "weather",
                                                TravelToolPort.Operation.WEATHER,
                                                Map.of("city", "110000", "date", date.toString()),
                                                date))));
        through(f, StageType.PRESENT);
        var view = itineraries.latest(f.actor(), f.scope().taskId());
        assertThat(view.acceptable()).isTrue();
        assertThat(view.candidate().validation().issues())
                .anyMatch(i -> i.code().equals("WEATHER_UNKNOWN") && !i.blocking() && i.unknown());
        var weather =
                evidence.list(f.actor(), f.scope().taskId()).stream()
                        .filter(v -> v.evidence().fieldPath().equals("weather"))
                        .findFirst()
                        .orElseThrow();
        assertThat(view.candidate().validation().validUntil())
                .isBeforeOrEqualTo(weather.evidence().expiresAt());
        assertThat(accept(f).status()).isEqualTo(CandidateStore.Status.ACCEPTED);
    }

    /**
     * @param actor 主体
     * @param repository 会话仓储
     * @param <S> 会话类型
     * @return 仅内存Cookie，不打印会话值
     */
    private <S extends org.springframework.session.Session> String session(
            UserIdentity actor, org.springframework.session.SessionRepository<S> repository) {
        var value = repository.createSession();
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
        value.setAttribute(
                org.springframework.security.web.context.HttpSessionSecurityContextRepository
                        .SPRING_SECURITY_CONTEXT_KEY,
                context);
        repository.save(value);
        sessionIds.add(value.getId());
        return "SESSION="
                + java.util.Base64.getEncoder()
                        .encodeToString(
                                value.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 接受HTTP保留CSRF、身份和归属门禁；没有显式POST不能生效。 */
    @Test
    void httpAcceptanceRequiresCsrfAndOwner() throws Exception {
        var f = fixture();
        through(f, StageType.PRESENT);
        var view = itineraries.latest(f.actor(), f.scope().taskId());
        var v = view.candidate().version();
        String cookie = session(f.actor(), sessions);
        var body =
                new com.lawrence.travel.api.itinerary.ItineraryController.Accept(
                        UUID.randomUUID(),
                        view.candidate().candidateId(),
                        v.baseVersionId(),
                        v.constraintRevision(),
                        engine.run(f.scope(), f.run()).generation(),
                        v.payloadHash());
        var http = java.net.http.HttpClient.newHttpClient();
        String root = "http://127.0.0.1:" + port;
        var uri = URI.create(root + "/api/v1/tasks/" + f.scope().taskId() + "/accept");
        var denied =
                java.net.http.HttpRequest.newBuilder(uri)
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.encode(body)))
                        .build();
        assertThat(
                        http.send(denied, java.net.http.HttpResponse.BodyHandlers.discarding())
                                .statusCode())
                .isEqualTo(403);
        var csrf =
                http.send(
                        java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/csrf"))
                                .header("Cookie", cookie)
                                .GET()
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
        var token = json.decode(csrf.body(), Map.class);
        var recheckUri =
                URI.create(root + "/api/v1/tasks/" + f.scope().taskId() + "/candidate/revalidate");
        var recheckDenied =
                java.net.http.HttpRequest.newBuilder(recheckUri)
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.encode(body)))
                        .build();
        assertThat(
                        http.send(
                                        recheckDenied,
                                        java.net.http.HttpResponse.BodyHandlers.discarding())
                                .statusCode())
                .isEqualTo(403);
        var recheck =
                java.net.http.HttpRequest.newBuilder(recheckUri)
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .header((String) token.get("headerName"), (String) token.get("token"))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.encode(body)))
                        .build();
        assertThat(
                        http.send(recheck, java.net.http.HttpResponse.BodyHandlers.discarding())
                                .statusCode())
                .isEqualTo(202);
        assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId()).isNull();
        through(f, StageType.PRESENT);
        body =
                new com.lawrence.travel.api.itinerary.ItineraryController.Accept(
                        UUID.randomUUID(),
                        body.candidateId(),
                        body.expectedCurrentVersionId(),
                        body.constraintRevision(),
                        body.generation(),
                        body.snapshotHash());
        var allowed =
                java.net.http.HttpRequest.newBuilder(uri)
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .header((String) token.get("headerName"), (String) token.get("token"))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.encode(body)))
                        .build();
        assertThat(
                        http.send(allowed, java.net.http.HttpResponse.BodyHandlers.discarding())
                                .statusCode())
                .isEqualTo(200);
    }

    /**
     * @param f 原运行
     * @param facts 明确修改
     * @return 已接受旧版本后的新运行；空调查计划复用已有字段事实
     */
    private Fixture replan(Fixture f, Map<String, String> facts) {
        through(f, StageType.PRESENT);
        accept(f);
        when(researcher.plan(any(), any())).thenReturn(new Research.Plan(List.of()));
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            Planning.Base base = invocation.getArgument(2);
                            var item = base.snapshot().items().getFirst();
                            var replacement =
                                    new TripSnapshot.Item(
                                            item.id(),
                                            item.cityStayId(),
                                            item.type(),
                                            "修改后的景点说明",
                                            item.locationKey(),
                                            item.startsAt(),
                                            item.endsAt(),
                                            item.fixedArrangementId());
                            var uses =
                                    base.snapshot().evidenceUses().stream()
                                            .filter(u -> u.objectId().equals(item.id()))
                                            .toList();
                            var operation =
                                    new com.lawrence.travel.domain.itinerary.ItineraryPatch
                                            .Operation(
                                            com.lawrence.travel.domain.itinerary.ItineraryPatch.Kind
                                                    .ITEM,
                                            com.lawrence.travel.domain.itinerary.ItineraryPatch
                                                    .Action.REPLACE,
                                            item.id(),
                                            null,
                                            null,
                                            replacement,
                                            null,
                                            null,
                                            null,
                                            null,
                                            uses);
                            return new Planning.PatchReply(
                                    new com.lawrence.travel.domain.itinerary.ItineraryPatch(
                                            base.hash(), List.of(operation), null),
                                    null);
                        })
                .when(advisor)
                .patch(any(), any(), any(), any(), any(), any());
        var receipt =
                negotiation.send(
                        f.actor(),
                        f.scope().taskId(),
                        UUID.randomUUID(),
                        "",
                        facts.entrySet().stream()
                                .map(
                                        e ->
                                                new Negotiation.Fact(
                                                        e.getKey(),
                                                        e.getValue().equals("@first")
                                                                ? candidates
                                                                        .base(
                                                                                f.scope(),
                                                                                travel.task(
                                                                                                f
                                                                                                        .actor(),
                                                                                                f.scope()
                                                                                                        .taskId())
                                                                                        .task()
                                                                                        .currentVersionId())
                                                                        .snapshot()
                                                                        .items()
                                                                        .getFirst()
                                                                        .id()
                                                                        .toString()
                                                                : e.getValue(),
                                                        null))
                                .toList());
        return new Fixture(f.actor(), f.scope(), receipt.runId());
    }

    /** 已生效版本始终保留，Patch复用稳定ID和证据，全部重新校验审核并等待显式接受。 */
    @Test
    void replanKeepsBaseUntilExplicitAcceptance() {
        var f = replan(fixture(), Map.of("interests", "希望说明更清楚"));
        var old = travel.task(f.actor(), f.scope().taskId()).task().currentVersionId();
        var original = candidates.base(f.scope(), old);
        through(f, StageType.PLAN);
        var current = itineraries.latest(f.actor(), f.scope().taskId());
        assertThat(current.acceptable()).isFalse();
        assertThat(current.candidate().snapshot().items().getFirst().id())
                .isEqualTo(original.snapshot().items().getFirst().id());
        assertThat(current.candidate().snapshot().cityStays())
                .isEqualTo(original.snapshot().cityStays());
        assertThat(current.candidate().snapshot().costEntries())
                .isEqualTo(original.snapshot().costEntries());
        assertThat(current.candidate().snapshot().evidenceUses())
                .containsExactlyInAnyOrderElementsOf(original.snapshot().evidenceUses());
        through(f, StageType.PRESENT);
        assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId())
                .isEqualTo(old);
        assertThat(accept(f).status()).isEqualTo(CandidateStore.Status.ACCEPTED);
        assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId())
                .isNotEqualTo(old);
        verify(advisor, times(1)).draft(any(), any(), any(), any());
        verify(advisor, times(2)).review(any(), any(), any());
    }

    /** C事务重新执行Patch合并，篡改已准备的完整快照不能登记候选或后继。 */
    @Test
    void replanCommitRejectsTamperedMergedSnapshot() {
        var f = replan(fixture(), Map.of("interests", "更新说明"));
        through(f, StageType.RESEARCH);
        var c = claim(f);
        assertThat(c.step().mode())
                .isEqualTo(com.lawrence.travel.domain.execution.PlanningMode.REPLAN);
        var out = planning.execute(c);
        var body = new java.util.LinkedHashMap<>(json.decode(out.checkpoint(), Map.class));
        var snapshot = json.decode(json.encode(body.get("snapshot")), TripSnapshot.class);
        body.put(
                "snapshot",
                new TripSnapshot(
                        1,
                        snapshot.taskId(),
                        snapshot.constraintRevision(),
                        snapshot.baseVersionId(),
                        snapshot.window(),
                        snapshot.cityStays(),
                        snapshot.days(),
                        List.of(),
                        snapshot.transportLegs(),
                        snapshot.accommodationStays(),
                        snapshot.costEntries(),
                        snapshot.alternatives(),
                        snapshot.evidenceRefs(),
                        snapshot.evidenceUses()));
        var forged =
                new StageOutput(out.text(), json.encode(body), out.next(), out.outcome(), null);
        assertThatThrownBy(() -> engine.commit(engine.prepare(c, forged)))
                .isInstanceOf(com.lawrence.travel.application.travel.TravelStorageFailure.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM itinerary_candidate WHERE run_id=?",
                                Integer.class,
                                f.run()))
                .isZero();
    }

    /** 不合法Patch共享两轮修订预算，最终等待用户，不重置预算或调用初始整份规划。 */
    @Test
    void invalidPatchStopsAfterSharedRepairLimit() {
        var f = replan(fixture(), Map.of("interests", "调整"));
        org.mockito.Mockito.doAnswer(
                        invocation ->
                                new Planning.PatchReply(
                                        new com.lawrence.travel.domain.itinerary.ItineraryPatch(
                                                "0".repeat(64), List.of(), null),
                                        null))
                .when(advisor)
                .patch(any(), any(), any(), any(), any(), any());
        through(f, StageType.RESEARCH);
        advance(f);
        advance(f);
        advance(f);
        assertThat(engine.run(f.scope(), f.run()).state())
                .isEqualTo(com.lawrence.travel.domain.execution.ExecutionRun.State.WAITING_USER);
        assertThat(budgets.read(f.run()).repairRounds()).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM itinerary_candidate WHERE run_id=?",
                                Integer.class,
                                f.run()))
                .isZero();
    }

    /** 新需求即刻废止旧领取，已经算好的Patch不能覆盖新运行或基础版本。 */
    @Test
    void supersededReplanCannotCommit() {
        var f = replan(fixture(), Map.of("interests", "调整"));
        through(f, StageType.RESEARCH);
        var c = claim(f);
        var out = planning.execute(c);
        negotiation.send(
                f.actor(),
                f.scope().taskId(),
                UUID.randomUUID(),
                "",
                List.of(new Negotiation.Fact("pace", "慢一些", null)));
        assertThat(engine.commit(engine.prepare(c, out))).isEqualTo(StageCommitPort.Result.STALE);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM itinerary_candidate WHERE run_id=?",
                                Integer.class,
                                f.run()))
                .isZero();
    }

    /** 无额外需求的扩大请求在已是全程范围时直接暂停，不授予解除锁定权限。 */
    @Test
    void expansionAtWholeTripBoundaryWaitsForUser() {
        var f = replan(fixture(), Map.of("interests", "调整"));
        org.mockito.Mockito.doReturn(new Planning.PatchReply(null, "需要解除固定安排"))
                .when(advisor)
                .patch(any(), any(), any(), any(), any(), any());
        through(f, StageType.PLAN);
        assertThat(engine.run(f.scope(), f.run()).state())
                .isEqualTo(com.lawrence.travel.domain.execution.ExecutionRun.State.WAITING_USER);
        assertThat(budgets.read(f.run()).repairRounds()).isZero();
        assertThat(negotiation.card(f.actor(), f.scope().taskId())).isNotNull();
    }

    /** 扩大理由和范围由同一阶段事务保存，下一阶段领取恢复后仍使用它并累计原修订预算。 */
    @Test
    void localExpansionSurvivesDurableStageHandoff() {
        var f = replan(fixture(), Map.of("changeTargets", "@first", "interests", "更新局部说明"));
        var base =
                candidates.base(
                        f.scope(),
                        travel.task(f.actor(), f.scope().taskId()).task().currentVersionId());
        org.mockito.Mockito.doReturn(
                        new Planning.PatchReply(null, "需要同日缓冲联动"),
                        new Planning.PatchReply(
                                new com.lawrence.travel.domain.itinerary.ItineraryPatch(
                                        base.hash(), List.of(), null),
                                null))
                .when(advisor)
                .patch(any(), any(), any(), any(), any(), any());
        through(f, StageType.RESEARCH);
        var firstClaim = claim(f);
        var calculated =
                new com.lawrence.travel.domain.itinerary.ImpactAnalyzer()
                        .analyze(
                                base.snapshot(),
                                base.revision(),
                                candidates.context(firstClaim.fence()).revision(),
                                List.of());
        assertThat(calculated.level()).isZero();
        var firstOutput = planning.execute(firstClaim);
        assertThat(firstOutput.outcome()).isEqualTo(StageOutput.Outcome.CONTINUE);
        assertThat(engine.commit(engine.prepare(firstClaim, firstOutput)))
                .isEqualTo(StageCommitPort.Result.COMMITTED);
        var c = claim(f);
        var body = json.decode(c.input(), Map.class);
        assertThat((List<?>) body.get("expansionHistory")).hasSize(1);
        assertThat(c.step().mode())
                .isEqualTo(com.lawrence.travel.domain.execution.PlanningMode.REPLAN);
        var out = planning.execute(c);
        assertThat(engine.commit(engine.prepare(c, out)))
                .isEqualTo(StageCommitPort.Result.COMMITTED);
        through(f, StageType.PRESENT);
        assertThat(itineraries.latest(f.actor(), f.scope().taskId()).acceptable()).isTrue();
        assertThat(budgets.read(f.run()).repairRounds()).isEqualTo(1);
    }

    /** 用户锁定的活动不能由规划模型改写，即使自动修订用完也不改变生效版本。 */
    @Test
    void lockedItemCannotBeChangedByReplanning() {
        var f =
                replan(
                        fixture(),
                        Map.of(
                                "changeTargets",
                                "@first",
                                "lockedIds",
                                "@first",
                                "interests",
                                "更新说明"));
        var old = travel.task(f.actor(), f.scope().taskId()).task().currentVersionId();
        through(f, StageType.RESEARCH);
        advance(f);
        advance(f);
        advance(f);
        assertThat(engine.run(f.scope(), f.run()).state())
                .isEqualTo(com.lawrence.travel.domain.execution.ExecutionRun.State.WAITING_USER);
        assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId())
                .isEqualTo(old);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM itinerary_candidate WHERE run_id=?",
                                Integer.class,
                                f.run()))
                .isZero();
    }

    /** 审核反馈修订继续以REPLAN累计Patch，不退回整份初始规划或重置运行预算。 */
    @Test
    void reviewRepairRetainsReplanMode() {
        var f = replan(fixture(), Map.of("interests", "更新说明"));
        org.mockito.Mockito.doReturn(
                        new Planning.Review(
                                List.of(
                                        new ItineraryReport.Issue(
                                                "REVIEW_RHYTHM", null, true, false))))
                .when(advisor)
                .review(any(), any(), any());
        through(f, StageType.REVIEW);
        var c = claim(f);
        assertThat(c.step().mode())
                .isEqualTo(com.lawrence.travel.domain.execution.PlanningMode.REPLAN);
        assertThat(engine.commit(engine.prepare(c, planning.execute(c))))
                .isEqualTo(StageCommitPort.Result.COMMITTED);
        assertThat(budgets.read(f.run()).repairRounds()).isEqualTo(1);
        verify(advisor, times(1)).draft(any(), any(), any(), any());
    }

    /** 集合序列化顺序可在进程间变化，范围按类型化集合比较，不把顺序差异误判为越界。 */
    @Test
    void scopeSetOrderDoesNotChangeCommitEligibility() {
        var f = replan(fixture(), Map.of("interests", "更新说明"));
        through(f, StageType.RESEARCH);
        var c = claim(f);
        var out = planning.execute(c);
        var body = new java.util.LinkedHashMap<>(json.decode(out.checkpoint(), Map.class));
        var scope =
                new java.util.LinkedHashMap<>(
                        json.decode(json.encode(body.get("impactScope")), Map.class));
        var ids = new java.util.ArrayList<>((List<?>) scope.get("allowedIds"));
        java.util.Collections.reverse(ids);
        scope.put("allowedIds", ids);
        body.put("impactScope", scope);
        assertThat(
                        engine.commit(
                                engine.prepare(
                                        c,
                                        new StageOutput(
                                                out.text(),
                                                json.encode(body),
                                                out.next(),
                                                out.outcome(),
                                                null))))
                .isEqualTo(StageCommitPort.Result.COMMITTED);
    }

    /** 历史恢复产生新候选和新报告，重复请求仅一个运行，确认前保留当前生效指针。 */
    @Test
    void historicalRestoreRequiresFreshReviewAndAcceptance() {
        var f = replan(fixture(), Map.of("interests", "更新说明"));
        var first = travel.task(f.actor(), f.scope().taskId()).task().currentVersionId();
        through(f, StageType.PRESENT);
        accept(f);
        var current = travel.task(f.actor(), f.scope().taskId()).task();
        UUID request = UUID.randomUUID();
        var restored =
                versionService.restore(
                        f.actor(),
                        f.scope().taskId(),
                        request,
                        first,
                        current.currentVersionId(),
                        current.constraintRevision(),
                        current.executionGeneration());
        assertThat(
                        versionService
                                .restore(
                                        f.actor(),
                                        f.scope().taskId(),
                                        request,
                                        first,
                                        current.currentVersionId(),
                                        current.constraintRevision(),
                                        current.executionGeneration())
                                .id())
                .isEqualTo(restored.id());
        assertThat(versionService.list(f.actor(), f.scope().taskId(), 0, 20))
                .hasSize(2)
                .allMatch(v -> v.accepted());
        assertThat(versionService.detail(f.actor(), f.scope().taskId(), first).sourceMessages())
                .isNotEmpty();
        assertThat(
                        versionService.diff(
                                f.actor(), f.scope().taskId(), current.currentVersionId(), first))
                .anyMatch(v -> v.kind().equals("Item") && v.action().equals("CHANGED"));
        assertThatThrownBy(
                        () ->
                                versionService.restore(
                                        f.actor(),
                                        f.scope().taskId(),
                                        request,
                                        current.currentVersionId(),
                                        current.currentVersionId(),
                                        current.constraintRevision(),
                                        current.executionGeneration()))
                .hasMessage("IDEMPOTENCY_CONFLICT");
        var r = new Fixture(f.actor(), f.scope(), restored.id());
        through(r, StageType.PRESENT);
        var candidate = itineraries.latest(r.actor(), r.scope().taskId()).candidate();
        assertThat(candidate.snapshot().items())
                .isEqualTo(candidates.base(f.scope(), first).snapshot().items());
        assertThat(candidate.version().snapshotId()).isNotEqualTo(first);
        assertThat(
                        versionService
                                .detail(
                                        f.actor(),
                                        f.scope().taskId(),
                                        candidate.version().snapshotId())
                                .sourceCode())
                .isEqualTo("HISTORY_RESTORE");
        assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId())
                .isEqualTo(current.currentVersionId());
        assertThat(candidate.validation().conclusion()).isEqualTo(ItineraryReport.Conclusion.PASS);
        assertThat(candidate.review().conclusion()).isEqualTo(ItineraryReport.Conclusion.PASS);
        assertThat(accept(r).status()).isEqualTo(CandidateStore.Status.ACCEPTED);
        assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId())
                .isEqualTo(candidate.version().snapshotId());
    }

    /** 历史预算和旧报告不能覆盖最新硬约束；不满足的新恢复候选保持阻断。 */
    @Test
    void restoreUsesCurrentRequirementsRatherThanHistoricalOnes() {
        var f = replan(fixture(), Map.of("budgetCny", "10"));
        through(f, StageType.UNDERSTAND);
        var current = travel.task(f.actor(), f.scope().taskId()).task();
        var run =
                versionService.restore(
                        f.actor(),
                        f.scope().taskId(),
                        UUID.randomUUID(),
                        current.currentVersionId(),
                        current.currentVersionId(),
                        current.constraintRevision(),
                        current.executionGeneration());
        var restored = new Fixture(f.actor(), f.scope(), run.id());
        through(restored, StageType.VALIDATE);
        var view = itineraries.latest(f.actor(), f.scope().taskId());
        assertThat(view.acceptable()).isFalse();
        assertThat(view.candidate().validation().issues())
                .anyMatch(i -> i.code().equals("HARD_BUDGET_EXCEEDED"));
        assertThat(travel.task(f.actor(), f.scope().taskId()).task().constraintRevision())
                .isEqualTo(current.constraintRevision());
        assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId())
                .isEqualTo(current.currentVersionId());
    }

    /** 跨用户历史不可见，未接受草案不可恢复，过期预期不能启动替代运行。 */
    @Test
    void versionOwnershipDraftAndStaleRestoreAreRejected() {
        var f = fixture();
        through(f, StageType.PRESENT);
        var candidate = itineraries.latest(f.actor(), f.scope().taskId()).candidate();
        var other = fixture();
        assertThatThrownBy(
                        () ->
                                versionService.detail(
                                        other.actor(),
                                        f.scope().taskId(),
                                        candidate.version().snapshotId()))
                .hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> versionService.list(other.actor(), f.scope().taskId(), 0, 20))
                .hasMessage("NOT_FOUND");
        assertThatThrownBy(
                        () ->
                                versionService.restore(
                                        f.actor(),
                                        f.scope().taskId(),
                                        UUID.randomUUID(),
                                        candidate.version().snapshotId(),
                                        candidate.version().snapshotId(),
                                        candidate.version().constraintRevision(),
                                        1))
                .hasMessage("INVALID_INPUT");
        accept(f);
        var task = travel.task(f.actor(), f.scope().taskId()).task();
        assertThatThrownBy(
                        () ->
                                versionService.restore(
                                        f.actor(),
                                        f.scope().taskId(),
                                        UUID.randomUUID(),
                                        task.currentVersionId(),
                                        task.currentVersionId(),
                                        task.constraintRevision() + 1,
                                        task.executionGeneration()))
                .hasMessage("STALE_VERSION");
    }

    /** 两个恢复命令同一预期竞争时只启动一个运行；另一个不会悄悄取代用户第一次请求。 */
    @Test
    void concurrentRestoreHasOneWinner() throws Exception {
        var f = fixture();
        through(f, StageType.PRESENT);
        accept(f);
        var current = travel.task(f.actor(), f.scope().taskId()).task();
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var work =
                    (java.util.concurrent.Callable<String>)
                            () -> {
                                gate.await();
                                try {
                                    versionService.restore(
                                            f.actor(),
                                            f.scope().taskId(),
                                            UUID.randomUUID(),
                                            current.currentVersionId(),
                                            current.currentVersionId(),
                                            current.constraintRevision(),
                                            current.executionGeneration());
                                    return "QUEUED";
                                } catch (
                                        com.lawrence.travel.application.travel.TravelStorageFailure
                                                failure) {
                                    return failure.code().name();
                                }
                            };
            var a = pool.submit(work);
            var b = pool.submit(work);
            gate.countDown();
            assertThat(List.of(a.get(), b.get()))
                    .containsExactlyInAnyOrder("QUEUED", "STALE_VERSION");
        }
    }

    /** 新需求取代历史恢复时，已经生成的恢复Patch失去提交资格。 */
    @Test
    void supersededRestoreCannotCommit() {
        var f = fixture();
        through(f, StageType.PRESENT);
        accept(f);
        var current = travel.task(f.actor(), f.scope().taskId()).task();
        var run =
                versionService.restore(
                        f.actor(),
                        f.scope().taskId(),
                        UUID.randomUUID(),
                        current.currentVersionId(),
                        current.currentVersionId(),
                        current.constraintRevision(),
                        current.executionGeneration());
        var restored = new Fixture(f.actor(), f.scope(), run.id());
        var c = claim(restored);
        var out = planning.execute(c);
        negotiation.send(
                f.actor(),
                f.scope().taskId(),
                UUID.randomUUID(),
                "",
                List.of(new Negotiation.Fact("interests", "新的要求", null)));
        assertThat(engine.commit(engine.prepare(c, out))).isEqualTo(StageCommitPort.Result.STALE);
    }

    /** 恢复目标会跨校验修订阶段保存，不会在第一次受阻后退回无目标普通规划。 */
    @Test
    void restoreGoalSurvivesValidationRepair() {
        var f = replan(fixture(), Map.of("budgetCny", "10"));
        through(f, StageType.UNDERSTAND);
        var task = travel.task(f.actor(), f.scope().taskId()).task();
        var run =
                versionService.restore(
                        f.actor(),
                        f.scope().taskId(),
                        UUID.randomUUID(),
                        task.currentVersionId(),
                        task.currentVersionId(),
                        task.constraintRevision(),
                        task.executionGeneration());
        var r = new Fixture(f.actor(), f.scope(), run.id());
        through(r, StageType.VALIDATE);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            Planning.Context context = invocation.getArgument(1);
                            Planning.Base base = invocation.getArgument(2);
                            assertThat(context.restorationTarget()).isNotNull();
                            return new Planning.PatchReply(
                                    new com.lawrence.travel.domain.itinerary.ItineraryPatch(
                                            base.hash(), List.of(), null),
                                    null);
                        })
                .when(advisor)
                .patch(any(), any(), any(), any(), any(), any());
        var c = claim(r);
        assertThat(c.input()).contains("restoreSnapshotId");
        assertThat(engine.commit(engine.prepare(c, planning.execute(c))))
                .isEqualTo(StageCommitPort.Result.COMMITTED);
    }

    /** 历史与当前锁定冲突直接等待用户，不把恢复变成没有目标的普通模型修改。 */
    @Test
    void restoreLockConflictWaitsWithoutModelRewrite() {
        var f = replan(fixture(), Map.of("interests", "新说明"));
        var first = travel.task(f.actor(), f.scope().taskId()).task().currentVersionId();
        through(f, StageType.PRESENT);
        accept(f);
        var item =
                candidates
                        .base(
                                f.scope(),
                                travel.task(f.actor(), f.scope().taskId())
                                        .task()
                                        .currentVersionId())
                        .snapshot()
                        .items()
                        .getFirst()
                        .id();
        var receipt =
                negotiation.send(
                        f.actor(),
                        f.scope().taskId(),
                        UUID.randomUUID(),
                        "",
                        List.of(new Negotiation.Fact("lockedIds", item.toString(), null)));
        through(new Fixture(f.actor(), f.scope(), receipt.runId()), StageType.UNDERSTAND);
        var task = travel.task(f.actor(), f.scope().taskId()).task();
        var run =
                versionService.restore(
                        f.actor(),
                        f.scope().taskId(),
                        UUID.randomUUID(),
                        first,
                        task.currentVersionId(),
                        task.constraintRevision(),
                        task.executionGeneration());
        var r = new Fixture(f.actor(), f.scope(), run.id());
        advance(r);
        assertThat(engine.run(f.scope(), run.id()).state())
                .isEqualTo(com.lawrence.travel.domain.execution.ExecutionRun.State.WAITING_USER);
        verify(advisor, times(1)).patch(any(), any(), any(), any(), any(), any());
        assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId())
                .isEqualTo(task.currentVersionId());
    }

    /** 三城市代表任务走完真实阶段事务；路线和票价是明确合成资料，不能解释为真实旅行评测。 */
    @Test
    void threeCityRepresentativeWorkflowReachesExplicitAcceptance() {
        var original = fixture();
        var receipt =
                negotiation.send(
                        original.actor(),
                        original.scope().taskId(),
                        UUID.randomUUID(),
                        "",
                        List.of(
                                new Negotiation.Fact("cities", "北京,天津,上海", null),
                                new Negotiation.Fact("budgetRule", "NONE", null),
                                new Negotiation.Fact("travelers", "4", null)));
        var f = new Fixture(original.actor(), original.scope(), receipt.runId());
        String[] cities = {"北京", "天津", "上海"},
                coordinates = {"116.4,39.9", "117.2,39.1", "121.4,31.2"};
        var queries = new ArrayList<Research.Query>();
        for (int i = 0; i < 3; i++)
            queries.add(
                    new Research.Query(
                            "poi:" + i,
                            "poi",
                            TravelToolPort.Operation.PLACE,
                            Map.of("city", cities[i], "keywords", "测试地点"),
                            date));
        for (int i = 0; i < 2; i++) {
            String target = "poi:" + i + "->poi:" + (i + 1);
            queries.add(
                    new Research.Query(
                            target,
                            "route",
                            TravelToolPort.Operation.ROUTE,
                            Map.of(
                                    "origin",
                                    coordinates[i],
                                    "destination",
                                    coordinates[i + 1],
                                    "mode",
                                    "driving",
                                    "date",
                                    date.toString()),
                            date));
            queries.add(
                    new Research.Query(
                            target,
                            "price",
                            TravelToolPort.Operation.EXTRACT,
                            Map.of("url", "https://official.example/fare" + i),
                            date));
        }
        org.mockito.Mockito.doReturn(new Research.Plan(queries))
                .when(researcher)
                .plan(any(), any());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            TravelToolPort.Operation operation = invocation.getArgument(1);
                            @SuppressWarnings("unchecked")
                            Map<String, String> args = invocation.getArgument(2);
                            Map<String, Object> data;
                            if (operation == TravelToolPort.Operation.PLACE) {
                                int i = List.of(cities).indexOf(args.get("city"));
                                data =
                                        Map.of(
                                                "pois",
                                                List.of(
                                                        Map.of(
                                                                "id",
                                                                Integer.toString(i),
                                                                "cityname",
                                                                cities[i],
                                                                "location",
                                                                coordinates[i])));
                            } else if (operation == TravelToolPort.Operation.ROUTE)
                                data =
                                        Map.of(
                                                "route",
                                                Map.of(
                                                        "origin",
                                                        args.get("origin"),
                                                        "destination",
                                                        args.get("destination"),
                                                        "paths",
                                                        List.of(
                                                                Map.of(
                                                                        "duration",
                                                                        args.get("origin")
                                                                                        .equals(
                                                                                                coordinates[
                                                                                                        0])
                                                                                ? "5400"
                                                                                : "21600"))));
                            else {
                                String url = args.get("url");
                                data =
                                        Map.of(
                                                "results",
                                                List.of(
                                                        Map.of(
                                                                "url",
                                                                url,
                                                                "raw_content",
                                                                date
                                                                        + " "
                                                                        + (url.endsWith("0")
                                                                                ? "100元/人"
                                                                                : "500元/人"))));
                            }
                            return new TravelToolPort.Evidence(
                                    URI.create(
                                            operation == TravelToolPort.Operation.EXTRACT
                                                    ? args.get("url")
                                                    : "https://restapi.amap.com/v3/test"),
                                    Instant.now(),
                                    json.encode(
                                            Map.of(
                                                    "operation",
                                                    operation.name(),
                                                    "status",
                                                    "AVAILABLE",
                                                    "targetDate",
                                                    date.toString(),
                                                    "data",
                                                    data)));
                        })
                .when(tools)
                .query(any(), any(), any());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            Research.Query query = invocation.getArgument(1);
                            String value =
                                    query.arguments().get("url").endsWith("0")
                                            ? "100元/人"
                                            : "500元/人";
                            return new Research.Extraction(value, date + " " + value, null, date);
                        })
                .when(researcher)
                .extract(any(), any(), any());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            Planning.Context context = invocation.getArgument(1);
                            var start = date.atStartOfDay(ZoneId.of("Asia/Shanghai"));
                            var stays = new ArrayList<TripSnapshot.CityStay>();
                            var items = new ArrayList<TripSnapshot.Item>();
                            var legs = new ArrayList<TripSnapshot.Transport>();
                            var costs = new ArrayList<TripSnapshot.Cost>();
                            var uses = new ArrayList<TripSnapshot.EvidenceUse>();
                            int[] boundaries = {0, 11, 19, 24}, hours = {8, 12, 20};
                            for (int i = 0; i < 3; i++) {
                                UUID stay = UUID.randomUUID();
                                stays.add(
                                        new TripSnapshot.CityStay(
                                                stay,
                                                cities[i],
                                                start.plusHours(boundaries[i]),
                                                start.plusHours(boundaries[i + 1])));
                                var item =
                                        new TripSnapshot.Item(
                                                UUID.randomUUID(),
                                                stay,
                                                TripSnapshot.ItemType.REST,
                                                "合成测试休息点",
                                                "poi:" + i,
                                                start.plusHours(hours[i]),
                                                start.plusHours(hours[i] + 1),
                                                null);
                                items.add(item);
                                int index = i;
                                var fact =
                                        context.evidence().stream()
                                                .filter(
                                                        v ->
                                                                v.evidence()
                                                                                .fieldPath()
                                                                                .equals("poi")
                                                                        && v.evidence()
                                                                                .targetId()
                                                                                .equals(
                                                                                        "poi:"
                                                                                                + index))
                                                .findFirst()
                                                .orElseThrow();
                                uses.add(
                                        new TripSnapshot.EvidenceUse(
                                                item.id(), "poi", fact.evidence().reference()));
                            }
                            for (int i = 0; i < 2; i++) {
                                UUID id = UUID.randomUUID(), cost = UUID.randomUUID();
                                int departure = i == 0 ? 10 : 13;
                                long minutes = i == 0 ? 90 : 360;
                                legs.add(
                                        new TripSnapshot.Transport(
                                                id,
                                                items.get(i).id(),
                                                items.get(i + 1).id(),
                                                start.plusHours(departure),
                                                start.plusHours(departure).plusMinutes(minutes),
                                                minutes,
                                                true));
                                BigDecimal amount = new BigDecimal(i == 0 ? "100" : "500");
                                costs.add(
                                        new TripSnapshot.Cost(
                                                cost,
                                                id,
                                                "INTERCITY",
                                                TripSnapshot.CostUnit.PER_PERSON,
                                                BigDecimal.ONE,
                                                amount,
                                                amount,
                                                false,
                                                true,
                                                "CNY"));
                                String target = "poi:" + i + "->poi:" + (i + 1);
                                for (var fact : context.evidence())
                                    if (fact.evidence().targetId().equals(target))
                                        uses.add(
                                                new TripSnapshot.EvidenceUse(
                                                        fact.evidence().fieldPath().equals("price")
                                                                ? cost
                                                                : id,
                                                        fact.evidence().fieldPath(),
                                                        fact.evidence().reference()));
                            }
                            return new Planning.Draft(
                                    new TripSnapshot(
                                            1,
                                            context.scope().taskId(),
                                            context.revision().revision(),
                                            null,
                                            new TripWindow(Set.of(cities), date, date, 4),
                                            stays,
                                            List.of(new TripSnapshot.Day(UUID.randomUUID(), date)),
                                            items,
                                            legs,
                                            List.of(),
                                            costs,
                                            List.of(),
                                            uses.stream()
                                                    .map(TripSnapshot.EvidenceUse::reference)
                                                    .collect(java.util.stream.Collectors.toSet()),
                                            uses));
                        })
                .when(advisor)
                .draft(any(), any(), any(), any());
        through(f, StageType.PRESENT);
        var candidate = itineraries.latest(f.actor(), f.scope().taskId());
        assertThat(candidate.acceptable()).isTrue();
        assertThat(candidate.candidate().snapshot().cityStays()).hasSize(3);
        assertThat(candidate.costs().upperCny()).isEqualByComparingTo("2400");
        assertThat(accept(f).status()).isEqualTo(CandidateStore.Status.ACCEPTED);
    }

    /** 新工作台与历史入口保留真实HTTP身份、CSRF及归属门禁，查看不会触发生效。 */
    @Test
    void httpWorkspaceAndRestoreRequireOwnerAndCsrf() throws Exception {
        var f = fixture();
        through(f, StageType.PRESENT);
        accept(f);
        var task = travel.task(f.actor(), f.scope().taskId()).task();
        String cookie = session(f.actor(), sessions);
        var http = java.net.http.HttpClient.newHttpClient();
        String root = "http://127.0.0.1:" + port;
        assertThat(
                        http.send(
                                        java.net.http.HttpRequest.newBuilder(
                                                        URI.create(root + "/api/v1/tasks"))
                                                .GET()
                                                .build(),
                                        java.net.http.HttpResponse.BodyHandlers.discarding())
                                .statusCode())
                .isEqualTo(401);
        var list =
                http.send(
                        java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/v1/tasks"))
                                .header("Cookie", cookie)
                                .GET()
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(list.body()).contains(f.scope().taskId().toString());
        String path =
                root
                        + "/api/v1/tasks/"
                        + f.scope().taskId()
                        + "/versions/"
                        + task.currentVersionId();
        var detail =
                http.send(
                        java.net.http.HttpRequest.newBuilder(URI.create(path))
                                .header("Cookie", cookie)
                                .GET()
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding());
        assertThat(detail.statusCode()).isEqualTo(200);
        var body =
                new com.lawrence.travel.api.itinerary.VersionController.Restore(
                        UUID.randomUUID(),
                        task.currentVersionId(),
                        task.constraintRevision(),
                        task.executionGeneration());
        var denied =
                java.net.http.HttpRequest.newBuilder(URI.create(path + "/restore"))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.encode(body)))
                        .build();
        assertThat(
                        http.send(denied, java.net.http.HttpResponse.BodyHandlers.discarding())
                                .statusCode())
                .isEqualTo(403);
        var csrf =
                http.send(
                        java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/csrf"))
                                .header("Cookie", cookie)
                                .GET()
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
        var token = json.decode(csrf.body(), Map.class);
        var allowed =
                java.net.http.HttpRequest.newBuilder(URI.create(path + "/restore"))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .header((String) token.get("headerName"), (String) token.get("token"))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.encode(body)))
                        .build();
        assertThat(
                        http.send(allowed, java.net.http.HttpResponse.BodyHandlers.discarding())
                                .statusCode())
                .isEqualTo(202);
        var other = fixture();
        String otherCookie = session(other.actor(), sessions);
        assertThat(
                        http.send(
                                        java.net.http.HttpRequest.newBuilder(URI.create(path))
                                                .header("Cookie", otherCookie)
                                                .GET()
                                                .build(),
                                        java.net.http.HttpResponse.BodyHandlers.discarding())
                                .statusCode())
                .isEqualTo(404);
    }

    /** 单独重校验意图永不接受，即使当前报告新鲜；重复请求不登记第二阶段，完成后仍待明确接受。 */
    @Test
    void explicitRecheckNeverAcceptsFreshCandidate() {
        var f = fixture();
        through(f, StageType.PRESENT);
        var c = itineraries.latest(f.actor(), f.scope().taskId()).candidate();
        var version = c.version();
        UUID request = UUID.randomUUID();
        long generation = engine.run(f.scope(), f.run()).generation();
        var result =
                itineraries.recheck(
                        f.actor(),
                        f.scope().taskId(),
                        request,
                        c.candidateId(),
                        version.baseVersionId(),
                        version.constraintRevision(),
                        generation,
                        version.payloadHash());
        assertThat(result.status()).isEqualTo(CandidateStore.Status.REVALIDATION_REQUIRED);
        assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId()).isNull();
        assertThat(
                        itineraries.recheck(
                                f.actor(),
                                f.scope().taskId(),
                                request,
                                c.candidateId(),
                                version.baseVersionId(),
                                version.constraintRevision(),
                                generation,
                                version.payloadHash()))
                .isEqualTo(result);
        assertThatThrownBy(
                        () ->
                                itineraries.accept(
                                        f.actor(),
                                        f.scope().taskId(),
                                        request,
                                        c.candidateId(),
                                        version.baseVersionId(),
                                        version.constraintRevision(),
                                        generation,
                                        version.payloadHash()))
                .hasMessage("IDEMPOTENCY_CONFLICT");
        through(f, StageType.PRESENT);
        assertThat(itineraries.latest(f.actor(), f.scope().taskId()).acceptable()).isTrue();
        assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId()).isNull();
        assertThat(accept(f).status()).isEqualTo(CandidateStore.Status.ACCEPTED);
    }

    @Autowired private com.lawrence.travel.application.memory.PreferenceService preferences;

    /**
     * @param f 随机测试任务
     * @param category 类别
     * @param value 内容
     * @return 新偏好身份
     */
    private UUID preference(Fixture f, String category, String value) {
        UUID id = UUID.randomUUID();
        preferences.change(
                f.actor(),
                UUID.randomUUID(),
                preferences.read(f.actor()).version().revision(),
                id,
                "SAVE",
                category,
                value,
                "");
        return id;
    }

    /** 已确认偏好进入独立规划及审核上下文，待确认建议从不参与；候选绑定真实用户版本。 */
    @Test
    void confirmedPreferencesAreUsedAndBoundToCandidate() {
        var f = fixture();
        preference(f, "pace", "轻松");
        preferences.change(
                f.actor(), UUID.randomUUID(), 1, UUID.randomUUID(), "SUGGEST", "diet", "清淡", "");
        through(f, StageType.PRESENT);
        var captured = org.mockito.ArgumentCaptor.forClass(Planning.Context.class);
        verify(advisor).draft(any(), captured.capture(), any(), any());
        assertThat(captured.getValue().preferences().entries())
                .extracting(com.lawrence.travel.domain.memory.Preference::category)
                .containsExactly("pace");
        var review = org.mockito.ArgumentCaptor.forClass(Planning.Context.class);
        verify(advisor).review(any(), review.capture(), any());
        assertThat(review.getValue().preferences()).isEqualTo(captured.getValue().preferences());
        assertThat(
                        itineraries
                                .latest(f.actor(), f.scope().taskId())
                                .candidate()
                                .version()
                                .preferenceVersion()
                                .revision())
                .isEqualTo(2);
        assertThat(accept(f).status()).isEqualTo(CandidateStore.Status.ACCEPTED);
    }

    /** 本次用户明确要求覆盖历史同类偏好，不会把旧偏好写回旅行需求。 */
    @Test
    void currentRequirementsOverrideLongTermPreferences() {
        var original = fixture();
        preference(original, "pace", "轻松");
        var receipt =
                negotiation.send(
                        original.actor(),
                        original.scope().taskId(),
                        UUID.randomUUID(),
                        "",
                        List.of(new Negotiation.Fact("pace", "紧凑", null)));
        var f = new Fixture(original.actor(), original.scope(), receipt.runId());
        through(f, StageType.PRESENT);
        var captured = org.mockito.ArgumentCaptor.forClass(Planning.Context.class);
        verify(advisor).draft(any(), captured.capture(), any(), any());
        assertThat(captured.getValue().preferences().entries()).isEmpty();
        assertThat(captured.getValue().revision().requirements())
                .anyMatch(r -> r.field().equals("pace") && r.value().equals("紧凑"));
        assertThat(preferences.read(f.actor()).entries().getFirst().value()).isEqualTo("轻松");
    }

    /** 模型返回前发生删除，C事务拒绝旧结果和后继；同一次领取可重新装配有效上下文，不新增预算。 */
    @Test
    void deletedPreferenceRejectsLateModelResultAtCommit() {
        var f = fixture();
        UUID id = preference(f, "pace", "轻松");
        through(f, StageType.RESEARCH);
        var claim = claim(f);
        for (int i = 0; claim.step().key().type() != StageType.PLAN && i < 20; i++) {
            assertThat(engine.commit(engine.prepare(claim, research.execute(claim))))
                    .isEqualTo(StageCommitPort.Result.COMMITTED);
            claim = claim(f);
        }
        assertThat(claim.step().key().type()).isEqualTo(StageType.PLAN);
        var output = planning.execute(claim);
        var prepared = engine.prepare(claim, output);
        preferences.change(f.actor(), UUID.randomUUID(), 1, id, "DELETE", null, null, null);
        assertThatThrownBy(() -> engine.commit(prepared))
                .isInstanceOf(
                        com.lawrence.travel.application.memory.PreferenceChangedException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM itinerary_candidate WHERE run_id=?",
                                Integer.class,
                                f.run()))
                .isZero();
        var fresh = planning.execute(claim);
        assertThat(engine.commit(engine.prepare(claim, fresh)))
                .isEqualTo(StageCommitPort.Result.COMMITTED);
        assertThat(
                        itineraries
                                .latest(f.actor(), f.scope().taskId())
                                .candidate()
                                .version()
                                .preferenceVersion()
                                .revision())
                .isEqualTo(2);
        var captured = org.mockito.ArgumentCaptor.forClass(Planning.Context.class);
        verify(advisor, times(2)).draft(any(), captured.capture(), any(), any());
        assertThat(captured.getAllValues().getLast().preferences().entries()).isEmpty();
    }

    /** 已展示草案遇到偏好删除后不能接受，原预算重新规划与审核完成后仍需另行接受。 */
    @Test
    void changedPreferencesInvalidateReportsAndReplanInSameRun() {
        var f = fixture();
        UUID id = preference(f, "pace", "轻松");
        through(f, StageType.PRESENT);
        var old = itineraries.latest(f.actor(), f.scope().taskId()).candidate();
        preferences.change(f.actor(), UUID.randomUUID(), 1, id, "DELETE", null, null, null);
        assertThat(itineraries.latest(f.actor(), f.scope().taskId()).acceptable()).isFalse();
        assertThat(accept(f).status()).isEqualTo(CandidateStore.Status.REVALIDATION_REQUIRED);
        through(f, StageType.PRESENT);
        var fresh = itineraries.latest(f.actor(), f.scope().taskId());
        assertThat(fresh.candidate().version().snapshotId())
                .isNotEqualTo(old.version().snapshotId());
        assertThat(fresh.candidate().version().preferenceVersion().revision()).isEqualTo(2);
        assertThat(budgets.read(f.run()).repairRounds()).isEqualTo(1);
        assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId()).isNull();
        assertThat(accept(f).status()).isEqualTo(CandidateStore.Status.ACCEPTED);
    }

    /** 删除偏好保留已接受指针和独立确认的同类旅行要求。 */
    @Test
    void deletingPreferenceDoesNotRewriteAcceptedItineraryOrUserConstraints() {
        var original = fixture();
        UUID id = preference(original, "pace", "轻松");
        var receipt =
                negotiation.send(
                        original.actor(),
                        original.scope().taskId(),
                        UUID.randomUUID(),
                        "",
                        List.of(new Negotiation.Fact("pace", "轻松", null)));
        var f = new Fixture(original.actor(), original.scope(), receipt.runId());
        through(f, StageType.PRESENT);
        accept(f);
        var before = travel.task(f.actor(), f.scope().taskId());
        preferences.change(f.actor(), UUID.randomUUID(), 1, id, "DELETE", null, null, null);
        var after = travel.task(f.actor(), f.scope().taskId());
        assertThat(after.task().currentVersionId()).isEqualTo(before.task().currentVersionId());
        assertThat(after.task().constraintRevision()).isEqualTo(before.task().constraintRevision());
        assertThat(
                        candidates
                                .base(f.scope(), after.task().currentVersionId())
                                .revision()
                                .requirements())
                .anyMatch(r -> r.field().equals("pace") && r.value().equals("轻松"));
    }

    /** 旧审核迟到时不能保存，重新调用必须读到当前偏好，不能替旧报告重新绑定版本。 */
    @Test
    void preferenceChangeDuringReviewRejectsOldReport() {
        var f = fixture();
        UUID id = preference(f, "pace", "轻松");
        through(f, StageType.VALIDATE);
        var claim = claim(f);
        assertThat(claim.step().key().type()).isEqualTo(StageType.REVIEW);
        var prepared = engine.prepare(claim, planning.execute(claim));
        preferences.change(f.actor(), UUID.randomUUID(), 1, id, "DELETE", null, null, null);
        assertThatThrownBy(() -> engine.commit(prepared))
                .isInstanceOf(
                        com.lawrence.travel.application.memory.PreferenceChangedException.class);
        assertThat(itineraries.latest(f.actor(), f.scope().taskId()).candidate().review()).isNull();
        assertThat(engine.commit(engine.prepare(claim, planning.execute(claim))))
                .isEqualTo(StageCommitPort.Result.COMMITTED);
        assertThat(claim(f).step().key().type()).isEqualTo(StageType.PLAN);
    }

    /** 偏好重规划同样受原修订上限保护，达到两轮时暂停预算，不创建新运行绕过上限。 */
    @Test
    void preferenceReplanningHonorsOriginalRepairBudget() {
        var f = fixture();
        UUID id = preference(f, "pace", "轻松");
        through(f, StageType.PRESENT);
        preferences.change(f.actor(), UUID.randomUUID(), 1, id, "DELETE", null, null, null);
        assertThat(accept(f).status()).isEqualTo(CandidateStore.Status.REVALIDATION_REQUIRED);
        jdbc.update("UPDATE execution_budget SET repair_rounds=2 WHERE run_id=?", f.run());
        var claim = claim(f);
        var control =
                new com.lawrence.travel.application.execution.StageExecutor.Control() {
                    /**
                     * @return 本地执行允许进入
                     */
                    @Override
                    public boolean enter() {
                        return true;
                    }

                    /** 本地无异步取消，无副作用。 */
                    @Override
                    public void leave() {}

                    /**
                     * @return 本地不请求取消
                     */
                    @Override
                    public boolean cancelled() {
                        return false;
                    }
                };
        new com.lawrence.travel.application.execution.StageExecutor(engine, List.of(planning))
                .execute(claim, control);
        assertThat(engine.run(f.scope(), f.run()).state())
                .isEqualTo(com.lawrence.travel.domain.execution.ExecutionRun.State.PAUSED_BUDGET);
        assertThat(budgets.read(f.run()).repairRounds()).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT current_run_id FROM travel_task WHERE user_id=? AND id=?",
                                UUID.class,
                                f.actor().id(),
                                f.scope().taskId()))
                .isEqualTo(f.run());
    }

    /** 上传→实际只读对话→逐项采纳→原需求和受限规划→校验/独立审核→显式接受，所有外部端口均本地替身。 */
    @Test
    void uploadedPlanAdviceUsesOriginalCandidateAndAcceptanceGates() {
        var f = fixture();
        through(f, StageType.PRESENT);
        UUID chat = UUID.randomUUID();
        dialogue.attach(f.actor(), chat, f.scope().taskId());
        when(parser.parse(any()))
                .thenReturn(
                        new com.lawrence.travel.application.document.Documents.Report(
                                "pdfbox-3.0.8_poi-5.5.1_v1",
                                "OK",
                                List.of(
                                        new com.lawrence.travel.application.document.Documents
                                                .Section(1, "打算一天只玩一个景点，住宿尚未预订。")),
                                "合成文本，不是官方证据"));
        var uploaded =
                documents.upload(
                        f.actor(),
                        chat,
                        UUID.randomUUID(),
                        "合成计划.txt",
                        "打算一天只玩一个景点".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var scope = new com.lawrence.travel.application.execution.Jobs.Scope(f.actor().id(), chat);
        var entry = documentStore.get(scope, uploaded.id());
        var parse = jobs.claim(scope, entry.jobId()).orElseThrow();
        documents.commit(parse, documents.execute(parse), () -> {});
        String text = "检查并优化这个文件，先不要修改旅行";
        dialogue.send(f.actor(), chat, UUID.randomUUID(), text);
        when(interpreter.interpret(any()))
                .thenAnswer(
                        call -> {
                            com.lawrence.travel.application.interaction.Dialogue.Claim c =
                                    call.getArgument(0);
                            var fragment =
                                    sources.section(
                                            c.budgetId(), c.token(), uploaded.id(), 1, 0, 1000);
                            var item =
                                    new com.lawrence.travel.domain.interaction.PlanAdvice.Draft(
                                            fragment.reference(),
                                            "一天只玩一个景点",
                                            com.lawrence.travel.domain.interaction.PlanAdvice
                                                    .Statement.INTENDED,
                                            "节奏",
                                            "用户打算减少赶路",
                                            "采用慢游节奏",
                                            "当前旅行节奏",
                                            com.lawrence.travel.domain.interaction.PlanAdvice
                                                    .Evidence.USER_MATERIAL,
                                            "",
                                            Map.of("pace", "慢游"));
                            return new com.lawrence.travel.domain.interaction.DialogueIntent.Result(
                                    List.of(
                                            new com.lawrence.travel.domain.interaction
                                                    .DialogueIntent.Intent(
                                                    com.lawrence.travel.domain.interaction
                                                            .DialogueIntent.Kind.CONVERSATION,
                                                    com.lawrence.travel.domain.interaction
                                                            .DialogueIntent.Action.OPTIMIZE_PLAN,
                                                    text)),
                                    List.of(),
                                    "只给出建议",
                                    "",
                                    null,
                                    "",
                                    "",
                                    "",
                                    List.of(),
                                    List.of(item));
                        });
        long before = travel.task(f.actor(), f.scope().taskId()).task().constraintRevision();
        dialogue.claimWork().orElseThrow().run();
        assertThat(travel.task(f.actor(), f.scope().taskId()).task().constraintRevision())
                .isEqualTo(before);
        var item = advice.list(f.actor(), chat).getFirst();
        advice.act(f.actor(), chat, item.id(), "ADOPT", Map.of(), true);
        UUID run =
                UUID.fromString(
                        jdbc.queryForObject(
                                "SELECT current_run_id::text FROM travel_task WHERE id=?",
                                String.class,
                                f.scope().taskId()));
        var next = new Fixture(f.actor(), f.scope(), run);
        through(next, StageType.PRESENT);
        var candidate = itineraries.latest(f.actor(), f.scope().taskId());
        assertThat(candidate.candidate().review().conclusion())
                .isEqualTo(ItineraryReport.Conclusion.PASS);
        assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId()).isNull();
        assertThat(accept(next).status()).isEqualTo(CandidateStore.Status.ACCEPTED);
        assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId())
                .isEqualTo(candidate.candidate().version().snapshotId());
    }
}
