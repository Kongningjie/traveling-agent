package com.lawrence.travel.infrastructure.memory;

import com.lawrence.travel.application.memory.SessionContext;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 会话上下文SQL；参数为服务器预算归属，不暴露任意用户或任意SQL给Agent。 */
public interface SessionContextMapper {
    /** @param budget 原旅行 @param attempt 原阶段尝试 @return 仍处于同一有效租约，不能采用其他活跃阶段的资格 */
    @Select("SELECT EXISTS(SELECT 1 FROM step_attempt a JOIN outbox_event e ON e.id=a.event_id WHERE a.run_id=#{budget} AND a.id=#{attempt} AND a.state='RUNNING' AND e.status='LEASED' AND e.fencing_token=a.fencing_token AND e.lease_until>clock_timestamp())")
    boolean stageLive(@Param("budget") UUID budget,@Param("attempt") UUID attempt);

    /** @param userId 用户 @param chatId 独立聊天 @param taskId 可空旅行 @param inputId 当前输入 @param sequence 截止顺序 @param token 原尝试 @param epoch 资料水位 @param live 是否仍有执行资格 */
    record Operation(UUID userId,UUID chatId,UUID taskId,UUID inputId,long sequence,long token,long epoch,boolean live) { }
    /** @param budget 原输入预算 @return 作业和输入的真实归属，结束作业也保留归属但live=false */
    @Select("SELECT o.user_id,o.chat_id,c.task_id,i.id AS input_id,i.sequence,j.token,c.work_revision AS epoch,COALESCE(j.state='RUNNING' AND j.lease_until>clock_timestamp() AND j.source_version=c.work_revision,false) AS live FROM operation_context o JOIN dialogue_chat c ON c.id=o.chat_id JOIN dialogue_input i ON i.job_id IN(SELECT id FROM background_job WHERE operation_id=o.id AND kind='CHAT_INPUT') JOIN background_job j ON j.id=i.job_id WHERE o.budget_id=#{budget}")
    Operation operation(@Param("budget") UUID budget);
    /** @param user 用户 @return 偏好版本，所有读取先user再chat／task */
    @Select("SELECT preference_revision FROM app_user WHERE id=#{user} AND account_status='ACTIVE' FOR SHARE")
    Long preference(@Param("user") UUID user);
    /** @param user 用户 @param chat 聊天 @return 当前资料水位 */
    @Select("SELECT work_revision FROM dialogue_chat WHERE user_id=#{user} AND id=#{chat} FOR SHARE")
    Long epoch(@Param("user") UUID user,@Param("chat") UUID chat);
    /** @param user 归属 @param task 真实旅行 @return 关联聊天，可空 */
    @Select("SELECT id::text FROM dialogue_chat WHERE user_id=#{user} AND task_id=#{task}")
    String chat(@Param("user") UUID user,@Param("task") UUID task);
    /** @param id 原输入 @param sequence 顺序 @param text 用户 @param reply 已检查回复 @param state 当前状态 @param operation 不带新授权的待确认元信息 */
    record Input(UUID id,long sequence,String text,String reply,String state,String operation) { }
    /** @param user 归属 @param chat 聊天 @param before 当前输入水位 @param preference 当前偏好；只取完整来源，不按单侧字符截断 */
    @Select("SELECT id,sequence,text,reply,state,operation::text FROM (SELECT * FROM dialogue_input WHERE user_id=#{user} AND chat_id=#{chat} AND sequence<#{before} AND state IN ('DONE','CLARIFY','CONFIRM') AND preference_revision=#{preference} ORDER BY sequence DESC LIMIT 200) i ORDER BY sequence")
    List<Input> history(@Param("user") UUID user,@Param("chat") UUID chat,@Param("before") long before,@Param("preference") long preference);
    /** @param user 用户 @return 仅当前已确认偏好，用户原文仍是数据 */
    @Select("SELECT body::text FROM user_preference WHERE user_id=#{user} AND state='CONFIRMED' ORDER BY category,city,id")
    List<String> preferences(@Param("user") UUID user);
    /** @param user 用户 @param chat 聊天 @return 未完成的问题和固定确认对象，不携带旧偏好正文 */
    @Select("SELECT jsonb_build_object('id',id,'state',state,'operation',operation)::text FROM dialogue_input WHERE user_id=#{user} AND chat_id=#{chat} AND state IN ('CLARIFY','CONFIRM','PAUSED') AND preference_revision=(SELECT preference_revision FROM app_user WHERE id=#{user}) ORDER BY sequence LIMIT 20")
    List<String> unresolved(@Param("user") UUID user,@Param("chat") UUID chat);
    /** @param source 当前快照 @param scope 聊天或真实旅行 @param dependency 业务依赖 @return 最新摘要，可空 */
    @Select("SELECT s.body::text FROM session_context_summary s WHERE s.user_id=#{source.userId} AND s.scope_id=#{scope} AND s.role=#{source.role} AND s.dependency_hash=#{dependency} AND (EXISTS(SELECT 1 FROM background_job j WHERE j.operation_id=s.source_budget AND j.kind='CHAT_INPUT' AND ((j.state='RUNNING' AND j.token=s.source_token AND s.source_budget=#{source.budgetId} AND j.lease_until>clock_timestamp()) OR (j.state IN ('COMPLETED','WAITING_USER') AND j.token=s.source_token+1))) OR (s.source_attempt IS NOT NULL AND EXISTS(SELECT 1 FROM step_attempt a WHERE a.id=s.source_attempt AND (a.state='COMPLETED' OR (a.state='RUNNING' AND a.id=#{source.attemptId})))) OR (s.source_token=0 AND s.source_attempt IS NULL AND s.source_budget=#{source.budgetId} AND NOT EXISTS(SELECT 1 FROM operation_context o WHERE o.id=s.source_budget))) ORDER BY s.created_at DESC,s.id DESC LIMIT 1")
    String cached(@Param("source") SessionContext.Source source,@Param("scope") UUID scope,@Param("dependency") String dependency);
    /** @param source 有效尝试 @param scope 归属 @param summary 已验证元信息 @param body 有界JSON */
    @Insert("INSERT INTO session_context_summary(id,user_id,scope_id,role,dependency_hash,source_budget,body,source_token,source_attempt) VALUES(#{summary.id},#{source.userId},#{scope},#{source.role},#{summary.dependencyHash},#{source.budgetId},CAST(#{body} AS jsonb),#{source.attemptToken},#{source.attemptId}) ON CONFLICT DO NOTHING")
    void save(@Param("source") SessionContext.Source source,@Param("scope") UUID scope,@Param("summary") SessionContext.Summary summary,@Param("body") String body);
}
