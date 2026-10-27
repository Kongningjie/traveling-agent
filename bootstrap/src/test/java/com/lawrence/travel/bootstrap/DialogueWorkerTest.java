package com.lawrence.travel.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.lawrence.travel.application.execution.StageExecutor;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.infrastructure.execution.BoundedExecutionResources;
import com.lawrence.travel.infrastructure.execution.PersistentStageWorker;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 同一Worker对话工作和旅行阶段共用物理容量的离线线程验证。 */
class DialogueWorkerTest {
    /** 撤销资格只发出中断，处理器忽略中断时物理容量仍保留到真实退出。 */
    @Test
    void cancelledLeaseDoesNotReleasePhysicalCapacityEarly() throws Exception {
        var engine = mock(ExecutionEngineStore.class);
        var entered = new CountDownLatch(1);
        var exit = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var claimed = new AtomicInteger();
        try (var resources = new BoundedExecutionResources(1, 1);
                var worker =
                        new PersistentStageWorker(
                                engine,
                                new StageExecutor(engine, List.of()),
                                resources,
                                1,
                                Duration.ofMillis(20))) {
            worker.register(
                    () -> {
                        claimed.incrementAndGet();
                        return Optional.of(
                                new com.lawrence.travel.application.port.BackgroundWorkSource
                                        .LeasedWork() {
                                    /** 本例明确撤销租约。 */
                                    @Override
                                    public boolean renew() {
                                        return false;
                                    }

                                    /** 模拟取消后仍有物理请求尚未返回，不能把中断视作完成。 */
                                    @Override
                                    public void run() {
                                        entered.countDown();
                                        while (exit.getCount() > 0) {
                                            try {
                                                exit.await();
                                            } catch (InterruptedException ignored) {
                                                cancelled.countDown();
                                            }
                                        }
                                    }
                                });
                    });
            worker.tick();
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(cancelled.await(3, TimeUnit.SECONDS)).isTrue();
                worker.tick();
                assertThat(claimed.get()).isEqualTo(1);
                assertThatThrownBy(resources::openRun)
                        .isInstanceOf(RejectedExecutionException.class);
            } finally {
                exit.countDown();
            }
            worker.close();
            try (var slot = resources.openRun()) {
                assertThat(slot).isNotNull();
            }
        }
    }

    /** 解析有独立单槽，与模型运行不互借；仍使用同一个Worker循环。 */
    @Test
    void parserUsesOneSeparatePhysicalSlot() throws Exception {
        var engine = mock(ExecutionEngineStore.class);
        var entered = new CountDownLatch(1);
        var exit = new CountDownLatch(1);
        var claimed = new AtomicInteger();
        try (var resources = new BoundedExecutionResources(1, 1);
                var worker =
                        new PersistentStageWorker(
                                engine,
                                new StageExecutor(engine, List.of()),
                                resources,
                                1,
                                Duration.ofSeconds(1))) {
            worker.register(
                    new com.lawrence.travel.application.port.BackgroundWorkSource() {
                        /** 解析源获得独立物理槽。 */
                        @Override
                        public boolean parserCapacity() {
                            return true;
                        }

                        /** 固定本地工作，不访问任何文件或供应商。 */
                        @Override
                        public Optional<Runnable> claimWork() {
                            claimed.incrementAndGet();
                            return Optional.of(
                                    () -> {
                                        entered.countDown();
                                        try {
                                            exit.await();
                                        } catch (InterruptedException ignored) {
                                            Thread.currentThread().interrupt();
                                        }
                                    });
                        }
                    });
            worker.tick();
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                worker.tick();
                assertThat(claimed.get()).isEqualTo(1);
                try (var model = resources.openRun()) {
                    assertThat(model).isNotNull();
                }
            } finally {
                exit.countDown();
            }
        }
    }

    /** 对话真实退出前不能再领取或释放容量，关闭后才可回收。 */
    @Test
    void dialogueUsesExistingWorkerCapacityUntilActualExit() throws Exception {
        var engine = mock(ExecutionEngineStore.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var claimed = new AtomicInteger();
        try (var resources = new BoundedExecutionResources(1, 1);
                var worker =
                        new PersistentStageWorker(
                                engine,
                                new StageExecutor(engine, List.of()),
                                resources,
                                1,
                                Duration.ofSeconds(1))) {
            worker.register(
                    () -> {
                        claimed.incrementAndGet();
                        return Optional.of(
                                () -> {
                                    entered.countDown();
                                    try {
                                        release.await(5, TimeUnit.SECONDS);
                                    } catch (InterruptedException interrupted) {
                                        Thread.currentThread().interrupt();
                                    }
                                });
                    });
            worker.tick();
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                worker.tick();
                assertThat(claimed.get()).isEqualTo(1);
                assertThatThrownBy(resources::openRun)
                        .isInstanceOf(RejectedExecutionException.class);
            } finally {
                release.countDown();
            }
            worker.close();
            try (var slot = resources.openRun()) {
                assertThat(slot).isNotNull();
            }
        }
    }

    /**
     * 已取消但未物理退出的用户不占用第二个模型槽；其他用户仍可公平执行。
     * @throws Exception 有界线程信号失败
     */
    @Test
    void cancelledUserCannotTakeSecondSlot() throws Exception {
        var engine = mock(ExecutionEngineStore.class);
        var entered = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var exit = new CountDownLatch(1);
        var otherEntered = new CountDownLatch(1);
        var user = java.util.UUID.randomUUID();
        var other = java.util.UUID.randomUUID();
        var calls = new AtomicInteger();
        try (var resources = new BoundedExecutionResources(2, 1);
                var worker =
                        new PersistentStageWorker(
                                engine,
                                new StageExecutor(engine, List.of()),
                                resources,
                                1,
                                Duration.ofMillis(20))) {
            worker.register(
                    new com.lawrence.travel.application.port.BackgroundWorkSource() {
                        /** 所有领取必须使用排除用户的端口。 */
                        @Override
                        public Optional<Runnable> claimWork() {
                            throw new AssertionError("EXCLUSIONS_REQUIRED");
                        }

                        /** {@inheritDoc} 合成领取器模拟用户队列，持有者未退出时必须收到排除集。 */
                        @Override
                        public Optional<Runnable> claimWorkExcluding(
                                java.util.Set<java.util.UUID> excluded) {
                            int count = calls.incrementAndGet();
                            if (count > 2) return Optional.empty();
                            if (count == 2) assertThat(excluded).contains(user);
                            return Optional.of(
                                    new LeasedWork() {
                                        /** {@inheritDoc} 第二个工作属于其他用户。 */
                                        @Override
                                        public java.util.UUID userId() {
                                            return count == 1 ? user : other;
                                        }

                                        /** {@inheritDoc} 第一工作失去逻辑资格但模拟底层调用尚未退出。 */
                                        @Override
                                        public boolean renew() {
                                            return count != 1;
                                        }

                                        /** 只等待本机信号，不执行模型调用。 */
                                        @Override
                                        public void run() {
                                            if (count == 2) {
                                                otherEntered.countDown();
                                                return;
                                            }
                                            entered.countDown();
                                            while (exit.getCount() > 0)
                                                try {
                                                    exit.await();
                                                } catch (InterruptedException ignored) {
                                                    cancelled.countDown();
                                                }
                                        }
                                    });
                        }
                    });
            worker.tick();
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(cancelled.await(3, TimeUnit.SECONDS)).isTrue();
                worker.tick();
                assertThat(otherEntered.await(3, TimeUnit.SECONDS)).isTrue();
            } finally {
                exit.countDown();
            }
        }
    }
}
