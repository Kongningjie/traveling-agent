package com.lawrence.travel.application.interaction;

import com.lawrence.travel.application.execution.StageOutput;
import com.lawrence.travel.application.execution.StagePlan;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.InputInterpreter;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.NegotiationStore;
import com.lawrence.travel.application.port.StageHandler;
import com.lawrence.travel.domain.execution.ExecutionStep;
import com.lawrence.travel.domain.execution.StageType;
import com.lawrence.travel.domain.interaction.DecisionRequest;
import com.lawrence.travel.domain.interaction.RequirementFields;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 合并需求字段；缺项时提问，信息齐全后进入后续阶段。
 */
public final class NegotiationHandler implements StageHandler {
    private final NegotiationStore store;
    private final InteractionCodec json;
    private final InputInterpreter interpreter;
    private final int maximumClarifications;

    /**
     * @param store 权威上下文
     * @param json 严格编解码
     * @param interpreter 自由文本提取
     * @param maximumClarifications 主动追问轮数，达到后只保留缺项等待手动补充
     */
    public NegotiationHandler(
            NegotiationStore store,
            InteractionCodec json,
            InputInterpreter interpreter,
            int maximumClarifications) {
        if (maximumClarifications < 1)
            throw new IllegalArgumentException("CLARIFICATION_LIMIT_INVALID");
        this.store = store;
        this.json = json;
        this.interpreter = interpreter;
        this.maximumClarifications = maximumClarifications;
    }

    /**
     * {@inheritDoc} 只处理协商类型；没有注册研究或规划替身冒充后续能力。
     */
    @Override
    public boolean supports(StageType type) {
        return type == StageType.UNDERSTAND;
    }

    /**
     * 解码阶段inputId后读取协商上下文：INTERPRETED输入直接复用聊天已提取facts，其他原文才调用 InputInterpreter。
     */
    @Override
    public StageOutput execute(ExecutionEngineStore.Claim claim) {
        var input = json.decode(claim.input(), Input.class);
        var context = store.context(claim.fence().scope(), input.inputId());
        var facts = new ArrayList<>(context.message().facts());
        String clarification = "";
        boolean outside = false;
        if (!context.message().text().isBlank()
                && !context.message().kind().equals("INTERPRETED")) {
            var parsed = interpreter.interpret(claim.fence().runId(), context);
            if (parsed.facts() == null || parsed.facts().size() > 20)
                throw new IllegalArgumentException("INTERPRETATION_INVALID");
            for (var fact : parsed.facts()) {
                if (fact.quote() == null
                        || fact.quote().isBlank()
                        || !context.message().text().contains(fact.quote()))
                    throw new IllegalArgumentException("SOURCE_MISMATCH");
                facts.add(fact);
            }
            outside = parsed.outsideScope();
            clarification = parsed.clarification() == null ? "" : parsed.clarification();
        }
        Map<String, String> updates;
        try {
            updates = updates(facts);
        } catch (IllegalArgumentException conflict) {
            facts.clear();
            updates = Map.of();
            clarification = "选项与补充内容冲突，已保留原文，请明确选择。";
        }
        if (context.response() != null) {
            var allowed =
                    context.card().specs().entrySet().stream()
                            .filter(
                                    entry ->
                                            !context.response().action().equals("AUTHORIZE")
                                                    || context.response()
                                                            .authorization()
                                                            .contains(entry.getKey()))
                            .map(entry -> entry.getValue().field())
                            .collect(java.util.stream.Collectors.toSet());
            outside |= !allowed.containsAll(updates.keySet());
        }
        if (outside && context.response() != null) {
            UUID replacement = outsideId(context.message().id());
            return new StageOutput(
                    "回答包含新的修改，将作为变更继续处理。",
                    json.encode(
                            Map.of(
                                    "negotiation",
                                    new Negotiation.Prepared(
                                            context.message().id(),
                                            facts,
                                            Set.of(),
                                            true,
                                            "",
                                            null))),
                    List.of(stage(replacement, StageType.UNDERSTAND)),
                    StageOutput.Outcome.CONTINUE,
                    null);
        }
        List<ConstraintRevision.Requirement> requirements;
        try {
            requirements =
                    RequirementFields.merge(
                            RequirementFields.inputBase(
                                    context.revision().requirements(), updates.keySet()),
                            updates,
                            source(context),
                            replacements(facts));
        } catch (IllegalArgumentException conflict) {
            facts.clear();
            requirements = context.revision().requirements();
            clarification = "已保留原始要求，存在冲突或超出首版范围，请修改相关字段。";
        }
        var resolved = new LinkedHashSet<UUID>();
        if (context.card() != null)
            for (var entry : context.card().specs().entrySet())
                if (facts.stream().anyMatch(fact -> fact.field().equals(entry.getValue().field())))
                    resolved.add(entry.getKey());
        long revision = context.revision().revision() + 1;
        var missing = new ArrayList<>(RequirementFields.missing(requirements));
        if (!clarification.isBlank()) {
            var fields =
                    updates.isEmpty() && context.card() != null
                            ? context.card().specs().values().stream()
                                    .map(Negotiation.QuestionSpec::field)
                                    .toList()
                            : updates.keySet();
            for (String field : fields) if (!missing.contains(field)) missing.add(field);
        }
        var card =
                card(context, claim.fence().runId(), claim.fence().generation(), revision, missing);
        boolean pending = context.pendingRemaining() > 0;
        var prepared =
                new Negotiation.Prepared(
                        context.message().id(),
                        facts,
                        resolved,
                        false,
                        clarification,
                        pending ? null : card);
        return new StageOutput(
                clarification.isBlank() ? "需求已记录；建议只有明确采纳后才生效。" : clarification,
                json.encode(Map.of("negotiation", prepared)),
                pending
                        ? List.of(stage(input.inputId(), StageType.UNDERSTAND))
                        : missing.isEmpty()
                                ? List.of(stage(context.message().id(), StageType.RESEARCH))
                                : List.of(),
                pending || missing.isEmpty()
                        ? StageOutput.Outcome.CONTINUE
                        : StageOutput.Outcome.WAIT_USER,
                pending || missing.isEmpty() ? null : card.decision());
    }

