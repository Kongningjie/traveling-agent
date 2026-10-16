package com.lawrence.travel.application.port;

import java.util.function.Supplier;

/**
 * 应用短事务边界；端口允许业务存储和执行登记加入同一个事务。
 */
public interface BusinessTransactions {
    /**
     * @param work 不含网络调用的业务操作
     * @param <T> 结果类型
     * @return 全部成功才提交的结果
     */
    <T> T required(Supplier<T> work);
    /**
     * @param work 事务提交后的尽力取消；回滚时不得执行
     */
    void afterCommit(Runnable work);
}
