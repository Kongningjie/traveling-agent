package com.lawrence.travel.application.port;

import com.lawrence.travel.domain.itinerary.ItineraryVersion;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.domain.travel.TravelTask;
import java.util.List;
import java.util.UUID;

/**
 * 私有任务与不可变版本存储端口；全部读取附带受信用户归属。
 * 写入为短事务，禁止在事务中执行网络或模型调用；不提供直接接受快照的方法。
 */
public interface TravelStore {
    /**
     * 任务状态与存储并发标记；事件游标和状态由同一行一次读取。
     * @param task 领域状态
     * @param title 任务标题
     * @param lockVersion CAS 行版本，从零开始
     * @param eventSequence 已提交事件游标，事件引擎尚未接入时为零
     */
    record StoredTask(TravelTask task, String title, long lockVersion, long eventSequence) { }

    /**
     * 确认后的完整需求内容；替换必须保留仍有效要求，语义协商由上层负责。
     * @param requirements 已确认需求及来源
     * @param fixedArrangements 已确认固定安排
     * @param source 确认来源说明，不代表自动采纳模型建议
     * @param difference 本次变更说明
     */
    record RevisionContent(List<ConstraintRevision.Requirement> requirements,
                           List<ConstraintRevision.FixedArrangement> fixedArrangements,
                           String source, String difference) {
        /**
         * 防御性复制，基础领域限制在构造具体需求版本时执行。
         */
        public RevisionContent {
            if (requirements == null || fixedArrangements == null || requirements.stream().anyMatch(java.util.Objects::isNull)
                    || fixedArrangements.stream().anyMatch(java.util.Objects::isNull)
                    || source == null || source.isBlank() || source.length() > 2000
                    || difference == null || difference.length() > 4000) {
                throw new com.lawrence.travel.application.travel.TravelStorageFailure(
                        com.lawrence.travel.application.travel.TravelStorageFailure.Code.INVALID_INPUT);
            }
            requirements = List.copyOf(requirements);
            fixedArrangements = List.copyOf(fixedArrangements);
        }
    }

    /**
     * 创建任务与初始需求，失败全部回滚；相同用户和请求键返回第一次创建结果。
     * @param userId 认证用户，不能取自请求体
     * @param key 请求幂等键，1～128 个可见 ASCII 字符
     * @param title 1～200 字符标题
     * @param content 完整初始需求，允许尚未收集任何需求项
     * @return 初次事务的任务结果；重放不返回后来被修改的状态
     */
    StoredTask create(UUID userId, String key, String title, RevisionContent content);

    /**
     * 立即登记已识别修改并提升执行代次，需求提取稍后进行。
     * @param scope 受信用户及任务
     * @param key 幂等请求键
     * @param expectedLockVersion 读取时的 CAS 行版本
     * @param inputId 服务端输入 ID
     * @param sequence 任务内唯一输入顺序
     * @return 保存后的任务；不生成运行或声称已提取需求
     */
    StoredTask registerChange(TaskScope scope, String key, long expectedLockVersion, UUID inputId, long sequence);

    /**
     * 按输入顺序保存确认后的完整需求，新 revision 与移除该待处理输入同事务提交。
     * @param scope 用户及任务
     * @param key 幂等请求键
     * @param expectedLockVersion CAS 行版本
     * @param inputId 已登记修改 ID
     * @param content 已完成协商的全量需求，不是局部增量
     * @return 初次提交结果；冲突不得覆盖新状态
     */
    StoredTask appendRevision(TaskScope scope, String key, long expectedLockVersion, UUID inputId, RevisionContent content);

    /**
     * @param scope 用户及任务
     * @return 当前状态，缺失或越权统一拒绝
     */
    StoredTask task(TaskScope scope);

    /**
     * @param userId 认证用户
     * @param offset 非负偏移
     * @param limit 1～100
     * @return 私有任务摘要，不联查大快照
     */
    List<StoredTask> tasks(UUID userId, int offset, int limit);

    /**
     * @param scope 用户及任务
     * @param revision 具体正版本
     * @return 历史完整需求，不能覆盖
     */
    ConstraintRevision revision(TaskScope scope, long revision);

    /**
     * 追加不可变草案；按快照 ID 幂等，同 ID 不同内容拒绝；不修改生效指针。
     * @param version 快照身份、基础版本与需求依赖
     * @param payload 完整 JSON 对象；存储格式检查不等于业务校验通过
     * @return 规范化后正文及元信息
     */
    StoredSnapshot appendSnapshot(ItineraryVersion version, String payload);

    /**
     * @param scope 用户及任务
     * @param snapshotId 快照 ID
     * @return 同任务可见快照，否则统一拒绝
     */
    StoredSnapshot snapshot(TaskScope scope, UUID snapshotId);

    /**
     * @param scope 用户及任务
     * @param offset 非负偏移
     * @param limit 1～100
     * @return 快照元信息，不联查正文
     */
    List<ItineraryVersion> snapshots(TaskScope scope, int offset, int limit);

    /**
     * 不可变内容读取结果，不含可变审核状态。
     * @param version 身份与依赖
     * @param payload 规范化 JSON 正文
     */
    record StoredSnapshot(ItineraryVersion version, String payload) { }
}
