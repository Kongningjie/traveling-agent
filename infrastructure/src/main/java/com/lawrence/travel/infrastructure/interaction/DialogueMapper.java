package com.lawrence.travel.infrastructure.interaction;

import com.lawrence.travel.application.interaction.Dialogue;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 独立聊天SQL；用户、任务、聊天、输入依次加锁，所有私有查询限定用户。 */
public interface DialogueMapper {
    /** @param user 用户 @param task 任务 @return 当前运行最近候选身份，仅供服务端构造确认，不向界面提前展示 */
    @Select("SELECT c.id::text FROM itinerary_candidate c JOIN travel_task t ON t.user_id=c.user_id AND t.id=c.task_id AND t.current_run_id=c.run_id WHERE c.user_id=#{user} AND c.task_id=#{task} ORDER BY c.created_at DESC,c.id DESC LIMIT 1")
    String candidate(@Param("user") UUID user, @Param("task") UUID task);
    /** @param id 消息 @param text 原文 @param state 生命周期 @param reply 助手内容 @param operation 确认JSON @param error 固定码 @param token 租约代次 @param receivedAt 收件时间 */
    record Row(UUID id, String text, String state, String reply, String operation, String error, long token, Instant receivedAt) { }
    /** @param user 权威用户 @return 规范登录名，同时锁住用户以维持全局锁序 */
    @Select("SELECT login_name FROM app_user WHERE id=#{user} FOR UPDATE")
    String lockUser(@Param("user") UUID user);
    /** @param user 归属 @param id 聊天 @return 聊天，不加行锁；所有改变关联的调用必须先锁用户 */
    @Select("SELECT id,title,task_id FROM dialogue_chat WHERE user_id=#{user} AND id=#{id}")
    Dialogue.Chat chat(@Param("user") UUID user, @Param("id") UUID id);
    /** @param user 归属 @param task 历史任务 @return 原关联聊天，用于幂等兼容旧旅行入口 */
    @Select("SELECT id,title,task_id FROM dialogue_chat WHERE user_id=#{user} AND task_id=#{task}")
    Dialogue.Chat forTask(@Param("user") UUID user, @Param("task") UUID task);
    /** @param user 用户 @param id 幂等会话ID @param title 首条标题 @return 插入数 */
    @Insert("INSERT INTO dialogue_chat(id,user_id,title,budget_id) VALUES(#{id},#{user},#{title},#{id}) ON CONFLICT DO NOTHING")
    int create(@Param("user") UUID user, @Param("id") UUID id, @Param("title") String title);
    /** @param user 用户 @param offset 分页 @return 最多20条 */
    @Select("SELECT id,title,task_id FROM dialogue_chat WHERE user_id=#{user} ORDER BY created_at DESC,id LIMIT 20 OFFSET #{offset}")
    List<Dialogue.Chat> list(@Param("user") UUID user, @Param("offset") int offset);
    /** @param user 用户 @param chat 聊天 @return 最近100条及尚未处理的输入，最多120条，避免旧屏障超出窗口后无法撤回 */
    @Select("SELECT id,text,state,reply,operation::text,error,token,received_at FROM dialogue_input WHERE user_id=#{user} AND chat_id=#{chat} AND (sequence IN (SELECT sequence FROM dialogue_input WHERE user_id=#{user} AND chat_id=#{chat} ORDER BY sequence DESC LIMIT 100) OR state IN ('QUEUED','RUNNING','CLARIFY','PAUSED','CONFIRM')) ORDER BY sequence")
    List<Row> messages(@Param("user") UUID user, @Param("chat") UUID chat);
    /** @param user 用户 @param chat 聊天 @param id 消息 @return 精确输入 */
    @Select("SELECT id,text,state,reply,operation::text,error,token,received_at FROM dialogue_input WHERE user_id=#{user} AND chat_id=#{chat} AND id=#{id}")
    Row message(@Param("user") UUID user, @Param("chat") UUID chat, @Param("id") UUID id);
    /** @param user 用户 @param chat 聊天 @param id 消息 @param text 不可变原文 @return 插入数 */
    @Insert("INSERT INTO dialogue_input(user_id,chat_id,id,text) VALUES(#{user},#{chat},#{id},#{text})")
    int insert(@Param("user") UUID user, @Param("chat") UUID chat, @Param("id") UUID id, @Param("text") String text);
    /** @param user 用户 @param task 关联任务 @return 未解释输入或明确等待澄清，屏障不能因刷新或失败消失 */
    @Select("SELECT EXISTS(SELECT 1 FROM dialogue_chat c JOIN dialogue_input i ON i.chat_id=c.id AND i.user_id=c.user_id WHERE c.user_id=#{user} AND c.task_id=#{task} AND i.state IN ('QUEUED','RUNNING','CLARIFY','PAUSED'))")
    boolean blocked(@Param("user") UUID user, @Param("task") UUID task);
    /** @return 待处理聊天的用户及ID，只作为发现提示，领取仍须复查 */
    @Select("SELECT DISTINCT c.user_id,c.id FROM dialogue_chat c JOIN dialogue_input i ON i.chat_id=c.id AND i.user_id=c.user_id WHERE i.state='QUEUED' OR (i.state='RUNNING' AND i.lease_until < clock_timestamp()) ORDER BY c.user_id,c.id LIMIT 32")
    List<Hint> hints();
    /** @param userId 权威用户 @param id 聊天 */
    record Hint(UUID userId, UUID id) { }
    /** @param user 用户 @param chat 聊天 @return 最早排队或运行输入，超时后原身份重新领取 */
    @Select("SELECT id,text,state,reply,operation::text,error,token,received_at FROM dialogue_input WHERE user_id=#{user} AND chat_id=#{chat} AND state IN ('QUEUED','RUNNING') ORDER BY sequence LIMIT 1 FOR UPDATE")
    Row next(@Param("user") UUID user, @Param("chat") UUID chat);
    /** @param user 用户 @param chat 聊天 @param id 消息 @return 唯一新租约令牌；三次失败后需显式重试，不丢屏障 */
    @Select(value = "UPDATE dialogue_input SET state='RUNNING',token=token+1,attempts=attempts+1,active_since=clock_timestamp(),lease_until=clock_timestamp()+interval '240 seconds' WHERE user_id=#{user} AND chat_id=#{chat} AND id=#{id} AND attempts<3 AND (state='QUEUED' OR (state='RUNNING' AND lease_until<clock_timestamp())) RETURNING token", affectData = true)
    @org.apache.ibatis.annotations.Options(flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    Long lease(@Param("user") UUID user, @Param("chat") UUID chat, @Param("id") UUID id);
    /** @param user 用户 @param chat 聊天 @param id 输入 @param force 正常结束或显式撤回 @param full 撤回后调用可能仍在途，保守计完整租约
     * @return 首次结算更新数；失联仅计到租约到期，不计服务停机等待时间 */
    @Update("""
        WITH accounted AS (
          UPDATE execution_budget b SET active_millis=b.active_millis+GREATEST(0,CEIL(EXTRACT(EPOCH FROM (
            (CASE WHEN #{full} THEN i.lease_until ELSE LEAST(clock_timestamp(),i.lease_until) END)-i.active_since))*1000))::bigint
          FROM dialogue_chat c,dialogue_input i
          WHERE b.run_id=c.budget_id AND c.user_id=#{user} AND c.id=#{chat} AND i.user_id=c.user_id AND i.chat_id=c.id AND i.id=#{id}
            AND i.active_since IS NOT NULL AND (#{force} OR (i.state='RUNNING' AND i.lease_until<clock_timestamp()))
          RETURNING b.run_id
        ) UPDATE dialogue_input SET active_since=NULL WHERE user_id=#{user} AND chat_id=#{chat} AND id=#{id} AND EXISTS(SELECT 1 FROM accounted)
        """)
    int settle(@Param("user") UUID user, @Param("chat") UUID chat, @Param("id") UUID id, @Param("force") boolean force, @Param("full") boolean full);
    /** @param user 用户 @param chat 聊天 @return 暂停超出重试次数的失联工作，不清零预算 */
    @Update("UPDATE dialogue_input SET state='PAUSED',error='DIALOGUE_RETRY_LIMIT' WHERE user_id=#{user} AND chat_id=#{chat} AND attempts>=3 AND (state='QUEUED' OR (state='RUNNING' AND lease_until<clock_timestamp()))")
    int cap(@Param("user") UUID user, @Param("chat") UUID chat);
    /** @param c 原领取 @return 是否仍有提交资格 */
    @Select("SELECT EXISTS(SELECT 1 FROM dialogue_input WHERE user_id=#{c.userId} AND chat_id=#{c.chatId} AND id=#{c.messageId} AND state='RUNNING' AND token=#{c.token} AND lease_until>clock_timestamp())")
    boolean live(@Param("c") Dialogue.Claim c);
    /** @param c 领取 @param task 关联任务 @return 更新数 */
    @Update("UPDATE dialogue_chat SET task_id=#{task} WHERE user_id=#{c.userId} AND id=#{c.chatId} AND task_id IS NULL")
    int link(@Param("c") Dialogue.Claim c, @Param("task") UUID task);
    /** @param c 领取 @param state 结果状态 @param reply 回复 @param operation 确认元数据 @param error 固定码 @return 更新数 */
    @Update("UPDATE dialogue_input SET state=#{state},reply=#{reply},operation=CAST(#{operation} AS jsonb),error=#{error},lease_until=NULL WHERE user_id=#{c.userId} AND chat_id=#{c.chatId} AND id=#{c.messageId} AND state='RUNNING' AND token=#{c.token} AND lease_until>clock_timestamp()")
    int finish(@Param("c") Dialogue.Claim c, @Param("state") String state, @Param("reply") String reply, @Param("operation") String operation, @Param("error") String error);
    /** @param user 用户 @param chat 聊天 @param id 输入 @param state 目标状态 @return 更新数，令牌递增使进行中的旧识别无法提交 */
    @Update("UPDATE dialogue_input SET state=#{state},token=token+1,attempts=CASE WHEN #{state}='QUEUED' THEN 0 ELSE attempts END,lease_until=NULL,error='' WHERE user_id=#{user} AND chat_id=#{chat} AND id=#{id}")
    int state(@Param("user") UUID user, @Param("chat") UUID chat, @Param("id") UUID id, @Param("state") String state);
    /** @param user 用户 @param chat 聊天 @param id 操作消息 @param reply 实际结果 @return 更新数，只更新已确认操作 */
    @Update("UPDATE dialogue_input SET reply=#{reply} WHERE user_id=#{user} AND chat_id=#{chat} AND id=#{id} AND state='DONE' AND operation IS NOT NULL")
    int operationResult(@Param("user") UUID user, @Param("chat") UUID chat, @Param("id") UUID id, @Param("reply") String reply);
    /** @param user 用户 @param task 任务 @return 当前运行身份 */
    @Select("SELECT current_run_id::text FROM travel_task WHERE user_id=#{user} AND id=#{task}")
    String run(@Param("user") UUID user, @Param("task") UUID task);
}
