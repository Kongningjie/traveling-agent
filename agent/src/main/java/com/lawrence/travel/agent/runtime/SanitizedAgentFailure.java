package com.lawrence.travel.agent.runtime;

import java.util.ArrayList;
import java.util.List;

/** 保留框架异常类型以定位问题；不持有原异常、异常消息、模型正文或供应商响应。 */
public final class SanitizedAgentFailure extends IllegalStateException {
    private final List<String> exceptionTypes;

    /**
     * @param code 由适配器白名单确定的固定错误码
     * @param failure 仅在构造期间提取最多八层Java类型名，不作为cause或suppressed保存
     */
    public SanitizedAgentFailure(String code, Throwable failure) {
        super(code);
        var types = new ArrayList<String>();
        for (Throwable current = failure;
                current != null && types.size() < 8;
                current = current.getCause()) {
            types.add(current.getClass().getName());
        }
        exceptionTypes = List.copyOf(types);
    }

    /**
     * @return 不含原始异常文本的有界只读类型链，不代表用户可重试
     */
    public List<String> exceptionTypes() {
        return exceptionTypes;
    }
}
