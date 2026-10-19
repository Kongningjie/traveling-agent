package com.lawrence.travel.infrastructure.support;

import com.lawrence.travel.application.execution.ExecutionCorrelation;
import com.lawrence.travel.application.port.SupportStore;
import com.lawrence.travel.application.support.Support;
import com.lawrence.travel.application.support.SupportFailure;
import com.lawrence.travel.domain.support.ProblemState;

import java.util.List;
import java.util.UUID;

/** PG权威反馈和实时维护权限；应用必须提供短事务，不执行网络IO。 */
public final class PostgresSupportStore implements SupportStore {
    private final SupportMapper sql;

    /**
     * @param sql 明确SQL端口
     */
    public PostgresSupportStore(SupportMapper sql) {
        this.sql = sql;
    }

    /** {@inheritDoc} 同用户锁串行计数与幂等。 */
    @Override
    public void lock(UUID user) {
        if (sql.lock(user) == null) throw fail("SUPPORT_NOT_FOUND");
    }

    /** {@inheritDoc} 异文不能覆盖已确认反馈。 */
    @Override
    public Support.Receipt replay(UUID user, UUID id, String hash) {
        var r = sql.own(user, id);
        if (r == null) return null;
        if (!r.requestHash().equals(hash)) throw fail("SUPPORT_CONFLICT");
        return receipt(r.id(), r.problemId());
    }

    /** {@inheritDoc} 不存在和跨用户统一拒绝。 */
    @Override
    public Support.Link locate(UUID user, Support.Target target) {
        var l = sql.locate(user, target);
        if (l == null) throw fail("SUPPORT_NOT_FOUND");
        return l;
    }

    /** {@inheritDoc} 容量丢弃或事务失败均无成功票据。 */
    @Override
    public Support.Receipt insert(UUID user, Support.Input input, String hash, Support.Link link) {
        if (sql.feedbackCount(user) >= 20) throw fail("SUPPORT_RATE_LIMIT");
        var p = UUID.randomUUID();
        if (sql.problem(p, user, link, "FEEDBACK", input.category().name()) != 1)
            throw fail("SUPPORT_CAPACITY");
        sql.insert(user, input, hash, p, link.chat());
        return receipt(input.id(), p);
    }

    /** {@inheritDoc} 不返回聚合内其他人的内容。 */
    @Override
    public List<Support.Feedback> feedback(UUID user, int offset) {
        return sql.feedback(user, offset).stream()
                .map(
                        r ->
                                new Support.Feedback(
                                        r.id(),
                                        r.problem(),
                                        Support.Category.valueOf(r.category()),
                                        new Support.Target(
                                                Support.Kind.valueOf(r.kind()),
                                                r.scope(),
                                                r.target()),
                                        ProblemState.valueOf(r.state()),
                                        r.created()))
                .toList();
    }

    /** {@inheritDoc} 本人读取与额外敏感授权均审计，只保存引用而不复制原文。 */
    @Override
    public Support.Detail detail(UUID actor, UUID id, boolean maintenance) {
        if (maintenance) require(actor, true);
        var r = maintenance ? sql.privateRow(id) : sql.own(actor, id);
        if (r == null) throw fail("SUPPORT_NOT_FOUND");
        audit(actor, maintenance ? "READ_PRIVATE_FEEDBACK" : "READ_OWN_FEEDBACK", id);
        var f =
                new Support.Feedback(
                        r.id(),
                        r.problemId(),
                        Support.Category.valueOf(r.category()),
                        new Support.Target(
                                Support.Kind.valueOf(r.targetKind()),
                                r.targetScope(),
                                r.targetId()),
                        ProblemState.valueOf(sql.state(r.problemId())),
                        r.createdAt());
        return new Support.Detail(f, r.description(), r.referenceId(), "");
    }

