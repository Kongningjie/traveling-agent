package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.execution.AgentCallFailure;
import com.lawrence.travel.application.execution.AgentCallFailure.Code;
import com.lawrence.travel.application.execution.AgentCallFailure.Recovery;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.knowledge.Research;
import com.lawrence.travel.infrastructure.interaction.InteractionJson;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证固定分类、当前请求的重试证据及隐私边界，不发外部请求。 */
class AgentFailurePolicyTest {
    /** 语义固定的协议错误停止，权限/配置/未知用量和超时暂停。 */
    @Test
    void deterministicAndUncertainFailuresNeverAutomaticallyRetry() {
        for (Code code :
                List.of(
                        Code.MODEL_OUTPUT_INVALID,
                        Code.MODEL_OUTPUT_UNSTRUCTURED,
                        Code.MODEL_OUTPUT_SCHEMA,
                        Code.MODEL_OUTPUT_TRUNCATED)) {
            assertThat(new AgentCallFailure(code).recovery()).isEqualTo(Recovery.STOP);
            assertThat(ExecutionEngineStore.Failure.forModel(new AgentCallFailure(code)))
                    .isEqualTo(ExecutionEngineStore.Failure.PERMANENT);
        }
        for (Code code :
                List.of(
                        Code.MODEL_ACCESS_DENIED,
                        Code.MODEL_OPTIONS_REJECTED,
                        Code.MODEL_INVALID_PARAMETER,
                        Code.MODEL_NOT_FOUND,
                        Code.MODEL_TRANSPORT_FAILED,
                        Code.MODEL_STREAM_INCOMPLETE,
                        Code.MODEL_TIMEOUT,
                        Code.MODEL_CANCELLED,
                        Code.MODEL_INTERRUPTED,
                        Code.MODEL_EXECUTION_FAILED)) {
            assertThat(new AgentCallFailure(code).recovery()).isEqualTo(Recovery.PAUSE);
        }
        assertThat(new AgentCallFailure(Code.MODEL_CONTEXT_LIMIT).recovery())
                .isEqualTo(Recovery.PAUSE_BUDGET);
    }

    /** 同一429/503码，只有明确零用量时才能进入有限重试；不把缺失用量推断为零。 */
    @Test
    void transientFailuresRequireExplicitZeroUsage() {
        for (String code : List.of("MODEL_RATE_LIMITED", "MODEL_PROVIDER_HTTP_503")) {
            assertThat(new AgentCallFailure(code, false).recovery()).isEqualTo(Recovery.PAUSE);
            assertThat(new AgentCallFailure(code, true).recovery()).isEqualTo(Recovery.RETRY);
            assertThat(new AgentCallFailure(code, true).diagnosticCode()).isEqualTo(code);
        }
        assertThat(new AgentCallFailure("MODEL_PROVIDER_HTTP_501", true).recovery())
                .isEqualTo(Recovery.PAUSE);
    }

    /** 未知码不能带入正文；异常不接受原原因或try-with-resources的敏感suppressed。 */
    @Test
    void unknownDiagnosticsAndSuppressedMessagesAreNotRetained() {
        for (String code :
                new String[] {null, "SYNTHETIC_PRIVATE", "MODEL_PROVIDER_HTTP_503_PRIVATE"}) {
            var failure = new AgentCallFailure(code, true);
            failure.addSuppressed(new IllegalStateException("SYNTHETIC_PRIVATE"));
            assertThat(failure.diagnosticCode()).isEqualTo("MODEL_EXECUTION_FAILED");
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getSuppressed()).isEmpty();
            assertThat(failure.toString()).doesNotContain("SYNTHETIC_PRIVATE");
            assertThat(failure.recovery()).isEqualTo(Recovery.PAUSE);
        }
    }

    /** 严格DTO解码失败归为一次性输出错误，不再变成泛化的可重试执行异常。 */
    @Test
    void invalidBusinessJsonIsAStopFailure() {
        var codec = new InteractionJson(new TravelJson());
        assertThatThrownBy(
                        () ->
                                AgentCallFailures.decode(
                                        codec, "SYNTHETIC_PRIVATE", Research.Plan.class))
                .isInstanceOf(AgentCallFailure.class)
                .hasMessage("MODEL_OUTPUT_SCHEMA")
                .satisfies(
                        error ->
                                assertThat(((AgentCallFailure) error).recovery())
                                        .isEqualTo(Recovery.STOP));
    }
}
