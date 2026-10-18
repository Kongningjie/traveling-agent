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
 * 单实例短轮询与同一阶段执行器；无第二套框架后台调度。
 * 取消仅中断线程，实际处理器退出前保留物理运行／调查容量，防止迟到请求绕过上限。
 */
public final class PersistentStageWorker implements RunHandles, AutoCloseable {
    private final ExecutionEngineStore store;
    private final StageExecutor executor;
    private final ExecutionResources resources;
    private final int researchLimit;
    private final String owner = "worker_" + UUID.randomUUID().toString().replace("-", "");
    private final Map<UUID, Group> groups = new HashMap<>();
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("stage-poll").factory());
    private final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(2, Thread.ofPlatform().daemon().name("stage-heartbeat-", 0).factory());
    private final Duration heartbeat;
    private boolean closed;
    private boolean started;
    private final java.util.List<com.lawrence.travel.application.port.BackgroundWorkSource> auxiliary = new java.util.ArrayList<>();
    private final Set<Thread> auxiliaryThreads = new HashSet<>();
    /** @param source 由bootstrap装配的应用收件端口，共用当前轮询和物理运行容量 */
    public synchronized void register(com.lawrence.travel.application.port.BackgroundWorkSource source) { auxiliary.add(java.util.Objects.requireNonNull(source)); }

    /**
     * @param store 唯一持久调度
     * @param executor 应用阶段处理
     * @param resources 物理容量
     * @param researchLimit 每运行调查并发
     * @param heartbeat 心跳正周期
     */
    public PersistentStageWorker(ExecutionEngineStore store, StageExecutor executor, ExecutionResources resources, int researchLimit, Duration heartbeat) {
        if (researchLimit < 1 || heartbeat == null || heartbeat.toMillis() < 1) throw new IllegalArgumentException("WORKER_LIMIT_INVALID");
        this.store = store; this.executor = executor; this.resources = resources; this.researchLimit = researchLimit; this.heartbeat = heartbeat;
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
        if (closed) return;
        for (var source : auxiliary) {
            ExecutionResources.RunScope slot;
            try { slot = resources.openRun(); } catch (RejectedExecutionException full) { break; }
            java.util.Optional<Runnable> next;
            try { next = source.claimWork(); } catch (RuntimeException failure) { slot.close(); throw failure; }
            if (next.isEmpty()) { slot.close(); continue; }
            var thread = Thread.ofVirtual().name("dialogue-understand").unstarted(() -> {
                try { next.get().run(); } finally { auxiliaryExited(Thread.currentThread(), slot); }
            });
            thread.setUncaughtExceptionHandler((ignored, failure) -> { /* 固定状态由应用持久化，失联请求按租约恢复。 */ });
            auxiliaryThreads.add(thread); thread.start();
        }
        store.recover(32);
        for (var hint : store.candidates(32)) {
            if (!executor.supports(hint.type())) continue;
            Group group = groups.get(hint.runId());
            if (group == null) {
                try { group = new Group(resources.openRun()); groups.put(hint.runId(), group); }
                catch (RejectedExecutionException full) { continue; }
            }
            boolean research = hint.type() == StageType.RESEARCH;
            if (!group.work.isEmpty() && (!research || group.nonResearch > 0 || group.research >= researchLimit)) continue;
            java.util.Optional<ExecutionEngineStore.Claim> claim;
            try { claim = store.claim(hint, owner); }
            catch (RuntimeException failed) { releaseIfEmpty(hint.runId(), group); throw failed; }
            if (claim.isEmpty()) { releaseIfEmpty(hint.runId(), group); continue; }
            Group selected = group;
            Work work = new Work(claim.get(), research);
            group.work.add(work); if (research) group.research++; else group.nonResearch++;
            work.thread = Thread.ofVirtual().name("stage-" + claim.get().step().id()).unstarted(() -> {
                try { executor.execute(work.claim, work); }
                finally { exited(hint.runId(), selected, work); }
            });
            work.thread.setUncaughtExceptionHandler((thread, failure) -> { /* 原始异常不得泄露；失联阶段由权威租约恢复。 */ });
            work.heartbeat = heartbeats.scheduleWithFixedDelay(() -> pulse(work), heartbeat.toMillis(), heartbeat.toMillis(), TimeUnit.MILLISECONDS);
            work.thread.start();
        }
    }

    /**
     * 定时器异常仅延迟发现，不写原始异常日志；数据库请求和租约仍是权威。
     */
    private void safeTick() { try { tick(); } catch (RuntimeException failure) { /* 下一轮重新检查，不伪造执行成功。 */ } }
    /** @param thread 已实际退出的对话线程 @param slot 仅退出后释放物理容量 */
    private synchronized void auxiliaryExited(Thread thread, ExecutionResources.RunScope slot) { auxiliaryThreads.remove(thread); slot.close(); }
    /**
     * @param work 执行句柄；心跳失败尽力中断，不能延长未知租约
     */
    private void pulse(Work work) { try { if (!store.renew(work.claim.fence())) work.requestCancel(); } catch (RuntimeException unavailable) { work.requestCancel(); } }
    /**
     * @param run 运行
     * @param group 物理容量
     * @param work 真实退出尝试；不使用 Future.cancel 的提前完成语义
     */
    private synchronized void exited(UUID run, Group group, Work work) {
        if (work.heartbeat != null) work.heartbeat.cancel(false);
        group.work.remove(work); if (work.research) group.research--; else group.nonResearch--;
        releaseIfEmpty(run, group);
    }
    /**
     * @param run 运行
     * @param group 当前组；所有实际执行退出后才释放物理容量
     */
    private void releaseIfEmpty(UUID run, Group group) { if (group.work.isEmpty()) { groups.remove(run, group); group.scope.close(); } }
    /**
     * {@inheritDoc} 不清空组、不提前关闭请求槽；下一轮会按数据库权威状态跳过旧执行。
     */
    @Override public synchronized void cancel(UUID runId) { Group group = groups.get(runId); if (group != null) for (Work work : group.work) work.requestCancel(); }
    /**
     * {@inheritDoc} 只匹配同用户同任务，不能中断另一个用户的工作。
     */
    @Override public synchronized void supersede(com.lawrence.travel.domain.travel.TaskScope scope, long currentGeneration) {
        for (var entry : groups.entrySet()) {
            for (Work work : entry.getValue().work) if (work.claim.fence().scope().equals(scope)
                    && work.claim.fence().generation() < currentGeneration) work.requestCancel();
        }
    }
    /**
     * 停止领取和心跳，最多等待五秒让实际执行及数据库回调退出；超时仍由租约恢复。
     * 等待期间不持监视器，否则执行线程无法进入 finally 释放物理容量。
     */
    @Override public void close() {
        java.util.List<Thread> running;
        synchronized (this) {
            if (closed) return; closed = true; poller.shutdown(); heartbeats.shutdown();
            for (Group group : groups.values()) for (Work work : group.work) work.requestCancel();
            running = groups.values().stream().flatMap(group -> group.work.stream()).map(work -> work.thread).toList();
            auxiliaryThreads.forEach(Thread::interrupt);
            var all = new java.util.ArrayList<>(running); all.addAll(auxiliaryThreads); running = all;
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        try {
            for (Thread thread : running) {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0 && thread != Thread.currentThread()) thread.join(Duration.ofNanos(remaining));
            }
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) heartbeats.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    /**
     * 每运行物理执行资源，仅在 Worker 监视器内修改。
     */
    private static final class Group {
        private final ExecutionResources.RunScope scope;
        private final Set<Work> work = new HashSet<>();
        private int research;
        private int nonResearch;
        /**
         * @param scope 共享物理运行容量
         */
        private Group(ExecutionResources.RunScope scope) { this.scope = scope; }
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
        private Work(ExecutionEngineStore.Claim claim, boolean research) { this.claim = claim; this.research = research; }
        /** 在同一监视器内标记可中断区，取消先发生则不进入处理器。 */
        @Override public synchronized boolean enter() { if (cancelRequested) return false; inHandler = true; return true; }
        /** 退出处理器后不再允许取消信号打断 JDBC；清除已处理的线程中断状态。 */
        @Override public synchronized void leave() { inHandler = false; Thread.interrupted(); }
        /** @return 是否已登记尽力停止 */
        @Override public synchronized boolean cancelled() { return cancelRequested; }
        /** 只在业务处理器内发送中断；事务期的取消依靠数据库代次及租约拒绝提交。 */
        private synchronized void requestCancel() { cancelRequested = true; if (inHandler) thread.interrupt(); }
    }
}
