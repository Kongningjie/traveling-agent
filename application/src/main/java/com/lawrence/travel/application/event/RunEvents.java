package com.lawrence.travel.application.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 用户可见只读契约；序号以十进制文本传输，避免浏览器整数精度丢失。 */
public final class RunEvents {
    /** 仅作命名空间，禁止实例化。 */
    private RunEvents() {}

    /**
     * @param taskId 任务
     * @param constraintRevision 当前需求
     * @param generation 当前代次
     * @param currentVersionId 生效版本
     * @param runId 当前运行
     * @param state 当前运行状态，可空
     * @param pendingInput 是否有未决修改
     * @param cursor 与本视图一致的事件水位
     * @param requirements 当前需求
     * @param itinerary 生效快照，可空
     */
    public record View(
            UUID taskId,
            long constraintRevision,
            long generation,
            UUID currentVersionId,
            UUID runId,
            String state,
            boolean pendingInput,
            String cursor,
            Map<?, ?> requirements,
            Map<?, ?> itinerary) {}

    /**
     * @param id 任务与序号复合游标
     * @param sequence 十进制序号
     * @param runId 原运行
     * @param generation 原执行代次
     * @param stepId 原阶段，可空
     * @param type 安全事件类型
     * @param sourceType 固定业务动作
     * @param state 提交时状态，历史事件可空
     * @param createdAt 数据库时间
     */
    public record Event(
            String id,
            String sequence,
            UUID runId,
            long generation,
            UUID stepId,
            String type,
            String sourceType,
            String state,
            Instant createdAt) {}

    /**
     * @param events 严格升序事件
     * @param nextCursor 最后事件或原游标；不能跳到当前任务头
     * @param hasMore 是否还有已提交事件
     */
    public record Page(List<Event> events, String nextCursor, boolean hasMore) {
        /** 防止调用者修改读取结果。 */
        public Page {
            events = List.copyOf(events);
        }
    }
}
