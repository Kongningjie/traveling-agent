package com.lawrence.travel.application.port;

import com.lawrence.travel.application.execution.StageOutput;
import com.lawrence.travel.domain.execution.ExecutionFence;
import com.lawrence.travel.domain.execution.StageType;
import java.util.UUID;

/** 阶段事务内的业务产物投影；仅引擎通过 fence 后调用，不能凭模型自报角色写报告。 */
public interface StageProjection {
    /**
     * @param candidateId 可接受候选或空
     * @param constraintRevision 已保存的新需求修订或空
     * @param replacementInput 需取代旧运行的新修改输入或空
     */
    record Result(UUID candidateId, Long constraintRevision, UUID replacementInput) {}

    /**
     * @param fence 归属
     * @param output 不可变产物；需要偏好锁时必须在任务锁之前取得
     */
    void beforeTaskLock(ExecutionFence fence, StageOutput output);

    /**
     * @param fence 已通过引擎检查的资格
     * @param type 权威阶段类型
     * @param output 产物
     * @return 候选门禁结果、已保存的新需求修订或明确新修改的替代输入；无本类产物时为空
     */
    Result project(ExecutionFence fence, StageType type, StageOutput output);
}
