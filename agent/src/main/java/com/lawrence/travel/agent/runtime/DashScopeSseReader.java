package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.domain.execution.control.TokenUsage;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.io.Reader;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 有界 SSE data 解析；正文是不可信产物，只有完整终帧用量可用于结算。 */
final class DashScopeSseReader {
    /** 工具类不允许实例化。 */
    private DashScopeSseReader() { }

    /**
     * 逐字符限制行、事件和总长度；按 SSE 空行提交，支持注释与多行 data。
     * @param reader 实际响应字符流，由调用者关闭
     * @param consumer 接收纯 JSON data，不含 SSE 前缀
     * @param cancelled 本次连接是否已取消
     * @return 正常 EOF 时的终帧累计用量；终帧无用量时仍为未知
     * @throws IOException 传输读取失败
     */
    static TokenUsage read(Reader reader, Consumer<String> consumer, BooleanSupplier cancelled) throws IOException {
        var line = new StringBuilder();
        var data = new StringBuilder();
        var state = new State();
        int total = 0;
        int value;
        boolean carriageReturn = false;
        while ((value = reader.read()) != -1) {
            if (cancelled.getAsBoolean()) throw new HttpTransportException("MODEL_CANCELLED");
            if (++total > 1_048_576) throw new HttpTransportException("MODEL_STREAM_TOO_LARGE");
            if (value == '\n' && carriageReturn) { carriageReturn = false; continue; }
            carriageReturn = value == '\r';
            if (value == '\n' || value == '\r') {
                String field = line.toString();
                line.setLength(0);
                if (field.isEmpty()) {
                    if (!data.isEmpty()) {
                        state.frame(data.substring(0, data.length() - 1), consumer);
                        data.setLength(0);
                    }
                } else if (field.equals("data") || field.startsWith("data:")) {
                    String text = field.equals("data") ? "" : field.substring(5);
                    if (text.startsWith(" ")) text = text.substring(1);
                    data.append(text).append('\n');
                    if (data.length() > 65536) throw new HttpTransportException("MODEL_STREAM_TOO_LARGE");
                }
            } else {
                line.append((char) value);
                if (line.length() > 65536) throw new HttpTransportException("MODEL_STREAM_TOO_LARGE");
            }
        }
        // SSE 未以空行完成的事件不算已提交；不能将断流尾部猜成完整终帧。
        if (!line.isEmpty() || !data.isEmpty() || !state.terminal) {
            throw new HttpTransportException("MODEL_STREAM_INCOMPLETE");
        }
        return state.usage;
    }

    /** 单连接解析状态，不跨订阅共享；累计用量不能把增量帧逐次相加。 */
    private static final class State {
        private int frames;
        private boolean terminal;
        private boolean done;
        private TokenUsage usage;

        /** 初始化空连接状态。 */
        private State() { }

        /**
         * 校验单帧结构与完成原因，仅保存终帧累计用量，不传播错误正文。
         * @param data 完整 SSE data
         * @param consumer 接收已经校验形状的模型产物
         */
        private void frame(String data, Consumer<String> consumer) {
            if (++frames > 512 || done) throw new HttpTransportException("MODEL_STREAM_INVALID");
            if (data.equals("[DONE]")) {
                if (!terminal) throw new HttpTransportException("MODEL_STREAM_INCOMPLETE");
                done = true;
                return;
            }
            Map<?, ?> json;
            try { json = JsonUtils.getJsonCodec().fromJson(data, Map.class); }
            catch (RuntimeException invalid) { throw new HttpTransportException("MODEL_STREAM_INVALID"); }
            if (json == null) throw new HttpTransportException("MODEL_STREAM_INVALID");
            if (json.get("code") != null) {
                String code = "AllocationQuota.FreeTierOnly".equals(json.get("code"))
                        ? "MODEL_FREE_QUOTA_EXHAUSTED" : "MODEL_STREAM_PROVIDER_ERROR";
                throw new HttpTransportException(code);
            }
            if (terminal) throw new HttpTransportException("MODEL_STREAM_INVALID");
            if (!(json.get("output") instanceof Map<?, ?> output)
                    || !(output.get("choices") instanceof java.util.List<?> choices) || choices.size() != 1
                    || !(choices.get(0) instanceof Map<?, ?> choice)) {
                throw new HttpTransportException("MODEL_STREAM_INVALID");
            }
            Object reason = choice.get("finish_reason");
            if (reason instanceof String text && !text.isBlank() && !text.equals("null")) {
                if (text.equals("length")) throw new HttpTransportException("MODEL_OUTPUT_TRUNCATED");
                if (!Set.of("stop", "tool_calls").contains(text)) {
                    throw new HttpTransportException("MODEL_STREAM_INVALID");
                }
                terminal = true;
                if (json.get("usage") instanceof Map<?, ?> tokens
                        && tokens.get("input_tokens") instanceof Number input
                        && tokens.get("output_tokens") instanceof Number generated) {
                    try { usage = new TokenUsage(input.longValue(), generated.longValue()); }
                    catch (IllegalArgumentException invalid) { throw new HttpTransportException("MODEL_STREAM_INVALID"); }
                }
            }
            consumer.accept(data);
        }
    }
}
