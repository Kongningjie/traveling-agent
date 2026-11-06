package com.lawrence.travel.application.port;

import com.lawrence.travel.application.execution.Jobs;
import com.lawrence.travel.domain.execution.BackgroundJob;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 短事务作业仓库；处理器在事务外执行，恢复与正常领取走同一入口。 */
public interface JobStore {
    /**
     * 完成用户明确触发的应用短命令，不领取长任务或调用模型。
     * @param jobScope 归属
     * @param jobId 尚未执行的CHAT_INPUT；仅用于用户明确停止等应用短命令
     */
    void completeCommand(Jobs.Scope jobScope, UUID jobId);

    /**
     * 登记稳定作业与执行上下文；相同身份只能重放相同内容。
     * @param submission 稳定输入和归属
     * @return 已保存作业；同键异内容拒绝
     */
    Jobs.View registerBackgroundJob(Jobs.Submit submission);

    /**
     * 读取经过归属检查的作业状态，越权和缺失使用相同失败语义。
     * @param jobScope 私有范围
     * @param jobId 作业
     * @return 本人的状态，跨用户与缺失一致拒绝
     */
    Jobs.View read(Jobs.Scope jobScope, UUID jobId);

    /**
     * 领取新作业或接管过期租约；带排除集合的默认重载仅兼容旧端口，生产实现需覆盖过滤。
     * @param kinds 已装配处理器类型
     * @return 一个新领取或失联接管，预算不重置
     */
    Optional<Jobs.Claim> claim(Set<BackgroundJob.Kind> kinds);

    /**
     * 领取新作业或接管过期租约；带排除集合的默认重载仅兼容旧端口，生产实现需覆盖过滤。
     * @param kinds 已装配类型
     * @param excludedUserIds 尚未实际退出的用户
     * @return 公平发现后的可靠领取，旧替身兼容原入口
     */
    default Optional<Jobs.Claim> claim(
            Set<BackgroundJob.Kind> kinds, java.util.Set<UUID> excludedUserIds) {
        return claim(kinds);
    }

    /**
     * 领取新作业或接管过期租约；带排除集合的默认重载仅兼容旧端口，生产实现需覆盖过滤。
     * @param jobScope 已验证范围
     * @param jobId 精确作业
     * @return 同聊天理解入口的有序领取
     */
    Optional<Jobs.Claim> claim(Jobs.Scope jobScope, UUID jobId);

    /**
     * 核对原作业围栏是否仍允许执行，不以当前最新token替旧调用重新授权。
     * @param fence 原租约
     * @return 是否仍可执行；已取消／过期／资料变化返回false
     */
    boolean isJobExecutionValid(Jobs.Fence fence);

    /**
     * 延长仍有效的原租约，过期或已取消的执行不能被续租复活。
     * @param fence 原租约
     * @return 续租是否成功，不复活已过期租约
     */
    boolean renew(Jobs.Fence fence);

    /**
     * 在一个短事务中提交阶段产物、终态或等待状态及可靠后继，拒绝陈旧写入。
     * @param claim 原领取
     * @param output 原子产物与后继；非法状态或陈旧写入拒绝
     */
    void finish(Jobs.Claim claim, Jobs.Output output);

    /**
     * 处理用户明确的重试或取消，沿用原作业和预算，终态取消保持幂等。
     * @param jobScope 私有范围
     * @param jobId 稳定作业
     * @param retryRequested true仅显式重试等待／暂停；false取消，终态幂等
     */
    void applyJobRetryOrCancellation(Jobs.Scope jobScope, UUID jobId, boolean retryRequested);

    /**
     * 结束已等待用户的作业，只记录明确确认或补充，不重新调用模型。
     * @param jobScope 归属
     * @param jobId 已等待用户的作业；明确确认／补充后终结，不再调用模型
     */
    void acknowledge(Jobs.Scope jobScope, UUID jobId);

    /**
     * 提升资料/删除水位并撤销旧执行资格，不自动删除已生效的业务事实。
     * @param jobScope 私有范围
     * @return 增加删除／资料水位并撤销在途旧资格，不自动删除业务事实
     */
    long invalidate(Jobs.Scope jobScope);
}
