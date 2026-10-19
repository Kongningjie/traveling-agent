package com.lawrence.travel.infrastructure.document;

import com.lawrence.travel.application.document.DocumentFailure;
import com.lawrence.travel.application.document.SourceRead;
import com.lawrence.travel.application.memory.AgentContext;
import com.lawrence.travel.application.port.SourceReadStore;
import java.util.List;
import java.util.UUID;

/** PG权威引用；固定错误不泄露SQL或正文，调用者必须先进入短事务。 */
public final class PostgresSourceReadStore implements SourceReadStore {
    private final SourceReadMapper mapper;
    /** @param mapper 只读来源和受控工作文件SQL */
    public PostgresSourceReadStore(SourceReadMapper mapper) { this.mapper = mapper; }

    /** {@inheritDoc} 锁序始终user→chat，阻止读取与删除交错发布。 */
    @Override public SourceRead.Scope browser(UUID user, UUID chat) {
        return safe(() -> {
            if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("TRANSACTION_REQUIRED");
            Long preference = mapper.preference(user), revision = mapper.revision(user, chat);
            if (preference == null || revision == null) throw missing();
            return new SourceRead.Scope(user, chat, null, 0, revision, preference, Long.MAX_VALUE);
        });
    }

    /** {@inheritDoc} 锁后重读资格，调用方token不能被当前新尝试替换。 */
    @Override public SourceRead.Scope agent(UUID budget, long token) {
        return safe(() -> {
            if (token < 1) throw missing();
            var first = mapper.operation(budget); if (first == null) throw missing();
            browser(first.user(), first.chat()); var fresh = mapper.operation(budget);
            if (fresh == null || fresh.token() != token) throw missing();
            mapper.bind(fresh); if (mapper.bound(fresh) != fresh.revision()) throw missing(); return fresh;
        });
    }

    /** {@inheritDoc} 配额在用户锁下检查，路径重写不同正文不能覆盖旧原文。 */
    @Override public void save(SourceRead.Scope s, UUID id, UUID document, String body, String path) {
        safe(() -> {
            if (path != null) { var old = mapper.read(s, null, path); if (old != null) { if (!old.body().equals(body)) throw missing(); return null; } }
            if (body.length() > 100000 || mapper.full(s, body.length())) throw new DocumentFailure("SOURCE_CAPACITY");
            mapper.save(s, id, document, body, path, AgentContext.hash(body)); return null;
        });
    }

    /** {@inheritDoc} 当前资料/偏好水位及原件状态均必须有效。 */
    @Override public String read(SourceRead.Scope s, UUID id) { return value(s, id, null); }
    /** {@inheritDoc} 工作文件始终从PG恢复，不依赖本机缓存存在。 */
    @Override public String file(SourceRead.Scope s, String path) { return value(s, null, path); }
    /** {@inheritDoc} 不查询其他聊天、当前输入或旧偏好版本。 */
    @Override public List<SourceRead.Conversation> history(SourceRead.Scope s, String query) { return safe(() -> mapper.history(s, query)); }
    /** {@inheritDoc} 不把失败/取消尝试或其他消息的引用附在当前回复下。 */
    @Override public List<UUID> references(SourceRead.Scope s, UUID message) { return safe(() -> mapper.references(s, message).stream().map(UUID::fromString).toList()); }
    /** {@inheritDoc} 不复活取消或失败尝试的文件记忆。 */
    @Override public List<SourceRead.WorkFile> files(SourceRead.Scope s) { return safe(() -> mapper.files(s)); }
    /** {@inheritDoc} 调用前必须先通过file()验证工作文件来源资格。 */
    @Override public List<String> fileReferences(SourceRead.Scope s, String path) { return safe(() -> mapper.fileReferences(s, path)); }
    /** @param s 私有范围 @param id 引用 @param path 工作文件 @return 完整性通过的正文 */
    private String value(SourceRead.Scope s, UUID id, String path) { return safe(() -> {
        var result = mapper.read(s, id, path);
        if (result == null || !AgentContext.hash(result.body()).equals(result.hash())) throw missing(); return result.body();
    }); }
    /** @param action 固定SQL动作 @param <T> 返回类型 @return 脱敏结果 */
    private <T> T safe(java.util.function.Supplier<T> action) {
        try { return action.get(); } catch (org.springframework.dao.DataAccessException failure) { throw new DocumentFailure("SOURCE_STORAGE_UNAVAILABLE"); }
    }
    /** @return 跨用户、失效或不存在统一拒绝 */
    private static DocumentFailure missing() { return new DocumentFailure("SOURCE_NOT_FOUND"); }
}
