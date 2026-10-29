package com.lawrence.travel.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lawrence.travel.application.evaluation.EvaluationRetention;
import com.lawrence.travel.application.evaluation.EvaluationRetention.CopyKind;
import com.lawrence.travel.application.evaluation.EvaluationRetentionService;
import com.lawrence.travel.infrastructure.evaluation.EvaluationRetentionMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
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

/** 真实本机PG验证期限、撤销、代次和锁顺序；不调用平台或供应商，不证明物理副本已清理。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EvaluationRetentionIntegrationTest {
    @Autowired private EvaluationRetentionMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private final UUID owner = UUID.randomUUID();
    private final UUID run = UUID.randomUUID();
    private final String locator = "3".repeat(64);
    private EvaluationRetentionService service;

    /** 每例只使用随机运行，不创建真实用户、外部副本或自动清理线程。 */
    @BeforeEach
    void setup() {
        service = new EvaluationRetentionService(mapper);
    }

    /** 仅清理本例合成台账；生产代码不提供删除墓碑的入口。 */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM evaluation_retention_copy WHERE run_id=?", run);
        jdbc.update("DELETE FROM evaluation_retention_run WHERE run_id=?", run);
    }

    /** 同身份重试不续期；不同时间、配置、数据和归属均不能接管同ID。 */
    @Test
    void registrationIsImmutableAndOwnerScoped() {
        var registration = registration(Instant.now().minusSeconds(1));
        service.register(registration);
        service.register(registration);
        var before = service.read(owner, run);
        assertEquals(Duration.ofDays(30), Duration.between(before.startedAt(), before.expiresAt()));
        assertThrows(IllegalStateException.class, () -> service.read(UUID.randomUUID(), run));
        assertThrows(IllegalStateException.class, () -> service.revoke(UUID.randomUUID(), run));
        assertThrows(
                IllegalStateException.class, () -> service.register(registration(Instant.now())));
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.register(
                                new EvaluationRetention.Registration(
                                        owner,
                                        run,
                                        registration.startedAt(),
                                        "9".repeat(64),
                                        registration.configurationHash())));
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.register(
                                new EvaluationRetention.Registration(
                                        UUID.randomUUID(),
                                        run,
                                        registration.startedAt(),
                                        registration.datasetHash(),
                                        registration.configurationHash())));
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.register(
                                new EvaluationRetention.Registration(
                                        owner,
                                        run,
                                        registration.startedAt(),
                                        registration.datasetHash(),
                                        "8".repeat(64))));
        assertEquals(before, service.read(owner, run));
    }

    /** 未来和已过期运行不登记；输入形状和扫描上限不能绕过应用协议。 */
    @Test
    void rejectsFutureExpiredAndInvalidRegistrations() {
        assertThrows(
                IllegalStateException.class,
                () -> service.register(registration(Instant.now().plusSeconds(600))));
        assertThrows(
                IllegalStateException.class,
                () -> service.register(registration(Instant.now().minus(Duration.ofDays(31)))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new EvaluationRetention.Registration(
                                owner, run, Instant.now(), "body", "1".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> service.pending(101));
        assertThrows(IllegalArgumentException.class, () -> service.pending(0));
        assertThrows(IllegalStateException.class, () -> service.read(owner, run));
    }

    /** 无需清理器先运行，数据库时钟越过到期点就立即拒绝读写；24小时只用于清理。 */
    @Test
    void expiryBlocksReadAndTrackingBeforeCleanup() {
        service.register(registration(Instant.now().minusSeconds(1)));
        service.track(owner, run, CopyKind.TRACE, locator);
        expire(25);
        assertThrows(IllegalStateException.class, () -> service.read(owner, run));
        assertThrows(
                IllegalStateException.class,
                () -> service.track(owner, run, CopyKind.OBJECT, "4".repeat(64)));
        var task = task();
        assertEquals(Duration.ofHours(24), Duration.between(task.blockedAt(), task.deadline()));
        assertTrue(task.overdue());
        assertThrows(
                IllegalStateException.class,
                () -> service.register(registration(Instant.now().minusSeconds(1))));
    }

    /** 主动撤销先持久化；重建服务和重复撤销不会恢复权限或延后首次清理期限。 */
    @Test
    void revokeSurvivesRestartAndCannotBeResurrected() {
        var registration = registration(Instant.now().minusSeconds(1));
        service.register(registration);
        for (var kind : CopyKind.values()) service.track(owner, run, kind, locator);
        service.revoke(owner, run);
        var first = task();
        service = new EvaluationRetentionService(mapper);
        service.revoke(owner, run);
        assertEquals(first.deadline(), task().deadline());
        assertEquals(
                CopyKind.values().length,
                service.pending(100).stream().filter(t -> run.equals(t.run())).count());
        assertThrows(IllegalStateException.class, () -> service.read(owner, run));
        assertThrows(IllegalStateException.class, () -> service.register(registration));
        assertThrows(
                IllegalStateException.class,
                () -> service.track(owner, run, CopyKind.LOCAL, "4".repeat(64)));
    }

    /** 回执只能提交当前代次；迟到副本撤销旧回执，不把回执哈希当作真实删除证明。 */
    @Test
    void lateArrivalInvalidatesOldAttestationWithoutRestoringAccess() {
        service.register(registration(Instant.now().minusSeconds(1)));
        service.track(owner, run, CopyKind.TRACE, locator);
        service.track(owner, run, CopyKind.TRACE, locator);
        assertFalse(service.arrivedLate(run, CopyKind.TRACE, locator));
        service.revoke(owner, run);
        var first = task();
        assertTrue(service.attest(first, "5".repeat(64)));
        assertFalse(service.attest(first, "5".repeat(64)));
        assertTrue(service.pending(100).stream().noneMatch(t -> run.equals(t.run())));
        assertTrue(service.arrivedLate(run, CopyKind.TRACE, locator));
        assertFalse(service.attest(first, "5".repeat(64)));
        var next = task();
        assertEquals(first.generation() + 1, next.generation());
        assertEquals(first.deadline(), next.deadline());
        assertTrue(service.attest(next, "6".repeat(64)));
        assertThrows(IllegalStateException.class, () -> service.read(owner, run));
    }

    /** 台账事务回滚不能留下已登记的运行和副本。 */
    @Test
    void rolledBackRegistrationsAreNotReadable() {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            service.register(registration(Instant.now().minusSeconds(1)));
                            service.track(owner, run, CopyKind.TRACE, locator);
                            status.setRollbackOnly();
                        });
        assertThrows(IllegalStateException.class, () -> service.read(owner, run));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM evaluation_retention_copy WHERE run_id=?",
                        Integer.class,
                        run));
    }

    /** 同一事务内也必须按当前时间拒绝到期读取，不能复用MyBatis早先的活跃查询缓存。 */
    @Test
    void repeatedReadInSameTransactionDoesNotReuseExpiredView() {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            Instant started =
                                    Instant.now().minus(Duration.ofDays(30)).plusSeconds(2);
                            service.register(registration(started));
                            service.read(owner, run);
                            jdbc.execute("SELECT pg_sleep(2.1)");
                            assertThrows(
                                    IllegalStateException.class, () -> service.read(owner, run));
                            assertThrows(
                                    IllegalStateException.class,
                                    () -> service.track(owner, run, CopyKind.TRACE, locator));
                            status.setRollbackOnly();
                        });
    }

    /**
     * 副本登记在run锁上等待到期，取得锁后仍须复核时钟，不能沿用等待前的有效性。
     *
     * @throws Exception 并发测试超时或线程异常
     */
    @Test
    void copyRegistrationRechecksExpiryAfterWaitingForLock() throws Exception {
        service.register(registration(Instant.now().minus(Duration.ofDays(30)).plusSeconds(2)));
        try (var pool = Executors.newSingleThreadExecutor()) {
            var future =
                    new java.util.concurrent.atomic.AtomicReference<
                            java.util.concurrent.Future<Boolean>>();
            new TransactionTemplate(transactions)
                    .executeWithoutResult(
                            status -> {
                                jdbc.queryForObject(
                                        "SELECT run_id FROM evaluation_retention_run WHERE run_id=? FOR UPDATE",
                                        UUID.class,
                                        run);
                                future.set(
                                        pool.submit(
                                                () ->
                                                        mapper.track(
                                                                owner,
                                                                run,
                                                                CopyKind.TRACE,
                                                                locator)));
                                waitForCopyLock();
                                jdbc.execute("SELECT pg_sleep(2.1)");
                            });
            assertFalse(future.get().get(5, TimeUnit.SECONDS));
        }
    }

    /**
     * 登记副本等待撤销事务的run锁；锁释放后不能在撤销运行下新增副本。
     *
     * @throws Exception 并发测试超时或线程异常
     */
    @Test
    void revocationWinsAgainstBlockedCopyRegistration() throws Exception {
        service.register(registration(Instant.now().minusSeconds(1)));
        try (var pool = Executors.newSingleThreadExecutor()) {
            var future =
                    new java.util.concurrent.atomic.AtomicReference<
                            java.util.concurrent.Future<Boolean>>();
            new TransactionTemplate(transactions)
                    .executeWithoutResult(
                            status -> {
                                service.revoke(owner, run);
                                future.set(
                                        pool.submit(
                                                () ->
                                                        mapper.track(
                                                                owner,
                                                                run,
                                                                CopyKind.TRACE,
                                                                locator)));
                                waitForCopyLock();
                            });
            assertFalse(future.get().get(5, TimeUnit.SECONDS));
        }
    }

    /** 等待PG确认另一连接正在等副本登记锁，最多5秒，不靠固定延时假定竞争已发生。 */
    private void waitForCopyLock() {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) {
            // 当前测试持有事务；刷新统计快照才能看见后来开始等待的连接。
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            boolean waiting =
                    Boolean.TRUE.equals(
                            jdbc.queryForObject(
                                    "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%evaluation_retention_copy%' AND pid<>pg_backend_pid())",
                                    Boolean.class));
            if (waiting) return;
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("COPY_REGISTRATION_DID_NOT_WAIT_FOR_RUN_LOCK");
    }

    /**
     * 生成微秒精度的可信合成开始时间，避免PG精度截断影响幂等比较。
     *
     * @param startedAt 合成开始时间
     * @return 不含正文的登记参数
     */
    private EvaluationRetention.Registration registration(Instant startedAt) {
        return new EvaluationRetention.Registration(
                owner,
                run,
                startedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                "1".repeat(64),
                "2".repeat(64));
    }

    /**
     * 仅为本例模拟已到期记录；生产仓储不提供修改开始时间的入口。
     *
     * @param overdueHours 到期后经过的小时数
     */
    private void expire(int overdueHours) {
        jdbc.update(
                "WITH t AS MATERIALIZED (SELECT clock_timestamp() AS n) UPDATE evaluation_retention_run SET started_at=t.n-INTERVAL '720 hours'-?*INTERVAL '1 hour',expires_at=t.n-?*INTERVAL '1 hour' FROM t WHERE run_id=?",
                overdueHours,
                overdueHours,
                run);
    }

    /**
     * 从清理列表找到本例任务，不假定共享数据库没有其他到期记录。
     *
     * @return 当前本例第一个待清理副本
     */
    private EvaluationRetention.Cleanup task() {
        return service.pending(100).stream()
                .filter(t -> run.equals(t.run()))
                .findFirst()
                .orElseThrow();
    }
}
