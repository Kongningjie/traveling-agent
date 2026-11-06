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

/**
 * 保存和读取聊天消息，领取后台作业，提交回复。
 */
public final class PostgresDialogueStore implements DialogueStore {
    /** {@inheritDoc} 与停止命令共用归属锁，不能定位其他聊天的运行。 */
    @Override
    public UUID currentRun(UUID userId, UUID chatId) {
        requireActiveTransaction();
        return getTravelExecutionSnapshot(userId, lockUserChatAndTask(userId, chatId)).runId();
    }

    /** {@inheritDoc} 用户锁使停止命令的幂等判断和副作用同事务。 */
    @Override
    public boolean containsInput(UUID userId, UUID chatId, UUID inputId, String text) {
        requireActiveTransaction();
        lockUserChatAndTask(userId, chatId);
        var row = dialogueMapper.getChatInputById(userId, chatId, inputId);
        if (row != null) requireStorageCondition(row.text().equals(text), false);
        return row != null;
    }

    /** {@inheritDoc} 原文及已生效行程保留，只撤销未完成执行权。 */
    @Override
    public void stopPending(UUID userId, UUID chatId) {
        requireActiveTransaction();
        lockUserChatAndTask(userId, chatId);
        jobStore.invalidate(
                new com.lawrence.travel.application.execution.Jobs.Scope(userId, chatId));
    }

    /** {@inheritDoc} 直接命令与回复、作业完成同事务，不借此接受候选或新增授权。 */
    @Override
    public void completeCommand(UUID userId, UUID chatId, UUID inputId, String reply) {
        requireActiveTransaction();
        lockUserChatAndTask(userId, chatId);
        jobStore.completeCommand(
                new com.lawrence.travel.application.execution.Jobs.Scope(userId, chatId),
                getChatInputJobId(userId, chatId, inputId));
        requireStorageCondition(
                dialogueMapper.directReply(userId, chatId, inputId, reply) == 1, false);
    }

    private final DialogueMapper dialogueMapper;
    private final TravelMapper taskMapper;
    private final BudgetMapper budgetMapper;
    private final TravelJson interactionCodec;
    private final com.lawrence.travel.application.port.JobStore jobStore;

    /**
     * @param dialogueMapper 聊天SQL
     * @param taskMapper 权威任务SQL
     * @param budgetMapper 原预算
     * @param interactionCodec 严格JSON
     */
    public PostgresDialogueStore(
            DialogueMapper dialogueMapper,
            TravelMapper taskMapper,
            BudgetMapper budgetMapper,
            TravelJson interactionCodec,
            com.lawrence.travel.application.port.JobStore jobStore) {
        this.dialogueMapper = dialogueMapper;
        this.taskMapper = taskMapper;
        this.budgetMapper = budgetMapper;
        this.interactionCodec = interactionCodec;
        this.jobStore = jobStore;
    }

    /** {@inheritDoc} 同会话ID异标题拒绝，不重置预算。 */
    @Override
    public Dialogue.Chat create(UUID userId, UUID chatId, String title) {
        requireActiveTransaction();
        requireStorageCondition(
                chatId != null && title != null && !title.isBlank() && title.length() <= 120,
                false);
        requireStorageCondition(dialogueMapper.lockUser(userId) != null, true);
        var old = dialogueMapper.chat(userId, chatId);
        if (old != null) {
            requireStorageCondition(old.title().equals(title), false);
            return old;
        }
        budgetMapper.create(chatId);
        dialogueMapper.create(userId, chatId, title);
        return dialogueMapper.chat(userId, chatId);
    }

    /** {@inheritDoc} 有界分页。 */
    @Override
    public List<Dialogue.Chat> list(UUID userId, int offset) {
        requireStorageCondition(offset >= 0 && offset <= 100000, false);
        return executeStorageReadWithErrorMapping(() -> dialogueMapper.list(userId, offset));
    }

