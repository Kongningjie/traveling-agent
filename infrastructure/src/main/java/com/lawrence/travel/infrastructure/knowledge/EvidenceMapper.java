package com.lawrence.travel.infrastructure.knowledge;

import com.lawrence.travel.domain.execution.ExecutionFence;
import com.lawrence.travel.domain.travel.TaskScope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 所有证据查询均限定双重归属；写入在任务锁和引擎权威事务内串行化。 */
public interface EvidenceMapper {
    /** @param body 不可变正文 @param artifactId 精确被选中的已提交产物 @param reason 追加失效状态，可空 */
    record Row(String body, UUID artifactId, String reason) { }
    /** @return 数据库时间；用于最终门禁 */
    @Select("SELECT clock_timestamp()") Instant now();
    /** @param scope 归属 @return 最近500条历史字段；正文不含整页原文 */
    @Select("""
            SELECT e.body::text,s.artifact_id,
              (SELECT n.reason FROM evidence_notice n WHERE n.user_id=e.user_id AND n.task_id=e.task_id AND n.evidence_id=e.id
               ORDER BY CASE n.reason WHEN 'WITHDRAWN' THEN 0 WHEN 'SUPERSEDED' THEN 1 ELSE 2 END,n.created_at DESC LIMIT 1) reason
            FROM field_evidence e JOIN execution_step s ON s.id=e.step_id AND s.committed_attempt_id=e.attempt_id
            WHERE e.user_id=#{scope.userId} AND e.task_id=#{scope.taskId} ORDER BY e.created_at DESC,e.id LIMIT 500
            """)
    List<Row> list(@Param("scope") TaskScope scope);
    /** @param scope 归属 @param id 精确ID @return 包括尚在同事务投影中的事实，产物引用在提交后才可见 */
    @Select("""
            SELECT e.body::text,s.artifact_id,
              (SELECT n.reason FROM evidence_notice n WHERE n.user_id=e.user_id AND n.task_id=e.task_id AND n.evidence_id=e.id
               ORDER BY CASE n.reason WHEN 'WITHDRAWN' THEN 0 WHEN 'SUPERSEDED' THEN 1 ELSE 2 END,n.created_at DESC LIMIT 1) reason
            FROM field_evidence e LEFT JOIN execution_step s ON s.id=e.step_id AND s.committed_attempt_id=e.attempt_id
            WHERE e.user_id=#{scope.userId} AND e.task_id=#{scope.taskId} AND e.id=#{id}
            """)
    Row read(@Param("scope") TaskScope scope, @Param("id") UUID id);
    /** @param scope 归属 @param fact 同语义对象字段日期 @return 全部相关证据，避免展示分页导致漏掉旧冲突 */
    @Select("""
            SELECT e.body::text,NULL::uuid artifact_id,
              (SELECT n.reason FROM evidence_notice n WHERE n.evidence_id=e.id
               ORDER BY CASE n.reason WHEN 'WITHDRAWN' THEN 0 WHEN 'SUPERSEDED' THEN 1 ELSE 2 END LIMIT 1) reason
            FROM field_evidence e WHERE e.user_id=#{scope.userId} AND e.task_id=#{scope.taskId}
            AND e.body->>'targetId'=#{fact.targetId} AND e.body->>'fieldPath'=#{fact.fieldPath}
            AND (e.body->>'validForDate') IS NOT DISTINCT FROM #{fact.validForDate,jdbcType=VARCHAR}::text
            """)
    List<Row> matching(@Param("scope") TaskScope scope, @Param("fact") com.lawrence.travel.domain.knowledge.FieldEvidence fact);
    /** @param scope 归属 @param key 完整参数 @param dependency 需求依赖 @return 同参数最新已提交查询的记录 */
    @Select("""
            SELECT e.body::text,s.artifact_id,NULL::text reason FROM field_evidence e
            JOIN execution_step s ON s.id=e.step_id AND s.committed_attempt_id=e.attempt_id
            WHERE e.user_id=#{scope.userId} AND e.task_id=#{scope.taskId} AND e.query_key=#{key} AND e.dependency_hash=#{dependency}
            AND NOT EXISTS(SELECT 1 FROM evidence_notice n WHERE n.user_id=e.user_id AND n.task_id=e.task_id AND n.evidence_id=e.id)
            ORDER BY e.created_at DESC,e.id LIMIT 1
            """)
    List<Row> cache(@Param("scope") TaskScope scope, @Param("key") String key, @Param("dependency") String dependency);
    /** @param scope 归属 @param id 被复用字段 @param key 原始参数 @param dependency 原始完整需求 @return 精确符合才为1；C事务再次核对 */
    @Select("SELECT count(*) FROM field_evidence WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND id=#{id} AND query_key=#{key} AND dependency_hash=#{dependency}")
    int reuseMatches(@Param("scope") TaskScope scope, @Param("id") UUID id, @Param("key") String key, @Param("dependency") String dependency);
    /** @param fence 权威来源 @param id 新证据 @param key 查询参数摘要 @param dependency 需求依赖 @param body 不可变正文 @return 插入数 */
    @Insert("INSERT INTO field_evidence(id,user_id,task_id,run_id,step_id,attempt_id,query_key,dependency_hash,body) VALUES(#{id},#{fence.scope.userId},#{fence.scope.taskId},#{fence.runId},#{fence.stepId},#{fence.attemptId},#{key},#{dependency},CAST(#{body} AS jsonb))")
    int insert(@Param("fence") ExecutionFence fence, @Param("id") UUID id, @Param("key") String key, @Param("dependency") String dependency, @Param("body") String body);
    /** @param scope 归属 @param id 原始证据 @param reason 固定原因 @param cause 新证据或撤回请求 @param notice 新增通知身份 @return 追加数，重复通知不改历史 */
    @Insert("INSERT INTO evidence_notice(id,user_id,task_id,evidence_id,reason,caused_by) VALUES(#{notice},#{scope.userId},#{scope.taskId},#{id},#{reason},#{cause}) ON CONFLICT(evidence_id,reason,caused_by) DO NOTHING")
    int notice(@Param("scope") TaskScope scope, @Param("id") UUID id, @Param("reason") String reason, @Param("cause") UUID cause, @Param("notice") UUID notice);
    /** @param scope 归属 @param evidenceId 失效引用 @return 只标记实际引用该事实的待接受候选，不修改生效版本 */
    @Update("""
            UPDATE itinerary_candidate c SET state='STALE' WHERE c.user_id=#{scope.userId} AND c.task_id=#{scope.taskId} AND c.state='PRESENTED'
            AND EXISTS(SELECT 1 FROM itinerary_snapshot s,jsonb_array_elements(s.metadata->'evidenceRefs') ref
              WHERE s.user_id=c.user_id AND s.task_id=c.task_id AND s.id=c.snapshot_id AND ref->>'id'=CAST(#{evidenceId} AS text))
            """)
    int invalidate(@Param("scope") TaskScope scope, @Param("evidenceId") UUID evidenceId);
}
