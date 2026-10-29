package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.safety.SafetyGate;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用真实Harness delegate与本地Model，验证恢复、消息角色与安全展示边界。 */
class ControlledHarnessCallTest {
    /** 完整合法JSON正文可进入同一业务解码器，保留原字段，不靠框架元数据判定业务有效性。 */
    @Test
    void completeBusinessJsonTextUsesStrictApplicationCodec() {
        var codec =
                new com.lawrence.travel.infrastructure.interaction.InteractionJson(
                        new com.lawrence.travel.infrastructure.travel.TravelJson());
        var expected =
                new com.lawrence.travel.domain.interaction.DialogueIntent.Result(
                        List.of(
                                new com.lawrence.travel.domain.interaction.DialogueIntent.Intent(
                                        com.lawrence.travel.domain.interaction.DialogueIntent.Kind
                                                .PLANNING,
                                        com.lawrence.travel.domain.interaction.DialogueIntent.Action
                                                .SUPPLEMENT,
                                        "北京")),
                        List.of(
                                new com.lawrence.travel.domain.interaction.DialogueIntent.Fact(
                                        "cities", "北京", "北京")),
                        "已记录",
                        "",
                        null,
                        "",
                        "",
                        "");
        String result = plainFixture(codec.encode(expected), true, true);
        assertThat(
                        codec.decode(
                                result,
                                com.lawrence.travel.domain.interaction.DialogueIntent.Result.class))
                .isEqualTo(expected);
        assertThatThrownBy(() -> plainFixture(codec.encode(expected), false, true))
                .isInstanceOf(ControlledHarnessCall.OutputFailure.class);
        assertThatThrownBy(() -> plainFixture(codec.encode(expected), true, false))
                .isInstanceOf(ControlledHarnessCall.OutputFailure.class);
    }

    /** 只解析完整对象；重复键、尾随内容、代码围栏及空对象不能被截取或补写后接受。 */
    @Test
    void textFallbackDoesNotRepairMalformedOrAmbiguousJson() {
        for (String text :
                List.of(
                        "{}",
                        "null",
                        "[]",
                        "\"text\"",
                        "说明{\"reply\":\"a\"}",
                        "{\"reply\":\"a\"}尾随",
                        "{\"reply\":\"a\"}{}",
                        "```json\n{\"reply\":\"a\"}\n```",
                        "{\"reply\":\"a\",\"reply\":\"b\"}")) {
            assertThatThrownBy(() -> plainFixture(text, true, true))
                    .isInstanceOf(ControlledHarnessCall.OutputFailure.class)
                    .hasMessage("MODEL_OUTPUT_INVALID");
        }
        assertThatThrownBy(
                        () -> plainFixture("{\"reply\":\"<script>unsafe</script>\"}", true, true))
                .isInstanceOf(SafetyGate.Failure.class);
        var codec =
                new com.lawrence.travel.infrastructure.interaction.InteractionJson(
                        new com.lawrence.travel.infrastructure.travel.TravelJson());
        String incomplete = plainFixture("{\"intents\":\"非法数组\"}", true, true);
        assertThatThrownBy(
                        () ->
                                codec.decode(
                                        incomplete,
                                        com.lawrence.travel.domain.interaction.DialogueIntent.Result
                                                .class))
                .isInstanceOf(
                        com.lawrence.travel.application.interaction.DialogueDecodeFailure.class);
    }

    /**
     * 用真实Harness接收本地纯文本返回，校验成功后仍须通过原提交前检查。
     *
     * @param text 合成返回正文
     * @param businessJson 应用是否声明JSON模式
     * @param withCodec 是否显式提供严格编解码器
     * @return 完整规范化产物；不发外部请求
     */
    private String plainFixture(String text, boolean businessJson, boolean withCodec) {
        var model =
                new HarnessProbeFixture.ProbeModel(
                        (messages, n) -> HarnessProbeFixture.answer(text));
        var checks = new AtomicInteger();
        String result =
                ControlledHarnessCall.execute(
                        () ->
                                RestrictedHarnessFactory.create(
                                        model,
                                        new Toolkit(),
                                        root.resolve(java.util.UUID.randomUUID().toString()),
                                        "coordinator",
                                        businessJson),
                        () -> List.of(message("本地合成输入", MsgRole.USER)),
                        () -> {
                            throw new AssertionError("NO_RETRY");
                        },
                        checks::incrementAndGet,
                        SafetyGate.local(),
                        () -> false,
                        agent -> {},
                        businessJson,
                        withCodec
                                ? new com.lawrence.travel.infrastructure.interaction
                                        .InteractionJson(
                                        new com.lawrence.travel.infrastructure.travel.TravelJson())
                                : null);
        assertThat(checks).hasValue(1);
        assertThat(model.requests).hasSize(1);
        return result;
    }

