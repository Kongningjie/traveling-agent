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

/**
 * 保存用户输入，调用模型理解，再保存回复或执行对应业务。
 */
public final class DialogueService implements BackgroundWorkSource {
    private final com.lawrence.travel.application.document.AdviceService adviceService; // 保存分析建议
    private final DialogueStore dialogueStore; // 聊天持久化、消息状态、任务领取
    private final DialogueInterpreter dialogueInterpreter; // 	将用户自然语言理解为结构化意图
    private final BusinessTransactions transactions; // 管理数据库事务
    private final AccountStore accountStore; // 查找和检查用户身份
    private final NegotiationService negotiationService; // 收集、合并旅行需求和处理问题卡片
    private final RunService runService; // 取消旅行规划的执行
    private final ItineraryService itineraryService; // 查询、接受候选行程
    private final VersionService versionService; // 恢复历史行程版本
    private final PreferenceService preferenceService; // 读取、修改长期偏好
    private final TravelStore travelStore; // 	查询旅行任务及版本数据
    private final InteractionCodec interactionCodec; // 	JSON 编解码
    private final ExecutionEngineStore executionEngineStore; // 查询执行状态、恢复原运行
    private final boolean externalCallsEnabled; // 控制真实外部调用是否获准
    private final com.lawrence.travel.application.safety.SafetyGate safetyGate; // 输入和输出安全检查

    /**
     * @param dialogueStore 聊天权威
     * @param dialogueInterpreter 一次协调理解
     * @param transactions 短事务
     * @param accountStore 身份权威
     * @param negotiationService 需求用例
     * @param runService 运行控制
     * @param itineraryService 候选接受
     * @param versionService 历史恢复
     * @param preferenceService 长期偏好
     * @param travelStore 旅行只读端口
     * @param interactionCodec JSON
     * @param executionEngineStore 原运行恢复
     * @param externalCallsEnabled 现有调用授权开关
     */
    public DialogueService(
            DialogueStore dialogueStore,
            DialogueInterpreter dialogueInterpreter,
            BusinessTransactions transactions,
            AccountStore accountStore,
            NegotiationService negotiationService,
            RunService runService,
            ItineraryService itineraryService,
            VersionService versionService,
            PreferenceService preferenceService,
            TravelStore travelStore,
            InteractionCodec interactionCodec,
            ExecutionEngineStore executionEngineStore,
            boolean externalCallsEnabled) {
        this(
                dialogueStore,
                dialogueInterpreter,
                transactions,
                accountStore,
                negotiationService,
                runService,
                itineraryService,
                versionService,
                preferenceService,
                travelStore,
                interactionCodec,
                executionEngineStore,
                externalCallsEnabled,
                com.lawrence.travel.application.safety.SafetyGate.local());
    }

    /**
     * @param dialogueStore 聊天
     * @param dialogueInterpreter 受控理解
     * @param transactions 事务
     * @param accountStore 身份
     * @param negotiationService 需求
     * @param runService 运行
     * @param itineraryService 接受
     * @param versionService 历史
     * @param preferenceService 偏好
     * @param travelStore 权威旅行
     * @param interactionCodec JSON
     * @param executionEngineStore 执行
     * @param externalCallsEnabled 原授权
     * @param safetyGate 展示前检查
     */
    public DialogueService(
            DialogueStore dialogueStore,
            DialogueInterpreter dialogueInterpreter,
            BusinessTransactions transactions,
            AccountStore accountStore,
            NegotiationService negotiationService,
            RunService runService,
            ItineraryService itineraryService,
            VersionService versionService,
            PreferenceService preferenceService,
            TravelStore travelStore,
            InteractionCodec interactionCodec,
            ExecutionEngineStore executionEngineStore,
            boolean externalCallsEnabled,
            com.lawrence.travel.application.safety.SafetyGate safetyGate) {
        this(
                dialogueStore,
                dialogueInterpreter,
                transactions,
                accountStore,
                negotiationService,
                runService,
                itineraryService,
                versionService,
                preferenceService,
                travelStore,
                interactionCodec,
                executionEngineStore,
                externalCallsEnabled,
                safetyGate,
                null);
    }