    /** {@inheritDoc} 同用户串行关联，不因前端重试生成第二个聊天或预算。 */
    @Override
    public Dialogue.Chat attach(UUID userId, UUID chatId, UUID task) {
        requireActiveTransaction();
        requireStorageCondition(dialogueMapper.lockUser(userId) != null, true);
        var row = taskMapper.lockTask(new TaskScope(userId, task));
        requireStorageCondition(row != null, true);
        var old = dialogueMapper.forTask(userId, task);
        if (old != null) return old;
        var chat = create(userId, chatId, row.title());
        requireStorageCondition(chat.taskId() == null || chat.taskId().equals(task), false);
        if (chat.taskId() == null) {
            var chatInputClaim =
                    new Dialogue.Claim(
                            userId,
                            chatId,
                            chatId,
                            0,
                            chatId,
                            "",
                            java.time.Instant.EPOCH,
                            getTravelExecutionSnapshot(userId, chat),
                            "{}");
            requireStorageCondition(dialogueMapper.link(chatInputClaim, task) == 1, false);
        }
        return dialogueMapper.chat(userId, chatId);
    }

    /**
     * DialogueController.read及收件/提交返回路径均使用此投影。
     */
    @Override
    public Dialogue.View read(UUID userId, UUID chatId) {
        return executeStorageReadWithErrorMapping(
                () -> {
                    var c = dialogueMapper.chat(userId, chatId);
                    requireStorageCondition(c != null, true);
                    var rows = dialogueMapper.messages(userId, chatId);
                    var attachments =
                            new java.util.HashMap<UUID, List<Dialogue.DocumentReference>>();
                    if (!rows.isEmpty())
                        for (var reference :
                                dialogueMapper.attachedDocuments(
                                        userId,
                                        chatId,
                                        rows.stream().map(DialogueMapper.Row::id).toList())) {
                            attachments
                                    .computeIfAbsent(
                                            reference.inputId(),
                                            ignored -> new java.util.ArrayList<>())
                                    .add(
                                            new Dialogue.DocumentReference(
                                                    reference.id(),
                                                    reference.name(),
                                                    reference.available()));
                        }
                    return new Dialogue.View(
                            c,
                            rows.stream()
                                    .map(
                                            row ->
                                                    message(
                                                            row,
                                                            attachments.getOrDefault(
                                                                    row.id(), List.of())))
                                    .toList(),
                            c.taskId() != null && blocked(userId, c.taskId()));
                });
    }

    /** {@inheritDoc} 用户锁串行收件与接受；普通输入不递增旅行代次。 */
    @Override
    public Dialogue.View saveChatInputAndEnsureJob(
            UUID userId, UUID chatId, UUID inputId, String text) {
        return saveChatInputAndEnsureJob(userId, chatId, inputId, text, List.of());
    }

    /**
     * 由DialogueService.send的事务调用：锁定归属 → 检查重放原文/附件集合 → 限制未决输入数量 → 插入原文 → 绑定文件内容版本 → ensureJob登记持久作业。
     */
    @Override
    public Dialogue.View saveChatInputAndEnsureJob(
            UUID userId, UUID chatId, UUID inputId, String text, List<UUID> documents) {
        requireActiveTransaction();
        lockUserChatAndTask(userId, chatId);
        requireStorageCondition(
                documents != null
                        && documents.size() <= 10
                        && documents.stream().noneMatch(Objects::isNull)
                        && new java.util.HashSet<>(documents).size() == documents.size(),
                false);
        requireStorageCondition(
                inputId != null
                        && text != null
                        && !text.isBlank()
                        && text.length()
                                <= com.lawrence.travel.domain.interaction.DialogueIntent
                                        .MAX_TEXT_LENGTH
                        && text.indexOf(0) < 0,
                false);
        var existingInput = dialogueMapper.getChatInputById(userId, chatId, inputId);
        if (existingInput != null) {
            requireStorageCondition(
                    existingInput.text().equals(text)
                            && new java.util.HashSet<>(
                                            dialogueMapper
                                                    .documents(userId, chatId, inputId)
                                                    .stream()
                                                    .map(Dialogue.DocumentReference::id)
                                                    .toList())
                                    .equals(new java.util.HashSet<>(documents)),
                    false);
            return read(userId, chatId);
        }
        requireStorageCondition(
                dialogueMapper.messages(userId, chatId).stream()
                                .filter(
                                        m ->
                                                List.of(
                                                                "QUEUED", "RUNNING", "PAUSED",
                                                                "CLARIFY", "CONFIRM")
                                                        .contains(m.state()))
                                .count()
                        < 20,
                false);
        dialogueMapper.insert(userId, chatId, inputId, text);
        for (UUID document : documents)
            requireStorageCondition(
                    dialogueMapper.bindDocument(userId, chatId, inputId, document) == 1, true);
        getOrCreateChatInputJobId(userId, chatId, inputId, false);
        return read(userId, chatId);
    }

