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
public final class PersistentStageWorker implements RunHandles, AutoCloseable {
    private final ExecutionEngineStore store;
    private final StageExecutor executor;
    private final ExecutionResources resources;
    private final int researchLimit;
    private final String owner = "worker_" + UUID.randomUUID().toString().replace("-", "");
    private final Map<UUID, Group> groups = new HashMap<>();
    private final ScheduledExecutorService poller =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("stage-poll").factory());
    private final ScheduledExecutorService heartbeats =
            Executors.newScheduledThreadPool(
                    2, Thread.ofPlatform().daemon().name("stage-heartbeat-", 0).factory());
    private final Duration heartbeat;
    private volatile java.util.function.BooleanSupplier readiness = () -> true;

    /**
     * @param check 恢复最新删除清单后才允许领取，不授予额外执行权
     */
    public synchronized void readiness(java.util.function.BooleanSupplier check) {
        this.readiness = java.util.Objects.requireNonNull(check);
    }

    private boolean closed;
    private boolean started;
    private final java.util.List<com.lawrence.travel.application.port.BackgroundWorkSource>
            auxiliary = new java.util.ArrayList<>();
    private boolean stagesFirst;
    private int sourceCursor;
    private final Map<Thread, AuxOwner> auxiliaryOwners = new HashMap<>();

    /**
     * @param user 原归属可空
     * @param parser 独立解析容量
     */
    private record AuxOwner(UUID user, boolean parser) {}

    private final Set<Thread> auxiliaryThreads = new HashSet<>();
    private final java.util.concurrent.Semaphore parserSlots =
            new java.util.concurrent.Semaphore(1);
    private volatile java.util.function.BiConsumer<String, String> problems =
            (component, code) -> {};

    /**
     * @param reporter 由装配层提供的固定分类问题端口；不传原始异常
     */
    public synchronized void problems(java.util.function.BiConsumer<String, String> reporter) {
        this.problems = java.util.Objects.requireNonNull(reporter);
    }

    /**
     * @param component 固定组件
     * @param code 固定分类；诊断库故障不能覆盖原执行状态
     */
    private void report(String component, String code) {
        try {
            problems.accept(component, code);
        } catch (RuntimeException unavailable) {
            System.getLogger(getClass().getName())
                    .log(System.Logger.Level.WARNING, "PROBLEM_STORE_UNAVAILABLE");
        }
    }

    /**
     * @param parser 独立解析物理槽
     * @return 只有实际退出才释放的资源范围
     */
    private ExecutionResources.RunScope openAuxiliary(boolean parser) {
        if (!parser) return resources.openRun();
        if (!parserSlots.tryAcquire()) throw new RejectedExecutionException("PARSER_CAPACITY_FULL");
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
                if (closed.compareAndSet(false, true)) parserSlots.release();
            }
        };
    }

    /**
     * @param source 由bootstrap装配的应用收件端口，共用当前轮询和物理运行容量
     */
    public synchronized void register(
            com.lawrence.travel.application.port.BackgroundWorkSource source) {
        auxiliary.add(java.util.Objects.requireNonNull(source));
    }

    /**
     * @param store 唯一持久调度
     * @param executor 应用阶段处理
     * @param resources 物理容量
     * @param researchLimit 每运行调查并发
     * @param heartbeat 心跳正周期
     */
    public PersistentStageWorker(
            ExecutionEngineStore store,
            StageExecutor executor,
            ExecutionResources resources,
            int researchLimit,
            Duration heartbeat) {
        if (researchLimit < 1 || heartbeat == null || heartbeat.toMillis() < 1)
            throw new IllegalArgumentException("WORKER_LIMIT_INVALID");
        this.store = store;
        this.executor = executor;
        this.resources = resources;
        this.researchLimit = researchLimit;
        this.heartbeat = heartbeat;
    }

    /**
     * 启动到期发现与恢复；没有业务处理器时不领取阶段，更不会隐式调用模型。
     */
    public synchronized void start() {
        if (closed || started) return;
        started = true;
        poller.scheduleWithFixedDelay(this::safeTick, 0, 500, TimeUnit.MILLISECONDS);
    }

    /**
     * 单次受控发现，供本地确定性测试；同一 Worker 的派发串行执行。
     */
    public synchronized void tick() {
        if (closed || !readiness.getAsBoolean()) return;
        stagesFirst = !stagesFirst;
        if (stagesFirst) {
            dispatchStages();
            dispatchAuxiliary();
        } else {
            dispatchAuxiliary();
            dispatchStages();
        }
    }

    /**
     * 轮流领取已注册工作源的任务，启动线程并安排续租。
     */
    private void dispatchAuxiliary() {
        if (auxiliary.isEmpty()) return;
        int start = sourceCursor;
        sourceCursor = (sourceCursor + 1) % auxiliary.size();
        for (int position = 0; position < auxiliary.size(); position++) {
            var source = auxiliary.get((start + position) % auxiliary.size());
            ExecutionResources.RunScope slot;
            try {
                slot = openAuxiliary(source.parserCapacity());
            } catch (RejectedExecutionException full) {
                continue;
            }
            java.util.Optional<Runnable> next;
            try {
                next = source.claimWorkExcluding(busyUsers(source.parserCapacity()));
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
                                                            ? heartbeats.scheduleWithFixedDelay(
                                                                    () -> {
                                                                        try {
                                                                            if (!leased.renew())
                                                                                running.interrupt();
                                                                        } catch (
                                                                                RuntimeException
                                                                                        failure) {
                                                                            report(
                                                                                    "JOB",
                                                                                    "HEARTBEAT_FAILED");
                                                                            running.interrupt();
                                                                        }
                                                                    },
                                                                    heartbeat.toMillis(),
                                                                    heartbeat.toMillis(),
                                                                    TimeUnit.MILLISECONDS)
                                                            : null;
                                            next.get().run();
                                        } finally {
                                            if (pulse != null) pulse.cancel(false);
                                            auxiliaryExited(running, slot);
                                        }
                                    });
            thread.setUncaughtExceptionHandler(
                    (ignored, failure) -> {
                        report("JOB", "WORKER_EXECUTION_FAILED");
                    });
            var user =
                    next.get()
                                    instanceof
                                    com.lawrence.travel.application.port.BackgroundWorkSource
                                                            .LeasedWork
                                                    work
                            ? work.userId()
                            : null;
            auxiliaryOwners.put(thread, new AuxOwner(user, source.parserCapacity()));
            auxiliaryThreads.add(thread);
            thread.start();
        }
    }

    /** 持久用户轮转顺序不改变阶段依赖或提交围栏；同用户实际退出后才可切换下一运行。 */
    private void dispatchStages() {
        store.recover(32);
        for (var hint : store.candidates(32)) {
            if (!executor.supports(hint.type())) continue;
            Group group = groups.get(hint.runId());
            if (group == null) {
                if (busyUsers(false).contains(hint.scope().userId())) continue;
                try {
                    group = new Group(resources.openRun(), hint.scope().userId());
                    groups.put(hint.runId(), group);
                } catch (RejectedExecutionException full) {
                    continue;
                }
            }
            boolean research = hint.type() == StageType.RESEARCH;
            if (!group.work.isEmpty()
                    && (!research || group.nonResearch > 0 || group.research >= researchLimit))
                continue;
            java.util.Optional<ExecutionEngineStore.Claim> claim;
            try {
                claim = store.claim(hint, owner);
            } catch (RuntimeException failed) {
                releaseIfEmpty(hint.runId(), group);
                throw failed;
            }
            if (claim.isEmpty()) {
                releaseIfEmpty(hint.runId(), group);
                continue;
            }
            Group selected = group;
            Work work = new Work(claim.get(), research);
            group.work.add(work);
            if (research) group.research++;
            else group.nonResearch++;
            work.thread =
                    Thread.ofVirtual()
                            .name("stage-" + claim.get().step().id())
                            .unstarted(
                                    () -> {
                                        try {
                                            var ids = store.correlation(work.claim);
                                            if (ids == null) executor.execute(work.claim, work);
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
                                                                                work.claim
                                                                                        .fence()
                                                                                        .attemptId())) {
                                                    executor.execute(work.claim, work);
                                                }
                                        } finally {
                                            exited(hint.runId(), selected, work);
                                        }
                                    });
            work.thread.setUncaughtExceptionHandler(
                    (thread, failure) -> {
                        report("STAGE", "WORKER_EXECUTION_FAILED");
                    });
            work.heartbeat =
                    heartbeats.scheduleWithFixedDelay(
                            () -> pulse(work),
                            heartbeat.toMillis(),
                            heartbeat.toMillis(),
                            TimeUnit.MILLISECONDS);
            work.thread.start();
        }
    }

    /**
     * @param parser 独立解析类别
     * @return 仍实际持槽的用户，包含已取消但网络/处理器尚未退出者
     */
    private Set<UUID> busyUsers(boolean parser) {
        var users = new HashSet<UUID>();
        if (!parser) for (var group : groups.values()) users.add(group.user);
        for (var owner : auxiliaryOwners.values())
            if (owner.parser() == parser && owner.user() != null) users.add(owner.user());
        return users;
    }

    /**
     * 定时器异常仅延迟发现，不写原始异常日志；数据库请求和租约仍是权威。
     */
    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException failure) {
            report("WORKER", "DISCOVERY_FAILED");
        }
    }

    /**
     * @param thread 已实际退出的对话线程
     * @param slot 仅退出后释放物理容量
     */
    private synchronized void auxiliaryExited(Thread thread, ExecutionResources.RunScope slot) {
        auxiliaryThreads.remove(thread);
        auxiliaryOwners.remove(thread);
        slot.close();
    }

    /**
     * @param work 执行句柄；心跳失败尽力中断，不能延长未知租约
     */
    private void pulse(Work work) {
        try {
            if (!store.renew(work.claim.fence())) work.requestCancel();
        } catch (RuntimeException unavailable) {
            work.requestCancel();
        }
    }

    /**
     * @param run 运行
     * @param group 物理容量
     * @param work 真实退出尝试；不使用 Future.cancel 的提前完成语义
     */
    private synchronized void exited(UUID run, Group group, Work work) {
        if (work.heartbeat != null) work.heartbeat.cancel(false);
        group.work.remove(work);
        if (work.research) group.research--;
        else group.nonResearch--;
        releaseIfEmpty(run, group);
    }

    /**
     * @param run 运行
     * @param group 当前组；所有实际执行退出后才释放物理容量
     */
    private void releaseIfEmpty(UUID run, Group group) {
        if (group.work.isEmpty()) {
            groups.remove(run, group);
            group.scope.close();
        }
    }

    /**
     * {@inheritDoc} 不清空组、不提前关闭请求槽；下一轮会按数据库权威状态跳过旧执行。
     */
    @Override
    public synchronized void cancel(UUID runId) {
        Group group = groups.get(runId);
        if (group != null) for (Work work : group.work) work.requestCancel();
    }

    /**
     * {@inheritDoc} 只匹配同用户同任务，不能中断另一个用户的工作。
     */
    @Override
    public synchronized void supersede(
            com.lawrence.travel.domain.travel.TaskScope scope, long currentGeneration) {
        for (var entry : groups.entrySet()) {
            for (Work work : entry.getValue().work)
                if (work.claim.fence().scope().equals(scope)
                        && work.claim.fence().generation() < currentGeneration)
                    work.requestCancel();
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
            poller.shutdown();
            heartbeats.shutdown();
            for (Group group : groups.values()) for (Work work : group.work) work.requestCancel();
            running =
                    groups.values().stream()
                            .flatMap(group -> group.work.stream())
                            .map(work -> work.thread)
                            .toList();
            auxiliaryThreads.forEach(Thread::interrupt);
            var all = new java.util.ArrayList<>(running);
            all.addAll(auxiliaryThreads);
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
            if (remaining > 0) heartbeats.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 每运行物理执行资源，仅在 Worker 监视器内修改。
     */
    private static final class Group {
        private final UUID user;
        private final ExecutionResources.RunScope scope;
        private final Set<Work> work = new HashSet<>();
        private int research;
        private int nonResearch;

        /**
         * @param scope 共享物理运行容量
         */
        private Group(ExecutionResources.RunScope scope, UUID user) {
            this.scope = scope;
            this.user = user;
        }
    }

    /**
     * 每尝试进程句柄，不能作为提交资格。
     */
    private static final class Work implements StageExecutor.Control {
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
        private Work(ExecutionEngineStore.Claim claim, boolean research) {
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
