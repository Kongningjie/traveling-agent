package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.safety.SafetyGate;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 执行Harness并读取最终产物；明确零用量的上下文超限允许恢复一次。
 */
public final class ControlledHarnessCall {
    /** 结构化产物适配失败位置，不携带模型内容。 */
    public enum OutputProblem {
        /** 框架未返回消息。 */
        MISSING,
        /** 最终消息没有结构化元数据。 */
        UNSTRUCTURED,
        /** answer封装混入其他字段或类型错误。 */
        ENVELOPE,
        /** 返回了空对象。 */
        EMPTY
    }

    /** 用固定原因标记返回结构问题，保持既有异常消息和拒绝行为。 */
    public static final class OutputFailure extends IllegalStateException {
        private final OutputProblem problem;

        /**
         * 标记适配失败的位置。
         *
         * @param problem 编译期固定原因
         */
        public OutputFailure(OutputProblem problem) {
            super("MODEL_OUTPUT_INVALID");
            this.problem = java.util.Objects.requireNonNull(problem);
        }

        /**
         * 返回脱敏错误码，不读取模型内容。
         *
         * @return 仅由固定枚举组成的错误码
         */
        public String diagnosticCode() {
            return "MODEL_OUTPUT_" + problem.name();
        }
    }

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
        return execute(
                factory, normal, recovery, guard, safety, cancelled, active, businessJson, null);
    }

    /**
     * 执行业务调用；显式提供严格编解码器时，完整JSON正文也可进入相同业务校验链。
     *
     * @param factory 每轮隔离实例
     * @param normal 完整输入
     * @param recovery 同预算上下文恢复
     * @param guard 返回前版本检查
     * @param safety 输出安全检查
     * @param cancelled 取消标志
     * @param active 实际框架句柄
     * @param businessJson 应用声明的JSON业务模式
     * @param json 严格对象解码器；为空时不接受无结构化元数据的消息
     * @return 保留全部字段的业务JSON，后续仍须业务DTO校验
     */
    public static String execute(
            Supplier<HarnessAgent> factory,
            Supplier<List<Msg>> normal,
            Supplier<List<Msg>> recovery,
            Runnable guard,
            SafetyGate safety,
            BooleanSupplier cancelled,
            Consumer<HarnessAgent> active,
            boolean businessJson,
            InteractionCodec json) {
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
                                                ? Map.class
                                                : RestrictedHarnessFactory.StructuredReply.class)
                                .block();
                if (result == null) throw new OutputFailure(OutputProblem.MISSING);
                String text = readResult(result, businessJson, json, safety);
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
     * @param json 严格编解码器，不从正文截取或修复JSON
     * @param safety 原文及规范化结果的输出安全检查
     * @return 保留全部字段的产物；空答案及非法封装继续拒绝
     */
    private static String readResult(
            Msg result, boolean businessJson, InteractionCodec json, SafetyGate safety) {
        if (!result.hasStructuredData()) {
            // 仅业务模式接受完整的助手文本对象；工具调用、思考块等不当作最终业务结果。
            if (!businessJson
                    || json == null
                    || result.getRole() != MsgRole.ASSISTANT
                    || result.getContent().isEmpty()
                    || result.getContent().stream()
                            .anyMatch(block -> !(block instanceof TextBlock))) {
                throw new OutputFailure(OutputProblem.UNSTRUCTURED);
            }
            String raw =
                    new RestrictedHarnessFactory.StructuredReply(result.getTextContent()).answer();
            safety.require("OUTPUT", raw);
            Map<?, ?> data;
            try {
                data = json.decode(raw, Map.class);
            } catch (RuntimeException invalid) {
                // 不保存解析器异常，避免正文、非法值或动态路径进入诊断。
                throw new OutputFailure(OutputProblem.UNSTRUCTURED);
            }
            if (data == null) throw new OutputFailure(OutputProblem.UNSTRUCTURED);
            return readBusinessObject(data);
        }
        if (!businessJson)
            return result.getStructuredData(RestrictedHarnessFactory.StructuredReply.class)
                    .answer();
        return readBusinessObject(result.getStructuredData(Map.class));
    }

    /**
     * 规范化完整业务对象，沿用原有封装检查，不丢弃旁字段。
     *
     * @param data 完整业务对象
     * @return 仍待调用方校验的JSON或兼容answer正文
     */
    private static String readBusinessObject(Map<?, ?> data) {
        // generate_response的单层response封装不能混入业务字段后再被静默丢弃。
        if (data.size() == 1 && data.get("response") instanceof Map<?, ?> nested) data = nested;
        if (data.containsKey("answer")) {
            if (data.size() != 1 || !(data.get("answer") instanceof String answer))
                throw new OutputFailure(OutputProblem.ENVELOPE);
            return new RestrictedHarnessFactory.StructuredReply(answer).answer();
        }
        if (data.isEmpty()) throw new OutputFailure(OutputProblem.EMPTY);
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
