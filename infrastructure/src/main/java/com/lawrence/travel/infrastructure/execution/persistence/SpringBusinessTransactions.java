package com.lawrence.travel.infrastructure.execution.persistence;

import com.lawrence.travel.application.port.BusinessTransactions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 同一 PostgreSQL 业务事务及提交回调适配；不使用隐式新事务绕过调用方锁。
 */
public final class SpringBusinessTransactions implements BusinessTransactions {
    private final TransactionTemplate transaction;
    /**
     * @param manager 同一业务数据源事务
     */
    public SpringBusinessTransactions(PlatformTransactionManager manager) { transaction = new TransactionTemplate(manager); }
    /**
     * {@inheritDoc} 默认 REQUIRED，异常回滚全部已加入的仓储操作。
     */
    @Override public <T> T required(Supplier<T> work) {
        try { return transaction.execute(status -> work.get()); }
        catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException failure) {
            com.lawrence.travel.infrastructure.execution.AdmissionErrors.rethrowKnown(failure);
            throw new com.lawrence.travel.application.travel.TravelStorageFailure(com.lawrence.travel.application.travel.TravelStorageFailure.Code.DEPENDENCY_UNAVAILABLE);
        }
    }
    /**
     * {@inheritDoc} 事务外立即执行；提交后中断异常不改变已提交业务事实。
     */
    @Override public void afterCommit(Runnable work) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { bestEffort(work); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 在提交已成功后执行尽力取消，不记录凭据或底层异常。
             */
            @Override public void afterCommit() { bestEffort(work); }
        });
    }
    /**
     * @param work 尽力通知；通知失败不将已提交事务误报为回滚
     */
    private static void bestEffort(Runnable work) { try { work.run(); } catch (RuntimeException unavailable) { /* 数据库终态已生效，租约复核仍会阻止旧提交。 */ } }
}