    /** 框架返回普通文本时不自动提取JSON，固定错误码指出缺少结构化数据。 */
    @Test
    void unstructuredReplyIsRejectedWithoutReadingItsBodyIntoDiagnostics() {
        var model =
                new HarnessProbeFixture.ProbeModel(
                        (messages, n) -> HarnessProbeFixture.answer("SYNTHETIC_PRIVATE_BODY"));
        assertThatThrownBy(
                        () ->
                                ControlledHarnessCall.execute(
                                        () ->
                                                RestrictedHarnessFactory.create(
                                                        model,
                                                        new Toolkit(),
                                                        root,
                                                        "coordinator",
                                                        true),
                                        () -> List.of(message("本地合成输入", MsgRole.USER)),
                                        () -> {
                                            throw new AssertionError("NO_RECOVERY");
                                        },
                                        () -> {},
                                        SafetyGate.local(),
                                        () -> false,
                                        agent -> {},
                                        true))
                .isInstanceOf(ControlledHarnessCall.OutputFailure.class)
                .hasMessage("MODEL_OUTPUT_INVALID")
                .satisfies(
                        failure ->
                                assertThat(
                                                ((ControlledHarnessCall.OutputFailure) failure)
                                                        .diagnosticCode())
                                        .isEqualTo("MODEL_OUTPUT_UNSTRUCTURED"));
    }

    /** 空对象和混合封装继续拒绝，只增加固定失败原因，不将无效输出放行。 */
    @Test
    void invalidEnvelopeDiagnosticsDoNotContainValues() {
        assertThatThrownBy(() -> businessFixture(Map.of(), true))
                .isInstanceOf(ControlledHarnessCall.OutputFailure.class)
                .satisfies(
                        failure ->
                                assertThat(
                                                ((ControlledHarnessCall.OutputFailure) failure)
                                                        .diagnosticCode())
                                        .isEqualTo("MODEL_OUTPUT_EMPTY"));
        assertThatThrownBy(
                        () ->
                                businessFixture(
                                        Map.of("answer", "SYNTHETIC_PRIVATE", "facts", List.of()),
                                        true))
                .isInstanceOf(ControlledHarnessCall.OutputFailure.class)
                .hasMessage("MODEL_OUTPUT_INVALID")
                .satisfies(
                        failure ->
                                assertThat(
                                                ((ControlledHarnessCall.OutputFailure) failure)
                                                        .diagnosticCode())
                                        .isEqualTo("MODEL_OUTPUT_ENVELOPE"));
    }

    /** 复现缺少answer的业务对象在旧封装中失败；业务模式保留原字段并交给严格业务解码。 */
    @Test
    void directBusinessJsonKeepsFactsAndRequiresExplicitMode() {
        var data =
                new java.util.LinkedHashMap<String, Object>(
                        Map.of(
                                "intents",
                                List.of(
                                        Map.of(
                                                "kind",
                                                "PLANNING",
                                                "action",
                                                "START_PLAN",
                                                "quote",
                                                "开始规划北京旅行")),
                                "facts",
                                List.of(Map.of("field", "cities", "value", "北京", "quote", "北京")),
                                "reply",
                                "已记录北京",
                                "clarification",
                                "",
                                "category",
                                "",
                                "value",
                                "",
                                "city",
                                "",
                                "resolvedInputs",
                                List.of(),
                                "advice",
                                List.of()));
        data.put("targetId", null);
        assertThatThrownBy(() -> businessFixture(data, false))
                .hasStackTraceContaining("RestrictedHarnessFactory$StructuredReply");
        String result = businessFixture(data, true);
        var decoded =
                new com.lawrence.travel.infrastructure.interaction.InteractionJson(
                                new com.lawrence.travel.infrastructure.travel.TravelJson())
                        .decode(
                                result,
                                com.lawrence.travel.domain.interaction.DialogueIntent.Result.class);
        assertThat(decoded.facts().getFirst().value()).isEqualTo("北京");
        assertThat(decoded.intents().getFirst().action())
                .isEqualTo(com.lawrence.travel.domain.interaction.DialogueIntent.Action.START_PLAN);
        assertThat(decoded.reply()).isEqualTo("已记录北京");
    }

