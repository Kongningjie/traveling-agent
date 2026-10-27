package com.lawrence.travel.bootstrap;

import com.lawrence.travel.agent.runtime.GuardedDashScopeTransport;
import com.lawrence.travel.application.execution.ModelCallAdmission;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.bootstrap.config.ExecutionControlProperties;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.EndpointType;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

/** 需当前明确授权才运行：单次 Flash 文本流请求，不触发工具、Harness 循环或取消实验。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_MODEL_REACHABILITY_TEST", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(
        properties = {"travel.external-calls-enabled=true", "logging.level.io.agentscope=OFF"})
class ModelReachabilityLiveTest {
    @Autowired private ModelCallAdmission admission;
    @Autowired private BudgetLedger ledger;
    @Autowired private ExecutionControlProperties properties;
    @Autowired private Environment environment;
    @Autowired private JdbcTemplate jdbc;

    /** 单次实际 SDK 流请求必须返回文本，保存真实计量；失败不重试或换型号。 */
    @Test
    void verifiesOneResearchModelRequest() {
        UUID run = UUID.randomUUID();
        ledger.create(run);
        try (var transport =
                new GuardedDashScopeTransport(admission, run, "research", new OkHttpClient())) {
            var model =
                    DashScopeChatModel.builder()
                            .apiKey(environment.getRequiredProperty("DASHSCOPE_API_KEY"))
                            .modelName(properties.models().get("research"))
                            .baseUrl("https://dashscope.aliyuncs.com")
                            .endpointType(EndpointType.MULTIMODAL)
                            .stream(true)
                            .enableThinking(false)
                            .enableSearch(false)
                            .httpTransport(transport)
                            .build();
            var messages =
                    List.of(
                            Msg.builder()
                                    .name("user")
                                    .role(MsgRole.USER)
                                    .content(TextBlock.builder().text("请只回复 OK。").build())
                                    .build());
            var options =
                    GenerateOptions.builder()
                            .maxTokens(16)
                            .executionConfig(
                                    ExecutionConfig.builder()
                                            .maxAttempts(1)
                                            .timeout(Duration.ofSeconds(60))
                                            .build())
                            .build();
            var responses =
                    model.stream(messages, List.of(), options)
                            .collectList()
                            .block(Duration.ofSeconds(70));
            assertThat(responses).isNotEmpty();
            String text =
                    responses.stream()
                            .flatMap(response -> response.getContent().stream())
                            .filter(TextBlock.class::isInstance)
                            .map(TextBlock.class::cast)
                            .map(TextBlock::getText)
                            .collect(java.util.stream.Collectors.joining());
            assertThat(text).contains("OK");
            assertThat(ledger.read(run).modelCalls()).isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM call_meter WHERE run_id=? AND status <> 'COMPLETED'",
                                    Integer.class,
                                    run))
                    .isZero();
            Long tokens =
                    jdbc.queryForObject(
                            "SELECT sum(input_tokens + output_tokens) FROM call_meter WHERE run_id=?",
                            Long.class,
                            run);
            System.out.println(
                    "MINIMAL_MODEL_PASS requests=1 role=research tokens=" + tokens + " run=" + run);
        }
    }
}
