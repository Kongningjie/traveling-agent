package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.execution.AgentCallFailure;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.tool.ToolValidator;
import io.agentscope.core.util.JsonUtils;
import java.util.Map;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** 在框架执行结构化结果工具前校验业务Schema，阻止确定性错误触发额外模型修正。 */
final class BusinessOutputGuard implements MiddlewareBase {
    private volatile Map<String, Object> responseSchema;

    /** 每个隔离Harness独占一份Schema引用，不跨运行或并行调用共享。 */
    BusinessOutputGuard() {}

    /**
     * 保存本轮真正交给模型的generate_response参数Schema，不按角色猜测输出类型。
     *
     * @param agent 当前隔离Agent
     * @param context 当前调用上下文
     * @param input 实际推理输入及工具定义
     * @param next 原推理链
     * @return 未改写的推理事件流
     */
    @Override
    public Flux<AgentEvent> onReasoning(
            io.agentscope.core.agent.Agent agent,
            io.agentscope.core.agent.RuntimeContext context,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        responseSchema =
                input.tools() == null
                        ? null
                        : input.tools().stream()
                                .filter(tool -> tool.getName().equals("generate_response"))
                                .map(tool -> tool.getParameters())
                                .findFirst()
                                .orElse(null);
        return next.apply(input);
    }

    /**
     * 非结果工具沿用原链，结构化结果不合法时直接失败，不交给框架转换成工具错误后再次推理。
     *
     * @param agent 当前隔离Agent
     * @param context 当前调用上下文
     * @param input 模型返回的待执行工具
     * @param next 原工具执行链
     * @return 原事件流，或仅含固定失败码的错误流
     */
    @Override
    public Flux<AgentEvent> onActing(
            io.agentscope.core.agent.Agent agent,
            io.agentscope.core.agent.RuntimeContext context,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    for (var tool : input.toolCalls()) {
                        if (!tool.getName().equals("generate_response")) continue;
                        String body = JsonUtils.getJsonCodec().toJson(tool.getInput());
                        if (responseSchema == null
                                || ToolValidator.validateInput(body, responseSchema) != null)
                            return Flux.error(
                                    new AgentCallFailure(
                                            AgentCallFailure.Code.MODEL_OUTPUT_SCHEMA));
                    }
                    return next.apply(input);
                });
    }
}
