package com.lawrence.travel.infrastructure.document;

import com.lawrence.travel.application.document.Documents;
import com.lawrence.travel.application.document.DocumentFailure;
import com.lawrence.travel.application.execution.Jobs;
import com.lawrence.travel.application.port.DocumentStore;
import java.util.List;
import java.util.UUID;

/** PG附件仓储，仅做索引与配额，不调用其他适配器。 */
public final class PostgresDocumentStore implements DocumentStore {
    /** {@inheritDoc} 不直接改作业状态，保留预算/尝试/投影的唯一提交入口。 */
    @Override
    public List<UUID> revokedReaders(Jobs.Scope s) {
        return safe(() -> mapper.revokedReaders(s).stream().map(UUID::fromString).toList());
    }

    private final DocumentMapper mapper;
    private final com.lawrence.travel.application.document.DocumentLimits limits;

    /**
     * @param mapper 明确SQL
     */
    public PostgresDocumentStore(DocumentMapper mapper) {
        this(mapper, com.lawrence.travel.application.document.DocumentLimits.DEFAULT);
    }

    /**
     * @param mapper SQL
     * @param limits 有界配置
     */
    public PostgresDocumentStore(
            DocumentMapper mapper, com.lawrence.travel.application.document.DocumentLimits limits) {
        this.mapper = mapper;
        this.limits = limits;
    }

    /** {@inheritDoc} 缺失与越权统一固定分类。 */
    @Override
    public void lock(Jobs.Scope scope) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive())
            throw new IllegalStateException("TRANSACTION_REQUIRED");
        if (safe(() -> mapper.user(scope)) == null || safe(() -> mapper.chat(scope)) == null)
            throw new DocumentFailure("DOCUMENT_NOT_FOUND");
    }

    /** {@inheritDoc} 用户锁串行计算原件、文本预留及附件数量。 */
    @Override
    public void capacity(Jobs.Scope s, long bytes) {
        if (safe(() -> mapper.bytes(s)) + bytes > limits.userBytes()
                || safe(() -> mapper.characters(s, limits.text())) + limits.text()
                        > limits.userText()
                || safe(() -> mapper.count(s)) >= limits.perChat()
                || safe(() -> mapper.pending(s)) >= 10)
            throw new DocumentFailure("DOCUMENT_CAPACITY");
    }

    /** {@inheritDoc} 已有文件不再次占字节，完成后改用实际文本字符。 */
    @Override
    public void retryCapacity(Jobs.Scope s, UUID id) {
        if (safe(() -> mapper.otherCharacters(s, id, limits.text())) + limits.text()
                        > limits.userText()
                || safe(() -> mapper.pending(s)) >= 10)
            throw new DocumentFailure("DOCUMENT_CAPACITY");
    }

    /** {@inheritDoc} 与登记使用同一文件锁。 */
    @Override
    public java.util.Set<String> references() {
        return safe(mapper::references);
    }

    /** {@inheritDoc} 与作业及文件登记共用事务。 */
    @Override
    public void insert(Documents.Entry e) {
        safe(
                () -> {
                    mapper.insert(e);
                    return null;
                });
    }

    /** {@inheritDoc} 删除墓碑仅供受信维护读取。 */
    @Override
    public Documents.Entry get(Jobs.Scope s, UUID id) {
        var e = safe(() -> mapper.get(s, id));
        if (e == null) throw new DocumentFailure("DOCUMENT_NOT_FOUND");
        return e;
    }

    /** {@inheritDoc} 列表不带其他聊天。 */
    @Override
    public List<Documents.Entry> list(Jobs.Scope s) {
        return safe(() -> mapper.list(s));
    }

    /** {@inheritDoc} 完成提交与作业围栏同事务。 */
    @Override
    public void report(Jobs.Scope s, UUID id, String report) {
        safe(
                () -> {
                    mapper.report(s, id, report);
                    return null;
                });
    }

    /** {@inheritDoc} 墓碑不能被重试复活。 */
    @Override
    public void delete(Jobs.Scope s, UUID id) {
        safe(
                () -> {
                    mapper.delete(s, id);
                    return null;
                });
    }

    /** {@inheritDoc} 不读取正文。 */
    @Override
    public List<Documents.Entry> garbage() {
        return safe(mapper::garbage);
    }

    /** {@inheritDoc} 只处理删除墓碑。 */
    @Override
    public void purged(UUID id) {
        safe(
                () -> {
                    mapper.purged(id);
                    return null;
                });
    }

    /**
     * @param work 固定SQL
     * @param <T> 结果
     * @return 无SQL、文件名或正文异常链的固定存储错误
     */
    private <T> T safe(java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (org.springframework.dao.DataAccessException failure) {
            throw new DocumentFailure("DOCUMENT_STORAGE_UNAVAILABLE");
        }
    }
}
