package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.TravelStore;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.application.travel.TravelStorageService;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.itinerary.ItineraryVersion;
import com.lawrence.travel.domain.rule.DomainViolation;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.infrastructure.travel.PostgresTravelStore;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import com.lawrence.travel.infrastructure.travel.TravelMapper;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实本地 PostgreSQL 事务、JSONB、行锁和私有存储测试；不创建供应商请求或真实账号密码。
 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TravelStorageIntegrationTest {
    @Autowired private TravelStore store;
    @Autowired private TravelStorageService service;
    @Autowired private AccountStore accounts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TravelJson json;
    @Autowired private TravelMapper mapper;
    @Autowired private PlatformTransactionManager manager;
    private final List<UUID> users = new ArrayList<>();

    /**
     * 清理仅本用例随机用户数据；延迟需求 FK 在同一事务中完成父子删除。
     */
    @AfterEach
    void cleanOwnFixtures() {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        status -> {
                            for (UUID user : users) {
                                jdbc.update("DELETE FROM itinerary_snapshot WHERE user_id=?", user);
                                jdbc.update("DELETE FROM change_request WHERE user_id=?", user);
                                jdbc.update(
                                        "DELETE FROM travel_constraint_revision WHERE user_id=?",
                                        user);
                                jdbc.update("DELETE FROM travel_task WHERE user_id=?", user);
                                jdbc.update("DELETE FROM travel_idempotency WHERE user_id=?", user);
                                jdbc.update("DELETE FROM app_user WHERE id=?", user);
                            }
                        });
    }

    /**
     * @return 独立随机身份，凭据使用不可登录占位而非明文密码
     */
    private UserIdentity actor() {
        UUID id = UUID.randomUUID();
        String name = "v_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        users.add(id);
        accounts.insert(id, name, "__NON_LOGIN_TEST_HASH__");
        return new UserIdentity(id, name);
    }

    /**
     * @param value 已确认需求值
     * @return 具有稳定来源的完整测试需求
     */
    private TravelStore.RevisionContent content(String value) {
        return new TravelStore.RevisionContent(
                List.of(
                        new ConstraintRevision.Requirement(
                                UUID.randomUUID(),
                                "pace",
                                value,
                                ConstraintRevision.Kind.SOFT,
                                ConstraintRevision.Source.USER,
                                Set.of())),
                List.of(),
                "用户明确确认",
                "测试需求版本");
    }

    /**
     * @param actor 身份
     * @return 初始任务
     */
    private TravelStore.StoredTask create(UserIdentity actor) {
        return service.create(actor, UUID.randomUUID().toString(), "跨城市旅行", content("适中"));
    }

    /**
     * @param work 存储操作
     * @param expected 固定错误
     * @return 测试线程可用的断言结果
     */
    private boolean rejected(Runnable work, TravelStorageFailure.Code expected) {
        try {
            work.run();
            return false;
        } catch (TravelStorageFailure failure) {
            assertThat(failure.getMessage()).isEqualTo(expected.name());
            return true;
        }
    }

    /**
     * 同用户创建键并发竞争只产生一个任务，正文变化冲突，其他用户的相同键独立。
     * @throws Exception 线程任务失败
     */
    @Test
    void concurrentCreationHasOneDurableResult() throws Exception {
        var actor = actor();
        var content = content("宽松");
        String key = UUID.randomUUID().toString();
        var calls = new ArrayList<Callable<TravelStore.StoredTask>>();
        for (int i = 0; i < 6; i++) calls.add(() -> service.create(actor, key, "并发任务", content));
        try (var pool = Executors.newFixedThreadPool(6)) {
            var results = pool.invokeAll(calls);
            var first = results.getFirst().get();
            for (var result : results) assertThat(result.get()).isEqualTo(first);
        }
        assertThat(service.tasks(actor, 0, 100)).hasSize(1);
        assertThat(
                        rejected(
                                () -> service.create(actor, key, "不同内容", content),
                                TravelStorageFailure.Code.IDEMPOTENCY_CONFLICT))
                .isTrue();
        assertThat(service.create(actor(), key, "独立任务", content).task().scope().userId())
                .isNotEqualTo(actor.id());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM travel_idempotency WHERE user_id=?",
                                Integer.class,
                                actor.id()))
                .isEqualTo(1);
    }

    /**
     * 修改先失效旧执行；完整需求追加与清除对应输入原子提交，重放返回首次结果而非最新状态。
     */
    @Test
    void revisionsAndGenerationsRemainIndependentAndReplayable() {
        var actor = actor();
        var initial = create(actor);
        var scope = initial.task().scope();
        var original = store.revision(scope, 1);
        UUID input = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        var changed = store.registerChange(scope, key, 0, input, 1);
        assertThat(changed.task().executionGeneration()).isEqualTo(2);
        assertThat(changed.task().constraintRevision()).isEqualTo(1);
        assertThat(changed.task().pendingInputs()).containsKey(input);
        var nextContent = content("宽松");
        var revised = store.appendRevision(scope, "revision", 1, input, nextContent);
        assertThat(revised.task().constraintRevision()).isEqualTo(2);
        assertThat(revised.task().executionGeneration()).isEqualTo(2);
        assertThat(revised.task().pendingInputs()).isEmpty();
        assertThat(store.revision(scope, 1)).isEqualTo(original);
        assertThat(store.revision(scope, 2).requirements()).isEqualTo(nextContent.requirements());
        assertThat(store.registerChange(scope, key, 0, input, 1)).isEqualTo(changed);
        assertThat(store.appendRevision(scope, "revision", 1, input, nextContent))
                .isEqualTo(revised);
        assertThat(
                        rejected(
                                () ->
                                        store.appendRevision(
                                                scope, "revision", 1, input, content("紧凑")),
                                TravelStorageFailure.Code.IDEMPOTENCY_CONFLICT))
                .isTrue();
        assertThat(store.task(scope)).isEqualTo(revised);
        assertThat(revised.task().currentVersionId()).isNull();
    }

    /**
     * 同一旧行版本的不同请求并发只有一个获准；失败占位回滚，可携同键和新版本重试。
     * @throws Exception 线程任务失败
     */
    @Test
    void optimisticVersionRejectsConcurrentOverwrite() throws Exception {
        var scope = create(actor()).task().scope();
        var calls = new ArrayList<Callable<Boolean>>();
        for (int i = 0; i < 4; i++) {
            UUID input = UUID.randomUUID();
            String key = "change-" + i;
            long seq = i + 1;
            calls.add(
                    () -> {
                        try {
                            store.registerChange(scope, key, 0, input, seq);
                            return true;
                        } catch (TravelStorageFailure failure) {
                            assertThat(failure.getMessage()).isEqualTo("STALE_VERSION");
                            return false;
                        }
                    });
        }
        int winners = 0;
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (var result : pool.invokeAll(calls)) if (result.get()) winners++;
        }
        assertThat(winners).isEqualTo(1);
        assertThat(store.task(scope).lockVersion()).isEqualTo(1);
        assertThat(store.task(scope).task().executionGeneration()).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM travel_idempotency WHERE user_id=? AND operation='CHANGE'",
                                Integer.class,
                                scope.userId()))
                .isEqualTo(1);
        UUID retryInput = UUID.randomUUID();
        assertThat(
                        rejected(
                                () -> store.registerChange(scope, "retry", 0, retryInput, 9),
                                TravelStorageFailure.Code.STALE_VERSION))
                .isTrue();
        assertThat(store.registerChange(scope, "retry", 1, retryInput, 9).lockVersion())
                .isEqualTo(2);
    }

    /**
     * 不得跳过更早的待处理修改；失败不丢失两个未完成输入，也不产生需求历史或幂等占位。
     */
    @Test
    void failedExtractionCannotDropPendingRequirements() {
        var scope = create(actor()).task().scope();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.registerChange(scope, "first", 0, first, 1);
        var before = store.registerChange(scope, "second", 1, second, 2);
        assertThatThrownBy(
                        () -> store.appendRevision(scope, "out-of-order", 2, second, content("宽松")))
                .isInstanceOf(DomainViolation.class)
                .hasMessage("ILLEGAL_TRANSITION");
        assertThat(store.task(scope)).isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM travel_constraint_revision WHERE task_id=?",
                                Integer.class,
                                scope.taskId()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM travel_idempotency WHERE user_id=? AND request_key='out-of-order'",
                                Integer.class,
                                scope.userId()))
                .isZero();
        var after = store.appendRevision(scope, "first-revision", 2, first, content("适中"));
        assertThat(after.task().pendingInputs()).containsOnlyKeys(second);
    }

    /**
     * 用户／任务归属限制同时覆盖主对象、需求历史和基础快照，空列表不能成为越权旁路。
     */
    @Test
    void allResourcesAndBaseVersionsRemainPrivate() {
        var owner = actor();
        var task = create(owner);
        var ownScope = task.task().scope();
        var other = actor();
        TaskScope forged = new TaskScope(other.id(), ownScope.taskId());
        assertThat(rejected(() -> store.task(forged), TravelStorageFailure.Code.NOT_FOUND))
                .isTrue();
        assertThat(rejected(() -> store.revision(forged, 1), TravelStorageFailure.Code.NOT_FOUND))
                .isTrue();
        assertThat(
                        rejected(
                                () -> store.snapshots(forged, 0, 10),
                                TravelStorageFailure.Code.NOT_FOUND))
                .isTrue();
        assertThat(
                        rejected(
                                () ->
                                        store.registerChange(
                                                forged, "foreign", 0, UUID.randomUUID(), 1),
                                TravelStorageFailure.Code.NOT_FOUND))
                .isTrue();
        assertThat(service.tasks(other, 0, 10)).isEmpty();
        var first = snapshot(ownScope, null);
        var otherScope = create(owner).task().scope();
        assertThat(
                        rejected(
                                () -> store.snapshot(otherScope, first.version().snapshotId()),
                                TravelStorageFailure.Code.NOT_FOUND))
                .isTrue();
        assertThat(
                        rejected(
                                () -> snapshot(otherScope, first.version().snapshotId()),
                                TravelStorageFailure.Code.NOT_FOUND))
                .isTrue();
        assertThat(
                        rejected(
                                () -> service.task(other, ownScope.taskId()),
                                TravelStorageFailure.Code.NOT_FOUND))
                .isTrue();
    }

    /**
     * @param scope 同任务归属
     * @param base 可空基础版本
     * @return 保存的无证据草案；不代表可接受候选
     */
    private TravelStore.StoredSnapshot snapshot(TaskScope scope, UUID base) {
        var body = new java.util.TreeMap<String, Object>();
        body.put("schemaVersion", 1);
        body.put("taskId", scope.taskId());
        body.put("constraintRevision", 1);
        body.put("baseVersionId", base);
        for (String field :
                List.of(
                        "cityStays",
                        "days",
                        "items",
                        "transportLegs",
                        "accommodationStays",
                        "costEntries",
                        "alternatives",
                        "evidenceRefs")) body.put(field, List.of());
        String payload = json.encode(body);
        var version =
                new ItineraryVersion(
                        UUID.randomUUID(), scope, 1, json.hash(payload), 1, base, Set.of(), null);
        return store.appendSnapshot(version, payload);
    }

    /**
     * JSONB 全文与元信息可重建，重复快照幂等，数据库拒绝原地更新，新的仓储对象不依赖进程内缓存。
     */
    @Test
    void immutableSnapshotsSurviveAdapterRecreation() {
        var scope = create(actor()).task().scope();
        var first = snapshot(scope, null);
        var second = snapshot(scope, first.version().snapshotId());
        assertThat(store.appendSnapshot(first.version(), first.payload())).isEqualTo(first);
        var restored = new PostgresTravelStore(mapper, manager, new TravelJson());
        assertThat(restored.snapshot(scope, first.version().snapshotId())).isEqualTo(first);
        assertThat(restored.snapshots(scope, 0, 10))
                .containsExactlyInAnyOrder(first.version(), second.version());
        assertThat(store.task(scope).task().currentVersionId()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT jsonb_typeof(payload) FROM itinerary_snapshot WHERE id=?",
                                String.class,
                                first.version().snapshotId()))
                .isEqualTo("object");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE itinerary_snapshot SET payload='{}'::jsonb WHERE id=?",
                                        first.version().snapshotId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE travel_constraint_revision SET payload='{}'::jsonb WHERE task_id=?",
                                        scope.taskId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(restored.snapshot(scope, first.version().snapshotId())).isEqualTo(first);
    }

    /**
     * 相同快照 ID 的不同载荷不能覆盖；错误任务、未知 Schema、坏摘要及悬空依赖不能保存。
     */
    @Test
    void snapshotIdentityAndEnvelopeRejectConflictingContent() {
        var scope = create(actor()).task().scope();
        var saved = snapshot(scope, null);
        String different =
                saved.payload()
                        .replace(
                                "\"items\":[]",
                                "\"items\":[{\"id\":\"" + UUID.randomUUID() + "\"}]");
        var conflicting =
                new ItineraryVersion(
                        saved.version().snapshotId(),
                        scope,
                        1,
                        json.hash(different),
                        1,
                        null,
                        Set.of(),
                        null);
        assertThat(
                        rejected(
                                () -> store.appendSnapshot(conflicting, different),
                                TravelStorageFailure.Code.IDEMPOTENCY_CONFLICT))
                .isTrue();
        assertThat(
                        rejected(
                                () -> store.appendSnapshot(saved.version(), different),
                                TravelStorageFailure.Code.INVALID_INPUT))
                .isTrue();
        assertThat(
                        rejected(
                                () ->
                                        store.appendSnapshot(
                                                saved.version(),
                                                saved.payload()
                                                        .replace(
                                                                scope.taskId().toString(),
                                                                UUID.randomUUID().toString())),
                                TravelStorageFailure.Code.INVALID_INPUT))
                .isTrue();
        var unknownSchema =
                new ItineraryVersion(
                        UUID.randomUUID(),
                        scope,
                        2,
                        saved.version().payloadHash(),
                        1,
                        null,
                        Set.of(),
                        null);
        assertThat(
                        rejected(
                                () -> store.appendSnapshot(unknownSchema, saved.payload()),
                                TravelStorageFailure.Code.INVALID_INPUT))
                .isTrue();
        var preference =
                new ItineraryVersion(
                        UUID.randomUUID(),
                        scope,
                        1,
                        saved.version().payloadHash(),
                        1,
                        null,
                        Set.of(),
                        new com.lawrence.travel.domain.memory.PreferenceVersion(scope.userId(), 0));
        assertThat(
                        rejected(
                                () -> store.appendSnapshot(preference, saved.payload()),
                                TravelStorageFailure.Code.UNSUPPORTED_REFERENCE))
                .isTrue();
        var evidence =
                new ItineraryVersion(
                        UUID.randomUUID(),
                        scope,
                        1,
                        saved.version().payloadHash(),
                        1,
                        null,
                        Set.of(
                                new com.lawrence.travel.domain.knowledge.EvidenceRecord.Reference(
                                        UUID.randomUUID(), 1, "0".repeat(64))),
                        null);
        assertThat(
                        rejected(
                                () -> store.appendSnapshot(evidence, saved.payload()),
                                TravelStorageFailure.Code.UNSUPPORTED_REFERENCE))
                .isTrue();
        assertThat(store.snapshot(scope, saved.version().snapshotId())).isEqualTo(saved);
        assertThat(store.snapshots(scope, 0, 10)).hasSize(1);
    }

    /**
     * 复合外键在数据库层拒绝跨任务生效指针，不能仅依赖 Java 归属判断。
     */
    @Test
    void databaseRejectsCrossTaskSnapshotPointer() {
        var actor = actor();
        var first = create(actor).task().scope();
        var second = create(actor).task().scope();
        var saved = snapshot(first, null);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE travel_task SET current_version_id=? WHERE user_id=? AND id=?",
                                        saved.version().snapshotId(),
                                        second.userId(),
                                        second.taskId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(store.task(second).task().currentVersionId()).isNull();
    }

    /**
     * 在已插入历史需求后注入状态写失败，验证真实 PostgreSQL 回滚需求、指针与幂等请求。
     */
    @Test
    void failureAfterHistoryInsertRollsBackTheWholeWrite() {
        var scope = create(actor()).task().scope();
        UUID input = UUID.randomUUID();
        var before = store.registerChange(scope, "change", 0, input, 1);
        var failingMapper =
                org.mockito.Mockito.mock(
                        TravelMapper.class, org.mockito.AdditionalAnswers.delegatesTo(mapper));
        org.mockito.Mockito.doThrow(
                        new org.springframework.dao.DataIntegrityViolationException(
                                "TEST_WRITE_FAILURE"))
                .when(failingMapper)
                .updateTask(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
        var failingStore = new PostgresTravelStore(failingMapper, manager, json);
        assertThat(
                        rejected(
                                () ->
                                        failingStore.appendRevision(
                                                scope, "failed-write", 1, input, content("宽松")),
                                TravelStorageFailure.Code.DEPENDENCY_UNAVAILABLE))
                .isTrue();
        assertThat(store.task(scope)).isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM travel_constraint_revision WHERE task_id=?",
                                Integer.class,
                                scope.taskId()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM travel_idempotency WHERE user_id=? AND request_key='failed-write'",
                                Integer.class,
                                scope.userId()))
                .isZero();
        assertThat(
                        store.appendRevision(scope, "failed-write", 1, input, content("宽松"))
                                .task()
                                .constraintRevision())
                .isEqualTo(2);
    }

    /**
     * 固定安排完整保留跨午夜时刻及区域时区，不把 JSONB 序列化当作只保存 HH:mm。
     */
    @Test
    void fixedArrangementsKeepTimezoneAndStableIds() {
        var actor = actor();
        var begin = ZonedDateTime.parse("2026-11-01T23:30:00+08:00[Asia/Shanghai]");
        var arrangement =
                new ConstraintRevision.FixedArrangement(
                        UUID.randomUUID(), "已订交通", begin, begin.plusHours(3), UUID.randomUUID());
        var content =
                new TravelStore.RevisionContent(List.of(), List.of(arrangement), "用户提供", "初始已订信息");
        var task = service.create(actor, "timezone", "夜间交通", content);
        assertThat(store.revision(task.task().scope(), 1).fixedArrangements())
                .containsExactly(arrangement);
    }

    /**
     * 新建或追加任一环节失败均整体回滚；外层业务事务回滚也不留请求占位或状态。
     */
    @Test
    void rollbackLeavesNoPartialCreationOrRevision() {
        var actor = actor();
        var content = content("适中");
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        status -> {
                            service.create(actor, "rolled-back", "应回滚", content);
                            status.setRollbackOnly();
                        });
        assertThat(service.tasks(actor, 0, 10)).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM travel_idempotency WHERE user_id=?",
                                Integer.class,
                                actor.id()))
                .isZero();
        var task = service.create(actor, "rolled-back", "重试", content);
        UUID input = UUID.randomUUID();
        var changed = store.registerChange(task.task().scope(), "change", 0, input, 1);
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        status -> {
                            store.appendRevision(
                                    task.task().scope(),
                                    "rolled-revision",
                                    1,
                                    input,
                                    content("宽松"));
                            status.setRollbackOnly();
                        });
        assertThat(store.task(task.task().scope())).isEqualTo(changed);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM travel_constraint_revision WHERE task_id=?",
                                Integer.class,
                                task.task().scope().taskId()))
                .isEqualTo(1);
    }

    /**
     * 请求键变化或缓存清理后，永久修改身份仍阻止重新应用和占用已使用的输入序号。
     */
    @Test
    void durableInputIdentitySurvivesRequestKeyChanges() {
        var scope = create(actor()).task().scope();
        UUID input = UUID.randomUUID();
        store.registerChange(scope, "change", 0, input, 1);
        var content = content("宽松");
        var saved = store.appendRevision(scope, "revision", 1, input, content);
        assertThat(store.registerChange(scope, "new-change-key", 2, input, 1)).isEqualTo(saved);
        assertThat(store.appendRevision(scope, "new-revision-key", 2, input, content))
                .isEqualTo(saved);
        assertThat(
                        rejected(
                                () -> store.registerChange(scope, "wrong-sequence", 2, input, 2),
                                TravelStorageFailure.Code.IDEMPOTENCY_CONFLICT))
                .isTrue();
        assertThat(
                        rejected(
                                () ->
                                        store.registerChange(
                                                scope, "reused-sequence", 2, UUID.randomUUID(), 1),
                                TravelStorageFailure.Code.IDEMPOTENCY_CONFLICT))
                .isTrue();
        assertThat(
                        rejected(
                                () ->
                                        store.appendRevision(
                                                scope,
                                                "different-content",
                                                2,
                                                input,
                                                content("紧凑")),
                                TravelStorageFailure.Code.IDEMPOTENCY_CONFLICT))
                .isTrue();
        assertThat(store.task(scope)).isEqualTo(saved);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM travel_constraint_revision WHERE task_id=?",
                                Integer.class,
                                scope.taskId()))
                .isEqualTo(2);
    }
}
