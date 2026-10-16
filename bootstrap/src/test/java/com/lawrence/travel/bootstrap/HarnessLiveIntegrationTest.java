package com.lawrence.travel.bootstrap;

import com.lawrence.travel.agent.runtime.GuardedDashScopeTransport;
import com.lawrence.travel.agent.runtime.RestrictedHarnessFactory;
import com.lawrence.travel.application.execution.ModelCallAdmission;
import com.lawrence.travel.application.port.AgentRuntimePort;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.bootstrap.config.ExecutionControlProperties;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.EndpointType;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 显式免费验证入口；任一角色失败立即停止，不自动改型号或删除真实计量记录。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_LIVE_MODEL_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(properties = {"travel.external-calls-enabled=true", "logging.level.io.agentscope=OFF"},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class HarnessLiveIntegrationTest {
    @Autowired ModelCallAdmission admission;
    @Autowired BudgetLedger ledger;
    @Autowired ExecutionControlProperties properties;
    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentRuntimePort runtime;
    @TempDir Path workspace;

    /** 逐个验证三种精确型号的工具、结构化输出和真实用量；最后验证运行端口及主动取消。 */
    @Test
    void verifiesThreeModelsThenRuntimeAndCancellation() throws Exception {
        for (String role : List.of("coordinator", "research", "reviewer")) {
            UUID runId = UUID.randomUUID();
            ledger.create(runId);
            var probe = new ValidationTool();
            var toolkit = new Toolkit();
            toolkit.registerTool(probe);
            try (var transport = new GuardedDashScopeTransport(admission, runId, role, new OkHttpClient())) {
                var model = DashScopeChatModel.builder().apiKey(environment.getRequiredProperty("DASHSCOPE_API_KEY"))
                        .modelName(properties.models().get(role)).baseUrl("https://dashscope.aliyuncs.com")
                        .endpointType(EndpointType.MULTIMODAL).stream(false).enableThinking(false).enableSearch(false)
                        .httpTransport(transport).defaultOptions(GenerateOptions.builder().maxTokens(256)
                                .executionConfig(ExecutionConfig.builder().maxAttempts(1).build()).build()).build();
                try (var harness = RestrictedHarnessFactory.create(model, toolkit, workspace.resolve(role), role)) {
                    var result = harness.call(List.of(Msg.builder().name("user").role(MsgRole.USER)
                            .content(TextBlock.builder().text("必须先调用 lookup_validation_value 获取校验值，"
                                    + "再通过结构化输出返回 answer，只包含工具给出的数字，不要猜测。").build()).build()),
                            RestrictedHarnessFactory.StructuredReply.class).block(Duration.ofMinutes(4));
                    assertThat(result.getStructuredData(RestrictedHarnessFactory.StructuredReply.class).answer())
                            .isEqualTo(probe.value);
                    assertThat(probe.calls.get()).isGreaterThanOrEqualTo(1);
                    assertThat(ledger.read(runId).modelCalls()).isBetween(2L, 5L);
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM call_meter WHERE run_id = ? "
                            + "AND (status <> 'COMPLETED' OR input_tokens IS NULL OR output_tokens IS NULL)",
                            Integer.class, runId)).isZero();
                    System.out.println("LIVE_MODEL_PASS role=" + role + " run=" + runId);
                }
            }
        }
        UUID runId = UUID.randomUUID();
        ledger.create(runId);
        try (var invocation = runtime.start(runId, "planner", "请在 answer 中只返回 OK。")) {
            assertThat(invocation.result().toCompletableFuture().get(200, TimeUnit.SECONDS)).isEqualTo("OK");
        }
        UUID cancelledRun = UUID.randomUUID();
        ledger.create(cancelledRun);
        try (var invocation = runtime.start(cancelledRun, "planner", "请详细介绍十个中国城市，每个城市至少一百字。")) {
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (ledger.read(cancelledRun).modelCalls() == 0 && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(ledger.read(cancelledRun).modelCalls()).isPositive();
            invocation.close();
            assertThatThrownBy(() -> invocation.result().toCompletableFuture().get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);
            deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (jdbc.queryForObject("SELECT count(*) FROM call_meter WHERE run_id = ? AND status='RESERVED'",
                    Integer.class, cancelledRun) > 0 && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM call_meter WHERE run_id = ? AND status='RESERVED'",
                    Integer.class, cancelledRun)).isZero();
            System.out.println("LIVE_CANCEL_PASS run=" + cancelledRun);
        }
    }

    /** 每次测试生成随机值，证明模型使用了工具结果而非固定常识。 */
    public static class ValidationTool {
        private final String value = String.valueOf(java.util.concurrent.ThreadLocalRandom.current().nextInt(100000, 999999));
        private final AtomicInteger calls = new AtomicInteger();
        /** @return 随机非敏感校验值 */
        @Tool(name = "lookup_validation_value", description = "返回本次随机校验数字，回答前必须调用。")
        public String lookup() { calls.incrementAndGet(); return value; }
    }
}
