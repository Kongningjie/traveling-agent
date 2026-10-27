package com.lawrence.travel.infrastructure.knowledge;

import com.lawrence.travel.application.execution.StageOutput;
import com.lawrence.travel.application.knowledge.EvidencePolicy;
import com.lawrence.travel.application.knowledge.Research;
import com.lawrence.travel.application.port.ResearchStore;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.domain.execution.ExecutionFence;
import com.lawrence.travel.domain.execution.StageType;
import com.lawrence.travel.domain.knowledge.FieldEvidence;
import com.lawrence.travel.domain.knowledge.EvidenceRecord;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.infrastructure.execution.persistence.EngineMapper;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import com.lawrence.travel.infrastructure.travel.TravelMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 保存调查查询、结果和字段证据。
 */
public final class PostgresResearchStore implements ResearchStore {
    private final EvidenceMapper mapper;
    private final TravelMapper tasks;
    private final EngineMapper engine;
    private final TravelJson json;

    /**
     * @param mapper 证据 SQL
     * @param tasks 需求 SQL
     * @param engine 事件 SQL
     * @param json JSON
     */
    public PostgresResearchStore(
            EvidenceMapper mapper, TravelMapper tasks, EngineMapper engine, TravelJson json) {
        this.mapper = mapper;
        this.tasks = tasks;
        this.engine = engine;
        this.json = json;
    }

    /** {@inheritDoc} 即使调用者拿到旧领取副本，也不能读取错修订作为新结果依据。 */
    @Override
    public Research.Context context(ExecutionFence fence) {
        var task = tasks.task(fence.scope());
        check(task != null);
        if (task.constraintRevision() != fence.constraintRevision()
                || task.executionGeneration() != fence.generation())
            throw new TravelStorageFailure(TravelStorageFailure.Code.STALE_VERSION);
        return new Research.Context(
                json.decode(
                        tasks.revision(fence.scope(), fence.constraintRevision()),
                        ConstraintRevision.class),
                evidence(fence.scope()),
                mapper.now(),
                task.currentVersionId(),
                mapper.preference(fence.scope().userId()));
    }

    /** {@inheritDoc} 缺失和越权统一 NOT_FOUND；旧值完整保留并显式显示未知／冲突／失效。 */
    @Override
    public List<Research.View> evidence(TaskScope scope) {
        check(tasks.task(scope) != null);
        Instant now = mapper.now();
        return mapper.list(scope).stream().map(row -> view(row, now)).toList();
    }

    /** {@inheritDoc} 查询所有指定引用，不能因为列表分页而把旧事实误认为不存在。 */
    @Override
    public List<Research.View> resolve(TaskScope scope, Set<EvidenceRecord.Reference> references) {
        check(tasks.task(scope) != null);
        if (references.size() > 200) throw new IllegalArgumentException("EVIDENCE_REFERENCE_LIMIT");
        var result = new java.util.ArrayList<Research.View>();
        Instant now = mapper.now();
        for (var reference : references) {
            var row = mapper.read(scope, reference.id());
            check(row != null);
            var value = view(row, now);
            if (!value.evidence().reference().equals(reference) || row.artifactId() == null)
                throw new TravelStorageFailure(TravelStorageFailure.Code.STALE_VERSION);
            result.add(value);
        }
        return List.copyOf(result);
    }

    /** {@inheritDoc} 查询键、依赖、日期、状态和时间必须同时匹配；缓存命中不申请外部调用预算。 */
    @Override
    public List<FieldEvidence> cached(
            TaskScope scope, String key, String dependencyHash, LocalDate date) {
        check(tasks.task(scope) != null);
        var rows = mapper.cache(scope, key, dependencyHash);
        Instant now = mapper.now();
        return rows.stream()
                .map(this::fact)
                .filter(
                        value ->
                                value.reusableAt(now, date)
                                        && value.status() != EvidenceRecord.Status.CONFLICT)
                .toList();
    }

    /** {@inheritDoc} 逐一核对 ID／版本／正文哈希及追加失效记录，不猜测他人证据或已失效事实。 */
    @Override
    public Research.Dependencies dependencies(
            TaskScope scope,
            Set<EvidenceRecord.Reference> references,
            Instant now,
            Instant upperBound) {
        check(tasks.task(scope) != null);
        Instant until = upperBound;
        for (var reference : references) {
            var row = mapper.read(scope, reference.id());
            if (row == null || row.reason() != null || row.artifactId() == null)
                return new Research.Dependencies(false, until);
            var fact = fact(row);
            if (!fact.reference().equals(reference)
                    || fact.status() != EvidenceRecord.Status.KNOWN
                    || now.isBefore(fact.retrievedAt())
                    || !now.isBefore(fact.expiresAt()))
                return new Research.Dependencies(false, until);
            if (fact.expiresAt().isBefore(until)) until = fact.expiresAt();
        }
        return new Research.Dependencies(true, until);
    }

