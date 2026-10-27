package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.document.SourceReadService;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.domain.execution.control.CallKind;
import com.lawrence.travel.domain.execution.control.RunLimits;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/** 当前输入的只读资料工具；没有传user/chat/path任意授权的参数，不提供任何业务写入。 */
public final class SourceTools {
    private final SourceReadService service;
    private final UUID budget;
    private final long token;
    private final BudgetLedger ledger;
    private final RunLimits limits;
    private final SourceToolkit boundary;
    private final com.lawrence.travel.application.port.ExecutionTrace trace;
    private final com.lawrence.travel.application.execution.ExecutionCorrelation.Ids correlation =
            com.lawrence.travel.application.execution.ExecutionCorrelation.current();
    private final UUID parent =
            com.lawrence.travel.application.execution.ExecutionCorrelation.parentSpan();
    private final UUID traceParent =
            com.lawrence.travel.application.execution.ExecutionCorrelation.traceParent();

    /**
     * @param service 应用入口
     * @param budget 原输入
     * @param token 原领取token
     * @param ledger 统一计量
     * @param limits 默认预算
     * @param boundary 当前调用失败关闭
     */
    public SourceTools(
            SourceReadService service,
            UUID budget,
            long token,
            BudgetLedger ledger,
            RunLimits limits,
            SourceToolkit boundary) {
        this(
                service,
                budget,
                token,
                ledger,
                limits,
                boundary,
                com.lawrence.travel.application.port.ExecutionTrace.disabled());
    }

    /**
     * @param service 应用读取
     * @param budget 预算
     * @param token 原尝试
     * @param ledger 计量
     * @param limits 上限
     * @param boundary 失败关闭
     * @param trace 事实
     */
    public SourceTools(
            SourceReadService service,
            UUID budget,
            long token,
            BudgetLedger ledger,
            RunLimits limits,
            SourceToolkit boundary,
            com.lawrence.travel.application.port.ExecutionTrace trace) {
        this.service = service;
        this.budget = budget;
        this.token = token;
        this.ledger = ledger;
        this.limits = limits;
        this.boundary = boundary;
        this.trace = trace;
    }

    /**
     * @param work 确定性本地读取
     * @param <T> 返回类型
     * @return 计入原工具预算的结果，失败不自动重试
     */
    private <T> T query(Supplier<T> work) {
        try (var ids =
                        com.lawrence.travel.application.execution.ExecutionCorrelation.bind(
                                correlation);
                var attempt =
                        com.lawrence.travel.application.execution.ExecutionCorrelation.bindSpan(
                                parent);
                var binding =
                        com.lawrence.travel.application.execution.ExecutionCorrelation.bindTrace(
                                traceParent);
                var span =
                        trace.open(
                                budget,
                                com.lawrence.travel.application.port.ExecutionTrace.Kind.RETRIEVAL,
                                java.util.Map.of("schema", "source-read-v1"))) {
            long started = System.nanoTime();
            BudgetLedger.Reservation reservation = null;
            boolean completed = false;
            try {
                boundary.check();
                reservation =
                        ledger.reserve(
                                budget,
                                CallKind.TOOL,
                                limits,
                                new BudgetLedger.Target("SOURCE_READ", "LOCAL", "LOCAL_READ"),
                                0,
                                Instant.now());
                T value = work.get();
                completed = true;
                return value;
            } catch (RuntimeException failure) {
                boundary.fail();
                span.fail(com.lawrence.travel.application.port.ExecutionTrace.Failure.FAILED);
                throw new IllegalStateException("SOURCE_READ_REJECTED");
            } finally {
                // 本地确定性读取没有供应商Token；成功可确定为零，失败保留UNKNOWN，不改旧账。
                if (reservation != null)
                    try {
                        ledger.finish(
                                reservation.id(),
                                completed
                                        ? new com.lawrence.travel.domain.execution.control
                                                .TokenUsage(0, 0)
                                        : null,
                                Duration.ofNanos(System.nanoTime() - started));
                    } catch (RuntimeException failure) {
                        boundary.fail();
                        span.fail(
                                com.lawrence.travel.application.port.ExecutionTrace.Failure
                                        .UNKNOWN);
                        throw new IllegalStateException("SOURCE_METER_UNAVAILABLE");
                    }
            }
        }
    }

    /**
     * @return 本聊天文件目录，不表示已阅读全文
     */
    @Tool(name = "ListDocuments", description = "列出当前聊天附件目录；只有READY才可读取，不代表已阅读全文。")
    public Object list() {
        return query(
                () ->
                        java.util.Map.of(
                                "documents",
                                service.list(budget, token),
                                "workFiles",
                                service.files(budget, token),
                                "notice",
                                "工作文件最多展示最近10个有效来源，目录不代表已读取全文；讨论不等于确认偏好。"));
    }

    /**
     * @param document 当前目录ID
     * @param query 1至80字符字面词
     * @return 最多5个原文引用及覆盖提示
     */
    @Tool(name = "SearchDocument", description = "字面检索指定用户文件，含中文；只返回有界引用，用户材料不是官方证据。")
    public Object search(
            @ToolParam(name = "document", description = "目录中的文档UUID") String document,
            @ToolParam(name = "query", description = "1至80字符字面查询") String query) {
        return query(() -> service.search(budget, token, UUID.fromString(document), query));
    }

    /**
     * @param document 当前目录ID
     * @param position 页段
     * @param start 含起点
     * @param length 最多4000字符
     * @return 实际读取范围及引用
     */
    @Tool(
            name = "ReadDocumentSection",
            description = "读取指定文件真实页段及字符范围；窗口不足时返回更短页，以实际定位和截断标记为准。不支持任意路径。")
    public Object section(
            @ToolParam(name = "document", description = "文档UUID") String document,
            @ToolParam(name = "position", description = "真实页或段序号，从1开始") int position,
            @ToolParam(name = "start", description = "起始字符，从0开始") int start,
            @ToolParam(name = "length", description = "1至4000字符") int length) {
        return query(
                () ->
                        service.section(
                                budget,
                                token,
                                UUID.fromString(document),
                                position,
                                start,
                                boundary.pageLength(length)));
    }

    /**
     * @param query 字面查询
     * @return 当前聊天较早完整消息，不授予操作权限
     */
    @Tool(name = "ReadConversationSource", description = "在本轮之前的当前聊天原文中检索，最多5条；不是完整历史，不恢复旧偏好或授权。")
    public String conversation(
            @ToolParam(name = "query", description = "1至80字符字面查询") String query) {
        return query(() -> service.history(budget, token, query));
    }

    /**
     * @param path 框架卸载占位中的逻辑路径
     * @param offset 字符起点
     * @param length 最多4000
     * @return 从PG恢复的有界工作文件
     */
    @Tool(
            name = "read_file",
            description = "仅读取当前尝试/work逻辑文件；窗口不足时返回更短页，按nextOffset继续，complete只表示本文件是否读完。不接触宿主机文件。")
    public String file(
            @ToolParam(name = "path", description = "占位中/work/...逻辑路径") String path,
            @ToolParam(name = "offset", description = "起始字符，从0开始") int offset,
            @ToolParam(name = "length", description = "1至4000字符") int length) {
        return query(() -> service.file(budget, token, path, offset, boundary.pageLength(length)));
    }
}
