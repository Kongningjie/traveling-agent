package com.lawrence.travel.agent.runtime;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 运行真实 Harness 工具循环，用确定性模型验证结构化产物和工具权限。 */
public class HarnessContractTest {
    @TempDir Path workspace;

    /** 工具调用完成后进入下一模型轮次，结构化输出正确且无默认高权限工具。 */
    @Test
    void executesWhitelistedToolAndStructuredReply() {
        var tool = new ProbeTool();
        var toolkit = new Toolkit();
        toolkit.registerTool(tool);
        var model = new ScriptedModel();
        try (var agent = RestrictedHarnessFactory.create(model, toolkit, workspace, "planner")) {
            assertThat(agent.getToolkit().getToolNames()).containsExactly("lookup_validation_value");
            var result = agent.call(List.of(Msg.builder().name("user").role(MsgRole.USER)
                    .content(TextBlock.builder().text("lookup then answer").build()).build()),
                    RestrictedHarnessFactory.StructuredReply.class).block(Duration.ofSeconds(10));
            assertThat(result.getStructuredData(RestrictedHarnessFactory.StructuredReply.class).answer()).isEqualTo("7");
            assertThat(tool.calls.get()).isEqualTo(1);
            assertThat(model.calls.get()).isEqualTo(2);
        }
    }

    /** 模型失败不会触发默认 SDK／Harness 多次重试。 */
    @Test
    void modelFailureIsNotRetried() {
        var count = new AtomicInteger();
        var model = new ScriptedModel() {
            /** {@inheritDoc} 只返回固定测试失败。 */
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(() -> { count.incrementAndGet(); return Flux.error(new IllegalStateException("TEST_FAILURE")); });
            }
        };
        try (var agent = RestrictedHarnessFactory.create(model, new Toolkit(), workspace, "reviewer")) {
            assertThatThrownBy(() -> agent.call(List.of(Msg.builder().name("user").role(MsgRole.USER)
                    .content(TextBlock.builder().text("test").build()).build())).block(Duration.ofSeconds(10)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(count.get()).isEqualTo(1);
        }
    }

    /** 相同角色和工作区连续构建时，新尝试不得加载上次对话或工具去重状态。 */
    @Test
    void repeatedAttemptsDoNotShareConversationOrToolState() {
        for (int attempt = 0; attempt < 2; attempt++) {
            var tool = new ProbeTool();
            var toolkit = new Toolkit();
            toolkit.registerTool(tool);
            var model = new ScriptedModel();
            try (var agent = RestrictedHarnessFactory.create(model, toolkit, workspace, "planner")) {
                var result = agent.call(List.of(Msg.builder().name("user").role(MsgRole.USER)
                        .content(TextBlock.builder().text("isolated attempt " + attempt).build()).build()),
                        RestrictedHarnessFactory.StructuredReply.class).block(Duration.ofSeconds(10));
                assertThat(result.getStructuredData(RestrictedHarnessFactory.StructuredReply.class).answer()).isEqualTo("7");
                assertThat(tool.calls.get()).isEqualTo(1);
                assertThat(model.initialUserMessages).isEqualTo(1);
            }
        }
    }

    /** 无外部副作用的测试工具，不读取真实用户数据。 */
    public static class ProbeTool {
        final AtomicInteger calls = new AtomicInteger();
        /** @return 固定校验值，用于确认真实工具执行而非模型猜测 */
        @Tool(name = "lookup_validation_value", description = "Return the validation value. Call this before answering.")
        public String lookup() {
            calls.incrementAndGet();
            return "7";
        }
    }

    /** 先调用工具再返回结构化数据的确定性模型。 */
    static class ScriptedModel implements Model {
        final AtomicInteger calls = new AtomicInteger();
        long initialUserMessages;
        /** {@inheritDoc} 每次订阅表示一个实际模型轮次。 */
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(() -> {
                boolean first = calls.getAndIncrement() == 0;
                if (first) initialUserMessages = messages.stream().filter(msg -> msg.getRole() == MsgRole.USER).count();
                return Flux.just(ChatResponse.builder().id("test")
                        .content(List.of(ToolUseBlock.builder().id(first ? "tool1" : "tool2")
                                .name(first ? "lookup_validation_value" : "generate_response")
                                .content(first ? "{}" : "{\"response\":{\"answer\":\"7\"}}")
                                .input(first ? Map.of() : Map.of("response", Map.of("answer", "7"))).build())).build());
            });
        }
        /** @return 明确的测试模型名称 */
        @Override
        public String getModelName() { return "offline-test"; }
    }
}
