package com.lawrence.travel.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.lawrence.travel.application.document.SourceReadService;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.SupportStore;
import com.lawrence.travel.application.safety.SafetyGate;
import com.lawrence.travel.application.support.DiagnosticBuffer;
import com.lawrence.travel.application.support.Support;
import com.lawrence.travel.application.support.SupportService;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.Supplier;

/** PG不可用的故障注入，不以替身代替集成测试中的事务断言。 */
class SupportServiceTest {
    /** 数据库依赖失败只返回固定未保存错误，并排入固定ID诊断队列，不复制反馈说明。 */
    @Test
    void unavailableIsNotSaved() {
        var store = mock(SupportStore.class);
        var user = UUID.randomUUID();
        var id = UUID.randomUUID();
        var tx =
                new BusinessTransactions() {
                    /** {@inheritDoc} 故障注入专用，不代表真实事务。 */
                    public <T> T required(Supplier<T> work) {
                        throw new IllegalStateException("SYNTHETIC_PG_UNAVAILABLE");
                    }

                    /** {@inheritDoc} 本例不执行回调。 */
                    public void afterCommit(Runnable work) {}
                };
        var buffer = new DiagnosticBuffer(store, tx);
        var service =
                new SupportService(
                        store,
                        tx,
                        mock(InteractionCodec.class),
                        SafetyGate.local(),
                        mock(SourceReadService.class),
                        buffer);
        var in =
                new Support.Input(
                        id,
                        new Support.Target(Support.Kind.REPLY, id, id),
                        Support.Category.OTHER,
                        "合成反馈",
                        false,
                        null);
        assertThatThrownBy(() -> service.create(user, in))
                .hasMessage("SUPPORT_UNAVAILABLE")
                .hasNoCause();
        assertThat(buffer.health().rows()).isEqualTo(1);
        assertThat(buffer.health().failures()).isEqualTo(1);
    }
}
