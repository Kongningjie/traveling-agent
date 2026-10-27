package com.lawrence.travel.application.itinerary;

import com.lawrence.travel.application.execution.StagePlan;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.CandidateStore;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.domain.execution.ExecutionStep;
import com.lawrence.travel.domain.execution.StageType;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.travel.TaskScope;
import java.util.UUID;

/**
 * 查询、接受或重新校验行程候选。
 */
public final class ItineraryService {
    private final CandidateStore store;
    private final ExecutionEngineStore engine;
    private final BusinessTransactions transactions;

    /**
     * @param store 候选权威
     * @param engine 同源执行登记
     * @param transactions 同一业务事务
     */
    public ItineraryService(
            CandidateStore store, ExecutionEngineStore engine, BusinessTransactions transactions) {
        this.store = store;
        this.engine = engine;
        this.transactions = transactions;
    }

    /**
     * @param actor 认证主体
     * @param taskId 任务
     * @return 当前运行最近草案及Java总价；不以列表读取代替接受门禁
     */
    public Planning.View latest(UserIdentity actor, UUID taskId) {
        return store.latest(new TaskScope(actor.id(), taskId));
    }

    /**
     * @param actor 服务端认证身份
     * @param taskId 任务
     * @param requestId 幂等请求
     * @param candidateId 候选
     * @param expectedCurrent 当前指针
     * @param revision 需求
     * @param generation 代次
     * @param hash 快照摘要
     * @return 接受或需要重新校验
     */
    public CandidateStore.Acceptance accept(
            UserIdentity actor,
            UUID taskId,
            UUID requestId,
            UUID candidateId,
            UUID expectedCurrent,
            long revision,
            long generation,
            String hash) {
        return submit(
                actor,
                taskId,
                requestId,
                candidateId,
                expectedCurrent,
                revision,
                generation,
                hash,
                false);
    }

    /**
     * @param actor 认证
     * @param taskId 任务
     * @param requestId 请求
     * @param candidateId 候选
     * @param expectedCurrent 当前指针
     * @param revision 需求
     * @param generation 代次
     * @param hash 内容
     * @return 只排队重新校验，之后仍需另行明确接受
     */
    public CandidateStore.Acceptance recheck(
            UserIdentity actor,
            UUID taskId,
            UUID requestId,
            UUID candidateId,
            UUID expectedCurrent,
            long revision,
            long generation,
            String hash) {
        return submit(
                actor,
                taskId,
                requestId,
                candidateId,
                expectedCurrent,
                revision,
                generation,
                hash,
                true);
    }

    /**
     * @param actor 认证
     * @param taskId 任务
     * @param requestId 请求
     * @param candidateId 候选
     * @param expectedCurrent 基础
     * @param revision 需求
     * @param generation 代次
     * @param hash 摘要
     * @param forceRecheck 仅校验意图
     * @return 同事务结果及可靠后继
     */
    private CandidateStore.Acceptance submit(
            UserIdentity actor,
            UUID taskId,
            UUID requestId,
            UUID candidateId,
            UUID expectedCurrent,
            long revision,
            long generation,
            String hash,
            boolean forceRecheck) {
        if (actor == null)
            throw new com.lawrence.travel.application.travel.TravelStorageFailure(
                    com.lawrence.travel.application.travel.TravelStorageFailure.Code.NOT_FOUND);
        var scope = new TaskScope(actor.id(), taskId);
        var command =
                new CandidateAcceptanceCommand(
                        scope, requestId, candidateId, expectedCurrent, revision, generation, hash);
        return transactions.required(
                () -> {
                    var attempt = forceRecheck ? store.recheck(command) : store.accept(command);
                    if (attempt.enqueueRevalidation()) {
                        UUID snapshot = store.snapshotId(scope, candidateId);
                        var next =
                                new StagePlan(
                                        UUID.randomUUID(),
                                        new ExecutionStep.Key(
                                                StageType.VALIDATE, 0, "revalidate_" + requestId),
                                        null,
                                        "{\"candidateId\":\""
                                                + candidateId
                                                + "\",\"snapshotId\":\""
                                                + snapshot
                                                + "\"}",
                                        java.util.List.of());
                        engine.revalidateCandidate(
                                scope, attempt.result().runId(), requestId, next);
                    }
                    return attempt.result();
                });
    }
}
