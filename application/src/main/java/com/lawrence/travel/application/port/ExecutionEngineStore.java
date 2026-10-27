package com.lawrence.travel.application.port;

import com.lawrence.travel.application.execution.StageCommitCommand;
import com.lawrence.travel.application.execution.StageOutput;
import com.lawrence.travel.application.execution.StagePlan;
import com.lawrence.travel.domain.execution.ExecutionFence;
import com.lawrence.travel.domain.execution.ExecutionRun;
import com.lawrence.travel.domain.execution.ExecutionStep;
import com.lawrence.travel.domain.interaction.DecisionAnswer;
import com.lawrence.travel.domain.travel.TaskScope;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * PostgreSQL 唯一执行调度入口；无锁候选不是领取资格，所有变更事务内重新判断。
 */
public interface ExecutionEngineStore extends StageCommitPort {
    /**
     * @param claim 已领取归属
     * @return 持久化链路；兼容离线实现
     */
    default com.lawrence.travel.application.execution.ExecutionCorrelation.Ids correlation(
            Claim claim) {
        return new com.lawrence.travel.application.execution.ExecutionCorrelation.Ids(
                claim.fence().runId(), claim.fence().runId());
    }

    /**
     * 用例明确选择的运行目标；模型不能将规划自行改为咨询完成。
     */
    enum Goal {
        /**
         * 规划，必须后续用户接受。
         */
        PLANNING,
        /**
         * 纯咨询，无行程提交。
         */
        CONSULTATION
    }

    /**
     * 调度提示，不携带执行资格。
     * @param scope 归属
     * @param runId 运行
     * @param stepId 阶段
     * @param eventId 事件
     * @param type 阶段类型
     */
    record Hint(
            TaskScope scope,
            UUID runId,
            UUID stepId,
            UUID eventId,
            com.lawrence.travel.domain.execution.StageType type) {}

    /**
     * 已领取阶段。
     * @param fence 权威领取副本
     * @param step 阶段
     * @param input 持久输入 JSON
     */
    record Claim(ExecutionFence fence, ExecutionStep step, String input) {}

    /**
     * 失败处理方式，错误消息不包含供应商内容。
     */
    enum Failure {
        /**
         * 有界重试。
         */
        RETRYABLE,
        /**
         * 必要分支失败。
         */
        PERMANENT,
        /**
         * 预算不足。
         */
        BUDGET,
        /**
         * 免费条件不满足。
         */
        QUOTA
    }

    /**
     * @param scope 归属
     * @param inputId 幂等启动输入
     * @param first 首个阶段
     * @return 同事务建立运行、预算和事件
     */
    default ExecutionRun start(TaskScope scope, UUID inputId, StagePlan first) {
        return start(scope, inputId, first, Goal.PLANNING);
    }

    /**
     * @param scope 归属
     * @param inputId 请求
     * @param first 首阶段
     * @param goal 应用确定目标
     * @return 可靠登记运行
     */
    ExecutionRun start(TaskScope scope, UUID inputId, StagePlan first, Goal goal);

    /**
     * @param scope 归属
     * @param runId 运行
     * @return 权威预算及状态，越权统一拒绝
     */
    ExecutionRun run(TaskScope scope, UUID runId);

    /**
     * @param limit 1～100
     * @return 到期候选提示，不取得锁
     */
    List<Hint> candidates(int limit);

    /**
     * @param hint 不可信旧提示
     * @param owner 当前执行实例
     * @return 受控领取或竞争失败
     */
    Optional<Claim> claim(Hint hint, String owner);

    /**
     * @param fence 当前尝试
     * @return 续租及预算检查是否允许继续；不接受过期执行权
     */
    boolean renew(ExecutionFence fence);

    /**
     * @param claim 已领取尝试
     * @param output 阶段产物
     * @return 已落库不可变正文引用；不表示阶段完成
     */
    StageCommitCommand prepare(Claim claim, StageOutput output);

    /**
     * @param fence 本次尝试
     * @param failure 固定处理分类；重复或迟到不推进业务
     */
    void fail(ExecutionFence fence, Failure failure);

    /**
     * @param scope 归属
     * @param runId 取消目标
     * @return 先提交终态和代次；重复取消幂等
     */
    ExecutionRun cancel(TaskScope scope, UUID runId);

    /**
     * @param scope 已更新任务
     * @return 已取代运行，用于提交后的尽力取消
     */
    List<UUID> supersede(TaskScope scope);

    /**
     * @param scope 归属
     * @param oldRunId 原运行
     * @param inputId 显式继续请求
     * @param first 新运行首阶段
     * @return 唯一直接后继
     */
    ExecutionRun continueRun(TaskScope scope, UUID oldRunId, UUID inputId, StagePlan first);

    /**
     * @param scope 归属
     * @param runId 原运行
     * @param answer 已解释并经应用确认的回答
     * @param resolved 解决问题
     * @param next 恢复阶段
     * @return 原预算运行；本端口不解析自由文本或替代业务冲突检查
     */
    default ExecutionRun resume(
            TaskScope scope,
            UUID runId,
            DecisionAnswer answer,
            Set<UUID> resolved,
            StagePlan next) {
        return resume(scope, runId, answer, resolved, next, null);
    }

    /**
     * @param scope 归属
     * @param runId 原运行
     * @param answer 已解释确认的回答
     * @param resolved 解决问题
     * @param next 完成回答后的执行请求
     * @param requirements 已通过应用冲突校验的全量需求，可空
     * @return 原代次与原预算运行，需求追加和回答应用同事务
     */
    ExecutionRun resume(
            TaskScope scope,
            UUID runId,
            DecisionAnswer answer,
            Set<UUID> resolved,
            StagePlan next,
            TravelStore.RevisionContent requirements);

    /**
     * @param scope 归属
     * @param runId 原运行
     * @param confirmed 受信应用核实免费条件
     * @return 原预算恢复，不隐式创建新预算
     */
    ExecutionRun resumeQuota(TaskScope scope, UUID runId, boolean confirmed);

    /**
     * @param scope 归属
     * @param runId 原运行
     * @param cause 接受请求身份
     * @param next 重校验阶段；沿用原预算，不复用旧接受意图
     */
    void revalidateCandidate(TaskScope scope, UUID runId, UUID cause, StagePlan next);

    /**
     * @param scope 归属
     * @param runId 原等待运行
     * @param input 原始回答
     * @param next 解释阶段，不将接收等同于采纳
     */
    void interpretAnswer(TaskScope scope, UUID runId, UUID input, StagePlan next);

    /**
     * @param limit 恢复批量上限
     * @return 本次实际撤销的失联租约数；通过相同领取流程重试
     */
    int recover(int limit);
}
