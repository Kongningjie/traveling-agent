package com.lawrence.travel.infrastructure.itinerary;

import com.lawrence.travel.application.itinerary.CandidateAcceptanceCommand;
import com.lawrence.travel.domain.travel.TaskScope;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 候选报告的显式私有 SQL；调用方统一先锁用户、任务、运行、阶段及事件。 */
public interface CandidateMapper {
    /** @param scope 归属 @return 当前运行最近草案，不把历史候选冒充当前 */
    @Select("SELECT c.id::text FROM itinerary_candidate c JOIN travel_task t ON t.user_id=c.user_id AND t.id=c.task_id AND t.current_run_id=c.run_id WHERE c.user_id=#{scope.userId} AND c.task_id=#{scope.taskId} ORDER BY c.created_at DESC,c.id DESC LIMIT 1")
    String latest(@Param("scope") TaskScope scope);
    /** @param scope 私有归属 @param id 问题 @param revision 卡片版本 @param specs 明确字段定义，不能伪造采纳 @return 插入数 */
    @Insert("INSERT INTO negotiation_card(decision_id,revision,user_id,task_id,specs) VALUES(#{id},#{revision},#{scope.userId},#{scope.taskId},CAST(#{specs} AS jsonb))")
    int insertDecisionSpecs(@Param("scope") TaskScope scope, @Param("id") UUID id, @Param("revision") long revision, @Param("specs") String specs);
    /**
     * @param id 候选
     * @param snapshotId 快照
     * @param runId 来源运行
     * @param generation 代次
     * @param planStepId 规划阶段
     * @param planAttemptId 规划尝试
     * @param state 生命周期
     * @param validationId 程序报告
     * @param reviewId 审核报告
     */
    record CandidateRow(UUID id, UUID snapshotId, UUID runId, long generation, UUID planStepId,
                        UUID planAttemptId, String state, UUID validationId, UUID reviewId) { }
    /**
     * @param body 报告
     * @param validationId 审核所读程序报告
     */
    record ReportRow(String body, UUID validationId) { }
    /**
     * @param commandHash 原始语义
     * @param result 稳定响应
     */
    record ReceiptRow(String commandHash, String result) { }
    /**
     * @param scope 用户归属
     * @return 当前偏好修订；先锁用户防止接受与偏好更新交错
     */
    @Select("SELECT preference_revision FROM app_user WHERE id=#{scope.userId} AND account_status='ACTIVE' FOR UPDATE")
    Long lockUser(@Param("scope") TaskScope scope);
    /** @param scope 私有归属 @return 权威偏好版本；最终写入方已先锁用户行 */
    @Select("SELECT preference_revision FROM app_user WHERE id=#{scope.userId} AND account_status='ACTIVE'") Long preferenceRevision(@Param("scope") TaskScope scope);
    /**
     * @return 数据库真实时钟，不能由客户端提供
     */
    @Select("SELECT clock_timestamp()") Instant now();
    /**
     * @param scope 私有归属
     * @param id 候选
     * @return 同任务候选或空，任务锁已串行化全部候选写
     */
    @Select("SELECT id,snapshot_id,run_id,generation,plan_step_id,plan_attempt_id,state,validation_id,review_id FROM itinerary_candidate WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND id=#{id}")
    CandidateRow candidate(@Param("scope") TaskScope scope, @Param("id") UUID id);
    /**
     * @param scope 私有归属
     * @param row 新候选
     * @return 插入数；冲突不可覆盖来源
     */
    @Insert("""
            INSERT INTO itinerary_candidate(id,user_id,task_id,snapshot_id,run_id,generation,plan_step_id,plan_attempt_id,state)
            VALUES(#{row.id},#{scope.userId},#{scope.taskId},#{row.snapshotId},#{row.runId},#{row.generation},#{row.planStepId},#{row.planAttemptId},'NEW')
            """)
    int insertCandidate(@Param("scope") TaskScope scope, @Param("row") CandidateRow row);
    /**
     * @param scope 归属
     * @param id 候选
     * @param state 新状态
     * @param validation 程序报告
     * @param review 审核报告
     * @return 更新数
     */
    @Update("UPDATE itinerary_candidate SET state=#{state},validation_id=#{validation},review_id=#{review} WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND id=#{id}")
    int update(@Param("scope") TaskScope scope, @Param("id") UUID id, @Param("state") String state,
               @Param("validation") UUID validation, @Param("review") UUID review);
    /**
     * @param scope 归属
     * @param id 报告
     * @return 不可变正文及审核依据
     */
    @Select("SELECT body::text,validation_id FROM itinerary_report WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND id=#{id}")
    ReportRow report(@Param("scope") TaskScope scope, @Param("id") UUID id);
    /**
     * @param scope 归属
     * @param candidate 候选
     * @param report 来源报告
     * @param body 不可变正文
     * @param validation 审核依据或空
     * @return 插入数
     */
    @Insert("""
            INSERT INTO itinerary_report(id,user_id,task_id,run_id,candidate_id,kind,body,producer_step_id,producer_attempt_id,validation_id)
            VALUES(#{report.id},#{scope.userId},#{scope.taskId},#{candidate.runId},#{candidate.id},#{report.kind},CAST(#{body} AS jsonb),#{report.producerStepId},#{report.producerAttemptId},#{validation})
            """)
    int insertReport(@Param("scope") TaskScope scope, @Param("candidate") CandidateRow candidate,
                     @Param("report") com.lawrence.travel.domain.itinerary.ItineraryReport report,
                     @Param("body") String body, @Param("validation") UUID validation);
    /**
     * @param scope 归属
     * @param id 请求
     * @return 首次响应或空
     */
    @Select("SELECT command_hash,result::text FROM itinerary_acceptance_request WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND request_id=#{id}")
    ReceiptRow request(@Param("scope") TaskScope scope, @Param("id") UUID id);
    /**
     * @param scope 归属
     * @param id 候选
     * @return 永久接受事实或空
     */
    @Select("SELECT command_hash,result::text FROM itinerary_acceptance WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND candidate_id=#{id}")
    ReceiptRow accepted(@Param("scope") TaskScope scope, @Param("id") UUID id);
    /**
     * @param command 接受语义
     * @param hash 规范摘要
     * @param body 首次响应
     * @return 插入数
     */
    @Insert("INSERT INTO itinerary_acceptance_request(user_id,task_id,request_id,command_hash,result) VALUES(#{command.scope.userId},#{command.scope.taskId},#{command.acceptanceRequestId},#{hash},CAST(#{body} AS jsonb))")
    int saveRequest(@Param("command") CandidateAcceptanceCommand command, @Param("hash") String hash, @Param("body") String body);
    /**
     * @param command 原始接受
     * @param candidate 精确来源
     * @param id 永久身份
     * @param hash 语义摘要
     * @param body 稳定响应
     * @return 插入数；候选唯一
     */
    @Insert("""
            INSERT INTO itinerary_acceptance(id,user_id,task_id,run_id,candidate_id,snapshot_id,previous_version_id,command_hash,result)
            VALUES(#{id},#{command.scope.userId},#{command.scope.taskId},#{candidate.runId},#{candidate.id},#{candidate.snapshotId},#{command.expectedCurrentVersionId},#{hash},CAST(#{body} AS jsonb))
            """)
    int accept(@Param("command") CandidateAcceptanceCommand command, @Param("candidate") CandidateRow candidate,
               @Param("id") UUID id, @Param("hash") String hash, @Param("body") String body);
    /**
     * @param command 当前状态预期
     * @param candidate 候选
     * @return CAS 生效数；只有该语句允许更新生效指针
     */
    @Update("""
            UPDATE travel_task SET current_version_id=#{candidate.snapshotId},lock_version=lock_version+1
            WHERE user_id=#{command.scope.userId} AND id=#{command.scope.taskId}
            AND current_version_id IS NOT DISTINCT FROM #{command.expectedCurrentVersionId,jdbcType=OTHER}
            AND current_run_id=#{candidate.runId} AND execution_generation=#{command.expectedGeneration}
            AND constraint_revision=#{command.expectedConstraintRevision} AND pending_inputs='{}'::jsonb
            AND EXISTS(SELECT 1 FROM itinerary_report v, itinerary_report r
                WHERE v.id=#{candidate.validationId} AND r.id=#{candidate.reviewId}
                AND (v.body->>'validUntil')::timestamptz>clock_timestamp()
                AND (r.body->>'validUntil')::timestamptz>clock_timestamp())
            """)
    int activate(@Param("command") CandidateAcceptanceCommand command, @Param("candidate") CandidateRow candidate);
}
