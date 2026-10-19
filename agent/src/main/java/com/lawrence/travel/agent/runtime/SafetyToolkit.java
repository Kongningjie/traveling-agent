package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.safety.SafetyGate;
import io.agentscope.core.tool.Toolkit;
import java.util.concurrent.atomic.AtomicReference;

/** 一次调用的失败关闭工具集，SDK不能把安全异常转成工具文本后继续让模型改写绕过。 */
public class SafetyToolkit extends Toolkit {
    private final AtomicReference<SafetyGate.Failure> rejected=new AtomicReference<>();
    /** 无共享跨用户状态；每次运行实例独立。 */
    public SafetyToolkit() { super(); }
    /** @param failure 固定安全结果；首次拒绝保持到本次调用结束 */
    public void reject(SafetyGate.Failure failure) { rejected.compareAndSet(null,failure); }
    /** 所有后续模型发送和工具调用前复核，拒绝不得由模型自评清除。 */
    public void check() { if(rejected.get()!=null) throw rejected.get(); }
}
