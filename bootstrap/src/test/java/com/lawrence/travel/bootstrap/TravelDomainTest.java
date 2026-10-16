package com.lawrence.travel.bootstrap;

import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.domain.travel.TravelTask;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TripWindow;
import com.lawrence.travel.domain.memory.PreferenceVersion;
import com.lawrence.travel.domain.knowledge.EvidenceRecord;
import com.lawrence.travel.domain.itinerary.ItineraryVersion;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 需求、范围与不可变依赖的纯 Java 测试，不启动 Spring、数据库或模型。 */
class TravelDomainTest {
    private final TaskScope scope = new TaskScope(UUID.randomUUID(), UUID.randomUUID());
    private final UUID currentVersion = UUID.randomUUID();

    /** @return 已有生效行程但尚无新输入的任务 */
    private TravelTask task() { return new TravelTask(scope, 1, 1, currentVersion, Map.of()); }

    /** 新消息先阻止接受；确认修改立即撤销旧执行，需求提取成功后才推进需求版本。 */
    @Test
    void separatesInputChangeAndConstraintRevisions() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var original = task();
        var pending = original.registerInput(first, 10).registerInput(second, 11);
        assertThatThrownBy(pending::requireNoPendingInputs).hasMessage("PENDING_INPUT");
        assertThat(pending.executionGeneration()).isEqualTo(1);
        var changed = pending.registerChange(first);
        assertThat(changed.registerChange(first)).isSameAs(changed);
        assertThat(changed.executionGeneration()).isEqualTo(2);
        assertThat(changed.constraintRevision()).isEqualTo(1);
        var applied = changed.applyChange(first, 1);
        assertThat(applied.constraintRevision()).isEqualTo(2);
        assertThat(applied.pendingInputs()).containsOnlyKeys(second);
        assertThat(applied.currentVersionId()).isEqualTo(currentVersion);
        assertThat(original.pendingInputs()).isEmpty();
        assertThatThrownBy(() -> changed.applyChange(first, 2)).hasMessage("STALE_REVISION");
    }

    /** 后发要求不得跳过前一条未完成输入；纯咨询也不能误删已确认修改。 */
    @Test
    void processesInputsInMessageOrderAndKeepsUnfinishedRequirements() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var task = task().registerInput(first, 1).registerInput(second, 2).registerChange(second);
        assertThatThrownBy(() -> task.applyChange(second, 1)).hasMessage("ILLEGAL_TRANSITION");
        assertThatThrownBy(() -> task.resolveInquiry(second)).hasMessage("ILLEGAL_TRANSITION");
        var resolved = task.resolveInquiry(first).applyChange(second, 1);
        resolved.requireNoPendingInputs();
        assertThat(resolved.executionGeneration()).isEqualTo(2);
        assertThatThrownBy(() -> task.registerInput(first, 3)).hasMessage("IDEMPOTENCY_CONFLICT");
    }

    /** 取消只撤销执行资格，不能抹掉已接受版本、需求或待解析输入。 */
    @Test
    void fencingKeepsAcceptedBusinessData() {
        UUID input = UUID.randomUUID();
        var original = task().registerInput(input, 1);
        var stopped = original.fenceExecution();
        assertThat(stopped.executionGeneration()).isEqualTo(2);
        assertThat(stopped.currentVersionId()).isEqualTo(currentVersion);
        assertThat(stopped.pendingInputs()).isEqualTo(original.pendingInputs());
        assertThatThrownBy(() -> scope.requireSame(new TaskScope(UUID.randomUUID(), scope.taskId()))).hasMessage("SCOPE_MISMATCH");
    }

    /** 首末日期包含当天，验证 1／3 城市和 1／10 天边界，超出明确拒绝。 */
    @Test
    void enforcesFirstReleaseSizeWithoutRestrictingTravelerGroups() {
        var start = LocalDate.of(2026, 10, 20);
        assertThat(new TripWindow(Set.of("a"), start, start, 1).travelers()).isEqualTo(1);
        assertThat(new TripWindow(Set.of("a", "b", "c"), start, start.plusDays(9), 8).cityIds()).hasSize(3);
        assertThatThrownBy(() -> new TripWindow(Set.of("a"), start, start.plusDays(10), 1)).hasMessage("INVALID_INPUT");
        assertThatThrownBy(() -> new TripWindow(Set.of("a", "b", "c", "d"), start, start, 1)).hasMessage("INVALID_INPUT");
        assertThatThrownBy(() -> new TripWindow(Set.of("a"), start, start.minusDays(1), 1)).hasMessage("INVALID_INPUT");
    }

    /** 假设不能成为硬要求，历史版本及对象适用范围不受调用方集合修改影响。 */
    @Test
    void requirementsAreImmutableAndDoNotPromoteAssumptions() {
        var targets = new HashSet<UUID>();
        targets.add(UUID.randomUUID());
        var requirement = new ConstraintRevision.Requirement(UUID.randomUUID(), "walking", "5000m",
                ConstraintRevision.Kind.HARD, ConstraintRevision.Source.USER, targets);
        var requirements = new ArrayList<>(List.of(requirement));
        var revision = new ConstraintRevision(scope, 1, requirements, List.of());
        targets.clear();
        requirements.clear();
        assertThat(revision.requirements()).hasSize(1);
        assertThat(requirement.targetIds()).hasSize(1);
        assertThatThrownBy(() -> new ConstraintRevision.Requirement(UUID.randomUUID(), "budget", "1000",
                ConstraintRevision.Kind.HARD, ConstraintRevision.Source.ASSUMPTION, Set.of())).hasMessage("INVALID_INPUT");
        assertThatThrownBy(() -> new ConstraintRevision(scope, 2, List.of(requirement, requirement), List.of())).hasMessage("INVALID_INPUT");
    }

    /** 固定安排使用完整日期与时区，跨午夜正常，逆序明确拒绝。 */
    @Test
    void fixedArrangementsUseActualTimeLine() {
        var start = ZonedDateTime.parse("2026-10-20T23:30:00+08:00[Asia/Shanghai]");
        var fixed = new ConstraintRevision.FixedArrangement(UUID.randomUUID(), "transport", start, start.plusHours(2), UUID.randomUUID());
        assertThat(fixed.endsAt().toLocalDate()).isEqualTo(start.toLocalDate().plusDays(1));
        assertThatThrownBy(() -> new ConstraintRevision.FixedArrangement(UUID.randomUUID(), "transport", start,
                start.minusMinutes(1), UUID.randomUUID())).hasMessage("INVALID_INPUT");
    }

    /** 精确证据版本防御复制，同一 ID 不可同时引用多个内容版本，偏好也必须同用户。 */
    @Test
    void snapshotBindsExactEvidenceAndPreferenceVersions() {
        UUID evidenceId = UUID.randomUUID();
        var first = new EvidenceRecord.Reference(evidenceId, 1, "a".repeat(64));
        var refs = new HashSet<>(Set.of(first));
        var preference = new PreferenceVersion(scope.userId(), 0);
        var version = new ItineraryVersion(UUID.randomUUID(), scope, 1, "B".repeat(64), 1, currentVersion, refs, preference);
        refs.clear();
        assertThat(version.evidenceRefs()).containsExactly(first);
        assertThat(version.payloadHash()).isEqualTo("b".repeat(64));
        assertThatThrownBy(() -> new ItineraryVersion(UUID.randomUUID(), scope, 1, "b".repeat(64), 1, null,
                Set.of(first, new EvidenceRecord.Reference(evidenceId, 2, "c".repeat(64))), preference)).hasMessage("INVALID_INPUT");
        assertThatThrownBy(() -> new ItineraryVersion(UUID.randomUUID(), scope, 1, "b".repeat(64), 1, null, Set.of(),
                new PreferenceVersion(UUID.randomUUID(), 1))).hasMessage("INVALID_INPUT");
        assertThat(preference.next(0).revision()).isEqualTo(1);
        assertThatThrownBy(() -> preference.next(1)).hasMessage("STALE_REVISION");
    }

    /** 有效截止为排他边界，资料不足即使时间新鲜也不能冒充已知事实。 */
    @Test
    void evidenceFreshnessDoesNotTurnUnknownIntoFact() {
        var now = Instant.parse("2026-10-16T01:00:00Z");
        var ref = new EvidenceRecord.Reference(UUID.randomUUID(), 1, "1".repeat(64));
        var known = new EvidenceRecord(ref, "hours", URI.create("https://example.org/hours"), now, now.plusSeconds(10), EvidenceRecord.Status.KNOWN);
        assertThat(known.knownAndFreshAt(now)).isTrue();
        assertThat(known.knownAndFreshAt(now.plusSeconds(10))).isFalse();
        var unknown = new EvidenceRecord(ref, "hours", known.source(), now, known.validUntil(), EvidenceRecord.Status.UNKNOWN);
        assertThat(unknown.knownAndFreshAt(now)).isFalse();
    }

    /** 反序列化集合中缺失内容时应返回固定领域错误，而不是漏出空指针异常。 */
    @Test
    void rejectsMissingCollectionMembersWithDomainClassification() {
        assertThatThrownBy(() -> task().registerChange(null)).hasMessage("INVALID_INPUT");
        assertThatThrownBy(() -> task().resolveInquiry(null)).hasMessage("INVALID_INPUT");
        var pending = new java.util.HashMap<UUID, TravelTask.PendingInput>();
        pending.put(UUID.randomUUID(), null);
        assertThatThrownBy(() -> new TravelTask(scope, 1, 1, null, pending)).hasMessage("INVALID_INPUT");
        var requirements = new ArrayList<ConstraintRevision.Requirement>();
        requirements.add(null);
        assertThatThrownBy(() -> new ConstraintRevision(scope, 1, requirements, List.of())).hasMessage("INVALID_INPUT");
    }
}
