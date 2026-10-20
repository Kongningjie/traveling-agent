package com.lawrence.travel.bootstrap.acceptance;

import com.lawrence.travel.bootstrap.TravelingAgentApplication;
import com.lawrence.travel.application.port.DialogueInterpreter;
import com.lawrence.travel.domain.interaction.DialogueIntent;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** 仅测试类路径内的HTTP验收进程；真实安全链/PG/Redis/Worker，理解替身固定一秒，不使用供应商。 */
public final class LocalAcceptanceProbe {
    private static final TravelJson JSON = new TravelJson();
    private static final AtomicInteger CALLS = new AtomicInteger();
    /** 工具进程不供实例化。 */
    private LocalAcceptanceProbe() { }
    /** @param args 本地进程参数，不接收密钥
     * @throws Exception 固定验收报告写入失败；原始异常不输出 */
    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("TRAVEL_LOCAL_ACCEPTANCE"))) throw new IllegalStateException("LOCAL_ACCEPTANCE_OPT_IN_REQUIRED");
        Path report = Path.of(System.getenv().getOrDefault("ACCEPTANCE_REPORT", "/report/performance.json"));
        try (var context = new SpringApplicationBuilder(TravelingAgentApplication.class, Fixture.class)
                .web(WebApplicationType.SERVLET).profiles("local").properties(Map.of(
                    "server.port", "8080", "server.address", "0.0.0.0", "spring.main.banner-mode", "off",
                    "logging.level.root", "OFF", "travel.external-calls-enabled", "false",
                    "travel.engine.enabled", "true", "travel.identity.address-logins", "1000",
                    "travel.identity.address-registrations", "1000", "travel.documents.cleanup-enabled", "false",
                    "travel.lifecycle.maintenance-enabled", "false")).run()) {
            require(!context.getEnvironment().getProperty("travel.external-calls-enabled", Boolean.class, true), "EXTERNAL_MUST_BE_DISABLED");
            if (List.of(args).contains("--serve")) { serveUi(); return; }
            var jdbc = context.getBean(JdbcTemplate.class);
            var browsers = new ArrayList<Browser>();
            for (int i = 0; i < 5; i++) browsers.add(Browser.login());
            var receipts = Collections.synchronizedList(new ArrayList<Long>());
            var reads = Collections.synchronizedList(new ArrayList<Long>());
            var stops = Collections.synchronizedList(new ArrayList<Long>());
            var jobs = Collections.synchronizedList(new ArrayList<Job>());
            var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(20)) {
                var futures = new ArrayList<java.util.concurrent.Future<?>>();
                for (int u = 0; u < 5; u++) for (int n = 0; n < 4; n++) {
                    Browser browser = browsers.get(u);
                    futures.add(pool.submit(() -> {
                        try {
                            start.await(); UUID chat = UUID.randomUUID(), input = UUID.randomUUID();
                            var body = Map.of("inputId", input.toString(), "text", "请规划一次北京旅行，先核对缺失信息，不擅自确认安排。");
                            long before = System.nanoTime(); browser.post("/chats/" + chat + "/messages", body, 200); receipts.add(System.nanoTime() - before);
                            // 同一消息重放必须命中原收件，不额外创建作业或旅行。
                            browser.post("/chats/" + chat + "/messages", body, 200);
                            jobs.add(new Job(browser, chat, input));
                        } catch (Exception error) { throw new IllegalStateException("RECEIVE_FAILED"); }
                    }));
                }
                start.countDown(); for (var future : futures) future.get(60, TimeUnit.SECONDS);
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            for (Job job : jobs) {
                Map<?, ?> state;
                while (true) {
                    long before = System.nanoTime(); state = job.browser().get("/chats/" + job.chat(), 200); reads.add(System.nanoTime() - before);
                    var messages = (List<?>) state.get("messages"); require(messages.size() == 1, "DUPLICATE_MESSAGE");
                    String status = (String) ((Map<?, ?>) messages.getFirst()).get("state");
                    if ("DONE".equals(status)) break;
                    require(!List.of("FAILED", "CLARIFY", "PAUSED").contains(status) && System.nanoTime() < deadline, "QUEUE_NOT_COMPLETED");
                    Thread.sleep(100);
                }
                String task = (String) ((Map<?, ?>) state.get("chat")).get("taskId"); require(task != null, "TRAVEL_NOT_CREATED");
                var view = job.browser().get("/tasks/" + task + "/view", 200);
                String run = (String) view.get("runId"); require(run != null, "RUN_NOT_CREATED");
                long before = System.nanoTime(); job.browser().post("/tasks/" + task + "/runs/" + run + "/cancel", Map.of(), 200); stops.add(System.nanoTime() - before);
            }
            int tasks = jdbc.queryForObject("SELECT count(*) FROM travel_task", Integer.class);
            int messages = jdbc.queryForObject("SELECT count(*) FROM dialogue_input", Integer.class);
            require(tasks == 20 && messages == 20 && CALLS.get() == 20, "DUPLICATE_OR_MISSING_WORK");
            require(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class) == 5, "USER_COUNT_INVALID");
            long receiptP95 = p95(receipts), stateP95 = p95(reads), stopP95 = p95(stops);
            var metrics = Map.of("users", 5, "travelTasks", tasks, "inputs", messages, "fixtureModelRequests", CALLS.get(),
                    "receiptP95Ms", receiptP95, "stateP95Ms", stateP95, "stopP95Ms", stopP95,
                    "fixedModelDelayMs", 1000, "statusSamples", reads.size());
            boolean passed = receiptP95 <= 2000 && stateP95 <= 2000 && stopP95 <= 2000;
            Files.createDirectories(report.getParent());
            Files.writeString(report, JSON.encode(Map.of("mode", "LOCAL_CONTRACT", "status", passed ? "PASS" : "FAIL", "metrics", metrics,
                    "externalRequests", 0, "java", System.getProperty("java.version"), "coverage", "HTTP_AUTH_PG_REDIS_QUEUE_START_STOP_NOT_MODEL_QUALITY")));
            require(passed, "P95_TARGET_NOT_MET");
            System.out.println("LOCAL_ACCEPTANCE_PASS users=5 tasks=20; report contains measured P95");
        } catch (Throwable failure) {
            if (!Files.exists(report)) { Files.createDirectories(report.getParent()); Files.writeString(report, JSON.encode(Map.of("mode", "LOCAL_CONTRACT", "status", "FAIL", "reason", "LOCAL_ACCEPTANCE_FAILED"))); }
            System.err.println("LOCAL_ACCEPTANCE_FAILED; no raw exception or credentials emitted"); System.exit(1);
        }
    }
    /** @param samples 实际纳秒样本，不包含模型完成等待 @return nearest-rank P95，向上取整毫秒 */
    private static long p95(List<Long> samples) { var sorted = samples.stream().sorted().toList(); require(!sorted.isEmpty(), "NO_SAMPLES"); return (sorted.get((int) Math.ceil(sorted.size() * .95) - 1) + 999999) / 1000000; }
    /** @param valid 确定性条件 @param code 固定分类，禁止正文或凭据进入断言 */
    private static void require(boolean valid, String code) { if (!valid) throw new IllegalStateException(code); }
    /** 浏览器专项代理仅绑定独立验收容器端口，由Compose发布到宿主回环；私有合成会话不进入工具输出。
     * @throws Exception 测试服务器启动失败，不记录原始请求或认证信息 */
    private static void serveUi() throws Exception {
        var browser = Browser.login();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("0.0.0.0", 8090), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.startsWith("/api/") || path.equals("/actuator/health")) {
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080" + exchange.getRequestURI())).timeout(Duration.ofSeconds(20));
                    for (String name : List.of("Content-Type", browser.header)) { String value = exchange.getRequestHeaders().getFirst(name); if (value != null) request.header(name, value); }
                    request.method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readNBytes(11 * 1024 * 1024)));
                    var response = browser.client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                    for (String name : List.of("Content-Type", "Cache-Control", "Content-Disposition")) response.headers().firstValue(name).ifPresent(value -> exchange.getResponseHeaders().set(name, value));
                    // Cookie保留在本进程内存；浏览器只接触同一合成用户的真实业务API投影。
                    exchange.sendResponseHeaders(response.statusCode(), 0);
                    try (var body = response.body()) { body.transferTo(exchange.getResponseBody()); }
                } else {
                    Path root = Path.of("/probe/ui").toAbsolutePath(), file = root.resolve(path.equals("/") ? "index.html" : path.substring(1)).normalize();
                    if (!file.startsWith(root) || !Files.isRegularFile(file)) { exchange.sendResponseHeaders(404, -1); return; }
                    var bytes = Files.readAllBytes(file); exchange.getResponseHeaders().set("Content-Type", file.toString().endsWith(".js") ? "application/javascript" : file.toString().endsWith(".css") ? "text/css" : "text/html; charset=utf-8");
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                }
            } catch (Exception ignored) { /* 关闭页面/停止容器会中断SSE；不输出Cookie、请求体或原始异常。 */ }
            finally { exchange.close(); }
        });
        server.start(); System.out.println("LOCAL_ACCEPTANCE_UI_READY"); new CountDownLatch(1).await();
    }
    /** @param browser 私有内存会话 @param chat 幂等聊天 @param input 幂等消息 */
    private record Job(Browser browser, UUID chat, UUID input) { }
    /** 唯一测试装配，只在显式主进程中加入，不打入生产Jar。 */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Fixture {
        /** 固定本地装配。 */
        public Fixture() { }
        /** @return 一秒理解替身，真实业务用例仍负责创建需求、调度和停止 */
        @Bean @Primary DialogueInterpreter localDialogueInterpreter() { return claim -> {
            CALLS.incrementAndGet();
            try { Thread.sleep(1000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("LOCAL_CALL_INTERRUPTED"); }
            return new DialogueIntent.Result(List.of(new DialogueIntent.Intent(DialogueIntent.Kind.PLANNING, DialogueIntent.Action.START_PLAN, claim.text())), List.of(), "本地替身已记录规划请求，尚未确认任何安排。", "", null, "", "", "");
        }; }
        /** @return 受控UI夹具直调真实解析器；不代表独立沙箱验收，不进入生产Jar */
        @Bean @Primary com.lawrence.travel.application.port.ParserProcessPort localUiParser() {
            return request -> {
                Path root = Path.of(System.getenv("TRAVEL_DOCUMENTS_ROOT")).toAbsolutePath().normalize();
                Path file = root.resolve(request.key()).normalize(); require(file.startsWith(root), "LOCAL_UI_FILE_ESCAPE");
                var report = com.lawrence.travel.infrastructure.document.IsolatedDocumentParser.parse(file, request.kind());
                return new com.lawrence.travel.application.document.Documents.Report(report.parserVersion(), report.code(), report.sections(), "受控UI夹具直调解析器；独立沙箱验收另列。" + report.notice());
            };
        }
    }
    /** 真实Cookie/CSRF客户端，仅连容器自身回环端口，不输出认证内容。 */
    private static final class Browser {
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build();
        private String header;
        private String token;
        /** 创建独立内存会话，不读取模型凭据。 */
        private Browser() { }
        /** @return 已经Redis会话登录的合成用户 @throws Exception 本地协议失败 */
        private static Browser login() throws Exception {
            var browser = new Browser(); String name = "accept_" + UUID.randomUUID().toString().replace("-", "").substring(0, 18), password = "A-" + UUID.randomUUID();
            browser.csrf(); browser.post("/auth/register", Map.of("username", name, "password", password), 201);
            browser.post("/auth/login", Map.of("username", name, "password", password), 200); browser.csrf(); return browser;
        }
        /** CSRF仅存内存；登录轮换后重新读取。 @throws Exception 本地读取失败 */
        private void csrf() throws Exception { var value = get("/auth/csrf", 200); header = (String) value.get("headerName"); token = (String) value.get("token"); }
        /** @param path 本地路径 @param status 预期状态 @return 内存JSON @throws Exception 本地失败 */
        private Map<?, ?> get(String path, int status) throws Exception { return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/api/v1" + path)).GET().build(), status); }
        /** @param path 本地写路径 @param body 不输出的请求 @param status 预期状态 @return 内存结果 @throws Exception 本地失败 */
        private Map<?, ?> post(String path, Object body, int status) throws Exception { return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/api/v1" + path)).header("Content-Type", "application/json").header(header, token).POST(HttpRequest.BodyPublishers.ofString(JSON.encode(body))).build(), status); }
        /** @param request 回环请求 @param expected 预期状态 @return 经检查JSON @throws Exception 网络失败，不附正文 */
        private Map<?, ?> send(HttpRequest request, int expected) throws Exception { var response = client.send(request, HttpResponse.BodyHandlers.ofString()); require(response.statusCode() == expected, "HTTP_STATUS_INVALID"); return JSON.decode(response.body(), Map.class); }
    }
}
