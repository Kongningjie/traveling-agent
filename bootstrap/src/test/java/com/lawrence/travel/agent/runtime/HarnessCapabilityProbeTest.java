package com.lawrence.travel.agent.runtime;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.middleware.MemoryFlushMiddleware;
import io.agentscope.harness.agent.middleware.ToolResultEvictionMiddleware;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import static com.lawrence.travel.agent.runtime.HarnessProbeFixture.answer;
import static com.lawrence.travel.agent.runtime.HarnessProbeFixture.builder;
import static com.lawrence.travel.agent.runtime.HarnessProbeFixture.context;
import static com.lawrence.travel.agent.runtime.HarnessProbeFixture.text;
import static com.lawrence.travel.agent.runtime.HarnessProbeFixture.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 直接运行锁定的Harness2.0.4，验证原生能力及不适合直接启用的边界；全部资料和模型均为离线合成。 */
class HarnessCapabilityProbeTest {
    @TempDir Path root;

    /** 同一状态仓库可跨Harness实例恢复；相同session下不同用户不应召回另一用户文本。 */
    @Test
    void restoresSessionAcrossInstancesAndSeparatesUsers() {
        var store = new InMemoryAgentStateStore();
        var model = new HarnessProbeFixture.ProbeModel((messages, number) -> answer("ACK"));
        try (var first =
                builder(model, root)
                        .stateStore(store)
                        .disableCompaction()
                        .disableToolResultEviction()
                        .build()) {
            first.call(List.of(user("USER_A_PRIVATE")), context("user-a", "chat"))
                    .block(Duration.ofSeconds(5));
        }
        try (var second =
                builder(model, root)
                        .stateStore(store)
                        .disableCompaction()
                        .disableToolResultEviction()
                        .build()) {
            second.call(List.of(user("NEXT")), context("user-a", "chat"))
                    .block(Duration.ofSeconds(5));
            assertThat(text(model.requests.getLast())).contains("USER_A_PRIVATE", "NEXT");
            second.call(List.of(user("USER_B")), context("user-b", "chat"))
                    .block(Duration.ofSeconds(5));
            assertThat(text(model.requests.getLast())).doesNotContain("USER_A_PRIVATE");
        }
    }

    /** 状态可序列化后恢复消息顺序；这只验证SDK接缝，不冒充已实现PG检查点。 */
    @Test
    void roundTripsCanonicalStateWithoutFilesystemPersistence() {
        var state =
                AgentState.builder()
                        .userId("user-a")
                        .sessionId("chat")
                        .context(List.of(user("ORIGINAL_CONSTRAINT")))
                        .build();
        var restored = AgentState.fromJsonString(state.toJson());
        assertThat(restored.getUserId()).isEqualTo("user-a");
        assertThat(restored.getSessionId()).isEqualTo("chat");
        assertThat(restored.getContext())
                .extracting(Msg::getTextContent)
                .containsExactly("ORIGINAL_CONSTRAINT");
        var model = new HarnessProbeFixture.ProbeModel((messages, number) -> answer("ACK"));
        var store = new InMemoryAgentStateStore();
        // ReActAgent按(user, session, agent_state)装载规范状态；仅往RuntimeContext塞agentState不会完成恢复。
        store.save("user-a", "chat", "agent_state", restored);
        try (var agent =
                builder(model, root)
                        .stateStore(store)
                        .disableCompaction()
                        .disableToolResultEviction()
                        .build()) {
            var rc = context("user-a", "chat");
            agent.call(List.of(user("CURRENT")), rc).block(Duration.ofSeconds(5));
            assertThat(text(model.requests.getLast())).contains("ORIGINAL_CONSTRAINT", "CURRENT");
        }
    }

