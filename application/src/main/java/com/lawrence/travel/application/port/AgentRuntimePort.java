package com.lawrence.travel.application.port;

/**
 * 定义模型调用、结果读取和取消接口。
 */
public interface AgentRuntimePort {
    /**
     * 启动独立上下文调用；运行预算必须已由用例建立。
     * @param runId 原运行标识
     * @param role 受控角色
     * @param prompt 本次文本输入，不包含凭证
     * @return 可取消句柄；产物不代表业务提交
     */
    Invocation start(java.util.UUID runId, String role, String prompt);

    /**
     * @param runId 原运行
     * @param role 固定模型角色
     * @param prompt 不可信数据上下文
     * @return 不暴露任何工具的结构化调用句柄；原预算和取消机制不变
     */
    Invocation startWithoutTools(java.util.UUID runId, String role, String prompt);

    /**
     * @param runId 原运行
     * @param role 固定角色
     * @param prompt 当前完整请求
     * @param basis 实际读取版本
     * @return 独立业务上下文；非生产替身默认沿用无工具调用
     */
    default Invocation startContextual(
            java.util.UUID runId,
            String role,
            String prompt,
            com.lawrence.travel.application.memory.AgentContext.Basis basis) {
        return startWithoutTools(runId, role, prompt);
    }

    /**
     * 按本次业务DTO约束模型返回；生产适配器将其转换为框架Schema。
     *
     * @param runId 原运行预算
     * @param role 配置角色，不能代替本次输出契约
     * @param prompt 当前请求
     * @param basis 权威版本
     * @param outputType 调用方随后解码的业务DTO，不允许由用户输入指定
     * @return 原预算下的可取消句柄；兼容替身默认沿用旧接口，不生成Schema
     */
    default Invocation startContextual(
            java.util.UUID runId,
            String role,
            String prompt,
            com.lawrence.travel.application.memory.AgentContext.Basis basis,
            Class<?> outputType) {
        return startContextual(runId, role, prompt, basis);
    }

    /** 隔离本次执行的结果及取消能力，不暴露 SDK 类型。 */
    interface Invocation extends AutoCloseable {
        /**
         * @return 异步产物，失败时携带固定错误分类
         */
        java.util.concurrent.CompletionStage<String> result();

        /** 请求取消实际连接和迟到结果；不代表供应商没有产生用量。 */
        @Override
        void close();
    }

    /**
     * 读取适配器声明的框架接线状态，不发起模型请求或业务状态提交。
     * @return 非空状态标识；待验证状态不能解释为真实模型可用
     */
    String integrationStatus();
}
