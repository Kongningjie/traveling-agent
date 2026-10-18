package com.lawrence.travel.infrastructure.artifact;

import com.lawrence.travel.application.port.ArtifactStore;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.domain.travel.TaskScope;
import java.util.Set;
import java.util.UUID;

/** PostgreSQL不可变产物索引；不直接调用文件适配器。 */
public final class PostgresArtifactStore implements ArtifactStore {
    private final ArtifactMapper mapper;
    /** @param mapper 显式SQL */
    public PostgresArtifactStore(ArtifactMapper mapper) { this.mapper = mapper; }
    /** {@inheritDoc} 锁由应用短事务持有。 */
    @Override public void requireRun(TaskScope scope, UUID run) { if (mapper.run(scope, run) == null) throw missing(); }
    /** {@inheritDoc} 固定命名空间锁，与任务行锁无反向依赖。 */
    @Override public void lockFiles(boolean exclusive) { mapper.lock(exclusive); }
    /** {@inheritDoc} 插入失败交由事务回滚，不吞掉数据库约束错误。 */
    @Override public void insert(Entry entry) { mapper.insert(entry); }
    /** {@inheritDoc} 内部框架文件和他人文件都按缺失处理。 */
    @Override public Entry visible(UUID user, UUID id) {
        var r = mapper.visible(user, id); if (r == null) throw missing();
        return new Entry(r.id(), new TaskScope(r.userId(), r.taskId()), r.runId(), r.kind(), r.mediaType(), r.hash(), r.size(), r.fileKey(), r.body(), r.visible());
    }
    /** {@inheritDoc} 不提供对外索引遍历。 */
    @Override public Set<String> referencedFiles() { return Set.copyOf(mapper.references()); }
    /** @return 越权与缺失统一分类 */
    private static TravelStorageFailure missing() { return new TravelStorageFailure(TravelStorageFailure.Code.NOT_FOUND); }
}
