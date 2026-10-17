package com.lawrence.travel.infrastructure.event;

import com.lawrence.travel.domain.travel.TaskScope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 单条快照查询与有界补读 SQL；不读取模型推理、原始工具产物或会话内容。 */
public interface EventMapper {
    /**
     * @param id 任务
     * @param constraintRevision 需求
     * @param executionGeneration 代次
     * @param currentVersionId 生效版本
     * @param currentRunId 当前运行
     * @param state 状态
     * @param pendingInput 待解析输入
     * @param eventSequence 事务游标
     * @param requirements 需求 JSON
     * @param itinerary 快照 JSON，可空
     */
    record ViewRow(UUID id, long constraintRevision, long executionGeneration, UUID currentVersionId, UUID currentRunId,
                   String state, boolean pendingInput, long eventSequence, String requirements, String itinerary) { }
    /**
     * @param sequence 任务顺序
     * @param runId 原运行
     * @param generation 原代次
     * @param stepId 原阶段
     * @param type 固定动作
     * @param state 当时状态
     * @param createdAt 提交事件时间
     */
    record EventRow(long sequence, UUID runId, long generation, UUID stepId, String type, String state, Instant createdAt) { }
    /**
     * @param scope 私有归属
     * @return 单个 MVCC 语句内的一致视图，不能拆成两次 READ COMMITTED 查询
     */
    @Select("""
            SELECT t.id,t.constraint_revision,t.execution_generation,t.current_version_id,t.current_run_id,
            r.body->>'state' AS state,(t.pending_inputs<>'{}'::jsonb) AS pending_input,t.event_sequence,
            c.payload::text AS requirements,s.payload::text AS itinerary
            FROM travel_task t JOIN travel_constraint_revision c ON c.user_id=t.user_id AND c.task_id=t.id AND c.revision=t.constraint_revision
            LEFT JOIN execution_run r ON r.user_id=t.user_id AND r.task_id=t.id AND r.id=t.current_run_id
            LEFT JOIN itinerary_snapshot s ON s.user_id=t.user_id AND s.task_id=t.id AND s.id=t.current_version_id
            WHERE t.user_id=#{scope.userId} AND t.id=#{scope.taskId}
            """)
    ViewRow view(@Param("scope") TaskScope scope);
    /**
     * @param scope 私有归属
     * @return 轻量水位或空；高频订阅不得反复加载快照和需求大正文
     */
    @Select("SELECT event_sequence FROM travel_task WHERE user_id=#{scope.userId} AND id=#{scope.taskId}")
    Long head(@Param("scope") TaskScope scope);
    /**
     * @param scope 私有归属
     * @param after 精确水位
     * @param limit 含额外一条的有界大小
     * @return 只读取已提交记录，事务计数器保证序号与提交顺序一致
     */
    @Select("""
            SELECT sequence,run_id,generation,step_id,type,payload->>'state' AS state,created_at
            FROM run_event WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND sequence>#{after}
            ORDER BY sequence LIMIT #{limit}
            """)
    List<EventRow> page(@Param("scope") TaskScope scope, @Param("after") long after, @Param("limit") int limit);
}
