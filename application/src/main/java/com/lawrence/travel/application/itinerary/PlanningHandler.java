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

/**
 * 旅行候选生成到展示的业务处理器，统一承接PLAN、VALIDATE、REVIEW和PRESENT阶段。
 *
 * <p>StageExecutor → execute读取权威需求/证据/偏好 → executeAt按阶段分支。
 * PLAN由PlanningAdvisor生成草案或受限Patch；VALIDATE由Java确定性规则检查；
 * REVIEW用独立审核上下文；PRESENT仍需满足候选展示门槛，不等于用户接受。
 * 各分支只返回StageOutput与后继计划，由引擎提交事务复查版本、围栏和预算后投影落库。
 * 局部修改范围与锁定受Java约束，失败可有限次修订或等待用户，不能自动放宽硬约束。
 */
public final class PlanningHandler implements StageHandler {
    private final CandidateStore store;
    private final PlanningAdvisor advisor;
    private final InteractionCodec json;
    private final BudgetLedger budgets;

    /**
     * @param store 候选及报告
     * @param advisor 无工具模型
     * @param json JSON
     * @param budgets 原运行预算
     */
    public PlanningHandler(
            CandidateStore store,
            PlanningAdvisor advisor,
            InteractionCodec json,
            BudgetLedger budgets) {
        this.store = store;
        this.advisor = advisor;
        this.json = json;
        this.budgets = budgets;
    }

    /** {@inheritDoc} 初始规划和局部重规划共享校验、审核与显式接受门禁。 */
    @Override
    public boolean supports(StageType type) {
        return Set.of(StageType.PLAN, StageType.VALIDATE, StageType.REVIEW, StageType.PRESENT)
                .contains(type);
    }

    /**
     * {@inheritDoc}
     *
     * <p>StageExecutor的处理器入口；先从CandidateStore重装当前权威上下文，再调用executeAt。
     * 若使用长期偏好，将实际revision写入检查点，使提交事务能拒绝模型运行期间发生的偏好变更。
     * 返回StageOutput后由执行器prepare/commit，本方法不把模型草案直接发布为生效行程。
     */
    @Override
    public StageOutput execute(ExecutionEngineStore.Claim claim) {
        var context = store.context(claim.fence());
        var result = executeAt(claim, context);
        var body =
                new java.util.LinkedHashMap<String, Object>(
                        json.decode(result.checkpoint(), Map.class));
        if (context.preferences() != null)
            body.put("preferenceRevision", context.preferences().version().revision());
        return new StageOutput(
                result.text(),
                json.encode(body),
                result.next(),
                result.outcome(),
                result.decision());
    }

