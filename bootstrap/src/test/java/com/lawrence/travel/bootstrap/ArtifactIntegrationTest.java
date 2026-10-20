package com.lawrence.travel.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lawrence.travel.application.artifact.ArtifactService;
import com.lawrence.travel.application.interaction.NegotiationService;
import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.ArtifactStore;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.infrastructure.artifact.VolumeArtifactFiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 真实PostgreSQL与文件系统的索引、锁、完整性、原工作区丢弃以及备份恢复演练；无真实外部调用。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "travel.engine.enabled=false")
class ArtifactIntegrationTest {
    @org.springframework.boot.test.web.server.LocalServerPort private int port;
    @Autowired private org.springframework.session.SessionRepository<? extends org.springframework.session.Session> sessions;
    @Autowired private ArtifactStore store;
    @Autowired private BusinessTransactions transactions;
    @Autowired private NegotiationService negotiation;
    @Autowired private AccountStore accounts;
    @Autowired private ExecutionEngineStore engine;
    @Autowired private com.lawrence.travel.application.port.TravelStore travel;
    @Autowired private com.lawrence.travel.infrastructure.travel.TravelJson json;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @TempDir Path root;
    private final List<UUID> users = new ArrayList<>();
    private final List<String> sessionIds = new ArrayList<>();
    /** @param actor 身份 @param scope 私有任务 @param run 可恢复运行 */
    private record Fixture(UserIdentity actor, TaskScope scope, UUID run) { }
    /** @return 随机私有任务，保持未执行UNDERSTAND事件供恢复领取 */
    private Fixture fixture() {
        UUID user = UUID.randomUUID(); String name = "art_" + user.toString().replace("-", "").substring(0, 16); accounts.insert(user, name, "__NON_LOGIN_TEST_HASH__"); users.add(user); var actor = new UserIdentity(user, name);
        var task = negotiation.create(actor, UUID.randomUUID(), "存储恢复测试", "本地需求：保留原始消息", List.of());
        return new Fixture(actor, new TaskScope(user, task.taskId()), task.receipt().runId());
    }
    /** @return 重建应用服务，文件根与工作区分离 */
    private ArtifactService service() { return new ArtifactService(store, new VolumeArtifactFiles(root.resolve("files")), transactions, Clock.systemUTC()); }
    /** 按FK仅清理本轮随机用户，不影响现有业务数据。 */
    @AfterEach void clean() { for (String id : sessionIds) sessions.deleteById(id); transactions.required(() -> {
        for (UUID user : users) {
            var runs = jdbc.queryForList("SELECT id FROM execution_run WHERE user_id=?", UUID.class, user); jdbc.update("UPDATE travel_task SET current_run_id=NULL,current_version_id=NULL WHERE user_id=?", user);
            jdbc.update("DELETE FROM artifact_content WHERE artifact_id IN(SELECT id FROM artifact_record WHERE user_id=?)", user);
            for (String table : List.of("retained_artifact", "artifact_record", "negotiation_card", "conversation_message", "decision_response", "decision_request", "run_event", "execution_start_request", "step_attempt", "outbox_event", "execution_step", "execution_run")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
            for (UUID run : runs) { jdbc.update("DELETE FROM call_meter WHERE run_id=?", run); jdbc.update("DELETE FROM execution_budget WHERE run_id=?", run); }
            for (String table : List.of("itinerary_snapshot", "change_request", "travel_constraint_revision", "travel_task", "travel_idempotency")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user); jdbc.update("DELETE FROM app_user WHERE id=?", user);
        } return true;
    }); }
    /** 数据库文本/JSON精确保留字节哈希，工作区消失后重建服务仍可读取并领取原事件。 */
    @Test void textOnlyTaskResumesWithoutWorkspaceOrFiles() throws Exception {
        var f = fixture(); var service = service(); String text = "中文原文\n".repeat(10000); UUID id = service.text(f.scope(), f.run(), text, false); UUID json = service.text(f.scope(), f.run(), "{ \"a\": 1 }", true);
        Path disposable = Files.createDirectory(root.resolve("workspace")); Files.writeString(disposable.resolve("cache"), "可丢弃"); Files.delete(disposable.resolve("cache")); Files.delete(disposable); Files.delete(root.resolve("files"));
        assertThat(new String(service().read(f.actor(), id).bytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(text);
        assertThat(new String(service().read(f.actor(), json).bytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("{ \"a\": 1 }");
        var hint = engine.candidates(100).stream().filter(h -> h.runId().equals(f.run())).findFirst().orElseThrow(); assertThat(engine.claim(hint, "rebuilt-process")).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversation_message WHERE user_id=?", Integer.class, f.actor().id())).isPositive();
    }
    /** 双用户、关联运行和框架文件均隔离；缺失与越权同类错误。 */
    @Test void privateFilesAndFrameworkDataAreNotCrossReadable() {
        var a = fixture(); var b = fixture(); var s = service(); UUID id = s.file(a.scope(), a.run(), new byte[]{1, 2, 3}, "application/pdf", true);
        UUID internal = s.file(a.scope(), a.run(), new byte[]{4}, "application/octet-stream", false);
        assertThat(s.read(a.actor(), id).bytes()).containsExactly(1, 2, 3);
        assertThatThrownBy(() -> s.read(b.actor(), id)).hasMessage("NOT_FOUND"); assertThatThrownBy(() -> s.read(a.actor(), internal)).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> s.file(b.scope(), a.run(), new byte[]{1}, "application/pdf", true)).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> s.file(a.scope(), a.run(), new byte[]{1}, "text/html", true)).hasMessage("INVALID_INPUT");
    }
    /** 真实HTTP和Redis会话验证下载权限、安全响应头；Cookie仅在本地进程构造，不输出。 */
    @Test void httpDownloadRequiresOwnerSessionAndNeverExposesFramework() throws Exception {
        var a = fixture(); var b = fixture(); var s = service(); UUID id = s.text(a.scope(), a.run(), "可展示文本", false); UUID internal = s.file(a.scope(), a.run(), new byte[]{1}, "application/octet-stream", false);
        assertThat(download(null, id).statusCode()).isEqualTo(401);
        assertThat(download(b.actor(), id).statusCode()).isEqualTo(404); assertThat(download(a.actor(), internal).statusCode()).isEqualTo(404);
        var response = download(a.actor(), id); assertThat(response.statusCode()).isEqualTo(200); assertThat(response.body()).isEqualTo("可展示文本"); assertThat(response.headers().firstValue("Cache-Control")).contains("no-store"); assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment;"); assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    }
    /** @param actor 可空表示匿名，其他身份仅通过真实Redis会话绑定 @param id 私有产物 @return 不含请求凭据的HTTP结果，测试不打印响应认证头 */
    private java.net.http.HttpResponse<String> download(UserIdentity actor, UUID id) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/api/v1/artifacts/" + id)).GET();
        if (actor != null) {
            String idValue = login(sessions, actor); String cookie = java.util.Base64.getEncoder().encodeToString(idValue.getBytes(java.nio.charset.StandardCharsets.UTF_8)); request.header("Cookie", "SESSION=" + cookie);
        }
        try (var client = java.net.http.HttpClient.newHttpClient()) { return client.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString()); }
    }
    /** @param repository 真实Redis会话仓储 @param actor 测试身份 @param <S> 框架实际会话类型 @return 仅本地请求使用的会话ID；不放入断言或日志 */
    private <S extends org.springframework.session.Session> String login(org.springframework.session.SessionRepository<S> repository, UserIdentity actor) {
        S session = repository.createSession(); var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext(); context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")))); session.setAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context); repository.save(session); sessionIds.add(session.getId()); return session.getId();
    }
    /** 已引用附件丢失或同长度篡改必须显式拒绝，不用新文件冒充原始附件。 */
    @Test void missingAndCorruptFilesFailExplicitly() throws Exception {
        var f = fixture(); var s = service(); UUID id = s.file(f.scope(), f.run(), new byte[]{1, 2, 3}, "image/png", true); Path file = root.resolve("files").resolve(store.visible(f.actor().id(), id).fileKey());
        Files.write(file, new byte[]{9, 2, 3}); assertThatThrownBy(() -> s.read(f.actor(), id)).hasMessage("ARTIFACT_CORRUPT"); Files.delete(file); assertThatThrownBy(() -> s.read(f.actor(), id)).hasMessage("ARTIFACT_MISSING");
    }
    /** 数据库登记失败留下老孤儿可回收；保留期和有效引用保护使用真实事务。 */
    @Test void rolledBackRegistrationLeavesCollectableOrphanOnly() throws Exception {
        var f = fixture(); var s = service(); UUID retained = s.file(f.scope(), f.run(), new byte[]{1}, "application/pdf", true);
        assertThatThrownBy(() -> transactions.required(() -> { s.file(f.scope(), f.run(), new byte[]{2}, "application/pdf", true); throw new IllegalStateException("injected rollback"); })).hasMessage("injected rollback");
        try (var paths = Files.walk(root.resolve("files"))) { for (Path file : paths.filter(Files::isRegularFile).toList()) Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(200000))); }
        assertThat(s.collect(Instant.now().minusSeconds(86400))).isEqualTo(1); assertThat(s.read(f.actor(), retained).bytes()).containsExactly(1);
        assertThatThrownBy(() -> s.collect(Instant.now())).hasMessage("INVALID_INPUT");
    }
    /** 共享登记锁在提交之前阻塞排他回收；无有效引用窗口内不会被误回收。 */
    @Test void collectorWaitsForRegistrationTransaction() throws Exception {
        var f = fixture(); var s = service(); CountDownLatch written = new CountDownLatch(1), finish = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var writer = workers.submit(() -> transactions.required(() -> { UUID id = s.file(f.scope(), f.run(), new byte[]{5}, "application/pdf", true); written.countDown(); try { if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("TIMEOUT"); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); throw new IllegalStateException("INTERRUPTED"); } return id; }));
            assertThat(written.await(5, TimeUnit.SECONDS)).isTrue(); var collector = workers.submit(() -> s.collect(Instant.now().minusSeconds(86400))); Thread.sleep(100); assertThat(collector.isDone()).isFalse(); finish.countDown(); UUID id = writer.get(5, TimeUnit.SECONDS); assertThat(collector.get(5, TimeUnit.SECONDS)).isZero(); assertThat(s.read(f.actor(), id).bytes()).containsExactly(5);
        } finally { finish.countDown(); }
    }
    /** 真正pg_dump/pg_restore到临时数据库，文件提取到临时卷并逐引用核对；原库不覆盖，不保留输出归档。 */
    @Test void backupRestoresDatabaseAndRequiredFiles() throws Exception {
        var f = fixture(); var s = service(); s.text(f.scope(), f.run(), "备份正文", false); s.file(f.scope(), f.run(), new byte[]{1, 3, 5}, "application/pdf", true);
        // 备份不只包含新文件表：同时保存真正提交的检查点、待回答问题、原消息、需求及不可变草案。
        var hint = engine.candidates(100).stream().filter(h -> h.runId().equals(f.run())).findFirst().orElseThrow(); var claim = engine.claim(hint, "backup-fixture").orElseThrow(); var run = engine.run(f.scope(), f.run()); UUID question = UUID.randomUUID();
        var decision = new com.lawrence.travel.domain.interaction.DecisionRequest(UUID.randomUUID(), f.scope(), f.run(), run.generation(), 1, 1, java.util.Map.of(question, new com.lawrence.travel.domain.interaction.DecisionRequest.Question(question, "确认旅行时间？", java.util.Map.of(), null)), true, java.util.Set.of(), com.lawrence.travel.domain.interaction.DecisionRequest.State.OPEN);
        assertThat(engine.commit(engine.prepare(claim, new com.lawrence.travel.application.execution.StageOutput("等待回答", "{\"restoreCheckpoint\":true}", List.of(), com.lawrence.travel.application.execution.StageOutput.Outcome.WAIT_USER, decision)))).isEqualTo(com.lawrence.travel.application.port.StageCommitPort.Result.COMMITTED);
        var body = new java.util.TreeMap<String, Object>(); body.put("schemaVersion", 1); body.put("taskId", f.scope().taskId()); body.put("constraintRevision", 1); body.put("baseVersionId", null); for (String field : List.of("cityStays", "days", "items", "transportLegs", "accommodationStays", "costEntries", "alternatives", "evidenceRefs")) body.put(field, List.of()); String payload = json.encode(body);
        travel.appendSnapshot(new com.lawrence.travel.domain.itinerary.ItineraryVersion(UUID.randomUUID(), f.scope(), 1, json.hash(payload), 1, null, java.util.Set.of(), null), payload);
        Path backup = root.resolve("backup"); Path script = Path.of("scripts/backup-local.ps1").toAbsolutePath(); if (!Files.exists(script)) script = Path.of("../scripts/backup-local.ps1").toAbsolutePath().normalize();
        Files.writeString(root.resolve("files/.env"), "__LOCAL_TEST_PLACEHOLDER__");
        runBackup(script, "Backup", backup, true, true); runBackup(script, "Verify", backup, false, true);
        assertThat(new String(Files.readAllBytes(backup.resolve("files.tar")), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("__LOCAL_TEST_PLACEHOLDER__", ".pending");
        byte[] corrupted = Files.readAllBytes(backup.resolve("files.tar")); corrupted[0] ^= 1; Files.write(backup.resolve("files.tar"), corrupted); runBackup(script, "Verify", backup, false, false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM retained_artifact WHERE user_id=?", Integer.class, f.actor().id())).isEqualTo(2);
    }
    /** @param script 固定本地脚本 @param action 备份或验证 @param directory 本轮临时目录 @param backup 是否传入宿主机文件根 @param success 是否预期成功；子进程输出丢弃，不含凭据参数 */
    private void runBackup(Path script, String action, Path directory, boolean backup, boolean success) throws Exception {
        var command = new ArrayList<>(List.of("pwsh", "-NoProfile", "-File", script.toString(), "-Action", action, "-Directory", directory.toString(),
                                "-BackupRoot",
                                root.toString())); if (backup) command.addAll(List.of("-FilesDirectory", root.resolve("files").toString(), "-Quiesced"));
        var process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try { assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue() == 0).as("本地备份/恢复脚本退出状态符合预期").isEqualTo(success); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
