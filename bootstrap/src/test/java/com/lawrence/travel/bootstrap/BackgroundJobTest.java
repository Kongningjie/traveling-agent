package com.lawrence.travel.bootstrap;

import com.lawrence.travel.domain.execution.BackgroundJob;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 状态机确定性断言，不让模型决定终态复活或重试资格。 */
class BackgroundJobTest {
    /** 终态不能被普通重试复活。 */
    @Test void terminalJobsCannotReturnToQueue() {
        for(var state:BackgroundJob.State.values()) if(BackgroundJob.terminal(state)) assertThat(BackgroundJob.allows(state,BackgroundJob.State.QUEUED)).isFalse();
    }
    /** 暂停与用户等待只有显式继续后回到队列。 */
    @Test void waitRequiresExplicitQueueTransition() {
        for(var state:java.util.List.of(BackgroundJob.State.PAUSED,BackgroundJob.State.WAITING_USER)) {
            assertThat(BackgroundJob.allows(state,BackgroundJob.State.QUEUED)).isTrue(); assertThat(BackgroundJob.allows(state,BackgroundJob.State.RUNNING)).isFalse();
        }
    }
}
