package com.lawrence.travel.infrastructure.evaluation;

import com.lawrence.travel.application.evaluation.Evaluation;
import com.lawrence.travel.application.evaluation.SessionEvaluationExecutor;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 显式评测用本机真实HTTP驱动；注册/登录/脚本均走现有接口，不被生产自动装配或默认测试调用。 */
public final class HttpSessionEvaluationDriver implements SessionEvaluationExecutor.Driver {
    private final URI endpoint;
    private final EvidenceReader evidence;
    private final FixtureFiles fixtures;
    private final Restart restart;
    private final Duration timeout;
    private final boolean auditIsolation;
    private final TravelJson json = new TravelJson();

    /**
     * @param endpoint 本机已配置的业务后端，不接受凭据、路径或外网地址
     * @param evidence 独立读取持久状态与实际计量，不从模型文本提取用量
     * @param fixtures 固定合成文件，不接受用户任意路径
     * @param restart 显式本地进程恢复动作；未配置返回false，不能跳过后宣称通过
     * @param timeout 每个等待的墙钟上限，1秒到15分钟
     */
    public HttpSessionEvaluationDriver(
            URI endpoint,
            EvidenceReader evidence,
            FixtureFiles fixtures,
            Restart restart,
            Duration timeout) {
        this(endpoint, evidence, fixtures, restart, timeout, false);
    }

    /**
     * @param endpoint 本机后端
     * @param evidence 同库观察器
     * @param fixtures 固定原件
     * @param restart 显式重启
     * @param timeout 有界等待
     * @param auditIsolation 是否额外注册隔离身份，实测聊天及旅行跨用户读取404
     */
    public HttpSessionEvaluationDriver(
            URI endpoint,
            EvidenceReader evidence,
            FixtureFiles fixtures,
            Restart restart,
            Duration timeout,
            boolean auditIsolation) {
        if (endpoint == null
                || !"http".equals(endpoint.getScheme())
                || !List.of("127.0.0.1", "localhost", "[::1]").contains(endpoint.getHost())
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || !List.of("", "/").contains(endpoint.getPath()))
            throw new IllegalArgumentException("EVALUATION_LOOPBACK_REQUIRED");
        if (timeout.compareTo(Duration.ofSeconds(1)) < 0
                || timeout.compareTo(Duration.ofMinutes(15)) > 0)
            throw new IllegalArgumentException("EVALUATION_TIMEOUT_INVALID");
        this.endpoint = endpoint;
        this.evidence = java.util.Objects.requireNonNull(evidence);
        this.fixtures = java.util.Objects.requireNonNull(fixtures);
        this.restart = java.util.Objects.requireNonNull(restart);
        this.timeout = timeout;
        this.auditIsolation = auditIsolation;
    }

    /**
     * @param action 固定用户操作码
     * @param text 完整用户输入
     * @param fixture 封存夹具逻辑名，仅UPLOAD使用
     */
    public record Turn(String action, String text, String fixture) {}

    /**
     * @param name 安全显示名
     * @param bytes 固定合成文件，不是模型生成的路径
     */
    public record File(String name, byte[] bytes) {
        /** 防止multipart头注入、超限文件及并发修改。 */
        public File {
            if (name == null
                    || !name.matches("[A-Za-z0-9_-]{1,80}\\.(pdf|docx|txt|md)")
                    || bytes == null
                    || bytes.length == 0
                    || bytes.length > 10485760)
                throw new IllegalArgumentException("EVALUATION_FILE_INVALID");
            bytes = bytes.clone();
        }

        /**
         * @return 副本，执行端不能改变封存夹具内容
         */
        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /** 独立程序/计量查询，接收真实执行身份，不接收Oracle。 */
    public interface EvidenceReader {
        /**
         * @param user 本次合成用户
         * @param chat 会话
         * @param turns 已完成步骤
         * @return 私有权威状态，报告不保留正文
         */
        SessionEvaluationExecutor.Capture read(UUID user, UUID chat, int turns);
    }

