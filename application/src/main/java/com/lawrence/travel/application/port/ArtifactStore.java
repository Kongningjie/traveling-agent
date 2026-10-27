package com.lawrence.travel.application.port;

import com.lawrence.travel.domain.travel.TaskScope;
import java.util.Set;
import java.util.UUID;

/** 必要产物的私有索引；普通文本与JSON正文不随体积自动转移到文件。 */
public interface ArtifactStore {
    /**
     * @param id 不可变身份
     * @param scope 归属
     * @param runId 来源运行
     * @param kind DB_TEXT／DB_JSON／FILE
     * @param mediaType 白名单类型
     * @param hash 正文SHA256
     * @param size UTF8或文件字节数
     * @param fileKey 服务端存储键，可空
     * @param body 数据库正文，可空
     * @param visible 是否允许经鉴权展示
     */
    record Entry(
            UUID id,
            TaskScope scope,
            UUID runId,
            String kind,
            String mediaType,
            String hash,
            long size,
            String fileKey,
            String body,
            boolean visible) {}

    /**
     * @param scope 受信归属
     * @param run 来源运行；不存在或越权同样拒绝
     */
    void requireRun(TaskScope scope, UUID run);

    /**
     * @param exclusive 回收用排他锁；文件登记用共享锁，必须在调用者事务内，保护落盘到引用登记的窗口
     */
    void lockFiles(boolean exclusive);

    /**
     * @param entry 完整不可变引用；失败事务回滚，已落盘文件留待保留期后回收
     */
    void insert(Entry entry);

    /**
     * @param user 认证用户
     * @param id 私有产物
     * @return 仅允许展示的所属产物；越权与缺失统一拒绝
     */
    Entry visible(UUID user, UUID id);

    /**
     * @return 全部有效文件引用；仅供受信维护任务在排他事务内回收，不对模型或HTTP暴露
     */
    Set<String> referencedFiles();
}
