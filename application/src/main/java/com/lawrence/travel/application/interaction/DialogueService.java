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
 * 聊天主链的应用编排中心：收件、后台理解、结果路由和显式操作确认统一由本类串接。
 *
 * <p>入口DialogueController.send → send在短事务内可靠收件；唯一Worker通过claimWorkExcluding
 * 领取输入 → process在事务外调用DialogueIntentInterpreter → route在短事务内处理确定性业务。
 * PostgresDialogueStore.finish保存回复及消息/作业状态，页面随后读取该权威投影。
 *
 * <p>模型只能提议意图和字段，不能直接写需求、接受行程或授权。提交前复查领取围栏、业务版本、
 * 偏好版本与附件可读性；失效或安全失败保持原文并澄清/暂停，不让迟到回复覆盖新输入。
 */
public final class DialogueService implements BackgroundWorkSource {
    private final com.lawrence.travel.application.document.AdviceService advice;//保存分析建议
    private final DialogueStore store;//聊天持久化、消息状态、任务领取
    private final DialogueInterpreter interpreter;//	将用户自然语言理解为结构化意图
    private final BusinessTransactions transactions;//管理数据库事务
    private final AccountStore accounts;//查找和检查用户身份
    private final NegotiationService negotiation;//收集、合并旅行需求和处理问题卡片
    private final RunService runs;//取消旅行规划的执行
    private final ItineraryService itineraries;//查询、接受候选行程
    private final VersionService versions;//恢复历史行程版本
    private final PreferenceService preferences;//读取、修改长期偏好
    private final TravelStore travel;//	查询旅行任务及版本数据
    private final InteractionCodec json;//	JSON 编解码
    private final ExecutionEngineStore engine;//查询执行状态、恢复原运行
    private final boolean externalEnabled;//控制真实外部调用是否获准
    private final com.lawrence.travel.application.safety.SafetyGate safety;//输入和输出安全检查

    /**
     * @param store 聊天权威
     * @param interpreter 一次协调理解
     * @param transactions 短事务
     * @param accounts 身份权威
     * @param negotiation 需求用例
     * @param runs 运行控制
     * @param itineraries 候选接受
     * @param versions 历史恢复
     * @param preferences 长期偏好
     * @param travel 旅行只读端口
     * @param json JSON
     * @param engine 原运行恢复
     * @param externalEnabled 现有调用授权开关
     */
    public DialogueService(
            DialogueStore store,
            DialogueInterpreter interpreter,
            BusinessTransactions transactions,
            AccountStore accounts,
            NegotiationService negotiation,
            RunService runs,
            ItineraryService itineraries,
            VersionService versions,
            PreferenceService preferences,
            TravelStore travel,
            InteractionCodec json,
            ExecutionEngineStore engine,
            boolean externalEnabled) {
        this(
                store,
                interpreter,
                transactions,
                accounts,
                negotiation,
                runs,
                itineraries,
                versions,
                preferences,
                travel,
                json,
                engine,
                externalEnabled,
                com.lawrence.travel.application.safety.SafetyGate.local());
    }

    /**
     * @param store 聊天
     * @param interpreter 受控理解
     * @param transactions 事务
     * @param accounts 身份
     * @param negotiation 需求
     * @param runs 运行
     * @param itineraries 接受
     * @param versions 历史
     * @param preferences 偏好
     * @param travel 权威旅行
     * @param json JSON
     * @param engine 执行
     * @param externalEnabled 原授权
     * @param safety 展示前检查
     */
    public DialogueService(
            DialogueStore store,
            DialogueInterpreter interpreter,
            BusinessTransactions transactions,
            AccountStore accounts,
            NegotiationService negotiation,
            RunService runs,
            ItineraryService itineraries,
            VersionService versions,
            PreferenceService preferences,
            TravelStore travel,
            InteractionCodec json,
            ExecutionEngineStore engine,
            boolean externalEnabled,
            com.lawrence.travel.application.safety.SafetyGate safety) {
        this(
                store,
                interpreter,
                transactions,
                accounts,
                negotiation,
                runs,
                itineraries,
                versions,
                preferences,
                travel,
                json,
                engine,
                externalEnabled,
                safety,
                null);
    }