    /** {@inheritDoc} 读取当前索引和原绑定版本；删除后只返回墓碑名称。 */
    @Override
    public List<Dialogue.DocumentReference> documents(UUID userId, UUID chatId, UUID inputId) {
        return executeStorageReadWithErrorMapping(
                () -> dialogueMapper.documents(userId, chatId, inputId));
    }

    /** {@inheritDoc} 同聊天串行，领取使用用户锁与输入租约，恢复不创建新的预算身份。 */
    @Override
    public Optional<Dialogue.Claim> claim() {
        return claimNextChatInputExcludingUsers(java.util.Set.of());
    }

    /**
     * 由Worker经DialogueService调用，在短事务中按提示发现聊天，并先排除物理运行尚未退出的用户。
     */
    @Override
    public Optional<Dialogue.Claim> claimNextChatInputExcludingUsers(
            java.util.Set<UUID> excludedUserIds) {
        requireActiveTransaction();
        for (var pendingChatHint : dialogueMapper.listPendingChatHints()) {
            if (excludedUserIds.contains(pendingChatHint.userId())) continue;
            var lockedChat = lockUserChatAndTask(pendingChatHint.userId(), pendingChatHint.id());
            var inputRow =
                    dialogueMapper.getEarliestPendingInputForUpdate(
                            pendingChatHint.userId(), pendingChatHint.id());
            if (inputRow == null) continue;
            if (dialogueMapper.hasUnexpiredLegacyInputExecution(
                    pendingChatHint.userId(), pendingChatHint.id(), inputRow.id())) continue;
            UUID jobId =
                    getOrCreateChatInputJobId(
                            pendingChatHint.userId(), pendingChatHint.id(), inputRow.id(), true);
            var jobClaimResult =
                    jobStore.claim(
                            new com.lawrence.travel.application.execution.Jobs.Scope(
                                    pendingChatHint.userId(), pendingChatHint.id()),
                            jobId);
            if (jobClaimResult.isEmpty()) {
                var jobState =
                        jobStore.read(
                                        new com.lawrence.travel.application.execution.Jobs.Scope(
                                                pendingChatHint.userId(), pendingChatHint.id()),
                                        jobId)
                                .state();
                if (jobState == com.lawrence.travel.domain.execution.BackgroundJob.State.PAUSED)
                    dialogueMapper.updateInputExecutionFromJob(
                            pendingChatHint.userId(), pendingChatHint.id(), inputRow.id(), jobId);
                continue;
            }
            dialogueMapper.updateInputExecutionFromJob(
                    pendingChatHint.userId(), pendingChatHint.id(), inputRow.id(), jobId);
            long leaseToken = jobClaimResult.get().fence().token();
            var travelExecutionSnapshot =
                    getTravelExecutionSnapshot(pendingChatHint.userId(), lockedChat);
            var inputContext = new LinkedHashMap<String, Object>();
            // 权威问题按本轮水位读取；完整历史由统一SessionContextService装载。
            inputContext.put(
                    "unresolved",
                    dialogueMapper
                            .contextQuestions(
                                    pendingChatHint.userId(), pendingChatHint.id(), inputRow.id())
                            .stream()
                            .map(
                                    unresolvedInput ->
                                            java.util.Map.of(
                                                    "id",
                                                    unresolvedInput.id().toString(),
                                                    "text",
                                                    unresolvedInput.text(),
                                                    "question",
                                                    unresolvedInput.reply()))
                            .toList());
            if (lockedChat.taskId() != null) {
                var scope = new TaskScope(pendingChatHint.userId(), lockedChat.taskId());
                inputContext.put(
                        "requirements",
                        interactionCodec.decode(
                                taskMapper.revision(scope, travelExecutionSnapshot.revision()),
                                Object.class));
                var candidateId =
                        dialogueMapper.candidate(pendingChatHint.userId(), lockedChat.taskId());
                if (candidateId != null) inputContext.put("candidateId", candidateId.toString());
                if (travelExecutionSnapshot.currentVersion() != null)
                    inputContext.put(
                            "itinerary",
                            interactionCodec.decode(
                                    taskMapper
                                            .snapshot(
                                                    scope, travelExecutionSnapshot.currentVersion())
                                            .payload(),
                                    Object.class));
            }
            inputContext.put(
                    "selectedDocuments",
                    dialogueMapper.documents(
                            pendingChatHint.userId(), lockedChat.id(), inputRow.id()));
            inputContext.put(
                    "previousDocumentSelections",
                    dialogueMapper
                            .previousDocumentInputs(
                                    pendingChatHint.userId(), lockedChat.id(), inputRow.id())
                            .stream()
                            .map(
                                    previousInputId ->
                                            java.util.Map.of(
                                                    "messageId",
                                                    previousInputId,
                                                    "documents",
                                                    dialogueMapper.documents(
                                                            pendingChatHint.userId(),
                                                            lockedChat.id(),
                                                            UUID.fromString(previousInputId))))
                            .toList());
            String contextJson = interactionCodec.encode(inputContext);
            return Optional.of(
                    new Dialogue.Claim(
                            pendingChatHint.userId(),
                            lockedChat.id(),
                            inputRow.id(),
                            leaseToken,
                            jobClaimResult.get().job().budgetId(),
                            inputRow.text(),
                            inputRow.receivedAt(),
                            travelExecutionSnapshot,
                            contextJson));
        }
        return Optional.empty();
    }

