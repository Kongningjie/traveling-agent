package com.lawrence.travel.application.port;

import com.lawrence.travel.application.execution.Jobs;
import com.lawrence.travel.domain.execution.BackgroundJob;

/** 已明确装配的作业处理器；未注册类型不能被当作成功完成。 */
public interface JobHandler {
    /** @return 唯一处理类型 */
    BackgroundJob.Kind kind();
    /** @param claim 原资格与参数 @return 产物／后继；运行在数据库事务外 */
    Jobs.Output execute(Jobs.Claim claim);
}