    /**
     * @param store 聊天
     * @param interpreter 理解
     * @param transactions 事务
     * @param accounts 身份
     * @param negotiation 协商
     * @param runs 运行
     * @param itineraries 候选
     * @param versions 版本
     * @param preferences 偏好
     * @param travel 旅行
     * @param json JSON
     * @param engine 执行
     * @param externalEnabled 当前许可
     * @param safety 文本检查
     * @param advice 建议应用用例，旧离线调用可空
     */
    public DialogueService(
            DialogueStore store,
            DialogueInterpreter interpreter,
            BusinessTransactions transactions,
            AccountStore accounts,
            NegotiationService negotiation,
            RunService runs,
            ItineraryService itineraries,
            VersionService versions,
            PreferenceService preferences,
            TravelStore travel,
            InteractionCodec json,
            ExecutionEngineStore engine,
            boolean externalEnabled,
            com.lawrence.travel.application.safety.SafetyGate safety,
            com.lawrence.travel.application.document.AdviceService advice) {
        this.advice = advice;
        this.safety = safety;
        this.store = store;
        this.interpreter = interpreter;
        this.transactions = transactions;
        this.accounts = accounts;
        this.negotiation = negotiation;
        this.runs = runs;
        this.itineraries = itineraries;
        this.versions = versions;
        this.preferences = preferences;
        this.travel = travel;
        this.json = json;
        this.engine = engine;
        this.externalEnabled = externalEnabled;
    }

    /**
     * 这个版本没有附件，直接调用带附件的版本，并传入空列表。
     * @param actor 认证
     * @param chat 可重试的创建ID
     * @param input 消息幂等键
     * @param text 自由文本
     * @return 可靠保存的聊天，无旅行占位任务
     */
    public Dialogue.View send(UserIdentity actor, UUID chat, UUID input, String text) {
        return send(actor, chat, input, text, List.of());
    }

