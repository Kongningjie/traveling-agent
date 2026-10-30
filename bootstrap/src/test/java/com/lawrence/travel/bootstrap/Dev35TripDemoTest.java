package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.evaluation.Evaluation;
import com.lawrence.travel.application.evaluation.SessionEvaluationExecutor;
import com.lawrence.travel.application.execution.TravelToolService;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.application.port.DialogueInterpreter;
import com.lawrence.travel.application.port.PlanningAdvisor;
import com.lawrence.travel.application.port.ResearchAdvisor;
import com.lawrence.travel.application.safety.SafetyGate;
import com.lawrence.travel.bootstrap.config.ExecutionControlProperties;
import com.lawrence.travel.domain.execution.control.CallKind;
import com.lawrence.travel.domain.execution.control.CallRejectedException;
import com.lawrence.travel.domain.interaction.DialogueIntent;
import com.lawrence.travel.infrastructure.evaluation.HttpSessionEvaluationDriver;
import com.lawrence.travel.infrastructure.evaluation.JdbcSessionEvaluationEvidence;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/** 单条旅行演示：真实HTTP收件、后台阶段、独立审核、两次显式接受及局部修改。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_DEV35_TRIP_DEMO", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1", "travel.engine.enabled=true",
            "travel.documents.cleanup-enabled=false", "travel.lifecycle.maintenance-enabled=false",
            "logging.level.root=OFF", "logging.level.io.agentscope=OFF"
        })
class Dev35TripDemoTest {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ExecutionControlProperties properties;
    @MockitoSpyBean private SafetyGate safety;
    @Autowired private com.lawrence.travel.application.port.ToolEvidenceJournal toolJournal;
    @Autowired private com.lawrence.travel.application.port.TravelToolPort toolPort;
    @MockitoSpyBean private DialogueInterpreter interpreter;
    @MockitoSpyBean private ResearchAdvisor researcher;
    @MockitoSpyBean private PlanningAdvisor planner;
    @MockitoSpyBean private TravelToolService tools;
    @MockitoSpyBean private BudgetLedger ledger;
    @MockitoSpyBean private com.lawrence.travel.application.port.AgentRuntimePort runtime;
    private final TravelJson json = new TravelJson();
    private final RuntimeFailureEvidence runtimeFailures = new RuntimeFailureEvidence();

    /** 验证独立库与文件根；使用项目当前模型配置，不额外设置配额窗口。 */
    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry registry) {
        String database = System.getenv("POSTGRES_DB");
        String root = System.getenv("TRAVEL_DEV35_HTTP_STORAGE");
        if (database == null
                || !database.matches("travel_dev35_http_[a-f0-9]{16}")
                || root == null
                || root.isBlank()) throw new IllegalStateException("TRIP_DEMO_ISOLATION_REQUIRED");
        registry.add("travel.external-calls-enabled", Dev35TripDemoTest::real);
        registry.add("travel.tools.enabled", () -> real() && !understandOnly());
        registry.add("spring.session.redis.namespace", () -> "travel:demo:" + database);
        registry.add("travel.deletions.root", () -> Path.of(root, "lifecycle").toString());
        registry.add("travel.artifacts.root", () -> Path.of(root, "artifacts").toString());
        registry.add("travel.documents.root", () -> Path.of(root, "documents").toString());
        registry.add(
                "travel.tools.evidence-hosts[0]",
                () -> real() ? "www.dpm.org.cn" : "official.example");
    }

    /**
     * 是否实际启用本批授权的供应商调用。
     *
     * @return 只有脚本显式选择真实模式时返回true
     */
    private static boolean real() {
        return "true".equals(System.getenv("TRAVEL_DEV35_HTTP_REAL"));
    }

    /**
     * 是否只定位首条需求的理解协议，禁用后续真实资料调用。
     *
     * @return 首条诊断模式，不代表完整旅行验收
     */
    private static boolean understandOnly() {
        return "true".equals(System.getenv("TRAVEL_DEV35_TRIP_UNDERSTAND_ONLY"));
    }

    /**
     * 验证首次生成不生效、接受后生效、修改不提前覆盖，以及第二次接受和请求重放。
     *
     * @throws Exception 本机HTTP、报告写入或业务判据失败
     */
    @Test
    void plansAcceptsAndRevisesOneTrip() throws Exception {
        if (Boolean.parseBoolean(System.getenv("TRAVEL_DEV35_TRIP_TOOL_PROBE"))) {
            probeToolSafety();
            return;
        }
        doAnswer(
                        call -> {
                            String role = call.getArgument(1);
                            if (understandOnly() && !"coordinator".equals(role))
                                throw new CallRejectedException(
                                        CallRejectedException.Reason.MODEL_NOT_APPROVED);
                            return runtimeFailures.observe(
                                    role,
                                    (com.lawrence.travel.application.port.AgentRuntimePort
                                                    .Invocation)
                                            call.callRealMethod());
                        })
                .when(runtime)
                .startContextual(any(), any(), any(), any(), any());
        assertThat(properties.models())
                .containsEntry("coordinator", "qwen3.8-flash")
                .containsEntry("planner", "qwen3.8-flash")
                .containsEntry("research", "qwen3.8-flash")
                .containsEntry("reviewer", "qwen3.8-max-0902");
        var modelCalls = new AtomicInteger();
        var toolCalls = new AtomicInteger();
        doAnswer(
                        call -> {
                            // 真实批次首个运行失败后禁止再预占；不让阶段重试继续发送请求。
                            if (real() && runtimeFailures.failed()) {
                                throw new CallRejectedException(
                                        CallRejectedException.Reason.EXTERNAL_DISABLED);
                            }
                            if (jdbc.queryForObject(
                                            "SELECT count(*) FROM call_meter WHERE status='UNKNOWN'",
                                            Long.class)
                                    > 0)
                                throw new IllegalStateException("TRIP_DEMO_UNKNOWN_USAGE_STOP");
                            CallKind kind = call.getArgument(1);
                            int count =
                                    (kind == CallKind.MODEL ? modelCalls : toolCalls)
                                            .incrementAndGet();
                            if (count > (kind == CallKind.MODEL ? (understandOnly() ? 4 : 30) : 20))
                                throw new CallRejectedException(
                                        CallRejectedException.Reason.CALL_BUDGET_EXHAUSTED);
                            if (kind == CallKind.MODEL
                                    && call.getArgument(3) instanceof BudgetLedger.Target target
                                    && !Set.of("qwen3.8-flash", "qwen3.8-max-0902")
                                            .contains(target.modelId()))
                                throw new CallRejectedException(
                                        CallRejectedException.Reason.MODEL_NOT_APPROVED);
                            return call.callRealMethod();
                        })
                .when(ledger)
                .reserve(any(), any(), any(), any(), anyLong(), any());
        if (!real()) {
            new TripDemoLocalFixture(researcher, planner, tools).install();
            doAnswer(call -> localReply(call.getArgument(0))).when(interpreter).interpret(any());
        }
        var turns =
                List.of(
                        new HttpSessionEvaluationDriver.Turn(
                                "MESSAGE", "开始规划北京旅行：2026年11月7日当天，2人，上午只参观故宫一个景点，其余时间自由休息。", null),
                        new HttpSessionEvaluationDriver.Turn(
                                "MESSAGE", "预算只计算景点活动，两人合计100元，没有已订安排。", null),
                        new HttpSessionEvaluationDriver.Turn("ACCEPT_CANDIDATE", null, null),
                        new HttpSessionEvaluationDriver.Turn(
                                "MESSAGE", "只把第一个活动的说明补充为“慢慢游览，可随时休息”，不改变活动地点、时间、费用和其他安排。", null),
                        new HttpSessionEvaluationDriver.Turn("ACCEPT_CANDIDATE", null, null));
        String script = json.encode(understandOnly() ? turns.subList(0, 1) : turns);
        var input =
                new Evaluation.Case(
                        understandOnly() ? "DEMO-UNDERSTAND-01" : "DEMO-TRIP-01",
                        "DEVELOPMENT",
                        "TRIP_DEMO",
                        "单日旅行的规划、接受和局部说明修改",
                        Map.of("script", script),
                        Map.of());
        var evidence =
                new JdbcSessionEvaluationEvidence(
                        jdbc, transactions, Set.copyOf(properties.models().values()));
        var driver =
                new HttpSessionEvaluationDriver(
                        URI.create("http://127.0.0.1:" + port),
                        evidence,
                        name -> {
                            throw new IllegalArgumentException("TRIP_DEMO_HAS_NO_DOCUMENT");
                        },
                        () -> false,
                        Duration.ofMinutes(15),
                        true);
        var capture = driver.execute(input);
        boolean passed = false;
        try {
            check(capture);
            passed = true;
        } finally {
            save(capture, script, passed);
        }
    }

    /**
     * 单次原文提取定位；保留真实安全检查和PG计量，只报告长度/命中类别，不保存被拒正文。
     *
     * @throws Exception 本地报告不可写
     */
    private void probeToolSafety() throws Exception {
        var count = new AtomicInteger();
        var diagnostics =
                new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>(
                        Map.of("receivedOutput", false));
        doAnswer(
                        call -> {
                            if (call.getArgument(1) != CallKind.TOOL || count.incrementAndGet() > 1)
                                throw new IllegalStateException("TOOL_PROBE_CALL_LIMIT");
                            return call.callRealMethod();
                        })
                .when(ledger)
                .reserve(any(), any(), any(), any(), anyLong(), any());
        doAnswer(
                        call -> {
                            diagnostics.set(ToolSafetyDiagnostics.inspect(call.getArgument(1)));
                            return call.callRealMethod();
                        })
                .when(safety)
                .require(org.mockito.ArgumentMatchers.eq("TOOL_OUTPUT"), any());
        var adapter = toolPort;
        if (!real()) {
            adapter =
                    org.mockito.Mockito.mock(
                            com.lawrence.travel.application.port.TravelToolPort.class);
            org.mockito.Mockito.when(adapter.query(any(), any()))
                    .thenReturn(
                            new com.lawrence.travel.application.port.TravelToolPort.Evidence(
                                    URI.create("https://official.example/"),
                                    java.time.Instant.now(),
                                    "{\"raw_content\":\"[合成链接](javascript:blocked)\"}"));
        }
        // 独立探针没有旅行任务归属，不装配任务追踪；两种模式共用相同服务、账本和安全检查。
        var service =
                new TravelToolService(
                        adapter,
                        toolJournal,
                        ledger,
                        properties.limits().runLimits(),
                        Map.of(),
                        true,
                        java.time.Clock.systemUTC(),
                        safety);
        var run = java.util.UUID.randomUUID();
        ledger.create(run);
        String outcome = "ALLOW";
        try {
            service.query(
                    run,
                    com.lawrence.travel.application.port.TravelToolPort.Operation.EXTRACT,
                    Map.of("url", "https://www.dpm.org.cn/Visit.html"));
        } catch (SafetyGate.Failure failure) {
            outcome = failure.result().status().name() + ":" + failure.result().reason();
        } catch (RuntimeException failure) {
            String message = failure.getMessage();
            outcome =
                    message != null
                                    && message.matches(
                                            "TOOL_(HTTP_[1-5][0-9]{2}|REQUEST_FAILED|RESPONSE_INVALID|RESPONSE_TOO_LARGE|INTERRUPTED|CREDENTIAL_MISSING)")
                            ? message
                            : "TOOL_PROBE_FAILED";
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM call_meter WHERE run_id=? AND kind='MODEL'",
                                Long.class,
                                run))
                .isZero();
        if (!real()) {
            assertThat(outcome).isEqualTo("REJECT:UNSAFE_TEXT");
            assertThat(diagnostics.get().get("activeUri")).isEqualTo(true);
        }
        var metrics = Map.of("actualModelRequests", 0, "toolAttempts", count.get());
        String body =
                json.encode(
                        Map.of(
                                "version",
                                "tool-safety-probe-v2",
                                "mode",
                                "DIAGNOSTIC_ONLY",
                                "reviews",
                                List.of(Map.of("outcome", outcome, "features", diagnostics.get())),
                                "rows",
                                List.of(
                                        Map.of(
                                                "caseId",
                                                "DEMO-TOOL-PROBE",
                                                "mode",
                                                "DIAGNOSTIC_ONLY",
                                                "status",
                                                count.get() == 1 ? "PASS" : "FAIL",
                                                "metrics",
                                                metrics))));
        safety.require("EVALUATION_REPORT", body);
        Path report = Path.of(System.getenv("TRAVEL_DEV35_HTTP_REPORT"));
        Files.createDirectories(report.getParent());
        Files.writeString(report, body);
        System.out.println(
                "DEV35_HTTP case=DEMO-TOOL-PROBE status=" + (count.get() == 1 ? "PASS" : "FAIL"));
        assertThat(count.get()).isEqualTo(1);
    }

    /**
     * 为三条固定合成输入提供本地结构化解释；真实模式不安装。
     *
     * @param claim 当前输入
     * @return 有连续原文依据的字段，不替代真实模型质量验收
     */
    private DialogueIntent.Result localReply(
            com.lawrence.travel.application.interaction.Dialogue.Claim claim) {
        var text = claim.text();
        List<DialogueIntent.Fact> facts;
        DialogueIntent.Action action;
        if (text.startsWith("开始规划")) {
            action = DialogueIntent.Action.START_PLAN;
            facts =
                    List.of(
                            new DialogueIntent.Fact("cities", "北京", "北京"),
                            new DialogueIntent.Fact("startDate", "2026-11-07", "2026年11月7日当天"),
                            new DialogueIntent.Fact("endDate", "2026-11-07", "2026年11月7日当天"),
                            new DialogueIntent.Fact("travelers", "2", "2人"));
        } else if (text.startsWith("预算")) {
            action = DialogueIntent.Action.SUPPLEMENT;
            facts =
                    List.of(
                            new DialogueIntent.Fact("budgetRule", "PER_GROUP", "两人合计100元"),
                            new DialogueIntent.Fact("budgetCny", "100", "两人合计100元"),
                            new DialogueIntent.Fact("budgetScope", "ACTIVITIES", "只计算景点活动"),
                            new DialogueIntent.Fact("fixedStatus", "NONE", "没有已订安排"));
        } else {
            action = DialogueIntent.Action.REVISE_ITINERARY;
            facts = List.of(new DialogueIntent.Fact("interests", "慢慢游览，可随时休息", "慢慢游览，可随时休息"));
        }
        return new DialogueIntent.Result(
                List.of(new DialogueIntent.Intent(DialogueIntent.Kind.PLANNING, action, text)),
                facts,
                "本地替身已记录本轮需求。",
                "",
                null,
                "",
                "",
                "");
    }

    /**
     * 检查HTTP接受证据、数据库接受记录和局部修改范围，不能用模型自评替代。
     *
     * @param capture 本次HTTP及数据库观察结果
     */
    private void check(SessionEvaluationExecutor.Capture capture) {
        if (understandOnly()) {
            assertThat(capture.turns()).as("首条需求理解完成").isEqualTo(1);
            assertThat(capture.state().get("messageStates")).isEqualTo(List.of("DONE"));
            assertThat(capture.metrics()).containsEntry("unknownModelRequests", 0L);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM travel_task WHERE user_id=?",
                                    Long.class,
                                    capture.userId()))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM itinerary_acceptance WHERE user_id=?",
                                    Long.class,
                                    capture.userId()))
                    .isZero();
            return;
        }
        assertThat(capture.turns()).as("完成5个步骤").isEqualTo(5);
        assertThat(capture.metrics())
                .containsEntry("unknownModelRequests", 0L)
                .containsEntry("unknownToolRequests", 0L);
        assertThat(capture.state())
                .containsEntry("httpOwnerVisible", true)
                .containsEntry("httpOtherUserReadDenied", true);
        assertThat((List<?>) capture.state().get("httpAcceptedVersions"))
                .hasSize(2)
                .doesNotHaveDuplicates();
        var snapshots = (List<?>) capture.state().get("httpAcceptedSnapshots");
        var first = (Map<?, ?>) snapshots.getFirst();
        var second = (Map<?, ?>) snapshots.getLast();
        var window = (Map<?, ?>) first.get("window");
        assertThat(window.get("startsOn")).isEqualTo("2026-11-07");
        assertThat(window.get("endsOn")).isEqualTo("2026-11-07");
        assertThat(window.get("travelers")).isEqualTo(2);
        assertThat((List<?>) window.get("cityIds")).isEqualTo(List.of("北京"));
        for (String key :
                List.of(
                        "window",
                        "cityStays",
                        "days",
                        "transportLegs",
                        "accommodationStays",
                        "costEntries"))
            assertThat(second.get(key)).as("保留" + key).isEqualTo(first.get(key));
        var original = (List<?>) first.get("items");
        var revised = (List<?>) second.get("items");
        assertThat(original).isNotEmpty();
        assertThat(revised).hasSameSizeAs(original);
        for (int index = 0; index < original.size(); index++) {
            var before = (Map<?, ?>) original.get(index);
            var after = (Map<?, ?>) revised.get(index);
            for (String key :
                    List.of(
                            "id",
                            "cityStayId",
                            "type",
                            "locationKey",
                            "startsAt",
                            "endsAt",
                            "fixedArrangementId"))
                assertThat(after.get(key)).as("保留活动" + key).isEqualTo(before.get(key));
        }
        assertThat(((Map<?, ?>) revised.getFirst()).get("title"))
                .isNotEqualTo(((Map<?, ?>) original.getFirst()).get("title"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM itinerary_acceptance WHERE user_id=?",
                                Long.class,
                                capture.userId()))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM itinerary_report WHERE user_id=? AND kind='REVIEW'",
                                Long.class,
                                capture.userId()))
                .isGreaterThanOrEqualTo(2);
        if (real()) {
            assertThat(capture.metrics().get("actualModelRequests")).isPositive();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM call_meter WHERE model_id='qwen3.8-max-0902' AND status='COMPLETED'",
                                    Long.class))
                    .isGreaterThanOrEqualTo(2);
        }
    }

    /**
     * 保存合成输入、实际状态和用量；失败也记录，不输出凭据或整份运行日志。
     *
     * @param capture 本批观察
     * @param script 固定合成输入
     * @param passed 仅程序检查结果；人工复核仍待完成
     * @throws Exception 文件或安全检查失败
     */
    private void save(SessionEvaluationExecutor.Capture capture, String script, boolean passed)
            throws Exception {
        var review = new java.util.HashMap<String, Object>();
        review.put("capture", capture);
        review.put("script", script);
        review.put(
                "messages",
                jdbc.queryForList(
                        "SELECT text,reply,state FROM dialogue_input WHERE user_id=? AND chat_id=? ORDER BY sequence",
                        capture.userId(),
                        capture.chatId()));
        review.put(
                "runs",
                jdbc.queryForList(
                        "SELECT state,body->>'pauseReason' AS reason FROM execution_run WHERE user_id=?",
                        capture.userId()));
        review.put(
                "modelMeters",
                jdbc.queryForList(
                        "SELECT model_id,status,input_tokens,output_tokens FROM call_meter WHERE kind='MODEL'"));
        review.put(
                "steps",
                jdbc.queryForList(
                        "SELECT body->'key'->>'type' AS type,body->>'state' AS state FROM execution_step WHERE user_id=?",
                        capture.userId()));
        review.put(
                "outboxErrors",
                jdbc.queryForList(
                        "SELECT last_error_code FROM outbox_event WHERE user_id=? AND last_error_code IS NOT NULL",
                        capture.userId()));
        review.put("runtimeFailures", runtimeFailures.snapshot());
        String body =
                json.encode(
                        Map.of(
                                "version",
                                "trip-demo-v1",
                                "mode",
                                real() ? "REAL_MODEL_AND_TOOL" : "LOCAL_CONTRACT",
                                "manualReview",
                                "PENDING",
                                "reviews",
                                List.of(review),
                                "rows",
                                List.of(
                                        Map.of(
                                                "caseId",
                                                understandOnly()
                                                        ? "DEMO-UNDERSTAND-01"
                                                        : "DEMO-TRIP-01",
                                                "mode",
                                                real() ? "REAL_MODEL_AND_TOOL" : "LOCAL_CONTRACT",
                                                "status",
                                                passed ? "PASS" : "FAIL",
                                                "metrics",
                                                capture.metrics()))));
        safety.require("EVALUATION_REPORT", body);
        Path report = Path.of(System.getenv("TRAVEL_DEV35_HTTP_REPORT"));
        Files.createDirectories(report.getParent());
        Files.writeString(report, body);
        System.out.println(
                "DEV35_HTTP case="
                        + (understandOnly() ? "DEMO-UNDERSTAND-01" : "DEMO-TRIP-01")
                        + " status="
                        + (passed ? "PASS" : "FAIL"));
    }
}
