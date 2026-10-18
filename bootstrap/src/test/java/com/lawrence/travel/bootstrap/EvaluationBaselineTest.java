package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.evaluation.Evaluation;
import com.lawrence.travel.application.evaluation.EvaluationRunner;
import com.lawrence.travel.application.interaction.Negotiation;
import com.lawrence.travel.application.interaction.NegotiationHandler;
import com.lawrence.travel.application.interaction.NegotiationService;
import com.lawrence.travel.application.memory.AgentContext;
import com.lawrence.travel.application.memory.ContextAssembler;
import com.lawrence.travel.application.memory.PreferenceService;
import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.ContextStore;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.InputInterpreter;
import com.lawrence.travel.application.port.TravelStore;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.interaction.DecisionAnswer;
import com.lawrence.travel.domain.interaction.DecisionRequest;
import com.lawrence.travel.domain.itinerary.ImpactAnalyzer;
import com.lawrence.travel.domain.itinerary.ItineraryPatch;
import com.lawrence.travel.domain.itinerary.ItineraryValidator;
import com.lawrence.travel.domain.itinerary.PatchService;
import com.lawrence.travel.domain.itinerary.TripSnapshot;
import com.lawrence.travel.domain.knowledge.EvidenceRecord;
import com.lawrence.travel.domain.memory.ConversationDigest;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.domain.travel.TripWindow;
import com.lawrence.travel.infrastructure.evaluation.FixedEvaluationSuite;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 固定场景的真实PG／应用与领域契约基线；不是完整模型旅行轨迹，也不测自然语言提取质量。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@EnabledIfEnvironmentVariable(named = "TRAVEL_EVALUATION", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(properties = {"travel.engine.enabled=false", "travel.external-calls-enabled=false"})
class EvaluationBaselineTest implements Evaluation.Executor {
    @Autowired private AccountStore accounts;
    @Autowired private NegotiationService negotiation;
    @Autowired private NegotiationHandler understand;
    @Autowired private ExecutionEngineStore engine;
    @Autowired private TravelStore travel;
    @Autowired private PreferenceService preferences;
    @Autowired private ContextAssembler contexts;
    @Autowired private ContextStore contextStore;
    @Autowired private BusinessTransactions transactions;
    @Autowired private TravelJson json;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean private InputInterpreter interpreter;
    private final List<UUID> users = new ArrayList<>();
    /** @param actor 私有身份 @param scope 任务 @param run 原运行 @param revision 已确认需求 */
    private record Fixture(UserIdentity actor, TaskScope scope, UUID run, ConstraintRevision revision) { }
    /** 只清理本轮随机用户及其事务产物，不删除源业务数据或日志目录。 */
    @AfterEach void clean() { transactions.required(() -> {
        for (UUID user : users) {
            // 只处理本次随机身份：先断开任务指针，再按外键依赖清理；不扫描或删除其他用户。
            var runs = jdbc.queryForList("SELECT id FROM execution_run WHERE user_id=?", UUID.class, user); jdbc.update("UPDATE travel_task SET current_run_id=NULL,current_version_id=NULL WHERE user_id=?", user);
            jdbc.update("DELETE FROM artifact_content WHERE artifact_id IN(SELECT id FROM artifact_record WHERE user_id=?)", user);
            for (String table : List.of("artifact_record", "negotiation_card", "conversation_message", "decision_response", "decision_request", "run_event", "execution_start_request", "step_attempt", "outbox_event", "execution_step", "execution_run")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
            for (UUID run : runs) { jdbc.update("DELETE FROM call_meter WHERE run_id=?", run); jdbc.update("DELETE FROM execution_budget WHERE run_id=?", run); }
            for (String table : List.of("preference_request", "user_preference", "change_request", "travel_constraint_revision", "travel_task", "travel_idempotency")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
            jdbc.update("DELETE FROM app_user WHERE id=?", user);
        } return true;
    }); }
    /** 核对固定资源后执行选择分区，输出简短可审计结果；真实层次逐行未运行。 */
    @Test void fixedContractBaseline() throws Exception {
        boolean holdout = Boolean.parseBoolean(System.getenv("TRAVEL_EVAL_HOLDOUT"));
        var suite = new FixedEvaluationSuite(EvaluationBaselineTest::resource).load(holdout);
        var configuration = Map.of("coverage", "DOMAIN_AND_APPLICATION_CONTRACTS_NOT_FULL_E2E", "gitCommit", System.getenv().getOrDefault("TRAVEL_EVAL_COMMIT", "UNKNOWN"), "sourceHash", System.getenv().getOrDefault("TRAVEL_EVAL_SOURCE_HASH", "UNKNOWN"), "java", System.getProperty("java.version"), "stack", "Java21_Boot4.1.1_AgentScope2.0.4", "plannedModels", "qwen3.8-27b;qwen3.7-flash-2026-07-15;qwen3.8-max-0902", "actualModels", "NONE", "promptConfiguration", "UNUSED_LOCAL_CONTRACT", "partitions", holdout ? "DEVELOPMENT,HOLDOUT" : "DEVELOPMENT");
        var report = new EvaluationRunner(this).run(suite, Boolean.parseBoolean(System.getenv("TRAVEL_EVAL_REPEAT")), configuration);
        Path destination = Path.of(System.getenv("TRAVEL_EVAL_DIRECTORY")).toAbsolutePath().normalize(); Files.createDirectory(destination);
        Files.writeString(destination.resolve("report.json"), json.encode(report), StandardCharsets.UTF_8);
        long pass = report.rows().stream().filter(r -> r.mode() == Evaluation.Mode.LOCAL_CONTRACT && r.status().equals("PASS")).count(); long fail = report.rows().stream().filter(r -> r.status().equals("FAIL")).count();
        var text = new StringBuilder("# 固定场景本地契约基线\n\n仅测应用／领域契约；没有执行完整模型行程，不是模型质量或浏览器E2E成绩。所有地点、价格和来源均为合成数据。\n\n数据指纹：" + report.suiteHash() + "\n\n本地通过：" + pass + "，失败：" + fail + "。真实模型／工具均未运行，不计算成功率、Token或真实服务延迟。\n\n| 场景 | 分区 | 次数 | 层次 | 结果 | 失败或未运行原因 |\n| --- | --- | --- | --- | --- | --- |\n");
        report.rows().forEach(r -> text.append("| ").append(r.caseId()).append(" | ").append(r.partition()).append(" | ").append(r.repetition()).append(" | ").append(r.mode()).append(" | ").append(r.status()).append(" | ").append(r.reason() == null ? String.join(",", r.failedChecks()) : r.reason()).append(" |\n"));
        text.append("\nE01未执行真实候选接受全链路；E03检查多城结构与房晚，并非六日行程质量；E06检查受限对象Patch，并非与全量重规划的质量对照；E11使用本地摘录器。真实模型12例、稳定后4例×3次、独立质量评分及人工抽查、真实工具验收和浏览器交付验收仍待完成。历史故障／完整流程回归单列，不混入这12例质量成绩。\n");
        Files.writeString(destination.resolve("report.md"), text, StandardCharsets.UTF_8);
        System.out.println("EVALUATION local_contract_pass=" + pass + " fail=" + fail + " real_model=NOT_RUN real_tool=NOT_RUN");
        assertThat(fail).as("本地契约失败，查看report.json中的固定检查码").isZero();
    }
    /** @param name 已知固定文件名 @return 测试类路径字节，不读取密钥配置 */
    static byte[] resource(String name) { try (var input = EvaluationBaselineTest.class.getResourceAsStream("/evaluation/v1/" + name)) { if (input == null) throw new IllegalArgumentException("FIXTURE_MISSING"); return input.readAllBytes(); } catch (java.io.IOException ignored) { throw new IllegalArgumentException("FIXTURE_UNAVAILABLE"); } }
    /** {@inheritDoc} 本实现始终只提供本地契约，不允许登记为真实模型。 */
    @Override public Evaluation.Mode mode() { return Evaluation.Mode.LOCAL_CONTRACT; }
    /** @param input 固定结构化输入及原文 @return 真实UNDERSTAND提交后的私有状态；本地提取器不对原文做模型解释 */
    private Fixture fixture(Evaluation.Case input) {
        UUID user = UUID.randomUUID(); String name = "eval_" + user.toString().replace("-", "").substring(0, 16); accounts.insert(user, name, "__NON_LOGIN_TEST_HASH__"); users.add(user); var actor = new UserIdentity(user, name);
        var date = LocalDate.parse(input.facts().get("startDate"));
        var facts = new java.util.LinkedHashMap<>(Map.of("cities", input.facts().get("cities"), "startDate", date.toString(), "endDate", date.plusDays(Integer.parseInt(input.facts().get("days")) - 1).toString(), "travelers", input.facts().get("travelers"), "budgetRule", "PER_GROUP", "budgetCny", input.facts().get("budgetCny"), "budgetScope", "ACTIVITIES", "fixedStatus", "NONE"));
        if (Set.of("PREFERENCE", "COMPACTION").contains(input.scenario())) facts.put("hardRequirements", "每天休息，未经允许不能解除锁定或扩大授权");
        if (input.scenario().equals("COMPACTION")) { facts.put("fixedStatus", "PROVIDED"); facts.put("fixedArrangements", "RESERVATION|2026-11-01T14:00:00+08:00[Asia/Shanghai]|2026-11-01T15:00:00+08:00[Asia/Shanghai]"); }
        when(interpreter.interpret(any(), any())).thenReturn(new Negotiation.Interpretation(List.of(), false, ""));
        var created = negotiation.create(actor, UUID.randomUUID(), input.id(), input.input(), facts.entrySet().stream().map(e -> new Negotiation.Fact(e.getKey(), e.getValue(), null)).toList());
        var scope = new TaskScope(user, created.taskId()); var hint = engine.candidates(100).stream().filter(h -> h.runId().equals(created.receipt().runId())).findFirst().orElseThrow(); var claim = engine.claim(hint, "fixed-evaluation").orElseThrow();
        engine.commit(engine.prepare(claim, understand.execute(claim)));
        return new Fixture(actor, scope, created.receipt().runId(), travel.revision(scope, travel.task(scope).task().constraintRevision()));
    }
    /** {@inheritDoc} 固定输入驱动真实规则／应用服务，程序检查不接收Oracle，失败交给外部Runner报告。 */
    @Override public Evaluation.Observation execute(Evaluation.Case input) {
        long started = System.nanoTime(); var f = fixture(input); var trip = snapshot(input, f); var checks = new java.util.LinkedHashMap<String, Boolean>(); String outcome;
        switch (input.scenario()) {
            case "INITIAL" -> { outcome = "CANDIDATE_REQUIRES_CONFIRMATION"; checks.put("STRUCTURE_VALID", validate(trip, revision(f, trip, null, List.of())).stream().noneMatch(i -> i.blocking())); checks.put("POINTER_UNCHANGED", travel.task(f.scope()).task().currentVersionId() == null); }
            case "PARTIAL" -> { outcome = "CLARIFICATION_PARTIAL"; UUID q1 = id(input, "q1"), q2 = id(input, "q2"); var card = new DecisionRequest(id(input, "card"), f.scope(), f.run(), engine.run(f.scope(), f.run()).generation(), 1, f.revision().revision(), Map.of(q1, new DecisionRequest.Question(q1, "预算口径", Map.of(), null), q2, new DecisionRequest.Question(q2, "预算范围", Map.of(), null)), true, Set.of(), DecisionRequest.State.OPEN); var answer = new DecisionAnswer(UUID.randomUUID(), card.id(), 1, card.constraintRevision(), DecisionAnswer.Kind.ANSWER, Map.of(), input.input(), Set.of(), DecisionAnswer.State.INTERPRETED); var remaining = card.applyConfirmed(answer, travel.task(f.scope()).task(), Set.of(q1), f.revision().revision()); checks.put("QUESTION_REMAINS", remaining.remaining().containsKey(q2) && !remaining.remaining().containsKey(q1)); checks.put("FREE_TEXT_ACCEPTED", remaining.revision() == 2); }
            case "MULTICITY" -> { outcome = "MULTICITY_STRUCTURE"; checks.put("CITY_DAY_COVERAGE", trip.cityStays().size() == 3 && trip.days().size() == 6); checks.put("LODGING_PRESENT", trip.accommodationStays().size() == 3); checks.put("TRANSFERS_PRESENT", trip.transportLegs().size() == 2); }
            case "BUDGET" -> { outcome = "BUDGET_CONFLICT"; var issues = validate(trip, revision(f, trip, input.facts().get("changedBudgetCny"), List.of())); checks.put("BUDGET_RECALCULATED", trip.costEntries().stream().map(c -> c.upper().multiply(c.quantity()).multiply(BigDecimal.valueOf(trip.window().travelers()))).reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(new BigDecimal(input.facts().get("changedBudgetCny"))) > 0); checks.put("BUDGET_BLOCKED", issues.stream().anyMatch(i -> i.code().equals("HARD_BUDGET_EXCEEDED"))); }
            case "LOW_INTENSITY", "UNSOLVABLE" -> { outcome = input.scenario().equals("LOW_INTENSITY") ? "UNKNOWN_BLOCKED" : "DRAFT_BLOCKED"; var issues = validate(trip, revision(f, trip, null, List.of(requirement("hardRequirements", "内部步行上限3000米，原始数据未知")))); if (input.scenario().equals("LOW_INTENSITY")) { checks.put("UNKNOWN_NOT_PASS", issues.stream().anyMatch(i -> i.blocking() && i.unknown())); checks.put("FIXED_PRESERVED", fixedCheck(input, f, trip, false)); } else { checks.put("BLOCKING_ISSUES_VISIBLE", issues.stream().anyMatch(i -> i.blocking())); checks.put("POINTER_UNCHANGED", travel.task(f.scope()).task().currentVersionId() == null); } }
            case "RAIN_PATCH" -> { outcome = "PATCH_SCOPED"; var original = revision(f, trip, null, List.of()); var changed = revision(f, trip, null, List.of(requirement("changeTargets", trip.items().getFirst().id().toString()), requirement("pace", "雨天室内休息"))); var scope = new ImpactAnalyzer().analyze(trip, original, changed, List.of()); var old = trip.items().getFirst(); var item = new TripSnapshot.Item(old.id(), old.cityStayId(), old.type(), input.facts().get("changedTitle"), old.locationKey(), old.startsAt(), old.endsAt(), old.fixedArrangementId()); var operation = new ItineraryPatch.Operation(ItineraryPatch.Kind.ITEM, ItineraryPatch.Action.REPLACE, item.id(), null, null, item, null, null, null, null, List.of()); var patch = new ItineraryPatch(json.hash(json.encode(trip)), List.of(operation), null); var merged = new PatchService().apply(trip, id(input, "base"), patch.expectedBaseHash(), f.revision().revision(), scope, patch); checks.put("PATCH_WITHIN_SCOPE", scope.allowedIds().contains(item.id()) && merged.items().getFirst().title().equals(item.title())); checks.put("UNAFFECTED_PRESERVED", merged.items().getLast().equals(trip.items().getLast())); }
            case "EXPIRED" -> { outcome = "EVIDENCE_EXPIRED"; var now = Instant.parse("2026-10-18T00:00:00Z"); long life = Long.parseLong(input.seed().get("lifetimeSeconds")); var ref = new EvidenceRecord.Reference(id(input, "evidence"), 1, json.hash(input.seed().get("price"))); var expired = new EvidenceRecord(ref, "price", java.net.URI.create(input.seed().get("url")), now.minusSeconds(life + 1), now.minusSeconds(1), EvidenceRecord.Status.KNOWN); var unknown = new EvidenceRecord(ref, "price", expired.source(), now, now.plusSeconds(life), EvidenceRecord.Status.UNKNOWN); checks.put("EXPIRED_REJECTED", !expired.knownAndFreshAt(now)); checks.put("FAILURE_NOT_FACT", !unknown.knownAndFreshAt(now)); }
            case "PREFERENCE" -> { outcome = "PREFERENCE_DELETED"; UUID p = id(input, "preference"); preferences.change(f.actor(), UUID.randomUUID(), 0, p, "SAVE", "pace", input.facts().get("preferenceInitial"), ""); preferences.change(f.actor(), UUID.randomUUID(), 1, p, "UPDATE", "pace", input.facts().get("preferenceUpdated"), ""); preferences.change(f.actor(), UUID.randomUUID(), 2, p, "DELETE", null, null, null); checks.put("DELETE_PERSISTED", preferences.read(f.actor()).entries().isEmpty()); checks.put("CURRENT_REQUIREMENT_REMAINS", travel.revision(f.scope(), f.revision().revision()).equals(f.revision())); }
            case "TRANSFER_CONFLICT" -> { outcome = "FIXED_CONFLICT"; boolean conflict = !fixedCheck(input, f, trip, true); checks.put("FIXED_CONFLICT_REPORTED", conflict); checks.put("NO_SILENT_RELAXATION", trip.items().getFirst().startsAt().getHour() == 11); }
            case "SUPERSEDE" -> { outcome = "OLD_BINDING_REJECTED"; var hint = engine.candidates(100).stream().filter(h -> h.runId().equals(f.run())).findFirst().orElseThrow(); var claim = engine.claim(hint, "old-eval").orElseThrow(); UUID q = id(input, "old-question"); var oldCard = new DecisionRequest(id(input, "old-card"), f.scope(), f.run(), engine.run(f.scope(), f.run()).generation(), 1, f.revision().revision(), Map.of(q, new DecisionRequest.Question(q, "旧问题", Map.of(), null)), true, Set.of(), DecisionRequest.State.OPEN); var oldAnswer = new DecisionAnswer(UUID.randomUUID(), oldCard.id(), 1, f.revision().revision(), DecisionAnswer.Kind.ANSWER, Map.of(), "旧回答", Set.of(), DecisionAnswer.State.INTERPRETED); negotiation.send(f.actor(), f.scope().taskId(), UUID.randomUUID(), input.facts().get("newRequirement"), List.of()); var result = engine.commit(engine.prepare(claim, new com.lawrence.travel.application.execution.StageOutput("旧本地输出", "{}", List.of(), com.lawrence.travel.application.execution.StageOutput.Outcome.BRANCH_COMPLETE, null))); checks.put("OLD_COMMIT_REJECTED", result == com.lawrence.travel.application.port.StageCommitPort.Result.STALE); checks.put("OLD_ANSWER_REJECTED", rejected(() -> oldCard.applyConfirmed(oldAnswer, travel.task(f.scope()).task(), Set.of(q), f.revision().revision()))); }
            case "COMPACTION" -> { outcome = "CONTEXT_PROTECTED"; compact(input, f, checks); }
            default -> throw new IllegalArgumentException("SCENARIO_NOT_IMPLEMENTED");
        }
        return new Evaluation.Observation(outcome, Map.copyOf(checks), Map.of("localElapsedMillis", (System.nanoTime() - started) / 1000000, "actualModelRequests", 0L, "actualToolRequests", 0L), List.of("DOMAIN_APPLICATION_CONTRACT_ONLY"));
    }
    /** @param input 固定输入 @param f 权威归属 @return 合成结构草案，只供契约；不冒充真实模型产出或景区事实 */
    private TripSnapshot snapshot(Evaluation.Case input, Fixture f) {
        String[] names = input.facts().get("cities").split(","); int days = Integer.parseInt(input.facts().get("days")); var date = LocalDate.parse(input.facts().get("startDate")); var start = date.atStartOfDay(ZoneId.of("Asia/Shanghai")); var cities = new ArrayList<TripSnapshot.CityStay>(); var calendar = new ArrayList<TripSnapshot.Day>(); var items = new ArrayList<TripSnapshot.Item>(); var lodging = new ArrayList<TripSnapshot.Accommodation>(); var legs = new ArrayList<TripSnapshot.Transport>(); var costs = new ArrayList<TripSnapshot.Cost>();
        for (int i = 0; i < names.length; i++) { int from = days * i / names.length, to = days * (i + 1) / names.length; UUID city = id(input, "city" + i); cities.add(new TripSnapshot.CityStay(city, names[i], start.plusDays(from), start.plusDays(to))); if (names.length > 1) lodging.add(new TripSnapshot.Accommodation(id(input, "hotel" + i), city, start.plusDays(from).plusHours(18), start.plusDays(to).minusHours(14), 1));
            for (int day = from; day < to; day++) { calendar.add(new TripSnapshot.Day(id(input, "day" + day), date.plusDays(day))); var item = new TripSnapshot.Item(id(input, "item" + day), city, TripSnapshot.ItemType.REST, "合成休息位", "same-location", start.plusDays(day).plusHours(11), start.plusDays(day).plusHours(12), null); items.add(item); BigDecimal value = new BigDecimal(input.seed().get("price")); costs.add(new TripSnapshot.Cost(id(input, "cost" + day), item.id(), "ACTIVITIES", TripSnapshot.CostUnit.PER_PERSON, BigDecimal.ONE, value, value, false, true, "CNY")); }
        }
        for (int i = 1; i < names.length; i++) { int boundary = days * i / names.length; var from = items.get(boundary - 1); var to = items.get(boundary); legs.add(new TripSnapshot.Transport(id(input, "leg" + i), from.id(), to.id(), start.plusDays(boundary).plusHours(8), start.plusDays(boundary).plusHours(9), Long.parseLong(input.seed().get("routeMinutes")), false)); }
        return new TripSnapshot(1, f.scope().taskId(), f.revision().revision(), null, new TripWindow(Set.of(names), date, date.plusDays(days - 1), Integer.parseInt(input.facts().get("travelers"))), cities, calendar, items, legs, lodging, costs, List.of(), Set.of(), List.of());
    }
    /** @param f 权威归属 @param trip 合成草案 @param budget 可空预算覆盖 @param additions 明确的测试约束 @return 用于规则探针的确认版本，绝不覆写PG需求 */
    private ConstraintRevision revision(Fixture f, TripSnapshot trip, String budget, List<ConstraintRevision.Requirement> additions) { var values = new ArrayList<>(f.revision().requirements().stream().filter(r -> budget == null || !r.field().equals("budgetCny")).toList()); if (budget != null) values.add(requirement("budgetCny", budget)); for (var cost : trip.costEntries()) values.add(requirement("costCny:" + cost.id(), ItineraryValidator.costConfirmation(cost))); values.addAll(additions); return new ConstraintRevision(f.scope(), f.revision().revision(), values, List.of()); }
    /** @param field 已确认字段 @param value 合成用户事实 @return 独立明确需求，不提升种子为官方事实 */
    private ConstraintRevision.Requirement requirement(String field, String value) { return new ConstraintRevision.Requirement(UUID.nameUUIDFromBytes(field.getBytes(StandardCharsets.UTF_8)), field, value, ConstraintRevision.Kind.HARD, ConstraintRevision.Source.USER, Set.of()); }
    /** @param trip 草案 @param revision 要求 @return 程序问题，不读取模型自评 */
    private List<com.lawrence.travel.domain.itinerary.ItineraryReport.Issue> validate(TripSnapshot trip, ConstraintRevision revision) { return new ItineraryValidator().validate(trip, revision); }
    /** @param input 固定时刻 @param f 归属 @param trip 草案 @param shifted 是否模拟未授权改时 @return 固定预约是否保持精确时间；这里只探测固定时间保护，不代表交通规划质量 */
    private boolean fixedCheck(Evaluation.Case input, Fixture f, TripSnapshot trip, boolean shifted) { var original = trip.items().getFirst(); var starts = original.startsAt().withHour(Integer.parseInt(input.facts().get("fixedHour"))); var fixed = new ConstraintRevision.FixedArrangement(id(input, "fixed"), "RESERVATION", starts, starts.plusHours(1), UUID.randomUUID()); var item = new TripSnapshot.Item(original.id(), original.cityStayId(), original.type(), original.title(), original.locationKey(), shifted ? starts.withHour(Integer.parseInt(input.facts().get("changedHour"))) : starts, shifted ? starts.withHour(Integer.parseInt(input.facts().get("changedHour"))).plusHours(1) : starts.plusHours(1), fixed.id()); var items = new ArrayList<>(trip.items()); items.set(0, item); var revised = new TripSnapshot(1, trip.taskId(), trip.constraintRevision(), null, trip.window(), trip.cityStays(), trip.days(), items, trip.transportLegs(), trip.accommodationStays(), trip.costEntries(), trip.alternatives(), trip.evidenceRefs()); return validate(revised, new ConstraintRevision(f.scope(), f.revision().revision(), revision(f, trip, null, List.of()).requirements(), List.of(fixed))).stream().noneMatch(i -> i.code().equals("FIXED_TIME_CHANGED") || i.code().equals("FIXED_ARRANGEMENT_UNMAPPED")); }
    /** @param action 预期被权威规则拒绝的旧请求 @return 是否固定业务拒绝，其他异常不吞掉 */
    private boolean rejected(Runnable action) { try { action.run(); return false; } catch (com.lawrence.travel.domain.rule.DomainViolation expected) { return expected.getMessage().equals("STALE_DECISION"); } }
    /** @param input 固定场景 @param suffix 对象类型和序号 @return 稳定业务对象ID，任务／用户本轮随机 */
    private static UUID id(Evaluation.Case input, String suffix) { return UUID.nameUUIDFromBytes((input.id() + ":" + suffix).getBytes(StandardCharsets.UTF_8)); }
    /** @param input 固定长历史参数 @param f 权威归属 @param checks 写入程序检查；本地摘录不称为真实模型压缩质量 */
    private void compact(Evaluation.Case input, Fixture f, Map<String, Boolean> checks) {
        UUID preference = id(input, "deleted-preference"); preferences.change(f.actor(), UUID.randomUUID(), 0, preference, "SAVE", "pace", "DELETED_PREFERENCE_SENTINEL", "");
        transactions.required(() -> { long watermark = jdbc.queryForObject("SELECT message_sequence FROM travel_task WHERE user_id=? AND id=? FOR UPDATE", Long.class, f.actor().id(), f.scope().taskId()); int count = Integer.parseInt(input.facts().get("historyMessages")); for (int i = 1; i <= count; i++) { UUID id = UUID.randomUUID(); String text = "合成历史" + i + "local-history-".repeat(60); var message = new Negotiation.Message(id, "CHANGE", text, List.of(), null, 0, f.revision().revision(), "APPLIED"); jdbc.update("INSERT INTO conversation_message(id,user_id,task_id,sequence,request_hash,body,status,preference_revision) VALUES(?,?,?,?,?,CAST(? AS jsonb),'APPLIED',1)", id, f.actor().id(), f.scope().taskId(), watermark + i, json.hash(json.encode(message)), json.encode(message)); } jdbc.update("UPDATE travel_task SET message_sequence=? WHERE user_id=? AND id=?", watermark + count, f.actor().id(), f.scope().taskId()); return true; });
        var source = contextStore.load(f.run(), "planner"); var prepared = contexts.prepare(f.run(), "planner", new AgentContext.Basis(f.revision().revision(), 1L), input.input(), new AgentContext.Compression() {
            /** {@inheritDoc} 使用真实Harness适配器的保守计数，无外部调用。 */ @Override public long estimate(String text) { return com.lawrence.travel.agent.runtime.HarnessContextCompression.estimateInput(text); }
            /** {@inheritDoc} 只摘录真实来源连续子串。 */ @Override public ConversationDigest compact(List<AgentContext.Message> messages) { var first = messages.getFirst(); return new ConversationDigest(List.of(new ConversationDigest.Excerpt(first.id(), first.text().substring(0, 80)))); }
        }); checks.put("SUMMARY_PERSISTED", prepared.summaryId() != null && contextStore.cached(source) != null);
        // 删除偏好提升权威版本；旧摘要和旧历史不能重新注入，已确认本次要求独立保留。
        preferences.change(f.actor(), UUID.randomUUID(), 1, preference, "DELETE", null, null, null);
        var fresh = contextStore.load(f.run(), "planner"); checks.put("PROTECTED_FIELDS_REMAIN", prepared.prompt().contains(source.authority()) && fresh.authority().contains("RESERVATION") && fresh.authority().contains(input.facts().get("budgetCny"))); checks.put("DELETED_PREFERENCE_ABSENT", !fresh.authority().contains("DELETED_PREFERENCE_SENTINEL") && fresh.history().isEmpty());
    }
}
