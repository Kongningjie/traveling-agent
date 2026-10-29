package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.execution.ModelCallAdmission;
import com.lawrence.travel.application.port.AgentRuntimePort;
import com.lawrence.travel.domain.execution.control.CallRejectedException;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.EndpointType;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import okhttp3.OkHttpClient;

/**
 * 创建Harness调用，组装上下文并执行模型请求。
 */
public final class HarnessAgentRuntimeAdapter implements AgentRuntimePort {
    private final ModelCallAdmission admission;
    private final Map<String, String> models;
    private final Supplier<String> keySupplier;
    private final Path workspaceRoot;
    private final java.util.function.BiFunction<UUID, String, Toolkit> toolkits;
    private final com.lawrence.travel.application.memory.ContextAssembler contexts;
    private final com.lawrence.travel.application.port.InteractionCodec json;
    private final com.lawrence.travel.application.memory.SessionContextService sessions;
    private final com.lawrence.travel.application.safety.SafetyGate safety;
    private final com.lawrence.travel.application.document.SourceReadService sourceReads;
    private final com.lawrence.travel.application.port.BudgetLedger sourceLedger;
    private final com.lawrence.travel.domain.execution.control.RunLimits sourceLimits;
    private final com.lawrence.travel.application.port.ExecutionTrace trace;
    private final OkHttpClient client = new OkHttpClient();

    /** smoke 仅提供状态，没有请求发送能力。 */
    public HarnessAgentRuntimeAdapter() {
        this(null, Map.of(), () -> "", Path.of(".runtime/harness"));
    }

    /**
     * 装配固定角色配置；密钥仅开始调用时读取。
     * @param admission 门禁，null 表示 smoke 禁用
     * @param models 角色白名单
     * @param keySupplier 本机凭证提供器，不记录其结果
     * @param workspaceRoot 隔离工作区根目录
     */
    public HarnessAgentRuntimeAdapter(
            ModelCallAdmission admission,
            Map<String, String> models,
            Supplier<String> keySupplier,
            Path workspaceRoot) {
        this(admission, models, keySupplier, workspaceRoot, (run, role) -> new Toolkit());
    }

    /**
     * 装配按运行及角色创建的工具集，不能跨尝试共享可变 Toolkit。
     * @param admission 请求门禁
     * @param models 精确型号白名单
     * @param keySupplier 密钥提供器
     * @param workspaceRoot 工作区
     * @param toolkits 每次调用工具集工厂
     */
    public HarnessAgentRuntimeAdapter(
            ModelCallAdmission admission,
            Map<String, String> models,
            Supplier<String> keySupplier,
            Path workspaceRoot,
            java.util.function.BiFunction<UUID, String, Toolkit> toolkits) {
        this(admission, models, keySupplier, workspaceRoot, toolkits, null, null);
    }

    /**
     * @param admission 门禁
     * @param models 型号
     * @param keySupplier 本机凭据
     * @param workspaceRoot 隔离目录
     * @param toolkits 白名单工具
     * @param contexts 业务上下文用例
     * @param json 严格JSON
     */
    public HarnessAgentRuntimeAdapter(
            ModelCallAdmission admission,
            Map<String, String> models,
            Supplier<String> keySupplier,
            Path workspaceRoot,
            java.util.function.BiFunction<UUID, String, Toolkit> toolkits,
            com.lawrence.travel.application.memory.ContextAssembler contexts,
            com.lawrence.travel.application.port.InteractionCodec json) {
        this(
                admission,
                models,
                keySupplier,
                workspaceRoot,
                toolkits,
                contexts,
                json,
                null,
                com.lawrence.travel.application.safety.SafetyGate.local());
    }

    /**
     * @param admission 同一计量
     * @param models 型号
     * @param keySupplier 本机凭据
     * @param workspaceRoot 隔离工作区
     * @param toolkits 白名单
     * @param contexts 兼容权威源
     * @param json JSON
     * @param sessions 统一会话
     * @param safety 基础安全检查
     */
    public HarnessAgentRuntimeAdapter(
            ModelCallAdmission admission,
            Map<String, String> models,
            Supplier<String> keySupplier,
            Path workspaceRoot,
            java.util.function.BiFunction<UUID, String, Toolkit> toolkits,
            com.lawrence.travel.application.memory.ContextAssembler contexts,
            com.lawrence.travel.application.port.InteractionCodec json,
            com.lawrence.travel.application.memory.SessionContextService sessions,
            com.lawrence.travel.application.safety.SafetyGate safety) {
        this(
                admission,
                models,
                keySupplier,
                workspaceRoot,
                toolkits,
                contexts,
                json,
                sessions,
                safety,
                null,
                null,
                null);
    }

