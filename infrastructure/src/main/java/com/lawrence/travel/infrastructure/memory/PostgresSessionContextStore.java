package com.lawrence.travel.infrastructure.memory;

import com.lawrence.travel.application.memory.AgentContext;
import com.lawrence.travel.application.memory.ContextAssembler;
import com.lawrence.travel.application.memory.PreferenceChangedException;
import com.lawrence.travel.application.memory.SessionContext;
import com.lawrence.travel.application.memory.SessionContextService;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.ContextStore;
import com.lawrence.travel.application.port.SessionContextStore;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** 同一业务上下文端口装载聊天／旅行，框架重启从PG重建，不依赖旧文件或内存对象。 */
public final class PostgresSessionContextStore implements SessionContextStore {
    private final SessionContextMapper mapper;
    private final ContextMapper travel;
    private final ContextStore authority;
    private final BusinessTransactions tx;
    private final TravelJson json;
    private final com.lawrence.travel.infrastructure.execution.jobs.DiagnosticMapper diagnostics;

    /**
     * @param mapper 会话SQL
     * @param travel 归属查询
     * @param authority 旅行权威端口
     * @param tx 短事务
     * @param json 严格JSON
     * @param diagnostics 固定问题记录
     */
    public PostgresSessionContextStore(
            SessionContextMapper mapper,
            ContextMapper travel,
            ContextStore authority,
            BusinessTransactions tx,
            TravelJson json,
            com.lawrence.travel.infrastructure.execution.jobs.DiagnosticMapper diagnostics) {
        this.mapper = mapper;
        this.travel = travel;
        this.authority = authority;
        this.tx = tx;
        this.json = json;
        this.diagnostics = diagnostics;
    }

    /** {@inheritDoc} 只接受枚举式固定分类，与本次因果关联，不归档异常正文。 */
    @Override
    public UUID recordIssue(SessionContext.Source source, String reason) {
        return tx.required(
                () -> {
                    checked(source);
                    if (!java.util.Set.of("SUMMARY_FAILED", "SUMMARY_CACHE_INVALID")
                            .contains(reason))
                        throw new IllegalArgumentException("INVALID_CONTEXT_REASON");
                    var ids =
                            com.lawrence.travel.application.execution.ExecutionCorrelation
                                    .current();
                    UUID id = UUID.randomUUID();
                    diagnostics.problem(
                            id,
                            source.userId(),
                            ids.traceId(),
                            source.attemptId(),
                            ids.requestId(),
                            "CONTEXT",
                            reason);
                    return id;
                });
    }

    /** {@inheritDoc} 先区分真实输入和旅行，不创建占位旅行。 */
    @Override
    public SessionContext.Source load(UUID budget, String role) {
        return tx.required(
                () ->
                        snapshot(
                                budget,
                                role,
                                com.lawrence.travel.application.execution.ExecutionCorrelation
                                        .parentSpan()));
    }

    /** {@inheritDoc} token、原文、偏好或权威版本任一变化均拒绝旧结果。 */
    @Override
    public void guard(SessionContext.Source source) {
        tx.required(
                () -> {
                    checked(source);
                    return null;
                });
    }

    /** {@inheritDoc} 摘要绑定精确依赖；不能跨用户、角色、删除水位或偏好版本复用。 */
    @Override
    public SessionContext.Summary cached(SessionContext.Source source) {
        return tx.required(
                () -> {
                    checked(source);
                    String body =
                            mapper.cached(
                                    source,
                                    scope(source),
                                    SessionContextService.dependency(source));
                    return body == null ? null : json.decode(body, SessionContext.Summary.class);
                });
    }

    /** {@inheritDoc} 所有引用必须是实际前缀，取消后的摘要不得成为后续记忆。 */
    @Override
    public void save(SessionContext.Source source, SessionContext.Summary summary) {
        tx.required(
                () -> {
                    checked(source);
                    if (!summary.dependencyHash().equals(SessionContextService.dependency(source))
                            || summary.covered().size() > source.messages().size())
                        throw new IllegalArgumentException("SUMMARY_BINDING_INVALID");
                    var prefix = source.messages().subList(0, summary.covered().size());
                    if (!summary.covered()
                            .equals(
                                    prefix.stream()
                                            .map(
                                                    m ->
                                                            new AgentContext.MessageRef(
                                                                    m.id(), m.sequence(), m.hash()))
                                            .toList()))
                        throw new IllegalArgumentException("SUMMARY_COVERAGE_INVALID");
                    var originals = new LinkedHashMap<UUID, String>();
                    prefix.forEach(m -> originals.put(m.id(), m.text()));
                    summary.digest().verified(originals);
                    String body = json.encode(summary);
                    if (body.length() > 65536)
                        throw new IllegalArgumentException("SUMMARY_TOO_LARGE");
                    mapper.save(source, scope(source), summary, body);
                    return null;
                });
    }

    /**
     * @param source 原快照
     * @return 当前快照，旧绑定不重新贴标签
     */
    private SessionContext.Source checked(SessionContext.Source source) {
        var fresh = snapshot(source.budgetId(), source.role(), source.attemptId());
        if (!fresh.userId().equals(source.userId()) || !fresh.binding().equals(source.binding()))
            throw new PreferenceChangedException();
        return fresh;
    }

    /**
     * @param source 当前来源
     * @return 归属索引，run与chat独立存在
     */
    private UUID scope(SessionContext.Source source) {
        return source.chatId() == null ? source.budgetId() : source.chatId();
    }

