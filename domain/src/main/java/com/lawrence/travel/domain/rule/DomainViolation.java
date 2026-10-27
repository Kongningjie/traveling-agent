package com.lawrence.travel.domain.rule;

/** 可确定处理的领域拒绝，仅携带固定分类，不包含用户输入或外部异常链。 */
public final class DomainViolation extends RuntimeException {
    /** 领域失败分类；HTTP 映射与数据库竞争处理由外层实现。 */
    public enum Code {
        /** 输入结构或范围不合法。 */
        INVALID_INPUT,
        /** 状态不允许该操作。 */
        ILLEGAL_TRANSITION,
        /** 关联对象不属于同一用户或任务。 */
        SCOPE_MISMATCH,
        /** 需求或偏好版本已过时。 */
        STALE_REVISION,
        /** 执行代次已被新操作取代。 */
        STALE_GENERATION,
        /** 仍有待解析输入，不能接受旧候选。 */
        PENDING_INPUT,
        /** 当前尝试标识不匹配。 */
        ATTEMPT_MISMATCH,
        /** 权威租约已过期或执行权不匹配。 */
        STALE_LEASE,
        /** 相同因果键承载不同内容。 */
        IDEMPOTENCY_CONFLICT,
        /** 决策版本或关联需求已失效。 */
        STALE_DECISION
    }

    /**
     * 创建固定分类异常。
     * @param code 非空失败分类
     */
    public DomainViolation(Code code) {
        super(java.util.Objects.requireNonNull(code).name());
    }

    /**
     * 断言领域前置条件，不附带用户数据。
     * @param condition 已计算条件
     * @param code 失败分类
     * @throws DomainViolation 条件不满足
     */
    public static void require(boolean condition, Code code) {
        if (!condition) throw new DomainViolation(code);
    }
}
