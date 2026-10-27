package com.lawrence.travel.api.event;

import com.lawrence.travel.application.event.RunEventService;
import com.lawrence.travel.application.port.SubscriptionAccess;
import com.lawrence.travel.domain.identity.UserIdentity;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 旅行事件的有界SSE传输管理器，负责连接容量、认证复查、心跳及成功发送后的游标推进。
 *
 * <p>由RunEventController.stream调用open，连接定时经RunEventService.page补读PG可见事件；
 * 没有新事件不代表模型不存在，也不会因此另起推理。IO失败/超时只清理连接，后台继续保存，
 * 客户端按最后已收到的游标重连。连接内的会话身份只存在内存，不序列化或打印。
 */
public final class EventStreams implements AutoCloseable {
    private final RunEventService service;
    private final SubscriptionAccess access;
    private final Duration heartbeatInterval;
    private final ConcurrentHashMap<UUID, Connection> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer =
            Executors.newScheduledThreadPool(
                    4,
                    task -> {
                        var thread = new Thread(task, "task-event-stream");
                        thread.setDaemon(true);
                        return thread;
                    });
    private boolean closed;

    /**
     * @param service 持久补读用例
     * @param access 仍有效的会话及账号
     * @param heartbeatInterval 正心跳间隔，必须小于六十秒连接轮换时间
     */
    public EventStreams(
            RunEventService service, SubscriptionAccess access, Duration heartbeatInterval) {
        if (heartbeatInterval == null
                || heartbeatInterval.isNegative()
                || heartbeatInterval.isZero()
                || heartbeatInterval.compareTo(Duration.ofSeconds(60)) >= 0)
            throw new IllegalArgumentException("INVALID_HEARTBEAT");
        this.service = service;
        this.access = access;
        this.heartbeatInterval = heartbeatInterval;
    }

    /**
     * @param user 认证主体
     * @param task 私有任务
     * @param sessionId 不记录的会话标识
     * @param after 已确认复合游标
     * @return 60 秒轮换连接，重连继续补读；每用户最多四条、全局最多三十二条
     */
    public synchronized SseEmitter open(
            UserIdentity user, UUID task, String sessionId, String after) {
        service.page(user, task, after, 1); // 先鉴权及检查游标，响应开始后不能再可靠返回 HTTP 错误。
        if (!access.active(sessionId, user))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        if (closed)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "STREAM_UNAVAILABLE");
        if (connections.size() >= 32
                || connections.values().stream()
                                .filter(value -> value.user.id().equals(user.id()))
                                .count()
                        >= 4)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "STREAM_LIMIT");
        var connection = new Connection(user, task, sessionId, after);
        connections.put(connection.id, connection);
        connection.emitter.onCompletion(connection::stop);
        connection.emitter.onTimeout(connection::stop);
        connection.emitter.onError(ignored -> connection.stop());
        try {
            connection.emitter.send(SseEmitter.event().comment("connected"));
            connection.future =
                    timer.scheduleWithFixedDelay(connection::poll, 250, 500, TimeUnit.MILLISECONDS);
            if (connection.stopped.get()) connection.future.cancel(false);
        } catch (IOException | RuntimeException failure) {
            connection.stop();
        }
        return connection.emitter;
    }

    /** 关闭传输连接和定时领取，不中断可能正在数据库查询的线程，也不取消业务任务。 */
    @Override
    public synchronized void close() {
        closed = true;
        connections.values().forEach(Connection::stop);
        timer.shutdown();
    }

    /** 单连接游标及短期认证引用，仅在本地内存保留，不序列化或打印。 */
    private final class Connection {
        private final UUID id = UUID.randomUUID();
        private final UserIdentity user;
        private final UUID task;
        private String sessionId;
        private String cursor;
        private final SseEmitter emitter = new SseEmitter(Duration.ofSeconds(60).toMillis());
        private final AtomicBoolean stopped = new AtomicBoolean();
        private volatile ScheduledFuture<?> future;
        private long heartbeat = System.nanoTime();

        /**
         * @param user 初始认证
         * @param task 任务
         * @param sessionId 不输出的会话引用
         * @param cursor 起始水位
         */
        private Connection(UserIdentity user, UUID task, String sessionId, String cursor) {
            this.user = user;
            this.task = task;
            this.sessionId = sessionId;
            this.cursor = cursor;
        }

        /** 每条连接串行拉取；只在 send 成功后推进水位，断线允许重放，不能跳过未交付页。 */
        private void poll() {
            if (stopped.get()) return;
            try {
                if (!access.active(sessionId, user)) {
                    stop();
                    return;
                }
                var page = service.page(user, task, cursor, 100);
                for (var event : page.events()) {
                    if (stopped.get()) return;
                    emitter.send(SseEmitter.event().id(event.id()).name("run-event").data(event));
                    cursor = event.id();
                }
                if (System.nanoTime() - heartbeat >= heartbeatInterval.toNanos()) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    heartbeat = System.nanoTime();
                }
            } catch (IOException | RuntimeException failure) {
                stop();
            }
        }

        /** 幂等释放；完成回调可再次进入，不能改变后台业务状态。 */
        private void stop() {
            if (!stopped.compareAndSet(false, true)) return;
            connections.remove(id);
            sessionId = null;
            var scheduled = future;
            if (scheduled != null) scheduled.cancel(false);
            emitter.complete();
        }
    }
}
