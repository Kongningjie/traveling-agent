package com.lawrence.travel.agent.runtime;

import java.util.ArrayList;
import java.util.List;

/** 保留框架异常类型及类/方法定位点；不持有原异常、异常消息、模型正文或供应商响应。 */
public final class SanitizedAgentFailure extends IllegalStateException {
    private final List<String> exceptionTypes;
    private final List<String> exceptionSites;

    /**
     * 提取类型和首个类/方法，不保留原异常引用。
     *
     * @param code 由适配器白名单确定的固定错误码
     * @param failure 仅提取最多八层Java类型及首个类/方法，不作为cause或suppressed保存
     */
    public SanitizedAgentFailure(String code, Throwable failure) {
        super(code);
        var types = new ArrayList<String>();
        var sites = new ArrayList<String>();
        for (Throwable current = failure;
                current != null && types.size() < 8;
                current = current.getCause()) {
            types.add(current.getClass().getName());
            var frames = current.getStackTrace();
            sites.add(
                    frames.length == 0
                            ? "UNAVAILABLE"
                            : frames[0].getClassName() + "#" + frames[0].getMethodName());
        }
        exceptionTypes = List.copyOf(types);
        exceptionSites = List.copyOf(sites);
    }

    /**
     * @return 不含原始异常文本的有界只读类型链，不代表用户可重试
     */
    public List<String> exceptionTypes() {
        return exceptionTypes;
    }

    /**
     * 返回各层异常的首个类名和方法名，不包含路径、行号、消息或模型内容。
     *
     * @return 最多八条只读定位点，仅用于脱敏诊断
     */
    public List<String> exceptionSites() {
        return exceptionSites;
    }
}
