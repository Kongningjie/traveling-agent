package com.lawrence.travel.application.port;

import com.lawrence.travel.application.event.RunEvents;
import com.lawrence.travel.domain.travel.TaskScope;

/** 业务事件权威读取；所有查询均按认证用户及任务限定。 */
public interface RunEventStore {
    /**
     * @param scope 私有归属
     * @return 同一一致性视图内的任务、快照和事件游标
     */
    RunEvents.View view(TaskScope scope);

    /**
     * @param scope 私有归属
     * @param after 已确认水位
     * @param limit 有界页长
     * @return 只含已提交事件的升序页；未来游标拒绝
     */
    RunEvents.Page page(TaskScope scope, long after, int limit);
}
