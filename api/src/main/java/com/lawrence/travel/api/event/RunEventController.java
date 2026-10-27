package com.lawrence.travel.api.event;

import com.lawrence.travel.application.event.RunEventService;
import com.lawrence.travel.application.event.RunEvents;
import com.lawrence.travel.domain.identity.UserIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 私有事件入口，只调用应用用例；断线及订阅操作没有提交、接受或取消业务的权限。 */
@RestController
@Profile("local")
@RequestMapping("/api/v1/tasks/{taskId}")
public final class RunEventController {
    private final RunEventService service;
    private final EventStreams streams;

    /**
     * @param service 归属及游标用例
     * @param streams 有界传输
     */
    public RunEventController(RunEventService service, EventStreams streams) {
        this.service = service;
        this.streams = streams;
    }

    /**
     * @param actor 服务端认证
     * @param taskId 私有任务
     * @param response 禁止缓存
     * @return 一致性视图和游标
     */
    @GetMapping("/view")
    public RunEvents.View view(
            @AuthenticationPrincipal UserIdentity actor,
            @PathVariable UUID taskId,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.view(actor, taskId);
    }

    /**
     * @param actor 认证
     * @param taskId 任务
     * @param after 初次游标
     * @param limit 1～100
     * @param response 禁止缓存
     * @return 升序页
     */
    @GetMapping("/events")
    public RunEvents.Page page(
            @AuthenticationPrincipal UserIdentity actor,
            @PathVariable UUID taskId,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "100") int limit,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.page(actor, taskId, after, limit);
    }

    /**
     * @param actor 认证
     * @param taskId 任务
     * @param lastId 标准重连头，优先于 after
     * @param after 初次游标
     * @param request 当前会话
     * @param response 同源无缓冲
     * @return 只读 SSE；超时轮换连接由客户端补读
     */
    @GetMapping(value = "/events/stream", produces = "text/event-stream")
    public SseEmitter stream(
            @AuthenticationPrincipal UserIdentity actor,
            @PathVariable UUID taskId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastId,
            @RequestParam(required = false) String after,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        var session = request.getSession(false);
        return streams.open(
                actor,
                taskId,
                session == null ? null : session.getId(),
                lastId == null ? after : lastId);
    }
}
