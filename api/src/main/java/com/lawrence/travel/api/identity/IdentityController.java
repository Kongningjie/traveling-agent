package com.lawrence.travel.api.identity;

import com.lawrence.travel.application.identity.IdentityService;
import com.lawrence.travel.domain.identity.UserIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 身份入口只调用应用用例和框架会话，不接触 Mapper 或客户端自报 userId。 */
@RestController
@Profile("local")
@RequestMapping("/api/v1/auth")
public final class IdentityController {
    /**
     * 请求凭据禁止默认 toString 输出；不得作为任何响应或会话属性。
     * @param username 输入登录名
     * @param password 仅当前请求使用的原始密码
     */
    public record CredentialsRequest(String username, String password) {
        /** @return 固定脱敏文本 */
        @Override public String toString() { return "CredentialsRequest[redacted]"; }
    }
    /**
     * CSRF 令牌只发给同源客户端，不写日志。
     * @param headerName 后续写请求头名称
     * @param token 本会话 CSRF 值
     */
    public record CsrfView(String headerName, String token) {
        /** @return 固定脱敏文本，禁止误打印令牌 */
        @Override public String toString() { return "CsrfView[redacted]"; }
    }
    private final IdentityService identity;
    private final AuthenticationManager authentication;
    private final SessionAuthenticationStrategy sessions;
    private final SecurityContextRepository contexts;
    /**
     * 框架会话与应用用例显式协作。
     * @param identity 账号用例
     * @param authentication 已限定的认证管理器
     * @param sessions 会话与 CSRF 轮换策略
     * @param contexts Redis Session 上的安全上下文仓储
     */
    public IdentityController(IdentityService identity, AuthenticationManager authentication,
                              SessionAuthenticationStrategy sessions, SecurityContextRepository contexts) {
        this.identity = identity; this.authentication = authentication; this.sessions = sessions; this.contexts = contexts;
    }
    /**
     * 首次请求强制产生并保存 CSRF，建立匿名会话供注册／登录校验。
     * @param request 当前请求
     * @return 仅同源可读的 CSRF 信息
     */
    @GetMapping("/csrf")
    public CsrfView csrf(HttpServletRequest request) {
        var token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return new CsrfView(token.getHeaderName(), token.getToken());
    }
    /**
     * 注册不登录，返回不含哈希的新身份。
     * @param body 当前请求凭据
     * @param request 仅读取连接远端地址
     * @return 201 账号身份
     */
    @PostMapping("/register")
    public ResponseEntity<UserIdentity> register(@RequestBody CredentialsRequest body, HttpServletRequest request) {
        return ResponseEntity.status(201).body(identity.register(body.username(), body.password(), request.getRemoteAddr()));
    }
    /**
     * 成功后先轮换 Session ID／CSRF，再显式保存不含密码的安全上下文。
     * @param body 当前请求凭据
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @return 已认证身份；不会返回 Session ID
     */
    @PostMapping("/login")
    public UserIdentity login(@RequestBody CredentialsRequest body, HttpServletRequest request, HttpServletResponse response) {
        var input = UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password());
        input.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        Authentication result = authentication.authenticate(input);
        sessions.onAuthentication(result, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(result);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return (UserIdentity) result.getPrincipal();
    }
    /**
     * 身份只取安全上下文，忽略浏览器传入的用户查询参数。
     * @param authentication 已经账号状态过滤的认证
     * @return 最小账号身份
     */
    @GetMapping("/me")
    public UserIdentity me(Authentication authentication) { return (UserIdentity) authentication.getPrincipal(); }
}
