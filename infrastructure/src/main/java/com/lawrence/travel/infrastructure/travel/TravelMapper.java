package com.lawrence.travel.infrastructure.travel;

import com.lawrence.travel.domain.travel.TaskScope;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 版本存储显式 SQL；每个私有查询包含 user_id 和 task_id，所有占位均使用绑定参数。
 */
public interface TravelMapper {
    /**
     * 任务行映射，不加载需求和快照正文。
     * @param id 任务
     * @param userId 用户
     * @param title 标题
     * @param constraintRevision 需求版本
     * @param executionGeneration 代次
     * @param currentVersionId 生效快照
     * @param pendingInputs 待输入 JSON
     * @param eventSequence 事件游标
     * @param lockVersion 行版本
     */
    record TaskRow(
            UUID id,
            UUID userId,
            String title,
            long constraintRevision,
            long executionGeneration,
            UUID currentVersionId,
            String pendingInputs,
            long eventSequence,
            long lockVersion) {}

    /**
     * 请求行映射。
     * @param requestHash 首次内容摘要
     * @param result 首次结果，事务完成前可空
     */
    record RequestRow(String requestHash, String result) {}

    /**
     * 快照存储映射。
     * @param metadata 不可变元信息
     * @param payload 正文
     */
    record SnapshotRow(String metadata, String payload) {}

    /**
     * 永久修改身份。
     * @param inputSequence 原输入顺序
     * @param state 登记或应用状态
     * @param appliedRevision 已应用需求版本
     * @param contentHash 已确认内容摘要
     */
    record ChangeRow(long inputSequence, String state, Long appliedRevision, String contentHash) {}

    /**
     * @param scope 归属
     * @param id 服务端输入 ID
     * @return 同任务永久修改记录或空
     */
    @Select(
            "SELECT input_sequence,state,applied_revision,content_hash FROM change_request WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND id=#{id}")
    ChangeRow change(@Param("scope") TaskScope scope, @Param("id") UUID id);

    /**
     * @param scope 归属
     * @param id 输入身份
     * @param sequence 原输入顺序
     * @return 插入数；其他任务的同 ID 或顺序冲突不得覆盖
     */
    @Insert(
            """
            INSERT INTO change_request(id,user_id,task_id,input_sequence,state)
            VALUES(#{id},#{scope.userId},#{scope.taskId},#{sequence},'REGISTERED') ON CONFLICT DO NOTHING
            """)
    int insertChange(
            @Param("scope") TaskScope scope,
            @Param("id") UUID id,
            @Param("sequence") long sequence);

    /**
     * @param scope 归属
     * @param id 输入身份
     * @param revision 目标需求版本
     * @param hash 完整确认内容摘要
     * @return 更新数
     */
    @Update(
            """
            UPDATE change_request SET state='APPLIED',applied_revision=#{revision},content_hash=#{hash}
            WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND id=#{id} AND state='REGISTERED'
            """)
    int applyChange(
            @Param("scope") TaskScope scope,
            @Param("id") UUID id,
            @Param("revision") long revision,
            @Param("hash") String hash);

    /**
     * @param row 初始任务
     * @return 插入数；需求 FK 延迟到提交时检查
     */
    @Insert(
            """
            INSERT INTO travel_task(id,user_id,title,constraint_revision,execution_generation,pending_inputs)
            VALUES(#{row.id},#{row.userId},#{row.title},#{row.constraintRevision},#{row.executionGeneration},CAST(#{row.pendingInputs} AS jsonb))
            """)
    int insertTask(@Param("row") TaskRow row);

    /**
     * @param scope 受信归属
     * @return 同行当前状态与事件游标
     */
    @Select(
            """
            SELECT id,user_id,title,constraint_revision,execution_generation,current_version_id,
            pending_inputs::text,event_sequence,lock_version FROM travel_task WHERE user_id=#{scope.userId} AND id=#{scope.taskId}
            """)
    TaskRow task(@Param("scope") TaskScope scope);

    /**
     * @param scope 受信归属
     * @return 锁定任务或空；后续引擎继续保持任务→运行→阶段锁序
     */
    @Select(
            """
            SELECT id,user_id,title,constraint_revision,execution_generation,current_version_id,
            pending_inputs::text,event_sequence,lock_version FROM travel_task WHERE user_id=#{scope.userId} AND id=#{scope.taskId} FOR UPDATE
            """)
    TaskRow lockTask(@Param("scope") TaskScope scope);

    /**
     * @param user 用户
     * @param offset 偏移
     * @param limit 大小
     * @return 稳定顺序的私有摘要列表
     */
    @Select(
            """
            SELECT id,user_id,title,constraint_revision,execution_generation,current_version_id,
            pending_inputs::text,event_sequence,lock_version FROM travel_task WHERE user_id=#{user}
            ORDER BY created_at DESC,id LIMIT #{limit} OFFSET #{offset}
            """)
    List<TaskRow> tasks(
            @Param("user") UUID user, @Param("offset") int offset, @Param("limit") int limit);