    /** 仅已注册固定夹具名可读取；路径解析和原件指纹由调用方固定。 */
    public interface FixtureFiles {
        /**
         * @param name 逻辑名
         * @return 封存原件；缺失必须失败，不能生成假上传结果
         */
        File read(String name);
    }

    /** 主机控制的恢复演练，不向Agent暴露进程控制或任意命令。 */
    public interface Restart {
        /**
         * @return 实际完成本次原进程重启才返回true；未配置返回false
         */
        boolean execute();
    }

    /** {@inheritDoc} 每次独立会话与登录，真实计量缺失由上层标未运行；不接收裁判预期。 */
    @Override
    public SessionEvaluationExecutor.Capture execute(Evaluation.Case input) {
        var turns = json.decode(input.facts().get("script"), Turn[].class);
        requireScript(turns);
        var files = new java.util.HashMap<String, File>();
        // 所有原件必须在首个请求前可用；缺少尾部夹具时不能先消耗前面的真实模型额度。
        for (var turn : turns)
            if (turn.action().equals("UPLOAD"))
                files.computeIfAbsent(
                        turn.fixture(),
                        name ->
                                java.util.Objects.requireNonNull(
                                        fixtures.read(name), "SESSION_FIXTURE_MISSING"));
        long start = System.nanoTime();
        var browser = new Browser();
        UUID user = null;
        UUID chat = UUID.randomUUID();
        UUID document = null, lastInput = null;
        String lastText = null;
        Map<String, Object> lastBody = null;
        boolean structuredDocumentBound = false;
        var trace = new ArrayList<String>();
        var acceptedVersions = new ArrayList<String>();
        var acceptedSnapshots = new ArrayList<Map<?, ?>>();
        int completed = 0;
        var deletedMetrics = new java.util.HashMap<String, Long>();
        try {
            user = browser.login();
            for (var turn : turns) {
                switch (turn.action()) {
                    case "MESSAGE", "MESSAGE_ASYNC", "MESSAGE_DOCUMENT" -> {
                        if (turn.text() == null
                                || turn.text().isBlank()
                                || turn.text().length() > 4000)
                            throw new IllegalArgumentException("SESSION_TEXT_INVALID");
                        lastInput = UUID.randomUUID();
                        lastText =
                                turn.text()
                                        .replace(
                                                "{{document}}",
                                                document == null ? "未上传资料" : document.toString());
                        lastBody =
                                turn.action().equals("MESSAGE_DOCUMENT")
                                        ? Map.of(
                                                "inputId",
                                                lastInput,
                                                "text",
                                                lastText,
                                                "documentIds",
                                                List.of(document))
                                        : Map.of("inputId", lastInput, "text", lastText);
                        var receipt = browser.post("/chats/" + chat + "/messages", lastBody, 200);
                        if (turn.action().equals("MESSAGE_DOCUMENT")) {
                            // 检查实际HTTP收件投影，不能只因请求里带过附件字段就宣称绑定成功。
                            structuredDocumentBound =
                                    boundDocument(receipt, lastInput, document, lastText);
                        }
                        if (turn.action().equals("MESSAGE_ASYNC")) break;
                        String state = browser.waitMessage(chat, lastInput);
                        if (List.of("FAILED", "PAUSED", "PAUSED_QUOTA", "PAUSED_BUDGET", "RETRY")
                                .contains(state))
                            return paused(
                                    user, chat, completed, "PAUSED_SESSION", start, deletedMetrics);
                        String taskState = browser.waitTask(chat);
                        if (taskState.startsWith("PAUSED_"))
                            return paused(
                                    user, chat, completed, "PAUSED_SESSION", start, deletedMetrics);
                        if (List.of("FAILED", "SUPERSEDED").contains(taskState))
                            return paused(
                                    user,
                                    chat,
                                    completed,
                                    "PAUSED_TASK_FAILED",
                                    start,
                                    deletedMetrics);
                    }
                    case "STOP_CHAT" ->
                            browser.post(
                                    "/chats/" + chat + "/messages/" + lastInput + "/actions",
                                    Map.of("action", "WITHDRAW"),
                                    200);
                    case "DISCONNECT_RECONNECT" -> browser.reconnect();
                    case "REPLAY_MESSAGE" -> {
                        browser.post("/chats/" + chat + "/messages", lastBody, 200);
                        String state = browser.waitMessage(chat, lastInput);
                        if (state.equals("PAUSED"))
                            return paused(
                                    user, chat, completed, "PAUSED_SESSION", start, deletedMetrics);
                        String taskState = browser.waitTask(chat);
                        if (taskState.startsWith("PAUSED_")
                                || List.of("FAILED", "SUPERSEDED").contains(taskState))
                            return paused(
                                    user,
                                    chat,
                                    completed,
                                    "PAUSED_TASK_INCOMPLETE",
                                    start,
                                    deletedMetrics);
                    }
                    case "UPLOAD" -> {
                        document = UUID.randomUUID();
                        browser.upload(chat, document, files.get(turn.fixture()));
                    }
                    case "WAIT_DOCUMENT" ->
                            browser.waitDocument(
                                    chat,
                                    document,
                                    List.of("P07", "P19", "R02", "R03").contains(input.id()));
                    case "READ_AS_OTHER" -> {
                        try (var other = new Browser()) {
                            other.login();
                            other.get(
                                    "/chats/" + chat + "/documents/" + document + "/download", 404);
                        }
                    }
                    case "DELETE_DOCUMENT" -> browser.delete("DOCUMENT", document);
                    case "DELETE_CHAT" -> {
                        // 删除会原子清理该会话的计量明细；只保留匿名数值，不能把清零后的查询写成零消耗。
                        var before = evidence.read(user, chat, completed);
                        browser.delete("CHAT", chat);
                        before.metrics()
                                .forEach(
                                        (key, value) ->
                                                deletedMetrics.merge(key, value, Long::sum));
                    }
                    case "CONFIRM_OPERATION" -> {
                        if (lastInput == null)
                            throw new IllegalArgumentException("SESSION_OPERATION_MISSING");
                        browser.post(
                                "/chats/" + chat + "/messages/" + lastInput + "/actions",
                                Map.of("action", "CONFIRM"),
                                200);
                    }
                    case "ACCEPT_CANDIDATE" -> {
                        var accepted = browser.acceptCandidate(chat, acceptedVersions);
                        acceptedVersions.add((String) accepted.get("snapshotId"));
                        acceptedSnapshots.add((Map<?, ?>) accepted.get("snapshot"));
                    }
                    case "STOP" -> {
                        var view = browser.get("/chats/" + chat, 200);
                        String task = (String) ((Map<?, ?>) view.get("chat")).get("taskId");
                        if (task == null)
                            throw new IllegalArgumentException("SESSION_TASK_MISSING");
                        var run = browser.get("/tasks/" + task + "/view", 200).get("runId");
                        browser.post("/tasks/" + task + "/runs/" + run + "/cancel", Map.of(), 200);
                    }
                    case "RESTART" -> {
                        if (!restart.execute())
                            return paused(
                                    user,
                                    chat,
                                    completed,
                                    "PAUSED_RESTART_NOT_CONFIGURED",
                                    start,
                                    deletedMetrics);
                        browser.csrf();
                    }
                    default -> throw new IllegalArgumentException("SESSION_ACTION_INVALID");
                }
                completed++;
                trace.add(turn.action());
            }
            var isolation =
                    auditIsolation ? auditIsolation(browser, chat) : Map.<String, Object>of();
            var observed = evidence.read(user, chat, completed);
            var metrics = new java.util.HashMap<>(observed.metrics());
            deletedMetrics.forEach((key, value) -> metrics.merge(key, value, Long::sum));
            metrics.put("wallMillis", (System.nanoTime() - start) / 1000000);
            var state = new java.util.HashMap<>(observed.state());
            state.putAll(isolation);
            state.put("httpStructuredDocumentBound", structuredDocumentBound);
            if (!acceptedVersions.isEmpty()) {
                state.put("httpAcceptedVersions", List.copyOf(acceptedVersions));
                state.put("httpAcceptedSnapshots", List.copyOf(acceptedSnapshots));
            }
            if (auditIsolation
                    && List.of("P07", "P19", "R02", "R03").contains(input.id())
                    && document != null
                    && lastInput != null)
                state.putAll(auditDocument(browser, chat, document, lastInput));
            return new SessionEvaluationExecutor.Capture(
                    user, chat, observed.outcome(), completed, state, metrics, List.copyOf(trace));
        } catch (java.io.IOException failure) {
            if (user != null)
                return paused(user, chat, completed, "PAUSED_HTTP_FAILED", start, deletedMetrics);
            throw new IllegalStateException("SESSION_HTTP_FAILED");
        } catch (RuntimeException failure) {
            if (user != null)
                return paused(
                        user, chat, completed, "PAUSED_EXECUTION_FAILED", start, deletedMetrics);
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SESSION_INTERRUPTED");
        } finally {
            browser.close();
        }
    }

