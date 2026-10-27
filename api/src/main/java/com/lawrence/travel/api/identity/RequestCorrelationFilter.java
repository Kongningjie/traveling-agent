package com.lawrence.travel.api.identity;

import com.lawrence.travel.application.execution.ExecutionCorrelation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 用户HTTP请求进入主链时生成服务端requestId/traceId，供后续作业、模型、工具和问题记录关联。
 *
 * <p>过滤器先绑定ExecutionCorrelation再执行Controller，线程归还前恢复原绑定。
 * 后台不继承请求线程状态，而是从PG归属记录显式恢复同一关联；不能用登录Cookie、Session ID
 * 或客户端自报header作为诊断trace。响应关联ID只用于排障，不授予用户数据读取权限。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestCorrelationFilter extends OncePerRequestFilter {
    /** {@inheritDoc} 线程归还前恢复旧绑定；异步业务使用持久化ID显式重新绑定。 */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var ids = new ExecutionCorrelation.Ids(UUID.randomUUID(), UUID.randomUUID());
        response.setHeader("X-Request-ID", ids.requestId().toString());
        response.setHeader("X-Trace-ID", ids.traceId().toString());
        try (var ignored = ExecutionCorrelation.bind(ids)) {
            chain.doFilter(request, response);
        }
    }
}
