package com.lawrence.travel.infrastructure.execution.persistence;

import com.lawrence.travel.domain.travel.TaskScope;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 引擎显式 SQL；提示查询无锁，所有写事务按任务→运行→阶段→事件锁序执行。
 */
public interface EngineMapper {
    /**
     * 运行行。
     * @param body 领域状态 JSON
     * @param activeSince 最近记账起点或空
     * @param goal 应用目标
     * @param limits 原运行预算上限
     */
    record RunRow(String body, Instant activeSince, String goal, String limits) { }
    /**
     * 无锁数据库提示。
     * @param userId 用户
     * @param taskId 任务
     * @param runId 运行
     * @param stepId 阶段
     * @param eventId 事件
     * @param type 阶段类型
     */
    record HintRow(UUID userId, UUID taskId, UUID runId, UUID stepId, UUID eventId, com.lawrence.travel.domain.execution.StageType type) { }
    /**
     * 阶段行。
     * @param body 领域状态
     * @param definition 持久阶段定义
     * @param committedAttemptId 已完成尝试
     * @param receiptHash 提交语义摘要
     * @param artifactId 已选产物
     */
    record StepRow(String body, String definition, UUID committedAttemptId, String receiptHash, UUID artifactId) { }
    /**
     * 事件是唯一租约权威。
     * @param id 事件
     * @param userId 用户
     * @param taskId 任务
     * @param runId 运行
     * @param stepId 阶段
     * @param causationId 原因
     * @param eventType 类型
     * @param branchKey 分支
     * @param payloadHash 输入摘要
     * @param expectedGeneration 代次
     * @param expectedConstraintRevision 需求
     * @param status 状态
     * @param availableAt 可执行时刻
     * @param attemptCount 尝试数
     * @param leaseOwner 实例
     * @param leaseUntil 到期
     * @param fencingToken 令牌
     * @param lastErrorCode 固定错误
     */
    record EventRow(UUID id, UUID userId, UUID taskId, UUID runId, UUID stepId, UUID causationId,
                    String eventType, String branchKey, String payloadHash, long expectedGeneration, long expectedConstraintRevision,
                    String status, Instant availableAt, int attemptCount, String leaseOwner, Instant leaseUntil, long fencingToken, String lastErrorCode) { }
    /**
     * 启动记录。
     * @param requestHash 语义摘要
     * @param runId 首次运行
     */
    record StartRow(String requestHash, UUID runId) { }
    /**
     * 产物引用。
     * @param attemptId 生成尝试
     * @param contentHash 内容摘要
     * @param body 完整产物
     */
    record ArtifactRow(UUID attemptId, String contentHash, String body) { }
    /**
     * 已采纳回答。
     * @param requestHash 完整回答及下一步摘要
     * @param body 已应用回答
     */
    record AnswerRow(String requestHash, String body) { }

