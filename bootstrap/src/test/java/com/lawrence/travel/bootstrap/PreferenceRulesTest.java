package com.lawrence.travel.bootstrap;

import com.lawrence.travel.domain.memory.Preference;
import com.lawrence.travel.domain.memory.PreferenceVersion;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 确定性偏好过滤；不让模型猜测确认、范围或删除状态。 */
class PreferenceRulesTest {
    /**
     * @param category 类别
     * @param city 范围
     * @param state 状态
     * @return 固定时间合成偏好
     */
    private Preference entry(String category, String city, Preference.State state) {
        return new Preference(
                UUID.randomUUID(),
                category,
                "合成值",
                city,
                Preference.Source.USER,
                state,
                Instant.EPOCH);
    }

    /**
     * @param entries 当前条目
     * @return 唯一用户版本的快照
     */
    private Preference.View view(Preference... entries) {
        return new Preference.View(new PreferenceVersion(UUID.randomUUID(), 3), List.of(entries));
    }

    /** 范围必须精确匹配，待确认不能依靠模型解释为用户采纳。 */
    @Test
    void filtersPendingAndUnrelatedCities() {
        var recalled =
                Preference.recall(
                        view(
                                entry("pace", "", Preference.State.CONFIRMED),
                                entry("diet", "北京", Preference.State.CONFIRMED),
                                entry("transport", "上海", Preference.State.CONFIRMED),
                                entry("interests", "", Preference.State.PENDING)),
                        Set.of("北京"),
                        Set.of());
        assertThat(recalled.entries())
                .extracting(Preference::category)
                .containsExactly("pace", "diet");
    }

    /** 本次明确字段优先，历史值不会并列进入提示词。 */
    @Test
    void explicitTripFieldsOverrideHistoricalPreference() {
        assertThat(
                        Preference.recall(
                                        view(entry("pace", "", Preference.State.CONFIRMED)),
                                        Set.of("北京"),
                                        Set.of("pace"))
                                .entries())
                .isEmpty();
    }

    /** 单城有更具体范围时去掉同类通用项，避免互斥值并列作为同城事实。 */
    @Test
    void citySpecificOverridesGlobalInSingleCity() {
        var result =
                Preference.recall(
                        view(
                                entry("pace", "", Preference.State.CONFIRMED),
                                entry("pace", "北京", Preference.State.CONFIRMED)),
                        Set.of("北京"),
                        Set.of());
        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().getFirst().city()).isEqualTo("北京");
    }

    /** 多城保留范围区别，通用项用于未设置单城覆盖的城市。 */
    @Test
    void multiCityRetainsExplicitScope() {
        assertThat(
                        Preference.recall(
                                        view(
                                                entry("pace", "", Preference.State.CONFIRMED),
                                                entry("pace", "北京", Preference.State.CONFIRMED)),
                                        Set.of("北京", "上海"),
                                        Set.of())
                                .entries())
                .extracting(Preference::city)
                .containsExactly("", "北京");
    }

    /** 墓碑不能作为当前快照，未知类别和超长值在领域入口即拒绝。 */
    @Test
    void rejectsTombstoneRecallAndUnboundedInput() {
        assertThatThrownBy(() -> view(entry("pace", "", Preference.State.DELETED)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry("arbitrarySql", "", Preference.State.CONFIRMED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new Preference(
                                        UUID.randomUUID(),
                                        "pace",
                                        "a".repeat(501),
                                        "",
                                        Preference.Source.USER,
                                        Preference.State.CONFIRMED,
                                        Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
