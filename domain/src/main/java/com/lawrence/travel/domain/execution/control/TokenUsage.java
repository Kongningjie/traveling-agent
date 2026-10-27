package com.lawrence.travel.domain.execution.control;

/**
 * 供应商明确返回的实际用量；未返回时使用缺失状态，不能构造零值假装未知。
 * @param inputTokens 输入 Token，包含供应商统计的上下文
 * @param outputTokens 输出 Token
 */
public record TokenUsage(long inputTokens, long outputTokens) {
    /**
     * 拒绝负数或总和溢出的供应商计量。
     * @throws IllegalArgumentException 任一用量为负
     * @throws ArithmeticException 总 Token 数溢出 long
     */
    public TokenUsage {
        if (inputTokens < 0 || outputTokens < 0)
            throw new IllegalArgumentException("Negative token usage");
        Math.addExact(inputTokens, outputTokens);
    }
}