    /** 只识别完整的单层response封装，不把answer对象、空答案或空对象当成功。 */
    @Test
    void businessModeRejectsEmptyAndMalformedEnvelopes() {
        assertThat(businessFixture(Map.of("response", Map.of("answer", "合法封装")), true))
                .isEqualTo("合法封装");
        for (Map<String, Object> data :
                List.of(
                        Map.<String, Object>of(),
                        Map.<String, Object>of("answer", ""),
                        Map.<String, Object>of("answer", Map.of("reply", "不能伪装字符串")),
                        Map.<String, Object>of("answer", "{}", "facts", List.of("不能丢弃的字段")))) {
            assertThatThrownBy(() -> businessFixture(data, true))
                    .hasMessageContaining("MODEL_OUTPUT_INVALID");
        }
    }

    /** 原业务类型校验和输出安全检查仍然生效，适配不修补非法业务字段。 */
    @Test
    void directJsonStillRequiresBusinessAndSafetyValidation() {
        String wrong = businessFixture(Map.of("intents", "不是数组"), true);
        assertThatThrownBy(
                        () ->
                                new com.lawrence.travel.infrastructure.interaction.InteractionJson(
                                                new com.lawrence.travel.infrastructure.travel
                                                        .TravelJson())
                                        .decode(
                                                wrong,
                                                com.lawrence.travel.domain.interaction
                                                        .DialogueIntent.Result.class))
                .isInstanceOf(
                        com.lawrence.travel.application.interaction.DialogueDecodeFailure.class);
        assertThatThrownBy(() -> businessFixture(Map.of("reply", "<script>unsafe</script>"), true))
                .isInstanceOf(SafetyGate.Failure.class);
    }

