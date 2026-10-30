package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.execution.AgentCallFailure;
import java.util.ArrayList;
import java.util.List;

/** 保留框架异常类型及类/方法定位点；不持有原异常、异常消息、模型正文或供应商响应。 */
public final class SanitizedAgentFailure extends AgentCallFailure {
    private final List<String> exceptionTypes;
    private final List<String> exceptionSites;

    /**
     * 提取类型和首个类/方法，不保留原异常引用。
     *
     * @param code 由适配器白名单确定的固定错误码
     * @param failure 仅提取最多八层Java类型及首个类/方法，不作为cause或suppressed保存
     */
    public SanitizedAgentFailure(String code, Throwable failure) {
        super(code, knownZeroUsage(failure));
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

    /**
     * 仅从自有传输异常或已归一化异常读取当前请求的零用量证据。
     *
     * @param failure 内部失败链
     * @return 不读取错误正文的重试证据
     */
    private static boolean knownZeroUsage(Throwable failure) {
        int depth = 0;
        for (var current = failure; current != null && depth++ < 8; current = current.getCause()) {
            if (current instanceof ProviderCallFailure provider) return provider.knownZeroUsage();
            if (current instanceof AgentCallFailure known) return known.knownZeroUsage();
        }
        return false;
    }
}
