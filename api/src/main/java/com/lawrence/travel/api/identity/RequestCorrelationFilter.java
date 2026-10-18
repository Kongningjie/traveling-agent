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

/** 服务端生成非敏感关联身份，不能把客户端header或登录Cookie作为trace。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestCorrelationFilter extends OncePerRequestFilter {
    /** {@inheritDoc} 线程归还前恢复旧绑定；异步业务使用持久化ID显式重新绑定。 */
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var ids = new ExecutionCorrelation.Ids(UUID.randomUUID(), UUID.randomUUID());
        response.setHeader("X-Request-ID", ids.requestId().toString()); response.setHeader("X-Trace-ID", ids.traceId().toString());
        try (var ignored = ExecutionCorrelation.bind(ids)) { chain.doFilter(request, response); }
    }
}
