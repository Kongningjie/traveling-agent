package com.lawrence.travel.application.port;

import com.lawrence.travel.domain.identity.UserIdentity;

/** 长连接持续认证端口；会话标识仅用于内存查询，禁止日志、返回正文和持久化。 */
public interface SubscriptionAccess {
    /**
     * @param sessionId 不可输出的会话标识
     * @param user 初次认证身份
     * @return 会话、身份及账号仍有效；依赖异常安全失败
     */
    boolean active(String sessionId, UserIdentity user);
}
