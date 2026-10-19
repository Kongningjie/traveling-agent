package com.lawrence.travel.bootstrap;

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

/** 真实PG分析/建议/采纳/删除；解析与理解均为明确替身，所有文件及消息都是本地合成数据。 */
@EnabledIfEnvironmentVariable(named="TRAVEL_INTEGRATION_TESTS", matches="true")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"travel.engine.enabled=false", "travel.documents.cleanup-enabled=false"})
class AdviceIntegrationTest {
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
    @Autowired AccountStore accounts;
    @Autowired BusinessTransactions tx;
    @Autowired com.lawrence.travel.application.port.InteractionCodec codec;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ParserProcessPort parser;
    @MockitoBean com.lawrence.travel.application.port.DialogueInterpreter interpreter;
    @Autowired com.lawrence.travel.application.document.AdviceService advice;
    @Autowired com.lawrence.travel.application.port.ExecutionEngineStore engine;

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
                var budgets = jdbc.queryForList("SELECT budget_id FROM operation_context WHERE user_id=? UNION SELECT budget_id FROM dialogue_chat WHERE user_id=? UNION SELECT id FROM execution_run WHERE user_id=?", UUID.class, user.id(), user.id(),user.id());
                jdbc.update("DELETE FROM uploaded_document WHERE user_id=?", user.id());
                jdbc.update("DELETE FROM dialogue_input WHERE user_id=?", user.id()); JobTestCleanup.remove(jdbc, user.id());
                jdbc.update("DELETE FROM dialogue_chat WHERE user_id=?", user.id());
                jdbc.update("UPDATE travel_task SET current_run_id=NULL,current_version_id=NULL WHERE user_id=?",user.id());
                for(String table:List.of("evidence_notice","field_evidence","itinerary_acceptance_request","itinerary_acceptance","itinerary_report","itinerary_candidate","negotiation_card","conversation_message","decision_response","decision_request","run_event","execution_start_request"))jdbc.update("DELETE FROM "+table+" WHERE user_id=?",user.id());
                jdbc.update("DELETE FROM artifact_content WHERE artifact_id IN(SELECT id FROM artifact_record WHERE user_id=?)",user.id());
                for(String table:List.of("artifact_record","step_attempt","outbox_event","execution_step","execution_run","change_request","itinerary_snapshot","travel_constraint_revision","travel_task","travel_idempotency"))jdbc.update("DELETE FROM "+table+" WHERE user_id=?",user.id());
                for(var budget:budgets){jdbc.update("DELETE FROM call_meter WHERE run_id=?",budget);jdbc.update("DELETE FROM execution_budget WHERE run_id=?",budget);}
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
    /** @param user 合成身份 @param chat 聊天 @param doc 已解析原件 @param changes 提议字段 @param count 建议数 @return 经过实际对话处理的建议 */
    private List<com.lawrence.travel.application.document.Advice.View> analyze(UserIdentity user,UUID chat,UUID doc,java.util.Map<String,String> changes,int count){
        String text="请检查并优化我的计划，只给建议";dialogue.send(user,chat,UUID.randomUUID(),text);
        when(interpreter.interpret(any())).thenAnswer(invocation -> {
            Dialogue.Claim c=invocation.getArgument(0);var fragment=reads.section(c.budgetId(),c.token(),doc,1,0,1000);
            var draft=new com.lawrence.travel.domain.interaction.PlanAdvice.Draft(fragment.reference(),"住宿在龙翔桥",com.lawrence.travel.domain.interaction.PlanAdvice.Statement.INTENDED,"节奏调整","材料仅表示打算，未核实开放时间","减少赶路","涉及旅行节奏",com.lawrence.travel.domain.interaction.PlanAdvice.Evidence.USER_MATERIAL,"",changes);
            return new com.lawrence.travel.domain.interaction.DialogueIntent.Result(List.of(new com.lawrence.travel.domain.interaction.DialogueIntent.Intent(com.lawrence.travel.domain.interaction.DialogueIntent.Kind.CONVERSATION,com.lawrence.travel.domain.interaction.DialogueIntent.Action.OPTIMIZE_PLAN,text)),List.of(),"只是优化建议","",null,"","","",List.of(),java.util.Collections.nCopies(count,draft));
        });
        dialogue.claimWork().orElseThrow().run();return advice.list(user,chat);
    }
    /** 真正聊天理解只产建议，不创建旅行；原文和材料性质完整保留。 */
    @Test void analysisIsReadOnlyAndCitesActualMaterial(){var u=actor();var chat=UUID.randomUUID();var doc=ready(u,chat);var items=analyze(u,chat,doc,java.util.Map.of("pace","慢游"),1);assertThat(items).hasSize(1);assertThat(items.getFirst().draft().statement()).isEqualTo(com.lawrence.travel.domain.interaction.PlanAdvice.Statement.INTENDED);assertThat(dialogue.read(u,chat).chat().taskId()).isNull();assertThat(dialogue.read(u,chat).messages().getLast().reply()).contains("尚未采纳");assertThat(jdbc.queryForObject("SELECT count(*) FROM travel_task WHERE user_id=?",Integer.class,u.id())).isZero();}
    /** 逐项确认才新建旅行；重复确认不创建新任务、变更或预算，尚无生效版本。 */
    @Test void adoptionIsAtomicIdempotentAndNeverAcceptsItinerary(){var u=actor();var chat=UUID.randomUUID();var items=analyze(u,chat,ready(u,chat),java.util.Map.of("pace","慢游"),1);var id=items.getFirst().id();var applied=advice.act(u,chat,id,"ADOPT",java.util.Map.of(),true);var again=advice.act(u,chat,id,"ADOPT",java.util.Map.of(),true);assertThat(again).isEqualTo(applied);assertThat(applied.state()).isEqualTo("APPLIED");assertThat(dialogue.read(u,chat).chat().taskId()).isEqualTo(applied.task());assertThat(jdbc.queryForObject("SELECT current_version_id FROM travel_task WHERE id=?",UUID.class,applied.task())).isNull();assertThat(jdbc.queryForObject("SELECT count(*) FROM change_request WHERE user_id=?",Integer.class,u.id())).isEqualTo(1);assertThatThrownBy(()->advice.act(u,chat,id,"CUSTOM",java.util.Map.of("pace","紧凑"),true)).hasMessage("STALE_VERSION");}
    /** 无明确确认、自定义越界及有未决输入时均不能开始规划。 */
    @Test void adoptionRequiresConfirmationScopeAndIdleInput(){var u=actor();var chat=UUID.randomUUID();var id=analyze(u,chat,ready(u,chat),java.util.Map.of("pace","慢游"),1).getFirst().id();assertThatThrownBy(()->advice.act(u,chat,id,"ADOPT",java.util.Map.of(),false)).hasMessage("ADVICE_CONFIRMATION_REQUIRED");assertThatThrownBy(()->advice.act(u,chat,id,"CUSTOM",java.util.Map.of("cities","北京"),true)).hasMessage("ADVICE_SCOPE_INVALID");dialogue.send(u,chat,UUID.randomUUID(),"先别执行，我还要改");assertThatThrownBy(()->advice.act(u,chat,id,"ADOPT",java.util.Map.of(),true)).hasMessage("ADVICE_REQUIRES_CLARIFICATION");assertThat(dialogue.read(u,chat).chat().taskId()).isNull();}
    /** 拒绝无业务副作用；另一建议可独立自定义，第一次变更使重叠旧建议过期。 */
    @Test void rejectCustomAndOverlapStaySeparate(){var u=actor();var chat=UUID.randomUUID();var items=analyze(u,chat,ready(u,chat),java.util.Map.of("pace","慢游"),3);var rejected=advice.act(u,chat,items.get(0).id(),"REJECT",java.util.Map.of(),true);assertThat(rejected.state()).isEqualTo("REJECTED");assertThat(dialogue.read(u,chat).chat().taskId()).isNull();var changed=advice.act(u,chat,items.get(1).id(),"CUSTOM",java.util.Map.of("pace","更轻松"),true);assertThat(changed.task()).isNotNull();assertThat(advice.list(u,chat)).anyMatch(x -> x.id().equals(items.get(2).id())&&x.state().equals("STALE"));assertThatThrownBy(()->advice.act(u,chat,items.get(2).id(),"ADOPT",java.util.Map.of(),true)).hasMessage("STALE_VERSION");}
    /** 用户与文件删除水位均强约束，删除立即清除建议正文及读取权。 */
    @Test void ownershipAndDeletedSourcesCannotBeAdopted(){var u=actor();var other=actor();var chat=UUID.randomUUID();var doc=ready(u,chat);var id=analyze(u,chat,doc,java.util.Map.of("pace","慢游"),1).getFirst().id();assertThatThrownBy(()->advice.act(other,chat,id,"ADOPT",java.util.Map.of(),true)).hasMessage("NOT_FOUND");documents.action(u,chat,doc,"DELETE");assertThat(advice.list(u,chat)).isEmpty();assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_advice WHERE user_id=?",Integer.class,u.id())).isZero();assertThatThrownBy(()->advice.act(u,chat,id,"ADOPT",java.util.Map.of(),true)).hasMessage("NOT_FOUND");}
    /** 偏好版本变化清除派生建议，不通过建议恢复已删除偏好。 */
    @Test void preferenceChangePurgesDerivedAdvice(){var u=actor();var chat=UUID.randomUUID();analyze(u,chat,ready(u,chat),java.util.Map.of("pace","慢游"),1);jdbc.update("UPDATE app_user SET preference_revision=preference_revision+1 WHERE id=?",u.id());assertThat(advice.list(u,chat)).isEmpty();assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_advice WHERE user_id=?",Integer.class,u.id())).isZero();}
    /** 并发重放在真实PG用户锁下归并为唯一收件。 */
    @Test void simultaneousAdoptionsShareOneRequest() throws Exception {var u=actor();var chat=UUID.randomUUID();var id=analyze(u,chat,ready(u,chat),java.util.Map.of("pace","慢游"),1).getFirst().id();try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){var a=pool.submit(()->advice.act(u,chat,id,"ADOPT",java.util.Map.of(),true));var b=pool.submit(()->advice.act(u,chat,id,"ADOPT",java.util.Map.of(),true));assertThat(a.get()).isEqualTo(b.get());}assertThat(jdbc.queryForObject("SELECT count(*) FROM change_request WHERE user_id=?",Integer.class,u.id())).isEqualTo(1);}
    /** 模型虚构的引用/摘录不落建议，原输入暂停而不修改业务。 */
    @Test void inventedReferenceIsRejectedBeforePublishing(){var u=actor();var chat=UUID.randomUUID();ready(u,chat);String text="检查计划";dialogue.send(u,chat,UUID.randomUUID(),text);var draft=new com.lawrence.travel.domain.interaction.PlanAdvice.Draft(UUID.randomUUID(),"编造原文",com.lawrence.travel.domain.interaction.PlanAdvice.Statement.UNKNOWN,"检查","无法定位","先补充","无",com.lawrence.travel.domain.interaction.PlanAdvice.Evidence.UNKNOWN,"请补充",java.util.Map.of());when(interpreter.interpret(any())).thenReturn(new com.lawrence.travel.domain.interaction.DialogueIntent.Result(List.of(new com.lawrence.travel.domain.interaction.DialogueIntent.Intent(com.lawrence.travel.domain.interaction.DialogueIntent.Kind.CONVERSATION,com.lawrence.travel.domain.interaction.DialogueIntent.Action.CHECK_PLAN,text)),List.of(),"检查结果","",null,"","","",List.of(),List.of(draft)));dialogue.claimWork().orElseThrow().run();assertThat(advice.list(u,chat)).isEmpty();assertThat(dialogue.read(u,chat).messages().getLast().state()).isEqualTo("PAUSED");}
    /** 浏览器可读的真实引用仍不能冒充本次模型尝试实际读取。 */
    @Test void browserReferenceCannotMasqueradeAsCurrentToolRead(){var u=actor();var chat=UUID.randomUUID();var doc=ready(u,chat);var reference=reads.section(u.id(),chat,doc,1,0,1000);String text="解释原计划";dialogue.send(u,chat,UUID.randomUUID(),text);var draft=new com.lawrence.travel.domain.interaction.PlanAdvice.Draft(reference.reference(),"住宿在龙翔桥",com.lawrence.travel.domain.interaction.PlanAdvice.Statement.UNKNOWN,"说明","需要核对","先核对","资料",com.lawrence.travel.domain.interaction.PlanAdvice.Evidence.USER_MATERIAL,"",java.util.Map.of());when(interpreter.interpret(any())).thenReturn(new com.lawrence.travel.domain.interaction.DialogueIntent.Result(List.of(new com.lawrence.travel.domain.interaction.DialogueIntent.Intent(com.lawrence.travel.domain.interaction.DialogueIntent.Kind.CONVERSATION,com.lawrence.travel.domain.interaction.DialogueIntent.Action.EXPLAIN_PLAN,text)),List.of(),"解释","",null,"","","",List.of(),List.of(draft)));dialogue.claimWork().orElseThrow().run();assertThat(advice.list(u,chat)).isEmpty();assertThat(dialogue.read(u,chat).messages().getLast().state()).isEqualTo("PAUSED");}
    /** 没有可执行字段的解释不能被确认按钮变成空旅行。 */
    @Test void explanatoryAdviceDoesNotCreatePlaceholderTravel(){var u=actor();var chat=UUID.randomUUID();var id=analyze(u,chat,ready(u,chat),java.util.Map.of(),1).getFirst().id();assertThatThrownBy(()->advice.act(u,chat,id,"ADOPT",java.util.Map.of(),true)).hasMessage("ADVICE_REQUIRES_CLARIFICATION");assertThat(dialogue.read(u,chat).chat().taskId()).isNull();}
    /** 私有HTTP与CSRF门禁有效；成功确认只登记一次变更，不通过模型或GET隐式执行。 */
    @Test void httpRequiresOwnerCsrfAndExplicitConfirmation() throws Exception {
        var u=actor();var other=actor();var chat=UUID.randomUUID();var id=analyze(u,chat,ready(u,chat),java.util.Map.of("pace","慢游"),1).getFirst().id();
        var client=java.net.http.HttpClient.newHttpClient();String base="http://127.0.0.1:"+port,cookie=login(sessions,u);var uri=java.net.URI.create(base+"/api/v1/chats/"+chat+"/advice");
        assertThat(client.send(java.net.http.HttpRequest.newBuilder(uri).GET().build(),java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
        assertThat(client.send(java.net.http.HttpRequest.newBuilder(uri).header("Cookie",login(sessions,other)).GET().build(),java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
        var csrf=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/v1/auth/csrf")).header("Cookie",cookie).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());var token=codec.decode(csrf.body(),java.util.Map.class);
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(uri+"/"+id+"/actions")).header("Cookie",cookie).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"action\":\"ADOPT\",\"changes\":{},\"confirmed\":true}"));
        assertThat(client.send(request.build(),java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);request.header(token.get("headerName").toString(),token.get("token").toString());
        assertThat(client.send(request.build(),java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM change_request WHERE user_id=?",Integer.class,u.id())).isEqualTo(1);
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
}
