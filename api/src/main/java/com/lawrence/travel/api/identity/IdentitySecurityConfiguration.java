package com.lawrence.travel.api.identity;

import com.lawrence.travel.application.identity.IdentityFailure;
import com.lawrence.travel.application.identity.IdentityService;
import com.lawrence.travel.domain.identity.UserIdentity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/** local 账号认证边界；仅开放明确路由，后续未知业务接口继续默认拒绝。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class IdentitySecurityConfiguration {
    /** @return Session 中存储的 CSRF，Cookie 仅保存会话 ID */
    @Bean CsrfTokenRepository csrfTokenRepository() { return new HttpSessionCsrfTokenRepository(); }
    /** @return 显式保存登录结果的 Session 上下文仓储 */
    @Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
    /**
     * 登录同时更新会话 ID 和 CSRF，不保留匿名 token 继续写入。
     * @param tokens 同一 CSRF 仓储
     * @return 顺序执行的认证策略
     */
    @Bean SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository tokens) {
        return new CompositeSessionAuthenticationStrategy(List.of(new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(tokens)));
    }
    /**
     * 只装配账号用例认证，不启用默认账号或其他认证机制。
     * @param service 账号用例
     * @return 成功主体不含密码的认证管理器
     */
    @Bean AuthenticationManager authenticationManager(IdentityService service) {
        AuthenticationProvider provider = new AuthenticationProvider() {
            /** {@inheritDoc} 远端地址从服务端构造的 details 取得，不信任请求正文。 */
            @Override public Authentication authenticate(Authentication input) {
                String address = input.getDetails() instanceof WebAuthenticationDetails details ? details.getRemoteAddress() : "unknown";
                try {
                    var user = service.authenticate(input.getName(), (String) input.getCredentials(), address);
                    return UsernamePasswordAuthenticationToken.authenticated(user, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                } catch (IdentityFailure failure) {
                    if (failure.code() == IdentityFailure.Code.INVALID_CREDENTIALS) throw new BadCredentialsException("INVALID_CREDENTIALS");
                    throw failure;
                }
            }
            /** {@inheritDoc} 限定单一账号密码 Token 类型。 */
            @Override public boolean supports(Class<?> type) { return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type); }
        };
        return new ProviderManager(List.of(provider));
    }
    /**
     * 真实账号使用 Redis Session；匿名私有请求 401，认证后未知路由仍拒绝。
     * @param http 框架构建器
     * @param tokens CSRF 仓储
     * @param contexts 会话上下文仓储
     * @param manager 单一认证管理器
     * @param service 每次私有访问重核账号状态
     * @return HTTP 安全链
     * @throws Exception 装配失败中止启动
     */
    @Bean SecurityFilterChain identitySecurityChain(HttpSecurity http, CsrfTokenRepository tokens,
            SecurityContextRepository contexts, AuthenticationManager manager, IdentityService service) throws Exception {
        return http.authenticationManager(manager)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/api/v1/system/info").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        // 初次 REQUEST 仍必须认证；仅放行本入口的 Servlet 异步完成派发，避免会话撤销后完成流时再次被拒绝。
                        // ASYNC 类型由容器产生，浏览器不能通过 HTTP 参数伪造；没有额外业务读取或写入权限。
                        .requestMatchers(request -> request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC
                                && "GET".equals(request.getMethod())
                                && request.getServletPath().matches("/api/v1/tasks/[0-9a-fA-F-]{36}/events/stream")).permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/tasks/*/view", "/api/v1/tasks/*/events", "/api/v1/tasks/*/events/stream").authenticated()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/tasks/*/messages", "/api/v1/tasks/*/decision", "/api/v1/tasks/*/evidence", "/api/v1/tasks/*/candidate", "/api/v1/tasks", "/api/v1/tasks/*/versions", "/api/v1/tasks/*/versions/*", "/api/v1/tasks/*/version-diff").authenticated()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/tasks", "/api/v1/tasks/*/messages", "/api/v1/tasks/*/answers", "/api/v1/tasks/*/accept", "/api/v1/tasks/*/candidate/revalidate", "/api/v1/tasks/*/versions/*/restore", "/api/v1/tasks/*/runs/*/cancel").authenticated()
                        .requestMatchers("/api/v1/preferences", "/api/v1/preferences/**").authenticated()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/chats", "/api/v1/chats/*").authenticated()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/chats/*/messages", "/api/v1/chats/*/messages/*/actions", "/api/v1/chats/*/link").authenticated()
                        .requestMatchers("/api/v1/chats/*/documents", "/api/v1/chats/*/documents/**").authenticated()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/chats/*/references/*", "/api/v1/chats/*/messages/*/references").authenticated()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/chats/*/advice").authenticated()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/chats/*/advice/*/actions").authenticated()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/artifacts/*").authenticated()
                        .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/v1/support/access","/api/v1/support/feedback","/api/v1/support/feedback/*","/api/v1/support/maintenance/groups","/api/v1/support/maintenance/groups/*","/api/v1/support/maintenance/groups/*/events","/api/v1/support/maintenance/traces/*","/api/v1/support/maintenance/feedback/*","/api/v1/support/maintenance/health").authenticated()
                        .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/support/feedback","/api/v1/support/client","/api/v1/support/maintenance/groups/*").authenticated()
                        .anyRequest().denyAll())
                .securityContext(context -> context.requireExplicitSave(true).securityContextRepository(contexts))
                .csrf(csrf -> csrf.csrfTokenRepository(tokens).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .requestCache(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> IdentityErrors.write(response, 401, "UNAUTHENTICATED"))
                        .accessDeniedHandler((request, response, failure) -> IdentityErrors.write(response, 403, "FORBIDDEN")))
                .logout(logout -> logout.logoutUrl("/api/v1/auth/logout").invalidateHttpSession(true).clearAuthentication(true)
                        .deleteCookies("SESSION").logoutSuccessHandler((request, response, auth) -> response.setStatus(204)))
                .addFilterBefore(new ActiveAccountFilter(service), AuthorizationFilter.class).build();
    }
    /** 当前会话身份必须仍有效，停用后立即失效且不信任客户端 userId。 */
    private static final class ActiveAccountFilter extends OncePerRequestFilter {
        private final IdentityService service;
        /**
         * 绑定当前账号用例。
         * @param service 账号用例
         */
        private ActiveAccountFilter(IdentityService service) { this.service = service; }
        /** {@inheritDoc} 依赖故障安全失败，不回退内存身份；不处理浏览器 Cookie 本文。 */
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof UserIdentity user) {
                try { service.current(user.id()); }
                catch (IdentityFailure failure) {
                    SecurityContextHolder.clearContext();
                    var session = request.getSession(false);
                    if (session != null) session.invalidate();
                    IdentityErrors.write(response, failure.code() == IdentityFailure.Code.DEPENDENCY_UNAVAILABLE ? 503 : 401,
                            failure.code().name());
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }
}
