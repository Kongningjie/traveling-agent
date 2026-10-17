package com.lawrence.travel.application.knowledge;

import com.lawrence.travel.application.execution.StageOutput;
import com.lawrence.travel.application.execution.TravelToolService;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.ResearchAdvisor;
import com.lawrence.travel.application.port.ResearchStore;
import com.lawrence.travel.application.port.StageHandler;
import com.lawrence.travel.application.port.TravelToolPort;
import com.lawrence.travel.domain.execution.StageType;
import com.lawrence.travel.domain.execution.control.CallRejectedException;
import com.lawrence.travel.domain.knowledge.FieldEvidence;
import com.lawrence.travel.domain.knowledge.EvidenceRecord;
import com.lawrence.travel.domain.interaction.RequirementFields;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 一个工具查询一个可恢复 RESEARCH 实例；已提交查询可复用，网络不占业务事务。 */
public final class ResearchHandler implements StageHandler {
    private final ResearchStore store;
    private final ResearchAdvisor advisor;
    private final TravelToolService tools;
    private final InteractionCodec json;
    private final EvidencePolicy policy;
    private final List<FieldEvidence> seeds;
    /** @param store 证据端口 @param advisor 有预算的调查策略 @param tools 既有受控工具用例 @param json JSON @param policy 确定性规则 @param seeds 人工种子，不能永久有效 */
    public ResearchHandler(ResearchStore store, ResearchAdvisor advisor, TravelToolService tools, InteractionCodec json, EvidencePolicy policy, List<FieldEvidence> seeds) {
        this.store = store; this.advisor = advisor; this.tools = tools; this.json = json; this.policy = policy; this.seeds = List.copyOf(seeds);
    }
    /** {@inheritDoc} 仅新增调查，不替代规划或另建调度器。 */
    @Override public boolean supports(StageType type) { return type == StageType.RESEARCH; }
    /** {@inheritDoc} 计划和结果按阶段保存，重试沿用预算；免费开关拒绝不能降级成供应商事实。 */
    @Override public StageOutput execute(ExecutionEngineStore.Claim claim) {
        var context = store.context(claim.fence()); var fields = RequirementFields.fields(context.revision().requirements());
        String dependency = EvidencePolicy.dependency(context.revision(), json::encode);
        var body = json.decode(claim.input(), Map.class);
        if (!body.containsKey("queries")) {
            var plan = advisor.plan(claim.fence().runId(), context);
            if (plan == null || plan.queries() == null || plan.queries().size() > 12) throw new IllegalArgumentException("RESEARCH_PLAN_LIMIT");
            var queries = plan.queries().stream().map(q -> policy.checked(q, LocalDate.parse(fields.get("startDate")), LocalDate.parse(fields.get("endDate")))).toList();
            if (queries.stream().map(policy::key).distinct().count() != queries.size()) throw new IllegalArgumentException("RESEARCH_NO_PROGRESS");
            var applicableSeeds = seeds.stream().filter(seed -> seed.sourceType() == FieldEvidence.SourceType.SEED
                    && !context.now().isBefore(seed.retrievedAt()) && context.now().isBefore(seed.expiresAt())
                    && context.now().isBefore(seed.retrievedAt().plusSeconds(86400))
                    && context.evidence().stream().noneMatch(view -> view.reason().equals("ORIGINAL") && view.evidence().sourceType() == FieldEvidence.SourceType.SEED
                        && view.evidence().targetId().equals(seed.targetId()) && view.evidence().fieldPath().equals(seed.fieldPath())
                        && java.util.Objects.equals(view.evidence().sourceUrl(), seed.sourceUrl()) && view.evidence().retrievedAt().equals(seed.retrievedAt()) && view.evidence().quote().equals(seed.quote()))
                    && context.revision().requirements().stream().anyMatch(r -> r.field().equals("cities") && java.util.Arrays.asList(r.value().split(",")).contains(seed.targetId())))
                    .map(seed -> new FieldEvidence(new EvidenceRecord.Reference(java.util.UUID.randomUUID(), 1, EvidencePolicy.hash(seed.quote())), seed.targetId(), seed.fieldPath(), seed.value(), FieldEvidence.SourceType.SEED,
                            seed.sourceUrl(), null, seed.retrievedAt(), seed.publishedAt(), seed.validForDate(), seed.expiresAt().isBefore(seed.retrievedAt().plusSeconds(86400)) ? seed.expiresAt() : seed.retrievedAt().plusSeconds(86400), seed.quote(), null, seed.status())).toList();
            var initial = new java.util.ArrayList<>(applicableSeeds);
            for (var fixed : context.revision().fixedArrangements()) {
                String value = json.encode(fixed);
                if (context.evidence().stream().anyMatch(view -> view.reason().equals("ORIGINAL") && view.evidence().sourceType() == FieldEvidence.SourceType.USER && view.evidence().targetId().equals(fixed.id().toString()) && view.evidence().value().equals(value))) continue;
                initial.add(new FieldEvidence(new EvidenceRecord.Reference(java.util.UUID.randomUUID(), 1, EvidencePolicy.hash(value)), fixed.id().toString(), "fixedArrangement", value,
                        FieldEvidence.SourceType.USER, null, fixed.sourceMessageId(), context.now(), null, fixed.startsAt().toLocalDate(), context.now().plusSeconds(86400), value, null, EvidenceRecord.Status.KNOWN));
            }
            return output(new Research.Prepared("seed", dependency, initial, Set.of(), null), new Research.Input(queries, 0, dependency), context.baseVersionId() != null);
        }
        var input = json.decode(claim.input(), Research.Input.class);
        if (!dependency.equals(input.dependencyHash()) || input.queries() == null || input.queries().size() > 12 || input.cursor() < 0 || input.cursor() >= input.queries().size()) throw new IllegalArgumentException("RESEARCH_INPUT_STALE");
        var query = policy.checked(input.queries().get(input.cursor()), LocalDate.parse(fields.get("startDate")), LocalDate.parse(fields.get("endDate")));
        var cached = store.cached(claim.fence().scope(), policy.key(query), dependency, query.date());
        Research.Prepared prepared;
        if (!cached.isEmpty()) prepared = new Research.Prepared(policy.key(query), dependency, List.of(), cached.stream().map(FieldEvidence::reference).collect(java.util.stream.Collectors.toSet()), null);
        else {
            TravelToolPort.Evidence raw;
            try { raw = tools.query(claim.fence().runId(), query.operation(), query.arguments()); }
            catch (IllegalStateException failure) {
                String code = failure.getMessage();
                if ("TOOLS_DISABLED".equals(code)) throw new CallRejectedException(CallRejectedException.Reason.EXTERNAL_DISABLED);
                if (code != null && code.startsWith("TOOL_QUOTA_")) throw new CallRejectedException(CallRejectedException.Reason.QUOTA_UNVERIFIED);
                if (Thread.currentThread().isInterrupted()) throw failure;
                // 传输失败保存有界未知，绝不把供应商异常正文或 URL 拼入产物。
                raw = new TravelToolPort.Evidence(query.operation() == TravelToolPort.Operation.EXTRACT ? policy.requireOfficial(query.arguments().get("url")) : java.net.URI.create("https://unavailable.invalid/"), context.now(), json.encode(Map.of("operation", query.operation().name(), "status", "UNKNOWN_TOOL_FAILURE", "data", Map.of())));
            }
            Research.Extraction extraction = query.operation() == TravelToolPort.Operation.EXTRACT && !policy.extractedText(raw).isBlank() ? advisor.extract(claim.fence().runId(), query, raw) : null;
            prepared = new Research.Prepared(policy.key(query), dependency, List.of(policy.normalize(query, raw, extraction, java.time.Instant.now())), Set.of(), raw);
        }
        return output(prepared, new Research.Input(input.queries(), input.cursor() + 1, dependency), context.baseVersionId() != null);
    }
    /** @param prepared 待 C 事务登记 @param next 已持久化计划游标 @param replan 是否已有生效版本 @return 后继同事务登记；查询结束后只排队 PLAN，不伪造规划完成 */
    private StageOutput output(Research.Prepared prepared, Research.Input next, boolean replan) {
        boolean more = next.cursor() < next.queries().size();
        var stage = new com.lawrence.travel.application.execution.StagePlan(java.util.UUID.randomUUID(),
                new com.lawrence.travel.domain.execution.ExecutionStep.Key(more ? StageType.RESEARCH : StageType.PLAN, 0, "research_" + java.util.UUID.randomUUID()),
                more ? null : replan ? com.lawrence.travel.domain.execution.PlanningMode.REPLAN : com.lawrence.travel.domain.execution.PlanningMode.INITIAL, more ? json.encode(next) : "{}", List.of());
        return new StageOutput("调查结果已登记；未知和冲突须在规划中显式处理。", json.encode(Map.of("research", prepared)), List.of(stage), StageOutput.Outcome.CONTINUE, null);
    }
}
