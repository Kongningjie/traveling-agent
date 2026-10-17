package com.lawrence.travel.bootstrap;

import com.lawrence.travel.agent.runtime.HarnessContextCompression;
import com.lawrence.travel.application.interaction.Negotiation;
import com.lawrence.travel.application.interaction.NegotiationService;
import com.lawrence.travel.application.memory.AgentContext;
import com.lawrence.travel.application.memory.ContextAssembler;
import com.lawrence.travel.application.memory.PreferenceService;
import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.ContextStore;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.memory.ConversationDigest;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实PostgreSQL上下文、摘要、隔离和并发；外部模型只用明确的本地摘要器。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(properties = "travel.engine.enabled=false")
class ContextIntegrationTest {
    @Autowired private ContextAssembler assembler;
    @Autowired private ContextStore store;
    @Autowired private com.lawrence.travel.application.interaction.NegotiationHandler handler;
    @Autowired private com.lawrence.travel.application.port.ExecutionEngineStore engine;
    @Autowired private PreferenceService preferences;
    @Autowired private NegotiationService negotiation;
    @Autowired private AccountStore accounts;
    @Autowired private BusinessTransactions transactions;
    @Autowired private TravelJson json;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final List<UUID> users = new ArrayList<>();
    /** @param actor 身份 @param scope 归属 @param run 当前排队运行 */
    private record Fixture(UserIdentity actor, TaskScope scope, UUID run) { }
    /** 随机测试数据按FK删除，级联摘要；不重建卷，不保存控制台输出。 */
    @AfterEach void clean() { transactions.required(() -> {
        for (UUID user : users) {
            var runs = jdbc.queryForList("SELECT id FROM execution_run WHERE user_id=?", UUID.class, user); jdbc.update("UPDATE travel_task SET current_run_id=NULL,current_version_id=NULL WHERE user_id=?", user);
            jdbc.update("DELETE FROM artifact_content WHERE artifact_id IN(SELECT id FROM artifact_record WHERE user_id=?)", user);
            for (String table : List.of("artifact_record", "negotiation_card", "conversation_message", "decision_response", "decision_request", "run_event", "execution_start_request", "step_attempt", "outbox_event", "execution_step", "execution_run")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
            for (UUID run : runs) { jdbc.update("DELETE FROM call_meter WHERE run_id=?", run); jdbc.update("DELETE FROM execution_budget WHERE run_id=?", run); }
            for (String table : List.of("change_request", "travel_constraint_revision", "travel_task", "travel_idempotency")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user); jdbc.update("DELETE FROM app_user WHERE id=?", user);
        } return true;
    }); }
    /** @return 带硬要求、预算和原输入的真实任务，尚未开始模型阶段 */
    private Fixture fixture() {
        UUID user = UUID.randomUUID(); String name = "ctx_" + user.toString().replace("-", "").substring(0, 16); accounts.insert(user, name, "__NON_LOGIN_TEST_HASH__"); users.add(user); var actor = new UserIdentity(user, name);
        var facts = Map.of("cities", "北京", "startDate", "2026-11-01", "endDate", "2026-11-02", "travelers", "2", "budgetRule", "NONE", "fixedStatus", "PROVIDED", "fixedArrangements", "RESERVATION|2026-11-01T09:00:00+08:00[Asia/Shanghai]|2026-11-01T10:00:00+08:00[Asia/Shanghai]", "hardRequirements", "步行不超过3000米，每天午休，禁止自动解锁");
        var created = negotiation.create(actor, UUID.randomUUID(), "上下文本地任务", "", facts.entrySet().stream().map(e -> new Negotiation.Fact(e.getKey(), e.getValue(), null)).toList());
        UUID run = created.receipt().runId(); var hint = engine.candidates(100).stream().filter(h -> h.runId().equals(run)).findFirst().orElseThrow(); var claim = engine.claim(hint, "context-fixture").orElseThrow();
        assertThat(engine.commit(engine.prepare(claim, handler.execute(claim)))).isEqualTo(com.lawrence.travel.application.port.StageCommitPort.Result.COMMITTED);
        return new Fixture(actor, new TaskScope(user, created.taskId()), run);
    }
    /** @param f 私有任务 @param preference 接收时实际偏好版本 @param count 合成历史条数；仅准备已处理消息，不模拟模型验收成绩 */
    private void history(Fixture f, long preference, int count) { transactions.required(() -> {
        long start = jdbc.queryForObject("SELECT message_sequence FROM travel_task WHERE user_id=? AND id=? FOR UPDATE", Long.class, f.actor().id(), f.scope().taskId());
        for (int i = 1; i <= count; i++) {
            UUID id = UUID.randomUUID(); String text = "本地历史" + i + ":" + "historical-data-".repeat(60);
            var message = new Negotiation.Message(id, "CHANGE", text, List.of(), null, 0, 2, "APPLIED");
            jdbc.update("INSERT INTO conversation_message(id,user_id,task_id,sequence,request_hash,body,status,preference_revision) VALUES(?,?,?,?,?,CAST(? AS jsonb),'APPLIED',?)", id, f.actor().id(), f.scope().taskId(), start + i, json.hash(json.encode(message)), json.encode(message), preference);
        }
        jdbc.update("UPDATE travel_task SET message_sequence=? WHERE user_id=? AND id=?", start + count, f.actor().id(), f.scope().taskId()); return true;
    }); }
    /** 本地摘录器不调用真实服务；保守计数复用真实Harness适配器。 */
    private static class LocalCompression implements AgentContext.Compression {
        private final AtomicInteger calls = new AtomicInteger();
        /** {@inheritDoc} 使用同一输入估算规则。 */
        @Override public long estimate(String text) { return HarnessContextCompression.estimateInput(text); }
        /** {@inheritDoc} 返回实际消息连续摘录，不编造摘要。 */
        @Override public ConversationDigest compact(List<AgentContext.Message> messages) { calls.incrementAndGet(); var first = messages.getFirst(); return new ConversationDigest(List.of(new ConversationDigest.Excerpt(first.id(), first.text().substring(0, Math.min(80, first.text().length()))))); }
    }
    /** 长历史压缩后保护区、需求ID及版本完整；来源覆盖精确保存，模型调用不推进业务阶段。 */
    @Test void compressedContextPreservesProtectedAuthorityAndAuditableCoverage() {
        var f = fixture(); history(f, 0, 40); var local = new LocalCompression(); var source = store.load(f.run(), "planner");
        var result = assembler.prepare(f.run(), "planner", new AgentContext.Basis(2, 0L), "CURRENT_REQUEST_MUST_REMAIN", local);
        assertThat(result.summaryId()).isNotNull(); assertThat(result.prompt()).contains(source.authority(), "CURRENT_REQUEST_MUST_REMAIN", "RESERVATION", "步行不超过3000米", "每天午休", "禁止自动解锁", source.scope().taskId().toString());
        var summary = store.cached(source); assertThat(summary.covered()).hasSize(40); assertThat(summary.afterTokens()).isLessThan(summary.beforeTokens()); assertThat(summary.protectedHash()).isEqualTo(AgentContext.hash(source.authority()));
        assertThat(summary.covered().stream().map(AgentContext.MessageRef::id)).containsExactlyElementsOf(source.history().stream().map(AgentContext.Message::id).toList());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversation_summary WHERE run_id=?", Integer.class, f.run())).isEqualTo(1); assertThat(jdbc.queryForObject("SELECT count(*) FROM step_attempt WHERE run_id=?", Integer.class, f.run())).isEqualTo(1);
    }
    /** 重建服务或再读时只复用同绑定摘要，不依赖内存会话或任何工作区文件。 */
    @Test void summaryIsReusableAfterReassemblyWithoutAnotherModelCall() {
        var f = fixture(); history(f, 0, 40); var first = assembler.prepare(f.run(), "planner", new AgentContext.Basis(2, 0L), "当前请求", new LocalCompression());
        var never = new LocalCompression(); var rebuilt = new ContextAssembler(store, new com.lawrence.travel.infrastructure.interaction.InteractionJson(json));
        var second = rebuilt.prepare(f.run(), "planner", new AgentContext.Basis(2, 0L), "当前请求", never); assertThat(second.summaryId()).isEqualTo(first.summaryId()); assertThat(never.calls.get()).isZero();
    }
    /** 偏好变化后旧历史不自动召回，旧摘要和待返回结果不能重贴新版本。 */
    @Test void deletingPreferenceInvalidatesSummaryAndOldNarrative() {
        var f = fixture(); UUID id = UUID.randomUUID(); preferences.change(f.actor(), UUID.randomUUID(), 0, id, "SAVE", "pace", "DELETED_LONG_TERM_VALUE", ""); history(f, 1, 40);
        var old = assembler.prepare(f.run(), "planner", new AgentContext.Basis(2, 1L), "当前请求", new LocalCompression());
        preferences.change(f.actor(), UUID.randomUUID(), 1, id, "DELETE", null, null, null);
        assertThatThrownBy(() -> assembler.guard(old.source())).hasMessage("PREFERENCE_CHANGED");
        var fresh = assembler.prepare(f.run(), "planner", new AgentContext.Basis(2, 2L), "当前请求", new LocalCompression());
        assertThat(fresh.prompt()).doesNotContain("DELETED_LONG_TERM_VALUE", "historical-data-"); assertThat(fresh.source().history()).isEmpty(); assertThat(store.cached(fresh.source())).isNull();
    }
    /** 摘要调用期间发生删除，独立缓存写回也需锁内检查，不能保存迟到摘要。 */
    @Test void lateSummaryCannotCommitAfterPreferenceChange() {
        var f = fixture(); history(f, 0, 40);
        var local = new LocalCompression() {
            /** {@inheritDoc} 注入真实数据库并发变化，模型等待期间没有持有用户锁。 */
            @Override public ConversationDigest compact(List<AgentContext.Message> messages) { preferences.change(f.actor(), UUID.randomUUID(), 0, UUID.randomUUID(), "SAVE", "pace", "新偏好", ""); return super.compact(messages); }
        };
        assertThatThrownBy(() -> assembler.prepare(f.run(), "planner", new AgentContext.Basis(2, 0L), "当前请求", local)).hasMessage("PREFERENCE_CHANGED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversation_summary WHERE run_id=?", Integer.class, f.run())).isZero();
    }
    /** 双用户同样内容仍有不同归属和绑定，摘要读取不能跨用户回退。 */
    @Test void summariesAndHistoryAreIsolatedByUserAndTask() {
        var first = fixture(); var second = fixture(); history(first, 0, 40); history(second, 0, 40);
        var a = assembler.prepare(first.run(), "planner", new AgentContext.Basis(2, 0L), "当前请求", new LocalCompression());
        var b = assembler.prepare(second.run(), "planner", new AgentContext.Basis(2, 0L), "当前请求", new LocalCompression());
        assertThat(a.summaryId()).isNotEqualTo(b.summaryId()); assertThat(store.cached(b.source()).covered()).doesNotContainAnyElementsOf(store.cached(a.source()).covered());
    }
    /** 同一绑定并发压缩只有一个数据库版本，失败回滚不会留下第二份当前摘要。 */
    @Test void concurrentSummariesShareOnePersistentVersion() throws Exception {
        var f = fixture(); history(f, 0, 40);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> assembler.prepare(f.run(), "planner", new AgentContext.Basis(2, 0L), "当前请求", new LocalCompression()));
            var second = pool.submit(() -> assembler.prepare(f.run(), "planner", new AgentContext.Basis(2, 0L), "当前请求", new LocalCompression()));
            assertThat(first.get(10, TimeUnit.SECONDS).summaryId()).isEqualTo(second.get(10, TimeUnit.SECONDS).summaryId());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversation_summary WHERE run_id=?", Integer.class, f.run())).isEqualTo(1);
    }
    /** 当前版本改变时旧上下文及摘要不继续执行；过期问题不能被摘要重新授权。 */
    @Test void newRequestSupersedesOldContext() {
        var f = fixture(); var source = store.load(f.run(), "planner"); negotiation.send(f.actor(), f.scope().taskId(), UUID.randomUUID(), "", List.of(new Negotiation.Fact("pace", "轻松", null)));
        assertThatThrownBy(() -> store.guard(source)).hasMessage("PREFERENCE_CHANGED");
    }
    /** 保护区显式保留卡片、选项及有限授权范围；历史说明不会变成新授权；审核不读取这些对话。 */
    @Test void decisionAuthorizationAndUnresolvedQuestionsSurviveCompaction() {
        var f = fixture(); history(f, 0, 40); UUID question = UUID.randomUUID(), decision = UUID.randomUUID(), message = UUID.randomUUID();
        var card = new com.lawrence.travel.domain.interaction.DecisionRequest(decision, f.scope(), f.run(), 2, 1, 2, Map.of(question, new com.lawrence.travel.domain.interaction.DecisionRequest.Question(question, "仍待明确的问题", Map.of(), null)), true, Set.of(question), com.lawrence.travel.domain.interaction.DecisionRequest.State.OPEN);
        jdbc.update("INSERT INTO decision_request(id,user_id,task_id,run_id,body) VALUES(?,?,?,?,CAST(? AS jsonb))", decision, f.actor().id(), f.scope().taskId(), f.run(), json.encode(card));
        var response = new Negotiation.Response(decision, 1, 2, "AUTHORIZE", Map.of(), "明确有限授权理由", Set.of(question));
        var raw = new Negotiation.Message(message, "ANSWER", response.text(), List.of(), decision, 1, 2, "RECEIVED");
        jdbc.update("INSERT INTO conversation_message(id,user_id,task_id,sequence,request_hash,body,answer,status) VALUES(?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),'RECEIVED')", message, f.actor().id(), f.scope().taskId(), 42, json.hash(json.encode(raw)), json.encode(raw), json.encode(response));
        UUID rejectedMessage = UUID.randomUUID(); var rejection = new Negotiation.Response(decision, 1, 2, "REJECT", Map.of(), "拒绝赶场，因为需要午休", Set.of());
        var rejectedRaw = new Negotiation.Message(rejectedMessage, "ANSWER", rejection.text(), List.of(), decision, 1, 2, "RECORDED");
        jdbc.update("INSERT INTO conversation_message(id,user_id,task_id,sequence,request_hash,body,answer,status) VALUES(?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),'RECORDED')", rejectedMessage, f.actor().id(), f.scope().taskId(), 43, json.hash(json.encode(rejectedRaw)), json.encode(rejectedRaw), json.encode(rejection));
        jdbc.update("UPDATE travel_task SET message_sequence=43 WHERE user_id=? AND id=?", f.actor().id(), f.scope().taskId());
        var result = assembler.prepare(f.run(), "planner", new AgentContext.Basis(2, 0L), "当前请求", new LocalCompression());
        assertThat(result.prompt()).contains("仍待明确的问题", "明确有限授权理由", decision.toString(), question.toString(), "authorizationScope", "grantsNewPermission", "拒绝赶场，因为需要午休", "REJECT");
        var review = assembler.prepare(f.run(), "reviewer", new AgentContext.Basis(2, 0L), "独立审核候选", new LocalCompression());
        assertThat(review.prompt()).doesNotContain("明确有限授权理由", "仍待明确的问题", "historical-data-", "decisionLedger");
    }
    /** 引用卸载不丢原文；只能读取同任务同偏好版本的消息，其他用户或审核角色均不能读。 */
    @Test void offloadedOriginalCanOnlyBeReadWithinItsSourceScope() {
        var first = fixture(); var second = fixture(); history(first, 0, 40); history(second, 0, 1);
        var source = store.load(first.run(), "planner"); var id = source.history().getFirst().id();
        assertThat(store.original(source, id)).contains(source.history().getFirst().text());
        assertThatThrownBy(() -> store.original(store.load(second.run(), "planner"), id)).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> store.original(store.load(first.run(), "reviewer"), id)).hasMessage("NOT_FOUND");
    }

    /** 关键输入超限沿原运行预算进入暂停，不能创建截断候选或新预算绕过。 */
    @Test void oversizedContextPausesOriginalRunWithoutCandidate() {
        var f = fixture(); var hint = engine.candidates(100).stream().filter(h -> h.runId().equals(f.run())).findFirst().orElseThrow(); var claim = engine.claim(hint, "context-budget").orElseThrow();
        var bounded = new com.lawrence.travel.application.port.StageHandler() {
            /** {@inheritDoc} 仅接管本例真实排队的研究阶段，不调用外部服务。 */
            @Override public boolean supports(com.lawrence.travel.domain.execution.StageType type) { return type == claim.step().key().type(); }
            /** {@inheritDoc} 用完整超限请求验证执行器统一分类，不能截断。 */
            @Override public com.lawrence.travel.application.execution.StageOutput execute(com.lawrence.travel.application.port.ExecutionEngineStore.Claim current) { assembler.prepare(f.run(), "planner", new AgentContext.Basis(2, 0L), "x".repeat(40000), new LocalCompression()); throw new AssertionError("MUST_REJECT_OVERSIZE"); }
        };
        var control = new com.lawrence.travel.application.execution.StageExecutor.Control() {
            /** @return 测试执行允许进入 */
            @Override public boolean enter() { return true; }
            /** 本地无异步中断副作用。 */
            @Override public void leave() { }
            /** @return 测试不主动取消 */
            @Override public boolean cancelled() { return false; }
        };
        new com.lawrence.travel.application.execution.StageExecutor(engine, List.of(bounded)).execute(claim, control);
        assertThat(engine.run(f.scope(), f.run()).state()).isEqualTo(com.lawrence.travel.domain.execution.ExecutionRun.State.PAUSED_BUDGET);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM itinerary_candidate WHERE run_id=?", Integer.class, f.run())).isZero();
        assertThat(jdbc.queryForObject("SELECT model_calls FROM execution_budget WHERE run_id=?", Integer.class, f.run())).isZero();
    }

}
