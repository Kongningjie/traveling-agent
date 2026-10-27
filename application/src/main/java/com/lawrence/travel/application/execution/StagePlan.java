package com.lawrence.travel.application.execution;

import com.lawrence.travel.domain.execution.ExecutionStep;
import com.lawrence.travel.domain.execution.PlanningMode;
import com.lawrence.travel.domain.rule.DomainViolation;
import java.util.List;
import java.util.UUID;

/**
 * 后续逻辑阶段定义；依赖只允许引用同一运行的既存阶段。
 * @param id 稳定身份
 * @param key 类型轮次分支
 * @param mode 规划模式
 * @param input JSON 对象输入
 * @param dependencies 前置阶段
 */
public record StagePlan(
        UUID id, ExecutionStep.Key key, PlanningMode mode, String input, List<UUID> dependencies) {
    /**
     * 校验结构；跨资源依赖及 JSON 摘要由提交事务再次验证。
     */
    public StagePlan {
        DomainViolation.require(
                id != null && key != null && input != null && dependencies != null,
                DomainViolation.Code.INVALID_INPUT);
        dependencies = List.copyOf(dependencies);
        DomainViolation.require(
                dependencies.size() == dependencies.stream().distinct().count()
                        && !dependencies.contains(id)
                        && key.branchKey().length() <= 128,
                DomainViolation.Code.INVALID_INPUT);
        DomainViolation.require(
                (key.type() == com.lawrence.travel.domain.execution.StageType.PLAN)
                        == (mode != null),
                DomainViolation.Code.INVALID_INPUT);
    }
}