    /**
     * @param dialogueStore 聊天
     * @param dialogueInterpreter 理解
     * @param transactions 事务
     * @param accountStore 身份
     * @param negotiationService 协商
     * @param runService 运行
     * @param itineraryService 候选
     * @param versionService 版本
     * @param preferenceService 偏好
     * @param travelStore 旅行
     * @param interactionCodec JSON
     * @param executionEngineStore 执行
     * @param externalCallsEnabled 当前许可
     * @param safetyGate 文本检查
     * @param adviceService 建议应用用例，旧离线调用可空
     */
    public DialogueService(
            DialogueStore dialogueStore,
            DialogueInterpreter dialogueInterpreter,
            BusinessTransactions transactions,
            AccountStore accountStore,
            NegotiationService negotiationService,
            RunService runService,
            ItineraryService itineraryService,
            VersionService versionService,
            PreferenceService preferenceService,
            TravelStore travelStore,
            InteractionCodec interactionCodec,
            ExecutionEngineStore executionEngineStore,
            boolean externalCallsEnabled,
            com.lawrence.travel.application.safety.SafetyGate safetyGate,
            com.lawrence.travel.application.document.AdviceService adviceService) {
        this.adviceService = adviceService;
        this.safetyGate = safetyGate;
        this.dialogueStore = dialogueStore;
        this.dialogueInterpreter = dialogueInterpreter;
        this.transactions = transactions;
        this.accountStore = accountStore;
        this.negotiationService = negotiationService;
        this.runService = runService;
        this.itineraryService = itineraryService;
        this.versionService = versionService;
        this.preferenceService = preferenceService;
        this.travelStore = travelStore;
        this.interactionCodec = interactionCodec;
        this.executionEngineStore = executionEngineStore;
        this.externalCallsEnabled = externalCallsEnabled;
    }

    /**
     * 这个版本没有附件，直接调用带附件的版本，并传入空列表。
     *
     * @param actor 认证
     * @param chatId 可重试的创建ID
     * @param inputId 消息幂等键
     * @param text 自由文本
     * @return 可靠保存的聊天，无旅行占位任务
     */
    public Dialogue.View send(UserIdentity actor, UUID chatId, UUID inputId, String text) {
        return send(actor, chatId, inputId, text, List.of());
    }

    /**
     * 保存消息和附件；明确的停止指令直接执行。
     *
     * @param actor 当前登录用户
     * @param chatId 聊天ID，不存在时创建
     * @param inputId 消息ID，重试时复用
     * @param text 用户原文，非空、长度不超限且不含NUL
     * @param documents 附件ID列表，最多10个，无空值或重复；null视为空列表
     * @return 保存或停止处理后的聊天视图
     * @throws TravelStorageFailure 参数、归属、版本或幂等校验失败
     */
    public Dialogue.View send(
            UserIdentity actor, UUID chatId, UUID inputId, String text, List<UUID> documents) {
        // 未选择附件时使用空列表，否则复制附件列表。
        var selectedDocumentIds =
                documents == null ? List.<UUID>of() : new java.util.ArrayList<>(documents);
        // 附件最多10个，不允许null或重复ID。
        require(
                selectedDocumentIds.size() <= 10
                        && selectedDocumentIds.stream().noneMatch(Objects::isNull)
                        && new java.util.HashSet<>(selectedDocumentIds).size()
                                == selectedDocumentIds.size());
        // 校验身份、消息ID、非空文本、长度和NUL字符。
        require(
                actor != null
                        && chatId != null
                        && inputId != null
                        && text != null
                        && !text.isBlank()
                        && text.length() <= DialogueIntent.MAX_TEXT_LENGTH
                        && text.indexOf(0) < 0);
        // 在同一事务中创建聊天并保存消息。
        return transactions.required(
                () -> {
                    // 先在当前用户的聊天列表中，寻找是否存在指定 chat ID。
                    boolean exists =
                            dialogueStore.list(actor.id(), 0).stream()
                                    .anyMatch(c -> c.id().equals(chatId));
                    // 第一页未找到时按ID查询；仅NOT_FOUND表示聊天不存在。
                    if (!exists) {
                        try {
                            dialogueStore.read(actor.id(), chatId);
                            exists = true;
                        } catch (TravelStorageFailure missing) {
                            if (missing.code() != TravelStorageFailure.Code.NOT_FOUND)
                                throw missing;
                        }
                    }
                    // 聊天不存在时创建，标题截取首条消息。
                    if (!exists)
                        dialogueStore.create(
                                actor.id(), chatId, text.substring(0, Math.min(60, text.length())));
                    // 匹配明确的停止指令，忽略首尾空白和一个末尾标点。
                    if (java.util.Set.of("停止", "停止规划", "停止当前规划", "停止当前执行")
                            .contains(text.trim().replaceAll("[。！!]$", ""))) {
                        // 停止指令不允许携带附件。
                        require(selectedDocumentIds.isEmpty());
                        // 相同停止消息已处理时直接返回，避免重复取消。
                        if (dialogueStore.containsInput(actor.id(), chatId, inputId, text))
                            return dialogueStore.saveChatInputAndEnsureJob(
                                    actor.id(), chatId, inputId, text);
                        // 撤销未完成输入的提交资格，阻止旧回复写入。
                        dialogueStore.stopPending(actor.id(), chatId);
                        var current = dialogueStore.read(actor.id(), chatId).chat();
                        // 已关联旅行时，继续检查并取消当前运行。
                        if (current.taskId() != null) {
                            var run = dialogueStore.currentRun(actor.id(), chatId);
                            // 存在尚未结束的运行时才取消。
                            if (run != null
                                    && !executionEngineStore
                                            .run(new TaskScope(actor.id(), current.taskId()), run)
                                            .state()
                                            .terminal())
                                runService.cancel(actor, current.taskId(), run);
                        }
                        // 保存停止消息并直接标记完成。
                        dialogueStore.saveChatInputAndEnsureJob(actor.id(), chatId, inputId, text);
                        dialogueStore.completeCommand(
                                actor.id(), chatId, inputId, "已处理停止请求，已有行程和对话保留。");
                        return dialogueStore.read(actor.id(), chatId);
                    }
                    // 保存普通消息、附件绑定和后台作业。
                    return dialogueStore.saveChatInputAndEnsureJob(
                            actor.id(), chatId, inputId, text, selectedDocumentIds);
                });
    }

