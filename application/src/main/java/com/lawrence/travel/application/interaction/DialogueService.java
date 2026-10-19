package com.lawrence.travel.application.interaction;

import com.lawrence.travel.application.execution.RunService;
import com.lawrence.travel.application.itinerary.ItineraryService;
import com.lawrence.travel.application.itinerary.VersionService;
import com.lawrence.travel.application.memory.PreferenceService;
import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.BackgroundWorkSource;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.DialogueInterpreter;
import com.lawrence.travel.application.port.DialogueStore;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.TravelStore;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.interaction.DialogueIntent;
import com.lawrence.travel.domain.interaction.RequirementFields;
import com.lawrence.travel.domain.travel.TaskScope;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 统一聊天用例；事务外理解，事务内确认路由与业务提交，不另建调度器。 */
public final class DialogueService implements BackgroundWorkSource {
    private final com.lawrence.travel.application.document.AdviceService advice;
    private final DialogueStore store;
    private final DialogueInterpreter interpreter;
    private final BusinessTransactions transactions;
    private final AccountStore accounts;
    private final NegotiationService negotiation;
    private final RunService runs;
    private final ItineraryService itineraries;
    private final VersionService versions;
    private final PreferenceService preferences;
    private final TravelStore travel;
    private final InteractionCodec json;
    private final ExecutionEngineStore engine;
    private final boolean externalEnabled;
    private final com.lawrence.travel.application.safety.SafetyGate safety;
    /** @param store 聊天权威 @param interpreter 一次协调理解 @param transactions 短事务 @param accounts 身份权威 @param negotiation 需求用例
     * @param runs 运行控制 @param itineraries 候选接受 @param versions 历史恢复 @param preferences 长期偏好 @param travel 旅行只读端口 @param json JSON @param engine 原运行恢复 @param externalEnabled 现有调用授权开关 */
    public DialogueService(DialogueStore store, DialogueInterpreter interpreter, BusinessTransactions transactions, AccountStore accounts,
            NegotiationService negotiation, RunService runs, ItineraryService itineraries, VersionService versions, PreferenceService preferences, TravelStore travel, InteractionCodec json, ExecutionEngineStore engine, boolean externalEnabled) {
        this(store,interpreter,transactions,accounts,negotiation,runs,itineraries,versions,preferences,travel,json,engine,externalEnabled,com.lawrence.travel.application.safety.SafetyGate.local());
    }
    /** @param store 聊天 @param interpreter 受控理解 @param transactions 事务 @param accounts 身份 @param negotiation 需求 @param runs 运行 @param itineraries 接受 @param versions 历史 @param preferences 偏好 @param travel 权威旅行 @param json JSON @param engine 执行 @param externalEnabled 原授权 @param safety 展示前检查 */
    public DialogueService(DialogueStore store,DialogueInterpreter interpreter,BusinessTransactions transactions,AccountStore accounts,NegotiationService negotiation,RunService runs,ItineraryService itineraries,VersionService versions,PreferenceService preferences,TravelStore travel,InteractionCodec json,ExecutionEngineStore engine,boolean externalEnabled,com.lawrence.travel.application.safety.SafetyGate safety) {
        this(store,interpreter,transactions,accounts,negotiation,runs,itineraries,versions,preferences,travel,json,engine,externalEnabled,safety,null);
    }
    /** @param store 聊天 @param interpreter 理解 @param transactions 事务 @param accounts 身份 @param negotiation 协商 @param runs 运行 @param itineraries 候选 @param versions 版本 @param preferences 偏好 @param travel 旅行 @param json JSON @param engine 执行 @param externalEnabled 当前许可 @param safety 文本检查 @param advice 建议应用用例，旧离线调用可空 */
    public DialogueService(DialogueStore store,DialogueInterpreter interpreter,BusinessTransactions transactions,AccountStore accounts,NegotiationService negotiation,RunService runs,ItineraryService itineraries,VersionService versions,PreferenceService preferences,TravelStore travel,InteractionCodec json,ExecutionEngineStore engine,boolean externalEnabled,com.lawrence.travel.application.safety.SafetyGate safety,com.lawrence.travel.application.document.AdviceService advice) {
        this.advice=advice;
        this.safety=safety;
        this.store = store; this.interpreter = interpreter; this.transactions = transactions; this.accounts = accounts; this.negotiation = negotiation;
        this.runs = runs; this.itineraries = itineraries; this.versions = versions; this.preferences = preferences; this.travel = travel; this.json = json;
        this.engine = engine; this.externalEnabled = externalEnabled;
    }
    /** @param actor 认证 @param chat 可重试的创建ID @param input 消息幂等键 @param text 自由文本 @return 可靠保存的聊天，无旅行占位任务 */
    public Dialogue.View send(UserIdentity actor, UUID chat, UUID input, String text) {
        require(actor != null && chat != null && input != null && text != null && !text.isBlank() && text.length() <= 4000 && text.indexOf(0)<0);
        return transactions.required(() -> {
            // 创建和首条消息同事务；后续消息不能用新标题覆盖旧标题。
            boolean exists = store.list(actor.id(), 0).stream().anyMatch(c -> c.id().equals(chat));
            if (!exists) { try { store.read(actor.id(), chat); exists = true; } catch (TravelStorageFailure missing) { if (missing.code() != TravelStorageFailure.Code.NOT_FOUND) throw missing; } }
            if (!exists) store.create(actor.id(), chat, text.substring(0, Math.min(60, text.length())));
            if(java.util.Set.of("停止","停止规划","停止当前规划","停止当前执行").contains(text.trim().replaceAll("[。！!]$",""))) {
                if(store.containsInput(actor.id(),chat,input,text)) return store.receive(actor.id(),chat,input,text);
                store.stopPending(actor.id(),chat); var current=store.read(actor.id(),chat).chat();
                if(current.taskId()!=null) {
                    var run=store.currentRun(actor.id(),chat);
                    if(run!=null && !engine.run(new TaskScope(actor.id(),current.taskId()),run).state().terminal()) runs.cancel(actor,current.taskId(),run);
                }
                store.receive(actor.id(),chat,input,text); store.completeCommand(actor.id(),chat,input,"已处理停止请求，已有行程和对话保留。"); return store.read(actor.id(),chat);
            }
            return store.receive(actor.id(), chat, input, text);
        });
    }
    /** @param actor 认证 @param offset 分页 @return 仅本人聊天 */
    public List<Dialogue.Chat> list(UserIdentity actor, int offset) { return store.list(actor.id(), offset); }
    /** @param actor 认证 @param chat 聊天 @return 持久化的完整可见结果 */
    public Dialogue.View read(UserIdentity actor, UUID chat) { return store.read(actor.id(), chat); }
    /** @param actor 已认证用户 @param chat 新聊天幂等身份 @param task 历史私有旅行 @return 原有或新关联聊天，历史任务不绕过意图理解 */
    public Dialogue.View attach(UserIdentity actor, UUID chat, UUID task) { return transactions.required(() -> store.read(actor.id(), store.attach(actor.id(), chat, task).id())); }
    /** {@inheritDoc} 由既有Worker取得运行容量后调用，领取事务不跨模型调用。 */
    @Override public Optional<Runnable> claimWork() { return transactions.required(store::claim).map(c -> new com.lawrence.travel.application.port.BackgroundWorkSource.LeasedWork() {
        /** 原输入续租，不授予新预算或业务版本。 */
        @Override public boolean renew() { return transactions.required(() -> store.renew(c)); }
        /** 异步执行显式绑定持久关联身份；业务提交仍由原领取资格控制。 */
        @Override public void run() {
            var ids = store.correlation(c);
            if (ids == null) { process(c); return; }
            try (var ignored = com.lawrence.travel.application.execution.ExecutionCorrelation.bind(ids)) { process(c); }
        }
    }); }
    /** @param c 原领取；任何模型或协议失败保存固定原因，屏障必须等待重试或撤回 */
    private void process(Dialogue.Claim c) {
        try {
            safety.require("INPUT",c.text());
            var pref = preferences.read(actor(c.userId()));
            var augmented = new java.util.LinkedHashMap<String, Object>(); augmented.put("travel", json.decode(c.context(), Object.class)); augmented.put("preferences", pref.entries().stream().limit(20).toList());
            if (c.basis().taskId() != null) { var card = negotiation.card(actor(c.userId()), c.basis().taskId()); if (card != null) augmented.put("questions", card); }
            var modelContext = new Dialogue.Claim(c.userId(), c.chatId(), c.messageId(), c.token(), c.budgetId(), c.text(), c.receivedAt(), c.basis(), json.encode(augmented));
            var result = interpreter.interpret(modelContext);
            safety.require("OUTPUT",json.encode(result));
            var unresolved = json.decode(c.context(), java.util.Map.class).get("unresolved");
            var sources = new java.util.LinkedHashMap<UUID, String>();
            if (unresolved instanceof List<?> entries) for (Object entry : entries) if (entry instanceof java.util.Map<?, ?> item) sources.put(UUID.fromString((String) item.get("id")), (String) item.get("text"));
            require(sources.keySet().containsAll(result.resolvedInputs()));
            result.validateSources(c.text(), result.resolvedInputs().stream().map(sources::get).toList());
            transactions.required(() -> { if (!store.guard(c) || preferences.read(actor(c.userId())).version().revision()!=pref.version().revision()) { store.finish(c, "CLARIFY", "旅行状态已经变化，请核对后重新补充，或撤回这条输入。", null, "DIALOGUE_CONTEXT_CHANGED"); return null; } route(c, result); return null; });
        } catch (RuntimeException failure) {
            Thread.interrupted();
            boolean disabled = failure instanceof com.lawrence.travel.domain.execution.control.CallRejectedException rejected && rejected.reason() == com.lawrence.travel.domain.execution.control.CallRejectedException.Reason.EXTERNAL_DISABLED;
            String reason = failure instanceof com.lawrence.travel.application.memory.PreferenceChangedException ? "PREFERENCE_CHANGED" : failure instanceof com.lawrence.travel.application.safety.SafetyGate.Failure rejected ? "SAFETY_"+rejected.result().status().name() : failure instanceof com.lawrence.travel.domain.execution.control.CallRejectedException rejected ? rejected.reason().name() : "DIALOGUE_PROCESSING_FAILED";
            String explanation = disabled ? "真实外部调用尚未开启，输入已保存，暂未进行模型理解。此提示不表示免费额度用完；可以保留或撤回这条输入。" : "输入已保存，理解尚未完成。可重试或撤回；不会自动修改行程。";
            String safeReply=failure instanceof com.lawrence.travel.application.safety.SafetyGate.Failure rejected ? "本次检查未通过或暂不可用，输入已保留。可修改内容或撤回。问题编号："+rejected.result().problemId() : explanation;
            try {
                transactions.required(() -> { store.guard(c); store.finish(c, "PAUSED", safeReply, null, reason); return null; });
            } catch (RuntimeException obsolete) { System.getLogger(getClass().getName()).log(System.Logger.Level.INFO, "DIALOGUE_FAILURE_NOT_COMMITTED"); }
        }
    }
    /** @param c 已锁上下文 @param result 结构化语义；不以模型回复声称业务已提交 */
    private void route(Dialogue.Claim c, DialogueIntent.Result result) {
        var actor = actor(c.userId());
        var operations = result.intents().stream().filter(i -> i.kind() == DialogueIntent.Kind.OPERATION).toList();
        boolean planning = result.intents().stream().anyMatch(i -> i.kind() == DialogueIntent.Kind.PLANNING);
        if (!result.clarification().isBlank() || result.intents().stream().anyMatch(i -> i.kind() == DialogueIntent.Kind.UNCLEAR)
                || operations.size() > 1 || (!operations.isEmpty() && planning)) {
            store.finish(c, "CLARIFY", result.reply() + "\n" + (result.clarification().isBlank() ? "请明确本次需要执行的操作与对象，其他要求已保留原文。" : result.clarification()), null, ""); return;
        }
        store.resolve(c, result.resolvedInputs());
        if (!operations.isEmpty()) {
            var action = operations.getFirst().action();
            if (action == DialogueIntent.Action.STOP) {
                if (c.basis().taskId() == null || c.basis().runId() == null) { store.finish(c, "DONE", "当前没有正在执行的旅行规划。", null, ""); return; }
                runs.cancel(actor, c.basis().taskId(), c.basis().runId()); store.finish(c, "DONE", "已停止当前规划，已有行程和对话保留。", null, ""); return;
            }
            if (action == DialogueIntent.Action.CONTINUE) {
                String reply = "当前没有可直接恢复的运行。可继续补充需求；达到预算上限时不会自动新建预算。";
                if (c.basis().runId() != null) {
                    var scope = new TaskScope(c.userId(), c.basis().taskId()); var run = engine.run(scope, c.basis().runId());
                    if (run.state() == com.lawrence.travel.domain.execution.ExecutionRun.State.PAUSED_QUOTA) {
                        if (externalEnabled) { engine.resumeQuota(scope, run.id(), true); reply = "已请求沿用原预算恢复；每次调用仍检查免费额度、未知用量和资源限制。"; }
                        else reply = "真实外部调用仍关闭，当前规划保持暂停；不会因这条消息打开调用开关。";
                    }
                }
                store.finish(c, "DONE", reply, null, ""); return;
            }
            if (action == DialogueIntent.Action.AUTHORIZE) {
                store.finish(c, "DONE", "授权需要明确问题、选项和范围。请在当前问题卡片勾选授权内容后提交；这条文字不会授予额外权限。", null, ""); return;
            }
            if (action == DialogueIntent.Action.REJECT || action == DialogueIntent.Action.UNDECIDED) {
                var card = c.basis().taskId() == null ? null : negotiation.card(actor, c.basis().taskId());
                if (card != null) negotiation.answer(actor, c.basis().taskId(), c.messageId(), new Negotiation.Response(card.decision().id(), card.decision().revision(), card.decision().constraintRevision(), action.name(), java.util.Map.of(), c.text(), java.util.Set.of()));
                store.finish(c, "DONE", "已记录你的态度，未采纳任何建议；尚未解决的问题继续保留。", null, ""); return;
            }
            var operation = prepare(actor, c, result, action);
            if (operation == null) { store.finish(c, "CLARIFY", "请在行程详情、问题卡片或偏好管理中定位具体对象后操作；继续执行仍受原预算和调用门禁限制。", null, ""); return; }
            store.finish(c, "CONFIRM", result.reply().isBlank() ? "请核对以下操作，点击确认后才会执行。" : result.reply(), operation, ""); return;
        }
        if (planning) {
            var facts = result.facts().stream().map(f -> new Negotiation.Fact(f.field(), f.value(), f.quote())).toList();
            RequirementFields.merge(c.basis().taskId() == null ? List.of() : travel.revision(new TaskScope(c.userId(), c.basis().taskId()), c.basis().revision()).requirements(), NegotiationHandler.updates(facts), com.lawrence.travel.domain.travel.ConstraintRevision.Source.USER);
            UUID task = negotiation.applyInterpreted(actor, c.basis().taskId(), c.messageId(), c.text(), facts);
            if (c.basis().taskId() == null) store.link(c, task);
            store.finish(c, "DONE", result.reply().isBlank() ? "已记录你的规划要求，将继续核对缺项并生成候选。" : result.reply(), null, ""); return;
        }
        if(!result.advice().isEmpty()){if(advice==null)throw new IllegalStateException("ADVICE_NOT_CONFIGURED");advice.capture(c,result.advice());}
        store.finish(c, "DONE", (result.reply().isBlank() ? "你可以继续聊旅行想法，也可以让我开始规划。" : result.reply())+(result.advice().isEmpty()?"":"\n以上仅为分析建议，尚未采纳或修改生效行程。请逐项核对来源和影响后确认。"), null, "");
    }
    /** @param actor 用户 @param c 当前业务基础 @param r 模型参数 @param action 动作 @return 服务端构造的精确确认，不能定位时为空 */
    private Dialogue.Operation prepare(UserIdentity actor, Dialogue.Claim c, DialogueIntent.Result r, DialogueIntent.Action action) {
        UUID target = r.targetId(); String description;
        long preferenceRevision = 0;
        if (action == DialogueIntent.Action.ACCEPT && c.basis().taskId() != null) {
            var metadata = json.decode(c.context(), java.util.Map.class); Object candidate = metadata.get("candidateId");
            if (!(candidate instanceof String id) || (target != null && !target.toString().equals(id))) return null;
            target = UUID.fromString(id); description = "接受候选 " + target + "，通过当前校验后设为生效行程；过期只重校验，仍需再次接受。";
        } else if (action == DialogueIntent.Action.RESTORE_VERSION && c.basis().taskId() != null && c.basis().currentVersion() != null && target != null) {
            versions.detail(actor, c.basis().taskId(), target); description = "以历史版本 " + target + " 生成恢复候选；不会立即替换当前行程。";
        } else if (action == DialogueIntent.Action.SAVE_PREFERENCE && List.of("pace", "diet", "interests", "transport").contains(r.category()) && !r.value().isBlank()) {
            preferenceRevision = preferences.read(actor).version().revision(); target = UUID.randomUUID();
            description = "保存长期偏好：" + r.category() + "＝" + r.value() + "；适用范围：" + (r.city().isBlank() ? "所有城市" : r.city());
        } else if (action == DialogueIntent.Action.DELETE_PREFERENCE && target != null) {
            var view = preferences.read(actor); UUID id = target;
            var entry = view.entries().stream().filter(p -> p.id().equals(id)).findFirst(); if (entry.isEmpty()) return null;
            preferenceRevision = view.version().revision(); description = "删除长期偏好：" + entry.get().value() + "；后续不再召回。";
        } else return null;
        return new Dialogue.Operation(action, target, description, c.basis(), preferenceRevision, r.category(), r.value(), r.city());
    }
    /** @param actor 认证 @param chat 聊天 @param input 精确确认消息 @param action CONFIRM／WITHDRAW／RETRY @return 最新视图；重试沿用预算，确认必须核对业务版本 */
    public Dialogue.View act(UserIdentity actor, UUID chat, UUID input, String action) {
        require(List.of("CONFIRM", "WITHDRAW", "RETRY").contains(action));
        return transactions.required(() -> {
            var m = store.lockMessage(actor.id(), chat, input, action);
            if (action.equals("WITHDRAW")) { require(!m.state().equals("DONE")); if (!m.state().equals("WITHDRAWN")) store.changeState(actor.id(), chat, input, "WITHDRAWN"); }
            else if (action.equals("RETRY")) { require(List.of("PAUSED", "CLARIFY", "QUEUED", "RUNNING").contains(m.state())); if (List.of("PAUSED", "CLARIFY").contains(m.state())) store.changeState(actor.id(), chat, input, "QUEUED"); }
            else {
                if (m.state().equals("DONE")) return store.read(actor.id(), chat);
                require(m.state().equals("CONFIRM") && m.operation() != null); var op = m.operation();
                var task = store.read(actor.id(), chat).chat().taskId(); require(Objects.equals(task, op.basis().taskId()));
                if (task != null) {
                    var latest = travel.task(new TaskScope(actor.id(), task)).task();
                    require(latest.executionGeneration() == op.basis().generation() && latest.constraintRevision() == op.basis().revision() && Objects.equals(latest.currentVersionId(), op.basis().currentVersion()) && !store.blocked(actor.id(), task));
                }
                // 状态更新与业务操作同事务；失败全部回滚，重复确认只返回首次已提交结果。
                store.changeState(actor.id(), chat, input, "DONE");
                String outcome = switch (op.action()) {
                    case ACCEPT -> {
                        var candidate = itineraries.latest(actor, task); require(candidate != null && candidate.candidate().candidateId().equals(op.targetId()));
                        var v = candidate.candidate().version(); var accepted = itineraries.accept(actor, task, input, op.targetId(), op.basis().currentVersion(), op.basis().revision(), op.basis().generation(), v.payloadHash());
                        yield accepted.status() == com.lawrence.travel.application.port.CandidateStore.Status.ACCEPTED ? "已接受这份候选并设为生效行程。" : "报告已过期，已请求重新校验；完成后仍需你再次接受，目前没有切换生效版本。";
                    }
                    case RESTORE_VERSION -> { versions.restore(actor, task, input, op.targetId(), op.basis().currentVersion(), op.basis().revision(), op.basis().generation()); yield "已请求生成历史恢复候选；完成校验和审核后仍需你接受。"; }
                    case SAVE_PREFERENCE -> { preferences.change(actor, input, op.preferenceRevision(), op.targetId(), "SAVE", op.category(), op.value(), op.city()); yield "已保存你确认的长期偏好。"; }
                    case DELETE_PREFERENCE -> { preferences.change(actor, input, op.preferenceRevision(), op.targetId(), "DELETE", null, null, null); yield "已删除这条长期偏好，后续不再召回。"; }
                    default -> throw new IllegalArgumentException("DIALOGUE_OPERATION_UNSUPPORTED");
                };
                store.operationResult(actor.id(), chat, input, outcome);
            }
            return store.read(actor.id(), chat);
        });
    }
    /** @param user 已通过收件认证的私有用户 @return 当前启用账号的最小身份，不伪造登录名 */
    private UserIdentity actor(UUID user) { return accounts.findById(user).filter(AccountStore.Account::enabled).orElseThrow(() -> new TravelStorageFailure(TravelStorageFailure.Code.NOT_FOUND)).identity(); }
    /** @param condition 应用执行条件，失败保留原文，不包含模型正文 */
    private static void require(boolean condition) { if (!condition) throw new TravelStorageFailure(TravelStorageFailure.Code.STALE_VERSION); }
}
