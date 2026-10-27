package com.lawrence.travel.infrastructure.execution.jobs;

import java.time.LocalDate;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 日配额与实际请求唯一归属；由预算事务调用，失败整体回滚。 */
public interface QuotaMapper {
    /**
     * @param models 模型上限
     * @param tools 工具上限
     * @param timeoutMillis 单请求
     * @param activeMillis 主动执行
     */
    record Limits(long models, long tools, long timeoutMillis, long activeMillis) {}

    /**
     * @param budget 输入预算
     * @return 创建时冻结的策略；重启／配置调整不为旧输入增加预算
     */
    @Select(
            "SELECT model_limit AS models,tool_limit AS tools,timeout_millis,active_limit_millis AS active_millis FROM operation_context WHERE budget_id=#{budget}")
    Limits limits(@Param("budget") UUID budget);

    /**
     * @param budget 输入预算
     * @return 尚未由阶段结算的活动区间，不包含停机和用户等待
     */
    @Select(
            "SELECT COALESCE(MAX(GREATEST(0,CEIL(EXTRACT(EPOCH FROM (LEAST(clock_timestamp(),lease_until)-active_since))*1000))),0)::bigint FROM background_job WHERE operation_id=#{budget} AND state='RUNNING' AND active_since IS NOT NULL")
    long activeMillis(@Param("budget") UUID budget);

    /**
     * @param userId 用户
     * @param chatId 聊天
     * @param operationId 输入
     * @param runId 旅行运行
     * @param jobId 当前作业
     * @param traceId 链路
     * @param requestId 请求
     * @param parentSpanId 尝试
     * @param active 是否仍有提交资格
     */
    record Owner(
            UUID userId,
            UUID chatId,
            UUID operationId,
            UUID runId,
            UUID jobId,
            UUID traceId,
            UUID requestId,
            UUID parentSpanId,
            boolean active) {
        /**
         * @param parent 当前实际阶段尝试
         * @return 保持业务归属的不可变关联
         */
        public Owner withParent(UUID parent) {
            return new Owner(
                    userId, chatId, operationId, runId, jobId, traceId, requestId, parent, active);
        }
    }

    /**
     * @param budget 预算
     * @return 输入归属；没有活动作业仍返回归属并拒绝准入
     */
    @Select(
            "SELECT o.user_id,o.chat_id,o.id AS operation_id,NULL::uuid AS run_id,j.id AS job_id,o.trace_id,o.request_id,a.span_id AS parent_span_id,COALESCE(j.state='RUNNING' AND j.lease_until>clock_timestamp() AND j.source_version=c.work_revision,false) AS active FROM operation_context o JOIN dialogue_chat c ON c.id=o.chat_id LEFT JOIN LATERAL(SELECT * FROM background_job WHERE operation_id=o.id ORDER BY (state='RUNNING') DESC,created_at DESC LIMIT 1) j ON true LEFT JOIN job_attempt a ON a.job_id=j.id AND a.token=j.token WHERE o.budget_id=#{budget}")
    Owner operation(@Param("budget") UUID budget);

    /**
     * @param budget 预算
     * @return 原旅行运行归属，旧运行继续原预算
     */
    @Select(
            "SELECT user_id,NULL::uuid AS chat_id,NULL::uuid AS operation_id,id AS run_id,NULL::uuid AS job_id,trace_id,request_id,NULL::uuid AS parent_span_id,true AS active FROM execution_run WHERE id=#{budget}")
    Owner run(@Param("budget") UUID budget);

    /**
     * @param user 用户
     * @param day 预占日
     * @param kind 计数类别
     */
    @Insert(
            "INSERT INTO user_daily_quota(user_id,quota_day,kind) VALUES(#{user},#{day},#{kind}) ON CONFLICT DO NOTHING")
    void create(@Param("user") UUID user, @Param("day") LocalDate day, @Param("kind") String kind);

    /**
     * @param user 用户
     * @param day 预占日
     * @param kind 类别
     * @param limit 上限
     * @return 原子预占成功数
     */
    @Update(
            "UPDATE user_daily_quota SET reserved=reserved+1 WHERE user_id=#{user} AND quota_day=#{day} AND kind=#{kind} AND reserved+consumed<#{limit}")
    int reserve(
            @Param("user") UUID user,
            @Param("day") LocalDate day,
            @Param("kind") String kind,
            @Param("limit") long limit);

    /**
     * @param id 请求
     * @param owner 唯一归属
     * @param budget 预算
     * @param day 原日期
     * @param kind 类别
     */
    @Insert(
            "INSERT INTO call_ownership(id,user_id,budget_id,job_id,quota_day,kind,trace_id,span_id,parent_span_id) VALUES(#{id},#{owner.userId},#{budget},#{owner.jobId},#{day},#{kind},#{owner.traceId},#{id},#{owner.parentSpanId})")
    void own(
            @Param("id") UUID id,
            @Param("owner") Owner owner,
            @Param("budget") UUID budget,
            @Param("day") LocalDate day,
            @Param("kind") String kind);

    /**
     * @param id 首次请求结束
     * @param known 已知用量；未知保持预占不返还，始终使用原日期
     */
    @Update(
            "WITH done AS(UPDATE call_ownership SET state=CASE WHEN #{known} THEN 'COMPLETED' ELSE 'UNKNOWN' END WHERE id=#{id} AND state='RESERVED' RETURNING user_id,quota_day,kind) UPDATE user_daily_quota q SET reserved=reserved-CASE WHEN #{known} THEN 1 ELSE 0 END,consumed=consumed+CASE WHEN #{known} THEN 1 ELSE 0 END FROM done d WHERE q.user_id=d.user_id AND q.quota_day=d.quota_day AND q.kind=d.kind")
    void finish(@Param("id") UUID id, @Param("known") boolean known);
}
