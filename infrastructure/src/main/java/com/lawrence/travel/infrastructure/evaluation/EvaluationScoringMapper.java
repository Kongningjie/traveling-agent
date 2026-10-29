package com.lawrence.travel.infrastructure.evaluation;

import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 程序来源和人工记录SQL；所有读写联查原run归属、撤销及数据库当前时间。 */
public interface EvaluationScoringMapper {
    /**
     * 只在原始时间和指纹匹配时保存来源，同ID已有不同JSON不能覆盖。
     *
     * @param owner 原归属
     * @param run 原运行
     * @param source 规范化元数据JSON
     * @param fingerprint 来源指纹
     * @return 新增或相同内容匹配行数
     */
    @Insert(
            """
            INSERT INTO evaluation_scoring_source(run_id,source,fingerprint)
            SELECT r.run_id,CAST(#{source} AS JSONB),#{fingerprint} FROM evaluation_retention_run r
            WHERE r.run_id=#{run} AND r.owner_id=#{owner} AND r.revoked_at IS NULL AND r.expires_at>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM evaluation_deletion_marker t WHERE t.run_id=r.run_id)
              AND r.started_at=CAST(CAST(#{source} AS JSONB)#>>'{context,startedAt}' AS TIMESTAMPTZ)
              AND r.dataset_hash=CAST(#{source} AS JSONB)#>>'{context,datasetHash}'
              AND r.configuration_hash=CAST(#{source} AS JSONB)#>>'{context,configurationHash}'
            ON CONFLICT(run_id) DO UPDATE SET run_id=EXCLUDED.run_id
            WHERE evaluation_scoring_source.source=EXCLUDED.source AND evaluation_scoring_source.fingerprint=EXCLUDED.fingerprint
            """)
    int record(
            @Param("owner") UUID owner,
            @Param("run") UUID run,
            @Param("source") String source,
            @Param("fingerprint") String fingerprint);

    /**
     * 评分写入前先取得run锁，与撤销和未来清理保持相同顺序。
     *
     * @param owner 认证账号
     * @param run 原运行
     * @return 可见运行身份，缺失为null
     */
    @Select(
            "SELECT run_id::text FROM evaluation_retention_run WHERE run_id=#{run} AND owner_id=#{owner} AND revoked_at IS NULL AND expires_at>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM evaluation_deletion_marker t WHERE t.run_id=#{run}) FOR UPDATE")
    String lockRun(@Param("owner") UUID owner, @Param("run") UUID run);

    /**
     * 取得run锁后再锁评分来源，不暴露其他用户的来源。
     *
     * @param owner 认证账号
     * @param run 原运行
     * @return 已锁定的来源身份
     */
    @Select(
            "SELECT s.run_id::text FROM evaluation_scoring_source s JOIN evaluation_retention_run r ON r.run_id=s.run_id WHERE r.run_id=#{run} AND r.owner_id=#{owner} AND r.revoked_at IS NULL AND r.expires_at>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM evaluation_deletion_marker t WHERE t.run_id=r.run_id) FOR UPDATE OF s")
    String lockSource(@Param("owner") UUID owner, @Param("run") UUID run);

    /**
     * 读取来源与最新回执，不允许缓存到期之前的结果供以后复用。
     *
     * @param owner 认证账号
     * @param run 原运行
     * @return 来源快照，缺失为null
     */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select(
            """
            SELECT s.source::text AS source,s.fingerprint,s.revision,v.receipt::text AS receipt
            FROM evaluation_scoring_source s JOIN evaluation_retention_run r ON r.run_id=s.run_id
            LEFT JOIN evaluation_scoring_review v ON v.run_id=s.run_id AND v.request_id=s.current_request_id
            WHERE r.run_id=#{run} AND r.owner_id=#{owner} AND r.revoked_at IS NULL AND r.expires_at>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM evaluation_deletion_marker t WHERE t.run_id=r.run_id)
            """)
    Row read(@Param("owner") UUID owner, @Param("run") UUID run);

    /**
     * 查原幂等回执；到期和撤销后不能凭旧请求再次取得评分。
     *
     * @param owner 认证账号
     * @param requestId 原请求
     * @return 请求指纹及回执，缺失为null
     */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select(
            """
            SELECT v.request_hash,v.receipt::text AS receipt FROM evaluation_scoring_review v
            JOIN evaluation_retention_run r ON r.run_id=v.run_id
            WHERE v.request_id=#{id} AND v.reviewer_id=#{owner} AND r.owner_id=#{owner}
              AND r.revoked_at IS NULL AND r.expires_at>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM evaluation_deletion_marker t WHERE t.run_id=r.run_id)
            """)
    Saved receipt(@Param("owner") UUID owner, @Param("id") UUID requestId);

    /**
     * 在当前修订且未到期的来源下追加记录；冲突不覆盖既有评分。
     *
     * @param owner 认证账号
     * @param run 原运行
     * @param expected 当前修订
     * @param id 幂等请求
     * @param requestHash 请求内容指纹
     * @param receipt 服务器生成的回执JSON
     * @return 追加行数
     */
    @Insert(
            """
            INSERT INTO evaluation_scoring_review(request_id,run_id,reviewer_id,revision,request_hash,receipt)
            SELECT #{id},r.run_id,#{owner},#{expected}+1,#{requestHash},CAST(#{receipt} AS JSONB)
            FROM evaluation_retention_run r JOIN evaluation_scoring_source s ON s.run_id=r.run_id
            WHERE r.run_id=#{run} AND r.owner_id=#{owner} AND r.revoked_at IS NULL AND r.expires_at>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM evaluation_deletion_marker t WHERE t.run_id=r.run_id)
              AND s.revision=#{expected}
            ON CONFLICT DO NOTHING
            """)
    int insert(
            @Param("owner") UUID owner,
            @Param("run") UUID run,
            @Param("expected") long expected,
            @Param("id") UUID id,
            @Param("requestHash") String requestHash,
            @Param("receipt") String receipt);

    /**
     * 仅在仍有效的同一修订上移动指针；失败必须由调用事务回滚此前追加。
     *
     * @param owner 认证账号
     * @param run 原运行
     * @param expected 当前修订
     * @param id 已追加请求
     * @return 更新行数
     */
    @Update(
            """
            UPDATE evaluation_scoring_source s SET revision=#{expected}+1,current_request_id=#{id}
            FROM evaluation_retention_run r WHERE s.run_id=r.run_id AND r.run_id=#{run}
              AND r.owner_id=#{owner} AND r.revoked_at IS NULL AND r.expires_at>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM evaluation_deletion_marker t WHERE t.run_id=r.run_id) AND s.revision=#{expected}
            """)
    int advance(
            @Param("owner") UUID owner,
            @Param("run") UUID run,
            @Param("expected") long expected,
            @Param("id") UUID id);

    /**
     * 原始存储投影，只由仓储解码，不从Controller返回。
     *
     * @param source 程序证据JSON
     * @param fingerprint 来源指纹
     * @param revision 当前修订
     * @param receipt 最新回执JSON，可空
     */
    record Row(String source, String fingerprint, long revision, String receipt) {}

    /**
     * 幂等回执投影。
     *
     * @param requestHash 原请求指纹
     * @param receipt 原回执JSON
     */
    record Saved(String requestHash, String receipt) {}
}
