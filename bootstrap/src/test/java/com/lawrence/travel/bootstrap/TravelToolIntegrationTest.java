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
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "travel.engine.enabled=false")
class TravelToolIntegrationTest {
    /**
     * 定向工具计量验证使用专用数据库和恢复目录，默认运行仍沿用既有测试配置。
     *
     * @param registry Spring动态测试属性
     */
    @org.springframework.test.context.DynamicPropertySource
    static void isolatedDatabase(
            org.springframework.test.context.DynamicPropertyRegistry registry) {
        ModelPolicyDatabase.bind(registry);
    }

    @Autowired ToolEvidenceJournal journal;
    @Autowired BudgetLedger ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private final UUID runId = UUID.randomUUID();

    /** 只清理本用例随机运行，不删除真实免费用量记录。 */
    @AfterEach
    void cleanOwnProbe() {
        jdbc.update("DELETE FROM tool_evidence WHERE run_id=?", runId);
        jdbc.update("DELETE FROM call_meter WHERE run_id=?", runId);
        jdbc.update("DELETE FROM execution_budget WHERE run_id=?", runId);
    }

    /** 真实应用工具调用成功后，两份PG记录都完成；工具Token为0，不被评测判为未知。 */
    @Test
    void completedToolHasKnownZeroModelTokens() {
        ledger.create(runId);
        var tool = org.mockito.Mockito.mock(TravelToolPort.class);
        var response =
                new TravelToolPort.Evidence(
                        URI.create("https://official.example/"),
                        Instant.now(),
                        "{\"status\":\"AVAILABLE\"}");
        org.mockito.Mockito.when(
                        tool.query(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn(response);
        assertThat(
                        localService(tool)
                                .query(
                                        runId,
                                        TravelToolPort.Operation.SEARCH,
                                        java.util.Map.of("query", "合成旅行资料")))
                .isEqualTo(response);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM tool_evidence WHERE run_id=?",
                                String.class,
                                runId))
                .isEqualTo("COMPLETED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM call_meter WHERE run_id=?",
                                String.class,
                                runId))
                .isEqualTo("COMPLETED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT input_tokens+output_tokens FROM call_meter WHERE run_id=?",
                                Long.class,
                                runId))
                .isZero();
        assertThat(ledger.read(runId).toolCalls()).isEqualTo(1);
        assertThat(ledger.read(runId).modelCalls()).isZero();
        org.mockito.Mockito.verify(tool)
                .query(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /** 失败仍保留未知且不自动重发；之后一次显式成功有自己的完成记录，不改历史或重置次数。 */
    @Test
    void failedToolStaysUnknownAndExplicitSuccessHasSeparateMeter() {
        ledger.create(runId);
        var tool = org.mockito.Mockito.mock(TravelToolPort.class);
        org.mockito.Mockito.when(
                        tool.query(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("LOCAL_TOOL_FAILED"))
                .thenReturn(
                        new TravelToolPort.Evidence(
                                URI.create("https://official.example/"),
                                Instant.now(),
                                "{\"status\":\"AVAILABLE\"}"));
        var service = localService(tool);
        assertThatThrownBy(
                        () ->
                                service.query(
                                        runId,
                                        TravelToolPort.Operation.SEARCH,
                                        java.util.Map.of("query", "合成旅行资料")))
                .hasMessage("LOCAL_TOOL_FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM call_meter WHERE run_id=?",
                                String.class,
                                runId))
                .isEqualTo("UNKNOWN");
        org.mockito.Mockito.verify(tool)
                .query(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        service.query(runId, TravelToolPort.Operation.SEARCH, java.util.Map.of("query", "第二次显式请求"));
        for (String table : java.util.List.of("call_meter", "tool_evidence")) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM "
                                            + table
                                            + " WHERE run_id=? AND status='UNKNOWN'",
                                    Integer.class,
                                    runId))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM "
                                            + table
                                            + " WHERE run_id=? AND status='COMPLETED'",
                                    Integer.class,
                                    runId))
                    .isEqualTo(1);
        }
        assertThat(ledger.read(runId).toolCalls()).isEqualTo(2);
        org.mockito.Mockito.verify(tool, org.mockito.Mockito.times(2))
                .query(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /**
     * 接入真实PG仓储，仅将外部HTTP工具替换为本地返回，不读取任何供应商凭据。
     *
     * @param tool 本地工具替身
     * @return 真实应用工具服务
     */
    private com.lawrence.travel.application.execution.TravelToolService localService(
            TravelToolPort tool) {
        return new com.lawrence.travel.application.execution.TravelToolService(
                tool,
                journal,
                ledger,
                new com.lawrence.travel.domain.execution.control.RunLimits(
                        30,
                        60,
                        2,
                        java.time.Duration.ofSeconds(180),
                        java.time.Duration.ofMinutes(15)),
                java.util.Map.of(),
                true,
                java.time.Clock.systemUTC());
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
