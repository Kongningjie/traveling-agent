package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.execution.ModelCallAdmission;
import com.lawrence.travel.domain.execution.control.TokenUsage;
import com.lawrence.travel.domain.execution.control.CallRejectedException;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import reactor.core.publisher.Flux;

/** 北京原生实时接口的发送边界；使用多模态端点但只允许文本，每次 HTTP 独立预占。 */
public final class GuardedDashScopeTransport implements HttpTransport, AutoCloseable {
    public static final String ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private final com.lawrence.travel.application.execution.ExecutionCorrelation.Ids correlation=com.lawrence.travel.application.execution.ExecutionCorrelation.current();
    private final java.util.concurrent.atomic.AtomicReference<UUID> traceParent=new java.util.concurrent.atomic.AtomicReference<>(com.lawrence.travel.application.execution.ExecutionCorrelation.traceParent());
    /** @param id 当前调用内部节点，业务attempt身份不变；串行模型发送前显式绑定 */
    void traceParent(UUID id) {traceParent.set(id);}
    private final UUID parentSpan=com.lawrence.travel.application.execution.ExecutionCorrelation.parentSpan();
    private final ModelCallAdmission admission;
    private final UUID runId;
    private final String role;
    private final Call.Factory client;
    private final Runnable contextGuard;
    private final Set<Call> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycle=new Object();
    private int activeRequests;

    /**
     * 固定实际传输策略，不接受调用方指定替代地域或重定向。
     * @param admission 持久化准入门禁
     * @param runId 原运行标识
     * @param role 已配置角色
     * @param client 共享连接池客户端；重试、重定向及拦截器在边界重新限制
     */
    public GuardedDashScopeTransport(ModelCallAdmission admission, UUID runId, String role,
                                    OkHttpClient client) {
        this(admission, runId, role, client, () -> { });
    }
    /** @param admission 原门禁 @param runId 原运行 @param role 角色 @param client HTTP客户端 @param contextGuard 每次真实发送前的短事务版本检查 */
    public GuardedDashScopeTransport(ModelCallAdmission admission, UUID runId, String role, OkHttpClient client, Runnable contextGuard) {
        this.contextGuard = contextGuard;
        this.admission = admission;
        this.runId = runId;
        this.role = role;
        var builder = client.newBuilder().retryOnConnectionFailure(false)
                .followRedirects(false).followSslRedirects(false)
                // 非流式模型可能长时间无响应字节；总时限由每次 Call 的预算超时统一控制。
                .readTimeout(0, TimeUnit.MILLISECONDS);
        builder.interceptors().clear();
        builder.networkInterceptors().clear();
        this.client = builder.build();
    }

    /**
     * 包内测试接缝，用可控连接验证取消与计量，不改变生产端点限制。
     * @param admission 持久化门禁
     * @param runId 原运行
     * @param role 角色
     * @param client 可控连接工厂
     */
    GuardedDashScopeTransport(ModelCallAdmission admission, UUID runId, String role, Call.Factory client) {
        this(admission, runId, role, client, () -> { });
    }
    /** @param admission 测试门禁 @param runId 原运行 @param role 角色 @param client 可控本地连接 @param contextGuard 发送前版本检查；仅包内测试接缝 */
    GuardedDashScopeTransport(ModelCallAdmission admission, UUID runId, String role, Call.Factory client, Runnable contextGuard) {
        this.contextGuard = contextGuard;
        this.admission = admission;
        this.runId = runId;
        this.role = role;
        this.client = client;
    }

