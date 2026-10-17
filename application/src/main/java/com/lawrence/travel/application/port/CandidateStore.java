package com.lawrence.travel.application.port;

import com.lawrence.travel.application.itinerary.CandidateAcceptanceCommand;
import com.lawrence.travel.domain.travel.TaskScope;
import java.util.UUID;

/** 候选、报告及接受持久化端口；报告只从对应阶段事务投影，不暴露任意保存 PASS 的方法。 */
public interface CandidateStore extends StageProjection {
    /** 用户可见结果，REVALIDATION_REQUIRED 不是接受成功。 */
    enum Status { /** 接受事实已记录。 */ ACCEPTED, /** 报告过期，需重校验及重新确认。 */ REVALIDATION_REQUIRED }
    /**
     * @param status 结果
     * @param acceptanceId 接受记录，可空
     * @param snapshotId 内容
     * @param runId 来源运行；不表示该快照仍是当前版本
     */
    record Acceptance(Status status, UUID acceptanceId, UUID snapshotId, UUID runId) { }
    /**
     * @param result 首次稳定响应
     * @param enqueueRevalidation 本事务是否首次需要登记重校验
     */
    record Attempt(Acceptance result, boolean enqueueRevalidation) { }
    /**
     * @param command 认证归属及预期版本
     * @return 同事务结果；调用方须在同一外层事务登记重校验请求
     */
    Attempt accept(CandidateAcceptanceCommand command);
    /**
     * @param scope 私有归属
     * @param candidateId 候选
     * @return 可用于可靠重校验的快照 ID；不读取其他用户资源
     */
    UUID snapshotId(TaskScope scope, UUID candidateId);
}
