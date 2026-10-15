package com.lawrence.travel.infrastructure.persistence.control;

import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.domain.execution.control.RunBudget;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** M0 计量 SQL；只由仓储适配器调用，不暴露给 Controller 或 Agent 工具。 */
public interface BudgetMapper {
    /**
     * 幂等建立预算，冲突时保留原计数。
     * @param runId 运行稳定标识
     */
    @Insert("INSERT INTO traveling_agent.execution_budget(run_id) VALUES (#{runId}) ON CONFLICT DO NOTHING")
    void create(@Param("runId") UUID runId);

    /**
     * 在现有事务内锁定预算行，后续预占和事件插入必须在同一事务提交。
     * @param runId 已存在的运行标识
     * @return 当前快照；不存在时为 null
     */
    @Select("SELECT model_calls, tool_calls, repair_rounds, active_millis "
            + "FROM traveling_agent.execution_budget WHERE run_id=#{runId} FOR UPDATE")
    BudgetRow lock(@Param("runId") UUID runId);

    /**
     * 读取预算，不持有跨请求的数据库锁。
     * @param runId 已存在的运行标识
     * @return 当前快照；不存在时为 null
     */
    @Select("SELECT model_calls, tool_calls, repair_rounds, active_millis "
            + "FROM traveling_agent.execution_budget WHERE run_id=#{runId}")
    BudgetRow read(@Param("runId") UUID runId);

    /**
     * 保存锁内计算出的预算。
     * @param runId 当前运行
     * @param budget 新快照
     * @param activeMillis 已累计主动时间，单位毫秒
     */
    @Update("UPDATE traveling_agent.execution_budget SET model_calls=#{budget.modelCalls}, "
            + "tool_calls=#{budget.toolCalls}, repair_rounds=#{budget.repairRounds}, active_millis=#{activeMillis} "
            + "WHERE run_id=#{runId}")
    void update(@Param("runId") UUID runId, @Param("budget") RunBudget budget,
                @Param("activeMillis") long activeMillis);

    /**
     * 与次数预占同事务登记请求，不保存提示词、凭证和供应商原始响应。
     * @param id 请求计量标识
     * @param runId 原运行标识
     * @param kind 操作类别
     * @param target 精确接入范围
     * @param upperBound 未决请求的保守 Token 上界
     * @param now 预占时间
     */
    @Insert("INSERT INTO traveling_agent.call_meter(id,run_id,kind,model_id,region,api,token_upper_bound,started_at) "
            + "VALUES (#{id},#{runId},#{kind},#{target.modelId},#{target.region},#{target.api},#{upperBound},#{now})")
    void insert(@Param("id") UUID id, @Param("runId") UUID runId, @Param("kind") String kind,
                @Param("target") BudgetLedger.Target target, @Param("upperBound") long upperBound,
                @Param("now") Instant now);

    /**
     * 首次结束回调生效，未知保留 NULL 用量，不能被重复回调覆盖。
     * @param id 请求计量标识
     * @param status COMPLETED 或 UNKNOWN
     * @param input 已知输入 Token，未知时为 null
     * @param output 已知输出 Token，未知时为 null
     * @param latency 请求耗时毫秒，不累加到全运行活跃时间
     */
    @Update("UPDATE traveling_agent.call_meter SET status=#{status}, input_tokens=#{input,jdbcType=BIGINT}, "
            + "output_tokens=#{output,jdbcType=BIGINT}, latency_millis=#{latency} "
            + "WHERE id=#{id} AND status='RESERVED'")
    void finish(@Param("id") UUID id, @Param("status") String status, @Param("input") Long input,
                @Param("output") Long output, @Param("latency") long latency);

    /**
     * 统计本项目已知用量和未决／未知请求的保守上界。
     * @param target 精确型号及接入范围
     * @param since 快照核实时间
     * @return 非负 Token 总量，溢出时 SQL 失败并阻止准入
     */
    @Select("SELECT COALESCE(SUM(CASE WHEN status='COMPLETED' "
            + "THEN input_tokens::numeric + output_tokens ELSE token_upper_bound END),0)::bigint "
            + "FROM traveling_agent.call_meter WHERE kind='MODEL' AND model_id=#{target.modelId} "
            + "AND region=#{target.region} AND api=#{target.api} AND started_at>=#{since}")
    long tokensSince(@Param("target") BudgetLedger.Target target, @Param("since") Instant since);

    /**
     * 查找当前快照之后未知或超过调用方承诺 Token 上界的记录。
     * @param target 精确接入范围
     * @param since 当前快照核实时间
     * @param unresolvedBefore 当前进程建立时间
     * @return 是否需要暂停并重新核实额度
     */
    @Select("SELECT EXISTS(SELECT 1 FROM traveling_agent.call_meter WHERE kind='MODEL' "
            + "AND model_id=#{target.modelId} AND region=#{target.region} AND api=#{target.api} "
            + "AND started_at>=#{since} AND (status='UNKNOWN' "
            + "OR (status='RESERVED' AND started_at<#{unresolvedBefore}) OR (status='COMPLETED' "
            + "AND input_tokens::numeric + output_tokens > token_upper_bound)))")
    boolean hasUncertainUsage(@Param("target") BudgetLedger.Target target, @Param("since") Instant since,
                              @Param("unresolvedBefore") Instant unresolvedBefore);

    /**
     * 数据库预算行，不作为可编辑的领域对象暴露。
     * @param modelCalls 模型预占次数
     * @param toolCalls 工具预占次数
     * @param repairRounds 修订预占次数
     * @param activeMillis 全运行累计主动时间
     */
    record BudgetRow(long modelCalls, long toolCalls, int repairRounds, long activeMillis) { }
}
