package com.lawrence.travel.application.itinerary;

import com.lawrence.travel.application.execution.RunService;
import com.lawrence.travel.application.execution.StagePlan;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.TravelStore;
import com.lawrence.travel.application.port.VersionStore;
import com.lawrence.travel.domain.execution.ExecutionRun;
import com.lawrence.travel.domain.execution.ExecutionStep;
import com.lawrence.travel.domain.execution.PlanningMode;
import com.lawrence.travel.domain.execution.StageType;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.itinerary.ImpactAnalyzer;
import com.lawrence.travel.domain.itinerary.VersionDiff;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TaskScope;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 版本浏览与显式历史恢复；恢复是新运行的新候选，模型不在HTTP事务执行。 */
public final class VersionService {
    private final VersionStore versions;
    private final TravelStore travel;
    private final RunService runs;
    private final BusinessTransactions transactions;
    private final InteractionCodec json;

    /**
     * @param versions 私有历史
     * @param travel 需求权威
     * @param runs 唯一执行器
     * @param transactions 同源短事务
     * @param json 严格JSON
     */
    public VersionService(
            VersionStore versions,
            TravelStore travel,
            RunService runs,
            BusinessTransactions transactions,
            InteractionCodec json) {
        this.versions = versions;
        this.travel = travel;
        this.runs = runs;
        this.transactions = transactions;
        this.json = json;
    }

    /**
     * @param actor 认证
     * @param task 任务
     * @param offset 分页
     * @param limit 页大小
     * @return 私有元信息
     */
    public List<Versions.Summary> list(UserIdentity actor, UUID task, int offset, int limit) {
        return versions.list(scope(actor, task), offset, limit);
    }

    /**
     * @param actor 认证
     * @param task 任务
     * @param id 快照
     * @return 私有完整内容
     */
    public Versions.Detail detail(UserIdentity actor, UUID task, UUID id) {
        return versions.detail(scope(actor, task), id);
    }

    /**
     * @param actor 认证
     * @param task 任务
     * @param from 原版本
     * @param to 新版本
     * @return 确定性对象、窗口及历史绑定锁定差异
     */
    public List<VersionDiff.Change> diff(UserIdentity actor, UUID task, UUID from, UUID to) {
        var scope = scope(actor, task);
        var a = versions.detail(scope, from);
        var b = versions.detail(scope, to);
        return VersionDiff.compare(
                a.snapshot(),
                b.snapshot(),
                locks(travel.revision(scope, a.summary().version().constraintRevision())),
                locks(travel.revision(scope, b.summary().version().constraintRevision())));
    }

    /**
     * @param actor 认证
     * @param task 任务
     * @param request 请求键
     * @param snapshot 历史目标
     * @param expected 当前指针
     * @param revision 当前需求
     * @param generation 当前代次
     * @return 已可靠排队的新运行；同请求稳定重放，不隐式接受
     */
    public ExecutionRun restore(
            UserIdentity actor,
            UUID task,
            UUID request,
            UUID snapshot,
            UUID expected,
            long revision,
            long generation) {
        var scope = scope(actor, task);
        if (request == null
                || snapshot == null
                || expected == null
                || revision < 1
                || generation < 1) throw new IllegalArgumentException("RESTORE_INPUT_INVALID");
        var command =
                new Versions.Restore(scope, request, snapshot, expected, revision, generation);
        // 因果身份与整个命令确定，网络重试不生成第二个运行；改变任一预期条件会触发原执行入口的幂等冲突。
        var first =
                new StagePlan(
                        UUID.nameUUIDFromBytes(
                                (request + ":restore").getBytes(StandardCharsets.UTF_8)),
                        new ExecutionStep.Key(StageType.PLAN, 0, "restore_" + request),
                        PlanningMode.REPLAN,
                        json.encode(
                                java.util.Map.of(
                                        "restoreSnapshotId", snapshot, "restoreCommand", command)),
                        List.of());
        return transactions.required(
                () -> {
                    versions.guardRestore(command);
                    return runs.start(actor, task, request, first);
                });
    }

    /**
     * @param revision 版本绑定的需求
     * @return 明确锁定，空表示无锁定
     */
    private Set<UUID> locks(ConstraintRevision revision) {
        return revision.requirements().stream()
                .filter(r -> r.field().equals("lockedIds"))
                .flatMap(r -> ImpactAnalyzer.ids(r.value()).stream())
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * @param actor 服务端认证
     * @param task 任务
     * @return 私有归属，空身份拒绝
     */
    private TaskScope scope(UserIdentity actor, UUID task) {
        if (actor == null)
            throw new com.lawrence.travel.application.travel.TravelStorageFailure(
                    com.lawrence.travel.application.travel.TravelStorageFailure.Code.NOT_FOUND);
        return new TaskScope(actor.id(), task);
    }
}
