package com.lawrence.travel.infrastructure.interaction;

import com.lawrence.travel.application.interaction.Dialogue;
import com.lawrence.travel.application.port.DialogueStore;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.infrastructure.persistence.control.BudgetMapper;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import com.lawrence.travel.infrastructure.travel.TravelMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 聊天和理解请求共用PostgreSQL事务；加锁从用户到任务，不与候选接受形成反向锁序。 */
public final class PostgresDialogueStore implements DialogueStore {
    /** {@inheritDoc} 与停止命令共用归属锁，不能定位其他聊天的运行。 */
    @Override public UUID currentRun(UUID user,UUID chat) { transaction(); return basis(user,lock(user,chat)).runId(); }
    /** {@inheritDoc} 用户锁使停止命令的幂等判断和副作用同事务。 */
    @Override public boolean containsInput(UUID user,UUID chat,UUID input,String text) { transaction(); lock(user,chat); var row=mapper.message(user,chat,input); if(row!=null) require(row.text().equals(text),false); return row!=null; }
    /** {@inheritDoc} 原文及已生效行程保留，只撤销未完成执行权。 */
    @Override public void stopPending(UUID user,UUID chat) { transaction(); lock(user,chat); jobs.invalidate(new com.lawrence.travel.application.execution.Jobs.Scope(user,chat)); }
    /** {@inheritDoc} 直接命令与回复、作业完成同事务，不借此接受候选或新增授权。 */
    @Override public void completeCommand(UUID user,UUID chat,UUID input,String reply) { transaction(); lock(user,chat); jobs.completeCommand(new com.lawrence.travel.application.execution.Jobs.Scope(user,chat),jobId(user,chat,input)); require(mapper.directReply(user,chat,input,reply)==1,false); }
    private final DialogueMapper mapper;
    private final TravelMapper tasks;
    private final BudgetMapper budgets;
    private final TravelJson json;
    private final com.lawrence.travel.application.port.JobStore jobs;
    /** @param mapper 聊天SQL @param tasks 权威任务SQL @param budgets 原预算 @param json 严格JSON */
    public PostgresDialogueStore(DialogueMapper mapper, TravelMapper tasks, BudgetMapper budgets, TravelJson json, com.lawrence.travel.application.port.JobStore jobs) {
        this.mapper = mapper; this.tasks = tasks; this.budgets = budgets; this.json = json; this.jobs = jobs;
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
        ensureJob(user, chat, id, false);
        return read(user, chat);
    }
    /** {@inheritDoc} 同聊天串行，领取使用用户锁与输入租约，恢复不创建新的预算身份。 */
    @Override public Optional<Dialogue.Claim> claim() {
        return claimExcluding(java.util.Set.of());
    }

