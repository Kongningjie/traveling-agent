package com.lawrence.travel.bootstrap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import com.lawrence.travel.application.document.DocumentService;
import com.lawrence.travel.application.document.Documents;
import com.lawrence.travel.application.execution.Jobs;
import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.DocumentStore;
import com.lawrence.travel.application.port.JobStore;
import com.lawrence.travel.application.port.ParserProcessPort;
import com.lawrence.travel.domain.execution.BackgroundJob;
import com.lawrence.travel.domain.identity.UserIdentity;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
/** 真实本机PG及文件系统；解析器明确替身，不访问模型或网络服务。 */
@EnabledIfEnvironmentVariable(named="TRAVEL_INTEGRATION_TESTS",matches="true")
@ActiveProfiles("local") @SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"travel.engine.enabled=false","travel.documents.cleanup-enabled=false"})
class DocumentIntegrationTest {
    private static final Path ROOT=temp();
    @Autowired DocumentService service;
    @Autowired DocumentStore store;
    @Autowired JobStore jobs;
    @Autowired AccountStore accounts;
    @Autowired BusinessTransactions tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext webContext;
    @MockitoBean ParserProcessPort parser;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired org.springframework.session.SessionRepository<? extends org.springframework.session.Session> sessions;
    private final List<String> sessionIds=new ArrayList<>();
    private final List<UserIdentity> users=new ArrayList<>();
    /** @return 本例独立原件目录，不触及用户真实附件 */
    private static Path temp() {try{return java.nio.file.Files.createTempDirectory("travel-document-test-");}catch(java.io.IOException e){throw new IllegalStateException("TEST_ROOT_FAILED");}}
    /** @param registry 只替换本机原件目录 */
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {registry.add("travel.documents.root",ROOT::toString);}
    /** 固定合成解析，不将替身写成真实解析验收。 */
    @BeforeEach void setup() {when(parser.parse(any())).thenReturn(new Documents.Report("pdfbox-3.0.8_poi-5.5.1_v1","OK",List.of(new Documents.Section(1,"旅行文本")),"未做OCR，表格需核对"));}
    /** 只清理随机用户和测试原件，保留其他会话／计量。 */
    @AfterEach void clean() {awaitHttpExit();for(var id:sessionIds)sessions.deleteById(id);for(var u:users) {for(var e:jdbc.queryForList("SELECT file_key FROM uploaded_document WHERE user_id=?",String.class,u.id())) {try{java.nio.file.Files.deleteIfExists(ROOT.resolve(e));}catch(java.io.IOException ignored){throw new IllegalStateException("TEST_CLEAN_FAILED");}}
        tx.required(()->{var budgets=jdbc.queryForList("SELECT id FROM operation_context WHERE user_id=? UNION SELECT budget_id FROM dialogue_chat WHERE user_id=?",UUID.class,u.id(),u.id());jdbc.update("DELETE FROM uploaded_document WHERE user_id=?",u.id());JobTestCleanup.remove(jdbc,u.id());jdbc.update("DELETE FROM dialogue_chat WHERE user_id=?",u.id());for(var b:budgets)jdbc.update("DELETE FROM execution_budget WHERE run_id=?",b);jdbc.update("DELETE FROM app_user WHERE id=?",u.id());return null;});}}
    /** @return 本例不可登录测试身份 */
    private UserIdentity actor() {var id=UUID.randomUUID();var name="doc_"+id.toString().replace("-","").substring(0,20);accounts.insert(id,name,"__NON_LOGIN_TEST_HASH__");var u=new UserIdentity(id,name);users.add(u);return u;}
    /** @param user 身份 @param chat 聊天 @return 文本附件 */
    private Documents.View upload(UserIdentity user,UUID chat) {return service.upload(user,chat,UUID.randomUUID(),"计划.txt","旅行原件".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    /** @param user 身份 @param chat 聊天 @param id 文档 @return 本次实际领取 */
    private Jobs.Claim claim(UserIdentity user,UUID chat,UUID id) {var e=store.get(new Jobs.Scope(user.id(),chat),id);return jobs.claim(new Jobs.Scope(user.id(),chat),e.jobId()).orElseThrow();}
    /** 上传和完成分开，全文／状态同事务，不创建旅行；同ID同内容幂等，异内容冲突。 */
    @Test void uploadParseAndIdempotencyPersistTogether() {
        var u=actor();var chat=UUID.randomUUID();var id=UUID.randomUUID();byte[] bytes="原始资料".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var first=service.upload(u,chat,id,"plan.txt",bytes);assertThat(first.state()).isEqualTo("QUEUED");assertThat(service.upload(u,chat,id,"plan.txt",bytes).id()).isEqualTo(id);
        assertThatThrownBy(()->service.upload(u,chat,id,"plan.txt",new byte[]{1})).hasMessage("DOCUMENT_IDEMPOTENCY_CONFLICT");
        var c=claim(u,chat,id);var output=service.execute(c);service.commit(c,output,()->{throw new AssertionError();});
        assertThat(service.list(u,chat).getFirst().state()).isEqualTo("READY");assertThat(service.download(u,chat,id)).isEqualTo(bytes);
        assertThat(jdbc.queryForObject("SELECT report->>'code' FROM uploaded_document WHERE id=?",String.class,id)).isEqualTo("OK");assertThat(jdbc.queryForObject("SELECT count(*) FROM travel_task WHERE user_id=?",Integer.class,u.id())).isZero();
    }
    /** 越权和删除统一不可读；墓碑保留，物理清理后容量才释放。 */
    @Test void crossUserAndDeletionFenceLateResults() {
        var u=actor();var other=actor();var chat=UUID.randomUUID();var d=upload(u,chat);var c=claim(u,chat,d.id());var output=service.execute(c);
        assertThatThrownBy(()->service.download(other,chat,d.id())).hasMessage("DOCUMENT_NOT_FOUND");
        service.action(u,chat,d.id(),"DELETE");assertThatThrownBy(()->service.commit(c,output,()->{})).hasMessage("DOCUMENT_STALE");assertThatThrownBy(()->service.download(u,chat,d.id())).hasMessage("DOCUMENT_NOT_FOUND");
        var entry=store.get(new Jobs.Scope(u.id(),chat),d.id());assertThat(java.nio.file.Files.exists(ROOT.resolve(entry.key()))).isTrue();service.cleanup();assertThat(java.nio.file.Files.exists(ROOT.resolve(entry.key()))).isFalse();assertThat(jdbc.queryForObject("SELECT purged FROM uploaded_document WHERE id=?",Boolean.class,d.id())).isTrue();
    }
    /** 失联后接管使用新token，旧尝试不能提交正文；不重发原件或预算。 */
    @Test void lostLeaseRejectsOldReportAndRecoversSameOriginal() {
        var u=actor();var chat=UUID.randomUUID();var d=upload(u,chat);var old=claim(u,chat,d.id());var output=service.execute(old);
        jdbc.update("UPDATE background_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",old.job().id());var fresh=claim(u,chat,d.id());
        assertThat(fresh.fence().token()).isGreaterThan(old.fence().token());assertThatThrownBy(()->service.commit(old,output,()->{})).hasMessage("DOCUMENT_STALE");service.commit(fresh,service.execute(fresh),()->{});assertThat(service.list(u,chat).getFirst().state()).isEqualTo("READY");
    }
    /** 预留字符最后一个名额竞争，在真实PG用户锁下只有一个成功。 */
    @Test void concurrentLastReservationHasOneWinner() throws Exception {
        var u=actor();var chat=UUID.randomUUID();for(int i=0;i<4;i++)upload(u,chat);var start=new java.util.concurrent.CountDownLatch(1);var successes=new java.util.concurrent.atomic.AtomicInteger();
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){var futures=new ArrayList<java.util.concurrent.Future<?>>();for(int i=0;i<2;i++)futures.add(pool.submit(()->{try{start.await();upload(u,chat);successes.incrementAndGet();}catch(com.lawrence.travel.application.document.DocumentFailure limit){assertThat(limit.getMessage()).isEqualTo("DOCUMENT_CAPACITY");}catch(InterruptedException e){Thread.currentThread().interrupt();}}));start.countDown();for(var f:futures)f.get();}
        assertThat(successes.get()).isEqualTo(1);assertThat(service.list(u,chat)).hasSize(5);
    }
    /** 文件已落盘而索引未提交的崩溃窗口，按相同ID和哈希重新登记，不覆盖异内容。 */
    @Test void originalWrittenBeforeDatabaseCrashCanBeRegisteredOnRetry() {
        var u=actor();var chat=UUID.randomUUID();var id=UUID.randomUUID();byte[] bytes="崩溃前完整原件".getBytes(java.nio.charset.StandardCharsets.UTF_8);String key="documents/"+u.id()+"/"+chat+"/"+id+".bin";
        new com.lawrence.travel.infrastructure.artifact.VolumeArtifactFiles(ROOT).write(key,bytes);assertThat(service.upload(u,chat,id,"retry.txt",bytes).state()).isEqualTo("QUEUED");assertThat(service.download(u,chat,id)).isEqualTo(bytes);
    }
    /** 故障或安全拒绝保持PAUSED，没有部分成功正文；显式重试仍用原operation预算。 */
    @Test void unsafeParsedTextPausesWithoutSavingPayload() {
        var u=actor();var chat=UUID.randomUUID();var d=upload(u,chat);when(parser.parse(any())).thenReturn(new Documents.Report("pdfbox-3.0.8_poi-5.5.1_v1","OK",List.of(new Documents.Section(1,"<script>unsafe</script>")),"说明"));
        var c=claim(u,chat,d.id());service.commit(c,service.execute(c),()->{});assertThat(service.list(u,chat).getFirst().state()).isEqualTo("PAUSED");assertThat(store.get(c.fence().scope(),d.id()).report()).doesNotContain("<script>");
        service.action(u,chat,d.id(),"RETRY");assertThat(jobs.read(c.fence().scope(),c.job().id()).budgetId()).isEqualTo(c.job().budgetId());
    }
    /** 不支持名称、伪装魔数和大小在收件边界拒绝，未建立占位聊天。 */
    @Test void filenameFormatAndBytesAreBounded() {
        var u=actor();for(var name:List.of("../secret.txt","plan.zip","fake.pdf","fake.docx"))assertThatThrownBy(()->service.upload(u,UUID.randomUUID(),UUID.randomUUID(),name,"fake".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isInstanceOf(com.lawrence.travel.application.document.DocumentFailure.class);
        assertThatThrownBy(()->service.upload(u,UUID.randomUUID(),UUID.randomUUID(),"large.txt",new byte[10485761])).hasMessage("DOCUMENT_INPUT_LIMIT");assertThat(jdbc.queryForObject("SELECT count(*) FROM dialogue_chat WHERE user_id=?",Integer.class,u.id())).isZero();
    }

    /** 原件读取必须有本人会话；真实HTTP／Redis验证下载头及multipart CSRF，不打印Cookie或令牌。 */
    @Test void httpMultipartAndDownloadRequireOwnerAndCsrf() throws Exception {
        var u=actor();var other=actor();var chat=UUID.randomUUID();var id=UUID.randomUUID();var client=java.net.http.HttpClient.newHttpClient();String cookie=login(sessions,u);
        var base="http://127.0.0.1:"+port;var csrfResponse=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/v1/auth/csrf")).header("Cookie",cookie).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        var json=new com.lawrence.travel.infrastructure.travel.TravelJson();var csrf=json.decode(csrfResponse.body(),java.util.Map.class);String boundary="doc-test-"+UUID.randomUUID();
        String body="--"+boundary+"\r\nContent-Disposition: form-data; name=\"id\"\r\n\r\n"+id+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"plan.txt\"\r\nContent-Type: text/plain\r\n\r\nSynthetic travel original\r\n--"+boundary+"--\r\n";
        var upload=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/v1/chats/"+chat+"/documents")).header("Cookie",cookie).header("Content-Type","multipart/form-data; boundary="+boundary).POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
        assertThat(client.send(upload.build(),java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        upload.header(csrf.get("headerName").toString(),csrf.get("token").toString());assertThat(client.send(upload.build(),java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(202);
        var url=java.net.URI.create(base+"/api/v1/chats/"+chat+"/documents/"+id+"/download");
        assertThat(client.send(java.net.http.HttpRequest.newBuilder(url).GET().build(),java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
        assertThat(client.send(java.net.http.HttpRequest.newBuilder(url).header("Cookie",login(sessions,other)).GET().build(),java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
        var response=client.send(java.net.http.HttpRequest.newBuilder(url).header("Cookie",cookie).GET().build(),java.net.http.HttpResponse.BodyHandlers.discarding());assertThat(response.statusCode()).isEqualTo(200);assertThat(response.headers().firstValue("Content-Disposition").orElse("")).startsWith("attachment;");assertThat(response.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
    }
    /** @param repository Redis会话 @param actor 本例用户 @param <S> 会话类型 @return 仅本地请求使用，不进入断言或日志 */
    private <S extends org.springframework.session.Session> String login(org.springframework.session.SessionRepository<S> repository,UserIdentity actor) {
        S session=repository.createSession();var context=org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(actor,null,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));session.setAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);repository.save(session);sessionIds.add(session.getId());return "SESSION="+java.util.Base64.getEncoder().encodeToString(session.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    /** 全文写入后终态提交失败，事务必须一并回滚，不能留下可读半成品。 */
    @Test void failedJobCommitRollsBackExtractedText() {
        var u=actor();var chat=UUID.randomUUID();var d=upload(u,chat);var c=claim(u,chat,d.id());var output=service.execute(c);String constraint="doc_probe_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("ALTER TABLE background_job ADD CONSTRAINT "+constraint+" CHECK(id!='"+c.job().id()+"' OR state!='COMPLETED') NOT VALID");
        try {assertThatThrownBy(()->service.commit(c,output,()->{})).isInstanceOf(RuntimeException.class);assertThat(store.get(c.fence().scope(),d.id()).report()).isNull();assertThat(jobs.read(c.fence().scope(),c.job().id()).state()).isEqualTo(BackgroundJob.State.RUNNING);}
        finally {jdbc.execute("ALTER TABLE background_job DROP CONSTRAINT "+constraint);}
    }
    /** 完成后按实际字符释放预留，允许10个小文件；第11个拒绝，不能跨并发绕过。 */
    @Test void tenSmallFilesUseActualTextReservation() {
        var u=actor();var chat=UUID.randomUUID();for(int i=0;i<10;i++) {var d=upload(u,chat);var c=claim(u,chat,d.id());service.commit(c,service.execute(c),()->{});}assertThat(service.list(u,chat)).hasSize(10);assertThatThrownBy(()->upload(u,chat)).hasMessage("DOCUMENT_CAPACITY");
    }

    /** 原子改名前遗留的半文件不能当原件读取；同键重试安全清除并重新完整写入。 */
    @Test void incompletePendingFileIsNeverPromoted() throws Exception {
        var u=actor();var chat=UUID.randomUUID();var id=UUID.randomUUID();Path file=ROOT.resolve("documents/"+u.id()+"/"+chat+"/"+id+".bin.pending");java.nio.file.Files.createDirectories(file.getParent());java.nio.file.Files.writeString(file,"partial");byte[] full="完整旅行原件".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        service.upload(u,chat,id,"完整.txt",full);assertThat(service.download(u,chat,id)).isEqualTo(full);assertThat(java.nio.file.Files.exists(file)).isFalse();
    }
    /** 原件被改写后明确拒绝下载，不能通过仅有索引假装文件仍然可靠。 */
    @Test void corruptedOriginalIsNotServed() throws Exception {
        var u=actor();var chat=UUID.randomUUID();var d=upload(u,chat);var entry=store.get(new Jobs.Scope(u.id(),chat),d.id());java.nio.file.Files.writeString(ROOT.resolve(entry.key()),"modified");assertThatThrownBy(()->service.download(u,chat,d.id())).hasMessage("DOCUMENT_CORRUPT");
    }

    /** 旧备份恢复先重放备份之后的删除；最新清单缺失拒绝恢复，不保留脚本控制台。 */
    @Test void backupRestoresUploadedOriginalAndPgReport() throws Exception {
        var u=actor();var chat=UUID.randomUUID();var d=upload(u,chat);var c=claim(u,chat,d.id());service.commit(c,service.execute(c),()->{});
        Path script=Path.of("scripts/backup-local.ps1").toAbsolutePath();if(!java.nio.file.Files.exists(script))script=Path.of("../scripts/backup-local.ps1").toAbsolutePath().normalize();Path backupParent=java.nio.file.Files.createTempDirectory("travel-document-backup-");Path backup=backupParent.resolve("backup");
        try {
        for(String action:List.of("Backup","Verify")) {
                if (action.equals("Verify")) service.action(u, chat, d.id(), "DELETE");var command=new ArrayList<>(List.of("pwsh","-NoProfile","-File",script.toString(),"-Action",action,"-Directory",backup.toString(),
                                        "-BackupRoot",
                                        backupParent.toString()));if(action.equals("Backup"))command.addAll(List.of("-DocumentsDirectory",ROOT.toString(),"-Quiesced"));var process=new ProcessBuilder(command).redirectErrorStream(true).start();try {assertThat(process.waitFor(120,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    String result =
                            new String(
                                    process.getInputStream().readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8);
                    var codes =
                            java.util.regex.Pattern.compile(
                                            "(?:LATEST_DELETIONS|LOCAL_BACKUP|BACKUP|DOCUMENT_BACKUP|RESTORE|DOCUMENT_RESTORE|DELETED_RESTORE)_[A-Z_]+")
                                    .matcher(result)
                                    .results()
                                    .map(java.util.regex.MatchResult::group)
                                    .distinct()
                                    .toList();assertThat(process.exitValue())
                            .withFailMessage("BACKUP_FIXTURE_%s_%s", action, codes).isZero();}finally{if(process.isAlive())process.destroyForcibly();}}
            assertThatThrownBy(() ->service.download(u,chat, d.id())).hasMessage("DOCUMENT_NOT_FOUND");
            var missing =
                    new ProcessBuilder(
                                    "pwsh",
                                    "-NoProfile",
                                    "-File",
                                    script.toString(),
                                    "-Action",
                                    "Verify",
                                    "-Directory",
                                    backup.toString(),
                                    "-DeletionDirectory",
                                    backupParent.resolve("missing-latest").toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
            try {
                assertThat(missing.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(missing.exitValue()).isNotZero();
            } finally {
                if (missing.isAlive()) missing.destroyForcibly();
            }
        } finally {
            if(!backupParent.toRealPath().getFileName().toString().startsWith("travel-document-backup-"))throw new IllegalStateException("TEST_CLEAN_PATH_INVALID");
            try(var paths=java.nio.file.Files.walk(backupParent)) {for(var p:paths.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.delete(p);}
        }
    }

    /** 客户端收到响应不代表Servlet finally已结束；等待实际请求退出再删测试会话，避免人为制造Redis失效竞争。 */
    private void awaitHttpExit() {
        var server=(org.springframework.boot.tomcat.TomcatWebServer)webContext.getWebServer();
        var protocol=(org.apache.coyote.AbstractProtocol<?>)server.getTomcat().getConnector().getProtocolHandler();
        if(protocol.getExecutor() instanceof org.apache.tomcat.util.threads.ThreadPoolExecutor pool) {long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);while(pool.getActiveCount()>0 && System.nanoTime()<deadline)java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10));assertThat(pool.getActiveCount()).isZero();}
    }
    /** Windows目录联接或Linux符号链接逃出受管根时拒绝，原件不能写入外部目录。 */
    @Test void directoryLinkCannotEscapeOriginalRoot() throws Exception {
        var u=actor();var chat=UUID.randomUUID();Path outside=java.nio.file.Files.createTempDirectory("travel-document-outside-");Path parent=ROOT.resolve("documents");java.nio.file.Files.createDirectories(parent);Path link=parent.resolve(u.id().toString());
        try {
            if(System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows")) {var mklink=new ProcessBuilder("cmd.exe","/c","mklink","/J",link.toString(),outside.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();assertThat(mklink.waitFor()).isZero();}
            else java.nio.file.Files.createSymbolicLink(link,outside);
            assertThatThrownBy(()->upload(u,chat)).isInstanceOf(com.lawrence.travel.application.artifact.ArtifactFailure.class);
            try(var files=java.nio.file.Files.list(outside)){assertThat(files.count()).isZero();}
        }finally {java.nio.file.Files.deleteIfExists(link);java.nio.file.Files.delete(outside);}
    }
}
