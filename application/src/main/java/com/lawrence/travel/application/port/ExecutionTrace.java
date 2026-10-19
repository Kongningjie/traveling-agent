package com.lawrence.travel.application.port;

import java.util.Map;
import java.util.UUID;

/** 执行事实轨迹；只接受固定分类和身份元数据，禁止存提示词、响应、推理或异常正文。 */
public interface ExecutionTrace {
    /** 结构化节点类别，不表示模型内部思维过程。 */
    enum Kind { /** 协调策略。 */ COORDINATE, /** 委派。 */ DELEGATE, /** 规划。 */ PLAN, /** 独立审核。 */ REVIEW,
        /** 上下文装载。 */ CONTEXT, /** 实际压缩。 */ COMPACT, /** 程序校验。 */ VALIDATE, /** 业务提交。 */ COMMIT, /** 用户等待。 */ WAIT, /** 资料检索。 */ RETRIEVAL, /** 外部证据工具。 */ TOOL }
    /** 固定结束原因；未知计量仍保存在原账本，不用成功状态覆盖。 */
    enum Failure { /** 取消。 */ CANCELLED, /** 准入拒绝。 */ REJECTED, /** 调用超时。 */ TIMEOUT, /** 执行失败。 */ FAILED, /** 用量未知。 */ UNKNOWN }
    /** @param budget 原预算身份 @param kind 固定节点 @param metadata 有界ID/版本元数据 @return 使用完关闭；不取得业务提交权限 */
    Span open(UUID budget, Kind kind, Map<String,String> metadata);
    /** 嵌套作用域须在创建线程内串行关闭，不支持跨线程共享；业务attempt独立保存，不能被子span替换。 */
    interface Span extends AutoCloseable {
        /** @param failure 固定分类，不接收异常内容 */
        void fail(Failure failure);
        /** 首次关闭保存结束状态，并恢复上层轨迹。 */
        @Override void close();
    }
    /** @return 仅供无持久化适配的旧测试/smoke，不冒充已记录轨迹 */
    static ExecutionTrace disabled() { return (budget,kind,metadata) -> new Span() {
        /** {@inheritDoc} 未装配时不生成虚假的问题记录。 */
        @Override public void fail(Failure failure) { }
        /** {@inheritDoc} 无资源。 */
        @Override public void close() { }
    }; }
}