    /**
     * @return 数据库实时时间，不能用事务启动时刻判断等待锁后的租约
     */
    @Select("SELECT clock_timestamp()") Instant now();
    /**
     * @return 非阻塞领取互斥，限制并发运行检查的竞争；不在持有事件锁后申请
     */
    @Select("SELECT pg_try_advisory_xact_lock(71351012)") boolean claimMutex();
    /**
     * @param scope 已锁任务
     * @return 当前可提交运行或空
     */
    @Select("SELECT current_run_id::text FROM travel_task WHERE user_id=#{scope.userId} AND id=#{scope.taskId}") String currentRun(@Param("scope") TaskScope scope);
    /**
     * @param scope 归属
     * @param runId 新指针
     * @param generation 新代次
     * @return 更新数
     */
    @Update("UPDATE travel_task SET current_run_id=#{runId},execution_generation=#{generation},lock_version=lock_version+1 WHERE user_id=#{scope.userId} AND id=#{scope.taskId}")
    int pointRun(@Param("scope") TaskScope scope, @Param("runId") UUID runId, @Param("generation") long generation);
    /**
     * @param scope 归属
     * @param id 运行
     * @return 锁定运行
     */
    @Select("SELECT body::text,active_since,goal,limits::text FROM execution_run WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND id=#{id} FOR UPDATE")
    RunRow lockRun(@Param("scope") TaskScope scope, @Param("id") UUID id);
    /**
     * @param scope 归属
     * @param id 运行
     * @return 状态
     */
    @Select("SELECT body::text,active_since,goal,limits::text FROM execution_run WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND id=#{id}")
    RunRow run(@Param("scope") TaskScope scope, @Param("id") UUID id);
    /**
     * @param scope 归属
     * @param id 运行
     * @param body 领域状态
     * @param goal 目标
     * @param limits 原预算
     * @return 插入数
     */
    @Insert("INSERT INTO execution_run(id,user_id,task_id,body,goal,limits,request_id,trace_id) VALUES(#{id},#{scope.userId},#{scope.taskId},CAST(#{body} AS jsonb),#{goal},CAST(#{limits} AS jsonb),#{ids.requestId},#{ids.traceId})")
    int insertRun(@Param("scope") TaskScope scope, @Param("id") UUID id, @Param("body") String body, @Param("goal") String goal, @Param("limits") String limits, @Param("ids") com.lawrence.travel.application.execution.ExecutionCorrelation.Ids ids);
    /** @param id 原运行 @return 持久入口关联，不从模型消息恢复权限 */
    @Select("SELECT request_id,trace_id FROM execution_run WHERE id=#{id}")
    com.lawrence.travel.application.execution.ExecutionCorrelation.Ids correlation(@Param("id") UUID id);
    /**
     * @param scope 归属
     * @param id 运行
     * @param body 状态
     * @param activeSince 新记账起点或空
     * @return 更新数
     */
    @Update("UPDATE execution_run SET body=CAST(#{body} AS jsonb),active_since=#{activeSince,jdbcType=TIMESTAMP_WITH_TIMEZONE} WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND id=#{id}")
    int updateRun(@Param("scope") TaskScope scope, @Param("id") UUID id, @Param("body") String body, @Param("activeSince") Instant activeSince);

    /**
     *
     * @return 当前活动运行数；领取互斥内使用
     */
    @Select(
            "SELECT count(DISTINCT run_id) FROM outbox_event WHERE status='LEASED' AND"
                + " lease_until>clock_timestamp()") int activeRuns();

    /**
     * @param user 当前归属
     * @param run 本运行
     * @return 其他实际有效旅行/聊天工作数
     */
    @Select(
            "SELECT (SELECT count(DISTINCT run_id) FROM outbox_event WHERE user_id=#{user} AND"
                + " run_id<>#{run} AND status='LEASED' AND lease_until>clock_timestamp())+(SELECT"
                + " count(*) FROM background_job WHERE user_id=#{user} AND kind<>'DOCUMENT_PARSE'"
                + " AND state='RUNNING' AND lease_until>clock_timestamp())")
    int otherUserWork(@Param("user") UUID user, @Param("run") UUID run);

    /**
     * @return 共享模型工作物理租约数量，解析不占模型槽
     */
    @Select(
            "SELECT (SELECT count(DISTINCT run_id) FROM outbox_event WHERE status='LEASED' AND"
                + " lease_until>clock_timestamp())+(SELECT count(*) FROM background_job WHERE"
                + " kind<>'DOCUMENT_PARSE' AND state='RUNNING' AND lease_until>clock_timestamp())")
    int activeWork();

