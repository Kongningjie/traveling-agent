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
            assertThat(agent.getToolkit().getToolNames())
                    .containsExactly("lookup_validation_value");
            var result =
                    agent.call(
                                    List.of(
                                            Msg.builder()
                                                    .name("user")
                                                    .role(MsgRole.USER)
                                                    .content(
                                                            TextBlock.builder()
                                                                    .text("lookup then answer")
                                                                    .build())
                                                    .build()),
                                    RestrictedHarnessFactory.StructuredReply.class)
                            .block(Duration.ofSeconds(10));
            assertThat(
                            result.getStructuredData(RestrictedHarnessFactory.StructuredReply.class)
                                    .answer())
                    .isEqualTo("7");
            assertThat(tool.calls.get()).isEqualTo(1);
            assertThat(model.calls.get()).isEqualTo(2);
        }
    }

    /** 旧工作区记忆及指令文件不能绕过结构化删除过滤；模型只看到本次受控输入。 */
    @Test
    void oldWorkspaceMemoryCannotRestoreDeletedPreference() throws Exception {
        String deleted = "DELETED_PREFERENCE_SENTINEL";
        java.nio.file.Files.writeString(workspace.resolve("MEMORY.md"), deleted);
        java.nio.file.Files.writeString(workspace.resolve("AGENTS.md"), deleted);
        java.nio.file.Files.createDirectories(workspace.resolve("memory"));
        java.nio.file.Files.writeString(workspace.resolve("memory/MEMORY.md"), deleted);
        var toolkit = new Toolkit();
        toolkit.registerTool(new ProbeTool());
        var model =
                new ScriptedModel() {
                    /** {@inheritDoc} 直接断言真实Harness发给模型的消息；无任何外部请求。 */
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        String body =
                                new com.lawrence.travel.infrastructure.travel.TravelJson()
                                        .encode(messages);
                        assertThat(body).contains("ONLY_CURRENT_INPUT").doesNotContain(deleted);
                        return super.stream(messages, tools, options);
                    }
                };
        try (var agent = RestrictedHarnessFactory.create(model, toolkit, workspace, "planner")) {
            var result =
                    agent.call(
                                    List.of(
                                            Msg.builder()
                                                    .name("user")
                                                    .role(MsgRole.USER)
                                                    .content(
                                                            TextBlock.builder()
                                                                    .text("ONLY_CURRENT_INPUT")
                                                                    .build())
                                                    .build()),
                                    RestrictedHarnessFactory.StructuredReply.class)
                            .block(Duration.ofSeconds(10));
            assertThat(
                            result.getStructuredData(RestrictedHarnessFactory.StructuredReply.class)
                                    .answer())
                    .isEqualTo("7");
        }
    }

    /** 模型失败不会触发默认 SDK／Harness 多次重试。 */
    @Test
    void modelFailureIsNotRetried() {
        var count = new AtomicInteger();
        var model =
                new ScriptedModel() {
                    /** {@inheritDoc} 只返回固定测试失败。 */
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.defer(
                                () -> {
                                    count.incrementAndGet();
                                    return Flux.error(new IllegalStateException("TEST_FAILURE"));
                                });
                    }
                };
        try (var agent =
                RestrictedHarnessFactory.create(model, new Toolkit(), workspace, "reviewer")) {
            assertThatThrownBy(
                            () ->
                                    agent.call(
                                                    List.of(
                                                            Msg.builder()
                                                                    .name("user")
                                                                    .role(MsgRole.USER)
                                                                    .content(
                                                                            TextBlock.builder()
                                                                                    .text("test")
                                                                                    .build())
                                                                    .build()))
                                            .block(Duration.ofSeconds(10)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(count.get()).isEqualTo(1);
        }
    }

    /** 真实Harness异常包装必须保留额度暂停类型，且不进行隐藏模型重试。 */
    @Test
    void typedQuotaFailureSurvivesHarnessWithoutRetry() {
        var quotaCalls = new AtomicInteger();
        var model =
                new ScriptedModel() {
                    /** {@inheritDoc} 只产生本地类型化拒绝，绝无网络请求。 */
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        quotaCalls.incrementAndGet();
                        return Flux.error(
                                new com.lawrence.travel.domain.execution.control
                                        .CallRejectedException(
                                        com.lawrence.travel.domain.execution.control
                                                .CallRejectedException.Reason.QUOTA_INSUFFICIENT));
                    }
                };
        try (var agent =
                RestrictedHarnessFactory.create(model, new Toolkit(), workspace, "planner")) {
            assertThatThrownBy(
                            () ->
                                    agent.call(
                                                    List.of(
                                                            Msg.builder()
                                                                    .name("user")
                                                                    .role(MsgRole.USER)
                                                                    .content(
                                                                            TextBlock.builder()
                                                                                    .text(
                                                                                            "local quota failure")
                                                                                    .build())
                                                                    .build()))
                                            .block(Duration.ofSeconds(10)))
                    .satisfies(
                            failure -> {
                                Throwable root = failure;
                                while (root.getCause() != null) root = root.getCause();
                                assertThat(root)
                                        .isInstanceOf(
                                                com.lawrence.travel.domain.execution.control
                                                        .CallRejectedException.class)
                                        .hasMessage("QUOTA_INSUFFICIENT");
                            });
            assertThat(quotaCalls.get()).isOne();
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
            try (var agent =
                    RestrictedHarnessFactory.create(model, toolkit, workspace, "planner")) {
                var result =
                        agent.call(
                                        List.of(
                                                Msg.builder()
                                                        .name("user")
                                                        .role(MsgRole.USER)
                                                        .content(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "isolated attempt "
                                                                                        + attempt)
                                                                        .build())
                                                        .build()),
                                        RestrictedHarnessFactory.StructuredReply.class)
                                .block(Duration.ofSeconds(10));
                assertThat(
                                result.getStructuredData(
                                                RestrictedHarnessFactory.StructuredReply.class)
                                        .answer())
                        .isEqualTo("7");
                assertThat(tool.calls.get()).isEqualTo(1);
                assertThat(model.initialUserMessages).isEqualTo(1);
            }
        }
    }

    /** 实际 Harness 工具委派另一 Harness；分支使用独立执行资源，不建立框架后台调度。 */
    @Test
    void delegatesToChildHarnessOnSeparateBoundedResources() throws Exception {
        try (var resources =
                        new com.lawrence.travel.infrastructure.execution.BoundedExecutionResources(
                                1, 1);
                var scope = resources.openRun()) {
            var toolkit = new Toolkit();
            toolkit.registerTool(new ChildHarnessTool(scope, workspace.resolve("child")));
            try (var parent =
                    RestrictedHarnessFactory.create(
                            new ScriptedModel(),
                            toolkit,
                            workspace.resolve("parent"),
                            "coordinator")) {
                var result =
                        parent.call(
                                        List.of(
                                                Msg.builder()
                                                        .name("user")
                                                        .role(MsgRole.USER)
                                                        .content(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "delegate then answer")
                                                                        .build())
                                                        .build()),
                                        RestrictedHarnessFactory.StructuredReply.class)
                                .block(Duration.ofSeconds(10));
                assertThat(
                                result.getStructuredData(
                                                RestrictedHarnessFactory.StructuredReply.class)
                                        .answer())
                        .isEqualTo("7");
            }
        }
    }

    /** 显式委派的诊断工具；不开放默认 subagent 工具，也不直接访问存储。 */
    public static final class ChildHarnessTool {
        private final com.lawrence.travel.application.port.ExecutionResources.RunScope scope;
        private final Path workspace;

        /**
         * 绑定父运行共享的分支额度和独立子上下文目录。
         * @param scope 父运行资源范围
         * @param workspace 隔离子目录
         */
        public ChildHarnessTool(
                com.lawrence.travel.application.port.ExecutionResources.RunScope scope,
                Path workspace) {
            this.scope = scope;
            this.workspace = workspace;
        }

        /**
         * @return 从真实子 Harness 工具循环取得的固定结果；失败不补造答案
         */
        @Tool(
                name = "lookup_validation_value",
                description = "Delegate the offline validation to a child agent.")
        public String lookup() {
            return scope.forkResearch(
                            () -> {
                                var toolkit = new Toolkit();
                                toolkit.registerTool(new ProbeTool());
                                try (var child =
                                        RestrictedHarnessFactory.create(
                                                new ScriptedModel(),
                                                toolkit,
                                                workspace,
                                                "research")) {
                                    var result =
                                            child.call(
                                                            List.of(
                                                                    Msg.builder()
                                                                            .name("user")
                                                                            .role(MsgRole.USER)
                                                                            .content(
                                                                                    TextBlock
                                                                                            .builder()
                                                                                            .text(
                                                                                                    "lookup then answer")
                                                                                            .build())
                                                                            .build()),
                                                            RestrictedHarnessFactory.StructuredReply
                                                                    .class)
                                                    .block(Duration.ofSeconds(5));
                                    return result.getStructuredData(
                                                    RestrictedHarnessFactory.StructuredReply.class)
                                            .answer();
                                }
                            })
                    .toCompletableFuture()
                    .orTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                    .join();
        }
    }

    /** 无外部副作用的测试工具，不读取真实用户数据。 */
    public static class ProbeTool {
        final AtomicInteger calls = new AtomicInteger();

        /**
         * @return 固定校验值，用于确认真实工具执行而非模型猜测
         */
        @Tool(
                name = "lookup_validation_value",
                description = "Return the validation value. Call this before answering.")
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
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(
                    () -> {
                        boolean first = calls.getAndIncrement() == 0;
                        if (first)
                            initialUserMessages =
                                    messages.stream()
                                            .filter(msg -> msg.getRole() == MsgRole.USER)
                                            .count();
                        return Flux.just(
                                ChatResponse.builder()
                                        .id("test")
                                        .content(
                                                List.of(
                                                        ToolUseBlock.builder()
                                                                .id(first ? "tool1" : "tool2")
                                                                .name(
                                                                        first
                                                                                ? "lookup_validation_value"
                                                                                : "generate_response")
                                                                .content(
                                                                        first
                                                                                ? "{}"
                                                                                : "{\"response\":{\"answer\":\"7\"}}")
                                                                .input(
                                                                        first
                                                                                ? Map.of()
                                                                                : Map.of(
                                                                                        "response",
                                                                                        Map.of(
                                                                                                "answer",
                                                                                                "7")))
                                                                .build()))
                                        .build());
                    });
        }

        /**
         * @return 明确的测试模型名称
         */
        @Override
        public String getModelName() {
            return "offline-test";
        }
    }
}
