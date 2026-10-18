package com.lawrence.travel.infrastructure.execution;

import com.lawrence.travel.application.port.ExecutionResources;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/** 有界运行及调查资源；虚拟线程隔离阻塞工作，所有计数均在实例监视器内维护。 */
public final class BoundedExecutionResources implements ExecutionResources, AutoCloseable {
    private final int maximumRuns;
    private final int maximumBranches;
    private final Set<Scope> scopes = new HashSet<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private boolean stopped;

    /**
     * 建立无等待队列的资源限制；容量不足交回未来的持久调度器，不自行重试。
     * @param maximumRuns 活跃运行上限，正数
     * @param maximumBranches 每运行同时调查分支上限，正数
     */
    public BoundedExecutionResources(int maximumRuns, int maximumBranches) {
        if (maximumRuns < 1 || maximumBranches < 1) throw new IllegalArgumentException("RESOURCE_LIMIT_INVALID");
        this.maximumRuns = maximumRuns;
        this.maximumBranches = maximumBranches;
    }

    /** {@inheritDoc} 运行占用与停止检查原子完成。 */
    @Override
    public synchronized RunScope openRun() {
        if (stopped || scopes.size() >= maximumRuns) throw new RejectedExecutionException("RUN_CAPACITY");
        var scope = new Scope();
        scopes.add(scope);
        return scope;
    }

    /** 停止接收并中断工作；运行容量仍等待各分支真正退出，不等待无界网络调用。 */
    @Override
    public synchronized void close() {
        if (stopped) return;
        stopped = true;
        for (var scope : Set.copyOf(scopes)) scope.close();
        executor.shutdownNow();
    }

    /** 单个运行的资源范围，关闭不代表业务取消或数据库状态变化。 */
    private final class Scope implements RunScope {
        private int branches;
        private boolean closing;

        /** 创建尚无调查分支的范围；仅由已检查全局容量的工厂调用。 */
        private Scope() { }

        /** {@inheritDoc} 先预占再派发，派发失败与实际退出均精确归还一次。 */
        @Override
        public <T> CompletionStage<T> forkResearch(Supplier<T> work) {
            Objects.requireNonNull(work);
            var result = new CompletableFuture<T>();
            var correlation = com.lawrence.travel.application.execution.ExecutionCorrelation.current();
            var parentSpan = com.lawrence.travel.application.execution.ExecutionCorrelation.parentSpan();
            synchronized (BoundedExecutionResources.this) {
                if (stopped || closing || branches >= maximumBranches) {
                    throw new RejectedExecutionException("RESEARCH_CAPACITY");
                }
                branches++;
                try {
                    executor.execute(() -> {
                        T value = null;
                        Throwable failure = null;
                        try (var ids = com.lawrence.travel.application.execution.ExecutionCorrelation.bind(correlation);
                             var span = com.lawrence.travel.application.execution.ExecutionCorrelation.bindSpan(parentSpan)) { value = work.get(); }
                        catch (Throwable error) { failure = error; }
                        finally { branchExited(); }
                        // 先释放实际执行资源，再通知父流程；回调可立即开启下一调查分支。
                        if (failure == null) result.complete(value);
                        else result.completeExceptionally(failure);
                    });
                } catch (RejectedExecutionException rejected) {
                    branches--;
                    throw rejected;
                }
            }
            return result.minimalCompletionStage();
        }

        /** 在真实工作退出时归还分支；范围已关闭且最后分支退出才释放运行槽。 */
        private void branchExited() {
            synchronized (BoundedExecutionResources.this) {
                branches--;
                if (closing && branches == 0) scopes.remove(this);
            }
        }

        /** {@inheritDoc} 不因 SSE 断线调用；关闭后不能继续派发。 */
        @Override
        public void close() {
            synchronized (BoundedExecutionResources.this) {
                closing = true;
                if (branches == 0) scopes.remove(this);
            }
        }
    }
}
