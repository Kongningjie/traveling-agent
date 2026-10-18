package com.lawrence.travel.domain.execution.control;

/** 调用被规则拒绝；异常仅包含固定原因码，不能携带密钥、提示词或供应商原始响应。 */
public final class CallRejectedException extends RuntimeException {
    /** 可映射到暂停或排队的原因；实际运行状态转换由后续执行用例处理。 */
    public enum Reason {
        /** 全局真实外部调用开关未开启。 */
        EXTERNAL_DISABLED,
        /** 角色或型号未获明确批准，不允许自动替换。 */
        MODEL_NOT_APPROVED,
        /** 额度、地域、接口或停用保护尚未核实。 */
        QUOTA_UNVERIFIED,
        /** 额度快照过期、尚未生效或免费额度已到期。 */
        QUOTA_EXPIRED,
        /** 记录的剩余额度不足以覆盖本次保守 Token 上界。 */
        QUOTA_INSUFFICIENT,
        /** 本运行调用或修订次数已达上限。 */
        CALL_BUDGET_EXHAUSTED,
        /** 用户当日预占与已消费合计达到上限；不能通过新聊天绕过。 */
        DAILY_QUOTA_EXHAUSTED,
        /** 本运行主动执行时间已达上限。 */
        ACTIVE_TIME_EXHAUSTED,
        /** 关键上下文无法在输入上限内完整容纳，必须保留产物并暂停。 */
        CONTEXT_BUDGET_EXHAUSTED,
        /** 当前实际模型请求槽已占满，应排队而非忽略限制。 */
        MODEL_CONCURRENCY_FULL,
        /** 当前模式没有持久化计量仓储，不能放行真实调用。 */
        LEDGER_UNAVAILABLE
    }

    private final Reason reason;

    /**
     * 创建不含外部输入的拒绝结果。
     * @param reason 固定原因，不可为 null
     */
    public CallRejectedException(Reason reason) {
        super(java.util.Objects.requireNonNull(reason).name());
        this.reason = reason;
    }

    /**
     * 获取调用方可用于暂停、排队或展示的安全原因码。
     * @return 固定拒绝原因
     */
    public Reason reason() {
        return reason;
    }
}