    /**
     * @param owner 实际登录身份
     * @param chat 当前聊天
     * @param document 实际上传版本
     * @param input 成功回复
     * @return HTTP引用预览与跨用户拒读证据；无引用保持false，不用目录冒充读过原文
     * @throws java.io.IOException 本机HTTP失败
     * @throws InterruptedException 审计取消
     */
    private Map<String, Object> auditDocument(Browser owner, UUID chat, UUID document, UUID input)
            throws java.io.IOException, InterruptedException {
        var request =
                HttpRequest.newBuilder(
                                endpoint.resolve(
                                        "/api/v1/chats/"
                                                + chat
                                                + "/messages/"
                                                + input
                                                + "/references"))
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .build();
        var response = owner.client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new IllegalStateException("SESSION_REFERENCE_LIST_FAILED");
        var references = json.decode(response.body(), UUID[].class);
        boolean preview = references.length > 0 && references.length <= 10;
        try (var other = new Browser()) {
            other.login();
            other.get("/chats/" + chat + "/documents/" + document + "/download", 404);
            for (UUID reference : references) {
                var fragment = owner.get("/chats/" + chat + "/references/" + reference, 200);
                preview &=
                        fragment.get("locator") instanceof Map<?, ?> locator
                                && document.toString().equals(locator.get("document"))
                                && fragment.get("text") instanceof String text
                                && !text.isBlank();
                other.get("/chats/" + chat + "/references/" + reference, 404);
            }
        }
        return Map.of(
                "httpDocumentPreview",
                preview,
                "httpForeignDocumentDenied",
                true,
                "httpForeignReferenceDenied",
                references.length > 0);
    }

