package com.lawrence.travel.bootstrap;

import java.sql.DriverManager;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

/** 在唯一临时数据库重建一期V18历史数据再升级；从不回退/清理正在使用的业务数据库。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(properties = "travel.engine.enabled=false")
class LegacyMigrationIntegrationTest {
    @Autowired private DataSource source;
    @Autowired private Environment environment;
    /** 一期已完成聊天和预算升级后保留原身份/文本/计量，不伪造后台作业重放。
     * @throws Exception 临时库实验失败，真实凭据不进入断言 */
    @Test void legacyCompletedDialogueAndBudgetSurviveUpgrade() throws Exception {
        String database = "ph2_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        boolean created = false;
        try (var admin = source.getConnection(); var command = admin.createStatement()) {
            String base = admin.getMetaData().getURL();
            String url = base.replaceFirst("(jdbc:postgresql://[^/]+/)[^?]+", "$1" + database);
            assertThat(url.equals(base)).isFalse();
            String username = environment.getRequiredProperty("spring.datasource.username"), password = environment.getRequiredProperty("spring.datasource.password");
            try {
                command.execute("CREATE DATABASE " + database); created = true;
                var old = Flyway.configure().dataSource(url, username, password).schemas("traveling_agent").defaultSchema("traveling_agent").target("18").load(); old.migrate();
                UUID user = UUID.randomUUID(), chat = UUID.randomUUID(), budget = UUID.randomUUID(), input = UUID.randomUUID();
                try (var connection = DriverManager.getConnection(url, username, password)) {
                    try (var statement = connection.prepareStatement("INSERT INTO app_user(id,login_name,password_hash) VALUES(?,?,?)")) { statement.setObject(1, user); statement.setString(2, "legacy_" + user.toString().substring(0, 8)); statement.setString(3, "__NON_LOGIN_TEST_HASH__"); statement.executeUpdate(); }
                    try (var statement = connection.prepareStatement("INSERT INTO execution_budget(run_id,model_calls) VALUES(?,3)")) { statement.setObject(1, budget); statement.executeUpdate(); }
                    try (var statement = connection.prepareStatement("INSERT INTO dialogue_chat(id,user_id,title,budget_id) VALUES(?,?,'升级前聊天',?)")) { statement.setObject(1, chat); statement.setObject(2, user); statement.setObject(3, budget); statement.executeUpdate(); }
                    try (var statement = connection.prepareStatement("INSERT INTO dialogue_input(id,user_id,chat_id,text,state,reply) VALUES(?,?,?,'升级前原文','DONE','升级前回复')")) { statement.setObject(1, input); statement.setObject(2, user); statement.setObject(3, chat); statement.executeUpdate(); }
                }
                var current = Flyway.configure().dataSource(url, username, password).schemas("traveling_agent").defaultSchema("traveling_agent").load(); current.migrate(); current.validate();
                assertThat(current.info().pending()).isEmpty();
                try (var connection = DriverManager.getConnection(url, username, password); var statement = connection.createStatement()) {
                    try (var result = statement.executeQuery("SELECT text,reply,state,job_id FROM dialogue_input")) { assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo("升级前原文"); assertThat(result.getString(2)).isEqualTo("升级前回复"); assertThat(result.getString(3)).isEqualTo("DONE"); assertThat(result.getObject(4)).isNull(); }
                    try (var result = statement.executeQuery("SELECT model_calls FROM execution_budget")) { assertThat(result.next()).isTrue(); assertThat(result.getLong(1)).isEqualTo(3); }
                    try (var result = statement.executeQuery("SELECT count(*) FROM background_job")) { assertThat(result.next()).isTrue(); assertThat(result.getInt(1)).isZero(); }
                }
            } finally {
                // 数据库名完全由本次UUID产生，仅本次确实创建成功时回收；不能清理源库或用户卷。
                if (created && database.matches("ph2_upgrade_[0-9a-f]{32}")) command.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }
}
