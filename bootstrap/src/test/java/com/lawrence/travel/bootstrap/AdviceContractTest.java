package com.lawrence.travel.bootstrap;

import com.lawrence.travel.domain.interaction.DialogueIntent;
import com.lawrence.travel.domain.interaction.PlanAdvice;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 文件建议与执行意图、固定安排及官方事实权限隔离的协议反例。 */
class AdviceContractTest {
    /** 模型不能通过规范字段建议取消预订或解除活动锁定。 */
    @Test
    void adviceCannotReleaseFixedArrangementsOrLocks() {
        for (String field : List.of("fixedStatus", "fixedArrangements", "lockedIds"))
            assertThatThrownBy(() -> draft(Map.of(field, "NONE"))).hasMessage("ADVICE_INVALID");
    }

    /** 分析建议不能夹带执行信封；真正开始规划走独立的显式意图。 */
    @Test
    void planningEnvelopeCannotCarryReadOnlyAdvice() {
        assertThatThrownBy(
                        () ->
                                new DialogueIntent.Result(
                                        List.of(
                                                new DialogueIntent.Intent(
                                                        DialogueIntent.Kind.PLANNING,
                                                        DialogueIntent.Action.START_PLAN,
                                                        "开始")),
                                        List.of(),
                                        "",
                                        "",
                                        null,
                                        "",
                                        "",
                                        "",
                                        List.of(),
                                        List.of(draft(Map.of("pace", "慢游")))))
                .hasMessage("DIALOGUE_INTERPRETATION_INVALID");
    }

    /**
     * @param changes 合成字段
     * @return 不含真实资料的有界建议
     */
    private PlanAdvice.Draft draft(Map<String, String> changes) {
        return new PlanAdvice.Draft(
                UUID.randomUUID(),
                "合成原文",
                PlanAdvice.Statement.UNKNOWN,
                "问题",
                "原因",
                "建议",
                "范围",
                PlanAdvice.Evidence.UNKNOWN,
                "",
                changes);
    }
}
