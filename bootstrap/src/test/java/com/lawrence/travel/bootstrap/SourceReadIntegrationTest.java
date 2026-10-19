package com.lawrence.travel.bootstrap;

import com.lawrence.travel.agent.runtime.SourceToolkit;
import com.lawrence.travel.application.document.DocumentService;
import com.lawrence.travel.application.document.Documents;
import com.lawrence.travel.application.document.SourceReadService;
import com.lawrence.travel.application.execution.Jobs;
import com.lawrence.travel.application.interaction.Dialogue;
import com.lawrence.travel.application.interaction.DialogueService;
import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.DialogueStore;
import com.lawrence.travel.application.port.DocumentStore;
import com.lawrence.travel.application.port.JobStore;
import com.lawrence.travel.application.port.ParserProcessPort;
import com.lawrence.travel.application.port.SessionContextStore;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.domain.identity.UserIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 真实PG归属/引用/删除与恢复；解析明确替身，所有文件及消息都是本地合成数据。 */
@EnabledIfEnvironmentVariable(named="TRAVEL_INTEGRATION_TESTS", matches="true")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"travel.engine.enabled=false", "travel.documents.cleanup-enabled=false"})
class SourceReadIntegrationTest {
    private static final Path ROOT = root();
    private final List<UserIdentity> users = new ArrayList<>();
    private final List<String> sessionIds = new ArrayList<>();
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired org.springframework.session.SessionRepository<? extends org.springframework.session.Session> sessions;
    @Autowired org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext webContext;
    @Autowired SourceReadService reads;
    @Autowired DocumentService documents;
    @Autowired DocumentStore store;
    @Autowired JobStore jobs;
    @Autowired DialogueService dialogue;
    @Autowired DialogueStore chats;
    @Autowired SessionContextStore contexts;
    @Autowired com.lawrence.travel.application.port.SourceReadStore sourceStore;
    @Autowired AccountStore accounts;
    @Autowired BusinessTransactions tx;
    @Autowired BudgetLedger ledger;
    @Autowired com.lawrence.travel.application.port.InteractionCodec codec;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.lawrence.travel.bootstrap.config.ExecutionControlProperties execution;
    @MockitoBean ParserProcessPort parser;

