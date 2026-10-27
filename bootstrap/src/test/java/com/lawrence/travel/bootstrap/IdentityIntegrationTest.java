package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.identity.IdentityFailure;
import com.lawrence.travel.application.identity.IdentityService;
import com.lawrence.travel.application.identity.OwnershipGuard;
import com.lawrence.travel.application.port.AuthRateLimiter;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.travel.TaskScope;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实本地 PostgreSQL／Redis 与随机 HTTP 端口；凭据、Cookie 和 CSRF 均不输出。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "travel.identity.address-logins=1000",
            "travel.identity.address-registrations=1000"
        })
class IdentityIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SessionRepository<? extends Session> sessions;
    @Autowired private IdentityService identity;
    @Autowired private OwnershipGuard ownership;
    @MockitoSpyBean private AuthRateLimiter rates;
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<String> names = new ArrayList<>();
    private final List<Browser> browsers = new ArrayList<>();

    /** 仅删除本用例生成的账号和已观察到的会话，不扫描或清空业务数据。 */
    @AfterEach
    void cleanOwnFixtures() {
        for (var browser : browsers) for (String id : browser.sessionIds) sessions.deleteById(id);
        for (String name : names) jdbc.update("DELETE FROM app_user WHERE login_name=?", name);
    }

    /**
     * @return 唯一测试账号名，加入精确清理列表
     */
    private String name() {
        String value = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        names.add(value);
        return value;
    }

    /**
     * @return 仅测试内存使用的随机密码，不输出到断言信息或命令
     */
    private String password() {
        return "T-" + UUID.randomUUID();
    }

    /**
     * @return 独立 Cookie 存储，模拟一个浏览器身份
     */
    private Browser browser() {
        var value = new Browser();
        browsers.add(value);
        return value;
    }

    /** 匿名 CSRF 保护注册和登录，密码只保存哈希；规范名唯一且注册不自动登录。 */
    @Test
    void registrationIsProtectedAndStoresOnlyPasswordHash() throws Exception {
        var browser = browser();
        String name = name();
        String password = password();
        assertThat(
                        browser.post(
                                        "/register",
                                        Map.of("username", name, "password", password),
                                        null)
                                .statusCode())
                .isEqualTo(403);
        var csrf = browser.csrf();
        var created =
                browser.post("/register", Map.of("username", name, "password", password), csrf);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(browser.get("/me").statusCode()).isEqualTo(401);
        String hash =
                jdbc.queryForObject(
                        "SELECT password_hash FROM app_user WHERE login_name=?",
                        String.class,
                        name);
        assertThat(hash != null && hash.startsWith("$2") && !hash.equals(password)).isTrue();
        assertThat(safeJson(created).containsKey("passwordHash")).isFalse();
        assertThat(
                        browser.post(
                                        "/register",
                                        Map.of(
                                                "username",
                                                name.toUpperCase(java.util.Locale.ROOT),
                                                "password",
                                                password),
                                        csrf)
                                .statusCode())
                .isEqualTo(409);
    }

    /** 两个会话只获得自身身份；登录轮换 ID，旧匿名会话不能取得新身份。 */
    @Test
    void separatesTwoUsersAndRotatesSessionIdentifier() throws Exception {
        var first = browser();
        var second = browser();
        String firstName = name();
        String secondName = name();
        String firstPassword = password();
        String secondPassword = password();
        var firstCsrf = first.csrf();
        var secondCsrf = second.csrf();
        assertThat(
                        first.post(
                                        "/register",
                                        Map.of("username", firstName, "password", firstPassword),
                                        firstCsrf)
                                .statusCode())
                .isEqualTo(201);
        assertThat(
                        second.post(
                                        "/register",
                                        Map.of("username", secondName, "password", secondPassword),
                                        secondCsrf)
                                .statusCode())
                .isEqualTo(201);
        String anonymousCookie = first.cookie();
        assertThat(
                        first.post(
                                        "/login",
                                        Map.of("username", firstName, "password", password()),
                                        firstCsrf)
                                .statusCode())
                .isEqualTo(401);
        assertThat(
                        first.post(
                                        "/login",
                                        Map.of("username", firstName, "password", firstPassword),
                                        firstCsrf)
                                .statusCode())
                .isEqualTo(200);
        assertThat(
                        second.post(
                                        "/login",
                                        Map.of("username", secondName, "password", secondPassword),
                                        secondCsrf)
                                .statusCode())
                .isEqualTo(200);
        assertThat(!first.cookie().equals(anonymousCookie)).isTrue();
        var a = safeJson(first.get("/me?userId=" + safeJson(second.get("/me")).get("id")));
        var b = safeJson(second.get("/me"));
        assertThat(a.get("loginName")).isEqualTo(firstName);
        assertThat(b.get("loginName")).isEqualTo(secondName);
        var stale =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(uri("/me"))
                                        .header("Cookie", "SESSION=" + anonymousCookie)
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.discarding());
        assertThat(stale.statusCode()).isEqualTo(401);
        var userA = new UserIdentity(UUID.fromString((String) a.get("id")), firstName);
        var userB = new UserIdentity(UUID.fromString((String) b.get("id")), secondName);
        var resource = new TaskScope(userA.id(), UUID.randomUUID());
        ownership.requireOwner(userA, resource);
        assertThatThrownBy(() -> ownership.requireOwner(userB, resource)).hasMessage("NOT_FOUND");
        assertThatThrownBy(
                        () ->
                                ownership.requireRelated(
                                        userA,
                                        resource,
                                        new TaskScope(userA.id(), UUID.randomUUID())))
                .hasMessage("NOT_FOUND");
        var stored = sessions.findById(first.decodedSession());
        assertThat(stored != null).isTrue();
        assertThat(stored.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(30));
        SecurityContext context =
                stored.getAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context != null && context.getAuthentication().getCredentials() == null)
                .isTrue();
        assertThat(context.getAuthentication().getPrincipal() instanceof UserIdentity).isTrue();
    }

    /** 登录后旧 CSRF 失效，退出删除 Redis 登录会话，重放旧 Cookie 也不能访问。 */
    @Test
    void rotatesCsrfAndInvalidatesSessionOnLogout() throws Exception {
        var browser = browser();
        String name = name();
        String password = password();
        var before = browser.csrf();
        browser.post("/register", Map.of("username", name, "password", password), before);
        assertThat(
                        browser.post(
                                        "/login",
                                        Map.of("username", name, "password", password),
                                        before)
                                .statusCode())
                .isEqualTo(200);
        assertThat(browser.post("/logout", Map.of(), before).statusCode()).isEqualTo(403);
        assertThat(browser.get("/me").statusCode()).isEqualTo(200);
        String loggedCookie = browser.cookie();
        String sessionId = browser.decodedSession();
        assertThat(browser.post("/logout", Map.of(), browser.csrf()).statusCode()).isEqualTo(204);
        assertThat(sessions.findById(sessionId) == null).isTrue();
        var replay =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(uri("/me"))
                                        .header("Cookie", "SESSION=" + loggedCookie)
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.discarding());
        assertThat(replay.statusCode()).isEqualTo(401);
    }

    /** 过期会话和数据库停用均立即失去身份，不依赖浏览器清理。 */
    @Test
    void expiredAndDisabledSessionsCannotAccessPrivateIdentity() throws Exception {
        var browser = browser();
        String name = name();
        String password = password();
        var csrf = browser.csrf();
        browser.post("/register", Map.of("username", name, "password", password), csrf);
        browser.post("/login", Map.of("username", name, "password", password), csrf);
        expire(sessions, browser.decodedSession());
        assertThat(browser.get("/me").statusCode()).isEqualTo(401);
        assertThat(
                        browser.post(
                                        "/login",
                                        Map.of("username", name, "password", password),
                                        browser.csrf())
                                .statusCode())
                .isEqualTo(200);
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE login_name=?", name);
        assertThat(browser.get("/me").statusCode()).isEqualTo(401);
        assertThat(
                        browser.post(
                                        "/login",
                                        Map.of("username", name, "password", password),
                                        browser.csrf())
                                .statusCode())
                .isEqualTo(401);
    }

    /** 真正并发注册同规范名至多一方成功；实际 Redis 计数达到上限后拒绝。 */
    @Test
    void concurrentRegistrationAndRedisLimitsAreAtomic() throws Exception {
        String name = name();
        String password = password();
        var tasks = new ArrayList<CompletableFuture<Boolean>>();
        for (int i = 0; i < 4; i++)
            tasks.add(
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    identity.register(name, password, "test-" + name);
                                    return true;
                                } catch (IdentityFailure failure) {
                                    if (failure.code()
                                            != IdentityFailure.Code.REGISTRATION_REJECTED)
                                        throw failure;
                                    return false;
                                }
                            }));
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new))
                .get(20, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(tasks.stream().filter(CompletableFuture::join).count()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM app_user WHERE login_name=?",
                                Integer.class,
                                name))
                .isEqualTo(1);
        String bucket = "test-rate-" + UUID.randomUUID();
        rates.take(bucket, 2, Duration.ofMinutes(1));
        rates.take(bucket, 2, Duration.ofMinutes(1));
        assertThatThrownBy(() -> rates.take(bucket, 2, Duration.ofMinutes(1)))
                .hasMessage("RATE_LIMITED");
    }

    /** 注入明确依赖失败／限流信号，真实 HTTP 必须返回 503／429，不能建立登录。 */
    @Test
    void unavailableOrLimitedAdmissionCannotAuthenticate() throws Exception {
        var browser = browser();
        String name = name();
        String password = password();
        var csrf = browser.csrf();
        assertThat(
                        browser.post(
                                        "/register",
                                        Map.of("username", name, "password", password),
                                        csrf)
                                .statusCode())
                .isEqualTo(201);
        try {
            for (var code :
                    List.of(
                            IdentityFailure.Code.DEPENDENCY_UNAVAILABLE,
                            IdentityFailure.Code.RATE_LIMITED)) {
                org.mockito.Mockito.doThrow(new IdentityFailure(code))
                        .when(rates)
                        .take(
                                org.mockito.ArgumentMatchers.startsWith("login:address:"),
                                org.mockito.ArgumentMatchers.anyInt(),
                                org.mockito.ArgumentMatchers.any(Duration.class));
                var rejected =
                        browser.post(
                                "/login", Map.of("username", name, "password", password), csrf);
                assertThat(rejected.statusCode())
                        .isEqualTo(code == IdentityFailure.Code.DEPENDENCY_UNAVAILABLE ? 503 : 429);
                assertThat(safeJson(rejected).get("retryable")).isEqualTo(true);
                assertThat(browser.get("/me").statusCode()).isEqualTo(401);
            }
        } finally {
            org.mockito.Mockito.reset(rates);
        }
    }

    /**
     * 使用真实仓储设定已过期时间，不等待长超时也不触碰其他会话。
     * @param repository 会话仓储
     * @param id 本测试会话 ID，不能输出
     * @param <S> 实际会话类型
     */
    private <S extends Session> void expire(SessionRepository<S> repository, String id) {
        S session = repository.findById(id);
        assertThat(session != null).isTrue();
        session.setMaxInactiveInterval(Duration.ofSeconds(1));
        session.setLastAccessedTime(Instant.now().minusSeconds(10));
        repository.save(session);
    }

    /**
     * 本地目标 URI。
     * @param path 固定测试路由
     * @return 回环地址
     */
    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + "/api/v1/auth" + path);
    }

    /**
     * JSON 解码失败不将原始响应或其令牌作为异常正文。
     * @param response 仅本机响应
     * @return 内存结构
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> safeJson(HttpResponse<String> response) {
        try {
            return json.readValue(response.body(), Map.class);
        } catch (RuntimeException invalid) {
            throw new AssertionError("INVALID_RESPONSE_JSON");
        }
    }

    /**
     * 私有令牌容器，禁止默认文本泄露。
     * @param header 请求头名
     * @param token 不输出的 CSRF
     */
    private record Token(String header, String token) {
        /**
         * @return 固定脱敏说明
         */
        @Override
        public String toString() {
            return "Token[redacted]";
        }
    }

    /** 有独立 Cookie 存储的测试客户端；所有敏感内容只留内存。 */
    private final class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client =
                HttpClient.newBuilder()
                        .cookieHandler(cookies)
                        .connectTimeout(Duration.ofSeconds(3))
                        .build();
        private final Set<String> sessionIds = new HashSet<>();

        /** 创建匿名浏览器，不输出 Cookie。 */
        private Browser() {}

        /**
         * GET 后保留自己观察到的会话供清理。
         * @param path 本地路由
         * @return 内存响应
         * @throws Exception 本地网络失败
         */
        private HttpResponse<String> get(String path) throws Exception {
            return send(HttpRequest.newBuilder(uri(path)).GET().build());
        }

        /**
         * @return 当前 CSRF，仅供后续本机写请求；不输出令牌
         */
        private Token csrf() throws Exception {
            var response = get("/csrf");
            assertThat(response.statusCode()).isEqualTo(200);
            var data = safeJson(response);
            return new Token((String) data.get("headerName"), (String) data.get("token"));
        }

        /**
         * 写入本地接口，不记录正文。
         * @param path 本地路由
         * @param body 内存请求
         * @param csrf 可空，用于验证缺失令牌
         * @return 响应
         * @throws Exception 网络失败
         */
        private HttpResponse<String> post(String path, Map<String, String> body, Token csrf)
                throws Exception {
            var request =
                    HttpRequest.newBuilder(uri(path))
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            json.writeValueAsString(body)));
            if (csrf != null) request.header(csrf.header(), csrf.token());
            return send(request.build());
        }

        /**
         * @return 不对外输出的会话 Cookie 值，缺失时仅返回空串
         */
        private String cookie() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("SESSION"))
                    .map(java.net.HttpCookie::getValue)
                    .findFirst()
                    .orElse("");
        }

        /**
         * @return 本测试会话仓储 ID，不能输出
         */
        private String decodedSession() {
            return new String(
                    Base64.getDecoder().decode(cookie()), java.nio.charset.StandardCharsets.UTF_8);
        }

        /**
         * 收集 Cookie 策略断言，仅比较布尔结果避免失败时打印完整 Cookie。
         * @param request 本机请求
         * @return 内存响应
         * @throws Exception 网络失败
         */
        private HttpResponse<String> send(HttpRequest request) throws Exception {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            for (String header : response.headers().allValues("Set-Cookie")) {
                if (header.startsWith("SESSION=") && !header.startsWith("SESSION=;")) {
                    String lower = header.toLowerCase(java.util.Locale.ROOT);
                    assertThat(
                                    lower.contains("httponly")
                                            && lower.contains("samesite=lax")
                                            && lower.contains("path=/"))
                            .isTrue();
                }
            }
            if (!cookie().isEmpty()) sessionIds.add(decodedSession());
            return response;
        }
    }
}
