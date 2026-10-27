package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.memory.PreferenceService;
import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.memory.Preference;
import com.lawrence.travel.infrastructure.memory.PreferenceMapper;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
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
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实本地PostgreSQL／Redis／回环HTTP；无真实模型、地图或搜索调用。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "travel.engine.enabled=false")
class PreferenceIntegrationTest {
    @Autowired private PreferenceService preferences;
    @Autowired private AccountStore accounts;
    @Autowired private BusinessTransactions transactions;
    @Autowired private PreferenceMapper mapper;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private TravelJson json;

    @Autowired
    private org.springframework.session.SessionRepository<
                    ? extends org.springframework.session.Session>
            sessions;

    @org.springframework.boot.test.web.server.LocalServerPort private int port;
    private final List<UUID> users = new ArrayList<>();
    private final List<String> sessionIds = new ArrayList<>();

    /** 只清随机测试用户及级联偏好，不重建卷、不读取其他用户数据。 */
    @AfterEach
    void clean() {
        sessionIds.forEach(sessions::deleteById);
        users.forEach(user -> jdbc.update("DELETE FROM app_user WHERE id=?", user));
    }

    /**
     * @return 随机非登录凭据的本地身份；不保存或输出真实秘密
     */
    private UserIdentity actor() {
        UUID id = UUID.randomUUID();
        String name = "pref_" + id.toString().replace("-", "").substring(0, 16);
        accounts.insert(id, name, "__NON_LOGIN_TEST_HASH__");
        users.add(id);
        return new UserIdentity(id, name);
    }

    /** 待确认建议跨读取仍不参与召回，单独确认后才成为长期软偏好。 */
    @Test
    void suggestionRequiresExplicitConfirmation() {
        var actor = actor();
        UUID id = UUID.randomUUID();
        preferences.change(actor, UUID.randomUUID(), 0, id, "SUGGEST", "pace", "轻松", "");
        var pending = preferences.read(actor);
        assertThat(pending.entries().getFirst().state()).isEqualTo(Preference.State.PENDING);
        assertThat(Preference.recall(pending, java.util.Set.of("北京"), java.util.Set.of()).entries())
                .isEmpty();
        preferences.change(actor, UUID.randomUUID(), 1, id, "CONFIRM", null, null, null);
        assertThat(
                        Preference.recall(
                                        preferences.read(actor),
                                        java.util.Set.of("北京"),
                                        java.util.Set.of())
                                .entries())
                .extracting(Preference::value)
                .containsExactly("轻松");
    }

    /** 更新提升用户稳定版本；重复请求不多次提升，改义重放被拒绝。 */
    @Test
    void updateAndPermanentRequestAreVersioned() {
        var actor = actor();
        UUID id = UUID.randomUUID(), request = UUID.randomUUID();
        preferences.change(actor, request, 0, id, "SAVE", "diet", "清淡", "");
        preferences.change(actor, request, 0, id, "SAVE", "diet", "清淡", "");
        assertThat(preferences.read(actor).version().revision()).isEqualTo(1);
        assertThatThrownBy(
                        () -> preferences.change(actor, request, 0, id, "SAVE", "diet", "不同内容", ""))
                .hasMessage("IDEMPOTENCY_CONFLICT");
        preferences.change(actor, UUID.randomUUID(), 1, id, "SAVE", "diet", "素食", "");
        assertThat(preferences.read(actor).entries().getFirst().value()).isEqualTo("素食");
    }

