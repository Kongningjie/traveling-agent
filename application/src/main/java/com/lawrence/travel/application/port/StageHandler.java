package com.lawrence.travel.application.port;

import com.lawrence.travel.application.execution.StageOutput;
import com.lawrence.travel.domain.execution.StageType;

/**
 * 阶段业务处理端口；框架调用在事务外完成，必须遵守预算并支持尽力中断。
 */
public interface StageHandler {
    /**
     * @param type 阶段类型
     * @return 当前处理器是否明确支持
     */
    boolean supports(StageType type);
    /**
     * @param claim 持久输入及隔离尝试
     * @return 尚待权威提交的结果
     * @throws Exception 执行失败，由唯一执行器分类处理，不输出原始异常
     */
    StageOutput execute(ExecutionEngineStore.Claim claim) throws Exception;
}
