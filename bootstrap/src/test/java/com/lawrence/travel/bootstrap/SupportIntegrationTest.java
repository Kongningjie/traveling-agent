package com.lawrence.travel.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lawrence.travel.application.document.DocumentService;
import com.lawrence.travel.application.execution.Jobs;
import com.lawrence.travel.application.interaction.DialogueService;
import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.DocumentStore;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.JobStore;
import com.lawrence.travel.application.port.SupportStore;
import com.lawrence.travel.application.support.DiagnosticBuffer;
import com.lawrence.travel.application.support.Support;
import com.lawrence.travel.application.support.SupportService;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.support.ProblemState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 本地PG/Redis与HTTP反馈验收；合成用户和固定正文，不调用任何外部供应商。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"travel.engine.enabled=false", "travel.documents.cleanup-enabled=false"})
class SupportIntegrationTest {
    private static final Path ROOT = root();
    private final List<UserIdentity> users = new ArrayList<>();
    private final List<String> sessionIds = new ArrayList<>();
    @Autowired com.lawrence.travel.application.travel.TravelStorageService travel;
    @Autowired com.lawrence.travel.application.port.ExecutionEngineStore engine;
    @Autowired SupportService support;
    @Autowired SupportStore supportStore;
    @Autowired DiagnosticBuffer buffer;
    @Autowired DialogueService dialogue;
    @Autowired AccountStore accounts;
    @Autowired BusinessTransactions tx;
    @Autowired InteractionCodec codec;
    @Autowired JobStore jobs;
    @Autowired DocumentService documents;
    @Autowired DocumentStore store;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.boot.test.web.server.LocalServerPort int port;

    @Autowired
    org.springframework.session.SessionRepository<? extends org.springframework.session.Session>
            sessions;

    @Autowired
    org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext
            webContext;

    /**
     * @return 仅合成文件临时根
     */
    private static Path root() {
        try {
            return Files.createTempDirectory("support-fixture-");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("FIXTURE_ROOT");
        }
    }

