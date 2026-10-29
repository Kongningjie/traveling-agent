package com.lawrence.travel.infrastructure.evaluation;

import com.lawrence.travel.application.evaluation.EvaluationRetention;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** PG保留台账；期限以数据库时钟判定，所有条件写入SQL，不自行调用平台删除。 */
public interface EvaluationRetentionMapper extends EvaluationRetention.Store {
    /**
     * 原始字段完全相同且仍活跃才允许幂等匹配，不修改时间。
     *
     * @param registration 可信运行身份
     * @return 新增或幂等匹配行数；冲突或到期返回0
     */
    @Insert(
            """
            INSERT INTO evaluation_retention_run(run_id,owner_id,started_at,expires_at,dataset_hash,configuration_hash)
            SELECT #{record.run},#{record.owner},CAST(#{record.startedAt} AS TIMESTAMPTZ),CAST(#{record.startedAt} AS TIMESTAMPTZ)+INTERVAL '720 hours',#{record.datasetHash},#{record.configurationHash}
            WHERE CAST(#{record.startedAt} AS TIMESTAMPTZ)<=clock_timestamp() AND CAST(#{record.startedAt} AS TIMESTAMPTZ)+INTERVAL '720 hours'>clock_timestamp()
            ON CONFLICT(run_id) DO UPDATE SET run_id=EXCLUDED.run_id
            WHERE evaluation_retention_run.owner_id=EXCLUDED.owner_id
              AND evaluation_retention_run.started_at=EXCLUDED.started_at
              AND evaluation_retention_run.dataset_hash=EXCLUDED.dataset_hash
              AND evaluation_retention_run.configuration_hash=EXCLUDED.configuration_hash
              AND evaluation_retention_run.revoked_at IS NULL
              AND evaluation_retention_run.expires_at>clock_timestamp()
            """)
    int registerRow(@Param("record") EvaluationRetention.Registration registration);

    /** {@inheritDoc} SQL没有写入行时明确拒绝。 */
    @Override
    default boolean register(EvaluationRetention.Registration registration) {
        return registerRow(registration) == 1;
    }

    /** {@inheritDoc} 每次读取使用当前数据库时间，不依赖周期清理是否已运行。 */
    @Override
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select(
            """
            SELECT run_id AS run,started_at,expires_at,dataset_hash,configuration_hash
            FROM evaluation_retention_run WHERE run_id=#{run} AND owner_id=#{owner}
              AND revoked_at IS NULL AND expires_at>clock_timestamp()
            """)
    EvaluationRetention.Active active(@Param("owner") UUID owner, @Param("run") UUID run);

    /**
     * 锁定run后登记副本；与撤销串行，撤销之后不能再新增副本。
     *
     * @param owner 认证归属
     * @param run 独立运行
     * @param kind 副本类别
     * @param locatorHash 位置指纹
     * @return 插入或幂等匹配的行数
     */
    @Insert(
            """
            WITH eligible AS (
              SELECT run_id,expires_at FROM evaluation_retention_run
              WHERE run_id=#{run} AND owner_id=#{owner} AND revoked_at IS NULL
                AND expires_at>clock_timestamp() FOR UPDATE
            ) INSERT INTO evaluation_retention_copy(run_id,kind,locator_hash)
            SELECT run_id,#{kind},#{locatorHash} FROM eligible WHERE expires_at>clock_timestamp()
            ON CONFLICT(run_id,kind,locator_hash) DO UPDATE SET locator_hash=EXCLUDED.locator_hash
            """)
    int trackRow(
            @Param("owner") UUID owner,
            @Param("run") UUID run,
            @Param("kind") EvaluationRetention.CopyKind kind,
            @Param("locatorHash") String locatorHash);

    /** {@inheritDoc} 行数为零表示没有仍活跃的自有run。 */
    @Override
    default boolean track(
            UUID owner, UUID run, EvaluationRetention.CopyKind kind, String locatorHash) {
        return trackRow(owner, run, kind, locatorHash) == 1;
    }