    /** RuntimeContext中的状态提示不替代规范仓库装载，防止后续误把该参数当成持久化恢复实现。 */
    @Test
    void contextStateHintAloneDoesNotHydrateStoredConversation() {
        var state =
                AgentState.builder()
                        .userId("u")
                        .sessionId("s")
                        .context(List.of(user("NOT_HYDRATED")))
                        .build();
        var model = new HarnessProbeFixture.ProbeModel((messages, number) -> answer("ACK"));
        try (var agent =
                builder(model, root).disableCompaction().disableToolResultEviction().build()) {
            agent.call(
                            List.of(user("CURRENT")),
                            RuntimeContext.builder()
                                    .userId("u")
                                    .sessionId("s")
                                    .agentState(state)
                                    .build())
                    .block(Duration.ofSeconds(5));
            assertThat(text(model.requests.getLast()))
                    .contains("CURRENT")
                    .doesNotContain("NOT_HYDRATED");
        }
    }

    /** 原生自动压缩会额外调用一次模型；系统权威文本和本轮尾部保留，旧用户前缀进入摘要。 */
    @Test
    void automaticallyCompactsPrefixAndRetainsSystemAndCurrentTurn() {
        var model =
                new HarnessProbeFixture.ProbeModel(
                        (messages, number) ->
                                answer(
                                        text(messages).contains("SUMMARY_PROBE")
                                                ? "SUMMARY_RESULT"
                                                : "ACK"));
        try (var agent =
                builder(model, root)
                        .compaction(compaction(3))
                        .disableToolResultEviction()
                        .build()) {
            agent.call(
                            List.of(
                                    user("OLD_ONE HARD_LOCK_SENTINEL"),
                                    user("OLD_TWO"),
                                    user("CURRENT")),
                            context("u", "s"))
                    .block(Duration.ofSeconds(5));
            assertThat(model.requests).hasSize(2);
            assertThat(text(model.requests.getFirst()))
                    .contains("SUMMARY_PROBE", "OLD_ONE")
                    .doesNotContain("AUTHORITY_SENTINEL");
            assertThat(text(model.requests.getLast()))
                    .contains("AUTHORITY_SENTINEL", "SUMMARY_RESULT", "CURRENT")
                    .doesNotContain("OLD_ONE", "HARD_LOCK_SENTINEL");
        }
    }

    /** 默认异步等待工具仍会被SDK注入；生产工厂的最终白名单剔除步骤必须保留。 */
    @Test
    void disabledSubagentsDoNotRemoveImplicitWaitTool() {
        var model = new HarnessProbeFixture.ProbeModel((messages, number) -> answer("ACK"));
        try (var agent =
                builder(model, root).disableCompaction().disableToolResultEviction().build()) {
            assertThat(agent.getToolkit().getToolNames()).contains("wait_async_results");
        }
        try (var restricted =
                RestrictedHarnessFactory.create(
                        model, new Toolkit(), root.resolve("restricted"), "research")) {
            assertThat(restricted.getToolkit().getToolNames()).isEmpty();
        }
    }

    /** SDK摘要失败会将失败占位注入后续推理，因此业务层必须检测而不能将其作为可信摘要保存。 */
    @Test
    void nativeCompactorDegradesSummaryFailureInsteadOfFailingClosed() {
        var model =
                new HarnessProbeFixture.ProbeModel(
                        (messages, number) ->
                                Flux.error(new IllegalStateException("SYNTHETIC_SUMMARY_FAILURE")));
        var result =
                new ConversationCompactor(model, null)
                        .compactIfNeeded(
                                RuntimeContext.empty(),
                                List.of(user("OLD"), user("TAIL")),
                                compaction(2),
                                null,
                                null)
                        .block(Duration.ofSeconds(5));
        assertThat(result).isPresent();
        assertThat(text(result.orElseThrow()))
                .contains("Summarization failed", "SYNTHETIC_SUMMARY_FAILURE");
        assertThat(model.requests).hasSize(1);
    }

    /** 摘要器不能吞掉带中断原因的失败，调用取消必须向外传播。 */
    @Test
    void nativeCompactorPropagatesInterruption() {
        var model =
                new HarnessProbeFixture.ProbeModel(
                        (messages, number) ->
                                Flux.error(
                                        new IllegalStateException(
                                                "CANCELLED", new InterruptedException("LOCAL"))));
        assertThatThrownBy(
                        () ->
                                new ConversationCompactor(model, null)
                                        .compactIfNeeded(
                                                RuntimeContext.empty(),
                                                List.of(user("OLD"), user("TAIL")),
                                                compaction(2),
                                                null,
                                                null)
                                        .block(Duration.ofSeconds(5)))
                .hasRootCauseInstanceOf(InterruptedException.class);
        assertThat(model.requests).hasSize(1);
    }

