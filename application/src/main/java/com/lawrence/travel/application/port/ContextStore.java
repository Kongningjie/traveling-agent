package com.lawrence.travel.application.port;

import com.lawrence.travel.application.memory.AgentContext;
import java.util.UUID;

/** 私有上下文权威读取与独立可复用摘要；数据库事务只包住短读写。 */
public interface ContextStore {
    /**
     * @param runId 服务端运行
     * @param role 固定角色
     * @return 同用户／任务版本的保护区及过滤历史
     */
    AgentContext.Source load(UUID runId, String role);

    /**
     * @param source 已读取版本
     * @return 同一绑定的已校验摘要，旧绑定只失效不重新贴标签
     */
    AgentContext.Summary cached(AgentContext.Source source);

    /**
     * @param source 原始读取
     * @param summary 模型返回且已校验的摘要
     * @return 同绑定首次摘要；旧执行或变化版本拒绝
     */
    AgentContext.Summary save(AgentContext.Source source, AgentContext.Summary summary);

    /**
     * @param source 实际使用的版本；每次模型请求前重新检查，不持锁等网络
     */
    void guard(AgentContext.Source source);

    /**
     * @param source 受信调用上下文
     * @param messageId 已卸载的原消息
     * @return 私有完整正文；旧偏好版本或审核角色拒绝读取历史
     */
    String original(AgentContext.Source source, UUID messageId);
}
