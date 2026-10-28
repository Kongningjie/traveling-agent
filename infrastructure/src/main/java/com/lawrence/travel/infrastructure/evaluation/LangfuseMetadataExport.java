package com.lawrence.travel.infrastructure.evaluation;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 从已提交PG事实导出白名单元数据；线程、队列和重试有界，不加入业务事务。 */
public final class LangfuseMetadataExport implements AutoCloseable {
    private static final Set<String> KINDS =
            Set.of(
                    "INPUT",
                    "CHAT_INPUT",
                    "DOCUMENT_PARSE",
                    "DOCUMENT_ANALYZE",
                    "MEMORY_UPDATE",
                    "TRAVEL_RUN",
                    "STAGE",
                    "MODEL",
                    "PARSE",
                    "COORDINATE",
                    "DELEGATE",
                    "PLAN",
                    "REVIEW",
                    "CONTEXT",
                    "COMPACT",
                    "VALIDATE",
                    "COMMIT",
                    "WAIT",
                    "RETRIEVAL",
                    "TOOL");
    private static final Set<String> STATES =
            Set.of(
                    "COMPLETED",
                    "CANCELLED",
                    "SUPERSEDED",
                    "FAILED",
                    "REJECTED",
                    "TIMEOUT",
                    "UNKNOWN",
                    "PAUSED",
                    "WAITING_USER",
                    "RETRY_WAIT",
                    "SUCCEEDED",
                    "ABANDONED",
                    "WAITING_ACCEPTANCE",
                    "WAITING_DEPENDENCY",
                    "PAUSED_BUDGET",
                    "PAUSED_QUOTA");
    private static final Set<String> ROLES =
            Set.of("coordinator", "planner", "reviewer", "research");
    private static final Set<String> MODELS =
            Set.of("qwen3.8-27b", "qwen3.7-flash-2026-07-15", "qwen3.8-max-0902", "qwen3.8-flash");
    private static final Set<String> SCHEMAS =
            Set.of("structured-reply-v1", "session-context-v1", "conversation-digest-v1");
    private final LangfuseMetadataMapper mapper;
    private final UUID user;
    private final Instant since;
    private final byte[] salt;
    private final String release;
    private final ArrayBlockingQueue<LangfuseMetadataMapper.Fact> queue;
    private final Set<UUID> inflight = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService poller;
    private final Thread worker;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong sourceFailures = new AtomicLong();
    private final ThreadLocal<String[]> ids = new ThreadLocal<>();
    private final AtomicReference<CompletableResultCode> lastExport = new AtomicReference<>();
    private final SdkTracerProvider provider;

