package com.lawrence.travel.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lawrence.travel.application.evaluation.EvaluationRetention;
import com.lawrence.travel.infrastructure.evaluation.EvaluationPlatformAccessMapper;
import com.lawrence.travel.infrastructure.evaluation.EvaluationRetentionMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 本机PG验证默认关闭及最早期限；只在回滚事务里模拟核验，不打开实际平台入口。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EvaluationPlatformAccessIntegrationTest {
    @Autowired private EvaluationPlatformAccessMapper access;
    @Autowired private EvaluationRetentionMapper retention;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    /** 默认关闭；模拟核验后期限最多一秒，撤销立即使同事务下一次查询失效。 */
    @Test
    void verifiedSnapshotIsShortAndRevocationClosesWholePlatform() {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            access.closeAccess();
                            assertFalse(access.decision().allows(Instant.now()));
                            UUID owner = UUID.randomUUID();
                            UUID run = UUID.randomUUID();
                            assertTrue(
                                    retention.register(
                                            new EvaluationRetention.Registration(
                                                    owner,
                                                    run,
                                                    Instant.now().minusSeconds(1),
                                                    "1".repeat(64),
                                                    "2".repeat(64))));
                            jdbc.update(
                                    "UPDATE evaluation_platform_access SET verified=true WHERE singleton=true");
                            var decision = access.decision();
                            assertTrue(decision.allows(Instant.now()));
                            assertTrue(decision.notAfter().isBefore(Instant.now().plusSeconds(2)));
                            assertTrue(retention.revoke(owner, run));
                            assertFalse(access.decision().allows(Instant.now()));
                            status.setRollbackOnly();
                        });
    }

    /** 早于当前时间的run直接截断资格，即使核验标志仍为true也不能访问。 */
    @Test
    void expiryDoesNotWaitForCleanupOrReadinessFlagChange() {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            UUID run = UUID.randomUUID();
                            jdbc.update(
                                    "WITH t AS MATERIALIZED (SELECT clock_timestamp()-INTERVAL '1 second' AS n) INSERT INTO evaluation_retention_run(run_id,owner_id,started_at,expires_at,dataset_hash,configuration_hash) SELECT ?,?,t.n-INTERVAL '720 hours',t.n,?,? FROM t",
                                    run,
                                    UUID.randomUUID(),
                                    "1".repeat(64),
                                    "2".repeat(64));
                            jdbc.update(
                                    "UPDATE evaluation_platform_access SET verified=true WHERE singleton=true");
                            assertFalse(access.decision().allows(Instant.now()));
                            status.setRollbackOnly();
                        });
    }
}
