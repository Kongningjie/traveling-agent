package com.lawrence.travel.infrastructure.memory;

import com.lawrence.travel.application.interaction.Negotiation;
import com.lawrence.travel.application.memory.AgentContext;
import com.lawrence.travel.application.memory.PreferenceChangedException;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.ContextStore;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.domain.execution.ExecutionRun;
import com.lawrence.travel.domain.memory.Preference;
import com.lawrence.travel.domain.memory.PreferenceVersion;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.NOT_FOUND;

/** 数据库上下文和摘要唯一权威；共享锁读快照，模型调用在事务外，写回再次检查同一版本。 */
public final class PostgresContextStore implements ContextStore {
    private final ContextMapper mapper;
    private final TravelJson json;
    private final BusinessTransactions transactions;

    /**
     * @param mapper 私有SQL
     * @param json 规范JSON
     * @param transactions 短事务
     */
    public PostgresContextStore(
            ContextMapper mapper, TravelJson json, BusinessTransactions transactions) {
        this.mapper = mapper;
        this.json = json;
        this.transactions = transactions;
    }

    /** {@inheritDoc} 不依赖旧工作区、摘要或框架历史推断约束。 */
    @Override
    public AgentContext.Source load(UUID runId, String role) {
        return transactions.required(() -> snapshot(runId, role));
    }

    /** {@inheritDoc} 失效绑定保留审计但不参与召回。 */
    @Override
    public AgentContext.Summary cached(AgentContext.Source source) {
        return transactions.required(
                () -> {
                    checked(source);
                    mapper.invalidate(source);
                    return cachedAt(source);
                });
    }

    /** {@inheritDoc} 保存是独立产物缓存，不推进运行或阶段，不移动行程指针。 */
    @Override
    public AgentContext.Summary save(AgentContext.Source source, AgentContext.Summary summary) {
        return transactions.required(
                () -> {
                    var fresh = checked(source);
                    if (!summary.sourceHash().equals(fresh.bindingHash())
                            || !summary.protectedHash().equals(json.hash(fresh.authority()))
                            || !summary.covered()
                                    .equals(
                                            fresh.history().stream()
                                                    .map(
                                                            m ->
                                                                    new AgentContext.MessageRef(
                                                                            m.id(),
                                                                            m.sequence(),
                                                                            m.hash()))
                                                    .toList()))
                        throw new IllegalArgumentException("SUMMARY_BINDING_INVALID");
                    var originals = new LinkedHashMap<UUID, String>();
                    fresh.history().forEach(m -> originals.put(m.id(), m.text()));
                    summary.digest().verified(originals);
                    if (summary.beforeTokens() < 0
                            || summary.afterTokens() < 0
                            || source.role().equals("reviewer"))
                        throw new IllegalArgumentException("SUMMARY_AUDIT_INVALID");
                    var audit = summary.audit();
                    var authority = json.decode(fresh.authority(), Map.class);
                    if (audit == null
                            || audit.constraintRevision() != fresh.constraintRevision()
                            || audit.preferenceRevision() != fresh.preferenceRevision()
                            || audit.generation() != fresh.generation()
                            || audit.watermark() != fresh.watermark()
                            || audit.requestHash() == null
                            || !audit.requestHash().matches("[0-9a-f]{64}")
                            || !json.encode(audit.versions())
                                    .equals(json.encode(authority.get("versions")))
                            || !json.encode(audit.evidenceReferences())
                                    .equals(
                                            json.encode(
                                                    authority.get("selectedEvidenceReferences"))))
                        throw new IllegalArgumentException("SUMMARY_AUDIT_BINDING_INVALID");
                    mapper.invalidate(source);
                    mapper.save(source, summary, json.encode(summary));
                    return Objects.requireNonNull(cachedAt(source));
                });
    }

    /** {@inheritDoc} 每次发送前短事务核对，随后释放所有锁；C事务仍拥有最终提交权。 */
    @Override
    public void guard(AgentContext.Source source) {
        transactions.required(
                () -> {
                    checked(source);
                    return true;
                });
    }

    /** {@inheritDoc} 按需读取仍核对用户、任务、偏好版本和实际来源，不接受模型指定其他归属。 */
    @Override
    public String original(AgentContext.Source source, UUID messageId) {
        return transactions.required(
                () -> {
                    var current = checked(source);
                    if (current.role().equals("reviewer")
                            || current.history().stream().noneMatch(m -> m.id().equals(messageId)))
                        throw new TravelStorageFailure(NOT_FOUND);
                    String result =
                            mapper.original(
                                    current.scope(), messageId, current.preferenceRevision());
                    if (result == null) throw new TravelStorageFailure(NOT_FOUND);
                    return result;
                });
    }

    /**
     * @param source 旧版本
     * @return 与原输入绑定一致的最新读取；偏好／需求／证据变化不重新贴标签
     */
    private AgentContext.Source checked(AgentContext.Source source) {
        var fresh = snapshot(source.runId(), source.role());
        if (!fresh.scope().equals(source.scope())
                || !fresh.bindingHash().equals(source.bindingHash()))
            throw new PreferenceChangedException();
        return fresh;
    }

