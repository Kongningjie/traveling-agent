package com.lawrence.travel.bootstrap;

import com.lawrence.travel.agent.runtime.GuardedDashScopeTransport;
import com.lawrence.travel.agent.runtime.RestrictedHarnessFactory;
import com.lawrence.travel.application.execution.ModelCallAdmission;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.bootstrap.config.ExecutionControlProperties;
import com.lawrence.travel.domain.execution.control.CallRejectedException;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.EndpointType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

/** DEV-0035分批验收：文本3、Harness18、P01/P02应用各8请求上限；手动推进合成身份，不冒充完整质量成绩。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_DEV35_MODEL_PROBE", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(properties = {"travel.engine.enabled=false", "travel.external-calls-enabled=true",
        "travel.tools.enabled=false", "travel.limits.model-calls-per-run=6", "travel.jobs.model-limit=4", "travel.documents.cleanup-enabled=false", "travel.lifecycle.maintenance-enabled=false", "logging.level.root=OFF", "logging.level.io.agentscope=OFF"})
class Dev35ModelProbeTest {
    @Autowired private ModelCallAdmission admission;
    @Autowired private BudgetLedger ledger;
    @Autowired private ExecutionControlProperties properties;
    @Autowired private Environment environment;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.lawrence.travel.application.interaction.DialogueService dialogue;
    @Autowired private com.lawrence.travel.application.port.AccountStore accounts;
    @Autowired private com.lawrence.travel.application.port.TravelStore travel;
    @Autowired private com.lawrence.travel.application.port.ExecutionEngineStore engine;
    @Autowired private com.lawrence.travel.application.interaction.NegotiationHandler negotiationHandler;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.lawrence.travel.infrastructure.interaction.DialogueMapper dialogueMapper;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.lawrence.travel.application.port.InteractionCodec interactionCodec;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.lawrence.travel.application.port.AgentRuntimePort runtime;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.lawrence.travel.application.port.InputInterpreter inputInterpreter;
    @TempDir private Path workspace;
    private UUID conversationUser;
    private UUID conversationChat;

    /** 异常中止仍保存实际计量及失败状态；不导出原异常正文、不补造成功或零消耗。
     * @throws Exception 结构化失败证据无法落盘 */
    @org.junit.jupiter.api.AfterEach void preservesAbortedConversationEvidence() throws Exception {
        if (conversationUser == null) return;
        Path destination = Path.of(environment.getRequiredProperty("TRAVEL_DEV35_REPORT"));
        if (Files.exists(destination)) return;
        var report = new LinkedHashMap<String, Object>();
        report.put("version", "dev35-conversation-aborted-v1"); report.put("programStatus", "ABORTED_HARNESS");
        report.put("coverage", "ABORTED_NOT_QUALITY_PASS"); report.put("humanReview", "NOT_READY");
        report.put("caseId", environment.getProperty("TRAVEL_DEV35_CASE", "P01"));
        report.put("user", conversationUser); report.put("chat", conversationChat);
        try {
            var capture = new com.lawrence.travel.infrastructure.evaluation.JdbcSessionEvaluationEvidence(jdbc, transactions,
                    java.util.Set.of(properties.models().get("coordinator"))).read(conversationUser, conversationChat, 0);
            report.put("metrics", capture.metrics());
        } catch (RuntimeException unavailable) { report.put("meterEvidence", "READ_FAILED_NOT_ZERO"); }
        Files.createDirectories(destination.getParent()); Files.writeString(destination, new TravelJson().encode(report));
    }

    /** 只读复核已保存的合成P02需求，区分模型输出问题与验收读取时机问题。
     * @throws Exception 本地结构化证据读写失败；不发起模型或工具调用 */
    @Test void inspectsPersistedRequirementsWithoutVendorRequests() throws Exception {
        var json = new TravelJson();
        var prior = json.decode(Files.readString(Path.of(environment.getRequiredProperty("TRAVEL_DEV35_INSPECT_REPORT"))), Map.class);
        assertThat(prior.get("caseId")).isEqualTo("P02");
        UUID user = UUID.fromString((String) prior.get("user"));
        UUID task = jdbc.queryForObject("SELECT id FROM travel_task WHERE user_id=?", UUID.class, user);
        long revision = jdbc.queryForObject("SELECT constraint_revision FROM travel_task WHERE user_id=? AND id=?", Long.class, user, task);
        var values = new LinkedHashMap<String, String>();
        travel.revision(new com.lawrence.travel.domain.travel.TaskScope(user, task), revision).requirements().forEach(r -> values.put(r.field(), r.value()));
        var expected = Map.of("cities", "北京", "startDate", "2026-11-01", "endDate", "2026-11-03", "travelers", "2");
        var checks = new LinkedHashMap<String, Boolean>(); var lengths = new LinkedHashMap<String, Integer>();
        expected.forEach((key, value) -> { checks.put(key, value.equals(values.get(key))); lengths.put(key, values.getOrDefault(key, "").length()); });
        Path destination = Path.of(environment.getRequiredProperty("TRAVEL_DEV35_REPORT")); Files.createDirectories(destination.getParent());
        Files.writeString(destination, json.encode(Map.of("coverage", "LOCAL_EXISTING_P02_ONLY", "revision", revision,
                "checks", checks, "valueLengths", lengths, "rows", List.of())));
        System.out.println("DEV35_INSPECT checks=" + json.encode(checks) + " lengths=" + json.encode(lengths));
    }

    /** 封存P01/P02的实际收件、真实协调模型及PG提交；仅收窄调度范围，不替换模型/业务结果。
     * @throws Exception 最小报告写入失败；异常原文不导出 */
    @Test void verifiesConversationCaseWithOwnedState() throws Exception {
        String caseId = environment.getProperty("TRAVEL_DEV35_CASE", "P01");
        assertThat(caseId).isIn("P01", "P02");
        // 已解释消息应直接合并；若应用意外要求再次理解，验证失败且不发出额外真实请求。
        org.mockito.Mockito.doThrow(new IllegalStateException("UNEXPECTED_SECOND_INTERPRETATION"))
                .when(inputInterpreter).interpret(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        var suite = new com.lawrence.travel.infrastructure.evaluation.PhaseTwoEvaluationSuite(PhaseTwoEvaluationTest::resource).load(false);
        var input = suite.cases().stream().filter(c -> c.id().equals(caseId)).findFirst().orElseThrow();
        var json = new TravelJson();
        var diagnostics = new java.util.concurrent.ConcurrentHashMap<String, Object>();
        org.mockito.Mockito.doAnswer(invocation -> {
            String text = invocation.getArgument(0);
            try {
                Object root = json.decode(text, Object.class);
                if (root instanceof Map<?, ?> object) {
                    var shape = new java.util.TreeMap<String, String>();
                    for (String key : List.of("intents", "facts", "reply", "clarification", "targetId", "category", "value", "city", "resolvedInputs", "advice")) {
                        Object value = object.get(key); shape.put(key, value == null ? "NULL_OR_MISSING" : value instanceof List<?> ? "ARRAY" : value instanceof String ? "STRING" : "OTHER");
                    }
                    diagnostics.put("outputShape", shape);
                } else diagnostics.put("outputShape", "NOT_OBJECT");
                return invocation.callRealMethod();
            } catch (RuntimeException failure) { diagnostics.put("decodeFailureType", failure.getClass().getSimpleName()); throw failure; }
        }).when(interactionCodec).decode(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(com.lawrence.travel.domain.interaction.DialogueIntent.Result.class));
        org.mockito.Mockito.doAnswer(invocation -> {
            var call = (com.lawrence.travel.application.port.AgentRuntimePort.Invocation) invocation.callRealMethod();
            call.result().whenComplete((value, failure) -> {
                if (failure != null) diagnostics.put("runtimeFailureType", failure.getClass().getSimpleName());
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof com.lawrence.travel.agent.runtime.SanitizedAgentFailure safe) diagnostics.put("runtimeExceptionTypes", safe.exceptionTypes());
                    if (cause instanceof com.lawrence.travel.domain.execution.control.ContextBudgetDiagnostic budget)
                        diagnostics.put("contextBudget", Map.of("boundary", budget.boundary(), "estimated", budget.estimated(), "limit", budget.limit()));
                }
            });
            return call;
        }).when(runtime).startContextual(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        var turns = json.decode(input.facts().get("script"), com.lawrence.travel.infrastructure.evaluation.HttpSessionEvaluationDriver.Turn[].class);
        assertThat(turns).hasSize(2);
        UUID user = UUID.randomUUID(), chat = UUID.randomUUID();
        String name = "live_" + user.toString().replace("-", "").substring(0, 20);
        assertThat(accounts.insert(user, name, "__NON_LOGIN_TEST_HASH__")).isTrue();
        conversationUser = user; conversationChat = chat;
        var actor = new com.lawrence.travel.domain.identity.UserIdentity(user, name);
        // 仅将Worker发现候选限制为本次合成身份；领取、租约、预算、模型、提交均走真实实现。
        org.mockito.Mockito.doReturn(List.of(new com.lawrence.travel.infrastructure.interaction.DialogueMapper.Hint(user, chat)))
                .when(dialogueMapper).hints();
        long started = System.nanoTime(); int completed = 0; boolean valid = true; UUID firstTask = null; long firstRevision = 0;
        var checks = new LinkedHashMap<String, Boolean>();
        for (var turn : turns) {
            diagnostics.clear();
            assertThat(turn.action()).isEqualTo("MESSAGE");
            UUID message = UUID.randomUUID(); dialogue.send(actor, chat, message, turn.text());
            var work = dialogue.claimWork().orElseThrow();
            assertThat(((com.lawrence.travel.application.port.BackgroundWorkSource.LeasedWork) work).userId()).isEqualTo(user);
            work.run();
            var view = dialogue.read(actor, chat); var reply = view.messages().getLast();
            boolean done = "DONE".equals(reply.state()) && !reply.reply().isBlank();
            long taskCount = jdbc.queryForObject("SELECT count(*) FROM travel_task WHERE user_id=?", Long.class, user);
            boolean taskValid;
            if (caseId.equals("P01")) taskValid = view.chat().taskId() == null && taskCount == 0;
            else {
                UUID task = view.chat().taskId(); taskValid = task != null && taskCount == 1 && (firstTask == null || firstTask.equals(task));
                if (taskValid) {
                    // 聊天DONE仅表示解释已提交；需求字段由异步UNDERSTAND原子合并，不能提前读取初始空快照。
                    checks.put("turn" + (completed + 1) + "UnderstandCommitted", advanceOwnedUnderstand(user, task));
                    long revision = jdbc.queryForObject("SELECT constraint_revision FROM travel_task WHERE user_id=? AND id=?", Long.class, user, task);
                    var fields = new LinkedHashMap<String, String>();
                    travel.revision(new com.lawrence.travel.domain.travel.TaskScope(user, task), revision).requirements()
                            .forEach(requirement -> fields.put(requirement.field(), requirement.value()));
                    boolean preserved = "北京".equals(fields.get("cities")) && "2026-11-01".equals(fields.get("startDate"))
                            && "2026-11-03".equals(fields.get("endDate")) && "2".equals(fields.get("travelers"));
                    checks.put("turn" + (completed + 1) + "City", "北京".equals(fields.get("cities")));
                    checks.put("turn" + (completed + 1) + "Start", "2026-11-01".equals(fields.get("startDate")));
                    checks.put("turn" + (completed + 1) + "End", "2026-11-03".equals(fields.get("endDate")));
                    checks.put("turn" + (completed + 1) + "Travelers", "2".equals(fields.get("travelers")));
                    checks.put("turn" + (completed + 1) + "RequirementsPreserved", preserved);
                    if (firstTask != null) checks.put("followupCreatesNewRevision", revision > firstRevision);
                    else { firstTask = task; firstRevision = revision; }
                    taskValid &= preserved;
                }
            }
            checks.put("turn" + (completed + 1) + "Done", done); checks.put("turn" + (completed + 1) + "TaskOwnership", taskValid);
            valid &= done && taskValid; if (!valid) break; completed++;
        }
        var capture = new com.lawrence.travel.infrastructure.evaluation.JdbcSessionEvaluationEvidence(jdbc, transactions,
                java.util.Set.of(properties.models().get("coordinator"))).read(user, chat, completed);
        checks.put("twoPersistedMessages", dialogue.read(actor, chat).messages().size() == 2);
        checks.put("knownRealUsage", capture.metrics().get("actualModelRequests") > 0
                && capture.metrics().get("unknownModelRequests") == 0 && capture.metrics().get("unrecognizedModelRequests") == 0);
        valid &= checks.values().stream().allMatch(Boolean::booleanValue) && completed == 2;
        org.mockito.Mockito.verifyNoInteractions(inputInterpreter);
        var report = new LinkedHashMap<String, Object>(); report.put("version", "dev35-conversation-application-v2");
        report.put("coverage", "REAL_APPLICATION_AND_MODEL_NOT_HTTP_OR_HUMAN_REVIEW");
        report.put("caseId", caseId); report.put("suiteHash", suite.fingerprint()); report.put("user", user); report.put("chat", chat);
        report.put("programStatus", valid ? "PASS" : "FAIL"); report.put("humanReview", "PENDING");
        report.put("checks", checks); report.put("metrics", capture.metrics()); report.put("messageStates", capture.state().get("messageStates"));
        report.put("requestLimit", 8); report.put("diagnostics", Map.copyOf(diagnostics)); report.put("wallMillis", (System.nanoTime() - started) / 1000000);
        Path destination = Path.of(environment.getRequiredProperty("TRAVEL_DEV35_REPORT")); Files.createDirectories(destination.getParent());
        Files.writeString(destination, json.encode(report));
        System.out.println("DEV35_CONVERSATION case=" + caseId + " programStatus=" + report.get("programStatus") + " humanReview=PENDING requests=" + capture.metrics().get("actualModelRequests"));
    }

    /**
     * 只推进本例UNDERSTAND；共享库中已有进程可能先完成该阶段，必须接受已提交事实，不能重复执行。
     * @param user 合成用户
     * @param task 当前私有旅行
     * @return 五秒内该阶段是否已提交；不将收件完成或运行领取等同于合并完成
     * @throws InterruptedException 本机等待被取消
     */
    private boolean advanceOwnedUnderstand(UUID user, UUID task) throws InterruptedException {
        UUID run = jdbc.queryForObject("SELECT current_run_id FROM travel_task WHERE user_id=? AND id=?", UUID.class, user, task);
        for (int attempt = 0; attempt < 50; attempt++) {
            if (jdbc.queryForObject("SELECT count(*) FROM execution_step WHERE user_id=? AND task_id=? AND run_id=? AND type='UNDERSTAND' AND state='COMPLETED'", Long.class, user, task, run) > 0) return true;
            var hint = engine.candidates(100).stream().filter(h -> h.scope().userId().equals(user)
                    && h.scope().taskId().equals(task) && h.runId().equals(run)
                    && h.type() == com.lawrence.travel.domain.execution.StageType.UNDERSTAND).findFirst();
            if (hint.isPresent()) {
                var claim = engine.claim(hint.get(), "dev35-owned-understand");
                if (claim.isPresent()) {
                    var output = negotiationHandler.execute(claim.get());
                    return engine.commit(engine.prepare(claim.get(), output)) == com.lawrence.travel.application.port.StageCommitPort.Result.COMMITTED;
                }
            }
            Thread.sleep(100);
        }
        return false;
    }

    /** 每型号最多六请求验证真实Harness工具和结构化输出；失败停止批次，不调用真实旅行工具。
     * @throws Exception 仅最小结构化报告写入失败 */
    @Test void verifiesHarnessToolsAndStructuredOutput() throws Exception {
        var rows = new ArrayList<Map<String, Object>>(); boolean stop = false;
        for (String role : List.of("coordinator", "research", "reviewer")) {
            var row = new LinkedHashMap<String, Object>(); row.put("role", role); row.put("model", properties.models().get(role));
            if (stop) { row.put("status", "NOT_RUN"); row.put("reason", "PRIOR_FAILURE_OR_UNKNOWN"); rows.add(row); continue; }
            UUID run = UUID.randomUUID(); ledger.create(run); row.put("run", run);
            long started = System.nanoTime(); var probe = new ProbeTool(); var toolkit = new Toolkit(); toolkit.registerTool(probe);
            try (var transport = new GuardedDashScopeTransport(admission, run, role, new OkHttpClient())) {
                var model = DashScopeChatModel.builder().apiKey(environment.getRequiredProperty("DASHSCOPE_API_KEY"))
                        .modelName(properties.models().get(role)).baseUrl("https://dashscope.aliyuncs.com")
                        .endpointType(EndpointType.MULTIMODAL).stream(false).enableThinking(false).enableSearch(false)
                        .httpTransport(transport).build();
                try (var harness = RestrictedHarnessFactory.create(model, toolkit, workspace.resolve(role), role)) {
                    var reply = harness.call(List.of(Msg.builder().name("user").role(MsgRole.USER)
                            .content(TextBlock.builder().text("先调用 lookup_validation_value 读取本次随机数字，"
                                    + "再以指定结构返回 answer，answer只包含工具返回的数字，不要猜测。").build()).build()),
                            RestrictedHarnessFactory.StructuredReply.class).block(Duration.ofMinutes(4));
                    boolean valid = reply != null && probe.calls > 0
                            && probe.value.equals(reply.getStructuredData(RestrictedHarnessFactory.StructuredReply.class).answer());
                    row.put("status", valid ? "PASS" : "FAIL");
                    row.put("reason", valid ? "HARNESS_TOOL_AND_SCHEMA_OK" : "HARNESS_TOOL_OR_SCHEMA_MISMATCH");
                }
            } catch (RuntimeException failure) { row.put("status", "FAIL"); row.put("reason", reason(failure)); }
            long calls = ledger.read(run).modelCalls();
            long unknown = jdbc.queryForObject("SELECT count(*) FROM call_meter WHERE run_id=? AND status<>'COMPLETED'", Long.class, run);
            row.put("requests", calls); row.put("unknownRequests", unknown); row.put("localToolCalls", probe.calls);
            if (unknown == 0) {
                row.put("inputTokens", jdbc.queryForObject("SELECT COALESCE(sum(input_tokens),0) FROM call_meter WHERE run_id=?", Long.class, run));
                row.put("outputTokens", jdbc.queryForObject("SELECT COALESCE(sum(output_tokens),0) FROM call_meter WHERE run_id=?", Long.class, run));
            } else { row.put("status", "NOT_RUN"); row.put("reason", "REAL_USAGE_UNKNOWN"); }
            if ("PASS".equals(row.get("status")) && (calls < 2 || calls > 6)) { row.put("status", "FAIL"); row.put("reason", "HARNESS_REQUEST_COUNT_INVALID"); }
            row.put("wallMillis", (System.nanoTime() - started) / 1000000); rows.add(row);
            stop = !"PASS".equals(row.get("status"));
            System.out.println("DEV35_HARNESS role=" + role + " status=" + row.get("status") + " reason=" + row.get("reason") + " requests=" + calls);
        }
        Path destination = Path.of(environment.getRequiredProperty("TRAVEL_DEV35_REPORT")); Files.createDirectories(destination.getParent());
        Files.writeString(destination, new TravelJson().encode(Map.of("version", "dev35-harness-v1", "at", Instant.now().toString(),
                "coverage", "REAL_HARNESS_PROTOCOL_NOT_TASK_QUALITY", "requestLimit", 18, "rows", rows)));
    }

    /** 只返回本次新生成的非敏感随机数字，用于证明模型确实读取了工具结果。 */
    public static final class ProbeTool {
        private final String value = String.valueOf(java.util.concurrent.ThreadLocalRandom.current().nextInt(100000, 999999));
        private int calls;
        /** @return 本次随机数字，无网络或业务写入副作用 */
        @Tool(name = "lookup_validation_value", description = "读取本次随机校验数字，回答前必须调用。")
        public synchronized String lookup() { calls++; return value; }
    }

    /** 只读取现有快照及PG计量，确认拒绝分类；不创建预算、不调用供应商、不刷新核实时间。
     * @throws Exception 本地结构化诊断报告无法写入 */
    @Test void recordsLocalBlockersWithoutVendorRequests() throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        for (String role : List.of("coordinator", "research", "reviewer")) {
            String model = properties.models().get(role);
            var row = new LinkedHashMap<String, Object>();
            row.put("role", role); row.put("model", model); row.put("status", "LOCAL_DIAGNOSTIC");
            var quota = properties.quotaEvidence().get(model);
            String reason = "QUOTA_SNAPSHOT_MISSING";
            if (quota != null) {
                try {
                    quota.requireUsable(model, "cn-beijing", "DASHSCOPE_NATIVE_REALTIME", 128, Instant.now());
                    boolean uncertain = ledger.hasUncertainUsage(new BudgetLedger.Target(model,
                            "cn-beijing", "DASHSCOPE_NATIVE_REALTIME"), quota.verifiedAt(), Instant.now());
                    var target = new BudgetLedger.Target(model, "cn-beijing", "DASHSCOPE_NATIVE_REALTIME");
                    if (ledger.hasUsageOverrun(target, quota.verifiedAt())) reason = "KNOWN_USAGE_OVERRUN";
                    else if (ledger.tokensSince(target, quota.verifiedAt()) > quota.remainingTokens() - 128)
                        reason = "GATE_QUOTA_INSUFFICIENT";
                    else reason = uncertain ? "LOCAL_QUOTA_CHECK_OK_WITH_UNKNOWN_RESERVATIONS" : "LOCAL_QUOTA_CHECK_OK";
                } catch (CallRejectedException rejected) { reason = "GATE_" + rejected.reason().name(); }
            }
            row.put("reason", reason); row.put("requests", 0); rows.add(row);
            System.out.println("DEV35_DIAGNOSTIC role=" + role + " reason=" + reason + " requests=0");
        }
        Path destination = Path.of(environment.getRequiredProperty("TRAVEL_DEV35_REPORT"));
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, new TravelJson().encode(Map.of("version", "dev35-local-diagnostic-v1",
                "at", Instant.now().toString(), "coverage", "LOCAL_ADMISSION_ONLY", "rows", rows)));
    }

    /** 每角色最多一次SDK文本流；既有UNKNOWN或免费门禁拒绝不删除、不重新核实。报告保留所有真实计量。
     * @throws Exception 仅本地结构化报告无法写入；不输出供应商原文或认证数据 */
    @Test void probesThreeExactModelsWithNoAutomaticRetry() throws Exception {
        assertThat(properties.models()).containsEntry("coordinator", "qwen3.8-27b")
                .containsEntry("research", "qwen3.7-flash-2026-07-15").containsEntry("reviewer", "qwen3.8-max-0902");
        var rows = new ArrayList<Map<String, Object>>(); boolean stop = false;
        for (String role : List.of("coordinator", "research", "reviewer")) {
            var row = new LinkedHashMap<String, Object>(); row.put("role", role); row.put("model", properties.models().get(role));
            if (stop) { row.put("status", "NOT_RUN"); row.put("reason", "PRIOR_FAILURE_OR_UNKNOWN"); rows.add(row); continue; }
            UUID run = UUID.randomUUID(); ledger.create(run); row.put("run", run);
            long start = System.nanoTime();
            try (var transport = new GuardedDashScopeTransport(admission, run, role, new OkHttpClient())) {
                var model = DashScopeChatModel.builder().apiKey(environment.getRequiredProperty("DASHSCOPE_API_KEY"))
                        .modelName(properties.models().get(role)).baseUrl("https://dashscope.aliyuncs.com")
                        .endpointType(EndpointType.MULTIMODAL).stream(true).enableThinking(false).enableSearch(false)
                        .httpTransport(transport).build();
                var options = GenerateOptions.builder().maxTokens(32)
                        .executionConfig(ExecutionConfig.builder().maxAttempts(1).timeout(Duration.ofSeconds(60)).build()).build();
                var responses = model.stream(List.of(Msg.builder().name("user").role(MsgRole.USER)
                        .content(TextBlock.builder().text("请只回复 OK。").build()).build()), List.of(), options)
                        .collectList().block(Duration.ofSeconds(70));
                String answer = responses == null ? "" : responses.stream().flatMap(r -> r.getContent().stream())
                        .filter(TextBlock.class::isInstance).map(TextBlock.class::cast).map(TextBlock::getText)
                        .collect(java.util.stream.Collectors.joining());
                row.put("status", answer.contains("OK") ? "PASS" : "FAIL");
                row.put("reason", answer.contains("OK") ? "TEXT_STREAM_OK" : "EXPECTED_TEXT_MISSING");
                if (!answer.contains("OK")) stop = true;
            } catch (RuntimeException failure) {
                row.put("status", "NOT_RUN"); row.put("reason", reason(failure));
            }
            long calls = ledger.read(run).modelCalls();
            long unknown = jdbc.queryForObject("SELECT count(*) FROM call_meter WHERE run_id=? AND status<>'COMPLETED'", Long.class, run);
            row.put("requests", calls); row.put("unknownRequests", unknown);
            if (calls != 1 && "PASS".equals(row.get("status"))) {
                row.put("status", "FAIL"); row.put("reason", "REQUEST_METER_MISMATCH"); stop = true;
            }
            // 没有实际请求时零Token是精确的；请求已发送但未完成时只记录UNKNOWN，不补零消耗。
            if (unknown == 0) {
                row.put("inputTokens", jdbc.queryForObject("SELECT COALESCE(sum(input_tokens),0) FROM call_meter WHERE run_id=?", Long.class, run));
                row.put("outputTokens", jdbc.queryForObject("SELECT COALESCE(sum(output_tokens),0) FROM call_meter WHERE run_id=?", Long.class, run));
            } else { row.put("status", "NOT_RUN"); row.put("reason", "REAL_USAGE_UNKNOWN"); }
            row.put("wallMillis", (System.nanoTime() - start) / 1000000);
            assertThat(calls).isBetween(0L, 1L);
            if (unknown > 0 || (calls > 0 && !"PASS".equals(row.get("status")))) stop = true;
            rows.add(row);
            System.out.println("DEV35_PROBE role=" + role + " status=" + row.get("status") + " reason=" + row.get("reason") + " requests=" + calls);
        }
        Path destination = Path.of(environment.getRequiredProperty("TRAVEL_DEV35_REPORT"));
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, new TravelJson().encode(Map.of("version", "dev35-model-probe-v1", "at", Instant.now().toString(),
                "coverage", "REAL_MODEL_REACHABILITY_NOT_TASK_QUALITY", "requestLimit", 3, "rows", rows)));
        System.out.println("DEV35_PROBE_RECORDED; report status is separate from JUnit protocol success");
    }

    /** @param failure 已归一化的适配器异常链 @return 固定门禁分类；绝不拼接供应商异常文本 */
    private static String reason(Throwable failure) {
        Throwable value = failure;
        for (int i = 0; i < 12 && value != null; i++, value = value.getCause()) {
            if (value instanceof CallRejectedException rejected) return "GATE_" + rejected.reason().name();
        }
        return "MODEL_PROTOCOL_OR_PROVIDER_FAILURE";
    }
}
