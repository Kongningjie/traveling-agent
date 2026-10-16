package com.lawrence.travel.api.system;

import com.lawrence.travel.application.execution.ExecutionProbe;
import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** smoke 专用有限 SSE 探针；不接收用户输入、不支持业务事件补读，不在 local 装配。 */
@Profile("smoke")
@RestController
public final class ProbeStreamController {
    private final ExecutionProbe probe;
    private final Semaphore subscribers = new Semaphore(2);

    /**
     * 绑定离线任务应用服务，Controller 不拥有后台执行生命周期。
     * @param probe 不含供应商调用的诊断用例
     */
    public ProbeStreamController(ExecutionProbe probe) { this.probe = probe; }

    /**
     * 最多两条诊断连接，五秒超时；断线或发送失败仅结束订阅。
     * @return 禁止缓存与代理缓冲的固定 started/completed 事件流
     * @throws IOException 初始响应写入失败；后台任务继续运行
     */
    @GetMapping(value = "/api/v1/system/probe-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events() throws IOException {
        if (!subscribers.tryAcquire()) return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        var emitter = new SseEmitter(5000L);
        var released = new AtomicBoolean();
        Runnable detach = () -> {
            if (released.compareAndSet(false, true)) subscribers.release();
        };
        emitter.onCompletion(detach);
        emitter.onTimeout(() -> { detach.run(); emitter.complete(); });
        emitter.onError(failure -> detach.run());
        try {
            var execution = probe.start();
            emitter.send(SseEmitter.event().name("started").data("OFFLINE_PROBE_STARTED"));
            execution.whenComplete((value, failure) -> {
                try {
                    if (!released.get()) emitter.send(SseEmitter.event()
                            .name(failure == null ? "completed" : "failed")
                            .data(failure == null ? value : "OFFLINE_PROBE_FAILED"));
                } catch (IOException | IllegalStateException disconnected) {
                    // 订阅故障不得传播为业务取消，也不打印异常正文或请求信息。
                } finally {
                    detach.run();
                    emitter.complete();
                }
            });
        } catch (RejectedExecutionException full) {
            detach.run();
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        } catch (IOException | RuntimeException failure) {
            detach.run();
            emitter.complete();
            throw failure;
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .header("X-Accel-Buffering", "no").body(emitter);
    }
}