    /**
     * 按持久阶段类型处理候选，模型产物与确定性检查结论保持分离。
     *
     * <p>PLAN校验草案的任务/需求/基础版本绑定；已有基础版本走受限重规划。VALIDATE读取Java问题列表，
     * 通过后才可REVIEW；审核不读取规划自评。偏好版本改变时沿原预算重新规划，不能给旧草案贴新标签。
     * 所有输出只是提交建议，最终候选可见性和状态由提交事务重新判断。
     *
     * @param claim 领取
     * @param context 本次调用前重装的权威偏好和需求
     * @return 输出必须在C事务再次比较该偏好版本
     */
    private StageOutput executeAt(ExecutionEngineStore.Claim claim, Planning.Context context) {
        var input = input(claim.input());
        if (input.restoreSnapshotId() != null)
            context =
                    new Planning.Context(
                            context.scope(),
                            context.revision(),
                            context.baseVersionId(),
                            context.evidence(),
                            context.now(),
                            store.base(context.scope(), input.restoreSnapshotId()).snapshot(),
                            context.preferences());
        var type = claim.step().key().type();
        if (type == StageType.PLAN) {
            if (context.baseVersionId() != null) return replan(claim, context, input);
            if (claim.step().mode() != PlanningMode.INITIAL)
                throw new IllegalArgumentException("REPLAN_BASE_REQUIRED");
            var prior =
                    input.candidateId() == null
                            ? null
                            : store.read(claim.fence().scope(), input.candidateId());
            var draft =
                    advisor.draft(
                            claim.fence().runId(),
                            context,
                            prior,
                            input.issues() == null ? List.of() : input.issues());
            if (draft == null
                    || draft.snapshot() == null
                    || !draft.snapshot().taskId().equals(context.scope().taskId())
                    || draft.snapshot().constraintRevision() != context.revision().revision()
                    || !java.util.Objects.equals(
                            draft.snapshot().baseVersionId(), context.baseVersionId()))
                throw new IllegalArgumentException("DRAFT_BINDING_INVALID");
            UUID candidate = UUID.randomUUID();
            UUID snapshot = UUID.randomUUID();
            return output(
                    "SAVE_CANDIDATE",
                    candidate,
                    Map.of(
                            "snapshotId",
                            snapshot,
                            "snapshot",
                            draft.snapshot(),
                            "repair",
                            input.repair()),
                    stage(StageType.VALIDATE, new Planning.Input(candidate, false, List.of())),
                    null);
        }
        var candidate = store.read(claim.fence().scope(), input.candidateId());
        if (context.preferences() != null
                && !context.preferences()
                        .version()
                        .equals(candidate.version().preferenceVersion())) {
            // 旧草案不能贴上最新偏好标签；重新规划复用产物、原运行和原修订预算。
            if (budgets.read(claim.fence().runId()).repairRounds() >= 2)
                throw new com.lawrence.travel.domain.execution.control.CallRejectedException(
                        com.lawrence.travel.domain.execution.control.CallRejectedException.Reason
                                .CALL_BUDGET_EXHAUSTED);
            return output(
                    "PREFERENCE_REPLAN",
                    candidate.candidateId(),
                    Map.of(),
                    stage(
                            StageType.PLAN,
                            continued(
                                    claim,
                                    candidate.candidateId(),
                                    true,
                                    List.of(
                                            new ItineraryReport.Issue(
                                                    "PREFERENCE_STALE", null, true, false)))),
                    null);
        }
        if (type == StageType.VALIDATE) {
            var issues = store.validate(claim.fence(), candidate.candidateId());
            return afterCheck(
                    claim,
                    "VALIDATE_ITINERARY",
                    candidate,
                    issues,
                    Map.of("expectedIssues", issues));
        }
        if (type == StageType.REVIEW) {
            if (candidate.validation() == null
                    || candidate.validation().conclusion() != ItineraryReport.Conclusion.PASS)
                throw new IllegalArgumentException("VALIDATION_REQUIRED");
            var result =
                    advisor.review(
                            claim.fence().runId(),
                            context,
                            new Planning.Candidate(
                                    candidate.candidateId(),
                                    candidate.version(),
                                    candidate.snapshot(),
                                    candidate.validation(),
                                    null,
                                    candidate.state()));
            if (result == null || result.issues() == null || result.issues().size() > 50)
                throw new IllegalArgumentException("REVIEW_INVALID");
            return afterCheck(
                    claim,
                    "REVIEW_ITINERARY",
                    candidate,
                    result.issues(),
                    Map.of(
                            "issues",
                            result.issues(),
                            "validationId",
                            candidate.validation().id(),
                            "binding",
                            candidate.validation().binding()));
        }
        return output("PRESENT_ITINERARY", candidate.candidateId(), Map.of(), null, null);
    }

    /**
     * @param claim 原阶段
     * @param id 上轮候选
     * @param repair 共享修订标记
     * @param issues 问题
     * @return 保留扩大历史和基础版本身份
     */
    private Planning.Input continued(
            ExecutionEngineStore.Claim claim,
            UUID id,
            boolean repair,
            List<ItineraryReport.Issue> issues) {
        var context = store.context(claim.fence());
        return new Planning.Input(
                id,
                repair,
                issues,
                context.baseVersionId(),
                input(claim.input()).expansionHistory(),
                input(claim.input()).restoreSnapshotId());
    }

    /**
     * @param claim 当前领取
     * @param context 当前需求
     * @param input 上轮产物
     * @return 仅受限累计Patch；无法解决时有限扩大或暂停
     */
    private StageOutput replan(
            ExecutionEngineStore.Claim claim, Planning.Context context, Planning.Input input) {
        var base = store.base(context.scope(), context.baseVersionId());
        var scope =
                new com.lawrence.travel.domain.itinerary.ImpactAnalyzer()
                        .analyze(
                                base.snapshot(),
                                base.revision(),
                                context.revision(),
                                input.expansionHistory());
        var prior =
                input.candidateId() == null
                        ? null
                        : store.read(context.scope(), input.candidateId());
        Planning.PatchReply reply;
        if (context.restorationTarget() != null && !input.repair()) {
            reply =
                    new Planning.PatchReply(
                            com.lawrence.travel.domain.itinerary.HistoricalPatch.create(
                                    base.snapshot(), context.restorationTarget(), base.hash()),
                            null);
        } else
            reply =
                    advisor.patch(
                            claim.fence().runId(), context, base, scope, prior, input.issues());
        if (reply == null || (reply.patch() == null) == (reply.expansionReason() == null))
            throw new IllegalArgumentException("PATCH_REPLY_INVALID");
        if (reply.patch() == null)
            return replanControl(
                    claim, context, input, "PATCH_SCOPE_EXPANSION", reply.expansionReason());
        com.lawrence.travel.domain.itinerary.TripSnapshot snapshot;
        try {
            snapshot =
                    new com.lawrence.travel.domain.itinerary.PatchService()
                            .apply(
                                    base.snapshot(),
                                    context.baseVersionId(),
                                    base.hash(),
                                    context.revision().revision(),
                                    scope,
                                    reply.patch());
        } catch (IllegalArgumentException invalid) {
            return replanControl(
                    claim,
                    context,
                    input,
                    context.restorationTarget() == null ? "PATCH_REJECTED" : "RESTORE_CONFLICT",
                    "补丁违反对象、范围、锁定或基础版本约束，请在原边界修订。");
        }
        UUID id = UUID.randomUUID();
        var next =
                new Planning.Input(
                        id,
                        false,
                        List.of(),
                        context.baseVersionId(),
                        input.expansionHistory(),
                        input.restoreSnapshotId());
        return output(
                "SAVE_CANDIDATE",
                id,
                Map.of(
                        "snapshotId",
                        UUID.randomUUID(),
                        "snapshot",
                        snapshot,
                        "patch",
                        reply.patch(),
                        "impactScope",
                        scope,
                        "repair",
                        input.repair()),
                stage(StageType.VALIDATE, next),
                null);
    }

