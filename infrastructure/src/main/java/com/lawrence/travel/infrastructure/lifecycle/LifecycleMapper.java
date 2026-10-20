package com.lawrence.travel.infrastructure.lifecycle;

import com.lawrence.travel.application.lifecycle.Lifecycle;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 明确生命周期SQL；删除先按归属锁定，恢复不得执行任意路径或SQL。 */
public interface LifecycleMapper {
    /**
     * @param user 当前身份
     * @return 活跃用户行锁
     */
    @Select(
            "SELECT id::text FROM app_user WHERE id=#{user} AND account_status='ACTIVE' FOR NO KEY"
                    + " UPDATE")
    String user(@Param("user") UUID user);

    /**
     * @param user 当前身份
     * @param id 聊天
     * @return 私有范围锁
     */
    @Select(
            "SELECT id,task_id FROM dialogue_chat WHERE user_id=#{user} AND id=#{id} FOR NO KEY"
                    + " UPDATE")
    Scope chat(@Param("user") UUID user, @Param("id") UUID id);

    /**
     * @param user 当前身份
     * @param id 旅行
     * @return 已关联聊天，未关联时chat为空
     */
    @Select(
            "SELECT c.id,t.id AS task_id FROM travel_task t LEFT JOIN dialogue_chat c ON"
                + " c.user_id=t.user_id AND c.task_id=t.id WHERE t.user_id=#{user} AND t.id=#{id}"
                + " FOR NO KEY UPDATE OF t")
    Scope task(@Param("user") UUID user, @Param("id") UUID id);

    /**
     * @param user 当前身份
     * @param id 附件
     * @return 仍存在的私有来源
     */
    @Select(
            "SELECT d.chat_id AS id,c.task_id FROM uploaded_document d JOIN dialogue_chat c ON"
                + " c.user_id=d.user_id AND c.id=d.chat_id WHERE d.user_id=#{user} AND d.id=#{id}"
                + " AND NOT d.deleted FOR NO KEY UPDATE OF c")
    Scope document(@Param("user") UUID user, @Param("id") UUID id);

    /**
     * @param id 聊天可空
     * @param taskId 旅行可空
     */
    record Scope(UUID id, UUID taskId) {}

    /**
     * @param user 归属
     * @param c 聊天可空
     * @param t 旅行可空
     * @param doc 单附件范围可空
     * @return 对象集与输入/业务版本摘要，不含原文
     */
    @Select(
            "SELECT encode(sha256(convert_to(concat_ws('|',#{c}::text,#{t}::text,(SELECT"
                + " work_revision::text||':'||document_revision::text FROM dialogue_chat WHERE"
                + " user_id=#{user} AND id=#{c}),(SELECT lock_version::text FROM travel_task WHERE"
                + " user_id=#{user} AND id=#{t}),(SELECT max(sequence)::text FROM dialogue_input"
                + " WHERE user_id=#{user} AND chat_id=#{c}),(SELECT string_agg(id::text||hash,','"
                + " ORDER BY id) FROM uploaded_document WHERE user_id=#{user} AND chat_id=#{c} AND"
                + " NOT deleted AND (#{doc}::uuid IS NULL OR id=#{doc})),(SELECT"
                + " string_agg(id::text,',' ORDER BY id) FROM itinerary_snapshot WHERE"
                + " user_id=#{user} AND task_id=#{t})),'UTF8')),'hex')")
    String revision(
            @Param("user") UUID user,
            @Param("c") UUID c,
            @Param("t") UUID t,
            @Param("doc") UUID doc);

    /**
     * @param user 归属
     * @param c 聊天
     * @param t 旅行
     * @param doc 单附件可空
     * @return 完整影响数量
     */
    @Select(
            "SELECT (SELECT count(*) FROM uploaded_document WHERE user_id=#{user} AND chat_id=#{c}"
                + " AND NOT deleted AND (#{doc}::uuid IS NULL OR id=#{doc})) AS documents,(SELECT"
                + " count(*) FROM itinerary_snapshot WHERE user_id=#{user} AND task_id=#{t}) AS"
                + " versions")
    Counts counts(
            @Param("user") UUID user,
            @Param("c") UUID c,
            @Param("t") UUID t,
            @Param("doc") UUID doc);

    /**
     * @param documents 附件数
     * @param versions 版本数
     */
    record Counts(long documents, long versions) {}