    /** 压缩前Flush默认可独立产生额外请求并写文件；不能误计为仅一次摘要调用。 */
    @Test
    void flushAndSummaryAreSeparateModelCallsAndWriteWorkspaceMemory() throws Exception {
        var model =
                new HarnessProbeFixture.ProbeModel(
                        (messages, number) ->
                                answer(
                                        text(messages).contains("FLUSH_PROBE")
                                                ? "- WORKING_MEMORY_FACT"
                                                : "SUMMARY_RESULT"));
        try (var workspace = new WorkspaceManager(root)) {
            var config =
                    CompactionConfig.builder()
                            .triggerMessages(2)
                            .triggerTokens(100000)
                            .keepMessages(1)
                            .keepTokens(0)
                            .flushBeforeCompact(true)
                            .offloadBeforeCompact(false)
                            .summaryPrompt("SUMMARY_PROBE {messages}")
                            .build();
            var flush = new MemoryFlushManager(workspace, model, "FLUSH_PROBE");
            var result =
                    new ConversationCompactor(model, flush)
                            .compactIfNeeded(
                                    context("u", "s"),
                                    List.of(user("ORIGINAL"), user("TAIL")),
                                    config,
                                    "probe",
                                    "s")
                            .block(Duration.ofSeconds(5));
            assertThat(result).isPresent();
            assertThat(model.requests).hasSize(2);
            assertThat(files())
                    .anySatisfy(path -> assertThat(read(path)).contains("WORKING_MEMORY_FACT"));
        }
    }

    /** 原文卸载与摘要可单独启用，原始消息归档可核对，但仍不是PG权威恢复的实现。 */
    @Test
    void offloadsOriginalMessagesWithoutExtraMemoryExtraction() throws Exception {
        var model =
                new HarnessProbeFixture.ProbeModel((messages, number) -> answer("SUMMARY_RESULT"));
        try (var workspace = new WorkspaceManager(root)) {
            var config =
                    CompactionConfig.builder()
                            .triggerMessages(2)
                            .triggerTokens(100000)
                            .keepMessages(1)
                            .keepTokens(0)
                            .flushBeforeCompact(false)
                            .offloadBeforeCompact(true)
                            .summaryPrompt("SUMMARY_PROBE {messages}")
                            .build();
            var result =
                    new ConversationCompactor(model, new MemoryFlushManager(workspace, model))
                            .compactIfNeeded(
                                    context("u", "s"),
                                    List.of(user("ORIGINAL_ARCHIVE"), user("TAIL")),
                                    config,
                                    "probe",
                                    "s")
                            .block(Duration.ofSeconds(5));
            assertThat(result).isPresent();
            assertThat(model.requests).hasSize(1);
            assertThat(files().stream().filter(p -> p.toString().endsWith(".jsonl")).toList())
                    .anySatisfy(
                            path -> assertThat(read(path)).contains("ORIGINAL_ARCHIVE", "TAIL"));
        }
    }