    /**
     * @param claim 原运行
     * @param context 当前需求
     * @param input 原历史
     * @param code 固定原因
     * @param reason 有界扩大说明
     * @return 同一预算内继续；锁定冲突及预算用尽只产生用户问题
     */
    private StageOutput replanControl(
            ExecutionEngineStore.Claim claim,
            Planning.Context context,
            Planning.Input input,
            String code,
            String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw new IllegalArgumentException("PATCH_REASON_INVALID");
        var history = new java.util.ArrayList<>(input.expansionHistory());
        boolean expanding = code.equals("PATCH_SCOPE_EXPANSION");
        boolean canContinue =
                !code.equals("RESTORE_CONFLICT")
                        && budgets.read(claim.fence().runId()).repairRounds() < 2
                        && (!expanding
                                || history.size() < 2
                                        && new com.lawrence.travel.domain.itinerary.ImpactAnalyzer()
                                                        .analyze(
                                                                store.base(
                                                                                context.scope(),
                                                                                context
                                                                                        .baseVersionId())
                                                                        .snapshot(),
                                                                store.base(
                                                                                context.scope(),
                                                                                context
                                                                                        .baseVersionId())
                                                                        .revision(),
                                                                context.revision(),
                                                                history)
                                                        .level()
                                                < 4);
        if (canContinue) {
            if (expanding) history.add(reason);
            var next =
                    new Planning.Input(
                            input.candidateId(),
                            true,
                            List.of(new ItineraryReport.Issue(code, null, true, false)),
                            context.baseVersionId(),
                            List.copyOf(history),
                            input.restoreSnapshotId());
            return output(
                    "REPLAN_CONTROL",
                    UUID.randomUUID(),
                    Map.of("reasonCode", code, "expansionHistory", history),
                    stage(StageType.PLAN, next),
                    null);
        }
        UUID question = UUID.randomUUID();
        var decision =
                new DecisionRequest(
                        UUID.randomUUID(),
                        context.scope(),
                        claim.fence().runId(),
                        claim.fence().generation(),
                        1,
                        context.revision().revision(),
                        Map.of(
                                question,
                                new DecisionRequest.Question(
                                        question,
                                        "局部修改无法在当前约束与预算内完成，请补充资料或明确提出新的调整要求；不会自动解除锁定或放宽硬约束。",
                                        Map.of(),
                                        null)),
                        true,
                        Set.of(question),
                        DecisionRequest.State.OPEN);
        return output(
                "REPLAN_CONTROL",
                UUID.randomUUID(),
                Map.of(
                        "reasonCode",
                        code,
                        "expansionHistory",
                        history,
                        "decisionSpecs",
                        Map.of(
                                "specs",
                                Map.of(
                                        question,
                                        new Negotiation.QuestionSpec(
                                                "hardRequirements",
                                                "保留当前生效版本和有效产物。",
                                                "明确补充后产生新的候选，仍需接受。",
                                                Map.of())))),
                null,
                decision);
    }

