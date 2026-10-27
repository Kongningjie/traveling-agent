package com.lawrence.travel.application.interaction;

import com.lawrence.travel.application.execution.RunService;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.NegotiationStore;
import com.lawrence.travel.application.port.TravelStore;
import com.lawrence.travel.application.travel.TravelStorageService;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.domain.execution.StageType;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.travel.TaskScope;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 聊天规划意图进入旅行业务的应用入口，同时承接用户问题卡片及自由回答。
 *
 * <p>DialogueService.route把已验证facts交给applyInterpreted；本类在短事务中创建/关联旅行
 * 或登记变更，通过原ExecutionEngineStore发起UNDERSTAND阶段。字段值仍需领域合并与
 * 阶段提交校验，模型reply不能作为已保存需求的证据。新修改与旧执行失效遵循原引擎，
 * 不另建后台队列；等待用户时保存问题卡片并释放执行线程。
 */
public final class NegotiationService {
    /**
     * 接收聊天已完成模型理解的规划字段，事务内登记为旅行协商输入并衔接原阶段引擎。
     *
     * <p>首次明确规划时创建真实任务；已有任务登记变更并处理旧运行资格。INTERPRETED输入保留
     * 原文和已校验facts，NegotiationHandler不会再为同一份输入重复调用提取模型。
     * 本方法返回关联任务身份，不代表行程已生成、校验通过或被用户接受。
     *
     * @param actor 受信用户
     * @param taskId 已关联任务，可空
     * @param input 原聊天输入
     * @param text 完整原文
     * @param facts 已核实提取项
     * @return 唯一关联任务；新建、变更失效及已解释标记同事务，既有有效问题内补充沿用原预算
     */
    public UUID applyInterpreted(
            UserIdentity actor,
            UUID taskId,
            UUID input,
            String text,
            List<Negotiation.Fact> facts) {
        return transactions.required(
                () -> {
                    UUID task = taskId;
                    if (task == null) {
                        String title = text.substring(0, Math.min(60, text.length()));
                        task =
                                travel.create(
                                                actor,
                                                input.toString(),
                                                title,
                                                new TravelStore.RevisionContent(
                                                        List.of(), List.of(), "用户开始规划", ""))
                                        .task()
                                        .scope()
                                        .taskId();
                    }
                    var scope = new TaskScope(actor.id(), task);
                    var current = store.card(scope);
                    var fields =
                            facts.stream()
                                    .map(Negotiation.Fact::field)
                                    .collect(java.util.stream.Collectors.toSet());
                    var allowed =
                            current == null
                                    ? java.util.Set.<String>of()
                                    : current.specs().values().stream()
                                            .map(Negotiation.QuestionSpec::field)
                                            .collect(java.util.stream.Collectors.toSet());
                    if (current != null && !facts.isEmpty() && allowed.containsAll(fields)) {
                        answer(
                                actor,
                                task,
                                input,
                                new Negotiation.Response(
                                        current.decision().id(),
                                        current.decision().revision(),
                                        current.decision().constraintRevision(),
                                        "ANSWER",
                                        java.util.Map.of(),
                                        text,
                                        java.util.Set.of()));
                    } else sendInternal(actor, scope, input, text, facts);
                    store.interpreted(scope, input, facts);
                    return task;
                });
    }

    /**
     * @param actor 明确确认的用户
     * @param taskId 当前旅行，可空
     * @param input 建议稳定ID
     * @param text 确认后的规范原文
     * @param facts 已校验字段
     * @return 原变更流程的唯一任务；不将采纳猜成旧问题答案，也不直接提交行程
     */
    public UUID applyAdvice(
            UserIdentity actor,
            UUID taskId,
            UUID input,
            String text,
            List<Negotiation.Fact> facts) {
        return transactions.required(
                () -> {
                    UUID task = taskId;
                    if (task == null)
                        task =
                                travel.create(
                                                actor,
                                                input.toString(),
                                                "从旅行资料开始规划",
                                                new TravelStore.RevisionContent(
                                                        List.of(), List.of(), "用户明确采纳建议", "尚未形成行程"))
                                        .task()
                                        .scope()
                                        .taskId();
                    var scope = new TaskScope(actor.id(), task);
                    sendInternal(actor, scope, input, text, facts);
                    store.interpreted(scope, input, facts);
                    return task;
                });
    }

    private final NegotiationStore store;
    private final TravelStorageService travel;
    private final RunService runs;
    private final ExecutionEngineStore engine;
    private final BusinessTransactions transactions;

    /**
     * @param store 收件箱
     * @param travel 任务用例
     * @param runs 运行用例
     * @param engine 继续执行端口
     * @param transactions 业务事务
     */
    public NegotiationService(
            NegotiationStore store,
            TravelStorageService travel,
            RunService runs,
            ExecutionEngineStore engine,
            BusinessTransactions transactions) {
        this.store = store;
        this.travel = travel;
        this.runs = runs;
        this.engine = engine;
        this.transactions = transactions;
    }

