package com.lawrence.travel.infrastructure.travel;

import com.lawrence.travel.application.port.TravelStore;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.domain.itinerary.ItineraryVersion;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.domain.travel.TravelTask;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.DEPENDENCY_UNAVAILABLE;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.IDEMPOTENCY_CONFLICT;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.INVALID_INPUT;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.NOT_FOUND;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.STALE_VERSION;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.UNSUPPORTED_REFERENCE;

/**
 * PostgreSQL 任务版本适配器；请求占位、历史追加、状态 CAS 和首次响应在同一短事务提交。
 * 无可变成员状态；先锁私有任务，再锁其请求记录，创建任务使用独立的用户级创建请求键。
 */
public final class PostgresTravelStore implements TravelStore {
    private static final UUID CREATION_RESOURCE = new UUID(0, 0);
    private final TravelMapper mapper;
    private final TravelJson json;
    private final TransactionTemplate transaction;
    private final com.lawrence.travel.application.port.ResearchStore evidence;

    /**
     * @param mapper 显式私有资源 SQL
     * @param manager 同一数据源事务管理器
     * @param json 固定 JSONB 格式
     */
    public PostgresTravelStore(
            TravelMapper mapper, PlatformTransactionManager manager, TravelJson json) {
        this(mapper, manager, json, null);
    }

    /**
     * @param mapper 私有SQL
     * @param manager 事务
     * @param json JSON
     * @param evidence 字段证据端口，可空表示保守兼容旧接线
     */
    public PostgresTravelStore(
            TravelMapper mapper,
            PlatformTransactionManager manager,
            TravelJson json,
            com.lawrence.travel.application.port.ResearchStore evidence) {
        this.mapper = mapper;
        this.json = json;
        this.transaction = new TransactionTemplate(manager);
        this.evidence = evidence;
    }

    /**
     * {@inheritDoc} 并发同键等待首个事务；失败时连请求占位一起回滚。
     */
    @Override
    public StoredTask create(UUID userId, String key, String title, RevisionContent content) {
        require(
                userId != null && title != null && !title.isBlank() && title.length() <= 200,
                INVALID_INPUT);
        require(content != null, INVALID_INPUT);
        return transact(
                () ->
                        request(
                                userId,
                                "CREATE",
                                CREATION_RESOURCE,
                                key,
                                List.of(title, content),
                                () -> {
                                    TaskScope scope = new TaskScope(userId, UUID.randomUUID());
                                    var task = new TravelTask(scope, 1, 1, null, Map.of());
                                    var stored = new StoredTask(task, title, 0, 0);
                                    mapper.insertTask(row(stored));
                                    insertRevision(scope, 1, content);
                                    return stored;
                                }));
    }

    /**
     * {@inheritDoc} 代次提升与待处理输入同事务保存，不提前标为已完成提取。
     */
    @Override
    public StoredTask registerChange(
            TaskScope scope, String key, long expectedLockVersion, UUID inputId, long sequence) {
        require(inputId != null && sequence > 0, INVALID_INPUT);
        return update(
                scope,
                "CHANGE",
                key,
                expectedLockVersion,
                List.of(expectedLockVersion, inputId, sequence),
                current -> {
                    var previous = mapper.change(scope, inputId);
                    if (previous != null) {
                        require(previous.inputSequence() == sequence, IDEMPOTENCY_CONFLICT);
                        return current.task();
                    }
                    require(
                            mapper.insertChange(scope, inputId, sequence) == 1,
                            IDEMPOTENCY_CONFLICT);
                    return current.task().registerInput(inputId, sequence).registerChange(inputId);
                },
                null);
    }

    /**
     * {@inheritDoc} 完整历史需求与推进当前指针原子提交，不合并未经确认的自然语言。
     */
    @Override
    public StoredTask appendRevision(
            TaskScope scope,
            String key,
            long expectedLockVersion,
            UUID inputId,
            RevisionContent content) {
        require(inputId != null && content != null, INVALID_INPUT);
        return update(
                scope,
                "REVISION",
                key,
                expectedLockVersion,
                List.of(expectedLockVersion, inputId, content),
                current -> {
                    var registered = mapper.change(scope, inputId);
                    require(registered != null, NOT_FOUND);
                    String hash = json.hash(json.encode(content));
                    if ("APPLIED".equals(registered.state())) {
                        require(hash.equals(registered.contentHash()), IDEMPOTENCY_CONFLICT);
                        return current.task();
                    }
                    TravelTask next =
                            current.task()
                                    .applyChange(inputId, current.task().constraintRevision());
                    // 需求 FK 在事务结束检查：身份、完整历史及任务指针必须一起成功或一起回滚。
                    require(
                            mapper.applyChange(scope, inputId, next.constraintRevision(), hash)
                                    == 1,
                            IDEMPOTENCY_CONFLICT);
                    return next;
                },
                content);
    }

