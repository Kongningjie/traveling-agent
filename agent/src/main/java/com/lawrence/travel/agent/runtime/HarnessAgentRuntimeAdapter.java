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

/** 阶段级 Harness 接线：独立上下文，HTTP 经持久化门禁，产物不直接提交业务。 */
public final class HarnessAgentRuntimeAdapter implements AgentRuntimePort {
    private final ModelCallAdmission admission;
    private final Map<String, String> models;
    private final Supplier<String> keySupplier;
    private final Path workspaceRoot;
    private final java.util.function.BiFunction<UUID, String, Toolkit> toolkits;
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
    public HarnessAgentRuntimeAdapter(ModelCallAdmission admission, Map<String, String> models,
                                     Supplier<String> keySupplier, Path workspaceRoot) {
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
    public HarnessAgentRuntimeAdapter(ModelCallAdmission admission, Map<String, String> models,
                                     Supplier<String> keySupplier, Path workspaceRoot,
                                     java.util.function.BiFunction<UUID, String, Toolkit> toolkits) {
        this.admission = admission;
        this.models = Map.copyOf(models);
        this.keySupplier = keySupplier;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.toolkits = toolkits;
    }

    /** @return 固定框架类型，仅用于依赖接线检查 */
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
    @Override public Invocation startWithoutTools(UUID runId, String role, String prompt) { return startInvocation(runId, role, prompt, false); }
    /** @param runId 原运行 @param role 模型配置角色 @param prompt 有界文本 @param allowTools 是否允许既有白名单工具 @return 隔离调用，全部经过相同门禁 */
    private Invocation startInvocation(UUID runId, String role, String prompt, boolean allowTools) {
        if (admission == null) throw new IllegalStateException("EXTERNAL_DISABLED");
        if (runId == null || role == null || !models.containsKey(role)) {
            throw new IllegalArgumentException("MODEL_NOT_APPROVED");
        }
        if (prompt == null || prompt.isBlank() || prompt.length() > 8192) {
            throw new IllegalArgumentException("MODEL_INPUT_INVALID");
        }
        String key = keySupplier.get();
        if (key == null || key.isBlank()) throw new IllegalStateException("MODEL_CREDENTIAL_MISSING");
        var transport = new GuardedDashScopeTransport(admission, runId, role, client);
        var model = DashScopeChatModel.builder().apiKey(key).modelName(models.get(role))
                .baseUrl("https://dashscope.aliyuncs.com").endpointType(EndpointType.MULTIMODAL)
                .stream(true).enableThinking(false).enableSearch(false).httpTransport(transport)
                .defaultOptions(GenerateOptions.builder().maxTokens(1024)
                        .executionConfig(ExecutionConfig.builder().maxAttempts(1).build()).build()).build();
        var harness = RestrictedHarnessFactory.create(model, allowTools ? toolkits.apply(runId, role) : new Toolkit(),
                workspaceRoot.resolve(runId.toString()).resolve(UUID.randomUUID().toString()), role);
        var invocation = new ActiveInvocation(harness, transport);
        Thread.startVirtualThread(() -> invocation.execute(prompt));
        return invocation;
    }

    /**
     * 从异常链提取门禁或受控传输的固定分类，其余异常只报告执行失败。
     * @param failure 未对外暴露的内部异常
     * @return 不包含供应商正文、提示词或凭证的错误码
     */
    private static String failureCode(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof CallRejectedException rejected) return rejected.getMessage();
            if (current instanceof HttpTransportException) {
                String code = current.getMessage();
                if (code != null && (java.util.Set.of("MODEL_CANCELLED", "MODEL_FREE_QUOTA_EXHAUSTED",
                        "MODEL_ACCESS_DENIED", "MODEL_INVALID_PARAMETER", "MODEL_NOT_FOUND",
                        "MODEL_RATE_LIMITED", "MODEL_TRANSPORT_FAILED", "MODEL_OPTIONS_REJECTED",
                        "MODEL_ENDPOINT_REJECTED", "MODEL_INPUT_TOO_LARGE", "MODEL_INPUT_INVALID",
                        "MODEL_MEDIA_NOT_ALLOWED", "MODEL_ID_REJECTED", "MODEL_STREAM_INVALID",
                        "MODEL_STREAM_INCOMPLETE", "MODEL_STREAM_TOO_LARGE", "MODEL_STREAM_PROVIDER_ERROR").contains(code)
                        || code.matches("MODEL_PROVIDER_HTTP_[1-5][0-9]{2}"))) return code;
            }
        }
        return "MODEL_EXECUTION_FAILED";
    }

    /** 本次调用独占的资源句柄，取消不提前释放传输层许可。 */
    private static final class ActiveInvocation implements Invocation {
        private final HarnessAgent harness;
        private final GuardedDashScopeTransport transport;
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        /**
         * 保存一次调用的隔离资源。
         * @param harness 独立上下文
         * @param transport 独立取消范围
         */
        private ActiveInvocation(HarnessAgent harness, GuardedDashScopeTransport transport) {
            this.harness = harness;
            this.transport = transport;
        }

        /**
         * 后台等待框架，只公开校验后的结构化产物或固定失败码。
         * @param prompt 本次文本
         */
        private void execute(String prompt) {
            try (harness; transport) {
                if (cancelled.get()) throw new IllegalStateException("MODEL_CANCELLED");
                var message = Msg.builder().name("user").role(MsgRole.USER)
                        .content(TextBlock.builder().text(prompt).build()).build();
                var response = harness.call(List.of(message), RestrictedHarnessFactory.StructuredReply.class).block();
                if (response == null) throw new IllegalStateException("MODEL_OUTPUT_INVALID");
                String answer = response.getStructuredData(RestrictedHarnessFactory.StructuredReply.class).answer();
                if (!cancelled.get()) result.complete(answer);
            } catch (RuntimeException failure) {
                result.completeExceptionally(new IllegalStateException(
                        cancelled.get() ? "MODEL_CANCELLED" : failureCode(failure)));
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
            harness.interrupt();
        }
    }
}
