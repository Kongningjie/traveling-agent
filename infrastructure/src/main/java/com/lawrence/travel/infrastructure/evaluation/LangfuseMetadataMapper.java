package com.lawrence.travel.infrastructure.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 查询已提交诊断及逐HTTP计量；不读取消息、附件、完整metadata或供应商异常。 */
public interface LangfuseMetadataMapper {
    /**
     * 读取同一合成用户的结束节点，排除历史记录及已接收/丢弃项；未结束请求不伪造完成。
     *
     * @param user 本轮合成用户
     * @param since 本轮开始时间，不回填历史业务
     * @param limit 单批上限，由导出器固定
     * @return 独立提交快照；最多limit条
     */
    @Select(
            """
            SELECT s.id,s.trace_id,s.parent_id,s.chat_id,s.kind,s.state,s.started_at,s.ended_at,
              COALESCE(s.metadata->>'role',ancestor.role,'') AS role,
              COALESCE(s.metadata->>'schema',p.metadata->>'schema','') AS schema,
              m.model_id,m.status AS meter_state,m.token_upper_bound,m.input_tokens,m.output_tokens,m.latency_millis,
              CASE WHEN s.parent_id IS NULL THEN true
                ELSE p.user_id=s.user_id AND p.trace_id=s.trace_id END AS parent_valid
            FROM execution_trace_span s
            LEFT JOIN execution_trace_span p ON p.id=s.parent_id AND p.user_id=s.user_id AND p.trace_id=s.trace_id
            LEFT JOIN LATERAL (
              WITH RECURSIVE parents AS (
                SELECT a.parent_id,a.metadata->>'role' AS role,0 AS depth
                FROM execution_trace_span a WHERE a.id=s.parent_id AND a.user_id=s.user_id AND a.trace_id=s.trace_id
                UNION ALL SELECT a.parent_id,a.metadata->>'role',b.depth+1
                FROM execution_trace_span a JOIN parents b ON a.id=b.parent_id
                WHERE b.depth<8 AND a.user_id=s.user_id AND a.trace_id=s.trace_id
              ) SELECT role FROM parents WHERE role IS NOT NULL ORDER BY depth LIMIT 1
            ) ancestor ON true
            LEFT JOIN call_meter m ON m.id=s.id AND m.kind='MODEL'
            LEFT JOIN langfuse_span_mapping x ON x.span_id=s.id
            WHERE s.user_id=#{user} AND s.ended_at IS NOT NULL AND s.started_at>=#{since}
              AND s.started_at>=clock_timestamp()-INTERVAL '30 days'
              AND NOT EXISTS(SELECT 1 FROM execution_trace_span r WHERE r.user_id=s.user_id
                AND r.trace_id=s.trace_id AND (r.started_at<#{since}
                  OR r.started_at<clock_timestamp()-INTERVAL '30 days'))
              AND (x.span_id IS NULL OR (x.state='PENDING' AND x.attempts<3))
            ORDER BY s.ended_at,s.id LIMIT #{limit}
            """)
    List<Fact> pending(
            @Param("user") UUID user, @Param("since") Instant since, @Param("limit") int limit);

    /**
     * 查询原节点的稳定OTEL映射。
     *
     * @param span 原节点
     * @return 16位身份；未创建为null
     */
    @Select("SELECT otel_span_id FROM langfuse_span_mapping WHERE span_id=#{span}")
    String mapping(@Param("span") UUID span);

    /**
     * 创建归属一致的身份映射；重复或64位碰撞不覆盖已有记录。
     *
     * @param span 原节点
     * @param trace 原链路
     * @param user 合成归属
     * @param otel 新随机64位ID
     * @return 插入数；0需读回既有映射或换ID重试
     */
    @Insert(
            "INSERT INTO langfuse_span_mapping(span_id,trace_id,otel_span_id) SELECT id,trace_id,#{otel} FROM execution_trace_span WHERE id=#{span} AND trace_id=#{trace} AND user_id=#{user} ON CONFLICT DO NOTHING")
    int allocate(
            @Param("span") UUID span,
            @Param("trace") UUID trace,
            @Param("user") UUID user,
            @Param("otel") String otel);