    /**
     * 可靠接收一条聊天消息及其显式附件；必要时创建聊天，明确的停止命令直接执行。
     *
     * <p>本方法只在短业务事务中保存输入或处理停止，不同步调用模型，也不等待规划完成。
     * 普通消息的理解由后台后续执行；返回的视图代表收件结果，不代表已经生成模型回复。
     * 聊天创建、消息收件、附件版本绑定及停止状态变更共用事务，任一步失败均不提交本次变更。
     * 并发归属检查、加锁及消息幂等由存储端口实现，本方法的存在性预查不能代替这些保障。
     *
     * @param actor 已认证的当前用户，不能为空；以其身份限定聊天、文件与关联旅行的访问范围
     * @param chat 调用方提供的稳定聊天ID，不能为空；首次发送可创建聊天，后续发送复用原聊天
     * @param input 本条消息的幂等ID，不能为空；网络重试应复用，同ID不得更换原文或附件选择
     * @param text 用户原文，非空且不能全为空白，不超过MAX_TEXT_LENGTH个UTF-16代码单元，不能含NUL
     * @param documents 本轮明确选择的文件ID；null视为空列表，最多10个、不得含null或重复，不从正文猜测
     * @return 收件或停止处理后的聊天视图；停止命令附应用生成的完成说明，已有对话和行程保留
     * @throws TravelStorageFailure 参数不符合条件、幂等内容冲突、归属或附件版本校验失败等；
     *     本地require失败使用现有STALE_VERSION错误码，具体存储错误由端口向上传递
     */
    public Dialogue.View send(
            UserIdentity actor, UUID chat, UUID input, String text, List<UUID> documents) {
        // 将未选择附件统一为空列表；有附件时复制调用方列表，避免后续操作直接依赖其可变集合。
        // 这里仅检查请求形状；文件是否属于当前用户/聊天、能否读取以及绑定哪个版本，由收件端口核对。
        var selected = documents == null ? List.<UUID>of() : new java.util.ArrayList<>(documents);
        // 不静默去重或过滤null，否则重试可能在用户未察觉时改变附件选择，破坏消息幂等契约。
        require(
                selected.size() <= 10
                        && selected.stream().noneMatch(Objects::isNull)
                        && new java.util.HashSet<>(selected).size() == selected.size());
        // 按短路顺序先排除null，再访问文本；这里只校验原文，不trim或改写实际保存的消息。
        // NUL不允许进入文本持久化链路；length限制按Java字符串长度计算，不是模型Token数量。
        require(
                actor != null
                        && chat != null
                        && input != null
                        && text != null
                        && !text.isBlank()
                        && text.length() <= DialogueIntent.MAX_TEXT_LENGTH
                        && text.indexOf(0) < 0);
        // REQUIRED事务使下面的数据库变更共同提交或回滚；不能将模型推理放进此事务长期占锁。
        return transactions.required(
                () -> {
                    // 先在当前用户的聊天列表中，寻找是否存在指定 chat ID。
                    boolean exists = store.list(actor.id(), 0).stream().anyMatch(c -> c.id().equals(chat));
                    // 再按ID读取，覆盖较早聊天不在第一页的情况。只有明确NOT_FOUND才进入创建分支；
                    // 其他存储失败必须向上传递，不能把数据库异常当作缺失并尝试创建。
                    if (!exists) {
                        try {
                            store.read(actor.id(), chat);
                            exists = true;
                        } catch (TravelStorageFailure missing) {
                            if (missing.code() != TravelStorageFailure.Code.NOT_FOUND)
                                throw missing;
                        }
                    }
                    // 新聊天与首条消息同事务创建，标题按当前长度上限截取原文的UTF-16代码单元。
                    // 已有聊天不重新赋标题；并发创建的最终一致性仍由store.create约束保障。
                    if (!exists)
                        store.create(
                                actor.id(), chat, text.substring(0, Math.min(60, text.length())));
                    // 只对这些完整、明确的短命令走确定性停止，不调用模型判断。
                    // trim与移除一个末尾句号/感叹号仅用于匹配；原文仍原样保存，不做子串匹配。
                    if (java.util.Set.of("停止", "停止规划", "停止当前规划", "停止当前执行")
                            .contains(text.trim().replaceAll("[。！!]$", ""))) {
                        // 停止命令不兼作附件解释请求，禁止同时带附件，避免一条输入有两种执行语义。
                        require(selected.isEmpty());
                        // 先核对幂等键与原文：已经收过的停止请求只返回原收件结果，不能再次撤销
                        // 此后新启动的执行；同ID异原文由端口拒绝，不覆盖历史消息。
                        if (store.containsInput(actor.id(), chat, input, text))
                            return store.receive(actor.id(), chat, input, text);
                        // 先撤销聊天内未完成输入的提交资格，保留原文；旧后台尝试不能再提交结果。
                        // 这是业务围栏失效，不承诺供应商侧推理在这一行执行时已物理终止。
                        store.stopPending(actor.id(), chat);
                        var current = store.read(actor.id(), chat).chat();
                        // 聊天可能尚未关联旅行，此时只需停止聊天输入；关联旅行后还要处理其当前运行。
                        if (current.taskId() != null) {
                            var run = store.currentRun(actor.id(), chat);
                            // 没有当前运行或已处于终态则不重复取消；用当前用户与任务构造scope做归属限定。
                            if (run != null
                                    && !engine.run(new TaskScope(actor.id(), current.taskId()), run)
                                            .state()
                                            .terminal()) runs.cancel(actor, current.taskId(), run);
                        }
                        // 仍登记这条停止消息，保留可重放的输入记录；随即以应用事实完成命令及对应作业，
                        // 不让后台把它当作普通消息再送给模型。最后读取包含完成说明的最新视图。
                        store.receive(actor.id(), chat, input, text);
                        store.completeCommand(actor.id(), chat, input, "已处理停止请求，已有行程和对话保留。");
                        return store.read(actor.id(), chat);
                    }
                    // 普通消息由端口在同事务中保存正文、文件版本绑定和待处理作业；这里不直接规划。
                    // 同ID重试必须保持原文与附件一致；文件归属、删除状态及版本有效性仍须在端口校验。
                    return store.receive(actor.id(), chat, input, text, selected);
                });
    }

    /**
     * 获取当前用户的聊天列表。
     * @param actor 认证
     * @param offset 分页
     * @return 仅本人聊天
     */
    public List<Dialogue.Chat> list(UserIdentity actor, int offset) {
        return store.list(actor.id(), offset);
    }

    /**
     * 读取指定聊天的持久化视图。
     * @param actor 认证
     * @param chat 聊天
     * @return 持久化的完整可见结果
     */
    public Dialogue.View read(UserIdentity actor, UUID chat) {
        return store.read(actor.id(), chat);
    }

