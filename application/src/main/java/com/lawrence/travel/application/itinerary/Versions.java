package com.lawrence.travel.application.itinerary;

import com.lawrence.travel.domain.itinerary.ItineraryVersion;
import com.lawrence.travel.domain.itinerary.TripSnapshot;
import com.lawrence.travel.domain.travel.TaskScope;
import java.time.Instant;
import java.util.UUID;

/** 历史浏览与恢复的应用契约；历史报告不提供再次接受资格。 */
public final class Versions {
    /** 不持有状态。 */
    private Versions() { }
    /** @param version 不可变元信息 @param createdAt 数据库生成时间 @param accepted 是否曾被用户接受 */
    public record Summary(ItineraryVersion version, Instant createdAt, boolean accepted) { }
    /** @param summary 历史身份 @param snapshot 只读内容 @param sourceCode 真实来源类别 @param sourceMessages 本版本运行处理的用户变更原文，不能由模型编造原因 */
    public record Detail(Summary summary, TripSnapshot snapshot, String sourceCode, java.util.List<com.lawrence.travel.application.interaction.Negotiation.Message> sourceMessages) {
        /** 防御复制来源，不把历史原文当作执行指令。 */
        public Detail { sourceMessages = java.util.List.copyOf(sourceMessages); }
    }
    /** @param scope 认证归属 @param requestId 永久幂等键 @param snapshotId 已接受历史版本 @param expectedCurrent 当前指针 @param revision 当前需求 @param generation 当前代次 */
    public record Restore(TaskScope scope, UUID requestId, UUID snapshotId, UUID expectedCurrent, long revision, long generation) { }
}
