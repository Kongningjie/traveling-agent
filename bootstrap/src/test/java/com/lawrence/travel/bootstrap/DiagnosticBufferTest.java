package com.lawrence.travel.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.SupportStore;
import com.lawrence.travel.application.support.DiagnosticBuffer;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.Supplier;

/** 数据库故障降级有界且不会吞掉内存；替身不冒充PG事务验证。 */
class DiagnosticBufferTest {
    /** 上限128、最多3次补写，结束后丢弃可观测，内存中没有用户正文。 */
    @Test
    void boundedAndFinite() {
        var store = mock(SupportStore.class);
        BusinessTransactions tx =
                new BusinessTransactions() {
                    /** {@inheritDoc} 本例只验证队列边界。 */
                    public <T> T required(Supplier<T> action) {
                        return action.get();
                    }

                    /** {@inheritDoc} 同步测试回调。 */
                    public void afterCommit(Runnable action) {
                        action.run();
                    }
                };
        when(store.diagnostic(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("FIXTURE_DOWN"));
        var b = new DiagnosticBuffer(store, tx);
        for (int i = 0; i < 150; i++) b.failed(UUID.randomUUID());
        assertThat(b.health().rows()).isEqualTo(128);
        assertThat(b.health().dropped()).isEqualTo(22);
        for (int i = 0; i < 50; i++) b.flush();
        assertThat(b.health().rows()).isZero();
        assertThat(b.health().dropped()).isEqualTo(150);
        assertThat(b.health().failures()).isEqualTo(150);
    }
}
