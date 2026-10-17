package com.lawrence.travel.infrastructure.itinerary;

import com.lawrence.travel.application.itinerary.Versions;
import com.lawrence.travel.application.port.VersionStore;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.domain.itinerary.ItineraryVersion;
import com.lawrence.travel.domain.itinerary.TripSnapshot;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.infrastructure.execution.persistence.EngineMapper;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import com.lawrence.travel.infrastructure.travel.TravelMapper;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 私有历史与恢复前置检查；不复用旧报告，不直接写当前指针。 */
public final class PostgresVersionStore implements VersionStore {
    private final VersionMapper versions;
    private final TravelMapper tasks;
    private final CandidateMapper candidates;
    private final EngineMapper engine;
    private final TravelJson json;
    /** @param versions 历史查询 @param tasks 任务SQL @param candidates 统一用户锁 @param engine 永久执行幂等 @param json 严格格式 */
    public PostgresVersionStore(VersionMapper versions, TravelMapper tasks, CandidateMapper candidates, EngineMapper engine, TravelJson json) { this.versions = versions; this.tasks = tasks; this.candidates = candidates; this.engine = engine; this.json = json; }
    /** {@inheritDoc} 缺失与越权相同，不泄露空任务是否存在。 */
    @Override public List<Versions.Summary> list(TaskScope scope, int offset, int limit) {
        require(tasks.task(scope) != null, TravelStorageFailure.Code.NOT_FOUND); require(offset >= 0 && limit > 0 && limit <= 100, TravelStorageFailure.Code.INVALID_INPUT);
        return versions.list(scope, offset, limit).stream().map(this::summary).toList();
    }
    /** {@inheritDoc} 正文与元信息是不可变记录，读取不能触发生效。 */
    @Override public Versions.Detail detail(TaskScope scope, UUID id) { var row = versions.row(scope, id); require(row != null, TravelStorageFailure.Code.NOT_FOUND); var definition = versions.definition(scope, id);
        String source = "USER_INPUT";
        if (definition != null) {
            var plan = json.decode(definition, com.lawrence.travel.application.execution.StagePlan.class);
            if (json.decode(plan.input(), java.util.Map.class).containsKey("restoreSnapshotId")) source = "HISTORY_RESTORE";
        }
        return new Versions.Detail(summary(row), json.decode(tasks.snapshot(scope, id).payload(), TripSnapshot.class), source, versions.sources(scope, id).stream().map(value -> json.decode(value, com.lawrence.travel.application.interaction.Negotiation.Message.class)).toList()); }
    /** {@inheritDoc} 用户→任务锁序；重放先交给原执行幂等判定，不能因其首次执行已提升代次而误拒绝。 */
    @Override public void guardRestore(Versions.Restore command) {
        require(TransactionSynchronizationManager.isActualTransactionActive(), TravelStorageFailure.Code.INVALID_INPUT);
        require(candidates.lockUser(command.scope()) != null, TravelStorageFailure.Code.NOT_FOUND); var task = tasks.lockTask(command.scope()); require(task != null, TravelStorageFailure.Code.NOT_FOUND);
        if (engine.startRequest(command.scope(), command.requestId()) != null) return;
        var historical = versions.row(command.scope(), command.snapshotId()); require(historical != null, TravelStorageFailure.Code.NOT_FOUND);
        require(historical.accepted(), TravelStorageFailure.Code.INVALID_INPUT);
        require(Objects.equals(task.currentVersionId(), command.expectedCurrent()) && task.constraintRevision() == command.revision() && task.executionGeneration() == command.generation() && json.pendingInputs(task.pendingInputs()).isEmpty(), TravelStorageFailure.Code.STALE_VERSION);
    }
    /** @param row 数据库元信息 @return 只读摘要 */
    private Versions.Summary summary(VersionMapper.Row row) { return new Versions.Summary(json.decode(row.metadata(), ItineraryVersion.class), row.createdAt(), row.accepted()); }
    /** @param condition 业务检查 @param code 安全错误码 */
    private void require(boolean condition, TravelStorageFailure.Code code) { if (!condition) throw new TravelStorageFailure(code); }
}
