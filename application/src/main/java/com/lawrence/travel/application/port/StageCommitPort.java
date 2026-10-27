package com.lawrence.travel.application.port;

import com.lawrence.travel.application.execution.StageCommitCommand;

/** 阶段提交持久化契约；PostgreSQL 引擎执行实际短事务，接口不允许绕过执行资格。 */
public interface StageCommitPort {
    /** 提交结果不能用布尔成功掩盖过期执行。 */
    enum Result {
        /** 本次原子提交成功。 */
        COMMITTED,
        /** 同一尝试及相同内容已提交，不重复写入。 */
        ALREADY_COMMITTED,
        /** 任务代次、需求或租约已失效，无业务写入。 */
        STALE
    }

    /**
     * 短事务中按任务→运行→阶段→事件锁定并重读所有前置条件；不在事务内调用外部服务。
     * 必须原子保存产物、检查点、可见事件、下一请求及当前完成状态；同键异内容明确冲突。
     * @param command 不可变提交输入；不能仅凭构造时检查假定事务写入安全
     * @return 实际提交／重放／失效结果
     */
    Result commit(StageCommitCommand command);
}