    /**
     * 由process提交前调用，同时比较JobStore围栏、输入有效状态及旅行Basis。
     */
    @Override
    public boolean isChatInputExecutionValid(Dialogue.Claim chatInputClaim) {
        requireActiveTransaction();
        var chat = lockUserChatAndTask(chatInputClaim.userId(), chatInputClaim.chatId());
        return jobStore.isJobExecutionValid(buildJobClaimFromChatInput(chatInputClaim).fence())
                && dialogueMapper.live(chatInputClaim)
                && Objects.equals(
                        getTravelExecutionSnapshot(chatInputClaim.userId(), chat),
                        chatInputClaim.basis());
    }

    /** {@inheritDoc} 保留旧原文和提问，状态变化与本轮处理同事务；不允许清理失败或其他聊天请求。 */
    @Override
    public void resolve(Dialogue.Claim chatInputClaim, List<UUID> inputs) {
        requireActiveTransaction();
        for (UUID inputId : inputs) {
            var old =
                    dialogueMapper.getChatInputById(
                            chatInputClaim.userId(), chatInputClaim.chatId(), inputId);
            requireStorageCondition(
                    old != null
                            && old.state().equals("CLARIFY")
                            && !inputId.equals(chatInputClaim.messageId()),
                    false);
            changeState(chatInputClaim.userId(), chatInputClaim.chatId(), inputId, "DONE");
        }
    }

