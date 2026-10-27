package com.lawrence.travel.application.execution;

import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.StageHandler;
import com.lawrence.travel.domain.execution.StageType;
import com.lawrence.travel.domain.execution.control.CallRejectedException;
import com.lawrence.travel.domain.rule.DomainViolation;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import java.util.List;

/**
 * 执行对应阶段处理器，再保存产物并提交阶段结果。
 */
public final class StageExecutor {
    /** 框架处理器中断窗口；数据库阶段不能使用线程中断取消。 */
    public interface Control {
        /**
         * @return 是否允许进入可中断业务阶段，原子关闭与取消登记竞争窗口
         */
        boolean enter();

        /** 退出可中断区域，并清除处理器遗留中断，避免 JDBC 因标志关闭连接。 */
        void leave();

        /**
         * @return 是否已请求停止，停止后不再保存新的阶段结果
         */
        boolean cancelled();
    }

    private final ExecutionEngineStore store;
    private final List<StageHandler> handlers;
    private final com.lawrence.travel.application.port.ExecutionTrace trace;

    /**
     * @param store 权威状态
     * @param handlers 明确注册的处理器；不得默认替换为真实模型
     */
    public StageExecutor(ExecutionEngineStore store, List<StageHandler> handlers) {
        this(store, handlers, com.lawrence.travel.application.port.ExecutionTrace.disabled());
    }

    /**
     * @param store 权威引擎
     * @param handlers 固定处理器
     * @param trace 无正文执行轨迹
     */
    public StageExecutor(
            ExecutionEngineStore store,
            List<StageHandler> handlers,
            com.lawrence.travel.application.port.ExecutionTrace trace) {
        this.store = store;
        this.handlers = List.copyOf(handlers);
        this.trace = trace;
    }

    /**
     * @param type 阶段
     * @return 恰好一个处理器支持；未配置或冲突均不领取
     */
    public boolean supports(StageType type) {
        return handlers.stream().filter(h -> h.supports(type)).count() == 1;
    }

    /**
     * 执行一次已领取阶段并尝试提交，其预算、需求版本、generation与attempt围栏均来自原Claim。
     *
     * @param claim 已领取执行；中断后只尽力停止，业务资格以数据库判断
     * @param control 仅框架／业务处理阶段可中断，数据库操作始终由事务和 fence 判定
     */
    public void execute(ExecutionEngineStore.Claim claim, Control control) {
        try {
            if (control.cancelled()) return;
            if (!store.renew(claim.fence())) return;
            var matches =
                    handlers.stream().filter(h -> h.supports(claim.step().key().type())).toList();
            if (matches.size() != 1) {
                store.fail(claim.fence(), ExecutionEngineStore.Failure.PERMANENT);
                return;
            }
            var type = claim.step().key().type();
            var kind =
                    switch (type) {
                        case PLAN -> com.lawrence.travel.application.port.ExecutionTrace.Kind.PLAN;
                        case REVIEW ->
                                com.lawrence.travel.application.port.ExecutionTrace.Kind.REVIEW;
                        case VALIDATE ->
                                com.lawrence.travel.application.port.ExecutionTrace.Kind.VALIDATE;
                        case RESEARCH ->
                                claim.step().key().branchKey().startsWith("delegate_")
                                        ? com.lawrence.travel.application.port.ExecutionTrace.Kind
                                                .DELEGATE
                                        : com.lawrence.travel.application.port.ExecutionTrace.Kind
                                                .COORDINATE;
                        default -> com.lawrence.travel.application.port.ExecutionTrace.Kind.CONTEXT;
                    };
            StageOutput output;
            try (var span =
                    trace.open(
                            claim.fence().runId(),
                            kind,
                            java.util.Map.of(
                                    "stage",
                                    claim.fence().stepId().toString(),
                                    "generation",
                                    Long.toString(claim.fence().generation()),
                                    "revision",
                                    Long.toString(claim.fence().constraintRevision()),
                                    "schema",
                                    "stage-output-v1",
                                    "sourceHash",
                                    com.lawrence.travel.application.memory.AgentContext.hash(
                                            claim.input())))) {
                if (!control.enter()) {
                    span.fail(
                            com.lawrence.travel.application.port.ExecutionTrace.Failure.CANCELLED);
                    return;
                }
                try {
                    output = matches.getFirst().execute(claim);
                } catch (Exception failure) {
                    span.fail(
                            failure instanceof CallRejectedException
                                    ? com.lawrence.travel.application.port.ExecutionTrace.Failure
                                            .REJECTED
                                    : Thread.currentThread().isInterrupted()
                                            ? com.lawrence.travel.application.port.ExecutionTrace
                                                    .Failure.CANCELLED
                                            : com.lawrence.travel.application.port.ExecutionTrace
                                                    .Failure.FAILED);
                    throw failure;
                } finally {
                    control.leave();
                }
                if (control.cancelled()) {
                    span.fail(
                            com.lawrence.travel.application.port.ExecutionTrace.Failure.CANCELLED);
                    return;
                }
            }
            com.lawrence.travel.application.port.StageCommitPort.Result committed;
            try (var span =
                    trace.open(
                            claim.fence().runId(),
                            com.lawrence.travel.application.port.ExecutionTrace.Kind.COMMIT,
                            java.util.Map.of("stage", claim.fence().stepId().toString()))) {
                try {
                    committed = store.commit(store.prepare(claim, output));
                    if (committed
                            == com.lawrence.travel.application.port.StageCommitPort.Result.STALE)
                        span.fail(
                                com.lawrence.travel.application.port.ExecutionTrace.Failure
                                        .CANCELLED);
                } catch (RuntimeException failure) {
                    span.fail(com.lawrence.travel.application.port.ExecutionTrace.Failure.FAILED);
                    throw failure;
                }
            }
            if (committed == com.lawrence.travel.application.port.StageCommitPort.Result.COMMITTED
                    && output.outcome() == StageOutput.Outcome.WAIT_USER)
                try (var wait =
                        trace.open(
                                claim.fence().runId(),
                                com.lawrence.travel.application.port.ExecutionTrace.Kind.WAIT,
                                java.util.Map.of("stage", claim.fence().stepId().toString()))) {}
        } catch (CallRejectedException rejected) {
            if (control.cancelled()) return;
            var failure =
                    switch (rejected.reason()) {
                        case CALL_BUDGET_EXHAUSTED,
                                        ACTIVE_TIME_EXHAUSTED,
                                        CONTEXT_BUDGET_EXHAUSTED ->
                                ExecutionEngineStore.Failure.BUDGET;
                        case MODEL_CONCURRENCY_FULL -> ExecutionEngineStore.Failure.RETRYABLE;
                        default -> ExecutionEngineStore.Failure.QUOTA;
                    };
            store.fail(claim.fence(), failure);
        } catch (TravelStorageFailure rejected) {
            if (control.cancelled()) return;
            store.fail(
                    claim.fence(),
                    rejected.code() == TravelStorageFailure.Code.DEPENDENCY_UNAVAILABLE
                            ? ExecutionEngineStore.Failure.RETRYABLE
                            : ExecutionEngineStore.Failure.PERMANENT);
        } catch (DomainViolation | IllegalArgumentException invalid) {
            if (control.cancelled()) return;
            store.fail(claim.fence(), ExecutionEngineStore.Failure.PERMANENT);
        } catch (Exception failure) {
            if (control.cancelled()) return;
            // 不输出异常内容，处理器可能持有提示词、供应商响应或认证信息。
            store.fail(claim.fence(), ExecutionEngineStore.Failure.RETRYABLE);
        }
    }
}
