package com.lawrence.travel.domain.execution;

/** PLAN 阶段的规划意图，不表示一次可领取的阶段实例或重试尝试。 */
public enum PlanningMode {
    /** 根据已明确的需求生成首个完整候选。 */
    INITIAL,
    /** 依据需求变化及允许修改范围，对已有安排生成修订候选。 */
    REPLAN
}
