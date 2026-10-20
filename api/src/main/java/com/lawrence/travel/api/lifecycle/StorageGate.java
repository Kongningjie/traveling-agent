package com.lawrence.travel.api.lifecycle;

import com.lawrence.travel.application.port.StorageAdmission;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** 容量不足只停止接纳新正文/附件，停止和删除走原短接口，不与模型排队。 */
@org.springframework.stereotype.Component
@org.springframework.context.annotation.Profile("local")
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 20)
public final class StorageGate extends org.springframework.web.filter.OncePerRequestFilter {
    private final StorageAdmission admission;

    /**
     * @param admission 受信部署容量端口，无文件路径权限
     */
    public StorageGate(StorageAdmission admission) {
        this.admission = admission;
    }

    /** {@inheritDoc} 不读取请求正文，不将输入/凭据写入容量日志。 */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String p = request.getServletPath();
        boolean fresh =
                "POST".equals(request.getMethod())
                        && (p.matches("/api/v1/chats/[a-fA-F0-9-]{36}/(messages|documents)")
                                || p.equals("/api/v1/tasks")
                                || p.matches("/api/v1/tasks/[a-fA-F0-9-]{36}/messages"));
        if (fresh && !admission.accepting()) {
            response.setStatus(429);
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"STORAGE_CAPACITY\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