    /** {@inheritDoc} 明确确认的新任务关联不使用虚构Claim，不替换旧旅行。 */
    @Override
    public void linkConfirmed(UUID userId, UUID chatId, UUID task) {
        requireActiveTransaction();
        lockUserChatAndTask(userId, chatId);
        requireStorageCondition(taskMapper.task(new TaskScope(userId, task)) != null, true);
        requireStorageCondition(dialogueMapper.linkConfirmed(userId, chatId, task) == 1, false);
    }

    /** {@inheritDoc} 一个聊天只能关联一个任务。 */
    @Override
    public Dialogue.Chat link(Dialogue.Claim chatInputClaim, UUID task) {
        requireActiveTransaction();
        requireStorageCondition(dialogueMapper.link(chatInputClaim, task) == 1, false);
        return dialogueMapper.chat(chatInputClaim.userId(), chatInputClaim.chatId());
    }

    /**
     * 保存回复和消息状态，同时结束或暂停对应后台作业。
     */
    @Override
    public void finish(
            Dialogue.Claim chatInputClaim,
            String state,
            String reply,
            Dialogue.Operation operation,
            String error) {
        requireActiveTransaction();
        requireStorageCondition(
                List.of("DONE", "CONFIRM", "CLARIFY", "PAUSED").contains(state), false);
        requireStorageCondition(dialogueMapper.live(chatInputClaim), false);
        var target =
                switch (state) {
                    case "DONE" ->
                            com.lawrence.travel.domain.execution.BackgroundJob.State.COMPLETED;
                    case "PAUSED" ->
                            com.lawrence.travel.domain.execution.BackgroundJob.State.PAUSED;
                    default ->
                            com.lawrence.travel.domain.execution.BackgroundJob.State.WAITING_USER;
                };
        jobStore.finish(
                buildJobClaimFromChatInput(chatInputClaim),
                new com.lawrence.travel.application.execution.Jobs.Output(
                        target, null, List.of(), error));
        requireStorageCondition(
                dialogueMapper.finish(
                                chatInputClaim,
                                state,
                                reply,
                                operation == null ? null : interactionCodec.encode(operation),
                                error)
                        == 1,
                false);
    }

    /** {@inheritDoc} 用户和任务锁保护操作确认与候选接受的原子性。 */
    @Override
    public Dialogue.Message lockMessage(UUID userId, UUID chatId, UUID message, String action) {
        requireActiveTransaction();
        lockUserChatAndTask(userId, chatId);
        var row = dialogueMapper.getChatInputById(userId, chatId, message);
        requireStorageCondition(row != null, true);
        return message(row, userId, chatId);
    }

    /** {@inheritDoc} 重试只改队列尝试，不重置execution_budget或未知用量。 */
    @Override
    public void changeState(UUID userId, UUID chatId, UUID message, String state) {
        requireActiveTransaction();
        lockUserChatAndTask(userId, chatId);
        requireStorageCondition(List.of("DONE", "WITHDRAWN", "QUEUED").contains(state), false);
        UUID inputId = getChatInputJobId(userId, chatId, message);
        if (inputId != null) {
            var scope = new com.lawrence.travel.application.execution.Jobs.Scope(userId, chatId);
            if (state.equals("DONE")) jobStore.acknowledge(scope, inputId);
            else jobStore.applyJobRetryOrCancellation(scope, inputId, state.equals("QUEUED"));
        } else if (state.equals("WITHDRAWN"))
            dialogueMapper.settleLegacyInputActiveTime(userId, chatId, message, true, true);
        requireStorageCondition(dialogueMapper.state(userId, chatId, message, state) == 1, false);
    }

    /**
     * Worker心跳调用。
     */
    @Override
    public boolean renew(Dialogue.Claim chatInputClaim) {
        requireActiveTransaction();
        lockUserChatAndTask(chatInputClaim.userId(), chatInputClaim.chatId());
        boolean renewed = jobStore.renew(buildJobClaimFromChatInput(chatInputClaim).fence());
        if (renewed)
            dialogueMapper.updateInputExecutionFromJob(
                    chatInputClaim.userId(),
                    chatInputClaim.chatId(),
                    chatInputClaim.messageId(),
                    getChatInputJobId(
                            chatInputClaim.userId(),
                            chatInputClaim.chatId(),
                            chatInputClaim.messageId()));
        return renewed;
    }

