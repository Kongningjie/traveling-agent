package com.lawrence.travel.application.port;

import com.lawrence.travel.application.memory.SessionContext;
import java.util.UUID;

/** PG原文和摘要端口，来源改变或作业取消时拒绝旧缓存，不恢复已删偏好。 */
public interface SessionContextStore {
    /** @param source 有效尝试 @param reason 固定问题分类 @return 已持久化编号，不保存摘要错误原文 */
    UUID recordIssue(SessionContext.Source source,String reason);
    /** @param budgetId 服务器产生的原预算 @param role 固定角色 @return 独立聊天或旅行的权威来源 */
    SessionContext.Source load(UUID budgetId,String role);
    /** @param source 原来源；逐次发送与摘要写入前复核 */
    void guard(SessionContext.Source source);
    /** @param source 当前来源 @return 同版本最新可验证摘要，可空；调用者还须验证覆盖区间 */
    SessionContext.Summary cached(SessionContext.Source source);
    /** @param source 原来源 @param summary 已校验摘要；失效attempt不能写回 */
    void save(SessionContext.Source source,SessionContext.Summary summary);
}
