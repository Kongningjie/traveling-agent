package com.lawrence.travel.bootstrap;

import com.lawrence.travel.agent.runtime.GuardedDashScopeTransport;
import com.lawrence.travel.application.execution.ModelCallAdmission;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.bootstrap.config.ExecutionControlProperties;
import com.lawrence.travel.domain.execution.control.CallRejectedException;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.EndpointType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/** DEV-0035首批真实接入探针；显式授权后最多三请求，不启动后台Worker，不作为旅行质量成绩。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_DEV35_MODEL_PROBE", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(properties = {"travel.engine.enabled=false", "travel.external-calls-enabled=true",
        "travel.tools.enabled=false", "travel.documents.cleanup-enabled=false", "travel.lifecycle.maintenance-enabled=false", "logging.level.root=OFF", "logging.level.io.agentscope=OFF"})
class Dev35ModelProbeTest {
    @Autowired private ModelCallAdmission admission;
    @Autowired private BudgetLedger ledger;
    @Autowired private ExecutionControlProperties properties;
    @Autowired private Environment environment;
    @Autowired private JdbcTemplate jdbc;

    /** 只读取现有快照及PG计量，确认拒绝分类；不创建预算、不调用供应商、不刷新核实时间。
     * @throws Exception 本地结构化诊断报告无法写入 */
    @Test void recordsLocalBlockersWithoutVendorRequests() throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        for (String role : List.of("coordinator", "research", "reviewer")) {
            String model = properties.models().get(role);
            var row = new LinkedHashMap<String, Object>();
            row.put("role", role); row.put("model", model); row.put("status", "LOCAL_DIAGNOSTIC");
            var quota = properties.quotaEvidence().get(model);
            String reason = "QUOTA_SNAPSHOT_MISSING";
            if (quota != null) {
                try {
                    quota.requireUsable(model, "cn-beijing", "DASHSCOPE_NATIVE_REALTIME", 128, Instant.now());
                    boolean uncertain = ledger.hasUncertainUsage(new BudgetLedger.Target(model,
                            "cn-beijing", "DASHSCOPE_NATIVE_REALTIME"), quota.verifiedAt(), Instant.now());
                    var target = new BudgetLedger.Target(model, "cn-beijing", "DASHSCOPE_NATIVE_REALTIME");
                    if (ledger.hasUsageOverrun(target, quota.verifiedAt())) reason = "KNOWN_USAGE_OVERRUN";
                    else if (ledger.tokensSince(target, quota.verifiedAt()) > quota.remainingTokens() - 128)
                        reason = "GATE_QUOTA_INSUFFICIENT";
                    else reason = uncertain ? "LOCAL_QUOTA_CHECK_OK_WITH_UNKNOWN_RESERVATIONS" : "LOCAL_QUOTA_CHECK_OK";
                } catch (CallRejectedException rejected) { reason = "GATE_" + rejected.reason().name(); }
            }
            row.put("reason", reason); row.put("requests", 0); rows.add(row);
            System.out.println("DEV35_DIAGNOSTIC role=" + role + " reason=" + reason + " requests=0");
        }
        Path destination = Path.of(environment.getRequiredProperty("TRAVEL_DEV35_REPORT"));
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, new TravelJson().encode(Map.of("version", "dev35-local-diagnostic-v1",
                "at", Instant.now().toString(), "coverage", "LOCAL_ADMISSION_ONLY", "rows", rows)));
    }

    /** 每角色最多一次SDK文本流；既有UNKNOWN或免费门禁拒绝不删除、不重新核实。报告保留所有真实计量。
     * @throws Exception 仅本地结构化报告无法写入；不输出供应商原文或认证数据 */
    @Test void probesThreeExactModelsWithNoAutomaticRetry() throws Exception {
        assertThat(properties.models()).containsEntry("coordinator", "qwen3.8-27b")
                .containsEntry("research", "qwen3.7-flash-2026-07-15").containsEntry("reviewer", "qwen3.8-max-0902");
        var rows = new ArrayList<Map<String, Object>>(); boolean stop = false;
        for (String role : List.of("coordinator", "research", "reviewer")) {
            var row = new LinkedHashMap<String, Object>(); row.put("role", role); row.put("model", properties.models().get(role));
            if (stop) { row.put("status", "NOT_RUN"); row.put("reason", "PRIOR_FAILURE_OR_UNKNOWN"); rows.add(row); continue; }
            UUID run = UUID.randomUUID(); ledger.create(run); row.put("run", run);
            long start = System.nanoTime();
            try (var transport = new GuardedDashScopeTransport(admission, run, role, new OkHttpClient())) {
                var model = DashScopeChatModel.builder().apiKey(environment.getRequiredProperty("DASHSCOPE_API_KEY"))
                        .modelName(properties.models().get(role)).baseUrl("https://dashscope.aliyuncs.com")
                        .endpointType(EndpointType.MULTIMODAL).stream(true).enableThinking(false).enableSearch(false)
                        .httpTransport(transport).build();
                var options = GenerateOptions.builder().maxTokens(32)
                        .executionConfig(ExecutionConfig.builder().maxAttempts(1).timeout(Duration.ofSeconds(60)).build()).build();
                var responses = model.stream(List.of(Msg.builder().name("user").role(MsgRole.USER)
                        .content(TextBlock.builder().text("请只回复 OK。").build()).build()), List.of(), options)
                        .collectList().block(Duration.ofSeconds(70));
                String answer = responses == null ? "" : responses.stream().flatMap(r -> r.getContent().stream())
                        .filter(TextBlock.class::isInstance).map(TextBlock.class::cast).map(TextBlock::getText)
                        .collect(java.util.stream.Collectors.joining());
                row.put("status", answer.contains("OK") ? "PASS" : "FAIL");
                row.put("reason", answer.contains("OK") ? "TEXT_STREAM_OK" : "EXPECTED_TEXT_MISSING");
                if (!answer.contains("OK")) stop = true;
            } catch (RuntimeException failure) {
                row.put("status", "NOT_RUN"); row.put("reason", reason(failure));
            }
            long calls = ledger.read(run).modelCalls();
            long unknown = jdbc.queryForObject("SELECT count(*) FROM call_meter WHERE run_id=? AND status<>'COMPLETED'", Long.class, run);
            row.put("requests", calls); row.put("unknownRequests", unknown);
            if (calls != 1 && "PASS".equals(row.get("status"))) {
                row.put("status", "FAIL"); row.put("reason", "REQUEST_METER_MISMATCH"); stop = true;
            }
            // 没有实际请求时零Token是精确的；请求已发送但未完成时只记录UNKNOWN，不补零消耗。
            if (unknown == 0) {
                row.put("inputTokens", jdbc.queryForObject("SELECT COALESCE(sum(input_tokens),0) FROM call_meter WHERE run_id=?", Long.class, run));
                row.put("outputTokens", jdbc.queryForObject("SELECT COALESCE(sum(output_tokens),0) FROM call_meter WHERE run_id=?", Long.class, run));
            } else { row.put("status", "NOT_RUN"); row.put("reason", "REAL_USAGE_UNKNOWN"); }
            row.put("wallMillis", (System.nanoTime() - start) / 1000000);
            assertThat(calls).isBetween(0L, 1L);
            if (unknown > 0 || (calls > 0 && !"PASS".equals(row.get("status")))) stop = true;
            rows.add(row);
            System.out.println("DEV35_PROBE role=" + role + " status=" + row.get("status") + " reason=" + row.get("reason") + " requests=" + calls);
        }
        Path destination = Path.of(environment.getRequiredProperty("TRAVEL_DEV35_REPORT"));
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, new TravelJson().encode(Map.of("version", "dev35-model-probe-v1", "at", Instant.now().toString(),
                "coverage", "REAL_MODEL_REACHABILITY_NOT_TASK_QUALITY", "requestLimit", 3, "rows", rows)));
        System.out.println("DEV35_PROBE_RECORDED; report status is separate from JUnit protocol success");
    }

    /** @param failure 已归一化的适配器异常链 @return 固定门禁分类；绝不拼接供应商异常文本 */
    private static String reason(Throwable failure) {
        Throwable value = failure;
        for (int i = 0; i < 12 && value != null; i++, value = value.getCause()) {
            if (value instanceof CallRejectedException rejected) return "GATE_" + rejected.reason().name();
        }
        return "MODEL_PROTOCOL_OR_PROVIDER_FAILURE";
    }
}
