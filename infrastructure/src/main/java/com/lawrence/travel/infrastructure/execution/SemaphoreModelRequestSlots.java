package com.lawrence.travel.infrastructure.execution;

import com.lawrence.travel.application.port.ModelRequestSlots;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单实例实际模型请求槽；许可只覆盖 SDK 请求，不覆盖父 Agent 的工具等待。 */
public final class SemaphoreModelRequestSlots implements ModelRequestSlots {
    private final Semaphore semaphore;

    /**
     * 建立有界并发槽，不创建线程或等待队列。
     * @param maximum 全局最多在途请求数，必须为正
     */
    public SemaphoreModelRequestSlots(int maximum) {
        if (maximum <= 0) throw new IllegalArgumentException("Model concurrency must be positive");
        semaphore = new Semaphore(maximum);
    }

    /** {@inheritDoc} 释放使用原子标记，重复回调不会凭空增加许可。 */
    @Override
    public Lease tryAcquire() {
        if (!semaphore.tryAcquire()) return null;
        var released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) semaphore.release();
        };
    }
}