    /**
     * @param context 来源上下文
     * @return 有限授权和普通采纳使用不同来源
     */
    public static ConstraintRevision.Source source(Negotiation.Context context) {
        return context.response() == null || !context.message().text().isBlank()
                ? ConstraintRevision.Source.USER
                : context.response().action().equals("AUTHORIZE")
                        ? ConstraintRevision.Source.AUTHORIZED_SYSTEM
                        : ConstraintRevision.Source.ACCEPTED_SUGGESTION;
    }

    /**
     * @param facts 已解释字段
     * @return 去重增量，冲突同名字段不能静默最后覆盖
     */
    public static Map<String, String> updates(List<Negotiation.Fact> facts) {
        var values = new LinkedHashMap<String, String>();
        var operations = new LinkedHashMap<String, RequirementFields.PreferenceOperation>();
        for (var fact : facts) {
            String old = values.putIfAbsent(fact.field(), fact.value());
            var previous = operations.putIfAbsent(fact.field(), fact.operation());
            if (old != null && (!old.equals(fact.value()) || previous != fact.operation()))
                throw new IllegalArgumentException("DUPLICATED_FIELD");
        }
        return values;
    }

    /**
     * @param facts 本次有原文依据的提取项
     * @return 明确替换的软偏好字段，不能影响硬限制
     */
    public static Set<String> replacements(List<Negotiation.Fact> facts) {
        return facts.stream()
                .filter(f -> f.operation() == RequirementFields.PreferenceOperation.REPLACE)
                .map(Negotiation.Fact::field)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * @param input 原回答
     * @return 可恢复且稳定的新修改身份
     */
    public static UUID outsideId(UUID input) {
        return UUID.nameUUIDFromBytes((input + ":outside").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param input 可靠输入
     * @param type 后继阶段
     * @return 应用控制的计划，不由模型指定执行权限
     */
    public static StagePlan stage(UUID input, StageType type) {
        return new StagePlan(
                UUID.randomUUID(),
                new ExecutionStep.Key(type, 0, "input_" + input + "_" + UUID.randomUUID()),
                null,
                "{\"inputId\":\"" + input + "\"}",
                List.of());
    }

    /**
     * @param inputId 可靠消息身份
     */
    public record Input(UUID inputId) {}

    /**
     * @param context 当前需求
     * @param run 当前运行
     * @param generation 当前代次
     * @param revision 新需求
     * @param missing 关键缺项
     * @return 未预先采纳的建议卡
     */
    private Negotiation.Card card(
            Negotiation.Context context,
            UUID run,
            long generation,
            long revision,
            List<String> missing) {
        var questions = new LinkedHashMap<UUID, DecisionRequest.Question>();
        var specs = new LinkedHashMap<UUID, Negotiation.QuestionSpec>();
        Set<UUID> rejected = store.rejected(context.scope());
        boolean capped =
                context.card() != null
                        && context.card().decision().revision() >= maximumClarifications;
        for (String field : missing) {
            UUID id =
                    UUID.nameUUIDFromBytes(
                            (context.scope().taskId() + ":" + field)
                                    .getBytes(StandardCharsets.UTF_8));
            var values = new LinkedHashMap<UUID, String>();
            var labels = new LinkedHashMap<UUID, String>();
            Map<String, String> choices =
                    capped
                            ? Map.of()
                            : field.equals("budgetRule")
                                    ? Map.of(
                                            "NONE",
                                            "不设置预算上限",
                                            "PER_GROUP",
                                            "按全组总额",
                                            "PER_PERSON",
                                            "按每人金额")
                                    : field.equals("fixedStatus")
                                            ? Map.of("NONE", "没有已订固定安排", "UNKNOWN", "尚未确定，保留待核实")
                                            : Map.of();
            for (var choice : choices.entrySet()) {
                UUID option =
                        UUID.nameUUIDFromBytes(
                                (id + ":" + choice.getKey()).getBytes(StandardCharsets.UTF_8));
                if (!rejected.contains(option)) {
                    values.put(option, choice.getKey());
                    labels.put(option, choice.getValue());
                }
            }
            UUID recommended =
                    field.equals("budgetRule")
                            ? values.entrySet().stream()
                                    .filter(e -> e.getValue().equals("PER_GROUP"))
                                    .map(Map.Entry::getKey)
                                    .findFirst()
                                    .orElse(null)
                            : null;
            questions.put(
                    id,
                    new DecisionRequest.Question(
                            id,
                            capped ? "待补充字段：" + field + "。已停止主动追问，可自由补充或提交新修改。" : prompt(field),
                            labels,
                            recommended));
            specs.put(
                    id,
                    new Negotiation.QuestionSpec(
                            field,
                            recommended == null ? "请按实际情况补充，可自由回答。" : "按全组金额便于核对整次旅行费用。",
                            "不设上限减少预算限制；设置金额后还需明确包含项目。",
                            values));
        }
        var old = context.card();
        UUID id = old == null ? UUID.randomUUID() : old.decision().id();
        long decisionRevision = old == null ? 1 : old.decision().revision() + 1;
        return new Negotiation.Card(
                new DecisionRequest(
                        id,
                        context.scope(),
                        run,
                        generation,
                        decisionRevision,
                        revision,
                        questions,
                        true,
                        questions.keySet(),
                        missing.isEmpty()
                                ? DecisionRequest.State.ANSWERED
                                : DecisionRequest.State.OPEN),
                specs,
                rejected);
    }

    /**
     * @param field 缺项
     * @return 具体问题，不靠人群标签推断
     */
    private String prompt(String field) {
        return switch (field) {
            case "cities" -> "计划去哪些城市？首版支持 1～3 个城市。";
            case "startDate" -> "旅行从哪一天开始？请提供明确日期。";
            case "endDate" -> "旅行在哪一天结束？首版支持 1～10 天。";
            case "travelers" -> "共有多少位同行者？";
            case "budgetRule" -> "预算按每人还是全组计算，或不设上限？";
            case "budgetCny" -> "预算金额是多少人民币？";
            case "budgetScope" -> "预算包含哪些费用？请明确住宿、城际及往返交通等项目。";
            case "fixedStatus" -> "是否有已订交通、酒店或预约等固定安排？";
            default -> "请澄清这项要求：" + field;
        };
    }
}