    /**
     * {@inheritDoc} 只允许有界文本请求；供应商异常只保留固定分类，原始错误正文不进入 SDK 日志。
     * @param request SDK 完成格式化后的请求
     * @return 已采集用量的成功响应
     */
    @Override
    public HttpResponse execute(HttpRequest request) {
        enterRequest();
        try(var ids=com.lawrence.travel.application.execution.ExecutionCorrelation.bind(correlation); var span=com.lawrence.travel.application.execution.ExecutionCorrelation.bindSpan(parentSpan);var traceBinding=com.lawrence.travel.application.execution.ExecutionCorrelation.bindTrace(traceParent.get())) { return executeRequest(request); }
        finally { exitRequest(); }
    }
    /** @param request 已格式化文本请求 @return 发送及已结束计量的响应；关联由外层显式恢复 */
    private HttpResponse executeRequest(HttpRequest request) {
        var prepared = prepare(request);
        String body = prepared.body();
        long upper = prepared.upper();
        try (var permit = admission.begin(runId, role, "cn-beijing", "DASHSCOPE_NATIVE_REALTIME", upper)) {
            if (!permit.target().modelId().equals(prepared.model())) {
                throw new HttpTransportException("MODEL_ID_REJECTED");
            }
            Request.Builder outgoing = new Request.Builder().url(ENDPOINT)
                    .post(RequestBody.create(body, MediaType.get("application/json; charset=utf-8")));
            request.getHeaders().forEach(outgoing::header);
            Call call = client.newCall(outgoing.build());
            call.timeout().timeout(permit.timeout().toMillis(), TimeUnit.MILLISECONDS);
            inFlight.add(call);
            // close 与登记竞态时再检查一次，保证用户停止后不会漏掉刚登记的连接。
            if (closed.get()) call.cancel();
            try {
                String responseBody;
                int status;
                try (var response = call.execute()) {
                    status = response.code();
                    responseBody = response.body() == null ? "" : response.body().string();
                }
                // 先耗尽并关闭响应，再结算释放许可；取消不会提前释放仍在途的连接。
                permit.complete(readUsage(responseBody));
                if (status < 200 || status >= 300) {
                    throw providerFailure(status, responseBody);
                }
                if (closed.get()) throw new HttpTransportException("MODEL_CANCELLED");
                rejectTruncated(responseBody);
                return HttpResponse.builder().statusCode(status).body(responseBody).build();
            } catch (IOException failure) {
                // 不保留带 URL、请求内容或认证信息的异常链。未知用量由 permit.close 保守结算。
                throw new HttpTransportException(closed.get() ? "MODEL_CANCELLED" : "MODEL_TRANSPORT_FAILED");
            } finally {
                inFlight.remove(call);
            }
        }
    }

