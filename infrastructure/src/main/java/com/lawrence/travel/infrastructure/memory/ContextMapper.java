package com.lawrence.travel.infrastructure.memory;

import com.lawrence.travel.domain.travel.TaskScope;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 上下文私有SQL；读写统一先用户共享锁、再任务共享锁，不持锁调用模型。 */
public interface ContextMapper {
    /** @param userId 服务端用户 @param taskId 私有任务 @param body 运行权威状态 */
    record RunRow(UUID userId, UUID taskId, String body) { }
    /** @param run 受信运行 @return 归属，不能从浏览器传入userId */
    @Select("SELECT user_id,task_id,body::text FROM execution_run WHERE id=#{run}") RunRow run(@Param("run") UUID run);
    /** @param scope 服务端归属 @return 用户稳定版本，锁序第一 */
    @Select("SELECT preference_revision FROM app_user WHERE id=#{scope.userId} AND account_status='ACTIVE' FOR SHARE") Long user(@Param("scope") TaskScope scope);
    /** @param revision 需求 @param generation 代次 @param watermark 消息水位 @param currentVersionId 生效指针 @param currentRunId 当前执行 */
    record TaskRow(long revision, long generation, long watermark, UUID currentVersionId, UUID currentRunId) { }
    /** @param scope 私有任务 @return 锁定后读取的任务水位 */
    @Select("SELECT constraint_revision AS revision,execution_generation AS generation,message_sequence AS watermark,current_version_id,current_run_id FROM travel_task WHERE user_id=#{scope.userId} AND id=#{scope.taskId} FOR SHARE") TaskRow task(@Param("scope") TaskScope scope);
    /** @param scope 已锁任务 @return 原始未决输入身份和状态 */
    @Select("SELECT pending_inputs::text FROM travel_task WHERE user_id=#{scope.userId} AND id=#{scope.taskId}") String pending(@Param("scope") TaskScope scope);
    /** @param scope 私有任务 @param revision 精确版本 @return 不可变权威需求 */
    @Select("SELECT payload::text FROM travel_constraint_revision WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND revision=#{revision}") String requirements(@Param("scope") TaskScope scope, @Param("revision") long revision);
    /** @param scope 私有任务 @return 当前生效版本及最新候选元信息，非规划自评 */
    @Select("""
        SELECT jsonb_build_object('current',s.metadata,'candidate',(
            SELECT jsonb_build_object('id',c.id,'metadata',cs.metadata,'state',c.state)
            FROM itinerary_candidate c JOIN itinerary_snapshot cs ON cs.id=c.snapshot_id AND cs.user_id=c.user_id AND cs.task_id=c.task_id
            WHERE c.user_id=t.user_id AND c.task_id=t.id AND c.run_id=t.current_run_id ORDER BY c.created_at DESC,c.id DESC LIMIT 1))::text
        FROM travel_task t LEFT JOIN itinerary_snapshot s ON s.user_id=t.user_id AND s.task_id=t.id AND s.id=t.current_version_id
        WHERE t.user_id=#{scope.userId} AND t.id=#{scope.taskId}
        """) String versions(@Param("scope") TaskScope scope);
    /** @param scope 私有任务 @param run 当前运行 @return 未决卡片原结构，推荐不等于采纳 */
    @Select("SELECT body::text FROM decision_request WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND run_id=#{run} AND body->>'state'='OPEN' ORDER BY id") List<String> questions(@Param("scope") TaskScope scope, @Param("run") UUID run);
    /** @param id 原消息 @param sequence 顺序 @param body 原始正文 @param answer 原回答或提升后的原回答 @param status 明确处理状态 @param preferenceRevision 接收时偏好版本 */
    record MessageRow(UUID id, long sequence, String body, String answer, String status, long preferenceRevision) { }
    /** @param scope 私有任务 @param preference 当前偏好版本 @return 同版本最近50条按原顺序；其他历史只按范围引用，不自动注入 */
    @Select("SELECT id,sequence,body::text,COALESCE(answer,original_answer)::text AS answer,status,preference_revision FROM (SELECT * FROM conversation_message WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND preference_revision=#{preference} ORDER BY sequence DESC LIMIT 50) m ORDER BY sequence") List<MessageRow> messages(@Param("scope") TaskScope scope, @Param("preference") long preference);
    /** @param scope 私有任务 @return 所有明确回答的引用和语义，原始长文本只在当前偏好版本召回 */
    @Select("SELECT id,sequence,body::text,COALESCE(answer,original_answer)::text AS answer,status,preference_revision FROM conversation_message WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND COALESCE(answer,original_answer) IS NOT NULL ORDER BY sequence") List<MessageRow> decisions(@Param("scope") TaskScope scope);
    /** @param scope 私有归属 @return 仅确认偏好，仍需应用当前字段及城市过滤 */
    @Select("SELECT body::text FROM user_preference WHERE user_id=#{scope.userId} AND state='CONFIRMED' ORDER BY category,city,id") List<String> preferences(@Param("scope") TaskScope scope);
    /** @param scope 私有任务 @return 精确引用和有效截止；正文及完整工具响应不进入主上下文 */
    @Select("""
        SELECT jsonb_build_object('reference',e.body->'reference','expiresAt',e.body->'expiresAt','status',e.body->'status','fresh',CAST(e.body->>'expiresAt' AS timestamptz)>clock_timestamp(),
          'notices',(SELECT jsonb_agg(n.reason ORDER BY n.reason) FROM evidence_notice n WHERE n.user_id=e.user_id AND n.task_id=e.task_id AND n.evidence_id=e.id))::text
        FROM field_evidence e WHERE e.user_id=#{scope.userId} AND e.task_id=#{scope.taskId}
          AND e.body->'reference' IN (SELECT ref FROM itinerary_snapshot s JOIN travel_task t ON t.user_id=s.user_id AND t.id=s.task_id
            CROSS JOIN LATERAL jsonb_array_elements(s.metadata->'evidenceRefs') ref
            WHERE s.user_id=#{scope.userId} AND s.task_id=#{scope.taskId} AND (s.id=t.current_version_id OR s.id=(
              SELECT c.snapshot_id FROM itinerary_candidate c WHERE c.user_id=t.user_id AND c.task_id=t.id AND c.run_id=t.current_run_id ORDER BY c.created_at DESC,c.id DESC LIMIT 1))) ORDER BY e.id
        """) List<String> evidence(@Param("scope") TaskScope scope);
    /** @param source 精确绑定 @return 缓存摘要，历史版本不自动恢复 */
    @Select("SELECT body::text FROM conversation_summary WHERE user_id=#{source.scope.userId} AND task_id=#{source.scope.taskId} AND run_id=#{source.runId} AND role=#{source.role} AND binding_hash=#{source.bindingHash} AND state='VALID'") String cached(@Param("source") com.lawrence.travel.application.memory.AgentContext.Source source);
    /** @param source 当前绑定 @return 旧绑定失效数；不删除审计版本 */
    @Update("UPDATE conversation_summary SET state='INVALID' WHERE user_id=#{source.scope.userId} AND task_id=#{source.scope.taskId} AND run_id=#{source.runId} AND role=#{source.role} AND binding_hash<>#{source.bindingHash} AND state='VALID'") int invalidate(@Param("source") com.lawrence.travel.application.memory.AgentContext.Source source);
    /** @param source 锁内核对来源 @param summary 已校验摘要 @param body 正文 @return 同绑定并发只保留首次结果 */
    @Insert("INSERT INTO conversation_summary(id,user_id,task_id,run_id,role,binding_hash,body) VALUES(#{summary.id},#{source.scope.userId},#{source.scope.taskId},#{source.runId},#{source.role},#{source.bindingHash},CAST(#{body} AS jsonb)) ON CONFLICT(user_id,task_id,run_id,role,binding_hash) DO NOTHING") int save(@Param("source") com.lawrence.travel.application.memory.AgentContext.Source source, @Param("summary") com.lawrence.travel.application.memory.AgentContext.Summary summary, @Param("body") String body);
    /** @param scope 私有任务 @param id 原消息 @param preference 当前偏好版本 @return 完整正文按需读取；旧偏好版本拒绝召回 */
    @Select("SELECT body::text FROM conversation_message WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND id=#{id} AND preference_revision=#{preference}") String original(@Param("scope") TaskScope scope, @Param("id") UUID id, @Param("preference") long preference);
}
