package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.event.RunEventService;
import com.lawrence.travel.application.event.RunEvents;
import com.lawrence.travel.application.execution.StagePlan;
import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.TravelStore;
import com.lawrence.travel.application.travel.TravelStorageService;
import com.lawrence.travel.domain.execution.ExecutionStep;
import com.lawrence.travel.domain.execution.StageType;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.infrastructure.execution.persistence.EngineMapper;
import com.lawrence.travel.infrastructure.travel.TravelMapper;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实本地 PostgreSQL／Redis／HTTP；身份与 Cookie 只留内存，事件来自本地确定性写入。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "travel.engine.enabled=false")
class RunEventIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private RunEventService events;
    @Autowired private ExecutionEngineStore engine;
    @Autowired private TravelStorageService travel;
    @Autowired private AccountStore accounts;
    @Autowired private BusinessTransactions transactions;
    @Autowired private TravelMapper tasks;
    @Autowired private EngineMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SessionRepository<? extends Session> sessions;
    @Autowired private TravelJson json;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final List<Fixture> fixtures = new ArrayList<>();

    /**
     * @param actor 测试身份
     * @param scope 私有任务
     * @param runId 本地运行
     * @param sessionId 不输出的会话身份
     */
    private record Fixture(UserIdentity actor, TaskScope scope, UUID runId, String sessionId) {
        /**
         * @return 禁止断言或异常默认输出会话
         */
        @Override
        public String toString() {
            return "Fixture[redacted]";
        }
    }

    /** 删除本例会话和业务记录，不影响其他账号；订阅依靠持续认证自行关闭。 */
    @AfterEach
    void clean() {
        fixtures.forEach(f -> sessions.deleteById(f.sessionId()));
        transactions.required(
                () -> {
                    for (var f : fixtures) {
                        UUID user = f.actor().id();
                        jdbc.update(
                                "UPDATE travel_task SET current_run_id=NULL WHERE user_id=?", user);
                        for (String table :
                                List.of(
                                        "run_event",
                                        "execution_start_request",
                                        "outbox_event",
                                        "execution_step",
                                        "execution_run"))
                            jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
                        jdbc.update("DELETE FROM execution_budget WHERE run_id=?", f.runId());
                        for (String table :
                                List.of(
                                        "travel_constraint_revision",
                                        "travel_task",
                                        "travel_idempotency"))
                            jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
                        jdbc.update("DELETE FROM app_user WHERE id=?", user);
                    }
                    return true;
                });
    }

    /**
     * @param actor 测试认证身份
     * @param repository 真实仓储
     * @param <S> 仓储会话类型
     * @return 仅内存保存的会话 ID；不绕过 HTTP 认证读取
     */
    private <S extends Session> String loginSession(
            UserIdentity actor, SessionRepository<S> repository) {
        S session = repository.createSession();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        actor, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session.getId();
    }

    /**
     * @return 已可靠启动但尚无业务处理器的私有运行，账号不可密码登录
     */
    private Fixture fixture() {
        UUID user = UUID.randomUUID();
        String name = "s_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        accounts.insert(user, name, "__NON_LOGIN_TEST_HASH__");
        var actor = new UserIdentity(user, name);
        var scope =
                travel.create(
                                actor,
                                UUID.randomUUID().toString(),
                                "事件测试",
                                new TravelStore.RevisionContent(List.of(), List.of(), "测试", ""))
                        .task()
                        .scope();
        var first =
                new StagePlan(
                        UUID.randomUUID(),
                        new ExecutionStep.Key(StageType.UNDERSTAND, 0, "main"),
                        null,
                        "{}",
                        List.of());
        var run = engine.start(scope, UUID.randomUUID(), first);
        var f = new Fixture(actor, scope, run.id(), loginSession(actor, sessions));
        fixtures.add(f);
        return f;
    }

    /**
     * @param f 夹具
     * @param suffix 只读任务路由
     * @return 本机 URL
     */
    private URI uri(Fixture f, String suffix) {
        return URI.create(
                "http://127.0.0.1:" + port + "/api/v1/tasks/" + f.scope().taskId() + suffix);
    }

    /**
     * @param sessionId 不输出的会话
     * @return Cookie，仅作为本机 HTTP 内存参数
     */
    private String cookie(String sessionId) {
        return "SESSION="
                + Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param f 任务夹具
     * @param actorSession 会话或空
     * @param suffix 本机路由
     * @return 内存响应，测试不打印正文和 Cookie
     */
    private HttpResponse<String> get(Fixture f, String actorSession, String suffix)
            throws Exception {
        var request = HttpRequest.newBuilder(uri(f, suffix)).timeout(Duration.ofSeconds(5));
        if (actorSession != null) request.header("Cookie", cookie(actorSession));
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * @param f 测试任务；人工事件只用于确定性补读测试，不代表业务阶段已成功
     */
    private void emit(Fixture f) {
        transactions.required(
                () -> {
                    tasks.lockTask(f.scope());
                    assertThat(mapper.event(f.scope(), f.runId(), null, "STAGE_COMPLETED")).isOne();
                    return true;
                });
    }

    /**
     * @param f 夹具
     * @param after 查询初始游标
     * @param lastId 标准重连头或空
     * @return 已打开本地流，不输出认证信息
     */
    private HttpResponse<InputStream> stream(Fixture f, String after, String lastId)
            throws Exception {
        var request =
                HttpRequest.newBuilder(
                                uri(f, "/events/stream" + (after == null ? "" : "?after=" + after)))
                        .header("Cookie", cookie(f.sessionId()));
        if (lastId != null) request.header("Last-Event-ID", lastId);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    /**
     * @param body 本机流
     * @return 下一事件 ID 或 EOF；五秒内未返回则失败并由调用方关闭流
     */
    private String nextId(BufferedReader body) throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                return pool.submit(
                                () -> {
                                    for (String line; (line = body.readLine()) != null; )
                                        if (line.startsWith("id:")) return line.substring(3).trim();
                                    return null;
                                })
                        .get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                body.close();
                throw new AssertionError("LOCAL_STREAM_TIMEOUT");
            }
        }
    }

    /**
     * @param body 本机响应流
     * @return UTF-8 SSE reader
     */
    private BufferedReader reader(InputStream body) {
        return new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
    }

    /** 有界分页不能把 nextCursor 直接跳到任务头；未来游标必须拒绝。 */
    @Test
    void pagesAreOrderedAndDoNotSkipUnreadEvents() {
        var f = fixture();
        emit(f);
        emit(f);
        var view = events.view(f.actor(), f.scope().taskId());
        assertThat(view.cursor()).endsWith(":3");
        var page = events.page(f.actor(), f.scope().taskId(), null, 1);
        assertThat(page.events()).hasSize(1);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).endsWith(":1");
        var rest = events.page(f.actor(), f.scope().taskId(), page.nextCursor(), 100);
        assertThat(rest.events()).extracting(RunEvents.Event::sequence).containsExactly("2", "3");
        assertThatThrownBy(
                        () ->
                                events.page(
                                        f.actor(),
                                        f.scope().taskId(),
                                        f.scope().taskId() + ":4",
                                        100))
                .hasMessage("STALE_VERSION");
    }

    /** 并发写者必须等待同任务计数器事务提交；中间视图不能看到较大序号先提交。 */
    @Test
    void transactionCounterFollowsCommitOrderAndRollbackLeavesNoGap() throws Exception {
        var f = fixture();
        var firstWritten = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () ->
                                    transactions.required(
                                            () -> {
                                                tasks.lockTask(f.scope());
                                                mapper.event(
                                                        f.scope(),
                                                        f.runId(),
                                                        null,
                                                        "STAGE_COMPLETED");
                                                firstWritten.countDown();
                                                try {
                                                    if (!release.await(5, TimeUnit.SECONDS))
                                                        throw new IllegalStateException(
                                                                "LOCAL_TIMEOUT");
                                                } catch (InterruptedException interrupted) {
                                                    Thread.currentThread().interrupt();
                                                    throw new IllegalStateException(
                                                            "LOCAL_INTERRUPTED");
                                                }
                                                return true;
                                            }));
            assertThat(firstWritten.await(5, TimeUnit.SECONDS)).isTrue();
            var b =
                    pool.submit(
                            () -> {
                                secondStarted.countDown();
                                emit(f);
                            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(events.view(f.actor(), f.scope().taskId()).cursor()).endsWith(":1");
                assertThat(b.isDone()).isFalse();
            } finally {
                release.countDown();
            }
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
        }
        assertThatThrownBy(
                        () ->
                                transactions.required(
                                        () -> {
                                            tasks.lockTask(f.scope());
                                            mapper.event(
                                                    f.scope(), f.runId(), null, "STAGE_COMPLETED");
                                            throw new IllegalStateException("LOCAL_ROLLBACK");
                                        }))
                .hasMessage("LOCAL_ROLLBACK");
        emit(f);
        assertThat(events.page(f.actor(), f.scope().taskId(), null, 100).events())
                .extracting(RunEvents.Event::sequence)
                .containsExactly("1", "2", "3", "4");
    }

    /** 匿名、他人任务、跨任务游标、非法及未来水位都不能建立有效订阅。 */
    @Test
    void httpAuthenticatesOwnershipAndCursor() throws Exception {
        var f = fixture();
        var other = fixture();
        assertThat(get(f, null, "/view").statusCode()).isEqualTo(401);
        assertThat(get(f, other.sessionId(), "/view").statusCode()).isEqualTo(404);
        assertThat(get(f, other.sessionId(), "/events/stream").statusCode()).isEqualTo(404);
        assertThat(
                        get(f, f.sessionId(), "/events?after=" + other.scope().taskId() + ":0")
                                .statusCode())
                .isEqualTo(400);
        assertThat(
                        get(f, f.sessionId(), "/events?after=" + f.scope().taskId() + ":999")
                                .statusCode())
                .isEqualTo(409);
        assertThat(get(f, f.sessionId(), "/events?limit=101").statusCode()).isEqualTo(400);
        assertThat(get(f, f.sessionId(), "/view").statusCode()).isEqualTo(200);
    }

    /** 快照与实际建立订阅之间提交的事件可以补读；标准头优先于旧 URL 游标。 */
    @Test
    void snapshotSubscriptionGapAndLastEventIdAreHandled() throws Exception {
        var f = fixture();
        String cursor = events.view(f.actor(), f.scope().taskId()).cursor();
        emit(f);
        var response = stream(f, f.scope().taskId() + ":999", cursor);
        assertThat(response.statusCode()).isEqualTo(200);
        try (var body = reader(response.body())) {
            assertThat(nextId(body)).isEqualTo(f.scope().taskId() + ":2");
            emit(f);
            assertThat(nextId(body)).isEqualTo(f.scope().taskId() + ":3");
        }
    }

    /** 浏览器断线不取消运行；重新连接只读取上次游标之后的已提交事件。 */
    @Test
    void disconnectedClientReconnectsWithoutCancellingRun() throws Exception {
        var f = fixture();
        String cursor;
        try (var body = reader(stream(f, null, null).body())) {
            cursor = nextId(body);
        }
        emit(f);
        try (var body = reader(stream(f, null, cursor).body())) {
            assertThat(nextId(body)).isEqualTo(f.scope().taskId() + ":2");
        }
        assertThat(engine.run(f.scope(), f.runId()).state().name()).isEqualTo("QUEUED");
    }

    /** Redis 会话撤销后既有连接关闭，且后台授权运行仍保留；重连必须重新登录。 */
    @Test
    void revokedSessionClosesExistingStreamWithoutCancellingRun() throws Exception {
        var f = fixture();
        try (var body = reader(stream(f, null, null).body())) {
            assertThat(nextId(body)).endsWith(":1");
            sessions.deleteById(f.sessionId());
            assertThat(nextId(body)).isNull();
        }
        assertThat(get(f, f.sessionId(), "/events").statusCode()).isEqualTo(401);
        assertThat(engine.run(f.scope(), f.runId()).state().name()).isEqualTo("QUEUED");
    }

    /** 账号停用也会撤销长连接读取资格，不能只依靠初次 HTTP 认证。 */
    @Test
    void disabledAccountClosesExistingStream() throws Exception {
        var f = fixture();
        try (var body = reader(stream(f, null, null).body())) {
            assertThat(nextId(body)).endsWith(":1");
            jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", f.actor().id());
            assertThat(nextId(body)).isNull();
        }
    }

    /** 旧运行诊断保留旧代次，不能改写为当前代次，前端据此保护最新进度。 */
    @Test
    void eventsRetainSourceGenerationWhenNewRunStarts() {
        var f = fixture();
        var next =
                new StagePlan(
                        UUID.randomUUID(),
                        new ExecutionStep.Key(StageType.UNDERSTAND, 0, "next"),
                        null,
                        "{}",
                        List.of());
        var run = engine.start(f.scope(), UUID.randomUUID(), next);
        var page = events.page(f.actor(), f.scope().taskId(), null, 100);
        assertThat(page.events().getFirst().generation()).isEqualTo(1);
        assertThat(page.events().getLast().generation()).isEqualTo(run.generation());
    }

    /** 实际注销接口撤销 Redis 会话并关闭已建立流；注销不会调用业务取消。 */
    @Test
    void logoutClosesStreamAndDoesNotResurrectSession() throws Exception {
        var f = fixture();
        URI csrfUri = URI.create("http://127.0.0.1:" + port + "/api/v1/auth/csrf");
        var csrfResponse =
                http.send(
                        HttpRequest.newBuilder(csrfUri)
                                .header("Cookie", cookie(f.sessionId()))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(csrfResponse.statusCode()).isEqualTo(200);
        var csrf = json.decode(csrfResponse.body(), Map.class);
        try (var body = reader(stream(f, null, null).body())) {
            assertThat(nextId(body)).endsWith(":1");
            var request =
                    HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + port + "/api/v1/auth/logout"))
                            .header("Cookie", cookie(f.sessionId()))
                            .header((String) csrf.get("headerName"), (String) csrf.get("token"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build();
            assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(204);
            assertThat(nextId(body)).isNull();
        }
        assertThat(sessions.findById(f.sessionId()) == null).isTrue();
        assertThat(engine.run(f.scope(), f.runId()).state().name()).isEqualTo("QUEUED");
    }

    /** 单用户连接数有界，第五条明确 429；现有流仍可正常读取。 */
    @Test
    void connectionCapacityIsBoundedPerUser() throws Exception {
        var f = fixture();
        var streams = new ArrayList<InputStream>();
        try {
            for (int i = 0; i < 4; i++) {
                var response = stream(f, null, null);
                assertThat(response.statusCode()).isEqualTo(200);
                streams.add(response.body());
            }
            assertThat(get(f, f.sessionId(), "/events/stream").statusCode()).isEqualTo(429);
            assertThat(nextId(reader(streams.getFirst()))).endsWith(":1");
        } finally {
            for (var body : streams) body.close();
        }
    }
}