    /** {@inheritDoc} 在取得用户锁之前排除物理忙用户，按持久轮转顺序发现。 */
    @Override
    public Optional<Dialogue.Claim> claimExcluding(java.util.Set<UUID> excluded) {
        transaction();
        for (var hint : mapper.hints()) {
            if (excluded.contains(hint.userId())) continue;
            var chat = lock(hint.userId(), hint.id());
            var row = mapper.next(hint.userId(), hint.id()); if (row == null) continue;
            if (mapper.legacyRunning(hint.userId(), hint.id(), row.id())) continue;
            UUID jobId = ensureJob(hint.userId(), hint.id(), row.id(), true);
            var work = jobs.claim(new com.lawrence.travel.application.execution.Jobs.Scope(hint.userId(), hint.id()), jobId);
            if (work.isEmpty()) {
                var state = jobs.read(new com.lawrence.travel.application.execution.Jobs.Scope(hint.userId(), hint.id()), jobId).state();
                if (state == com.lawrence.travel.domain.execution.BackgroundJob.State.PAUSED) mapper.project(hint.userId(), hint.id(), row.id(), jobId);
                continue;
            }
            mapper.project(hint.userId(), hint.id(), row.id(), jobId);
            long token = work.get().fence().token();
            var basis = basis(hint.userId(), chat); var context = new LinkedHashMap<String, Object>();
            // 权威问题按本轮水位读取；完整历史由统一SessionContextService装载。
            context.put("unresolved", mapper.contextQuestions(hint.userId(),hint.id(),row.id()).stream().map(m -> java.util.Map.of("id",m.id().toString(),"text",m.text(),"question",m.reply())).toList());
            if (chat.taskId() != null) {
                var scope = new TaskScope(hint.userId(), chat.taskId());
                context.put("requirements", json.decode(tasks.revision(scope, basis.revision()), Object.class));
                var candidateId = mapper.candidate(hint.userId(), chat.taskId()); if (candidateId != null) context.put("candidateId", candidateId.toString());
                if (basis.currentVersion() != null) context.put("itinerary", json.decode(tasks.snapshot(scope, basis.currentVersion()).payload(), Object.class));
            }
            String data = json.encode(context);
            return Optional.of(new Dialogue.Claim(hint.userId(), chat.id(), row.id(), token, work.get().job().budgetId(), row.text(), row.receivedAt(), basis, data));
        }
        return Optional.empty();
    }
    /** {@inheritDoc} 业务版本改变后拒绝旧理解；后续由应用标记澄清，不强行合并旧上下文。 */
    @Override public boolean guard(Dialogue.Claim claim) { transaction(); var chat = lock(claim.userId(), claim.chatId()); return jobs.live(jobClaim(claim).fence()) && mapper.live(claim) && Objects.equals(basis(claim.userId(), chat), claim.basis()); }
    /** {@inheritDoc} 保留旧原文和提问，状态变化与本轮处理同事务；不允许清理失败或其他聊天请求。 */
    @Override public void resolve(Dialogue.Claim claim, List<UUID> inputs) {
        transaction();
        for (UUID id : inputs) { var old = mapper.message(claim.userId(), claim.chatId(), id); require(old != null && old.state().equals("CLARIFY") && !id.equals(claim.messageId()), false); changeState(claim.userId(), claim.chatId(), id, "DONE"); }
    }
    /** {@inheritDoc} 明确确认的新任务关联不使用虚构Claim，不替换旧旅行。 */
    @Override public void linkConfirmed(UUID user,UUID chat,UUID task){transaction();lock(user,chat);require(tasks.task(new TaskScope(user,task))!=null,true);require(mapper.linkConfirmed(user,chat,task)==1,false);}
    /** {@inheritDoc} 一个聊天只能关联一个任务。 */
    @Override public Dialogue.Chat link(Dialogue.Claim claim, UUID task) { transaction(); require(mapper.link(claim, task) == 1, false); return mapper.chat(claim.userId(), claim.chatId()); }
    /** {@inheritDoc} CAS同时检查租约有效期，迟到结果不得清除屏障。 */
    @Override public void finish(Dialogue.Claim claim, String state, String reply, Dialogue.Operation operation, String error) {
        transaction(); require(List.of("DONE", "CONFIRM", "CLARIFY", "PAUSED").contains(state), false);
        require(mapper.live(claim), false);
        var target = switch (state) { case "DONE" -> com.lawrence.travel.domain.execution.BackgroundJob.State.COMPLETED; case "PAUSED" -> com.lawrence.travel.domain.execution.BackgroundJob.State.PAUSED; default -> com.lawrence.travel.domain.execution.BackgroundJob.State.WAITING_USER; };
        jobs.finish(jobClaim(claim), new com.lawrence.travel.application.execution.Jobs.Output(target, null, List.of(), error));
        require(mapper.finish(claim, state, reply, operation == null ? null : json.encode(operation), error) == 1, false);
    }
    /** {@inheritDoc} 用户和任务锁保护操作确认与候选接受的原子性。 */
    @Override public Dialogue.Message lockMessage(UUID user, UUID chat, UUID message, String action) { transaction(); lock(user, chat); var row = mapper.message(user, chat, message); require(row != null, true); return message(row); }
    /** {@inheritDoc} 重试只改队列尝试，不重置execution_budget或未知用量。 */
    @Override public void changeState(UUID user, UUID chat, UUID message, String state) {
        transaction(); lock(user, chat); require(List.of("DONE", "WITHDRAWN", "QUEUED").contains(state), false);
        UUID id = jobId(user, chat, message);
        if (id != null) { var scope = new com.lawrence.travel.application.execution.Jobs.Scope(user, chat); if (state.equals("DONE")) jobs.acknowledge(scope, id); else jobs.control(scope, id, state.equals("QUEUED")); }
        else if (state.equals("WITHDRAWN")) mapper.settle(user, chat, message, true, true);
        require(mapper.state(user, chat, message, state) == 1, false);
    }
    /** {@inheritDoc} 同事务续租并刷新投影，失效时不延长任何租约。 */
    @Override public boolean renew(Dialogue.Claim c) {
        transaction(); lock(c.userId(), c.chatId()); boolean renewed = jobs.renew(jobClaim(c).fence());
        if (renewed) mapper.project(c.userId(), c.chatId(), c.messageId(), jobId(c.userId(), c.chatId(), c.messageId())); return renewed;
    }
    /** {@inheritDoc} 只读取已鉴权范围，不把用户原文或认证信息放进关联。 */
    @Override public com.lawrence.travel.application.execution.ExecutionCorrelation.Ids correlation(Dialogue.Claim c) {
        var job = jobs.read(new com.lawrence.travel.application.execution.Jobs.Scope(c.userId(), c.chatId()), jobId(c.userId(), c.chatId(), c.messageId()));
        return new com.lawrence.travel.application.execution.ExecutionCorrelation.Ids(job.requestId(), job.traceId());
    }
    /** @param user 归属 @param chat 聊天 @param input 输入 @return 可空映射；MyBatis标量UUID通过显式文本转换 */
    private UUID jobId(UUID user, UUID chat, UUID input) { String id=mapper.job(user,chat,input); return id==null ? null : UUID.fromString(id); }
    /** @param c 既有聊天资格 @return 当前作业元数据与原token组合，不能用读取到的新token替换旧资格 */
    private com.lawrence.travel.application.execution.Jobs.Claim jobClaim(Dialogue.Claim c) {
        var scope = new com.lawrence.travel.application.execution.Jobs.Scope(c.userId(), c.chatId());
        var job = jobs.read(scope, jobId(c.userId(), c.chatId(), c.messageId()));
        require(job.budgetId().equals(c.budgetId()), false);
        return new com.lawrence.travel.application.execution.Jobs.Claim(new com.lawrence.travel.application.execution.Jobs.Fence(scope, job.id(), c.token(), job.version()), job, "{}");
    }
    /** @param user 归属 @param chat 聊天 @param input 输入 @param legacy 是否旧待处理输入 @return 稳定映射；旧完成消息不进入此路径 */
    private UUID ensureJob(UUID user, UUID chat, UUID input, boolean legacy) {
        UUID old = jobId(user, chat, input); if (old != null) return old;
        // 旧在途请求先保守结算到已保存租约；复制原累计预算不删除旧计量或UNKNOWN。
        if (legacy) mapper.settle(user, chat, input, true, false);
        UUID operation = UUID.nameUUIDFromBytes((user+"/"+chat+"/"+input).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var job = jobs.submit(new com.lawrence.travel.application.execution.Jobs.Submit(new com.lawrence.travel.application.execution.Jobs.Scope(user, chat), operation, com.lawrence.travel.domain.execution.BackgroundJob.Kind.CHAT_INPUT, "input", json.encode(java.util.Map.of("inputId", input)), null, legacy ? chat : null, legacy ? mapper.priorAttempts(user, chat, input) : 0));
        mapper.associate(user, chat, input, job.id()); return job.id();
    }
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
        require(mapper.lockUser(user) != null, true); var result = mapper.lockChat(user, chat); require(result != null, true);
        if (result.taskId() != null) require(tasks.lockTask(new TaskScope(user, result.taskId())) != null, true);
        return result;
    }
    /** @param row 数据行 @return 不暴露租约内部信息的界面消息 */
    private Dialogue.Message message(DialogueMapper.Row row) { return new Dialogue.Message(row.id(), row.text(), row.state(), row.reply(), row.operation() == null ? null : json.decode(row.operation(), Dialogue.Operation.class), row.error()); }
    /** 必须参加应用短事务，禁止隐式自动提交产生部分结果。 */
    private static void transaction() { if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("TRANSACTION_REQUIRED"); }
    /** @param condition 检查 @param missing 是否归属不存在，错误不能包含私有正文 */
    private static void require(boolean condition, boolean missing) { if (!condition) throw new TravelStorageFailure(missing ? TravelStorageFailure.Code.NOT_FOUND : TravelStorageFailure.Code.STALE_VERSION); }
    /** @param work 只读查询 @param <T> 结果类型 @return 固定依赖错误，不让SQL和用户原文进入HTTP异常日志 */
    private static <T> T safe(java.util.function.Supplier<T> work) { try { return work.get(); } catch (org.springframework.dao.DataAccessException failure) {
            com.lawrence.travel.infrastructure.execution.AdmissionErrors.rethrowKnown(failure); throw new TravelStorageFailure(TravelStorageFailure.Code.DEPENDENCY_UNAVAILABLE); } }
}