    /**
     * @param owner 原登录身份
     * @param chat 私有聊天
     * @return 仅实际HTTP正/负检查的布尔值，删除场景不伪造隔离证据
     * @throws java.io.IOException 回环请求失败
     * @throws InterruptedException 审计取消
     */
    private Map<String, Object> auditIsolation(Browser owner, UUID chat)
            throws java.io.IOException, InterruptedException {
        var view = owner.get("/chats/" + chat, 200);
        Object task = ((Map<?, ?>) view.get("chat")).get("taskId");
        try (var other = new Browser()) {
            other.login();
            other.get("/chats/" + chat, 404);
            if (task != null) other.get("/tasks/" + task + "/view", 404);
        }
        return Map.of("httpOwnerVisible", true, "httpOtherUserReadDenied", true);
    }

    /**
     * 核对服务端真正返回的消息与附件投影，不通过文件名推断引用。
     *
     * @param receipt 已认证收件响应
     * @param input 本次幂等消息
     * @param document 本轮选中文件
     * @param text 不含内部身份的原文
     * @return 正文保持且有唯一、可读、正确命名的结构化附件
     */
    private static boolean boundDocument(
            Map<?, ?> receipt, UUID input, UUID document, String text) {
        if (!(receipt.get("messages") instanceof List<?> messages)) return false;
        for (var value : messages)
            if (value instanceof Map<?, ?> message && input.toString().equals(message.get("id"))) {
                if (!text.equals(message.get("text"))
                        || !(message.get("documents") instanceof List<?> references)
                        || references.size() != 1) return false;
                return references.getFirst() instanceof Map<?, ?> reference
                        && document.toString().equals(reference.get("id"))
                        && "trip1.txt".equals(reference.get("name"))
                        && Boolean.TRUE.equals(reference.get("available"));
            }
        return false;
    }

