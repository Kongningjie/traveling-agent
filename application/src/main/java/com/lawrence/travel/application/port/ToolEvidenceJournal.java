package com.lawrence.travel.application.port;

import java.util.UUID;

/** 工具调用及证据的持久化端口；保留成功、未知及崩溃记录，不执行供应商配额准入。 */
public interface ToolEvidenceJournal {
    /**
     * 在独立事务中记录一次工具调用；不按免费额度、有效期或历史未知记录阻断。
     *
     * @param runId 原运行
     * @param group 服务组
     * @return 调用记录身份
     */
    UUID reserve(UUID runId, String group);

    /**
     * 保存成功证据和哈希，或标记未知失败；不删除或覆盖首个完成结果。
     *
     * @param id 预占身份
     * @param evidence 成功证据；null 表示未知失败
     */
    void finish(UUID id, TravelToolPort.Evidence evidence);
}
