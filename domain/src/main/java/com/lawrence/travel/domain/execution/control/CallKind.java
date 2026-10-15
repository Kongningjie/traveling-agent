package com.lawrence.travel.domain.execution.control;

/** 原运行共享的计数类别，重试、压缩和子 Agent 不另建预算。 */
public enum CallKind {
    /** 一次真实模型请求，包括 SDK 重试和压缩请求。 */
    MODEL,
    /** 一次外部工具请求；缓存命中及纯本地计算不计入。 */
    TOOL,
    /** 一轮行程修订，校验和审核共享此计数。 */
    REPAIR
}