    /**
     * 共用发送前输入限制，避免流式绕过非流式的免费额度和文本权限检查。
     * @param request SDK 已格式化请求
     * @return 验证后的正文、型号及保守 Token 上界
     */
    private Prepared prepare(HttpRequest request) {
        contextGuard.run();
        if (closed.get()) throw new HttpTransportException("MODEL_CANCELLED");
        if (!ENDPOINT.equals(request.getUrl()) || !"POST".equals(request.getMethod())) {
            throw new HttpTransportException("MODEL_ENDPOINT_REJECTED");
        }
        String body = request.getBody();
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw new HttpTransportException("MODEL_INPUT_TOO_LARGE");
        }
        com.lawrence.travel.application.safety.SafetyGate.local().require("MODEL_REQUEST",body);
        Map<?, ?> json;
        try { json = JsonUtils.getJsonCodec().fromJson(body, Map.class); }
        catch (RuntimeException invalid) { throw new HttpTransportException("MODEL_INPUT_INVALID"); }
        if (json == null) throw new HttpTransportException("MODEL_INPUT_INVALID");
        requireTextOnly(json);
        if (!(json.get("model") instanceof String)) throw new HttpTransportException("MODEL_INPUT_INVALID");
        Map<?, ?> parameters = json.get("parameters") instanceof Map<?, ?> values ? values : null;
        if (parameters == null || !(parameters.get("max_tokens") instanceof Number max)
                || max.intValue() < 1 || max.intValue() > (role.equals("planner") ? 12288 : 4096)
                || Boolean.TRUE.equals(parameters.get("enable_thinking"))
                || Boolean.TRUE.equals(parameters.get("enable_search"))) {
            throw new HttpTransportException("MODEL_OPTIONS_REJECTED");
        }
        long estimatedInput = HarnessContextCompression.estimateInput(body);
        long contextLimit = com.lawrence.travel.application.memory.ContextAssembler.limit(role);
        if (estimatedInput > contextLimit) {
            var failure = new CallRejectedException(CallRejectedException.Reason.CONTEXT_BUDGET_EXHAUSTED);
            failure.initCause(new com.lawrence.travel.domain.execution.control.ContextBudgetDiagnostic("TRANSPORT_BODY", estimatedInput, contextLimit)); throw failure;
        }
        // 文本 JSON 的 UTF-8 字节数采用双倍余量并加消息模板开销；输出强制有上限。
        // 图片、音视频及联网搜索不在此适配器范围，不能借文本上界放行其隐藏消耗。
        long upper = body.getBytes(StandardCharsets.UTF_8).length * 2L + 4096 + max.intValue();
        return new Prepared(body, (String) json.get("model"), upper);
    }

    /** @param body 已脱敏边界内成功响应，供应商按长度终止时禁止将前缀当完整产物 */
    private void rejectTruncated(String body) {
        try {
            var payload = JsonUtils.getJsonCodec().fromJson(body, Map.class);
            if (payload != null && payload.get("output") instanceof Map<?, ?> output && output.get("choices") instanceof java.util.List<?> choices)
                for (Object item : choices) if (item instanceof Map<?, ?> choice && "length".equals(choice.get("finish_reason"))) throw new HttpTransportException("MODEL_OUTPUT_TRUNCATED");
        } catch (HttpTransportException rejected) { throw rejected; }
        catch (RuntimeException invalid) { throw new HttpTransportException("MODEL_INPUT_INVALID"); }
    }
    /**
     * 发送前不可变检查结果。
     * @param body 有界文本 JSON
     * @param model 请求中的精确型号
     * @param upper 保守 Token 上界
     */
    private record Prepared(String body, String model, long upper) { }

    /**
     * 限制多模态端点仅接收文本和工具消息，媒体内容必须在申请额度前拒绝。
     * @param json 已解析请求
     */
    private static void requireTextOnly(Map<?, ?> json) {
        if (!(json.get("input") instanceof Map<?, ?> input)
                || !(input.get("messages") instanceof java.util.List<?> messages)) {
            throw new HttpTransportException("MODEL_INPUT_INVALID");
        }
        for (Object item : messages) {
            if (!(item instanceof Map<?, ?> message)) throw new HttpTransportException("MODEL_INPUT_INVALID");
            Object content = message.get("content");
            if (content == null || content instanceof String) continue;
            if (!(content instanceof java.util.List<?> blocks)) throw new HttpTransportException("MODEL_INPUT_INVALID");
            for (Object block : blocks) {
                if (!(block instanceof Map<?, ?> fields) || fields.size() != 1
                        || !(fields.get("text") instanceof String)) {
                    throw new HttpTransportException("MODEL_MEDIA_NOT_ALLOWED");
                }
            }
        }
    }

    /**
     * 从供应商完整响应读取累计用量，缺失或不合法时保留未知。
     * @param body 仅在内存中解析的响应正文
     * @return 已知 Token 或 null
     */
    private static TokenUsage readUsage(String body) {
        try {
            Map<?, ?> json = JsonUtils.getJsonCodec().fromJson(body, Map.class);
            if (json.get("usage") instanceof Map<?, ?> usage
                    && usage.get("input_tokens") instanceof Number input
                    && usage.get("output_tokens") instanceof Number output) {
                return new TokenUsage(input.longValue(), output.longValue());
            }
        } catch (RuntimeException ignored) {
            // 解析失败不能凭空补零，后续门禁根据 UNKNOWN 暂停。
        }
        return null;
    }

    /**
     * 只映射明确的免费保护码和 HTTP 分类，不传播供应商任意文本。
     * @param status HTTP 状态
     * @param body 未记录日志的错误正文
     * @return 固定错误码
     */
    private static String safeError(int status, String body) {
        try {
            Object code = JsonUtils.getJsonCodec().fromJson(body, Map.class).get("code");
            if ("AllocationQuota.FreeTierOnly".equals(code)) return "MODEL_FREE_QUOTA_EXHAUSTED";
            if ("InvalidParameter".equals(code)) return "MODEL_INVALID_PARAMETER";
            if ("InvalidApiKey".equals(code)) return "MODEL_ACCESS_DENIED";
            if ("ModelNotFound".equals(code)) return "MODEL_NOT_FOUND";
        } catch (RuntimeException ignored) {
            // 未知正文只保留数字状态，不回传自由文本。
        }
        if (status == 401 || status == 403) return "MODEL_ACCESS_DENIED";
        if (status == 429) return "MODEL_RATE_LIMITED";
        return "MODEL_PROVIDER_HTTP_" + status;
    }

    /** @param status 供应商状态 @param body 仅在本边界分类的响应 @return 免费耗尽保留类型化暂停原因，禁止被当成普通失败重试；其余只保留固定错误码 */
    private static RuntimeException providerFailure(int status, String body) {
        String code = safeError(status, body);
        try {
            Object providerCode=JsonUtils.getJsonCodec().fromJson(body,Map.class).get("code");
            if(status==400 && "context_length_exceeded".equals(providerCode)) {
                var usage=readUsage(body);
                return new HttpTransportException(usage!=null && usage.inputTokens()==0 && usage.outputTokens()==0 ? "MODEL_CONTEXT_LIMIT_SAFE" : "MODEL_CONTEXT_LIMIT");
            }
        } catch(RuntimeException invalid) { /* 非明确协议不得从自由文本推断可恢复超限。 */ }
        return "MODEL_FREE_QUOTA_EXHAUSTED".equals(code)
                ? new CallRejectedException(CallRejectedException.Reason.QUOTA_INSUFFICIENT)
                : new HttpTransportException(code);
    }

    /**
     * 每次订阅只发送一次受门禁保护的 SSE 请求，不缓冲完整生成结果。
     * @param request SDK 完成格式化后的请求
     * @return 逐个 SSE data 帧的 JSON，终止信号在响应关闭和计量结束后发出
     */
    @Override
    public Flux<String> stream(HttpRequest request) {
        return Flux.defer(() -> Flux.create(sink -> {
            var cancelled = new AtomicBoolean();
            var connection = new java.util.concurrent.atomic.AtomicReference<Call>();
            sink.onCancel(() -> {
                cancelled.set(true);
                var call = connection.get();
                if (call != null) call.cancel();
            });
            Thread.startVirtualThread(() -> {
                boolean started=false;
                try {
                    enterRequest(); started=true;
                    try(var ids=com.lawrence.travel.application.execution.ExecutionCorrelation.bind(correlation); var span=com.lawrence.travel.application.execution.ExecutionCorrelation.bindSpan(parentSpan);var traceBinding=com.lawrence.travel.application.execution.ExecutionCorrelation.bindTrace(traceParent.get())) { readStream(request, sink, cancelled, connection); }
                    if (!sink.isCancelled()) sink.complete();
                } catch (RuntimeException failure) {
                    // 只允许本边界定义的分类，不把 SDK、JSON 或网络异常正文传出去。
                    if (!sink.isCancelled()) {
                        if(failure instanceof com.lawrence.travel.application.safety.SafetyGate.Failure rejected) { sink.error(rejected); }
                        else if(failure instanceof com.lawrence.travel.application.memory.PreferenceChangedException rejected) { sink.error(rejected); }
                        else if (failure instanceof com.lawrence.travel.domain.execution.control.CallRejectedException rejected) {
                            // 保留固定领域类别，应用层才能区分等待并发与额度暂停；不携带原异常链。
                            var safe = new com.lawrence.travel.domain.execution.control.CallRejectedException(rejected.reason());
                            if (rejected.getCause() instanceof com.lawrence.travel.domain.execution.control.ContextBudgetDiagnostic diagnostic)
                                safe.initCause(new com.lawrence.travel.domain.execution.control.ContextBudgetDiagnostic(diagnostic.boundary(), diagnostic.estimated(), diagnostic.limit()));
                            sink.error(safe);
                        } else {
                            String code = failure instanceof HttpTransportException
                                    ? failure.getMessage() : "MODEL_TRANSPORT_FAILED";
                            sink.error(new HttpTransportException(code));
                        }
                    }
                } finally { if(started) exitRequest(); }
            });
        }, reactor.core.publisher.FluxSink.OverflowStrategy.ERROR));
    }

    /**
     * 后台读取实际流；取消只关闭连接，计量及许可释放必须等待此方法的实际退出。
     * @param request 原始请求，仅固定端点可发送
     * @param sink 下游数据接收器，不输出内部推理日志
     * @param cancelled 本订阅取消标记
     * @param connection 供取消回调关闭本次实际连接
     */
    private void readStream(HttpRequest request, reactor.core.publisher.FluxSink<String> sink,
                            AtomicBoolean cancelled, java.util.concurrent.atomic.AtomicReference<Call> connection) {
        if (cancelled.get()) return;
        var prepared = prepare(request);
        try (var permit = admission.begin(runId, role, "cn-beijing", "DASHSCOPE_NATIVE_REALTIME", prepared.upper())) {
            if (!permit.target().modelId().equals(prepared.model())) throw new HttpTransportException("MODEL_ID_REJECTED");
            var outgoing = new Request.Builder().url(ENDPOINT).post(RequestBody.create(prepared.body(),
                    MediaType.get("application/json; charset=utf-8")));
            request.getHeaders().forEach(outgoing::header);
            outgoing.header("X-DashScope-SSE", "enable");
            var call = client.newCall(outgoing.build());
            call.timeout().timeout(permit.timeout().toMillis(), TimeUnit.MILLISECONDS);
            connection.set(call);
            inFlight.add(call);
            if (closed.get() || cancelled.get()) call.cancel();
            try {
                TokenUsage usage=null; String failureBody=null; int failureStatus=0;
                try (var response = call.execute()) {
                    if (!response.isSuccessful()) {
                        String error = "";
                        // 错误体也有界读取，只用于固定错误分类，绝不交给 SDK 或日志。
                        if (response.body() != null) {
                            var reader = response.body().charStream();
                            var chars = new char[4096];
                            int length = reader.read(chars);
                            error = length < 0 ? "" : new String(chars, 0, length);
                        }
                        failureBody=error; failureStatus=response.code();
                    } else {
                    if (response.body() == null || !response.header("Content-Type", "").toLowerCase(java.util.Locale.ROOT)
                            .startsWith("text/event-stream")) throw new HttpTransportException("MODEL_STREAM_INVALID");
                    usage = DashScopeSseReader.read(response.body().charStream(), data -> {
                        if (!cancelled.get() && !closed.get()) sink.next(data);
                    }, () -> cancelled.get() || closed.get());
                    }
                }
                if(failureStatus!=0) { permit.complete(readUsage(failureBody)); throw providerFailure(failureStatus,failureBody); }
                // 最终累计用量只在流正常结束、关闭响应后生效；取消或截断保持 UNKNOWN。
                if (cancelled.get() || closed.get()) throw new HttpTransportException("MODEL_CANCELLED");
                permit.complete(usage);
            } catch (IOException failure) {
                throw new HttpTransportException(cancelled.get() || closed.get()
                        ? "MODEL_CANCELLED" : "MODEL_TRANSPORT_FAILED");
            } finally { inFlight.remove(call); }
        }
    }

    /** 实际请求登记与关闭互斥，避免取消检查与登记间窗口被当成空闲。 */
    private void enterRequest() { synchronized(lifecycle) { if(closed.get()) throw new HttpTransportException("MODEL_CANCELLED"); activeRequests++; } }
    /** 响应、许可及回调实际退出后归还登记，不以Future提前结束替代。 */
    private void exitRequest() { synchronized(lifecycle) { activeRequests--; lifecycle.notifyAll(); } }
    /** 调用方已取消后等待真实传输退出；中断只是取消信号，不能提前释放运行容量。 */
    public void awaitIdle() {
        boolean interrupted=Thread.interrupted();
        synchronized(lifecycle) { while(activeRequests>0) { try { lifecycle.wait(); } catch(InterruptedException signal) { interrupted=true; } } }
        if(interrupted) Thread.currentThread().interrupt();
    }
    /** 返回模型产物前再次检查权威版本；不发起外部请求，不代替C事务资格检查。 */
    public void checkContext() { contextGuard.run(); }

    /** 停止新的发送并取消实际连接；许可仍由 execute 的退出路径结算，不提前释放。 */
    @Override
    public void close() {
        synchronized(lifecycle) { closed.set(true); }
        inFlight.forEach(Call::cancel);
    }
}
