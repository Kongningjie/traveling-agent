package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.execution.BackgroundJobService;
import com.lawrence.travel.application.execution.ExecutionCorrelation;
import com.lawrence.travel.application.execution.Jobs;
import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.BudgetLedger;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.DialogueStore;
import com.lawrence.travel.application.port.JobHandler;
import com.lawrence.travel.application.port.JobStore;
import com.lawrence.travel.domain.execution.BackgroundJob;
import com.lawrence.travel.domain.execution.control.CallKind;
import com.lawrence.travel.domain.execution.control.RunLimits;
import com.lawrence.travel.domain.execution.control.TokenUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实本机PG争锁、回滚与跨日测试；没有模型或工具网络调用。 */
@EnabledIfEnvironmentVariable(named="TRAVEL_INTEGRATION_TESTS", matches="true")
@ActiveProfiles("local")
@SpringBootTest(properties={"travel.engine.enabled=false", "travel.jobs.daily-models=3", "travel.jobs.daily-parses=2"})
class JobIntegrationTest {
    /** 资料取代原子关闭聊天投影屏障，旧理解不能提交，原文仍保留。 */
    @Test void invalidationWithdrawsLinkedInputAndRejectsOldInterpretation() {
        var s=scope(); UUID input=UUID.randomUUID(); tx.required(() -> chats.receive(s.userId(),s.chatId(),input,"原始旅行想法"));
        var old=tx.required(chats::claim).orElseThrow(); store.invalidate(s);
        assertThat(chats.read(s.userId(),s.chatId()).messages().getFirst().state()).isEqualTo("WITHDRAWN");
        assertThat(chats.read(s.userId(),s.chatId()).messages().getFirst().text()).isEqualTo("原始旅行想法");
        assertThat(tx.required(() -> chats.guard(old))).isFalse();
        assertThatThrownBy(() -> tx.required(() -> { chats.finish(old,"DONE","迟到结果",null,""); return null; })).isInstanceOf(RuntimeException.class);
    }
    /** 旧完成输入只读保留；未完成输入继承原累计预算、尝试和UNKNOWN，迁移不重发请求。 */
    @Test void legacyPendingInputKeepsBudgetAndUnknownWithoutReplayingDone() {
        var s=scope(); UUID input=UUID.randomUUID(),done=UUID.randomUUID();
        var meter=budgets.reserve(s.chatId(),CallKind.MODEL,TRAVEL,TARGET,100,Instant.now()); budgets.finish(meter.id(),null,Duration.ZERO);
        jdbc.update("UPDATE execution_budget SET model_calls=7 WHERE run_id=?",s.chatId());
        jdbc.update("INSERT INTO dialogue_input(user_id,chat_id,id,text,state,attempts) VALUES(?,?,?,'旧完成','DONE',1),(?,?,?,'旧待处理','QUEUED',1)",s.userId(),s.chatId(),done,s.userId(),s.chatId(),input);
        var c=tx.required(chats::claim).orElseThrow(); assertThat(c.messageId()).isEqualTo(input); assertThat(c.budgetId()).isNotEqualTo(s.chatId());
        assertThat(budgets.read(c.budgetId()).modelCalls()).isEqualTo(7); assertThat(jdbc.queryForObject("SELECT attempts FROM background_job WHERE operation_id=?",Integer.class,c.budgetId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM call_meter WHERE id=?",String.class,meter.id())).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT job_id IS NULL FROM dialogue_input WHERE id=?",Boolean.class,done)).isTrue();
        tx.required(() -> { chats.finish(c,"DONE","旧输入替身完成",null,""); return null; }); assertThat(tx.required(chats::claim)).isEmpty();
    }
    /** 旧有效租约不接管；三次失联达到上限后暂停，不借迁移重发预算或重置重试次数。 */
    @Test void legacyLiveLeaseAndAttemptLimitArePreserved() {
        var s=scope(); UUID input=UUID.randomUUID();
        jdbc.update("INSERT INTO dialogue_input(user_id,chat_id,id,text,state,attempts,lease_until) VALUES(?,?,?,'旧执行','RUNNING',3,clock_timestamp()+interval '30 seconds')",s.userId(),s.chatId(),input);
        assertThat(tx.required(chats::claim)).isEmpty(); assertThat(jdbc.queryForObject("SELECT count(*) FROM operation_context WHERE user_id=?",Integer.class,s.userId())).isZero();
        jdbc.update("UPDATE dialogue_input SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",input);
        assertThat(tx.required(chats::claim)).isEmpty(); assertThat(jdbc.queryForObject("SELECT state FROM dialogue_input WHERE id=?",String.class,input)).isEqualTo("PAUSED");
    }
    @Autowired private JobStore store;
    @Autowired private DialogueStore chats;
    @Autowired private AccountStore accounts;
    @Autowired private BusinessTransactions tx;
    @Autowired private BudgetLedger budgets;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.transaction.PlatformTransactionManager manager;
    private final List<Jobs.Scope> scopes = new ArrayList<>();
    private static final RunLimits TRAVEL = new RunLimits(30,60,2,Duration.ofSeconds(180),Duration.ofMinutes(15));
    private static final BudgetLedger.Target TARGET = new BudgetLedger.Target("LOCAL_FAKE_MODEL", "LOCAL", "LOCAL_FAKE");
    /** 完成器按归属→作业→预算锁定，与计量按预算→FK校验并发时不得形成锁环。 */
    @Test void businessLocksPermitMeterForeignKeysWithoutDeadlock() throws Exception {
        var s=scope(); var j=submit(s,BackgroundJob.Kind.CHAT_INPUT); store.claim(s,j.id()).orElseThrow();
        jdbc.update("INSERT INTO user_daily_quota(user_id,quota_day,kind) VALUES(?,'2026-10-18','TOOL')",s.userId());
        var transaction=new org.springframework.transaction.support.TransactionTemplate(manager);
        var ownerReady=new java.util.concurrent.CountDownLatch(1); var budgetReady=new java.util.concurrent.CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var owner=pool.submit(() -> transaction.execute(status -> {
                jdbc.execute("SET LOCAL lock_timeout='3000ms'"); store.read(s,j.id()); ownerReady.countDown(); await(budgetReady);
                jdbc.update("UPDATE execution_budget SET active_millis=active_millis+1 WHERE run_id=?",j.budgetId()); return true;
            }));
            var meter=pool.submit(() -> transaction.execute(status -> {
                jdbc.execute("SET LOCAL lock_timeout='3000ms'"); jdbc.queryForObject("SELECT model_calls FROM execution_budget WHERE run_id=? FOR UPDATE",Long.class,j.budgetId()); budgetReady.countDown(); await(ownerReady);
                jdbc.update("INSERT INTO call_ownership(id,user_id,budget_id,job_id,quota_day,kind,trace_id,span_id) VALUES(?,?,?,?,'2026-10-18','TOOL',?,?)",UUID.randomUUID(),s.userId(),j.budgetId(),j.id(),j.traceId(),UUID.randomUUID());
                jdbc.update("INSERT INTO execution_trace_span(id,trace_id,user_id,kind,state) VALUES(?,?,?,'LOCAL_TEST','RUNNING')",UUID.randomUUID(),j.traceId(),s.userId()); return true;
            }));
            assertThat(owner.get(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); assertThat(meter.get(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }
    /** @param latch 并发断言信号；设置上限防止失败用例遗留线程 */
    private static void await(java.util.concurrent.CountDownLatch latch) { try { if(!latch.await(3,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("LOCAL_TEST_TIMEOUT"); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("LOCAL_TEST_INTERRUPTED"); } }
    /** 仅删除本例随机身份，先清作业依赖再清预算。 */
    @AfterEach void clean() { tx.required(() -> {
        for (var s : scopes) {
            var ids = jdbc.queryForList("SELECT budget_id FROM operation_context WHERE user_id=? UNION SELECT budget_id FROM dialogue_chat WHERE user_id=?", UUID.class,s.userId(),s.userId());
            jdbc.update("DELETE FROM dialogue_input WHERE user_id=?",s.userId()); JobTestCleanup.remove(jdbc,s.userId()); jdbc.update("DELETE FROM dialogue_chat WHERE user_id=?",s.userId());
            for (UUID id : ids) { jdbc.update("DELETE FROM call_meter WHERE run_id=?",id); jdbc.update("DELETE FROM execution_budget WHERE run_id=?",id); }
            jdbc.update("DELETE FROM app_user WHERE id=?",s.userId());
        } return null;
    }); }
    /** @return 无旅行任务的随机测试聊天 */
    private Jobs.Scope scope() { UUID user=UUID.randomUUID(),chat=UUID.randomUUID(); accounts.insert(user,"jb_"+user.toString().replace("-","").substring(0,20),"__NON_LOGIN_TEST_HASH__"); tx.required(() -> chats.create(user,chat,"作业替身")); var s=new Jobs.Scope(user,chat); scopes.add(s); return s; }
    /** @param s 归属 @param kind 类型 @return 新输入作业 */
    private Jobs.View submit(Jobs.Scope s, BackgroundJob.Kind kind) { return store.submit(new Jobs.Submit(s,UUID.randomUUID(),kind,"main","{}",null,null)); }
    /** @param state 目标 @return 无后继有界产物 */
    private Jobs.Output output(BackgroundJob.State state) { return new Jobs.Output(state,"{\"local\":true}",List.of(),""); }
    /** 收件幂等不刷新预算；相同键不同内容和跨用户读取拒绝。 */
    @Test void idempotencyOwnershipAndIndependentInputBudget() {
        var s=scope(); UUID op=UUID.randomUUID(); var input=new Jobs.Submit(s,op,BackgroundJob.Kind.CHAT_INPUT,"main","{}",null,null);
        var a=store.submit(input); assertThat(store.submit(input).id()).isEqualTo(a.id());
        assertThat(submit(s,BackgroundJob.Kind.CHAT_INPUT).budgetId()).isNotEqualTo(a.budgetId());
        assertThatThrownBy(() -> store.submit(new Jobs.Submit(s,op,input.kind(),"main","{\"changed\":1}",null,null))).isInstanceOf(IllegalArgumentException.class);
        var other=scope(); assertThatThrownBy(() -> store.read(other,a.id())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM travel_task WHERE user_id=?",Integer.class,s.userId())).isZero();
    }
    /** 同时争抢同一作业仅一人成功；重建仓储的领取不依赖进程内状态。 */
    @Test void concurrentClaimHasOneWinner() throws Exception {
        var s=scope(); var j=submit(s,BackgroundJob.Kind.DOCUMENT_ANALYZE);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=new ArrayList<java.util.concurrent.Callable<Boolean>>(); for(int i=0;i<8;i++) tasks.add(() -> store.claim(s,j.id()).isPresent());
            int wins=0; for(var f:pool.invokeAll(tasks)) if(f.get()) wins++; assertThat(wins).isEqualTo(1);
        }
    }
    /** 过期接管保持原预算；旧token的续租、产物提交都拒绝。 */
    @Test void crashRecoveryAndLateResultAreFenced() {
        var s=scope(); var j=submit(s,BackgroundJob.Kind.MEMORY_UPDATE); var old=store.claim(s,j.id()).orElseThrow();
        jdbc.update("UPDATE background_job SET lease_until=clock_timestamp()-interval '1 second',active_since=clock_timestamp()-interval '10 seconds' WHERE id=?",j.id());
        var next=store.claim(s,j.id()).orElseThrow(); assertThat(next.job().budgetId()).isEqualTo(old.job().budgetId()); assertThat(next.fence().token()).isGreaterThan(old.fence().token());
        assertThat(store.renew(old.fence())).isFalse(); assertThatThrownBy(() -> store.finish(old,output(BackgroundJob.State.COMPLETED))).isInstanceOf(IllegalStateException.class);
        assertThat(budgets.read(j.budgetId()).activeTime()).isGreaterThanOrEqualTo(Duration.ofSeconds(9)); store.finish(next,output(BackgroundJob.State.COMPLETED));
    }
    /** 非法后继使产物和完成标记一起回滚；合法后继只登记一次并等待完成依赖。 */
    @Test void artifactsAndSuccessorsCommitAtomically() {
        var s=scope(); var j=submit(s,BackgroundJob.Kind.DOCUMENT_PARSE); var c=store.claim(s,j.id()).orElseThrow();
        var bad=new Jobs.Submit(s,j.operationId(),BackgroundJob.Kind.DOCUMENT_ANALYZE,"next","INVALID_JSON",j.id(),null);
        assertThatThrownBy(() -> store.finish(c,new Jobs.Output(BackgroundJob.State.COMPLETED,"{}",List.of(bad),""))).isInstanceOf(RuntimeException.class);
        assertThat(store.read(s,j.id()).state()).isEqualTo(BackgroundJob.State.RUNNING); assertThat(jdbc.queryForObject("SELECT artifact IS NULL FROM background_job WHERE id=?",Boolean.class,j.id())).isTrue();
        var good=new Jobs.Submit(s,j.operationId(),BackgroundJob.Kind.DOCUMENT_ANALYZE,"next","{}",j.id(),null);
        store.finish(c,new Jobs.Output(BackgroundJob.State.COMPLETED,"{}",List.of(good),"")); var n=store.submit(good);
        assertThat(store.claim(s,n.id())).isPresent(); assertThatThrownBy(() -> store.finish(c,output(BackgroundJob.State.COMPLETED))).isInstanceOf(IllegalStateException.class);
    }
    /** 等待依赖／用户释放租约；显式继续沿用相同operation和剩余额度。 */
    @Test void dependencyAndUserWaitDoNotHoldLeases() {
        var s=scope(); var a=submit(s,BackgroundJob.Kind.DOCUMENT_PARSE);
        var b=store.submit(new Jobs.Submit(s,a.operationId(),BackgroundJob.Kind.DOCUMENT_ANALYZE,"dependent","{}",a.id(),null)); assertThat(store.claim(s,b.id())).isEmpty();
        var c=store.claim(s,a.id()).orElseThrow(); assertThatThrownBy(() -> store.control(s,a.id(),true)).isInstanceOf(IllegalStateException.class);
        store.finish(c,output(BackgroundJob.State.WAITING_USER)); assertThat(store.read(s,a.id()).leaseUntil()).isNull();
        store.control(s,a.id(),true); var next=store.claim(s,a.id()).orElseThrow(); assertThat(next.job().budgetId()).isEqualTo(c.job().budgetId());
        store.finish(next,output(BackgroundJob.State.COMPLETED)); assertThat(store.claim(s,b.id())).isPresent();
    }
    /** 取消与资料水位变化阻止旧结果，不能通过重试复活终态。 */
    @Test void cancellationAndSupersessionRejectLateResults() {
        var s=scope(); var j=submit(s,BackgroundJob.Kind.MEMORY_UPDATE); var c=store.claim(s,j.id()).orElseThrow(); store.control(s,j.id(),false);
        assertThat(store.live(c.fence())).isFalse(); assertThatThrownBy(() -> store.control(s,j.id(),true)).isInstanceOf(IllegalStateException.class);
        var another=submit(s,BackgroundJob.Kind.DOCUMENT_ANALYZE); var old=store.claim(s,another.id()).orElseThrow(); store.invalidate(s);
        assertThat(store.live(old.fence())).isFalse(); assertThat(store.read(s,another.id()).state()).isEqualTo(BackgroundJob.State.SUPERSEDED);
    }
    /** 用户日配额竞争不会超卖；失败预占不产生call_meter或预算次数。 */
    @Test void concurrentDailyQuotaIsAtomic() throws Exception {
        var s=scope(); var j=submit(s,BackgroundJob.Kind.CHAT_INPUT); store.claim(s,j.id()).orElseThrow();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=new ArrayList<java.util.concurrent.Callable<Boolean>>(); for(int i=0;i<10;i++) tasks.add(() -> { try { budgets.reserve(j.budgetId(),CallKind.MODEL,TRAVEL,TARGET,100,Instant.now()); return true; } catch(com.lawrence.travel.domain.execution.control.CallRejectedException rejected) { return false; } });
            int wins=0; for(var f:pool.invokeAll(tasks)) if(f.get()) wins++; assertThat(wins).isEqualTo(3);
        }
        assertThat(budgets.read(j.budgetId()).modelCalls()).isEqualTo(3); assertThat(jdbc.queryForObject("SELECT reserved FROM user_daily_quota WHERE user_id=? AND kind='MODEL'",Long.class,s.userId())).isEqualTo(3);
    }
    /** 跨午夜后回调仍结算原日；重复回调和未知用量不返还计数。 */
    @Test void settlementUsesOriginalDayAndUnknownStaysReserved() {
        var s=scope(); var j=submit(s,BackgroundJob.Kind.CHAT_INPUT); store.claim(s,j.id()).orElseThrow();
        var before=budgets.reserve(j.budgetId(),CallKind.MODEL,TRAVEL,TARGET,100,Instant.parse("2026-10-17T15:59:59Z"));
        var after=budgets.reserve(j.budgetId(),CallKind.MODEL,TRAVEL,TARGET,100,Instant.parse("2026-10-17T16:00:01Z"));
        budgets.finish(before.id(),new TokenUsage(10,5),Duration.ofSeconds(1)); budgets.finish(before.id(),null,Duration.ZERO); budgets.finish(after.id(),null,Duration.ZERO);
        assertThat(jdbc.queryForObject("SELECT consumed FROM user_daily_quota WHERE user_id=? AND quota_day='2026-10-17' AND kind='MODEL'",Long.class,s.userId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reserved FROM user_daily_quota WHERE user_id=? AND quota_day='2026-10-18' AND kind='MODEL'",Long.class,s.userId())).isEqualTo(1);
        assertThat(budgets.hasUncertainUsage(TARGET,Instant.parse("2026-10-17T00:00:00Z"),Instant.now())).isTrue();
    }
    /** 输入级上限不能被旅行参数放宽；当前活动时间也参与请求超时。 */
    @Test void inputLimitsAndActiveIntervalAreEnforced() {
        var s=scope(); var j=submit(s,BackgroundJob.Kind.CHAT_INPUT); store.claim(s,j.id()).orElseThrow();
        assertThat(budgets.limitsFor(j.budgetId(),TRAVEL).modelCalls()).isEqualTo(8);
        jdbc.update("UPDATE execution_budget SET model_calls=8 WHERE run_id=?",j.budgetId());
        assertThatThrownBy(() -> budgets.reserve(j.budgetId(),CallKind.MODEL,TRAVEL,TARGET,100,Instant.now())).isInstanceOf(com.lawrence.travel.domain.execution.control.CallRejectedException.class);
        jdbc.update("UPDATE execution_budget SET model_calls=0,active_millis=300000 WHERE run_id=?",j.budgetId());
        assertThatThrownBy(() -> budgets.reserve(j.budgetId(),CallKind.TOOL,TRAVEL,TARGET,0,Instant.now())).isInstanceOf(com.lawrence.travel.domain.execution.control.CallRejectedException.class);
    }
    /** 稳定request与trace穿过作业及实际调用归属，固定问题记录不保存异常原文。 */
    @Test void correlationAndProblemArePersisted() {
        var s=scope(); var ids=new ExecutionCorrelation.Ids(UUID.randomUUID(),UUID.randomUUID()); Jobs.View j;
        try(var ignored=ExecutionCorrelation.bind(ids)) { j=submit(s,BackgroundJob.Kind.MEMORY_UPDATE); }
        var c=store.claim(s,j.id()).orElseThrow(); var meter=budgets.reserve(j.budgetId(),CallKind.MODEL,TRAVEL,TARGET,100,Instant.now());
        store.finish(c,new Jobs.Output(BackgroundJob.State.PAUSED,null,List.of(),"LOCAL_FAKE_FAILURE"));
        assertThat(j.traceId()).isEqualTo(ids.traceId()); assertThat(jdbc.queryForObject("SELECT trace_id FROM call_ownership WHERE id=?",UUID.class,meter.id())).isEqualTo(ids.traceId());
        assertThat(jdbc.queryForObject("SELECT code FROM problem_event WHERE user_id=?",String.class,s.userId())).isEqualTo("LOCAL_FAKE_FAILURE");
    }
    /** 后3类仅测试处理器端口；没有生产处理器时不能伪造完成。 */
    @Test void unregisteredKindsStayQueuedAndTestHandlerCanExecute() {
        var s=scope(); var j=submit(s,BackgroundJob.Kind.DOCUMENT_ANALYZE);
        assertThat(new BackgroundJobService(store,List.of(),false).claimWork()).isEmpty();
        var handler=new JobHandler() {
            /** 本例明确替身分析类型。 */
            @Override public BackgroundJob.Kind kind() { return BackgroundJob.Kind.DOCUMENT_ANALYZE; }
            /** 本地固定结果，不解析文件或调用模型。 */
            @Override public Jobs.Output execute(Jobs.Claim claim) { return output(BackgroundJob.State.COMPLETED); }
        };
        new BackgroundJobService(store,List.of(handler),false).claimWork().orElseThrow().run(); assertThat(store.read(s,j.id()).state()).isEqualTo(BackgroundJob.State.COMPLETED);
    }
    /** 单用户与全局解析领取有界，过期接管计新的实际解析尝试，不重发输入预算。 */
    @Test void parserConcurrencyAndDailyCapArePersistent() {
        var s=scope(); var a=submit(s,BackgroundJob.Kind.DOCUMENT_PARSE); var b=submit(s,BackgroundJob.Kind.DOCUMENT_PARSE);
        var first=store.claim(s,a.id()).orElseThrow(); assertThat(store.claim(s,b.id())).isEmpty(); store.finish(first,output(BackgroundJob.State.COMPLETED));
        var second=store.claim(s,b.id()).orElseThrow(); store.finish(second,output(BackgroundJob.State.COMPLETED));
        var last=submit(s,BackgroundJob.Kind.DOCUMENT_PARSE); assertThat(store.claim(s,last.id())).isEmpty(); assertThat(store.read(s,last.id()).error()).isEqualTo("DAILY_QUOTA_EXHAUSTED");
    }
}