    /**
     * @param actor 认证用户
     * @param request 幂等创建键
     * @param title 旅行名
     * @param text 初始原文，可空字符串
     * @param facts 明确输入
     * @return 初次创建结果；同键异文冲突
     */
    public Created create(
            UserIdentity actor,
            UUID request,
            String title,
            String text,
            List<Negotiation.Fact> facts) {
        require(actor != null && request != null && title != null && title.length() <= 120);
        return transactions.required(
                () -> {
                    var task =
                            travel.create(
                                    actor,
                                    request.toString(),
                                    title,
                                    new TravelStore.RevisionContent(
                                            List.of(), List.of(), "用户创建", ""));
                    UUID input =
                            UUID.nameUUIDFromBytes(
                                    (actor.id() + ":" + request + ":initial")
                                            .getBytes(StandardCharsets.UTF_8));
                    var receipt = sendInternal(actor, task.task().scope(), input, text, facts);
                    return new Created(task.task().scope().taskId(), receipt);
                });
    }

    /**
     * @param taskId 私有任务
     * @param receipt 收到但尚未采纳的稳定结果
     */
    public record Created(UUID taskId, Negotiation.Receipt receipt) {}

    /**
     * @param actor 认证
     * @param taskId 任务
     * @param input 输入身份
     * @param text 自由修改
     * @param facts 明确字段
     * @return 收讫，后续阶段负责解释
     */
    public Negotiation.Receipt send(
            UserIdentity actor,
            UUID taskId,
            UUID input,
            String text,
            List<Negotiation.Fact> facts) {
        require(
                actor != null
                        && text != null
                        && facts != null
                        && (!text.isBlank() || !facts.isEmpty()));
        return transactions.required(
                () -> sendInternal(actor, new TaskScope(actor.id(), taskId), input, text, facts));
    }

    /**
     * @param actor 认证
     * @param scope 任务
     * @param input 输入
     * @param text 原文
     * @param facts 结构化字段
     * @return 同一事务的可靠收件与唯一新运行
     */
    private Negotiation.Receipt sendInternal(
            UserIdentity actor,
            TaskScope scope,
            UUID input,
            String text,
            List<Negotiation.Fact> facts) {
        if (!store.receiveChange(scope, input, text, facts)) return store.receipt(scope, input);
        var run =
                runs.start(
                        actor,
                        scope.taskId(),
                        input,
                        NegotiationHandler.stage(input, StageType.UNDERSTAND));
        var result = new Negotiation.Receipt(input, "RECEIVED", run.id());
        store.saveReceipt(scope, result);
        return result;
    }

    /**
     * @param actor 认证
     * @param taskId 任务
     * @param input 回答幂等身份
     * @param response 明确回答
     * @return 仅接收或仅记录；不会把预选推荐当已采纳
     */
    public Negotiation.Receipt answer(
            UserIdentity actor, UUID taskId, UUID input, Negotiation.Response response) {
        require(actor != null && response != null && input != null);
        var scope = new TaskScope(actor.id(), taskId);
        return transactions.required(
                () -> {
                    var card =
                            store.receiveAnswer(
                                    scope,
                                    input,
                                    response.decisionId(),
                                    response.revision(),
                                    response.constraints(),
                                    response.action(),
                                    response.selected(),
                                    response.text(),
                                    response.authorization());
                    var replay = store.receipt(scope, input);
                    if (replay != null) return replay;
                    boolean recordOnly =
                            response.action().equals("REJECT")
                                    || response.action().equals("UNDECIDED");
                    if (!recordOnly)
                        engine.interpretAnswer(
                                scope,
                                card.decision().runId(),
                                input,
                                NegotiationHandler.stage(input, StageType.UNDERSTAND));
                    var result =
                            new Negotiation.Receipt(
                                    input,
                                    recordOnly ? "RECORDED" : "RECEIVED",
                                    card.decision().runId());
                    store.saveReceipt(scope, result);
                    return result;
                });
    }

    /**
     * @param actor 认证
     * @param taskId 任务
     * @return 当前有效问题，可能尚未产生
     */
    public Negotiation.Card card(UserIdentity actor, UUID taskId) {
        require(actor != null);
        return store.card(new TaskScope(actor.id(), taskId));
    }

    /**
     * @param actor 认证
     * @param taskId 任务
     * @return 最近输入及处理状态
     */
    public List<Negotiation.Message> messages(UserIdentity actor, UUID taskId) {
        require(actor != null);
        return store.messages(new TaskScope(actor.id(), taskId));
    }

    /**
     * @param condition 输入完整性，固定错误不包含正文
     */
    private static void require(boolean condition) {
        if (!condition) throw new TravelStorageFailure(TravelStorageFailure.Code.INVALID_INPUT);
    }
}
