package com.lawrence.travel.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lawrence.travel.application.evaluation.Evaluation;
import com.lawrence.travel.application.evaluation.EvaluationRetention;
import com.lawrence.travel.application.evaluation.EvaluationScorecard;
import com.lawrence.travel.application.evaluation.EvaluationScoring;
import com.lawrence.travel.application.evaluation.EvaluationScoring.Reason;
import com.lawrence.travel.application.evaluation.EvaluationScoringService;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.application.travel.TravelStorageFailure.Code;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.infrastructure.evaluation.EvaluationRetentionMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 本机PG验证真实评分事务和并发；评分及证据均为合成夹具，不代表模型或人工质量结论。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles({"local", "langfuse-scoring-validation"})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "travel.identity.address-logins=1000",
            "travel.identity.address-registrations=1000"
        })
class EvaluationScoringIntegrationTest {
    @Autowired private EvaluationScoringService service;
    @Autowired private EvaluationRetentionMapper retention;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @Autowired
    private com.lawrence.travel.infrastructure.evaluation.EvaluationScoringMapper scoring;

    @Autowired
    private com.lawrence.travel.application.port.BusinessTransactions businessTransactions;

    @Autowired
    private org.springframework.session.SessionRepository<
                    ? extends org.springframework.session.Session>
            sessions;

    @org.springframework.boot.test.web.server.LocalServerPort private int port;
    private final com.lawrence.travel.infrastructure.travel.TravelJson json =
            new com.lawrence.travel.infrastructure.travel.TravelJson();
    private final java.util.List<Browser> browsers = new java.util.ArrayList<>();
    private final java.util.List<String> accounts = new java.util.ArrayList<>();
    private final UUID run = UUID.randomUUID();
    private final UserIdentity owner = new UserIdentity(UUID.randomUUID(), "scoring_test");
    private final UserIdentity other = new UserIdentity(UUID.randomUUID(), "scoring_other");
    private Instant started;

