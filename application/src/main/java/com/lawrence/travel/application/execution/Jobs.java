package com.lawrence.travel.application.execution;

import com.lawrence.travel.domain.execution.BackgroundJob;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 无SDK／SQL依赖的持久化作业及追踪传输契约。 */
public final class Jobs {
    /** 协议命名空间。 */
    private Jobs() { }
    /** @param userId 归属 @param chatId 聊天，无旅行时仍存在 */
    public record Scope(UUID userId, UUID chatId) { }
    /** @param scope 私有范围 @param id 稳定作业 @param token 租约围栏 @param version 资料／删除水位 */
    public record Fence(Scope scope, UUID id, long token, long version) { }
    /** @param scope 私有归属 @param operationId 输入级幂等预算 @param kind 类型 @param branch 分支键 @param payload 有界JSON／引用 @param dependency 可选依赖作业 @param legacyBudget 旧消息的保守预算来源，普通新输入为空 @param priorAttempts 旧输入已发生尝试，迁移不能清零 */
    public record Submit(Scope scope, UUID operationId, BackgroundJob.Kind kind, String branch, String payload, UUID dependency, UUID legacyBudget, int priorAttempts) {
        /** @param scope 归属 @param operationId 稳定输入 @param kind 类型 @param branch 分支 @param payload JSON @param dependency 依赖 @param legacyBudget 旧预算；新提交默认没有历史尝试 */
        public Submit(Scope scope, UUID operationId, BackgroundJob.Kind kind, String branch, String payload, UUID dependency, UUID legacyBudget) { this(scope,operationId,kind,branch,payload,dependency,legacyBudget,0); }
    }
    /** @param id 作业 @param operationId 输入身份 @param budgetId 计量身份 @param kind 类型 @param state 状态 @param token 当前围栏 @param version 资料版本 @param leaseUntil 可空租约 @param traceId 跨异步追踪 @param requestId 原入口关联 @param error 固定错误 */
    public record View(UUID id, UUID operationId, UUID budgetId, BackgroundJob.Kind kind, BackgroundJob.State state, long token,
                       long version, Instant leaseUntil, UUID traceId, UUID requestId, String error) { }
    /** @param fence 领取资格 @param job 已领取状态 @param payload 原始有界处理参数 */
    public record Claim(Fence fence, View job, String payload) { }
    /** @param state 完成／等待／暂停状态 @param artifact 有界JSON产物 @param next 可靠后继 @param reason 固定原因 */
    public record Output(BackgroundJob.State state, String artifact, List<Submit> next, String reason) {
        /** 防止调用方在提交期间改变后继列表。 */
        public Output { next = List.copyOf(next); }
    }
}
