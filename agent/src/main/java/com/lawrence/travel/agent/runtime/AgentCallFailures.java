package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.execution.AgentCallFailure;
import com.lawrence.travel.application.interaction.DialogueDecodeFailure;
import com.lawrence.travel.application.safety.SafetyGate;
import com.lawrence.travel.application.memory.PreferenceChangedException;
import com.lawrence.travel.domain.execution.control.CallRejectedException;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** 适配器共用的失败边界；保留可信分类，不传播未知异常正文或原因链。 */
final class AgentCallFailures {
    /** 只提供静态边界方法。 */
    private AgentCallFailures() {}

    /**
     * 解开异步包装，保留应用已定义的安全异常；其余归入固定未知失败。
     *
     * @param failure 内部失败
     * @return 可交给应用的固定分类，不携带未知异常引用
     */
    static RuntimeException translate(Throwable failure) {
        var current = failure;
        for (int depth = 0; depth < 8 && current != null; depth++) {
            if (current instanceof AgentCallFailure known) return known;
            if (current instanceof CallRejectedException known) return known;
            if (current instanceof PreferenceChangedException known) return known;
            if (current instanceof SafetyGate.Failure known) return known;
            if (current instanceof DialogueDecodeFailure known) return known;
            if (current instanceof TravelStorageFailure known) return known;
            if (!(current instanceof CompletionException || current instanceof ExecutionException))
                break;
            current = current.getCause();
        }
        return new AgentCallFailure(AgentCallFailure.Code.MODEL_EXECUTION_FAILED);
    }

    /**
     * 将明确的JSON/DTO不合法归为确定性输出失败，避免再次调用模型。
     *
     * @param codec 严格业务编解码器
     * @param body 本地持有的完整返回，不记录日志
     * @param type 当前DTO
     * @param <T> 业务类型
     * @return 非空结果，业务提交仍需后续校验
     */
    static <T> T decode(InteractionCodec codec, String body, Class<T> type) {
        try {
            T value = codec.decode(body, type);
            if (value == null)
                throw new AgentCallFailure(AgentCallFailure.Code.MODEL_OUTPUT_SCHEMA);
            return value;
        } catch (TravelStorageFailure failure) {
            if (failure.code() != TravelStorageFailure.Code.INVALID_INPUT) throw failure;
            throw new AgentCallFailure(AgentCallFailure.Code.MODEL_OUTPUT_SCHEMA);
        } catch (IllegalArgumentException failure) {
            throw new AgentCallFailure(AgentCallFailure.Code.MODEL_OUTPUT_SCHEMA);
        }
    }
}