    /**
     *
     * @param scope 归属
     * @param input 原因
     * @return 稳定启动结果
     */
    @Select("SELECT request_hash,run_id FROM execution_start_request WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND input_id=#{input}")
    StartRow startRequest(@Param("scope") TaskScope scope, @Param("input") UUID input);
    /**
     * @param scope 归属
     * @param input 原因
     * @param hash 语义摘要
     * @param run 结果
     * @return 插入数
     */
    @Insert("INSERT INTO execution_start_request(user_id,task_id,input_id,request_hash,run_id) VALUES(#{scope.userId},#{scope.taskId},#{input},#{hash},#{run})")
    int insertStart(@Param("scope") TaskScope scope, @Param("input") UUID input, @Param("hash") String hash, @Param("run") UUID run);
    /**
     * @param scope 归属
     * @param run 运行
     * @param id 阶段
     * @return 锁定阶段或空
     */
    @Select("SELECT body::text,definition::text,committed_attempt_id,receipt_hash,artifact_id FROM execution_step WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND run_id=#{run} AND id=#{id} FOR UPDATE")
    StepRow lockStep(@Param("scope") TaskScope scope, @Param("run") UUID run, @Param("id") UUID id);
    /**
     * @param scope 归属
     * @param run 运行
     * @param id 阶段
     * @return 同运行阶段或空
     */
    @Select("SELECT body::text,definition::text,committed_attempt_id,receipt_hash,artifact_id FROM execution_step WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND run_id=#{run} AND id=#{id}")
    StepRow step(@Param("scope") TaskScope scope, @Param("run") UUID run, @Param("id") UUID id);
    /**
     * @param scope 归属
     * @param run 运行
     * @return 稳定顺序锁定全部阶段
     */
    @Select("SELECT id::text FROM execution_step WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND run_id=#{run} ORDER BY id FOR UPDATE")
    List<String> lockSteps(@Param("scope") TaskScope scope, @Param("run") UUID run);
    /**
     * @param scope 归属
     * @param run 运行
     * @param id 阶段
     * @param body 状态
     * @param definition 原定义
     * @return 插入数，身份或逻辑键冲突不覆盖
     */
    @Insert("INSERT INTO execution_step(id,user_id,task_id,run_id,body,definition) VALUES(#{id},#{scope.userId},#{scope.taskId},#{run},CAST(#{body} AS jsonb),CAST(#{definition} AS jsonb)) ON CONFLICT DO NOTHING")
    int insertStep(@Param("scope") TaskScope scope, @Param("run") UUID run, @Param("id") UUID id, @Param("body") String body, @Param("definition") String definition);
    /**
     * @param scope 归属
     * @param run 运行
     * @param id 阶段
     * @param body 新状态
     * @return 更新数
     */
    @Update("UPDATE execution_step SET body=CAST(#{body} AS jsonb) WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND run_id=#{run} AND id=#{id}")
    int updateStep(@Param("scope") TaskScope scope, @Param("run") UUID run, @Param("id") UUID id, @Param("body") String body);
    /**
     * @param scope 归属
     * @param run 运行
     * @param id 阶段
     * @param body 完成状态
     * @param attempt 完成尝试
     * @param hash 语义摘要
     * @param artifact 已选产物
     * @return 更新数
     */
    @Update("UPDATE execution_step SET body=CAST(#{body} AS jsonb),committed_attempt_id=#{attempt},receipt_hash=#{hash},artifact_id=#{artifact} WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND run_id=#{run} AND id=#{id} AND state='RUNNING'")
    int finishStep(@Param("scope") TaskScope scope, @Param("run") UUID run, @Param("id") UUID id, @Param("body") String body, @Param("attempt") UUID attempt, @Param("hash") String hash, @Param("artifact") UUID artifact);
    /**
     * @param limit 提示数量
     * @return 未领取的到期事件
     */
    @Select(
            """
            WITH p AS(SELECT e.*,s.type,row_number() OVER(PARTITION BY e.user_id ORDER BY e.available_at,e.id) AS rank FROM outbox_event e
            JOIN execution_step s ON s.id=e.step_id JOIN execution_run r ON r.id=e.run_id
            WHERE e.status IN ('READY','RETRY_WAIT') AND e.available_at<=clock_timestamp() AND r.state IN ('QUEUED','RUNNING'))
            SELECT p.user_id,p.task_id,p.run_id,p.step_id,p.id AS event_id,p.type FROM p LEFT JOIN dispatch_turn d ON d.user_id=p.user_id
            ORDER BY p.rank,COALESCE(d.last_turn,0),p.available_at,p.id LIMIT #{limit}
            """)
    List<HintRow> candidates(@Param("limit") int limit);

