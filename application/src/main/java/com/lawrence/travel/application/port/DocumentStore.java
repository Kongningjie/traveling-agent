package com.lawrence.travel.application.port;

import com.lawrence.travel.application.document.Documents;
import com.lawrence.travel.application.execution.Jobs;
import java.util.List;
import java.util.UUID;

/** PG元数据及解析权威，调用方短事务内先锁用户／聊天再锁作业。 */
public interface DocumentStore {
    /**
     * 定位依赖已失效资料的活跃聊天作业；默认空列表仅兼容旧端口。
     * @param scope 已完成删除事务中的私有范围
     * @return 依赖旧资料水位、仍待执行/确认的聊天作业；不得包含无关附件作业
     */
    default List<UUID> revokedReaders(Jobs.Scope scope) {
        return List.of();
    }

    /**
     * 按用户到聊天的顺序取得归属锁，供后续附件写入和删除使用。
     * @param scope 私有范围；不存在时拒绝
     */
    void lock(Jobs.Scope scope);

    /**
     * 在原件登记前检查字节、提取文本及待处理容量，避免并发请求各自看到同一个空位。
     * @param scope 私有范围
     * @param bytes 新原件字节；同时预留20万字符及聊天／用户容量
     */
    void capacity(Jobs.Scope scope, long bytes);

    /**
     * 为同一原件的显式解析重试重新检查文本容量，不重置每日计数。
     * @param scope 归属
     * @param id 原件；显式重试重新预留文本，不重置每日解析次数
     */
    void retryCapacity(Jobs.Scope scope, UUID id);

    /**
     * 提供仍受管理的原件键集合，孤儿回收不能删除仍被索引或墓碑保护的文件。
     * @return 所有尚未物理清理的受管文件键，用于排他孤儿回收
     */
    java.util.Set<String> references();

    /**
     * 登记已完整写入的原件索引，由应用事务保证索引与解析作业一致。
     * @param entry 元数据，文件已原子落盘，调用方同事务登记
     */
    void insert(Documents.Entry entry);

    /**
     * 按用户和聊天定位文档索引，调用者继续检查删除状态与读取资格。
     * @param scope 私有范围
     * @param id 文档
     * @return 包含删除状态的权威索引
     */
    Documents.Entry get(Jobs.Scope scope, UUID id);

    /**
     * 返回当前聊天未删除的附件索引，不在目录查询中装载原件。
     * @param scope 私有范围
     * @return 本聊天非删除附件
     */
    List<Documents.Entry> list(Jobs.Scope scope);

    /**
     * 保存通过校验的完整解析结果；显式重试可清除旧报告以等待新结果。
     * @param scope 私有范围
     * @param id 原件
     * @param report 完整文本JSON，已校验有界
     */
    void report(Jobs.Scope scope, UUID id, String report);

    /**
     * 立即撤销资料可读性并清除提取正文，原件物理清理另由受控任务执行。
     * @param scope 私有范围
     * @param id 原件；立即删除正文及读取权
     */
    void delete(Jobs.Scope scope, UUID id);

    /**
     * 有界发现等待物理清理的墓碑，仅供受信后台维护使用。
     * @return 有界待清理墓碑；后台受信入口，不开放HTTP
     */
    List<Documents.Entry> garbage();

    /**
     * 在原件实际清理后更新墓碑并释放容量，不提前宣称删除成功。
     * @param id 文件已实际删除，标记释放容量；调用方持归属锁
     */
    void purged(UUID id);
}
