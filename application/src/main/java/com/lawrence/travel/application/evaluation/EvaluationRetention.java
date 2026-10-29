package com.lawrence.travel.application.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 评测保留台账协议；只保存身份、期限和清理状态，不保存正文，也不证明平台已删除。 */
public final class EvaluationRetention {
    /** 不实例化协议容器。 */
    private EvaluationRetention() {}

    /**
     * 由可信运行记录提供的归属和固定配置；不是浏览器提交的授权凭据。
     *
     * @param owner 评测操作所属账号
     * @param run 本次独立场景运行，不得复用已删除身份
     * @param startedAt 原始运行开始时间，不使用导出或评审时间
     * @param datasetHash 固定数据和裁判指纹
     * @param configurationHash 模型、提示词和代码配置指纹
     */
    public record Registration(
            UUID owner, UUID run, Instant startedAt, String datasetHash, String configurationHash) {
        /** 检查登记形状；运行来源的真实性须由受控执行入口保证。 */
        public Registration {
            require(owner != null && run != null && startedAt != null);
            require(hash(datasetHash) && hash(configurationHash));
        }
    }

    /**
     * 活跃台账的只读视图；到期或撤销后仓储不再返回此视图。
     *
     * @param run 独立运行
     * @param startedAt 固定原始时间
     * @param expiresAt 开始后720小时，不随重试续期
     * @param datasetHash 数据指纹
     * @param configurationHash 配置指纹
     */
    public record Active(
            UUID run,
            Instant startedAt,
            Instant expiresAt,
            String datasetHash,
            String configurationHash) {}

    /** 需要清理的受管理副本种类；新增存储时必须显式登记。 */
    public enum CopyKind {
        /** 平台轨迹、观察和关联分数。 */
        TRACE,
        /** 平台数据集和条目运行副本。 */
        DATASET,
        /** 未归属轨迹的评测分数。 */
        SCORE,
        /** 平台原始事件和媒体对象。 */
        OBJECT,
        /** 受管理缓存。 */
        CACHE,
        /** 本地运行报告和导出。 */
        LOCAL,
        /** 受管理备份。 */
        BACKUP
    }

    /**
     * 清理任务的最小定位信息；定位符是规范化位置的哈希，不存原文或带凭据URL。
     *
     * @param run 原始运行
     * @param kind 副本类别
     * @param locatorHash 定位符哈希，实际位置由相应适配器管理
     * @param blockedAt 到期或主动删除时间，取更早者
     * @param deadline 阻断时间后24小时
     * @param generation 本副本清理代次；迟到数据使代次增加
     * @param overdue 是否已超过清理期限，供后续告警使用
     */
    public record Cleanup(
            UUID run,
            CopyKind kind,
            String locatorHash,
            Instant blockedAt,
            Instant deadline,
            long generation,
            boolean overdue) {}

    /** PG台账端口；读写须使用数据库时间，到期或存储失败不准放行。 */
    public interface Store {
        /**
         * 幂等登记可信运行；同ID不同身份/时间/指纹、未来或已过期开始时间一律拒绝。
         *
         * @param registration 可信运行记录
         * @return 是否首次登记或原记录完全一致且仍活跃
         */
        boolean register(Registration registration);

        /**
         * 读取未到期且未撤销的自有记录；不暴露其他账号的记录是否存在。
         *
         * @param owner 认证归属
         * @param run 原始运行
         * @return 活跃记录；不存在、越权、撤销或到期均为null
         */
        Active active(UUID owner, UUID run);

        /**
         * 在投递前登记副本；SQL再次检查期限，防止提前检查后跨过到期点。
         *
         * @param owner 认证归属
         * @param run 原始运行
         * @param kind 存储类别
         * @param locatorHash 位置指纹
         * @return 是否登记成功；成功不代表外部发送完成
         */
        boolean track(UUID owner, UUID run, CopyKind kind, String locatorHash);

        /**
         * 持久化主动删除标记；重复请求不刷新首次阻断时间，标记不随副本清理删除。
         *
         * @param owner 认证归属
         * @param run 原始运行
         * @return 是否有自有记录可撤销
         */
        boolean revoke(UUID owner, UUID run);

        /**
         * 列出已到期或撤销、尚无当前代次清理回执的副本，不返回评测正文。
         *
         * @param limit 单批1～100
         * @return 按最早清理期限排序的任务
         */
        List<Cleanup> pending(int limit);

        /**
         * 记录清理适配器的回执，只有代次一致才接受；本端口不验证物理清理本身。
         *
         * @param run 运行
         * @param kind 存储类别
         * @param locatorHash 位置指纹
         * @param generation 本次清理代次
         * @param evidenceHash 适配器验证记录的哈希；不能用删除接口ACK代替
         * @return 回执是否属于当前已阻断副本
         */
        boolean attest(
                UUID run, CopyKind kind, String locatorHash, long generation, String evidenceHash);

        /**
         * 已阻断运行发现迟到副本时撤销旧清理回执，避免重启或晚到数据被当作已清理。
         *
         * @param run 运行
         * @param kind 存储类别
         * @param locatorHash 已登记位置
         * @return 是否已重新加入清理；不会恢复运行读取权限
         */
        boolean arrivedLate(UUID run, CopyKind kind, String locatorHash);
    }

    /**
     * 校验无正文哈希的形状。
     *
     * @param value 指纹
     * @return 是否为64位小写十六进制
     */
    public static boolean hash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    /**
     * 拒绝非法台账操作，不回显调用参数。
     *
     * @param valid 是否满足协议
     */
    static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("EVALUATION_RETENTION_INVALID");
    }
}
