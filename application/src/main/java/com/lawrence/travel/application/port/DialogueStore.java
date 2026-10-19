package com.lawrence.travel.application.port;

import com.lawrence.travel.application.interaction.Dialogue;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL聊天收件与租约；所有写入在应用短事务执行，模型调用必须在事务外。 */
public interface DialogueStore {
    /** @param user 归属 @param chat 聊天 @return 真实旅行当前运行，可空，不从摘要推断 */
    default UUID currentRun(UUID user,UUID chat) { return null; }
    /** @param user 归属 @param chat 聊天 @param input 输入 @param text 原文 @return 精确幂等检查；同ID异原文拒绝 */
    default boolean containsInput(UUID user,UUID chat,UUID input,String text) { return false; }
    /** @param user 归属 @param chat 聊天；明确停止使旧输入失去提交权，保留原文 */
    default void stopPending(UUID user,UUID chat) { throw new IllegalStateException("DIRECT_STOP_UNSUPPORTED"); }
    /** @param user 归属 @param chat 聊天 @param input 本次明确停止命令 @param reply 应用执行事实，不经过模型 */
    default void completeCommand(UUID user,UUID chat,UUID input,String reply) { throw new IllegalStateException("DIRECT_STOP_UNSUPPORTED"); }
    /** @param claim 原输入资格 @return 是否仍可续租，不复活撤回或过期输入 */
    default boolean renew(Dialogue.Claim claim) { return true; }
    /** @param claim 原输入 @return 持久化入口关联，异步执行不生成另一条无关trace */
    default com.lawrence.travel.application.execution.ExecutionCorrelation.Ids correlation(Dialogue.Claim claim) { return com.lawrence.travel.application.execution.ExecutionCorrelation.current(); }
    /** @param user 身份 @param id 创建幂等键 @param title 首条文本生成的标题 @return 私有会话 */
    Dialogue.Chat create(UUID user, UUID id, String title);
    /** @param user 身份 @param id 新会话幂等ID @param task 已存在的私有任务 @return 已有关联会话或新会话，兼容历史任务而不重复创建旅行 */
    Dialogue.Chat attach(UUID user, UUID id, UUID task);
    /** @param user 身份 @param offset 起点 @return 最多20条私有聊天 */
    List<Dialogue.Chat> list(UUID user, int offset);
    /** @param user 身份 @param chat 聊天 @return 最近100条加未决消息，最多120条及关联任务 */
    Dialogue.View read(UUID user, UUID chat);
    /** @param user 身份 @param chat 聊天 @param id 幂等输入 @param text 原文 @return 收件后视图；重复请求不可变更正文 */
    Dialogue.View receive(UUID user, UUID chat, UUID id, String text);
    /** @return 同一聊天有序领取；超时重试保留原预算，超过尝试上限暂停并保留屏障 */
    Optional<Dialogue.Claim> claim();
    /** @param claim 原租约 @return 锁用户、任务、聊天及消息后检查租约与版本；不允许迟到结果推进 */
    boolean guard(Dialogue.Claim claim);
    /** @param claim 已锁领取 @param inputs 本轮明确解决的旧澄清；只允许同聊天CLARIFY消息，普通新消息不自动解除旧屏障 */
    void resolve(Dialogue.Claim claim, List<UUID> inputs);
    /** @param claim 已通过guard的领取 @param task 新任务 @return 关联结果；已有任务不得替换 */
    Dialogue.Chat link(Dialogue.Claim claim, UUID task);
    /** @param claim 已通过guard @param state DONE、CONFIRM、CLARIFY或PAUSED @param reply 可信协议中的回复 @param operation 待确认动作，可空 @param error 固定错误码 */
    void finish(Dialogue.Claim claim, String state, String reply, Dialogue.Operation operation, String error);
    /** @param user 身份 @param chat 聊天 @param message 消息 @param action CONFIRM、WITHDRAW或RETRY @return 锁定的消息；状态检查由应用执行 */
    Dialogue.Message lockMessage(UUID user, UUID chat, UUID message, String action);
    /** @param user 身份 @param chat 聊天 @param message 消息 @param state 新状态；撤回与重试不能重置预算 */
    void changeState(UUID user, UUID chat, UUID message, String state);
    /** @param user 身份 @param chat 聊天 @param message 精确确认 @param reply 已提交操作结果；与实际操作同事务，不能保留旧的待确认文案冒充结果 */
    void operationResult(UUID user, UUID chat, UUID message, String reply);
    /** @param user 身份 @param task 任务 @return 是否存在未解释自由输入，供展示及接受的持久屏障 */
    boolean blocked(UUID user, UUID task);
}