    /** @return 仅本例使用的原件根，不读写用户附件 */
    private static Path root() { try { return Files.createTempDirectory("travel-source-test-"); } catch (java.io.IOException e) { throw new IllegalStateException("TEST_ROOT_FAILED"); } }
    /** @param registry 单独替换原件目录 */
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("travel.documents.root", ROOT::toString); }
    /** 合成两段中文，保留真实字符定位和未读内容。 */
    @BeforeEach void setup() {
        when(parser.parse(any())).thenReturn(new Documents.Report("pdfbox-3.0.8_poi-5.5.1_v1", "OK",
                List.of(new Documents.Section(1, "杭州东到西湖，住宿在龙翔桥。"), new Documents.Section(3, "预算并非已订，第二天去灵隐寺。")), "未做OCR，表格需核对"));
    }
    /** 仅清理本轮随机身份的文件和PG数据，预算包括每次operation，保留其他用户资料。 */
    @AfterEach void clean() {
        awaitHttpExit();
        for (var id : sessionIds) sessions.deleteById(id);
        for (var user : users) {
            for (String key : jdbc.queryForList("SELECT file_key FROM uploaded_document WHERE user_id=?", String.class, user.id())) {
                try { Files.deleteIfExists(ROOT.resolve(key)); } catch (java.io.IOException e) { throw new IllegalStateException("TEST_CLEAN_FAILED"); }
            }
            tx.required(() -> {
                var budgets = jdbc.queryForList("SELECT budget_id FROM operation_context WHERE user_id=? UNION SELECT budget_id FROM dialogue_chat WHERE user_id=?", UUID.class, user.id(), user.id());
                jdbc.update("DELETE FROM uploaded_document WHERE user_id=?", user.id());
                jdbc.update("DELETE FROM dialogue_input WHERE user_id=?", user.id()); JobTestCleanup.remove(jdbc, user.id());
                jdbc.update("DELETE FROM dialogue_chat WHERE user_id=?", user.id());
                for (var budget : budgets) { jdbc.update("DELETE FROM call_meter WHERE run_id=?", budget); jdbc.update("DELETE FROM execution_budget WHERE run_id=?", budget); }
                jdbc.update("DELETE FROM app_user WHERE id=?", user.id()); return null;
            });
        }
    }
    /** @return 无登录能力的随机测试身份 */
    private UserIdentity actor() {
        UUID id = UUID.randomUUID(); String name = "src_" + id.toString().replace("-", "").substring(0, 20);
        accounts.insert(id, name, "__NON_LOGIN_TEST_HASH__"); var user = new UserIdentity(id, name); users.add(user); return user;
    }
    /** @param user 合成用户 @param chat 私有聊天 @return 已提交的READY文档 */
    private UUID ready(UserIdentity user, UUID chat) {
        var d = documents.upload(user, chat, UUID.randomUUID(), "计划.txt", "原件，仅本地夹具".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var e = store.get(new Jobs.Scope(user.id(), chat), d.id()); var c = jobs.claim(new Jobs.Scope(user.id(), chat), e.jobId()).orElseThrow();
        documents.commit(c, documents.execute(c), () -> { }); return d.id();
    }
    /** @param user 合成用户 @param chat 聊天 @param text 输入 @return 经现有单调度领取的真实输入；不运行模型 */
    private Dialogue.Claim receive(UserIdentity user, UUID chat, String text) {
        dialogue.send(user, chat, UUID.randomUUID(), text); var c = tx.required(chats::claim).orElseThrow();
        assertThat(c.userId()).isEqualTo(user.id()); return c;
    }
    /** @param c 原尝试 @param reply 合成回复，同事务完成作业投影 */
    private void finish(Dialogue.Claim c, String reply) { tx.required(() -> { chats.finish(c, "DONE", reply, null, ""); return null; }); }

    /** 中文词面匹配、百分号无通配语义；引用可精确回到真实字符范围，不猜缺失页。 */
    @Test void chineseLiteralSearchAndExactCitations() {
        var u = actor(); var chat = UUID.randomUUID(); var d = ready(u, chat);
        var result = reads.search(u.id(), chat, d, "住宿"); assertThat(result.fragments()).hasSize(1);
        var f = result.fragments().getFirst(); assertThat(f.locator().position()).isEqualTo(1);
        assertThat(reads.reference(u.id(), chat, f.reference())).isEqualTo(f);
        assertThat(reads.search(u.id(), chat, d, "%").fragments()).isEmpty();
        assertThat(reads.search(u.id(), chat, d, "不存在").fragments()).isEmpty();
        assertThatThrownBy(() -> reads.section(u.id(), chat, d, 2, 0, 100)).hasMessage("SOURCE_INPUT_INVALID");
    }
    /** 不能越用户、聊天或伪造文件版本，既有引用损坏也不返回。 */
    @Test void ownershipAndCorruptReferencesAreRejected() {
        var u = actor(); var other = actor(); var chat = UUID.randomUUID(); var d = ready(u, chat); var otherChat = UUID.randomUUID(); ready(u, otherChat);
        var f = reads.section(u.id(), chat, d, 1, 0, 3);
        assertThatThrownBy(() -> reads.reference(other.id(), chat, f.reference())).hasMessage("SOURCE_NOT_FOUND");
        assertThatThrownBy(() -> reads.reference(u.id(), otherChat, f.reference())).hasMessage("SOURCE_NOT_FOUND");
        jdbc.update("UPDATE source_read_result SET body='corrupt' WHERE id=?", f.reference());
        assertThatThrownBy(() -> reads.reference(u.id(), chat, f.reference())).hasMessage("SOURCE_NOT_FOUND");
    }
    /** 原文截断明确，页/段偏移和长度都有边界，不会把部分读取标为全文。 */
    @Test void sectionRangesAreBoundedAndExplicit() {
        var u = actor(); var chat = UUID.randomUUID(); var d = ready(u, chat);
        var f = reads.section(u.id(), chat, d, 3, 2, 4); assertThat(f.text()).isEqualTo("并非已订"); assertThat(f.truncated()).isTrue();
        assertThatThrownBy(() -> reads.section(u.id(), chat, d, 1, -1, 1)).hasMessage("SOURCE_INPUT_INVALID");
        assertThatThrownBy(() -> reads.section(u.id(), chat, d, 1, 0, 4001)).hasMessage("SOURCE_INPUT_INVALID");
        assertThatThrownBy(() -> reads.section(u.id(), chat, d, 1, 999, 10)).hasMessage("SOURCE_INPUT_INVALID");
    }
    /** PG工作文件在新服务/工具实例中仍可读取；同路径不能悄悄换正文，路径逃逸被拒。 */
    @Test void workFilesRecoverWithoutFilesystemCache() {
        var u = actor(); var chat = UUID.randomUUID(); ready(u, chat); var c = receive(u, chat, "帮我查资料");
        String body = "独立工作记忆".repeat(2000); reads.offload(c.budgetId(), c.token(), "/work/coordinator/call_1", body);
        reads.offload(c.budgetId(), c.token(), "/work/coordinator/call_1", body);
        var rebuilt = new SourceReadService(sourceStore, store, jobs, tx, codec, com.lawrence.travel.application.safety.SafetyGate.local());
        assertThat(rebuilt.file(c.budgetId(), c.token(), "/work/coordinator/call_1", 0, 10)).contains("独立工作记忆", "nextOffset");
        assertThatThrownBy(() -> reads.offload(c.budgetId(), c.token(), "/work/coordinator/call_1", "其他内容")).hasMessage("SOURCE_NOT_FOUND");
        assertThatThrownBy(() -> reads.file(c.budgetId(), c.token(), "../../.env", 0, 10)).hasMessage("SOURCE_INPUT_INVALID");
    }
    /** 删除瞬间撤销PG工作文件及引用，旧上下文与旧尝试不能再读或完成提交。 */
    @Test void deleteRevokesFilesContextAndLatePublication() {
        var u = actor(); var chat = UUID.randomUUID(); var d = ready(u, chat); var c = receive(u, chat, "读取资料");
        var snapshot = contexts.load(c.budgetId(), "coordinator");
        var f = reads.section(c.budgetId(), c.token(), d, 1, 0, 10);
        reads.offload(c.budgetId(), c.token(), "/work/coordinator/call_2", "源材料工作记忆");
        var other = documents.upload(u, chat, UUID.randomUUID(), "其他.txt", "无关原件".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var otherEntry = store.get(new Jobs.Scope(u.id(), chat), other.id());
        var otherClaim = jobs.claim(new Jobs.Scope(u.id(), chat), otherEntry.jobId()).orElseThrow();
        jdbc.update("INSERT INTO session_context_summary(id,user_id,scope_id,role,dependency_hash,source_budget,body) VALUES(?,?,?,'coordinator',?,?,CAST(? AS jsonb))", UUID.randomUUID(), u.id(), chat, "0".repeat(64), c.budgetId(), "{\"synthetic\":true}");
        documents.action(u, chat, d, "DELETE");
        assertThat(jdbc.queryForObject("SELECT state FROM background_job WHERE operation_id=? AND kind='CHAT_INPUT'", String.class, c.budgetId())).isEqualTo("CANCELLED");
        assertThat(jobs.read(new Jobs.Scope(u.id(), chat), otherClaim.job().id()).state()).isEqualTo(com.lawrence.travel.domain.execution.BackgroundJob.State.RUNNING);
        assertThatThrownBy(() -> reads.reference(u.id(), chat, f.reference())).hasMessage("SOURCE_NOT_FOUND");
        assertThatThrownBy(() -> reads.file(c.budgetId(), c.token(), "/work/coordinator/call_2", 0, 10)).hasMessage("SOURCE_NOT_FOUND");
        assertThatThrownBy(() -> contexts.guard(snapshot)).hasMessage("PREFERENCE_CHANGED");
        assertThatThrownBy(() -> finish(c, "旧材料回复")).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_read_result WHERE user_id=?", Integer.class, u.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM session_context_summary WHERE user_id=?", Integer.class, u.id())).isZero();
    }
    /** 混合旧回复无法逐句追溯时整条遮蔽，后续回复链同样遮蔽，原用户输入保留。 */
    @Test void deleteMasksDerivedReplyChainAndValidReferences() {
        var u = actor(); var chat = UUID.randomUUID(); var d = ready(u, chat); var c = receive(u, chat, "读取私有材料");
        var f = reads.section(c.budgetId(), c.token(), d, 1, 0, 10); finish(c, "来源私有内容");
        assertThat(reads.references(u.id(), chat, c.messageId())).contains(f.reference());
        var next = receive(u, chat, "解释上一条"); finish(next, "间接复制私有内容");
        documents.action(u, chat, d, "DELETE");
        assertThat(chats.read(u.id(), chat).messages()).allSatisfy(m -> { assertThat(m.reply()).doesNotContain("私有内容"); assertThat(m.text()).isNotBlank(); });
        assertThat(reads.references(u.id(), chat, c.messageId())).isEmpty();
    }
    /** 历史检索可超出近期窗口，但不能读取当前轮、其他聊天或已删除偏好版本。 */
    @Test void historicalSourcesRespectPreferenceAndCurrentTurn() {
        var u = actor(); var chat = UUID.randomUUID();
        var old = receive(u, chat, "旧轮次成都"); finish(old, "成都旧回复");
        for (int i = 0; i < 26; i++) { var c = receive(u, chat, "中间轮" + i); finish(c, "完整回复"); }
        var current = receive(u, chat, "当前不应检索到成都");
        assertThat(reads.history(current.budgetId(), current.token(), "成都")).contains("旧轮次成都").doesNotContain("当前不应检索到成都");
        jdbc.update("UPDATE app_user SET preference_revision=preference_revision+1 WHERE id=?", u.id());
        assertThat(reads.history(current.budgetId(), current.token(), "成都")).doesNotContain("旧轮次成都");
    }
    /** 旧token在失联接管或取消后不能读工作文件，不能刷新成新尝试身份。 */
    @Test void cancelledAndExpiredAttemptCannotAdoptNewToken() {
        var u = actor(); var chat = UUID.randomUUID(); var c = receive(u, chat, "当前");
        reads.offload(c.budgetId(), c.token(), "/work/coordinator/original", "旧产物");
        dialogue.act(u, chat, c.messageId(), "WITHDRAW");
        assertThatThrownBy(() -> reads.file(c.budgetId(), c.token(), "/work/coordinator/original", 0, 10)).hasMessage("SOURCE_NOT_FOUND");
        var old = receive(u, chat, "失联重领"); reads.offload(old.budgetId(), old.token(), "/work/coordinator/expired", "旧尝试");
        jdbc.update("UPDATE background_job SET lease_until=clock_timestamp()-interval '1 second' WHERE operation_id=?", old.budgetId());
        jdbc.update("UPDATE dialogue_input SET lease_until=clock_timestamp()-interval '1 second' WHERE user_id=? AND id=?", u.id(), old.messageId());
        var fresh = tx.required(chats::claim).orElseThrow(); assertThat(fresh.token()).isGreaterThan(old.token());
        assertThatThrownBy(() -> reads.file(old.budgetId(), old.token(), "/work/coordinator/expired", 0, 10)).hasMessage("SOURCE_NOT_FOUND");
    }

    /** 成功尝试的工作文件跨输入和新工具实例恢复；取消尝试永远不进入可恢复目录。 */
    @Test void onlySuccessfulWorkingMemoryHydratesNextInput() {
        var u = actor(); var chat = UUID.randomUUID(); var document = ready(u, chat); var first = receive(u, chat, "第一轮");
        reads.section(first.budgetId(), first.token(), document, 1, 0, 10);
        reads.offload(first.budgetId(), first.token(), "/work/coordinator/success", "已完成来源"); finish(first, "已读取");
        var second = receive(u, chat, "第二轮");
        assertThat(reads.files(second.budgetId(), second.token())).extracting(com.lawrence.travel.application.document.SourceRead.WorkFile::path).contains("/work/coordinator/success");
        assertThat(reads.file(second.budgetId(), second.token(), "/work/coordinator/success", 0, 30)).contains("已完成来源");
        finish(second, "间接读取成功工作文件");
        assertThat(reads.references(u.id(), chat, second.messageId())).hasSize(1);
        var cancelled = receive(u, chat, "随后取消");
        reads.offload(cancelled.budgetId(), cancelled.token(), "/work/coordinator/cancelled", "不得复活"); dialogue.act(u, chat, cancelled.messageId(), "WITHDRAW");
        var third = receive(u, chat, "第三轮");
        assertThat(reads.files(third.budgetId(), third.token())).extracting(com.lawrence.travel.application.document.SourceRead.WorkFile::path).doesNotContain("/work/coordinator/cancelled");
        assertThatThrownBy(() -> reads.file(third.budgetId(), third.token(), "/work/coordinator/cancelled", 0, 30)).hasMessage("SOURCE_NOT_FOUND");
    }

    /** 资料工具不依赖真实服务开关，确定性本地读取也计入原工具预算，不增加模型请求。 */
    @Test void sourceToolsShareOriginalBudgetAndFailClosed() {
        var u = actor(); var chat = UUID.randomUUID(); ready(u, chat); var c = receive(u, chat, "工具预算");
        var limits = execution.limits().runLimits(); var boundary = new SourceToolkit(reads, c.budgetId(), c.token(), ledger, limits);
        var tools = new com.lawrence.travel.agent.runtime.SourceTools(reads, c.budgetId(), c.token(), ledger, limits, boundary);
        tools.list(); assertThat(ledger.read(c.budgetId()).toolCalls()).isEqualTo(1); assertThat(ledger.read(c.budgetId()).modelCalls()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM call_meter WHERE run_id=? AND status='COMPLETED'", Integer.class, c.budgetId())).isEqualTo(1);
        assertThatThrownBy(() -> tools.file("/etc/passwd", 0, 10)).hasMessage("SOURCE_READ_REJECTED");
        assertThatThrownBy(boundary::check).hasMessage("SOURCE_READ_REJECTED");
        assertThat(ledger.read(c.budgetId()).toolCalls()).isEqualTo(2);
    }

    /** 有界搜索到达五段后明确告知未遍历完整范围；恶意原文不能经引用绕过安全边界。 */
    @Test void searchLimitsAndUnsafeStoredTextAreExplicit() {
        var u = actor(); var chat = UUID.randomUUID(); var d = ready(u, chat);
        var json = new com.lawrence.travel.infrastructure.travel.TravelJson();
        var report = new Documents.Report("pdfbox-3.0.8_poi-5.5.1_v1", "OK", java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(i -> new Documents.Section(i, "杭州".repeat(1000))).toList(), "合成报告");
        jdbc.update("UPDATE uploaded_document SET report=CAST(? AS jsonb) WHERE id=?", json.encode(report), d);
        var result = reads.search(u.id(), chat, d, "杭州"); assertThat(result.fragments()).hasSize(5); assertThat(result.complete()).isFalse();
        assertThat(result.fragments()).allSatisfy(f -> { assertThat(f.text()).hasSize(1200); assertThat(f.truncated()).isTrue(); });
        var unsafe = new Documents.Report("pdfbox-3.0.8_poi-5.5.1_v1", "OK", List.of(new Documents.Section(1, "<script>synthetic</script>")), "合成拒绝");
        jdbc.update("UPDATE uploaded_document SET report=CAST(? AS jsonb) WHERE id=?", json.encode(unsafe), d);
        assertThatThrownBy(() -> reads.section(u.id(), chat, d, 1, 0, 100)).isInstanceOf(com.lawrence.travel.application.safety.SafetyGate.Failure.class);
    }

    /** 真实HTTP/Redis验证登录、CSRF、私有引用和删除拒读；认证内容只在本地内存传递。 */
    @Test void httpSourcesRequireIdentityCsrfAndCurrentReference() throws Exception {
        var u = actor(); var other = actor(); var chat = UUID.randomUUID(); var d = ready(u, chat);
        var client = java.net.http.HttpClient.newHttpClient(); var json = new com.lawrence.travel.infrastructure.travel.TravelJson();
        String base = "http://127.0.0.1:" + port, cookie = login(sessions, u);
        var url = java.net.URI.create(base + "/api/v1/chats/" + chat + "/documents/" + d + "/search");
        var csrfResponse = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/v1/auth/csrf")).header("Cookie", cookie).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        var csrf = json.decode(csrfResponse.body(), java.util.Map.class);
        var request = java.net.http.HttpRequest.newBuilder(url).header("Cookie", cookie).header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"query\":\"住宿\"}"));
        assertThat(client.send(request.build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        request.header(csrf.get("headerName").toString(), csrf.get("token").toString());
        var response = client.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString()); assertThat(response.statusCode()).isEqualTo(200);
        var result = json.decode(response.body(), java.util.Map.class);
        var reference = ((java.util.Map<?,?>) ((List<?>) result.get("fragments")).getFirst()).get("reference").toString();
        var refUrl = java.net.URI.create(base + "/api/v1/chats/" + chat + "/references/" + reference);
        assertThat(client.send(java.net.http.HttpRequest.newBuilder(refUrl).GET().build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
        assertThat(client.send(java.net.http.HttpRequest.newBuilder(refUrl).header("Cookie", login(sessions, other)).GET().build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
        documents.action(u, chat, d, "DELETE");
        assertThat(client.send(java.net.http.HttpRequest.newBuilder(refUrl).header("Cookie", cookie).GET().build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
    }

    /** @param repository 本地Redis会话 @param user 合成身份 @param <S> 会话类型 @return 仅本地HTTP使用的Cookie，不进入日志或断言 */
    private <S extends org.springframework.session.Session> String login(org.springframework.session.SessionRepository<S> repository, UserIdentity user) {
        S session = repository.createSession(); var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(user, null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));
        session.setAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context); repository.save(session); sessionIds.add(session.getId());
        return "SESSION=" + java.util.Base64.getEncoder().encodeToString(session.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 等本地HTTP响应真正退出再删会话，防止Redis保存与夹具清理竞争。 */
    private void awaitHttpExit() {
        var server = (org.springframework.boot.tomcat.TomcatWebServer) webContext.getWebServer();
        var protocol = (org.apache.coyote.AbstractProtocol<?>) server.getTomcat().getConnector().getProtocolHandler();
        if (protocol.getExecutor() instanceof org.apache.tomcat.util.threads.ThreadPoolExecutor pool) {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (pool.getActiveCount() > 0 && System.nanoTime() < deadline) java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10));
            assertThat(pool.getActiveCount()).isZero();
        }
    }

    /** 工作文件累计容量必须计算本次写入，不能在边界后再多接受一份大结果。 */
    @Test void workingMemoryCapacityIncludesPendingWrite() {
        var u = actor(); var chat = UUID.randomUUID(); var c = receive(u, chat, "工作文件容量");
        String body = "x".repeat(99999);
        for (int i = 0; i < 20; i++) reads.offload(c.budgetId(), c.token(), "/work/coordinator/quota_" + i, body);
        assertThatThrownBy(() -> reads.offload(c.budgetId(), c.token(), "/work/coordinator/over", "x".repeat(100))).hasMessage("SOURCE_CAPACITY");
        assertThat(jdbc.queryForObject("SELECT sum(char_length(body)) FROM source_read_result WHERE user_id=?", Long.class, u.id())).isEqualTo(1999980);
    }
}
