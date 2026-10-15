package com.lawrence.travel.bootstrap;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** 显式连接本地 Compose 实例；默认构建不依赖容器，也不读取 .env。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalInfrastructureTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SessionRepository<? extends Session> sessions;

    /** 验证真实连接落入应用 schema，且 V1 迁移只有一条成功记录。 */
    @Test
    void connectsToPostgresAndAppliesFlywayMigrationOnce() {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class))
                .isEqualTo("traveling_agent");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history "
                + "WHERE version = '1' AND success", Integer.class)).isEqualTo(1);
    }

    /** 验证 JSONB 中文字段读取和真实事务回滚，不创建持久业务表。 */
    @Test
    void supportsJsonbAndRollsBackTheActualDatabaseTransaction() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // 临时表在同一连接的事务内创建；主动回滚后表也应不存在，而非仅清空行。
            // 不修改已执行的 Flyway 脚本，避免以测试探针改变业务 schema 历史。
            jdbc.execute("CREATE TEMP TABLE m0_transaction_probe (payload jsonb) ON COMMIT DROP");
            jdbc.update("INSERT INTO m0_transaction_probe VALUES (CAST(? AS jsonb))", "{\"city\":\"上海\"}");
            assertThat(jdbc.queryForObject("SELECT payload->>'city' FROM m0_transaction_probe", String.class))
                    .isEqualTo("上海");
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT to_regclass('pg_temp.m0_transaction_probe')::text", String.class))
                .isNull();
    }

    /** 验证实际 Redis Session 仓储的保存、读取和删除；不代表登录流程已实现。 */
    @Test
    void storesLoadsAndDeletesSessionsThroughTheConfiguredRedisRepository() {
        assertThat(sessions.getClass().getName()).contains("Redis");
        verifySessionRoundTrip(sessions);
    }

    /**
     * 通过框架仓储写入随机会话属性并校验，再无条件删除本轮会话。
     *
     * @param <S> 仓储实际管理的 Spring Session 类型
     * @param repository 已装配的真实 Redis 会话仓储，不可为 null
     */
    private <S extends Session> void verifySessionRoundTrip(SessionRepository<S> repository) {
        S session = repository.createSession();
        String marker = UUID.randomUUID().toString();
        session.setMaxInactiveInterval(Duration.ofMinutes(1));
        session.setAttribute("m0-verification", marker);
        try {
            repository.save(session);
            S loaded = repository.findById(session.getId());
            assertThat(loaded).isNotNull();
            assertThat((String) loaded.getAttribute("m0-verification")).isEqualTo(marker);
            assertThat(loaded.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(1));
        } finally {
            // 即使断言失败也只删除本轮创建的会话，不清理其他会话或整个 Redis 库。
            repository.deleteById(session.getId());
        }
        assertThat(repository.findById(session.getId())).isNull();
    }
}
