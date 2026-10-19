package com.lawrence.travel.application.memory;

import com.lawrence.travel.domain.memory.ConversationDigest;
import java.util.List;
import java.util.UUID;

/** 聊天和旅行共享的可重建会话协议；原文在PG，框架实例没有独立业务提交权。 */
public final class SessionContext {
    /** 命名空间。 */
    private SessionContext() { }
    /** 历史角色只允许原始用户、助手及成对工具结果，不能从用户文本导入SYSTEM。 */
    public enum Role { /** 用户原文。 */ USER, /** 已检查助手回复。 */ ASSISTANT, /** 有对应工具调用的结果。 */ TOOL }
    /** @param id 稳定消息 @param sequence 原顺序 @param role 原角色 @param text 完整正文 @param hash 内容指纹 @param callId 工具调用关联，非工具为空 */
    public record Message(UUID id,long sequence,Role role,String text,String hash,String callId) { }
    /** @param userId 所有者 @param chatId 可空独立聊天 @param budgetId 输入／旅行预算 @param role 角色 @param preferenceRevision 当前偏好 @param epoch 删除／资料水位 @param attemptToken 输入领取围栏，旅行为0 @param attemptId 旅行阶段尝试，独立聊天为空 @param binding 当前版本指纹 @param authority 权威数据JSON @param messages 当前输入之前的历史 @param legacy 旅行来源，聊天可空 */
    public record Source(UUID userId,UUID chatId,UUID budgetId,String role,long preferenceRevision,long epoch,long attemptToken,UUID attemptId,String binding,String authority,List<Message> messages,AgentContext.Source legacy) {
        /** 来源序列不可由调用者改变。 */
        public Source { messages=List.copyOf(messages); }
    }
    /** @param id 摘要版本 @param dependencyHash 偏好／资料／业务绑定 @param covered 实际送入摘要器的完整前缀 @param digest 可核对摘录 @param beforeTokens 摘要输入保守估算 @param afterTokens 摘录保守估算，不冒充供应商实际用量 */
    public record Summary(UUID id,String dependencyHash,List<AgentContext.MessageRef> covered,ConversationDigest digest,long beforeTokens,long afterTokens) {
        /** 不能把未送给模型的历史写作已总结。 */
        public Summary { covered=List.copyOf(covered); }
    }
    /** @param source 发送前需要复核的快照 @param messages 真实历史及本轮消息 @param summary 可空摘要 @param estimatedTokens 保守总预算估算 */
    public record Prepared(Source source,List<Message> messages,Summary summary,long estimatedTokens) {
        /** 不共享可变输入列表。 */
        public Prepared { messages=List.copyOf(messages); }
    }
}
