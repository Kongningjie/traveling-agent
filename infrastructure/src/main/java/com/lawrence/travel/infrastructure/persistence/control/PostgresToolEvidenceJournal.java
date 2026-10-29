package com.lawrence.travel.infrastructure.persistence.control;

import com.lawrence.travel.application.port.ToolEvidenceJournal;
import com.lawrence.travel.application.port.TravelToolPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 工具调用和原文证据的 PostgreSQL 权威记录，不执行免费配额限制。 */
public final class PostgresToolEvidenceJournal implements ToolEvidenceJournal {
    private final ToolEvidenceMapper mapper;
    private final TransactionTemplate transaction;

    /**
     * 绑定工具调用记录与独立事务。
     *
     * @param mapper 工具SQL
     * @param manager 数据库事务管理器
     */
    public PostgresToolEvidenceJournal(
            ToolEvidenceMapper mapper, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
    }

    /** {@inheritDoc} 调用记录提交后才允许发送，历史未知记录不阻断新调用。 */
    @Override
    public UUID reserve(UUID runId, String group) {
        requireNoTransaction();
        java.util.Objects.requireNonNull(runId);
        if (group == null || group.isBlank())
            throw new IllegalArgumentException("TOOL_GROUP_INVALID");
        return transaction.execute(
                status -> {
                    UUID id = UUID.randomUUID();
                    mapper.insert(id, runId, group);
                    return id;
                });
    }

    /** {@inheritDoc} JSON 入库与 SHA256 绑定；失败不伪造原文或已知 credits。 */
    @Override
    public void finish(UUID id, TravelToolPort.Evidence evidence) {
        requireNoTransaction();
        mapper.finish(
                id,
                evidence == null ? null : evidence.source().toString(),
                evidence == null ? null : evidence.retrievedAt(),
                evidence == null ? null : evidence.payload(),
                evidence == null ? null : hash(evidence.payload()));
    }

    /** 禁止在外层未提交事务里提前返回网络发送许可。 */
    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("TOOL_AMBIENT_TRANSACTION");
    }

    /**
     * 对规范化 JSON 求哈希用于追溯，不记录凭证。
     *
     * @param value 证据文本
     * @return SHA256 十六进制
     */
    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA256_UNAVAILABLE");
        }
    }
}