    /**
     * @param row 新状态
     * @param expected 旧行版本
     * @return CAS 更新数；不触碰事件游标或生效指针
     */
    @Update(
            """
            UPDATE travel_task SET constraint_revision=#{row.constraintRevision},execution_generation=#{row.executionGeneration},
            pending_inputs=CAST(#{row.pendingInputs} AS jsonb),lock_version=lock_version+1
            WHERE user_id=#{row.userId} AND id=#{row.id} AND lock_version=#{expected}
            """)
    int updateTask(@Param("row") TaskRow row, @Param("expected") long expected);

    /**
     * @param scope 归属
     * @param revision 版本
     * @param payload 全量需求
     * @param source 确认来源
     * @param difference 变化说明
     * @return 插入数
     */
    @Insert(
            """
            INSERT INTO travel_constraint_revision(user_id,task_id,revision,payload,source,difference)
            VALUES(#{scope.userId},#{scope.taskId},#{revision},CAST(#{payload} AS jsonb),#{source},#{difference})
            """)
    int insertRevision(
            @Param("scope") TaskScope scope,
            @Param("revision") long revision,
            @Param("payload") String payload,
            @Param("source") String source,
            @Param("difference") String difference);

    /**
     * @param scope 归属
     * @param revision 版本
     * @return 完整历史 JSON，缺失为空
     */
    @Select(
            "SELECT payload::text FROM travel_constraint_revision WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND revision=#{revision}")
    String revision(@Param("scope") TaskScope scope, @Param("revision") long revision);

    /**
     * @param user 用户
     * @param operation 用例
     * @param resource 资源或零 UUID
     * @param key 请求键
     * @param hash 参数摘要
     * @return 首次占位行数
     */
    @Insert(
            """
            INSERT INTO travel_idempotency(user_id,operation,resource_id,request_key,request_hash)
            VALUES(#{user},#{operation},#{resource},#{key},#{hash}) ON CONFLICT DO NOTHING
            """)
    int reserve(
            @Param("user") UUID user,
            @Param("operation") String operation,
            @Param("resource") UUID resource,
            @Param("key") String key,
            @Param("hash") String hash);

    /**
     * @param user 用户
     * @param operation 用例
     * @param resource 资源
     * @param key 请求键
     * @return 首次事务提交后的请求记录并锁定
     */
    @Select(
            """
            SELECT request_hash,result::text FROM travel_idempotency
            WHERE user_id=#{user} AND operation=#{operation} AND resource_id=#{resource} AND request_key=#{key} FOR UPDATE
            """)
    RequestRow request(
            @Param("user") UUID user,
            @Param("operation") String operation,
            @Param("resource") UUID resource,
            @Param("key") String key);

    /**
     * @param user 用户
     * @param operation 用例
     * @param resource 资源
     * @param key 请求键
     * @param result 首次响应
     * @return 更新数
     */
    @Update(
            """
            UPDATE travel_idempotency SET result=CAST(#{result} AS jsonb)
            WHERE user_id=#{user} AND operation=#{operation} AND resource_id=#{resource} AND request_key=#{key} AND result IS NULL
            """)
    int finishRequest(
            @Param("user") UUID user,
            @Param("operation") String operation,
            @Param("resource") UUID resource,
            @Param("key") String key,
            @Param("result") String result);

    /**
     * @param scope 归属
     * @param id 快照身份
     * @return 同任务的快照或空
     */
    @Select(
            "SELECT metadata::text,payload::text FROM itinerary_snapshot WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND id=#{id}")
    SnapshotRow snapshot(@Param("scope") TaskScope scope, @Param("id") UUID id);

    /**
     * @param scope 归属
     * @param offset 偏移
     * @param limit 大小
     * @return 不带正文的元信息 JSON 列表
     */
    @Select(
            """
            SELECT metadata::text FROM itinerary_snapshot WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId}
            ORDER BY created_at DESC,id LIMIT #{limit} OFFSET #{offset}
            """)
    List<String> snapshots(
            @Param("scope") TaskScope scope,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /**
     * @param version 元信息
     * @param metadata 元信息 JSON
     * @param payload 正文 JSON
     * @return 插入数；同 ID 的不同内容由适配器判冲突
     */
    @Insert(
            """
            INSERT INTO itinerary_snapshot(id,user_id,task_id,constraint_revision,base_version_id,schema_version,payload_hash,metadata,payload)
            VALUES(#{version.snapshotId},#{version.scope.userId},#{version.scope.taskId},#{version.constraintRevision},#{version.baseVersionId},
            #{version.schemaVersion},#{version.payloadHash},CAST(#{metadata} AS jsonb),CAST(#{payload} AS jsonb)) ON CONFLICT(id) DO NOTHING
            """)
    int insertSnapshot(
            @Param("version") com.lawrence.travel.domain.itinerary.ItineraryVersion version,
            @Param("metadata") String metadata,
            @Param("payload") String payload);
}