    /**
     * @param user 归属
     * @param c 聊天
     * @param t 旅行
     * @param doc 单附件可空
     * @return 至多41个有界名称，应用明确截断
     */
    @Select(
            "SELECT kind,id,name FROM (SELECT 0 AS rank,'CHAT' AS kind,id,title AS name FROM"
                + " dialogue_chat WHERE user_id=#{user} AND id=#{c} AND #{doc}::uuid IS NULL UNION"
                + " ALL SELECT 1,'TASK',id,title FROM travel_task WHERE user_id=#{user} AND id=#{t}"
                + " AND #{doc}::uuid IS NULL UNION ALL SELECT 2,'DOCUMENT',id,name FROM"
                + " uploaded_document WHERE user_id=#{user} AND chat_id=#{c} AND NOT deleted AND"
                + " (#{doc}::uuid IS NULL OR id=#{doc}) UNION ALL SELECT 3,'VERSION',id,'行程版本"
                + " '||created_at::date FROM itinerary_snapshot WHERE user_id=#{user} AND"
                + " task_id=#{t} AND #{doc}::uuid IS NULL) r ORDER BY rank,id LIMIT 41")
    List<Lifecycle.Resource> resources(
            @Param("user") UUID user,
            @Param("c") UUID c,
            @Param("t") UUID t,
            @Param("doc") UUID doc);

    /**
     * @return 序列锁，阻止独立清单与PG出现乱序
     */
    @Select("SELECT instance,sequence,hash FROM lifecycle_state WHERE id=1 FOR UPDATE")
    Lifecycle.Head head();

    /**
     * @return 当前不加锁水位
     */
    @Select("SELECT instance,sequence,hash FROM lifecycle_state WHERE id=1")
    Lifecycle.Head status();

    /**
     * @return 首次部署是否完成独立清单初始化
     */
    @Select("SELECT initialized FROM lifecycle_state WHERE id=1")
    boolean initialized();

    /**
     * @param user 归属
     * @param id 幂等请求
     * @return 已确认墓碑
     */
    @Select(
            "SELECT sequence,id,user_id AS user,kind,target_id AS target,chat_id AS chat,task_id AS"
                    + " task,deleted_at FROM deletion_tombstone WHERE user_id=#{user} AND id=#{id}")
    Lifecycle.Entry replay(@Param("user") UUID user, @Param("id") UUID id);

    /**
     * @param user 归属
     * @param task 旅行
     * @return 非终态运行
     */
    @Select(
            "SELECT id::text FROM execution_run WHERE user_id=#{user} AND task_id=#{task} AND state"
                + " IN('QUEUED','RUNNING','WAITING_USER','PAUSED_QUOTA','PAUSED_BUDGET','WAITING_ACCEPTANCE')"
                + " ORDER BY id")
    List<String> runs(@Param("user") UUID user, @Param("task") UUID task);

    /**
     * @param e 已验证技术墓碑；函数执行全部正文删除及文件回收登记
     */
    @Select(
            value =
                    "SELECT"
                        + " apply_deletion(#{e.sequence},#{e.id},#{e.user},#{e.kind},#{e.target},#{e.chat},#{e.task},#{e.deletedAt})",
            affectData = true)
    String apply(@Param("e") Lifecycle.Entry e);

    /**
     * @param h 原子推进的清单头
     */
    @Update(
            "UPDATE lifecycle_state SET sequence=#{h.sequence},hash=#{h.hash},initialized=true"
                    + " WHERE id=1 AND instance=#{h.instance}")
    void advance(@Param("h") Lifecycle.Head h);

    /**
     * @param now 当前时间
     * @return 待清理原件，优先处理较早删除
     */
    @Select(
            "SELECT id,user_id AS user,file_key AS key,document,attempts FROM deleted_file WHERE"
                    + " purged_at IS NULL AND next_at<=#{now} ORDER BY deleted_at,id LIMIT 30")
    List<Lifecycle.File> files(@Param("now") Instant now);

    /**
     * @param id 实际清理项
     */
    @Update(
            "UPDATE deleted_file SET purged_at=clock_timestamp(),code='' WHERE id=#{id} AND"
                    + " purged_at IS NULL")
    void purged(@Param("id") UUID id);

    /**
     * @param id 清理项，旧上传索引只保留技术墓碑
     */
    @Update(
            "UPDATE uploaded_document SET purged=true WHERE deleted AND file_key=(SELECT file_key"
                    + " FROM deleted_file WHERE id=#{id})")
    void documentPurged(@Param("id") UUID id);

    /**
     * @param f 固定失败项
     * @param next 下次重试
     */
    @Update(
            "UPDATE deleted_file SET attempts=attempts+1,next_at=#{next},code='CLEANUP_FAILED'"
                    + " WHERE id=#{f.id} AND purged_at IS NULL")
    void failed(@Param("f") Lifecycle.File f, @Param("next") Instant next);

