package com.lawrence.travel.infrastructure.execution;

import com.lawrence.travel.application.execution.StageExecutor;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.ExecutionResources;
import com.lawrence.travel.application.port.RunHandles;
import com.lawrence.travel.domain.execution.StageType;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 轮询并领取聊天、资料解析和旅行阶段任务，安排后台执行。
 */
public final class PersistentWorkDispatcher implements RunHandles, AutoCloseable {
    private final ExecutionEngineStore executionEngineStore;
    private final StageExecutor stageExecutor;
    private final ExecutionResources executionResources;
    private final int maxConcurrentResearchPerRun;
    private final String workerInstanceId =
            "worker_" + UUID.randomUUID().toString().replace("-", "");
    private final Map<UUID, RunExecutionGroup> executionGroupsByRunId = new HashMap<>();
    private final ScheduledExecutorService dispatchScheduler =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("stage-poll").factory());
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newScheduledThreadPool(
                    2, Thread.ofPlatform().daemon().name("stage-heartbeat-", 0).factory());
    private final Duration heartbeatInterval;
    private volatile java.util.function.BooleanSupplier readinessCheck = () -> true;

    /**
     * @param check 恢复最新删除清单后才允许领取，不授予额外执行权
     */
    public synchronized void setReadinessCheck(java.util.function.BooleanSupplier check) {
        this.readinessCheck = java.util.Objects.requireNonNull(check);
    }

    private boolean closed;
    private boolean started;
    private final java.util.List<com.lawrence.travel.application.port.BackgroundWorkSource>
            backgroundWorkSources = new java.util.ArrayList<>();
    private boolean travelStagesFirst;
    private int nextWorkSourceIndex;
    private final Map<Thread, BackgroundWorkOwner> backgroundWorkOwners = new HashMap<>();

    /**
     * @param user 原归属可空
     * @param parser 独立解析容量
     */
    private record BackgroundWorkOwner(UUID user, boolean parser) {}

    private final Set<Thread> backgroundWorkThreads = new HashSet<>();
    private final java.util.concurrent.Semaphore documentParsingSlots =
            new java.util.concurrent.Semaphore(1);
    private volatile java.util.function.BiConsumer<String, String> problemReporter =
            (component, code) -> {};

    /**
     * @param reporter 由装配层提供的固定分类问题端口；不传原始异常
     */
    public synchronized void setProblemReporter(
            java.util.function.BiConsumer<String, String> reporter) {
        this.problemReporter = java.util.Objects.requireNonNull(reporter);
    }

    /**
     * @param component 固定组件
     * @param code 固定分类；诊断库故障不能覆盖原执行状态
     */
    private void report(String component, String code) {
        try {
            problemReporter.accept(component, code);
        } catch (RuntimeException unavailable) {
            System.getLogger(getClass().getName())
                    .log(System.Logger.Level.WARNING, "PROBLEM_STORE_UNAVAILABLE");
        }
    }

    /**
     * @param documentParsing 独立解析物理槽
     * @return 只有实际退出才释放的资源范围
     */
    private ExecutionResources.RunScope openBackgroundWorkSlot(boolean documentParsing) {
        if (!documentParsing) return executionResources.openRun();
        if (!documentParsingSlots.tryAcquire())
            throw new RejectedExecutionException("PARSER_CAPACITY_FULL");
        return new ExecutionResources.RunScope() {
            private final java.util.concurrent.atomic.AtomicBoolean closed =
                    new java.util.concurrent.atomic.AtomicBoolean();

            /** 解析工作不能借此额外派发调查。 */
            @Override
            public <T> java.util.concurrent.CompletionStage<T> forkResearch(
                    java.util.function.Supplier<T> work) {
                throw new RejectedExecutionException("PARSER_CANNOT_FORK");
            }

            /** 仅由实际退出路径幂等归还。 */
            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) documentParsingSlots.release();
            }
        };
    }

    /**
     * @param source 由bootstrap装配的应用收件端口，共用当前轮询和物理运行容量
     */
    public synchronized void registerBackgroundWorkSource(
            com.lawrence.travel.application.port.BackgroundWorkSource source) {
        backgroundWorkSources.add(java.util.Objects.requireNonNull(source));
    }

    /**
     * @param executionEngineStore 唯一持久调度
     * @param stageExecutor 应用阶段处理
     * @param executionResources 物理容量
     * @param maxConcurrentResearchPerRun 每运行调查并发
     * @param heartbeatInterval 心跳正周期
     */
    public PersistentWorkDispatcher(
            ExecutionEngineStore executionEngineStore,
            StageExecutor stageExecutor,
            ExecutionResources executionResources,
            int maxConcurrentResearchPerRun,
            Duration heartbeatInterval) {
        if (maxConcurrentResearchPerRun < 1
                || heartbeatInterval == null
                || heartbeatInterval.toMillis() < 1)
            throw new IllegalArgumentException("WORKER_LIMIT_INVALID");
        this.executionEngineStore = executionEngineStore;
        this.stageExecutor = stageExecutor;
        this.executionResources = executionResources;
        this.maxConcurrentResearchPerRun = maxConcurrentResearchPerRun;
        this.heartbeatInterval = heartbeatInterval;
    }

    /**
     * 启动到期发现与恢复；没有业务处理器时不领取阶段，更不会隐式调用模型。
     */
    public synchronized void start() {
        if (closed || started) return;
        started = true;
        dispatchScheduler.scheduleWithFixedDelay(
                this::dispatchAvailableWorkSafely, 0, 500, TimeUnit.MILLISECONDS);
    }

    /**
     * 单次受控发现，供本地确定性测试；同一 Worker 的派发串行执行。
     */
    public synchronized void dispatchAvailableWork() {
        if (closed || !readinessCheck.getAsBoolean()) return;
        travelStagesFirst = !travelStagesFirst;
        if (travelStagesFirst) {
            dispatchTravelStages();
            dispatchBackgroundJobs();
        } else {
            dispatchBackgroundJobs();
            dispatchTravelStages();
        }
    }

    /**
     * 轮流领取已注册工作源的任务，启动线程并安排续租。
     */
    private void dispatchBackgroundJobs() {
        if (backgroundWorkSources.isEmpty()) return;
        int start = nextWorkSourceIndex;
        nextWorkSourceIndex = (nextWorkSourceIndex + 1) % backgroundWorkSources.size();
        for (int position = 0; position < backgroundWorkSources.size(); position++) {
            var source =
                    backgroundWorkSources.get((start + position) % backgroundWorkSources.size());
            ExecutionResources.RunScope slot;
            try {
                slot = openBackgroundWorkSlot(source.parserCapacity());
            } catch (RejectedExecutionException full) {
                continue;
            }
            java.util.Optional<Runnable> next;
            try {
                next =
                        source.claimWorkExcluding(
                                getUsersHoldingExecutionSlots(source.parserCapacity()));
            } catch (RuntimeException failure) {
                slot.close();
                throw failure;
            }
            if (next.isEmpty()) {
                slot.close();
                continue;
            }
            var thread =
                    Thread.ofVirtual()
                            .name("dialogue-understand")
                            .unstarted(
                                    () -> {
                                        Thread running = Thread.currentThread();
                                        ScheduledFuture<?> pulse = null;
                                        try {
                                            pulse =
                                                    next.get()
                                                                    instanceof
                                                                    com.lawrence.travel.application
                                                                                            .port
                                                                                            .BackgroundWorkSource
                                                                                            .LeasedWork
                                                                                    leased
                                                            ? heartbeatScheduler
                                                                    .scheduleWithFixedDelay(
                                                                            () -> {
                                                                                try {
                                                                                    if (!leased
                                                                                            .renew())
                                                                                        running
                                                                                                .interrupt();
                                                                                } catch (
                                                                                        RuntimeException
                                                                                                failure) {
                                                                                    report(
                                                                                            "JOB",
                                                                                            "HEARTBEAT_FAILED");
                                                                                    running
                                                                                            .interrupt();
                                                                                }
                                                                            },
                                                                            heartbeatInterval
                                                                                    .toMillis(),
                                                                            heartbeatInterval
                                                                                    .toMillis(),
                                                                            TimeUnit.MILLISECONDS)
                                                            : null;
                                            next.get().run();
                                        } finally {
                                            if (pulse != null) pulse.cancel(false);
                                            onBackgroundWorkExited(running, slot);
                                        }
                                    });
            thread.setUncaughtExceptionHandler(
                    (ignored, failure) -> {
                        report("JOB", "WORKER_EXECUTION_FAILED");
                    });
            var userId =
                    next.get()
                                    instanceof
                                    com.lawrence.travel.application.port.BackgroundWorkSource
                                                            .LeasedWork
                                                    work
                            ? work.userId()
                            : null;
            backgroundWorkOwners.put(
                    thread, new BackgroundWorkOwner(userId, source.parserCapacity()));
            backgroundWorkThreads.add(thread);
            thread.start();
        }
    }

    /** 持久用户轮转顺序不改变阶段依赖或提交围栏；同用户实际退出后才可切换下一运行。 */
    private void dispatchTravelStages() {
        executionEngineStore.recover(32);
        for (var hint : executionEngineStore.candidates(32)) {
            if (!stageExecutor.supports(hint.type())) continue;
            RunExecutionGroup executionGroup = executionGroupsByRunId.get(hint.runId());
            if (executionGroup == null) {
                if (getUsersHoldingExecutionSlots(false).contains(hint.scope().userId())) continue;
                try {
                    executionGroup =
                            new RunExecutionGroup(
                                    executionResources.openRun(), hint.scope().userId());
                    executionGroupsByRunId.put(hint.runId(), executionGroup);
                } catch (RejectedExecutionException full) {
                    continue;
                }
            }
            boolean research = hint.type() == StageType.RESEARCH;
            if (!executionGroup.activeStageExecutions.isEmpty()
                    && (!research
                            || executionGroup.activeNonResearchCount > 0
                            || executionGroup.activeResearchCount >= maxConcurrentResearchPerRun))
                continue;
            java.util.Optional<ExecutionEngineStore.Claim> claim;
            try {
                claim = executionEngineStore.claim(hint, workerInstanceId);
            } catch (RuntimeException failed) {
                releaseRunSlotIfIdle(hint.runId(), executionGroup);
                throw failed;
            }
            if (claim.isEmpty()) {
                releaseRunSlotIfIdle(hint.runId(), executionGroup);
                continue;
            }
            RunExecutionGroup selectedExecutionGroup = executionGroup;
            StageExecutionHandle stageExecution = new StageExecutionHandle(claim.get(), research);
            executionGroup.activeStageExecutions.add(stageExecution);
            if (research) executionGroup.activeResearchCount++;
            else executionGroup.activeNonResearchCount++;
            stageExecution.thread =
                    Thread.ofVirtual()
                            .name("stage-" + claim.get().step().id())
                            .unstarted(
                                    () -> {
                                        try {
                                            var ids =
                                                    executionEngineStore.correlation(
                                                            stageExecution.claim);
                                            if (ids == null)
                                                stageExecutor.execute(
                                                        stageExecution.claim, stageExecution);
                                            else
                                                try (var correlated =
                                                                com.lawrence.travel.application
                                                                        .execution
                                                                        .ExecutionCorrelation.bind(
                                                                        ids);
                                                        var span =
                                                                com.lawrence.travel.application
                                                                        .execution
                                                                        .ExecutionCorrelation
                                                                        .bindSpan(
                                                                                stageExecution
                                                                                        .claim
                                                                                        .fence()
                                                                                        .attemptId())) {
                                                    stageExecutor.execute(
                                                            stageExecution.claim, stageExecution);
                                                }
                                        } finally {
                                            onStageExecutionExited(
                                                    hint.runId(),
                                                    selectedExecutionGroup,
                                                    stageExecution);
                                        }
                                    });
            stageExecution.thread.setUncaughtExceptionHandler(
                    (thread, failure) -> {
                        report("STAGE", "WORKER_EXECUTION_FAILED");
                    });
            stageExecution.heartbeat =
                    heartbeatScheduler.scheduleWithFixedDelay(
                            () -> renewStageLeaseOrRequestCancellation(stageExecution),
                            heartbeatInterval.toMillis(),
                            heartbeatInterval.toMillis(),
                            TimeUnit.MILLISECONDS);
            stageExecution.thread.start();
        }
    }

    /**
     * @param documentParsing 独立解析类别
     * @return 仍实际持槽的用户，包含已取消但网络/处理器尚未退出者
     */
    private Set<UUID> getUsersHoldingExecutionSlots(boolean documentParsing) {
        var users = new HashSet<UUID>();
        if (!documentParsing)
            for (var executionGroup : executionGroupsByRunId.values())
                users.add(executionGroup.userId);
        for (var owner : backgroundWorkOwners.values())
            if (owner.parser() == documentParsing && owner.user() != null) users.add(owner.user());
        return users;
    }

    /**
     * 定时器异常仅延迟发现，不写原始异常日志；数据库请求和租约仍是权威。
     */
    private void dispatchAvailableWorkSafely() {
        try {
            dispatchAvailableWork();
        } catch (RuntimeException failure) {
            report("WORKER", "DISCOVERY_FAILED");
        }
    }

    /**
     * @param thread 已实际退出的对话线程
     * @param slot 仅退出后释放物理容量
     */
    private synchronized void onBackgroundWorkExited(
            Thread thread, ExecutionResources.RunScope slot) {
        backgroundWorkThreads.remove(thread);
        backgroundWorkOwners.remove(thread);
        slot.close();
    }

    /**
     * @param stageExecution 执行句柄；心跳失败尽力中断，不能延长未知租约
     */
    private void renewStageLeaseOrRequestCancellation(StageExecutionHandle stageExecution) {
        try {
            if (!executionEngineStore.renew(stageExecution.claim.fence()))
                stageExecution.requestCancel();
        } catch (RuntimeException unavailable) {
            stageExecution.requestCancel();
        }
    }

    /**
     * @param runId 运行
     * @param executionGroup 物理容量
     * @param stageExecution 真实退出尝试；不使用 Future.cancel 的提前完成语义
     */
    private synchronized void onStageExecutionExited(
            UUID runId, RunExecutionGroup executionGroup, StageExecutionHandle stageExecution) {
        if (stageExecution.heartbeat != null) stageExecution.heartbeat.cancel(false);
        executionGroup.activeStageExecutions.remove(stageExecution);
        if (stageExecution.research) executionGroup.activeResearchCount--;
        else executionGroup.activeNonResearchCount--;
        releaseRunSlotIfIdle(runId, executionGroup);
    }

    /**
     * @param runId 运行
     * @param executionGroup 当前组；所有实际执行退出后才释放物理容量
     */
    private void releaseRunSlotIfIdle(UUID runId, RunExecutionGroup executionGroup) {
        if (executionGroup.activeStageExecutions.isEmpty()) {
            executionGroupsByRunId.remove(runId, executionGroup);
            executionGroup.runSlot.close();
        }
    }

    /**
     * {@inheritDoc} 不清空组、不提前关闭请求槽；下一轮会按数据库权威状态跳过旧执行。
     */
    @Override
    public synchronized void cancel(UUID runId) {
        RunExecutionGroup executionGroup = executionGroupsByRunId.get(runId);
        if (executionGroup != null)
            for (StageExecutionHandle stageExecution : executionGroup.activeStageExecutions)
                stageExecution.requestCancel();
    }

    /**
     * {@inheritDoc} 只匹配同用户同任务，不能中断另一个用户的工作。
     */
    @Override
    public synchronized void supersede(
            com.lawrence.travel.domain.travel.TaskScope scope, long currentGeneration) {
        for (var entry : executionGroupsByRunId.entrySet()) {
            for (StageExecutionHandle stageExecution : entry.getValue().activeStageExecutions)
                if (stageExecution.claim.fence().scope().equals(scope)
                        && stageExecution.claim.fence().generation() < currentGeneration)
                    stageExecution.requestCancel();
        }
    }

    /**
     * 停止领取和心跳，最多等待五秒让实际执行及数据库回调退出；超时仍由租约恢复。
     * 等待期间不持监视器，否则执行线程无法进入 finally 释放物理容量。
     */
    @Override
    public void close() {
        java.util.List<Thread> running;
        synchronized (this) {
            if (closed) return;
            closed = true;
            dispatchScheduler.shutdown();
            heartbeatScheduler.shutdown();
            for (RunExecutionGroup executionGroup : executionGroupsByRunId.values())
                for (StageExecutionHandle stageExecution : executionGroup.activeStageExecutions)
                    stageExecution.requestCancel();
            running =
                    executionGroupsByRunId.values().stream()
                            .flatMap(
                                    executionGroup -> executionGroup.activeStageExecutions.stream())
                            .map(stageExecution -> stageExecution.thread)
                            .toList();
            backgroundWorkThreads.forEach(Thread::interrupt);
            var all = new java.util.ArrayList<>(running);
            all.addAll(backgroundWorkThreads);
            running = all;
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        try {
            for (Thread thread : running) {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0 && thread != Thread.currentThread())
                    thread.join(Duration.ofNanos(remaining));
            }
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) heartbeatScheduler.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 每运行物理执行资源，仅在 Worker 监视器内修改。
     */
    private static final class RunExecutionGroup {
        private final UUID userId;
        private final ExecutionResources.RunScope runSlot;
        private final Set<StageExecutionHandle> activeStageExecutions = new HashSet<>();
        private int activeResearchCount;
        private int activeNonResearchCount;

        /**
         * @param scope 共享物理运行容量
         */
        private RunExecutionGroup(ExecutionResources.RunScope scope, UUID user) {
            this.runSlot = scope;
            this.userId = user;
        }
    }

    /**
     * 每尝试进程句柄，不能作为提交资格。
     */
    private static final class StageExecutionHandle implements StageExecutor.Control {
        private final ExecutionEngineStore.Claim claim;
        private final boolean research;
        private Thread thread;
        private ScheduledFuture<?> heartbeat;
        private boolean cancelRequested;
        private boolean inHandler;

        /**
         * @param claim 已领取尝试
         * @param research 是否计入调查物理容量
         */
        private StageExecutionHandle(ExecutionEngineStore.Claim claim, boolean research) {
            this.claim = claim;
            this.research = research;
        }

        /** 在同一监视器内标记可中断区，取消先发生则不进入处理器。 */
        @Override
        public synchronized boolean enter() {
            if (cancelRequested) return false;
            inHandler = true;
            return true;
        }

        /** 退出处理器后不再允许取消信号打断 JDBC；清除已处理的线程中断状态。 */
        @Override
        public synchronized void leave() {
            inHandler = false;
            Thread.interrupted();
        }

        /**
         * @return 是否已登记尽力停止
         */
        @Override
        public synchronized boolean cancelled() {
            return cancelRequested;
        }

        /** 只在业务处理器内发送中断；事务期的取消依靠数据库代次及租约拒绝提交。 */
        private synchronized void requestCancel() {
            cancelRequested = true;
            if (inHandler) thread.interrupt();
        }
    }
}
