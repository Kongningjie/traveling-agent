package com.lawrence.travel.infrastructure.persistence.control;

import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 工具资源及 JSON 证据显式 SQL；仅由仓储适配器使用。 */
public interface ToolEvidenceMapper {
    /**
     * 对服务组取得事务级锁，避免并发检查余额后一起穿透。
     * @param group 服务组
     * @return PostgreSQL void 结果，调用方不使用
     */
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{group}, 7341))::text")
    String lock(@Param("group") String group);

    /**
     * 读取核实快照后的保守占用，包含在途及未知调用。
     * @param group 服务组
     * @param since 核实时间
     * @return 已占单位
     */
    @Select(
            "SELECT count(*) FROM traveling_agent.tool_evidence WHERE resource_group=#{group} AND started_at >= #{since}")
    long count(@Param("group") String group, @Param("since") Instant since);

    /**
     * 旧快照存在未知消耗或遗留在途时拒绝继续，等待重新核实。
     * @param group 服务组
     * @param since 核实时间
     * @return 是否有不确定消耗
     */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM traveling_agent.tool_evidence WHERE resource_group=#{group} AND started_at >= #{since} AND status <> 'COMPLETED')")
    boolean uncertain(@Param("group") String group, @Param("since") Instant since);

    /**
     * 建立预占记录，不保存认证信息或完整请求 URL。
     * @param id 调用身份
     * @param runId 原运行
     * @param group 服务组
     */
    @Insert(
            "INSERT INTO traveling_agent.tool_evidence(id,run_id,resource_group) VALUES(#{id},#{runId},#{group})")
    void insert(@Param("id") UUID id, @Param("runId") UUID runId, @Param("group") String group);

    /**
     * 首次完成生效，重复 finally 不覆盖已保存证据。
     * @param id 调用身份
     * @param source 无凭证来源，失败可空
     * @param retrieved 获取时间，失败可空
     * @param payload JSON 文本，失败为空
     * @param hash 原文哈希，失败为空
     */
    @Update(
            "UPDATE traveling_agent.tool_evidence SET status=CASE WHEN CAST(#{payload} AS text) IS NULL THEN 'UNKNOWN' ELSE 'COMPLETED' END, source_url=#{source},retrieved_at=#{retrieved},payload=CAST(#{payload} AS jsonb),content_hash=#{hash} WHERE id=#{id} AND status='RESERVED'")
    void finish(
            @Param("id") UUID id,
            @Param("source") String source,
            @Param("retrieved") Instant retrieved,
            @Param("payload") String payload,
            @Param("hash") String hash);
}