    /**
     * @param f 失败项
     * @return 每清理项只记录固定分类，不写原始异常
     */
    @Insert(
            "INSERT INTO problem_event(id,user_id,component,code,severity) SELECT"
                    + " gen_random_uuid(),#{f.user},'LIFECYCLE','CLEANUP_FAILED','ERROR' WHERE"
                    + " EXISTS(SELECT 1 FROM app_user WHERE id=#{f.user}) AND #{f.attempts}=0")
    void problem(@Param("f") Lifecycle.File f);

    /**
     * @param cutoff 诊断期限
     * @return 本次删除数量；反馈随诊断删除
     */
    @org.apache.ibatis.annotations.Delete(
            "DELETE FROM problem_event WHERE id IN(SELECT id FROM problem_event WHERE"
                    + " created_at<#{cutoff} ORDER BY created_at LIMIT 1000)")
    int pruneProblems(@Param("cutoff") Instant cutoff);

    /**
     * @param cutoff 诊断期限
     * @return 本次删除数量
     */
    @org.apache.ibatis.annotations.Delete(
            "DELETE FROM execution_trace_span WHERE id IN(SELECT id FROM execution_trace_span WHERE"
                    + " started_at<#{cutoff} ORDER BY started_at LIMIT 1000)")
    int pruneSpans(@Param("cutoff") Instant cutoff);

    /**
     * @param cutoff 诊断期限
     * @return 仅清除无引用的旧聚合，重复关系仍可追溯
     */
    @org.apache.ibatis.annotations.Delete(
            "DELETE FROM problem_group WHERE id IN(SELECT g.id FROM problem_group g WHERE"
                + " last_at<#{cutoff} AND NOT EXISTS(SELECT 1 FROM problem_event e WHERE"
                + " e.group_id=g.id) AND NOT EXISTS(SELECT 1 FROM problem_group d WHERE"
                + " d.duplicate_of=g.id) ORDER BY last_at,g.id LIMIT 1000)")
    int pruneGroups(@Param("cutoff") Instant cutoff);

    /**
     * @param now 受控当前时间
     * @return 最小清理指标，无用户正文
     */
    @Select(
            "SELECT count(*) FILTER(WHERE purged_at IS NULL) AS pending,count(*) FILTER(WHERE"
                + " purged_at IS NULL AND deleted_at<CAST(#{now} AS timestamptz)-interval '24"
                + " hour') AS overdue,count(*) FILTER(WHERE purged_at IS NULL AND attempts>0) AS"
                + " failed,(SELECT dropped FROM diagnostic_capacity WHERE id=1) AS"
                + " diagnostics_dropped FROM deleted_file")
    Lifecycle.Health health(@Param("now") Instant now);

    /**
     * @param user 当前归属
     * @param chat 聊天
     * @param document 来源
     * @return 来源解析/旧来源读取者，调用方按既有取消用例结算
     */
    @Select(
            "SELECT id::text FROM background_job WHERE user_id=#{user} AND chat_id=#{chat} AND (id"
                + " IN(SELECT job_id FROM uploaded_document WHERE user_id=#{user} AND"
                + " id=#{document}) OR (kind='CHAT_INPUT' AND operation_id IN(SELECT o.id FROM"
                + " operation_context o JOIN source_read_attempt a ON a.budget_id=o.budget_id WHERE"
                + " o.user_id=#{user} AND o.chat_id=#{chat}))) AND state NOT"
                + " IN('COMPLETED','CANCELLED','SUPERSEDED','FAILED') ORDER BY id")
    List<String> sourceJobs(
            @Param("user") UUID user, @Param("chat") UUID chat, @Param("document") UUID document);

    /**
     * @param e 本人技术墓碑 @return 对应范围是否仍有未清理文件；不查全局队列计数
     */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM deleted_file f WHERE f.user_id=#{e.user} AND f.purged_at"
                + " IS NULL AND ((#{e.kind}='DOCUMENT' AND f.document AND"
                + " f.file_key='documents/'||CAST(#{e.user} AS text)||'/'||CAST(#{e.chat} AS"
                + " text)||'/'||CAST(#{e.target} AS text)||'.bin') OR (#{e.kind}<>'DOCUMENT' AND"
                + " ((f.document AND strpos(f.file_key,'documents/'||CAST(#{e.user} AS"
                + " text)||'/'||CAST(#{e.chat} AS text)||'/')=1) OR (NOT f.document AND"
                + " strpos(f.file_key,'users/'||CAST(#{e.user} AS text)||'/tasks/'||CAST(#{e.task}"
                + " AS text)||'/')=1)))))")
    boolean pending(@Param("e") Lifecycle.Entry e);

    /**
     * @param request 全局UUID @return 是否已使用，不返回其他用户身份或删除正文
     */
    @Select("SELECT EXISTS(SELECT 1 FROM deletion_tombstone WHERE id=#{request})")
    boolean requestUsed(@Param("request") UUID request);
}
