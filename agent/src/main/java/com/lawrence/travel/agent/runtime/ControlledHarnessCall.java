package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.safety.SafetyGate;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 执行Harness并读取最终产物；明确零用量的上下文超限允许恢复一次。
 */
public final class ControlledHarnessCall {
    /** 命名空间。 */
    private ControlledHarnessCall() {}

    /**
     * 执行正常调用，必要时仅对明确安全的上下文超限执行一次恢复。
     *
     * @param factory 每次新建隔离实例
     * @param normal 正常完整消息
     * @param recovery 同预算紧急压缩
     * @param guard 返回前版本检查
     * @param safety 完整输出检查
     * @param cancelled 取消标志
     * @param active 实际框架句柄
     * @return 未流式泄露且检查通过的最终正文
     */
    public static String execute(
            Supplier<HarnessAgent> factory,
            Supplier<List<Msg>> normal,
            Supplier<List<Msg>> recovery,
            Runnable guard,
            SafetyGate safety,
            BooleanSupplier cancelled,
            Consumer<HarnessAgent> active) {
        return execute(factory, normal, recovery, guard, safety, cancelled, active, false);
    }

    /**
     * 执行调用；业务JSON模式兼容直接返回的业务对象，后续仍由调用方严格解码校验。
     *
     * @param factory 每轮隔离实例
     * @param normal 完整输入
     * @param recovery 同预算上下文恢复
     * @param guard 返回前版本检查
     * @param safety 输出安全检查
     * @param cancelled 取消标志
     * @param active 实际框架句柄
     * @param businessJson 应用声明结果为JSON，不能从用户提示词中推断此权限
     * @return 原answer字符串或完整业务对象的JSON，不补字段、不修正事实
     */
    public static String execute(
            Supplier<HarnessAgent> factory,
            Supplier<List<Msg>> normal,
            Supplier<List<Msg>> recovery,
            Runnable guard,
            SafetyGate safety,
            BooleanSupplier cancelled,
            Consumer<HarnessAgent> active,
            boolean businessJson) {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (cancelled.getAsBoolean()) throw new IllegalStateException("MODEL_CANCELLED");
            try (var harness = factory.get()) {
                active.accept(harness);
                if (cancelled.getAsBoolean()) throw new IllegalStateException("MODEL_CANCELLED");
                var messages = (attempt == 0 ? normal : recovery).get();
                if (cancelled.getAsBoolean()) throw new IllegalStateException("MODEL_CANCELLED");
                var result =
                        harness.getDelegate()
                                .call(
                                        messages,
                                        businessJson
                                                ? java.util.Map.class
                                                : RestrictedHarnessFactory.StructuredReply.class)
                                .block();
                if (result == null) throw new IllegalStateException("MODEL_OUTPUT_INVALID");
                String text = readResult(result, businessJson);
                safety.require("OUTPUT", text);
                guard.run();
                return text;
            } catch (RuntimeException failure) {
                if (attempt != 0 || !recoverable(failure)) throw failure;
            }
        }
        throw new IllegalStateException("CONTEXT_RECOVERY_EXHAUSTED");
    }

    /**
     * 读取框架结构化产物。JSON业务调用可直接返回对象，不再强制多包一层answer字符串。
     *
     * @param result 框架最终消息
     * @param businessJson 应用指定的业务JSON模式
     * @return 保留全部字段的产物；空答案及非法封装继续拒绝
     */
    private static String readResult(Msg result, boolean businessJson) {
        if (!result.hasStructuredData()) throw new IllegalStateException("MODEL_OUTPUT_INVALID");
        if (!businessJson)
            return result.getStructuredData(RestrictedHarnessFactory.StructuredReply.class)
                    .answer();
        java.util.Map<?, ?> data = result.getStructuredData(java.util.Map.class);
        // generate_response的单层response封装不能混入业务字段后再被静默丢弃。
        if (data.size() == 1 && data.get("response") instanceof java.util.Map<?, ?> nested)
            data = nested;
        if (data.containsKey("answer")) {
            if (data.size() != 1 || !(data.get("answer") instanceof String answer))
                throw new IllegalStateException("MODEL_OUTPUT_INVALID");
            return new RestrictedHarnessFactory.StructuredReply(answer).answer();
        }
        if (data.isEmpty()) throw new IllegalStateException("MODEL_OUTPUT_INVALID");
        return new RestrictedHarnessFactory.StructuredReply(
                        io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(data))
                .answer();
    }

    /**
     * @param failure 内部异常链
     * @return 只有传输明确零用量的固定分类可恢复，不依赖任意异常字符串
     */
    private static boolean recoverable(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause())
            if (current instanceof HttpTransportException
                    && "MODEL_CONTEXT_LIMIT_SAFE".equals(current.getMessage())) return true;
        return false;
    }
}