    /** {@inheritDoc} 与接受相同的任务锁串行化；不触碰生效指针，不把撤回当作更新事实。 */
    @Override
    public boolean withdraw(TaskScope scope, EvidenceRecord.Reference reference, UUID requestId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || requestId == null)
            throw new IllegalArgumentException("EVIDENCE_TRANSACTION_REQUIRED");
        check(tasks.lockTask(scope) != null);
        var row = mapper.read(scope, reference.id());
        check(row != null);
        if (!fact(row).reference().equals(reference))
            throw new TravelStorageFailure(TravelStorageFailure.Code.STALE_VERSION);
        boolean inserted =
                mapper.notice(scope, reference.id(), "WITHDRAWN", requestId, UUID.randomUUID())
                        == 1;
        if (inserted) {
            mapper.invalidate(scope, reference.id());
            String run = engine.currentRun(scope);
            if (run != null)
                engine.event(scope, UUID.fromString(run), null, "EVIDENCE_INVALIDATED");
        }
        return inserted;
    }

    /** {@inheritDoc} 策略先取得偏好共享锁，任务锁由引擎随后统一取得，维持用户→任务锁序。 */
    @Override
    public void beforeTaskLock(ExecutionFence fence, StageOutput output) {
        var body = json.decode(output.checkpoint(), Map.class);
        if (body.containsKey("strategyPreferenceRevision"))
            mapper.lockPreference(fence.scope().userId());
    }

    /** {@inheritDoc} 任何字段或后继错误均与阶段进度一起回滚，迟到结果由引擎先拒绝。 */
    @Override
    public Result project(ExecutionFence fence, StageType type, StageOutput output) {
        var envelope = json.decode(output.checkpoint(), Map.class);
        if (!envelope.containsKey("research")) return null;
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || type != StageType.RESEARCH)
            throw new IllegalArgumentException("RESEARCH_TRANSACTION_REQUIRED");
        if (envelope.get("strategyPreferenceRevision") instanceof Number preference
                && preference.longValue() != mapper.preference(fence.scope().userId()))
            throw new TravelStorageFailure(TravelStorageFailure.Code.STALE_VERSION);
        var prepared = json.decode(json.encode(envelope.get("research")), Research.Prepared.class);
        var revision =
                json.decode(
                        tasks.revision(fence.scope(), fence.constraintRevision()),
                        ConstraintRevision.class);
        if (!prepared.dependencyHash().equals(EvidencePolicy.dependency(revision, json::encode))
                || prepared.queryKey() == null
                || !prepared.queryKey().matches("seed|[a-f0-9]{64}")
                || prepared.facts() == null
                || prepared.facts().size() > 50
                || prepared.reused() == null)
            throw new IllegalArgumentException("RESEARCH_BINDING_INVALID");
        var now = mapper.now();
        // 委派来源也是结果依赖：阶段处理后、提交前的撤回或过期不能靠旧上下文放行。
        if (envelope.containsKey("handoff")) {
            var handoff = json.decode(json.encode(envelope.get("handoff")), Research.Handoff.class);
            if (!handoff.scope().equals(fence.scope())
                    || !handoff.run().equals(fence.runId())
                    || handoff.generation() != fence.generation()
                    || handoff.revision() != fence.constraintRevision()
                    || handoff.preferenceRevision() != mapper.preference(fence.scope().userId())
                    || !dependencies(fence.scope(), handoff.sources(), now, now.plusSeconds(1))
                            .allCurrent())
                throw new TravelStorageFailure(TravelStorageFailure.Code.STALE_VERSION);
        }
        for (var reference : prepared.reused()) {
            var row = mapper.read(fence.scope(), reference.id());
            check(row != null);
            var fact = fact(row);
            if (mapper.reuseMatches(
                            fence.scope(),
                            reference.id(),
                            prepared.queryKey(),
                            prepared.dependencyHash())
                    != 1) throw new IllegalArgumentException("RESEARCH_REUSE_BINDING_INVALID");
            if (!reference.equals(fact.reference())
                    || row.reason() != null
                    || row.artifactId() == null
                    || now.isBefore(fact.retrievedAt())
                    || !now.isBefore(fact.expiresAt()))
                throw new TravelStorageFailure(TravelStorageFailure.Code.STALE_VERSION);
        }
        boolean invalidated = false;
        for (var fact : prepared.facts()) {
            if (fact.artifactId() != null || now.isBefore(fact.retrievedAt().minusSeconds(5)))
                throw new IllegalArgumentException("RESEARCH_FACT_INVALID");
            if (prepared.raw() != null
                    && !fact.reference()
                            .contentHash()
                            .equals(EvidencePolicy.hash(prepared.raw().payload())))
                throw new IllegalArgumentException("RESEARCH_HASH_MISMATCH");
            if (prepared.raw() == null && fact.sourceType() != FieldEvidence.SourceType.SEED) {
                boolean fromUser =
                        fact.sourceType() == FieldEvidence.SourceType.USER
                                && revision.fixedArrangements().stream()
                                        .anyMatch(
                                                fixed ->
                                                        fixed.id()
                                                                        .toString()
                                                                        .equals(fact.targetId())
                                                                && fixed.sourceMessageId()
                                                                        .equals(
                                                                                fact
                                                                                        .userMessageId())
                                                                && json.encode(fixed)
                                                                        .equals(fact.value()));
                if (!fromUser) throw new IllegalArgumentException("RESEARCH_SOURCE_REQUIRED");
            }
            if (mapper.insert(
                            fence,
                            fact.reference().id(),
                            prepared.queryKey(),
                            prepared.dependencyHash(),
                            json.encode(fact))
                    != 1) throw new IllegalArgumentException("RESEARCH_INSERT_FAILED");
            for (var oldRow : mapper.matching(fence.scope(), fact)) {
                var old = fact(oldRow);
                if (old.reference().id().equals(fact.reference().id())) continue;
                if (!old.targetId().equals(fact.targetId())
                        || !old.fieldPath().equals(fact.fieldPath())
                        || !java.util.Objects.equals(old.validForDate(), fact.validForDate())
                        || old.status() != EvidenceRecord.Status.KNOWN
                        || fact.status() != EvidenceRecord.Status.KNOWN
                        || !now.isBefore(old.expiresAt())
                        || (oldRow.reason() != null && !oldRow.reason().equals("CONFLICT")))
                    continue;
                if (java.util.Objects.equals(old.sourceUrl(), fact.sourceUrl())) {
                    notice(
                            fence.scope(),
                            old.reference().id(),
                            "SUPERSEDED",
                            fact.reference().id());
                    invalidated = true;
                } else if (!old.value().equals(fact.value())) {
                    notice(fence.scope(), old.reference().id(), "CONFLICT", fact.reference().id());
                    notice(fence.scope(), fact.reference().id(), "CONFLICT", old.reference().id());
                    invalidated = true;
                }
            }
        }
        if (invalidated)
            engine.event(fence.scope(), fence.runId(), fence.stepId(), "EVIDENCE_INVALIDATED");
        engine.event(fence.scope(), fence.runId(), fence.stepId(), "EVIDENCE_RECORDED");
        return new Result(null, null, null);
    }

    /**
     * @param scope 私有归属
     * @param id 原证据
     * @param reason 固定通知
     * @param cause 因果身份
     */
    private void notice(TaskScope scope, UUID id, String reason, UUID cause) {
        mapper.notice(scope, id, reason, cause, UUID.randomUUID());
        mapper.invalidate(scope, id);
    }

    /**
     * @param row 存储行
     * @return 绑定实际已提交原始产物，不从模型接受文件地址
     */
    private FieldEvidence fact(EvidenceMapper.Row row) {
        var v = json.decode(row.body(), FieldEvidence.class);
        return new FieldEvidence(
                v.reference(),
                v.targetId(),
                v.fieldPath(),
                v.value(),
                v.sourceType(),
                v.sourceUrl(),
                v.userMessageId(),
                v.retrievedAt(),
                v.publishedAt(),
                v.validForDate(),
                v.expiresAt(),
                v.quote(),
                row.artifactId(),
                v.status());
    }

    /**
     * @param row 不可变正文及通知
     * @param now 当前时间
     * @return 当前认知状态；原始记录不被覆盖
     */
    private Research.View view(EvidenceMapper.Row row, Instant now) {
        var value = fact(row);
        String reason = row.reason();
        if (reason == null && !now.isBefore(value.expiresAt())) reason = "EXPIRED";
        return new Research.View(
                value,
                reason == null
                        ? value.status()
                        : reason.equals("CONFLICT")
                                ? EvidenceRecord.Status.CONFLICT
                                : EvidenceRecord.Status.UNKNOWN,
                reason == null ? "ORIGINAL" : reason);
    }

    /**
     * @param exists 归属存在；越权不暴露资源
     */
    private static void check(boolean exists) {
        if (!exists) throw new TravelStorageFailure(TravelStorageFailure.Code.NOT_FOUND);
    }
}
