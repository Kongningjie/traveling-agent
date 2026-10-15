package com.lawrence.travel.application.port;

/** 框架接线状态；真实阶段执行契约在 M0 验证后完善。 */
public interface AgentRuntimePort {
    /**
     * 读取适配器声明的框架接线状态，不发起模型请求或业务状态提交。
     *
     * @return 非空状态标识；待验证状态不能解释为真实模型可用
     */
    String integrationStatus();
}
