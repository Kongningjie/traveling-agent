package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.execution.StageOutput;
import com.lawrence.travel.application.execution.StagePlan;
import com.lawrence.travel.application.interaction.NegotiationService;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.domain.execution.ExecutionStep;
import com.lawrence.travel.domain.execution.StageType;
import com.lawrence.travel.domain.identity.UserIdentity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.SpringApplication;

/** 仅测试子进程：在指定数据库事务边界硬退出，输出丢弃，绝不接入真实模型。 */
public final class ExecutionCrashProbe {
    /**
     * @param args 模式及随机测试身份，不接受密钥；连接仅继承本机测试环境，固定退出码23表示到达故障窗口
     */
    public static void main(String[] args) {
        try {
            var app = new SpringApplication(TravelingAgentApplication.class);
            app.setDefaultProperties(
                    Map.of("spring.main.banner-mode", "off", "logging.level.root", "OFF"));
            var context =
                    app.run(
                            "--spring.profiles.active=local",
                            "--server.port=0",
                            "--travel.engine.enabled=false",
                            "--travel.external-calls-enabled=false");
            String mode = args.length > 1 ? args[1] : "CLAIM";
            if (mode.startsWith("INPUT_")) {
                var transactions = context.getBean(BusinessTransactions.class);
                var negotiation = context.getBean(NegotiationService.class);
                transactions.required(
                        () -> {
                            negotiation.create(
                                    new UserIdentity(UUID.fromString(args[0]), args[2]),
                                    UUID.fromString(args[3]),
                                    "输入故障实验",
                                    "本地原始需求",
                                    List.of());
                            // 外层事务尚未提交就halt，PostgreSQL必须回滚消息、任务、预算与启动事件。
                            if (mode.equals("INPUT_BEFORE")) Runtime.getRuntime().halt(23);
                            return true;
                        });
                if (!mode.equals("INPUT_AFTER")) Runtime.getRuntime().halt(24);
            } else {
                var store = context.getBean(ExecutionEngineStore.class);
                UUID run = UUID.fromString(args[0]);
                var hint =
                        store.candidates(100).stream()
                                .filter(h -> h.runId().equals(run))
                                .findFirst()
                                .orElseThrow();
                var claim = store.claim(hint, "crashed_process").orElseThrow();
                if (!mode.equals("CLAIM")) {
                    var next =
                            new StagePlan(
                                    UUID.fromString(args[2]),
                                    new ExecutionStep.Key(StageType.RESEARCH, 1, "after-crash"),
                                    null,
                                    "{}",
                                    List.of(claim.step().id()));
                    var prepared =
                            store.prepare(
                                    claim,
                                    new StageOutput(
                                            "已返回但未必提交的本地结果",
                                            "{\"crashCheckpoint\":true}",
                                            List.of(next),
                                            StageOutput.Outcome.CONTINUE,
                                            null));
                    if (mode.equals("COMMIT")) store.commit(prepared);
                    else if (!mode.equals("PREPARE")) Runtime.getRuntime().halt(24);
                }
            }
            Runtime.getRuntime().halt(23);
        } catch (Throwable failure) {
            Runtime.getRuntime().halt(25);
        }
    }
}
