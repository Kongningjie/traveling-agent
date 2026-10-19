package com.lawrence.travel.application.document;

import com.lawrence.travel.application.interaction.Dialogue;
import com.lawrence.travel.domain.interaction.PlanAdvice;
import java.util.Map;
import java.util.UUID;

/** PG权威建议及采纳回执；APPLIED只表示已登记变更，绝非行程生效。 */
public final class Advice {
    /** 协议命名空间。 */
    private Advice() { }
    /** @param basis 精确旅行基础 @param preference 偏好版本 @param documents 资料水位 @param blocked 是否有未决输入 */
    public record Context(Dialogue.Basis basis,long preference,long documents,boolean blocked) { }
    /** @param id 服务端建议ID @param user 用户 @param chat 聊天 @param message 分析输入 @param document 原件 @param context 分析依赖
     * @param draft 结构化建议 @param state OPEN/REJECTED/APPLIED @param applied 已提交字段，用于重复确认内容比较 @param task 采纳后关联任务，可空 */
    public record Entry(UUID id,UUID user,UUID chat,UUID message,UUID document,Context context,PlanAdvice.Draft draft,String state,Map<String,String> applied,UUID task) { }
    /** @param id 建议 @param message 分析消息 @param document 原件 @param draft 纯建议 @param state 含动态STALE @param task 变更目标，可空 @param applied 实际已确认字段，自定义后不展示原提议冒充结果 */
    public record View(UUID id,UUID message,UUID document,PlanAdvice.Draft draft,String state,UUID task,Map<String,String> applied) { }
}
