package com.lawrence.travel.application.port;

import com.lawrence.travel.application.lifecycle.Lifecycle;

import java.util.UUID;

/** 独立于业务备份的最小删除清单；恢复缺失/损坏必须拒绝开放旧数据。 */
public interface DeletionLedger {
    /**
     * @return 已封存头，不存在返回空；损坏必须抛固定错误
     */
    Lifecycle.Head head();

    /**
     * @param instance 首次PG部署身份
     * @return 独立清单初始头，禁止覆盖
     */
    Lifecycle.Head initialize(UUID instance);

    /**
     * @param expected 持PG全局序列锁的旧头
     * @param entry 下一条最小墓碑
     * @return fsync及原子头更新后的水位
     */
    Lifecycle.Head append(Lifecycle.Head expected, Lifecycle.Entry entry);

    /**
     * @param sequence 可信头内的连续顺序
     * @param previous 前序SHA256
     * @return 验证完整性的墓碑，不读取任意路径
     */
    Lifecycle.Entry read(long sequence, String previous);

    /**
     * @param sequence 条目顺序
     * @return 已验证文件的链哈希
     */
    String hash(long sequence);
}
