package com.lawrence.travel.infrastructure.itinerary;

import com.lawrence.travel.domain.travel.TaskScope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 版本只读SQL；用户、任务和接受事实均在数据库限定。 */
@Mapper
public interface VersionMapper {
    /** @param metadata 精确版本元信息 @param createdAt 数据库时间 @param accepted 是否存在永久接受事实 */
    record Row(String metadata, Instant createdAt, boolean accepted) { }
    /** @param scope 归属 @param offset 分页 @param limit 页大小 @return 有界元信息，不查询大正文 */
    @Select("SELECT s.metadata::text,s.created_at,EXISTS(SELECT 1 FROM itinerary_acceptance a WHERE a.user_id=s.user_id AND a.task_id=s.task_id AND a.snapshot_id=s.id) AS accepted FROM itinerary_snapshot s WHERE s.user_id=#{scope.userId} AND s.task_id=#{scope.taskId} ORDER BY s.created_at DESC,s.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Row> list(@Param("scope") TaskScope scope, @Param("offset") int offset, @Param("limit") int limit);
    /** @param scope 归属 @param id 快照 @return 该任务元信息或空 */
    @Select("SELECT s.metadata::text,s.created_at,EXISTS(SELECT 1 FROM itinerary_acceptance a WHERE a.user_id=s.user_id AND a.task_id=s.task_id AND a.snapshot_id=s.id) AS accepted FROM itinerary_snapshot s WHERE s.user_id=#{scope.userId} AND s.task_id=#{scope.taskId} AND s.id=#{id}")
    Row row(@Param("scope") TaskScope scope, @Param("id") UUID id);
    /** @param scope 私有归属 @param id 快照 @return 该版本形成前已收到且依赖需求版本更早的同运行输入，最多100条，不使用模型解释代替原文 */
    @Select("SELECT (m.body || jsonb_build_object('state',m.status))::text FROM conversation_message m JOIN itinerary_candidate c ON c.user_id=m.user_id AND c.task_id=m.task_id AND c.run_id=m.processed_run_id JOIN itinerary_snapshot v ON v.user_id=c.user_id AND v.task_id=c.task_id AND v.id=c.snapshot_id WHERE c.user_id=#{scope.userId} AND c.task_id=#{scope.taskId} AND c.snapshot_id=#{id} AND (m.body->>'constraintRevision')::bigint<v.constraint_revision AND m.created_at<=v.created_at ORDER BY m.sequence LIMIT 100")
    List<String> sources(@Param("scope") TaskScope scope, @Param("id") UUID id);
    /** @param scope 私有归属 @param id 快照 @return 实际规划阶段定义，历史数据无阶段为空 */
    @Select("SELECT s.definition::text FROM itinerary_candidate c JOIN execution_step s ON s.user_id=c.user_id AND s.task_id=c.task_id AND s.run_id=c.run_id AND s.id=c.plan_step_id WHERE c.user_id=#{scope.userId} AND c.task_id=#{scope.taskId} AND c.snapshot_id=#{id}")
    String definition(@Param("scope") TaskScope scope, @Param("id") UUID id);

}