    /** {@inheritDoc} 只读取已鉴权范围，不把用户原文或认证信息放进关联。 */
    @Override
    public com.lawrence.travel.application.execution.ExecutionCorrelation.Ids correlation(
            Dialogue.Claim chatInputClaim) {
        var job =
                jobStore.read(
                        new com.lawrence.travel.application.execution.Jobs.Scope(
                                chatInputClaim.userId(), chatInputClaim.chatId()),
                        getChatInputJobId(
                                chatInputClaim.userId(),
                                chatInputClaim.chatId(),
                                chatInputClaim.messageId()));
        return new com.lawrence.travel.application.execution.ExecutionCorrelation.Ids(
                job.requestId(), job.traceId());
    }

    /**
     * @param userId 归属
     * @param chatId 聊天
     * @param inputId 输入
     * @return 可空映射；MyBatis标量UUID通过显式文本转换
     */
    private UUID getChatInputJobId(UUID userId, UUID chatId, UUID inputId) {
        String id = dialogueMapper.getChatInputJobId(userId, chatId, inputId);
        return id == null ? null : UUID.fromString(id);
    }

    /**
     * @param chatInputClaim 既有聊天资格
     * @return 当前作业元数据与原token组合，不能用读取到的新token替换旧资格
     */
    private com.lawrence.travel.application.execution.Jobs.Claim buildJobClaimFromChatInput(
            Dialogue.Claim chatInputClaim) {
        var scope =
                new com.lawrence.travel.application.execution.Jobs.Scope(
                        chatInputClaim.userId(), chatInputClaim.chatId());
        var job =
                jobStore.read(
                        scope,
                        getChatInputJobId(
                                chatInputClaim.userId(),
                                chatInputClaim.chatId(),
                                chatInputClaim.messageId()));
        requireStorageCondition(job.budgetId().equals(chatInputClaim.budgetId()), false);
        return new com.lawrence.travel.application.execution.Jobs.Claim(
                new com.lawrence.travel.application.execution.Jobs.Fence(
                        scope, job.id(), chatInputClaim.token(), job.version()),
                job,
                "{}");
    }

