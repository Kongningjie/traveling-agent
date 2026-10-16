package com.lawrence.travel.application.port;

import java.time.Duration;

/** 身份入口速率端口；依赖不可用时必须失败，不能自动放行。 */
public interface AuthRateLimiter {
    /**
     * 原子消耗一次固定窗口配额，超限和依赖失败分别拒绝。
     * @param bucket 受信来源及规范账号组成的桶，适配器存储前应哈希
     * @param maximum 窗口内最大次数
     * @param window 窗口长度
     */
    void take(String bucket, int maximum, Duration window);
}
