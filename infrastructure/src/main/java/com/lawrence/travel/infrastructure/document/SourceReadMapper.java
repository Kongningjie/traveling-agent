package com.lawrence.travel.infrastructure.document;

import com.lawrence.travel.application.document.SourceRead;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 归属及水位SQL；所有正文仅在私有范围内按绑定参数读取。 */
public interface SourceReadMapper {
    /**
     * @param s 活跃读取
     * @param reference 实际片段
     * @return 本次预算和token的精确来源
     */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM source_read_result WHERE user_id=#{s.user} AND chat_id=#{s.chat} AND budget_id=#{s.budget} AND token=#{s.token} AND id=#{reference} AND document_id IS NOT NULL)")
    boolean fromAttempt(@Param("s") SourceRead.Scope s, @Param("reference") UUID reference);

    /**
     * @param user 用户
     * @return 锁定有效身份后的偏好水位
     */
    @Select(
            "SELECT preference_revision FROM app_user WHERE id=#{user} AND account_status='ACTIVE' FOR NO KEY UPDATE")
    Long preference(@Param("user") UUID user);

    /**
     * @param user 用户
     * @param chat 聊天
     * @return 锁定当前资料水位，不存在返回空
     */
    @Select(
            "SELECT document_revision FROM dialogue_chat WHERE user_id=#{user} AND id=#{chat} FOR NO KEY UPDATE")
    Long revision(@Param("user") UUID user, @Param("chat") UUID chat);

    /**
     * @param budget 原输入预算
     * @return 当前仍有效的聊天尝试；非聊天预算不获得任意文件权限
     */
    @Select(
            "SELECT o.user_id AS user,o.chat_id AS chat,o.budget_id AS budget,j.token,c.document_revision AS revision,u.preference_revision AS preference,i.sequence AS before FROM operation_context o JOIN dialogue_chat c ON c.user_id=o.user_id AND c.id=o.chat_id JOIN app_user u ON u.id=o.user_id JOIN background_job j ON j.operation_id=o.id AND j.kind='CHAT_INPUT' JOIN dialogue_input i ON i.job_id=j.id WHERE o.budget_id=#{budget} AND j.state='RUNNING' AND j.source_version=c.work_revision AND j.lease_until>clock_timestamp() AND u.account_status='ACTIVE'")
    SourceRead.Scope operation(@Param("budget") UUID budget);

    /**
     * @param s 首次读取绑定；重复绑定不能更新资料水位给旧执行重新授权
     */
    @Insert(
            "INSERT INTO source_read_attempt(budget_id,token,user_id,chat_id,revision) VALUES(#{s.budget},#{s.token},#{s.user},#{s.chat},#{s.revision}) ON CONFLICT DO NOTHING")
    void bind(@Param("s") SourceRead.Scope s);

    /**
     * @param s 原尝试
     * @return 首次读取资料水位
     */
    @Select(
            "SELECT revision FROM source_read_attempt WHERE budget_id=#{s.budget} AND token=#{s.token}")
    Long bound(@Param("s") SourceRead.Scope s);

    /**
     * @param s 私有范围
     * @param characters 本次新增字符
     * @return 加入本次结果后是否超过总量
     */
    @Select(
            "SELECT count(*)>=200 OR COALESCE(sum(char_length(body)),0)+#{characters}>2000000 FROM source_read_result WHERE user_id=#{s.user} AND chat_id=#{s.chat}")
    boolean full(@Param("s") SourceRead.Scope s, @Param("characters") int characters);

    /**
     * @param s 归属
     * @param id 引用
     * @param document 文档
     * @param body 原文
     * @param path 逻辑文件
     * @param hash 完整性
     */
    @Insert(
            "INSERT INTO source_read_result(id,user_id,chat_id,budget_id,token,revision,preference,document_id,path,body,hash) VALUES(#{id},#{s.user},#{s.chat},#{s.budget},#{s.token},#{s.revision},#{s.preference},#{document},#{path},#{body},#{hash}) ON CONFLICT(budget_id,token,path) DO NOTHING")
    void save(
            @Param("s") SourceRead.Scope s,
            @Param("id") UUID id,
            @Param("document") UUID document,
            @Param("body") String body,
            @Param("path") String path,
            @Param("hash") String hash);

