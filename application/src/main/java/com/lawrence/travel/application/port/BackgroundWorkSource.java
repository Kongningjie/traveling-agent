package com.lawrence.travel.application.port;

import java.util.Optional;

/** 无旅行任务的持久工作通过现有Worker轮询和物理容量执行，不启动另一套定时器。 */
public interface BackgroundWorkSource {
    /** @return 解析作业使用独立有界物理槽，其他工作共享既有运行容量 */
    default boolean parserCapacity() { return false; }
    /** 带租约的工作由唯一Worker现有心跳器续租；失效只能中断，实际退出才释放槽。 */
    interface LeasedWork extends Runnable {
        /** @return 是否仍具有提交资格；数据库不可用应返回false或抛异常 */
        boolean renew();
    }
    /** @return 已可靠领取的有界工作，空表示当前没有可执行输入 */
    Optional<Runnable> claimWork();
}
