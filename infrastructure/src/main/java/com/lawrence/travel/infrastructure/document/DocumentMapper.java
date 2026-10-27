package com.lawrence.travel.infrastructure.document;

import com.lawrence.travel.application.document.Documents;
import com.lawrence.travel.application.execution.Jobs;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;

/** 显式归属SQL；锁序user→chat→job，元数据没有公开路径接口。 */
public interface DocumentMapper {
    /**
     * @param s 私有范围
     * @return 已读取失效资料的活跃聊天作业，调用方通过JobStore取消并结算
     */
    @Select(
            "SELECT DISTINCT j.id::text FROM background_job j JOIN operation_context o ON o.id=j.operation_id JOIN source_read_attempt a ON a.budget_id=o.budget_id JOIN dialogue_chat c ON c.user_id=j.user_id AND c.id=j.chat_id WHERE j.user_id=#{s.userId} AND j.chat_id=#{s.chatId} AND j.kind='CHAT_INPUT' AND j.state IN('QUEUED','RUNNING','WAITING_USER','WAITING_DEPENDENCY') AND a.revision<c.document_revision AND a.token IN(j.token,j.token-1)")
    List<String> revokedReaders(@Param("s") Jobs.Scope s);

    /**
     * @param s 归属
     * @return 有效用户并持锁
     */
    @Select(
            "SELECT id::text FROM app_user WHERE id=#{s.userId} AND account_status='ACTIVE' FOR NO KEY UPDATE")
    String user(@Param("s") Jobs.Scope s);

    /**
     * @param s 归属
     * @return 同用户聊天并持锁
     */
    @Select(
            "SELECT id::text FROM dialogue_chat WHERE user_id=#{s.userId} AND id=#{s.chatId} FOR NO KEY UPDATE")
    String chat(@Param("s") Jobs.Scope s);

    /**
     * @param s 归属
     * @return 用户尚未清理原件总字节
     */
    @Select(
            "SELECT COALESCE(sum(size),0) FROM uploaded_document WHERE user_id=#{s.userId} AND NOT purged")
    long bytes(@Param("s") Jobs.Scope s);

    /**
     * @param s 归属
     * @return 聊天有效附件数
     */
    @Select(
            "SELECT count(*) FROM uploaded_document WHERE user_id=#{s.userId} AND chat_id=#{s.chatId} AND NOT deleted")
    int count(@Param("s") Jobs.Scope s);

    /**
     * @param s 归属
     * @param reserved 待解析单文件预留
     * @return 完成文本实际字符和待处理20万预留；删除正文已清除
     */
    @Select(
            "SELECT COALESCE(sum(CASE WHEN report IS NULL THEN #{reserved} ELSE COALESCE((SELECT sum(char_length(v->>'text')) FROM jsonb_array_elements(report->'sections') v),0) END),0) FROM uploaded_document WHERE user_id=#{s.userId} AND NOT deleted")
    long characters(@Param("s") Jobs.Scope s, @Param("reserved") int reserved);

    /**
     * @return 有效原件引用，包括仍待物理清理墓碑
     */
    @Select("SELECT file_key FROM uploaded_document WHERE NOT purged")
    java.util.Set<String> references();

    /**
     * @param s 用户
     * @param id 排除正在重试的原件
     * @param reserved 待解析单文件预留
     * @return 其他资料已占文本和预留
     */
    @Select(
            "SELECT COALESCE(sum(CASE WHEN report IS NULL THEN #{reserved} ELSE COALESCE((SELECT sum(char_length(v->>'text')) FROM jsonb_array_elements(report->'sections') v),0) END),0) FROM uploaded_document WHERE user_id=#{s.userId} AND id!=#{id} AND NOT deleted")
    long otherCharacters(
            @Param("s") Jobs.Scope s, @Param("id") UUID id, @Param("reserved") int reserved);

    /**
     * @param s 用户
     * @return 当前待处理请求，不因另开聊天绕过10个上限
     */
    @Select(
            "SELECT count(*) FROM background_job WHERE user_id=#{s.userId} AND state IN ('QUEUED','RUNNING','WAITING_DEPENDENCY')")
    int pending(@Param("s") Jobs.Scope s);

    /**
     * @param e 原子原件索引
     */
    @Insert(
            "INSERT INTO uploaded_document(id,user_id,chat_id,job_id,name,kind,hash,size,file_key) VALUES(#{e.id},#{e.userId},#{e.chatId},#{e.jobId},#{e.name},#{e.kind},#{e.hash},#{e.size},#{e.key})")
    void insert(@Param("e") Documents.Entry e);

    /**
     * @param s 归属
     * @param id 原件
     * @return 唯一索引，不跨用户
     */
    @Select(
            "SELECT id,user_id,chat_id,job_id,name,kind,hash,size,file_key AS key,deleted,report::text FROM uploaded_document WHERE user_id=#{s.userId} AND chat_id=#{s.chatId} AND id=#{id}")
    Documents.Entry get(@Param("s") Jobs.Scope s, @Param("id") UUID id);

    /**
     * @param s 归属
     * @return 本聊天有效索引
     */
    @Select(
            "SELECT id,user_id,chat_id,job_id,name,kind,hash,size,file_key AS key,deleted,report::text FROM uploaded_document WHERE user_id=#{s.userId} AND chat_id=#{s.chatId} AND NOT deleted ORDER BY created_at,id")
    List<Documents.Entry> list(@Param("s") Jobs.Scope s);

    /**
     * @param s 归属
     * @param id 原件
     * @param report 校验后全文，可空表示显式重试
     */
    @Update(
            "UPDATE uploaded_document SET report=CAST(#{report} AS jsonb) WHERE user_id=#{s.userId} AND chat_id=#{s.chatId} AND id=#{id} AND NOT deleted")
    void report(@Param("s") Jobs.Scope s, @Param("id") UUID id, @Param("report") String report);

    /**
     * @param s 归属
     * @param id 原件；保持墓碑，立即清除提取正文
     */
    @Update(
            "UPDATE uploaded_document SET deleted=true,name='已删除附件',deleted_at=COALESCE(deleted_at,clock_timestamp()),report=NULL WHERE user_id=#{s.userId} AND chat_id=#{s.chatId} AND id=#{id}")
    void delete(@Param("s") Jobs.Scope s, @Param("id") UUID id);

    /**
     * @return 有界待清理索引，无用户正文
     */
    @Select(
            "SELECT id,user_id,chat_id,job_id,name,kind,hash,size,file_key AS key,deleted,NULL::text AS report FROM uploaded_document WHERE deleted AND NOT purged ORDER BY deleted_at LIMIT 30")
    List<Documents.Entry> garbage();

    /**
     * @param id 已实际删除原件的墓碑
     */
    @Update("UPDATE uploaded_document SET purged=true WHERE id=#{id} AND deleted")
    void purged(@Param("id") UUID id);
}
