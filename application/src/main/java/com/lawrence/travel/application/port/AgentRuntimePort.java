package com.lawrence.travel.application.port;

/** 框架接线状态；真实阶段执行契约在 M0 验证后完善。 */
public interface AgentRuntimePort {
    /**
     * 启动独立上下文调用；运行预算必须已由用例建立。
     * @param runId 原运行标识
     * @param role 受控角色
     * @param prompt 本次文本输入，不包含凭证
     * @return 可取消句柄；产物不代表业务提交
     */
    Invocation start(java.util.UUID runId, String role, String prompt);

    /** 隔离本次执行的结果及取消能力，不暴露 SDK 类型。 */
    interface Invocation extends AutoCloseable {
        /** @return 异步产物，失败时携带固定错误分类 */
        java.util.concurrent.CompletionStage<String> result();

        /** 请求取消实际连接和迟到结果；不代表供应商没有产生用量。 */
        @Override
        void close();
    }

    /**
     * 读取适配器声明的框架接线状态，不发起模型请求或业务状态提交。
     *
     * @return 非空状态标识；待验证状态不能解释为真实模型可用
     */
    String integrationStatus();
}