    /**
     * 将当前聊天关联到已有的旅行任务，然后返回关联后的聊天视图。
     * @param actor 已认证用户
     * @param chat 新聊天幂等身份
     * @param task 历史私有旅行
     * @return 原有或新关联聊天，历史任务不绕过意图理解
     */
    public Dialogue.View attach(UserIdentity actor, UUID chat, UUID task) {
        return transactions.required(
                () -> store.read(actor.id(), store.attach(actor.id(), chat, task).id()));
    }

    /** {@inheritDoc} 由既有Worker取得运行容量后调用，领取事务不跨模型调用。 */
    @Override
    public Optional<Runnable> claimWork() {
        return claimed(transactions.required(store::claim));
    }

    /**
     * {@inheritDoc}
     *
     * <p>PersistentStageWorker在已取得物理容量后调用；此处仅在短事务中经store.claimExcluding
     * 领取一条输入并构造Runnable，不在领取事务中执行模型。排除集合来自尚未实际退出的用户线程，
     * 不能因逻辑暂停就给同一用户叠加新的物理执行。返回空表示目前无可领取输入。
     */
    @Override
    public Optional<Runnable> claimWorkExcluding(java.util.Set<UUID> excluded) {
        return claimed(transactions.required(() -> store.claimExcluding(excluded)));
    }

    /**
     * 把持久Claim包装为Worker可执行且可续租的LeasedWork，保持输入/预算/围栏身份不变。
     *
     * <p>userId用于调度公平性，renew委托原存储围栏续租；run恢复原ExecutionCorrelation后调用process。
     * 领取和执行分离，因此不把请求线程ThreadLocal或登录Cookie传给后台模型，也不长期持有收件事务。
     *
     * @param selected 原输入
     * @return 维持关联和心跳的实际工作
     */
    private Optional<Runnable> claimed(Optional<Dialogue.Claim> selected) {
        return selected.map(
                c ->
                        new com.lawrence.travel.application.port.BackgroundWorkSource.LeasedWork() {
                            /** {@inheritDoc} 使用权威输入归属。 */
                            @Override
                            public UUID userId() {
                                return c.userId();
                            }

                            /** 原输入续租，不授予新预算或业务版本。 */
                            @Override
                            public boolean renew() {
                                return transactions.required(() -> store.renew(c));
                            }

                            /** 异步执行显式绑定持久关联身份；业务提交仍由原领取资格控制。 */
                            @Override
                            public void run() {
                                var ids = store.correlation(c);
                                if (ids == null) {
                                    process(c);
                                    return;
                                }
                                try (var ignored =
                                        com.lawrence.travel.application.execution
                                                .ExecutionCorrelation.bind(ids)) {
                                    process(c);
                                }
                            }
                        });
    }

