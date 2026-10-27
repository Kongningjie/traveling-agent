package com.lawrence.travel.bootstrap;

import com.lawrence.travel.domain.execution.AgentStrategy;
import com.lawrence.travel.application.execution.ExecutionCorrelation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 有限委派协议的确定性边界，不依靠模型给自己评分。 */
class AgentStrategyTest {
    /** 有向依赖只能引用更早分支，循环、自依赖和重复依赖全部拒绝。 */
    @Test
    void rejectsCyclesAndDuplicateEdges() {
        for (var dependencies : List.of(List.of(0), List.of(1), List.of(-1)))
            assertThatThrownBy(() -> new AgentStrategy.Branch(0, dependencies, List.of("PLACE")))
                    .hasMessage("AGENT_STRATEGY_INVALID");
        assertThatThrownBy(() -> new AgentStrategy.Branch(1, List.of(0, 0), List.of("PLACE")))
                .hasMessage("AGENT_STRATEGY_INVALID");
    }

    /** 查询下标连续且拓扑有序，工具请求只能是一项，不能借策略索取Shell。 */
    @Test
    void rejectsInvalidShapeAndUnboundedBranches() {
        assertThatThrownBy(() -> new AgentStrategy.Branch(12, List.of(), List.of("PLACE")))
                .hasMessage("AGENT_STRATEGY_INVALID");
        assertThatThrownBy(() -> new AgentStrategy.Branch(0, List.of(), List.of("PLACE", "SHELL")))
                .hasMessage("AGENT_STRATEGY_INVALID");
        assertThatThrownBy(
                        () ->
                                new AgentStrategy(
                                        1,
                                        AgentStrategy.Goal.RESEARCH,
                                        AgentStrategy.Stop.PLAN,
                                        List.of(
                                                new AgentStrategy.Branch(
                                                        1, List.of(), List.of("PLACE"))),
                                        List.of()))
                .hasMessage("AGENT_STRATEGY_INVALID");
    }

    /** 复用不能悄悄追加调查；澄清必须带缺项并等待用户。 */
    @Test
    void goalsHaveExplicitStopConditions() {
        assertThatThrownBy(
                        () ->
                                new AgentStrategy(
                                        1,
                                        AgentStrategy.Goal.CLARIFY,
                                        AgentStrategy.Stop.PLAN,
                                        List.of(),
                                        List.of("cities")))
                .hasMessage("AGENT_STRATEGY_INVALID");
        assertThatThrownBy(
                        () ->
                                new AgentStrategy(
                                        1,
                                        AgentStrategy.Goal.REUSE,
                                        AgentStrategy.Stop.REFINE_ONCE,
                                        List.of(),
                                        List.of()))
                .hasMessage("AGENT_STRATEGY_INVALID");
        assertThat(
                        new AgentStrategy(
                                        1,
                                        AgentStrategy.Goal.REUSE,
                                        AgentStrategy.Stop.PLAN,
                                        List.of(),
                                        List.of())
                                .branches())
                .isEmpty();
    }

    /** 子轨迹不能替换PG业务attempt；嵌套退出和线程复用均恢复正确父级。 */
    @Test
    void diagnosticParentDoesNotChangeBusinessFence() {
        UUID attempt = UUID.randomUUID(), child = UUID.randomUUID();
        try (var original = ExecutionCorrelation.bindSpan(attempt)) {
            assertThat(ExecutionCorrelation.traceParent()).isEqualTo(attempt);
            try (var nested = ExecutionCorrelation.bindTrace(child)) {
                assertThat(ExecutionCorrelation.traceParent()).isEqualTo(child);
                assertThat(ExecutionCorrelation.parentSpan()).isEqualTo(attempt);
            }
            assertThat(ExecutionCorrelation.traceParent()).isEqualTo(attempt);
        }
        assertThat(ExecutionCorrelation.parentSpan()).isNull();
        assertThat(ExecutionCorrelation.traceParent()).isNull();
    }
}