    /**
     * 建立专属导出线程；构造器不扫描数据库，start才启动周期扫描。
     *
     * @param mapper 只读事实及独立投递状态SQL
     * @param user 本轮合成用户
     * @param since 本轮开始时间，不导入历史数据
     * @param salt 伪名化密钥，至少32字节，不作为属性导出
     * @param release 已固定代码版本或local-development
     * @param exporter 本机OTLP或受控替身，不注册全局SDK
     * @param capacity 1～128条队列容量
     * @throws IllegalArgumentException 配置缺失或队列容量不合法
     */
    public LangfuseMetadataExport(
            LangfuseMetadataMapper mapper,
            UUID user,
            Instant since,
            byte[] salt,
            String release,
            SpanExporter exporter,
            int capacity) {
        if (mapper == null
                || user == null
                || since == null
                || salt == null
                || salt.length < 32
                || release == null
                || !release.matches("[0-9a-f]{40}|local-development")
                || exporter == null
                || capacity < 1
                || capacity > 128) {
            throw new IllegalArgumentException("LANGFUSE_METADATA_CONFIG_INVALID");
        }
        this.mapper = mapper;
        this.user = user;
        this.since = since;
        this.salt = salt.clone();
        this.release = release;
        this.queue = new ArrayBlockingQueue<>(capacity);
        IdGenerator generator =
                new IdGenerator() {
                    /** {@inheritDoc} 使用PG持久映射，不截断业务UUID。 */
                    @Override
                    public String generateSpanId() {
                        return ids.get()[1];
                    }

                    /** {@inheritDoc} 使用伪名化trace身份。 */
                    @Override
                    public String generateTraceId() {
                        return ids.get()[0];
                    }
                };
        SpanExporter checked =
                new SpanExporter() {
                    /** {@inheritDoc} 保存本次发送的独立结果，不让迟到回调影响下一条。 */
                    @Override
                    public CompletableResultCode export(Collection<SpanData> spans) {
                        var result = exporter.export(spans);
                        lastExport.set(result);
                        return result;
                    }

                    /** {@inheritDoc} 刷新专属导出器。 */
                    @Override
                    public CompletableResultCode flush() {
                        return exporter.flush();
                    }

                    /** {@inheritDoc} 关闭专属导出器。 */
                    @Override
                    public CompletableResultCode shutdown() {
                        return exporter.shutdown();
                    }
                };
        this.provider =
                SdkTracerProvider.builder()
                        .setIdGenerator(generator)
                        .setResource(
                                Resource.create(
                                        Attributes.of(
                                                AttributeKey.stringKey("service.name"),
                                                "traveling-agent-metadata-validation")))
                        .addSpanProcessor(SimpleSpanProcessor.create(checked))
                        .build();
        this.poller =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon().name("langfuse-metadata-poll").factory());
        this.worker =
                Thread.ofPlatform().daemon().name("langfuse-metadata-send").start(this::consume);
    }

    /** 周期读取合成用户已提交事实；重复start不重复注册线程。 */
    public void start() {
        if (!closed.get() && started.compareAndSet(false, true)) {
            try {
                mapper.recoverExhausted(user);
            } catch (RuntimeException failure) {
                sourceFailures.incrementAndGet();
            }
            poller.scheduleWithFixedDelay(this::pollOnce, 0, 2, TimeUnit.SECONDS);
        }
    }

    /** 扫描至多16条并尝试入队；满队列丢弃观测，不阻塞业务、不改变原执行记录。 */
    public void pollOnce() {
        if (closed.get()) return;
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            sourceFailures.incrementAndGet();
            return;
        }
        try {
            for (var row : mapper.pending(user, since, 16)) {
                if (closed.get()) return;
                if (!inflight.add(row.id())) continue;
                try {
                    mapping(row.id(), row.traceId());
                    if (!valid(row) || !queue.offer(row)) {
                        mapper.drop(row.id());
                        inflight.remove(row.id());
                        dropped.incrementAndGet();
                    }
                } catch (RuntimeException failure) {
                    inflight.remove(row.id());
                    sourceFailures.incrementAndGet();
                }
            }
        } catch (RuntimeException failure) {
            // 只记录固定计数，不转储SQL、数据库异常或业务原文。
            sourceFailures.incrementAndGet();
        }
    }

    /** 独立线程串行发送；每条最多3次，传输失败不回调或重放原业务。 */
    private void consume() {
        while (!closed.get()) {
            LangfuseMetadataMapper.Fact row = null;
            try {
                row = queue.take();
                if (mapper.attempt(row.id()) == 1) {
                    boolean ok = publish(row);
                    mapper.result(row.id(), ok);
                    inflight.remove(row.id());
                    if (ok) accepted.incrementAndGet();
                    else failed.incrementAndGet();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException failure) {
                sourceFailures.incrementAndGet();
                if (row != null) {
                    failed.incrementAndGet();
                    try {
                        mapper.result(row.id(), false);
                    } catch (RuntimeException ignored) {
                        sourceFailures.incrementAndGet();
                    }
                }
            } finally {
                if (row != null) inflight.remove(row.id());
            }
        }
    }

    /**
     * 将已提交事实转为一个结束节点，使用原时间；网络发送不持有PG事务。
     *
     * @param row 已通过白名单的元数据
     * @return 是否完成本次HTTP接收，不表示平台查询验收通过
     */
    private boolean publish(LangfuseMetadataMapper.Fact row) {
        String trace = pseudonym("trace", row.traceId());
        String span = mapping(row.id(), row.traceId());
        Context parent = Context.root();
        if (row.parentId() != null) {
            String parentSpan = mapping(row.parentId(), row.traceId());
            var context =
                    SpanContext.createFromRemoteParent(
                            trace, parentSpan, TraceFlags.getSampled(), TraceState.getDefault());
            parent = parent.with(io.opentelemetry.api.trace.Span.wrap(context));
        }
        ids.set(new String[] {trace, span});
        lastExport.set(null);
        try {
            var observation =
                    provider.get("traveling-agent.metadata")
                            .spanBuilder(row.kind())
                            .setParent(parent)
                            .setStartTimestamp(row.startedAt())
                            .setAttribute("langfuse.environment", "local-contract")
                            .setAttribute("langfuse.trace.name", "Travel metadata validation")
                            .setAttribute("langfuse.release", release)
                            .setAttribute("langfuse.user.id", pseudonym("user", user))
                            .setAttribute(
                                    "langfuse.trace.metadata.pg_span", pseudonym("span", row.id()))
                            .setAttribute(
                                    "langfuse.observation.metadata.execution_state", row.state())
                            .setAttribute("langfuse.observation.metadata.role", row.role())
                            .setAttribute(
                                    "langfuse.version",
                                    row.schema().isEmpty() ? "metadata-v1" : row.schema())
                            .setAttribute(
                                    "langfuse.observation.type",
                                    row.modelId() == null ? "span" : "generation")
                            .startSpan();
            if (row.chatId() != null)
                observation.setAttribute("langfuse.session.id", pseudonym("chat", row.chatId()));
            if (row.modelId() != null) {
                // 型号仅作元数据；尚未核实价格映射，不触发平台按型号自动估价。
                observation.setAttribute(
                        "langfuse.observation.metadata.actual_model", row.modelId());
                observation.setAttribute("langfuse.observation.metadata.cost_state", "UNVERIFIED");
                observation.setAttribute(
                        "langfuse.observation.metadata.meter_state", row.meterState());
                observation.setAttribute(
                        "langfuse.observation.metadata.token_upper_bound", row.tokenUpperBound());
                boolean known = "COMPLETED".equals(row.meterState());
                observation.setAttribute("langfuse.observation.metadata.usage_known", known);
                observation.setAttribute(
                        "langfuse.observation.metadata.latency_millis", row.latencyMillis());
                if (known) {
                    observation.setAttribute("gen_ai.usage.input_tokens", row.inputTokens());
                    observation.setAttribute("gen_ai.usage.output_tokens", row.outputTokens());
                }
            }
            if (Set.of("FAILED", "REJECTED", "TIMEOUT", "UNKNOWN").contains(row.state())) {
                observation.setStatus(StatusCode.ERROR);
            }
            // CANCELLED保留分类，不recordException、不把正常停止改成执行失败。
            observation.end(row.endedAt());
            var result = lastExport.getAndSet(null);
            return result != null && result.join(5, TimeUnit.SECONDS).isSuccess();
        } finally {
            ids.remove();
        }
    }

    /**
     * 持久映射64位节点身份；主键并发或随机碰撞时读回/重新生成，最多16次。
     *
     * @param span PG节点
     * @param trace PG链路
     * @return 非零16位身份
     * @throws IllegalStateException 原节点已删除或16次分配均失败
     */
    private String mapping(UUID span, UUID trace) {
        String current = mapper.mapping(span);
        if (current != null) return current;
        for (int attempt = 0; attempt < 16; attempt++) {
            String candidate =
                    SpanId.fromLong(java.util.concurrent.ThreadLocalRandom.current().nextLong());
            if ("0000000000000000".equals(candidate)) continue;
            if (mapper.allocate(span, trace, user, candidate) == 1) return candidate;
            current = mapper.mapping(span);
            if (current != null) return current;
        }
        throw new IllegalStateException("LANGFUSE_ID_ALLOCATION_FAILED");
    }

    /**
     * 只接收固定类别/角色/版本/型号及合法数值；自由字符串不靠正则假装脱敏。
     *
     * @param row 待导出元数据
     * @return 是否满足白名单及结束/计量契约
     */
    private boolean valid(LangfuseMetadataMapper.Fact row) {
        if (!Boolean.TRUE.equals(row.parentValid())
                || !KINDS.contains(row.kind())
                || !STATES.contains(row.state())
                || row.role() == null
                || row.schema() == null
                || (!row.role().isEmpty() && !ROLES.contains(row.role()))
                || (!row.schema().isEmpty() && !SCHEMAS.contains(row.schema()))
                || row.startedAt() == null
                || row.endedAt() == null
                || row.startedAt().isBefore(since)
                || row.endedAt().isBefore(row.startedAt())) return false;
        if (row.modelId() == null) return !"MODEL".equals(row.kind()) && row.meterState() == null;
        if (!MODELS.contains(row.modelId())
                || row.tokenUpperBound() == null
                || row.tokenUpperBound() < 0
                || row.latencyMillis() == null
                || row.latencyMillis() < 0) return false;
        if ("COMPLETED".equals(row.meterState())) {
            return row.inputTokens() != null
                    && row.inputTokens() >= 0
                    && row.outputTokens() != null
                    && row.outputTokens() >= 0;
        }
        return row.meterState() != null
                && Set.of("UNKNOWN", "RESERVED").contains(row.meterState())
                && row.inputTokens() == null
                && row.outputTokens() == null;
    }

    /**
     * 以部署密钥伪名化关联身份，不导出原用户/聊天/trace UUID。
     *
     * @param type 身份命名空间
     * @param value 原身份
     * @return 128位十六进制伪名
     */
    private String pseudonym(String type, UUID value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(
                            mac.doFinal((type + ":" + value).getBytes(StandardCharsets.UTF_8)),
                            0,
                            16);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("LANGFUSE_PSEUDONYM_FAILED");
        }
    }

    /**
     * 返回进程内计数，重启清零；不冒充永久审计或平台查询成绩。
     *
     * @return 接收成功、传输失败、丢弃及源失败计数
     */
    public Counters counters() {
        return new Counters(accepted.get(), failed.get(), dropped.get(), sourceFailures.get());
    }

    /**
     * 进程内导出统计，不替代PG投递状态。
     *
     * @param accepted HTTP接收成功数
     * @param failed 失败尝试数，不等于失败业务数
     * @param dropped 本进程丢弃数
     * @param sourceFailures 数据读取或映射失败数
     */
    public record Counters(long accepted, long failed, long dropped, long sourceFailures) {}

    /** 停止本适配线程并关闭SDK，队列缺口计入丢弃；不改变业务状态或清理PG事实。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        poller.shutdownNow();
        dropped.addAndGet(queue.size());
        queue.clear();
        worker.interrupt();
        try {
            worker.join(6000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        provider.close();
        java.util.Arrays.fill(salt, (byte) 0);
    }
}