    /**
     * @param turns 全部脚本；在注册或首个模型请求之前校验所有操作，防止坏尾部步骤导致部分真实调用
     */
    private static void requireScript(Turn[] turns) {
        if (turns == null || turns.length < 1 || turns.length > 64)
            throw new IllegalArgumentException("SESSION_SCRIPT_INVALID");
        boolean document = false, input = false;
        for (var turn : turns) {
            if (turn == null
                    || turn.action() == null
                    || !List.of(
                                    "MESSAGE",
                                    "MESSAGE_ASYNC",
                                    "MESSAGE_DOCUMENT",
                                    "STOP_CHAT",
                                    "DISCONNECT_RECONNECT",
                                    "REPLAY_MESSAGE",
                                    "UPLOAD",
                                    "WAIT_DOCUMENT",
                                    "READ_AS_OTHER",
                                    "DELETE_DOCUMENT",
                                    "DELETE_CHAT",
                                    "CONFIRM_OPERATION",
                                    "ACCEPT_CANDIDATE",
                                    "STOP",
                                    "RESTART")
                            .contains(turn.action()))
                throw new IllegalArgumentException("SESSION_ACTION_INVALID");
            if (List.of("MESSAGE", "MESSAGE_ASYNC", "MESSAGE_DOCUMENT").contains(turn.action())) {
                if (turn.text() == null || turn.text().isBlank() || turn.text().length() > 4000)
                    throw new IllegalArgumentException("SESSION_TEXT_INVALID");
                if (turn.action().equals("MESSAGE_DOCUMENT")
                        && (!document
                                || turn.text().contains("{{document}}")
                                || turn.text()
                                        .matches(
                                                "(?s).*[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}.*")))
                    throw new IllegalArgumentException("SESSION_DOCUMENT_MESSAGE_INVALID");
                input = true;
            }
            if (turn.action().equals("UPLOAD")) {
                if (turn.fixture() == null
                        || !turn.fixture().matches("[A-Za-z0-9_-]{1,80}\\.(pdf|docx|txt|md)"))
                    throw new IllegalArgumentException("SESSION_FIXTURE_INVALID");
                document = true;
            }
            if (List.of("WAIT_DOCUMENT", "READ_AS_OTHER", "DELETE_DOCUMENT").contains(turn.action())
                    && !document) throw new IllegalArgumentException("SESSION_DOCUMENT_MISSING");
            if (List.of(
                                    "STOP",
                                    "STOP_CHAT",
                                    "REPLAY_MESSAGE",
                                    "CONFIRM_OPERATION",
                                    "ACCEPT_CANDIDATE")
                            .contains(turn.action())
                    && !input) throw new IllegalArgumentException("SESSION_INPUT_MISSING");
        }
    }

    /**
     * @param user 本次用户
     * @param chat 会话
     * @param turns 已完成步骤
     * @param reason 固定分类
     * @param start 起始时钟
     * @param deletedMetrics 删除前保存的匿名累计计数，不保留正文，不能同尚未删除的预算重复相加
     * @return 保留已发生的实际计量；只有独立审计核实的预期拒绝可转入程序裁判
     */
    private SessionEvaluationExecutor.Capture paused(
            UUID user,
            UUID chat,
            int turns,
            String reason,
            long start,
            Map<String, Long> deletedMetrics) {
        var observed = evidence.read(user, chat, turns);
        var metrics = new java.util.HashMap<>(observed.metrics());
        metrics.put("wallMillis", (System.nanoTime() - start) / 1000000);
        deletedMetrics.forEach((key, value) -> metrics.merge(key, value, Long::sum));
        // 只有独立观察器核实的预期拒绝可进入裁判；普通配额、异常、未配置重启一律未运行。
        boolean expected =
                reason.equals("PAUSED_SESSION")
                        && observed.outcome().equals("PAUSED_EXPECTED")
                        && metrics.getOrDefault("verifiedAdmissionDenials", 0L) > 0;
        return new SessionEvaluationExecutor.Capture(
                user,
                chat,
                expected ? "PAUSED_EXPECTED" : reason,
                turns,
                observed.state(),
                metrics,
                List.of("SESSION_PAUSED"));
    }