    /** 每例创建随机元数据运行，不产生聊天、附件、真实模型或平台记录。 */
    @BeforeEach
    void setup() {
        started = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MICROS);
        assertTrue(
                retention.register(
                        new EvaluationRetention.Registration(
                                owner.id(), run, started, "1".repeat(64), "2".repeat(64))));
    }

    /** 只删除本例运行，评分来源及历史应随外键级联清理。 */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM evaluation_retention_run WHERE run_id=?", run);
        assertEquals(
                0L,
                jdbc.queryForObject(
                        "SELECT count(*) FROM evaluation_scoring_source WHERE run_id=?",
                        Long.class,
                        run));
        assertEquals(0L, countReviews());
        for (var browser : browsers) for (var id : browser.sessionIds) sessions.deleteById(id);
        for (var account : accounts)
            jdbc.update("DELETE FROM app_user WHERE login_name=?", account);
    }

    /** 来源可原样登记但不能改写判据/程序项；确认前保持待评，不自动补2分。 */
    @Test
    void sourceIsImmutableAndUnreviewedStateRemainsPending() {
        var source = source(true, false);
        service.record(owner.id(), source);
        service.record(owner.id(), source);
        var view = service.read(owner, run);
        assertEquals(EvaluationScorecard.Status.PENDING, view.overall());
        assertEquals(0, view.revision());
        assertEquals(null, view.latest());
        assertCode(
                Code.IDEMPOTENCY_CONFLICT, () -> service.record(owner.id(), source(false, false)));
        var c = source.context();
        for (var changed :
                List.of(
                        new EvaluationScorecard.Context(
                                c.batchId(),
                                run,
                                started.minusSeconds(1),
                                c.datasetHash(),
                                c.configurationHash(),
                                c.executionEvidenceHash()),
                        new EvaluationScorecard.Context(
                                c.batchId(),
                                run,
                                started,
                                "4".repeat(64),
                                c.configurationHash(),
                                c.executionEvidenceHash()),
                        new EvaluationScorecard.Context(
                                c.batchId(),
                                run,
                                started,
                                c.datasetHash(),
                                "5".repeat(64),
                                c.executionEvidenceHash()))) {
            assertCode(
                    Code.IDEMPOTENCY_CONFLICT,
                    () ->
                            service.record(
                                    owner.id(),
                                    new EvaluationScoring.Source(
                                            changed,
                                            source.row(),
                                            source.oracle(),
                                            source.checks())));
        }
        assertCode(Code.NOT_FOUND, () -> service.read(other, run));
        assertCode(
                Code.NOT_FOUND,
                () ->
                        service.review(
                                other,
                                run,
                                command(UUID.randomUUID(), 0, view.fingerprint(), true)));
    }

    /** 等价判据集合以固定顺序入库，重放不能因Set迭代顺序变化而报内容冲突。 */
    @Test
    void equivalentOracleSetsHaveStableStorageAndReplay() {
        var base = source(true, false);
        var first =
                new EvaluationScoring.Source(
                        base.context(),
                        base.row(),
                        new Evaluation.Oracle(
                                "COMPLETED", new java.util.LinkedHashSet<>(List.of("B", "A"))),
                        Map.of("A", true, "B", true));
        var second =
                new EvaluationScoring.Source(
                        base.context(),
                        base.row(),
                        new Evaluation.Oracle(
                                "COMPLETED", new java.util.LinkedHashSet<>(List.of("A", "B"))),
                        Map.of("B", true, "A", true));
        assertEquals(List.of("A", "B"), List.copyOf(first.oracle().required()));
        assertTrue(json.encode(first).equals(json.encode(second)));
        service.record(owner.id(), first);
        service.record(owner.id(), second);
        assertEquals(EvaluationScorecard.Status.PENDING, service.read(owner, run).overall());
    }

    /** 人工高分不抵消硬门禁；重建服务后来源和回执仍由PG保存。 */
    @Test
    void manualHighScoresCannotTurnProgramFailureIntoPass() {
        service.record(owner.id(), source(false, false));
        var view = service.read(owner, run);
        var receipt =
                service.review(owner, run, command(UUID.randomUUID(), 0, view.fingerprint(), true));
        assertEquals(EvaluationScorecard.Status.FAIL, receipt.program());
        assertEquals(EvaluationScorecard.Status.FAIL, receipt.overall());
        var recreated =
                new EvaluationScoringService(
                        new com.lawrence.travel.infrastructure.evaluation
                                .PostgresEvaluationScoringStore(scoring, json),
                        businessTransactions,
                        java.time.Clock.systemUTC());
        assertEquals(receipt, recreated.read(owner, run).latest());
    }

    /** 旧请求重放只返回旧回执；后续评分已推进时不能把当前指针退回。 */
    @Test
    void idempotentReplayNeverReactivatesPreviousReview() {
        service.record(owner.id(), source(true, false));
        var view = service.read(owner, run);
        var firstCommand = command(UUID.randomUUID(), 0, view.fingerprint(), true);
        var first = service.review(owner, run, firstCommand);
        assertEquals(first, service.review(owner, run, firstCommand));
        var second =
                service.review(
                        owner, run, command(UUID.randomUUID(), 1, view.fingerprint(), false));
        assertEquals(EvaluationScorecard.Status.FAIL, second.overall());
        assertEquals(first, service.review(owner, run, firstCommand));
        assertEquals(second, service.read(owner, run).latest());
        assertNotEquals(first.review().evidenceHash(), second.review().evidenceHash());
        assertCode(
                Code.IDEMPOTENCY_CONFLICT,
                () ->
                        service.review(
                                owner,
                                run,
                                command(firstCommand.requestId(), 0, view.fingerprint(), false)));
        assertCode(
                Code.STALE_VERSION,
                () ->
                        service.review(
                                owner,
                                run,
                                command(UUID.randomUUID(), 0, view.fingerprint(), true)));
        assertEquals(2L, countReviews());
    }

    /** 缺少确认、分值越界、指纹过期或重大违规标记矛盾均拒绝且不落记录。 */
    @Test
    void invalidOrUnconfirmedRequestsCannotWriteReviews() {
        service.record(owner.id(), source(true, false));
        String fingerprint = service.read(owner, run).fingerprint();
        assertCode(
                Code.INVALID_INPUT,
                () ->
                        service.review(
                                owner,
                                run,
                                new EvaluationScoring.Command(
                                        UUID.randomUUID(),
                                        0,
                                        fingerprint,
                                        false,
                                        2,
                                        2,
                                        2,
                                        2,
                                        true,
                                        false,
                                        List.of(Reason.OK))));
        assertCode(
                Code.INVALID_INPUT,
                () ->
                        service.review(
                                owner,
                                run,
                                new EvaluationScoring.Command(
                                        UUID.randomUUID(),
                                        0,
                                        fingerprint,
                                        true,
                                        3,
                                        2,
                                        2,
                                        2,
                                        true,
                                        false,
                                        List.of(Reason.OK))));
        assertCode(
                Code.INVALID_INPUT,
                () ->
                        service.review(
                                owner,
                                run,
                                new EvaluationScoring.Command(
                                        UUID.randomUUID(),
                                        0,
                                        fingerprint,
                                        true,
                                        2,
                                        2,
                                        2,
                                        2,
                                        true,
                                        false,
                                        List.of(Reason.MAJOR_VIOLATION))));
        assertCode(
                Code.STALE_VERSION,
                () ->
                        service.review(
                                owner, run, command(UUID.randomUUID(), 0, "4".repeat(64), true)));
        assertEquals(0L, countReviews());
    }

    /** 未运行记录可以展示原因，但不能补人工分数冒充已执行。 */
    @Test
    void notRunCannotBeReviewedOrPromoted() {
        service.record(owner.id(), source(true, true));
        var view = service.read(owner, run);
        assertEquals(EvaluationScorecard.Status.NOT_RUN, view.overall());
        assertCode(
                Code.INVALID_INPUT,
                () ->
                        service.review(
                                owner,
                                run,
                                command(UUID.randomUUID(), 0, view.fingerprint(), true)));
        assertEquals(0L, countReviews());
    }

    /** 撤销后读取、追加和原回执重放都不可见；保留已有run期限不刷新。 */
    @Test
    void revokedRunHidesEvidenceAndOldReceipts() {
        service.record(owner.id(), source(true, false));
        var command = command(UUID.randomUUID(), 0, service.read(owner, run).fingerprint(), true);
        service.review(owner, run, command);
        assertTrue(retention.revoke(owner.id(), run));
        assertCode(Code.NOT_FOUND, () -> service.read(owner, run));
        assertCode(Code.NOT_FOUND, () -> service.review(owner, run, command));
        assertEquals(1L, countReviews());
    }

    /** 到期即拒绝；清理器未运行也不能读取评分历史或重放旧回执。 */
    @Test
    void expiryHidesSourceAndReviewWithoutWaitingForCleanup() {
        service.record(owner.id(), source(true, false));
        var command = command(UUID.randomUUID(), 0, service.read(owner, run).fingerprint(), true);
        service.review(owner, run, command);
        jdbc.update(
                "WITH t AS MATERIALIZED (SELECT clock_timestamp()-INTERVAL '1 second' AS n) UPDATE evaluation_retention_run SET started_at=t.n-INTERVAL '720 hours',expires_at=t.n FROM t WHERE run_id=?",
                run);
        assertCode(Code.NOT_FOUND, () -> service.read(owner, run));
        assertCode(Code.NOT_FOUND, () -> service.review(owner, run, command));
    }

    /** 整体回滚不留下人工记录或已推进的评分修订。 */
    @Test
    void reviewAndRevisionRollbackTogether() {
        service.record(owner.id(), source(true, false));
        var command = command(UUID.randomUUID(), 0, service.read(owner, run).fingerprint(), true);
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            service.review(owner, run, command);
                            status.setRollbackOnly();
                        });
        assertEquals(0L, countReviews());
        assertEquals(0, service.read(owner, run).revision());
    }

    /** 追加后跨过到期点，指针更新失败必须连追加一起回滚；只延迟SQL，不伪造PG结果。 */
    @Test
    void expiryBetweenInsertAndAdvanceRollsBackInsertedReview() {
        started =
                Instant.now()
                        .minus(java.time.Duration.ofDays(30))
                        .plusSeconds(2)
                        .truncatedTo(ChronoUnit.MICROS);
        jdbc.update(
                "UPDATE evaluation_retention_run SET started_at=?,expires_at=? WHERE run_id=?",
                java.sql.Timestamp.from(started),
                java.sql.Timestamp.from(started.plus(java.time.Duration.ofDays(30))),
                run);
        service.record(owner.id(), source(true, false));
        var delayed =
                org.mockito.Mockito.mock(
                        com.lawrence.travel.infrastructure.evaluation.EvaluationScoringMapper.class,
                        org.mockito.AdditionalAnswers.delegatesTo(scoring));
        org.mockito.Mockito.doAnswer(
                        call -> {
                            jdbc.execute("SELECT pg_sleep(2.1)");
                            return scoring.advance(
                                    call.getArgument(0),
                                    call.getArgument(1),
                                    call.getArgument(2),
                                    call.getArgument(3));
                        })
                .when(delayed)
                .advance(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any());
        var slow =
                new EvaluationScoringService(
                        new com.lawrence.travel.infrastructure.evaluation
                                .PostgresEvaluationScoringStore(delayed, json),
                        businessTransactions,
                        java.time.Clock.systemUTC());
        var command = command(UUID.randomUUID(), 0, service.read(owner, run).fingerprint(), true);
        assertCode(Code.STALE_VERSION, () -> slow.review(owner, run, command));
        assertEquals(0L, countReviews());
        assertEquals(
                0L,
                jdbc.queryForObject(
                        "SELECT revision FROM evaluation_scoring_source WHERE run_id=?",
                        Long.class,
                        run));
    }

    /**
     * 两个不同请求竞争同一修订，必须只有一个推进，不覆盖另一人的可见版本。
     *
     * @throws Exception 线程调度或测试超时
     */
    @Test
    void concurrentReviewUsesCompareAndSetRevision() throws Exception {
        service.record(owner.id(), source(true, false));
        var fingerprint = service.read(owner, run).fingerprint();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var calls =
                    List.of(
                                    command(UUID.randomUUID(), 0, fingerprint, true),
                                    command(UUID.randomUUID(), 0, fingerprint, false))
                            .stream()
                            .map(
                                    command ->
                                            pool.submit(
                                                    () -> {
                                                        ready.countDown();
                                                        if (!start.await(5, TimeUnit.SECONDS))
                                                            throw new AssertionError(
                                                                    "SCORING_START_TIMEOUT");
                                                        try {
                                                            service.review(owner, run, command);
                                                            return true;
                                                        } catch (TravelStorageFailure failure) {
                                                            assertEquals(
                                                                    Code.STALE_VERSION,
                                                                    failure.code());
                                                            return false;
                                                        }
                                                    }))
                            .toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int success = 0;
            for (var call : calls) if (call.get(10, TimeUnit.SECONDS)) success++;
            assertEquals(1, success);
            assertEquals(1L, countReviews());
            assertEquals(1, service.read(owner, run).revision());
        }
    }

    /**
     * 实际登录与Redis会话保护评分接口：匿名、缺CSRF和跨用户访问均拒绝。
     *
     * @throws Exception 本机HTTP失败
     */
    @Test
    void httpReviewRequiresLoginCsrfOwnershipAndExactSource() throws Exception {
        var first = new Browser();
        var second = new Browser();
        String path = "/api/v1/evaluations/" + run + "/review";
        assertEquals(401, first.get(path).statusCode());
        var actor = first.login();
        second.login();
        jdbc.update(
                "UPDATE evaluation_retention_run SET owner_id=? WHERE run_id=?", actor.id(), run);
        service.record(actor.id(), source(true, false));
        var view = service.read(actor, run);
        assertEquals(404, second.get(path + "?userId=" + actor.id()).statusCode());
        var command = command(UUID.randomUUID(), 0, view.fingerprint(), true);
        assertEquals(403, first.post(path, command, false).statusCode());
        var response = first.post(path, command, true);
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var receipt = json.decode(response.body(), EvaluationScoring.Receipt.class);
        assertEquals(EvaluationScorecard.Status.PASS, receipt.overall());
        assertEquals(
                receipt,
                json.decode(
                        first.post(path, command, true).body(), EvaluationScoring.Receipt.class));
        assertEquals(
                409,
                first.post(path, command(UUID.randomUUID(), 0, view.fingerprint(), true), true)
                        .statusCode());
        assertEquals(1L, countReviews());
    }

    /** 只保留测试会话于内存，不打印Cookie、CSRF或随机测试凭据。 */
    private final class Browser {
        private final java.net.CookieManager cookies =
                new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL);
        private final java.net.http.HttpClient client =
                java.net.http.HttpClient.newBuilder().cookieHandler(cookies).build();
        private final Set<String> sessionIds = new java.util.HashSet<>();

        /** 将本浏览器加入本例精确清理列表。 */
        private Browser() {
            browsers.add(this);
        }

        /**
         * 注册并登录独立合成账号，只返回非敏感身份。
         *
         * @return 服务端身份
         * @throws Exception 本机请求失败
         */
        private UserIdentity login() throws Exception {
            String name = "score_" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
            accounts.add(name);
            var credentials = Map.of("username", name, "password", "T-" + UUID.randomUUID());
            assertEquals(201, post("/api/v1/auth/register", credentials, true).statusCode());
            var response = post("/api/v1/auth/login", credentials, true);
            assertEquals(200, response.statusCode());
            return json.decode(response.body(), UserIdentity.class);
        }

        /**
         * GET本机私有路径。
         *
         * @param path 固定路径
         * @return 内存响应
         * @throws Exception 本机请求失败
         */
        private java.net.http.HttpResponse<String> get(String path) throws Exception {
            return send(
                    java.net.http.HttpRequest.newBuilder(
                                    java.net.URI.create("http://127.0.0.1:" + port + path))
                            .GET()
                            .build());
        }

        /**
         * 使用本会话CSRF提交；不带CSRF仅用于验证拒绝。
         *
         * @param path 固定路径
         * @param body 合成JSON
         * @param csrf 是否加载并携带本会话CSRF
         * @return 内存响应
         * @throws Exception 本机请求失败
         */
        private java.net.http.HttpResponse<String> post(String path, Object body, boolean csrf)
                throws Exception {
            var builder =
                    java.net.http.HttpRequest.newBuilder(
                                    java.net.URI.create("http://127.0.0.1:" + port + path))
                            .header("Content-Type", "application/json")
                            .POST(
                                    java.net.http.HttpRequest.BodyPublishers.ofString(
                                            json.encode(body)));
            if (csrf) {
                var response = get("/api/v1/auth/csrf");
                assertEquals(200, response.statusCode());
                var token = json.decode(response.body(), tools.jackson.databind.JsonNode.class);
                builder.header(token.path("headerName").asString(), token.path("token").asString());
            }
            return send(builder.build());
        }

        /**
         * 收集本浏览器观察到的会话，供测试后精确删除；不输出任何令牌。
         *
         * @param request 本机请求
         * @return 内存响应
         * @throws Exception 本机HTTP失败
         */
        private java.net.http.HttpResponse<String> send(java.net.http.HttpRequest request)
                throws Exception {
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            for (var cookie : cookies.getCookieStore().getCookies())
                if (cookie.getName().equals("SESSION") && !cookie.getValue().isEmpty())
                    sessionIds.add(
                            new String(
                                    java.util.Base64.getDecoder().decode(cookie.getValue()),
                                    java.nio.charset.StandardCharsets.UTF_8));
            return response;
        }
    }

    /**
     * 生成无正文合成程序来源，不调用模型或读取真实历史。
     *
     * @param pass 程序门禁合成值
     * @param notRun 是否未运行
     * @return 测试来源
     */
    private EvaluationScoring.Source source(boolean pass, boolean notRun) {
        var context =
                new EvaluationScorecard.Context(
                        UUID.nameUUIDFromBytes(
                                run.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        run,
                        started,
                        "1".repeat(64),
                        "2".repeat(64),
                        "3".repeat(64));
        var row =
                new Evaluation.Row(
                        "P01",
                        "DEVELOPMENT",
                        1,
                        Evaluation.Mode.LOCAL_CONTRACT,
                        notRun ? "NOT_RUN" : "PASS",
                        notRun ? null : "COMPLETED",
                        List.of(),
                        Map.of(),
                        List.of(),
                        notRun ? "NOT_AUTHORIZED" : "");
        return new EvaluationScoring.Source(
                context,
                row,
                new Evaluation.Oracle("COMPLETED", Set.of("OWNER_ISOLATED")),
                notRun ? Map.of() : Map.of("OWNER_ISOLATED", pass));
    }

    /**
     * 生成明确确认的纯合成评分，不能代表真实用户已给出四项分数。
     *
     * @param id 请求
     * @param revision 预期修订
     * @param fingerprint 来源指纹
     * @param completed 合成完成判断
     * @return 测试请求
     */
    private EvaluationScoring.Command command(
            UUID id, long revision, String fingerprint, boolean completed) {
        return new EvaluationScoring.Command(
                id,
                revision,
                fingerprint,
                true,
                2,
                2,
                2,
                completed ? 2 : 1,
                completed,
                false,
                List.of(completed ? Reason.OK : Reason.USABILITY));
    }

    /**
     * 读取本例历史数量，不通过日志或响应打印记录内容。
     *
     * @return 本例记录数
     */
    private long countReviews() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM evaluation_scoring_review WHERE run_id=?", Long.class, run);
    }

    /**
     * 只断言固定错误码，不输出SQL或证据正文。
     *
     * @param code 预期错误
     * @param action 被拒绝操作
     */
    private void assertCode(Code code, Runnable action) {
        assertEquals(code, assertThrows(TravelStorageFailure.class, action::run).code());
    }
}
