package com.lawrence.travel.application.interaction;

import com.lawrence.travel.domain.interaction.DecisionRequest;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TaskScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 协商模型；用户原文不携带模型权限或自动接受授权。 */
public final class Negotiation {
    /** 不实例化命名空间。 */
    private Negotiation() { }
    /**
     * @param field 字段
     * @param value 规范值
     * @param quote 用户原文依据，结构化输入可空
     */
    public record Fact(String field, String value, String quote) { }
    /**
     * @param facts 明确提取项
     * @param outsideScope 含原问题外修改
     * @param clarification 尚不明确的说明
     */
    public record Interpretation(List<Fact> facts, boolean outsideScope, String clarification) { }
    /**
     * @param id 输入身份
     * @param kind CHANGE／ANSWER
     * @param text 原文
     * @param facts 结构化明确字段
     * @param decisionId 原卡片，可空
     * @param decisionRevision 预期问题版本
     * @param constraintRevision 预期需求
     * @param state 接收与采纳状态
     */
    public record Message(UUID id, String kind, String text, List<Fact> facts, UUID decisionId, long decisionRevision, long constraintRevision, String state) { }
    /**
     * @param field 可回答字段
     * @param reason 推荐理由
     * @param tradeoff 取舍
     * @param options 选项到规范值
     */
    public record QuestionSpec(String field, String reason, String tradeoff, Map<UUID, String> options) { }
    /**
     * @param decision 版本化问题
     * @param specs 规范字段和说明
     * @param rejected 明确拒绝的选项
     */
    public record Card(DecisionRequest decision, Map<UUID, QuestionSpec> specs, Set<UUID> rejected) { }
    /**
     * @param scope 归属
     * @param revision 当前需求
     * @param message 原始输入
     * @param card 原卡片
     * @param pendingRemaining 后续修改数
     * @param response 原问题回答或空
     * @param receivedAt 原始收件时间，相对日期不使用重启时间
     */
    public record Context(TaskScope scope, ConstraintRevision revision, Message message, Card card, int pendingRemaining, Response response, java.time.Instant receivedAt) { }
    /**
     * @param decisionId 卡片
     * @param revision 问题版本
     * @param constraints 需求版本
     * @param action 明确动作
     * @param selected 选项
     * @param text 原文
     * @param authorization 有限授权
     */
    public record Response(UUID decisionId, long revision, long constraints, String action, Map<UUID, UUID> selected, String text, Set<UUID> authorization) { }
    /**
     * @param inputId 输入
     * @param facts 已解释字段
     * @param resolved 本次解决问题
     * @param outsideScope 新修改
     * @param clarification 澄清
     * @param nextCard 下一张卡片或空
     */
    public record Prepared(UUID inputId, List<Fact> facts, Set<UUID> resolved, boolean outsideScope, String clarification, Card nextCard) { }
    /**
     * @param inputId 请求
     * @param status RECEIVED／RECORDED／APPLIED
     * @param runId 来源运行
     */
    public record Receipt(UUID inputId, String status, UUID runId) { }
}
