package com.lawrence.travel.agent.runtime;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.tool.ToolValidator;
import io.agentscope.core.util.JsonUtils;
import java.util.Map;
import java.util.List;
import java.util.Set;
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
                        var schema = responseSchema;
                        String error =
                                schema == null ? null : ToolValidator.validateInput(body, schema);
                        if (schema == null || error != null) {
                            return Flux.error(
                                    new ControlledHarnessCall.OutputFailure(
                                            ControlledHarnessCall.OutputProblem.SCHEMA,
                                            schema == null
                                                    ? List.of("SCHEMA_UNAVAILABLE")
                                                    : schemaPaths(error, schema)));
                        }
                    }
                    return next.apply(input);
                });
    }

    /**
     * 从 SDK 错误中提取已知字段路径，仅保留当前 Schema 声明的字段和数组位置。
     *
     * @param error SDK 校验信息，仅在内存读取
     * @param schema 实际工具 Schema
     * @return 固定字段路径，不含动态键、值或异常正文
     */
    private static List<String> schemaPaths(String error, Map<String, Object> schema) {
        var names = new java.util.HashSet<String>();
        collectPropertyNames(schema, names);
        var paths = new java.util.LinkedHashSet<String>();
        for (String line : error.split("\\R", 32)) {
            int end = line.indexOf(": ");
            if (end < 1 || end > 128) continue;
            String path = line.substring(0, end);
            if (path.startsWith("/")) {
                path = "$" + path.replaceAll("/([0-9]{1,5})(?=/|$)", "[$1]").replace('/', '.');
            }
            if (!path.matches("\\$(?:\\.[A-Za-z][A-Za-z0-9]*|\\[\\d{1,5}\\])*")) continue;
            String[] segments = path.replaceAll("\\[\\d{1,5}\\]", "").split("\\.");
            if (java.util.Arrays.stream(segments).skip(1).allMatch(names::contains)) {
                paths.add(path.replaceAll("\\[\\d{1,5}\\]", "[]"));
            }
            if (paths.size() == 8) break;
        }
        return List.copyOf(paths);
    }

    /**
     * 收集服务端 Schema 中的属性名。
     *
     * @param node 服务端生成的 Schema 节点
     * @param names 收集属性名的集合
     */
    private static void collectPropertyNames(Object node, Set<String> names) {
        if (node instanceof Map<?, ?> map) {
            if (map.get("properties") instanceof Map<?, ?> properties) {
                for (Object key : properties.keySet()) {
                    if (key instanceof String name) names.add(name);
                }
            }
            map.values().forEach(child -> collectPropertyNames(child, names));
        } else if (node instanceof List<?> list) {
            list.forEach(child -> collectPropertyNames(child, names));
        }
    }
}
