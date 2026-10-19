package com.lawrence.travel.infrastructure.document;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 用户/聊天双重归属；只存建议协议，不能直接更新行程或需求表。 */
public interface AdviceMapper {
    /** @param user 用户 @param chat 聊天 @return 已锁聊天当前资料水位 */
    @Select("SELECT document_revision FROM dialogue_chat WHERE user_id=#{user} AND id=#{chat} FOR NO KEY UPDATE")
    Long revision(@Param("user") UUID user,@Param("chat") UUID chat);
    /** @param user 用户 @param chat 聊天 @return 不自动越过任何尚未理解或确认的输入 */
    @Select("SELECT EXISTS(SELECT 1 FROM dialogue_input WHERE user_id=#{user} AND chat_id=#{chat} AND state IN('QUEUED','RUNNING','PAUSED','CLARIFY','CONFIRM'))")
    boolean blocked(@Param("user") UUID user,@Param("chat") UUID chat);
    /** @param user 用户 @return 已锁用户偏好版本 */
    @Select("SELECT preference_revision FROM app_user WHERE id=#{user} AND account_status='ACTIVE' FOR NO KEY UPDATE")
    Long preference(@Param("user") UUID user);
    /** @param e 已检查归属 @param body 完整协议 @return 容量内插入数；用户锁下最多200项 */
    @Insert("INSERT INTO plan_advice(id,user_id,chat_id,message_id,document_id,body) SELECT #{e.id},#{e.user},#{e.chat},#{e.message},#{e.document},CAST(#{body} AS jsonb) WHERE (SELECT count(*) FROM plan_advice WHERE user_id=#{e.user} AND chat_id=#{e.chat})<200")
    int insert(@Param("e") com.lawrence.travel.application.document.Advice.Entry e,@Param("body") String body);
    /** @param user 用户 @param chat 聊天 @return 完成分析/等待确认的最多40项 */
    @Select("SELECT a.body::text FROM plan_advice a JOIN dialogue_input i ON i.user_id=a.user_id AND i.chat_id=a.chat_id AND i.id=a.message_id WHERE a.user_id=#{user} AND a.chat_id=#{chat} AND i.state='DONE' ORDER BY a.created_at DESC,a.id LIMIT 40")
    List<String> list(@Param("user") UUID user,@Param("chat") UUID chat);
    /** @param user 用户 @param chat 聊天 @param id 精确对象 @return 可空私有协议 */
    @Select("SELECT body::text FROM plan_advice WHERE user_id=#{user} AND chat_id=#{chat} AND id=#{id}")
    String get(@Param("user") UUID user,@Param("chat") UUID chat,@Param("id") UUID id);
    /** @param e 最终回执 @param body 已校验协议 @return 更新数 */
    @Update("UPDATE plan_advice SET body=CAST(#{body} AS jsonb) WHERE user_id=#{e.user} AND chat_id=#{e.chat} AND id=#{e.id}")
    int update(@Param("e") com.lawrence.travel.application.document.Advice.Entry e,@Param("body") String body);
}
