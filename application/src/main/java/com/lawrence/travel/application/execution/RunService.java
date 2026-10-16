package com.lawrence.travel.application.execution;

import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.RunHandles;
import com.lawrence.travel.application.port.TaskChangeObserver;
import com.lawrence.travel.domain.execution.ExecutionRun;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.travel.TaskScope;
import java.util.UUID;

/**
 * 执行用例：服务端身份限定归属，事务提交后才尽力取消旧句柄。
 */
public final class RunService implements TaskChangeObserver {
    private final ExecutionEngineStore store;
    private final BusinessTransactions transactions;
    private final RunHandles handles;
    /**
     * @param store 执行权威
     * @param transactions 短事务
     * @param handles 本地句柄
     */
    public RunService(ExecutionEngineStore store, BusinessTransactions transactions, RunHandles handles) {
        this.store = store; this.transactions = transactions; this.handles = handles;
    }
    /**
     * @param actor 认证身份
     * @param taskId 任务
     * @param inputId 启动原因
     * @param first 首阶段
     * @return 可靠登记后的运行
     */
    public ExecutionRun start(UserIdentity actor, UUID taskId, UUID inputId, StagePlan first) {
        return transactions.required(() -> {
            var scope = new TaskScope(actor.id(), taskId);
            var result = store.start(scope, inputId, first);
            transactions.afterCommit(() -> handles.supersede(scope, result.generation()));
            return result;
        });
    }
    /**
     * @param actor 认证身份
     * @param taskId 任务
     * @param runId 目标运行
     * @return 已提交取消状态
     */
    public ExecutionRun cancel(UserIdentity actor, UUID taskId, UUID runId) {
        return transactions.required(() -> {
            ExecutionRun result = store.cancel(new TaskScope(actor.id(), taskId), runId);
            transactions.afterCommit(() -> handles.cancel(runId));
            return result;
        });
    }
    /**
     * {@inheritDoc} 由需求登记事务调用，不等框架停机才释放数据库锁。
     */
    @Override public void changed(TaskScope scope) {
        for (UUID id : store.supersede(scope)) transactions.afterCommit(() -> handles.cancel(id));
    }
}
