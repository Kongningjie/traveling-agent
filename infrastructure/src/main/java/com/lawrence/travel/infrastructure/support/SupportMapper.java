package com.lawrence.travel.infrastructure.support;

import com.lawrence.travel.application.support.Support;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

/** 反馈和维护SQL；所有私有对象用归属绑定，运维权限由当前DB授权表决定。 */
public interface SupportMapper {
    /**
     * @param user 用户
     * @return 活跃身份锁，不以旧Session角色授权维护
     */
    @Select(
            "SELECT id::text FROM app_user WHERE id=#{user} AND account_status='ACTIVE' FOR NO KEY"
                    + " UPDATE")
    String lock(@Param("user") UUID user);

    /**
     * @param user 用户
     * @param id 原请求
     * @return 私有完整行，正文只供应用敏感读取
     */
    @Select(
            "SELECT"
                + " id,problem_id,request_hash,target_kind,target_scope,target_id,category,description,consent,reference_id,chat_id,created_at"
                + " FROM user_feedback WHERE user_id=#{user} AND id=#{id}")
    Row own(@Param("user") UUID user, @Param("id") UUID id);

    /**
     * @param id 精确反馈
     * @return 维护读取，调用前必须校验敏感权限
     */
    @Select(
            "SELECT"
                + " id,problem_id,request_hash,target_kind,target_scope,target_id,category,description,consent,reference_id,chat_id,created_at"
                + " FROM user_feedback WHERE id=#{id}")
    Row privateRow(@Param("id") UUID id);

    /**
     * @param id 反馈
     * @param problemId 问题
     * @param requestHash 幂等摘要
     * @param targetKind 种类 @param targetScope 范围
     * @param targetId 对象
     * @param category 分类
     * @param description 用户填写 @param consent 单引用许可
     * @param referenceId 可空
     * @param chatId 可空
     * @param createdAt 时间
     */
    record Row(
            UUID id,
            UUID problemId,
            String requestHash,
            String targetKind,
            UUID targetScope,
            UUID targetId,
            String category,
            String description,
            boolean consent,
            UUID referenceId,
            UUID chatId,
            java.time.Instant createdAt) {}

    /**
     * @param user 用户
     * @param t 精确目标
     * @return 归属检查后的链路；不读取消息或附件正文
     */
    @Select(
            """
            <script>
            <choose>
            <when test="t.kind.name() == 'RUN'">SELECT r.trace_id AS trace,r.id AS span,r.request_id AS request,NULL::uuid AS chat,NULL::uuid AS document FROM execution_run r WHERE r.user_id=#{user} AND r.task_id=#{t.scope} AND r.id=#{t.id}</when>
            <when test="t.kind.name() == 'DOCUMENT'">SELECT o.trace_id AS trace,(SELECT a.span_id FROM job_attempt a WHERE a.job_id=j.id ORDER BY token DESC LIMIT 1) AS span,o.request_id AS request,d.chat_id AS chat,d.id AS document FROM uploaded_document d JOIN background_job j ON j.id=d.job_id JOIN operation_context o ON o.id=j.operation_id AND o.user_id=j.user_id AND o.chat_id=j.chat_id WHERE d.user_id=#{user} AND d.chat_id=#{t.scope} AND d.id=#{t.id} AND NOT d.deleted</when>
            <otherwise>SELECT o.trace_id AS trace,(SELECT a.span_id FROM job_attempt a WHERE a.job_id=j.id ORDER BY token DESC LIMIT 1) AS span,o.request_id AS request,i.chat_id AS chat,<choose><when test="t.kind.name() == 'ADVICE'">a.document_id</when><otherwise>NULL::uuid</otherwise></choose> AS document FROM dialogue_input i LEFT JOIN background_job j ON j.id=i.job_id LEFT JOIN operation_context o ON o.id=j.operation_id AND o.user_id=j.user_id AND o.chat_id=j.chat_id <if test="t.kind.name() == 'ADVICE'">JOIN plan_advice a ON a.user_id=i.user_id AND a.chat_id=i.chat_id AND a.message_id=i.id</if> WHERE i.user_id=#{user} AND i.chat_id=#{t.scope} AND <choose><when test="t.kind.name() == 'ADVICE'">a.id=#{t.id}</when><otherwise>i.id=#{t.id} AND i.reply&lt;&gt;''</otherwise></choose></otherwise>
            </choose>
            </script>
            """)
    Support.Link locate(@Param("user") UUID user, @Param("t") Support.Target t);

