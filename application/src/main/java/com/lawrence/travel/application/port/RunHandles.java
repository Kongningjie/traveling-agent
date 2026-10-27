package com.lawrence.travel.application.port;

import java.util.UUID;

/**
 * 进程内框架执行句柄取消，不是业务状态权威。
 */
public interface RunHandles {
    /**
     * @param runId 已持久停止的运行；中断不代表实际请求已退出
     */
    void cancel(UUID runId);

    /**
     * @param scope 已切换运行的任务
     * @param currentGeneration 当前代次；只中断更旧代次，旧请求重放不能误中断新执行
     */
    void supersede(com.lawrence.travel.domain.travel.TaskScope scope, long currentGeneration);
}
