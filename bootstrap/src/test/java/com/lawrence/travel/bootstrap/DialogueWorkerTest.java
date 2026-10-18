package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.execution.StageExecutor;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.infrastructure.execution.BoundedExecutionResources;
import com.lawrence.travel.infrastructure.execution.PersistentStageWorker;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 同一Worker对话工作和旅行阶段共用物理容量的离线线程验证。 */
class DialogueWorkerTest {
    /** 对话真实退出前不能再领取或释放容量，关闭后才可回收。 */
    @Test void dialogueUsesExistingWorkerCapacityUntilActualExit() throws Exception {
        var engine = mock(ExecutionEngineStore.class); var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var claimed = new AtomicInteger();
        try (var resources = new BoundedExecutionResources(1, 1); var worker = new PersistentStageWorker(engine, new StageExecutor(engine, List.of()), resources, 1, Duration.ofSeconds(1))) {
            worker.register(() -> {
                claimed.incrementAndGet(); return Optional.of(() -> {
                    entered.countDown();
                    try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                });
            });
            worker.tick();
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue(); worker.tick(); assertThat(claimed.get()).isEqualTo(1);
                assertThatThrownBy(resources::openRun).isInstanceOf(RejectedExecutionException.class);
            } finally { release.countDown(); }
            worker.close(); try (var slot = resources.openRun()) { assertThat(slot).isNotNull(); }
        }
    }
}
