package com.lawrence.travel.application.port;

import com.lawrence.travel.application.itinerary.Planning;
import java.util.List;
import java.util.UUID;

/** 无写入权限的规划及审核模型，原运行预算不因角色切换重置。 */
public interface PlanningAdvisor {
    /** @param runId 原运行 @param context 最新需求 @param base 原生效快照 @param scope Java计算边界 @param prior 上轮草案，可空 @param issues 上轮问题 @return 累计补丁或扩大理由，不能直接提交 */
    Planning.PatchReply patch(UUID runId, Planning.Context context, Planning.Base base, com.lawrence.travel.domain.itinerary.ImpactScope scope, Planning.Candidate prior, List<com.lawrence.travel.domain.itinerary.ItineraryReport.Issue> issues);
    /** @param runId 原运行 @param context 当前需求与证据 @param prior 旧草案，可空 @param issues 待修问题 @return 一个完整新草案，不是生效版本 */
    Planning.Draft draft(UUID runId, Planning.Context context, Planning.Candidate prior, List<com.lawrence.travel.domain.itinerary.ItineraryReport.Issue> issues);
    /** @param runId 原运行 @param context 当前权威资料 @param candidate 只含内容和程序报告，不能含规划自评／提示历史 @return 独立问题清单 */
    Planning.Review review(UUID runId, Planning.Context context, Planning.Candidate candidate);
}
