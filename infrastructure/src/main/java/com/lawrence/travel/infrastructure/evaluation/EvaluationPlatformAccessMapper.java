package com.lawrence.travel.infrastructure.evaluation;

import com.lawrence.travel.application.evaluation.EvaluationPlatformAccess;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** PG访问资格投影；不自动核验存储或启用采集，到期判断不等待清理线程。 */
public interface EvaluationPlatformAccessMapper extends EvaluationPlatformAccess.Store {
    /** {@inheritDoc} 一秒短资格受最早到期/撤销时间截断，不复用SqlSession缓存。 */
    @Override
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select(
            """
            SELECT a.verified,
              LEAST(clock_timestamp()+INTERVAL '1 second',
                (SELECT MIN(LEAST(expires_at,revoked_at)) FROM evaluation_retention_run)) AS not_after
            FROM evaluation_platform_access a WHERE a.singleton=true
            """)
    EvaluationPlatformAccess.Decision decision();

    /** {@inheritDoc} 恢复或重启必须从关闭状态开始，不沿用备份中的开放标志。 */
    @Override
    @Update("UPDATE evaluation_platform_access SET verified=false WHERE singleton=true")
    void closeAccess();
}