    /** {@inheritDoc} 不缓存Session管理员角色。 */
    @Override
    public boolean allowed(UUID actor, boolean sensitive) {
        return sql.allowed(actor, sensitive);
    }

    /** {@inheritDoc} 列表也记录访问事实。 */
    @Override
    public List<Support.Group> groups(UUID actor, int offset) {
        require(actor, false);
        audit(actor, "LIST_GROUPS", null);
        return sql.groups(offset);
    }

    /** {@inheritDoc} 问题编号可以定位所属组。 */
    @Override
    public Support.Group group(UUID actor, UUID id) {
        require(actor, false);
        var g = sql.group(id);
        if (g == null) throw fail("SUPPORT_NOT_FOUND");
        audit(actor, "READ_GROUP", g.id());
        return g;
    }

    /** {@inheritDoc} 应用校验状态图后，SQL执行最终CAS。 */
    @Override
    public void change(UUID actor, UUID id, Support.Change c) {
        require(actor, false);
        if (sql.change(actor, id, c) != 1) throw fail("SUPPORT_CONFLICT");
        audit(actor, "CHANGE_GROUP", id);
    }

    /** {@inheritDoc} 有界查询，不读取用户正文。 */
    @Override
    public List<Support.Event> events(UUID actor, UUID id) {
        return sql.events(group(actor, id).id());
    }

    /** {@inheritDoc} 不返回提示词、工具响应及metadata。 */
    @Override
    public Support.Trace trace(UUID actor, UUID trace) {
        require(actor, false);
        audit(actor, "READ_TRACE", trace);
        var r = sql.spans(trace);
        return new Support.Trace(trace, r.stream().limit(200).toList(), r.size() > 200);
    }

    /** {@inheritDoc} 每用户每小时10条，重复不累计。 */
    @Override
    public UUID client(UUID user, UUID id, String code, String component) {
        lock(user);
        var old = sql.oldClient(user, id);
        if (old != null) {
            if (!old.code().equals(code) || !old.component().equals(component))
                throw fail("SUPPORT_CONFLICT");
            return old.problemId();
        }
        if (sql.clientCount(user) >= 10) throw fail("SUPPORT_RATE_LIMIT");
        var ids = ExecutionCorrelation.current();
        var p = UUID.randomUUID();
        if (sql.problem(
                        p,
                        user,
                        new Support.Link(ids.traceId(), null, ids.requestId(), null, null),
                        component,
                        code)
                != 1) throw fail("SUPPORT_CAPACITY");
        sql.client(user, id, code, component, p);
        return p;
    }

    /** {@inheritDoc} 与进程队列丢弃数分开计数。 */
    @Override
    public long dropped() {
        return sql.dropped();
    }

    /** {@inheritDoc} 固定内部故障记录，无正文。 */
    @Override
    public boolean diagnostic(UUID user, UUID id, UUID request, UUID trace, String code) {
        return sql.problem(
                        id,
                        user,
                        new Support.Link(trace, null, request, null, null),
                        "SUPPORT",
                        code)
                == 1;
    }

    /**
     * @param actor 操作者
     * @param sensitive 额外权限
     */
    private void require(UUID actor, boolean sensitive) {
        if (!allowed(actor, sensitive)) throw fail("SUPPORT_FORBIDDEN");
    }

    /**
     * @param actor 操作者
     * @param action 固定动作
     * @param target 对象ID
     */
    private void audit(UUID actor, String action, UUID target) {
        sql.audit(actor, action, target, ExecutionCorrelation.current().requestId());
    }

    /**
     * @param id 反馈
     * @param problem 实际保存的问题
     * @return 提交后使用的回执
     */
    private Support.Receipt receipt(UUID id, UUID problem) {
        return new Support.Receipt(true, id, problem, ExecutionCorrelation.current().requestId());
    }

    /**
     * @param code 固定代码
     * @return 无正文异常
     */
    private SupportFailure fail(String code) {
        return new SupportFailure(code);
    }
}