    /**
     * @param user 用户
     * @return 最近一小时反馈次数，用户锁下判定
     */
    @Select(
            "SELECT count(*) FROM user_feedback WHERE user_id=#{user} AND"
                    + " created_at>clock_timestamp()-interval '1 hour'")
    int feedbackCount(@Param("user") UUID user);

    /**
     * @param id 固定问题
     * @param user 受控主体
     * @param link 原链路
     * @param component 固定组件
     * @param code 固定分类
     * @return 实际保存行数，容量丢弃必须返回0
     */
    @Insert(
            "INSERT INTO"
                + " problem_event(id,user_id,trace_id,span_id,request_id,component,code,severity)"
                + " VALUES(#{id},#{user},#{link.trace},#{link.span},#{link.request},#{component},#{code},'ERROR')"
                + " ON CONFLICT DO NOTHING")
    int problem(
            @Param("id") UUID id,
            @Param("user") UUID user,
            @Param("link") Support.Link link,
            @Param("component") String component,
            @Param("code") String code);

    /**
     * @param user 用户
     * @param in 明确反馈
     * @param hash 幂等摘要
     * @param problem 问题
     * @param chat 关联聊天
     */
    @Insert(
            "INSERT INTO"
                + " user_feedback(id,user_id,problem_id,request_hash,target_kind,target_scope,target_id,category,description,consent,reference_id,chat_id)"
                + " VALUES(#{in.id},#{user},#{problem},#{hash},#{in.target.kind},#{in.target.scope},#{in.target.id},#{in.category},#{in.description},#{in.consent},#{in.reference},#{chat})")
    void insert(
            @Param("user") UUID user,
            @Param("in") Support.Input in,
            @Param("hash") String hash,
            @Param("problem") UUID problem,
            @Param("chat") UUID chat);

    /**
     * @param user 用户
     * @param offset 分页
     * @return 仅本人，不返回其他人的聚合内容
     */
    @Select(
            "SELECT f.id,f.problem_id AS problem,f.category,f.target_kind AS kind,f.target_scope AS"
                + " scope,f.target_id AS target,g.state,f.created_at AS created FROM user_feedback"
                + " f JOIN problem_event e ON e.id=f.problem_id JOIN problem_group g ON"
                + " g.id=e.group_id WHERE f.user_id=#{user} ORDER BY f.created_at DESC,f.id LIMIT"
                + " 20 OFFSET #{offset}")
    List<Summary> feedback(@Param("user") UUID user, @Param("offset") int offset);

    /**
     * @param id 反馈
     * @param problem 问题
     * @param category 类别
     * @param kind 目标
     * @param scope 范围 @param target 精确对象
     * @param state 聚合状态
     * @param created 时间
     */
    record Summary(
            UUID id,
            UUID problem,
            String category,
            String kind,
            UUID scope,
            UUID target,
            String state,
            java.time.Instant created) {}

    /**
     * @param id 问题
     * @return 当前聚合状态，不返回其他人反馈
     */
    @Select(
            "SELECT g.state FROM problem_event e JOIN problem_group g ON g.id=e.group_id WHERE"
                    + " e.id=#{id}")
    String state(@Param("id") UUID id);