    /**
     * 获取当前用户的聊天列表。
     *
     * @param actor 认证
     * @param offset 分页
     * @return 仅本人聊天
     */
    public List<Dialogue.Chat> list(UserIdentity actor, int offset) {
        return dialogueStore.list(actor.id(), offset);
    }

    /**
     * 读取指定聊天的持久化视图。
     *
     * @param actor 认证
     * @param chatId 聊天
     * @return 持久化的完整可见结果
     */
    public Dialogue.View read(UserIdentity actor, UUID chatId) {
        return dialogueStore.read(actor.id(), chatId);
    }

    /**
     * 将当前聊天关联到已有的旅行任务，然后返回关联后的聊天视图。
     *
     * @param actor 已认证用户
     * @param chatId 新聊天幂等身份
     * @param task 历史私有旅行
     * @return 原有或新关联聊天，历史任务不绕过意图理解
     */
    public Dialogue.View attach(UserIdentity actor, UUID chatId, UUID task) {
        return transactions.required(
                () ->
                        dialogueStore.read(
                                actor.id(), dialogueStore.attach(actor.id(), chatId, task).id()));
    }

    /** {@inheritDoc} 由既有Worker取得运行容量后调用，领取事务不跨模型调用。 */
    @Override
    public Optional<Runnable> claimWork() {
        return buildLeasedChatInputWork(transactions.required(dialogueStore::claim));
    }

    /**
     * 排除正在执行的用户，领取下一条输入并返回后台任务。
     */
    @Override
    public Optional<Runnable> claimWorkExcluding(java.util.Set<UUID> excludedUserIds) {
        return buildLeasedChatInputWork(
                transactions.required(
                        () -> dialogueStore.claimNextChatInputExcludingUsers(excludedUserIds)));
    }

    /**
     * 将已领取输入包装成可执行、可续租的后台任务。
     *
     * @param claimResult 原输入
     * @return 维持关联和心跳的实际工作
     */
    private Optional<Runnable> buildLeasedChatInputWork(Optional<Dialogue.Claim> claimResult) {
        return claimResult.map(
                chatInputClaim ->
                        new com.lawrence.travel.application.port.BackgroundWorkSource.LeasedWork() {
                            /** {@inheritDoc} 使用权威输入归属。 */
                            @Override
                            public UUID userId() {
                                return chatInputClaim.userId();
                            }

                            /** 原输入续租，不授予新预算或业务版本。 */
                            @Override
                            public boolean renew() {
                                return transactions.required(
                                        () -> dialogueStore.renew(chatInputClaim));
                            }

                            /** 异步执行显式绑定持久关联身份；业务提交仍由原领取资格控制。 */
                            @Override
                            public void run() {
                                var ids = dialogueStore.correlation(chatInputClaim);
                                if (ids == null) {
                                    processChatInput(chatInputClaim);
                                    return;
                                }
                                try (var ignored =
                                        com.lawrence.travel.application.execution
                                                .ExecutionCorrelation.bind(ids)) {
                                    processChatInput(chatInputClaim);
                                }
                            }
                        });
    }