    /** 独立内存Cookie身份；认证值没有默认toString或报告字段，超时后不自动重发写入。 */
    private final class Browser implements AutoCloseable {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private HttpClient client =
                HttpClient.newBuilder()
                        .cookieHandler(cookies)
                        .connectTimeout(Duration.ofSeconds(3))
                        .build();
        private String header, token;

        /** 创建独立会话。 */
        private Browser() {}

        /** 关闭本次客户端物理连接，认证资料仅随内存身份释放，不写入报告。 */
        @Override
        public void close() {
            client.shutdownNow();
        }

        /**
         * 真实关闭客户端连接池后沿用登录Cookie重连；不调用撤回/停止，不新建消息身份。
         * @throws InterruptedException 等待旧客户端退出时取消
         */
        private void reconnect() throws InterruptedException {
            client.shutdownNow();
            if (!client.awaitTermination(Duration.ofSeconds(3)))
                throw new IllegalStateException("SESSION_DISCONNECT_TIMEOUT");
            client =
                    HttpClient.newBuilder()
                            .cookieHandler(cookies)
                            .connectTimeout(Duration.ofSeconds(3))
                            .build();
        }

        /**
         * @return 本次合成用户
         * @throws java.io.IOException 本机网络失败
         * @throws InterruptedException 用户取消
         */
        private UUID login() throws java.io.IOException, InterruptedException {
            String name = "eval_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                    password = "T-" + UUID.randomUUID();
            csrf();
            post("/auth/register", Map.of("username", name, "password", password), 201);
            var identity = post("/auth/login", Map.of("username", name, "password", password), 200);
            csrf();
            return UUID.fromString((String) identity.get("id"));
        }

        /**
         * 登录后刷新内存令牌。
         * @throws java.io.IOException 本机网络失败
         * @throws InterruptedException 取消
         */
        private void csrf() throws java.io.IOException, InterruptedException {
            var value = get("/auth/csrf", 200);
            header = (String) value.get("headerName");
            token = (String) value.get("token");
        }

        /**
         * @param path 固定业务路径
         * @param status 精确状态
         * @return JSON对象；404只保留状态
         * @throws java.io.IOException 网络失败
         * @throws InterruptedException 取消
         */
        private Map<?, ?> get(String path, int status)
                throws java.io.IOException, InterruptedException {
            return send(
                    HttpRequest.newBuilder(endpoint.resolve("/api/v1" + path)).GET().build(),
                    status);
        }

        /**
         * @param path 固定业务写路径
         * @param body 显式脚本操作
         * @param status 精确状态
         * @return 私有JSON
         * @throws java.io.IOException 网络失败
         * @throws InterruptedException 取消
         */
        private Map<?, ?> post(String path, Object body, int status)
                throws java.io.IOException, InterruptedException {
            return send(
                    HttpRequest.newBuilder(endpoint.resolve("/api/v1" + path))
                            .header("Content-Type", "application/json")
                            .header(header, token)
                            .POST(HttpRequest.BodyPublishers.ofString(json.encode(body)))
                            .build(),
                    status);
        }

        /**
         * @param request 本机请求
         * @param expected 状态
         * @return 对象；原件下载不解析
         * @throws java.io.IOException 网络失败
         * @throws InterruptedException 取消
         */
        private Map<?, ?> send(HttpRequest request, int expected)
                throws java.io.IOException, InterruptedException {
            var response =
                    client.send(
                            HttpRequest.newBuilder(request, (name, value) -> true)
                                    .timeout(Duration.ofSeconds(20))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != expected)
                throw new IllegalStateException("SESSION_HTTP_STATUS_INVALID");
            if (expected == 404 || response.body().isBlank()) return Map.of();
            return json.decode(response.body(), Map.class);
        }