    /**
     * 每次实际导出前递增尝试数；最多3次，不重放业务。
     *
     * @param span 原节点
     * @return 1为领取；0为已结束或次数耗尽
     */
    @Update(
            "UPDATE langfuse_span_mapping SET attempts=attempts+1 WHERE span_id=#{span} AND state='PENDING' AND attempts<3")
    int attempt(@Param("span") UUID span);

    /**
     * 接收成功记ACCEPTED，失败满3次记DROPPED；ACCEPTED仅表示HTTP接收，不冒充查询已验证。
     *
     * @param span 原节点
     * @param accepted 本次传输结果
     */
    @Update(
            "UPDATE langfuse_span_mapping SET state=CASE WHEN #{accepted} THEN 'ACCEPTED' WHEN attempts>=3 THEN 'DROPPED' ELSE 'PENDING' END WHERE span_id=#{span} AND state='PENDING'")
    void result(@Param("span") UUID span, @Param("accepted") boolean accepted);

    /**
     * 白名单非法或队列满时终止该节点投递，不影响原业务状态。
     *
     * @param span 原节点
     */
    @Update(
            "UPDATE langfuse_span_mapping SET state='DROPPED' WHERE span_id=#{span} AND state='PENDING'")
    void drop(@Param("span") UUID span);

    /**
     * 单实例启动时标记第三次投递后崩溃的观测缺口，不重置次数或重放业务。
     *
     * @param user 本轮合成身份
     */
    @Update(
            "UPDATE langfuse_span_mapping x SET state='DROPPED' FROM execution_trace_span s WHERE x.span_id=s.id AND s.user_id=#{user} AND x.state='PENDING' AND x.attempts=3")
    void recoverExhausted(@Param("user") UUID user);

    /**
     * 限定验证profile仅使用测试前缀用户，不接受普通账号。
     *
     * @param user 明确指定的合成身份
     * @return 是否存在本地验证用户
     */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM app_user WHERE id=#{user} AND left(login_name,9)='lf2_test_' AND created_at>=clock_timestamp()-INTERVAL '30 days')")
    boolean validationUser(@Param("user") UUID user);

    /**
     * 读取合成批次持久起点，重启不重置导出窗口或延长30天期限。
     *
     * @param user 本轮合成用户
     * @return 用户创建时间；普通或过期账号为null
     */
    @Select(
            "SELECT created_at FROM app_user WHERE id=#{user} AND left(login_name,9)='lf2_test_' AND created_at>=clock_timestamp()-INTERVAL '30 days'")
    Instant validationStartedAt(@Param("user") UUID user);

    /**
     * 有界元数据快照，不含原文或错误正文；可空计量保持未知。
     *
     * @param id 原节点
     * @param traceId 原链路
     * @param parentId 原父级，可空
     * @param chatId 原会话，可空，导出前伪名化
     * @param kind 固定节点类别
     * @param state 执行状态
     * @param startedAt 开始时间
     * @param endedAt 已提交结束时间
     * @param role 角色标签
     * @param schema 结构化契约版本
     * @param modelId 实际HTTP请求型号，可空
     * @param meterState COMPLETED/UNKNOWN/RESERVED，可空
     * @param tokenUpperBound 预占上界，可空
     * @param inputTokens 已知输入Token，可空
     * @param outputTokens 已知输出Token，可空
     * @param latencyMillis 账本记录的实际请求耗时，可空
     * @param parentValid 父级是否同用户同trace，缺失为null
     */
    record Fact(
            UUID id,
            UUID traceId,
            UUID parentId,
            UUID chatId,
            String kind,
            String state,
            Instant startedAt,
            Instant endedAt,
            String role,
            String schema,
            String modelId,
            String meterState,
            Long tokenUpperBound,
            Long inputTokens,
            Long outputTokens,
            Long latencyMillis,
            Boolean parentValid) {}
}
