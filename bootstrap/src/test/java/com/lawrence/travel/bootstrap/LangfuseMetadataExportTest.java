package com.lawrence.travel.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lawrence.travel.infrastructure.evaluation.LangfuseMetadataExport;
import com.lawrence.travel.infrastructure.evaluation.LangfuseMetadataMapper;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 本地替身验证白名单、父子关联、计量未知、碰撞和故障隔离，不调用外部服务。 */
class LangfuseMetadataExportTest {
    /** 新业务JSON版本和旧文本版本均可导出，避免模式调整后轨迹被白名单丢弃。 */
    @Test
    void exportsBusinessJsonAndLegacySchemaVersions() throws Exception {
        try (var fixture = new Fixture(16)) {
            fixture.rows =
                    List.of(
                            fact(null, "COORDINATE", "COMPLETED", null, null),
                            fact(null, "COORDINATE", "COMPLETED", null, null, "business-json-v1"));
            fixture.export.pollOnce();
            await(() -> fixture.export.counters().accepted() == 2);
            String versions =
                    fixture.sink.spans.stream()
                            .map(s -> s.getAttributes().toString())
                            .collect(java.util.stream.Collectors.joining());
            assertTrue(versions.contains("business-json-v1"));
            assertTrue(versions.contains("structured-reply-v1"));
        }
    }

