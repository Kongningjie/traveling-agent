package com.lawrence.travel.infrastructure.persistence.control;

import com.lawrence.travel.application.port.ToolEvidenceJournal;
import com.lawrence.travel.application.port.TravelToolPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 免费资源和原文证据的 PostgreSQL 权威记录，不使用内存额度抵扣。 */
public final class PostgresToolEvidenceJournal implements ToolEvidenceJournal {
    private final ToolEvidenceMapper mapper;
    private final TransactionTemplate transaction;

    /**
     * 绑定同一业务数据库。
     * @param mapper 工具 SQL
     * @param manager 事务管理器
     */
    public PostgresToolEvidenceJournal(ToolEvidenceMapper mapper, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
    }

    /** {@inheritDoc} 只允许独立短事务；预占成功才允许发送。 */
    @Override
    public UUID reserve(UUID runId, String group, Instant verifiedAt, Instant validUntil, long allowance) {
        requireNoTransaction();
        var now = Instant.now();
        if (allowance < 1 || verifiedAt.isAfter(now) || !now.isBefore(validUntil)
                || validUntil.isAfter(verifiedAt.plus(Duration.ofHours(24)))) {
            throw new IllegalStateException("TOOL_QUOTA_UNVERIFIED");
        }
        return transaction.execute(status -> {
            mapper.lock(group);
            if (!Instant.now().isBefore(validUntil)) throw new IllegalStateException("TOOL_QUOTA_UNVERIFIED");
            if (mapper.uncertain(group, verifiedAt)) throw new IllegalStateException("TOOL_USAGE_UNKNOWN");
            if (mapper.count(group, verifiedAt) >= allowance) throw new IllegalStateException("TOOL_QUOTA_EXHAUSTED");
            UUID id = UUID.randomUUID();
            mapper.insert(id, runId, group);
            return id;
        });
    }

    /** {@inheritDoc} JSON 入库与 SHA256 绑定；失败不伪造原文或已知 credits。 */
    @Override
    public void finish(UUID id, TravelToolPort.Evidence evidence) {
        requireNoTransaction();
        mapper.finish(id, evidence == null ? null : evidence.source().toString(),
                evidence == null ? null : evidence.retrievedAt(), evidence == null ? null : evidence.payload(),
                evidence == null ? null : hash(evidence.payload()));
    }

    /** 禁止在外层未提交事务里提前返回网络发送许可。 */
    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("TOOL_AMBIENT_TRANSACTION");
    }

    /**
     * 对规范化 JSON 求哈希用于追溯，不记录凭证。
     * @param value 证据文本
     * @return SHA256 十六进制
     */
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
    }
}
