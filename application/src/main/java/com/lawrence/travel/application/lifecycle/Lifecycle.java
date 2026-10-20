package com.lawrence.travel.application.lifecycle;

import java.time.Instant;
import java.util.UUID;

/** 删除与恢复协议；墓碑只包含最小技术身份，不保存原文或文件名。 */
public final class Lifecycle {
    /** 协议命名空间。 */
    private Lifecycle() {}

    /** 明确删除范围，聊天包含其唯一关联旅行，长期偏好独立保留。 */
    public enum Kind {
        /** 整个聊天。 */
        CHAT,
        /** 未关联聊天的旅行，已关联时一并删除聊天。 */
        TASK,
        /** 单个源附件。 */
        DOCUMENT
    }

    /**
     * @param kind 范围
     * @param target 原对象
     * @param chat 关联聊天可空
     * @param task 关联旅行可空
     * @param documents 附件数
     * @param versions 行程版本数
     * @param revision 确认对象集摘要
     * @param notice 删除影响
     * @param resources 至多40个对象摘要
     * @param truncated 列表是否还有更多，删除范围仍含全部
     */
    public record Preview(
            Kind kind,
            UUID target,
            UUID chat,
            UUID task,
            long documents,
            long versions,
            String revision,
            String notice,
            java.util.List<Resource> resources,
            boolean truncated) {}

    /**
     * @param kind 对象类型
     * @param id 精确对象
     * @param name 用户可见名称，不进入墓碑
     */
    public record Resource(String kind, UUID id, String name) {}

    /**
     * @param sequence 单调顺序
     * @param id 幂等请求
     * @param user 归属
     * @param kind 范围
     * @param target 原对象
     * @param chat 聊天可空
     * @param task 旅行可空
     * @param deletedAt 逻辑删除时间
     */
    public record Entry(
            long sequence,
            UUID id,
            UUID user,
            Kind kind,
            UUID target,
            UUID chat,
            UUID task,
            Instant deletedAt) {}

    /**
     * @param instance PG部署身份
     * @param sequence 已可靠导出顺序
     * @param hash 链式SHA256
     */
    public record Head(UUID instance, long sequence, String hash) {}

    /**
     * @param saved 已提交墓碑
     * @param id 请求
     * @param readable 删除后始终false
     * @param cleanupPending 原件等待异步清理
     */
    public record Receipt(boolean saved, UUID id, boolean readable, boolean cleanupPending) {}

    /**
     * @param id 清理项
     * @param user 归属
     * @param key 服务器受管键
     * @param document 是否原件根
     * @param attempts 失败次数
     */
    public record File(UUID id, UUID user, String key, boolean document, int attempts) {}

    /**
     * @param pending 尚未清理原件
     * @param overdue 超过24小时
     * @param failed 有失败记录
     * @param diagnosticsDropped 容量丢弃
     */
    public record Health(long pending, long overdue, long failed, long diagnosticsDropped) {}
}
