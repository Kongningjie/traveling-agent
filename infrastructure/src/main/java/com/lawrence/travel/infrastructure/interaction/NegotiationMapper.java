package com.lawrence.travel.infrastructure.interaction;

import com.lawrence.travel.domain.travel.TaskScope;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 协商收件箱和历史卡片 SQL；均以服务端用户和任务限定。 */
public interface NegotiationMapper {
    /**
     * @param scope 归属
     * @param id 拆分后输入
     * @param original 原回答
     * @param kind 保留的选项来源
     * @return 关联审计数，不扩大原授权
     */
    @Update("UPDATE conversation_message SET body=jsonb_set(body,'{kind}',to_jsonb(CAST(#{kind} AS text))),original_answer=(SELECT answer FROM conversation_message WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND id=#{original}) WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND id=#{id} AND status='RECEIVED'")
    int inherit(@Param("scope") TaskScope scope, @Param("id") UUID id, @Param("original") UUID original, @Param("kind") String kind);
    /**
     * @param scope 归属
     * @param id 原始输入
     * @return 原接收时刻，重启后相对日期仍基于原消息，而非恢复时间
     */
    @Select("SELECT created_at FROM conversation_message WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND id=#{id}")
    java.time.Instant receivedAt(@Param("scope") TaskScope scope, @Param("id") UUID id);
    /**
     * @param scope 归属
     * @return 尚未解释的原问题回答，按消息顺序并入新修改
     */
    @Select("SELECT request_hash,body::text,answer::text,status,receipt::text FROM conversation_message WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND status='RECEIVED' AND answer IS NOT NULL ORDER BY sequence")
    List<Row> pendingAnswers(@Param("scope") TaskScope scope);
    /**
     * @param scope 归属
     * @param id 原回答
     * @param body 保留语义来源的新执行输入
     * @return 转换数；原回答单独留存
     */
    @Update("UPDATE conversation_message SET original_answer=answer,answer=NULL,body=CAST(#{body} AS jsonb) WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND id=#{id} AND status='RECEIVED' AND answer IS NOT NULL")
    int promote(@Param("scope") TaskScope scope, @Param("id") UUID id, @Param("body") String body);
    /**
     * @param scope 归属
     * @param id 卡片身份
     * @return 同任务历史卡片，允许旧请求幂等回放但不能采纳过期问题
     */
    @Select("SELECT body::text FROM decision_request WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND id=#{id}")
    String decision(@Param("scope") TaskScope scope, @Param("id") UUID id);
    /**
     * @param requestHash 请求摘要
     * @param body 原始消息
     * @param answer 原始回答
     * @param status 处理状态
     * @param receipt 初次响应
     */
    record Row(String requestHash, String body, String answer, String status, String receipt) { }
    /**
     * @param scope 私有任务
     * @param id 输入
     * @return 同任务原始记录
     */
    @Select("SELECT request_hash,body::text,answer::text,status,receipt::text FROM conversation_message WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND id=#{id}")
    Row message(@Param("scope") TaskScope scope, @Param("id") UUID id);
    /**
     * @param scope 已锁任务
     * @param id 输入
     * @param hash 语义
     * @param body 原始消息
     * @param answer 原始回答，可空
     * @param status 收到或仅记录
     * @return 事务内分配消息顺序并插入
     */
    @Insert("""
            WITH counter AS(UPDATE travel_task SET message_sequence=message_sequence+1 WHERE user_id=#{scope.userId} AND id=#{scope.taskId} RETURNING message_sequence)
            INSERT INTO conversation_message(id,user_id,task_id,sequence,request_hash,body,answer,status,preference_revision)
            SELECT #{id},#{scope.userId},#{scope.taskId},message_sequence,#{hash},CAST(#{body} AS jsonb),CAST(#{answer} AS jsonb),#{status},(SELECT preference_revision FROM app_user WHERE id=#{scope.userId}) FROM counter
            """)
    int insert(@Param("scope") TaskScope scope, @Param("id") UUID id, @Param("hash") String hash, @Param("body") String body, @Param("answer") String answer, @Param("status") String status);
    /**
     * @param scope 归属
     * @param id 消息
     * @return 本任务消息顺序
     */
    @Select("SELECT sequence FROM conversation_message WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND id=#{id}")
    Long sequence(@Param("scope") TaskScope scope, @Param("id") UUID id);
    /**
     * @param scope 归属
     * @return 最近一百条，按消息原顺序返回
     */
    @Select("SELECT request_hash,body::text,answer::text,status,receipt::text FROM (SELECT * FROM conversation_message WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} ORDER BY sequence DESC LIMIT 100) recent ORDER BY sequence")
    List<Row> messages(@Param("scope") TaskScope scope);
    /**
     * @param scope 归属
     * @return 尚未完成的修改，不能因新输入覆盖旧有效要求
     */
    @Select("SELECT request_hash,body::text,answer::text,status,receipt::text FROM conversation_message WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND status='RECEIVED' AND answer IS NULL ORDER BY sequence LIMIT 100")
    List<Row> pending(@Param("scope") TaskScope scope);
    /**
     * @param scope 归属
     * @param id 消息
     * @param receipt 首次响应
     * @return 首次写入数
     */
    @Update("UPDATE conversation_message SET receipt=CAST(#{receipt} AS jsonb) WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND id=#{id} AND receipt IS NULL")
    int receipt(@Param("scope") TaskScope scope, @Param("id") UUID id, @Param("receipt") String receipt);
    /**
     * @param scope 归属
     * @param id 输入
     * @param status 结果
     * @param run 来源运行
     * @return 一次处理数
     */
    @Update("UPDATE conversation_message SET status=#{status},processed_run_id=#{run} WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND id=#{id} AND status='RECEIVED'")
    int finish(@Param("scope") TaskScope scope, @Param("id") UUID id, @Param("status") String status, @Param("run") UUID run);
    /**
     * @param scope 归属
     * @param run 当前运行
     * @return 当前有效卡片或空
     */
    @Select("SELECT body::text FROM decision_request WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND run_id=#{run} AND body->>'state'='OPEN' ORDER BY id LIMIT 1")
    String card(@Param("scope") TaskScope scope, @Param("run") UUID run);
    /**
     * @param scope 归属
     * @param id 卡片
     * @param revision 版本
     * @return 精确版本的规范元信息
     */
    @Select("SELECT specs::text FROM negotiation_card WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND decision_id=#{id} AND revision=#{revision}")
    String specs(@Param("scope") TaskScope scope, @Param("id") UUID id, @Param("revision") long revision);
    /**
     * @param scope 归属
     * @param id 卡片
     * @param revision 版本
     * @param specs 不可覆盖的元信息
     * @return 插入数
     */
    @Insert("INSERT INTO negotiation_card(decision_id,revision,user_id,task_id,specs) VALUES(#{id},#{revision},#{scope.userId},#{scope.taskId},CAST(#{specs} AS jsonb))")
    int insertSpecs(@Param("scope") TaskScope scope, @Param("id") UUID id, @Param("revision") long revision, @Param("specs") String specs);
    /**
     * @param scope 归属
     * @return 已明确拒绝的稳定选项 UUID 文本，不把拒绝当采纳
     */
    @Select("SELECT DISTINCT choice.value FROM conversation_message c CROSS JOIN LATERAL jsonb_each_text(c.answer->'selected') choice WHERE c.user_id=#{scope.userId} AND c.task_id=#{scope.taskId} AND c.answer->>'action'='REJECT'")
    List<String> rejections(@Param("scope") TaskScope scope);
    /**
     * @param scope 新修改任务
     * @return 旧卡片取代数，历史回答不改写
     */
    @Update("UPDATE decision_request SET body=jsonb_set(body,'{state}','\"SUPERSEDED\"'::jsonb) WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND body->>'state'='OPEN'")
    int supersede(@Param("scope") TaskScope scope);
}
