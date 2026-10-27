package com.lawrence.travel.application.port;

import com.lawrence.travel.application.interaction.Dialogue;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL聊天收件与租约；所有写入在应用短事务执行，模型调用必须在事务外。 */
public interface DialogueStore {
    /**
     * 读取聊天关联旅行的当前运行；默认实现返回空，仅用于无旅行的兼容端口。
     * @param user 归属
     * @param chat 聊天
     * @return 真实旅行当前运行，可空，不从摘要推断
     */
    default UUID currentRun(UUID user, UUID chat) {
        return null;
    }

    /**
     * 核对消息幂等键与原文；默认实现不执行持久化检查，生产适配器必须覆盖。
     * @param user 归属
     * @param chat 聊天
     * @param input 输入
     * @param text 原文
     * @return 精确幂等检查；同ID异原文拒绝
     */
    default boolean containsInput(UUID user, UUID chat, UUID input, String text) {
        return false;
    }

    /**
     * 撤销聊天内未完成输入的执行资格，保留原文；默认实现明确拒绝不支持的停止操作。
     * @param user 归属
     * @param chat 聊天；明确停止使旧输入失去提交权，保留原文
     */
    default void stopPending(UUID user, UUID chat) {
        throw new IllegalStateException("DIRECT_STOP_UNSUPPORTED");
    }

    /**
     * 将应用已执行的短命令登记为完成，不经模型推断执行结果；默认实现不支持此操作。
     * @param user 归属
     * @param chat 聊天
     * @param input 本次明确停止命令
     * @param reply 应用执行事实，不经过模型
     */
    default void completeCommand(UUID user, UUID chat, UUID input, String reply) {
        throw new IllegalStateException("DIRECT_STOP_UNSUPPORTED");
    }

    /**
     * 按原围栏续租输入；默认返回true仅兼容旧替身，不会实际更新数据库租约。
     * @param claim 原输入资格
     * @return 是否仍可续租，不复活撤回或过期输入
     */
    default boolean renew(Dialogue.Claim claim) {
        return true;
    }

    /**
     * 恢复本次输入的执行关联；默认仅读取当前线程绑定，生产适配器应恢复持久化入口身份。
     * @param claim 原输入
     * @return 当前调用关联；默认实现仅为当前线程信息，生产实现应从持久化入口恢复
     */
    default com.lawrence.travel.application.execution.ExecutionCorrelation.Ids correlation(
            Dialogue.Claim claim) {
        return com.lawrence.travel.application.execution.ExecutionCorrelation.current();
    }

    /**
     * 以调用方提供的稳定身份创建私有聊天，不创建旅行占位任务。
     * @param user 身份
     * @param id 创建幂等键
     * @param title 首条文本生成的标题
     * @return 私有会话
     */
    Dialogue.Chat create(UUID user, UUID id, String title);

    /**
     * 将已有私有旅行关联到聊天；同一旅行的重复关联应返回原聊天。
     * @param user 身份
     * @param id 新会话幂等ID
     * @param task 已存在的私有任务
     * @return 已有关联会话或新会话，兼容历史任务而不重复创建旅行
     */
    Dialogue.Chat attach(UUID user, UUID id, UUID task);

    /**
     * 分页读取当前用户的聊天摘要，不返回其他用户的存在性信息。
     * @param user 身份
     * @param offset 起点
     * @return 最多20条私有聊天
     */
    List<Dialogue.Chat> list(UUID user, int offset);

    /**
     * 读取聊天、可见输入及候选屏障，恢复页面时只读，不启动新的模型执行。
     * @param user 身份
     * @param chat 聊天
     * @return 最近100条加未决消息，最多120条及关联任务
     */
    Dialogue.View read(UUID user, UUID chat);

    /**
     * 在应用短事务中可靠收件；消息正文、附件选择与作业登记必须满足同一幂等契约。
     * @param user 身份
     * @param chat 聊天
     * @param id 幂等输入
     * @param text 原文
     * @return 收件后视图；重复请求不可变更正文
     */
    Dialogue.View receive(UUID user, UUID chat, UUID id, String text);

    /**
     * 在应用短事务中可靠收件；消息正文、附件选择与作业登记必须满足同一幂等契约。
     * @param user 归属
     * @param chat 聊天
     * @param id 幂等消息
     * @param text 原文
     * @param documents 本轮明确选择的文件，最多10个且不得重复
     * @return 消息和文件版本绑定同事务保存；重放不得改变附件
     */
    default Dialogue.View receive(
            UUID user, UUID chat, UUID id, String text, List<UUID> documents) {
        if (!documents.isEmpty()) throw new IllegalStateException("DOCUMENT_BINDING_UNSUPPORTED");
        return receive(user, chat, id, text);
    }