    /**
     * 每个模型请求独立成节点；UNKNOWN不写零Token，取消不变失败，原身份不进入属性。
     *
     * @throws Exception 等待异步断言被中断
     */
    @Test
    void preservesParentageIndividualCallsAndUnknownUsage() throws Exception {
        try (var fixture = new Fixture(16)) {
            var root = fact(null, "COORDINATE", "CANCELLED", null, null);
            var first = fact(root, "MODEL", "COMPLETED", "qwen3.8-27b", "COMPLETED");
            var second = fact(root, "MODEL", "UNKNOWN", "qwen3.8-27b", "UNKNOWN");
            fixture.rows = List.of(root, first, second);
            fixture.export.pollOnce();
            await(() -> fixture.export.counters().accepted() == 3);
            var spans = fixture.sink.spans;
            var parent =
                    spans.stream()
                            .filter(s -> s.getName().equals("COORDINATE"))
                            .findFirst()
                            .orElseThrow();
            assertEquals("UNSET", parent.getStatus().getStatusCode().name());
            for (var span : spans) {
                assertFalse(span.getAttributes().toString().contains(root.id().toString()));
                assertFalse(span.getTraceId().equals(root.traceId().toString().replace("-", "")));
                if (span.getName().equals("MODEL"))
                    assertEquals(parent.getSpanId(), span.getParentSpanId());
            }
            var unknown =
                    spans.stream()
                            .filter(
                                    s ->
                                            "UNKNOWN"
                                                    .equals(
                                                            s.getAttributes()
                                                                    .get(
                                                                            AttributeKey.stringKey(
                                                                                    "langfuse.observation.metadata.meter_state"))))
                            .findFirst()
                            .orElseThrow();
            assertEquals(
                    100L,
                    unknown.getAttributes()
                            .get(
                                    AttributeKey.longKey(
                                            "langfuse.observation.metadata.token_upper_bound")));
            assertEquals(
                    null,
                    unknown.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
            assertEquals(
                    false,
                    unknown.getAttributes()
                            .get(
                                    AttributeKey.booleanKey(
                                            "langfuse.observation.metadata.usage_known")));
            assertEquals(
                    null,
                    unknown.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")));
            assertEquals(2, spans.stream().filter(s -> s.getName().equals("MODEL")).count());
        }
    }

    /** 自由版本文字不导出；事务内误调用只计源失败，不查询或发送。 */
    @Test
    void rejectsFreeTextAndAmbientTransactions() {
        try (var fixture = new Fixture(8)) {
            var row = fact(null, "COORDINATE", "COMPLETED", null, null);
            fixture.rows =
                    List.of(
                            new LangfuseMetadataMapper.Fact(
                                    row.id(),
                                    row.traceId(),
                                    null,
                                    null,
                                    row.kind(),
                                    row.state(),
                                    row.startedAt(),
                                    row.endedAt(),
                                    "private-user-text",
                                    "",
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    true));
            fixture.export.pollOnce();
            assertEquals(1, fixture.export.counters().dropped());
            assertEquals(0, fixture.sink.spans.size());
        }
        var mapper = mock(LangfuseMetadataMapper.class);
        try (var export =
                new LangfuseMetadataExport(
                        mapper,
                        UUID.randomUUID(),
                        Instant.now(),
                        new byte[32],
                        "local-development",
                        new Sink(),
                        8)) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                export.pollOnce();
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
            assertEquals(1, export.counters().sourceFailures());
            verifyNoInteractions(mapper);
        }
    }

    /**
     * 64位候选冲突后重新分配，发送使用已建立的映射；跨重启由PG集成验证。
     *
     * @throws Exception 等待异步断言被中断
     */
    @Test
    void retriesIdCollisionAndReusesMapping() throws Exception {
        try (var fixture = new Fixture(8)) {
            var attempts = new AtomicInteger();
            doAnswer(
                            call -> {
                                if (attempts.incrementAndGet() == 1) return 0;
                                fixture.mappings.put(call.getArgument(0), call.getArgument(3));
                                return 1;
                            })
                    .when(fixture.mapper)
                    .allocate(any(), any(), any(), any());
            var row = fact(null, "COORDINATE", "COMPLETED", null, null);
            fixture.rows = List.of(row);
            fixture.export.pollOnce();
            await(() -> fixture.export.counters().accepted() == 1);
            assertEquals(2, attempts.get());
            assertEquals(fixture.mappings.get(row.id()), fixture.sink.spans.getFirst().getSpanId());
        }
    }

    /**
     * 平台失败最多发送3次，同一节点始终使用相同ID，不影响原事实。
     *
     * @throws Exception 等待异步断言被中断
     */
    @Test
    void stopsAfterThreeFailedAttempts() throws Exception {
        try (var fixture = new Fixture(8)) {
            fixture.sink.fail = true;
            fixture.rows = List.of(fact(null, "COORDINATE", "COMPLETED", null, null));
            for (int n = 1; n <= 3; n++) {
                fixture.export.pollOnce();
                long expected = n;
                await(() -> fixture.export.counters().failed() == expected);
            }
            fixture.export.pollOnce();
            assertEquals(3, fixture.sink.spans.size());
            assertEquals(
                    1, fixture.sink.spans.stream().map(SpanData::getSpanId).distinct().count());
        }
    }

    /**
     * 本机503替身验证SDK不再内层重试，三次适配投递严格对应三个HTTP请求。
     *
     * @throws Exception 本机服务或异步等待失败
     */
    @Test
    void httpFailureHasNoHiddenRetryLayer() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/traces",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    requests.incrementAndGet();
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                });
        server.start();
        try (var fixture = new Fixture(8)) {
            fixture.sink.delegate =
                    OtlpHttpSpanExporter.builder()
                            .setRetryPolicy(null)
                            .setEndpoint(
                                    "http://127.0.0.1:" + server.getAddress().getPort() + "/traces")
                            .setTimeout(Duration.ofSeconds(1))
                            .build();
            fixture.rows = List.of(fact(null, "COORDINATE", "COMPLETED", null, null));
            for (int n = 1; n <= 3; n++) {
                fixture.export.pollOnce();
                long expected = n;
                await(() -> fixture.export.counters().failed() == expected);
            }
            assertEquals(3, requests.get());
        } finally {
            server.stop(0);
        }
    }

    /**
     * 前一节点超时后迟到失败，不能把下一节点已经成功的结果改成失败。
     *
     * @throws Exception 异步等待被中断
     */
    @Test
    void lateFailureDoesNotPoisonNextExport() throws Exception {
        try (var fixture = new Fixture(8)) {
            var first = new CompletableResultCode();
            var second = new CompletableResultCode();
            var calls = new AtomicInteger();
            fixture.sink.delegate =
                    new SpanExporter() {
                        /** {@inheritDoc} 分别保留两个未完成结果。 */
                        @Override
                        public CompletableResultCode export(Collection<SpanData> rows) {
                            return calls.incrementAndGet() == 1 ? first : second;
                        }

                        /** {@inheritDoc} 结果由测试显式完成。 */
                        @Override
                        public CompletableResultCode flush() {
                            return CompletableResultCode.ofSuccess();
                        }

                        /** {@inheritDoc} 释放未完成测试结果。 */
                        @Override
                        public CompletableResultCode shutdown() {
                            first.fail();
                            second.fail();
                            return CompletableResultCode.ofSuccess();
                        }
                    };
            fixture.rows = List.of(fact(null, "COORDINATE", "COMPLETED", null, null));
            fixture.export.pollOnce();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (fixture.export.counters().failed() == 0 && System.nanoTime() < until)
                Thread.sleep(10);
            assertEquals(1, fixture.export.counters().failed());
            fixture.rows = List.of(fact(null, "COORDINATE", "COMPLETED", null, null));
            fixture.export.pollOnce();
            await(() -> calls.get() == 2);
            first.fail();
            second.succeed();
            await(() -> fixture.export.counters().accepted() == 1);
            assertEquals(1, fixture.export.counters().failed());
        }
    }

    /**
     * 慢平台占住发送线程时，入队不等待；超容量丢弃并计数。
     *
     * @throws Exception 等待异步断言被中断
     */
    @Test
    void fullQueueDropsWithoutBlockingProducer() throws Exception {
        try (var fixture = new Fixture(1)) {
            fixture.sink.blocked = new CompletableResultCode();
            fixture.rows = List.of(fact(null, "COORDINATE", "COMPLETED", null, null));
            fixture.export.pollOnce();
            await(() -> fixture.sink.spans.size() == 1);
            fixture.rows =
                    List.of(
                            fact(null, "COORDINATE", "COMPLETED", null, null),
                            fact(null, "COORDINATE", "COMPLETED", null, null));
            long before = System.nanoTime();
            fixture.export.pollOnce();
            assertTrue(System.nanoTime() - before < 1_000_000_000L);
            assertEquals(1, fixture.export.counters().dropped());
            fixture.sink.blocked.succeed();
        }
    }

    /**
     * 构造纯合成节点；子节点复用trace，模型计量按状态设置。
     *
     * @param parent 父节点，可空
     * @param kind 节点分类
     * @param state 执行状态
     * @param model 型号，可空
     * @param meter 计量状态，可空
     * @return 无业务正文的合成事实
     */
    private static LangfuseMetadataMapper.Fact fact(
            LangfuseMetadataMapper.Fact parent,
            String kind,
            String state,
            String model,
            String meter) {
        return fact(parent, kind, state, model, meter, "structured-reply-v1");
    }

    /**
     * 构造指定输出版本的合成事实，不能携带业务正文。
     *
     * @param parent 父节点，可空
     * @param kind 节点类型
     * @param state 状态
     * @param model 型号，可空
     * @param meter 计量状态，可空
     * @param schema 明确的输出模式版本
     * @return 本地导出测试事实
     */
    private static LangfuseMetadataMapper.Fact fact(
            LangfuseMetadataMapper.Fact parent,
            String kind,
            String state,
            String model,
            String meter,
            String schema) {
        var start = Instant.now();
        boolean known = "COMPLETED".equals(meter);
        return new LangfuseMetadataMapper.Fact(
                UUID.randomUUID(),
                parent == null ? UUID.randomUUID() : parent.traceId(),
                parent == null ? null : parent.id(),
                null,
                kind,
                state,
                start,
                start.plusMillis(10),
                "coordinator",
                schema,
                model,
                meter,
                model == null ? null : 100L,
                known ? 10L : null,
                known ? 5L : null,
                model == null ? null : 10L,
                true);
    }

    /**
     * 至多等待5秒，不用固定长sleep冒充异步完成。
     *
     * @param condition 目标状态
     * @throws Exception 中断或未达到状态
     */
    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }

    /** 无网络导出替身，可模拟失败或挂起。 */
    private static final class Sink implements SpanExporter {
        private final List<SpanData> spans = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean fail;
        private volatile CompletableResultCode blocked;
        private volatile SpanExporter delegate;

        /** {@inheritDoc} 保存合成节点，不调用网络。 */
        @Override
        public CompletableResultCode export(Collection<SpanData> values) {
            spans.addAll(values);
            if (delegate != null) return delegate.export(values);
            return blocked != null
                    ? blocked
                    : fail ? CompletableResultCode.ofFailure() : CompletableResultCode.ofSuccess();
        }

        /** {@inheritDoc} 同步替身无需刷新。 */
        @Override
        public CompletableResultCode flush() {
            return delegate == null ? CompletableResultCode.ofSuccess() : delegate.flush();
        }

        /** {@inheritDoc} 释放挂起的合成请求。 */
        @Override
        public CompletableResultCode shutdown() {
            if (blocked != null) blocked.succeed();
            return delegate == null ? CompletableResultCode.ofSuccess() : delegate.shutdown();
        }
    }

    /** 模拟映射持久状态，独立于导出器内存。 */
    private static final class Fixture implements AutoCloseable {
        private final LangfuseMetadataMapper mapper = mock(LangfuseMetadataMapper.class);
        private final Map<UUID, String> mappings = new ConcurrentHashMap<>();
        private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();
        private final Set<UUID> done = ConcurrentHashMap.newKeySet();
        private final Sink sink = new Sink();
        private final LangfuseMetadataExport export;
        private volatile List<LangfuseMetadataMapper.Fact> rows = List.of();

        /**
         * 装配不访问数据库/网络的替身。
         *
         * @param capacity 有界队列容量
         */
        Fixture(int capacity) {
            when(mapper.mapping(any())).thenAnswer(call -> mappings.get(call.getArgument(0)));
            when(mapper.allocate(any(), any(), any(), any()))
                    .thenAnswer(
                            call -> {
                                mappings.putIfAbsent(call.getArgument(0), call.getArgument(3));
                                return 1;
                            });
            when(mapper.attempt(any()))
                    .thenAnswer(
                            call -> {
                                UUID id = call.getArgument(0);
                                return attempts.merge(id, 1, Integer::sum) <= 3 ? 1 : 0;
                            });
            doAnswer(
                            call -> {
                                UUID id = call.getArgument(0);
                                if ((boolean) call.getArgument(1) || attempts.get(id) >= 3)
                                    done.add(id);
                                return null;
                            })
                    .when(mapper)
                    .result(any(), anyBoolean());
            when(mapper.pending(any(), any(), anyInt()))
                    .thenAnswer(call -> rows.stream().filter(r -> !done.contains(r.id())).toList());
            export =
                    new LangfuseMetadataExport(
                            mapper,
                            UUID.randomUUID(),
                            Instant.now().minusSeconds(1),
                            new byte[32],
                            "local-development",
                            sink,
                            capacity);
        }

        /** 关闭测试线程，释放挂起请求。 */
        @Override
        public void close() {
            if (sink.blocked != null) sink.blocked.succeed();
            export.close();
        }
    }
}
