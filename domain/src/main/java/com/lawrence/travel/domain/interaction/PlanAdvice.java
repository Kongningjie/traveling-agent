package com.lawrence.travel.domain.interaction;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 用户材料的分析建议协议；只有明确采纳后才可成为业务变更，不具有事实认证或执行权限。 */
public final class PlanAdvice {
    /** 不实例化协议命名空间。 */
    private PlanAdvice() {}

    /** 文件中的安排性质，不等于系统已确认的固定安排。 */
    public enum Statement {
        /** 文件说已订。 */
        BOOKED,
        /** 打算安排。 */
        INTENDED,
        /** 可选安排。 */
        OPTIONAL,
        /** 无法确认。 */
        UNKNOWN
    }

    /** 用户文件不能被模型升级为官方核实。 */
    public enum Evidence {
        /** 仅用户材料。 */
        USER_MATERIAL,
        /** 尚无充分依据。 */
        UNKNOWN
    }

    /** 可逐项确认的字段边界；不允许通过建议解除固定安排或锁定。 */
    public static final Set<String> FIELDS =
            Set.of(
                    "cities",
                    "startDate",
                    "endDate",
                    "travelers",
                    "budgetRule",
                    "budgetCny",
                    "budgetScope",
                    "pace",
                    "diet",
                    "interests",
                    "hardRequirements",
                    "changeTargets");

    /**
     * @param reference 实际读取引用
     * @param quote 连续原文
     * @param statement 材料性质
     * @param problem 问题类型
     * @param reason 原因
     * @param proposal 建议内容
     * @param impact 影响范围
     * @param evidence 证据状态
     * @param pending 待确认项
     * @param changes 可选的规范字段增量；空表示仅讨论
     */
    public record Draft(
            UUID reference,
            String quote,
            Statement statement,
            String problem,
            String reason,
            String proposal,
            String impact,
            Evidence evidence,
            String pending,
            Map<String, String> changes) {
        /** 结构有界且不可变；原文定位由应用核对，字段合并由原业务用例验证。 */
        public Draft {
            if (reference == null
                    || statement == null
                    || evidence == null
                    || !text(quote, 500)
                    || !text(problem, 60)
                    || !text(reason, 1000)
                    || !text(proposal, 1000)
                    || !text(impact, 500)
                    || pending == null
                    || pending.length() > 500
                    || changes == null
                    || changes.size() > 12
                    || !FIELDS.containsAll(changes.keySet())
                    || changes.values().stream().anyMatch(v -> !text(v, 500)))
                throw new IllegalArgumentException("ADVICE_INVALID");
            changes = Map.copyOf(changes);
        }
    }

    /**
     * @param value 文本
     * @param max 字符上限
     * @return 有界非空文本，不解析其中指令
     */
    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }
}
