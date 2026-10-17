package com.lawrence.travel.application.port;

import com.lawrence.travel.application.interaction.Negotiation;
import com.lawrence.travel.domain.travel.TaskScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 可靠输入和卡片；写入须参与同一业务事务。 */
public interface NegotiationStore extends StageProjection {
    /**
     * @param scope 归属
     * @param id 输入
     * @param text 原文
     * @param facts 结构化明确字段
     * @return 首次登记，重放不再次取代
     */
    boolean receiveChange(TaskScope scope, UUID id, String text, List<Negotiation.Fact> facts);
    /**
     * @param scope 归属
     * @param id 输入
     * @return 初次稳定响应或空
     */
    Negotiation.Receipt receipt(TaskScope scope, UUID id);
    /**
     * @param scope 归属
     * @param result 初次稳定响应
     */
    void saveReceipt(TaskScope scope, Negotiation.Receipt result);
    /**
     * @param scope 归属
     * @param id 回答
     * @param decision 卡片
     * @param revision 问题版本
     * @param constraints 需求
     * @param action 明确动作
     * @param selected 选项
     * @param text 原文
     * @param authorization 有限授权
     * @return 原卡片，重放先检查内容
     */
    Negotiation.Card receiveAnswer(TaskScope scope, UUID id, UUID decision, long revision, long constraints, String action, Map<UUID, UUID> selected, String text, Set<UUID> authorization);
    /**
     * @param scope 归属
     * @return 最近一百条消息
     */
    List<Negotiation.Message> messages(TaskScope scope);
    /**
     * @param scope 归属
     * @return 当前有效卡片或空
     */
    Negotiation.Card card(TaskScope scope);
    /**
     * @param scope 归属
     * @return 本任务明确拒绝的稳定选项身份，跨轮次保留
     */
    Set<UUID> rejected(TaskScope scope);
    /**
     * @param scope 归属
     * @param inputId 阶段输入
     * @return 当前需求及最早待处理修改，或指定原问题回答
     */
    Negotiation.Context context(TaskScope scope, UUID inputId);
}
