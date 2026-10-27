package com.lawrence.travel.infrastructure.event;

import com.lawrence.travel.application.event.RunEvents;
import com.lawrence.travel.application.port.RunEventStore;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;

/** PostgreSQL 为事件事实源；不用内存消息或通知替代持久补读。 */
public final class PostgresRunEventStore implements RunEventStore {
    private final EventMapper mapper;
    private final TravelJson json;

    /**
     * @param mapper 私有 SQL
     * @param json 规范 JSON
     */
    public PostgresRunEventStore(EventMapper mapper, TravelJson json) {
        this.mapper = mapper;
        this.json = json;
    }

    /**
     * {@inheritDoc} 一条 SQL 同时获取快照与任务游标。
     */
    @Override
    public RunEvents.View view(TaskScope scope) {
        return safe(
                () -> {
                    var row = mapper.view(scope);
                    if (row == null)
                        throw new TravelStorageFailure(TravelStorageFailure.Code.NOT_FOUND);
                    return new RunEvents.View(
                            row.id(),
                            row.constraintRevision(),
                            row.executionGeneration(),
                            row.currentVersionId(),
                            row.currentRunId(),
                            row.state(),
                            row.pendingInput(),
                            scope.taskId() + ":" + row.eventSequence(),
                            object(row.requirements()),
                            object(row.itinerary()));
                });
    }

    /**
     * {@inheritDoc} 多读一条判断 hasMore，返回水位只采用实际交付的最后一条。
     */
    @Override
    public RunEvents.Page page(TaskScope scope, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 100)
            throw new TravelStorageFailure(TravelStorageFailure.Code.INVALID_INPUT);
        return safe(
                () -> {
                    var head = mapper.head(scope);
                    if (head == null)
                        throw new TravelStorageFailure(TravelStorageFailure.Code.NOT_FOUND);
                    if (after > head)
                        throw new TravelStorageFailure(TravelStorageFailure.Code.STALE_VERSION);
                    var rows = mapper.page(scope, after, limit + 1);
                    List<RunEvents.Event> events =
                            rows.stream()
                                    .limit(limit)
                                    .map(
                                            row ->
                                                    new RunEvents.Event(
                                                            scope.taskId() + ":" + row.sequence(),
                                                            Long.toString(row.sequence()),
                                                            row.runId(),
                                                            row.generation(),
                                                            row.stepId(),
                                                            category(row),
                                                            row.type(),
                                                            row.state(),
                                                            row.createdAt()))
                                    .toList();
                    return new RunEvents.Page(
                            events,
                            events.isEmpty() ? scope.taskId() + ":" + after : events.getLast().id(),
                            rows.size() > limit);
                });
    }

    /**
     * @param row 固定业务事件
     * @return 用户可见类别，旧动作仍保留 sourceType 供诊断
     */
    private static String category(EventMapper.EventRow row) {
        if ("WAITING_ACCEPTANCE".equals(row.state())) return "CANDIDATE_READY";
        if (row.type().contains("PAUSED")) return "BUDGET_UPDATE";
        if (row.type().contains("FAILED")) return "RUN_ERROR";
        if ("WAITING_USER".equals(row.state())) return "DECISION_REQUIRED";
        if (row.type().startsWith("STAGE")) return "STAGE_PROGRESS";
        if (row.type().contains("WAITING_USER")) return "DECISION_REQUIRED";
        return "RUN_STATUS";
    }

    /**
     * @param text 可空 JSON
     * @return 只读传输结构，不包含任意日志正文
     */
    private Map<?, ?> object(String text) {
        return text == null ? null : json.decode(text, Map.class);
    }

    /**
     * @param work 有界读取
     * @param <T> 结果类型
     * @return 固定错误，不传播 SQL 和用户正文
     */
    private static <T> T safe(Supplier<T> work) {
        try {
            return work.get();
        } catch (DataAccessException failure) {
            throw new TravelStorageFailure(TravelStorageFailure.Code.DEPENDENCY_UNAVAILABLE);
        }
    }
}