    /**
     *
     * @param limit 上限
     * @return 到期租约的无锁提示
     */
    @Select("SELECT e.user_id,e.task_id,e.run_id,e.step_id,e.id AS event_id,s.type FROM outbox_event e JOIN execution_step s ON s.id=e.step_id WHERE e.status='LEASED' AND e.lease_until<=clock_timestamp() ORDER BY e.lease_until,e.id LIMIT #{limit}")
    List<HintRow> expired(@Param("limit") int limit);
    /**
     * @param scope 归属
     * @param run 运行
     * @param id 事件
     * @return 唯一租约权威，按锁序最后取得
     */
    @Select("SELECT * FROM outbox_event WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND run_id=#{run} AND id=#{id} FOR UPDATE")
    EventRow lockEvent(@Param("scope") TaskScope scope, @Param("run") UUID run, @Param("id") UUID id);
    /**
     * @param scope 归属
     * @param run 运行
     * @return 同级事件按稳定顺序锁定
     */
    @Select("SELECT * FROM outbox_event WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND run_id=#{run} ORDER BY id FOR UPDATE")
    List<EventRow> lockEvents(@Param("scope") TaskScope scope, @Param("run") UUID run);
    /**
     * @param scope 归属
     * @param run 运行
     * @param cause 原因
     * @param type 阶段类型
     * @param branch 分支
     * @return 因果唯一记录
     */
    @Select("SELECT * FROM outbox_event WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND run_id=#{run} AND causation_id=#{cause} AND event_type=#{type} AND branch_key=#{branch}")
    EventRow causal(@Param("scope") TaskScope scope, @Param("run") UUID run, @Param("cause") UUID cause, @Param("type") String type, @Param("branch") String branch);
    /**
     * @param event 首次请求
     * @return 插入数；唯一冲突后须比对语义
     */
    @Insert("""
            INSERT INTO outbox_event(id,user_id,task_id,run_id,step_id,causation_id,event_type,branch_key,payload_hash,expected_generation,expected_constraint_revision)
            VALUES(#{e.id},#{e.userId},#{e.taskId},#{e.runId},#{e.stepId},#{e.causationId},#{e.eventType},#{e.branchKey},#{e.payloadHash},#{e.expectedGeneration},#{e.expectedConstraintRevision}) ON CONFLICT DO NOTHING
            """)
    int insertEvent(@Param("e") EventRow event);
    /**
     * @param e 已锁事件新状态
     * @param previousToken 旧权威令牌
     * @return CAS 更新数
     */
    @Update("""
            UPDATE outbox_event SET status=#{e.status},available_at=#{e.availableAt},attempt_count=#{e.attemptCount},lease_owner=#{e.leaseOwner},
            lease_until=#{e.leaseUntil,jdbcType=TIMESTAMP_WITH_TIMEZONE},fencing_token=#{e.fencingToken},last_error_code=#{e.lastErrorCode}
            WHERE id=#{e.id} AND user_id=#{e.userId} AND task_id=#{e.taskId} AND run_id=#{e.runId} AND fencing_token=#{previousToken}
            """)
    int updateEvent(@Param("e") EventRow event, @Param("previousToken") long previousToken);
    /** @param f 领取副本 @param millis 租约时长 @return 原子续租数；SQL 执行时过期即拒绝，不能靠先前读取复活 */
    @Update("""
            UPDATE outbox_event SET lease_until=clock_timestamp()+(#{millis}*interval '1 millisecond')
            WHERE id=#{f.claim.eventId} AND user_id=#{f.scope.userId} AND task_id=#{f.scope.taskId} AND run_id=#{f.runId}
            AND step_id=#{f.stepId} AND status='LEASED' AND lease_owner=#{f.claim.owner} AND fencing_token=#{f.claim.fencingToken}
            AND lease_until>clock_timestamp()
            """)
    int renewEvent(@Param("f") com.lawrence.travel.domain.execution.ExecutionFence f, @Param("millis") long millis);
    /** @param f 领取副本 @return 在当前短事务内占定阶段完成资格，后续任何写失败一起回滚 */
    @Update("""
            UPDATE outbox_event SET status='DONE',lease_owner=NULL,lease_until=NULL
            WHERE id=#{f.claim.eventId} AND user_id=#{f.scope.userId} AND task_id=#{f.scope.taskId} AND run_id=#{f.runId}
            AND step_id=#{f.stepId} AND status='LEASED' AND lease_owner=#{f.claim.owner} AND fencing_token=#{f.claim.fencingToken}
            AND lease_until>clock_timestamp()
            """)
    int completeEvent(@Param("f") com.lawrence.travel.domain.execution.ExecutionFence f);
    /**
     * @param run 运行
     * @return 当前仍持租约数；任务锁内判断并行分支
     */
    @Select("SELECT count(*) FROM outbox_event WHERE run_id=#{run} AND status='LEASED'") int leased(@Param("run") UUID run);
    /**
     * @param run 运行
     * @return 尚可推进请求数
     */
    @Select("SELECT count(*) FROM outbox_event WHERE run_id=#{run} AND status IN ('READY','RETRY_WAIT')") int pending(@Param("run") UUID run);
    /**
     * @param run 运行
     * @return 最近权威租约到期时间，限制崩溃后时间记账不含无限停机
     */
    @Select("SELECT max(lease_until) FROM outbox_event WHERE run_id=#{run} AND status='LEASED'") Instant latestLease(@Param("run") UUID run);
    /**
     * @param scope 归属
     * @param run 运行
     * @param step 阶段
     * @param event 事件
     * @param id 尝试
     * @param number 顺序
     * @param token 权威副本
     * @return 插入数
     */
    @Insert("INSERT INTO step_attempt(id,user_id,task_id,run_id,step_id,event_id,number,fencing_token,state) VALUES(#{id},#{scope.userId},#{scope.taskId},#{run},#{step},#{event},#{number},#{token},'RUNNING')")
    int insertAttempt(@Param("scope") TaskScope scope, @Param("run") UUID run, @Param("step") UUID step, @Param("event") UUID event, @Param("id") UUID id, @Param("number") int number, @Param("token") long token);
    /**
     * @param id 尝试
     * @param state 结束状态
     * @return 首次结束更新数
     */
    @Update("UPDATE step_attempt SET state=#{state} WHERE id=#{id} AND state='RUNNING'") int finishAttempt(@Param("id") UUID id, @Param("state") String state);
    /**
     * @param run 预算身份
     * @param millis 单运行区间增量
     * @return 更新数；不覆盖并发模型预占计数
     */
    @Update("UPDATE execution_budget SET active_millis=active_millis+#{millis} WHERE run_id=#{run}") int accrue(@Param("run") UUID run, @Param("millis") long millis);
    /**
     * @param run 原预算
     * @param maximum 已确认修订上限
     * @return 首次规划修订预占数，阶段重试不再扣
     */
    @Update("UPDATE execution_budget SET repair_rounds=repair_rounds+1 WHERE run_id=#{run} AND repair_rounds<#{maximum}")
    int repair(@Param("run") UUID run, @Param("maximum") int maximum);
    /**
     * @param scope 归属
     * @param run 运行
     * @param step 阶段或空
     * @param type 固定用户可见类型
     * @return 插入数；序号只在已锁任务内分配
     */
    @Insert("""
            WITH cursor AS (UPDATE travel_task SET event_sequence=event_sequence+1 WHERE user_id=#{scope.userId} AND id=#{scope.taskId} RETURNING event_sequence)
            INSERT INTO run_event(user_id,task_id,sequence,run_id,step_id,type,generation,payload)
            SELECT #{scope.userId},#{scope.taskId},event_sequence,#{run},#{step},#{type},
            (r.body->>'generation')::bigint,jsonb_build_object('state',r.body->>'state') FROM cursor
            JOIN execution_run r ON r.user_id=#{scope.userId} AND r.task_id=#{scope.taskId} AND r.id=#{run}
            """)
    int event(@Param("scope") TaskScope scope, @Param("run") UUID run, @Param("step") UUID step, @Param("type") String type);
    /**
     * @param scope 归属
     * @param run 运行
     * @param step 阶段
     * @param attempt 尝试
     * @param id 产物
     * @param hash 摘要
     * @param bytes UTF-8 字节
     * @return 插入数
     */
    @Insert("INSERT INTO artifact_record(id,user_id,task_id,run_id,step_id,attempt_id,storage_kind,content_type,content_hash,content_bytes,purpose) VALUES(#{id},#{scope.userId},#{scope.taskId},#{run},#{step},#{attempt},'DB_JSON','application/json',#{hash},#{bytes},'STAGE_RESULT')")
    int insertArtifact(@Param("scope") TaskScope scope, @Param("run") UUID run, @Param("step") UUID step, @Param("attempt") UUID attempt, @Param("id") UUID id, @Param("hash") String hash, @Param("bytes") long bytes);
    /**
     * @param id 产物
     * @param body JSON 正文
     * @return 插入数
     */
    @Insert("INSERT INTO artifact_content(artifact_id,body) VALUES(#{id},CAST(#{body} AS jsonb))") int insertContent(@Param("id") UUID id, @Param("body") String body);
    /**
     * @param scope 归属
     * @param run 运行
     * @param step 阶段
     * @param id 产物
     * @return 同任务同阶段正文引用
     */
    @Select("SELECT r.attempt_id,r.content_hash,c.body::text FROM artifact_record r JOIN artifact_content c ON c.artifact_id=r.id WHERE r.user_id=#{scope.userId} AND r.task_id=#{scope.taskId} AND r.run_id=#{run} AND r.step_id=#{step} AND r.id=#{id}")
    ArtifactRow artifact(@Param("scope") TaskScope scope, @Param("run") UUID run, @Param("step") UUID step, @Param("id") UUID id);
    /**
     * @param scope 归属
     * @param run 运行
     * @param id 决策
     * @param body 决策 JSON
     * @return 插入数
     */
    @Insert("INSERT INTO decision_request(id,user_id,task_id,run_id,body) VALUES(#{id},#{scope.userId},#{scope.taskId},#{run},CAST(#{body} AS jsonb))")
    int insertDecision(@Param("scope") TaskScope scope, @Param("run") UUID run, @Param("id") UUID id, @Param("body") String body);
    /**
     * @param scope 归属
     * @param run 运行
     * @param id 决策
     * @return 锁定的当前卡片 JSON
     */
    @Select("SELECT body::text FROM decision_request WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND run_id=#{run} AND id=#{id} FOR UPDATE")
    String decision(@Param("scope") TaskScope scope, @Param("run") UUID run, @Param("id") UUID id);
    /**
     * @param scope 归属
     * @param run 运行
     * @param id 决策
     * @param body 新卡片
     * @return 更新数
     */
    @Update("UPDATE decision_request SET body=CAST(#{body} AS jsonb) WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND run_id=#{run} AND id=#{id}")
    int updateDecision(@Param("scope") TaskScope scope, @Param("run") UUID run, @Param("id") UUID id, @Param("body") String body);
    /** @param scope 归属 @param run 运行 @param current 正在确认的卡片 @param revision 新需求版本 @return 其他已过期卡片数；保留问题正文供重新澄清，不重绑旧授权 */
    @Update("""
            UPDATE decision_request SET body=jsonb_set(body,'{state}','\"SUPERSEDED\"'::jsonb)
            WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND run_id=#{run} AND id<>#{current}
            AND body->>'state'='OPEN' AND (body->>'constraintRevision')::bigint<>#{revision}
            """)
    int staleDecisions(@Param("scope") TaskScope scope, @Param("run") UUID run, @Param("current") UUID current, @Param("revision") long revision);
    /**
     * @param scope 归属
     * @param run 运行
     * @return 是否有待回答问题
     */
    @Select("SELECT count(*) FROM decision_request WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND run_id=#{run} AND body->>'state'='OPEN'")
    int openDecisions(@Param("scope") TaskScope scope, @Param("run") UUID run);
    /**
     * @param scope 归属
     * @param run 运行
     * @param id 回答
     * @return 同运行已采纳回答
     */
    @Select("SELECT request_hash,body::text FROM decision_response WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND run_id=#{run} AND id=#{id}")
    AnswerRow answer(@Param("scope") TaskScope scope, @Param("run") UUID run, @Param("id") UUID id);
    /**
     * @param scope 归属
     * @param run 运行
     * @param id 回答
     * @param decision 决策
     * @param hash 幂等摘要
     * @param body 应用结果
     * @return 插入数
     */
    @Insert("INSERT INTO decision_response(id,user_id,task_id,run_id,decision_id,request_hash,body) VALUES(#{id},#{scope.userId},#{scope.taskId},#{run},#{decision},#{hash},CAST(#{body} AS jsonb))")
    int insertAnswer(@Param("scope") TaskScope scope, @Param("run") UUID run, @Param("id") UUID id, @Param("decision") UUID decision, @Param("hash") String hash, @Param("body") String body);
}