    /**
     * 检查输入和上下文，调用模型，再校验并保存结果。
     *
     * @param chatInputClaim 原领取；任何模型或协议失败保存固定原因，屏障必须等待重试或撤回
     */
    private void processChatInput(Dialogue.Claim chatInputClaim) {
        // 标记仅属于本次领取；不新增调度状态。事务回滚后仍可按实际失败位置保存固定分类。
        var phase = new java.util.concurrent.atomic.AtomicReference<>(FailurePhase.INPUT);
        try {
            safetyGate.require("INPUT", chatInputClaim.text());
            phase.set(FailurePhase.CONTEXT);
            var selectedDocuments =
                    interactionCodec
                            .decode(chatInputClaim.context(), java.util.Map.class)
                            .get("selectedDocuments");
            // 已绑定版本失效时不调用模型，也不悄悄替换成另一份同名资料。用户须重新选择。
            if (selectedDocuments instanceof List<?> selected
                    && selected.stream()
                            .anyMatch(
                                    item ->
                                            item instanceof java.util.Map<?, ?> ref
                                                    && !Boolean.TRUE.equals(
                                                            ref.get("available")))) {
                transactions.required(
                        () -> {
                            if (dialogueStore.isChatInputExecutionValid(chatInputClaim))
                                dialogueStore.finish(
                                        chatInputClaim,
                                        "CLARIFY",
                                        "选中的资料已删除或暂不可读取，请重新选择文件后发送。",
                                        null,
                                        "DOCUMENT_REFERENCE_UNAVAILABLE");
                            return null;
                        });
                return;
            }
            var pref = preferenceService.read(getUserIdentity(chatInputClaim.userId()));
            var augmented = new java.util.LinkedHashMap<String, Object>();
            augmented.put(
                    "travel", interactionCodec.decode(chatInputClaim.context(), Object.class));
            augmented.put("preferences", pref.entries().stream().limit(20).toList());
            if (chatInputClaim.basis().taskId() != null) {
                var card =
                        negotiationService.card(
                                getUserIdentity(chatInputClaim.userId()),
                                chatInputClaim.basis().taskId());
                if (card != null) augmented.put("questions", DialogueQuestionContext.from(card));
            }
            var interpreterClaim =
                    new Dialogue.Claim(
                            chatInputClaim.userId(),
                            chatInputClaim.chatId(),
                            chatInputClaim.messageId(),
                            chatInputClaim.token(),
                            chatInputClaim.budgetId(),
                            chatInputClaim.text(),
                            chatInputClaim.receivedAt(),
                            chatInputClaim.basis(),
                            interactionCodec.encode(augmented));
            // 模型调用在事务外执行。
            phase.set(FailurePhase.MODEL);
            var interpretationResult = dialogueInterpreter.interpret(interpreterClaim);
            phase.set(FailurePhase.OUTPUT);
            safetyGate.require("OUTPUT", interactionCodec.encode(interpretationResult));
            // 核对模型摘录是否来自本轮或已指明的历史原文。
            phase.set(FailurePhase.SOURCE);
            var unresolved =
                    interactionCodec
                            .decode(chatInputClaim.context(), java.util.Map.class)
                            .get("unresolved");
            var sources = new java.util.LinkedHashMap<UUID, String>();
            if (unresolved instanceof List<?> entries)
                for (Object entry : entries)
                    if (entry instanceof java.util.Map<?, ?> item)
                        sources.put(
                                UUID.fromString((String) item.get("id")),
                                (String) item.get("text"));
            if (!sources.keySet().containsAll(interpretationResult.resolvedInputs())) {
                throw new DialogueInterpretationFailure(
                        DialogueInterpretationFailure.Code.RESOLVED_INPUT_INVALID);
            }
            interpretationResult.validateSources(
                    chatInputClaim.text(),
                    interpretationResult.resolvedInputs().stream().map(sources::get).toList());
            phase.set(FailurePhase.COMMIT);
            transactions.required(
                    () -> {
                        // 提交前复查执行资格、偏好版本和附件可读性。
                        if (!dialogueStore.isChatInputExecutionValid(chatInputClaim)
                                || preferenceService
                                                .read(getUserIdentity(chatInputClaim.userId()))
                                                .version()
                                                .revision()
                                        != pref.version().revision()
                                || dialogueStore
                                        .documents(
                                                chatInputClaim.userId(),
                                                chatInputClaim.chatId(),
                                                chatInputClaim.messageId())
                                        .stream()
                                        .anyMatch(d -> !d.available())) {
                            dialogueStore.finish(
                                    chatInputClaim,
                                    "CLARIFY",
                                    "旅行状态或引用资料已经变化，请核对后重新补充，或撤回这条输入。",
                                    null,
                                    "DIALOGUE_CONTEXT_CHANGED");
                            return null;
                        }
                        handleInterpretedIntents(chatInputClaim, interpretationResult, phase::set);
                        return null;
                    });
        } catch (RuntimeException failure) {
            Thread.interrupted();
            boolean disabled =
                    failure
                                    instanceof
                                    com.lawrence.travel.domain.execution.control
                                                            .CallRejectedException
                                                    rejected
                            && rejected.reason()
                                    == com.lawrence.travel.domain.execution.control
                                            .CallRejectedException.Reason.EXTERNAL_DISABLED;
            String reason = failureReason(phase.get(), failure);
            String explanation =
                    disabled
                            ? "真实外部调用尚未开启，输入已保存，暂未进行模型理解。此提示不表示免费额度用完；可以保留或撤回这条输入。"
                            : "输入已保存，理解尚未完成。可重试或撤回；不会自动修改行程。";
            String safeReply =
                    failure
                                    instanceof
                                    com.lawrence.travel.application.safety.SafetyGate.Failure
                                                    rejected
                            ? "本次检查未通过或暂不可用，输入已保留。可修改内容或撤回。问题编号：" + rejected.result().problemId()
                            : explanation;
            try {
                transactions.required(
                        () -> {
                            dialogueStore.isChatInputExecutionValid(chatInputClaim);
                            dialogueStore.finish(chatInputClaim, "PAUSED", safeReply, null, reason);
                            return null;
                        });
            } catch (RuntimeException obsolete) {
                System.getLogger(getClass().getName())
                        .log(System.Logger.Level.INFO, "DIALOGUE_FAILURE_NOT_COMMITTED");
            }
        }
    }

