package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.domain.execution.control.TokenUsage;
import io.agentscope.core.model.transport.HttpTransportException;

/** 固定供应商错误及本次明确零用量标记，不保存响应正文。 */
final class ProviderCallFailure extends HttpTransportException {
    private final boolean knownZeroUsage;

    /**
     * 将已解析的当前请求计量收敛为重试证据。
     *
     * @param code 传输层已经归一化的固定码
     * @param usage 供应商明确用量；null不视为零
     */
    ProviderCallFailure(String code, TokenUsage usage) {
        super(code);
        knownZeroUsage = usage != null && usage.inputTokens() == 0 && usage.outputTokens() == 0;
    }

    /**
     * 读取当前请求是否明确未消耗Token，不推断历史或供应商余额。
     *
     * @return 明确零用量标记
     */
    boolean knownZeroUsage() {
        return knownZeroUsage;
    }
}