    /**
     * 保证聊天输入关联唯一持久作业，作为receive与claimExcluding衔接后台调度的桥梁。
     *
     * @param userId 归属
     * @param chatId 聊天
     * @param inputId 输入
     * @param migrateLegacyInput 是否旧待处理输入
     * @return 稳定映射；旧完成消息不进入此路径
     */
    private UUID getOrCreateChatInputJobId(
            UUID userId, UUID chatId, UUID inputId, boolean migrateLegacyInput) {
        UUID existingJobId = getChatInputJobId(userId, chatId, inputId);
        if (existingJobId != null) return existingJobId;
        // 旧在途请求先保守结算到已保存租约；复制原累计预算不删除旧计量或UNKNOWN。
        if (migrateLegacyInput)
            dialogueMapper.settleLegacyInputActiveTime(userId, chatId, inputId, true, false);
        UUID operationId =
                UUID.nameUUIDFromBytes(
                        (userId + "/" + chatId + "/" + inputId)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var job =
                jobStore.registerBackgroundJob(
                        new com.lawrence.travel.application.execution.Jobs.Submit(
                                new com.lawrence.travel.application.execution.Jobs.Scope(
                                        userId, chatId),
                                operationId,
                                com.lawrence.travel.domain.execution.BackgroundJob.Kind.CHAT_INPUT,
                                "input",
                                interactionCodec.encode(java.util.Map.of("inputId", inputId)),
                                null,
                                migrateLegacyInput ? chatId : null,
                                migrateLegacyInput
                                        ? dialogueMapper.priorAttempts(userId, chatId, inputId)
                                        : 0));
        dialogueMapper.bindJobToChatInput(userId, chatId, inputId, job.id());
        return job.id();
    }

    /** {@inheritDoc} 用作任务锁内的接受门禁及对外展示屏障。 */
    @Override
    public boolean blocked(UUID userId, UUID task) {
        return dialogueMapper.blocked(userId, task);
    }

    /** {@inheritDoc} 只保存应用服务确认的结果，不接收模型自行声称的执行成功。 */
    @Override
    public void operationResult(UUID userId, UUID chatId, UUID message, String reply) {
        requireActiveTransaction();
        requireStorageCondition(
                dialogueMapper.operationResult(userId, chatId, message, reply) == 1, false);
    }

    /**
     * @param userId 用户
     * @param chat 聊天
     * @return 权威当前基础
     */
    private Dialogue.Basis getTravelExecutionSnapshot(UUID userId, Dialogue.Chat chat) {
        if (chat.taskId() == null) return new Dialogue.Basis(null, null, 0, 0, null);
        var task = taskMapper.task(new TaskScope(userId, chat.taskId()));
        requireStorageCondition(task != null, true);
        var run = dialogueMapper.run(userId, chat.taskId());
        return new Dialogue.Basis(
                chat.taskId(),
                run == null ? null : UUID.fromString(run),
                task.executionGeneration(),
                task.constraintRevision(),
                task.currentVersionId());
    }

    /**
     * 在调用方事务内按用户、聊天、任务顺序加锁，并校验记录归属。
     *
     * @param userId 用户
     * @param chatId 聊天
     * @return 已按统一锁序保护的关联，不允许适配器绕过事务
     */
    private Dialogue.Chat lockUserChatAndTask(UUID userId, UUID chatId) {
        requireStorageCondition(dialogueMapper.lockUser(userId) != null, true);
        var result = dialogueMapper.lockChat(userId, chatId);
        requireStorageCondition(result != null, true);
        if (result.taskId() != null)
            requireStorageCondition(
                    taskMapper.lockTask(new TaskScope(userId, result.taskId())) != null, true);
        return result;
    }

    /**
     * @param row 数据行
     * @param userId 已认证归属
     * @param chatId 私有聊天
     * @return 不暴露租约内部信息的界面消息
     */
    private Dialogue.Message message(DialogueMapper.Row row, UUID userId, UUID chatId) {
        return message(row, dialogueMapper.documents(userId, chatId, row.id()));
    }

    /**
     * @param row 原消息
     * @param documents 当前窗口批量读取的引用
     * @return 不改变原文、不额外查询附件的界面消息
     */
    private Dialogue.Message message(
            DialogueMapper.Row row, List<Dialogue.DocumentReference> documents) {
        return new Dialogue.Message(
                row.id(),
                row.text(),
                row.state(),
                row.reply(),
                row.operation() == null
                        ? null
                        : interactionCodec.decode(row.operation(), Dialogue.Operation.class),
                row.error(),
                List.copyOf(documents));
    }

    /** 必须参加应用短事务，禁止隐式自动提交产生部分结果。 */
    private static void requireActiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("TRANSACTION_REQUIRED");
    }

    /**
     * @param condition 检查
     * @param missingResource 是否归属不存在，错误不能包含私有正文
     */
    private static void requireStorageCondition(boolean condition, boolean missingResource) {
        if (!condition)
            throw new TravelStorageFailure(
                    missingResource
                            ? TravelStorageFailure.Code.NOT_FOUND
                            : TravelStorageFailure.Code.STALE_VERSION);
    }

    /**
     * @param work 只读查询
     * @param <T> 结果类型
     * @return 固定依赖错误，不让SQL和用户原文进入HTTP异常日志
     */
    private static <T> T executeStorageReadWithErrorMapping(java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (org.springframework.dao.DataAccessException failure) {
            com.lawrence.travel.infrastructure.execution.AdmissionErrors.rethrowKnown(failure);
            throw new TravelStorageFailure(TravelStorageFailure.Code.DEPENDENCY_UNAVAILABLE);
        }
    }
}
