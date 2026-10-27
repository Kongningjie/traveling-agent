package com.lawrence.travel.application.port;

import com.lawrence.travel.application.memory.SessionContext;
import java.util.UUID;

/** PG原文和摘要端口，来源改变或作业取消时拒绝旧缓存，不恢复已删偏好。 */
public interface SessionContextStore {
    /**
     * 用固定分类记录可追踪的上下文问题，不保存失败摘要或原始异常正文。
     * @param source 有效尝试
     * @param reason 固定问题分类
     * @return 已持久化编号，不保存摘要错误原文
     */
    UUID recordIssue(SessionContext.Source source, String reason);

    /**
     * 读取当前角色执行所需的权威状态、历史和资料目录，不从摘要推导写入授权。
     * @param budgetId 服务器产生的原预算
     * @param role 固定角色
     * @return 独立聊天或旅行的权威来源
     */
    SessionContext.Source load(UUID budgetId, String role);

    /**
     * 发送请求或保存摘要前复核原来源，取消及删除后不能复用旧缓存。
     * @param source 原来源；逐次发送与摘要写入前复核
     */
    void guard(SessionContext.Source source);

    /**
     * 查找同依赖版本的最新摘要；摘要覆盖区间仍由调用方校验。
     * @param source 当前来源
     * @return 同版本最新可验证摘要，可空；调用者还须验证覆盖区间
     */
    SessionContext.Summary cached(SessionContext.Source source);

    /**
     * 在当前来源仍有效时保存已校验摘要，不允许过期尝试写回。
     * @param source 原来源
     * @param summary 已校验摘要；失效attempt不能写回
     */
    void save(SessionContext.Source source, SessionContext.Summary summary);
}
