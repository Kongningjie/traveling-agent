package com.lawrence.travel.infrastructure.interaction;

import com.lawrence.travel.application.execution.StageOutput;
import com.lawrence.travel.application.interaction.Negotiation;
import com.lawrence.travel.application.interaction.NegotiationHandler;
import com.lawrence.travel.application.port.NegotiationStore;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.domain.execution.ExecutionFence;
import com.lawrence.travel.domain.execution.ExecutionRun;
import com.lawrence.travel.domain.execution.StageType;
import com.lawrence.travel.domain.interaction.DecisionAnswer;
import com.lawrence.travel.domain.interaction.DecisionRequest;
import com.lawrence.travel.domain.interaction.RequirementFields;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.domain.travel.TravelTask;
import com.lawrence.travel.infrastructure.execution.persistence.EngineMapper;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import com.lawrence.travel.infrastructure.travel.TravelMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.IDEMPOTENCY_CONFLICT;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.INVALID_INPUT;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.NOT_FOUND;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.STALE_VERSION;

/** 协商收件箱与阶段提交投影；没有网络调用，需求、卡片和采纳记录与引擎 C 事务共同提交。 */
public final class PostgresNegotiationStore implements NegotiationStore {
    /** {@inheritDoc} 仅受信应用调用；自由文本的原文已经通过意图解释的来源检查。 */
    @Override public void interpreted(TaskScope scope, UUID input, List<Negotiation.Fact> facts) { transaction(); checked(mapper.interpreted(scope, input, json.encode(facts))); }
    private final NegotiationMapper mapper;
    private final TravelMapper tasks;
    private final EngineMapper engine;
    private final TravelJson json;
    /**
     * @param mapper 收件箱 SQL
     * @param tasks 任务 SQL
     * @param engine 同源执行 SQL
     * @param json 严格编解码
     */
    public PostgresNegotiationStore(NegotiationMapper mapper, TravelMapper tasks, EngineMapper engine, TravelJson json) { this.mapper = mapper; this.tasks = tasks; this.engine = engine; this.json = json; }
    /**
     * @param specs 精确版本的字段和选项元信息
     */
    private record Catalog(Map<UUID, Negotiation.QuestionSpec> specs) { }
    /**
     * {@inheritDoc} 新修改立即撤销旧执行资格，但不覆盖已确认内容。
     */
    @Override public boolean receiveChange(TaskScope scope, UUID id, String text, List<Negotiation.Fact> facts) {
        transaction(); validate(text, facts); var row = locked(scope);
        String hash = json.hash(json.encode(List.of("CHANGE", text, NegotiationHandler.updates(facts))));
        var replay = mapper.message(scope, id); if (replay != null) { require(hash.equals(replay.requestHash()), IDEMPOTENCY_CONFLICT); return false; }
        require(mapper.pending(scope).size() < 100, INVALID_INPUT);
        if (!facts.isEmpty()) RequirementFields.merge(revision(scope, row.constraintRevision()).requirements(), NegotiationHandler.updates(facts), ConstraintRevision.Source.USER);
        var message = new Negotiation.Message(id, "CHANGE", text, List.copyOf(facts), null, 0, row.constraintRevision(), "RECEIVED");
        checked(mapper.insert(scope, id, hash, json.encode(message), null, "RECEIVED"));
        long sequence = mapper.sequence(scope, id);
        var next = task(row);
        // 原回答已经可靠接收，新修改到来时只撤销旧执行，不能把尚未解释的有效选择一起丢掉。
        for (var pending : mapper.pendingAnswers(scope)) {
            var original = message(pending); var response = json.decode(pending.answer(), Negotiation.Response.class);
            var promoted = new Negotiation.Message(original.id(), response.action().equals("AUTHORIZE") ? "CHANGE_AUTHORIZED" : "CHANGE_ACCEPTED", original.text(), original.facts(), original.decisionId(), original.decisionRevision(), original.constraintRevision(), original.state());
            checked(mapper.promote(scope, original.id(), json.encode(promoted)));
            long originalSequence = mapper.sequence(scope, original.id());
            next = next.registerInput(original.id(), originalSequence).registerChange(original.id()); checked(tasks.insertChange(scope, original.id(), originalSequence));
        }
        next = next.registerInput(id, sequence).registerChange(id);
        checked(tasks.insertChange(scope, id, sequence)); update(row, next); mapper.supersede(scope); return true;
    }
    /**
     * {@inheritDoc} 接受响应和业务结果分开；幂等响应不会变成后来运行的状态。
     */
    @Override public Negotiation.Receipt receipt(TaskScope scope, UUID id) { var row = mapper.message(scope, id); return row == null || row.receipt() == null ? null : json.decode(row.receipt(), Negotiation.Receipt.class); }
    /**
     * {@inheritDoc} 外层同时登记运行或解释事件。
     */
    @Override public void saveReceipt(TaskScope scope, Negotiation.Receipt result) { transaction(); checked(mapper.receipt(scope, result.inputId(), json.encode(result))); }
    /**
     * {@inheritDoc} 锁任务后校验当前卡片；未知动作、过期回答和越界授权不记录为已采纳。
     */
    @Override public Negotiation.Card receiveAnswer(TaskScope scope, UUID id, UUID decisionId, long revision, long constraints, String action, Map<UUID, UUID> selected, String text, Set<UUID> authorization) {
        transaction(); validate(text, List.of()); require(id != null && decisionId != null && selected != null && authorization != null
                && Set.of("ANSWER", "AUTHORIZE", "REJECT", "UNDECIDED").contains(action), INVALID_INPUT);
        var row = locked(scope); var request = new Negotiation.Response(decisionId, revision, constraints, action, Map.copyOf(selected), text, Set.copyOf(authorization));
        String hash = json.hash(json.encode(List.of(decisionId, revision, constraints, action, selected, text, authorization.stream().sorted().toList()))); var replay = mapper.message(scope, id);
        if (replay != null) { require(hash.equals(replay.requestHash()), IDEMPOTENCY_CONFLICT); var original = json.decode(replay.body(), Negotiation.Message.class); return loadCard(scope, decisionId, original.decisionRevision()); }
        UUID runId = currentRun(scope); require(runId != null, STALE_VERSION);
        var run = json.decode(engine.run(scope, runId).body(), ExecutionRun.class);
        var card = loadCard(scope, decisionId, revision); var decision = card.decision();
        require(run.state() == ExecutionRun.State.WAITING_USER && decision.runId().equals(runId) && decision.state() == DecisionRequest.State.OPEN
                && decision.generation() == row.executionGeneration() && decision.revision() == revision && decision.constraintRevision() == constraints
                && row.constraintRevision() == constraints && task(row).pendingInputs().isEmpty(), STALE_VERSION);
        require(action.equals("AUTHORIZE") ? !authorization.isEmpty() && decision.allowedAuthorizationScope().containsAll(authorization)
                && selected.keySet().equals(authorization) : authorization.isEmpty(), INVALID_INPUT);
        require(action.equals("UNDECIDED") ? selected.isEmpty() : !selected.isEmpty() || !text.isBlank(), INVALID_INPUT);
        var facts = new ArrayList<Negotiation.Fact>();
        for (var choice : selected.entrySet()) {
            var spec = card.specs().get(choice.getKey()); require(spec != null && spec.options().containsKey(choice.getValue()), INVALID_INPUT);
            require(decision.remaining().containsKey(choice.getKey()) && decision.remaining().get(choice.getKey()).options().containsKey(choice.getValue()), INVALID_INPUT);
            facts.add(new Negotiation.Fact(spec.field(), spec.options().get(choice.getValue()), null));
        }
        boolean recordOnly = action.equals("REJECT") || action.equals("UNDECIDED");
        var message = new Negotiation.Message(id, "ANSWER", text, recordOnly ? List.of() : facts, decisionId, revision, constraints, recordOnly ? "RECORDED" : "RECEIVED");
        checked(mapper.insert(scope, id, hash, json.encode(message), json.encode(request), message.state()));
        checked(engine.event(scope, runId, null, recordOnly ? "ANSWER_RECORDED" : "MESSAGE_RECEIVED")); return card;
    }
    /**
     * {@inheritDoc} 状态来自权威列，不信任原始收讫 JSON 内的旧状态。
     */
    @Override public List<Negotiation.Message> messages(TaskScope scope) { require(tasks.task(scope) != null, NOT_FOUND); return mapper.messages(scope).stream().map(this::message).toList(); }
    /**
     * {@inheritDoc} 只读取当前运行仍有效的问题。
     */
    @Override public Negotiation.Card card(TaskScope scope) {
        require(tasks.task(scope) != null, NOT_FOUND); UUID run = currentRun(scope); if (run == null) return null;
        String body = mapper.card(scope, run); if (body == null) return null; var decision = json.decode(body, DecisionRequest.class); return loadCard(scope, decision.id(), decision.revision());
    }
    /**
     * {@inheritDoc} 仅返回经过原卡片校验的选项身份，不读取拒绝原文作事实。
     */
    @Override public Set<UUID> rejected(TaskScope scope) { require(tasks.task(scope) != null, NOT_FOUND); return mapper.rejections(scope).stream().map(UUID::fromString).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
    /**
     * {@inheritDoc} 多条修改按原消息顺序解释，不因新的运行只读取最后一条而丢要求。
     */
    @Override public Negotiation.Context context(TaskScope scope, UUID inputId) {
        var task = tasks.task(scope); require(task != null, NOT_FOUND); var row = mapper.message(scope, inputId); require(row != null, NOT_FOUND);
        int remaining = 0;
        if (row.answer() == null) { var pending = mapper.pending(scope); require(!pending.isEmpty(), STALE_VERSION); row = pending.getFirst(); remaining = pending.size() - 1; }
        var message = message(row); require(message.state().equals("RECEIVED"), STALE_VERSION);
        var response = row.answer() == null ? null : json.decode(row.answer(), Negotiation.Response.class);
        return new Negotiation.Context(scope, revision(scope, task.constraintRevision()), message,
                response == null ? card(scope) : loadCard(scope, response.decisionId(), response.revision()), remaining, response, mapper.receivedAt(scope, message.id()));
    }
    /**
     * {@inheritDoc} 未使用长期偏好；按引擎任务锁序提交，无额外前置锁。
     */
    @Override public void beforeTaskLock(ExecutionFence fence, StageOutput output) { }
    /**
     * {@inheritDoc} 阶段通过 fence 后再次验证回答、字段及卡片；任何失败回滚全部业务与后继事件。
     */
    @Override public Result project(ExecutionFence fence, StageType type, StageOutput output) {
        transaction(); if (!json.decode(output.checkpoint(), Map.class).containsKey("negotiation")) return null;
        var body = json.decode(output.checkpoint(), Envelope.class); require(body.negotiation() != null, INVALID_INPUT);
        require(type == StageType.UNDERSTAND, INVALID_INPUT);
        var prepared = body.negotiation(); var context = context(fence.scope(), prepared.inputId());
        require(context.revision().revision() == fence.constraintRevision(), STALE_VERSION);
        var row = locked(fence.scope());
        if (prepared.outsideScope()) {
            require(context.response() != null, INVALID_INPUT); UUID replacement = NegotiationHandler.outsideId(context.message().id());
            checked(mapper.finish(fence.scope(), context.message().id(), "SPLIT_TO_CHANGE", fence.runId()));
            receiveChange(fence.scope(), replacement, "", prepared.facts());
            checked(mapper.inherit(fence.scope(), replacement, context.message().id(), context.response().action().equals("AUTHORIZE") ? "CHANGE_AUTHORIZED" : "CHANGE_ACCEPTED"));
            return new Result(null, null, replacement);
        }
        var updates = NegotiationHandler.updates(prepared.facts());
        // 关联字段必须在完整变更上校验；逐字段合并会把新起日与旧止日混用，拒绝合法改期。
        var merged = RequirementFields.merge(RequirementFields.inputBase(context.revision().requirements(), updates.keySet()), updates, NegotiationHandler.source(context));
        var origins = new java.util.HashMap<String, ConstraintRevision.Source>();
        for (var fact : prepared.facts()) origins.put(fact.field(), source(context, fact));
        if (origins.containsKey("fixedArrangements")) origins.put("fixedStatus", origins.get("fixedArrangements"));
        // 一次校验不能抹平选项采纳和用户自由补充的不同来源；未修改字段保持原来源。
        var constraints = merged.stream().map(value -> origins.containsKey(value.field())
                ? new ConstraintRevision.Requirement(value.id(), value.field(), value.value(), value.kind(), origins.get(value.field()), value.targetIds()) : value).toList();
        long nextRevision = row.constraintRevision() + 1;
        if (context.response() != null) {
            var original = context.card().decision();
            require(original.revision() == context.response().revision() && original.constraintRevision() == row.constraintRevision()
                    && original.generation() == row.executionGeneration() && original.runId().equals(fence.runId()) && original.state() == DecisionRequest.State.OPEN, STALE_VERSION);
            require(prepared.facts().stream().allMatch(fact -> context.card().specs().values().stream().anyMatch(spec -> spec.field().equals(fact.field()))), INVALID_INPUT);
            if (!prepared.resolved().isEmpty()) {
                var response = context.response();
                var answer = new DecisionAnswer(context.message().id(), original.id(), original.revision(), original.constraintRevision(), response.action().equals("AUTHORIZE") ? DecisionAnswer.Kind.AUTHORIZE : DecisionAnswer.Kind.ANSWER,
                        response.selected(), response.text(), response.authorization(), DecisionAnswer.State.INTERPRETED);
                original.applyConfirmed(answer, task(row), prepared.resolved(), nextRevision);
                checked(engine.insertAnswer(fence.scope(), fence.runId(), answer.id(), original.id(), json.hash(json.encode(response)), json.encode(answer.applied())));
            }
        }
        var fixed = context.revision().fixedArrangements();
        for (var fact : prepared.facts()) {
            if (fact.field().equals("fixedStatus") && fact.value().equals("NONE")) fixed = List.of();
            if (fact.field().equals("fixedArrangements")) fixed = RequirementFields.mergeFixed(fixed, RequirementFields.fixed(fact.value(), context.message().id()));
        }
        var revision = new ConstraintRevision(fence.scope(), nextRevision, constraints, fixed);
        checked(tasks.insertRevision(fence.scope(), nextRevision, json.encode(revision), "用户协商", prepared.clarification().isBlank() ? "明确增量" : "保留要求待澄清"));
        var nextTask = context.response() == null ? task(row).applyChange(context.message().id(), row.constraintRevision())
                : new TravelTask(fence.scope(), nextRevision, row.executionGeneration(), row.currentVersionId(), task(row).pendingInputs());
        if (context.response() == null) checked(tasks.applyChange(fence.scope(), context.message().id(), nextRevision, json.hash(json.encode(prepared.facts()))));
        update(row, nextTask);
        var nextCard = prepared.nextCard();
        if (nextCard != null) {
            var decision = nextCard.decision(); require(decision.scope().equals(fence.scope()) && decision.runId().equals(fence.runId())
                    && decision.constraintRevision() == nextRevision && decision.generation() == fence.generation(), INVALID_INPUT);
            if (context.card() == null) checked(engine.insertDecision(fence.scope(), fence.runId(), decision.id(), json.encode(decision)));
            else { require(decision.id().equals(context.card().decision().id()) && decision.revision() == context.card().decision().revision() + 1, INVALID_INPUT); checked(engine.updateDecision(fence.scope(), fence.runId(), decision.id(), json.encode(decision))); }
            checked(mapper.insertSpecs(fence.scope(), decision.id(), decision.revision(), json.encode(new Catalog(nextCard.specs()))));
        }
        checked(mapper.finish(fence.scope(), context.message().id(), prepared.clarification().isBlank() ? "APPLIED" : "NEEDS_CLARIFICATION", fence.runId()));
        return new Result(null, nextRevision, null);
    }
    /**
     * @param negotiation 协商提交；候选检查点的其他字段由其独立适配器解释
     */
    private record Envelope(Negotiation.Prepared negotiation) { }
    /**
     * @param context 原始输入
     * @param fact 当前字段
     * @return 被新运行接管的回答仍保留其确认来源
     */
    private ConstraintRevision.Source source(Negotiation.Context context, Negotiation.Fact fact) {
        if (fact.quote() != null) return ConstraintRevision.Source.USER;
        if (context.message().kind().equals("CHANGE_AUTHORIZED")) return ConstraintRevision.Source.AUTHORIZED_SYSTEM;
        if (context.message().kind().equals("CHANGE_ACCEPTED")) return ConstraintRevision.Source.ACCEPTED_SUGGESTION;
        return context.response() == null ? ConstraintRevision.Source.USER : context.response().action().equals("AUTHORIZE") ? ConstraintRevision.Source.AUTHORIZED_SYSTEM : ConstraintRevision.Source.ACCEPTED_SUGGESTION;
    }
    /**
     * @param scope 归属
     * @param id 卡片
     * @param expected 客户端问题版本
     * @return 精确历史元信息与当前生命周期，不能取别的任务卡片
     */
    private Negotiation.Card loadCard(TaskScope scope, UUID id, long expected) {
        String body = mapper.decision(scope, id); require(body != null, NOT_FOUND); var decision = json.decode(body, DecisionRequest.class);
        String specs = mapper.specs(scope, id, expected); require(specs != null, STALE_VERSION);
        var rejected = rejected(scope); var questions = new java.util.LinkedHashMap<UUID, DecisionRequest.Question>();
        for (var entry : decision.remaining().entrySet()) {
            var question = entry.getValue();
            questions.put(entry.getKey(), question.recommendedOption() != null && rejected.contains(question.recommendedOption())
                    ? new DecisionRequest.Question(question.id(), question.prompt(), question.options(), null) : question);
        }
        var visible = new DecisionRequest(decision.id(), decision.scope(), decision.runId(), decision.generation(), decision.revision(), decision.constraintRevision(),
                questions, decision.freeTextAllowed(), decision.allowedAuthorizationScope(), decision.state());
        return new Negotiation.Card(visible, json.decode(specs, Catalog.class).specs(), rejected);
    }
    /**
     * @param row 原始记录
     * @return 带当前处理状态的私有消息
     */
    private Negotiation.Message message(NegotiationMapper.Row row) { var m = json.decode(row.body(), Negotiation.Message.class); return new Negotiation.Message(m.id(), m.kind(), m.text(), m.facts(), m.decisionId(), m.decisionRevision(), m.constraintRevision(), row.status()); }
    /**
     * @param scope 归属
     * @param revision 已存在修订
     * @return 权威不可变内容
     */
    private ConstraintRevision revision(TaskScope scope, long revision) { return json.decode(tasks.revision(scope, revision), ConstraintRevision.class); }
    /**
     * @param scope 归属
     * @return 已锁任务；缺失与越权同类拒绝
     */
    private TravelMapper.TaskRow locked(TaskScope scope) { var row = tasks.lockTask(scope); require(row != null, NOT_FOUND); return row; }
    /**
     * @param scope 归属
     * @return 当前运行或空
     */
    private UUID currentRun(TaskScope scope) { String value = engine.currentRun(scope); return value == null ? null : UUID.fromString(value); }
    /**
     * @param row 权威任务行
     * @return 领域状态
     */
    private TravelTask task(TravelMapper.TaskRow row) { return new TravelTask(new TaskScope(row.userId(), row.id()), row.constraintRevision(), row.executionGeneration(), row.currentVersionId(), json.pendingInputs(row.pendingInputs())); }
    /**
     * @param row 原行
     * @param next 领域新状态；不触碰当前行程或事件游标
     */
    private void update(TravelMapper.TaskRow row, TravelTask next) { checked(tasks.updateTask(new TravelMapper.TaskRow(row.id(), row.userId(), row.title(), next.constraintRevision(), next.executionGeneration(), row.currentVersionId(), json.encode(next.pendingInputs()), row.eventSequence(), row.lockVersion() + 1), row.lockVersion())); }
    /**
     * @param text 原文
     * @param facts 明确字段；限制单次提取上下文规模
     */
    private static void validate(String text, List<Negotiation.Fact> facts) { require(text != null && text.length() <= 1000 && facts != null && facts.size() <= 20 && facts.stream().allMatch(java.util.Objects::nonNull), INVALID_INPUT); }
    /** 写入只能在外层业务事务内，不允许把收件和可靠调度分开提交。 */
    private static void transaction() { require(TransactionSynchronizationManager.isActualTransactionActive(), INVALID_INPUT); }
    /**
     * @param count 写入行数
     */
    private static void checked(int count) { require(count == 1, INVALID_INPUT); }
    /**
     * @param condition 前置条件
     * @param code 安全分类
     */
    private static void require(boolean condition, TravelStorageFailure.Code code) { if (!condition) throw new TravelStorageFailure(code); }
}