    /** 单次理解的失败位置；不是运行时业务状态机，不参与恢复或预算判定。 */
    private enum FailurePhase {
        /** 输入安全检查。 */
        INPUT,
        /** 上下文装载与装配。 */
        CONTEXT,
        /** 理解适配器执行。 */
        MODEL,
        /** 输出安全与结构序列化。 */
        OUTPUT,
        /** 原文依据及解决对象校验。 */
        SOURCE,
        /** 需求确定性预检。 */
        REQUIREMENTS,
        /** 事务内路由、归属与提交。 */
        COMMIT
    }

    /**
     * @param phase 实际失败位置
     * @param failure 仅按可信Java类型和枚举分类，不读取message、cause或SQL
     * @return 最长48字符的持久化固定码，保留原门禁与安全分类语义
     */
    private static String failureReason(FailurePhase phase, RuntimeException failure) {
        if (failure instanceof com.lawrence.travel.application.memory.PreferenceChangedException)
            return "PREFERENCE_CHANGED";
        if (failure instanceof com.lawrence.travel.application.safety.SafetyGate.Failure rejected)
            return "SAFETY_" + rejected.result().status().name();
        if (failure
                instanceof
                com.lawrence.travel.domain.execution.control.CallRejectedException rejected)
            return rejected.reason().name();
        if (failure instanceof DialogueInterpretationFailure rejected)
            return "DIALOGUE_" + rejected.code().name();
        if (failure instanceof DialogueDecodeFailure rejected) return rejected.diagnosticCode();
        if (failure instanceof com.lawrence.travel.application.execution.AgentCallFailure rejected)
            return rejected.diagnosticCode();
        if (failure instanceof TravelStorageFailure rejected)
            return "DIALOGUE_" + phase.name() + "_" + rejected.code().name();
        return "DIALOGUE_" + phase.name() + "_FAILED";
    }

