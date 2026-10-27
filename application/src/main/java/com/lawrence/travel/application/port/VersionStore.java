package com.lawrence.travel.application.port;

import com.lawrence.travel.application.itinerary.Versions;
import com.lawrence.travel.domain.travel.TaskScope;
import java.util.List;
import java.util.UUID;

/** 私有版本查询与恢复前置检查，不允许直接移动生效指针。 */
public interface VersionStore {
    /**
     * @param scope 私有任务
     * @param offset 分页
     * @param limit 1至100
     * @return 按创建时间降序，标明曾接受状态
     */
    List<Versions.Summary> list(TaskScope scope, int offset, int limit);

    /**
     * @param scope 私有任务
     * @param id 快照
     * @return 同一归属版本，否则NOT_FOUND
     */
    Versions.Detail detail(TaskScope scope, UUID id);

    /**
     * @param command 当前认证及版本预期
     * @throws RuntimeException 不属于本人、从未接受、存在待处理修改或预期过期时拒绝；调用方须同事务启动运行
     */
    void guardRestore(Versions.Restore command);
}
