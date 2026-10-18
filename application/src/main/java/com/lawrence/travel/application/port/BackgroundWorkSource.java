package com.lawrence.travel.application.port;

import java.util.Optional;

/** 无旅行任务的持久工作通过现有Worker轮询和物理容量执行，不启动另一套定时器。 */
public interface BackgroundWorkSource {
    /** @return 已可靠领取的有界工作，空表示当前没有可执行输入 */
    Optional<Runnable> claimWork();
}
