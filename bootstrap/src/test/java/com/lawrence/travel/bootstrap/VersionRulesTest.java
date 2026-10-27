package com.lawrence.travel.bootstrap;

import com.lawrence.travel.domain.itinerary.HistoricalPatch;
import com.lawrence.travel.domain.itinerary.ImpactAnalyzer;
import com.lawrence.travel.domain.itinerary.PatchService;
import com.lawrence.travel.domain.itinerary.TripSnapshot;
import com.lawrence.travel.domain.itinerary.VersionDiff;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TaskScope;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 历史比较和恢复Patch的纯规则；不读数据库，不沿用旧审核结论。 */
class VersionRulesTest {
    /**
     * @param source 原内容
     * @param title 新说明
     * @return 只修改一项活动，其余ID与对象保持
     */
    private TripSnapshot changed(TripSnapshot source, String title) {
        var old = source.items().getFirst();
        var item =
                new TripSnapshot.Item(
                        old.id(),
                        old.cityStayId(),
                        old.type(),
                        title,
                        old.locationKey(),
                        old.startsAt(),
                        old.endsAt(),
                        old.fixedArrangementId());
        return new TripSnapshot(
                source.schemaVersion(),
                source.taskId(),
                source.constraintRevision(),
                source.baseVersionId(),
                source.window(),
                source.cityStays(),
                source.days(),
                List.of(item),
                source.transportLegs(),
                source.accommodationStays(),
                source.costEntries(),
                source.alternatives(),
                source.evidenceRefs(),
                source.evidenceUses());
    }

    /** 差异按稳定身份定位文字和锁定，不把未变对象误记为新增或删除。 */
    @Test
    void stableIdsExposeChangedFieldsAndLocks() {
        var old = ItineraryRulesTest.minimal(UUID.randomUUID(), null);
        var next = changed(old, "历史文字");
        var changes = VersionDiff.compare(old, next, Set.of(), Set.of(old.items().getFirst().id()));
        assertThat(changes).hasSize(2);
        assertThat(changes)
                .anyMatch(
                        c ->
                                c.kind().equals("Item")
                                        && c.action().equals("CHANGED")
                                        && c.after().get("title").equals("历史文字"));
        assertThat(changes).anyMatch(c -> c.kind().equals("LOCK"));
        assertThat(VersionDiff.compare(old, old, Set.of(), Set.of())).isEmpty();
    }

    /** 恢复沿用当前需求修订，产生新候选内容而不是原地覆盖历史。 */
    @Test
    void restorePatchRetainsCurrentRevisionAndBase() {
        var current = ItineraryRulesTest.minimal(UUID.randomUUID(), null);
        var historical = changed(current, "历史文字");
        var scope = new TaskScope(UUID.randomUUID(), current.taskId());
        var revision = new ConstraintRevision(scope, 4, List.of(), List.of());
        var id = UUID.randomUUID();
        var patch = HistoricalPatch.create(current, historical, "a".repeat(64));
        var impact = new ImpactAnalyzer().analyze(current, revision, revision, List.of());
        var result = new PatchService().apply(current, id, "a".repeat(64), 4, impact, patch);
        assertThat(result.items()).isEqualTo(historical.items());
        assertThat(result.constraintRevision()).isEqualTo(4);
        assertThat(result.baseVersionId()).isEqualTo(id);
        assertThat(current.items().getFirst().title()).isNotEqualTo("历史文字");
    }

    /** 用户恢复意图不等于解除当前锁定，锁冲突仍拒绝合并。 */
    @Test
    void restoreCannotBypassCurrentLock() {
        var current = ItineraryRulesTest.minimal(UUID.randomUUID(), null);
        var scope = new TaskScope(UUID.randomUUID(), current.taskId());
        var locked =
                new ConstraintRevision.Requirement(
                        UUID.randomUUID(),
                        "lockedIds",
                        current.items().getFirst().id().toString(),
                        ConstraintRevision.Kind.HARD,
                        ConstraintRevision.Source.USER,
                        Set.of());
        var revision = new ConstraintRevision(scope, 2, List.of(locked), List.of());
        var impact = new ImpactAnalyzer().analyze(current, revision, revision, List.of());
        var patch = HistoricalPatch.create(current, changed(current, "历史文字"), "a".repeat(64));
        assertThatThrownBy(
                        () ->
                                new PatchService()
                                        .apply(
                                                current,
                                                UUID.randomUUID(),
                                                "a".repeat(64),
                                                2,
                                                impact,
                                                patch))
                .hasMessage("PATCH_LOCKED");
    }

    /** 不同任务不能拼接成历史恢复，即便对象字段形状完全相同。 */
    @Test
    void crossTaskHistoricalPatchIsRejected() {
        assertThatThrownBy(
                        () ->
                                HistoricalPatch.create(
                                        ItineraryRulesTest.minimal(UUID.randomUUID(), null),
                                        ItineraryRulesTest.minimal(UUID.randomUUID(), null),
                                        "a".repeat(64)))
                .hasMessage("RESTORE_SCOPE_INVALID");
    }
}
