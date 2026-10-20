package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.document.SourceReadService;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.domain.execution.control.RunLimits;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.middleware.ToolResultEvictionMiddleware;
import java.lang.reflect.Proxy;
import java.util.UUID;

/** 每次调用隔离的逻辑工作文件；复用原生卸载，正文先写PG，文件系统没有宿主路径或shell能力。 */
public final class SourceToolkit extends SafetyToolkit {
    private final SourceReadService service;
    private final UUID budget;
    private final long token;
    private volatile boolean failed;
    private volatile long remainingBytes = Long.MAX_VALUE;

    /** @param remaining 最后一次完整SDK请求在原上限内的剩余量；不是供应商计费用量 */
    void contextHeadroom(long remaining) { remainingBytes = Math.max(0, remaining); }

    /** @param requested 工具请求字符数 @return 随发送窗口缩小的页长；非法输入仍交原服务拒绝，不擅自修正 */
    int pageLength(int requested) {
        if (requested < 1 || requested > 4000) return requested;
        // 给新tool_call及结果JSON至少预留1024字节，单UTF-16字符按两层JSON转义12字节保守估算。
        // 最小64字符只是读取粒度，不是放行保证；最终SDK完整正文仍必须通过原发送门禁。
        long available = remainingBytes == Long.MAX_VALUE ? 4000 : Math.max(64, (remainingBytes - 1024) / 12);
        return (int) Math.min(requested, available);
    }

    /** @param service 权威读取 @param budget 输入预算 @param token 原领取身份 @param ledger 同一工具计量 @param limits 初始限制 */
    public SourceToolkit(SourceReadService service, UUID budget, long token, BudgetLedger ledger, RunLimits limits) {
        this(service,budget,token,ledger,limits,com.lawrence.travel.application.port.ExecutionTrace.disabled());
    }
    /** @param service 权威资料 @param budget 原预算 @param token 原尝试 @param ledger 计量 @param limits 上限 @param trace 事实轨迹 */
    public SourceToolkit(SourceReadService service, UUID budget, long token, BudgetLedger ledger, RunLimits limits, com.lawrence.travel.application.port.ExecutionTrace trace) {
        this.service = service; this.budget = budget; this.token = token;
        registerTool(new SourceTools(service, budget, token, ledger, limits, this, trace));
    }

    /** 失败不可由模型或框架吞错后清除。 */
    public void fail() { failed = true; }
    /** {@inheritDoc} 包括卸载失败及资料失效，阻止携带过大原文继续发送。 */
    @Override public void check() { super.check(); if (failed) throw new IllegalStateException("SOURCE_READ_REJECTED"); }

    /** @return 只开放write的AbstractFilesystem适配；原生占位read_file由受控工具按逻辑键分页读取 */
    public ToolResultEvictionMiddleware eviction() {
        return eviction(6000, 160);
    }

    /** @return 原生卸载加发送前超限兜底；不会重复执行资料工具或已发送的模型请求 */
    public SourceResultBudgetMiddleware budgetedEviction() { return new SourceResultBudgetMiddleware(this); }

    /** @param threshold 原生字符阈值 @param preview 首尾预览字符数 @return 仍先写PG再替换的原生组件 */
    ToolResultEvictionMiddleware eviction(int threshold, int preview) {
        var filesystem = (AbstractFilesystem) Proxy.newProxyInstance(AbstractFilesystem.class.getClassLoader(), new Class<?>[]{AbstractFilesystem.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("write")) {
                        try { return WriteResult.ok(service.offload(budget, token, (String) args[1], (String) args[2])); }
                        catch (RuntimeException failure) { fail(); return WriteResult.fail("SOURCE_OFFLOAD_REJECTED"); }
                    }
                    // 此适配不是通用文件系统；工具只能调用应用读取端口，不能借默认方法访问OS。
                    throw new UnsupportedOperationException("SOURCE_FILESYSTEM_OPERATION_DENIED");
                });
        return new ToolResultEvictionMiddleware(filesystem, ToolResultEvictionConfig.builder()
                .evictionPath("/work").maxResultChars(threshold).previewChars(preview)
                .excludedToolNames(java.util.Set.of("read_file", "ReadDocumentSection")).build());
    }
}
