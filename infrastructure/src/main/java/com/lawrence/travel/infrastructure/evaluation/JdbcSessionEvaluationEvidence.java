package com.lawrence.travel.infrastructure.evaluation;

import com.lawrence.travel.application.evaluation.SessionEvaluationExecutor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 显式评测装配的只读PG观察器；计量来自实际账本，不读取模型自报分数，不自动装配到生产。 */
public final class JdbcSessionEvaluationEvidence implements HttpSessionEvaluationDriver.EvidenceReader {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate snapshot;
    private final Set<String> modelIds;

    /** @param jdbc 同一验收后端的数据库，调用方不能将其他环境的账本拼接到报告
     * @param manager 同数据源事务管理器
     * @param modelIds 本次明确授权的精确型号白名单；替身型号不得登记为真实用量 */
    public JdbcSessionEvaluationEvidence(JdbcTemplate jdbc, PlatformTransactionManager manager, Set<String> modelIds) {
        if (modelIds == null || modelIds.isEmpty() || modelIds.size() > 8 || modelIds.stream().anyMatch(id -> !id.matches("[A-Za-z0-9._-]{1,100}"))) throw new IllegalArgumentException("EVALUATION_MODELS_INVALID");
        this.jdbc = java.util.Objects.requireNonNull(jdbc); this.modelIds = Set.copyOf(modelIds);
        snapshot = new TransactionTemplate(manager); snapshot.setReadOnly(true);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** {@inheritDoc} 每次短只读快照只观察指定用户/聊天；缺失不算成功，不导出正文、密码或认证字段。
     * 此类只提供事实，权限、硬约束、来源和语义检查仍由独立Judge/人工完成，不能把计数当全部通过。 */
    @Override public SessionEvaluationExecutor.Capture read(UUID user, UUID chat, int turns) {
        return java.util.Objects.requireNonNull(snapshot.execute(status -> capture(user, chat, turns)));
    }

    /** @param user 本次合成身份 @param chat 原会话 @param turns 已完成步骤
     * @return 同一MVCC快照的实际状态；所有预算分支去重，防止聊天/旅行交接重复计量 */
    private SessionEvaluationExecutor.Capture capture(UUID user, UUID chat, int turns) {
        var metrics = new HashMap<String, Long>();
        for (String key : List.of("actualModelRequests", "unknownModelRequests", "actualInputTokens", "actualOutputTokens", "actualToolRequests", "unknownToolRequests", "unrecognizedModelRequests")) metrics.put(key, 0L);
        var meters = jdbc.queryForList("""
                SELECT m.kind,m.model_id,m.status,m.input_tokens,m.output_tokens FROM call_meter m
                WHERE m.run_id IN (
                  SELECT budget_id FROM operation_context WHERE user_id=? AND chat_id=?
                  UNION SELECT budget_id FROM dialogue_chat WHERE user_id=? AND id=?
                  UNION SELECT r.id FROM execution_run r JOIN dialogue_chat c ON c.user_id=r.user_id AND c.task_id=r.task_id WHERE c.user_id=? AND c.id=?)
                """, user, chat, user, chat, user, chat);
        for (var meter : meters) {
            if ("TOOL".equals(meter.get("kind"))) { metrics.merge("COMPLETED".equals(meter.get("status")) ? "actualToolRequests" : "unknownToolRequests", 1L, Long::sum); continue; }
            if (!"MODEL".equals(meter.get("kind"))) continue;
            if (!modelIds.contains(meter.get("model_id"))) { metrics.merge("unrecognizedModelRequests", 1L, Long::sum); continue; }
            if (!"COMPLETED".equals(meter.get("status"))) { metrics.merge("unknownModelRequests", 1L, Long::sum); continue; }
            metrics.merge("actualModelRequests", 1L, Long::sum);
            metrics.merge("actualInputTokens", ((Number) meter.get("input_tokens")).longValue(), Long::sum);
            metrics.merge("actualOutputTokens", ((Number) meter.get("output_tokens")).longValue(), Long::sum);
        }
        long chats = count("SELECT count(*) FROM dialogue_chat WHERE user_id=? AND id=?", user, chat);
        long deleted = count("SELECT count(*) FROM deletion_tombstone WHERE user_id=? AND kind='CHAT' AND target_id=?", user, chat);
        var states = jdbc.queryForList("SELECT state FROM dialogue_input WHERE user_id=? AND chat_id=? ORDER BY sequence", String.class, user, chat);
        var tasks = jdbc.queryForList("SELECT r.state FROM execution_run r JOIN travel_task t ON t.user_id=r.user_id AND t.current_run_id=r.id JOIN dialogue_chat c ON c.user_id=t.user_id AND c.task_id=t.id WHERE c.user_id=? AND c.id=?", String.class, user, chat);
        String outcome = chats == 0 ? (deleted > 0 ? "COMPLETED" : "PAUSED_CHAT_MISSING") : "COMPLETED";
        if (states.stream().anyMatch(s -> List.of("QUEUED", "RUNNING").contains(s)) || tasks.stream().anyMatch(s -> List.of("QUEUED", "RUNNING").contains(s))) outcome = "PAUSED_SESSION_ACTIVE";
        else if (states.contains("PAUSED") || tasks.stream().anyMatch(s -> s.startsWith("PAUSED"))) outcome = "PAUSED_SESSION";
        else if (tasks.contains("FAILED") || tasks.contains("SUPERSEDED")) outcome = "FAILED";
        else if (states.stream().anyMatch(s -> List.of("CLARIFY", "CONFIRM").contains(s)) || tasks.contains("WAITING_USER")) outcome = "WAITING_USER";
        return new SessionEvaluationExecutor.Capture(user, chat, outcome, turns,
                Map.of("chatExists", chats == 1, "deleted", deleted > 0, "messageStates", List.copyOf(states), "taskStates", List.copyOf(tasks)), metrics, List.of("PG_SNAPSHOT"));
    }

    /** @param sql 固定只读SQL @param user 本次身份 @param target 指定对象 @return 权威计数，不查询其他用户正文 */
    private long count(String sql, UUID user, UUID target) { return java.util.Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, user, target)); }
}
