package com.lawrence.travel.domain.execution;

import com.lawrence.travel.domain.rule.DomainViolation;
import com.lawrence.travel.domain.travel.TaskScope;
import java.time.Instant;
import java.util.UUID;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.INVALID_INPUT;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.STALE_LEASE;

/**
 * 从权威 Outbox 行读取的租约值；时间必须由数据库事务提供，不能由客户端决定。
 * @param eventId 事件 ID
 * @param scope 事件所属用户及任务
 * @param runId 事件所属运行
 * @param stepId 事件目标逻辑阶段
 * @param owner 执行实例身份，非用户登录会话
 * @param fencingToken 正数代际令牌
 * @param until 租约排他截止时刻
 * @param state 当前事件状态
 */
public record ExecutionLease(
        UUID eventId,
        TaskScope scope,
        UUID runId,
        UUID stepId,
        String owner,
        long fencingToken,
        Instant until,
        OutboxState state) {
    /** 保留失效事件的审计租约字段，但非 LEASED 状态不能提交。 */
    public ExecutionLease {
        DomainViolation.require(
                eventId != null
                        && scope != null
                        && runId != null
                        && stepId != null
                        && owner != null
                        && !owner.isBlank()
                        && fencingToken > 0
                        && until != null
                        && state != null,
                INVALID_INPUT);
    }

    /**
     * 以本对象代表的当前权威行校验旧执行者持有的领取副本；续租可改变截止时间。
     * @param claim 领取时副本
     * @param databaseNow 同事务数据库时间
     */
    public void requireCurrent(ExecutionLease claim, Instant databaseNow) {
        DomainViolation.require(
                claim != null
                        && databaseNow != null
                        && state == OutboxState.LEASED
                        && eventId.equals(claim.eventId)
                        && scope.equals(claim.scope)
                        && runId.equals(claim.runId)
                        && stepId.equals(claim.stepId)
                        && owner.equals(claim.owner)
                        && fencingToken == claim.fencingToken
                        && databaseNow.isBefore(until),
                STALE_LEASE);
    }
}
