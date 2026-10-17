package com.lawrence.travel.application.port;

import com.lawrence.travel.application.itinerary.CandidateAcceptanceCommand;
import com.lawrence.travel.domain.travel.TaskScope;
import java.util.UUID;

/** 候选、报告及接受持久化端口；报告只从对应阶段事务投影，不暴露任意保存 PASS 的方法。 */
public interface CandidateStore extends StageProjection {
    /** @param scope 私有归属 @param id 生效基础版本 @return 只读快照、摘要与当时需求，不读取其他用户 */
    com.lawrence.travel.application.itinerary.Planning.Base base(TaskScope scope, UUID id);
    /** @param fence 权威领取 @return 当前需求及证据，不提供写入权限 */
    com.lawrence.travel.application.itinerary.Planning.Context context(com.lawrence.travel.domain.execution.ExecutionFence fence);
    /** @param scope 私有任务 @param id 候选身份 @return 草案及精确报告；不返回其他归属 */
    com.lawrence.travel.application.itinerary.Planning.Candidate read(TaskScope scope, UUID id);
    /** @param scope 私有任务 @return 当前运行的最近候选和Java费用，无草案为空；不等于已接受 */
    com.lawrence.travel.application.itinerary.Planning.View latest(TaskScope scope);
    /** @param fence 当前领取 @param id 候选 @return 与C事务同一规则计算的问题，不保存模型提供的结论 */
    java.util.List<com.lawrence.travel.domain.itinerary.ItineraryReport.Issue> validate(com.lawrence.travel.domain.execution.ExecutionFence fence, UUID id);
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
    /** @param command 当前候选的精确前置条件 @return 仅登记重校验，报告已新鲜也绝不接受；调用方同事务排队 */
    Attempt recheck(CandidateAcceptanceCommand command);
    /**
     * @param scope 私有归属
     * @param candidateId 候选
     * @return 可用于可靠重校验的快照 ID；不读取其他用户资源
     */
    UUID snapshotId(TaskScope scope, UUID candidateId);
}
