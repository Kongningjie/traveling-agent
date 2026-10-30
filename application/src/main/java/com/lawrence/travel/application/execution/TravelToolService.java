package com.lawrence.travel.application.execution;

import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.application.port.ToolEvidenceJournal;
import com.lawrence.travel.application.port.TravelToolPort;
import com.lawrence.travel.domain.execution.control.CallKind;
import com.lawrence.travel.domain.execution.control.RunLimits;
import com.lawrence.travel.domain.execution.control.TokenUsage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 检查工具预算并调用地图、天气、检索等资料工具。
 */
public final class TravelToolService {
    private final TravelToolPort tools;
    private final ToolEvidenceJournal journal;
    private final BudgetLedger ledger;
    private final RunLimits limits;
    private final boolean enabled;
    private final Clock clock;
    private final com.lawrence.travel.application.safety.SafetyGate safety;
    private final com.lawrence.travel.application.port.ExecutionTrace trace;

    /**
     * 绑定工具与持久化应用端口，默认开关由装配提供。
     *
     * @param tools 供应商适配
     * @param journal 调用记录和证据仓储
     * @param ledger 原运行预算
     * @param limits 原运行限制
     * @param grants 兼容旧配置的服务组记录，不再参与准入
     * @param enabled 是否允许真实调用
     * @param clock 时间源
     */
    public TravelToolService(
            TravelToolPort tools,
            ToolEvidenceJournal journal,
            BudgetLedger ledger,
            RunLimits limits,
            Map<String, Grant> grants,
            boolean enabled,
            Clock clock) {
        this(
                tools,
                journal,
                ledger,
                limits,
                grants,
                enabled,
                clock,
                com.lawrence.travel.application.safety.SafetyGate.local());
    }

    /**
     * @param tools 供应商端口
     * @param journal 证据
     * @param ledger 计量
     * @param limits 运行上限
     * @param grants 兼容旧配置，不执行免费额度限制
     * @param enabled 当前授权
     * @param clock 时间
     * @param safety 共用文本边界，覆盖直接调查和Agent工具
     */
    public TravelToolService(
            TravelToolPort tools,
            ToolEvidenceJournal journal,
            BudgetLedger ledger,
            RunLimits limits,
            Map<String, Grant> grants,
            boolean enabled,
            Clock clock,
            com.lawrence.travel.application.safety.SafetyGate safety) {
        this(
                tools,
                journal,
                ledger,
                limits,
                grants,
                enabled,
                clock,
                safety,
                com.lawrence.travel.application.port.ExecutionTrace.disabled());
    }

    /**
     * @param tools 外部适配
     * @param journal 证据
     * @param ledger 原预算
     * @param limits 上限
     * @param grants 兼容旧配置，不执行免费额度限制
     * @param enabled 当前授权
     * @param clock 时钟
     * @param safety 安全
     * @param trace 固定事实轨迹
     */
    public TravelToolService(
            TravelToolPort tools,
            ToolEvidenceJournal journal,
            BudgetLedger ledger,
            RunLimits limits,
            Map<String, Grant> grants,
            boolean enabled,
            Clock clock,
            com.lawrence.travel.application.safety.SafetyGate safety,
            com.lawrence.travel.application.port.ExecutionTrace trace) {
        this.trace = trace;
        this.safety = safety;
        this.tools = tools;
        this.journal = journal;
        this.ledger = ledger;
        this.limits = limits;
        this.enabled = enabled;
        this.clock = clock;
    }

    /**
     * 调用前提交两份保守预占，完成后保存证据；中途失败不自动退回次数或重试。
     *
     * @param runId 已建立的原运行
     * @param operation 白名单操作
     * @param arguments 业务参数
     * @return 数据库已保存的供应商证据
     */
    public TravelToolPort.Evidence query(
            UUID runId, TravelToolPort.Operation operation, Map<String, String> arguments) {
        if (!enabled) throw new IllegalStateException("TOOLS_DISABLED");
        safety.require("TOOL_INPUT", arguments.toString());
        try (var span =
                trace.open(
                        runId,
                        com.lawrence.travel.application.port.ExecutionTrace.Kind.TOOL,
                        Map.of(
                                "schema",
                                operation.name(),
                                "sourceHash",
                                com.lawrence.travel.application.memory.AgentContext.hash(
                                        new java.util.TreeMap<>(arguments).toString())))) {
            try {
                String group =
                        switch (operation) {
                            case PLACE -> "amap-search";
                            case ROUTE -> "amap-lbs";
                            case WEATHER -> "amap-weather";
                            case SEARCH, EXTRACT -> "tavily";
                        };
                // 先提交调用记录；执行次数与耗时仍由原运行预算约束。
                UUID id = journal.reserve(runId, group);
                long started = System.nanoTime();
                BudgetLedger.Reservation meter = null;
                boolean completed = false;
                try {
                    meter =
                            ledger.reserve(
                                    runId,
                                    CallKind.TOOL,
                                    limits,
                                    new BudgetLedger.Target(
                                            operation.name(), group, "TOOL_REALTIME"),
                                    0,
                                    clock.instant());
                    var result = tools.query(operation, Map.copyOf(arguments));
                    safety.require("TOOL_OUTPUT", result.toString());
                    journal.finish(id, result);
                    completed = true;
                    return result;
                } finally {
                    // 未完成证据时由仓储将 RESERVED 转为 UNKNOWN；已完成记录不被覆盖。
                    try {
                        journal.finish(id, null);
                    } finally {
                        if (meter != null)
                            ledger.finish(
                                    meter.id(),
                                    // 工具不消耗模型Token；已保存响应记0，未完成仍记未知。
                                    completed ? new TokenUsage(0, 0) : null,
                                    Duration.ofNanos(System.nanoTime() - started));
                    }
                }
            } catch (RuntimeException failure) {
                span.fail(
                        Thread.currentThread().isInterrupted()
                                ? com.lawrence.travel.application.port.ExecutionTrace.Failure
                                        .CANCELLED
                                : com.lawrence.travel.application.port.ExecutionTrace.Failure
                                        .FAILED);
                throw failure;
            }
        }
    }

    /**
     * 兼容旧配置的历史免费资源记录；当前调用链不再读取这些字段作准入判断。
     *
     * @param verifiedAt 核实时间
     * @param validUntil 不晚于重置时间的保守截止
     * @param allowance 可预占次数或 credits
     * @param freeOnlyConfirmed 已确认无自动付费
     */
    public record Grant(
            Instant verifiedAt, Instant validUntil, long allowance, boolean freeOnlyConfirmed) {}
}