    /**
     * @param source 当前精确绑定
     * @return 摘要原版本，不从其他运行或角色回退
     */
    private AgentContext.Summary cachedAt(AgentContext.Source source) {
        String body = mapper.cached(source);
        return body == null ? null : json.decode(body, AgentContext.Summary.class);
    }

    /**
     * @param runId 受信运行
     * @param role 固定角色
     * @return 同事务装配的当前权威状态，审查角色严格排除会话
     */
    private AgentContext.Source snapshot(UUID runId, String role) {
        com.lawrence.travel.application.memory.ContextAssembler.limit(role);
        var row = mapper.run(runId);
        if (row == null) throw new TravelStorageFailure(NOT_FOUND);
        var scope = new TaskScope(row.userId(), row.taskId());
        Long preference = mapper.user(scope);
        if (preference == null) throw new TravelStorageFailure(NOT_FOUND);
        var task = mapper.task(scope);
        if (task == null) throw new TravelStorageFailure(NOT_FOUND);
        var run = json.decode(mapper.run(runId).body(), ExecutionRun.class);
        if (!runId.equals(task.currentRunId())
                || run.generation() != task.generation()
                || !Set.of(ExecutionRun.State.QUEUED, ExecutionRun.State.RUNNING)
                        .contains(run.state())) throw new PreferenceChangedException();
        var revision =
                json.decode(mapper.requirements(scope, task.revision()), ConstraintRevision.class);
        var cities =
                revision.requirements().stream()
                        .filter(r -> r.field().equals("cities"))
                        .flatMap(r -> Arrays.stream(r.value().split(",")))
                        .map(String::trim)
                        .collect(java.util.stream.Collectors.toSet());
        var overridden =
                revision.requirements().stream()
                        .filter(r -> r.source() != ConstraintRevision.Source.ASSUMPTION)
                        .map(ConstraintRevision.Requirement::field)
                        .collect(java.util.stream.Collectors.toSet());
        var preferences =
                Preference.recall(
                        new Preference.View(
                                new PreferenceVersion(scope.userId(), preference),
                                mapper.preferences(scope).stream()
                                        .map(body -> json.decode(body, Preference.class))
                                        .toList()),
                        cities,
                        overridden);
        var core = new LinkedHashMap<String, Object>();
        core.put(
                "contextPolicyVersion",
                com.lawrence.travel.application.memory.ContextAssembler.POLICY_VERSION);
        core.put("scope", scope);
        core.put("constraintRevision", revision);
        core.put("generation", task.generation());
        core.put("versions", json.decode(mapper.versions(scope), Object.class));
        core.put("preferences", preferences);
        core.put(
                "selectedEvidenceReferences",
                mapper.evidence(scope).stream()
                        .map(body -> json.decode(body, Object.class))
                        .toList());
        var history = new ArrayList<AgentContext.Message>();
        if (!role.equals("reviewer")) {
            core.put(
                    "unresolvedQuestions",
                    mapper.questions(scope, runId).stream()
                            .map(body -> json.decode(body, Object.class))
                            .toList());
            var decisions = new ArrayList<Map<String, Object>>();
            for (var message : mapper.decisions(scope)) {
                var response = json.decode(message.answer(), Negotiation.Response.class);
                var fact = new LinkedHashMap<String, Object>();
                fact.put("messageId", message.id());
                fact.put("status", message.status());
                fact.put("decisionId", response.decisionId());
                fact.put("decisionRevision", response.revision());
                fact.put("constraintRevision", response.constraints());
                fact.put("action", response.action());
                fact.put("selected", response.selected());
                fact.put("authorizationScope", response.authorization());
                // 旧授权只是来源记录，不能作为新授权；偏好变化后旧叙述只保留引用，防止从拒绝理由复活删除文本。
                fact.put("grantsNewPermission", false);
                fact.put(
                        "reason",
                        message.preferenceRevision() == preference ? response.text() : "");
                fact.put("reasonRef", message.id());
                decisions.add(fact);
            }
            core.put("decisionLedger", decisions);
            core.put("messageWatermark", task.watermark());
            core.put("pendingInputs", json.decode(pending(scope), Object.class));
            for (var message : mapper.messages(scope, preference)) {
                var body = json.decode(message.body(), Negotiation.Message.class);
                if (!body.text().isBlank())
                    history.add(
                            new AgentContext.Message(
                                    message.id(),
                                    message.sequence(),
                                    body.text(),
                                    json.hash(json.encode(message))));
            }
            core.put(
                    "historyWindow",
                    Map.of(
                            "maximumMessages",
                            50,
                            "firstIncludedSequence",
                            history.isEmpty() ? 0 : history.getFirst().sequence(),
                            "fullHistoryRemainsInDatabase",
                            true));
        }
        String authority = json.encode(core);
        String binding =
                json.hash(
                        json.encode(
                                List.of(
                                        role,
                                        task.revision(),
                                        preference,
                                        task.generation(),
                                        task.watermark(),
                                        authority,
                                        history)));
        return new AgentContext.Source(
                scope,
                runId,
                role,
                task.revision(),
                preference,
                task.generation(),
                task.watermark(),
                authority,
                binding,
                List.copyOf(history));
    }

    /**
     * @param scope 已锁定任务
     * @return 未决输入身份与状态，不能通过摘要标为已处理
     */
    private String pending(TaskScope scope) {
        return mapper.pending(scope);
    }
}