    /**
     * 读取本条消息的原件选择与当前可读性；默认空列表仅兼容不支持附件的旧端口。
     * @param user 归属
     * @param chat 聊天
     * @param input 消息
     * @return 该输入持久化的文件选择和当前可读性，不泄露其他用户信息
     */
    default List<Dialogue.DocumentReference> documents(UUID user, UUID chat, UUID input) {
        return List.of();
    }

    /**
     * 领取可执行的最早输入或接管失联尝试，保留原预算并使用新的执行围栏。
     * @return 同一聊天有序领取；超时重试保留原预算，超过尝试上限暂停并保留屏障
     */
    Optional<Dialogue.Claim> claim();

    /**
     * 排除尚未实际退出的模型用户后领取；默认仅委托claim，生产适配器必须实现排除。
     * @param excluded 尚未实际退出的模型用户
     * @return 公平发现，兼容旧测试端口
     */
    default Optional<Dialogue.Claim> claimExcluding(java.util.Set<UUID> excluded) {
        return claim();
    }

    /**
     * 按约定锁序核对原租约、需求及行程版本，是提交业务变化前的资格检查。
     * @param claim 原租约
     * @return 在归属锁保护下检查租约与业务版本；不允许迟到结果推进
     */
    boolean guard(Dialogue.Claim claim);

    /**
     * 显式解决本轮真正澄清的旧输入，不能用普通闲聊自动移除未决屏障。
     * @param claim 已锁领取
     * @param inputs 本轮明确解决的旧澄清；只允许同聊天CLARIFY消息，普通新消息不自动解除旧屏障
     */
    void resolve(Dialogue.Claim claim, List<UUID> inputs);

    /**
     * 在用户明确确认后关联新建旅行，不构造虚假的Agent执行资格。
     * @param user 已确认身份
     * @param chat 当前聊天
     * @param task 本次明确采纳新建的私有任务；不伪造聊天执行租约
     */
    void linkConfirmed(UUID user, UUID chat, UUID task);

    /**
     * 将当前输入产生的新旅行关联到聊天，不允许替换已存在的旅行关联。
     * @param claim 已通过guard的领取
     * @param task 新任务
     * @return 关联结果；已有任务不得替换
     */
    Dialogue.Chat link(Dialogue.Claim claim, UUID task);

    /**
     * 按原执行围栏提交本轮回复与待确认动作，并与后台作业状态保持原子一致。
     * @param claim 已通过guard
     * @param state DONE、CONFIRM、CLARIFY或PAUSED
     * @param reply 可信协议中的回复
     * @param operation 待确认动作，可空
     * @param error 固定错误码
     */
    void finish(
            Dialogue.Claim claim,
            String state,
            String reply,
            Dialogue.Operation operation,
            String error);

    /**
     * 锁定用户点击的精确消息；应用服务随后判断确认、撤回或重试是否合法。
     * @param user 身份
     * @param chat 聊天
     * @param message 消息
     * @param action CONFIRM、WITHDRAW或RETRY
     * @return 锁定的消息；状态检查由应用执行
     */
    Dialogue.Message lockMessage(UUID user, UUID chat, UUID message, String action);

    /**
     * 处理用户明确的撤回或同预算重试，旧执行不能因状态更新而重新获得提交权。
     * @param user 身份
     * @param chat 聊天
     * @param message 消息
     * @param state 新状态；撤回与重试不能重置预算
     */
    void changeState(UUID user, UUID chat, UUID message, String state);

    /**
     * 保存已确认操作的实际结果，与业务写入同事务，避免把意图识别当执行成功。
     * @param user 身份
     * @param chat 聊天
     * @param message 精确确认
     * @param reply 已提交操作结果；与实际操作同事务，不能保留旧的待确认文案冒充结果
     */
    void operationResult(UUID user, UUID chat, UUID message, String reply);

    /**
     * 判断任务是否仍有未解释输入，供候选展示和接受共同执行门禁。
     * @param user 身份
     * @param task 任务
     * @return 是否存在未解释自由输入，供展示及接受的持久屏障
     */
    boolean blocked(UUID user, UUID task);
}
