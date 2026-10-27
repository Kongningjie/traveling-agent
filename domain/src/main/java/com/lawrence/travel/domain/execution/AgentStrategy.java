package com.lawrence.travel.domain.execution;

import java.util.List;

/**
 * 协调层的有限策略；只有应用能把它转换成阶段，协议不允许递归委派或任意跳转。
 * @param schemaVersion 当前只支持1
 * @param goal 收集/复用/澄清
 * @param stop 汇合后的停止条件
 * @param branches 按拓扑顺序的查询索引
 * @param missingFields 需用户补充的字段名
 */
public record AgentStrategy(
        int schemaVersion,
        Goal goal,
        Stop stop,
        List<Branch> branches,
        List<String> missingFields) {
    /** 有限协调目标，不包含接受行程或修改权限。 */
    public enum Goal {
        /** 调查。 */
        RESEARCH,
        /** 复用有效资料。 */
        REUSE,
        /** 等待用户澄清。 */
        CLARIFY
    }

    /** 收敛规则；补查至多一次，不能由模型扩大修订预算。 */
    public enum Stop {
        /** 进入规划。 */
        PLAN,
        /** 汇合后最多补查一轮。 */
        REFINE_ONCE,
        /** 询问用户。 */
        WAIT_USER
    }

    /**
     * @param queryIndex 所委派的查询下标
     * @param dependsOn 仅引用更早分支
     * @param allowedTools 请求的工具名，应用与查询再次求交集
     */
    public record Branch(int queryIndex, List<Integer> dependsOn, List<String> allowedTools) {
        /** 结构有界且不可变，不能引用自己或后继形成循环。 */
        public Branch {
            if (queryIndex < 0
                    || queryIndex >= 12
                    || dependsOn == null
                    || allowedTools == null
                    || dependsOn.size() > 12
                    || dependsOn.stream().anyMatch(i -> i == null || i < 0 || i >= queryIndex)
                    || dependsOn.stream().distinct().count() != dependsOn.size()
                    || allowedTools.size() != 1) throw invalid();
            dependsOn = List.copyOf(dependsOn);
            allowedTools = List.copyOf(allowedTools);
        }
    }

    /** 拒绝无界、循环和不一致的目标；缺项名称由应用白名单进一步核对。 */
    public AgentStrategy {
        if (schemaVersion != 1
                || goal == null
                || stop == null
                || branches == null
                || missingFields == null
                || branches.size() > 12
                || missingFields.size() > 8) throw invalid();
        branches = List.copyOf(branches);
        missingFields = List.copyOf(missingFields);
        for (int i = 0; i < branches.size(); i++)
            if (branches.get(i).queryIndex() != i) throw invalid();
        if ((goal == Goal.CLARIFY) != (stop == Stop.WAIT_USER)
                || (goal == Goal.CLARIFY) != !missingFields.isEmpty()
                || goal != Goal.RESEARCH && !branches.isEmpty()
                || goal == Goal.RESEARCH && branches.isEmpty()
                || goal == Goal.REUSE && stop != Stop.PLAN) throw invalid();
    }

    /**
     * @return 固定协议错误，无模型原文
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("AGENT_STRATEGY_INVALID");
    }
}