    /**
     * @param text 已持久化对象，兼容研究的空初始输入及旧重校验输入
     * @return 显式默认值，避免缺失primitive被严格JSON解码拒绝
     */
    private Planning.Input input(String text) {
        var body = json.decode(text, Map.class);
        UUID id =
                body.get("candidateId") == null
                        ? null
                        : UUID.fromString((String) body.get("candidateId"));
        var issues = new java.util.ArrayList<ItineraryReport.Issue>();
        if (body.get("issues") instanceof List<?> values)
            for (Object value : values)
                issues.add(json.decode(json.encode(value), ItineraryReport.Issue.class));
        var history = new java.util.ArrayList<String>();
        if (body.get("expansionHistory") instanceof List<?> values)
            for (Object value : values) history.add((String) value);
        return new Planning.Input(
                id,
                Boolean.TRUE.equals(body.get("repair")),
                issues,
                null,
                List.copyOf(history),
                body.get("restoreSnapshotId") == null
                        ? null
                        : UUID.fromString((String) body.get("restoreSnapshotId")));
    }

    /**
     * @param claim 原运行
     * @param operation 权威操作
     * @param candidate 草案
     * @param issues 程序／审核问题
     * @param extra 精确绑定
     * @return 通过后继续，否则最多两轮修订，最后等待明确补充
     */
    private StageOutput afterCheck(
            ExecutionEngineStore.Claim claim,
            String operation,
            Planning.Candidate candidate,
            List<ItineraryReport.Issue> issues,
            Map<String, Object> extra) {
        boolean blocked = issues.stream().anyMatch(ItineraryReport.Issue::blocking);
        if (!blocked)
            return output(
                    operation,
                    candidate.candidateId(),
                    extra,
                    stage(
                            operation.equals("VALIDATE_ITINERARY")
                                    ? StageType.REVIEW
                                    : StageType.PRESENT,
                            continued(claim, candidate.candidateId(), false, List.of())),
                    null);
        if (budgets.read(claim.fence().runId()).repairRounds() < 2)
            return output(
                    operation,
                    candidate.candidateId(),
                    extra,
                    stage(StageType.PLAN, continued(claim, candidate.candidateId(), true, issues)),
                    null);
        UUID question = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        var decision =
                new DecisionRequest(
                        decisionId,
                        claim.fence().scope(),
                        claim.fence().runId(),
                        claim.fence().generation(),
                        1,
                        claim.fence().constraintRevision(),
                        Map.of(
                                question,
                                new DecisionRequest.Question(
                                        question,
                                        "草案仍有阻断问题："
                                                + issues.stream()
                                                        .filter(ItineraryReport.Issue::blocking)
                                                        .map(ItineraryReport.Issue::code)
                                                        .distinct()
                                                        .limit(8)
                                                        .toList()
                                                + "。请补充可核实资料或明确修改要求；不会自动放宽原约束。",
                                        Map.of(),
                                        null)),
                        true,
                        Set.of(question),
                        DecisionRequest.State.OPEN);
        var values = new java.util.LinkedHashMap<>(extra);
        values.put(
                "decisionSpecs",
                Map.of(
                        "specs",
                        Map.of(
                                question,
                                new Negotiation.QuestionSpec(
                                        "hardRequirements",
                                        "已用完本运行两轮修订，保留草案和问题。",
                                        "补充资料或提交新修改后重新校验；不自动接受。",
                                        Map.of()))));
        return output(operation, candidate.candidateId(), values, null, decision);
    }

    /**
     * @param operation 单一业务信封
     * @param candidate 当前候选
     * @param extra 不可信数据经对应投影校验
     * @param next 后继或空
     * @param decision 阻断补充卡片
     * @return 不可变待提交产物
     */
    private StageOutput output(
            String operation,
            UUID candidate,
            Map<String, Object> extra,
            StagePlan next,
            DecisionRequest decision) {
        var body = new java.util.LinkedHashMap<String, Object>(extra);
        body.put("operation", operation);
        body.put("candidateId", candidate);
        return new StageOutput(
                decision == null ? "行程草案及校验进度已保存，接受前不会生效。" : "草案需要补充信息，已停止自动修订。",
                json.encode(body),
                next == null ? List.of() : List.of(next),
                decision != null
                        ? StageOutput.Outcome.WAIT_USER
                        : next != null
                                ? StageOutput.Outcome.CONTINUE
                                : StageOutput.Outcome.PRESENT_CANDIDATE,
                decision);
    }

    /**
     * @param type 后继
     * @param input 可靠输入
     * @return 服务端生成阶段身份，保留REPLAN模式和扩大历史，重试不重置预算
     */
    private StagePlan stage(StageType type, Planning.Input input) {
        return new StagePlan(
                UUID.randomUUID(),
                new ExecutionStep.Key(
                        type, input.repair() ? 1 : 0, "planning_" + UUID.randomUUID()),
                type == StageType.PLAN
                        ? input.snapshotId() == null ? PlanningMode.INITIAL : PlanningMode.REPLAN
                        : null,
                json.encode(input),
                List.of());
    }
}
