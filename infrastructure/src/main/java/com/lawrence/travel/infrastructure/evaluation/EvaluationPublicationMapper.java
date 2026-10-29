package com.lawrence.travel.infrastructure.evaluation;

import com.lawrence.travel.application.evaluation.EvaluationPublication;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** PG发布登记；锁顺序run→副本/尝试，单条SQL提交登记，不执行平台请求或重放UNKNOWN。 */
public interface EvaluationPublicationMapper extends EvaluationPublication.Store {
    /** {@inheritDoc} 副本和尝试同事务；唯一键冲突返回null，原始payload和attempt不覆盖。 */
    @Override
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select(
            value =
                    """
            WITH locked AS MATERIALIZED (
              SELECT run_id,owner_id,expires_at,revoked_at FROM evaluation_retention_run
              WHERE run_id=#{request.run} AND owner_id=#{request.owner} FOR UPDATE
            ), copies AS (
              INSERT INTO evaluation_retention_copy(run_id,kind,locator_hash)
              SELECT run_id,#{request.kind},#{request.locatorHash} FROM locked
              WHERE revoked_at IS NULL AND expires_at>clock_timestamp()
                AND NOT EXISTS(SELECT 1 FROM evaluation_deletion_marker t WHERE t.run_id=locked.run_id)
              ON CONFLICT(run_id,kind,locator_hash) DO UPDATE SET locator_hash=EXCLUDED.locator_hash
              RETURNING run_id
            ), claimed AS (
              INSERT INTO evaluation_publication(run_id,kind,locator_hash,payload_hash,attempt_id)
              SELECT c.run_id,#{request.kind},#{request.locatorHash},#{request.payloadHash},#{attempt}
              FROM copies c JOIN locked l ON l.run_id=c.run_id WHERE l.expires_at>clock_timestamp()
              ON CONFLICT DO NOTHING RETURNING run_id
            ) SELECT l.expires_at FROM locked l JOIN claimed c ON c.run_id=l.run_id
            """,
            affectData = true)
    Instant claim(
            @Param("request") EvaluationPublication.Request request,
            @Param("attempt") UUID attempt);

    /** {@inheritDoc} 锁定run后只消耗一次UNKNOWN许可；并发、重启或已回执不能重复消费。 */
    @Override
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select(
            value =
                    """
            WITH locked AS MATERIALIZED (
              SELECT run_id,expires_at,revoked_at FROM evaluation_retention_run
              WHERE run_id=#{permit.request.run} AND owner_id=#{permit.request.owner} FOR UPDATE
            ), consumed AS (
              UPDATE evaluation_publication p SET send_started=true FROM locked r
              WHERE p.run_id=r.run_id AND r.expires_at=CAST(#{permit.expiresAt} AS TIMESTAMPTZ)
                AND r.expires_at>clock_timestamp() AND r.revoked_at IS NULL
                AND p.kind=#{permit.request.kind} AND p.locator_hash=#{permit.request.locatorHash}
                AND p.payload_hash=#{permit.request.payloadHash} AND p.attempt_id=#{permit.attempt}
                AND p.state='UNKNOWN' AND NOT p.send_started
                AND NOT EXISTS(SELECT 1 FROM evaluation_deletion_marker t WHERE t.run_id=r.run_id)
              RETURNING p.run_id
            ) SELECT EXISTS(SELECT 1 FROM consumed)
            """,
            affectData = true)
    boolean beginSend(@Param("permit") EvaluationPublication.Permit permit);

