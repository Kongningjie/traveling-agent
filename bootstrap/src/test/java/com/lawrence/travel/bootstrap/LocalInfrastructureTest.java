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

    @Test
    void connectsToPostgresAndAppliesFlywayMigrationOnce() {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class))
                .isEqualTo("traveling_agent");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history "
                + "WHERE version = '1' AND success", Integer.class)).isEqualTo(1);
    }

    @Test
    void supportsJsonbAndRollsBackTheActualDatabaseTransaction() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("CREATE TEMP TABLE m0_transaction_probe (payload jsonb) ON COMMIT DROP");
            jdbc.update("INSERT INTO m0_transaction_probe VALUES (CAST(? AS jsonb))", "{\"city\":\"上海\"}");
            assertThat(jdbc.queryForObject("SELECT payload->>'city' FROM m0_transaction_probe", String.class))
                    .isEqualTo("上海");
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT to_regclass('pg_temp.m0_transaction_probe')::text", String.class))
                .isNull();
    }

    @Test
    void storesLoadsAndDeletesSessionsThroughTheConfiguredRedisRepository() {
        assertThat(sessions.getClass().getName()).contains("Redis");
        verifySessionRoundTrip(sessions);
    }

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
            repository.deleteById(session.getId());
        }
        assertThat(repository.findById(session.getId())).isNull();
    }
}
