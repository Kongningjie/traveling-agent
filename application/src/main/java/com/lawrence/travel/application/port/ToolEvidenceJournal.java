package com.lawrence.travel.application.port;

import java.time.Instant;
import java.util.UUID;

/** 工具调用及证据的持久化端口；未知与崩溃记录不退还免费配额预占。 */
public interface ToolEvidenceJournal {
    /**
     * 原子预占一单位免费资源；配额按已核实快照计数且跨重启保留。
     * @param runId 原运行
     * @param group 服务组，搜索与提取共用 Tavily credits
     * @param verifiedAt 控制台或用量接口核实时间
     * @param validUntil 快照失效时间
     * @param allowance 快照后允许预占的保守单位数
     * @return 调用记录身份
     */
    UUID reserve(UUID runId, String group, Instant verifiedAt, Instant validUntil, long allowance);

    /**
     * 保存成功证据和哈希，或标记未知失败；不删除或覆盖首个完成结果。
     * @param id 预占身份
     * @param evidence 成功证据；null 表示未知失败
     */
    void finish(UUID id, TravelToolPort.Evidence evidence);
}