    /**
     * @param admission 统一准入
     * @param models 白名单
     * @param keySupplier 本机凭据
     * @param workspaceRoot 隔离工作区
     * @param toolkits 外部工具白名单
     * @param contexts 业务源
     * @param json JSON
     * @param sessions PG会话
     * @param safety 安全
     * @param sourceReads 本地资料
     * @param sourceLedger 原预算计量
     * @param sourceLimits 预算初值
     */
    public HarnessAgentRuntimeAdapter(
            ModelCallAdmission admission,
            Map<String, String> models,
            Supplier<String> keySupplier,
            Path workspaceRoot,
            java.util.function.BiFunction<UUID, String, Toolkit> toolkits,
            com.lawrence.travel.application.memory.ContextAssembler contexts,
            com.lawrence.travel.application.port.InteractionCodec json,
            com.lawrence.travel.application.memory.SessionContextService sessions,
            com.lawrence.travel.application.safety.SafetyGate safety,
            com.lawrence.travel.application.document.SourceReadService sourceReads,
            com.lawrence.travel.application.port.BudgetLedger sourceLedger,
            com.lawrence.travel.domain.execution.control.RunLimits sourceLimits) {
        this(
                admission,
                models,
                keySupplier,
                workspaceRoot,
                toolkits,
                contexts,
                json,
                sessions,
                safety,
                sourceReads,
                sourceLedger,
                sourceLimits,
                com.lawrence.travel.application.port.ExecutionTrace.disabled());
    }

    /**
     * @param admission 准入
     * @param models 型号
     * @param keySupplier 凭据
     * @param workspaceRoot 工作区
     * @param toolkits 白名单
     * @param contexts 权威源
     * @param json JSON
     * @param sessions 会话
     * @param safety 安全
     * @param sourceReads 资料
     * @param sourceLedger 计量
     * @param sourceLimits 限制
     * @param trace 执行事实
     */
    public HarnessAgentRuntimeAdapter(
            ModelCallAdmission admission,
            Map<String, String> models,
            Supplier<String> keySupplier,
            Path workspaceRoot,
            java.util.function.BiFunction<UUID, String, Toolkit> toolkits,
            com.lawrence.travel.application.memory.ContextAssembler contexts,
            com.lawrence.travel.application.port.InteractionCodec json,
            com.lawrence.travel.application.memory.SessionContextService sessions,
            com.lawrence.travel.application.safety.SafetyGate safety,
            com.lawrence.travel.application.document.SourceReadService sourceReads,
            com.lawrence.travel.application.port.BudgetLedger sourceLedger,
            com.lawrence.travel.domain.execution.control.RunLimits sourceLimits,
            com.lawrence.travel.application.port.ExecutionTrace trace) {
        this.trace = trace;
        this.sourceReads = sourceReads;
        this.sourceLedger = sourceLedger;
        this.sourceLimits = sourceLimits;
        this.contexts = contexts;
        this.json = json;
        this.sessions = sessions;
        this.safety =
                safety == null ? com.lawrence.travel.application.safety.SafetyGate.local() : safety;
        this.admission = admission;
        this.models = Map.copyOf(models);
        this.keySupplier = keySupplier;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.toolkits = toolkits;
    }

    /**
     * @return 固定框架类型，仅用于依赖接线检查
     */
    public Class<?> frameworkType() {
        return HarnessAgent.class;
    }

    /** {@inheritDoc} 仅表明装配状态，不声明供应商已可用。 */
    @Override
    public String integrationStatus() {
        return admission == null ? "HARNESS_NOT_VALIDATED" : "HARNESS_WIRED_NOT_PROVIDER_VERIFIED";
    }