    /**
     * 通过真实Harness和本地Model返回指定结构，不发网络；成功产物只消费一次请求。
     *
     * @param data 模拟的最终结构化对象
     * @param businessJson 是否由应用启用业务JSON适配
     * @return 安全检查后的原产物
     */
    private String businessFixture(Map<String, Object> data, boolean businessJson) {
        var model =
                new HarnessProbeFixture.ProbeModel(
                        (messages, n) ->
                                Flux.just(
                                        ChatResponse.builder()
                                                .id("local-json")
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("json-reply")
                                                                        .name("generate_response")
                                                                        .input(
                                                                                Map.of(
                                                                                        "response",
                                                                                        data))
                                                                        .content(
                                                                                new com.lawrence
                                                                                                .travel
                                                                                                .infrastructure
                                                                                                .travel
                                                                                                .TravelJson()
                                                                                        .encode(
                                                                                                Map
                                                                                                        .of(
                                                                                                                "response",
                                                                                                                data)))
                                                                        .build()))
                                                .build()));
        String result =
                ControlledHarnessCall.execute(
                        () ->
                                RestrictedHarnessFactory.create(
                                        model,
                                        new Toolkit(),
                                        root.resolve(java.util.UUID.randomUUID().toString()),
                                        "coordinator",
                                        businessJson),
                        () -> List.of(message("返回本地合成业务结果", MsgRole.USER)),
                        () -> {
                            throw new AssertionError("LOCAL_JSON_MUST_NOT_RETRY");
                        },
                        () -> {},
                        SafetyGate.local(),
                        () -> false,
                        agent -> {},
                        businessJson);
        assertThat(model.requests).hasSize(1);
        if (businessJson) {
            assertThat(HarnessProbeFixture.text(model.requests.getFirst()))
                    .contains("必须调用该工具", "完整业务对象放入response参数", "不能只在回复正文中输出JSON");
            assertThat(model.schemas.getFirst())
                    .anySatisfy(
                            schema -> assertThat(schema.getName()).isEqualTo("generate_response"));
        }
        return result;
    }

    /** 仅旧历史带可卸载标记；本轮权威区完整保留，审核单条上下文也不能被当成历史。 */
    @Test
    void sessionMappingNeverMarksCurrentAuthorityAsOptional() {
        var old =
                new com.lawrence.travel.application.memory.SessionContext.Message(
                        java.util.UUID.randomUUID(),
                        1,
                        com.lawrence.travel.application.memory.SessionContext.Role.USER,
                        "历史",
                        "hash",
                        "");
        var current =
                new com.lawrence.travel.application.memory.SessionContext.Message(
                        java.util.UUID.randomUUID(),
                        Long.MAX_VALUE,
                        com.lawrence.travel.application.memory.SessionContext.Role.USER,
                        "完整权威区及请求",
                        "hash",
                        "");
        var mapped =
                HarnessAgentRuntimeAdapter.sessionMessages(
                        new com.lawrence.travel.application.memory.SessionContext.Prepared(
                                null, List.of(old, current), null, 0));
        assertThat(
                        mapped.getFirst()
                                .getMetadata()
                                .get(SourceResultBudgetMiddleware.OPTIONAL_HISTORY))
                .isEqualTo(true);
        assertThat(
                        mapped.getLast()
                                .getMetadata()
                                .get(SourceResultBudgetMiddleware.OPTIONAL_HISTORY))
                .isEqualTo(false);
        assertThat(mapped.getLast().getTextContent()).isEqualTo(current.text());
        assertThat(
                        HarnessAgentRuntimeAdapter.sessionMessages(
                                        new com.lawrence.travel.application.memory.SessionContext
                                                .Prepared(null, List.of(current), null, 0))
                                .getFirst()
                                .getMetadata()
                                .get(SourceResultBudgetMiddleware.OPTIONAL_HISTORY))
                .isEqualTo(false);
    }

    /** 类型诊断保留可定位信息但不保留原异常引用或消息，防止未来日志回显模型内容。 */
    @Test
    void sanitizedFailureNeverRetainsOriginalMessageOrCause() {
        var original =
                new IllegalArgumentException(
                        "SYNTHETIC_PRIVATE_BODY", new RuntimeException("SYNTHETIC_PRIVATE_CAUSE"));
        var safe = new SanitizedAgentFailure("MODEL_EXECUTION_FAILED", original);
        assertThat(safe.getMessage()).isEqualTo("MODEL_EXECUTION_FAILED");
        assertThat(safe.getCause()).isNull();
        assertThat(safe.getSuppressed()).isEmpty();
        assertThat(safe.exceptionTypes())
                .containsExactly(
                        "java.lang.IllegalArgumentException", "java.lang.RuntimeException");
        assertThat(safe.toString()).doesNotContain("SYNTHETIC_PRIVATE");
    }

    /** 结果Future取消不代表上下文／框架执行退出，调用方close保持运行容量直到实际清理结束。 */
    @Test
    void invocationCloseWaitsForActualContextExecutionExit() throws Exception {
        var sessions =
                org.mockito.Mockito.mock(
                        com.lawrence.travel.application.memory.SessionContextService.class);
        var admission =
                org.mockito.Mockito.mock(
                        com.lawrence.travel.application.execution.ModelCallAdmission.class);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var closed = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(
                        sessions.prepare(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.anyBoolean(),
                                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        invocation -> {
                            entered.countDown();
                            if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                                throw new IllegalStateException("LOCAL_CONTEXT_TIMEOUT");
                            return new com.lawrence.travel.application.memory.SessionContext
                                    .Prepared(null, List.of(), null, 0);
                        });
        var runtime =
                new HarnessAgentRuntimeAdapter(
                        admission,
                        Map.of("coordinator", "local-model"),
                        () -> "SYNTHETIC_LOCAL_ONLY",
                        root,
                        (run, role) -> new Toolkit(),
                        null,
                        new com.lawrence.travel.infrastructure.interaction.InteractionJson(
                                new com.lawrence.travel.infrastructure.travel.TravelJson()),
                        sessions,
                        SafetyGate.local());
        var call =
                runtime.startContextual(
                        java.util.UUID.randomUUID(),
                        "coordinator",
                        "当前输入",
                        new com.lawrence.travel.application.memory.AgentContext.Basis(0, null));
        assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var closer =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    call.close();
                                    closed.countDown();
                                });
        try {
            assertThat(closed.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            release.countDown();
        }
        assertThat(closed.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        closer.join();
        org.mockito.Mockito.verifyNoInteractions(admission);
    }

    @TempDir Path root;

    /**
     * @param text 本地消息
     * @param role 原角色
     * @return 无外部权限的SDK消息
     */
    private Msg message(String text, MsgRole role) {
        return Msg.builder()
                .name(role == MsgRole.ASSISTANT ? "assistant" : "user")
                .role(role)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    /**
     * @param text 最终回复
     * @return 框架结构化工具回应，不发网络
     */
    private Flux<ChatResponse> answer(String text) {
        return Flux.just(
                ChatResponse.builder()
                        .id("local")
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("reply")
                                                .name("generate_response")
                                                .input(Map.of("response", Map.of("answer", text)))
                                                .content(
                                                        new com.lawrence.travel.infrastructure
                                                                        .travel.TravelJson()
                                                                .encode(
                                                                        Map.of(
                                                                                "response",
                                                                                Map.of(
                                                                                        "answer",
                                                                                        text))))
                                                .build()))
                        .build());
    }

    /** 明确零用量超限仅恢复一次，用新实例避免失败历史重复，保留真实助手角色。 */
    @Test
    void contextOverflowRecoversOnceWithFreshHarness() {
        var restores = new AtomicInteger();
        var model =
                new HarnessProbeFixture.ProbeModel(
                        (messages, n) ->
                                n == 1
                                        ? Flux.error(
                                                new HttpTransportException(
                                                        "MODEL_CONTEXT_LIMIT_SAFE"))
                                        : answer("已处理"));
        String result =
                ControlledHarnessCall.execute(
                        () ->
                                RestrictedHarnessFactory.create(
                                        model,
                                        new Toolkit(),
                                        root.resolve("a" + restores.get()),
                                        "coordinator"),
                        () ->
                                List.of(
                                        message("前文", MsgRole.ASSISTANT),
                                        message("本轮", MsgRole.USER)),
                        () -> {
                            restores.incrementAndGet();
                            return List.of(message("压缩后本轮", MsgRole.USER));
                        },
                        () -> {},
                        SafetyGate.local(),
                        () -> false,
                        agent -> {});
        assertThat(result).isEqualTo("已处理");
        assertThat(model.requests).hasSize(2);
        assertThat(restores.get()).isEqualTo(1);
        assertThat(model.requests.getFirst()).anyMatch(m -> m.getRole() == MsgRole.ASSISTANT);
    }

    /** 未知用量、权限和网络失败不能触发压缩恢复。 */
    @Test
    void uncertainOrUnrelatedFailureNeverRetries() {
        for (String code :
                List.of("MODEL_CONTEXT_LIMIT", "MODEL_ACCESS_DENIED", "MODEL_TRANSPORT_FAILED")) {
            var count = new AtomicInteger();
            var model =
                    new HarnessProbeFixture.ProbeModel(
                            (messages, n) -> Flux.error(new HttpTransportException(code)));
            assertThatThrownBy(
                            () ->
                                    ControlledHarnessCall.execute(
                                            () ->
                                                    RestrictedHarnessFactory.create(
                                                            model,
                                                            new Toolkit(),
                                                            root,
                                                            "coordinator"),
                                            () -> List.of(message("本轮", MsgRole.USER)),
                                            () -> {
                                                count.incrementAndGet();
                                                return List.of();
                                            },
                                            () -> {},
                                            SafetyGate.local(),
                                            () -> false,
                                            agent -> {}))
                    .isInstanceOf(RuntimeException.class);
            assertThat(model.requests).hasSize(1);
            assertThat(count.get()).isZero();
        }
    }

    /** 重复超限最多两个主请求，不进入Harness默认Flush／无限重试。 */
    @Test
    void repeatedOverflowStopsAfterOneRecovery() {
        var model =
                new HarnessProbeFixture.ProbeModel(
                        (messages, n) ->
                                Flux.error(new HttpTransportException("MODEL_CONTEXT_LIMIT_SAFE")));
        assertThatThrownBy(
                        () ->
                                ControlledHarnessCall.execute(
                                        () ->
                                                RestrictedHarnessFactory.create(
                                                        model, new Toolkit(), root, "coordinator"),
                                        () -> List.of(message("本轮", MsgRole.USER)),
                                        () -> List.of(message("恢复", MsgRole.USER)),
                                        () -> {},
                                        SafetyGate.local(),
                                        () -> false,
                                        agent -> {}))
                .isInstanceOf(RuntimeException.class);
        assertThat(model.requests).hasSize(2);
    }

    /** 结构化成功也要检查完整正文，拒绝内容不能先返回用户。 */
    @Test
    void unsafeFinalOutputNeverBecomesSuccessfulResult() {
        var model =
                new HarnessProbeFixture.ProbeModel(
                        (messages, n) -> answer("<script>unsafe</script>"));
        assertThatThrownBy(
                        () ->
                                ControlledHarnessCall.execute(
                                        () ->
                                                RestrictedHarnessFactory.create(
                                                        model, new Toolkit(), root, "coordinator"),
                                        () -> List.of(message("本轮", MsgRole.USER)),
                                        List::of,
                                        () -> {},
                                        SafetyGate.local(),
                                        () -> false,
                                        agent -> {}))
                .isInstanceOf(SafetyGate.Failure.class);
        assertThat(model.requests).hasSize(1);
    }

    /** 一次工具安全拒绝使整个调用关闭，不允许改写参数后继续真实工具。 */
    @Test
    void toolRejectionPoisonsOnlyCurrentInvocation() {
        var toolkit = new SafetyToolkit();
        var result = SafetyGate.local().check("TOOL_INPUT", "javascript:blocked");
        toolkit.reject(new SafetyGate.Failure(result));
        assertThatThrownBy(toolkit::check).isInstanceOf(SafetyGate.Failure.class);
        new SafetyToolkit().check();
    }
}
