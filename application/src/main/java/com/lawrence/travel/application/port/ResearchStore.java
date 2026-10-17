package com.lawrence.travel.application.port;

import com.lawrence.travel.application.knowledge.Research;
import com.lawrence.travel.domain.execution.ExecutionFence;
import com.lawrence.travel.domain.knowledge.EvidenceRecord;
import com.lawrence.travel.domain.knowledge.FieldEvidence;
import com.lawrence.travel.domain.travel.TaskScope;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** 证据权威端口；阶段 C 内登记，历史字段不可覆盖，查询始终带用户及任务范围。 */
public interface ResearchStore extends StageProjection {
    /** @param fence 当前执行 @return 与领取修订一致的需求及已登记资料；不持有网络事务 */
    Research.Context context(ExecutionFence fence);
    /** @param scope 私有任务 @return 历史及追加状态；不回传原始网页正文 */
    List<Research.View> evidence(TaskScope scope);
    /** @param scope 归属 @param key 精确参数 @param dependencyHash 需求依赖 @param date 适用日期 @return 仍有效且未冲突的完整查询结果，否则为空 */
    List<FieldEvidence> cached(TaskScope scope, String key, String dependencyHash, LocalDate date);
    /** @param scope 归属 @param references 精确引用 @param now 数据库时间 @param upperBound 报告最大期限 @return 依赖当前性和最早失效时间；不把未知视为有效事实 */
    Research.Dependencies dependencies(TaskScope scope, Set<EvidenceRecord.Reference> references, Instant now, Instant upperBound);
    /** @param scope 受信归属 @param reference 精确引用 @param requestId 撤回因果身份；必须在应用短事务内 @return 是否首次撤回；不删除原值 */
    boolean withdraw(TaskScope scope, EvidenceRecord.Reference reference, java.util.UUID requestId);
}
