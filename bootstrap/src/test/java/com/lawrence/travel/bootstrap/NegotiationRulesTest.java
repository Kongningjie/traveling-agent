package com.lawrence.travel.bootstrap;

import com.lawrence.travel.agent.runtime.NegotiationInterpreter;
import com.lawrence.travel.application.port.AgentRuntimePort;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.domain.interaction.RequirementFields;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 协商确定性范围、预算冲突和真实调用关闭边界。 */
class NegotiationRulesTest {
    /** 新增偏好保留前项，重放不重复，显式替换只作用于指定软字段。 */
    @Test
    void preferencesAppendDeduplicateAndReplaceOnlyWhenExplicit() {
        for (String field : List.of("pace", "diet", "interests")) {
            var first =
                    RequirementFields.merge(
                            List.of(), Map.of(field, "原偏好"), ConstraintRevision.Source.USER);
            var added =
                    RequirementFields.merge(
                            first, Map.of(field, "新偏好"), ConstraintRevision.Source.USER);
            assertThat(added.getFirst().value()).isEqualTo("原偏好\n新偏好");
            assertThat(
                            RequirementFields.merge(
                                    added, Map.of(field, "新偏好"), ConstraintRevision.Source.USER))
                    .isEqualTo(added);
            var replaced =
                    RequirementFields.merge(
                            added,
                            Map.of(field, "替代内容"),
                            ConstraintRevision.Source.USER,
                            java.util.Set.of(field));
            assertThat(replaced.getFirst().value()).isEqualTo("替代内容");
            assertThat(replaced.getFirst().id()).isEqualTo(first.getFirst().id());
        }
        assertThatThrownBy(
                        () ->
                                RequirementFields.merge(
                                        List.of(),
                                        Map.of("hardRequirements", "取消限制"),
                                        ConstraintRevision.Source.USER,
                                        java.util.Set.of("hardRequirements")))
                .isInstanceOf(IllegalArgumentException.class);
        var hard =
                new ConstraintRevision.Requirement(
                        UUID.randomUUID(),
                        "pace",
                        "必须休息",
                        ConstraintRevision.Kind.HARD,
                        ConstraintRevision.Source.USER,
                        java.util.Set.of());
        assertThatThrownBy(
                        () ->
                                RequirementFields.merge(
                                        List.of(hard),
                                        Map.of("pace", "紧凑"),
                                        ConstraintRevision.Source.USER,
                                        java.util.Set.of("pace")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 多对象或同字段多条约束不能被简单字段合并覆盖，必须保留并澄清范围。 */
    @Test
    void ambiguousOrScopedRequirementsAreNeverSilentlyCollapsed() {
        var first =
                new ConstraintRevision.Requirement(
                        UUID.randomUUID(),
                        "pace",
                        "休息",
                        ConstraintRevision.Kind.HARD,
                        ConstraintRevision.Source.USER,
                        java.util.Set.of());
        var second =
                new ConstraintRevision.Requirement(
                        UUID.randomUUID(),
                        "pace",
                        "步行上限",
                        ConstraintRevision.Kind.HARD,
                        ConstraintRevision.Source.USER,
                        java.util.Set.of());
        assertThatThrownBy(
                        () ->
                                RequirementFields.merge(
                                        List.of(first, second),
                                        Map.of("pace", "轻松"),
                                        ConstraintRevision.Source.USER))
                .isInstanceOf(IllegalArgumentException.class);
        var scoped =
                new ConstraintRevision.Requirement(
                        first.id(),
                        "pace",
                        first.value(),
                        first.kind(),
                        first.source(),
                        java.util.Set.of(UUID.randomUUID()));
        assertThatThrownBy(
                        () ->
                                RequirementFields.merge(
                                        List.of(scoped),
                                        Map.of("pace", "紧凑"),
                                        ConstraintRevision.Source.USER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(scoped.targetIds()).hasSize(1);
    }

    /** 未形式化的体力、饮食等硬限制仍是 HARD，追加要求不能丢弃旧限制。 */
    @Test
    void genericHardLimitsAreRetainedInsteadOfBecomingSoftPreferences() {
        var first =
                RequirementFields.merge(
                        List.of(),
                        Map.of("hardRequirements", "每天步行不超过3公里"),
                        ConstraintRevision.Source.USER);
        var next =
                RequirementFields.merge(
                        first,
                        Map.of("hardRequirements", "每两小时休息"),
                        ConstraintRevision.Source.USER);
        assertThat(next.getFirst().kind()).isEqualTo(ConstraintRevision.Kind.HARD);
        assertThat(next.getFirst().value()).contains("每天步行不超过3公里", "每两小时休息");
        assertThat(
                        RequirementFields.merge(
                                        next,
                                        Map.of("hardRequirements", "每两小时休息"),
                                        ConstraintRevision.Source.USER)
                                .getFirst()
                                .value())
                .isEqualTo(next.getFirst().value());
    }

    /** 超过三城市或十天拒绝，不自动截断。 */
    @Test
    void rejectsUnsupportedScopeAndInvalidDates() {
        for (Map<String, String> values :
                List.of(
                        Map.of("cities", "北京,天津,上海,南京"),
                        Map.of("startDate", "2026-11-01", "endDate", "2026-11-11"),
                        Map.of("travelers", "0")))
            assertThatThrownBy(
                            () ->
                                    RequirementFields.merge(
                                            List.of(), values, ConstraintRevision.Source.USER))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        RequirementFields.merge(
                                List.of(),
                                Map.of(
                                        "cities",
                                        "北京,天津,上海",
                                        "startDate",
                                        "2026-11-01",
                                        "endDate",
                                        "2026-11-10"),
                                ConstraintRevision.Source.USER))
                .hasSize(3);
    }

    /** 不设预算会清理旧金额，未知包含范围不能当作已明确；新增金额需重新说明预算口径。 */
    @Test
    void budgetRuleDoesNotKeepAnObsoleteHardAmount() {
        var first =
                RequirementFields.merge(
                        List.of(),
                        Map.of(
                                "budgetRule",
                                "PER_GROUP",
                                "budgetCny",
                                "1000",
                                "budgetScope",
                                "LODGING"),
                        ConstraintRevision.Source.USER);
        var none =
                RequirementFields.merge(
                        first, Map.of("budgetRule", "NONE"), ConstraintRevision.Source.USER);
        assertThat(RequirementFields.fields(none)).doesNotContainKeys("budgetCny", "budgetScope");
        assertThat(
                        RequirementFields.missing(
                                RequirementFields.merge(
                                        none,
                                        Map.of("budgetCny", "2000"),
                                        ConstraintRevision.Source.USER)))
                .contains("budgetRule");
        assertThatThrownBy(
                        () ->
                                RequirementFields.merge(
                                        first,
                                        Map.of("budgetScope", "随便"),
                                        ConstraintRevision.Source.USER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 固定节点保持完整时区、跨午夜顺序及消息来源，不声明向票务平台核实。 */
    @Test
    void fixedNodesRequireValidTimeLineAndKeepSource() {
        UUID source = UUID.randomUUID();
        var fixed =
                RequirementFields.fixed(
                        "TRAIN|2026-11-01T23:00+08:00[Asia/Shanghai]|2026-11-02T01:00+08:00[Asia/Shanghai]",
                        source);
        assertThat(fixed.getFirst().sourceMessageId()).isEqualTo(source);
        var none =
                RequirementFields.merge(
                        List.of(), Map.of("fixedStatus", "NONE"), ConstraintRevision.Source.USER);
        var provided =
                RequirementFields.merge(
                        none,
                        Map.of(
                                "fixedArrangements",
                                "TRAIN|2026-11-01T23:00+08:00[Asia/Shanghai]|2026-11-02T01:00+08:00[Asia/Shanghai]"),
                        ConstraintRevision.Source.USER);
        assertThat(RequirementFields.fields(provided)).containsEntry("fixedStatus", "PROVIDED");
        assertThatThrownBy(
                        () ->
                                RequirementFields.fixed(
                                        "TRAIN|2026-11-02T01:00+08:00[Asia/Shanghai]|2026-11-01T23:00+08:00[Asia/Shanghai]",
                                        source))
                .isInstanceOf(RuntimeException.class);
    }

    /** 开关关闭时不能接触模型或编解码器，不因账号有额度自动联调。 */
    @Test
    void disabledInterpreterNeverCallsRuntime() {
        var runtime = mock(AgentRuntimePort.class);
        var json = mock(InteractionCodec.class);
        assertThatThrownBy(
                        () ->
                                new NegotiationInterpreter(runtime, json, false)
                                        .interpret(UUID.randomUUID(), null))
                .hasMessage("EXTERNAL_DISABLED");
        verifyNoInteractions(runtime, json);
    }
}
