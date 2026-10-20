package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.lifecycle.Lifecycle;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.DeletionLedger;
import com.lawrence.travel.application.port.LifecycleStore;

import org.springframework.boot.SpringApplication;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 仅测试子进程：模拟删除清单已fsync、PG事务尚未提交时进程被硬终止。 */
public final class DeletionCrashProbe {
    /**
     * @param args 仅合成用户及聊天ID；凭据只继承本机测试环境，标准输出全部丢弃
     */
    public static void main(String[] args) {
        try {
            var app = new SpringApplication(TravelingAgentApplication.class);
            app.setDefaultProperties(
                    Map.of("spring.main.banner-mode", "off", "logging.level.root", "OFF"));
            var context =
                    app.run(
                            "--spring.profiles.active=local",
                            "--server.port=0",
                            "--travel.engine.enabled=false",
                            "--travel.external-calls-enabled=false",
                            "--travel.lifecycle.maintenance-enabled=false",
                            "--travel.documents.cleanup-enabled=false");
            var store = context.getBean(LifecycleStore.class);
            var ledger = context.getBean(DeletionLedger.class);
            context.getBean(BusinessTransactions.class)
                    .required(
                            () -> {
                                var p =
                                        store.preview(
                                                UUID.fromString(args[0]),
                                                Lifecycle.Kind.CHAT,
                                                UUID.fromString(args[1]));
                                var h = store.head();
                                ledger.append(
                                        h,
                                        new Lifecycle.Entry(
                                                h.sequence() + 1,
                                                UUID.randomUUID(),
                                                UUID.fromString(args[0]),
                                                Lifecycle.Kind.CHAT,
                                                UUID.fromString(args[1]),
                                                p.chat(),
                                                p.task(),
                                                Instant.now()));
                                // 故障窗口严格位于独立清单落盘之后、PG正文删除及水位提交之前。
                                Runtime.getRuntime().halt(37);
                                return null;
                            });
        } catch (Throwable failure) {
            Runtime.getRuntime().halt(38);
        }
    }
}
