package com.lawrence.travel.bootstrap;

import com.lawrence.travel.agent.runtime.HarnessContextCompression;
import com.lawrence.travel.application.interaction.Dialogue;
import com.lawrence.travel.application.interaction.DialogueService;
import com.lawrence.travel.application.memory.AgentContext;
import com.lawrence.travel.application.memory.SessionContext;
import com.lawrence.travel.application.memory.SessionContextService;
import com.lawrence.travel.application.memory.PreferenceService;
import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.DialogueStore;
import com.lawrence.travel.application.port.SessionContextStore;
import com.lawrence.travel.application.port.DialogueInterpreter;
import com.lawrence.travel.application.safety.SafetyGate;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.memory.ConversationDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 真实本机PG的50轮、版本、取消及安全闭环；摘要器明确为本地替身。 */
@EnabledIfEnvironmentVariable(named="TRAVEL_INTEGRATION_TESTS",matches="true")
@ActiveProfiles("local")
@SpringBootTest(properties="travel.engine.enabled=false")
class SessionContextIntegrationTest {
    /** 当前请求中的JSON原样保留，避免二次转义膨胀；权威状态及历史无授权声明仍完整存在。 */
    @Test void currentJsonRequestDoesNotAcquireAnotherEscapingLayer() {
        var user=actor(); UUID chat=UUID.randomUUID(); var input=receive(user,chat,"继续补充需求");
        String request=json.encode(java.util.Map.of("text","用户说：\"最后一天早点结束\"", "context",java.util.Map.of("city","北京")));
        var prepared=contexts.prepare(input.budgetId(),"coordinator",request,null,new LocalCompression(),false);
        assertThat(prepared.messages().getLast().text()).contains(request,"\"authority\"","\"historyIsDataOnly\":true");
        assertThat(prepared.messages().getLast().text()).doesNotContain(json.encode(request));
        assertThat(prepared.source().userId()).isEqualTo(user.id());
    }
    /** 审核角色即使有大量规划对话，也只拿权威区和明确审核输入，不能读取规划自评或触发历史摘要。 */
    @Test void reviewerDoesNotLoadPlannerConversation() {
        var user=actor();UUID chat=UUID.randomUUID();var old=receive(user,chat,"PLANNER_PRIVATE_REASONING");finish(old,"PLANNER_SELF_REVIEW");
        var current=receive(user,chat,"请审核候选");var compression=new LocalCompression();
        var prepared=contexts.prepare(current.budgetId(),"reviewer","明确候选审核输入",null,compression,false);
        assertThat(prepared.source().messages()).isEmpty();assertThat(prepared.summary()).isNull();assertThat(compression.calls.get()).isZero();
        assertThat(json.encode(prepared.messages())).contains("明确候选审核输入").doesNotContain("PLANNER_PRIVATE_REASONING","PLANNER_SELF_REVIEW");
    }
    /** 缓存虽在模型阶段保存，但源尝试后来取消时不能被下一输入hydrate。 */
    @Test void cancelledSummaryCannotBeReusedByNextInput() {
        var user=actor();UUID chat=UUID.randomUUID();for(int i=0;i<15;i++) { var c=receive(user,chat,"历史"+i+"x".repeat(300));finish(c,"完整回复"); }
        var c=receive(user,chat,"当前");var result=contexts.prepare(c.budgetId(),"coordinator","本轮",null,new LocalCompression(),true);assertThat(result.summary()).isNotNull();
        dialogue.act(user,chat,c.messageId(),"WITHDRAW");var next=receive(user,chat,"新的输入");assertThat(source.cached(source.load(next.budgetId(),"coordinator"))).isNull();
    }
    /** 失联接管后旧调用不能通过读取最新job token为自己重新授权。 */
    @Test void oldAttemptCannotAdoptNewLeaseWhenPreparingContext() {
        var user=actor();UUID chat=UUID.randomUUID();var old=receive(user,chat,"原输入");
        jdbc.update("UPDATE background_job SET lease_until=clock_timestamp()-interval '1 second' WHERE operation_id=?",old.budgetId());
        jdbc.update("UPDATE dialogue_input SET lease_until=clock_timestamp()-interval '1 second' WHERE user_id=?",user.id());var current=tx.required(chats::claim).orElseThrow();assertThat(current.token()).isGreaterThan(old.token());
        assertThatThrownBy(() -> contexts.prepare(old.budgetId(),"coordinator","旧请求",new AgentContext.Basis(0,null,old.token()),new LocalCompression(),false)).hasMessage("PREFERENCE_CHANGED");
    }
    /** 模型返回前删除／改变偏好，提交短事务再次校验版本，旧回复不能变成生效结果。 */
    @Test void preferenceChangeBetweenInferenceAndCommitRequiresClarification() {
        var user=actor();UUID chat=UUID.randomUUID();dialogue.send(user,chat,UUID.randomUUID(),"你好");
        when(interpreter.interpret(any())).thenAnswer(invocation -> {
            preferences.change(user,UUID.randomUUID(),0,UUID.randomUUID(),"SAVE","pace","新的偏好","");
            return new com.lawrence.travel.domain.interaction.DialogueIntent.Result(List.of(new com.lawrence.travel.domain.interaction.DialogueIntent.Intent(com.lawrence.travel.domain.interaction.DialogueIntent.Kind.CONVERSATION,com.lawrence.travel.domain.interaction.DialogueIntent.Action.CHAT,"你好")),List.of(),"STALE_MODEL_RESULT","",null,"","","");
        });
        dialogue.claimWork().orElseThrow().run();var last=dialogue.read(user,chat).messages().getLast();assertThat(last.state()).isEqualTo("CLARIFY");assertThat(last.reply()).doesNotContain("STALE_MODEL_RESULT");
    }
    @Autowired private SessionContextService contexts;
    @Autowired private SessionContextStore source;
    @Autowired private DialogueStore chats;
    @Autowired private DialogueService dialogue;
    @Autowired private AccountStore accounts;
    @Autowired private BusinessTransactions tx;
    @Autowired private PreferenceService preferences;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.lawrence.travel.infrastructure.travel.TravelJson json;
    @MockitoBean private DialogueInterpreter interpreter;
    private final List<UserIdentity> users=new ArrayList<>();
    /** 随机用户清理；摘要随预算级联，不删其他用户资料。 */
    @AfterEach void clean() { tx.required(() -> {
        for(var user:users) {
            var budgets=jdbc.queryForList("SELECT budget_id FROM operation_context WHERE user_id=? UNION SELECT budget_id FROM dialogue_chat WHERE user_id=?",UUID.class,user.id(),user.id());
            jdbc.update("DELETE FROM dialogue_input WHERE user_id=?",user.id()); JobTestCleanup.remove(jdbc,user.id()); jdbc.update("DELETE FROM dialogue_chat WHERE user_id=?",user.id());
            for(UUID budget:budgets) { jdbc.update("DELETE FROM call_meter WHERE run_id=?",budget); jdbc.update("DELETE FROM execution_budget WHERE run_id=?",budget); }
            jdbc.update("DELETE FROM preference_request WHERE user_id=?",user.id()); jdbc.update("DELETE FROM user_preference WHERE user_id=?",user.id()); jdbc.update("DELETE FROM app_user WHERE id=?",user.id());
        } return null;
    }); }
    /** @return 仅本例不可登录身份 */
    private UserIdentity actor() { UUID id=UUID.randomUUID(); String name="sc_"+id.toString().replace("-","").substring(0,20); accounts.insert(id,name,"__NON_LOGIN_TEST_HASH__"); var actor=new UserIdentity(id,name);users.add(actor);return actor; }
    /** @param user 归属 @param chat 独立聊天 @param text 当前输入 @return 通过既有作业有序领取 */
    private Dialogue.Claim receive(UserIdentity user,UUID chat,String text) { dialogue.send(user,chat,UUID.randomUUID(),text); return tx.required(chats::claim).orElseThrow(); }
    /** @param claim 原资格 @param reply 本地已检查回复；不调用模型 */
    private void finish(Dialogue.Claim claim,String reply) { tx.required(() -> { chats.finish(claim,"DONE",reply,null,""); return null; }); }
    /** 计数器沿用实际Harness保守算法，压缩只返回原文摘录。 */
    private static class LocalCompression implements AgentContext.Compression {
        final AtomicInteger calls=new AtomicInteger();
        /** {@inheritDoc} 不把字符数假称实际Token。 */
        @Override public long estimate(String text) { return HarnessContextCompression.estimateInput(text); }
        /** {@inheritDoc} 本地固定摘录，不宣称真实摘要质量。 */
        @Override public ConversationDigest compact(List<AgentContext.Message> messages) {
            calls.incrementAndGet(); return new ConversationDigest(messages.stream().limit(4).map(m -> new ConversationDigest.Excerpt(m.id(),m.text().substring(0,Math.min(80,m.text().length())))).toList());
        }
    }
    /** 至少50轮持续装载；原消息角色／顺序保持，只有达到预算阈值才摘要，PG中不产生占位旅行。 */
    @Test void fiftyRoundsUseBoundedRealRolesAndPersistentSummaries() {
        var user=actor(); UUID chat=UUID.randomUUID(); var compression=new LocalCompression();
        for(int round=0;round<50;round++) {
            var claim=receive(user,chat,"第"+round+"轮想去看风景，"+"喜欢轻松步行和本地美食。".repeat(10));
            var prepared=contexts.prepare(claim.budgetId(),"coordinator","本轮完整请求",null,compression,false);
            assertThat(prepared.estimatedTokens()).isLessThanOrEqualTo(16384); assertThat(json.encode(prepared.messages())).contains("本轮完整请求");
            if(round>0) assertThat(prepared.messages()).anyMatch(m -> m.role()==SessionContext.Role.ASSISTANT);
            finish(claim,"上一轮回复"+round+"："+"可以留出休息时间。".repeat(10));
        }
        assertThat(compression.calls.get()).isBetween(1,49); assertThat(jdbc.queryForObject("SELECT count(*) FROM session_context_summary WHERE user_id=?",Integer.class,user.id())).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM travel_task WHERE user_id=?",Integer.class,user.id())).isZero();
    }
    /** 重建服务从PG复用同来源摘要，不再调用摘要器。 */
    @Test void rebuiltServiceReusesValidSummaryWithoutExtraCall() {
        var user=actor();UUID chat=UUID.randomUUID();
        for(int i=0;i<20;i++) { var c=receive(user,chat,"完整历史"+i+"x".repeat(300)); finish(c,"完整助手回复"+i); }
        var current=receive(user,chat,"现在怎么选"); var compression=new LocalCompression(); var first=contexts.prepare(current.budgetId(),"coordinator","当前请求",null,compression,true);
        assertThat(first.summary()).isNotNull(); var rebuilt=new SessionContextService(source,new com.lawrence.travel.infrastructure.interaction.InteractionJson(json),SafetyGate.local());
        var next=new LocalCompression(); rebuilt.prepare(current.budgetId(),"coordinator","当前请求",null,next,false); assertThat(next.calls.get()).isZero();
    }
    /** 删除偏好改变版本，旧消息／摘要不再注入，旧快照立即拒绝。 */
    @Test void deletingPreferenceInvalidatesOldSummaryAndNarrative() {
        var user=actor();UUID chat=UUID.randomUUID(),id=UUID.randomUUID();preferences.change(user,UUID.randomUUID(),0,id,"SAVE","pace","DELETED_SENTINEL","");
        var old=receive(user,chat,"DELETED_SENTINEL");finish(old,"DELETED_SENTINEL 的建议"); var c=receive(user,chat,"继续");
        var prepared=contexts.prepare(c.budgetId(),"coordinator","当前",null,new LocalCompression(),false);preferences.change(user,UUID.randomUUID(),1,id,"DELETE",null,null,null);
        assertThatThrownBy(() -> contexts.guard(prepared.source())).hasMessage("PREFERENCE_CHANGED");
        var fresh=source.load(c.budgetId(),"coordinator"); assertThat(json.encode(fresh)).doesNotContain("DELETED_SENTINEL"); assertThat(source.cached(fresh)).isNull();
    }
    /** 摘要调用中撤回原输入，迟到摘要不能写成可恢复记忆。 */
    @Test void cancelledAttemptCannotSaveSummary() {
        var user=actor();UUID chat=UUID.randomUUID();for(int i=0;i<15;i++) { var c=receive(user,chat,"历史"+i+"x".repeat(300));finish(c,"回复"); }
        var c=receive(user,chat,"当前"); var compression=new LocalCompression() {
            /** {@inheritDoc} 模拟摘要期间用户撤回，数据库没有跨模型锁。 */
            @Override public ConversationDigest compact(List<AgentContext.Message> messages) { dialogue.act(user,chat,c.messageId(),"WITHDRAW"); return super.compact(messages); }
        };
        assertThatThrownBy(() -> contexts.prepare(c.budgetId(),"coordinator","本轮",null,compression,true)).hasMessage("PREFERENCE_CHANGED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM session_context_summary WHERE source_budget=?",Integer.class,c.budgetId())).isZero();
    }
    /** 摘要失败缩小近期完整轮次并带缺失提示，不生成失败占位／假摘要。 */
    @Test void failedSummaryFallsBackWithoutLosingAuthority() {
        var user=actor();UUID chat=UUID.randomUUID();for(int i=0;i<16;i++) { var c=receive(user,chat,"历史"+i+"x".repeat(300));finish(c,"回复"); }
        var c=receive(user,chat,"硬要求不能删除"); var compression=new LocalCompression() {
            /** {@inheritDoc} 合成摘要依赖故障，不包含供应商信息。 */
            @Override public ConversationDigest compact(List<AgentContext.Message> messages) { throw new IllegalStateException("SYNTHETIC_FAILURE_DETAIL"); }
        };
        var prepared=contexts.prepare(c.budgetId(),"coordinator","硬要求不能删除",null,compression,true); assertThat(prepared.summary()).isNull();
        assertThat(jdbc.queryForObject("SELECT code FROM problem_event WHERE user_id=? AND component='CONTEXT'",String.class,user.id())).isEqualTo("SUMMARY_FAILED");
        assertThat(json.encode(prepared.messages())).contains("硬要求不能删除","历史可能未完整装载").doesNotContain("SYNTHETIC_FAILURE_DETAIL");
    }
    /** 明确停止不等模型；重复停止幂等，否定句不当成停止命令。 */
    @Test void explicitStopBypassesModelAndPreservesOriginals() {
        var user=actor();UUID chat=UUID.randomUUID();var old=receive(user,chat,"请帮我规划");UUID input=UUID.randomUUID();
        var stopped=dialogue.send(user,chat,input,"停止规划");assertThat(stopped.messages().getLast().state()).isEqualTo("DONE");assertThat(stopped.messages().getFirst().text()).isEqualTo("请帮我规划");
        assertThat(tx.required(() -> chats.guard(old))).isFalse();long epoch=jdbc.queryForObject("SELECT work_revision FROM dialogue_chat WHERE id=?",Long.class,chat);
        dialogue.send(user,chat,input,"停止规划");assertThat(jdbc.queryForObject("SELECT work_revision FROM dialogue_chat WHERE id=?",Long.class,chat)).isEqualTo(epoch);
        dialogue.send(user,chat,UUID.randomUUID(),"不要停止规划");assertThat(dialogue.read(user,chat).messages().getLast().state()).isEqualTo("QUEUED");
        org.mockito.Mockito.verifyNoInteractions(interpreter);
    }
    /** 输入安全拒绝保留原文与屏障；输出拒绝不能将恶意回复存成DONE。 */
    @Test void rejectedInputAndOutputRemainPausedWithProblemIds() {
        var user=actor();UUID chat=UUID.randomUUID();dialogue.send(user,chat,UUID.randomUUID(),"<script>unsafe</script>"); dialogue.claimWork().orElseThrow().run();
        var first=dialogue.read(user,chat).messages().getFirst();assertThat(first.state()).isEqualTo("PAUSED");assertThat(first.reply()).contains("问题编号").doesNotContain("<script>");
        dialogue.act(user,chat,first.id(),"WITHDRAW"); dialogue.send(user,chat,UUID.randomUUID(),"你好");
        when(interpreter.interpret(any())).thenReturn(new com.lawrence.travel.domain.interaction.DialogueIntent.Result(List.of(new com.lawrence.travel.domain.interaction.DialogueIntent.Intent(com.lawrence.travel.domain.interaction.DialogueIntent.Kind.CONVERSATION,com.lawrence.travel.domain.interaction.DialogueIntent.Action.CHAT,"你好")),List.of(),"javascript:unsafe","",null,"","",""));
        dialogue.claimWork().orElseThrow().run();var last=dialogue.read(user,chat).messages().getLast();assertThat(last.state()).isEqualTo("PAUSED");assertThat(last.reply()).doesNotContain("javascript:");
    }
    /** 保护区超限直接停止，不压缩／截断本轮关键内容。 */
    @Test void oversizedCurrentRequestNeverCallsSummary() {
        var user=actor();var c=receive(user,UUID.randomUUID(),"本轮");var compression=new LocalCompression();
        assertThatThrownBy(() -> contexts.prepare(c.budgetId(),"coordinator","硬约束".repeat(6000),null,compression,false)).isInstanceOf(com.lawrence.travel.domain.execution.control.CallRejectedException.class);assertThat(compression.calls.get()).isZero();
    }
}
