package com.lawrence.travel.application.interaction;

import com.lawrence.travel.domain.interaction.DialogueIntent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 独立聊天的私有传输模型；聊天与旅行任务最多一对一，不假造空旅行。 */
public final class Dialogue {
    /** 不实例化协议命名空间。 */
    private Dialogue() { }
    /** @param id 聊天 @param title 标题 @param taskId 开始规划后关联的任务，可空 */
    public record Chat(UUID id, String title, UUID taskId) { }
    /** @param id 幂等消息 @param text 用户原文 @param state 持久状态 @param reply 助手结果 @param operation 仅待确认动作 @param error 固定失败码 */
    public record Message(UUID id, String text, String state, String reply, Operation operation, String error) { }
    /** @param chat 聊天归属 @param messages 最近消息，按接收顺序 @param blocked 未解释消息屏障 */
    public record View(Chat chat, List<Message> messages, boolean blocked) { }
    /** @param action 动作 @param targetId 已定位对象，可空 @param description 展示的具体影响 @param basis 精确任务版本 @param preferenceRevision 偏好版本
     * @param category 偏好类型 @param value 偏好内容 @param city 适用范围 */
    public record Operation(DialogueIntent.Action action, UUID targetId, String description, Basis basis, long preferenceRevision, String category, String value, String city) { }
    /** @param taskId 任务，可空 @param runId 当前运行，可空 @param generation 执行代次 @param revision 需求版本 @param currentVersion 当前行程，可空 */
    public record Basis(UUID taskId, UUID runId, long generation, long revision, UUID currentVersion) { }
    /** @param userId 权威用户 @param chatId 聊天 @param messageId 输入 @param token 租约围栏 @param budgetId 本次沿用预算
     * @param text 原文 @param receivedAt 收件时间 @param basis 领取时业务基础 @param context 有界上下文数据 */
    public record Claim(UUID userId, UUID chatId, UUID messageId, long token, UUID budgetId, String text, Instant receivedAt, Basis basis, String context) { }
}