    /** {@inheritDoc} 工作区按原运行和本次尝试隔离，结果取消后不可回流。 */
    @Override
    public Invocation start(UUID runId, String role, String prompt) {
        return startInvocation(runId, role, prompt, true);
    }

    /** {@inheritDoc} 原文提取等场景禁用所有框架工具，网页不能诱导额外付费动作。 */
    @Override
    public Invocation startWithoutTools(UUID runId, String role, String prompt) {
        return startInvocation(runId, role, prompt, false);
    }

    /**
     * 检查上下文服务是否已配置，然后启动模型调用。
     */
    @Override
    public Invocation startContextual(
            UUID runId,
            String role,
            String prompt,
            com.lawrence.travel.application.memory.AgentContext.Basis basis) {
        if ((sessions == null && contexts == null) || json == null || basis == null)
            throw new IllegalStateException("CONTEXT_NOT_CONFIGURED");
        return startInvocation(runId, role, prompt, false, basis);
    }

    /**
     * @param runId 原运行
     * @param role 模型配置角色
     * @param prompt 有界文本
     * @param allowTools 是否允许既有白名单工具
     * @return 隔离调用，全部经过相同门禁
     */
    private Invocation startInvocation(UUID runId, String role, String prompt, boolean allowTools) {
        return startInvocation(runId, role, prompt, allowTools, null);
    }

