package com.lawrence.travel.domain.travel;

import java.util.UUID;
import com.lawrence.travel.domain.rule.DomainViolation;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.INVALID_INPUT;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.SCOPE_MISMATCH;

/**
 * 由受信身份及任务记录构建的归属，不接受模型或浏览器自报 userId 作为认证依据。
 * @param userId 稳定账号 ID
 * @param taskId 稳定旅行任务 ID
 */
public record TaskScope(UUID userId, UUID taskId) {
    /** 归属字段不可缺失。 */
    public TaskScope { DomainViolation.require(userId != null && taskId != null, INVALID_INPUT); }

    /**
     * 比较关联归属；最终持久化路径仍须带归属条件。
     * @param other 关联对象归属
     */
    public void requireSame(TaskScope other) { DomainViolation.require(equals(other), SCOPE_MISMATCH); }
}
