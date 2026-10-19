package com.lawrence.travel.application.support;

import com.lawrence.travel.application.execution.ExecutionCorrelation;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.SupportStore;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** PG故障时最多128个固定诊断ID，最多3次尝试；不保存请求正文或原始异常，不是业务恢复队列。 */
public final class DiagnosticBuffer {
    private final SupportStore store;
    private final BusinessTransactions tx;
    private final ArrayDeque<Item> queue = new ArrayDeque<>();
    private final AtomicLong failures = new AtomicLong();
    private long dropped;
    private long lastWarning;

    /**
     * @param id 固定事件
     * @param user 归属
     * @param request 请求
     * @param trace 链路
     * @param attempt 已失败次数
     */
    private record Item(UUID id, UUID user, UUID request, UUID trace, int attempt) {}

    /**
     * @param store 诊断端口
     * @param tx 独立短事务
     */
    public DiagnosticBuffer(SupportStore store, BusinessTransactions tx) {
        this.store = store;
        this.tx = tx;
    }

    /**
     * @param user 失败主体；只计固定故障，不将异常或反馈正文加入内存
     */
    public synchronized void failed(UUID user) {
        failures.incrementAndGet();
        var ids = ExecutionCorrelation.current();
        if (queue.size() == 128) dropped++;
        else queue.add(new Item(UUID.randomUUID(), user, ids.requestId(), ids.traceId(), 0));
        long now = System.nanoTime();
        if (lastWarning == 0 || now - lastWarning > 60_000_000_000L) {
            System.err.println("SUPPORT_DIAGNOSTIC_WRITE_UNAVAILABLE");
            lastWarning = now;
        }
    }

    /** 每次最多处理8条，失败重排且超过3次丢弃；不会阻塞业务线程等待恢复。 */
    public void flush() {
        for (int n = 0; n < 8; n++) {
            Item item;
            synchronized (this) {
                item = queue.poll();
            }
            if (item == null) return;
            try {
                boolean saved =
                        tx.required(
                                () ->
                                        store.diagnostic(
                                                item.user(),
                                                item.id(),
                                                item.request(),
                                                item.trace(),
                                                "SUPPORT_UNAVAILABLE"));
                if (!saved)
                    synchronized (this) {
                        dropped++;
                    }
            } catch (RuntimeException unavailable) {
                synchronized (this) {
                    if (item.attempt() >= 2 || queue.size() == 128) dropped++;
                    else
                        queue.add(
                                new Item(
                                        item.id(),
                                        item.user(),
                                        item.request(),
                                        item.trace(),
                                        item.attempt() + 1));
                }
            }
        }
    }

    /** 显式反馈被容量门禁拒绝时计数，不谎称进入待补写队列。 */
    public synchronized void capacityRejected() {
        dropped++;
    }

    /**
     * @return 队列和丢弃计数，不含用户及凭据
     */
    public synchronized Support.Health health() {
        return new Support.Health(queue.size(), dropped, failures.get());
    }
}
