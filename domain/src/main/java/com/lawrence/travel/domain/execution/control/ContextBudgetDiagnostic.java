package com.lawrence.travel.domain.execution.control;

/** 上下文拒绝的安全量测：仅保存应用边界、估算数和上限，不携带任何输入正文。 */
public final class ContextBudgetDiagnostic extends IllegalStateException {
    private final String boundary;
    private final long estimated;
    private final long limit;
    /** @param boundary PREPARED_MESSAGES或TRANSPORT_BODY @param estimated 保守估算 @param limit 原输入上限 */
    public ContextBudgetDiagnostic(String boundary, long estimated, long limit) {
        super("CONTEXT_BUDGET_EXHAUSTED");
        if (!java.util.Set.of("PREPARED_MESSAGES", "TRANSPORT_BODY").contains(boundary) || estimated < 0 || limit <= 0) throw new IllegalArgumentException("CONTEXT_DIAGNOSTIC_INVALID");
        this.boundary = boundary; this.estimated = estimated; this.limit = limit;
    }
    /** @return 固定拒绝边界 */
    public String boundary() { return boundary; }
    /** @return 本边界保守输入估算，不是供应商已计费Token */
    public long estimated() { return estimated; }
    /** @return 本边界原上限 */
    public long limit() { return limit; }
}
