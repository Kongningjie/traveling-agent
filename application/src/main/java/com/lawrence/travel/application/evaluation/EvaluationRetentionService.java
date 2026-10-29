package com.lawrence.travel.application.evaluation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 台账用例；尚未接入平台Web或投递器，不能作为整个Langfuse的访问和删除保证。 */
public final class EvaluationRetentionService {
    private final EvaluationRetention.Store store;

    /**
     * 绑定以数据库时间判定到期的持久仓储，不内置放行替身。
     *
     * @param store 持久化端口
     */
    public EvaluationRetentionService(EvaluationRetention.Store store) {
        this.store = Objects.requireNonNull(store);
    }

    /**
     * 登记原始运行；客户端不能通过再次导出延长期限。
     *
     * @param registration 来自可信执行记录的身份和时间
     * @throws IllegalStateException 冲突、到期或数据库不可用时不准登记
     */
    public void register(EvaluationRetention.Registration registration) {
        EvaluationRetention.require(registration != null);
        allowed(store.register(registration));
    }

    /**
     * 读取当前活跃运行；没有记录也不放行。
     *
     * @param owner 认证账号
     * @param run 独立运行
     * @return 当前活跃记录，不包含正文
     * @throws IllegalStateException 到期、撤销、越权或存储不可用
     */
    public EvaluationRetention.Active read(UUID owner, UUID run) {
        identity(owner, run);
        var result = store.active(owner, run);
        allowed(result != null);
        return result;
    }

    /**
     * 在创建平台或本地副本前登记位置，后续投递还须在发送时复核读取资格。
     *
     * @param owner 认证账号
     * @param run 独立运行
     * @param kind 副本类别
     * @param locatorHash 规范化位置指纹
     * @throws IllegalStateException 到期、撤销或越权
     */
    public void track(UUID owner, UUID run, EvaluationRetention.CopyKind kind, String locatorHash) {
        identity(owner, run);
        location(kind, locatorHash);
        allowed(store.track(owner, run, kind, locatorHash));
    }

    /**
     * 主动删除先写持久标记，外部异步清理不能使标记回滚。
     *
     * @param owner 认证账号
     * @param run 独立运行
     * @throws IllegalStateException 无自有记录或存储不可用
     */
    public void revoke(UUID owner, UUID run) {
        identity(owner, run);
        allowed(store.revoke(owner, run));
    }

    /**
     * 给受控清理适配器读取最早到期副本；不是面向普通用户的查询入口。
     *
     * @param limit 最大100条
     * @return 尚无有效清理回执的副本，失败不返回虚假空列表
     */
    public List<EvaluationRetention.Cleanup> pending(int limit) {
        EvaluationRetention.require(limit >= 1 && limit <= 100);
        return List.copyOf(store.pending(limit));
    }

    /**
     * 记录实际清理适配器回执；调用者须先完成对应存储的物理检查。
     *
     * @param task 当前清理代次
     * @param evidenceHash 清理检查记录哈希
     * @return 是否接受回执；false时需重新读取代次，不覆盖新任务
     */
    public boolean attest(EvaluationRetention.Cleanup task, String evidenceHash) {
        EvaluationRetention.require(task != null && task.run() != null && task.generation() >= 0);
        location(task.kind(), task.locatorHash());
        EvaluationRetention.require(EvaluationRetention.hash(evidenceHash));
        return store.attest(
                task.run(), task.kind(), task.locatorHash(), task.generation(), evidenceHash);
    }

    /**
     * 撤销迟到副本的旧回执；该方法仅供后续受控投递/清理适配器使用。
     *
     * @param run 原始运行
     * @param kind 副本类别
     * @param locatorHash 原位置指纹
     * @return 是否重新排入清理，不恢复访问
     */
    public boolean arrivedLate(UUID run, EvaluationRetention.CopyKind kind, String locatorHash) {
        EvaluationRetention.require(run != null);
        location(kind, locatorHash);
        return store.arrivedLate(run, kind, locatorHash);
    }

    /**
     * 校验归属参数，身份来源由调用入口认证。
     *
     * @param owner 认证账号
     * @param run 独立运行
     */
    private static void identity(UUID owner, UUID run) {
        EvaluationRetention.require(owner != null && run != null);
    }

    /**
     * 限制副本索引内容，不允许写入正文或原始URL。
     *
     * @param kind 类别
     * @param locatorHash 位置指纹
     */
    private static void location(EvaluationRetention.CopyKind kind, String locatorHash) {
        EvaluationRetention.require(kind != null && EvaluationRetention.hash(locatorHash));
    }

    /**
     * 用相同错误拒绝不存在、越权、撤销和到期，避免泄露运行存在性。
     *
     * @param value 是否通过仓储权威检查
     */
    private static void allowed(boolean value) {
        if (!value) throw new IllegalStateException("EVALUATION_RETENTION_DENIED");
    }
}
