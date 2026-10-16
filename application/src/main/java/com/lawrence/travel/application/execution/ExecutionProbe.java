package com.lawrence.travel.application.execution;

import com.lawrence.travel.application.port.ExecutionResources;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

/** 仅供 smoke 诊断的固定离线任务，没有用户数据、模型、工具或持久化恢复能力。 */
public final class ExecutionProbe {
    private final ExecutionResources resources;
    private final AtomicLong completed = new AtomicLong();

    /**
     * 装配诊断任务使用的同一实例资源限制。
     * @param resources 活跃运行与调查资源端口
     */
    public ExecutionProbe(ExecutionResources resources) { this.resources = resources; }

    /**
     * 启动固定离线任务；HTTP 断线不持有其取消句柄。
     * @return 固定完成文本；资源不足时同步拒绝
     */
    public CompletionStage<String> start() {
        var scope = resources.openRun();
        try {
            return scope.forkResearch(() -> {
                try { Thread.sleep(700); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("PROBE_STOPPED");
                }
                completed.incrementAndGet();
                return "OFFLINE_PROBE_COMPLETED";
            }).whenComplete((value, failure) -> scope.close());
        } catch (RuntimeException rejected) {
            scope.close();
            throw rejected;
        }
    }

    /** @return 本进程已执行完毕的固定任务总数，仅供测试，不代表旅行运行状态 */
    public long completedCount() { return completed.get(); }
}
