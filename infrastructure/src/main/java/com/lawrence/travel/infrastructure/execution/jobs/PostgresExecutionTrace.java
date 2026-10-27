package com.lawrence.travel.infrastructure.execution.jobs;

import com.lawrence.travel.application.execution.ExecutionCorrelation;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.ExecutionTrace;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.util.Map;
import java.util.UUID;

/**
 * 保存执行节点、固定失败分类和关联计量。
 */
public final class PostgresExecutionTrace implements ExecutionTrace {
    private final DiagnosticMapper mapper;
    private final QuotaMapper ownership;
    private final BusinessTransactions tx;
    private final TravelJson json;

    /**
     * @param mapper 诊断
     * @param ownership 原预算归属
     * @param tx 短事务
     * @param json 固定元数据
     */
    public PostgresExecutionTrace(
            DiagnosticMapper mapper,
            QuotaMapper ownership,
            BusinessTransactions tx,
            TravelJson json) {
        this.mapper = mapper;
        this.ownership = ownership;
        this.tx = tx;
        this.json = json;
    }

    /** {@inheritDoc} 所有ID来自服务端，陌生键/自由文本拒绝；不向模型返回诊断授权。 */
    @Override
    public Span open(UUID budget, Kind kind, Map<String, String> metadata) {
        if (budget == null || kind == null || metadata == null || metadata.size() > 12)
            throw new IllegalArgumentException("TRACE_METADATA_INVALID");
        for (var entry : metadata.entrySet())
            if (!java.util.Set.of(
                                    "stage",
                                    "role",
                                    "generation",
                                    "revision",
                                    "schema",
                                    "sourceHash",
                                    "sourceCount",
                                    "preferenceRevision",
                                    "round",
                                    "budgetId")
                            .contains(entry.getKey())
                    || entry.getValue() == null
                    || !entry.getValue().matches("[A-Za-z0-9_-]{1,80}"))
                throw new IllegalArgumentException("TRACE_METADATA_INVALID");
        UUID id = UUID.randomUUID();
        var owner =
                tx.required(
                        () -> {
                            var o = ownership.operation(budget);
                            if (o == null) o = ownership.run(budget);
                            if (o == null) throw new IllegalStateException("TRACE_OWNER_MISSING");
                            mapper.span(
                                    id,
                                    o.traceId(),
                                    ExecutionCorrelation.traceParent() == null
                                            ? o.parentSpanId()
                                            : ExecutionCorrelation.traceParent(),
                                    o.userId(),
                                    o.requestId(),
                                    o.chatId(),
                                    o.operationId(),
                                    o.runId(),
                                    o.jobId(),
                                    null,
                                    kind.name());
                            var data = new java.util.LinkedHashMap<>(metadata);
                            data.put("budgetId", budget.toString());
                            mapper.metadata(id, json.encode(data));
                            return o;
                        });
        var binding = ExecutionCorrelation.bindTrace(id);
        return new Span() {
            private Failure failure;
            private boolean closed;

            /** {@inheritDoc} 首个失败原因不可被后续成功覆盖。 */
            @Override
            public void fail(Failure value) {
                if (failure == null) failure = value;
            }

            /** {@inheritDoc} 独立结束事实节点，主提交回滚也保留失败；恢复线程诊断父级。 */
            @Override
            public void close() {
                if (closed) return;
                closed = true;
                try {
                    tx.required(
                            () -> {
                                String state = failure == null ? "COMPLETED" : failure.name();
                                mapper.end(
                                        id,
                                        state,
                                        failure == null ? "" : "TRACE_" + failure.name());
                                mapper.meterSummary(id);
                                if (failure != null)
                                    mapper.problem(
                                            UUID.randomUUID(),
                                            owner.userId(),
                                            owner.traceId(),
                                            id,
                                            owner.requestId(),
                                            "AGENT",
                                            "TRACE_" + failure.name());
                                return null;
                            });
                } finally {
                    binding.close();
                }
            }
        };
    }
}
