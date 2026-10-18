package com.lawrence.travel.infrastructure.execution.jobs;

import com.lawrence.travel.application.execution.Jobs;
import com.lawrence.travel.domain.execution.BackgroundJob;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 作业私有SQL；归属先锁user／chat，再job，执行期间不持锁。 */
public interface JobMapper {
    /** @param id 权威终态作业 @param error 固定原因；同步关联聊天投影，防止取代后留下永久输入屏障 */
    @Update("UPDATE dialogue_input SET state='WITHDRAWN',token=token+1,lease_until=NULL,active_since=NULL,error=#{error} WHERE job_id=#{id} AND state IN ('QUEUED','RUNNING','PAUSED','CLARIFY','CONFIRM')")
    void withdrawProjection(@Param("id") UUID id, @Param("error") String error);
    /** @param s 归属 @return 本聊天的唯一旧预算，不能导入其他用户的计量 */
    @Select("SELECT budget_id::text FROM dialogue_chat WHERE user_id=#{s.userId} AND id=#{s.chatId}")
    String legacyBudget(@Param("s") Jobs.Scope s);
    /** @param op 输入 @return 未到终态的后继数量 */
    @Select("SELECT count(*) FROM background_job WHERE operation_id=#{op} AND state NOT IN ('COMPLETED','FAILED','CANCELLED','SUPERSEDED')")
    int pending(@Param("op") UUID op);
    /** @param op 原输入 @return 所有子作业结束后的真实汇总结论，不把失败／取消写成成功 */
    @Select("SELECT CASE WHEN bool_or(state='FAILED') THEN 'FAILED' WHEN bool_or(state='CANCELLED') THEN 'CANCELLED' WHEN bool_or(state='SUPERSEDED') THEN 'SUPERSEDED' ELSE 'COMPLETED' END FROM background_job WHERE operation_id=#{op}")
    String outcome(@Param("op") UUID op);
    /** @param operation 原输入 @return 已有有效执行，防止同operation时间区间重复计量 */
    @Select("SELECT count(*) FROM background_job WHERE operation_id=#{operation} AND state='RUNNING' AND lease_until>clock_timestamp()")
    int operationActive(@Param("operation") UUID operation);
    /** @param id 作业 @param token 尝试 @return 历史span，缺失可空 */
    @Select("SELECT span_id::text FROM job_attempt WHERE job_id=#{id} AND token=#{token}")
    String attemptSpan(@Param("id") UUID id, @Param("token") long token);
    /** @param s 归属 @param id 明确继续的作业；只重置重试次数，不重置预算 */
    @Update("UPDATE background_job SET attempts=0 WHERE user_id=#{s.userId} AND chat_id=#{s.chatId} AND id=#{id} AND state='QUEUED'")
    void resetAttempts(@Param("s") Jobs.Scope s, @Param("id") UUID id);
    /** @param id 作业 @param operationId 输入 @param budgetId 预算 @param kind 类型 @param state 状态 @param token 围栏 @param version 水位 @param leaseUntil 租约 @param traceId 链路 @param requestId 请求 @param error 分类 @param payload 参数 @param attempts 次数 */
    record Row(UUID id, UUID operationId, UUID budgetId, BackgroundJob.Kind kind, BackgroundJob.State state, long token, long version,
               Instant leaseUntil, UUID traceId, UUID requestId, String error, String payload, int attempts) { }
    /** @param user 用户 @return 存在且锁定 */
    @Select("SELECT id::text FROM app_user WHERE id=#{user} FOR NO KEY UPDATE") String user(@Param("user") UUID user);
    /** @param s 归属 @return 当前聊天水位 */
    @Select("SELECT work_revision FROM dialogue_chat WHERE user_id=#{s.userId} AND id=#{s.chatId} FOR NO KEY UPDATE") Long version(@Param("s") Jobs.Scope s);
    /** @return 数据库时钟 */
    @Select("SELECT clock_timestamp()") Instant now();
    /** @param id 预算 @param legacy 旧预算，可空 @return 创建数，旧计数保守复制而不删历史计量 */
    @Insert("INSERT INTO execution_budget(run_id,model_calls,tool_calls,repair_rounds,active_millis) SELECT #{id},COALESCE(b.model_calls,0),COALESCE(b.tool_calls,0),COALESCE(b.repair_rounds,0),COALESCE(b.active_millis,0) FROM (SELECT 1) x LEFT JOIN execution_budget b ON b.run_id=#{legacy} ON CONFLICT DO NOTHING")
    int budget(@Param("id") UUID id, @Param("legacy") UUID legacy);
    /** @param s 归属 @param id 输入身份 @param legacy 原预算 @param request 入口 @param trace 因果 @param limits 输入策略 @param timeout 单请求毫秒 @param active 主动上限毫秒 @return 插入数 */
    @Insert("INSERT INTO operation_context(id,user_id,chat_id,budget_id,legacy_budget_id,request_id,trace_id,model_limit,tool_limit,timeout_millis,active_limit_millis) VALUES(#{id},#{s.userId},#{s.chatId},#{id},#{legacy},#{request},#{trace},#{limits.modelCalls},#{limits.toolCalls},#{timeout},#{active}) ON CONFLICT DO NOTHING")
    int operation(@Param("s") Jobs.Scope s, @Param("id") UUID id, @Param("legacy") UUID legacy, @Param("request") UUID request, @Param("trace") UUID trace, @Param("limits") com.lawrence.travel.domain.execution.control.RunLimits limits, @Param("timeout") long timeout, @Param("active") long active);
    /** @param s 归属 @param op 输入 @return 是否为本人的operation */
    @Select("SELECT EXISTS(SELECT 1 FROM operation_context WHERE id=#{op} AND user_id=#{s.userId} AND chat_id=#{s.chatId})")
    boolean owns(@Param("s") Jobs.Scope s, @Param("op") UUID op);
    /** @param c 提交 @param id 稳定作业 @param hash 参数指纹 @param version 当前水位 @return 插入数 */
    @Insert("INSERT INTO background_job(id,user_id,chat_id,operation_id,kind,branch,payload,payload_hash,dependency_id,source_version,attempts,state) VALUES(#{id},#{c.scope.userId},#{c.scope.chatId},#{c.operationId},#{c.kind},#{c.branch},CAST(#{c.payload} AS jsonb),#{hash},#{c.dependency},#{version},#{c.priorAttempts},CASE WHEN CAST(#{c.dependency} AS uuid) IS NULL THEN 'QUEUED' ELSE 'WAITING_DEPENDENCY' END) ON CONFLICT DO NOTHING")
    int insert(@Param("c") Jobs.Submit c, @Param("id") UUID id, @Param("hash") String hash, @Param("version") long version);
    /** @param s 私有范围 @param id 作业 @return 当前行 */
    @Select("SELECT j.id,j.operation_id,o.budget_id,j.kind,j.state,j.token,j.source_version AS version,j.lease_until,o.trace_id,o.request_id,j.error,j.payload::text,j.attempts FROM background_job j JOIN operation_context o ON o.id=j.operation_id WHERE j.user_id=#{s.userId} AND j.chat_id=#{s.chatId} AND j.id=#{id} FOR NO KEY UPDATE OF j")
    Row lock(@Param("s") Jobs.Scope s, @Param("id") UUID id);
    /** @param s 私有范围 @param id 作业 @param hash 预期指纹 @param dependency 原依赖 @return 幂等身份是否一致 */
    @Select("SELECT EXISTS(SELECT 1 FROM background_job WHERE user_id=#{s.userId} AND chat_id=#{s.chatId} AND id=#{id} AND payload_hash=#{hash} AND dependency_id IS NOT DISTINCT FROM CAST(#{dependency} AS uuid))")
    boolean same(@Param("s") Jobs.Scope s, @Param("id") UUID id, @Param("hash") String hash, @Param("dependency") UUID dependency);
    /** @param id 依赖 @param s 归属 @return 依赖是否完成 */
    @Select("SELECT state FROM background_job WHERE id=#{id} AND user_id=#{s.userId} AND chat_id=#{s.chatId}")
    String dependency(@Param("s") Jobs.Scope s, @Param("id") UUID id);
    /** @param s 范围 @param id 作业 @return 依赖ID */
    @Select("SELECT dependency_id::text FROM background_job WHERE user_id=#{s.userId} AND chat_id=#{s.chatId} AND id=#{id}")
    String dependencyId(@Param("s") Jobs.Scope s, @Param("id") UUID id);
    /** @param kinds 已装配处理类型 @return 发现提示，实际领取必须重新加锁 */
    @Select("<script>SELECT user_id,chat_id,id FROM background_job WHERE kind IN <foreach collection='kinds' item='k' open='(' separator=',' close=')'>#{k}</foreach> AND (state IN ('QUEUED','WAITING_DEPENDENCY') OR (state='RUNNING' AND lease_until&lt;clock_timestamp())) ORDER BY created_at,id LIMIT 32</script>")
    List<Hint> hints(@Param("kinds") java.util.Set<BackgroundJob.Kind> kinds);
    /** @param userId 用户 @param chatId 聊天 @param id 作业 */
    record Hint(UUID userId, UUID chatId, UUID id) { }
    /** @param f 原资格 @return 仍可提交；归属和删除水位一起核对 */
    @Select("SELECT EXISTS(SELECT 1 FROM background_job j JOIN dialogue_chat c ON c.id=j.chat_id AND c.user_id=j.user_id WHERE j.user_id=#{f.scope.userId} AND j.chat_id=#{f.scope.chatId} AND j.id=#{f.id} AND j.token=#{f.token} AND j.source_version=#{f.version} AND c.work_revision=j.source_version AND j.state='RUNNING' AND j.lease_until>clock_timestamp())")
    boolean live(@Param("f") Jobs.Fence f);
    /** @param f 有效资格 @return 续租数 */
    @Update("UPDATE background_job SET lease_until=clock_timestamp()+interval '240 seconds' WHERE id=#{f.id} AND user_id=#{f.scope.userId} AND token=#{f.token} AND state='RUNNING' AND lease_until>clock_timestamp()")
    int renew(@Param("f") Jobs.Fence f);
    /** @param s 范围 @param id 作业 @return 更新数 */
    @Update("UPDATE background_job SET state='RUNNING',token=token+1,attempts=attempts+1,active_since=clock_timestamp(),lease_until=clock_timestamp()+interval '240 seconds',error='' WHERE user_id=#{s.userId} AND chat_id=#{s.chatId} AND id=#{id}")
    int lease(@Param("s") Jobs.Scope s, @Param("id") UUID id);
    /** @param f 原作业 @param state 状态 @param error 固定原因 @param artifact 有界JSON @param strict 正常完成须在SQL执行时租约仍有效 @return 更新数，取消和暂停均令旧token失效 */
    @Update("UPDATE background_job SET state=#{state},error=#{error},artifact=COALESCE(CAST(#{artifact} AS jsonb),artifact),token=token+1,lease_until=NULL,active_since=NULL WHERE user_id=#{f.scope.userId} AND chat_id=#{f.scope.chatId} AND id=#{f.id} AND token=#{f.token} AND (NOT #{strict} OR (state='RUNNING' AND lease_until>clock_timestamp()))")
    int state(@Param("f") Jobs.Fence f, @Param("state") String state, @Param("error") String error, @Param("artifact") String artifact, @Param("strict") boolean strict);
    /** @param row 作业 @param full 取消在途时保守计完整租约 @return 本次已结算的活跃区间数 */
    @Update("WITH n AS (UPDATE execution_budget b SET active_millis=b.active_millis+GREATEST(0,CEIL(EXTRACT(EPOCH FROM ((CASE WHEN #{full} THEN j.lease_until ELSE LEAST(clock_timestamp(),j.lease_until) END)-j.active_since))*1000))::bigint FROM background_job j WHERE j.id=#{row.id} AND b.run_id=#{row.budgetId} AND j.active_since IS NOT NULL RETURNING b.run_id) UPDATE background_job SET active_since=NULL WHERE id=#{row.id} AND EXISTS(SELECT 1 FROM n)")
    int settle(@Param("row") Row row, @Param("full") boolean full);
    /** @param row 新领取 @param span 尝试span */
    @Insert("INSERT INTO job_attempt(job_id,token,span_id) VALUES(#{row.id},#{row.token},#{span})") void attempt(@Param("row") Row row, @Param("span") UUID span);
    /** @param id 作业 @param token 原尝试 @param outcome 固定结论 */
    @Update("UPDATE job_attempt SET ended_at=clock_timestamp(),outcome=#{outcome} WHERE job_id=#{id} AND token=#{token} AND ended_at IS NULL")
    void endAttempt(@Param("id") UUID id, @Param("token") long token, @Param("outcome") String outcome);
    /** @param s 范围 @return 当前水位递增 */
    @Select(value="UPDATE dialogue_chat SET work_revision=work_revision+1 WHERE user_id=#{s.userId} AND id=#{s.chatId} RETURNING work_revision", affectData=true)
    @org.apache.ibatis.annotations.Options(flushCache=org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    long invalidate(@Param("s") Jobs.Scope s);
    /** @param s 私有范围 @return 活动作业ID */
    @Select("SELECT id::text FROM background_job WHERE user_id=#{s.userId} AND chat_id=#{s.chatId} AND state NOT IN ('COMPLETED','FAILED','CANCELLED','SUPERSEDED') ORDER BY id")
    List<String> active(@Param("s") Jobs.Scope s);
    /** @return 全局解析资格锁，不在取得此锁之后反向锁归属 */
    @Select("SELECT id FROM job_dispatch_guard WHERE id=1 FOR UPDATE") int dispatch();
    /** @param user 用户 @param parser 是否解析资源 @return 当前用户有效活动作业数 */
    @Select("SELECT count(*) FROM background_job WHERE user_id=#{user} AND state='RUNNING' AND lease_until>clock_timestamp() AND (kind='DOCUMENT_PARSE')=#{parser}")
    int activeCount(@Param("user") UUID user, @Param("parser") boolean parser);
    /** @return 全局有效解析数 */
    @Select("SELECT count(*) FROM background_job WHERE kind='DOCUMENT_PARSE' AND state='RUNNING' AND lease_until>clock_timestamp()") int parses();
}