    /**
     * {@inheritDoc} 单次查询同时读取状态和游标。
     */
    @Override
    public StoredTask task(TaskScope scope) {
        return safe(() -> stored(requireRow(mapper.task(scope))));
    }

    /**
     * {@inheritDoc} 有界列表只读取任务行。
     */
    @Override
    public List<StoredTask> tasks(UUID userId, int offset, int limit) {
        page(offset, limit);
        require(userId != null, INVALID_INPUT);
        return safe(() -> mapper.tasks(userId, offset, limit).stream().map(this::stored).toList());
    }

    /**
     * {@inheritDoc} 精确限定用户、任务和需求版本。
     */
    @Override
    public ConstraintRevision revision(TaskScope scope, long revision) {
        require(revision > 0, INVALID_INPUT);
        return safe(
                () -> {
                    String payload = mapper.revision(scope, revision);
                    require(payload != null, NOT_FOUND);
                    return json.decode(payload, ConstraintRevision.class);
                });
    }

    /**
     * {@inheritDoc} 保存草案不更改当前生效版本，不声明其通过校验或独立审核。
     */
    @Override
    public StoredSnapshot appendSnapshot(ItineraryVersion version, String payload) {
        require(version != null, INVALID_INPUT);
        if (!version.evidenceRefs().isEmpty()) {
            require(evidence != null, UNSUPPORTED_REFERENCE);
            try {
                evidence.resolve(version.scope(), version.evidenceRefs());
            } catch (TravelStorageFailure failure) {
                throw new TravelStorageFailure(UNSUPPORTED_REFERENCE);
            }
        }
        require(version.preferenceVersion() == null, UNSUPPORTED_REFERENCE);
        String canonical = json.snapshot(version, payload);
        return transact(
                () -> {
                    requireRow(mapper.lockTask(version.scope()));
                    // 基础快照必须同用户同任务，需求必须存在。历史需求允许保存草案，未来接受门禁另查最新版本。
                    revision(version.scope(), version.constraintRevision());
                    if (version.baseVersionId() != null)
                        snapshot(version.scope(), version.baseVersionId());
                    int inserted = mapper.insertSnapshot(version, json.encode(version), canonical);
                    StoredSnapshot saved = snapshot(version.scope(), version.snapshotId());
                    require(
                            inserted == 1
                                    || (saved.version().equals(version)
                                            && saved.payload().equals(canonical)),
                            IDEMPOTENCY_CONFLICT);
                    return saved;
                });
    }

    /**
     * {@inheritDoc} 同一快照 ID 不可通过另一个任务路径读取。
     */
    @Override
    public StoredSnapshot snapshot(TaskScope scope, UUID snapshotId) {
        return safe(
                () -> {
                    TravelMapper.SnapshotRow row = mapper.snapshot(scope, snapshotId);
                    require(row != null, NOT_FOUND);
                    return new StoredSnapshot(
                            json.decode(row.metadata(), ItineraryVersion.class),
                            json.encode(json.decode(row.payload(), Object.class)));
                });
    }

    /**
     * {@inheritDoc} 元信息分页不读取大正文。
     */
    @Override
    public List<ItineraryVersion> snapshots(TaskScope scope, int offset, int limit) {
        page(offset, limit);
        return safe(
                () -> {
                    requireRow(mapper.task(scope));
                    return mapper.snapshots(scope, offset, limit).stream()
                            .map(value -> json.decode(value, ItineraryVersion.class))
                            .toList();
                });
    }

    /**
     * 统一变更事务：授权任务锁→请求键→CAS→需求追加→结果记录；不持锁调用外部服务。
     * @param scope 归属
     * @param operation 用例
     * @param key 请求键
     * @param expected 行版本
     * @param parameters 完整语义参数
     * @param transition 领域转换与永久修改登记，均在当前事务内执行
     * @param content 新需求或空
     * @return 首次事务响应，重放先于过期行版本检查
     */
    private StoredTask update(
            TaskScope scope,
            String operation,
            String key,
            long expected,
            Object parameters,
            Function<StoredTask, TravelTask> transition,
            RevisionContent content) {
        require(expected >= 0, INVALID_INPUT);
        return transact(
                () -> {
                    StoredTask current = stored(requireRow(mapper.lockTask(scope)));
                    return request(
                            scope.userId(),
                            operation,
                            scope.taskId(),
                            key,
                            parameters,
                            () -> {
                                require(current.lockVersion() == expected, STALE_VERSION);
                                TravelTask next = transition.apply(current);
                                if (next.equals(current.task())) return current;
                                var result =
                                        new StoredTask(
                                                next,
                                                current.title(),
                                                Math.incrementExact(expected),
                                                current.eventSequence());
                                if (content != null)
                                    insertRevision(scope, next.constraintRevision(), content);
                                require(
                                        mapper.updateTask(row(result), expected) == 1,
                                        STALE_VERSION);
                                return result;
                            });
                });
    }