    /** 删除墓碑保留身份，旧请求重放只返回当前状态，不重新显示或召回删除文本。 */
    @Test
    void deleteCannotBeUndoneByOldReceiptOrNewStaleWrite() {
        var actor = actor();
        UUID id = UUID.randomUUID(), request = UUID.randomUUID();
        preferences.change(actor, request, 0, id, "SAVE", "interests", "博物馆", "");
        preferences.change(actor, UUID.randomUUID(), 1, id, "DELETE", null, null, null);
        assertThat(preferences.read(actor).entries()).isEmpty();
        assertThat(preferences.read(actor).version().revision()).isEqualTo(2);
        assertThat(
                        preferences
                                .change(actor, request, 0, id, "SAVE", "interests", "博物馆", "")
                                .entries())
                .isEmpty();
        assertThatThrownBy(
                        () ->
                                preferences.change(
                                        actor,
                                        UUID.randomUUID(),
                                        2,
                                        id,
                                        "SAVE",
                                        "interests",
                                        "旧摘要复活",
                                        ""))
                .hasMessage("STALE_VERSION");
        assertThatThrownBy(
                        () ->
                                preferences.change(
                                        actor,
                                        UUID.randomUUID(),
                                        2,
                                        id,
                                        "CONFIRM",
                                        null,
                                        null,
                                        null))
                .hasMessage("STALE_VERSION");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT body::text FROM user_preference WHERE user_id=? AND id=?",
                                String.class,
                                actor.id(),
                                id))
                .doesNotContain("博物馆");
    }

    /** 同类别同范围冲突需用户选择修改；拒绝不能破坏已确认状态或提升修订。 */
    @Test
    void conflictingPreferenceIsNotSilentlyMerged() {
        var actor = actor();
        UUID id = UUID.randomUUID();
        preferences.change(actor, UUID.randomUUID(), 0, id, "SAVE", "pace", "轻松", "");
        assertThatThrownBy(
                        () ->
                                preferences.change(
                                        actor,
                                        UUID.randomUUID(),
                                        1,
                                        UUID.randomUUID(),
                                        "SUGGEST",
                                        "pace",
                                        "紧凑",
                                        ""))
                .hasMessage("STALE_VERSION");
        assertThat(preferences.read(actor).version().revision()).isEqualTo(1);
        assertThat(preferences.read(actor).entries()).hasSize(1);
    }

    /** 两个用户无法读取或修改对方身份；未找到与越权返回同类错误。 */
    @Test
    void privateRowsCannotBeConfirmedUpdatedOrDeletedByAnotherUser() {
        var first = actor();
        var second = actor();
        UUID id = UUID.randomUUID();
        preferences.change(first, UUID.randomUUID(), 0, id, "SUGGEST", "diet", "清淡", "");
        assertThat(preferences.read(second).entries()).isEmpty();
        assertThatThrownBy(
                        () ->
                                preferences.change(
                                        second,
                                        UUID.randomUUID(),
                                        0,
                                        id,
                                        "CONFIRM",
                                        null,
                                        null,
                                        null))
                .hasMessage("NOT_FOUND");
        assertThatThrownBy(
                        () ->
                                preferences.change(
                                        second,
                                        UUID.randomUUID(),
                                        0,
                                        id,
                                        "DELETE",
                                        null,
                                        null,
                                        null))
                .hasMessage("NOT_FOUND");
        assertThatThrownBy(
                        () ->
                                preferences.change(
                                        second,
                                        UUID.randomUUID(),
                                        0,
                                        id,
                                        "UPDATE",
                                        "diet",
                                        "越权内容",
                                        ""))
                .hasMessage("NOT_FOUND");
        assertThat(preferences.read(first).entries().getFirst().state())
                .isEqualTo(Preference.State.PENDING);
    }

    /** 同一版本并发修改只有一个成功，另一个冲突，不会静默丢失写入。 */
    @Test
    void concurrentCasHasOneWinner() throws Exception {
        var actor = actor();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> race(actor, "pace", start));
            var b = pool.submit(() -> race(actor, "diet", start));
            start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SAVED", "STALE_VERSION");
        }
        assertThat(preferences.read(actor).version().revision()).isEqualTo(1);
        assertThat(preferences.read(actor).entries()).hasSize(1);
    }

    /**
     * @param actor 用户
     * @param category 类别
     * @param start 同时触发
     * @return 固定结果，不传播原始SQL或用户文本
     */
    private String race(UserIdentity actor, String category, CountDownLatch start)
            throws InterruptedException {
        start.await();
        try {
            preferences.change(
                    actor, UUID.randomUUID(), 0, UUID.randomUUID(), "SAVE", category, "本地偏好", "");
            return "SAVED";
        } catch (com.lawrence.travel.application.travel.TravelStorageFailure conflict) {
            return conflict.code().name();
        }
    }

    /** 用户版本共享锁与排他删除真实互斥，不能用进程内通知代替数据库资格。 */
    @Test
    void sharedVersionLockSerializesDeletion() throws Exception {
        var actor = actor();
        UUID id = UUID.randomUUID();
        preferences.change(actor, UUID.randomUUID(), 0, id, "SAVE", "pace", "轻松", "");
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var deleting = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var reader =
                    pool.submit(
                            () ->
                                    transactions.required(
                                            () -> {
                                                assertThat(mapper.readLock(actor.id()))
                                                        .isEqualTo(1);
                                                locked.countDown();
                                                try {
                                                    if (!release.await(5, TimeUnit.SECONDS))
                                                        throw new IllegalStateException(
                                                                "TEST_LOCK_TIMEOUT");
                                                } catch (InterruptedException interrupted) {
                                                    Thread.currentThread().interrupt();
                                                    throw new IllegalStateException(
                                                            "TEST_INTERRUPTED");
                                                }
                                                return true;
                                            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var writer =
                    pool.submit(
                            () -> {
                                deleting.countDown();
                                return preferences.change(
                                        actor,
                                        UUID.randomUUID(),
                                        1,
                                        id,
                                        "DELETE",
                                        null,
                                        null,
                                        null);
                            });
            assertThat(deleting.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> writer.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(reader.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(writer.get(5, TimeUnit.SECONDS).entries()).isEmpty();
        }
    }

    /** 私有HTTP跨会话读取及POST／PATCH／DELETE全程CSRF保护，不打印会话或令牌。 */
    @Test
    void httpAuthenticationCsrfAndSessionContinuity() throws Exception {
        var actor = actor();
        String root = "http://127.0.0.1:" + port;
        var http = HttpClient.newHttpClient();
        assertThat(
                        http.send(
                                        HttpRequest.newBuilder(
                                                        URI.create(root + "/api/v1/preferences"))
                                                .GET()
                                                .build(),
                                        HttpResponse.BodyHandlers.discarding())
                                .statusCode())
                .isEqualTo(401);
        String cookie = cookie(actor);
        UUID id = UUID.randomUUID();
        var body =
                Map.of(
                        "requestId",
                        UUID.randomUUID(),
                        "revision",
                        0,
                        "id",
                        id,
                        "category",
                        "pace",
                        "value",
                        "轻松",
                        "suggestion",
                        false);
        assertThat(send(http, root, cookie, "POST", "", body, false).statusCode()).isEqualTo(403);
        assertThat(send(http, root, cookie, "POST", "", body, true).statusCode()).isEqualTo(200);
        var response = send(http, root, cookie(actor), "GET", "", null, false);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("轻松");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var update =
                Map.of(
                        "requestId",
                        UUID.randomUUID(),
                        "revision",
                        1,
                        "category",
                        "pace",
                        "value",
                        "适中");
        assertThat(send(http, root, cookie, "PATCH", "/" + id, update, false).statusCode())
                .isEqualTo(403);
        assertThat(send(http, root, cookie, "PATCH", "/" + id, update, true).statusCode())
                .isEqualTo(200);
        var deletion = Map.of("requestId", UUID.randomUUID(), "revision", 2);
        assertThat(send(http, root, cookie, "DELETE", "/" + id, deletion, false).statusCode())
                .isEqualTo(403);
        assertThat(send(http, root, cookie, "DELETE", "/" + id, deletion, true).statusCode())
                .isEqualTo(200);
        assertThat(send(http, root, cookie, "GET", "", null, false).body())
                .doesNotContain("轻松", "适中");
    }

    /**
     * @param http 本地客户端
     * @param root 回环入口
     * @param cookie 仅内存身份
     * @param method 方法
     * @param path 条目路径
     * @param body 本地数据
     * @param csrf 是否获取CSRF
     * @return 回环响应，不输出认证信息
     */
    private HttpResponse<String> send(
            HttpClient http,
            String root,
            String cookie,
            String method,
            String path,
            Object body,
            boolean csrf)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create(root + "/api/v1/preferences" + path))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json");
        if (csrf) {
            var response =
                    http.send(
                            HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/csrf"))
                                    .header("Cookie", cookie)
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            var token = json.decode(response.body(), Map.class);
            builder.header((String) token.get("headerName"), (String) token.get("token"));
        }
        return http.send(
                builder.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(json.encode(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * @param actor 本地身份
     * @return 仅当前测试内存持有的Cookie，禁止写日志
     */
    @SuppressWarnings("unchecked")
    private String cookie(UserIdentity actor) {
        var context =
                org.springframework.security.core.context.SecurityContextHolder
                        .createEmptyContext();
        context.setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        actor,
                        null,
                        List.of(
                                new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("ROLE_USER"))));
        var repository =
                (org.springframework.session.SessionRepository<org.springframework.session.Session>)
                        sessions;
        var session = repository.createSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);
        repository.save(session);
        sessionIds.add(session.getId());
        return "SESSION="
                + java.util.Base64.getEncoder()
                        .encodeToString(
                                session.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
