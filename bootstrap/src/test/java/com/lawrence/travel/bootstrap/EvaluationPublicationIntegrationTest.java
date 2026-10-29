package com.lawrence.travel.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lawrence.travel.application.evaluation.EvaluationPublication;
import com.lawrence.travel.application.evaluation.EvaluationPublicationService;
import com.lawrence.travel.application.evaluation.EvaluationRetention;
import com.lawrence.travel.application.evaluation.EvaluationRetention.CopyKind;
import com.lawrence.travel.application.evaluation.EvaluationRetentionService;
import com.lawrence.travel.infrastructure.evaluation.EvaluationPlatformAccessMapper;
import com.lawrence.travel.infrastructure.evaluation.EvaluationPublicationMapper;
import com.lawrence.travel.infrastructure.evaluation.EvaluationRetentionMapper;
import com.lawrence.travel.infrastructure.evaluation.EvaluationScoringMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 用真实本机PG验证发布登记、并发、删除和恢复屏障；不向Langfuse或模型发送请求。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EvaluationPublicationIntegrationTest {
    @Autowired private EvaluationPublicationMapper mapper;
    @Autowired private EvaluationRetentionMapper retentionMapper;
    @Autowired private EvaluationPlatformAccessMapper access;
    @Autowired private EvaluationScoringMapper scoring;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private final UUID owner = UUID.randomUUID();
    private final UUID run = UUID.randomUUID();
    private EvaluationPublicationService service;
    private EvaluationRetentionService retention;
    private EvaluationRetention.Registration registration;

    /** 每例登记独立随机run，所有指纹均为合成值。 */
    @BeforeEach
    void setup() {
        service = new EvaluationPublicationService(mapper);
        retention = new EvaluationRetentionService(retentionMapper);
        registration =
                new EvaluationRetention.Registration(
                        owner,
                        run,
                        Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MICROS),
                        "1".repeat(64),
                        "2".repeat(64));
        retention.register(registration);
    }

    /** 仅清除本例随机夹具；生产端口没有删除最小标记的方法。 */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM evaluation_publication WHERE run_id=?", run);
        jdbc.update("DELETE FROM evaluation_retention_copy WHERE run_id=?", run);
        jdbc.update("DELETE FROM evaluation_retention_run WHERE run_id=?", run);
        jdbc.update("DELETE FROM evaluation_deletion_marker WHERE run_id=?", run);
    }

    /** 首次领取后UNKNOWN已持久化，重建服务也不能换attempt或payload重新投递。 */
    @Test
    void claimIsDurableAndDoesNotReplayUnknown() {
        var permit = service.claim(request());
        service.beginSend(permit);
        service = new EvaluationPublicationService(mapper);
        assertThrows(IllegalStateException.class, () -> service.beginSend(permit));
        assertThrows(IllegalStateException.class, () -> service.claim(request()));
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.claim(
                                new EvaluationPublication.Request(
                                        owner,
                                        run,
                                        CopyKind.TRACE,
                                        "3".repeat(64),
                                        "9".repeat(64))));
        assertEquals(
                "UNKNOWN",
                jdbc.queryForObject(
                        "SELECT state FROM evaluation_publication WHERE run_id=?",
                        String.class,
                        run));
        assertEquals(1, count("evaluation_publication"));
        assertEquals(1, count("evaluation_retention_copy"));
        assertEquals(retention.read(owner, run).expiresAt(), permit.expiresAt());
    }

    /** ACK与查询确认分离，旧回执、重复回执和状态倒退均不能再发放发送资格。 */
    @Test
    void receiptsAdvanceWithoutReopeningSendPermission() {
        var permit = service.claim(request());
        service.beginSend(permit);
        assertEquals(
                EvaluationPublication.State.ACCEPTED,
                service.acknowledge(permit, EvaluationPublication.State.ACCEPTED, "5".repeat(64)));
        assertThrows(IllegalStateException.class, () -> service.beginSend(permit));
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.acknowledge(
                                permit, EvaluationPublication.State.ACCEPTED, "5".repeat(64)));
        assertEquals(
                EvaluationPublication.State.CONFIRMED,
                service.acknowledge(permit, EvaluationPublication.State.CONFIRMED, "6".repeat(64)));
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.acknowledge(
                                permit, EvaluationPublication.State.ACCEPTED, "5".repeat(64)));
        assertThrows(IllegalStateException.class, () -> service.claim(request()));
    }

    /** 同一位置其他账号、伪造attempt或延长期限不能读取/更新原始尝试。 */
    @Test
    void rejectsForeignAndAlteredPermits() {
        var permit = service.claim(request());
        service.beginSend(permit);
        var foreign =
                new EvaluationPublication.Request(
                        UUID.randomUUID(), run, CopyKind.TRACE, "3".repeat(64), "4".repeat(64));
        assertThrows(IllegalStateException.class, () -> service.claim(foreign));
        var fake =
                new EvaluationPublication.Permit(
                        permit.request(), UUID.randomUUID(), permit.expiresAt());
        assertThrows(IllegalStateException.class, () -> service.beginSend(fake));
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.acknowledge(
                                fake, EvaluationPublication.State.CONFIRMED, "6".repeat(64)));
        var changed =
                new EvaluationPublication.Permit(foreign, permit.attempt(), permit.expiresAt());
        assertThrows(IllegalStateException.class, () -> service.beginSend(changed));
        var extended =
                new EvaluationPublication.Permit(
                        permit.request(), permit.attempt(), permit.expiresAt().plusSeconds(1));
        assertThrows(IllegalStateException.class, () -> service.beginSend(extended));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.acknowledge(
                                permit, EvaluationPublication.State.UNKNOWN, "6".repeat(64)));
    }

    /** 主动删除同事务保存独立标记，迟到回执撤销旧代次证明，重复回执不再次增加代次。 */
    @Test
    void lateReceiptInvalidatesCleanupAfterSeal() {
        var permit = service.claim(request());
        service.beginSend(permit);
        retention.revoke(owner, run);
        assertEquals(1, count("evaluation_deletion_marker"));
        service.seal(run);
        var task = cleanupTask();
        assertTrue(retention.attest(task, "7".repeat(64)));
        assertEquals(
                EvaluationPublication.State.BLOCKED,
                service.acknowledge(permit, EvaluationPublication.State.ACCEPTED, "5".repeat(64)));
        var late = cleanupTask();
        assertEquals(task.generation() + 1, late.generation());
        assertEquals(task.deadline(), late.deadline());
        assertFalse(retention.attest(task, "7".repeat(64)));
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.acknowledge(
                                permit, EvaluationPublication.State.CONFIRMED, "6".repeat(64)));
        assertEquals(late.generation(), cleanupTask().generation());
        assertThrows(IllegalStateException.class, () -> service.beginSend(permit));
    }

    /** 到期无须等待周期任务；迟到ACK直接阻断并创建独立标记，清理期限不从ACK刷新。 */
    @Test
    void expiredReceiptCreatesMarkerAndPreservesOriginalDeadline() {
        expireSoon();
        var permit = service.claim(request());
        service.beginSend(permit);
        jdbc.execute("SELECT pg_sleep(1.2)");
        assertEquals(
                EvaluationPublication.State.BLOCKED,
                service.acknowledge(permit, EvaluationPublication.State.CONFIRMED, "6".repeat(64)));
        var task = cleanupTask();
        assertTrue(task.blockedAt().isBefore(Instant.now()));
        assertEquals(1, count("evaluation_deletion_marker"));
        service.seal(run);
        assertEquals(task.deadline(), cleanupTask().deadline());
        assertThrows(IllegalStateException.class, () -> service.beginSend(permit));
    }

    /** 明细清除后独立标记仍阻止重新登记；即使模拟恢复旧run，保留/评分/发布/访问仍拒绝。 */
    @Test
    void minimalMarkerBlocksRestoredActiveRun() {
        retention.revoke(owner, run);
        jdbc.update("DELETE FROM evaluation_retention_run WHERE run_id=?", run);
        assertEquals(1, count("evaluation_deletion_marker"));
        assertThrows(IllegalStateException.class, () -> retention.register(registration));
        jdbc.update(
                "INSERT INTO evaluation_retention_run(run_id,owner_id,started_at,expires_at,dataset_hash,configuration_hash) VALUES(?,?,?,?,?,?)",
                run,
                owner,
                java.sql.Timestamp.from(registration.startedAt()),
                java.sql.Timestamp.from(registration.startedAt().plusSeconds(720 * 3600)),
                registration.datasetHash(),
                registration.configurationHash());
        assertThrows(IllegalStateException.class, () -> retention.read(owner, run));
        assertThrows(
                IllegalStateException.class,
                () -> retention.track(owner, run, CopyKind.TRACE, "3".repeat(64)));
        assertThrows(IllegalStateException.class, () -> service.claim(request()));
        assertNull(scoring.lockRun(owner, run));
        String restoredSource =
                """
                {"context":{"startedAt":"%s","datasetHash":"%s","configurationHash":"%s"}}
                """
                        .formatted(
                                registration.startedAt(),
                                registration.datasetHash(),
                                registration.configurationHash());
        assertEquals(0, scoring.record(owner, run, restoredSource, "8".repeat(64)));
        jdbc.update(
                "INSERT INTO evaluation_scoring_source(run_id,source,fingerprint) VALUES(?,CAST(? AS JSONB),?)",
                run,
                restoredSource,
                "8".repeat(64));
        assertNull(scoring.read(owner, run));
        jdbc.update(
                "INSERT INTO evaluation_retention_copy(run_id,kind,locator_hash) VALUES(?,'TRACE',?)",
                run,
                "3".repeat(64));
        var first = cleanupTask();
        service.seal(run);
        assertEquals(first.deadline(), cleanupTask().deadline());
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            jdbc.update(
                                    "UPDATE evaluation_platform_access SET verified=true WHERE singleton=true");
                            assertFalse(access.decision().allows(Instant.now()));
                            status.setRollbackOnly();
                        });
    }

    /**
     * 两个并行领取只能一个成功，副本与UNKNOWN记录不会重复。
     *
     * @throws Exception 测试线程执行或等待失败
     */
    @Test
    void concurrentClaimsHaveSingleWinner() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var work =
                    (java.util.concurrent.Callable<Boolean>)
                            () -> {
                                ready.countDown();
                                assertTrue(start.await(5, TimeUnit.SECONDS));
                                try {
                                    service.claim(request());
                                    return true;
                                } catch (IllegalStateException denied) {
                                    return false;
                                }
                            };
            var first = workers.submit(work);
            var second = workers.submit(work);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(
                    1,
                    (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                            + (second.get(10, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(1, count("evaluation_publication"));
        }
    }

    /**
     * 等待run锁期间被撤销，锁释放后不能沿用语句旧快照领取。
     *
     * @throws Exception 测试线程执行或等待失败
     */
    @Test
    void queuedClaimRechecksRevocationAfterRunLock() throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            var submitted = new CountDownLatch(1);
            var pending =
                    new java.util.concurrent.atomic.AtomicReference<
                            java.util.concurrent.Future<Boolean>>();
            new TransactionTemplate(transactions)
                    .executeWithoutResult(
                            status -> {
                                jdbc.queryForObject(
                                        "SELECT run_id FROM evaluation_retention_run WHERE run_id=? FOR UPDATE",
                                        UUID.class,
                                        run);
                                pending.set(
                                        worker.submit(
                                                () -> {
                                                    submitted.countDown();
                                                    try {
                                                        service.claim(request());
                                                        return true;
                                                    } catch (IllegalStateException denied) {
                                                        return false;
                                                    }
                                                }));
                                try {
                                    assertTrue(submitted.await(5, TimeUnit.SECONDS));
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException("TEST_INTERRUPTED");
                                }
                                retention.revoke(owner, run);
                            });
            assertFalse(pending.get().get(10, TimeUnit.SECONDS));
            assertEquals(0, count("evaluation_publication"));
        }
    }

    /** 外层短事务回滚时，副本及尝试同时撤销，不留下半条登记。 */
    @Test
    void rollbackLeavesNoPartialClaim() {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            service.claim(request());
                            status.setRollbackOnly();
                        });
        assertEquals(0, count("evaluation_publication"));
        assertEquals(0, count("evaluation_retention_copy"));
        service.claim(request());
    }

    /** 活跃run不允许清理封存，非法类别和正文形状不能写入发布索引。 */
    @Test
    void rejectsActiveSealAndInvalidShapes() {
        assertThrows(IllegalStateException.class, () -> service.seal(run));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new EvaluationPublication.Request(
                                owner, run, CopyKind.LOCAL, "3".repeat(64), "4".repeat(64)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new EvaluationPublication.Request(
                                owner, run, CopyKind.TRACE, "raw-url", "4".repeat(64)));
        assertEquals(0, count("evaluation_publication"));
        assertEquals(0, count("evaluation_deletion_marker"));
    }

    /**
     * 并行消耗同一已登记许可只有一个成功；重建服务不能再次消耗。
     *
     * @throws Exception 测试线程执行或等待失败
     */
    @Test
    void sendPermitIsConsumedOnceAcrossConcurrentWorkers() throws Exception {
        var permit = service.claim(request());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> work =
                    () -> {
                        ready.countDown();
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        try {
                            new EvaluationPublicationService(mapper).beginSend(permit);
                            return true;
                        } catch (IllegalStateException denied) {
                            return false;
                        }
                    };
            var first = workers.submit(work);
            var second = workers.submit(work);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(
                    1,
                    (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                            + (second.get(10, TimeUnit.SECONDS) ? 1 : 0));
            assertThrows(
                    IllegalStateException.class,
                    () -> new EvaluationPublicationService(mapper).beginSend(permit));
        }
    }

    /** 未消耗许可不能保存平台回执；登记后进程中断也不能重新领取另一尝试。 */
    @Test
    void unconsumedPermitCannotReceiveReceiptOrBeReclaimed() {
        var permit = service.claim(request());
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.acknowledge(
                                permit, EvaluationPublication.State.ACCEPTED, "5".repeat(64)));
        assertThrows(
                IllegalStateException.class,
                () -> new EvaluationPublicationService(mapper).claim(request()));
        retention.revoke(owner, run);
        assertThrows(IllegalStateException.class, () -> service.beginSend(permit));
        service.seal(run);
        assertEquals(
                "BLOCKED",
                jdbc.queryForObject(
                        "SELECT state FROM evaluation_publication WHERE run_id=?",
                        String.class,
                        run));
    }

    /**
     * 构造本例固定位置与内容指纹。
     *
     * @return 原始发布请求
     */
    private EvaluationPublication.Request request() {
        return new EvaluationPublication.Request(
                owner, run, CopyKind.TRACE, "3".repeat(64), "4".repeat(64));
    }

    /**
     * 只查询测试中固定允许的表，不接收用户表名。
     *
     * @param table 本测试固定表名
     * @return 本例行数
     */
    private int count(String table) {
        if (!java.util.Set.of(
                        "evaluation_publication",
                        "evaluation_retention_copy",
                        "evaluation_deletion_marker")
                .contains(table)) throw new IllegalArgumentException("TEST_TABLE_INVALID");
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE run_id=?", Integer.class, run);
    }

    /**
     * 读取本例当前代次，不把其他测试运行的任务作为本例结果。
     *
     * @return 当前待清理副本
     */
    private EvaluationRetention.Cleanup cleanupTask() {
        return retention.pending(100).stream()
                .filter(task -> run.equals(task.run()))
                .findFirst()
                .orElseThrow();
    }

    /** 只将本例夹具预设为一秒后到期，领取后不再修改原始期限。 */
    private void expireSoon() {
        jdbc.update(
                "WITH n AS (SELECT clock_timestamp()+INTERVAL '1 second' AS expiry) UPDATE evaluation_retention_run SET started_at=n.expiry-INTERVAL '720 hours',expires_at=n.expiry FROM n WHERE run_id=?",
                run);
    }
}
