package com.lawrence.travel.bootstrap;

import com.lawrence.travel.agent.runtime.SanitizedAgentFailure;
import com.lawrence.travel.application.port.AgentRuntimePort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** 隔离验收的固定码、异常类型及定位点记录器；不保存原异常正文或模型输出，不装配到生产。 */
final class RuntimeFailureEvidence {
    private final List<Map<String, Object>> failures = new ArrayList<>();

    /**
     * 包装验收句柄，保持原结果和取消行为；先记录类型再将失败交回调用方。
     *
     * @param role 本次配置角色
     * @param original 原运行句柄
     * @return 仅增加脱敏验收记录的句柄
     */
    AgentRuntimePort.Invocation observe(String role, AgentRuntimePort.Invocation original) {
        var result =
                original.result()
                        .whenComplete(
                                (answer, failure) -> {
                                    if (failure != null) record(role, "RESULT", failure);
                                });
        return new AgentRuntimePort.Invocation() {
            /** {@inheritDoc} 不改写成功文本，也不吞掉原失败。 */
            @Override
            public CompletionStage<String> result() {
                return result;
            }

            /** {@inheritDoc} 仍由原句柄取消或等待退出，关闭异常只记录类型。 */
            @Override
            public void close() {
                try {
                    original.close();
                } catch (RuntimeException failure) {
                    record(role, "CLOSE", failure);
                    throw failure;
                }
            }
        };
    }

    /**
     * 在短临界区保存最多32条记录、每条最多8层；只读取脱敏固定码，不读取原异常消息。
     *
     * @param role 固定角色，未知值记为OTHER
     * @param boundary 由调用点传入的RESULT或CLOSE
     * @param failure 只在本方法内读取类型，不保存引用
     */
    private synchronized void record(String role, String boundary, Throwable failure) {
        if (failures.size() >= 32) return;
        var types = new ArrayList<String>();
        var sites = new ArrayList<String>();
        String code = "UNCLASSIFIED";
        for (var current = failure;
                current != null && types.size() < 8;
                current = current.getCause()) {
            if (current instanceof SanitizedAgentFailure sanitized) {
                if (sanitized.getMessage() != null
                        && Set.of(
                                        "MODEL_EXECUTION_FAILED",
                                        "MODEL_CANCELLED",
                                        "MODEL_FREE_QUOTA_EXHAUSTED",
                                        "MODEL_ACCESS_DENIED",
                                        "MODEL_INVALID_PARAMETER",
                                        "MODEL_CONTEXT_LIMIT_SAFE",
                                        "MODEL_CONTEXT_LIMIT",
                                        "MODEL_NOT_FOUND",
                                        "MODEL_RATE_LIMITED",
                                        "MODEL_TRANSPORT_FAILED",
                                        "MODEL_OPTIONS_REJECTED",
                                        "MODEL_ENDPOINT_REJECTED",
                                        "MODEL_INPUT_TOO_LARGE",
                                        "MODEL_INPUT_INVALID",
                                        "MODEL_MEDIA_NOT_ALLOWED",
                                        "MODEL_ID_REJECTED",
                                        "MODEL_OUTPUT_INVALID",
                                        "MODEL_OUTPUT_MISSING",
                                        "MODEL_OUTPUT_UNSTRUCTURED",
                                        "MODEL_OUTPUT_ENVELOPE",
                                        "MODEL_OUTPUT_EMPTY",
                                        "MODEL_STREAM_INVALID",
                                        "MODEL_STREAM_INCOMPLETE",
                                        "MODEL_STREAM_TOO_LARGE")
                                .contains(sanitized.getMessage())) code = sanitized.getMessage();
                for (int index = 0; index < sanitized.exceptionTypes().size(); index++) {
                    if (types.size() < 8) {
                        types.add(sanitized.exceptionTypes().get(index));
                        sites.add(sanitized.exceptionSites().get(index));
                    }
                }
                break;
            }
            types.add(current.getClass().getName());
            var frames = current.getStackTrace();
            sites.add(
                    frames.length == 0
                            ? "UNAVAILABLE"
                            : frames[0].getClassName() + "#" + frames[0].getMethodName());
        }
        failures.add(
                Map.of(
                        "role",
                        role != null
                                        && Set.of("coordinator", "research", "planner", "reviewer")
                                                .contains(role)
                                ? role
                                : "OTHER",
                        "boundary",
                        boundary,
                        "code",
                        code,
                        "exceptionTypes",
                        List.copyOf(types),
                        "exceptionSites",
                        List.copyOf(sites)));
    }

    /**
     * 返回有界只读快照，可安全写入合成验收报告。
     *
     * @return 仅角色、边界、固定码和类/方法，不代表根因已经确定
     */
    synchronized List<Map<String, Object>> snapshot() {
        return List.copyOf(failures);
    }

    /**
     * 检查本批是否已有运行失败，供真实验收在下一次计量预占前停止请求。
     *
     * @return 一旦记录失败就始终为true
     */
    synchronized boolean failed() {
        return !failures.isEmpty();
    }
}
