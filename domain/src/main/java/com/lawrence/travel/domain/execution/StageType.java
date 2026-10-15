package com.lawrence.travel.domain.execution;

/** 阶段类型不等同于阶段实例；补查与修订需创建新的逻辑实例。 */
public enum StageType {
    /** 解析本次请求，汇总需求及需要用户澄清的问题。 */
    UNDERSTAND,
    /** 调查候选地点、交通和天气等资料，形成可追溯证据。 */
    RESEARCH,
    /** 按 INITIAL 或 REPLAN 意图产生结构化行程草案。 */
    PLAN,
    /** 由 Java 对费用、时间及其他确定性约束执行校验。 */
    VALIDATE,
    /** 使用独立审核上下文检查待接受候选，不复用规划 Agent 自评。 */
    REVIEW,
    /** 呈现合格候选和变化说明，等待用户决定是否接受。 */
    PRESENT
}
