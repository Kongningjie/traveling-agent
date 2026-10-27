package com.lawrence.travel.infrastructure.execution.jobs;

import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 只保存关联身份与固定分类，禁止保存正文、异常消息和凭据。 */
public interface DiagnosticMapper {
    /**
     * @param id 轨迹
     * @param metadata 已校验有界身份元数据，不含正文
     */
    @Update(
            "UPDATE execution_trace_span SET metadata=metadata||CAST(#{metadata} AS jsonb) WHERE id=#{id}")
    void metadata(@Param("id") UUID id, @Param("metadata") String metadata);

    /**
     * @param id 当前结束节点；只汇总最多8层的原call_meter，不复制计量或清除UNKNOWN
     */
    @Update(
            "WITH RECURSIVE tree AS (SELECT id,0 AS depth FROM execution_trace_span WHERE id=#{id} UNION ALL SELECT s.id,t.depth+1 FROM execution_trace_span s JOIN tree t ON s.parent_id=t.id WHERE t.depth<8), totals AS (SELECT count(*) AS calls,count(*) FILTER(WHERE m.status<>'COMPLETED') AS unknown,COALESCE(sum(m.input_tokens),0) AS inputs,COALESCE(sum(m.output_tokens),0) AS outputs FROM call_meter m JOIN tree t ON t.id=m.id) UPDATE execution_trace_span SET metadata=metadata||jsonb_build_object('meterCalls',totals.calls,'unknownCalls',totals.unknown,'knownInputTokens',totals.inputs,'knownOutputTokens',totals.outputs) FROM totals WHERE id=#{id}")
    void meterSummary(@Param("id") UUID id);

    /**
     * @param id 问题编号
     * @param trace 可信链路
     * @param request 入口
     * @param code 固定安全原因
     * @param version 策略版本
     * @param point 固定检查点，非白名单归为SAFETY
     */
    @Insert(
            "INSERT INTO problem_event(id,trace_id,request_id,component,code,severity,policy_version) VALUES(#{id},#{trace},#{request},CASE WHEN #{point} IN ('INPUT','OUTPUT','CONTEXT','SUMMARY','TOOL_INPUT','TOOL_OUTPUT','MODEL_REQUEST') THEN 'SAFETY_'||#{point} ELSE 'SAFETY' END,#{code},'ERROR',#{version})")
    void safety(
            @Param("id") UUID id,
            @Param("trace") UUID trace,
            @Param("request") UUID request,
            @Param("code") String code,
            @Param("version") String version,
            @Param("point") String point);

    /**
     * @param id span
     * @param trace 链路
     * @param parent 父级
     * @param user 用户
     * @param request 请求
     * @param chat 聊天
     * @param operation 输入
     * @param run 旅行运行
     * @param job 作业
     * @param token 围栏
     * @param kind 类型
     */
    @Insert(
            "INSERT INTO execution_trace_span(id,trace_id,parent_id,user_id,request_id,chat_id,operation_id,run_id,job_id,token,kind,state) VALUES(#{id},#{trace},#{parent},#{user},#{request},#{chat},#{operation},#{run},#{job},#{token},#{kind},'RUNNING') ON CONFLICT DO NOTHING")
    void span(
            @Param("id") UUID id,
            @Param("trace") UUID trace,
            @Param("parent") UUID parent,
            @Param("user") UUID user,
            @Param("request") UUID request,
            @Param("chat") UUID chat,
            @Param("operation") UUID operation,
            @Param("run") UUID run,
            @Param("job") UUID job,
            @Param("token") Long token,
            @Param("kind") String kind);

    /**
     * @param id span
     * @param state 首次终态
     * @param code 固定错误码
     */
    @Update(
            "UPDATE execution_trace_span SET state=#{state},code=#{code},ended_at=clock_timestamp() WHERE id=#{id} AND ended_at IS NULL")
    void end(@Param("id") UUID id, @Param("state") String state, @Param("code") String code);

    /**
     * @param id 问题身份
     * @param user 用户，可空
     * @param trace 链路
     * @param span 定位
     * @param request 请求
     * @param component 固定组件
     * @param code 固定原因
     */
    @Insert(
            "INSERT INTO problem_event(id,user_id,trace_id,span_id,request_id,component,code,severity) VALUES(#{id},#{user},#{trace},#{span},#{request},#{component},#{code},CASE WHEN #{code} IN ('USER_CANCELLED','SOURCE_CHANGED') THEN 'INFO' WHEN #{code}='LEASE_EXPIRED' THEN 'WARN' ELSE 'ERROR' END)")
    void problem(
            @Param("id") UUID id,
            @Param("user") UUID user,
            @Param("trace") UUID trace,
            @Param("span") UUID span,
            @Param("request") UUID request,
            @Param("component") String component,
            @Param("code") String code);
}