        /**
         * @param chat 会话
         * @param input 原消息
         * @return 已提交状态
         * @throws java.io.IOException 网络失败
         * @throws InterruptedException 取消
         */
        private String waitMessage(UUID chat, UUID input)
                throws java.io.IOException, InterruptedException {
            long until = System.nanoTime() + timeout.toNanos();
            do {
                var state = get("/chats/" + chat, 200);
                var messages = (List<?>) state.get("messages");
                for (var item : messages) {
                    var message = (Map<?, ?>) item;
                    if (input.toString().equals(message.get("id"))) {
                        String status = (String) message.get("state");
                        if (List.of("DONE", "CLARIFY", "CONFIRM", "PAUSED", "WITHDRAWN")
                                .contains(status)) return status;
                        if (!List.of("RUNNING", "QUEUED").contains(status))
                            throw new IllegalStateException("SESSION_STATE_INVALID");
                    }
                }
                Thread.sleep(100);
            } while (System.nanoTime() < until);
            throw new IllegalStateException("SESSION_WAIT_TIMEOUT");
        }

        /**
         * @param chat 本次会话
         * @return 规划到达用户边界后的状态；不把仅收件或理解完成当成完整规划
         * @throws java.io.IOException 本地协议失败
         * @throws InterruptedException 取消
         */
        private String waitTask(UUID chat) throws java.io.IOException, InterruptedException {
            var conversation = get("/chats/" + chat, 200);
            Object task = ((Map<?, ?>) conversation.get("chat")).get("taskId");
            if (task == null) return "NO_TASK";
            long until = System.nanoTime() + timeout.toNanos();
            do {
                String state = (String) get("/tasks/" + task + "/view", 200).get("state");
                if (List.of(
                                "WAITING_USER",
                                "WAITING_ACCEPTANCE",
                                "PAUSED_BUDGET",
                                "PAUSED_QUOTA",
                                "SUCCEEDED",
                                "COMPLETED",
                                "CANCELLED",
                                "FAILED",
                                "SUPERSEDED")
                        .contains(state)) return state;
                if (!List.of("RUNNING", "QUEUED").contains(state))
                    throw new IllegalStateException("SESSION_RUN_STATE_INVALID");
                Thread.sleep(100);
            } while (System.nanoTime() < until);
            throw new IllegalStateException("SESSION_TASK_TIMEOUT");
        }

        /**
         * 通过真实HTTP显式接受当前候选，同时检查生成候选没有提前切换生效版本。
         *
         * @param chat 本批聊天
         * @param acceptedVersions 本脚本之前已接受的版本；首次必须尚无生效版本
         * @return 已接受的版本身份和候选快照
         * @throws java.io.IOException 本机HTTP失败
         * @throws InterruptedException 等待中断
         */
        private Map<String, Object> acceptCandidate(UUID chat, List<String> acceptedVersions)
                throws java.io.IOException, InterruptedException {
            var conversation = get("/chats/" + chat, 200);
            Object task = ((Map<?, ?>) conversation.get("chat")).get("taskId");
            if (task == null) throw new IllegalStateException("SESSION_TASK_MISSING");
            var view = get("/tasks/" + task + "/view", 200);
            Object previous = acceptedVersions.isEmpty() ? null : acceptedVersions.getLast();
            if (!java.util.Objects.equals(previous, view.get("currentVersionId")))
                throw new IllegalStateException("SESSION_VERSION_CHANGED_WITHOUT_ACCEPTANCE");
            var candidateView = get("/tasks/" + task + "/candidate", 200);
            if (!Boolean.TRUE.equals(candidateView.get("acceptable")))
                throw new IllegalStateException("SESSION_CANDIDATE_NOT_ACCEPTABLE");
            var candidate = (Map<?, ?>) candidateView.get("candidate");
            var version = (Map<?, ?>) candidate.get("version");
            if (!java.util.Objects.equals(previous, version.get("baseVersionId")))
                throw new IllegalStateException("SESSION_CANDIDATE_BASE_CHANGED");
            var body = new java.util.HashMap<String, Object>();
            body.put("requestId", UUID.randomUUID());
            body.put("candidateId", candidate.get("candidateId"));
            body.put("expectedCurrentVersionId", previous);
            body.put("constraintRevision", view.get("constraintRevision"));
            body.put("generation", view.get("generation"));
            body.put("snapshotHash", version.get("payloadHash"));
            var receipt = post("/tasks/" + task + "/accept", body, 200);
            Object snapshot = version.get("snapshotId");
            if (!"ACCEPTED".equals(receipt.get("status"))
                    || !java.util.Objects.equals(snapshot, receipt.get("snapshotId"))
                    || !java.util.Objects.equals(
                            snapshot, get("/tasks/" + task + "/view", 200).get("currentVersionId")))
                throw new IllegalStateException("SESSION_ACCEPTANCE_NOT_COMMITTED");
            // 同一接受请求重放不能创建第二条接受事实。
            if (!receipt.equals(post("/tasks/" + task + "/accept", body, 200)))
                throw new IllegalStateException("SESSION_ACCEPTANCE_NOT_IDEMPOTENT");
            return Map.of("snapshotId", snapshot, "snapshot", candidate.get("snapshot"));
        }

