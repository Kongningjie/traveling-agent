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
 * Harness单次执行的受控边界：先准备完整消息，再调用delegate并提取结构化answer。
 *
 * <p>由ActiveInvocation调用；不使用框架默认紧急Flush自动追加请求。只在传输已明确报告
 * 零用量的上下文超限时允许一次同预算紧急压缩恢复，其他失败向上交给应用暂停。
 * 结果在安全检查和版本守卫通过后才交还，原始供应商Token流不直接发送给用户。
 */
public final class ControlledHarnessCall {
    /** 命名空间。 */
    private ControlledHarnessCall() {}

    /**
     * 执行正常调用，必要时仅对明确安全的上下文超限执行一次恢复；每轮使用独立Harness实例。
     *
     * <p>准备前后检查取消，框架调用可继续白名单工具循环；拿到最终结构化answer后，
     * 先做完整输出安全检查，再运行guard复查业务快照。返回文本仍需上游解释器解码并由应用提交。
     * 任意超时、网络失败或未知用量都不能因本循环存在而自动重放。
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
        for (int attempt = 0; attempt < 2; attempt++) {
            if (cancelled.getAsBoolean()) throw new IllegalStateException("MODEL_CANCELLED");
            try (var harness = factory.get()) {
                active.accept(harness);
                if (cancelled.getAsBoolean()) throw new IllegalStateException("MODEL_CANCELLED");
                var messages = (attempt == 0 ? normal : recovery).get();
                if (cancelled.getAsBoolean()) throw new IllegalStateException("MODEL_CANCELLED");
                var result =
                        harness.getDelegate()
                                .call(messages, RestrictedHarnessFactory.StructuredReply.class)
                                .block();
                if (result == null) throw new IllegalStateException("MODEL_OUTPUT_INVALID");
                String text =
                        result.getStructuredData(RestrictedHarnessFactory.StructuredReply.class)
                                .answer();
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
