package com.lawrence.travel.domain.itinerary;

import java.time.Instant;

/** 纯报告门槛；归属、来源运行、待解析输入和条件提交仍须在事务内检查。 */
public final class ReviewGate {
    /** 不持有数据库或可变模型上下文。 */
    public ReviewGate() {}

    /** 可接受、明确阻断和需要重新校验不能混为一个成功布尔值。 */
    public enum Result {
        /** 报告可接受。 */
        ELIGIBLE,
        /** 报告 FAIL／UNKNOWN。 */
        BLOCKED,
        /** 报告缺失／过期／依赖改变。 */
        REVALIDATE
    }

    /**
     * @param expected 当前精确绑定
     * @param validation 程序报告
     * @param review 独立报告
     * @param now DB 时间
     * @param dependenciesCurrent 依赖是否仍适用
     * @return 报告级结论
     */
    public Result evaluate(
            ItineraryReport.Binding expected,
            ItineraryReport validation,
            ItineraryReport review,
            Instant now,
            boolean dependenciesCurrent) {
        if (!dependenciesCurrent
                || validation == null
                || review == null
                || !expected.equals(validation.binding())
                || !expected.equals(review.binding())
                || validation.kind() != ItineraryReport.Kind.VALIDATION
                || review.kind() != ItineraryReport.Kind.REVIEW
                || !validation.freshAt(now)
                || !review.freshAt(now)) return Result.REVALIDATE;
        if (validation.producerStepId().equals(review.producerStepId())
                || validation.producerAttemptId().equals(review.producerAttemptId()))
            return Result.BLOCKED;
        return validation.conclusion() == ItineraryReport.Conclusion.PASS
                        && review.conclusion() == ItineraryReport.Conclusion.PASS
                ? Result.ELIGIBLE
                : Result.BLOCKED;
    }
}
