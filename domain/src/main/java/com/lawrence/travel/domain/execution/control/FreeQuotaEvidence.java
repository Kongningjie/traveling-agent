package com.lawrence.travel.domain.execution.control;

import java.time.Instant;
import java.util.Objects;

/**
 * 操作者核实的额度快照，不是实时余额；供应商用完即停是阻止转付费的必要保护。
 * @param modelId 精确型号，快照版本不能混用
 * @param region 接入地域
 * @param api 接口类型，用于防止实时额度误用于其他产品
 * @param remainingTokens 记录时输入和输出共用的免费剩余 Token
 * @param verifiedAt 核实时间
 * @param validUntil 本地快照失效时间
 * @param expiresAt 额度到期保守截止时间
 * @param stopProtectionConfirmed 用完即停已核实生效
 */
public record FreeQuotaEvidence(String modelId, String region, String api, long remainingTokens,
                                Instant verifiedAt, Instant validUntil, Instant expiresAt,
                                boolean stopProtectionConfirmed) {
    /**
     * 校验快照结构，不把空白或负数值解释为已核实。
     * @throws IllegalArgumentException 字段缺失、额度为负或有效期顺序不合理
     */
    public FreeQuotaEvidence {
        if (Objects.requireNonNull(modelId).isBlank() || Objects.requireNonNull(region).isBlank()
                || Objects.requireNonNull(api).isBlank() || remainingTokens < 0
                || !Objects.requireNonNull(validUntil).isAfter(Objects.requireNonNull(verifiedAt))) {
            throw new IllegalArgumentException("Invalid quota evidence");
        }
        Objects.requireNonNull(expiresAt);
    }

    /**
     * 对精确接入范围和 Token 上界执行保守准入判断。
     * @param model 目标精确型号
     * @param targetRegion 实际请求地域
     * @param targetApi 实际请求接口类型
     * @param tokenUpperBound 本请求输入和最大输出的保守总 Token 上界，必须为正
     * @param now 当前时间
     * @throws CallRejectedException 保护未确认、范围不匹配、过期或余额不足
     */
    public void requireUsable(String model, String targetRegion, String targetApi,
                              long tokenUpperBound, Instant now) {
        if (tokenUpperBound <= 0) throw new IllegalArgumentException("Token bound must be positive");
        if (!stopProtectionConfirmed || !modelId.equals(model) || !region.equals(targetRegion)
                || !api.equals(targetApi)) {
            throw new CallRejectedException(CallRejectedException.Reason.QUOTA_UNVERIFIED);
        }
        if (now.isBefore(verifiedAt) || !now.isBefore(validUntil) || !now.isBefore(expiresAt)) {
            throw new CallRejectedException(CallRejectedException.Reason.QUOTA_EXPIRED);
        }
        if (remainingTokens < tokenUpperBound) {
            throw new CallRejectedException(CallRejectedException.Reason.QUOTA_INSUFFICIENT);
        }
    }
}
