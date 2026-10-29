package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.application.port.ToolEvidenceJournal;
import com.lawrence.travel.application.port.TravelToolPort;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实 PostgreSQL 验证并发调用记录、未知保留及 JSON 原文哈希持久化，不调用供应商。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TravelToolIntegrationTest {
    @Autowired ToolEvidenceJournal journal;
    @Autowired BudgetLedger ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private final UUID runId = UUID.randomUUID();

    /** 只清理本用例随机运行，不删除真实免费用量记录。 */
    @AfterEach
    void cleanOwnProbe() {
        jdbc.update("DELETE FROM tool_evidence WHERE run_id=?", runId);
        jdbc.update("DELETE FROM execution_budget WHERE run_id=?", runId);
    }

    /** 并发调用均保存独立记录，不再受服务组免费配额或在途记录限制。 */
    @Test
    void concurrentCallsAllKeepSeparateRecords() throws Exception {
        ledger.create(runId);
        String group = "test-" + runId;
        try (var threads = Executors.newFixedThreadPool(6)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int index = 0; index < 12; index++)
                results.add(
                        threads.submit(
                                () -> {
                                    try {
                                        journal.reserve(runId, group);
                                        return true;
                                    } catch (IllegalStateException expected) {
                                        return false;
                                    }
                                }));
            int winners = 0;
            for (var result : results) if (result.get()) winners++;
            assertThat(winners).isEqualTo(12);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM tool_evidence WHERE run_id=?",
                                    Integer.class,
                                    runId))
                    .isEqualTo(12);
        }
    }

    /** JSON 与哈希保存后重复失败回调不能覆盖成功；未知结果保留记录但不阻断后续调用。 */
    @Test
    void savesEvidenceAndAllowsCallsAfterUnknownUsage() {
        ledger.create(runId);
        String group = "test-" + runId;
        UUID first = journal.reserve(runId, group);
        journal.finish(
                first,
                new TravelToolPort.Evidence(
                        URI.create("https://www.dpm.org.cn/"),
                        Instant.now(),
                        "{\"status\":\"UNKNOWN\"}"));
        journal.finish(first, null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM tool_evidence WHERE id=?", String.class, first))
                .isEqualTo("COMPLETED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT length(content_hash) FROM tool_evidence WHERE id=?",
                                Integer.class,
                                first))
                .isEqualTo(64);
        UUID second = journal.reserve(runId, group);
        journal.finish(second, null);
        assertThat(journal.reserve(runId, group)).isNotNull();
    }

    /** 外层业务事务不能让未提交的预占提前获得网络发送资格。 */
    @Test
    void rejectsAmbientBusinessTransaction() {
        ledger.create(runId);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.executeWithoutResult(
                status ->
                        assertThatThrownBy(() -> journal.reserve(runId, "test-" + runId))
                                .hasMessage("TOOL_AMBIENT_TRANSACTION"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM tool_evidence WHERE run_id=?",
                                Integer.class,
                                runId))
                .isZero();
    }
}
