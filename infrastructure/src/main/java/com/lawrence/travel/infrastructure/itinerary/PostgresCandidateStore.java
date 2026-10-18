package com.lawrence.travel.infrastructure.itinerary;

import com.lawrence.travel.application.execution.StageOutput;
import com.lawrence.travel.application.itinerary.CandidateAcceptanceCommand;
import com.lawrence.travel.application.port.CandidateStore;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.domain.execution.ExecutionFence;
import com.lawrence.travel.domain.execution.ExecutionRun;
import com.lawrence.travel.domain.execution.ExecutionStep;
import com.lawrence.travel.domain.execution.StageType;
import com.lawrence.travel.domain.execution.control.RunBudget;
import com.lawrence.travel.domain.itinerary.ItineraryReport;
import com.lawrence.travel.domain.itinerary.ItineraryValidator;
import com.lawrence.travel.domain.itinerary.ItineraryVersion;
import com.lawrence.travel.domain.itinerary.ReviewGate;
import com.lawrence.travel.domain.itinerary.TripSnapshot;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.infrastructure.execution.persistence.EngineMapper;
import com.lawrence.travel.infrastructure.persistence.control.BudgetMapper;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import com.lawrence.travel.infrastructure.travel.TravelMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.IDEMPOTENCY_CONFLICT;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.INVALID_INPUT;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.NOT_FOUND;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.STALE_VERSION;

/**
 * 候选、精确报告和接受事实的 PostgreSQL 投影；不调用模型，也不拥有另一套调度。
 * 必须参加应用或引擎事务；用户→任务→运行→阶段→事件的统一锁序保护偏好及执行状态。
 */
public final class PostgresCandidateStore implements CandidateStore {
    private final CandidateMapper mapper;
    private final TravelMapper tasks;
    private final EngineMapper engine;
    private final BudgetMapper budgets;
    private final TravelJson json;
    private final String rules;
    private final String reviewConfiguration;
    private final Duration validity;
    private final com.lawrence.travel.application.port.ResearchStore evidence;
    private com.lawrence.travel.application.port.PreferenceStore preferences;
    private com.lawrence.travel.application.port.BusinessTransactions transactions;
    private final ItineraryValidator validator = new ItineraryValidator();
    private final ReviewGate gate = new ReviewGate();

