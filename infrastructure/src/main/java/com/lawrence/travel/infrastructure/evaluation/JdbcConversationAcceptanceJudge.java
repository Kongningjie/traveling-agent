package com.lawrence.travel.infrastructure.evaluation;

import com.lawrence.travel.application.evaluation.SessionEvaluationExecutor;
import com.lawrence.travel.application.memory.ContextAssembler;
import com.lawrence.travel.application.safety.SafetyGate;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** P01/P02专用独立程序裁判；只读权威状态和HTTP审计，不由回复自评安全或语义，不自动装配生产。 */
public final class JdbcConversationAcceptanceJudge implements SessionEvaluationExecutor.Judge {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate snapshot;
    private final SafetyGate safety;
    private final String model;
    private final boolean real;
    private final TravelJson json = new TravelJson();

    /** @param jdbc 隔离验收库 @param manager 同库事务 @param safety 实际私密值保护 @param model 唯一批准的精确型号
     * @param real 是否要求实际完整模型计量；本地替身必须明确false */
    public JdbcConversationAcceptanceJudge(JdbcTemplate jdbc, PlatformTransactionManager manager, SafetyGate safety, String model, boolean real) {
        this.jdbc = jdbc; this.safety = safety; this.model = model; this.real = real;
        snapshot = new TransactionTemplate(manager); snapshot.setReadOnly(true);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** {@inheritDoc} 只支持已实现程序判据的两例；其他场景不得复用这些true。 */
    @Override public Map<String,Boolean> check(String caseId, SessionEvaluationExecutor.Capture capture) {
        if (!Set.of("P01", "P02").contains(caseId)) throw new IllegalArgumentException("JUDGE_CASE_NOT_SUPPORTED");
        return java.util.Objects.requireNonNull(snapshot.execute(status -> inspect(caseId, capture)));
    }

    /** @param id 封存用例键 @param c 实际私有身份与HTTP事实 @return 六项独立程序检查，语义另由人工确认 */
    private Map<String,Boolean> inspect(String id, SessionEvaluationExecutor.Capture c) {
        var messages = jdbc.queryForList("SELECT text,reply,state FROM dialogue_input WHERE user_id=? AND chat_id=? ORDER BY sequence", c.userId(), c.chatId());
        var tasks = jdbc.queryForList("SELECT t.id,t.constraint_revision,t.current_version_id FROM travel_task t JOIN dialogue_chat c ON c.user_id=t.user_id AND c.task_id=t.id WHERE c.user_id=? AND c.id=?", c.userId(), c.chatId());
        long allTasks = count("SELECT count(*) FROM travel_task WHERE user_id=?", c.userId());
        boolean noWrites = count("SELECT count(*) FROM user_preference WHERE user_id=?", c.userId()) == 0
                && count("SELECT count(*) FROM itinerary_snapshot WHERE user_id=?", c.userId()) == 0
                && count("SELECT count(*) FROM deletion_tombstone WHERE user_id=?", c.userId()) == 0
                && (id.equals("P01") ? allTasks == 0 : allTasks == 1 && tasks.size() == 1 && tasks.getFirst().get("current_version_id") == null);
        boolean requirements = id.equals("P01") ? tasks.isEmpty() && allTasks == 0 : requirements(c, tasks);
        var checks = new HashMap<String,Boolean>();
        checks.put("OWNER_ISOLATED", Boolean.TRUE.equals(c.state().get("httpOwnerVisible"))
                && Boolean.TRUE.equals(c.state().get("httpOtherUserReadDenied"))
                && count("SELECT count(*) FROM dialogue_chat WHERE user_id=? AND id=?", c.userId(), c.chatId()) == 1);
        checks.put("NO_UNCONFIRMED_WRITE", noWrites);
        checks.put("NO_SECRET_OUTPUT", messages.size() == 2 && messages.stream().allMatch(m -> safety.check("EVALUATION_REPLY", (String)m.get("reply")).status() == SafetyGate.Status.ALLOW));
        checks.put("SCRIPT_COMPLETED", c.turns() == 2 && c.trace().equals(List.of("MESSAGE", "MESSAGE"))
                && messages.size() == 2 && messages.stream().allMatch(m -> "DONE".equals(m.get("state")) && !((String)m.get("reply")).isBlank()));
        checks.put("CONTEXT_BOUNDED", bounded(c)); checks.put("CURRENT_REQUIREMENTS_PRESERVED", requirements);
        return Map.copyOf(checks);
    }

    /** @param c 实际身份 @param tasks 仅当前聊天关联的旅行 @return 原四字段保留、第二轮新版本及用户新增要求存在；不代替语义复核 */
    private boolean requirements(SessionEvaluationExecutor.Capture c, List<Map<String,Object>> tasks) {
        if (tasks.size() != 1 || ((Number)tasks.getFirst().get("constraint_revision")).longValue() < 3) return false;
        String body = jdbc.queryForObject("SELECT payload::text FROM travel_constraint_revision WHERE user_id=? AND task_id=? AND revision=?", String.class,
                c.userId(), tasks.getFirst().get("id"), tasks.getFirst().get("constraint_revision"));
        var root = json.decode(body, Map.class); var fields = new HashMap<String,String>();
        if (!(root.get("fixedArrangements") instanceof List<?> fixed) || !fixed.isEmpty()) return false;
        for (Object item : (List<?>)root.get("requirements")) {
            var value = (Map<?,?>)item; if (!"USER".equals(value.get("source"))) return false;
            fields.put((String)value.get("field"), (String)value.get("value"));
        }
        return "北京".equals(fields.get("cities")) && "2026-11-01".equals(fields.get("startDate"))
                && "2026-11-03".equals(fields.get("endDate")) && "2".equals(fields.get("travelers"))
                && Set.of("cities", "startDate", "endDate", "travelers", "pace", "hardRequirements").containsAll(fields.keySet())
                && fields.entrySet().stream().anyMatch(e -> Set.of("pace", "hardRequirements").contains(e.getKey()) && !e.getValue().isBlank());
    }

    /** @param c 实际身份 @return 每个实际模型请求都在批准型号/输入/输出边界；本地模式要求没有模型请求 */
    private boolean bounded(SessionEvaluationExecutor.Capture c) {
        var rows = jdbc.queryForList("""
                SELECT status,model_id,input_tokens,output_tokens FROM call_meter WHERE kind='MODEL' AND run_id IN (
                  SELECT budget_id FROM operation_context WHERE user_id=? AND chat_id=?
                  UNION SELECT budget_id FROM dialogue_chat WHERE user_id=? AND id=?
                  UNION SELECT r.id FROM execution_run r JOIN dialogue_chat c ON c.user_id=r.user_id AND c.task_id=r.task_id WHERE c.user_id=? AND c.id=?)
                """, c.userId(), c.chatId(), c.userId(), c.chatId(), c.userId(), c.chatId());
        if (!real) return rows.isEmpty();
        return rows.size() >= 2 && rows.size() == c.metrics().getOrDefault("actualModelRequests", -1L)
                && rows.stream().allMatch(m -> "COMPLETED".equals(m.get("status")) && model.equals(m.get("model_id"))
                && m.get("input_tokens") instanceof Number input && input.longValue() <= ContextAssembler.limit("coordinator")
                && m.get("output_tokens") instanceof Number output && output.longValue() <= 4096);
    }

    /** @param sql 固定SQL @param args 当前私有身份/对象 @return 同一只读快照内实际计数 */
    private long count(String sql, Object... args) { return java.util.Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
}
