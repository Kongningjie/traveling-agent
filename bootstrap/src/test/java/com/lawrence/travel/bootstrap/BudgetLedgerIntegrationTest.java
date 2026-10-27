package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.bootstrap.config.ExecutionControlProperties;
import com.lawrence.travel.domain.execution.control.CallKind;
import com.lawrence.travel.domain.execution.control.CallRejectedException;
import com.lawrence.travel.domain.execution.control.RunLimits;
import com.lawrence.travel.domain.execution.control.TokenUsage;
import com.lawrence.travel.infrastructure.persistence.control.BudgetMapper;
import com.lawrence.travel.infrastructure.persistence.control.PostgresBudgetLedger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 显式启用的真实 PostgreSQL／MyBatis 计量测试；仅使用独立测试身份，不调用供应商。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BudgetLedgerIntegrationTest {
    @Autowired private BudgetLedger ledger;
    @Autowired private BudgetMapper mapper;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExecutionControlProperties properties;

    private final UUID runId = UUID.randomUUID();
    private final Instant started = Instant.now();
    private final BudgetLedger.Target target =
            new BudgetLedger.Target("__meter_test__", "test-region", "TEST");
    private final RunLimits limits =
            new RunLimits(30, 60, 2, Duration.ofSeconds(180), Duration.ofMinutes(15));

    /** 仅清理本测试随机运行的子记录和预算，不删除其他用户或运行的计量。 */
    @AfterEach
    void cleanUpOwnBudget() {
        jdbc.update("DELETE FROM traveling_agent.call_meter WHERE run_id=?", runId);
        jdbc.update("DELETE FROM traveling_agent.execution_budget WHERE run_id=?", runId);
    }

    /**
     * 验证 60 个并发预占中只有 30 个成功，预算和请求记录同事务一致。
     * @throws Exception 线程池等待或测试任务失败时使测试失败
     */
    @Test
    void concurrentReservationsCannotExceedModelLimit() throws Exception {
        ledger.create(runId);
        var calls = new ArrayList<Callable<Boolean>>();
        for (int i = 0; i < 60; i++) {
            calls.add(
                    () -> {
                        try {
                            ledger.reserve(runId, CallKind.MODEL, limits, target, 10, started);
                            return true;
                        } catch (CallRejectedException rejected) {
                            assertThat(rejected.reason())
                                    .isEqualTo(CallRejectedException.Reason.CALL_BUDGET_EXHAUSTED);
                            return false;
                        }
                    });
        }
        int accepted = 0;
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (var result : executor.invokeAll(calls)) if (result.get()) accepted++;
        }
        assertThat(accepted).isEqualTo(30);
        assertThat(ledger.read(runId).modelCalls()).isEqualTo(30);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM traveling_agent.call_meter WHERE run_id=?",
                                Integer.class,
                                runId))
                .isEqualTo(30);
    }

    /** 验证记录插入失败会回滚预占，不产生只扣次数却没有请求身份的事务裂缝。 */
    @Test
    void reservationAndMeterInsertRollBackTogether() {
        ledger.create(runId);
        var invalid = new BudgetLedger.Target(null, "test-region", "TEST");
        assertThatThrownBy(
                        () -> ledger.reserve(runId, CallKind.MODEL, limits, invalid, 10, started))
                .isInstanceOf(RuntimeException.class);
        assertThat(ledger.read(runId).modelCalls()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM traveling_agent.call_meter WHERE run_id=?",
                                Integer.class,
                                runId))
                .isZero();
    }

    /** 验证已知、未知和崩溃未决用量区别计量，重复回调和建账不能重置数据。 */
    @Test
    void unknownAndUnfinishedRequestsRemainChargedAcrossRepositoryRecreation() {
        ledger.create(runId);
        var known = ledger.reserve(runId, CallKind.MODEL, limits, target, 10, started);
        ledger.finish(known.id(), new TokenUsage(3, 2), Duration.ofMillis(10));
        ledger.finish(known.id(), new TokenUsage(90, 90), Duration.ofMillis(20));
        ledger.reserve(runId, CallKind.MODEL, limits, target, 30, started);
        assertThat(ledger.hasUncertainUsage(target, started.minusSeconds(1), started)).isFalse();
        assertThat(
                        ledger.hasUncertainUsage(
                                target, started.minusSeconds(1), started.plusSeconds(1)))
                .isTrue();
        var unknown = ledger.reserve(runId, CallKind.MODEL, limits, target, 20, started);
        ledger.finish(unknown.id(), null, Duration.ofMillis(10));

        // 重建仓储实例模拟不复用进程内状态；真正容器重建保留由持久化脚本另测。
        var recreated = new PostgresBudgetLedger(mapper, transactions);
        recreated.create(runId);
        assertThat(recreated.read(runId).modelCalls()).isEqualTo(3);
        assertThat(recreated.tokensSince(target, started.minusSeconds(1))).isEqualTo(55);
        assertThat(recreated.hasUsageOverrun(target, started.minusSeconds(1))).isFalse();
        assertThat(recreated.hasUncertainUsage(target, started.minusSeconds(1), started)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM traveling_agent.call_meter WHERE id=?",
                                String.class,
                                unknown.id()))
                .isEqualTo("UNKNOWN");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT input_tokens FROM traveling_agent.call_meter WHERE id=?",
                                Long.class,
                                unknown.id()))
                .isNull();
    }

    /** 验证实际用量超过保守上界后额度变为不确定，阻止沿用失真的快照继续调用。 */
    @Test
    void usageAboveDeclaredBoundRequiresReverification() {
        ledger.create(runId);
        var request = ledger.reserve(runId, CallKind.MODEL, limits, target, 1, started);
        ledger.finish(request.id(), new TokenUsage(1, 1), Duration.ofMillis(1));
        assertThat(ledger.hasUncertainUsage(target, started.minusSeconds(1), started)).isTrue();
        assertThat(ledger.tokensSince(target, started.minusSeconds(1))).isEqualTo(2);
        assertThat(ledger.hasUsageOverrun(target, started.minusSeconds(1))).isTrue();
        assertThat(ledger.hasUsageOverrun(target, started.plusSeconds(1))).isFalse();
        assertThat(
                        ledger.hasUsageOverrun(
                                new BudgetLedger.Target("__other_model__", "test-region", "TEST"),
                                started.minusSeconds(1)))
                .isFalse();
    }

    /** 真实PG验证重建服务后UNKNOWN/RESERVED仍按上限扣算，余额充足放行、余额不足拒绝且不改历史。 */
    @Test
    void admissionAfterRestartChargesUnknownAndReservedWithoutBlanketBlock() {
        ledger.create(runId);
        var unknown = ledger.reserve(runId, CallKind.MODEL, limits, target, 20, started);
        ledger.finish(unknown.id(), null, Duration.ofMillis(1));
        var pending = ledger.reserve(runId, CallKind.MODEL, limits, target, 30, started);
        var known = ledger.reserve(runId, CallKind.MODEL, limits, target, 10, started);
        ledger.finish(known.id(), new TokenUsage(3, 2), Duration.ofMillis(1));
        var recreated = new PostgresBudgetLedger(mapper, transactions);
        var quota =
                new com.lawrence.travel.domain.execution.control.FreeQuotaEvidence(
                        target.modelId(),
                        target.region(),
                        target.api(),
                        100,
                        started.minusSeconds(1),
                        started.plusSeconds(60),
                        started.plusSeconds(600),
                        true);
        var gate =
                new com.lawrence.travel.application.execution.ModelCallAdmission(
                        true,
                        java.util.Map.of("planner", target.modelId()),
                        java.util.Map.of(target.modelId(), quota),
                        limits,
                        recreated,
                        new com.lawrence.travel.infrastructure.execution.SemaphoreModelRequestSlots(
                                1),
                        java.time.Clock.systemUTC());
        assertThat(recreated.tokensSince(target, started.minusSeconds(1))).isEqualTo(55);
        try (var permit = gate.begin(runId, "planner", target.region(), target.api(), 45)) {
            assertThat(recreated.tokensSince(target, started.minusSeconds(1))).isEqualTo(100);
            permit.complete(new TokenUsage(1, 0));
        }
        assertThat(recreated.tokensSince(target, started.minusSeconds(1))).isEqualTo(56);
        assertThatThrownBy(() -> gate.begin(runId, "planner", target.region(), target.api(), 45))
                .hasMessage("QUOTA_INSUFFICIENT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM traveling_agent.call_meter WHERE id=?",
                                String.class,
                                unknown.id()))
                .isEqualTo("UNKNOWN");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM traveling_agent.call_meter WHERE id=?",
                                String.class,
                                pending.id()))
                .isEqualTo("RESERVED");
        assertThat(recreated.read(runId).modelCalls()).isEqualTo(4);
    }

    /** 验证不在已有业务事务中返回未提交的请求许可，避免事务回滚后供应商仍已消耗。 */
    @Test
    void rejectsAdmissionInsideAnAmbientBusinessTransaction() {
        ledger.create(runId);
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status ->
                                assertThatThrownBy(
                                                () ->
                                                        ledger.reserve(
                                                                runId,
                                                                CallKind.MODEL,
                                                                limits,
                                                                target,
                                                                10,
                                                                started))
                                        .isInstanceOf(IllegalStateException.class)
                                        .hasMessageContaining("ambient business transaction"));
        assertThat(ledger.read(runId).modelCalls()).isZero();
    }

    /** 验证主动时间可持久化且不在重建仓储时归零，同时配置仍默认禁用真实调用。 */
    @Test
    void activeTimeAndDisabledConfigurationRemainConservative() {
        assertThat(properties.externalCallsEnabled()).isFalse();
        assertThat(properties.limits().modelConcurrency()).isEqualTo(4);
        ledger.create(runId);
        ledger.addActiveTime(runId, Duration.ofSeconds(899));
        var reloaded = new PostgresBudgetLedger(mapper, transactions).read(runId);
        assertThat(limits.requestTimeout(reloaded)).isEqualTo(Duration.ofSeconds(1));
        ledger.addActiveTime(runId, Duration.ofSeconds(1));
        assertThatThrownBy(() -> ledger.reserve(runId, CallKind.MODEL, limits, target, 1, started))
                .hasMessage("ACTIVE_TIME_EXHAUSTED");
        assertThat(ledger.read(runId).modelCalls()).isZero();
    }
}