    /**
     * 按意图选择澄清、操作确认、旅行规划或普通回复分支。
     *
     * @param chatInputClaim 已锁上下文
     * @param interpretationResult 结构化语义；不以模型回复声称业务已提交
     * @param failurePhaseRecorder 本次调用私有失败位置标记，不包含正文或凭据
     */
    private void handleInterpretedIntents(
            Dialogue.Claim chatInputClaim,
            DialogueIntent.Result interpretationResult,
            java.util.function.Consumer<FailurePhase> failurePhaseRecorder) {
        var actor = getUserIdentity(chatInputClaim.userId());
        var operations =
                interpretationResult.intents().stream()
                        .filter(i -> i.kind() == DialogueIntent.Kind.OPERATION)
                        .toList();
        boolean planning =
                interpretationResult.intents().stream()
                        .anyMatch(i -> i.kind() == DialogueIntent.Kind.PLANNING);
        // 意图不明确、操作过多或同时要求规划和操作时，先追问。
        if (!interpretationResult.clarification().isBlank()
                || interpretationResult.intents().stream()
                        .anyMatch(i -> i.kind() == DialogueIntent.Kind.UNCLEAR)
                || operations.size() > 1
                || (!operations.isEmpty() && planning)) {
            dialogueStore.finish(
                    chatInputClaim,
                    "CLARIFY",
                    interpretationResult.reply()
                            + "\n"
                            + (interpretationResult.clarification().isBlank()
                                    ? "请明确本次需要执行的操作与对象，其他要求已保留原文。"
                                    : interpretationResult.clarification()),
                    null,
                    "");
            return;
        }
        // 已有旅行但未提取到修改字段时，要求用户补充。
        if (planning
                && chatInputClaim.basis().taskId() != null
                && interpretationResult.facts().isEmpty()) {
            dialogueStore.finish(
                    chatInputClaim,
                    "CLARIFY",
                    "这条规划要求已保留，但尚未提取到可记录的修改，未更新旅行需求。请补充希望调整的内容，也可以重试理解或撤回。",
                    null,
                    "DIALOGUE_PLANNING_FACTS_MISSING");
            return;
        }
        // 将本轮已解决的旧澄清问题标记完成。
        dialogueStore.resolve(chatInputClaim, interpretationResult.resolvedInputs());
        if (!operations.isEmpty()) {
            var action = operations.getFirst().action();
            // 识别为停止操作时，取消关联旅行的当前运行。
            if (action == DialogueIntent.Action.STOP) {
                if (chatInputClaim.basis().taskId() == null
                        || chatInputClaim.basis().runId() == null) {
                    dialogueStore.finish(chatInputClaim, "DONE", "当前没有正在执行的旅行规划。", null, "");
                    return;
                }
                runService.cancel(
                        actor, chatInputClaim.basis().taskId(), chatInputClaim.basis().runId());
                dialogueStore.finish(chatInputClaim, "DONE", "已停止当前规划，已有行程和对话保留。", null, "");
                return;
            }
            // 识别为继续操作时，检查是否有可恢复的运行。
            if (action == DialogueIntent.Action.CONTINUE) {
                String reply = "当前没有可直接恢复的运行。可继续补充需求；达到预算上限时不会自动新建预算。";
                if (chatInputClaim.basis().runId() != null) {
                    var scope =
                            new TaskScope(chatInputClaim.userId(), chatInputClaim.basis().taskId());
                    var run = executionEngineStore.run(scope, chatInputClaim.basis().runId());
                    if (run.state()
                            == com.lawrence.travel.domain.execution.ExecutionRun.State
                                    .PAUSED_QUOTA) {
                        if (externalCallsEnabled) {
                            executionEngineStore.resumeQuota(scope, run.id(), true);
                            reply = "已请求沿用原预算恢复；每次调用仍检查免费额度、未知用量和资源限制。";
                        } else reply = "真实外部调用仍关闭，当前规划保持暂停；不会因这条消息打开调用开关。";
                    }
                }
                dialogueStore.finish(chatInputClaim, "DONE", reply, null, "");
                return;
            }
            // 文字授权不直接生效，提示用户在问题卡片确认。
            if (action == DialogueIntent.Action.AUTHORIZE) {
                dialogueStore.finish(
                        chatInputClaim,
                        "DONE",
                        "授权需要明确问题、选项和范围。请在当前问题卡片勾选授权内容后提交；这条文字不会授予额外权限。",
                        null,
                        "");
                return;
            }
            // 用户拒绝或暂未决定时，只记录态度。
            if (action == DialogueIntent.Action.REJECT
                    || action == DialogueIntent.Action.UNDECIDED) {
                var card =
                        chatInputClaim.basis().taskId() == null
                                ? null
                                : negotiationService.card(actor, chatInputClaim.basis().taskId());
                if (card != null)
                    negotiationService.answer(
                            actor,
                            chatInputClaim.basis().taskId(),
                            chatInputClaim.messageId(),
                            new Negotiation.Response(
                                    card.decision().id(),
                                    card.decision().revision(),
                                    card.decision().constraintRevision(),
                                    action.name(),
                                    java.util.Map.of(),
                                    chatInputClaim.text(),
                                    java.util.Set.of()));
                dialogueStore.finish(
                        chatInputClaim, "DONE", "已记录你的态度，未采纳任何建议；尚未解决的问题继续保留。", null, "");
                return;
            }
            var operation =
                    buildPendingConfirmationOperation(
                            actor, chatInputClaim, interpretationResult, action);
            // 无法确定操作对象时，要求用户明确对象。
            if (operation == null) {
                dialogueStore.finish(
                        chatInputClaim,
                        "CLARIFY",
                        "请在行程详情、问题卡片或偏好管理中定位具体对象后操作；继续执行仍受原预算和调用门禁限制。",
                        null,
                        "");
                return;
            }
            dialogueStore.finish(
                    chatInputClaim,
                    "CONFIRM",
                    interpretationResult.reply().isBlank()
                            ? "请核对以下操作，点击确认后才会执行。"
                            : interpretationResult.reply(),
                    operation,
                    "");
            return;
        }
        // 识别为规划需求时，保存提取字段并关联旅行任务。
        if (planning) {
            var facts =
                    interpretationResult.facts().stream()
                            .map(
                                    f ->
                                            new Negotiation.Fact(
                                                    f.field(), f.value(), f.quote(), f.operation()))
                            .toList();
            failurePhaseRecorder.accept(FailurePhase.REQUIREMENTS);
            // 校验字段合并，再交给协商服务保存。
            RequirementFields.merge(
                    chatInputClaim.basis().taskId() == null
                            ? List.of()
                            : travelStore
                                    .revision(
                                            new TaskScope(
                                                    chatInputClaim.userId(),
                                                    chatInputClaim.basis().taskId()),
                                            chatInputClaim.basis().revision())
                                    .requirements(),
                    NegotiationHandler.updates(facts),
                    com.lawrence.travel.domain.travel.ConstraintRevision.Source.USER,
                    NegotiationHandler.replacements(facts));
            failurePhaseRecorder.accept(FailurePhase.COMMIT);
            UUID task =
                    negotiationService.applyInterpreted(
                            actor,
                            chatInputClaim.basis().taskId(),
                            chatInputClaim.messageId(),
                            chatInputClaim.text(),
                            facts);
            if (chatInputClaim.basis().taskId() == null) dialogueStore.link(chatInputClaim, task);
            dialogueStore.finish(
                    chatInputClaim,
                    "DONE",
                    interpretationResult.reply().isBlank()
                            ? "已记录你的规划要求，将继续核对缺项并生成候选。"
                            : interpretationResult.reply(),
                    null,
                    "");
            return;
        }
        // 有资料分析建议时，单独保存建议。
        if (!interpretationResult.advice().isEmpty()) {
            if (adviceService == null) throw new IllegalStateException("ADVICE_NOT_CONFIGURED");
            adviceService.capture(chatInputClaim, interpretationResult.advice());
        }
        dialogueStore.finish(
                chatInputClaim,
                "DONE",
                (interpretationResult.reply().isBlank()
                                ? "你可以继续聊旅行想法，也可以让我开始规划。"
                                : interpretationResult.reply())
                        + (interpretationResult.advice().isEmpty()
                                ? ""
                                : "\n以上仅为分析建议，尚未采纳或修改生效行程。请逐项核对来源和影响后确认。"),
                null,
                "");
    }

