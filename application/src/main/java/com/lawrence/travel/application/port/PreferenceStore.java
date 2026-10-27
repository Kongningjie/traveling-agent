package com.lawrence.travel.application.port;

import com.lawrence.travel.domain.memory.Preference;
import java.util.UUID;

/** 用户级偏好权威端口；变更须在应用短事务中执行，先锁稳定用户版本行。 */
public interface PreferenceStore {
    /**
     * @param userId 认证用户
     * @return 同一版本下的条目，不包含墓碑；短事务共享锁防止撕裂读取
     */
    Preference.View read(UUID userId);

    /**
     * @param userId 认证用户
     * @param requestId 永久请求键
     * @param expectedRevision 明确读到的用户版本
     * @param id 新建或已有身份
     * @param action SAVE／UPDATE／SUGGEST／CONFIRM／DELETE
     * @param category 类别
     * @param value 有界值
     * @param city 城市范围
     * @return 变更结果；同键重放不重复变更且返回当前视图，同键异义冲突，旧写入不得恢复墓碑
     */
    Preference.View change(
            UUID userId,
            UUID requestId,
            long expectedRevision,
            UUID id,
            String action,
            String category,
            String value,
            String city);
}
