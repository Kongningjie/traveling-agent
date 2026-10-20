package com.lawrence.travel.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lawrence.travel.api.lifecycle.LifecycleGate;
import com.lawrence.travel.api.lifecycle.StorageGate;
import com.lawrence.travel.application.lifecycle.LifecycleService;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** 入口容量与恢复屏障的确定性反例；鉴权/归属另由真实HTTP集成覆盖。 */
class LifecycleGateTest {
    /**
     * * 容量满只拒绝新正文，不阻断读取、停止、删除或CSRF。
     *
     * @throws Exception Servlet调用失败
     */
    @Test
    void capacityKeepsControlEndpointsUsable() throws Exception {
        var gate = new StorageGate(() -> false);
        var id = UUID.randomUUID();
        var passed = new AtomicInteger();
        for (var path :
                java.util.List.of(
                        "/api/v1/chats/" + id,
                        "/api/v1/tasks/" + id + "/runs/" + id + "/cancel",
                        "/api/v1/chats/" + id + "/documents/" + id + "/actions",
                        "/api/v1/lifecycle/deletions",
                        "/api/v1/auth/csrf")) {
            var request = new MockHttpServletRequest("POST", path);
            request.setServletPath(path);
            var response = new MockHttpServletResponse();
            gate.doFilter(request, response, (a, b) -> passed.incrementAndGet());
            assertThat(response.getStatus()).isEqualTo(200);
        }
        var path = "/api/v1/chats/" + id + "/messages";
        var request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        var response = new MockHttpServletResponse();
        gate.doFilter(request, response, (a, b) -> passed.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(passed.get()).isEqualTo(5);
    }

    /**
     * * 最新清单尚未恢复时私有资料失败关闭，明确停止仍可进入原鉴权用例。
     *
     * @throws Exception Servlet调用失败
     */
    @Test
    void restorationBlocksDataButAllowsStop() throws Exception {
        var service = mock(LifecycleService.class);
        when(service.ready()).thenReturn(false);
        var gate = new LifecycleGate(service);
        var passed = new AtomicInteger();
        var id = UUID.randomUUID();
        var data = new MockHttpServletRequest("GET", "/api/v1/chats/" + id);
        data.setServletPath(data.getRequestURI());
        var blocked = new MockHttpServletResponse();
        gate.doFilter(data, blocked, (a, b) -> passed.incrementAndGet());
        assertThat(blocked.getStatus()).isEqualTo(503);
        var stop =
                new MockHttpServletRequest(
                        "POST", "/api/v1/tasks/" + id + "/runs/" + id + "/cancel");
        stop.setServletPath(stop.getRequestURI());
        gate.doFilter(stop, new MockHttpServletResponse(), (a, b) -> passed.incrementAndGet());
        assertThat(passed.get()).isEqualTo(1);
    }
}
