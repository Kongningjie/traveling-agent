package com.lawrence.travel.application.interaction;

import java.util.LinkedHashMap;
import java.util.Map;

/** 对话专用问题投影：合并展示选项与规范值，避免整张卡的重复UUID/索引挤占模型上下文。 */
public final class DialogueQuestionContext {
    /** 无状态投影工具，不允许实例化。 */
    private DialogueQuestionContext() { }

    /**
     * 完整保留版本、授权范围、拒绝项和每个剩余问题的含义，仅消除重复索引表示。
     * @param card 当前已鉴权的私有问题卡
     * @return 只用于模型理解的数据，不赋予执行权限；允许推荐项为空
     * @throws IllegalArgumentException 问题缺少规范字段或选项映射，不能静默丢失含义
     */
    public static Map<String, Object> from(Negotiation.Card card) {
        var decision = card.decision(); var result = new LinkedHashMap<String, Object>();
        result.put("id", decision.id()); result.put("scope", decision.scope()); result.put("runId", decision.runId());
        result.put("generation", decision.generation()); result.put("revision", decision.revision());
        result.put("constraintRevision", decision.constraintRevision()); result.put("state", decision.state());
        result.put("freeTextAllowed", decision.freeTextAllowed()); result.put("allowedAuthorizationScope", decision.allowedAuthorizationScope());
        result.put("rejected", card.rejected());
        result.put("questions", decision.remaining().values().stream().map(question -> {
            var spec = card.specs().get(question.id());
            if (spec == null || !spec.options().keySet().equals(question.options().keySet())) throw new IllegalArgumentException("QUESTION_CONTEXT_INVALID");
            var item = new LinkedHashMap<String, Object>(); item.put("id", question.id()); item.put("field", spec.field());
            item.put("prompt", question.prompt()); item.put("reason", spec.reason()); item.put("tradeoff", spec.tradeoff());
            item.put("recommendedOption", question.recommendedOption());
            item.put("options", question.options().entrySet().stream().map(option -> Map.of("id", option.getKey(),
                    "label", option.getValue(), "value", spec.options().get(option.getKey()))).toList());
            return java.util.Collections.unmodifiableMap(item);
        }).toList());
        return java.util.Collections.unmodifiableMap(result);
    }
}
