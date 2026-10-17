package com.lawrence.travel.application.itinerary;

import com.lawrence.travel.application.execution.StageOutput;
import com.lawrence.travel.application.execution.StagePlan;
import com.lawrence.travel.application.interaction.Negotiation;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.application.port.CandidateStore;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.PlanningAdvisor;
import com.lawrence.travel.application.port.StageHandler;
import com.lawrence.travel.domain.execution.ExecutionStep;
import com.lawrence.travel.domain.execution.PlanningMode;
import com.lawrence.travel.domain.execution.StageType;
import com.lawrence.travel.domain.interaction.DecisionRequest;
import com.lawrence.travel.domain.itinerary.ItineraryReport;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** PLAN／VALIDATE／REVIEW／PRESENT 唯一业务处理器；模型调用在事务外，C事务重新判定门禁。 */
public final class PlanningHandler implements StageHandler {
    private final CandidateStore store;
    private final PlanningAdvisor advisor;
    private final InteractionCodec json;
    private final BudgetLedger budgets;
    /** @param store 候选及报告 @param advisor 无工具模型 @param json JSON @param budgets 原运行预算 */
    public PlanningHandler(CandidateStore store, PlanningAdvisor advisor, InteractionCodec json, BudgetLedger budgets) { this.store = store; this.advisor = advisor; this.json = json; this.budgets = budgets; }
    /** {@inheritDoc} 仅初始闭环；受限局部 Patch 在后续阶段接入。 */
    @Override public boolean supports(StageType type) { return Set.of(StageType.PLAN, StageType.VALIDATE, StageType.REVIEW, StageType.PRESENT).contains(type); }
    /** {@inheritDoc} 预测分支必须与C事务重新计算相符；未知不伪造通过。 */
    @Override public StageOutput execute(ExecutionEngineStore.Claim claim) {
        var context = store.context(claim.fence()); var input = input(claim.input());
        var type = claim.step().key().type();
        if (type == StageType.PLAN) {
            if (claim.step().mode() != PlanningMode.INITIAL) throw new IllegalArgumentException("REPLAN_NOT_IMPLEMENTED");
            var prior = input.candidateId() == null ? null : store.read(claim.fence().scope(), input.candidateId());
            var draft = advisor.draft(claim.fence().runId(), context, prior, input.issues() == null ? List.of() : input.issues());
            if (draft == null || draft.snapshot() == null || !draft.snapshot().taskId().equals(context.scope().taskId()) || draft.snapshot().constraintRevision() != context.revision().revision()
                    || !java.util.Objects.equals(draft.snapshot().baseVersionId(), context.baseVersionId())) throw new IllegalArgumentException("DRAFT_BINDING_INVALID");
            UUID candidate = UUID.randomUUID(); UUID snapshot = UUID.randomUUID();
            return output("SAVE_CANDIDATE", candidate, Map.of("snapshotId", snapshot, "snapshot", draft.snapshot(), "repair", input.repair()), stage(StageType.VALIDATE, new Planning.Input(candidate, false, List.of())), null);
        }
        var candidate = store.read(claim.fence().scope(), input.candidateId());
        if (type == StageType.VALIDATE) {
            var issues = store.validate(claim.fence(), candidate.candidateId());
            return afterCheck(claim, "VALIDATE_ITINERARY", candidate, issues, Map.of("expectedIssues", issues));
        }
        if (type == StageType.REVIEW) {
            if (candidate.validation() == null || candidate.validation().conclusion() != ItineraryReport.Conclusion.PASS) throw new IllegalArgumentException("VALIDATION_REQUIRED");
            var result = advisor.review(claim.fence().runId(), context, new Planning.Candidate(candidate.candidateId(), candidate.version(), candidate.snapshot(), candidate.validation(), null, candidate.state()));
            if (result == null || result.issues() == null || result.issues().size() > 50) throw new IllegalArgumentException("REVIEW_INVALID");
            return afterCheck(claim, "REVIEW_ITINERARY", candidate, result.issues(), Map.of("issues", result.issues(), "validationId", candidate.validation().id(), "binding", candidate.validation().binding()));
        }
        return output("PRESENT_ITINERARY", candidate.candidateId(), Map.of(), null, null);
    }
    /** @param text 已持久化对象，兼容研究的空初始输入及旧重校验输入 @return 显式默认值，避免缺失primitive被严格JSON解码拒绝 */
    private Planning.Input input(String text) {
        var body = json.decode(text, Map.class); UUID id = body.get("candidateId") == null ? null : UUID.fromString((String) body.get("candidateId"));
        var issues = new java.util.ArrayList<ItineraryReport.Issue>();
        if (body.get("issues") instanceof List<?> values) for (Object value : values) issues.add(json.decode(json.encode(value), ItineraryReport.Issue.class));
        return new Planning.Input(id, Boolean.TRUE.equals(body.get("repair")), issues);
    }
    /** @param claim 原运行 @param operation 权威操作 @param candidate 草案 @param issues 程序／审核问题 @param extra 精确绑定 @return 通过后继续，否则最多两轮修订，最后等待明确补充 */
    private StageOutput afterCheck(ExecutionEngineStore.Claim claim, String operation, Planning.Candidate candidate, List<ItineraryReport.Issue> issues, Map<String, Object> extra) {
        boolean blocked = issues.stream().anyMatch(ItineraryReport.Issue::blocking);
        if (!blocked) return output(operation, candidate.candidateId(), extra, stage(operation.equals("VALIDATE_ITINERARY") ? StageType.REVIEW : StageType.PRESENT, new Planning.Input(candidate.candidateId(), false, List.of())), null);
        if (budgets.read(claim.fence().runId()).repairRounds() < 2) return output(operation, candidate.candidateId(), extra, stage(StageType.PLAN, new Planning.Input(candidate.candidateId(), true, issues)), null);
        UUID question = UUID.randomUUID(); UUID decisionId = UUID.randomUUID();
        var decision = new DecisionRequest(decisionId, claim.fence().scope(), claim.fence().runId(), claim.fence().generation(), 1, claim.fence().constraintRevision(),
                Map.of(question, new DecisionRequest.Question(question, "草案仍有阻断问题：" + issues.stream().filter(ItineraryReport.Issue::blocking).map(ItineraryReport.Issue::code).distinct().limit(8).toList() + "。请补充可核实资料或明确修改要求；不会自动放宽原约束。", Map.of(), null)), true, Set.of(question), DecisionRequest.State.OPEN);
        var values = new java.util.LinkedHashMap<>(extra); values.put("decisionSpecs", Map.of("specs", Map.of(question, new Negotiation.QuestionSpec("hardRequirements", "已用完本运行两轮修订，保留草案和问题。", "补充资料或提交新修改后重新校验；不自动接受。", Map.of()))));
        return output(operation, candidate.candidateId(), values, null, decision);
    }
    /** @param operation 单一业务信封 @param candidate 当前候选 @param extra 不可信数据经对应投影校验 @param next 后继或空 @param decision 阻断补充卡片 @return 不可变待提交产物 */
    private StageOutput output(String operation, UUID candidate, Map<String, Object> extra, StagePlan next, DecisionRequest decision) {
        var body = new java.util.LinkedHashMap<String, Object>(extra); body.put("operation", operation); body.put("candidateId", candidate);
        return new StageOutput(decision == null ? "行程草案及校验进度已保存，接受前不会生效。" : "草案需要补充信息，已停止自动修订。", json.encode(body), next == null ? List.of() : List.of(next),
                decision != null ? StageOutput.Outcome.WAIT_USER : next != null ? StageOutput.Outcome.CONTINUE : StageOutput.Outcome.PRESENT_CANDIDATE, decision);
    }
    /** @param type 后继 @param input 可靠输入 @return 服务端生成阶段身份，修订仍属INITIAL而非局部Patch */
    private StagePlan stage(StageType type, Planning.Input input) { return new StagePlan(UUID.randomUUID(), new ExecutionStep.Key(type, input.repair() ? 1 : 0, "planning_" + UUID.randomUUID()), type == StageType.PLAN ? PlanningMode.INITIAL : null, json.encode(input), List.of()); }
}
