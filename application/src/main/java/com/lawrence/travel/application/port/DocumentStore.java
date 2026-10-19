package com.lawrence.travel.application.port;
import com.lawrence.travel.application.document.Documents;
import com.lawrence.travel.application.execution.Jobs;
import java.util.List;
import java.util.UUID;
/** PG元数据及解析权威，调用方短事务内先锁用户／聊天再锁作业。 */
public interface DocumentStore {
    /** @param scope 已完成删除事务中的私有范围 @return 依赖旧资料水位、仍待执行/确认的聊天作业；不得包含无关附件作业 */
    default List<UUID> revokedReaders(Jobs.Scope scope) { return List.of(); }
    /** @param scope 私有范围；不存在时拒绝 */
    void lock(Jobs.Scope scope);
    /** @param scope 私有范围 @param bytes 新原件字节；同时预留20万字符及聊天／用户容量 */
    void capacity(Jobs.Scope scope,long bytes);
    /** @param scope 归属 @param id 原件；显式重试重新预留文本，不重置每日解析次数 */
    void retryCapacity(Jobs.Scope scope,UUID id);
    /** @return 所有尚未物理清理的受管文件键，用于排他孤儿回收 */
    java.util.Set<String> references();
    /** @param entry 元数据，文件已原子落盘，调用方同事务登记 */
    void insert(Documents.Entry entry);
    /** @param scope 私有范围 @param id 文档 @return 包含删除状态的权威索引 */
    Documents.Entry get(Jobs.Scope scope,UUID id);
    /** @param scope 私有范围 @return 本聊天非删除附件 */
    List<Documents.Entry> list(Jobs.Scope scope);
    /** @param scope 私有范围 @param id 原件 @param report 完整文本JSON，已校验有界 */
    void report(Jobs.Scope scope,UUID id,String report);
    /** @param scope 私有范围 @param id 原件；立即删除正文及读取权 */
    void delete(Jobs.Scope scope,UUID id);
    /** @return 有界待清理墓碑；后台受信入口，不开放HTTP */
    List<Documents.Entry> garbage();
    /** @param id 文件已实际删除，标记释放容量；调用方持归属锁 */
    void purged(UUID id);
}
