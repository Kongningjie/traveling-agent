package com.lawrence.travel.application.travel;

import com.lawrence.travel.application.port.TravelStore;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.TaskChangeObserver;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.itinerary.ItineraryVersion;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TaskScope;
import java.util.List;
import java.util.UUID;

/**
 * 认证主体到私有仓储的用例入口；任务用户只能来自服务端身份，不能由客户端指定。
 */
public final class TravelStorageService {
    private final TravelStore store;
    private final BusinessTransactions transactions;
    private final TaskChangeObserver changes;

    /**
     * @param store 原子存储端口，不依赖具体 Mapper
     * @param transactions 同源短事务
     * @param changes 原子失效旧运行，不触发模型调用
     */
    public TravelStorageService(TravelStore store, BusinessTransactions transactions, TaskChangeObserver changes) {
        this.store = java.util.Objects.requireNonNull(store); this.transactions = transactions; this.changes = changes;
    }

    /**
     * @param actor 已认证主体
     * @param key 请求键
     * @param title 标题
     * @param content 初始需求
     * @return 创建或重放结果
     */
    public TravelStore.StoredTask create(UserIdentity actor, String key, String title, TravelStore.RevisionContent content) {
        return store.create(requireActor(actor), key, title, content);
    }

    /**
     * @param actor 已认证主体
     * @param taskId 任务 ID
     * @return 本人任务；不接受客户端用户 ID
     */
    public TravelStore.StoredTask task(UserIdentity actor, UUID taskId) {
        return store.task(new TaskScope(requireActor(actor), taskId));
    }

    /**
     * @param actor 已认证主体
     * @param offset 分页偏移
     * @param limit 分页大小
     * @return 本人的任务摘要
     */
    public List<TravelStore.StoredTask> tasks(UserIdentity actor, int offset, int limit) {
        return store.tasks(requireActor(actor), offset, limit);
    }

    /**
     * @param actor 已认证主体
     * @param taskId 任务
     * @param key 请求键
     * @param expected 行版本
     * @param inputId 可靠输入 ID
     * @param sequence 输入序号
     * @return 修改登记结果，不表示执行已启动
     */
    public TravelStore.StoredTask registerChange(UserIdentity actor, UUID taskId, String key, long expected, UUID inputId, long sequence) {
        return transactions.required(() -> {
            var result = store.registerChange(new TaskScope(requireActor(actor), taskId), key, expected, inputId, sequence);
            changes.changed(new TaskScope(actor.id(), taskId));
            return result;
        });
    }

    /**
     * @param actor 已认证主体
     * @param taskId 任务
     * @param key 请求键
     * @param expected 行版本
     * @param inputId 可靠修改 ID
     * @param content 已确认完整需求
     * @return 追加结果；本方法不负责模型解析或确认授权
     */
    public TravelStore.StoredTask appendRevision(UserIdentity actor, UUID taskId, String key, long expected, UUID inputId, TravelStore.RevisionContent content) {
        return store.appendRevision(new TaskScope(requireActor(actor), taskId), key, expected, inputId, content);
    }

    /**
     * @param actor 已认证主体
     * @param taskId 任务
     * @param revision 需求版本
     * @return 本人任务历史需求
     */
    public ConstraintRevision revision(UserIdentity actor, UUID taskId, long revision) {
        return store.revision(new TaskScope(requireActor(actor), taskId), revision);
    }

    /**
     * @param actor 已认证主体
     * @param version 草案元信息
     * @param payload 草案正文
     * @return 已存草案；不通过本方法授予接受资格
     */
    public TravelStore.StoredSnapshot saveDraft(UserIdentity actor, ItineraryVersion version, String payload) {
        if (version == null || !requireActor(actor).equals(version.scope().userId())) {
            throw new TravelStorageFailure(TravelStorageFailure.Code.NOT_FOUND);
        }
        return store.appendSnapshot(version, payload);
    }

    /**
     * @param actor 已认证主体
     * @param taskId 任务
     * @param id 快照
     * @return 同用户同任务快照
     */
    public TravelStore.StoredSnapshot snapshot(UserIdentity actor, UUID taskId, UUID id) {
        return store.snapshot(new TaskScope(requireActor(actor), taskId), id);
    }

    /**
     * @param actor 已认证主体
     * @param taskId 任务
     * @param offset 偏移
     * @param limit 大小
     * @return 快照元信息分页
     */
    public List<ItineraryVersion> snapshots(UserIdentity actor, UUID taskId, int offset, int limit) {
        return store.snapshots(new TaskScope(requireActor(actor), taskId), offset, limit);
    }

    /**
     * @param actor 服务端身份
     * @return 用户主键；匿名拒绝且不泄露资源存在性
     */
    private static UUID requireActor(UserIdentity actor) {
        if (actor == null) throw new TravelStorageFailure(TravelStorageFailure.Code.NOT_FOUND);
        return actor.id();
    }
}
