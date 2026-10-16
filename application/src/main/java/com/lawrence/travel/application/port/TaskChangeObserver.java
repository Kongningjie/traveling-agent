package com.lawrence.travel.application.port;

import com.lawrence.travel.domain.travel.TaskScope;

/**
 * 在同一任务事务中使旧运行失效；不在事务内等待框架或外部请求停止。
 */
public interface TaskChangeObserver {
    /**
     * @param scope 已变更任务；调用时任务锁尚在当前事务内
     */
    void changed(TaskScope scope);
}
