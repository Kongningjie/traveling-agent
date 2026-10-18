package com.lawrence.travel.bootstrap;

import com.lawrence.travel.infrastructure.execution.BoundedExecutionResources;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 纯离线并发实验，验证真实线程资源而非数据库状态或业务恢复。 */
class ExecutionResourcesTest {
    /** 并行工作显式复制因果身份，退出后不污染调用线程的上下文。 */
    @Test void researchBranchPropagatesCorrelationAndParentSpan() throws Exception {
        var ids=new com.lawrence.travel.application.execution.ExecutionCorrelation.Ids(java.util.UUID.randomUUID(),java.util.UUID.randomUUID()); var parent=java.util.UUID.randomUUID();
        try(var resources=new BoundedExecutionResources(1,1); var scope=resources.openRun();
            var correlation=com.lawrence.travel.application.execution.ExecutionCorrelation.bind(ids);
            var span=com.lawrence.travel.application.execution.ExecutionCorrelation.bindSpan(parent)) {
            var result=scope.forkResearch(() -> java.util.List.of(com.lawrence.travel.application.execution.ExecutionCorrelation.current().traceId(),com.lawrence.travel.application.execution.ExecutionCorrelation.parentSpan()));
            assertThat(result.toCompletableFuture().get(3,TimeUnit.SECONDS)).containsExactly(ids.traceId(),parent);
        }
        assertThat(com.lawrence.travel.application.execution.ExecutionCorrelation.parentSpan()).isNull();
    }
    /** 两运行、每运行两调查均有界；父流程仅组合 Future，不占用调查执行线程等待。 */
    @Test
    void composesTwoParentsAndFourChildrenWithoutStarvation() throws Exception {
        try (var resources = new BoundedExecutionResources(2, 2);
             var first = resources.openRun(); var second = resources.openRun()) {
            var entered = new CountDownLatch(4);
            var release = new CountDownLatch(1);
            var a = first.forkResearch(() -> await(entered, release));
            var b = first.forkResearch(() -> await(entered, release));
            var c = second.forkResearch(() -> await(entered, release));
            var d = second.forkResearch(() -> await(entered, release));
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(resources::openRun).isInstanceOf(RejectedExecutionException.class);
                assertThatThrownBy(() -> first.forkResearch(() -> 9)).isInstanceOf(RejectedExecutionException.class);
            } finally { release.countDown(); }
            var parentOne = a.thenCombine(b, Integer::sum);
            var parentTwo = c.thenCombine(d, Integer::sum);
            assertThat(parentOne.thenCombine(parentTwo, Integer::sum).toCompletableFuture()
                    .get(3, TimeUnit.SECONDS)).isEqualTo(4);
        }
    }

    /** 范围关闭或返回视图取消均不能提前释放尚在执行的工作；实际退出后才可接管。 */
    @Test
    void retainsCapacityUntilActualExitAndReleasesAfterFailure() throws Exception {
        try (var resources = new BoundedExecutionResources(1, 1)) {
            var scope = resources.openRun();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var stage = scope.forkResearch(() -> await(entered, release));
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                stage.toCompletableFuture().cancel(true);
                scope.close();
                scope.close();
                assertThatThrownBy(resources::openRun).isInstanceOf(RejectedExecutionException.class);
                assertThatThrownBy(() -> scope.forkResearch(() -> 0)).isInstanceOf(RejectedExecutionException.class);
            } finally { release.countDown(); }
            assertThat(stage.toCompletableFuture().get(3, TimeUnit.SECONDS)).isEqualTo(1);
            try (var next = resources.openRun()) {
                var failed = next.forkResearch(() -> { throw new IllegalStateException("TEST_FAILURE"); });
                assertThatThrownBy(() -> failed.toCompletableFuture().get(3, TimeUnit.SECONDS))
                        .rootCause().hasMessage("TEST_FAILURE");
                assertThat(next.forkResearch(() -> 7).toCompletableFuture().get(3, TimeUnit.SECONDS)).isEqualTo(7);
            }
        }
    }

    /** 停止中断实际工作并拒绝新任务，关闭可重复调用。 */
    @Test
    void shutdownInterruptsWorkAndRejectsFurtherAdmission() throws Exception {
        var resources = new BoundedExecutionResources(1, 1);
        var scope = resources.openRun();
        var entered = new CountDownLatch(1);
        var blocked = scope.forkResearch(() -> await(entered, new CountDownLatch(1)));
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            resources.close();
            resources.close();
            assertThatThrownBy(resources::openRun).isInstanceOf(RejectedExecutionException.class);
            assertThatThrownBy(() -> blocked.toCompletableFuture().get(3, TimeUnit.SECONDS))
                    .rootCause().hasMessage("TEST_INTERRUPTED");
        } finally { resources.close(); scope.close(); }
    }

    /**
     * 阻塞一个真实虚拟线程，超时避免失败测试遗留永不退出的工作。
     * @param entered 启动计数
     * @param release 测试控制的释放信号
     * @return 固定分支值
     */
    private static int await(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("TEST_TIMEOUT");
            return 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TEST_INTERRUPTED");
        }
    }
}
