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

        /**
         * @return 实际执行归属；兼容旧测试源可为空，不赋予数据库提交权
         */
        default java.util.UUID userId() {
            return null;
    }
    }
    /** @return 已可靠领取的有界工作，空表示当前没有可执行输入 */
    Optional<Runnable> claimWork();

    /**
     * @param excluded 仍占物理容量的用户，包括已取消但尚未退出者
     * @return 持久领取仍须复查；旧源兼容默认入口
     */
    default Optional<Runnable> claimWorkExcluding(java.util.Set<java.util.UUID> excluded) {
        return claimWork();
}
}
