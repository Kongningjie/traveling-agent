package com.lawrence.travel.application.port;

/** 单实例实际模型在途槽；不得覆盖整个 Agent 工具循环或运行等待期。 */
public interface ModelRequestSlots {
    /**
     * 非阻塞获取一个在途请求槽，排队由执行器另行处理。
     * @return 可幂等释放的槽；已满时返回 null
     */
    Lease tryAcquire();

    /** 一次实际 SDK／HTTP 请求的槽，不可在请求仍在途时因浏览器断开而释放。 */
    interface Lease extends AutoCloseable {
        /** 幂等释放；只能在实际请求确认结束或尚未发送时调用。 */
        @Override
        void close();
    }
}