        /**
         * @param kind 精确删除类型
         * @param target 本次真实对象
         * @throws java.io.IOException 网络失败
         * @throws InterruptedException 取消
         */
        private void delete(String kind, UUID target)
                throws java.io.IOException, InterruptedException {
            var preview = get("/lifecycle/preview?kind=" + kind + "&target=" + target, 200);
            post(
                    "/lifecycle/deletions",
                    Map.of(
                            "request",
                            UUID.randomUUID(),
                            "kind",
                            kind,
                            "target",
                            target,
                            "revision",
                            preview.get("revision"),
                            "confirmed",
                            true),
                    200);
        }

        /**
         * @param chat 本次会话
         * @param id 固定上传身份
         * @param file 固定合成原件
         * @throws java.io.IOException 网络失败
         * @throws InterruptedException 取消
         */
        private void upload(UUID chat, UUID id, File file)
                throws java.io.IOException, InterruptedException {
            String boundary = "evaluation-" + UUID.randomUUID();
            var body = new java.io.ByteArrayOutputStream();
            body.write(
                    ("--"
                                    + boundary
                                    + "\r\nContent-Disposition: form-data; name=\"id\"\r\n\r\n"
                                    + id
                                    + "\r\n--"
                                    + boundary
                                    + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                                    + file.name()
                                    + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
            body.write(file.bytes());
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            send(
                    HttpRequest.newBuilder(endpoint.resolve("/api/v1/chats/" + chat + "/documents"))
                            .header(header, token)
                            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                            .build(),
                    202);
        }

        /**
         * @param chat 本次会话
         * @param id 原文件
         * @param readyRequired 解释型场景必须READY；扫描拒绝场景可继续解释不可读原因
         * @throws java.io.IOException 网络失败
         * @throws InterruptedException 取消
         */
        private void waitDocument(UUID chat, UUID id, boolean readyRequired)
                throws java.io.IOException, InterruptedException {
            long until = System.nanoTime() + timeout.toNanos();
            do {
                var response =
                        client.send(
                                HttpRequest.newBuilder(
                                                endpoint.resolve(
                                                        "/api/v1/chats/" + chat + "/documents"))
                                        .timeout(Duration.ofSeconds(20))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200)
                    throw new IllegalStateException("SESSION_DOCUMENT_STATUS_FAILED");
                for (var item : json.decode(response.body(), Map[].class))
                    if (id.toString().equals(item.get("id"))) {
                        String state = (String) item.get("state");
                        if (state.equals("READY")) return;
                        if (state.equals("REJECTED") && !readyRequired) return;
                        if (List.of("REJECTED", "PAUSED").contains(state))
                            throw new IllegalStateException("SESSION_DOCUMENT_NOT_READY");
                    }
                Thread.sleep(100);
            } while (System.nanoTime() < until);
            throw new IllegalStateException("SESSION_DOCUMENT_TIMEOUT");
        }
    }
}