    /** 原生记忆Hook在主流结束后排后台Flush；返回成功不代表辅助调用已经结算。 */
    @Test
    void memoryHookCanOutliveMainCompletion() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var model =
                new HarnessProbeFixture.ProbeModel(
                        (messages, number) ->
                                Flux.defer(
                                        () -> {
                                            started.countDown();
                                            try {
                                                if (!release.await(3, TimeUnit.SECONDS))
                                                    return Flux.error(
                                                            new IllegalStateException(
                                                                    "PROBE_TIMEOUT"));
                                                return answer("NO_REPLY");
                                            } catch (InterruptedException stopped) {
                                                Thread.currentThread().interrupt();
                                                return Flux.error(stopped);
                                            }
                                        }));
        try (var agent =
                builder(model, root).disableCompaction().disableToolResultEviction().build()) {
            var state =
                    AgentState.builder()
                            .userId("u")
                            .sessionId("s")
                            .context(List.of(user("FACT")))
                            .build();
            var rc = RuntimeContext.builder().userId("u").sessionId("s").agentState(state).build();
            var hook = new MemoryFlushMiddleware(agent.getWorkspaceManager(), model);
            try {
                hook.onAgent(
                                agent.getDelegate(),
                                rc,
                                new AgentInput(List.of()),
                                input -> Flux.empty())
                        .blockLast(Duration.ofSeconds(2));
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(release.getCount()).isEqualTo(1);
            } finally {
                release.countDown();
                assertThat(MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    /** 大工具结果原生卸载保留调用ID及原文缓存；不额外授予模型任意文件读取能力。 */
    @Test
    void evictsLargeResultAndKeepsToolCorrelation() throws Exception {
        var toolkit = new Toolkit();
        toolkit.registerTool(new LargeTool());
        var model =
                new HarnessProbeFixture.ProbeModel(
                        (messages, number) ->
                                number == 1
                                        ? Flux.just(
                                                ChatResponse.builder()
                                                        .content(
                                                                List.of(
                                                                        ToolUseBlock.builder()
                                                                                .id("call-proof")
                                                                                .name(
                                                                                        "large_document")
                                                                                .input(Map.of())
                                                                                .build()))
                                                        .build())
                                        : answer("ACK"));
        var eviction =
                ToolResultEvictionConfig.builder()
                        .maxResultChars(100)
                        .previewChars(12)
                        .evictionPath("tool-cache")
                        .build();
        try (var agent =
                builder(model, root)
                        .toolkit(toolkit)
                        .disableCompaction()
                        .toolResultEviction(eviction)
                        .build()) {
            agent.call(List.of(user("READ")), context("u", "s")).block(Duration.ofSeconds(5));
            var results =
                    model.requests.getLast().stream()
                            .flatMap(m -> m.getContent().stream())
                            .filter(ToolResultBlock.class::isInstance)
                            .map(ToolResultBlock.class::cast)
                            .toList();
            assertThat(results).hasSize(1);
            assertThat(results.getFirst().getId()).isEqualTo("call-proof");
            assertThat(
                            results.getFirst().getOutput().stream()
                                    .filter(TextBlock.class::isInstance)
                                    .map(TextBlock.class::cast)
                                    .map(TextBlock::getText)
                                    .collect(java.util.stream.Collectors.joining()))
                    .contains("saved to", "read_file")
                    .doesNotContain(LargeTool.BODY);
            assertThat(agent.getToolkit().getToolNames())
                    .doesNotContain("read_file", "execute_shell");
            // SDK把字符串工具返回值编码为JSON文本；卸载应逐字保留这一真实工具结果，包括外层引号。
            assertThat(files())
                    .anySatisfy(
                            path -> assertThat(read(path)).isEqualTo("\"" + LargeTool.BODY + "\""));
        }
    }

    /** 卸载写入失败时SDK仍会将完整结果交给模型，发送前必须另设最终Token／内容门禁。 */
    @Test
    void evictionFailureKeepsFullToolResultRatherThanEnforcingInputBudget() {
        var filesystem = mock(AbstractFilesystem.class);
        when(filesystem.write(any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("DISK_UNAVAILABLE"));
        var config =
                ToolResultEvictionConfig.builder()
                        .maxResultChars(100)
                        .previewChars(12)
                        .evictionPath("tool-cache")
                        .build();
        var middleware = new ToolResultEvictionMiddleware(filesystem, config);
        var result =
                ToolResultBlock.builder()
                        .id("call-proof")
                        .name("large_document")
                        .output(List.of(TextBlock.builder().text(LargeTool.BODY).build()))
                        .build();
        var input =
                new ReasoningInput(
                        List.of(Msg.builder().role(MsgRole.TOOL).content(List.of(result)).build()),
                        List.of(),
                        null);
        var captured = new AtomicReference<ReasoningInput>();
        var model = new HarnessProbeFixture.ProbeModel((messages, number) -> answer("ACK"));
        try (var agent =
                builder(model, root).disableCompaction().disableToolResultEviction().build()) {
            middleware
                    .onReasoning(
                            agent.getDelegate(),
                            context("u", "s"),
                            input,
                            next -> {
                                captured.set(next);
                                return Flux.empty();
                            })
                    .blockLast(Duration.ofSeconds(5));
            var unchanged =
                    (ToolResultBlock) captured.get().messages().getFirst().getContent().getFirst();
            assertThat(((TextBlock) unchanged.getOutput().getFirst()).getText())
                    .isEqualTo(LargeTool.BODY);
            assertThat(model.requests).isEmpty();
        }
    }

    /** 原生interrupt为协作停止信号，阻塞模型仍需显式取消订阅／传输；不冒称业务已可靠取消。 */
    @Test
    void interruptCancelsModelSubscription() throws Exception {
        var started = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var model =
                new HarnessProbeFixture.ProbeModel(
                        (messages, number) ->
                                Flux.<ChatResponse>never()
                                        .doOnSubscribe(s -> started.countDown())
                                        .doOnCancel(cancelled::countDown));
        try (var agent =
                builder(model, root).disableCompaction().disableToolResultEviction().build()) {
            var rc = context("u", "s");
            var call = agent.call(List.of(user("WAIT")), rc).subscribe(value -> {}, failure -> {});
            try {
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                agent.interrupt(rc);
                assertThat(cancelled.await(100, TimeUnit.MILLISECONDS)).isFalse();
                call.dispose();
                assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
            } finally {
                call.dispose();
            }
        }
    }

    /** 原生紧急恢复重建默认压缩配置，会重新打开已在普通压缩关闭的Flush／卸载，需适配兜底。 */
    @Test
    void emergencyRecoveryUsesDefaultFlushRatherThanConfiguredDisabledFlags() throws Exception {
        var model =
                new HarnessProbeFixture.ProbeModel(
                        (messages, number) ->
                                switch (number) {
                                    case 1 ->
                                            Flux.error(
                                                    new IllegalStateException(
                                                            "context_length_exceeded"));
                                    case 2 -> answer("- EMERGENCY_MEMORY");
                                    case 3 -> answer("EMERGENCY_SUMMARY");
                                    default -> answer("ACK");
                                });
        var messages = new ArrayList<Msg>();
        for (int i = 0; i < 25; i++) messages.add(user("EARLY_" + i));
        messages.add(user("CURRENT"));
        try (var agent =
                builder(model, root)
                        .compaction(compaction(10000))
                        .disableToolResultEviction()
                        .build()) {
            agent.call(messages, context("u", "s")).block(Duration.ofSeconds(5));
            assertThat(model.requests).hasSize(4);
            assertThat(text(model.requests.get(1))).contains("Extract NEW memories");
            assertThat(text(model.requests.getLast()))
                    .contains("AUTHORITY_SENTINEL", "EMERGENCY_SUMMARY");
            assertThat(files())
                    .anySatisfy(path -> assertThat(read(path)).contains("EMERGENCY_MEMORY"));
        }
    }

    /**
     * @param count 小规模触发条数
     * @return 禁止文件Flush／卸载的原生压缩配置，确保独立验证摘要成本
     */
    private static CompactionConfig compaction(int count) {
        return CompactionConfig.builder()
                .triggerMessages(count)
                .triggerTokens(100000)
                .keepMessages(1)
                .keepTokens(0)
                .flushBeforeCompact(false)
                .offloadBeforeCompact(false)
                .summaryPrompt("SUMMARY_PROBE {messages}")
                .build();
    }

    /**
     * @return JUnit隔离目录内的普通文件；流必须关闭，不扫描项目或用户目录
     * @throws Exception 无法遍历临时目录
     */
    private List<Path> files() throws Exception {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    /**
     * @param path 仅来自临时目录遍历的文件
     * @return 合成文件正文供断言
     * @throws IllegalStateException 本地夹具文件不可读
     */
    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("PROBE_FILE_UNREADABLE");
        }
    }

    /** 只返回合成大文本的白名单工具，不读取真实附件或网络。 */
    public static final class LargeTool {
        static final String BODY = "LARGE_DOCUMENT_SENTINEL_".repeat(100);

        /**
         * @return 合成工具结果，交给SDK原生卸载中间件处理
         */
        @Tool(name = "large_document", description = "Return an offline synthetic document.")
        public String load() {
            return BODY;
        }
    }
}
