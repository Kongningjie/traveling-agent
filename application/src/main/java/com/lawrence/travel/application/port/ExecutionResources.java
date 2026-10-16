package com.lawrence.travel.application.port;

import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** 单实例执行资源端口；不负责持久化调度、业务取消或重启恢复。 */
public interface ExecutionResources {
    /**
     * 非阻塞取得活跃运行范围；等待用户时由应用显式关闭，恢复后重新取得。
     * @return 独占运行范围
     * @throws java.util.concurrent.RejectedExecutionException 容量已满或服务停止
     */
    RunScope openRun();

    /** 父阶段在调用线程异步组合分支；不能占用调查线程同步等待同池子任务。 */
    interface RunScope extends AutoCloseable {
        /**
         * 非阻塞提交一个有界调查分支，实际模型请求仍须经过独立模型门禁。
         * @param work 不含浏览器会话的受信工作；必须自行遵守请求超时
         * @param <T> 分支产物类型
         * @return 只读完成视图，取消该视图不能提前释放底层资源
         * @throws java.util.concurrent.RejectedExecutionException 范围关闭或分支容量已满
         */
        <T> CompletionStage<T> forkResearch(Supplier<T> work);

        /** 幂等停止接收分支；既有分支实际退出后才释放运行容量，不隐式取消它们。 */
        @Override
        void close();
    }
}