    /**
     * 后台处理一条已领取输入：安全检查 → 组装本轮业务数据 → 模型理解 → 输出/原文依据校验 → 事务提交。
     *
     * <p>由claimWorkExcluding返回的LeasedWork.run调用，模型阶段不占用户、聊天或任务行锁。
     * Claim携带原输入、预算和领取token；补充长期偏好与问题卡片后交给DialogueIntentInterpreter，
     * 模型运行时发生的取消、资料删除或需求/偏好变化必须在提交前被重新识别。
     *
     * <p>成功由route决定只读回复、规划字段或确认卡，统一经store.finish落库。失败只存固定分类和
     * 安全说明；旧领取失效时放弃提交失败结果，不输出供应商异常正文，也不自动发起第二次模型调用。
     *
     * @param c 原领取；任何模型或协议失败保存固定原因，屏障必须等待重试或撤回
     */
    private void process(Dialogue.Claim c) {
        // 标记仅属于本次领取；不新增调度状态。事务回滚后仍可按实际失败位置保存固定分类。
        var phase = new java.util.concurrent.atomic.AtomicReference<>(FailurePhase.INPUT);
        try {
            safety.require("INPUT", c.text());
            phase.set(FailurePhase.CONTEXT);
            var selectedDocuments =
                    json.decode(c.context(), java.util.Map.class).get("selectedDocuments");
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
                            if (store.guard(c))
                                store.finish(
                                        c,
                                        "CLARIFY",
                                        "选中的资料已删除或暂不可读取，请重新选择文件后发送。",
                                        null,
                                        "DOCUMENT_REFERENCE_UNAVAILABLE");
                            return null;
                        });
                return;
            }
            var pref = preferences.read(actor(c.userId()));
            var augmented = new java.util.LinkedHashMap<String, Object>();
            augmented.put("travel", json.decode(c.context(), Object.class));
            augmented.put("preferences", pref.entries().stream().limit(20).toList());
            if (c.basis().taskId() != null) {
                var card = negotiation.card(actor(c.userId()), c.basis().taskId());
                if (card != null) augmented.put("questions", DialogueQuestionContext.from(card));
            }
            var modelContext =
                    new Dialogue.Claim(
                            c.userId(),
                            c.chatId(),
                            c.messageId(),
                            c.token(),
                            c.budgetId(),
                            c.text(),
                            c.receivedAt(),
                            c.basis(),
                            json.encode(augmented));
            // 这里已经离开收件/领取事务：模型等待期间不持有用户、任务或聊天行锁。
            // phase只用于脱敏失败分类，不是另一套可恢复状态机。
            phase.set(FailurePhase.MODEL);
            var result = interpreter.interpret(modelContext);
            phase.set(FailurePhase.OUTPUT);
            safety.require("OUTPUT", json.encode(result));
            // 模型只能声明原文中确实存在的诉求。先核对引用与待澄清输入，之后才进入业务提交。
            phase.set(FailurePhase.SOURCE);
            var unresolved = json.decode(c.context(), java.util.Map.class).get("unresolved");
            var sources = new java.util.LinkedHashMap<UUID, String>();
            if (unresolved instanceof List<?> entries)
                for (Object entry : entries)
                    if (entry instanceof java.util.Map<?, ?> item)
                        sources.put(
                                UUID.fromString((String) item.get("id")),
                                (String) item.get("text"));
            require(sources.keySet().containsAll(result.resolvedInputs()));
            result.validateSources(
                    c.text(), result.resolvedInputs().stream().map(sources::get).toList());
            phase.set(FailurePhase.COMMIT);
            transactions.required(
                    () -> {
                        // 先取得原执行的归属锁和围栏，再重查选中文件；模型期间的删除/版本变化不能被旧回复覆盖。
                        if (!store.guard(c)
                                || preferences.read(actor(c.userId())).version().revision()
                                        != pref.version().revision()
                                || store.documents(c.userId(), c.chatId(), c.messageId()).stream()
                                        .anyMatch(d -> !d.available())) {
                            store.finish(
                                    c,
                                    "CLARIFY",
                                    "旅行状态或引用资料已经变化，请核对后重新补充，或撤回这条输入。",
                                    null,
                                    "DIALOGUE_CONTEXT_CHANGED");
                            return null;
                        }
                        route(c, result, phase::set);
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
                            store.guard(c);
                            store.finish(c, "PAUSED", safeReply, null, reason);
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
        if (failure instanceof TravelStorageFailure rejected)
            return "DIALOGUE_" + phase.name() + "_" + rejected.code().name();
        return "DIALOGUE_" + phase.name() + "_FAILED";
    }

    /**
     * 把通过安全和原文依据检查的模型结果转换为应用动作；调用方已进入短事务并复查原领取资格。
     *
     * <p>先处理歧义/冲突，避免在范围未明确时修改业务；再区分操作、规划和只读咨询。
     * 重要操作通过prepare生成待确认对象；规划字段经RequirementFields预检后交给
     * NegotiationService.applyInterpreted，创建或变更旅行并进入阶段链。只读建议独立保存，
     * 不能因模型写了“已采纳”就视为需求已修改。所有分支最终由store.finish记录实际结果。
     *
     * @param c 已锁上下文
     * @param result 结构化语义；不以模型回复声称业务已提交
     * @param mark 本次调用私有失败位置标记，不包含正文或凭据
     */
    private void route(
            Dialogue.Claim c,
            DialogueIntent.Result result,
            java.util.function.Consumer<FailurePhase> mark) {
        var actor = actor(c.userId());
        var operations =
                result.intents().stream()
                        .filter(i -> i.kind() == DialogueIntent.Kind.OPERATION)
                        .toList();
        boolean planning =
                result.intents().stream().anyMatch(i -> i.kind() == DialogueIntent.Kind.PLANNING);
        if (!result.clarification().isBlank()
                || result.intents().stream().anyMatch(i -> i.kind() == DialogueIntent.Kind.UNCLEAR)
                || operations.size() > 1
                || (!operations.isEmpty() && planning)) {
            store.finish(
                    c,
                    "CLARIFY",
                    result.reply()
                            + "\n"
                            + (result.clarification().isBlank()
                                    ? "请明确本次需要执行的操作与对象，其他要求已保留原文。"
                                    : result.clarification()),
                    null,
                    "");
            return;
        }
        // 已有旅行的规划输入若没有任何可提交字段，不能把自然语言承诺当成成功修改。
        // 保留原文和澄清屏障，且不提前解决旧诉求；用户可补充、重试或撤回。
        // 首次仅表达规划意愿仍允许创建任务，由既有协商流程收集缺项。
        if (planning && c.basis().taskId() != null && result.facts().isEmpty()) {
            store.finish(
                    c,
                    "CLARIFY",
                    "这条规划要求已保留，但尚未提取到可记录的修改，未更新旅行需求。请补充希望调整的内容，也可以重试理解或撤回。",
                    null,
                    "DIALOGUE_PLANNING_FACTS_MISSING");
            return;
        }
// 只有前面的歧义和字段完整性门槛通过后，才将用户本轮明确解决的旧澄清输入标记为已解决。
// 该更新与后续路由同事务；业务执行失败会回滚，避免旧问题被消除而新要求没有保存。
        store.resolve(c, result.resolvedInputs());
        if (!operations.isEmpty()) {
            var action = operations.getFirst().action();
            if (action == DialogueIntent.Action.STOP) {
                if (c.basis().taskId() == null || c.basis().runId() == null) {
                    store.finish(c, "DONE", "当前没有正在执行的旅行规划。", null, "");
                    return;
                }
                runs.cancel(actor, c.basis().taskId(), c.basis().runId());
                store.finish(c, "DONE", "已停止当前规划，已有行程和对话保留。", null, "");
                return;
            }
            if (action == DialogueIntent.Action.CONTINUE) {
                String reply = "当前没有可直接恢复的运行。可继续补充需求；达到预算上限时不会自动新建预算。";
                if (c.basis().runId() != null) {
                    var scope = new TaskScope(c.userId(), c.basis().taskId());
                    var run = engine.run(scope, c.basis().runId());
                    if (run.state()
                            == com.lawrence.travel.domain.execution.ExecutionRun.State
                                    .PAUSED_QUOTA) {
                        if (externalEnabled) {
                            engine.resumeQuota(scope, run.id(), true);
                            reply = "已请求沿用原预算恢复；每次调用仍检查免费额度、未知用量和资源限制。";
                        } else reply = "真实外部调用仍关闭，当前规划保持暂停；不会因这条消息打开调用开关。";
                    }
                }
                store.finish(c, "DONE", reply, null, "");
                return;
            }
            if (action == DialogueIntent.Action.AUTHORIZE) {
                store.finish(c, "DONE", "授权需要明确问题、选项和范围。请在当前问题卡片勾选授权内容后提交；这条文字不会授予额外权限。", null, "");
                return;
            }
            if (action == DialogueIntent.Action.REJECT
                    || action == DialogueIntent.Action.UNDECIDED) {
                var card =
                        c.basis().taskId() == null
                                ? null
                                : negotiation.card(actor, c.basis().taskId());
                if (card != null)
                    negotiation.answer(
                            actor,
                            c.basis().taskId(),
                            c.messageId(),
                            new Negotiation.Response(
                                    card.decision().id(),
                                    card.decision().revision(),
                                    card.decision().constraintRevision(),
                                    action.name(),
                                    java.util.Map.of(),
                                    c.text(),
                                    java.util.Set.of()));
                store.finish(c, "DONE", "已记录你的态度，未采纳任何建议；尚未解决的问题继续保留。", null, "");
                return;
            }
            var operation = prepare(actor, c, result, action);
            if (operation == null) {
                store.finish(
                        c, "CLARIFY", "请在行程详情、问题卡片或偏好管理中定位具体对象后操作；继续执行仍受原预算和调用门禁限制。", null, "");
                return;
            }
            store.finish(
                    c,
                    "CONFIRM",
                    result.reply().isBlank() ? "请核对以下操作，点击确认后才会执行。" : result.reply(),
                    operation,
                    "");
            return;
        }
        if (planning) {
            var facts =
                    result.facts().stream()
                            .map(
                                    f ->
                                            new Negotiation.Fact(
                                                    f.field(), f.value(), f.quote(), f.operation()))
                            .toList();
            mark.accept(FailurePhase.REQUIREMENTS);
// 先用领域合并规则验证提取字段及ADD/REPLACE语义，再登记解释结果。
// 这里不以模型reply为修改成功依据；真正的需求版本与后继阶段由协商服务及提交链管理。
            RequirementFields.merge(
                    c.basis().taskId() == null
                            ? List.of()
                            : travel.revision(
                                            new TaskScope(c.userId(), c.basis().taskId()),
                                            c.basis().revision())
                                    .requirements(),
                    NegotiationHandler.updates(facts),
                    com.lawrence.travel.domain.travel.ConstraintRevision.Source.USER,
                    NegotiationHandler.replacements(facts));
            mark.accept(FailurePhase.COMMIT);
            UUID task =
                    negotiation.applyInterpreted(
                            actor, c.basis().taskId(), c.messageId(), c.text(), facts);
            if (c.basis().taskId() == null) store.link(c, task);
            store.finish(
                    c,
                    "DONE",
                    result.reply().isBlank() ? "已记录你的规划要求，将继续核对缺项并生成候选。" : result.reply(),
                    null,
                    "");
            return;
        }
        if (!result.advice().isEmpty()) {
            if (advice == null) throw new IllegalStateException("ADVICE_NOT_CONFIGURED");
            advice.capture(c, result.advice());
        }
        store.finish(
                c,
                "DONE",
                (result.reply().isBlank() ? "你可以继续聊旅行想法，也可以让我开始规划。" : result.reply())
                        + (result.advice().isEmpty()
                                ? ""
                                : "\n以上仅为分析建议，尚未采纳或修改生效行程。请逐项核对来源和影响后确认。"),
                null,
                "");
    }

    /**
     * 为接受候选、恢复版本、保存/删除长期偏好构造待确认操作快照，不在此处执行操作。
     *
     * <p>对象只能来自当前上下文或已归属验证的详情；找不到唯一对象则返回null交回澄清。
     * Operation保存当时旅行Basis及偏好版本，用户稍后CONFIRM时act必须重新核对，不能仅凭模型targetId写库。
     *
     * @param actor 用户
     * @param c 当前业务基础
     * @param r 模型参数
     * @param action 动作
     * @return 服务端构造的精确确认，不能定位时为空
     */
    private Dialogue.Operation prepare(
            UserIdentity actor,
            Dialogue.Claim c,
            DialogueIntent.Result r,
            DialogueIntent.Action action) {
        UUID target = r.targetId();
        String description;
        long preferenceRevision = 0;
        if (action == DialogueIntent.Action.ACCEPT && c.basis().taskId() != null) {
            var metadata = json.decode(c.context(), java.util.Map.class);
            Object candidate = metadata.get("candidateId");
            if (!(candidate instanceof String id)
                    || (target != null && !target.toString().equals(id))) return null;
            target = UUID.fromString(id);
            description = "接受候选 " + target + "，通过当前校验后设为生效行程；过期只重校验，仍需再次接受。";
        } else if (action == DialogueIntent.Action.RESTORE_VERSION
                && c.basis().taskId() != null
                && c.basis().currentVersion() != null
                && target != null) {
            versions.detail(actor, c.basis().taskId(), target);
            description = "以历史版本 " + target + " 生成恢复候选；不会立即替换当前行程。";
        } else if (action == DialogueIntent.Action.SAVE_PREFERENCE
                && List.of("pace", "diet", "interests", "transport").contains(r.category())
                && !r.value().isBlank()) {
            preferenceRevision = preferences.read(actor).version().revision();
            target = UUID.randomUUID();
            description =
                    "保存长期偏好："
                            + r.category()
                            + "＝"
                            + r.value()
                            + "；适用范围："
                            + (r.city().isBlank() ? "所有城市" : r.city());
        } else if (action == DialogueIntent.Action.DELETE_PREFERENCE && target != null) {
            var view = preferences.read(actor);
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
                c.basis(),
                preferenceRevision,
                r.category(),
                r.value(),
                r.city());
    }

    /**
     * 执行用户在精确消息上明确点击的CONFIRM、WITHDRAW或RETRY，全部在短业务事务中处理。
     *
     * <p>先锁消息及归属。撤回使原输入失去后续执行资格；重试沿用原输入/预算，不重置调用限制。
     * 确认只适用于仍有效的Operation，重新比对旅行Basis或偏好版本后才委托行程/版本/偏好应用服务。
     * operationResult记录应用实际执行结果，不把模型先前生成的reply当作操作已成功的凭据。
     *
     * @param actor 认证
     * @param chat 聊天
     * @param input 精确确认消息
     * @param action CONFIRM／WITHDRAW／RETRY
     * @return 最新视图；重试沿用预算，确认必须核对业务版本
     */
    public Dialogue.View act(UserIdentity actor, UUID chat, UUID input, String action) {
        require(List.of("CONFIRM", "WITHDRAW", "RETRY").contains(action));
        return transactions.required(
                () -> {
                    var m = store.lockMessage(actor.id(), chat, input, action);
                    if (action.equals("WITHDRAW")) {
                        require(!m.state().equals("DONE"));
                        if (!m.state().equals("WITHDRAWN"))
                            store.changeState(actor.id(), chat, input, "WITHDRAWN");
                    } else if (action.equals("RETRY")) {
                        require(
                                List.of("PAUSED", "CLARIFY", "QUEUED", "RUNNING")
                                        .contains(m.state()));
                        if (List.of("PAUSED", "CLARIFY").contains(m.state()))
                            store.changeState(actor.id(), chat, input, "QUEUED");
                    } else {
                        if (m.state().equals("DONE")) return store.read(actor.id(), chat);
                        require(m.state().equals("CONFIRM") && m.operation() != null);
                        var op = m.operation();
                        var task = store.read(actor.id(), chat).chat().taskId();
                        require(Objects.equals(task, op.basis().taskId()));
                        if (task != null) {
                            var latest = travel.task(new TaskScope(actor.id(), task)).task();
                            require(
                                    latest.executionGeneration() == op.basis().generation()
                                            && latest.constraintRevision() == op.basis().revision()
                                            && Objects.equals(
                                                    latest.currentVersionId(),
                                                    op.basis().currentVersion())
                                            && !store.blocked(actor.id(), task));
                        }
                        // 状态更新与业务操作同事务；失败全部回滚，重复确认只返回首次已提交结果。
                        store.changeState(actor.id(), chat, input, "DONE");
                        String outcome =
                                switch (op.action()) {
                                    case ACCEPT -> {
                                        var candidate = itineraries.latest(actor, task);
                                        require(
                                                candidate != null
                                                        && candidate
                                                                .candidate()
                                                                .candidateId()
                                                                .equals(op.targetId()));
                                        var v = candidate.candidate().version();
                                        var accepted =
                                                itineraries.accept(
                                                        actor,
                                                        task,
                                                        input,
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
                                        versions.restore(
                                                actor,
                                                task,
                                                input,
                                                op.targetId(),
                                                op.basis().currentVersion(),
                                                op.basis().revision(),
                                                op.basis().generation());
                                        yield "已请求生成历史恢复候选；完成校验和审核后仍需你接受。";
                                    }
                                    case SAVE_PREFERENCE -> {
                                        preferences.change(
                                                actor,
                                                input,
                                                op.preferenceRevision(),
                                                op.targetId(),
                                                "SAVE",
                                                op.category(),
                                                op.value(),
                                                op.city());
                                        yield "已保存你确认的长期偏好。";
                                    }
                                    case DELETE_PREFERENCE -> {
                                        preferences.change(
                                                actor,
                                                input,
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
                        store.operationResult(actor.id(), chat, input, outcome);
                    }
                    return store.read(actor.id(), chat);
                });
    }

    /**
     * @param user 已通过收件认证的私有用户
     * @return 当前启用账号的最小身份，不伪造登录名
     */
    private UserIdentity actor(UUID user) {
        return accounts.findById(user)
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