    /**
     * 定位操作对象，生成等待用户确认的操作数据。
     *
     * @param actor 用户
     * @param chatInputClaim 当前业务基础
     * @param interpretationResult 模型参数
     * @param action 动作
     * @return 服务端构造的精确确认，不能定位时为空
     */
    private Dialogue.Operation buildPendingConfirmationOperation(
            UserIdentity actor,
            Dialogue.Claim chatInputClaim,
            DialogueIntent.Result interpretationResult,
            DialogueIntent.Action action) {
        UUID target = interpretationResult.targetId();
        String description;
        long preferenceRevision = 0;
        if (action == DialogueIntent.Action.ACCEPT && chatInputClaim.basis().taskId() != null) {
            var metadata = interactionCodec.decode(chatInputClaim.context(), java.util.Map.class);
            Object candidate = metadata.get("candidateId");
            if (!(candidate instanceof String id)
                    || (target != null && !target.toString().equals(id))) return null;
            target = UUID.fromString(id);
            description = "接受候选 " + target + "，通过当前校验后设为生效行程；过期只重校验，仍需再次接受。";
        } else if (action == DialogueIntent.Action.RESTORE_VERSION
                && chatInputClaim.basis().taskId() != null
                && chatInputClaim.basis().currentVersion() != null
                && target != null) {
            versionService.detail(actor, chatInputClaim.basis().taskId(), target);
            description = "以历史版本 " + target + " 生成恢复候选；不会立即替换当前行程。";
        } else if (action == DialogueIntent.Action.SAVE_PREFERENCE
                && List.of("pace", "diet", "interests", "transport")
                        .contains(interpretationResult.category())
                && !interpretationResult.value().isBlank()) {
            preferenceRevision = preferenceService.read(actor).version().revision();
            target = UUID.randomUUID();
            description =
                    "保存长期偏好："
                            + interpretationResult.category()
                            + "＝"
                            + interpretationResult.value()
                            + "；适用范围："
                            + (interpretationResult.city().isBlank()
                                    ? "所有城市"
                                    : interpretationResult.city());
        } else if (action == DialogueIntent.Action.DELETE_PREFERENCE && target != null) {
            var view = preferenceService.read(actor);
            UUID id = target;
            var entry = view.entries().stream().filter(p -> p.id().equals(id)).findFirst();
            if (entry.isEmpty()) return null;
            preferenceRevision = view.version().revision();
            description = "删除长期偏好：" + entry.get().value() + "；后续不再召回。";
        } else return null;
        return new Dialogue.Operation(
                action,
                target,
                description,
                chatInputClaim.basis(),
                preferenceRevision,
                interpretationResult.category(),
                interpretationResult.value(),
                interpretationResult.city());
    }