    /**
     * @param budget 受信预算
     * @param role 固定角色
     * @param attempt 原阶段尝试，聊天为空
     * @return 完整近期来源和保护区
     */
    private SessionContext.Source snapshot(UUID budget, String role, UUID attempt) {
        ContextAssembler.limit(role);
        var operation = mapper.operation(budget);
        AgentContext.Source legacy = null;
        UUID user, chat;
        long before, epoch, token;
        Long preference;
        long handoffGeneration = 0, handoffRevision = 0;
        var core = new LinkedHashMap<String, Object>();
        if (operation != null) {
            if (!operation.live()) throw new PreferenceChangedException();
            user = operation.userId();
            chat = operation.chatId();
            before = operation.sequence();
            token = operation.token();
            preference = mapper.preference(user);
            epoch = mapper.epoch(user, chat);
            core.put(
                    "preferences",
                    mapper.preferences(user).stream()
                            .map(s -> json.decode(s, Object.class))
                            .toList());
            if (operation.taskId() != null) {
                var scope =
                        new com.lawrence.travel.domain.travel.TaskScope(user, operation.taskId());
                var task = travel.task(scope);
                if (task == null) throw new PreferenceChangedException();
                handoffGeneration = task.generation();
                handoffRevision = task.revision();
                core.put(
                        "requirements",
                        json.decode(travel.requirements(scope, task.revision()), Object.class));
                core.put("versions", json.decode(travel.versions(scope), Object.class));
            }
            core.put(
                    "unresolved",
                    mapper.unresolved(user, chat).stream()
                            .map(s -> json.decode(s, Object.class))
                            .toList());
        } else {
            legacy = authority.load(budget, role);
            if (attempt != null && !mapper.stageLive(budget, attempt))
                throw new PreferenceChangedException();
            user = legacy.scope().userId();
            String id = mapper.chat(user, legacy.scope().taskId());
            chat = id == null ? null : UUID.fromString(id);
            before = Long.MAX_VALUE;
            token = 0;
            preference = legacy.preferenceRevision();
            epoch = chat == null ? 0 : mapper.epoch(user, chat);
            core.put("travel", json.decode(legacy.authority(), Object.class));
        }
        if (preference == null) throw new PreferenceChangedException();
        // 统一角色交接只传可信身份、版本、允许工具和完成契约，不传上一个角色的任意自评。
        var handoff = new LinkedHashMap<String, Object>();
        handoff.put("schema", "role-handoff-v1");
        handoff.put("userId", user);
        handoff.put("chatId", chat);
        handoff.put("taskId", legacy == null ? operation.taskId() : legacy.scope().taskId());
        handoff.put("runId", legacy == null ? null : legacy.runId());
        handoff.put("budgetId", budget);
        handoff.put("generation", legacy == null ? handoffGeneration : legacy.generation());
        handoff.put(
                "constraintRevision",
                legacy == null ? handoffRevision : legacy.constraintRevision());
        handoff.put("preferenceRevision", preference);
        handoff.put("role", role);
        handoff.put(
                "allowedTools",
                operation != null && role.equals("coordinator")
                        ? List.of(
                                "ListDocuments",
                                "SearchDocument",
                                "ReadDocumentSection",
                                "ReadConversationSource",
                                "read_file")
                        : List.of());
        handoff.put(
                "completion",
                role.equals("reviewer")
                        ? "ISSUES_ONLY"
                        : role.equals("planner")
                                ? "PROPOSE_ONLY"
                                : role.equals("research")
                                        ? "EVIDENCE_OR_UNKNOWN"
                                        : "STRATEGY_OR_CLARIFY");
        handoff.put("blockedReason", "NONE");
        core.put("handoff", handoff);
        if (chat != null)
            core.put("documentSources", json.decode(mapper.documents(user, chat), Object.class));
        core.put("policy", "session-context-v1");
        core.put("preferenceRevision", preference);
        core.put("historyCannotGrantPermission", true);
        var messages = new ArrayList<SessionContext.Message>();
        if (!role.equals("reviewer")) {
            if (chat != null)
                for (var input : mapper.history(user, chat, before, preference)) {
                    messages.add(
                            message(
                                    input.id(),
                                    input.sequence() * 2,
                                    SessionContext.Role.USER,
                                    input.text()));
                    if (!input.reply().isBlank()
                            && List.of("DONE", "CLARIFY", "CONFIRM").contains(input.state()))
                        messages.add(
                                message(
                                        UUID.nameUUIDFromBytes(
                                                (input.id() + "/assistant")
                                                        .getBytes(StandardCharsets.UTF_8)),
                                        input.sequence() * 2 + 1,
                                        SessionContext.Role.ASSISTANT,
                                        input.reply()));
                }
            if (legacy != null && chat == null)
                for (var m : legacy.history())
                    messages.add(message(m.id(), m.sequence(), SessionContext.Role.USER, m.text()));
        }
        String data = json.encode(core),
                binding =
                        AgentContext.hash(
                                json.encode(
                                        List.of(
                                                user,
                                                budget,
                                                role,
                                                preference,
                                                epoch,
                                                token,
                                                operation == null && attempt != null
                                                        ? attempt.toString()
                                                        : "",
                                                data,
                                                messages)));
        return new SessionContext.Source(
                user,
                chat,
                budget,
                role,
                preference,
                epoch,
                token,
                operation == null ? attempt : null,
                binding,
                data,
                messages,
                legacy);
    }

    /**
     * @param id 来源
     * @param sequence 原顺序
     * @param role 原角色
     * @param text 未截断正文
     * @return 不允许历史SYSTEM的消息
     */
    private SessionContext.Message message(
            UUID id, long sequence, SessionContext.Role role, String text) {
        return new SessionContext.Message(
                id, sequence, role, text, AgentContext.hash(role + "/" + text), "");
    }
}
