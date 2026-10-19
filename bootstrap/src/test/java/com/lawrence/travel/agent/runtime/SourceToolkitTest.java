package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.document.SourceReadService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.ReasoningInput;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实Harness原生卸载组件配合本地应用替身；无任何模型或网络服务调用。 */
class SourceToolkitTest {
    @TempDir Path root;

    /** 原生卸载先落权威端口，保留toolCallId，生成可用的受限read_file逻辑入口。 */
    @Test void nativeEvictionPersistsAndPreservesCorrelation() {
        var service = mock(SourceReadService.class); var saved = new AtomicReference<String>();
        when(service.offload(any(), anyLong(), anyString(), anyString())).thenAnswer(c -> { saved.set(c.getArgument(3)); return c.getArgument(2); });
        var toolkit = new SourceToolkit(service, UUID.randomUUID(), 1, null, null);
        var model = new HarnessProbeFixture.ProbeModel((messages, number) -> Flux.empty());
        var block = ToolResultBlock.builder().id("call-proof").name("SearchDocument")
                .output(List.of(TextBlock.builder().text("中文原文".repeat(2000)).build())).build();
        var input = new ReasoningInput(List.of(Msg.builder().role(MsgRole.TOOL).content(List.of(block)).build()), List.of(), null);
        var captured = new AtomicReference<ReasoningInput>();
        try (var agent = RestrictedHarnessFactory.create(model, toolkit, root, "coordinator")) {
            toolkit.eviction().onReasoning(agent.getDelegate(), RuntimeContext.empty(), input, next -> { captured.set(next); return Flux.empty(); }).blockLast(Duration.ofSeconds(5));
            var result = (ToolResultBlock) captured.get().messages().getFirst().getContent().getFirst();
            assertThat(result.getId()).isEqualTo("call-proof");
            assertThat(result.getOutput().toString()).contains("/work/coordinator/call-proof-", "read_file");
            assertThat(result.getOutput().toString().length()).isLessThan(2000);
            assertThat(saved.get()).hasSize(8000); toolkit.check();
        }
    }

    /** 原生卸载失败本会保留全文；应用失败关闭必须阻止下一次模型发送。 */
    @Test void persistenceFailureCannotSilentlySendFullResult() {
        var service = mock(SourceReadService.class);
        when(service.offload(any(), anyLong(), anyString(), anyString())).thenThrow(new IllegalStateException("SYNTHETIC_UNAVAILABLE"));
        var toolkit = new SourceToolkit(service, UUID.randomUUID(), 1, null, null);
        var model = new HarnessProbeFixture.ProbeModel((messages, number) -> Flux.empty());
        var block = ToolResultBlock.builder().id("call-proof").name("SearchDocument").output(List.of(TextBlock.builder().text("x".repeat(8000)).build())).build();
        var input = new ReasoningInput(List.of(Msg.builder().role(MsgRole.TOOL).content(List.of(block)).build()), List.of(), null);
        try (var agent = RestrictedHarnessFactory.create(model, toolkit, root, "coordinator")) {
            toolkit.eviction().onReasoning(agent.getDelegate(), RuntimeContext.empty(), input, next -> Flux.empty()).blockLast(Duration.ofSeconds(5));
            assertThatThrownBy(toolkit::check).hasMessage("SOURCE_READ_REJECTED");
        }
    }

    /** 白名单只有资料工具及逻辑分页读取，禁止通用shell、任意文件和隐式后台工具。 */
    @Test void exposesOnlyExplicitSourceTools() {
        var toolkit = new SourceToolkit(mock(SourceReadService.class), UUID.randomUUID(), 1, null, null);
        var model = new HarnessProbeFixture.ProbeModel((messages, number) -> Flux.empty());
        try (var agent = RestrictedHarnessFactory.create(model, toolkit, root, "coordinator")) {
            assertThat(agent.getToolkit().getToolNames()).containsExactlyInAnyOrder("ListDocuments", "SearchDocument", "ReadDocumentSection", "ReadConversationSource", "read_file");
        }
    }
}
