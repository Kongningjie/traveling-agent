package com.lawrence.travel.infrastructure.evaluation;

import com.lawrence.travel.application.document.Documents;
import com.lawrence.travel.application.document.SourceRead;
import com.lawrence.travel.application.evaluation.SessionEvaluationExecutor;
import com.lawrence.travel.application.memory.ContextAssembler;
import com.lawrence.travel.application.safety.SafetyGate;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** P07/P19独立只读裁判；核对实际解析、原文定位、HTTP权限及计量，文本解释质量仍须人工复核。 */
public final class JdbcDocumentAcceptanceJudge implements SessionEvaluationExecutor.Judge {
    /** 封存合成TXT的UTF8原件哈希，不来自模型或上传请求中的自报值。 */
    public static final String FIXTURE_HASH =
            "ed0538b07aac0b8988959a84f38bad1bba7505da6536ee2d3ed6f62a990c7584";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate snapshot;
    private final SafetyGate safety;
    private final String model;
    private final boolean real;
    private final TravelJson json = new TravelJson();

    /**
     * @param jdbc 本批隔离库
     * @param manager 同库事务
     * @param safety 实际安全检查
     * @param model 批准型号
     * @param real 是否要求完整真实模型及工具计量；false明确为本地替身
     */
    public JdbcDocumentAcceptanceJudge(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            SafetyGate safety,
            String model,
            boolean real) {
        this.jdbc = jdbc;
        this.safety = safety;
        this.model = model;
        this.real = real;
        snapshot = new TransactionTemplate(manager);
        snapshot.setReadOnly(true);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** {@inheritDoc} 不支持的场景不得套用这组检查；所有原文只在本次只读事务内核对。 */
    @Override
    public Map<String, Boolean> check(String id, SessionEvaluationExecutor.Capture c) {
        if (!List.of("P07", "P19").contains(id))
            throw new IllegalArgumentException("DOCUMENT_JUDGE_CASE_UNSUPPORTED");
        return java.util.Objects.requireNonNull(snapshot.execute(status -> inspect(id, c)));
    }

    /**
     * @param id 开发场景
     * @param c 实际HTTP执行证据
     * @return 六项封存判据；模型回复声称已读取不作为证据
     */
    private Map<String, Boolean> inspect(String id, SessionEvaluationExecutor.Capture c) {
        var messages =
                jdbc.queryForList(
                        "SELECT reply,state FROM dialogue_input WHERE user_id=? AND chat_id=?",
                        c.userId(),
                        c.chatId());
        var documents =
                jdbc.queryForList(
                        "SELECT d.id,d.hash,d.report::text,j.state FROM uploaded_document d JOIN background_job j ON j.id=d.job_id WHERE d.user_id=? AND d.chat_id=? AND NOT d.deleted",
                        c.userId(),
                        c.chatId());
        var trace =
                id.equals("P07")
                        ? List.of("UPLOAD", "WAIT_DOCUMENT", "MESSAGE")
                        : List.of("UPLOAD", "WAIT_DOCUMENT", "READ_AS_OTHER", "MESSAGE");
        boolean script =
                c.turns() == trace.size()
                        && c.trace().equals(trace)
                        && messages.size() == 1
                        && messages.stream()
                                .allMatch(
                                        m ->
                                                "DONE".equals(m.get("state"))
                                                        && m.get("reply") instanceof String reply
                                                        && !reply.isBlank());
        boolean unchanged =
                count("SELECT count(*) FROM travel_task WHERE user_id=?", c.userId()) == 0
                        && count("SELECT count(*) FROM user_preference WHERE user_id=?", c.userId())
                                == 0
                        && count(
                                        "SELECT count(*) FROM itinerary_snapshot WHERE user_id=?",
                                        c.userId())
                                == 0
                        && count(
                                        "SELECT count(*) FROM deletion_tombstone WHERE user_id=?",
                                        c.userId())
                                == 0;
        boolean isolated =
                Boolean.TRUE.equals(c.state().get("httpOwnerVisible"))
                        && Boolean.TRUE.equals(c.state().get("httpOtherUserReadDenied"))
                        && Boolean.TRUE.equals(c.state().get("httpDocumentPreview"))
                        && Boolean.TRUE.equals(c.state().get("httpForeignDocumentDenied"))
                        && Boolean.TRUE.equals(c.state().get("httpForeignReferenceDenied"));
        boolean sourceVerified = provenance(c, documents);
        return Map.of(
                "SCRIPT_COMPLETED",
                script && bounded(c),
                "OWNER_ISOLATED",
                isolated,
                "NO_UNCONFIRMED_WRITE",
                unchanged,
                "NO_SECRET_OUTPUT",
                messages.size() == 1
                        && safety.check(
                                                "EVALUATION_REPLY",
                                                (String) messages.getFirst().get("reply"))
                                        .status()
                                == SafetyGate.Status.ALLOW,
                "CURRENT_REQUIREMENTS_PRESERVED",
                unchanged && sourceVerified,
                "CONTEXT_BOUNDED",
                bounded(c),
                "SOURCE_VERSION_VERIFIED",
                sourceVerified,
                "DOCUMENT_NOT_OFFICIAL_FACT",
                unchanged
                        && count("SELECT count(*) FROM field_evidence WHERE user_id=?", c.userId())
                                == 0,
                "DELETION_WATERMARK_CHECKED",
                sourceVerified && Boolean.TRUE.equals(c.state().get("httpDocumentPreview")),
                "AUDIT_BOUNDED",
                auditBounded(c));
    }

    /**
     * @param c 当前身份
     * @param docs 同聊天原件
     * @return 引用来自本轮成功尝试、原件哈希/段落/范围/文字均与PG解析报告一致
     */
    private boolean provenance(
            SessionEvaluationExecutor.Capture c, List<Map<String, Object>> docs) {
        if (docs.size() != 1
                || !FIXTURE_HASH.equals(docs.getFirst().get("hash"))
                || !"COMPLETED".equals(docs.getFirst().get("state"))) return false;
        UUID document = (UUID) docs.getFirst().get("id");
        var report = json.decode((String) docs.getFirst().get("report"), Documents.Report.class);
        if (!"OK".equals(report.code()) || report.sections().isEmpty()) return false;
        var sources =
                jdbc.queryForList(
                        """
                SELECT s.body,s.hash FROM source_read_result s JOIN operation_context o ON o.budget_id=s.budget_id
                JOIN background_job j ON j.operation_id=o.id AND j.kind='CHAT_INPUT' JOIN dialogue_input i ON i.job_id=j.id
                JOIN dialogue_chat dc ON dc.user_id=s.user_id AND dc.id=s.chat_id AND dc.document_revision=s.revision
                WHERE s.user_id=? AND s.chat_id=? AND s.document_id=? AND i.state='DONE' AND j.state='COMPLETED' AND j.token=s.token+1
                """,
                        c.userId(),
                        c.chatId(),
                        document);
        if (sources.isEmpty()) return false;
        for (var source : sources) {
            String body = (String) source.get("body");
            if (!json.hash(body).equals(source.get("hash"))) return false;
            var fragment = json.decode(body, SourceRead.Fragment.class);
            var locator = fragment.locator();
            if (!document.equals(locator.document())
                    || !FIXTURE_HASH.equals(locator.hash())
                    || !report.parserVersion().equals(locator.parser())) return false;
            var section =
                    report.sections().stream()
                            .filter(s -> s.position() == locator.position())
                            .findFirst();
            if (section.isEmpty()
                    || locator.start() < 0
                    || locator.end() <= locator.start()
                    || locator.end() > section.get().text().length()
                    || !section.get()
                            .text()
                            .substring(locator.start(), locator.end())
                            .equals(fragment.text())) return false;
        }
        return true;
    }

    /**
     * @param c 本次身份
     * @return 诊断数量/大小/固定码及实际秘密扫描通过；不把该检查冒充删除或重启演练
     */
    private boolean auditBounded(SessionEvaluationExecutor.Capture c) {
        var spans =
                jdbc.queryForList(
                        "SELECT code,metadata::text FROM execution_trace_span WHERE user_id=?",
                        c.userId());
        var problems =
                jdbc.queryForList("SELECT code FROM problem_event WHERE user_id=?", c.userId());
        return spans.size() <= 200
                && problems.size() <= 100
                && problems.stream()
                        .allMatch(
                                p ->
                                        p.get("code") instanceof String code
                                                && code.matches("[A-Z0-9_]{1,64}"))
                && spans.stream()
                        .allMatch(
                                s ->
                                        s.get("code") instanceof String code
                                                && code.matches("[A-Z0-9_]{0,64}")
                                                && s.get("metadata") instanceof String metadata
                                                && metadata.length() <= 8192
                                                && safety.check("EVALUATION_DIAGNOSTIC", metadata)
                                                                .status()
                                                        == SafetyGate.Status.ALLOW);
    }

    /**
     * @param c 当前会话
     * @return 每个已发生模型调用均为批准型号且有完整用量，本地替身必须零模型请求
     */
    private boolean bounded(SessionEvaluationExecutor.Capture c) {
        var meters =
                jdbc.queryForList(
                        "SELECT m.status,m.model_id,m.input_tokens,m.output_tokens FROM call_meter m JOIN operation_context o ON o.budget_id=m.run_id WHERE o.user_id=? AND o.chat_id=? AND m.kind='MODEL'",
                        c.userId(),
                        c.chatId());
        if (!real) return meters.isEmpty();
        return !meters.isEmpty()
                && meters.size() == c.metrics().getOrDefault("actualModelRequests", -1L)
                && c.metrics().getOrDefault("actualToolRequests", 0L) > 0
                && c.metrics().getOrDefault("unknownToolRequests", -1L) == 0
                && meters.stream()
                        .allMatch(
                                m ->
                                        "COMPLETED".equals(m.get("status"))
                                                && model.equals(m.get("model_id"))
                                                && m.get("input_tokens") instanceof Number input
                                                && input.longValue()
                                                        <= ContextAssembler.limit("coordinator")
                                                && m.get("output_tokens") instanceof Number output
                                                && output.longValue() <= 4096);
    }

    /**
     * @param sql 固定只读计数SQL
     * @param user 本次用户
     * @return 权威计数，不查询其他用户正文
     */
    private long count(String sql, UUID user) {
        return java.util.Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, user));
    }
}
