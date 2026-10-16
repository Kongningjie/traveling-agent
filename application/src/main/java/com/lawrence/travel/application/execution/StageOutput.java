package com.lawrence.travel.application.execution;

import com.lawrence.travel.domain.interaction.DecisionRequest;
import com.lawrence.travel.domain.rule.DomainViolation;
import java.util.List;

/**
 * 阶段可提交产物；不提供绕过候选审核直接接受行程的状态。
 * @param text 业务文本
 * @param checkpoint JSON 检查点
 * @param next 后续阶段
 * @param outcome 完成方式
 * @param decision 持久问题或空
 */
public record StageOutput(String text, String checkpoint, List<StagePlan> next, Outcome outcome, DecisionRequest decision) {
    /**
     * 咨询完成与生成可接受行程严格区分。
     */
    public enum Outcome { /**
 * 继续动态阶段。
 */ CONTINUE, /**
 * 当前分支已完成，父运行仍由剩余事件决定。
 */ BRANCH_COMPLETE, /**
 * 纯咨询完成。
 */ CONSULTATION_COMPLETE, /**
 * 等待明确回答。
 */ WAIT_USER }
    /**
     * 禁止没有下一步却仍显示运行，也禁止把问题或行程生成当作咨询完成。
     */
    public StageOutput {
        DomainViolation.require(text != null && checkpoint != null && next != null && outcome != null, DomainViolation.Code.INVALID_INPUT);
        next = List.copyOf(next);
        DomainViolation.require((outcome == Outcome.CONTINUE) == !next.isEmpty()
                && (outcome == Outcome.WAIT_USER) == (decision != null), DomainViolation.Code.INVALID_INPUT);
    }
}
