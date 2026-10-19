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
    /** 结果Future取消不代表上下文／框架执行退出，调用方close保持运行容量直到实际清理结束。 */
    @Test void invocationCloseWaitsForActualContextExecutionExit() throws Exception {
        var sessions=org.mockito.Mockito.mock(com.lawrence.travel.application.memory.SessionContextService.class);
        var admission=org.mockito.Mockito.mock(com.lawrence.travel.application.execution.ModelCallAdmission.class);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var closed=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(sessions.prepare(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyBoolean(),org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            entered.countDown(); if(!release.await(3,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("LOCAL_CONTEXT_TIMEOUT");
            return new com.lawrence.travel.application.memory.SessionContext.Prepared(null,List.of(),null,0);
        });
        var runtime=new HarnessAgentRuntimeAdapter(admission,Map.of("coordinator","local-model"),() -> "SYNTHETIC_LOCAL_ONLY",root,(run,role) -> new Toolkit(),null,new com.lawrence.travel.infrastructure.interaction.InteractionJson(new com.lawrence.travel.infrastructure.travel.TravelJson()),sessions,SafetyGate.local());
        var call=runtime.startContextual(java.util.UUID.randomUUID(),"coordinator","当前输入",new com.lawrence.travel.application.memory.AgentContext.Basis(0,null));
        assertThat(entered.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();var closer=Thread.ofVirtual().start(() -> { call.close();closed.countDown(); });
        try { assertThat(closed.await(100,java.util.concurrent.TimeUnit.MILLISECONDS)).isFalse(); }
        finally { release.countDown(); }
        assertThat(closed.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();closer.join();org.mockito.Mockito.verifyNoInteractions(admission);
    }
    @TempDir Path root;
    /** @param text 本地消息 @param role 原角色 @return 无外部权限的SDK消息 */
    private Msg message(String text,MsgRole role) { return Msg.builder().name(role==MsgRole.ASSISTANT?"assistant":"user").role(role).content(TextBlock.builder().text(text).build()).build(); }
    /** @param text 最终回复 @return 框架结构化工具回应，不发网络 */
    private Flux<ChatResponse> answer(String text) { return Flux.just(ChatResponse.builder().id("local").content(List.of(ToolUseBlock.builder().id("reply").name("generate_response").input(Map.of("response",Map.of("answer",text))).content(new com.lawrence.travel.infrastructure.travel.TravelJson().encode(Map.of("response",Map.of("answer",text)))).build())).build()); }
    /** 明确零用量超限仅恢复一次，用新实例避免失败历史重复，保留真实助手角色。 */
    @Test void contextOverflowRecoversOnceWithFreshHarness() {
        var restores=new AtomicInteger(); var model=new HarnessProbeFixture.ProbeModel((messages,n) -> n==1?Flux.error(new HttpTransportException("MODEL_CONTEXT_LIMIT_SAFE")):answer("已处理"));
        String result=ControlledHarnessCall.execute(() -> RestrictedHarnessFactory.create(model,new Toolkit(),root.resolve("a"+restores.get()),"coordinator"),
                () -> List.of(message("前文",MsgRole.ASSISTANT),message("本轮",MsgRole.USER)),() -> { restores.incrementAndGet(); return List.of(message("压缩后本轮",MsgRole.USER)); },() -> { },SafetyGate.local(),() -> false,agent -> { });
        assertThat(result).isEqualTo("已处理"); assertThat(model.requests).hasSize(2); assertThat(restores.get()).isEqualTo(1); assertThat(model.requests.getFirst()).anyMatch(m -> m.getRole()==MsgRole.ASSISTANT);
    }
    /** 未知用量、权限和网络失败不能触发压缩恢复。 */
    @Test void uncertainOrUnrelatedFailureNeverRetries() {
        for(String code:List.of("MODEL_CONTEXT_LIMIT","MODEL_ACCESS_DENIED","MODEL_TRANSPORT_FAILED")) {
            var count=new AtomicInteger(); var model=new HarnessProbeFixture.ProbeModel((messages,n) -> Flux.error(new HttpTransportException(code)));
            assertThatThrownBy(() -> ControlledHarnessCall.execute(() -> RestrictedHarnessFactory.create(model,new Toolkit(),root,"coordinator"),() -> List.of(message("本轮",MsgRole.USER)),() -> { count.incrementAndGet(); return List.of(); },() -> { },SafetyGate.local(),() -> false,agent -> { })).isInstanceOf(RuntimeException.class);
            assertThat(model.requests).hasSize(1); assertThat(count.get()).isZero();
        }
    }
    /** 重复超限最多两个主请求，不进入Harness默认Flush／无限重试。 */
    @Test void repeatedOverflowStopsAfterOneRecovery() {
        var model=new HarnessProbeFixture.ProbeModel((messages,n) -> Flux.error(new HttpTransportException("MODEL_CONTEXT_LIMIT_SAFE")));
        assertThatThrownBy(() -> ControlledHarnessCall.execute(() -> RestrictedHarnessFactory.create(model,new Toolkit(),root,"coordinator"),() -> List.of(message("本轮",MsgRole.USER)),() -> List.of(message("恢复",MsgRole.USER)),() -> { },SafetyGate.local(),() -> false,agent -> { })).isInstanceOf(RuntimeException.class); assertThat(model.requests).hasSize(2);
    }
    /** 结构化成功也要检查完整正文，拒绝内容不能先返回用户。 */
    @Test void unsafeFinalOutputNeverBecomesSuccessfulResult() {
        var model=new HarnessProbeFixture.ProbeModel((messages,n) -> answer("<script>unsafe</script>"));
        assertThatThrownBy(() -> ControlledHarnessCall.execute(() -> RestrictedHarnessFactory.create(model,new Toolkit(),root,"coordinator"),() -> List.of(message("本轮",MsgRole.USER)),List::of,() -> { },SafetyGate.local(),() -> false,agent -> { })).isInstanceOf(SafetyGate.Failure.class); assertThat(model.requests).hasSize(1);
    }
    /** 一次工具安全拒绝使整个调用关闭，不允许改写参数后继续真实工具。 */
    @Test void toolRejectionPoisonsOnlyCurrentInvocation() {
        var toolkit=new SafetyToolkit(); var result=SafetyGate.local().check("TOOL_INPUT","javascript:blocked"); toolkit.reject(new SafetyGate.Failure(result));
        assertThatThrownBy(toolkit::check).isInstanceOf(SafetyGate.Failure.class); new SafetyToolkit().check();
    }
}