    /**
     * @param mapper 候选 SQL
     * @param tasks 任务 SQL
     * @param engine 执行 SQL
     * @param budgets 权威预算
     * @param json JSON
     * @param rules 确定性规则版本
     * @param reviewConfiguration 审核模型及提示词指纹
     * @param validity 报告有效正时长
     */
    public PostgresCandidateStore(CandidateMapper mapper, TravelMapper tasks, EngineMapper engine, BudgetMapper budgets,
                                  TravelJson json, String rules, String reviewConfiguration, Duration validity) {
        this(mapper, tasks, engine, budgets, json, rules, reviewConfiguration, validity, null);
    }
    /** @param mapper 候选SQL @param tasks 任务SQL @param engine 执行SQL @param budgets 原预算 @param json JSON @param rules 规则指纹 @param reviewConfiguration 审核指纹 @param validity 报告上限 @param evidence 字段证据端口 */
    public PostgresCandidateStore(CandidateMapper mapper, TravelMapper tasks, EngineMapper engine, BudgetMapper budgets, TravelJson json, String rules, String reviewConfiguration, Duration validity, com.lawrence.travel.application.port.ResearchStore evidence) {
        require(rules != null && !rules.isBlank() && reviewConfiguration != null && !reviewConfiguration.isBlank()
                && validity != null && validity.toMillis() > 0, INVALID_INPUT);
        this.mapper = mapper; this.tasks = tasks; this.engine = engine; this.budgets = budgets; this.json = json;
        this.rules = rules; this.reviewConfiguration = reviewConfiguration; this.validity = validity;
        this.evidence = evidence;
    }
    /** @param mapper SQL @param tasks 任务 @param engine 执行 @param budgets 预算 @param json JSON @param rules 规则 @param reviewConfiguration 审核指纹 @param validity 有效时长 @param evidence 证据 @param preferences 偏好权威端口 @param transactions 读取快照短事务 */
    public PostgresCandidateStore(CandidateMapper mapper, TravelMapper tasks, EngineMapper engine, BudgetMapper budgets, TravelJson json, String rules, String reviewConfiguration, Duration validity, com.lawrence.travel.application.port.ResearchStore evidence, com.lawrence.travel.application.port.PreferenceStore preferences, com.lawrence.travel.application.port.BusinessTransactions transactions) {
        this(mapper, tasks, engine, budgets, json, rules, reviewConfiguration, validity, evidence); this.preferences = preferences; this.transactions = transactions;
    }
    /** {@inheritDoc} 修订必须与领取一致；不信任客户端指定的生效指针和证据。 */
    @Override public com.lawrence.travel.application.itinerary.Planning.Context context(ExecutionFence fence) {
        var task = tasks.task(fence.scope()); require(task != null, NOT_FOUND);
        require(task.constraintRevision() == fence.constraintRevision() && task.executionGeneration() == fence.generation() && fence.runId().toString().equals(engine.currentRun(fence.scope())), STALE_VERSION);
        var revision = json.decode(tasks.revision(fence.scope(), fence.constraintRevision()), ConstraintRevision.class);
        var selected = preferences == null ? null : transactions.required(() -> {
            var stored = preferences.read(fence.scope().userId());
            var cities = revision.requirements().stream().filter(r -> r.field().equals("cities")).flatMap(r -> java.util.Arrays.stream(r.value().split("[,，]"))).map(String::trim).collect(java.util.stream.Collectors.toSet());
            var overridden = revision.requirements().stream().filter(r -> r.source() != ConstraintRevision.Source.ASSUMPTION).map(ConstraintRevision.Requirement::field).collect(java.util.stream.Collectors.toSet());
            return com.lawrence.travel.domain.memory.Preference.recall(stored, cities, overridden);
        });
        return new com.lawrence.travel.application.itinerary.Planning.Context(fence.scope(), revision, task.currentVersionId(), evidence == null ? List.of() : evidence.evidence(fence.scope()), mapper.now(), null, selected);
    }
    /** {@inheritDoc} 三项都从同一私有任务读取；历史需求与快照不可变。 */
    @Override public com.lawrence.travel.application.itinerary.Planning.Base base(TaskScope scope, UUID id) {
        var metadata = version(scope, id);
        return new com.lawrence.travel.application.itinerary.Planning.Base(json.decode(tasks.snapshot(scope, id).payload(), TripSnapshot.class), metadata.payloadHash(), json.decode(tasks.revision(scope, metadata.constraintRevision()), ConstraintRevision.class));
    }
    /** {@inheritDoc} 读取草案不授予接受资格；历史不覆盖当前。 */
    @Override public com.lawrence.travel.application.itinerary.Planning.Candidate read(TaskScope scope, UUID id) {
        var candidate = candidate(scope, id); var version = version(scope, candidate.snapshotId());
        return new com.lawrence.travel.application.itinerary.Planning.Candidate(id, version, json.decode(tasks.snapshot(scope, version.snapshotId()).payload(), TripSnapshot.class), report(scope, candidate.validationId()), report(scope, candidate.reviewId()), candidate.state());
    }
    /** {@inheritDoc} 展示草案和错误不授予接受资格，旧运行草案不替代当前。 */
    @Override public com.lawrence.travel.application.itinerary.Planning.View latest(TaskScope scope) {
        if (mapper.dialogueBlocked(scope)) return null;
        var task = tasks.task(scope); require(task != null, NOT_FOUND); String latest = mapper.latest(scope); if (latest == null) return null; UUID id = UUID.fromString(latest);
        var candidate = candidate(scope, id); var result = read(scope, id); var row = engine.run(scope, candidate.runId());
        com.lawrence.travel.domain.itinerary.CostSummary costs;
        try { costs = com.lawrence.travel.domain.itinerary.CostSummary.calculate(result.snapshot()); } catch (IllegalArgumentException | NullPointerException invalid) { costs = null; }
        boolean current = row != null && json.decode(row.body(), ExecutionRun.class).state() == ExecutionRun.State.WAITING_ACCEPTANCE
                && candidate.state().equals("PRESENTED") && task.constraintRevision() == result.version().constraintRevision() && task.executionGeneration() == candidate.generation()
                && json.pendingInputs(task.pendingInputs()).isEmpty() && eligibility(scope, candidate, result.version()) == ReviewGate.Result.ELIGIBLE;
        return new com.lawrence.travel.application.itinerary.Planning.View(result, costs, current);
    }
    /** {@inheritDoc} 与提交同一确定性函数；过期或失效事实不会进入已知资料。 */
    @Override public List<ItineraryReport.Issue> validate(ExecutionFence fence, UUID id) { context(fence); var candidate = candidate(fence.scope(), id); require(candidate.runId().equals(fence.runId()), STALE_VERSION); return validate(fence.scope(), version(fence.scope(), candidate.snapshotId())); }
    /**
     * {@inheritDoc} 无业务操作的历史检查点无需锁用户；不得把任意检查点当作候选。
     */
    @Override public void beforeTaskLock(ExecutionFence fence, StageOutput output) {
        transaction();
        if (operation(output) != null) require(mapper.lockUser(fence.scope()) != null, NOT_FOUND);
    }
    /**
     * {@inheritDoc} 只在完成事件已取得资格的同一事务投影；任何失败回滚阶段及报告。
     */
    @Override public Result project(ExecutionFence fence, StageType type, StageOutput output) {
        transaction(); String operation = operation(output);
        if (operation == null) return null;
        var body = object(output.checkpoint()); UUID id = uuid(body.get("candidateId"));
        Long currentPreference = mapper.preferenceRevision(fence.scope()); require(currentPreference != null, NOT_FOUND);
        if (body.containsKey("preferenceRevision")) require(body.get("preferenceRevision") instanceof Number && body.get("preferenceRevision").toString().matches("[0-9]+"), INVALID_INPUT);
        if (body.containsKey("preferenceRevision") && ((Number) body.get("preferenceRevision")).longValue() != currentPreference
                || !body.containsKey("preferenceRevision") && currentPreference != null && currentPreference > 0) throw new com.lawrence.travel.application.memory.PreferenceChangedException();
        var task = tasks.task(fence.scope()); require(task != null && json.pendingInputs(task.pendingInputs()).isEmpty(), STALE_VERSION);
        require(fence.runId().toString().equals(engine.currentRun(fence.scope())) && task.executionGeneration() == fence.generation()
                && task.constraintRevision() == fence.constraintRevision(), STALE_VERSION);
        if (operation.equals("PREFERENCE_REPLAN")) {
            require(java.util.Set.of(StageType.VALIDATE, StageType.REVIEW, StageType.PRESENT).contains(type) && output.next().size() == 1 && output.next().getFirst().key().type() == StageType.PLAN, INVALID_INPUT);
            var source = candidate(fence.scope(), id);
            require(source.runId().equals(fence.runId()) && source.generation() == fence.generation(), STALE_VERSION);
            var next = json.decode(output.next().getFirst().input(), com.lawrence.travel.application.itinerary.Planning.Input.class);
            require(id.equals(next.candidateId()) && next.repair() && output.next().getFirst().key().round() > 0, INVALID_INPUT);
            var old = version(fence.scope(), source.snapshotId());
            require(!preferenceCurrent(fence.scope(), old), STALE_VERSION);
            return null;
        }
        if (operation.equals("REPLAN_CONTROL")) {
            require(type == StageType.PLAN && task.currentVersionId() != null && (output.outcome() == StageOutput.Outcome.CONTINUE || output.outcome() == StageOutput.Outcome.WAIT_USER), INVALID_INPUT);
            var definition = json.decode(engine.step(fence.scope(), fence.runId(), fence.stepId()).definition(), com.lawrence.travel.application.execution.StagePlan.class);
            require(definition.mode() == com.lawrence.travel.domain.execution.PlanningMode.REPLAN, INVALID_INPUT);
            var oldHistory = history(object(definition.input())); var newHistory = history(body);
            if (output.outcome() == StageOutput.Outcome.CONTINUE) {
                require(output.next().size() == 1, INVALID_INPUT); var next = output.next().getFirst();
                require(next.key().type() == StageType.PLAN && next.key().round() > 0 && next.mode() == com.lawrence.travel.domain.execution.PlanningMode.REPLAN
                        && history(object(next.input())).equals(newHistory), INVALID_INPUT);
            }
            require(newHistory.size() >= oldHistory.size() && newHistory.size() <= oldHistory.size() + 1 && newHistory.subList(0, oldHistory.size()).equals(oldHistory), INVALID_INPUT);
            var original = base(fence.scope(), task.currentVersionId());
            new com.lawrence.travel.domain.itinerary.ImpactAnalyzer().analyze(original.snapshot(), original.revision(), context(fence).revision(), newHistory);
            if (output.decision() != null) {
                checked(engine.insertDecision(fence.scope(), fence.runId(), output.decision().id(), json.encode(output.decision())));
                checked(mapper.insertDecisionSpecs(fence.scope(), output.decision().id(), output.decision().revision(), json.encode(body.get("decisionSpecs"))));
            }
            return null;
        }
        if (operation.equals("SAVE_CANDIDATE")) {
            require(type == StageType.PLAN && output.outcome() == StageOutput.Outcome.CONTINUE, INVALID_INPUT);
            var declared = json.decode(engine.step(fence.scope(), fence.runId(), fence.stepId()).definition(), com.lawrence.travel.application.execution.StagePlan.class);
            require(declared.mode() == (task.currentVersionId() == null ? com.lawrence.travel.domain.execution.PlanningMode.INITIAL : com.lawrence.travel.domain.execution.PlanningMode.REPLAN), INVALID_INPUT);
            UUID snapshotId = uuid(body.get("snapshotId"));
            require(task.currentVersionId() == null || body.containsKey("snapshot") && body.containsKey("patch") && body.containsKey("impactScope"), INVALID_INPUT);
            if (body.containsKey("snapshot")) {
                var trip = json.decode(json.encode(body.get("snapshot")), TripSnapshot.class); String payload = json.encode(trip);
                if (task.currentVersionId() != null) {
                    // C事务重读基础版本和已持久化范围，不能只信任事务外Handler的检查或模型自报范围。
                    require(body.containsKey("patch") && body.containsKey("impactScope"), INVALID_INPUT);
                    var definition = json.decode(engine.step(fence.scope(), fence.runId(), fence.stepId()).definition(), com.lawrence.travel.application.execution.StagePlan.class);
                    var original = base(fence.scope(), task.currentVersionId());
                    var scope = new com.lawrence.travel.domain.itinerary.ImpactAnalyzer().analyze(original.snapshot(), original.revision(), context(fence).revision(), history(object(definition.input())));
                    require(scope.equals(json.decode(json.encode(body.get("impactScope")), com.lawrence.travel.domain.itinerary.ImpactScope.class)), STALE_VERSION);
                    var patch = json.decode(json.encode(body.get("patch")), com.lawrence.travel.domain.itinerary.ItineraryPatch.class);
                    var merged = new com.lawrence.travel.domain.itinerary.PatchService().apply(original.snapshot(), task.currentVersionId(), original.hash(), task.constraintRevision(), scope, patch);
                    require(merged.equals(trip), INVALID_INPUT);
                } else require(!body.containsKey("patch"), INVALID_INPUT);
                var metadata = new ItineraryVersion(snapshotId, fence.scope(), trip.schemaVersion(), json.hash(payload), trip.constraintRevision(), trip.baseVersionId(), trip.evidenceRefs(), body.containsKey("preferenceRevision") ? new com.lawrence.travel.domain.memory.PreferenceVersion(fence.scope().userId(), ((Number) body.get("preferenceRevision")).longValue()) : null);
                require(trip.taskId().equals(fence.scope().taskId()) && trip.constraintRevision() == fence.constraintRevision(), STALE_VERSION);
                if (!trip.evidenceRefs().isEmpty()) { require(evidence != null, INVALID_INPUT); evidence.resolve(fence.scope(), trip.evidenceRefs()); }
                checked(tasks.insertSnapshot(metadata, json.encode(metadata), json.snapshot(metadata, payload)));
            }
            var version = version(fence.scope(), snapshotId);
            require(version.constraintRevision() == fence.constraintRevision() && Objects.equals(version.baseVersionId(), task.currentVersionId()), STALE_VERSION);
            checked(mapper.insertCandidate(fence.scope(), new CandidateMapper.CandidateRow(id, snapshotId, fence.runId(), fence.generation(),
                    fence.stepId(), fence.attemptId(), "NEW", null, null))); return null;
        }
        var candidate = candidate(fence.scope(), id);
        require(candidate.runId().equals(fence.runId()) && candidate.generation() == fence.generation()
                && !candidate.state().equals("ACCEPTED"), STALE_VERSION);
        var version = version(fence.scope(), candidate.snapshotId());
        require(Objects.equals(version.baseVersionId(), task.currentVersionId()) && version.constraintRevision() == task.constraintRevision(), STALE_VERSION);
        committed(fence.scope(), candidate, candidate.planStepId(), candidate.planAttemptId(), StageType.PLAN);
        var binding = binding(version);
        switch (operation) {
            case "VALIDATE_ITINERARY" -> {
                require(type == StageType.VALIDATE && output.outcome() != StageOutput.Outcome.PRESENT_CANDIDATE, INVALID_INPUT);
                var issues = validate(fence.scope(), version);
                if (body.containsKey("expectedIssues")) require(json.encode(issues).equals(json.encode(body.get("expectedIssues"))), STALE_VERSION);
                saveReport(fence, candidate, binding, ItineraryReport.Kind.VALIDATION, issues, null);
            }
            case "REVIEW_ITINERARY" -> {
                require(type == StageType.REVIEW && output.outcome() != StageOutput.Outcome.PRESENT_CANDIDATE, INVALID_INPUT);
                var validation = report(fence.scope(), candidate.validationId());
                require(validation != null && validation.binding().equals(binding) && validation.conclusion() == ItineraryReport.Conclusion.PASS
                        && validation.freshAt(mapper.now()) && !fence.stepId().equals(candidate.planStepId()), INVALID_INPUT);
                // 审核必须明确指出实际读过的程序报告与依赖，不能在提交时替它绑定“最新报告”。
                require(validation.id().equals(uuid(body.get("validationId")))
                        && binding.equals(json.decode(json.encode(body.get("binding")), ItineraryReport.Binding.class)), STALE_VERSION);
                committed(fence.scope(), candidate, validation.producerStepId(), validation.producerAttemptId(), StageType.VALIDATE);
                require(body.get("issues") instanceof List<?>, INVALID_INPUT);
                var issues = new ArrayList<ItineraryReport.Issue>();
                for (Object value : (List<?>) body.get("issues")) issues.add(json.decode(json.encode(value), ItineraryReport.Issue.class));
                saveReport(fence, candidate, binding, ItineraryReport.Kind.REVIEW, issues, validation.id());
            }
            case "PRESENT_ITINERARY" -> {
                require(type == StageType.PRESENT && output.outcome() == StageOutput.Outcome.PRESENT_CANDIDATE, INVALID_INPUT);
                require(eligibility(fence.scope(), candidate, version) == ReviewGate.Result.ELIGIBLE, INVALID_INPUT);
                checked(mapper.update(fence.scope(), id, "PRESENTED", candidate.validationId(), candidate.reviewId())); return new Result(id, null, null);
            }
            default -> throw new TravelStorageFailure(INVALID_INPUT);
        }
        if (output.decision() != null) {
            require(body.containsKey("decisionSpecs"), INVALID_INPUT);
            checked(engine.insertDecision(fence.scope(), fence.runId(), output.decision().id(), json.encode(output.decision())));
            checked(mapper.insertDecisionSpecs(fence.scope(), output.decision().id(), output.decision().revision(), json.encode(body.get("decisionSpecs"))));
        }
        return null;
    }
    /** @param body 已持久化阶段输入或控制信封 @return 有界扩大历史，缺省为空 */
    private List<String> history(Map<?, ?> body) {
        if (!body.containsKey("expansionHistory")) return List.of();
        require(body.get("expansionHistory") instanceof List<?>, INVALID_INPUT);
        return ((List<?>) body.get("expansionHistory")).stream().map(String.class::cast).toList();
    }
    /**
     * {@inheritDoc} 永久接受重放先于当前状态检查，避免旧重试恢复已经被后继版本替换的指针。
     */
    @Override public Attempt accept(CandidateAcceptanceCommand command) { return submit(command, false); }
    /** {@inheritDoc} 独立幂等语义，不能让只请求重校验的用户意图变成接受。 */
    @Override public Attempt recheck(CandidateAcceptanceCommand command) { return submit(command, true); }
    /** @param command 精确预期 @param forceRecheck 只重校验 @return 永久幂等结果；与排队参加同一事务 */
    private Attempt submit(CandidateAcceptanceCommand command, boolean forceRecheck) {
        transaction(); var scope = command.scope(); require(mapper.lockUser(scope) != null, NOT_FOUND);
        var task = tasks.lockTask(scope); require(task != null, NOT_FOUND);
        String hash = forceRecheck ? json.hash(json.encode(List.of("RECHECK", commandHash(command)))) : commandHash(command);
        var replay = mapper.request(scope, command.acceptanceRequestId());
        if (replay != null) return new Attempt(replay(replay, hash), false);
        require(!mapper.dialogueBlocked(scope), STALE_VERSION);
        var candidate = candidate(scope, command.candidateId());
        var accepted = mapper.accepted(scope, candidate.id());
        if (accepted != null && !forceRecheck) return receipt(command, hash, replay(accepted, hash), false);
        // 任务锁已串行化同任务写入；仍按引擎统一顺序锁住所有权威行，禁止无锁自判完成。
        var row = engine.lockRun(scope, candidate.runId()); require(row != null, NOT_FOUND);
        engine.lockSteps(scope, candidate.runId()); engine.lockEvents(scope, candidate.runId());
        var run = json.decode(row.body(), ExecutionRun.class);
        var version = version(scope, candidate.snapshotId());
        require(run.state() == ExecutionRun.State.WAITING_ACCEPTANCE && candidate.runId().toString().equals(engine.currentRun(scope))
                && candidate.generation() == task.executionGeneration() && run.generation() == task.executionGeneration()
                && run.constraintRevision() == task.constraintRevision() && command.expectedGeneration() == task.executionGeneration()
                && command.expectedConstraintRevision() == task.constraintRevision() && version.constraintRevision() == task.constraintRevision()
                && version.payloadHash().equals(command.expectedSnapshotHash()) && json.pendingInputs(task.pendingInputs()).isEmpty()
                && Objects.equals(task.currentVersionId(), command.expectedCurrentVersionId())
                && Objects.equals(version.baseVersionId(), task.currentVersionId()), STALE_VERSION);
        require(candidate.state().equals("PRESENTED") || candidate.state().equals("STALE"), STALE_VERSION);
        var eligibility = eligibility(scope, candidate, version);
        require(forceRecheck || eligibility != ReviewGate.Result.BLOCKED, INVALID_INPUT);
        if (forceRecheck || eligibility == ReviewGate.Result.REVALIDATE || candidate.state().equals("STALE")) {
            // 证据通知也可先把候选标为STALE；是否已经排队必须依据运行状态，不能只看候选标签。
            boolean enqueue = run.state() == ExecutionRun.State.WAITING_ACCEPTANCE;
            checked(mapper.update(scope, candidate.id(), "STALE", candidate.validationId(), candidate.reviewId()));
            return receipt(command, hash, new Acceptance(Status.REVALIDATION_REQUIRED, null, candidate.snapshotId(), candidate.runId()), enqueue);
        }
        UUID acceptanceId = UUID.randomUUID();
        var result = new Acceptance(Status.ACCEPTED, acceptanceId, candidate.snapshotId(), candidate.runId());
        checked(mapper.accept(command, candidate, acceptanceId, hash, json.encode(result)));
        // 接受判断和实际移动指针之间仍可能到期；SQL 用数据库时钟再封一次窗口，失败回滚接受事实。
        require(mapper.activate(command, candidate) == 1, STALE_VERSION);
        var b = budgets.read(run.id()); require(b != null, NOT_FOUND);
        var current = new ExecutionRun(run.id(), run.scope(), run.generation(), run.constraintRevision(), run.state(),
                new RunBudget(b.modelCalls(), b.toolCalls(), b.repairRounds(), Duration.ofMillis(b.activeMillis())),
                run.continuedFromRunId(), run.continuedByRunId(), run.failureKind());
        checked(engine.updateRun(scope, run.id(), json.encode(current.completeAfterAcceptance(acceptanceId)), null));
        checked(mapper.update(scope, candidate.id(), "ACCEPTED", candidate.validationId(), candidate.reviewId()));
        checked(engine.event(scope, run.id(), null, "ITINERARY_ACCEPTED"));
        return receipt(command, hash, result, false);
    }
    /**
     * {@inheritDoc} 不返回其他归属的快照身份。
     */
    @Override public UUID snapshotId(TaskScope scope, UUID candidateId) { return candidate(scope, candidateId).snapshotId(); }
    /**
     * @param scope 归属
     * @param version 不可变元信息
     * @return Java 问题；坏格式保存失败报告而非将其当成可接受空行程
     */
    private List<ItineraryReport.Issue> validate(TaskScope scope, ItineraryVersion version) {
        try {
            var snapshot = json.decode(tasks.snapshot(scope, version.snapshotId()).payload(), TripSnapshot.class);
            var content = json.decode(tasks.revision(scope, version.constraintRevision()), ConstraintRevision.class);
            var facts = evidence == null ? List.<com.lawrence.travel.domain.knowledge.FieldEvidence>of() : evidence.resolve(scope, version.evidenceRefs()).stream().filter(v -> v.effectiveStatus() == com.lawrence.travel.domain.knowledge.EvidenceRecord.Status.KNOWN).map(com.lawrence.travel.application.knowledge.Research.View::evidence).toList();
            var issues = new ArrayList<>(validator.validate(snapshot, content, facts, mapper.now(), com.lawrence.travel.application.itinerary.TravelEvidenceParser.parse(facts, value -> json.decode(value, Object.class))));
            if (!preferenceCurrent(scope, version)) issues.add(new ItineraryReport.Issue("PREFERENCE_STALE", null, true, false));
            if (!dependencies(scope, version.evidenceRefs(), mapper.now(), mapper.now().plus(validity)).allCurrent()) issues.add(new ItineraryReport.Issue("EVIDENCE_STALE_OR_UNKNOWN", null, true, true));
            if (evidence != null && evidence.resolve(scope, version.evidenceRefs()).stream().anyMatch(v -> v.evidence().fieldPath().equals("weather") && v.evidence().status() == com.lawrence.travel.domain.knowledge.EvidenceRecord.Status.UNKNOWN)) issues.add(new ItineraryReport.Issue("WEATHER_UNKNOWN", null, false, true));
            if (!snapshot.evidenceRefs().equals(version.evidenceRefs()) || !Objects.equals(snapshot.baseVersionId(), version.baseVersionId())) issues.add(new ItineraryReport.Issue("SCHEMA_BINDING", null, true, false));
            return issues;
        } catch (TravelStorageFailure | IllegalArgumentException | NullPointerException malformed) {
            return List.of(new ItineraryReport.Issue("SCHEMA_INVALID", null, true, false));
        }
    }
    /**
     * @param scope 归属
     * @param candidate 候选
     * @param version 快照
     * @return 精确门禁；证据存储未接入时不猜测其有效性
     */
    private ReviewGate.Result eligibility(TaskScope scope, CandidateMapper.CandidateRow candidate, ItineraryVersion version) {
        var validation = report(scope, candidate.validationId()); var review = report(scope, candidate.reviewId());
        if (validation != null) committed(scope, candidate, validation.producerStepId(), validation.producerAttemptId(), StageType.VALIDATE);
        if (review != null) {
            committed(scope, candidate, review.producerStepId(), review.producerAttemptId(), StageType.REVIEW);
            require(!review.producerStepId().equals(candidate.planStepId()), INVALID_INPUT);
            var row = mapper.report(scope, review.id());
            if (!Objects.equals(row.validationId(), candidate.validationId())) return ReviewGate.Result.REVALIDATE;
        }
        boolean dependencies = preferenceCurrent(scope, version) && dependencies(scope, version.evidenceRefs(), mapper.now(), mapper.now().plus(validity)).allCurrent();
        return gate.evaluate(binding(version), validation, review, mapper.now(), dependencies);
    }
    /**
     * @param fence 来源资格
     * @param candidate 候选
     * @param binding 精确绑定
     * @param kind 来源角色
     * @param issues 问题
     * @param validation 审核依据；任一写入失败回滚整个阶段
     */
    private void saveReport(ExecutionFence fence, CandidateMapper.CandidateRow candidate, ItineraryReport.Binding binding,
                            ItineraryReport.Kind kind, List<ItineraryReport.Issue> issues, UUID validation) {
        Instant now = mapper.now();
        Instant until = now.plus(validity);
        until = dependencies(fence.scope(), binding.evidenceRefs(), now, until).validUntil();
        if (validation != null) { var prior = report(fence.scope(), validation); if (prior != null && prior.validUntil().isBefore(until)) until = prior.validUntil(); }
        if (!until.isAfter(now)) until = now.plusMillis(1);
        var report = new ItineraryReport(UUID.randomUUID(), binding, kind, issues, now, until, fence.stepId(), fence.attemptId());
        checked(mapper.insertReport(fence.scope(), candidate, report, json.encode(report), validation));
        checked(mapper.update(fence.scope(), candidate.id(), "NEW", kind == ItineraryReport.Kind.VALIDATION ? report.id() : validation,
                kind == ItineraryReport.Kind.REVIEW ? report.id() : null));
    }
    /** @param scope 私有范围 @param refs 实际引用 @param now DB时间 @param upper 报告上限 @return 原始未知天气仅作可见风险；撤回、冲突和其他未知不豁免，硬天气约束仍由校验器阻断 */
    private com.lawrence.travel.application.knowledge.Research.Dependencies dependencies(TaskScope scope, java.util.Set<com.lawrence.travel.domain.knowledge.EvidenceRecord.Reference> refs, Instant now, Instant upper) {
        if (refs.isEmpty()) return new com.lawrence.travel.application.knowledge.Research.Dependencies(true, upper);
        if (evidence == null) return new com.lawrence.travel.application.knowledge.Research.Dependencies(false, upper);
        boolean current = true; Instant until = upper;
        for (var view : evidence.resolve(scope, refs)) {
            var value = view.evidence();
            boolean known = view.effectiveStatus() == com.lawrence.travel.domain.knowledge.EvidenceRecord.Status.KNOWN;
            boolean unknownWeather = value.fieldPath().equals("weather") && value.status() == com.lawrence.travel.domain.knowledge.EvidenceRecord.Status.UNKNOWN && view.reason().equals("ORIGINAL");
            current &= (known || unknownWeather) && !now.isBefore(value.retrievedAt()) && now.isBefore(value.expiresAt());
            if (value.expiresAt().isBefore(until)) until = value.expiresAt();
        }
        return new com.lawrence.travel.application.knowledge.Research.Dependencies(current, until);
    }
    /**
     * @param scope 归属
     * @param candidate 候选
     * @param stepId 来源阶段
     * @param attemptId 来源尝试
     * @param type 应有角色；仅选中的已完成尝试可成为依据
     */
    private void committed(TaskScope scope, CandidateMapper.CandidateRow candidate, UUID stepId, UUID attemptId, StageType type) {
        var row = engine.step(scope, candidate.runId(), stepId); require(row != null, NOT_FOUND);
        var step = json.decode(row.body(), ExecutionStep.class);
        require(step.key().type() == type && step.state() == ExecutionStep.State.COMPLETED && attemptId.equals(row.committedAttemptId()), INVALID_INPUT);
    }
    /**
     * @param version 不可变快照
     * @return 对应本实例当前规则及审核配置的完整绑定
     */
    private ItineraryReport.Binding binding(ItineraryVersion version) {
        return new ItineraryReport.Binding(version.snapshotId(), version.schemaVersion(), version.payloadHash(), version.constraintRevision(),
                version.evidenceRefs(), version.preferenceVersion(), rules, reviewConfiguration);
    }
    /** @param scope 私有范围 @param version 实际使用版本 @return 空旧绑定仅在用户从未有偏好时有效；保守失效不重写已接受行程 */
    private boolean preferenceCurrent(TaskScope scope, ItineraryVersion version) {
        Long current = mapper.preferenceRevision(scope);
        return current != null && (version.preferenceVersion() == null ? current == 0 : version.preferenceVersion().revision() == current);
    }
    /**
     * @param scope 归属
     * @param id 候选
     * @return 同任务记录，缺失和越权同类拒绝
     */
    private CandidateMapper.CandidateRow candidate(TaskScope scope, UUID id) { var value = mapper.candidate(scope, id); require(value != null, NOT_FOUND); return value; }
    /**
     * @param scope 归属
     * @param id 快照
     * @return 精确元信息
     */
    private ItineraryVersion version(TaskScope scope, UUID id) { var row = tasks.snapshot(scope, id); require(row != null, NOT_FOUND); return json.decode(row.metadata(), ItineraryVersion.class); }
    /**
     * @param scope 归属
     * @param id 报告或空
     * @return 不可变报告或空
     */
    private ItineraryReport report(TaskScope scope, UUID id) { if (id == null) return null; var row = mapper.report(scope, id); return row == null ? null : json.decode(row.body(), ItineraryReport.class); }
    /**
     * @param output 检查点
     * @return 已知业务操作或空；未知操作不静默忽略
     */
    private String operation(StageOutput output) { Object value = object(output.checkpoint()).get("operation"); if (value == null) return null; require(value instanceof String, INVALID_INPUT); return (String) value; }
    /**
     * @param text 对象 JSON
     * @return 对象；不记录原始正文
     */
    private Map<?, ?> object(String text) { Object value = json.decode(text, Object.class); require(value instanceof Map<?, ?>, INVALID_INPUT); return (Map<?, ?>) value; }
    /**
     * @param value UUID 文本
     * @return 稳定身份，错误不回显输入
     */
    private UUID uuid(Object value) { try { return UUID.fromString((String) value); } catch (RuntimeException invalid) { throw new TravelStorageFailure(INVALID_INPUT); } }
    /**
     * @param command 接受请求
     * @return 排除传输幂等键的语义摘要，允许不同键重试同一接受事实
     */
    private String commandHash(CandidateAcceptanceCommand command) {
        var values = new LinkedHashMap<String, Object>(); values.put("scope", command.scope()); values.put("candidate", command.candidateId());
        values.put("current", command.expectedCurrentVersionId()); values.put("revision", command.expectedConstraintRevision());
        values.put("generation", command.expectedGeneration()); values.put("hash", command.expectedSnapshotHash()); return json.hash(json.encode(values));
    }
    /**
     * @param row 原始结果
     * @param hash 当前语义
     * @return 原响应；同键不同内容拒绝
     */
    private Acceptance replay(CandidateMapper.ReceiptRow row, String hash) { require(row.commandHash().equals(hash), IDEMPOTENCY_CONFLICT); return json.decode(row.result(), Acceptance.class); }
    /**
     * @param command 请求
     * @param hash 语义
     * @param result 首次结果
     * @param enqueue 是否登记后继
     * @return 稳定响应；后继必须由外层同事务完成
     */
    private Attempt receipt(CandidateAcceptanceCommand command, String hash, Acceptance result, boolean enqueue) {
        checked(mapper.saveRequest(command, hash, json.encode(result))); return new Attempt(result, enqueue);
    }
    /** 必须在真实事务中使用，不允许把接受请求与重校验事件分两次提交。 */
    private static void transaction() { require(TransactionSynchronizationManager.isActualTransactionActive(), INVALID_INPUT); }
    /**
     * @param count SQL 更新数；零行不能当成功
     */
    private static void checked(int count) { require(count == 1, INVALID_INPUT); }
    /**
     * @param condition 条件
     * @param code 固定错误，不泄露私有正文
     */
    private static void require(boolean condition, TravelStorageFailure.Code code) { if (!condition) throw new TravelStorageFailure(code); }
}