    /**
     * 建立一次隔离Agent调用，并异步串接上下文准备、Harness执行、受控工具与最终产物。
     *
     * @param runId 原运行
     * @param role 角色
     * @param prompt 当前请求
     * @param allowTools 工具权限
     * @param basis 业务输入版本，原始探针可空
     * @return 压缩与主调用共用传输及预算
     */
    private Invocation startInvocation(
            UUID runId,
            String role,
            String prompt,
            boolean allowTools,
            com.lawrence.travel.application.memory.AgentContext.Basis basis) {
        if (admission == null) throw new IllegalStateException("EXTERNAL_DISABLED");
        if (runId == null || role == null || !models.containsKey(role)) {
            throw new IllegalArgumentException("MODEL_NOT_APPROVED");
        }
        if (prompt == null || prompt.isBlank() || prompt.length() > 131072) {
            throw new IllegalArgumentException("MODEL_INPUT_INVALID");
        }
        safety.require("INPUT", prompt);
        String key = keySupplier.get();
        if (key == null || key.isBlank())
            throw new IllegalStateException("MODEL_CREDENTIAL_MISSING");
        // 只有携带领取token的协调调用可以读取个人资料。
        Toolkit toolkit =
                basis != null
                                && basis.attemptToken() != null
                                && role.equals("coordinator")
                                && sourceReads != null
                        ? new SourceToolkit(
                                sourceReads,
                                runId,
                                basis.attemptToken(),
                                sourceLedger,
                                sourceLimits,
                                trace)
                        : allowTools ? toolkits.apply(runId, role) : new Toolkit();
        var source =
                new java.util.concurrent.atomic.AtomicReference<
                        com.lawrence.travel.application.memory.AgentContext.Source>();
        var sessionSource =
                new java.util.concurrent.atomic.AtomicReference<
                        com.lawrence.travel.application.memory.SessionContext.Source>();
        var transport =
                new GuardedDashScopeTransport(
                        admission,
                        runId,
                        role,
                        client,
                        () -> {
                            if (toolkit instanceof SafetyToolkit boundary) boundary.check();
                            if (sessionSource.get() != null) sessions.guard(sessionSource.get());
                            else if (source.get() != null) contexts.guard(source.get());
                        });
        if (toolkit instanceof SourceToolkit sources)
            transport.contextHeadroom(sources::contextHeadroom);
        // 关闭SDK自动搜索和重试。
        var model =
                DashScopeChatModel.builder()
                        .apiKey(key)
                        .modelName(models.get(role))
                        .baseUrl("https://dashscope.aliyuncs.com")
                        .endpointType(EndpointType.MULTIMODAL)
                        .stream(true)
                        .enableThinking(false)
                        .enableSearch(false)
                        .httpTransport(transport)
                        .defaultOptions(
                                GenerateOptions.builder()
                                        .maxTokens(role.equals("planner") ? 12288 : 4096)
                                        .executionConfig(
                                                ExecutionConfig.builder().maxAttempts(1).build())
                                        .build())
                        .build();
        var harness =
                RestrictedHarnessFactory.create(
                        model,
                        toolkit,
                        workspaceRoot
                                .resolve(runId.toString())
                                .resolve(UUID.randomUUID().toString()),
                        role,
                        basis != null);
        var invocation = new ActiveInvocation(harness, transport, safety);
        var firstHarness = new AtomicBoolean(true);
        Supplier<HarnessAgent> factory =
                () ->
                        firstHarness.compareAndSet(true, false)
                                ? harness
                                : RestrictedHarnessFactory.create(
                                        model,
                                        toolkit,
                                        workspaceRoot
                                                .resolve(runId.toString())
                                                .resolve(UUID.randomUUID().toString()),
                                        role,
                                        basis != null);
        var correlation = com.lawrence.travel.application.execution.ExecutionCorrelation.current();
        var parent = com.lawrence.travel.application.execution.ExecutionCorrelation.parentSpan();
        var traceParent =
                com.lawrence.travel.application.execution.ExecutionCorrelation.traceParent();
        Thread.startVirtualThread(
                () -> {
                    try (var ids =
                                    com.lawrence.travel.application.execution.ExecutionCorrelation
                                            .bind(correlation);
                            var span =
                                    com.lawrence.travel.application.execution.ExecutionCorrelation
                                            .bindSpan(parent);
                            var traceBinding =
                                    com.lawrence.travel.application.execution.ExecutionCorrelation
                                            .bindTrace(traceParent);
                            var roleSpan =
                                    trace.open(
                                            runId,
                                            role.equals("reviewer")
                                                    ? com.lawrence.travel.application.port
                                                            .ExecutionTrace.Kind.REVIEW
                                                    : role.equals("planner")
                                                            ? com.lawrence.travel.application.port
                                                                    .ExecutionTrace.Kind.PLAN
                                                            : role.equals("research")
                                                                    ? com.lawrence.travel
                                                                            .application.port
                                                                            .ExecutionTrace.Kind
                                                                            .DELEGATE
                                                                    : com.lawrence.travel
                                                                            .application.port
                                                                            .ExecutionTrace.Kind
                                                                            .COORDINATE,
                                            Map.of(
                                                    "role",
                                                    role,
                                                    "schema",
                                                    basis == null
                                                            ? "structured-reply-v1"
                                                            : "business-json-v1"))) {
                        transport.traceParent(
                                com.lawrence.travel.application.execution.ExecutionCorrelation
                                        .traceParent());
                        java.util.function.Function<Boolean, List<Msg>> prepare =
                                emergency -> {
                                    try (var contextSpan =
                                            trace.open(
                                                    runId,
                                                    com.lawrence.travel.application.port
                                                            .ExecutionTrace.Kind.CONTEXT,
                                                    Map.of(
                                                            "role",
                                                            role,
                                                            "schema",
                                                            "session-context-v1"))) {
                                        try {
                                            if (sessions != null && basis != null) {
                                                var prepared =
                                                        sessions.prepare(
                                                                runId,
                                                                role,
                                                                prompt,
                                                                basis,
                                                                new TracedCompression(
                                                                        new HarnessContextCompression(
                                                                                model,
                                                                                json,
                                                                                source::set),
                                                                        trace,
                                                                        runId,
                                                                        transport),
                                                                emergency,
                                                                sessionSource::set);
                                                return sessionMessages(prepared);
                                            }
                                            String text =
                                                    basis == null
                                                            ? prompt
                                                            : contexts.prepare(
                                                                            runId,
                                                                            role,
                                                                            basis,
                                                                            prompt,
                                                                            new TracedCompression(
                                                                                    new HarnessContextCompression(
                                                                                            model,
                                                                                            json,
                                                                                            source
                                                                                                    ::set),
                                                                                    trace,
                                                                                    runId,
                                                                                    transport))
                                                                    .prompt();
                                            return List.of(
                                                    Msg.builder()
                                                            .name("user")
                                                            .role(MsgRole.USER)
                                                            .content(
                                                                    TextBlock.builder()
                                                                            .text(text)
                                                                            .build())
                                                            .build());
                                        } catch (RuntimeException failure) {
                                            contextSpan.fail(
                                                    com.lawrence.travel.application.port
                                                            .ExecutionTrace.Failure.FAILED);
                                            throw failure;
                                        }
                                    } finally {
                                        transport.traceParent(
                                                com.lawrence.travel.application.execution
                                                        .ExecutionCorrelation.traceParent());
                                    }
                                };
                        invocation.execute(
                                () -> prepare.apply(false),
                                () -> {
                                    if (sessions == null || basis == null)
                                        throw new CallRejectedException(
                                                CallRejectedException.Reason
                                                        .CONTEXT_BUDGET_EXHAUSTED);
                                    return prepare.apply(true);
                                },
                                factory,
                                () -> {
                                    if (firstHarness.compareAndSet(true, false)) harness.close();
                                },
                                basis != null,
                                json);
                        if (invocation.result.isCompletedExceptionally())
                            roleSpan.fail(
                                    invocation.cancelled.get()
                                            ? com.lawrence.travel.application.port.ExecutionTrace
                                                    .Failure.CANCELLED
                                            : com.lawrence.travel.application.port.ExecutionTrace
                                                    .Failure.FAILED);
                    } catch (RuntimeException failure) {
                        try {
                            transport.close();
                            harness.close();
                        } finally {
                            invocation.result.completeExceptionally(
                                    new IllegalStateException("TRACE_EXECUTION_FAILED"));
                            invocation.exited.countDown();
                        }
                    }
                });
        return invocation;
    }

