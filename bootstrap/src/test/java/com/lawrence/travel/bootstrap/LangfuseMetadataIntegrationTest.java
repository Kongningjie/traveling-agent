package com.lawrence.travel.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lawrence.travel.infrastructure.evaluation.LangfuseMetadataExport;
import com.lawrence.travel.infrastructure.evaluation.LangfuseMetadataMapper;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 真实本机PG验证提交、归属、碰撞、恢复；显式条件才访问本机Langfuse，无真实模型。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LangfuseMetadataIntegrationTest {
    @Autowired private LangfuseMetadataMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationContext context;
    private final UUID user = UUID.randomUUID();
    private final UUID budget = UUID.randomUUID();
    private final UUID trace = UUID.randomUUID();
    private final Instant since = Instant.now().minusSeconds(1);

    /** 为当前测试创建随机合成用户和独立预算，不使用真实账号。 */
    @BeforeEach
    void setup() {
        jdbc.update(
                "INSERT INTO app_user(id,login_name,password_hash) VALUES(?,?,?)",
                user,
                "lf2_test_" + user.toString().substring(0, 8),
                "synthetic-not-a-login-hash");
        jdbc.update("INSERT INTO execution_budget(run_id) VALUES(?)", budget);
    }

    /** 只清理本例身份；映射应随诊断行级联清理，Langfuse副本的30天治理仍待LF-3。 */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM call_meter WHERE run_id=?", budget);
        jdbc.update("DELETE FROM execution_budget WHERE run_id=?", budget);
        jdbc.update("DELETE FROM app_user WHERE id=?", user);
    }

    /** 已回滚节点不会导出；其他用户或未结束节点不进入投影，正文键不读取。 */
    @Test
    void readsOnlyCommittedScopedFactsAndKeepsPerCallUsage() {
        assertTrue(mapper.validationUser(user));
        assertNotNull(mapper.validationStartedAt(user));
        assertTrue(context.getBeansOfType(LangfuseMetadataExport.class).isEmpty());
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(
                status -> {
                    node(null, "COORDINATE", "COMPLETED");
                    status.setRollbackOnly();
                });
        assertTrue(mapper.pending(user, since, 16).isEmpty());
        UUID root = node(null, "COORDINATE", "CANCELLED");
        jdbc.update(
                "UPDATE execution_trace_span SET metadata=metadata||'{\"input\":\"private-synthetic-text\"}'::jsonb WHERE id=?",
                root);
        UUID known = node(root, "MODEL", "COMPLETED");
        UUID unknown = node(root, "MODEL", "UNKNOWN");
        meter(known, true);
        meter(unknown, false);
        var rows = mapper.pending(user, since, 16);
        assertEquals(3, rows.size());
        assertTrue(mapper.pending(UUID.randomUUID(), since, 16).isEmpty());
        assertFalse(rows.toString().contains("private-synthetic-text"));
        var unresolved =
                rows.stream().filter(r -> r.id().equals(unknown)).findFirst().orElseThrow();
        assertNull(unresolved.inputTokens());
        assertNull(unresolved.outputTokens());
        assertEquals(100L, unresolved.tokenUpperBound());
        assertEquals("UNKNOWN", unresolved.meterState());
        jdbc.update("UPDATE execution_trace_span SET ended_at=NULL WHERE id=?", unknown);
        assertEquals(2, mapper.pending(user, since, 16).size());
        jdbc.update(
                "UPDATE execution_trace_span SET started_at=clock_timestamp()-INTERVAL '31 days' WHERE id=?",
                root);
        assertTrue(mapper.pending(user, since, 16).isEmpty());
        jdbc.update(
                "UPDATE app_user SET created_at=clock_timestamp()-INTERVAL '31 days' WHERE id=?",
                user);
        assertFalse(mapper.validationUser(user));
        assertNull(mapper.validationStartedAt(user));
    }

    /** 错误父级指向其他用户时，连父级版本字段也不读回；所有临时记录回滚。 */
    @Test
    void rejectsForeignParentMetadata() {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            UUID other = UUID.randomUUID();
                            UUID parent = UUID.randomUUID();
                            jdbc.update(
                                    "INSERT INTO app_user(id,login_name,password_hash) VALUES(?,?,'synthetic-not-a-login-hash')",
                                    other,
                                    "lf2_test_" + other.toString().substring(0, 8));
                            jdbc.update(
                                    "INSERT INTO execution_trace_span(id,trace_id,user_id,kind,state,ended_at,metadata) VALUES(?,?,?,'COORDINATE','COMPLETED',clock_timestamp(),'{\"schema\":\"foreign-private-version\"}'::jsonb)",
                                    parent,
                                    trace,
                                    other);
                            UUID child = node(parent, "CONTEXT", "COMPLETED");
                            jdbc.update(
                                    "UPDATE execution_trace_span SET metadata='{}'::jsonb WHERE id=?",
                                    child);
                            var row = mapper.pending(user, since, 16).getFirst();
                            assertFalse(Boolean.TRUE.equals(row.parentValid()));
                            assertEquals("", row.schema());
                            assertEquals("", row.role());
                            assertFalse(row.toString().contains("foreign-private-version"));
                            status.setRollbackOnly();
                        });
    }

    /**
     * 不同虚拟线程生成同一PG父级的子节点，导出关联不依赖发送线程ThreadLocal。
     *
     * @throws Exception 线程或异步等待被中断
     */
    @Test
    void restoresParentageAcrossThreadsAndExporterRestart() throws Exception {
        UUID root = node(null, "COORDINATE", "COMPLETED");
        Thread a = Thread.startVirtualThread(() -> node(root, "CONTEXT", "COMPLETED"));
        Thread b = Thread.startVirtualThread(() -> node(root, "COMPACT", "COMPLETED"));
        a.join();
        b.join();
        var firstSink = new Capture(null);
        try (var export = export(firstSink)) {
            export.pollOnce();
            await(() -> export.counters().accepted() == 3);
        }
        String original = mapper.mapping(root);
        assertNotNull(original);
        for (var span : firstSink.spans)
            if (!span.getSpanId().equals(original)) assertEquals(original, span.getParentSpanId());
        assertTrue(mapper.pending(user, since, 16).isEmpty());
        // 模拟响应后ACK丢失；只重发相同观测身份，不执行业务或模型。
        jdbc.update("UPDATE langfuse_span_mapping SET state='PENDING' WHERE span_id=?", root);
        var secondSink = new Capture(null);
        try (var export = export(secondSink)) {
            export.pollOnce();
            await(() -> export.counters().accepted() == 1);
        }
        assertEquals(original, secondSink.spans.getFirst().getSpanId());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM call_meter WHERE run_id=?", Integer.class, budget));
    }

    /** 同trace的64位碰撞被唯一键拒绝；耗尽3次的崩溃缺口可明确标记DROPPED。 */
    @Test
    void enforcesMappingCollisionAndExhaustedRecovery() {
        UUID first = node(null, "COORDINATE", "COMPLETED");
        UUID second = node(first, "CONTEXT", "COMPLETED");
        assertEquals(1, mapper.allocate(first, trace, user, "0000000000000001"));
        assertEquals(0, mapper.allocate(second, trace, user, "0000000000000001"));
        assertNull(mapper.mapping(second));
        for (int i = 0; i < 3; i++) assertEquals(1, mapper.attempt(first));
        mapper.recoverExhausted(user);
        assertEquals(
                "DROPPED",
                jdbc.queryForObject(
                        "SELECT state FROM langfuse_span_mapping WHERE span_id=?",
                        String.class,
                        first));
        jdbc.update("DELETE FROM execution_trace_span WHERE id=?", first);
        assertNull(mapper.mapping(first));
    }

    /** 成功运行按真实SUCCEEDED终态结束诊断，重复更新不刷新完成时间；本例全部回滚。 */
    @Test
    void successfulRunTraceGetsStableCompletionTime() {
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(
                status -> {
                    UUID task = UUID.randomUUID();
                    jdbc.update(
                            "INSERT INTO travel_task(id,user_id,title,constraint_revision,execution_generation) VALUES(?,?,'synthetic',1,1)",
                            task,
                            user);
                    jdbc.update(
                            "INSERT INTO travel_constraint_revision(user_id,task_id,revision,payload,source,difference) VALUES(?,?,1,'{}'::jsonb,'synthetic','')",
                            user,
                            task);
                    String body =
                            JsonMapper.builder()
                                    .build()
                                    .writeValueAsString(
                                            Map.of(
                                                    "id",
                                                    budget,
                                                    "scope",
                                                    Map.of("userId", user, "taskId", task),
                                                    "state",
                                                    "RUNNING",
                                                    "constraintRevision",
                                                    1,
                                                    "generation",
                                                    1));
                    jdbc.update(
                            "INSERT INTO execution_run(id,user_id,task_id,body,limits,goal,request_id,trace_id) VALUES(?,?,?,CAST(? AS jsonb),'{}'::jsonb,'CONSULTATION',?,?)",
                            budget,
                            user,
                            task,
                            body,
                            UUID.randomUUID(),
                            trace);
                    jdbc.update(
                            "UPDATE execution_run SET body=body||'{\"state\":\"SUCCEEDED\"}'::jsonb WHERE id=?",
                            budget);
                    var end =
                            jdbc.queryForObject(
                                    "SELECT ended_at FROM execution_trace_span WHERE id=?",
                                    Instant.class,
                                    budget);
                    assertNotNull(end);
                    jdbc.update("UPDATE execution_run SET body=body WHERE id=?", budget);
                    assertEquals(
                            end,
                            jdbc.queryForObject(
                                    "SELECT ended_at FROM execution_trace_span WHERE id=?",
                                    Instant.class,
                                    budget));
                    assertEquals("SUCCEEDED", mapper.pending(user, since, 16).getFirst().state());
                    jdbc.update("UPDATE execution_trace_span SET ended_at=NULL WHERE id=?", budget);
                    jdbc.update("UPDATE execution_run SET body=body WHERE id=?", budget);
                    assertNull(
                            jdbc.queryForObject(
                                    "SELECT ended_at FROM execution_trace_span WHERE id=?",
                                    Instant.class,
                                    budget));
                    status.setRollbackOnly();
                });
    }

    /**
     * 平台传输失败仍可提交取消和后续事实，未知账本不改为0或成功。
     *
     * @throws Exception 异步等待被中断
     */
    @Test
    void failedPlatformDoesNotBlockCancellationOrChangeUnknownMeter() throws Exception {
        UUID root = node(null, "COORDINATE", "CANCELLED");
        UUID call = node(root, "MODEL", "UNKNOWN");
        meter(call, false);
        SpanExporter rejected =
                new Capture(null) {
                    /** {@inheritDoc} 模拟平台拒收，不发网络。 */
                    @Override
                    public CompletableResultCode export(Collection<SpanData> spans) {
                        return CompletableResultCode.ofFailure();
                    }
                };
        try (var export = export(rejected)) {
            export.pollOnce();
            await(() -> export.counters().failed() == 2);
            assertEquals(
                    "CANCELLED",
                    jdbc.queryForObject(
                            "SELECT state FROM execution_trace_span WHERE id=?",
                            String.class,
                            root));
            assertEquals(
                    "UNKNOWN",
                    jdbc.queryForObject(
                            "SELECT status FROM call_meter WHERE id=?", String.class, call));
            assertEquals(
                    1,
                    jdbc.update(
                            "UPDATE execution_budget SET active_millis=1 WHERE run_id=?", budget));
        }
    }

    /**
     * PG投影经实际本机OTLP入库后，v2查询仍保留父子、未知标记与每请求节点。
     *
     * @throws Exception 本机平台不可用或异步等待被中断
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "LANGFUSE_LOCAL_VERIFY", matches = "true")
    void metadataIsQueryableInLocalLangfuse() throws Exception {
        String auth =
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        (System.getenv("LF_PUBLIC_KEY")
                                                        + ":"
                                                        + System.getenv("LF_SECRET_KEY"))
                                                .getBytes(StandardCharsets.UTF_8));
        var otlp =
                OtlpHttpSpanExporter.builder()
                        .setEndpoint("http://127.0.0.1:3100/api/public/otel/v1/traces")
                        .setRetryPolicy(null)
                        .setTimeout(Duration.ofSeconds(3))
                        .addHeader("Authorization", auth)
                        .addHeader("x-langfuse-ingestion-version", "4")
                        .build();
        var capture = new Capture(otlp);
        UUID root = node(null, "COORDINATE", "CANCELLED");
        meter(node(root, "MODEL", "UNKNOWN"), false);
        try (var export = export(capture)) {
            export.pollOnce();
            await(() -> export.counters().accepted() == 2);
        }
        String traceId = capture.spans.getFirst().getTraceId();
        String url =
                "http://127.0.0.1:3100/api/public/v2/observations?traceId="
                        + traceId
                        + "&fromStartTime="
                        + URLEncoder.encode(since.toString(), StandardCharsets.UTF_8)
                        + "&toStartTime="
                        + URLEncoder.encode(
                                Instant.now().plusSeconds(60).toString(), StandardCharsets.UTF_8)
                        + "&fields=core,basic,metadata,usage&limit=10";
        var request =
                HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", auth)
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
        try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            var json = JsonMapper.builder().build();
            for (int attempt = 0; attempt < 30; attempt++) {
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                var rows = json.readTree(response.body()).path("data");
                if (rows.size() == 2) {
                    var generation =
                            rows.valueStream()
                                    .filter(row -> "GENERATION".equals(row.path("type").asString()))
                                    .findFirst()
                                    .orElseThrow();
                    assertEquals(
                            mapper.mapping(root),
                            generation.path("parentObservationId").asString());
                    assertFalse(response.body().contains(user.toString()));
                    assertTrue(response.body().contains("UNKNOWN"));
                    assertTrue(response.body().contains("usage_known"));
                    return;
                }
                Thread.sleep(1000);
            }
        }
        throw new AssertionError("LANGFUSE_METADATA_NOT_QUERYABLE");
    }

    /**
     * 为本例装配受控导出器，伪名化密钥仅用于合成数据。
     *
     * @param sink 本地替身或明确启用的回环OTLP
     * @return 未启动周期扫描的导出器，由测试显式推进
     */
    private LangfuseMetadataExport export(SpanExporter sink) {
        return new LangfuseMetadataExport(
                mapper, user, since, new byte[32], "local-development", sink, 16);
    }

    /**
     * 插入无正文的合成结束节点。
     *
     * @param parent 原父级，可空
     * @param kind 固定类型
     * @param state 固定状态
     * @return 本节点ID
     */
    private UUID node(UUID parent, String kind, String state) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO execution_trace_span(id,trace_id,parent_id,user_id,kind,state,started_at,ended_at,metadata) VALUES(?,?,?,?,?,?,clock_timestamp(),clock_timestamp(),'{\"role\":\"coordinator\",\"schema\":\"structured-reply-v1\"}'::jsonb)",
                id,
                trace,
                parent,
                user,
                kind,
                state);
        return id;
    }

    /**
     * 每个节点登记独立HTTP计量，未知保留NULL。
     *
     * @param id 节点及计量共用身份
     * @param known 是否已知用量
     */
    private void meter(UUID id, boolean known) {
        jdbc.update(
                "INSERT INTO call_meter(id,run_id,kind,model_id,region,api,token_upper_bound,started_at,status,input_tokens,output_tokens,latency_millis) VALUES(?,?,'MODEL','qwen3.8-27b','test','TEST',100,clock_timestamp(),?,?,?,10)",
                id,
                budget,
                known ? "COMPLETED" : "UNKNOWN",
                known ? 10L : null,
                known ? 5L : null);
    }

    /**
     * 等待后台结果，最多5秒。
     *
     * @param condition 目标状态
     * @throws Exception 等待被中断
     */
    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }

    /** 保存SDK生成的合成节点，可选委派到真实本机平台。 */
    private static class Capture implements SpanExporter {
        private final CopyOnWriteArrayList<SpanData> spans = new CopyOnWriteArrayList<>();
        private final SpanExporter delegate;

        /**
         * 绑定可选本机发送器。
         *
         * @param delegate 为null时无网络
         */
        Capture(SpanExporter delegate) {
            this.delegate = delegate;
        }

        /** {@inheritDoc} 仅保存本例合成元数据。 */
        @Override
        public CompletableResultCode export(Collection<SpanData> values) {
            spans.addAll(values);
            return delegate == null ? CompletableResultCode.ofSuccess() : delegate.export(values);
        }

        /** {@inheritDoc} 刷新可选委派器。 */
        @Override
        public CompletableResultCode flush() {
            return delegate == null ? CompletableResultCode.ofSuccess() : delegate.flush();
        }

        /** {@inheritDoc} 关闭可选委派器。 */
        @Override
        public CompletableResultCode shutdown() {
            return delegate == null ? CompletableResultCode.ofSuccess() : delegate.shutdown();
        }
    }
}
