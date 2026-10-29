package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.evaluation.Evaluation;
import com.lawrence.travel.application.evaluation.EvaluationRunner;
import com.lawrence.travel.application.evaluation.SessionEvaluationExecutor;
import com.lawrence.travel.application.execution.ModelCallAdmission;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.application.port.DialogueInterpreter;
import com.lawrence.travel.application.safety.SafetyGate;
import com.lawrence.travel.bootstrap.config.ExecutionControlProperties;
import com.lawrence.travel.domain.execution.control.CallKind;
import com.lawrence.travel.domain.execution.control.CallRejectedException;
import com.lawrence.travel.domain.interaction.DialogueIntent;
import com.lawrence.travel.infrastructure.evaluation.HttpSessionEvaluationDriver;
import com.lawrence.travel.infrastructure.evaluation.JdbcConversationAcceptanceJudge;
import com.lawrence.travel.infrastructure.evaluation.JdbcSessionEvaluationEvidence;
import com.lawrence.travel.infrastructure.evaluation.PhaseTwoEvaluationSuite;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

/** 隔离库的实际HTTP/登录/Worker/PG裁判入口；默认本地替身，真实模式须脚本及当前授权双重选择。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_DEV35_HTTP_ACCEPTANCE", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1",
            "travel.engine.enabled=true",
            "travel.tools.enabled=false",
            "travel.jobs.model-limit=4",
            "travel.documents.cleanup-enabled=false",
            "travel.lifecycle.maintenance-enabled=false",
            "logging.level.root=OFF",
            "logging.level.io.agentscope=OFF"
        })
class Dev35HttpAcceptanceTest {
    @LocalServerPort private int port;
    @Autowired private Environment environment;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private SafetyGate safety;
    @Autowired private ExecutionControlProperties properties;
    @MockitoSpyBean private DialogueInterpreter interpreter;
    @MockitoSpyBean private BudgetLedger ledger;
    @MockitoSpyBean private ModelCallAdmission admission;
    private final AtomicInteger modelReservations = new AtomicInteger();
    private final List<Evaluation.Row> rows = new ArrayList<>();
    private final List<SessionEvaluationExecutor.Capture> captures = new ArrayList<>();
    private final TravelJson json = new TravelJson();
    private String suiteHash = "UNLOADED";
    private com.sun.net.httpserver.HttpServer parserServer;
    private static final String PARSER_SECRET =
            java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID();
    @Autowired private com.lawrence.travel.application.document.SourceReadService sourceReads;

    /** 在后台Worker创建前验证独立库名及隔离存储根，禁止借错误配置对开发库开启真实Worker。 */
    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry registry) {
        String database = System.getenv("POSTGRES_DB"),
                root = System.getenv("TRAVEL_DEV35_HTTP_STORAGE");
        if (database == null
                || !database.matches("travel_dev35_http_[a-f0-9]{16}")
                || root == null
                || root.isBlank())
            throw new IllegalStateException("HTTP_ACCEPTANCE_ISOLATED_DATABASE_REQUIRED");
        registry.add("travel.external-calls-enabled", Dev35HttpAcceptanceTest::real);
        // 当前批次只批准协调27b；其余角色即使意外被调度，也没有任何可匹配的免费准入快照。
        for (String role : List.of("research", "planner", "reviewer"))
            registry.add("travel.models." + role, () -> "UNAPPROVED_HTTP_ROLE");
        registry.add("spring.session.redis.namespace", () -> "travel:acceptance:" + database);
        registry.add("travel.deletions.root", () -> Path.of(root, "lifecycle").toString());
        registry.add("travel.artifacts.root", () -> Path.of(root, "artifacts").toString());
        registry.add("travel.documents.root", () -> Path.of(root, "documents").toString());
        registry.add("DOCUMENT_PARSER_SECRET", () -> PARSER_SECRET);
        registry.add("travel.documents.runner", () -> "http://127.0.0.1:8766");
    }

    /**
     * @return 只有脚本明确设置的真实模式才打开当前测试进程的模型开关
     */
    private static boolean real() {
        return "true".equals(System.getenv("TRAVEL_DEV35_HTTP_REAL"));
    }

    /**
     * @return 显式选择补充回归才返回true；非法批次在首个HTTP或模型请求前拒绝
     */
    private static boolean requirementsBatch() {
        String batch = System.getenv("TRAVEL_DEV35_HTTP_BATCH");
        if (batch == null
                || "Conversation".equals(batch)
                || ("Documents".equals(batch) || "DocumentReferences".equals(batch))) return false;
        if ("Requirements".equals(batch)) return true;
        throw new IllegalArgumentException("HTTP_ACCEPTANCE_BATCH_INVALID");
    }

    /**
     * @return 只有明确选择Documents或DocumentReferences才运行上传与真实隔离解析，不因其他场景顺带启动runner
     */
    private static boolean documentsBatch() {
        return "Documents".equals(System.getenv("TRAVEL_DEV35_HTTP_BATCH"))
                || documentReferencesBatch();
    }

    /**
     * @return 独立自然语言文件引用批次，不回写封存P07/P19输入或成绩
     */
    private static boolean documentReferencesBatch() {
        return "DocumentReferences".equals(System.getenv("TRAVEL_DEV35_HTTP_BATCH"));
    }

    /**
     * 文档批次独占8766并使用本批原件根；端口冲突直接失败，不停止或接管用户runner。
     * @throws java.io.IOException 本机runner不可启动
     */
    @org.junit.jupiter.api.BeforeEach
    void startsDocumentRunner() throws java.io.IOException {
        if (documentsBatch()) {
            fixture("trip1.txt");
            parserServer =
                    new com.lawrence.travel.infrastructure.document.ParserRunner(
                                    Path.of(
                                            environment.getRequiredProperty(
                                                    "TRAVEL_DEV35_HTTP_STORAGE"),
                                            "documents"),
                                    environment.getRequiredProperty("DOCUMENT_PARSER_IMAGE"),
                                    PARSER_SECRET)
                            .start("127.0.0.1", 8766);
        }
    }

    /**
     * @param name 封存逻辑名
     * @return 原件副本及名称；首个模型调用前校验固定哈希，禁止读取任意本机文件
     */
    private HttpSessionEvaluationDriver.File fixture(String name) {
        if (!documentsBatch() || !"trip1.txt".equals(name))
            throw new IllegalArgumentException("HTTP_ACCEPTANCE_FIXTURE_NOT_SUPPORTED");
        try (var stream = getClass().getResourceAsStream("/evaluation/documents/trip1.txt")) {
            if (stream == null) throw new IllegalStateException("DOCUMENT_FIXTURE_MISSING");
            byte[] bytes = stream.readAllBytes();
            if (!com.lawrence.travel.application.artifact.ArtifactService.hash(bytes)
                    .equals(
                            com.lawrence.travel.infrastructure.evaluation
                                    .JdbcDocumentAcceptanceJudge.FIXTURE_HASH))
                throw new IllegalStateException("DOCUMENT_FIXTURE_CHANGED");
            return new HttpSessionEvaluationDriver.File(name, bytes);
        } catch (java.io.IOException unavailable) {
            throw new IllegalStateException("DOCUMENT_FIXTURE_UNAVAILABLE");
        }
    }

    /**
     * @return 封存会话或独立补充回归；输入哈希写入报告，补充用例不修改原24例锁
     * @throws java.io.IOException 补充合成资源不可读
     */
    private Evaluation.Suite selectedSuite() throws java.io.IOException {
        if (documentReferencesBatch()) {
            try (var stream =
                    getClass()
                            .getResourceAsStream(
                                    "/evaluation/regressions/document-references.json")) {
                if (stream == null)
                    throw new IllegalStateException("DOCUMENT_REFERENCE_FIXTURE_MISSING");
                byte[] source = stream.readAllBytes();
                var cases =
                        List.of(
                                json.decode(
                                        new String(source, java.nio.charset.StandardCharsets.UTF_8),
                                        Evaluation.Case[].class));
                if (!cases.stream().map(Evaluation.Case::id).toList().equals(List.of("R02", "R03")))
                    throw new IllegalStateException("DOCUMENT_REFERENCE_FIXTURE_INVALID");
                var oracle =
                        new Evaluation.Oracle(
                                "COMPLETED",
                                Set.of(
                                        "OWNER_ISOLATED",
                                        "NO_UNCONFIRMED_WRITE",
                                        "NO_SECRET_OUTPUT",
                                        "SCRIPT_COMPLETED",
                                        "SOURCE_VERSION_VERIFIED",
                                        "DOCUMENT_NOT_OFFICIAL_FACT",
                                        "DELETION_WATERMARK_CHECKED",
                                        "AUDIT_BOUNDED",
                                        "MESSAGE_DOCUMENT_BOUND"));
                var oracles = Map.of("R02", oracle, "R03", oracle);
                String fingerprint = referenceSuiteFingerprint(source, oracle);
                return new Evaluation.Suite(
                        "document-reference-regression-v1", fingerprint, cases, oracles);
            }
        }
        if (!requirementsBatch())
            return new PhaseTwoEvaluationSuite(PhaseTwoEvaluationTest::resource).load(false);
        try (var stream =
                getClass().getResourceAsStream("/evaluation/regressions/requirements.json")) {
            if (stream == null) throw new IllegalStateException("REGRESSION_FIXTURE_MISSING");
            byte[] bytes = stream.readAllBytes();
            var cases =
                    List.of(
                            json.decode(
                                    new String(bytes, java.nio.charset.StandardCharsets.UTF_8),
                                    Evaluation.Case[].class));
            if (cases.size() != 1 || !"R01".equals(cases.getFirst().id()))
                throw new IllegalStateException("REGRESSION_FIXTURE_INVALID");
            var oracle =
                    new Evaluation.Oracle(
                            "WAITING_USER",
                            Set.of(
                                    "OWNER_ISOLATED",
                                    "NO_UNCONFIRMED_WRITE",
                                    "NO_SECRET_OUTPUT",
                                    "SCRIPT_COMPLETED",
                                    "CONTEXT_BOUNDED",
                                    "CURRENT_REQUIREMENTS_PRESERVED"));
            return new Evaluation.Suite(
                    "requirements-regression-v1",
                    com.lawrence.travel.application.artifact.ArtifactService.hash(bytes),
                    cases,
                    Map.of("R01", oracle));
        }
    }

    /**
     * 对补充输入和程序判据建立稳定指纹，不依赖Set/Map在不同JVM中的迭代顺序。
     *
     * @param source 原始补充用例字节
     * @param oracle 两例共同采用的固定程序判据
     * @return 输入内容、预期结果和排序后检查项共同决定的SHA256
     */
    static String referenceSuiteFingerprint(byte[] source, Evaluation.Oracle oracle) {
        String canonical =
                new String(source, java.nio.charset.StandardCharsets.UTF_8)
                        + "\n"
                        + oracle.outcome()
                        + "\n"
                        + new java.util.TreeSet<>(oracle.required());
        return com.lawrence.travel.application.artifact.ArtifactService.hash(
                canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 执行所选封存会话或独立回归的HTTP闭环；首个失败停止后续场景，未完成判据绝不填true。
     * @throws Exception 报告或本机HTTP证据不可用
     */
    @Test
    void runsLockedConversationCasesOverHttp() throws Exception {
        assertThat(properties.models().get("coordinator")).isEqualTo("qwen3.8-flash");
        if (!real())
            org.mockito.Mockito.doAnswer(invocation -> localReply(invocation.getArgument(0)))
                    .when(interpreter)
                    .interpret(any());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            if (!"coordinator".equals(invocation.getArgument(1)))
                                throw new CallRejectedException(
                                        CallRejectedException.Reason.MODEL_NOT_APPROVED);
                            return invocation.callRealMethod();
                        })
                .when(admission)
                .begin(any(), anyString(), anyString(), anyString(), anyLong());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            countModelReservation(modelReservations, invocation.getArgument(1));
                            return invocation.callRealMethod();
                        })
                .when(ledger)
                .reserve(any(), any(), any(), any(), anyLong(), any());
        var suite = selectedSuite();
        suiteHash = suite.fingerprint();
        var evidence =
                new JdbcSessionEvaluationEvidence(
                        jdbc, transactions, Set.of(properties.models().get("coordinator")));
        var conversationJudge =
                new JdbcConversationAcceptanceJudge(
                        jdbc, transactions, safety, properties.models().get("coordinator"), real());
        SessionEvaluationExecutor.Judge judge =
                documentsBatch()
                        ? new com.lawrence.travel.infrastructure.evaluation
                                .JdbcDocumentAcceptanceJudge(
                                jdbc,
                                transactions,
                                safety,
                                properties.models().get("coordinator"),
                                real())
                        : conversationJudge;
        var driver =
                new HttpSessionEvaluationDriver(
                        URI.create("http://127.0.0.1:" + port),
                        evidence,
                        this::fixture,
                        () -> false,
                        Duration.ofMinutes(3),
                        true);
        var mode = real() ? Evaluation.Mode.REAL_MODEL : Evaluation.Mode.LOCAL_CONTRACT;
        var executor =
                new SessionEvaluationExecutor(
                        mode,
                        input -> {
                            var c = driver.execute(input);
                            captures.add(c);
                            return c;
                        },
                        judge,
                        real());
        var ids =
                requirementsBatch()
                        ? List.of("R01")
                        : documentReferencesBatch()
                                ? List.of("R02", "R03")
                                : documentsBatch() ? List.of("P07", "P19") : List.of("P01", "P02");
        for (String id : ids) {
            var input =
                    suite.cases().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
            var selected =
                    new Evaluation.Suite(
                            suite.version(),
                            suite.fingerprint(),
                            List.of(input),
                            Map.of(id, suite.oracles().get(id)));
            var report =
                    real()
                            ? EvaluationRunner.modelsOnly()
                                    .runModels(selected, executor, true, Map.of())
                            : new EvaluationRunner(executor).run(selected, false, Map.of());
            var row =
                    report.rows().stream().filter(r -> r.mode() == mode).findFirst().orElseThrow();
            rows.add(row);
            if (!real() && id.equals("P01") && row.status().equals("PASS"))
                verifyJudgeRejectsMissingProof(conversationJudge, captures.getLast());
            if (!real() && id.equals("R01") && row.status().equals("PASS"))
                verifyRegressionRejectsLostPreference(conversationJudge, captures.getLast());
            if (!real() && documentsBatch() && row.status().equals("PASS"))
                verifyDocumentJudgeRejectsMissingProof(id, judge, captures.getLast());
            System.out.println(
                    "DEV35_HTTP case=" + id + " mode=" + mode + " status=" + row.status());
            if (!row.status().equals("PASS")) break;
        }
        assertThat(rows).hasSize(ids.size()).allMatch(r -> r.status().equals("PASS"));
    }

    /**
     * @param id 文档场景
     * @param judge 实际PG裁判
     * @param c 已完成合成闭环；缺少HTTP证据必须失败，不能由PG计数补真
     */
    private void verifyDocumentJudgeRejectsMissingProof(
            String id, SessionEvaluationExecutor.Judge judge, SessionEvaluationExecutor.Capture c) {
        var missing =
                new SessionEvaluationExecutor.Capture(
                        c.userId(),
                        c.chatId(),
                        c.outcome(),
                        c.turns(),
                        Map.of(),
                        c.metrics(),
                        c.trace());
        assertThat(judge.check(id, missing)).containsEntry("OWNER_ISOLATED", false);
        if (documentReferencesBatch()) {
            assertThat(judge.check(id, missing)).containsEntry("MESSAGE_DOCUMENT_BOUND", false);
            assertThat(judge.check(id, c)).containsEntry("MESSAGE_DOCUMENT_BOUND", true);
        }
        var incomplete =
                new SessionEvaluationExecutor.Capture(
                        c.userId(), c.chatId(), c.outcome(), 0, c.state(), c.metrics(), List.of());
        assertThat(judge.check(id, incomplete)).containsEntry("SCRIPT_COMPLETED", false);
        String sql =
                "SELECT d.id,d.hash,d.report::text,j.state FROM uploaded_document d JOIN background_job j ON j.id=d.job_id WHERE d.user_id=? AND d.chat_id=? AND NOT d.deleted";
        var wrongVersion =
                new java.util.HashMap<>(jdbc.queryForList(sql, c.userId(), c.chatId()).getFirst());
        wrongVersion.put("hash", "0".repeat(64));
        var reader = org.mockito.Mockito.spy(jdbc);
        org.mockito.Mockito.doReturn(List.of(wrongVersion))
                .when(reader)
                .queryForList(sql, c.userId(), c.chatId());
        var rejecting =
                new com.lawrence.travel.infrastructure.evaluation.JdbcDocumentAcceptanceJudge(
                        reader,
                        transactions,
                        safety,
                        properties.models().get("coordinator"),
                        false);
        assertThat(rejecting.check(id, c))
                .containsEntry("SOURCE_VERSION_VERIFIED", false)
                .containsEntry("DELETION_WATERMARK_CHECKED", false);
    }

    /**
     * 对只读观察器注入第三轮偏好丢失负例，不更新不可变业务快照，不能只因最终正确掩盖中途丢失。
     *
     * @param judge 同库只读裁判
     * @param c 本地四轮完成证据
     */
    private void verifyRegressionRejectsLostPreference(
            JdbcConversationAcceptanceJudge judge, SessionEvaluationExecutor.Capture c) {
        var task =
                jdbc.queryForObject(
                        "SELECT task_id FROM dialogue_chat WHERE user_id=? AND id=?",
                        java.util.UUID.class,
                        c.userId(),
                        c.chatId());
        String sql =
                "SELECT payload::text FROM travel_constraint_revision WHERE user_id=? AND task_id=? AND revision BETWEEN 2 AND 5 ORDER BY revision";
        var broken = new ArrayList<>(jdbc.queryForList(sql, String.class, c.userId(), task));
        broken.set(2, broken.get(2).replace("每天午休", "偏好丢失"));
        var reader = org.mockito.Mockito.spy(jdbc);
        org.mockito.Mockito.doReturn(List.copyOf(broken))
                .when(reader)
                .queryForList(sql, String.class, c.userId(), task);
        var rejecting =
                new JdbcConversationAcceptanceJudge(
                        reader,
                        transactions,
                        safety,
                        properties.models().get("coordinator"),
                        false);
        assertThat(rejecting.check("R01", c))
                .containsEntry("CURRENT_REQUIREMENTS_PRESERVED", false);
        assertThat(judge.check("R01", c)).containsEntry("CURRENT_REQUIREMENTS_PRESERVED", true);
    }

    /**
     * @param judge 实际PG裁判
     * @param c 已完成本地HTTP咨询；负例只改观察输入，不伪造真实成绩或破坏业务数据
     */
    private void verifyJudgeRejectsMissingProof(
            JdbcConversationAcceptanceJudge judge, SessionEvaluationExecutor.Capture c) {
        var batch = new AtomicInteger();
        for (int i = 0; i < 16; i++) countModelReservation(batch, CallKind.MODEL);
        countModelReservation(batch, CallKind.TOOL);
        assertThat(batch.get()).isEqualTo(16);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> countModelReservation(batch, CallKind.MODEL))
                .hasMessage("CALL_BUDGET_EXHAUSTED");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                admission.begin(
                                        java.util.UUID.randomUUID(),
                                        "research",
                                        "cn-beijing",
                                        "DASHSCOPE_NATIVE_REALTIME",
                                        128))
                .hasMessage("MODEL_NOT_APPROVED");
        var missing =
                new SessionEvaluationExecutor.Capture(
                        c.userId(), c.chatId(), c.outcome(), 0, Map.of(), c.metrics(), c.trace());
        assertThat(judge.check("P01", missing))
                .containsEntry("OWNER_ISOLATED", false)
                .containsEntry("SCRIPT_COMPLETED", false);
        assertThat(judge.check("P02", c))
                .containsEntry("CURRENT_REQUIREMENTS_PRESERVED", false)
                .containsEntry("NO_UNCONFIRMED_WRITE", false);
        var requiresReal =
                new JdbcConversationAcceptanceJudge(
                        jdbc, transactions, safety, properties.models().get("coordinator"), true);
        assertThat(requiresReal.check("P01", c)).containsEntry("CONTEXT_BOUNDED", false);
        var syntheticSecret = new SafetyGate(() -> List.of("本地替身只做旅行咨询"), (point, result) -> {});
        assertThat(
                        new JdbcConversationAcceptanceJudge(
                                        jdbc,
                                        transactions,
                                        syntheticSecret,
                                        properties.models().get("coordinator"),
                                        false)
                                .check("P01", c))
                .containsEntry("NO_SECRET_OUTPUT", false);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> judge.check("P03", c))
                .hasMessage("JUDGE_CASE_NOT_SUPPORTED");
    }

    /**
     * @param count 全批计数
     * @param kind 计量类型；模型预占尝试最多16次，底层拒绝也不退还本批尝试额
     */
    private static void countModelReservation(AtomicInteger count, CallKind kind) {
        if (kind == CallKind.MODEL && count.incrementAndGet() > 16)
            throw new CallRejectedException(CallRejectedException.Reason.CALL_BUDGET_EXHAUSTED);
    }

    /**
     * 本地固定解释仅验证生产HTTP、Worker和裁判接线；不得当真实模型成绩。
     *
     * @param c 实际领取的合成消息
     * @return 有真实原文引用的固定业务协议
     */
    private DialogueIntent.Result localReply(
            com.lawrence.travel.application.interaction.Dialogue.Claim c) {
        if (documentsBatch()) {
            var document = sourceReads.list(c.budgetId(), c.token()).getFirst();
            var fragment = sourceReads.section(c.budgetId(), c.token(), document.id(), 1, 0, 4000);
            return new DialogueIntent.Result(
                    List.of(
                            new DialogueIntent.Intent(
                                    DialogueIntent.Kind.CONVERSATION,
                                    DialogueIntent.Action.EXPLAIN_PLAN,
                                    c.text())),
                    List.of(),
                    "本地替身只解释实际读取的用户材料，不创建旅行：" + fragment.text(),
                    "",
                    null,
                    "",
                    "",
                    "");
        }
        if (c.text().startsWith("开始规划北京旅行")) {
            var facts =
                    new ArrayList<>(
                            List.of(
                                    new DialogueIntent.Fact("cities", "北京", "北京"),
                                    new DialogueIntent.Fact(
                                            "startDate", "2026-11-01", "2026年11月1日至3日"),
                                    new DialogueIntent.Fact(
                                            "endDate", "2026-11-03", "2026年11月1日至3日"),
                                    new DialogueIntent.Fact("travelers", "2", "2人")));
            if (c.text().contains("每天午休"))
                facts.add(new DialogueIntent.Fact("pace", "每天午休", "每天午休"));
            return new DialogueIntent.Result(
                    List.of(
                            new DialogueIntent.Intent(
                                    DialogueIntent.Kind.PLANNING,
                                    DialogueIntent.Action.START_PLAN,
                                    c.text())),
                    List.copyOf(facts),
                    "本地替身已理解北京、日期与2人。",
                    "",
                    null,
                    "",
                    "",
                    "");
        }
        if (c.text().startsWith("同一趟旅行改到"))
            return new DialogueIntent.Result(
                    List.of(
                            new DialogueIntent.Intent(
                                    DialogueIntent.Kind.PLANNING,
                                    DialogueIntent.Action.SUPPLEMENT,
                                    c.text())),
                    List.of(
                            new DialogueIntent.Fact("startDate", "2026-12-01", "2026年12月1日至3日"),
                            new DialogueIntent.Fact("endDate", "2026-12-03", "2026年12月1日至3日")),
                    "本地替身将起止日期一起改到12月。",
                    "",
                    null,
                    "",
                    "",
                    "");
        if (c.text().startsWith("节奏偏好整体改为"))
            return new DialogueIntent.Result(
                    List.of(
                            new DialogueIntent.Intent(
                                    DialogueIntent.Kind.PLANNING,
                                    DialogueIntent.Action.SUPPLEMENT,
                                    c.text())),
                    List.of(
                            new DialogueIntent.Fact(
                                    "pace",
                                    "紧凑游览",
                                    c.text(),
                                    com.lawrence.travel.domain.interaction.RequirementFields
                                            .PreferenceOperation.REPLACE)),
                    "本地替身替换全部节奏偏好。",
                    "",
                    null,
                    "",
                    "",
                    "");
        if (c.text().startsWith("刚才说的城市不变") || c.text().startsWith("再补充一个节奏偏好"))
            return new DialogueIntent.Result(
                    List.of(
                            new DialogueIntent.Intent(
                                    DialogueIntent.Kind.PLANNING,
                                    DialogueIntent.Action.SUPPLEMENT,
                                    c.text())),
                    List.of(new DialogueIntent.Fact("pace", "最后一天希望早点结束", "最后一天希望早点结束")),
                    "本地替身已理解最后一天早点结束。",
                    "",
                    null,
                    "",
                    "",
                    "");
        return new DialogueIntent.Result(
                List.of(
                        new DialogueIntent.Intent(
                                DialogueIntent.Kind.CONVERSATION,
                                DialogueIntent.Action.TRAVEL_QA,
                                c.text())),
                List.of(),
                "本地替身只做旅行咨询，不建立旅行。",
                "",
                null,
                "",
                "",
                "");
    }

    /**
     * 即使断言中止也保存真实账本总量和部分结果；原始认证值不进入报告，语义仍待人工。
     * @throws Exception 最小结构化报告无法持久化
     */
    @AfterEach
    void recordsPartialEvidence() throws Exception {
        if (parserServer != null) {
            parserServer.stop(0);
            parserServer = null;
        }
        var reviews = new ArrayList<Object>();
        for (var c : captures)
            reviews.add(
                    Map.of(
                            "user",
                            c.userId(),
                            "chat",
                            c.chatId(),
                            "state",
                            c.state(),
                            "metrics",
                            c.metrics(),
                            "outcome",
                            c.outcome(),
                            "turns",
                            c.turns(),
                            "spans",
                            jdbc.queryForList(
                                    "SELECT id::text AS id,parent_id::text AS parent,kind,state,(extract(epoch FROM started_at)*1000)::bigint AS started,(extract(epoch FROM ended_at)*1000)::bigint AS ended FROM execution_trace_span WHERE user_id=? ORDER BY started_at,id LIMIT 101",
                                    c.userId()),
                            "messages",
                            jdbc.queryForList(
                                    "SELECT text,reply,state FROM dialogue_input WHERE user_id=? AND chat_id=? ORDER BY sequence",
                                    c.userId(),
                                    c.chatId())));
        var meters =
                jdbc.queryForMap(
                        "SELECT count(*) FILTER(WHERE kind='MODEL') AS requests,coalesce(sum(input_tokens),0) AS input,coalesce(sum(output_tokens),0) AS output,count(*) FILTER(WHERE kind='MODEL' AND status<>'COMPLETED') AS unknown FROM call_meter");
        String body =
                json.encode(
                        Map.of(
                                "version",
                                "dev35-http-v1",
                                "mode",
                                real() ? "REAL_MODEL" : "LOCAL_CONTRACT",
                                "coverage",
                                requirementsBatch()
                                        ? "HTTP_WORKER_PG_R01_SUPPLEMENT_NOT_FROZEN_SUITE"
                                        : documentReferencesBatch()
                                                ? "HTTP_WORKER_PG_R02_R03_STRUCTURED_DOCUMENTS_NOT_BROWSER_OR_FROZEN_SUITE"
                                                : documentsBatch()
                                                        ? "HTTP_WORKER_PG_P07_P19_DOCUMENTS_NOT_FULL_SUITE"
                                                        : "HTTP_WORKER_PG_P01_P02_NOT_BROWSER_OR_FULL_SUITE",
                                "requestLimit",
                                16,
                                "suiteHash",
                                suiteHash,
                                "rows",
                                rows,
                                "meterTotals",
                                meters,
                                "manualReview",
                                "PENDING",
                                "reviews",
                                reviews,
                                "modelId",
                                real()
                                        ? properties.models().get("coordinator")
                                        : "LOCAL_INTERPRETER_STUB"));
        safety.require("EVALUATION_REPORT", body);
        Path report = Path.of(environment.getRequiredProperty("TRAVEL_DEV35_HTTP_REPORT"));
        Files.createDirectories(report.getParent());
        Files.writeString(report, body);
    }
}
