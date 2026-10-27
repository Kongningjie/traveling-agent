package com.lawrence.travel.infrastructure.memory;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 偏好私有SQL；全部读写限定user_id，不执行用户文本中的指令。 */
public interface PreferenceMapper {
    /**
     * @param user 用户
     * @return 排他锁版本；变更与候选提交统一先锁此行
     */
    @Select(
            "SELECT preference_revision FROM app_user WHERE id=#{user} AND account_status='ACTIVE' FOR NO KEY UPDATE")
    Long lock(@Param("user") UUID user);

    /**
     * @param user 用户
     * @return 共享锁版本；同事务读取条目避免新版本配旧文本
     */
    @Select(
            "SELECT preference_revision FROM app_user WHERE id=#{user} AND account_status='ACTIVE' FOR SHARE")
    Long readLock(@Param("user") UUID user);

    /**
     * @param user 用户
     * @return 当前版本；调用方已持锁时重用
     */
    @Select("SELECT preference_revision FROM app_user WHERE id=#{user} AND account_status='ACTIVE'")
    Long revision(@Param("user") UUID user);

    /**
     * @param user 用户
     * @return 有界有效条目
     */
    @Select(
            "SELECT body::text FROM user_preference WHERE user_id=#{user} AND state<>'DELETED' ORDER BY category,city,id")
    List<String> entries(@Param("user") UUID user);

    /**
     * @param user 用户
     * @param id 身份
     * @return 含墓碑的私有条目
     */
    @Select("SELECT body::text FROM user_preference WHERE user_id=#{user} AND id=#{id}")
    String entry(@Param("user") UUID user, @Param("id") UUID id);

    /**
     * @param user 用户
     * @param category 类别
     * @param city 范围
     * @param id 当前身份
     * @return 不同身份的冲突数
     */
    @Select(
            "SELECT count(*) FROM user_preference WHERE user_id=#{user} AND category=#{category} AND city=#{city} AND id<>#{id} AND state<>'DELETED'")
    int conflicts(
            @Param("user") UUID user,
            @Param("category") String category,
            @Param("city") String city,
            @Param("id") UUID id);

    /**
     * @param user 用户
     * @param p 权威对象
     * @param body 规范正文
     * @return 一条写入，用户锁内允许覆盖同身份但不允许恢复墓碑
     */
    @Insert(
            "INSERT INTO user_preference(user_id,id,category,city,state,body) VALUES(#{user},#{p.id},#{p.category},#{p.city},#{p.state},CAST(#{body} AS jsonb)) ON CONFLICT(user_id,id) DO UPDATE SET category=EXCLUDED.category,city=EXCLUDED.city,state=EXCLUDED.state,body=EXCLUDED.body")
    int save(
            @Param("user") UUID user,
            @Param("p") com.lawrence.travel.domain.memory.Preference p,
            @Param("body") String body);

    /**
     * @param user 用户
     * @param revision 预期
     * @return 提升数；零行不能当成功
     */
    @Update(
            "UPDATE app_user SET preference_revision=preference_revision+1 WHERE id=#{user} AND preference_revision=#{revision}")
    int advance(@Param("user") UUID user, @Param("revision") long revision);

    /**
     * @param hash 请求摘要
     * @param result 原响应
     */
    record Receipt(String hash, String result) {}

    /**
     * @param user 用户
     * @param request 请求
     * @return 永久回执
     */
    @Select(
            "SELECT command_hash AS hash,result::text FROM preference_request WHERE user_id=#{user} AND request_id=#{request}")
    Receipt receipt(@Param("user") UUID user, @Param("request") UUID request);

    /**
     * @param user 用户
     * @param request 请求
     * @param hash 摘要
     * @param result 原结果
     * @return 插入数
     */
    @Insert(
            "INSERT INTO preference_request(user_id,request_id,command_hash,result) VALUES(#{user},#{request},#{hash},CAST(#{result} AS jsonb))")
    int receiptSave(
            @Param("user") UUID user,
            @Param("request") UUID request,
            @Param("hash") String hash,
            @Param("result") String result);

    /**
     * @return 数据库时间，不信任浏览器
     */
    @Select("SELECT clock_timestamp()")
    java.time.Instant now();
}