    /**
     * @param s 归属及水位
     * @param id 引用，可空
     * @param path 本次尝试路径，可空
     * @return 正文与哈希，不接受跨水位引用
     */
    @Select(
            "SELECT r.body,r.hash FROM source_read_result r WHERE r.user_id=#{s.user} AND r.chat_id=#{s.chat} AND r.revision=#{s.revision} AND r.preference=#{s.preference} AND ((#{id}::uuid IS NOT NULL AND r.id=#{id}) OR (#{path}::text IS NOT NULL AND r.path=#{path})) AND (r.budget_id IS NULL OR (r.budget_id=#{s.budget} AND r.token=#{s.token}) OR EXISTS(SELECT 1 FROM background_job j WHERE j.operation_id=r.budget_id AND j.kind='CHAT_INPUT' AND j.state IN('COMPLETED','WAITING_USER') AND j.token=r.token+1)) AND (r.document_id IS NULL OR EXISTS(SELECT 1 FROM uploaded_document d JOIN background_job j ON j.id=d.job_id WHERE d.id=r.document_id AND NOT d.deleted AND j.state='COMPLETED')) ORDER BY r.created_at DESC LIMIT 1")
    Stored read(@Param("s") SourceRead.Scope s, @Param("id") UUID id, @Param("path") String path);

    /**
     * @param body 权威正文
     * @param hash 防损坏哈希
     */
    record Stored(String body, String hash) {}

    /**
     * @param s 当前范围
     * @param query 字面词；百分号和下划线没有通配语义
     * @return 仅当前偏好且早于本轮的最多5条
     */
    @Select(
            "SELECT id,sequence,text,reply FROM dialogue_input WHERE user_id=#{s.user} AND chat_id=#{s.chat} AND sequence<#{s.before} AND preference_revision=#{s.preference} AND state IN('DONE','CLARIFY','CONFIRM') AND (strpos(lower(text),lower(#{query}))>0 OR strpos(lower(reply),lower(#{query}))>0) ORDER BY sequence DESC LIMIT 5")
    List<SourceRead.Conversation> history(
            @Param("s") SourceRead.Scope s, @Param("query") String query);

    /**
     * @param s 当前资料水位
     * @param message 私有输入
     * @return 仅成功尝试的实际资料引用
     */
    @Select(
            "SELECT r.id::text FROM source_read_result r JOIN operation_context o ON o.budget_id=r.budget_id JOIN background_job j ON j.operation_id=o.id AND j.kind='CHAT_INPUT' JOIN dialogue_input i ON i.job_id=j.id WHERE r.user_id=#{s.user} AND r.chat_id=#{s.chat} AND r.revision=#{s.revision} AND r.preference=#{s.preference} AND r.document_id IS NOT NULL AND i.id=#{message} AND j.state IN('COMPLETED','WAITING_USER') AND j.token=r.token+1 ORDER BY r.created_at,r.id LIMIT 10")
    List<String> references(@Param("s") SourceRead.Scope s, @Param("message") UUID message);

    /**
     * @param s 当前有效读取
     * @return 当前或成功尝试的有界工作文件索引，旧偏好/资料均不可恢复
     */
    @Select(
            "SELECT r.path,r.hash,char_length(r.body) AS characters FROM source_read_result r WHERE r.user_id=#{s.user} AND r.chat_id=#{s.chat} AND r.revision=#{s.revision} AND r.preference=#{s.preference} AND r.path IS NOT NULL AND ((r.budget_id=#{s.budget} AND r.token=#{s.token}) OR EXISTS(SELECT 1 FROM background_job j WHERE j.operation_id=r.budget_id AND j.kind='CHAT_INPUT' AND j.state IN('COMPLETED','WAITING_USER') AND j.token=r.token+1)) ORDER BY r.created_at DESC,r.id LIMIT 10")
    List<SourceRead.WorkFile> files(@Param("s") SourceRead.Scope s);

    /**
     * @param s 当前范围
     * @param path 已经应用校验的工作文件
     * @return 同源尝试的真实引用，仅当前水位与偏好有效
     */
    @Select(
            "SELECT r.body FROM source_read_result r WHERE r.user_id=#{s.user} AND r.chat_id=#{s.chat} AND r.revision=#{s.revision} AND r.preference=#{s.preference} AND r.document_id IS NOT NULL AND EXISTS(SELECT 1 FROM source_read_result f WHERE f.user_id=r.user_id AND f.chat_id=r.chat_id AND f.budget_id=r.budget_id AND f.token=r.token AND f.path=#{path} AND f.revision=r.revision AND f.preference=r.preference AND ((f.budget_id=#{s.budget} AND f.token=#{s.token}) OR EXISTS(SELECT 1 FROM background_job j WHERE j.operation_id=f.budget_id AND j.kind='CHAT_INPUT' AND j.state IN('COMPLETED','WAITING_USER') AND j.token=f.token+1))) ORDER BY r.created_at,r.id LIMIT 10")
    List<String> fileReferences(@Param("s") SourceRead.Scope s, @Param("path") String path);
}