    /**
     * @param actor 操作者
     * @param sensitive 是否要求敏感授权
     * @return 实时权限，不信任浏览器或注册字段
     */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM maintenance_grant m JOIN app_user u ON u.id=m.user_id"
                + " WHERE m.user_id=#{actor} AND u.account_status='ACTIVE' AND (NOT #{sensitive} OR"
                + " m.sensitive))")
    boolean allowed(@Param("actor") UUID actor, @Param("sensitive") boolean sensitive);

    /**
     * @param actor 操作者
     * @param action 固定读取/维护动作
     * @param target 对象
     * @param request 当前请求
     */
    @Insert(
            "INSERT INTO maintenance_audit(actor_id,action,target_id,request_id)"
                    + " VALUES(#{actor},#{action},#{target},#{request})")
    void audit(
            @Param("actor") UUID actor,
            @Param("action") String action,
            @Param("target") UUID target,
            @Param("request") UUID request);

    /**
     * @param offset 分页
     * @return 固定元数据，无用户正文
     */
    @Select(
            "SELECT id,component,code,severity,occurrences AS count,first_at AS first,last_at AS"
                + " last,state,revision,cause,repair,repair_version AS"
                + " version,regression,representative FROM problem_group WHERE severity<>'INFO' AND"
                + " occurrences>0 ORDER BY last_at DESC,id LIMIT 20 OFFSET #{offset}")
    List<Support.Group> groups(@Param("offset") int offset);

    /**
     * @param id 聚合或用户问题编号
     * @return 锁定问题组，便于CAS及审计
     */
    @Select(
            "SELECT id,component,code,severity,occurrences AS count,first_at AS first,last_at AS"
                + " last,state,revision,cause,repair,repair_version AS"
                + " version,regression,representative FROM problem_group g WHERE id=#{id} OR"
                + " EXISTS(SELECT 1 FROM problem_event e WHERE e.id=#{id} AND e.group_id=g.id) FOR"
                + " UPDATE")
    Support.Group group(@Param("id") UUID id);

    /**
     * @param actor 维护者
     * @param id 精确组ID
     * @param c 版本和修复依据
     * @return CAS修改数
     */
    @Update(
            "UPDATE problem_group SET"
                + " state=#{c.next},revision=revision+1,cause=#{c.cause},repair=#{c.repair},repair_version=#{c.version},regression=#{c.regression},duplicate_of=#{c.duplicateOf},maintained_by=#{actor}"
                + " WHERE id=#{id} AND revision=#{c.revision}")
    int change(@Param("actor") UUID actor, @Param("id") UUID id, @Param("c") Support.Change c);

    /**
     * @param group 聚合编号
     * @return 最多100个问题的精确步骤，不读取正文
     */
    @Select(
            "SELECT id,trace_id AS trace,span_id AS span,request_id AS request,created_at AS"
                + " created FROM problem_event WHERE group_id=#{group} ORDER BY created_at DESC,id"
                + " LIMIT 100")
    List<Support.Event> events(@Param("group") UUID group);

    /**
     * @param trace 精确链路
     * @return 最多201条供应用判断截断，绝不返回metadata或正文
     */
    @Select(
            "SELECT id,parent_id AS parent,kind,state,code,started_at AS started,ended_at AS"
                + " ended,run_id AS run FROM execution_trace_span WHERE trace_id=#{trace} ORDER BY"
                + " started_at,id LIMIT 201")
    List<Support.Span> spans(@Param("trace") UUID trace);

    /**
     * @return PG全局诊断容量丢弃数量
     */
    @Select("SELECT dropped FROM diagnostic_capacity WHERE id=1")
    long dropped();

    /**
     * @param user 登录主体
     * @param id 固定输入
     * @return 幂等客户端记录，可空
     */
    @Select(
            "SELECT code,component,problem_id FROM client_issue_receipt WHERE user_id=#{user} AND"
                    + " id=#{id}")
    Client oldClient(@Param("user") UUID user, @Param("id") UUID id);

    /**
     * @param code 固定错误
     * @param component 固定位置
     * @param problemId 问题
     */
    record Client(String code, String component, UUID problemId) {}

    /**
     * @param user 用户
     * @return 当前小时上报量
     */
    @Select(
            "SELECT count(*) FROM client_issue_receipt WHERE user_id=#{user} AND"
                    + " created_at>clock_timestamp()-interval '1 hour'")
    int clientCount(@Param("user") UUID user);

    /**
     * @param user 用户
     * @param id 原请求
     * @param code 固定分类
     * @param component 固定组件
     * @param problem 实际问题
     */
    @Insert(
            "INSERT INTO client_issue_receipt(user_id,id,code,component,problem_id)"
                    + " VALUES(#{user},#{id},#{code},#{component},#{problem})")
    void client(
            @Param("user") UUID user,
            @Param("id") UUID id,
            @Param("code") String code,
            @Param("component") String component,
            @Param("problem") UUID problem);
}