    /**
     * @param r 随机原件根，无第三方请求配置
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("travel.documents.root", ROOT::toString);
    }

    /** 仅清理本轮随机身份的文件和PG数据，预算包括每次operation，保留其他用户资料。 */
    @AfterEach
    void clean() {
        awaitHttpExit();
        for (var id : sessionIds) sessions.deleteById(id);
        for (var user : users) {
            for (String key :
                    jdbc.queryForList(
                            "SELECT file_key FROM uploaded_document WHERE user_id=?",
                            String.class,
                            user.id())) {
                try {
                    Files.deleteIfExists(ROOT.resolve(key));
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("TEST_CLEAN_FAILED");
                }
            }
            tx.required(
                    () -> {
                        jdbc.update("DELETE FROM maintenance_grant WHERE user_id=?", user.id());
                        jdbc.update(
                                "DELETE FROM maintenance_audit WHERE actor_id=? OR target_id=?",
                                user.id(),
                                user.id());
                        var budgets =
                                jdbc.queryForList(
                                        "SELECT budget_id FROM operation_context WHERE user_id=?"
                                                + " UNION SELECT budget_id FROM dialogue_chat WHERE"
                                                + " user_id=? UNION SELECT id FROM execution_run WHERE"
                                                + " user_id=?",
                                        UUID.class,
                                        user.id(),
                                        user.id(),
                                        user.id());
                        jdbc.update("DELETE FROM uploaded_document WHERE user_id=?", user.id());
                        jdbc.update("DELETE FROM dialogue_input WHERE user_id=?", user.id());
                        JobTestCleanup.remove(jdbc, user.id());
                        jdbc.update("DELETE FROM dialogue_chat WHERE user_id=?", user.id());
                        jdbc.update(
                                "UPDATE travel_task SET current_run_id=NULL,current_version_id=NULL"
                                        + " WHERE user_id=?",
                                user.id());
                        for (String table :
                                List.of(
                                        "evidence_notice",
                                        "field_evidence",
                                        "itinerary_acceptance_request",
                                        "itinerary_acceptance",
                                        "itinerary_report",
                                        "itinerary_candidate",
                                        "negotiation_card",
                                        "conversation_message",
                                        "decision_response",
                                        "decision_request",
                                        "run_event",
                                        "execution_start_request"))
                            jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user.id());
                        jdbc.update(
                                "DELETE FROM artifact_content WHERE artifact_id IN(SELECT id FROM"
                                        + " artifact_record WHERE user_id=?)",
                                user.id());
                        for (String table :
                                List.of(
                                        "artifact_record",
                                        "step_attempt",
                                        "outbox_event",
                                        "execution_step",
                                        "execution_run",
                                        "change_request",
                                        "itinerary_snapshot",
                                        "travel_constraint_revision",
                                        "travel_task",
                                        "travel_idempotency"))
                            jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user.id());
                        for (var budget : budgets) {
                            jdbc.update("DELETE FROM call_meter WHERE run_id=?", budget);
                            jdbc.update("DELETE FROM execution_budget WHERE run_id=?", budget);
                        }
                        jdbc.update(
                                "UPDATE problem_group SET maintained_by=NULL WHERE maintained_by=?",
                                user.id());
                        jdbc.update(
                                "DELETE FROM problem_group g WHERE g.component='SUPPORT_TEST' AND"
                                        + " NOT EXISTS(SELECT 1 FROM problem_event e WHERE"
                                        + " e.group_id=g.id)");
                        jdbc.update("DELETE FROM app_user WHERE id=?", user.id());
                        return null;
                    });
        }
    }

    /**
     * @return 无登录能力的随机测试身份
     */
    private UserIdentity actor() {
        UUID id = UUID.randomUUID();
        String name = "src_" + id.toString().replace("-", "").substring(0, 20);
        accounts.insert(id, name, "__NON_LOGIN_TEST_HASH__");
        var user = new UserIdentity(id, name);
        users.add(user);
        return user;
    }

    /**
     * @param repository 本地Redis会话
     * @param user 合成身份
     * @param <S> 会话类型
     * @return 仅本地HTTP使用的Cookie，不进入日志或断言
     */
    private <S extends org.springframework.session.Session> String login(
            org.springframework.session.SessionRepository<S> repository, UserIdentity user) {
        S session = repository.createSession();
        var context =
                org.springframework.security.core.context.SecurityContextHolder
                        .createEmptyContext();
        context.setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                        .authenticated(
                                user,
                                null,
                                List.of(
                                        new org.springframework.security.core.authority
                                                .SimpleGrantedAuthority("ROLE_USER"))));
        session.setAttribute(
                org.springframework.security.web.context.HttpSessionSecurityContextRepository
                        .SPRING_SECURITY_CONTEXT_KEY,
                context);
        repository.save(session);
        sessionIds.add(session.getId());
        return "SESSION="
                + java.util.Base64.getEncoder()
                        .encodeToString(
                                session.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 等本地HTTP响应真正退出再删会话，防止Redis保存与夹具清理竞争。 */
    private void awaitHttpExit() {
        var server = (org.springframework.boot.tomcat.TomcatWebServer) webContext.getWebServer();
        var protocol =
                (org.apache.coyote.AbstractProtocol<?>)
                        server.getTomcat().getConnector().getProtocolHandler();
        if (protocol.getExecutor()
                instanceof org.apache.tomcat.util.threads.ThreadPoolExecutor pool) {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (pool.getActiveCount() > 0 && System.nanoTime() < deadline)
                java.util.concurrent.locks.LockSupport.parkNanos(
                        java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10));
            assertThat(pool.getActiveCount()).isZero();
        }
    }

    /**
     * @param u 合成用户
     * @return 拥有回复与执行attempt的私有目标；仅SQL模拟已提交文本，不调用模型。
     */
    private Support.Target target(UserIdentity u) {
        var chat = UUID.randomUUID();
        var id = UUID.randomUUID();
        dialogue.send(u, chat, id, "本地反馈夹具");
        var job =
                jdbc.queryForObject(
                        "SELECT job_id FROM dialogue_input WHERE user_id=? AND id=?",
                        UUID.class,
                        u.id(),
                        id);
        jobs.claim(new Jobs.Scope(u.id(), chat), job).orElseThrow();
        jdbc.update(
                "UPDATE dialogue_input SET reply='本地合成回复' WHERE user_id=? AND id=?", u.id(), id);
        return new Support.Target(Support.Kind.REPLY, chat, id);
    }

    /**
     * @param t 目标
     * @return 无会话复制的输入
     */
    private Support.Input input(Support.Target t) {
        return new Support.Input(
                UUID.randomUUID(), t, Support.Category.INACCURATE, "预期慢游，实际安排太紧。", false, null);
    }

    /**
     * @param u 本地维护者，授权动作必须由受控SQL发生
     */
    private void grant(UserIdentity u) {
        jdbc.update("INSERT INTO maintenance_grant(user_id) VALUES(?)", u.id());
    }

    /** 无异常的质量反馈仍落票，原问题能定位真实job attempt，不能伪造他人目标。 */
    @Test
    void feedbackPrivateAndPrecise() {
        var u = actor();
        var other = actor();
        var t = target(u);
        var in = input(t);
        var r = support.create(u.id(), in);
        assertThat(r.saved()).isTrue();
        assertThat(support.create(u.id(), in).problem()).isEqualTo(r.problem());
        assertThat(support.list(other.id(), 0)).isEmpty();
        assertThatThrownBy(() -> support.create(other.id(), input(t)))
                .hasMessage("SUPPORT_NOT_FOUND");
        assertThatThrownBy(() -> support.detail(other.id(), r.id(), false))
                .hasMessage("SUPPORT_NOT_FOUND");
        assertThatThrownBy(() -> support.groups(u.id(), 0)).hasMessage("SUPPORT_FORBIDDEN");
        grant(other);
        var g = support.group(other.id(), r.problem());
        var event =
                support.events(other.id(), g.id()).stream()
                        .filter(e -> e.id().equals(r.problem()))
                        .findFirst()
                        .orElseThrow();
        assertThat(event.span()).isNotNull();
        assertThat(support.trace(other.id(), event.trace()).spans())
                .anyMatch(s -> s.id().equals(event.span()));
        assertThatThrownBy(() -> support.detail(other.id(), r.id(), true))
                .hasMessage("SUPPORT_FORBIDDEN");
        jdbc.update("UPDATE maintenance_grant SET sensitive=true WHERE user_id=?", other.id());
        assertThat(support.detail(other.id(), r.id(), true).description())
                .isEqualTo(in.description());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM maintenance_audit WHERE actor_id=? AND"
                                        + " action='READ_PRIVATE_FEEDBACK'",
                                Integer.class,
                                other.id()))
                .isEqualTo(1);
        jdbc.update("DELETE FROM maintenance_grant WHERE user_id=?", other.id());
        assertThatThrownBy(() -> support.trace(other.id(), event.trace()))
                .hasMessage("SUPPORT_FORBIDDEN");
    }

    /** 同请求并发串行，异文拒绝，客户端上报只接受固定代码并按小时限流。 */
    @Test
    void idempotencyAndClientRate() throws Exception {
        var u = actor();
        var in = input(target(u));
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> support.create(u.id(), in));
            var b = pool.submit(() -> support.create(u.id(), in));
            assertThat(a.get().problem()).isEqualTo(b.get().problem());
        }
        assertThatThrownBy(
                        () ->
                                support.create(
                                        u.id(),
                                        new Support.Input(
                                                in.id(),
                                                in.target(),
                                                in.category(),
                                                "异文",
                                                false,
                                                null)))
                .hasMessage("SUPPORT_CONFLICT");
        var id = UUID.randomUUID();
        assertThat(support.client(u.id(), id, "RENDER", "APP"))
                .isEqualTo(support.client(u.id(), id, "RENDER", "APP"));
        for (int i = 1; i < 10; i++) support.client(u.id(), UUID.randomUUID(), "RENDER", "APP");
        assertThatThrownBy(() -> support.client(u.id(), UUID.randomUUID(), "RENDER", "APP"))
                .hasMessage("SUPPORT_RATE_LIMIT");
        assertThatThrownBy(() -> support.client(u.id(), UUID.randomUUID(), "原始堆栈", "APP"))
                .hasMessage("SUPPORT_INPUT_INVALID");
    }

    /** 修复必须经过分类与处理，缺少回归依据不允许已修复或已验证。 */
    @Test
    void lifecycleRequiresEvidence() {
        var u = actor();
        var r = support.create(u.id(), input(target(u)));
        grant(u);
        var isolated = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                        + " problem_group(id,component,code,bucket,severity,first_at,last_at,representative)"
                        + " VALUES(?,'SUPPORT_TEST',?,clock_timestamp(),'ERROR',clock_timestamp(),clock_timestamp(),?)",
                isolated,
                isolated.toString().replace("-", "").toUpperCase(),
                r.problem());
        jdbc.update("UPDATE problem_event SET group_id=? WHERE id=?", isolated, r.problem());
        var g = support.group(u.id(), r.problem());
        g = change(u, g, ProblemState.TRIAGED, "", "", false);
        g = change(u, g, ProblemState.IN_PROGRESS, "", "", false);
        var pending = g;
        assertThatThrownBy(() -> change(u, pending, ProblemState.RESOLVED, "v1", "", false))
                .hasMessage("SUPPORT_INPUT_INVALID");
        g = change(u, g, ProblemState.RESOLVED, "v1", "support-case", false);
        var resolved = g;
        assertThatThrownBy(
                        () ->
                                change(
                                        u,
                                        resolved,
                                        ProblemState.VERIFIED,
                                        "v1",
                                        "support-case",
                                        false))
                .hasMessage("SUPPORT_INPUT_INVALID");
        assertThat(change(u, g, ProblemState.VERIFIED, "v1", "support-case", true).state())
                .isEqualTo(ProblemState.VERIFIED);
    }

    /** 附件/建议/运行的目标分别关联自己的尝试，不能借用其他聊天或任务的ID。 */
    @Test
    void documentAdviceAndRunLinks() {
        var u = actor();
        var t = target(u);
        var d =
                documents.upload(
                        u,
                        t.scope(),
                        UUID.randomUUID(),
                        "合成.txt",
                        "本地合成文件".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var entry = store.get(new Jobs.Scope(u.id(), t.scope()), d.id());
        jobs.claim(new Jobs.Scope(u.id(), t.scope()), entry.jobId()).orElseThrow();
        var doc = new Support.Target(Support.Kind.DOCUMENT, t.scope(), d.id());
        var docLink = tx.required(() -> supportStore.locate(u.id(), doc));
        assertThat(docLink.document()).isEqualTo(d.id());
        assertThat(docLink.span()).isNotNull();
        support.create(u.id(), input(doc));
        var advice = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO plan_advice(id,user_id,chat_id,message_id,document_id,body)"
                        + " VALUES(?,?,?,?,?,'{}'::jsonb)",
                advice,
                u.id(),
                t.scope(),
                t.id(),
                d.id());
        var at = new Support.Target(Support.Kind.ADVICE, t.scope(), advice);
        var adviceLink = tx.required(() -> supportStore.locate(u.id(), at));
        assertThat(adviceLink.document()).isEqualTo(d.id());
        assertThat(adviceLink.span())
                .isEqualTo(tx.required(() -> supportStore.locate(u.id(), t)).span());
        support.create(u.id(), input(at));
        var task =
                travel.create(
                                u,
                                UUID.randomUUID().toString(),
                                "合成任务",
                                new com.lawrence.travel.application.port.TravelStore
                                        .RevisionContent(List.of(), List.of(), "合成", ""))
                        .task()
                        .scope();
        var stage =
                new com.lawrence.travel.application.execution.StagePlan(
                        UUID.randomUUID(),
                        new com.lawrence.travel.domain.execution.ExecutionStep.Key(
                                com.lawrence.travel.domain.execution.StageType.UNDERSTAND,
                                0,
                                "main"),
                        null,
                        "{}",
                        List.of());
        // 本例验证追踪关联，不测试同用户并行；结束合成聊天工作后再领取旅行，遵守单用户模型槽。
        var inputJob =
                jdbc.queryForObject(
                        "SELECT job_id FROM dialogue_input WHERE user_id=? AND id=?",
                        UUID.class,
                        u.id(),
                        t.id());
        jobs.control(new Jobs.Scope(u.id(), t.scope()), inputJob, false);
        var run = engine.start(task, UUID.randomUUID(), stage);
        var hint =
                engine.candidates(100).stream()
                        .filter(h -> h.runId().equals(run.id()))
                        .findFirst()
                        .orElseThrow();
        var claim = engine.claim(hint, "support_test").orElseThrow();
        var rt = new Support.Target(Support.Kind.RUN, task.taskId(), run.id());
        var link = tx.required(() -> supportStore.locate(u.id(), rt));
        assertThat(link.span()).isEqualTo(run.id());
        assertThat(link.trace()).isNotNull();
        support.create(u.id(), input(rt));
        assertThatThrownBy(
                        () ->
                                support.create(
                                        u.id(),
                                        input(
                                                new Support.Target(
                                                        Support.Kind.RUN,
                                                        UUID.randomUUID(),
                                                        run.id()))))
                .hasMessage("SUPPORT_NOT_FOUND");
    }

    /** 全局诊断容量拒绝时不保存反馈、不返回成功回执；测试事务回滚恢复容量。 */
    @Test
    void capacityCannotPretendSaved() {
        var u = actor();
        var in = input(target(u));
        long before = buffer.health().dropped();
        assertThatThrownBy(
                        () ->
                                tx.required(
                                        () -> {
                                            jdbc.update(
                                                    "UPDATE diagnostic_capacity SET limit_bytes=1"
                                                            + " WHERE id=1");
                                            return support.create(u.id(), in);
                                        }))
                .hasMessage("SUPPORT_CAPACITY");
        assertThat(support.list(u.id(), 0)).isEmpty();
        assertThat(buffer.health().dropped()).isEqualTo(before + 1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT limit_bytes FROM diagnostic_capacity WHERE id=1",
                                Long.class))
                .isGreaterThan(1);
    }

    /**
     * @param u 维护者
     * @param g 当前组
     * @param state 目标
     * @param version 修复版本
     * @param regression 用例
     * @param passed 是否实际通过
     * @return 已提交组
     */
    private Support.Group change(
            UserIdentity u,
            Support.Group g,
            ProblemState state,
            String version,
            String regression,
            boolean passed) {
        return support.change(
                u.id(),
                g.id(),
                new Support.Change(
                        g.revision(), state, "CODE", "CODE", version, regression, passed, null));
    }

    /** HTTP匿名/无CSRF拒绝；角色字段不能提权，合法CSRF才可保存。 */
    @Test
    void httpSecurityAndFailureReceipt() throws Exception {
        var u = actor();
        var input = input(target(u));
        String base = "http://127.0.0.1:" + port;
        var client = java.net.http.HttpClient.newHttpClient();
        var uri = java.net.URI.create(base + "/api/v1/support/feedback");
        assertThat(
                        client.send(
                                        java.net.http.HttpRequest.newBuilder(uri).GET().build(),
                                        java.net.http.HttpResponse.BodyHandlers.discarding())
                                .statusCode())
                .isEqualTo(401);
        String cookie = login(sessions, u);
        var req =
                java.net.http.HttpRequest.newBuilder(uri)
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .POST(
                                java.net.http.HttpRequest.BodyPublishers.ofString(
                                        codec.encode(input)));
        assertThat(
                        client.send(
                                        req.build(),
                                        java.net.http.HttpResponse.BodyHandlers.discarding())
                                .statusCode())
                .isEqualTo(403);
        var csrf =
                client.send(
                        java.net.http.HttpRequest.newBuilder(
                                        java.net.URI.create(base + "/api/v1/auth/csrf"))
                                .header("Cookie", cookie)
                                .GET()
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
        var token = codec.decode(csrf.body(), Map.class);
        req.header(token.get("headerName").toString(), token.get("token").toString());
        var result = client.send(req.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(codec.decode(result.body(), Map.class).get("saved")).isEqualTo(true);
        var maintenance =
                client.send(
                        java.net.http.HttpRequest.newBuilder(
                                        java.net.URI.create(
                                                base + "/api/v1/support/maintenance/groups"))
                                .header("Cookie", cookie)
                                .GET()
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(maintenance.statusCode()).isEqualTo(403);
        assertThat(codec.decode(maintenance.body(), Map.class).get("saved")).isEqualTo(false);
    }

    /** 相同质量类别分别建单；固定客户端故障按小时聚合，事件ID重放不多计次数。 */
    @Test
    void qualityIsNotAutomaticRootCause() {
        var u = actor();
        var t = target(u);
        var a = support.create(u.id(), input(t));
        var b = support.create(u.id(), input(t));
        grant(u);
        assertThat(support.group(u.id(), a.problem()).id())
                .isNotEqualTo(support.group(u.id(), b.problem()).id());
        var first = support.client(u.id(), UUID.randomUUID(), "RESOURCE", "APP");
        var second = support.client(u.id(), UUID.randomUUID(), "RESOURCE", "APP");
        assertThat(support.group(u.id(), first).id()).isEqualTo(support.group(u.id(), second).id());
        var replay = UUID.randomUUID();
        var link = tx.required(() -> supportStore.locate(u.id(), t));
        jdbc.update(
                "INSERT INTO problem_event(id,user_id,trace_id,component,code,severity)"
                        + " VALUES(?,?,?,'SUPPORT_TEST','REPLAY','WARN')",
                replay,
                u.id(),
                link.trace());
        var g = support.group(u.id(), replay);
        jdbc.update(
                "INSERT INTO problem_event(id,user_id,trace_id,component,code,severity)"
                        + " VALUES(?,?,?,'SUPPORT_TEST','REPLAY','WARN') ON CONFLICT DO NOTHING",
                replay,
                u.id(),
                link.trace());
        assertThat(support.group(u.id(), replay).count()).isEqualTo(g.count());
        var zero = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                        + " problem_group(id,component,code,bucket,severity,occurrences,first_at,last_at,representative)"
                        + " VALUES(?,'SUPPORT_TEST',?,clock_timestamp(),'ERROR',0,clock_timestamp(),clock_timestamp(),?)",
                zero,
                zero.toString().replace("-", "").toUpperCase(),
                zero);
        assertThat(support.groups(u.id(), 0)).allMatch(item -> item.count() > 0);
    }

    /** 旧回复没有operation/job追踪时保留可报告性，但不能编造原始span或trace。 */
    @Test
    void legacyReplyDoesNotInventTrace() {
        var u = actor();
        var target = target(u);
        jdbc.update(
                "UPDATE dialogue_input SET job_id=NULL WHERE user_id=? AND id=?",
                u.id(),
                target.id());
        var link = tx.required(() -> supportStore.locate(u.id(), target));
        assertThat(link.trace()).isNull();
        assertThat(link.span()).isNull();
        assertThat(support.create(u.id(), input(target)).saved()).isTrue();
    }

    /** 重复关联必须是组ID，不能形成环；旧revision不能覆盖并发状态维护。 */
    @Test
    void duplicateAndRevisionAreGuarded() {
        var u = actor();
        var t = target(u);
        var a = support.create(u.id(), input(t));
        var b = support.create(u.id(), input(t));
        grant(u);
        var ga = support.group(u.id(), a.problem());
        var gb = support.group(u.id(), b.problem());
        assertThatThrownBy(
                        () ->
                                support.change(
                                        u.id(),
                                        ga.id(),
                                        new Support.Change(
                                                ga.revision(),
                                                ProblemState.DUPLICATE,
                                                "UNKNOWN",
                                                "",
                                                "",
                                                "",
                                                false,
                                                b.problem())))
                .hasMessage("SUPPORT_INPUT_INVALID");
        var duplicate =
                support.change(
                        u.id(),
                        ga.id(),
                        new Support.Change(
                                ga.revision(),
                                ProblemState.DUPLICATE,
                                "UNKNOWN",
                                "",
                                "",
                                "",
                                false,
                                gb.id()));
        assertThatThrownBy(
                        () ->
                                support.change(
                                        u.id(),
                                        gb.id(),
                                        new Support.Change(
                                                gb.revision(),
                                                ProblemState.DUPLICATE,
                                                "UNKNOWN",
                                                "",
                                                "",
                                                "",
                                                false,
                                                ga.id())))
                .hasMessage("SUPPORT_INPUT_INVALID");
        assertThatThrownBy(() -> change(u, ga, ProblemState.TRIAGED, "", "", false))
                .hasMessage("SUPPORT_CONFLICT");
        assertThat(change(u, duplicate, ProblemState.REOPENED, "", "", false).state())
                .isEqualTo(ProblemState.REOPENED);
    }
}