    /**
     * 确认操作、撤回消息，或将暂停消息重新排队。
     *
     * @param actor 认证
     * @param chatId 聊天
     * @param inputId 精确确认消息
     * @param action CONFIRM／WITHDRAW／RETRY
     * @return 最新视图；重试沿用预算，确认必须核对业务版本
     */
    public Dialogue.View applyChatInputAction(
            UserIdentity actor, UUID chatId, UUID inputId, String action) {
        require(List.of("CONFIRM", "WITHDRAW", "RETRY").contains(action));
        return transactions.required(
                () -> {
                    var m = dialogueStore.lockMessage(actor.id(), chatId, inputId, action);
                    if (action.equals("WITHDRAW")) {
                        require(!m.state().equals("DONE"));
                        if (!m.state().equals("WITHDRAWN"))
                            dialogueStore.changeState(actor.id(), chatId, inputId, "WITHDRAWN");
                    } else if (action.equals("RETRY")) {
                        require(
                                List.of("PAUSED", "CLARIFY", "QUEUED", "RUNNING")
                                        .contains(m.state()));
                        if (List.of("PAUSED", "CLARIFY").contains(m.state()))
                            dialogueStore.changeState(actor.id(), chatId, inputId, "QUEUED");
                    } else {
                        if (m.state().equals("DONE")) return dialogueStore.read(actor.id(), chatId);
                        require(m.state().equals("CONFIRM") && m.operation() != null);
                        var op = m.operation();
                        var task = dialogueStore.read(actor.id(), chatId).chat().taskId();
                        require(Objects.equals(task, op.basis().taskId()));
                        if (task != null) {
                            var latest = travelStore.task(new TaskScope(actor.id(), task)).task();
                            require(
                                    latest.executionGeneration() == op.basis().generation()
                                            && latest.constraintRevision() == op.basis().revision()
                                            && Objects.equals(
                                                    latest.currentVersionId(),
                                                    op.basis().currentVersion())
                                            && !dialogueStore.blocked(actor.id(), task));
                        }
                        // 状态更新与业务操作同事务；失败全部回滚，重复确认只返回首次已提交结果。
                        dialogueStore.changeState(actor.id(), chatId, inputId, "DONE");
                        String outcome =
                                switch (op.action()) {
                                    case ACCEPT -> {
                                        var candidate = itineraryService.latest(actor, task);
                                        require(
                                                candidate != null
                                                        && candidate
                                                                .candidate()
                                                                .candidateId()
                                                                .equals(op.targetId()));
                                        var v = candidate.candidate().version();
                                        var accepted =
                                                itineraryService.accept(
                                                        actor,
                                                        task,
                                                        inputId,
                                                        op.targetId(),
                                                        op.basis().currentVersion(),
                                                        op.basis().revision(),
                                                        op.basis().generation(),
                                                        v.payloadHash());
                                        yield accepted.status()
                                                        == com.lawrence.travel.application.port
                                                                .CandidateStore.Status.ACCEPTED
                                                ? "已接受这份候选并设为生效行程。"
                                                : "报告已过期，已请求重新校验；完成后仍需你再次接受，目前没有切换生效版本。";
                                    }
                                    case RESTORE_VERSION -> {
                                        versionService.restore(
                                                actor,
                                                task,
                                                inputId,
                                                op.targetId(),
                                                op.basis().currentVersion(),
                                                op.basis().revision(),
                                                op.basis().generation());
                                        yield "已请求生成历史恢复候选；完成校验和审核后仍需你接受。";
                                    }
                                    case SAVE_PREFERENCE -> {
                                        preferenceService.change(
                                                actor,
                                                inputId,
                                                op.preferenceRevision(),
                                                op.targetId(),
                                                "SAVE",
                                                op.category(),
                                                op.value(),
                                                op.city());
                                        yield "已保存你确认的长期偏好。";
                                    }
                                    case DELETE_PREFERENCE -> {
                                        preferenceService.change(
                                                actor,
                                                inputId,
                                                op.preferenceRevision(),
                                                op.targetId(),
                                                "DELETE",
                                                null,
                                                null,
                                                null);
                                        yield "已删除这条长期偏好，后续不再召回。";
                                    }
                                    default ->
                                            throw new IllegalArgumentException(
                                                    "DIALOGUE_OPERATION_UNSUPPORTED");
                                };
                        dialogueStore.operationResult(actor.id(), chatId, inputId, outcome);
                    }
                    return dialogueStore.read(actor.id(), chatId);
                });
    }

    /**
     * @param userId 已通过收件认证的私有用户
     * @return 当前启用账号的最小身份，不伪造登录名
     */
    private UserIdentity getUserIdentity(UUID userId) {
        return accountStore
                .findById(userId)
                .filter(AccountStore.Account::enabled)
                .orElseThrow(() -> new TravelStorageFailure(TravelStorageFailure.Code.NOT_FOUND))
                .identity();
    }

    /**
     * @param condition 应用执行条件，失败保留原文，不包含模型正文
     */
    private static void require(boolean condition) {
        if (!condition) throw new TravelStorageFailure(TravelStorageFailure.Code.STALE_VERSION);
    }
}
