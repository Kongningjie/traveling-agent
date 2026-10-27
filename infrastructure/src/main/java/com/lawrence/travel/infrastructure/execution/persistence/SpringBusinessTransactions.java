package com.lawrence.travel.infrastructure.execution.persistence;

import com.lawrence.travel.application.port.BusinessTransactions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 应用BusinessTransactions端口的Spring实现，使用同一PG数据源的REQUIRED事务。
 *
 * <p>DialogueService、协商与候选接受等应用用例通过required包围短数据库业务；
 * 嵌套用例加入已有事务，不能理解为每次required都是独立提交。网络及模型等待必须在外部。
 * afterCommit只负责数据库提交后的尽力通知/取消；通知失败不能将已生效数据库事实冒称回滚，
 * 旧执行的最终提交资格仍由PG围栏和租约控制。
 */
public final class SpringBusinessTransactions implements BusinessTransactions {
    private final TransactionTemplate transaction;

    /**
     * @param manager 同一业务数据源事务
     */
    public SpringBusinessTransactions(PlatformTransactionManager manager) {
        transaction = new TransactionTemplate(manager);
    }

    /**
     * {@inheritDoc} 默认 REQUIRED，异常回滚全部已加入的仓储操作。
     */
    @Override
    public <T> T required(Supplier<T> work) {
        try {
            return transaction.execute(status -> work.get());
        } catch (org.springframework.dao.DataAccessException
                | org.springframework.transaction.TransactionException failure) {
            com.lawrence.travel.infrastructure.execution.AdmissionErrors.rethrowKnown(failure);
            throw new com.lawrence.travel.application.travel.TravelStorageFailure(
                    com.lawrence.travel.application.travel.TravelStorageFailure.Code
                            .DEPENDENCY_UNAVAILABLE);
        }
    }

    /**
     * {@inheritDoc} 事务外立即执行；提交后中断异常不改变已提交业务事实。
     */
    @Override
    public void afterCommit(Runnable work) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            bestEffort(work);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    /**
                     * 在提交已成功后执行尽力取消，不记录凭据或底层异常。
                     */
                    @Override
                    public void afterCommit() {
                        bestEffort(work);
                    }
                });
    }

    /**
     * @param work 尽力通知；通知失败不将已提交事务误报为回滚
     */
    private static void bestEffort(Runnable work) {
        try {
            work.run();
        } catch (RuntimeException unavailable) {
            /* 数据库终态已生效，租约复核仍会阻止旧提交。 */
        }
    }
}
