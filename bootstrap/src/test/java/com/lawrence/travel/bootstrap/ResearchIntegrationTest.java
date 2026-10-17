package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.execution.StageOutput;
import com.lawrence.travel.application.execution.StagePlan;
import com.lawrence.travel.application.execution.TravelToolService;
import com.lawrence.travel.application.knowledge.EvidencePolicy;
import com.lawrence.travel.application.knowledge.EvidenceService;
import com.lawrence.travel.application.knowledge.Research;
import com.lawrence.travel.application.knowledge.ResearchHandler;
import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.ResearchAdvisor;
import com.lawrence.travel.application.port.ResearchStore;
import com.lawrence.travel.application.port.StageCommitPort;
import com.lawrence.travel.application.port.TravelStore;
import com.lawrence.travel.application.port.TravelToolPort;
import com.lawrence.travel.application.travel.TravelStorageService;
import com.lawrence.travel.domain.execution.ExecutionStep;
import com.lawrence.travel.domain.execution.StageType;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.interaction.RequirementFields;
import com.lawrence.travel.domain.knowledge.EvidenceRecord;
import com.lawrence.travel.domain.knowledge.FieldEvidence;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

/** PostgreSQL 真实事务、不可变性、租约与归属；供应商和模型均为显式本地替身。 */
@EnabledIfEnvironmentVariable(named = "TRAVEL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"travel.engine.enabled=false", "travel.tools.evidence-hosts[0]=official.example", "travel.tools.evidence-hosts[1]=operator.example"})
class ResearchIntegrationTest {
    @Autowired private ResearchHandler handler;
    @Autowired private ResearchStore store;
    @Autowired private EvidenceService service;
    @Autowired private TravelStorageService travel;
    @Autowired private ExecutionEngineStore engine;
    @Autowired private AccountStore accounts;
    @Autowired private BusinessTransactions transactions;
    @Autowired private InteractionCodec codec;
    @Autowired private EvidencePolicy policy;
    @Autowired private TravelJson json;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private ResearchAdvisor advisor;
    @MockitoBean private TravelToolService tools;
    @org.springframework.boot.test.web.server.LocalServerPort private int port;
    @Autowired private org.springframework.session.SessionRepository<? extends org.springframework.session.Session> sessions;
    private final List<String> sessionIds = new ArrayList<>();
    private final List<UUID> users = new ArrayList<>();
    private final LocalDate date = LocalDate.parse("2026-11-01");
    /** @param actor 随机主体 @param scope 私有任务 */
    private record Fixture(UserIdentity actor, TaskScope scope) { }
    /** 仅删除本例随机账号资料；先清证据外键及循环指针，不清数据库和卷。 */
    @AfterEach void clean() {
        sessionIds.forEach(sessions::deleteById);
        transactions.required(() -> {
            for (UUID user : users) {
                var runs = jdbc.queryForList("SELECT id FROM execution_run WHERE user_id=?", UUID.class, user);
                jdbc.update("UPDATE travel_task SET current_run_id=NULL,current_version_id=NULL WHERE user_id=?", user);
                for (String table : List.of("evidence_notice", "field_evidence", "itinerary_candidate", "negotiation_card", "conversation_message", "decision_response", "decision_request", "run_event", "execution_start_request")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
                jdbc.update("DELETE FROM artifact_content WHERE artifact_id IN(SELECT id FROM artifact_record WHERE user_id=?)", user);
                for (String table : List.of("artifact_record", "step_attempt", "outbox_event", "execution_step", "execution_run")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
                for (UUID run : runs) { jdbc.update("DELETE FROM call_meter WHERE run_id=?", run); jdbc.update("DELETE FROM execution_budget WHERE run_id=?", run); }
                for (String table : List.of("change_request", "itinerary_snapshot", "travel_constraint_revision", "travel_task", "travel_idempotency")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
                jdbc.update("DELETE FROM app_user WHERE id=?", user);
            }
            return true;
        });
    }
    /** @return 无可登录密码的本地随机账号与已确认需求 */
    private Fixture fixture() {
        UUID id = UUID.randomUUID(); String name = "r_" + id.toString().replace("-", "").substring(0, 20); accounts.insert(id, name, "__NON_LOGIN_TEST_HASH__"); users.add(id); var actor = new UserIdentity(id, name);
        var requirements = RequirementFields.merge(List.of(), Map.of("cities", "北京", "startDate", date.toString(), "endDate", date.plusDays(1).toString(), "travelers", "2", "budgetRule", "NONE", "fixedStatus", "NONE"), ConstraintRevision.Source.USER);
        var task = travel.create(actor, UUID.randomUUID().toString(), "本地证据调查", new TravelStore.RevisionContent(requirements, List.of(), "USER", "测试"));
        return new Fixture(actor, task.task().scope());
    }
    /** @param f 任务 @return 当前内容依赖摘要；跨运行保持稳定但新需求变化不复用 */
    private String dependency(Fixture f) { var t = travel.task(f.actor(), f.scope().taskId()).task(); return EvidencePolicy.dependency(travel.revision(f.actor(), f.scope().taskId(), t.constraintRevision()), json::encode); }
    /** @param url 明确原文 @return 精确字段查询 */
    private Research.Query query(String url) { return new Research.Query("poi:123", "price", TravelToolPort.Operation.EXTRACT, Map.of("url", url), date); }
    /** @param f 任务 @param input 持久输入 @return 真实领取，保持调用者可检查迟到提交 */
    private ExecutionEngineStore.Claim claim(Fixture f, String input) {
        var first = new StagePlan(UUID.randomUUID(), new ExecutionStep.Key(StageType.RESEARCH, 0, "research_" + UUID.randomUUID()), null, input, List.of());
        var run = transactions.required(() -> engine.start(f.scope(), UUID.randomUUID(), first));
        return engine.claim(engine.candidates(100).stream().filter(h -> h.runId().equals(run.id())).findFirst().orElseThrow(), "research-test").orElseThrow();
    }
    /** @param f 任务 @param query 已筛选查询 @return 从单查询阶段开始，不依赖模型计划 */
    private ExecutionEngineStore.Claim claim(Fixture f, Research.Query query) { return claim(f, json.encode(new Research.Input(List.of(query), 0, dependency(f)))); }
    /** @param query 原文查询 @param value 明确价格 @param retrievedAt 获取时刻 @return 实际来源匹配的本地响应 */
    private TravelToolPort.Evidence response(Research.Query query, String value, Instant retrievedAt) {
        String quote = date + " 门票" + value; var raw = new TravelToolPort.Evidence(URI.create(query.arguments().get("url")), retrievedAt, json.encode(Map.of("operation", "EXTRACT", "status", "AVAILABLE", "data", Map.of("results", List.of(Map.of("url", query.arguments().get("url"), "raw_content", quote))))));
        when(tools.query(any(), any(), any())).thenReturn(raw); when(advisor.extract(any(), any(), any())).thenReturn(new Research.Extraction(value, quote, null, date)); return raw;
    }
    /** @param claim 真实领取 @return 原子登记与后继的结果 */
    private StageCommitPort.Result complete(ExecutionEngineStore.Claim claim) { return engine.commit(engine.prepare(claim, handler.execute(claim))); }
    /** 字段、原文哈希及所选阶段产物一起提交，重复提交不会重复证据和后继。 */
    @Test void immutableEvidenceAndSuccessorCommitExactlyOnce() {
        var f = fixture(); var q = query("https://official.example/tickets"); var raw = response(q, "20元", Instant.now()); var c = claim(f, q); var command = engine.prepare(c, handler.execute(c));
        assertThat(engine.commit(command)).isEqualTo(StageCommitPort.Result.COMMITTED); assertThat(engine.commit(command)).isEqualTo(StageCommitPort.Result.ALREADY_COMMITTED);
        var view = service.list(f.actor(), f.scope().taskId()).getFirst(); assertThat(view.effectiveStatus()).isEqualTo(EvidenceRecord.Status.KNOWN);
        assertThat(view.evidence().artifactId()).isNotNull(); assertThat(view.evidence().reference().contentHash()).isEqualTo(EvidencePolicy.hash(raw.payload()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM field_evidence WHERE user_id=?", Integer.class, f.actor().id())).isOne();
        assertThat(engine.candidates(100)).anyMatch(h -> h.runId().equals(c.fence().runId()) && h.type() == StageType.PLAN);
        assertThatThrownBy(() -> jdbc.update("UPDATE field_evidence SET body=body WHERE id=?", view.evidence().reference().id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    /** 新运行命中有效且精确参数缓存时不访问模型或工具，不重复扣调用预算。 */
    @Test void cacheReusesCommittedResultsWithoutExternalCalls() {
        var f = fixture(); var q = query("https://official.example/tickets"); response(q, "20元", Instant.now()); complete(claim(f, q));
        assertThat(complete(claim(f, q))).isEqualTo(StageCommitPort.Result.COMMITTED);
        verify(tools, times(1)).query(any(), any(), any()); verify(advisor, times(1)).extract(any(), any(), any());
        assertThat(service.list(f.actor(), f.scope().taskId())).hasSize(1);
        assertThat(store.cached(f.scope(), policy.key(q), "0".repeat(64), date)).isEmpty();
        assertThat(store.cached(f.scope(), policy.key(q), dependency(f), date.plusDays(1))).isEmpty();
    }
    /** 缓存读取后撤回，C事务必须再次拒绝，不能提交已不适用的复用引用。 */
    @Test void withdrawalBetweenCacheReadAndCommitIsDetected() {
        var f = fixture(); var q = query("https://official.example/tickets"); response(q, "20元", Instant.now()); complete(claim(f, q));
        var reference = service.list(f.actor(), f.scope().taskId()).getFirst().evidence().reference(); var c = claim(f, q); var command = engine.prepare(c, handler.execute(c));
        service.withdraw(f.actor(), f.scope().taskId(), reference, UUID.randomUUID()); assertThatThrownBy(() -> engine.commit(command)).hasMessage("STALE_VERSION");
        assertThat(service.list(f.actor(), f.scope().taskId())).hasSize(1); verify(tools, times(1)).query(any(), any(), any());
    }
    /** 即使引用同属当前用户任务，也不能把另一组查询参数的缓存当作本次结果。 */
    @Test void reusedReferenceMustMatchQueryAndDependency() {
        var f = fixture(); var q = query("https://official.example/tickets"); response(q, "20元", Instant.now()); complete(claim(f, q)); var reference = service.list(f.actor(), f.scope().taskId()).getFirst().evidence().reference();
        var c = claim(f, q); var output = handler.execute(c); var wrong = new Research.Prepared("0".repeat(64), dependency(f), List.of(), Set.of(reference), null);
        var broken = new StageOutput(output.text(), json.encode(Map.of("research", wrong)), output.next(), output.outcome(), null);
        assertThatThrownBy(() -> engine.commit(engine.prepare(c, broken))).hasMessage("RESEARCH_REUSE_BINDING_INVALID");
        assertThat(service.list(f.actor(), f.scope().taskId())).hasSize(1);
    }
    /** 不同官方／运营方同字段冲突保留两值，并使双方精确依赖失效。 */
    @Test void officialConflictsNeverBecomeLastWriteWins() {
        var f = fixture(); var a = query("https://official.example/tickets"); response(a, "20元", Instant.now()); complete(claim(f, a)); var first = service.list(f.actor(), f.scope().taskId()).getFirst().evidence();
        var b = query("https://operator.example/tickets"); response(b, "30元", Instant.now()); complete(claim(f, b));
        var values = service.list(f.actor(), f.scope().taskId()); assertThat(values).hasSize(2).allMatch(v -> v.effectiveStatus() == EvidenceRecord.Status.CONFLICT);
        assertThat(values.stream().map(v -> v.evidence().value())).containsExactlyInAnyOrder("20元", "30元");
        assertThat(store.dependencies(f.scope(), Set.of(first.reference()), Instant.now(), Instant.now().plusSeconds(60)).allCurrent()).isFalse();
        assertThat(store.cached(f.scope(), policy.key(a), dependency(f), date)).isEmpty();
    }
    /** 同来源刷新追加新身份与失效通知，原始正文及旧引用保持原值。 */
    @Test void refreshAppendsNewVersionRatherThanOverwriting() {
        var f = fixture(); var q = query("https://official.example/tickets"); response(q, "20元", Instant.now()); complete(claim(f, q)); var old = service.list(f.actor(), f.scope().taskId()).getFirst().evidence();
        service.withdraw(f.actor(), f.scope().taskId(), old.reference(), UUID.randomUUID()); response(q, "30元", Instant.now()); complete(claim(f, q));
        var values = service.list(f.actor(), f.scope().taskId()); assertThat(values).hasSize(2); assertThat(values).anyMatch(v -> v.evidence().reference().equals(old.reference()) && v.evidence().value().equals("20元") && v.reason().equals("WITHDRAWN"));
        assertThat(values).anyMatch(v -> v.evidence().value().equals("30元") && v.effectiveStatus() == EvidenceRecord.Status.KNOWN);
    }
    /** 超过有效期、错误哈希和他人归属都不能用作报告依据。 */
    @Test void dependencyChecksExactHashExpiryAndOwnership() {
        var f = fixture(); var q = query("https://official.example/tickets"); response(q, "20元", Instant.now()); complete(claim(f, q)); var value = service.list(f.actor(), f.scope().taskId()).getFirst().evidence();
        var valid = store.dependencies(f.scope(), Set.of(value.reference()), Instant.now(), Instant.now().plusSeconds(200000)); assertThat(valid.allCurrent()).isTrue(); assertThat(valid.validUntil()).isEqualTo(value.expiresAt());
        assertThat(store.dependencies(f.scope(), Set.of(value.reference()), value.expiresAt(), value.expiresAt().plusSeconds(60)).allCurrent()).isFalse();
        assertThat(store.dependencies(f.scope(), Set.of(new EvidenceRecord.Reference(value.reference().id(), 1, "0".repeat(64))), Instant.now(), Instant.now().plusSeconds(60)).allCurrent()).isFalse();
        var other = fixture(); assertThatThrownBy(() -> service.list(other.actor(), f.scope().taskId())).hasMessage("NOT_FOUND");
        assertThat(store.dependencies(other.scope(), Set.of(value.reference()), Instant.now(), Instant.now().plusSeconds(60)).allCurrent()).isFalse();
    }
    /** 新需求撤销旧 fence 后，已准备原文也不能登记为业务证据。 */
    @Test void lateStageCannotPublishEvidence() {
        var f = fixture(); var q = query("https://official.example/tickets"); response(q, "20元", Instant.now()); var c = claim(f, q); var prepared = engine.prepare(c, handler.execute(c));
        claim(f, q); assertThat(engine.commit(prepared)).isEqualTo(StageCommitPort.Result.STALE); assertThat(service.list(f.actor(), f.scope().taskId())).isEmpty();
    }
    /** 后继唯一键失败回滚字段、冲突通知和事件，不出现只登记资料未完成阶段。 */
    @Test void successorConflictRollsBackEvidenceProjection() {
        var f = fixture(); var q = query("https://official.example/tickets"); response(q, "20元", Instant.now()); var c = claim(f, q); var output = handler.execute(c);
        var duplicate = new StagePlan(UUID.randomUUID(), c.step().key(), null, c.input(), List.of());
        var broken = new StageOutput(output.text(), output.checkpoint(), List.of(duplicate), StageOutput.Outcome.CONTINUE, null);
        assertThatThrownBy(() -> engine.commit(engine.prepare(c, broken))).isInstanceOf(com.lawrence.travel.application.travel.TravelStorageFailure.class);
        assertThat(service.list(f.actor(), f.scope().taskId())).isEmpty();
    }
    /** 混入候选或协商写入标记必须在任一投影写入前拒绝，不能利用空返回值叠加业务。 */
    @Test void mixedBusinessProjectionIsRejectedBeforeWriting() {
        var f = fixture(); var q = query("https://official.example/tickets"); response(q, "20元", Instant.now()); var c = claim(f, q); var output = handler.execute(c);
        var envelope = new java.util.LinkedHashMap<String, Object>(); envelope.putAll(json.decode(output.checkpoint(), Map.class)); envelope.put("negotiation", Map.of());
        var invalid = new StageOutput(output.text(), json.encode(envelope), output.next(), output.outcome(), null);
        assertThatThrownBy(() -> engine.commit(engine.prepare(c, invalid))).hasMessage("AMBIGUOUS_PROJECTION");
        assertThat(service.list(f.actor(), f.scope().taskId())).isEmpty();
    }
    /** 传输失败明确登记未知；禁用／免费门禁拒绝则暂停且没有伪造事实。 */
    @Test void failureIsUnknownButDisabledAndQuotaDoNotBecomeFacts() {
        var f = fixture(); var q = query("https://official.example/tickets"); when(tools.query(any(), any(), any())).thenThrow(new IllegalStateException("TOOL_REQUEST_FAILED"));
        complete(claim(f, q)); assertThat(service.list(f.actor(), f.scope().taskId()).getFirst().effectiveStatus()).isEqualTo(EvidenceRecord.Status.UNKNOWN);
        var other = fixture(); org.mockito.Mockito.doThrow(new IllegalStateException("TOOLS_DISABLED")).when(tools).query(any(), any(), any());
        assertThatThrownBy(() -> handler.execute(claim(other, q))).hasMessage("EXTERNAL_DISABLED"); assertThat(service.list(other.actor(), other.scope().taskId())).isEmpty();
        org.mockito.Mockito.doThrow(new IllegalStateException("TOOL_QUOTA_UNVERIFIED")).when(tools).query(any(), any(), any());
        assertThatThrownBy(() -> handler.execute(claim(other, q))).hasMessage("QUOTA_UNVERIFIED");
    }
    /** 撤回是追加、可重放且有归属检查；旧快照不被修改。 */
    @Test void withdrawalIsAuditedAndIdempotent() {
        var f = fixture(); var q = query("https://official.example/tickets"); response(q, "20元", Instant.now()); complete(claim(f, q)); var ref = service.list(f.actor(), f.scope().taskId()).getFirst().evidence().reference(); UUID request = UUID.randomUUID();
        assertThat(service.withdraw(f.actor(), f.scope().taskId(), ref, request)).isTrue(); assertThat(service.withdraw(f.actor(), f.scope().taskId(), ref, request)).isFalse();
        assertThat(service.list(f.actor(), f.scope().taskId()).getFirst().reason()).isEqualTo("WITHDRAWN");
        var other = fixture(); assertThatThrownBy(() -> service.withdraw(other.actor(), f.scope().taskId(), ref, UUID.randomUUID())).hasMessage("NOT_FOUND");
    }
    /** 计划和后继在事务保存；应用拒绝无进展重复查询，不绕开12项上限。 */
    @Test void boundedPlanIsPersistedAndDuplicateQueriesRejected() {
        var f = fixture(); var q = query("https://official.example/tickets"); when(advisor.plan(any(), any())).thenReturn(new Research.Plan(List.of(q)));
        var c = claim(f, "{}"); assertThat(complete(c)).isEqualTo(StageCommitPort.Result.COMMITTED);
        var next = engine.candidates(100).stream().filter(h -> h.runId().equals(c.fence().runId())).findFirst().orElseThrow(); assertThat(next.type()).isEqualTo(StageType.RESEARCH);
        when(advisor.plan(any(), any())).thenReturn(new Research.Plan(List.of(q, q)));
        assertThatThrownBy(() -> handler.execute(claim(f, "{}"))).hasMessage("RESEARCH_NO_PROGRESS"); verifyNoInteractions(tools);
    }
    /** 人工资料按原更新时间失效，不能通过导入时间获得永久有效性。 */
    @Test void seedsRetainOriginalTimeAndAreNotModelFacts() {
        var f = fixture(); when(advisor.plan(any(), any())).thenReturn(new Research.Plan(List.of())); Instant original = Instant.now().minusSeconds(100);
        var seed = new FieldEvidence(new EvidenceRecord.Reference(UUID.randomUUID(), 1, "a".repeat(64)), "北京", "openingHours", "09:00", FieldEvidence.SourceType.SEED, URI.create("https://official.example/hours"), null, original, null, date, original.plusSeconds(999999), "人工记录09:00，日期待核实", null, EvidenceRecord.Status.UNKNOWN);
        var seeded = new ResearchHandler(store, advisor, tools, codec, policy, List.of(seed)); var c = claim(f, "{}"); engine.commit(engine.prepare(c, seeded.execute(c)));
        var result = service.list(f.actor(), f.scope().taskId()).getFirst().evidence(); assertThat(result.retrievedAt()).isEqualTo(original); assertThat(result.expiresAt()).isEqualTo(original.plusSeconds(86400)); assertThat(result.sourceType()).isEqualTo(FieldEvidence.SourceType.SEED);
        verifyNoInteractions(tools);
    }
    /** 已过期的查询结果不能因为重启／新运行重新获得 TTL，必须重新获取。 */
    @Test void expiredCacheDoesNotReviveOnNewRun() {
        var f = fixture(); var q = query("https://official.example/tickets"); response(q, "20元", Instant.now().minusSeconds(90000)); complete(claim(f, q));
        assertThat(service.list(f.actor(), f.scope().taskId()).getFirst().reason()).isEqualTo("EXPIRED"); assertThat(store.cached(f.scope(), policy.key(q), dependency(f), date)).isEmpty();
        response(q, "30元", Instant.now()); complete(claim(f, q)); verify(tools, times(2)).query(any(), any(), any());
        assertThat(service.list(f.actor(), f.scope().taskId())).hasSize(2);
    }
    /** @param actor 身份 @param repository 会话仓储 @param <S> 会话类型 @return 仅内存的 Cookie，不输出真实身份凭证 */
    private <S extends org.springframework.session.Session> String session(UserIdentity actor, org.springframework.session.SessionRepository<S> repository) {
        var value = repository.createSession(); var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));
        value.setAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context); repository.save(value); sessionIds.add(value.getId());
        return "SESSION=" + java.util.Base64.getEncoder().encodeToString(value.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    /** 真实会话鉴权、匿名拒绝和跨用户404，字段结果禁止缓存，不返回整页原文。 */
    @Test void httpEvidenceRequiresAuthenticatedOwner() throws Exception {
        var f = fixture(); var q = query("https://official.example/tickets"); response(q, "20元", Instant.now()); complete(claim(f, q));
        var client = java.net.http.HttpClient.newHttpClient(); var uri = URI.create("http://127.0.0.1:" + port + "/api/v1/tasks/" + f.scope().taskId() + "/evidence");
        assertThat(client.send(java.net.http.HttpRequest.newBuilder(uri).GET().build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
        var good = client.send(java.net.http.HttpRequest.newBuilder(uri).header("Cookie", session(f.actor(), sessions)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(good.statusCode()).isEqualTo(200); assertThat(good.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(json.decode(good.body(), List.class)).hasSize(1);
        var other = fixture(); var foreign = client.send(java.net.http.HttpRequest.newBuilder(uri).header("Cookie", session(other.actor(), sessions)).GET().build(), java.net.http.HttpResponse.BodyHandlers.discarding()); assertThat(foreign.statusCode()).isEqualTo(404);
    }
    /**
     * 通过明确数据库夹具验证精确失效 SQL，不把夹具宣称为已通过规划／审核的真实候选。
     * 当前正式快照入口仍在下一规划阶段接入字段引用。
     */
    @Test void withdrawalInvalidatesOnlyCandidatesReferencingThatEvidence() {
        var f = fixture(); var q = query("https://official.example/tickets"); response(q, "20元", Instant.now()); var c = claim(f, q); complete(c);
        var ref = service.list(f.actor(), f.scope().taskId()).getFirst().evidence().reference();
        UUID linked = candidateFixture(f, c, Set.of(ref)); UUID unrelated = candidateFixture(f, c, Set.of());
        service.withdraw(f.actor(), f.scope().taskId(), ref, UUID.randomUUID());
        assertThat(jdbc.queryForObject("SELECT state FROM itinerary_candidate WHERE id=?", String.class, linked)).isEqualTo("STALE");
        assertThat(jdbc.queryForObject("SELECT state FROM itinerary_candidate WHERE id=?", String.class, unrelated)).isEqualTo("PRESENTED");
        assertThat(travel.task(f.actor(), f.scope().taskId()).task().currentVersionId()).isNull();
    }
    /** @param f 归属 @param c 外键来源夹具 @param refs 精确引用 @return 仅用于失效 SQL 的候选，不经过业务候选入口 */
    private UUID candidateFixture(Fixture f, ExecutionEngineStore.Claim c, Set<EvidenceRecord.Reference> refs) {
        UUID snapshot = UUID.randomUUID(); UUID candidate = UUID.randomUUID(); var version = new com.lawrence.travel.domain.itinerary.ItineraryVersion(snapshot, f.scope(), 1, json.hash("{}"), 1, null, refs, null);
        jdbc.update("INSERT INTO itinerary_snapshot(id,user_id,task_id,constraint_revision,schema_version,payload_hash,metadata,payload) VALUES(?,?,?,1,1,?,CAST(? AS jsonb),'{}'::jsonb)", snapshot, f.actor().id(), f.scope().taskId(), version.payloadHash(), json.encode(version));
        jdbc.update("INSERT INTO itinerary_candidate(id,user_id,task_id,snapshot_id,run_id,generation,plan_step_id,plan_attempt_id,state) VALUES(?,?,?,?,?,?,?,?,'PRESENTED')", candidate, f.actor().id(), f.scope().taskId(), snapshot, c.fence().runId(), c.fence().generation(), c.fence().stepId(), c.fence().attemptId());
        return candidate;
    }
}
