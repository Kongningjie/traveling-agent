package com.lawrence.travel.api.lifecycle;

import com.lawrence.travel.application.lifecycle.LifecycleService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 最新删除清单/PG水位不一致时关闭私有资料入口；已知停止接口不等待恢复队列。 */
@Component
@Profile("local")
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10)
public final class LifecycleGate extends OncePerRequestFilter {
    private final LifecycleService service;

    /**
     * @param service PG与文件双重屏障
     */
    public LifecycleGate(LifecycleService service) {
        this.service = service;
    }

    /** {@inheritDoc} 只返回固定503，无目录、原文或请求认证信息。 */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        boolean stop =
                "POST".equals(request.getMethod())
                        && path.matches(
                                "/api/v1/tasks/[a-fA-F0-9-]{36}/runs/[a-fA-F0-9-]{36}/cancel");
        if (path.startsWith("/api/v1/")
                && !path.equals("/api/v1/system/info")
                && !path.equals("/api/v1/lifecycle/health")
                && !stop
                && !service.ready()) {
            response.setStatus(503);
            response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"code\":\"RESTORE_REQUIRED\",\"saved\":false}");
            return;
        }
        chain.doFilter(request, response);
    }
}