    /**
     * 写入第一次撤销时间；同run锁与副本登记互斥。
     *
     * @param owner 认证归属
     * @param run 独立运行
     * @return 自有记录数量，重复撤销仍为1
     */
    @Update(
            """
            UPDATE evaluation_retention_run SET revoked_at=COALESCE(revoked_at,clock_timestamp())
            WHERE run_id=#{run} AND owner_id=#{owner}
            """)
    int revokeRow(@Param("owner") UUID owner, @Param("run") UUID run);

    /** {@inheritDoc} 不存在和越权统一返回false。 */
    @Override
    default boolean revoke(UUID owner, UUID run) {
        return revokeRow(owner, run) == 1;
    }

    /** {@inheritDoc} 24小时从首次阻断计算，不从清理开始或重试时间计算。 */
    @Override
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select(
            """
            SELECT r.run_id AS run,c.kind,c.locator_hash,LEAST(r.expires_at,r.revoked_at) AS blocked_at,
              LEAST(r.expires_at,r.revoked_at)+INTERVAL '24 hours' AS deadline,c.generation,
              clock_timestamp()>=LEAST(r.expires_at,r.revoked_at)+INTERVAL '24 hours' AS overdue
            FROM evaluation_retention_run r JOIN evaluation_retention_copy c ON c.run_id=r.run_id
            WHERE (r.revoked_at IS NOT NULL OR r.expires_at<=clock_timestamp()) AND c.attested_at IS NULL
            ORDER BY LEAST(r.expires_at,r.revoked_at),r.run_id,c.kind,c.locator_hash LIMIT #{limit}
            """)
    List<EvaluationRetention.Cleanup> pending(@Param("limit") int limit);

    /**
     * 只记录对应代次的回执；迟到数据使旧代次失效，不能覆盖新清理任务。
     *
     * @param run 原始运行
     * @param kind 副本类别
     * @param locatorHash 位置指纹
     * @param generation 清理代次
     * @param evidenceHash 受控清理适配器证据哈希
     * @return 成功更新行数
     */
    @Update(
            """
            UPDATE evaluation_retention_copy c SET evidence_hash=#{evidenceHash},attested_at=clock_timestamp()
            FROM evaluation_retention_run r WHERE c.run_id=r.run_id AND c.run_id=#{run}
              AND c.kind=#{kind} AND c.locator_hash=#{locatorHash} AND c.generation=#{generation}
              AND c.attested_at IS NULL AND (r.revoked_at IS NOT NULL OR r.expires_at<=clock_timestamp())
            """)
    int attestRow(
            @Param("run") UUID run,
            @Param("kind") EvaluationRetention.CopyKind kind,
            @Param("locatorHash") String locatorHash,
            @Param("generation") long generation,
            @Param("evidenceHash") String evidenceHash);

    /** {@inheritDoc} 回执不验证物理存储，未装配外部清理器前不能宣称删除已完成。 */
    @Override
    default boolean attest(
            UUID run,
            EvaluationRetention.CopyKind kind,
            String locatorHash,
            long generation,
            String evidenceHash) {
        return attestRow(run, kind, locatorHash, generation, evidenceHash) == 1;
    }

    /**
     * 清空迟到副本的回执并增加代次；只能处理已阻断run，不改变原始期限。
     *
     * @param run 原始运行
     * @param kind 副本类别
     * @param locatorHash 位置指纹
     * @return 已重新排队行数
     */
    @Update(
            """
            UPDATE evaluation_retention_copy c SET evidence_hash=NULL,attested_at=NULL,generation=c.generation+1
            FROM evaluation_retention_run r WHERE c.run_id=r.run_id AND c.run_id=#{run}
              AND c.kind=#{kind} AND c.locator_hash=#{locatorHash}
              AND (r.revoked_at IS NOT NULL OR r.expires_at<=clock_timestamp())
            """)
    int arrivedLateRow(
            @Param("run") UUID run,
            @Param("kind") EvaluationRetention.CopyKind kind,
            @Param("locatorHash") String locatorHash);

    /** {@inheritDoc} 迟到回执只增加清理工作，不恢复读取或投递资格。 */
    @Override
    default boolean arrivedLate(UUID run, EvaluationRetention.CopyKind kind, String locatorHash) {
        return arrivedLateRow(run, kind, locatorHash) == 1;
    }
}