    /**
     * @param scope 归属
     * @param revision 版本
     * @param content 全量需求；领域构造与 SQL 插入均在同一事务中执行
     */
    private void insertRevision(TaskScope scope, long revision, RevisionContent content) {
        var value =
                new ConstraintRevision(
                        scope, revision, content.requirements(), content.fixedArrangements());
        mapper.insertRevision(
                scope, revision, json.encode(value), content.source(), content.difference());
    }

    /**
     * 请求唯一键与结果同事务，禁止读到占位后在另一事务做业务提交。
     * @param user 用户
     * @param operation 操作
     * @param resource 资源
     * @param key 请求键
     * @param parameters 参数
     * @param work 首次写入
     * @return 首次结果或相同重放
     */
    private StoredTask request(
            UUID user,
            String operation,
            UUID resource,
            String key,
            Object parameters,
            Supplier<StoredTask> work) {
        require(key != null && key.matches("[!-~]{1,128}"), INVALID_INPUT);
        String hash = json.hash(json.encode(parameters));
        mapper.reserve(user, operation, resource, key, hash);
        var request = mapper.request(user, operation, resource, key);
        require(request != null && request.requestHash().equals(hash), IDEMPOTENCY_CONFLICT);
        if (request.result() != null) return json.decode(request.result(), StoredTask.class);
        StoredTask result = work.get();
        require(
                mapper.finishRequest(user, operation, resource, key, json.encode(result)) == 1,
                IDEMPOTENCY_CONFLICT);
        return result;
    }

    /**
     * @param row 受信数据库行
     * @return 不可变领域状态及游标
     */
    private StoredTask stored(TravelMapper.TaskRow row) {
        var task =
                new TravelTask(
                        new TaskScope(row.userId(), row.id()),
                        row.constraintRevision(),
                        row.executionGeneration(),
                        row.currentVersionId(),
                        json.pendingInputs(row.pendingInputs()));
        return new StoredTask(task, row.title(), row.lockVersion(), row.eventSequence());
    }

    /**
     * @param stored 领域结果
     * @return 显式 SQL 行参数
     */
    private TravelMapper.TaskRow row(StoredTask stored) {
        TravelTask task = stored.task();
        return new TravelMapper.TaskRow(
                task.scope().taskId(),
                task.scope().userId(),
                stored.title(),
                task.constraintRevision(),
                task.executionGeneration(),
                task.currentVersionId(),
                json.encode(task.pendingInputs()),
                stored.eventSequence(),
                stored.lockVersion());
    }

    /**
     * @param row 私有查询结果
     * @return 非空行，越权和不存在统一处理
     */
    private static TravelMapper.TaskRow requireRow(TravelMapper.TaskRow row) {
        require(row != null, NOT_FOUND);
        return row;
    }

    /**
     * @param offset 偏移
     * @param limit 大小；拒绝无界分页
     */
    private static void page(int offset, int limit) {
        require(offset >= 0 && limit >= 1 && limit <= 100, INVALID_INPUT);
    }

    /**
     * @param condition 条件
     * @param code 固定拒绝分类
     */
    private static void require(boolean condition, TravelStorageFailure.Code code) {
        if (!condition) throw new TravelStorageFailure(code);
    }

    /**
     * @param work 数据库工作
     * @param <T> 结果类型
     * @return 结果；异常退出回滚后再脱敏
     */
    private <T> T transact(Supplier<T> work) {
        return safe(() -> Objects.requireNonNull(transaction.execute(status -> work.get())));
    }

    /**
     * @param work 数据库工作
     * @param <T> 结果类型
     * @return 结果；不泄露 SQL 或 JSON 正文
     */
    private <T> T safe(Supplier<T> work) {
        try {
            return work.get();
        } catch (DataAccessException
                | org.springframework.transaction.TransactionException failure) {
            throw new TravelStorageFailure(DEPENDENCY_UNAVAILABLE);
        }
    }
}
