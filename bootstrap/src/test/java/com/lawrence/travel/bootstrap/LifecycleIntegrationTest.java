package com.lawrence.travel.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lawrence.travel.application.document.DocumentService;
import com.lawrence.travel.application.execution.Jobs;
import com.lawrence.travel.application.interaction.DialogueService;
import com.lawrence.travel.application.lifecycle.Lifecycle;
import com.lawrence.travel.application.lifecycle.LifecycleService;
import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.ArtifactFiles;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.DeletionLedger;
import com.lawrence.travel.application.port.DialogueStore;
import com.lawrence.travel.application.port.DocumentStore;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.JobStore;
import com.lawrence.travel.application.port.LifecycleStore;
import com.lawrence.travel.application.port.TravelStore;
import com.lawrence.travel.domain.identity.UserIdentity;

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
import java.util.UUID;

/** 真实PG/Redis/原件的删除闭环；只创建随机合成用户，不连接真实模型或解析服务。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "travel.engine.enabled=false",
            "travel.lifecycle.maintenance-enabled=false",
            "travel.documents.cleanup-enabled=false"
        })
class LifecycleIntegrationTest {
    private static final Path ROOT = root();
    private final List<UserIdentity> users = new ArrayList<>();
    private final List<String> sessionIds = new ArrayList<>();
    @Autowired LifecycleService lifecycle;
    @Autowired LifecycleStore lifecycleStore;
    @Autowired DeletionLedger ledger;
    @Autowired DocumentService documents;
    @Autowired DocumentStore store;
    @Autowired JobStore jobs;
    @Autowired DialogueService dialogue;
    @Autowired AccountStore accounts;
    @Autowired BusinessTransactions tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.lawrence.travel.application.travel.TravelStorageService travel;

    @Autowired
    org.springframework.session.SessionRepository<? extends org.springframework.session.Session>
            sessions;

    /**
     * @return 仅合成附件根
     */
    private static Path root() {
        try {
            return Files.createTempDirectory("lifecycle-fixture-");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("FIXTURE_ROOT");
        }
    }

    /**
     * @param r 独立原件根，不替换生产部署的最小删除清单
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("travel.documents.root", ROOT::toString);
    }

    /** 初始用例不发HTTP，无在途请求需等待。 */
    private void awaitHttpExit() {}

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
     * * *
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
     * @param user 合成身份
     * @return 私有聊天，不执行模型
     */
    private UUID chat(UserIdentity user) {
        var c = UUID.randomUUID();
        dialogue.send(user, c, UUID.randomUUID(), "合成生命周期测试");
        return c;
    }

    /** 确认范围变化拒绝删除；确认后聊天/旅行/附件/预算不可读，旧聊天身份不可复活。 */
    @Test
    void deleteWholeChatWithTravel() {
        var u = actor();
        var other = actor();
        var c = chat(u);
        var task =
                travel.create(
                                u,
                                UUID.randomUUID().toString(),
                                "合成旅行",
                                new TravelStore.RevisionContent(List.of(), List.of(), "合成", ""))
                        .task()
                        .scope()
                        .taskId();
        jdbc.update("UPDATE dialogue_chat SET task_id=? WHERE user_id=? AND id=?", task, u.id(), c);
        var d =
                documents.upload(
                        u,
                        c,
                        UUID.randomUUID(),
                        "合成.txt",
                        "合成原件".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var p = lifecycle.preview(u.id(), Lifecycle.Kind.CHAT, c);
        assertThat(p.task()).isEqualTo(task);
        assertThat(p.documents()).isEqualTo(1);
        assertThatThrownBy(() -> lifecycle.preview(other.id(), Lifecycle.Kind.CHAT, c))
                .hasMessage("DELETE_NOT_FOUND");
        assertThatThrownBy(
                        () ->
                                lifecycle.delete(
                                        u.id(),
                                        UUID.randomUUID(),
                                        Lifecycle.Kind.CHAT,
                                        c,
                                        p.revision(),
                                        false))
                .hasMessage("DELETE_INPUT_INVALID");
        dialogue.send(u, c, UUID.randomUUID(), "预览后的新消息");
        assertThatThrownBy(
                        () ->
                                lifecycle.delete(
                                        u.id(),
                                        UUID.randomUUID(),
                                        Lifecycle.Kind.CHAT,
                                        c,
                                        p.revision(),
                                        true))
                .hasMessage("DELETE_SCOPE_CHANGED");
        var fresh = lifecycle.preview(u.id(), Lifecycle.Kind.CHAT, c);
        var id = UUID.randomUUID();
        assertThat(
                        lifecycle
                                .delete(u.id(), id, Lifecycle.Kind.CHAT, c, fresh.revision(), true)
                                .readable())
                .isFalse();
        assertThat(
                        lifecycle
                                .delete(u.id(), id, Lifecycle.Kind.CHAT, c, fresh.revision(), true)
                                .saved())
                .isTrue();
        assertThatThrownBy(() -> dialogue.read(u, c)).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> travel.task(u, task)).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> dialogue.send(u, c, UUID.randomUUID(), "旧ID不能复活"))
                .isInstanceOf(RuntimeException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM dialogue_chat WHERE id=?", Integer.class, c))
                .isZero();
        maintainOwnFiles();
        assertThat(
                        Files.exists(
                                ROOT.resolve(
                                        "documents/" + u.id() + "/" + c + "/" + d.id() + ".bin")))
                .isFalse();
        assertThat(lifecycle.ready()).isTrue();
    }

    /** 源附件撤销使旧parse失去围栏，原件实际删除后才释放用户容量。 */
    @Test
    void sourceDeleteRevokesAttempt() {
        var u = actor();
        var c = chat(u);
        var d =
                documents.upload(
                        u,
                        c,
                        UUID.randomUUID(),
                        "合成.txt",
                        "合成资料".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var e = store.get(new Jobs.Scope(u.id(), c), d.id());
        var claim = jobs.claim(new Jobs.Scope(u.id(), c), e.jobId()).orElseThrow();
        documents.action(u, c, d.id(), "DELETE");
        assertThat(jobs.live(claim.fence())).isFalse();
        assertThatThrownBy(() -> documents.download(u, c, d.id())).hasMessage("DOCUMENT_NOT_FOUND");
        assertThat(dialogue.read(u, c).chat().id()).isEqualTo(c);
        dialogue.send(u, c, UUID.randomUUID(), "删除来源后仍可继续聊天");
        maintainOwnFiles();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT purged FROM uploaded_document WHERE id=?",
                                Boolean.class,
                                d.id()))
                .isTrue();
    }

    /** 诊断30天清理归还逻辑容量，不依赖VACUUM FULL。 */
    @Test
    void retentionReleasesCapacity() {
        var u = actor();
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO problem_event(id,user_id,component,code,severity,created_at)"
                        + " VALUES(?,?,'LIFECYCLE_TEST','OLD','ERROR',clock_timestamp()-interval '31"
                        + " day')",
                id,
                u.id());
        // 在同一真实PG事务内验证清理与容量归还，最后回滚，避免测试清理其他用户的过期诊断。
        assertThatThrownBy(
                        () ->
                                tx.required(
                                        () -> {
                                            long before =
                                                    jdbc.queryForObject(
                                                            "SELECT used_bytes FROM"
                                                                    + " diagnostic_capacity WHERE id=1",
                                                            Long.class);
                                            lifecycleStore.prune(java.time.Instant.now());
                                            assertThat(
                                                            jdbc.queryForObject(
                                                                    "SELECT count(*) FROM"
                                                                            + " problem_event WHERE"
                                                                            + " id=?",
                                                                    Integer.class,
                                                                    id))
                                                    .isZero();
                                            assertThat(
                                                            jdbc.queryForObject(
                                                                    "SELECT used_bytes FROM"
                                                                            + " diagnostic_capacity"
                                                                            + " WHERE id=1",
                                                                    Long.class))
                                                    .isLessThan(before);
                                            throw new IllegalStateException("FIXTURE_ROLLBACK");
                                        }))
                .hasMessage("FIXTURE_ROLLBACK");
    }

    /** 十个待处理请求封顶；重复键不加占位，撤回后允许补入，不阻断删除。 */
    @Test
    void pendingCapIsIdempotentAndDeletable() {
        var u = actor();
        var c = UUID.randomUUID();
        var first = UUID.randomUUID();
        dialogue.send(u, c, first, "首个合成请求");
        for (int n = 1; n < 10; n++) dialogue.send(u, c, UUID.randomUUID(), "合成排队请求");
        assertThatThrownBy(() -> dialogue.send(u, c, UUID.randomUUID(), "第十一条"))
                .hasMessage("USER_PENDING_LIMIT");
        dialogue.send(u, c, first, "首个合成请求");
        assertThat(dialogue.read(u, c).messages()).hasSize(10);
        var p = lifecycle.preview(u.id(), Lifecycle.Kind.CHAT, c);
        assertThat(
                        lifecycle
                                .delete(
                                        u.id(),
                                        UUID.randomUUID(),
                                        Lifecycle.Kind.CHAT,
                                        c,
                                        p.revision(),
                                        true)
                                .saved())
                .isTrue();
    }

    /** 用户轮转不被同一用户的多聊天掩盖；同聊天按输入顺序领取，取消前保留单用户槽。 */
    @Test
    void dispatchIsPerUserAndFifo() {
        var a = actor();
        var b = actor();
        var ca = chat(a);
        var cb = chat(b);
        UUID next = UUID.randomUUID();
        dialogue.send(a, ca, next, "合成第二条");
        // 人为反转作业墙上时钟，聊天FIFO必须使用已持久化的输入sequence。
        jdbc.update(
                "UPDATE background_job SET created_at=created_at-interval '1 day' WHERE id=(SELECT"
                        + " job_id FROM dialogue_input WHERE id=?)",
                next);
        var first = tx.required(() -> ((DialogueStore) chats()).claim()).orElseThrow();
        assertThat(first.userId()).isEqualTo(a.id());
        var second = tx.required(() -> ((DialogueStore) chats()).claim()).orElseThrow();
        assertThat(second.userId()).isEqualTo(b.id());
        assertThat(tx.required(() -> ((DialogueStore) chats()).claim())).isEmpty();
        tx.required(
                () -> {
                    chats().finish(first, "DONE", "合成完成", null, "");
                    return null;
                });
        var third = tx.required(() -> chats().claim()).orElseThrow();
        assertThat(third.messageId()).isEqualTo(next);
    }

    /**
     * @return 既有聊天持久端口，不引入新的调度器
     */
    private DialogueStore chats() {
        return chatStore;
    }

    @Autowired DialogueStore chatStore;

    /** 删除来源保留已确认版本/需求和共享偏好；删除整趟旅行才删除版本，偏好仍保留。 */
    @Test
    void sourceAndTripHaveDifferentScopes() {
        var u = actor();
        var c = chat(u);
        var task =
                travel.create(
                                u,
                                UUID.randomUUID().toString(),
                                "合成保留版本",
                                new TravelStore.RevisionContent(List.of(), List.of(), "合成", ""))
                        .task()
                        .scope()
                        .taskId();
        jdbc.update("UPDATE dialogue_chat SET task_id=? WHERE user_id=? AND id=?", task, u.id(), c);
        var version = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                        + " itinerary_snapshot(id,user_id,task_id,constraint_revision,schema_version,payload_hash,metadata,payload)"
                        + " VALUES(?,?,?,1,1,repeat('0',64),'{}','{}')",
                version,
                u.id(),
                task);
        jdbc.update("UPDATE travel_task SET current_version_id=? WHERE id=?", version, task);
        jdbc.update(
                "INSERT INTO user_preference(user_id,id,category,state,body)"
                        + " VALUES(?,?,'pace','CONFIRMED','{}')",
                u.id(),
                UUID.randomUUID());
        var d =
                documents.upload(
                        u,
                        c,
                        UUID.randomUUID(),
                        "合成.txt",
                        "合成正文".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        documents.action(u, c, d.id(), "DELETE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT current_version_id FROM travel_task WHERE id=?",
                                UUID.class,
                                task))
                .isEqualTo(version);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM travel_constraint_revision WHERE task_id=?",
                                Integer.class,
                                task))
                .isEqualTo(1);
        var p = lifecycle.preview(u.id(), Lifecycle.Kind.TASK, task);
        assertThat(p.versions()).isEqualTo(1);
        lifecycle.delete(u.id(), UUID.randomUUID(), Lifecycle.Kind.TASK, task, p.revision(), true);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM itinerary_snapshot WHERE task_id=?",
                                Integer.class,
                                task))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM user_preference WHERE user_id=?",
                                Integer.class,
                                u.id()))
                .isEqualTo(1);
    }

    /**
     * * 硬退出后PG尚保留原行，但屏障立即关闭；重放完成后才开放，旧正文不能复活。
     * @throws Exception 合成子进程或有界等待失败
     */
    @Test
    void crashAfterJournalBeforeCommit() throws Exception {
        var u = actor();
        var c = chat(u);
        Path argsFile = Files.createTempFile(Path.of("target"), "deletion-crash-", ".args");
        Process child = null;
        try {
            String cp =
                    System.getProperty("java.class.path")
                            .replace("\\", "\\\\")
                            .replace("\"", "\\\"");
            Files.writeString(argsFile, "-cp\n\"" + cp + "\"\n");
            String exe =
                    System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                            ? "java.exe"
                            : "java";
            child =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", exe).toString(),
                                    "@" + argsFile.toAbsolutePath(),
                                    DeletionCrashProbe.class.getName(),
                                    u.id().toString(),
                                    c.toString())
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
            assertThat(child.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isEqualTo(37);
            assertThat(lifecycle.ready()).isFalse();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM dialogue_chat WHERE id=?",
                                    Integer.class,
                                    c))
                    .isEqualTo(1);
            assertThatThrownBy(() -> lifecycle.preview(u.id(), Lifecycle.Kind.CHAT, c))
                    .hasMessage("RESTORE_REQUIRED");
            lifecycle.recover();
            assertThat(lifecycle.ready()).isTrue();
            assertThatThrownBy(() -> dialogue.read(u, c)).hasMessage("NOT_FOUND");
            lifecycle.recover();
            assertThat(lifecycle.ready()).isTrue();
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
            Files.deleteIfExists(argsFile);
            if (!lifecycle.ready()) lifecycle.recover();
        }
    }

    @Autowired ArtifactFiles artifacts;
    @Autowired ExecutionEngineStore engine;

    /**
     * @return 只清理本用例身份的文件端口，不能用临时原件根将真实用户文件误记为已清除
     */
    private LifecycleStore ownStore() {
        var limited =
                org.mockito.Mockito.mock(
                        LifecycleStore.class,
                        org.mockito.AdditionalAnswers.delegatesTo(lifecycleStore));
        org.mockito.Mockito.when(
                        limited.files(org.mockito.ArgumentMatchers.any(java.time.Instant.class)))
                .thenAnswer(
                        i -> {
                            var found = new ArrayList<Lifecycle.File>();
                            for (var u : users)
                                found.addAll(
                                        jdbc.query(
                                                "SELECT id,user_id,file_key,document,attempts FROM"
                                                        + " deleted_file WHERE user_id=? AND purged_at"
                                                        + " IS NULL AND next_at<=? ORDER BY"
                                                        + " deleted_at,id LIMIT 30",
                                                (r, n) ->
                                                        new Lifecycle.File(
                                                                r.getObject("id", UUID.class),
                                                                r.getObject("user_id", UUID.class),
                                                                r.getString("file_key"),
                                                                r.getBoolean("document"),
                                                                r.getInt("attempts")),
                                                u.id(),
                                                java.sql.Timestamp.from(
                                                        (java.time.Instant) i.getArgument(0))));
                            return found;
                        });
        org.mockito.Mockito.doReturn(0)
                .when(limited)
                .prune(org.mockito.ArgumentMatchers.any(java.time.Instant.class));
        return limited;
    }

    /** 清理仅作用于当前随机身份，不碰真实用户的文件或诊断。 */
    private void maintainOwnFiles() {
        var service =
                new LifecycleService(
                        ownStore(),
                        tx,
                        ledger,
                        new com.lawrence.travel.infrastructure.artifact.VolumeArtifactFiles(ROOT),
                        artifacts,
                        jobs,
                        engine,
                        java.time.Clock.systemUTC());
        service.recover();
        service.maintain();
    }

    /** 文件删除失败保留索引/容量，固定问题可观察；退避后实际清除才标记完成。 */
    @Test
    void cleanupFailureRetriesWithoutFalseSuccess() {
        var u = actor();
        var c = chat(u);
        var d =
                documents.upload(
                        u,
                        c,
                        UUID.randomUUID(),
                        "合成.txt",
                        "待清理合成资料".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        documents.action(u, c, d.id(), "DELETE");
        var now = java.time.Instant.now();
        jdbc.update(
                "UPDATE deleted_file SET deleted_at=? WHERE user_id=?",
                java.sql.Timestamp.from(now.minusSeconds(25 * 3600)),
                u.id());
        var failing = org.mockito.Mockito.mock(ArtifactFiles.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("FIXTURE_IO_FAILURE"))
                .when(failing)
                .delete(org.mockito.ArgumentMatchers.anyString());
        var service =
                new LifecycleService(
                        ownStore(),
                        tx,
                        ledger,
                        failing,
                        artifacts,
                        jobs,
                        engine,
                        java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
        service.recover();
        service.maintain();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempts FROM deleted_file WHERE user_id=?",
                                Integer.class,
                                u.id()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT purged_at IS NULL FROM deleted_file WHERE user_id=?",
                                Boolean.class,
                                u.id()))
                .isTrue();
        assertThat(service.health().overdue()).isPositive();
        var recovered =
                new LifecycleService(
                        ownStore(),
                        tx,
                        ledger,
                        new com.lawrence.travel.infrastructure.artifact.VolumeArtifactFiles(ROOT),
                        artifacts,
                        jobs,
                        engine,
                        java.time.Clock.fixed(now.plusSeconds(3), java.time.ZoneOffset.UTC));
        recovered.recover();
        recovered.maintain();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT purged_at IS NOT NULL FROM deleted_file WHERE user_id=?",
                                Boolean.class,
                                u.id()))
                .isTrue();
    }

    /** 已暂停输入重试仍受十请求上限；明确停止先撤回旧输入，不会被满队列阻断。 */
    @Test
    void retryCannotBypassPendingCapButStopCan() {
        var u = actor();
        var c = UUID.randomUUID();
        var paused = UUID.randomUUID();
        dialogue.send(u, c, paused, "先暂停的合成输入");
        jdbc.update(
                "UPDATE dialogue_input SET state='PAUSED' WHERE user_id=? AND id=?",
                u.id(),
                paused);
        for (int n = 0; n < 10; n++) dialogue.send(u, c, UUID.randomUUID(), "待处理合成输入");
        assertThatThrownBy(
                        () ->
                                tx.required(
                                        () -> {
                                            chatStore.changeState(u.id(), c, paused, "QUEUED");
                                            return null;
                                        }))
                .hasMessage("USER_PENDING_LIMIT");
        dialogue.send(u, c, UUID.randomUUID(), "停止规划");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM dialogue_input WHERE user_id=? AND state"
                                        + " IN('QUEUED','RUNNING')",
                                Integer.class,
                                u.id()))
                .isZero();
    }

    /** 普通删除回执不得借全局pending泄露其他用户的清理队列。 */
    @Test
    void deletionReceiptIsScoped() {
        var other = actor();
        var co = chat(other);
        var d =
                documents.upload(
                        other,
                        co,
                        UUID.randomUUID(),
                        "合成.txt",
                        "合成待清理".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        documents.action(other, co, d.id(), "DELETE");
        var u = actor();
        var c = chat(u);
        var p = lifecycle.preview(u.id(), Lifecycle.Kind.CHAT, c);
        assertThat(
                        lifecycle
                                .delete(
                                        u.id(),
                                        UUID.randomUUID(),
                                        Lifecycle.Kind.CHAT,
                                        c,
                                        p.revision(),
                                        true)
                                .cleanupPending())
                .isFalse();
    }

    /** 跨用户客户端UUID碰撞返回冲突，不误报已删除，不污染独立清单水位；换新键可继续。 */
    @Test
    void crossUserRequestCollisionDoesNotPretendSuccess() {
        var a = actor();
        var b = actor();
        var ca = chat(a);
        var cb = chat(b);
        var pa = lifecycle.preview(a.id(), Lifecycle.Kind.CHAT, ca);
        var pb = lifecycle.preview(b.id(), Lifecycle.Kind.CHAT, cb);
        var id = UUID.randomUUID();
        lifecycle.delete(a.id(), id, Lifecycle.Kind.CHAT, ca, pa.revision(), true);
        var head = ledger.head();
        assertThatThrownBy(
                        () ->
                                lifecycle.delete(
                                        b.id(), id, Lifecycle.Kind.CHAT, cb, pb.revision(), true))
                .hasMessage("DELETE_CONFLICT");
        assertThat(ledger.head()).isEqualTo(head);
        assertThat(lifecycle.ready()).isTrue();
        assertThat(dialogue.read(b, cb).chat().id()).isEqualTo(cb);
        assertThat(
                        lifecycle
                                .delete(
                                        b.id(),
                                        UUID.randomUUID(),
                                        Lifecycle.Kind.CHAT,
                                        cb,
                                        pb.revision(),
                                        true)
                                .saved())
                .isTrue();
    }
}