    /** {@inheritDoc} 回执不倒退；迟到首次回执与清理代次失效同事务，重复回执不重复增加代次。 */
    @Override
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select(
            value =
                    """
            WITH locked AS MATERIALIZED (
              SELECT run_id,expires_at,revoked_at FROM evaluation_retention_run
              WHERE run_id=#{permit.request.run} AND owner_id=#{permit.request.owner}
                AND expires_at=CAST(#{permit.expiresAt} AS TIMESTAMPTZ) FOR UPDATE
            ), changed AS (
              UPDATE evaluation_publication p SET
                state=CASE WHEN l.revoked_at IS NOT NULL OR l.expires_at<=clock_timestamp()
                    OR EXISTS(SELECT 1 FROM evaluation_deletion_marker t WHERE t.run_id=l.run_id)
                  THEN 'BLOCKED' ELSE #{outcome} END,
                evidence_hash=#{evidenceHash},
                late_reported=(l.revoked_at IS NOT NULL OR l.expires_at<=clock_timestamp()
                    OR EXISTS(SELECT 1 FROM evaluation_deletion_marker t WHERE t.run_id=l.run_id))
              FROM locked l WHERE p.run_id=l.run_id AND p.kind=#{permit.request.kind}
                AND p.locator_hash=#{permit.request.locatorHash} AND p.payload_hash=#{permit.request.payloadHash}
                AND p.attempt_id=#{permit.attempt}
                AND p.send_started
                AND (p.state='UNKNOWN' OR (p.state='ACCEPTED' AND #{outcome}='CONFIRMED')
                  OR (NOT p.late_reported AND (l.revoked_at IS NOT NULL OR l.expires_at<=clock_timestamp()
                    OR EXISTS(SELECT 1 FROM evaluation_deletion_marker t WHERE t.run_id=l.run_id))))
              RETURNING p.run_id,p.kind,p.locator_hash,p.state
            ), marker AS (
              INSERT INTO evaluation_deletion_marker(run_id,blocked_at)
              SELECT l.run_id,LEAST(l.expires_at,l.revoked_at) FROM locked l JOIN changed c ON c.run_id=l.run_id
              WHERE c.state='BLOCKED' ON CONFLICT(run_id) DO UPDATE
                SET blocked_at=LEAST(evaluation_deletion_marker.blocked_at,EXCLUDED.blocked_at)
            ), dirtied AS (
              UPDATE evaluation_retention_copy c SET generation=c.generation+1,evidence_hash=NULL,attested_at=NULL
              FROM changed p WHERE c.run_id=p.run_id AND c.kind=p.kind AND c.locator_hash=p.locator_hash AND p.state='BLOCKED'
            ) SELECT state FROM changed
            """,
            affectData = true)
    EvaluationPublication.State acknowledge(
            @Param("permit") EvaluationPublication.Permit permit,
            @Param("outcome") EvaluationPublication.State outcome,
            @Param("evidenceHash") String evidenceHash);

    /** {@inheritDoc} 独立标记无运行外键；重复封存保留最早阻断点，不刷新清理期限。 */
    @Override
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select(
            value =
                    """
            WITH locked AS MATERIALIZED (
              SELECT run_id,expires_at,revoked_at FROM evaluation_retention_run WHERE run_id=#{run} FOR UPDATE
            ), marker AS (
              INSERT INTO evaluation_deletion_marker(run_id,blocked_at)
              SELECT run_id,LEAST(expires_at,revoked_at,
                (SELECT blocked_at FROM evaluation_deletion_marker t WHERE t.run_id=locked.run_id)) FROM locked
              WHERE revoked_at IS NOT NULL OR expires_at<=clock_timestamp()
                OR EXISTS(SELECT 1 FROM evaluation_deletion_marker t WHERE t.run_id=locked.run_id)
              ON CONFLICT(run_id) DO UPDATE SET blocked_at=LEAST(evaluation_deletion_marker.blocked_at,EXCLUDED.blocked_at)
              RETURNING run_id
            ), blocked AS (
              UPDATE evaluation_publication p SET state='BLOCKED' FROM marker m WHERE p.run_id=m.run_id AND p.state<>'BLOCKED'
            ) SELECT EXISTS(SELECT 1 FROM marker) OR EXISTS(SELECT 1 FROM evaluation_deletion_marker WHERE run_id=#{run})
            """,
            affectData = true)
    boolean seal(@Param("run") UUID run);
}
