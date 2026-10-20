package com.lawrence.travel.infrastructure.lifecycle;

import com.lawrence.travel.application.lifecycle.Lifecycle;
import com.lawrence.travel.application.lifecycle.LifecycleFailure;
import com.lawrence.travel.application.port.LifecycleStore;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PG技术墓碑和清理索引；文件IO和清单封存由应用端口协调，不直调其他适配器。 */
public final class PostgresLifecycleStore implements LifecycleStore {
    private final LifecycleMapper sql;

    /**
     * @param sql 专用SQL
     */
    public PostgresLifecycleStore(LifecycleMapper sql) {
        this.sql = sql;
    }

    /** {@inheritDoc} 同用户写入串行；对象范围由当前PG关系计算。 */
    @Override
    public Lifecycle.Preview preview(UUID user, Lifecycle.Kind kind, UUID target) {
        if (sql.user(user) == null) throw fail();
        var scope =
                switch (kind) {
                    case CHAT -> sql.chat(user, target);
                    case TASK -> sql.task(user, target);
                    case DOCUMENT -> sql.document(user, target);
                };
        if (scope == null) throw fail();
        UUID doc = kind == Lifecycle.Kind.DOCUMENT ? target : null;
        var counts = sql.counts(user, scope.id(), scope.taskId(), doc);
        var rows = sql.resources(user, scope.id(), scope.taskId(), doc);
        return new Lifecycle.Preview(
                kind,
                target,
                scope.id(),
                scope.taskId(),
                counts.documents(),
                counts.versions(),
                sql.revision(user, scope.id(), scope.taskId(), doc),
                doc == null
                        ? "将删除此聊天及关联旅行的全部消息、版本、附件和派生记忆；共享长期偏好保留。删除后立即不可读，原件后台清理；备份最多保留30天。"
                        : "仅删除所选原件及派生资料，历史派生回复会隐藏、未接受候选失效；已确认需求、已接受行程及长期偏好保留。",
                rows.stream().limit(40).toList(),
                rows.size() > 40);
    }

    /** {@inheritDoc} 调用方必须持短事务。 */
    @Override
    public Lifecycle.Head head() {
        return sql.head();
    }

    /** {@inheritDoc} 读屏障不持有写序列锁。 */
    @Override
    public Lifecycle.Head status() {
        return sql.status();
    }

    /** {@inheritDoc} 旧备份不能以新部署为理由跳过清单。 */
    @Override
    public boolean initialized() {
        return sql.initialized();
    }

    /** {@inheritDoc} 已删除身份仍能核对原请求。 */
    @Override
    public Lifecycle.Entry replay(UUID user, UUID id) {
        return sql.replay(user, id);
    }

    /** {@inheritDoc} 仅提供受控身份，不取得执行权。 */
    @Override
    public List<UUID> runs(UUID user, UUID task) {
        return sql.runs(user, task).stream().map(UUID::fromString).toList();
    }

    /** {@inheritDoc} 原删除和重放走同一原子函数。 */
    @Override
    public void apply(Lifecycle.Entry e) {
        sql.apply(e);
    }

    /** {@inheritDoc} 墓碑及水位同事务。 */
    @Override
    public void advance(Lifecycle.Head h) {
        sql.advance(h);
    }

    /** {@inheritDoc} 有界发现，未失败项优先按删除时间。 */
    @Override
    public List<Lifecycle.File> files(Instant now) {
        return sql.files(now);
    }

    /** {@inheritDoc} 只有实际文件已删除才能释放原件容量。 */
    @Override
    public void purged(UUID id) {
        sql.purged(id);
        sql.documentPurged(id);
    }

    /** {@inheritDoc} 最长一小时退避，失败不清除引用或虚称已完成。 */
    @Override
    public void failed(Lifecycle.File file, Instant now) {
        sql.failed(file, now.plusSeconds(Math.min(3600L, 1L << Math.min(file.attempts() + 1, 12))));
        sql.problem(file);
    }

    /** {@inheritDoc} 诊断有界批量回收，业务接受和授权审计不受影响。 */
    @Override
    public int prune(Instant now) {
        var cutoff = now.minusSeconds(30L * 86400);
        return sql.pruneProblems(cutoff) + sql.pruneSpans(cutoff) + sql.pruneGroups(cutoff);
    }

    /** {@inheritDoc} 不输出失败路径或原文。 */
    @Override
    public Lifecycle.Health health(Instant now) {
        return sql.health(now);
    }

    /**
     * @return 缺失与越权统一错误
     */
    private LifecycleFailure fail() {
        return new LifecycleFailure("DELETE_NOT_FOUND");
    }

    /** {@inheritDoc} 归属由明确SQL过滤，无关解析不因删除来源失去提交资格。 */
    @Override
    public List<UUID> sourceJobs(UUID user, UUID chat, UUID document) {
        return sql.sourceJobs(user, chat, document).stream().map(UUID::fromString).toList();
    }

    /** {@inheritDoc} 文件键只在SQL内部与本墓碑比较，返回值不包含路径或其他归属。 */
    @Override
    public boolean pending(Lifecycle.Entry entry) {
        return sql.pending(entry);
    }

    /** {@inheritDoc} 同用户重放已由应用先处理；这里仅检测剩余的全局唯一碰撞。 */
    @Override
    public boolean requestUsed(UUID request) {
        return sql.requestUsed(request);
    }
}
