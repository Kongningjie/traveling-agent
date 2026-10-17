package com.lawrence.travel.infrastructure.memory;

import com.lawrence.travel.application.port.PreferenceStore;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.domain.memory.Preference;
import com.lawrence.travel.domain.memory.PreferenceVersion;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.IDEMPOTENCY_CONFLICT;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.INVALID_INPUT;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.NOT_FOUND;
import static com.lawrence.travel.application.travel.TravelStorageFailure.Code.STALE_VERSION;

/** 结构化偏好唯一权威；墓碑、用户级CAS及永久回执与变更在同一短事务提交。 */
public final class PostgresPreferenceStore implements PreferenceStore {
    private final PreferenceMapper mapper;
    private final TravelJson json;
    /** @param mapper 私有SQL @param json 规范JSON */
    public PostgresPreferenceStore(PreferenceMapper mapper, TravelJson json) { this.mapper = mapper; this.json = json; }
    /** {@inheritDoc} 模型调用前的快照在短事务读取，绝不持锁等待网络。 */
    @Override public Preference.View read(UUID userId) { transaction(); Long revision = mapper.readLock(userId); require(revision != null, NOT_FOUND); return view(userId, revision); }
    /** {@inheritDoc} 同请求重放优先于版本冲突；墓碑不被任何新命令原身份复活。 */
    @Override public Preference.View change(UUID userId, UUID requestId, long expectedRevision, UUID id, String action, String category, String value, String city) {
        transaction(); Long current = mapper.lock(userId); require(current != null, NOT_FOUND);
        String hash = json.hash(json.encode(Arrays.asList(expectedRevision, id, action, category, value, city)));
        var receipt = mapper.receipt(userId, requestId);
        if (receipt != null) { require(receipt.hash().equals(hash), IDEMPOTENCY_CONFLICT); return view(userId, current); }
        require(current == expectedRevision, STALE_VERSION);
        String raw = mapper.entry(userId, id); var old = raw == null ? null : json.decode(raw, Preference.class);
        require(old == null || old.state() != Preference.State.DELETED, STALE_VERSION);
        Preference next;
        if (action.equals("CONFIRM") || action.equals("DELETE")) {
            require(old != null, NOT_FOUND);
            require(category == null && value == null && city.isEmpty(), INVALID_INPUT);
            if (action.equals("CONFIRM")) require(old.state() == Preference.State.PENDING, STALE_VERSION);
            next = new Preference(id, old.category(), action.equals("DELETE") ? "" : old.value(), old.city(), old.source(), action.equals("DELETE") ? Preference.State.DELETED : Preference.State.CONFIRMED, mapper.now());
        } else {
            require(action.equals("SAVE") || action.equals("UPDATE") || action.equals("SUGGEST"), INVALID_INPUT);
            if (action.equals("UPDATE")) require(old != null, NOT_FOUND);
            require(old == null || !action.equals("SUGGEST") && old.state() == Preference.State.CONFIRMED, STALE_VERSION);
            next = new Preference(id, category, value == null ? null : value.trim(), city, !action.equals("SUGGEST") ? Preference.Source.USER : Preference.Source.SUGGESTION, !action.equals("SUGGEST") ? Preference.State.CONFIRMED : Preference.State.PENDING, mapper.now());
            require(mapper.conflicts(userId, category, city, id) == 0, STALE_VERSION);
            require(old != null || mapper.entries(userId).size() < 100, INVALID_INPUT);
        }
        // 先锁用户版本行，后写条目并提升版本；候选C事务也先取此锁，删除与旧结果提交不可能穿插。
        require(mapper.save(userId, next, json.encode(next)) == 1, STALE_VERSION);
        require(mapper.advance(userId, current) == 1, STALE_VERSION);
        var result = view(userId, current + 1);
        require(mapper.receiptSave(userId, requestId, hash, json.encode(java.util.Map.of("revision", current + 1))) == 1, STALE_VERSION);
        return result;
    }
    /** @param user 用户 @param revision 已锁定版本 @return 不从旧摘要、文件或回执召回，只读当前表 */
    private Preference.View view(UUID user, long revision) { return new Preference.View(new PreferenceVersion(user, revision), mapper.entries(user).stream().map(body -> json.decode(body, Preference.class)).toList()); }
    /** 必须参加业务短事务，避免共享锁在读取条目前释放。 */
    private void transaction() { if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("PREFERENCE_TRANSACTION_REQUIRED"); }
    /** @param valid 条件 @param code 固定分类，不包含用户内容 */
    private void require(boolean valid, TravelStorageFailure.Code code) { if (!valid) throw new TravelStorageFailure(code); }
}
