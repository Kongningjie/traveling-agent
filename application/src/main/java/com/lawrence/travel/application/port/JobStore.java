package com.lawrence.travel.application.port;

import com.lawrence.travel.application.execution.Jobs;
import com.lawrence.travel.domain.execution.BackgroundJob;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 短事务作业仓库；处理器在事务外执行，恢复与正常领取走同一入口。 */
public interface JobStore {
    /** @param scope 归属 @param id 尚未执行的CHAT_INPUT；仅用于用户明确停止等应用短命令 */
    void completeCommand(Jobs.Scope scope,UUID id);
    /** @param input 稳定输入和归属 @return 已保存作业；同键异内容拒绝 */
    Jobs.View submit(Jobs.Submit input);
    /** @param scope 私有范围 @param id 作业 @return 本人的状态，跨用户与缺失一致拒绝 */
    Jobs.View read(Jobs.Scope scope, UUID id);
    /** @param kinds 已装配处理器类型 @return 一个新领取或失联接管，预算不重置 */
    Optional<Jobs.Claim> claim(Set<BackgroundJob.Kind> kinds);
    /** @param scope 已验证范围 @param id 精确作业 @return 同聊天理解入口的有序领取 */
    Optional<Jobs.Claim> claim(Jobs.Scope scope, UUID id);
    /** @param fence 原租约 @return 是否仍可执行；已取消／过期／资料变化返回false */
    boolean live(Jobs.Fence fence);
    /** @param fence 原租约 @return 续租是否成功，不复活已过期租约 */
    boolean renew(Jobs.Fence fence);
    /** @param claim 原领取 @param output 原子产物与后继；非法状态或陈旧写入拒绝 */
    void finish(Jobs.Claim claim, Jobs.Output output);
    /** @param scope 私有范围 @param id 稳定作业 @param retry true仅显式重试等待／暂停；false取消，终态幂等 */
    void control(Jobs.Scope scope, UUID id, boolean retry);
    /** @param scope 归属 @param id 已等待用户的作业；明确确认／补充后终结，不再调用模型 */
    void acknowledge(Jobs.Scope scope, UUID id);
    /** @param scope 私有范围 @return 增加删除／资料水位并撤销在途旧资格，不自动删除业务事实 */
    long invalidate(Jobs.Scope scope);
}
