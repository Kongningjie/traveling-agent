package com.lawrence.travel.bootstrap;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** 随机测试用户的二期数据清理；不触及非测试用户，不清历史真实调用。 */
final class JobTestCleanup {
    /** 工具类型。 */
    private JobTestCleanup() {}

    /**
     * @param jdbc 同一测试事务
     * @param user 本例随机用户；调用方已先删除输入，预算另行清理
     */
    static void remove(JdbcTemplate jdbc, UUID user) {
        jdbc.update(
                "DELETE FROM problem_event WHERE trace_id IN(SELECT trace_id FROM operation_context WHERE user_id=?)",
                user);
        for (String table :
                java.util.List.of(
                        "call_ownership",
                        "user_daily_quota",
                        "execution_trace_span",
                        "problem_event"))
            jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
        jdbc.update(
                "DELETE FROM job_attempt WHERE job_id IN(SELECT id FROM background_job WHERE user_id=?)",
                user);
        jdbc.update("UPDATE background_job SET dependency_id=NULL WHERE user_id=?", user);
        jdbc.update("DELETE FROM background_job WHERE user_id=?", user);
        jdbc.update("DELETE FROM operation_context WHERE user_id=?", user);
    }
}
