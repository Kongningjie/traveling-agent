package com.lawrence.travel.infrastructure.interaction;

import com.lawrence.travel.application.interaction.Dialogue;
import com.lawrence.travel.application.port.DialogueStore;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.infrastructure.persistence.control.BudgetMapper;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import com.lawrence.travel.infrastructure.travel.TravelMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 聊天和理解请求共用PostgreSQL事务；加锁从用户到任务，不与候选接受形成反向锁序。 */
public final class PostgresDialogueStore implements DialogueStore {
    private final DialogueMapper mapper;
    private final TravelMapper tasks;
    private final BudgetMapper budgets;
    private final TravelJson json;
    /** @param mapper 聊天SQL @param tasks 权威任务SQL @param budgets 原预算 @param json 严格JSON */
    public PostgresDialogueStore(DialogueMapper mapper, TravelMapper tasks, BudgetMapper budgets, TravelJson json) {
        this.mapper = mapper; this.tasks = tasks; this.budgets = budgets; this.json = json;
    }
    /** {@inheritDoc} 同会话ID异标题拒绝，不重置预算。 */
    @Override public Dialogue.Chat create(UUID user, UUID id, String title) {
        transaction(); require(id != null && title != null && !title.isBlank() && title.length() <= 120, false);
        require(mapper.lockUser(user) != null, true); var old = mapper.chat(user, id);
        if (old != null) { require(old.title().equals(title), false); return old; }
        budgets.create(id); mapper.create(user, id, title); return mapper.chat(user, id);
    }
    /** {@inheritDoc} 有界分页。 */
    @Override public List<Dialogue.Chat> list(UUID user, int offset) { require(offset >= 0 && offset <= 100000, false); return safe(() -> mapper.list(user, offset)); }
    /** {@inheritDoc} 同用户串行关联，不因前端重试生成第二个聊天或预算。 */
    @Override public Dialogue.Chat attach(UUID user, UUID id, UUID task) {
        transaction(); require(mapper.lockUser(user) != null, true); var row = tasks.lockTask(new TaskScope(user, task)); require(row != null, true);
        var old = mapper.forTask(user, task); if (old != null) return old;
        var chat = create(user, id, row.title()); require(chat.taskId() == null || chat.taskId().equals(task), false);
        if (chat.taskId() == null) {
            var c = new Dialogue.Claim(user, id, id, 0, id, "", java.time.Instant.EPOCH, basis(user, chat), "{}"); require(mapper.link(c, task) == 1, false);
        }
        return mapper.chat(user, id);
    }
    /** {@inheritDoc} 不返回其他用户的聊天存在性。 */
    @Override public Dialogue.View read(UUID user, UUID chat) {
        return safe(() -> {
        var c = mapper.chat(user, chat); require(c != null, true);
        return new Dialogue.View(c, mapper.messages(user, chat).stream().map(this::message).toList(), c.taskId() != null && blocked(user, c.taskId()));
        });
    }
    /** {@inheritDoc} 用户锁串行收件与接受；普通输入不递增旅行代次。 */
    @Override public Dialogue.View receive(UUID user, UUID chat, UUID id, String text) {
        transaction(); lock(user, chat); require(id != null && text != null && !text.isBlank() && text.length() <= 4000, false);
        var replay = mapper.message(user, chat, id);
        if (replay != null) { require(replay.text().equals(text), false); return read(user, chat); }
        require(mapper.messages(user, chat).stream().filter(m -> List.of("QUEUED", "RUNNING", "PAUSED", "CLARIFY", "CONFIRM").contains(m.state())).count() < 20, false);
        mapper.insert(user, chat, id, text);
        return read(user, chat);
    }
    /** {@inheritDoc} 同聊天串行，领取使用用户锁与输入租约，恢复不创建新的预算身份。 */
    @Override public Optional<Dialogue.Claim> claim() {
        transaction();
        for (var hint : mapper.hints()) {
            var chat = lock(hint.userId(), hint.id());
            var row = mapper.next(hint.userId(), hint.id()); if (row == null) continue;
            mapper.settle(hint.userId(), hint.id(), row.id(), false, false); mapper.cap(hint.userId(), hint.id());
            var token = mapper.lease(hint.userId(), hint.id(), row.id()); if (token == null) continue;
            var basis = basis(hint.userId(), chat); var context = new LinkedHashMap<String, Object>();
            // 只保留本轮之前的少量对话，不把后到消息当作本轮授权依据。
            var history = mapper.messages(hint.userId(), hint.id()); int index = 0;
            while (index < history.size() && !history.get(index).id().equals(row.id())) index++;
            context.put("history", history.subList(Math.max(0, index - 4), index).stream().map(m -> java.util.Map.of("user", shortText(m.text(), 300), "assistant", shortText(m.reply(), 300))).toList());
            context.put("unresolved", history.subList(0, index).stream().filter(m -> m.state().equals("CLARIFY")).limit(5).map(m -> java.util.Map.of("id", m.id().toString(), "text", m.text(), "question", m.reply())).toList());
            if (chat.taskId() != null) {
                var scope = new TaskScope(hint.userId(), chat.taskId());
                context.put("requirements", json.decode(tasks.revision(scope, basis.revision()), Object.class));
                var candidateId = mapper.candidate(hint.userId(), chat.taskId()); if (candidateId != null) context.put("candidateId", candidateId.toString());
                if (basis.currentVersion() != null) context.put("itinerary", json.decode(tasks.snapshot(scope, basis.currentVersion()).payload(), Object.class));
            }
            String data = json.encode(context);
            // 不能截断JSON造成语义错乱；大行程降为需求＋说明，具体对象由用户定位。
            if (data.length() > 14000) { context.remove("itinerary"); context.put("itineraryOmitted", true); data = json.encode(context); }
            if (data.length() > 14000) data = "{\"contextOmitted\":true}";
            return Optional.of(new Dialogue.Claim(hint.userId(), chat.id(), row.id(), token, chat.id(), row.text(), row.receivedAt(), basis, data));
        }
        return Optional.empty();
    }
    /** {@inheritDoc} 业务版本改变后拒绝旧理解；后续由应用标记澄清，不强行合并旧上下文。 */
    @Override public boolean guard(Dialogue.Claim claim) { transaction(); var chat = lock(claim.userId(), claim.chatId()); return mapper.live(claim) && Objects.equals(basis(claim.userId(), chat), claim.basis()); }
    /** {@inheritDoc} 保留旧原文和提问，状态变化与本轮处理同事务；不允许清理失败或其他聊天请求。 */
    @Override public void resolve(Dialogue.Claim claim, List<UUID> inputs) {
        transaction();
        for (UUID id : inputs) { var old = mapper.message(claim.userId(), claim.chatId(), id); require(old != null && old.state().equals("CLARIFY") && !id.equals(claim.messageId()), false); mapper.state(claim.userId(), claim.chatId(), id, "DONE"); }
    }
    /** {@inheritDoc} 一个聊天只能关联一个任务。 */
    @Override public Dialogue.Chat link(Dialogue.Claim claim, UUID task) { transaction(); require(mapper.link(claim, task) == 1, false); return mapper.chat(claim.userId(), claim.chatId()); }
    /** {@inheritDoc} CAS同时检查租约有效期，迟到结果不得清除屏障。 */
    @Override public void finish(Dialogue.Claim claim, String state, String reply, Dialogue.Operation operation, String error) {
        transaction(); require(List.of("DONE", "CONFIRM", "CLARIFY", "PAUSED").contains(state), false);
        require(mapper.live(claim), false); mapper.settle(claim.userId(), claim.chatId(), claim.messageId(), true, false);
        require(mapper.finish(claim, state, reply, operation == null ? null : json.encode(operation), error) == 1, false);
    }
    /** {@inheritDoc} 用户和任务锁保护操作确认与候选接受的原子性。 */
    @Override public Dialogue.Message lockMessage(UUID user, UUID chat, UUID message, String action) { transaction(); lock(user, chat); var row = mapper.message(user, chat, message); require(row != null, true); return message(row); }
    /** {@inheritDoc} 重试只改队列尝试，不重置execution_budget或未知用量。 */
    @Override public void changeState(UUID user, UUID chat, UUID message, String state) { transaction(); require(List.of("DONE", "WITHDRAWN", "QUEUED").contains(state), false); if (state.equals("WITHDRAWN")) mapper.settle(user, chat, message, true, true); require(mapper.state(user, chat, message, state) == 1, false); }
    /** {@inheritDoc} 用作任务锁内的接受门禁及对外展示屏障。 */
    @Override public boolean blocked(UUID user, UUID task) { return mapper.blocked(user, task); }
    /** {@inheritDoc} 只保存应用服务确认的结果，不接收模型自行声称的执行成功。 */
    @Override public void operationResult(UUID user, UUID chat, UUID message, String reply) { transaction(); require(mapper.operationResult(user, chat, message, reply) == 1, false); }
    /** @param user 用户 @param chat 聊天 @return 权威当前基础 */
    private Dialogue.Basis basis(UUID user, Dialogue.Chat chat) {
        if (chat.taskId() == null) return new Dialogue.Basis(null, null, 0, 0, null);
        var task = tasks.task(new TaskScope(user, chat.taskId())); require(task != null, true);
        var run = mapper.run(user, chat.taskId());
        return new Dialogue.Basis(chat.taskId(), run == null ? null : UUID.fromString(run), task.executionGeneration(), task.constraintRevision(), task.currentVersionId());
    }
    /** @param user 用户 @param chat 聊天 @return 已按统一锁序保护的关联，不允许适配器绕过事务 */
    private Dialogue.Chat lock(UUID user, UUID chat) {
        require(mapper.lockUser(user) != null, true); var result = mapper.chat(user, chat); require(result != null, true);
        if (result.taskId() != null) require(tasks.lockTask(new TaskScope(user, result.taskId())) != null, true);
        return result;
    }
    /** @param row 数据行 @return 不暴露租约内部信息的界面消息 */
    private Dialogue.Message message(DialogueMapper.Row row) { return new Dialogue.Message(row.id(), row.text(), row.state(), row.reply(), row.operation() == null ? null : json.decode(row.operation(), Dialogue.Operation.class), row.error()); }
    /** @param text 历史 @param maximum 显示上限 @return 有界历史片段，原文仍完整保存 */
    private static String shortText(String text, int maximum) { return text.length() <= maximum ? text : text.substring(0, maximum); }
    /** 必须参加应用短事务，禁止隐式自动提交产生部分结果。 */
    private static void transaction() { if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("TRANSACTION_REQUIRED"); }
    /** @param condition 检查 @param missing 是否归属不存在，错误不能包含私有正文 */
    private static void require(boolean condition, boolean missing) { if (!condition) throw new TravelStorageFailure(missing ? TravelStorageFailure.Code.NOT_FOUND : TravelStorageFailure.Code.STALE_VERSION); }
    /** @param work 只读查询 @param <T> 结果类型 @return 固定依赖错误，不让SQL和用户原文进入HTTP异常日志 */
    private static <T> T safe(java.util.function.Supplier<T> work) { try { return work.get(); } catch (org.springframework.dao.DataAccessException failure) { throw new TravelStorageFailure(TravelStorageFailure.Code.DEPENDENCY_UNAVAILABLE); } }
}
