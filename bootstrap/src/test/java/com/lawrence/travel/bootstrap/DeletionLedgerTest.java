package com.lawrence.travel.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lawrence.travel.application.lifecycle.Lifecycle;
import com.lawrence.travel.infrastructure.lifecycle.FileDeletionLedger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** 最小删除清单的完整性、断点重试与缺失失败，不保存真实正文。 */
class DeletionLedgerTest {
    @TempDir Path root;

    /**
     * PG标记初始化后缺失独立头不得重新生成；已匹配的最新条目损坏也不能跳过验证。
     * @throws Exception 合成文件删除失败
     */
    @Test
    void missingLatestFailsClosed() throws Exception {
        var ledger = new FileDeletionLedger(root);
        var first = ledger.initialize(UUID.randomUUID());
        var next =
                ledger.append(
                        first,
                        new Lifecycle.Entry(
                                1,
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                Lifecycle.Kind.CHAT,
                                UUID.randomUUID(),
                                null,
                                null,
                                java.time.Instant.now()));
        var store =
                org.mockito.Mockito.mock(com.lawrence.travel.application.port.LifecycleStore.class);
        org.mockito.Mockito.when(store.head()).thenReturn(next);
        org.mockito.Mockito.when(store.initialized()).thenReturn(true);
        var tx =
                new com.lawrence.travel.application.port.BusinessTransactions() {
                    /** {@inheritDoc} 这里只验证文件恢复屏障，不将替身事务宣称为PG一致性验证。 */
                    @Override
                    public <T> T required(java.util.function.Supplier<T> action) {
                        return action.get();
                    }

                    /** {@inheritDoc} 本文件屏障用例没有业务取消，若调用则同步执行。 */
                    @Override
                    public void afterCommit(Runnable action) {
                        action.run();
                    }
                };
        var service =
                new com.lawrence.travel.application.lifecycle.LifecycleService(
                        store, tx, ledger, null, null, null, null, java.time.Clock.systemUTC());
        Files.delete(root.resolve("00000000000000000001.del"));
        assertThatThrownBy(service::recover).hasMessage("DELETION_LEDGER_UNAVAILABLE");
        assertThat(service.ready()).isFalse();
        Files.delete(root.resolve("head"));
        assertThatThrownBy(service::recover).hasMessage("LATEST_DELETIONS_MISSING");
        assertThat(root.resolve("head")).doesNotExist();
    }

    /** 条目落盘后头未推进可原样重试；损坏链条必须失败关闭。 */
    @Test
    void durableReplayAndIntegrity() throws Exception {
        var ledger = new FileDeletionLedger(root);
        var head = ledger.initialize(UUID.randomUUID());
        var e =
                new Lifecycle.Entry(
                        1,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        Lifecycle.Kind.CHAT,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        java.time.Instant.parse("2026-10-19T00:00:00Z"));
        var next = ledger.append(head, e);
        assertThat(ledger.read(1, head.hash())).isEqualTo(e);
        assertThat(ledger.head()).isEqualTo(next);
        Files.writeString(root.resolve("head"), "v1\t" + head.instance() + "\t0\t" + head.hash());
        assertThat(ledger.head()).isEqualTo(next);
        assertThat(ledger.read(1, head.hash())).isEqualTo(e);
        Files.writeString(root.resolve("00000000000000000001.del"), "损坏的合成记录");
        assertThatThrownBy(() -> ledger.read(1, head.hash()))
                .hasMessage("DELETION_LEDGER_UNAVAILABLE")
                .hasNoCause();
    }
}