    /**
     * @param prepared 已校验权威快照
     * @return 历史单独标识，发送超限时只可卸载历史；末条完整权威区和本轮请求永不裁剪
     */
    static List<Msg> sessionMessages(
            com.lawrence.travel.application.memory.SessionContext.Prepared prepared) {
        return java.util.stream.IntStream.range(0, prepared.messages().size())
                .mapToObj(
                        index -> {
                            var m = prepared.messages().get(index);
                            return Msg.builder()
                                    .name(
                                            m.role()
                                                            == com.lawrence.travel.application
                                                                    .memory.SessionContext.Role
                                                                    .ASSISTANT
                                                    ? "assistant"
                                                    : "user")
                                    .role(
                                            m.role()
                                                            == com.lawrence.travel.application
                                                                    .memory.SessionContext.Role
                                                                    .ASSISTANT
                                                    ? MsgRole.ASSISTANT
                                                    : MsgRole.USER)
                                    .content(TextBlock.builder().text(m.text()).build())
                                    .metadata(
                                            Map.of(
                                                    SourceResultBudgetMiddleware.OPTIONAL_HISTORY,
                                                    index < prepared.messages().size() - 1))
                                    .build();
                        })
                .toList();
    }

    /**
     * 从异常链提取门禁或受控传输的固定分类，其余异常只报告执行失败。
     * @param failure 未对外暴露的内部异常
     * @return 不包含供应商正文、提示词或凭证的错误码
     */
    private static String failureCode(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof CallRejectedException rejected) return rejected.getMessage();
            if (current instanceof ControlledHarnessCall.OutputFailure invalid) {
                return invalid.diagnosticCode();
            }
            if (current instanceof HttpTransportException) {
                String code = current.getMessage();
                if (code != null
                        && (java.util.Set.of(
                                                "MODEL_CANCELLED",
                                                "MODEL_FREE_QUOTA_EXHAUSTED",
                                                "MODEL_ACCESS_DENIED",
                                                "MODEL_INVALID_PARAMETER",
                                                "MODEL_CONTEXT_LIMIT_SAFE",
                                                "MODEL_CONTEXT_LIMIT",
                                                "MODEL_NOT_FOUND",
                                                "MODEL_RATE_LIMITED",
                                                "MODEL_TRANSPORT_FAILED",
                                                "MODEL_OPTIONS_REJECTED",
                                                "MODEL_ENDPOINT_REJECTED",
                                                "MODEL_INPUT_TOO_LARGE",
                                                "MODEL_INPUT_INVALID",
                                                "MODEL_MEDIA_NOT_ALLOWED",
                                                "MODEL_ID_REJECTED",
                                                "MODEL_STREAM_INVALID",
                                                "MODEL_STREAM_INCOMPLETE",
                                                "MODEL_STREAM_TOO_LARGE",
                                                "MODEL_STREAM_PROVIDER_ERROR",
                                                "MODEL_OUTPUT_TRUNCATED")
                                        .contains(code)
                                || code.matches("MODEL_PROVIDER_HTTP_[1-5][0-9]{2}"))) return code;
            }
        }
        return "MODEL_EXECUTION_FAILED";
    }

    /** 本次调用独占的资源句柄，取消不提前释放传输层许可。 */
    private static final class ActiveInvocation implements Invocation {
        private final HarnessAgent harness;
        private final java.util.concurrent.atomic.AtomicReference<HarnessAgent> activeHarness =
                new java.util.concurrent.atomic.AtomicReference<>();
        private final GuardedDashScopeTransport transport;
        private final com.lawrence.travel.application.safety.SafetyGate safety;
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final java.util.concurrent.CountDownLatch exited =
                new java.util.concurrent.CountDownLatch(1);
        private volatile Thread executionThread;

        /**
         * 保存一次调用的隔离资源。
         * @param harness 独立上下文
         * @param transport 独立取消范围
         * @param safety 完整输出基础检查
         */
        private ActiveInvocation(
                HarnessAgent harness,
                GuardedDashScopeTransport transport,
                com.lawrence.travel.application.safety.SafetyGate safety) {
            this.harness = harness;
            this.activeHarness.set(harness);
            this.transport = transport;
            this.safety = safety;
        }

        /**
         * 在后台线程执行ControlledHarnessCall，成功时只完成只读结果Future，不提交业务状态。
         *
         * @param prompt 本次完整输入提供器，先压缩与校验再调用模型
         * @param recovery 同预算唯一超限恢复
         * @param factory 每轮隔离框架实例
         * @param cleanupUnused 取消发生在建轮前时释放预建资源
         * @param businessJson 应用声明的业务JSON模式，非上下文调用仍要求answer字符串
         * @param json 严格业务编解码器，不额外开放工具或写入权限
         */
        private void execute(
                Supplier<List<Msg>> prompt,
                Supplier<List<Msg>> recovery,
                Supplier<HarnessAgent> factory,
                Runnable cleanupUnused,
                boolean businessJson,
                com.lawrence.travel.application.port.InteractionCodec json) {
            executionThread = Thread.currentThread();
            try (transport) {
                String answer =
                        ControlledHarnessCall.execute(
                                factory,
                                prompt,
                                recovery,
                                transport::checkContext,
                                safety,
                                cancelled::get,
                                activeHarness::set,
                                businessJson,
                                json);
                if (!cancelled.get()) result.complete(answer);
            } catch (RuntimeException failure) {
                if (!cancelled.get()) {
                    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                        if (cause instanceof CallRejectedException rejected) {
                            result.completeExceptionally(rejected);
                            return;
                        }
                        if (cause
                                instanceof
                                com.lawrence.travel.application.memory.PreferenceChangedException
                                                rejected) {
                            result.completeExceptionally(rejected);
                            return;
                        }
                        if (cause
                                instanceof
                                com.lawrence.travel.application.safety.SafetyGate.Failure
                                                rejected) {
                            result.completeExceptionally(rejected);
                            return;
                        }
                    }
                }
                result.completeExceptionally(
                        new SanitizedAgentFailure(
                                cancelled.get() ? "MODEL_CANCELLED" : failureCode(failure),
                                failure));
            } finally {
                try {
                    cleanupUnused.run();
                } finally {
                    exited.countDown();
                }
            }
        }

        /** {@inheritDoc} 只读视图防止调用方绕过 close 取消内部 Future。 */
        @Override
        public CompletionStage<String> result() {
            return result.minimalCompletionStage();
        }

        /** 取消产物并关闭实际传输；框架中断为辅助信号。 */
        @Override
        public void close() {
            cancelled.set(true);
            result.completeExceptionally(new IllegalStateException("MODEL_CANCELLED"));
            transport.close();
            activeHarness.get().interrupt();
            transport.awaitIdle();
            if (Thread.currentThread() != executionThread) {
                boolean interrupted = Thread.interrupted();
                while (exited.getCount() != 0) {
                    try {
                        exited.await();
                    } catch (InterruptedException signal) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
