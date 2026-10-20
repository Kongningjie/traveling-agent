package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.evaluation.Evaluation;
import com.lawrence.travel.application.evaluation.SessionEvaluationExecutor;
import com.lawrence.travel.infrastructure.evaluation.HttpSessionEvaluationDriver;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 会话适配的授权、用量、负例和实际回环HTTP协议；不读取任何真实服务密钥。 */
class SessionEvaluationExecutorTest {
    /** P24的调用前拒绝需要独立审计证据；零调用不能冒充模型生成，也不应为验收突破保护。 */
    @Test void expectedAdmissionDenialRequiresIndependentProof() {
        var input = new Evaluation.Case("P24", "HOLDOUT", "SAFETY_RECOVERY", "合成门禁负例", Map.of("script", "[]"), Map.of());
        var metrics = Map.of("actualModelRequests", 0L, "unknownModelRequests", 0L, "actualInputTokens", 0L, "actualOutputTokens", 0L, "verifiedAdmissionDenials", 1L);
        var value = new SessionEvaluationExecutor(Evaluation.Mode.REAL_MODEL,
                ignored -> new SessionEvaluationExecutor.Capture(UUID.randomUUID(), UUID.randomUUID(), "PAUSED_EXPECTED", 1, Map.of(), metrics, List.of("ADMISSION_DENIED")),
                (id, capture) -> Map.of("KNOWN", true), true);
        assertThat(value.execute(input).outcome()).isEqualTo("PAUSED_EXPECTED");
        assertThat(value.execute(input).checks()).containsEntry("KNOWN", true);
        assertThat(value.execute(input("[]")).outcome()).isEqualTo("PAUSED_REAL_USAGE_MISSING");
    }
    /** 未授权真实模式不进入Driver，历史授权或单纯构造不发请求。 */
    @Test void unauthorizedDriverNeverRuns() {
        var calls = new AtomicInteger(); var executor = new SessionEvaluationExecutor(Evaluation.Mode.REAL_MODEL, input -> { calls.incrementAndGet(); throw new AssertionError("MUST_NOT_RUN"); }, (id, observed) -> Map.of(), false);
        assertThat(executor.execute(input("[]")).outcome()).isEqualTo("PAUSED_NOT_AUTHORIZED"); assertThat(calls).hasValue(0);
    }
    /** 固定本地输出不能冒充真实用量；UNKNOWN已发生请求也不能当成完整真实通过。 */
    @Test void realUsageMustExistAndBeComplete() {
        for (var metrics : List.of(Map.<String, Long>of(), Map.of("actualModelRequests", 1L, "unknownModelRequests", 1L), Map.of("actualModelRequests", 1L, "unknownModelRequests", 0L))) {
            var executor = new SessionEvaluationExecutor(Evaluation.Mode.REAL_MODEL, input -> capture(metrics), (id, observed) -> Map.of("KNOWN", true), true);
            assertThat(executor.execute(input("[]")).outcome()).startsWith("PAUSED_REAL_USAGE_");
        }
    }
    /** 本地层次保留实际指标，程序裁判只能读取执行后观察，不能由模型自评替代。 */
    @Test void localJudgeGetsObservedState() {
        var executor = new SessionEvaluationExecutor(Evaluation.Mode.LOCAL_CONTRACT, input -> capture(Map.of("wallMillis", 3L)), (id, observed) -> Map.of("KNOWN", observed.turns() == 2), false);
        assertThat(executor.execute(input("[]")).checks()).containsEntry("KNOWN", true);
        assertThat(executor.mode()).isEqualTo(Evaluation.Mode.LOCAL_CONTRACT);
    }
    /** 后端接口必须是本机，不允许把配置错误的密钥身份发送到任意网络地址。 */
    @Test void nonLoopbackAndCredentialUrlsAreRejected() {
        for (String url : List.of("https://example.invalid/", "http://user:password@localhost/", "http://localhost/api", "http://localhost/?x=1")) assertThatThrownBy(() -> driver(URI.create(url))).hasMessage("EVALUATION_LOOPBACK_REQUIRED");
    }
    /** 坏脚本尾部在首个HTTP请求前拒绝，不先执行合法前缀再发现错误。 */
    @Test void invalidTailIsRejectedBeforeConnection() {
        var value = driver(URI.create("http://127.0.0.1:1/"));
        assertThatThrownBy(() -> value.execute(input("[{\"action\":\"MESSAGE\",\"text\":\"你好\"},{\"action\":\"ARBITRARY_SHELL\"}]"))).hasMessage("SESSION_ACTION_INVALID");
        assertThatThrownBy(() -> value.execute(input("[{\"action\":\"DELETE_DOCUMENT\"}]"))).hasMessage("SESSION_DOCUMENT_MISSING");
        var missing = new HttpSessionEvaluationDriver(URI.create("http://127.0.0.1:1/"), (user, chat, turns) -> capture(Map.of()), name -> null, () -> false, Duration.ofSeconds(1));
        assertThatThrownBy(() -> missing.execute(input("[{\"action\":\"MESSAGE\",\"text\":\"你好\"},{\"action\":\"UPLOAD\",\"fixture\":\"trip1.txt\"}]"))).hasMessage("SESSION_FIXTURE_MISSING");
    }
    /** 使用真实回环HTTP跑两轮独立会话脚本，认证值只留内存；这是替身协议证据，不写REAL_MODEL成绩。
     * @throws Exception 回环夹具启动/关闭失败 */
    @Test void loopbackSessionUsesRealRoutesWithoutOracle() throws Exception {
        var json = new TravelJson(); var count = new AtomicInteger(); var current = new java.util.concurrent.atomic.AtomicReference<String>(); var deleted = new java.util.concurrent.atomic.AtomicBoolean();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath(); Object result;
            if (path.endsWith("/csrf")) result = Map.of("headerName", "X-CSRF-TOKEN", "token", "SYNTHETIC_PROTOCOL_TOKEN");
            else if (path.endsWith("/register") || path.endsWith("/login")) result = Map.of("id", "11111111-1111-4111-8111-111111111111");
            else if (path.endsWith("/preview")) result = Map.of("revision", "UNIT_REVISION");
            else if (path.endsWith("/deletions")) { deleted.set(true); result = Map.of(); }
            else if (path.contains("/tasks/")) result = Map.of("state", "SUCCEEDED");
            else if (path.endsWith("/messages")) { var body = json.decode(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), Map.class); current.set((String) body.get("inputId")); count.incrementAndGet(); result = Map.of(); }
            else result = Map.of("chat", Map.of("id", "11111111-1111-4111-8111-111111111111", "taskId", "11111111-1111-4111-8111-111111111111"), "messages", List.of(Map.of("id", current.get(), "state", "DONE")));
            byte[] bytes = json.encode(result).getBytes(java.nio.charset.StandardCharsets.UTF_8); exchange.sendResponseHeaders(path.endsWith("/register") ? 201 : 200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var observed = driver(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).execute(input("[{\"action\":\"MESSAGE\",\"text\":\"你好\"},{\"action\":\"MESSAGE\",\"text\":\"继续咨询\"}]"));
            assertThat(count).hasValue(2); assertThat(observed.turns()).isEqualTo(2); assertThat(observed.trace()).containsExactly("MESSAGE", "MESSAGE");
            var deleting = new HttpSessionEvaluationDriver(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    (user, chat, turns) -> new SessionEvaluationExecutor.Capture(user, chat, "COMPLETED", turns, Map.of(), Map.of("actualInputTokens", deleted.get() ? 0L : 19L), List.of()),
                    name -> { throw new IllegalArgumentException("NO_FILE"); }, () -> false, Duration.ofSeconds(1));
            var afterDelete = deleting.execute(input("[{\"action\":\"MESSAGE\",\"text\":\"先咨询\"},{\"action\":\"DELETE_CHAT\"}]"));
            assertThat(deleted).isTrue(); assertThat(afterDelete.metrics()).containsEntry("actualInputTokens", 19L);
        } finally { server.stop(0); }
    }
    /** @param metrics 实测或明确未知的指标 @return 纯本地裁判夹具，不代表真实模型证据 */
    private static SessionEvaluationExecutor.Capture capture(Map<String, Long> metrics) { return new SessionEvaluationExecutor.Capture(UUID.randomUUID(), UUID.randomUUID(), "COMPLETED", 2, Map.of(), metrics, List.of("MESSAGE")); }
    /** @param script 已知测试脚本 @return 只有输入，不含Oracle */
    private static Evaluation.Case input(String script) { return new Evaluation.Case("P01", "DEVELOPMENT", "CONTEXT", "合成测试", Map.of("script", script), Map.of("source", "SYNTHETIC_TEST_DATA")); }
    /** @param endpoint 回环替身地址 @return 不带任何真实服务配置的HTTP适配 */
    private static HttpSessionEvaluationDriver driver(URI endpoint) { return new HttpSessionEvaluationDriver(endpoint, (user, chat, turns) -> new SessionEvaluationExecutor.Capture(user, chat, "COMPLETED", turns, Map.of(), Map.of(), List.of()), name -> { throw new IllegalArgumentException("FIXTURE_NOT_CONFIGURED"); }, () -> false, Duration.ofSeconds(1)); }
}
