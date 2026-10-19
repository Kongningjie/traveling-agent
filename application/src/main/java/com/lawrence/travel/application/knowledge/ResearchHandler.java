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
            var prepared = new Research.Prepared("seed", dependency, initial, Set.of(), null);
            if (plan.strategy() != null) return strategy(claim, context, plan, queries, prepared, body);
            return output(prepared, new Research.Input(queries, 0, dependency), context.baseVersionId() != null);
        }
        // 旧PG检查点没有handoff字段，显式补null兼容，不能为旧阶段构造虚假的委派身份。
        var normalized = new java.util.LinkedHashMap<String,Object>(body); normalized.putIfAbsent("handoff", null);
        var input = json.decode(json.encode(normalized), Research.Input.class);
        if (input.handoff() != null) {
            var h = input.handoff();
            if (!h.scope().equals(claim.fence().scope()) || !h.run().equals(claim.fence().runId()) || h.generation()!=claim.fence().generation() || h.revision()!=claim.fence().constraintRevision()
                    || h.preferenceRevision()!=context.preferenceRevision() || !h.role().equals("research") || !h.schema().equals("research-prepared-v1")
                    || input.queries().size()!=1 || input.cursor()!=0 || !h.tools().equals(Set.of(input.queries().getFirst().operation().name()))
                    || !context.evidence().stream().filter(v -> v.effectiveStatus()==EvidenceRecord.Status.KNOWN).map(v -> v.evidence().reference()).collect(java.util.stream.Collectors.toSet()).containsAll(h.sources())) throw new IllegalArgumentException("AGENT_HANDOFF_STALE");
        }
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
        if (input.handoff() != null) return new StageOutput("委派调查分支已结束；未知保留至汇合处理。", json.encode(Map.of("research",prepared,"handoff",input.handoff(),"strategyPreferenceRevision",context.preferenceRevision())), List.of(), StageOutput.Outcome.BRANCH_COMPLETE, null);
        return output(prepared, new Research.Input(input.queries(), input.cursor() + 1, dependency), context.baseVersionId() != null);
    }
    /** @param claim 协调领取 @param context 权威上下文 @param plan 模型建议 @param queries 校验后查询 @param prepared 初始事实 @param body 持久化补查水位 @return 原调度器可执行的有限DAG，不能递归或跳过审核 */
    private StageOutput strategy(ExecutionEngineStore.Claim claim, Research.Context context, Research.Plan plan, List<Research.Query> queries, Research.Prepared prepared, Map body) {
        var policyPlan=plan.strategy(); int round=body.get("strategyRound") instanceof Number n?n.intValue():0;
        if (round<0 || round>1 || queries.size()!=policyPlan.branches().size()) throw new IllegalArgumentException("AGENT_STRATEGY_INVALID");
        var prior=new java.util.HashSet<String>(); if(body.get("previousKeys") instanceof List<?> keys) for(var key:keys) prior.add((String)key);
        if (queries.stream().anyMatch(q -> prior.contains(policy.key(q)))) throw new IllegalArgumentException("RESEARCH_NO_PROGRESS");
        for(int i=0;i<queries.size();i++) if(!policyPlan.branches().get(i).allowedTools().equals(List.of(queries.get(i).operation().name()))) throw new IllegalArgumentException("AGENT_TOOL_SCOPE_INVALID");
        var checkpoint=new java.util.LinkedHashMap<String,Object>(); checkpoint.put("research",prepared); checkpoint.put("strategy",policyPlan); checkpoint.put("strategyRound",round); checkpoint.put("strategyPreferenceRevision",context.preferenceRevision());
        if(policyPlan.goal()==com.lawrence.travel.domain.execution.AgentStrategy.Goal.CLARIFY) {
            if(!Set.of("cities","startDate","endDate","travelers","budgetRule","fixedArrangements","hardRequirements","interests").containsAll(policyPlan.missingFields())) throw new IllegalArgumentException("AGENT_MISSING_FIELD_INVALID");
            var questions=new java.util.LinkedHashMap<java.util.UUID,com.lawrence.travel.domain.interaction.DecisionRequest.Question>();
            var specs=new java.util.LinkedHashMap<java.util.UUID,com.lawrence.travel.application.interaction.Negotiation.QuestionSpec>();
            // 一个字段对应一个问题及解释契约，避免多个缺项的回答被错误写入首个字段。
            for(var field:policyPlan.missingFields().stream().distinct().toList()) {
                var id=java.util.UUID.randomUUID();
                questions.put(id,new com.lawrence.travel.domain.interaction.DecisionRequest.Question(id,"请补充旅行信息："+field+"。不会自动放宽已有约束。",Map.of(),null));
                specs.put(id,new com.lawrence.travel.application.interaction.Negotiation.QuestionSpec(field,"已有要求继续保留。","补充后仍需形成候选并明确接受。",Map.of()));
            }
            var decision=new com.lawrence.travel.domain.interaction.DecisionRequest(java.util.UUID.randomUUID(),claim.fence().scope(),claim.fence().runId(),claim.fence().generation(),1,claim.fence().constraintRevision(),questions,true,questions.keySet(),com.lawrence.travel.domain.interaction.DecisionRequest.State.OPEN);
            checkpoint.put("decisionSpecs",Map.of("specs",specs));
            return new StageOutput("调查需要补充信息。",json.encode(checkpoint),List.of(),StageOutput.Outcome.WAIT_USER,decision);
        }
        var steps=new java.util.ArrayList<com.lawrence.travel.application.execution.StagePlan>();var ids=new java.util.ArrayList<java.util.UUID>();
        var sources=context.evidence().stream().filter(v -> v.effectiveStatus()==EvidenceRecord.Status.KNOWN && context.now().isBefore(v.evidence().expiresAt())).map(v -> v.evidence().reference()).limit(50).collect(java.util.stream.Collectors.toSet());
        for(int i=0;i<queries.size();i++) {
            var id=java.util.UUID.randomUUID(); var node=policyPlan.branches().get(i);
            var h=new Research.Handoff(claim.fence().scope(),claim.fence().runId(),claim.fence().generation(),claim.fence().constraintRevision(),context.preferenceRevision(),"research","research-prepared-v1",Set.of(queries.get(i).operation().name()),sources);
            steps.add(new com.lawrence.travel.application.execution.StagePlan(id,new com.lawrence.travel.domain.execution.ExecutionStep.Key(StageType.RESEARCH,round,"delegate_"+id),null,json.encode(new Research.Input(List.of(queries.get(i)),0,prepared.dependencyHash(),h)),node.dependsOn().stream().map(ids::get).toList()));ids.add(id); prior.add(policy.key(queries.get(i)));
        }
        boolean refine=round==0 && !queries.isEmpty() && policyPlan.stop()==com.lawrence.travel.domain.execution.AgentStrategy.Stop.REFINE_ONCE;
        var join=java.util.UUID.randomUUID();steps.add(new com.lawrence.travel.application.execution.StagePlan(join,new com.lawrence.travel.domain.execution.ExecutionStep.Key(refine?StageType.RESEARCH:StageType.PLAN,round,"join_"+join),refine?null:context.baseVersionId()==null?com.lawrence.travel.domain.execution.PlanningMode.INITIAL:com.lawrence.travel.domain.execution.PlanningMode.REPLAN,refine?json.encode(Map.of("strategyRound",1,"previousKeys",prior)):"{}",ids));
        return new StageOutput("协调策略已校验；分支受原预算和依赖限制，汇合后再规划。",json.encode(checkpoint),steps,StageOutput.Outcome.CONTINUE,null);
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
