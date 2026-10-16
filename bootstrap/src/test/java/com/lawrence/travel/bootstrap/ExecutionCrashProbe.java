package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.port.ExecutionEngineStore;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.SpringApplication;

/**
 * 仅测试子进程：领取事务提交后硬退出，避免把新建 Java 对象等同于真实进程崩溃。
 */
public final class ExecutionCrashProbe {
    /**
     * @param args 唯一测试运行 UUID；不接受凭据，连接配置仅继承本机测试环境
     */
    public static void main(String[] args) {
        try {
            var app = new SpringApplication(TravelingAgentApplication.class);
            app.setDefaultProperties(Map.of("spring.profiles.active", "local", "server.port", "0", "spring.main.banner-mode", "off", "logging.level.root", "OFF", "travel.engine.enabled", "false"));
            var context = app.run(); var store = context.getBean(ExecutionEngineStore.class); UUID run = UUID.fromString(args[0]);
            var hint = store.candidates(100).stream().filter(h -> h.runId().equals(run)).findFirst().orElseThrow();
            if (store.claim(hint, "crashed_process").isPresent()) Runtime.getRuntime().halt(23);
            Runtime.getRuntime().halt(24);
        } catch (Throwable failure) { Runtime.getRuntime().halt(25); }
    }
}
