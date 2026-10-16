package com.lawrence.travel.api.identity;

import com.lawrence.travel.application.identity.IdentityFailure;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 身份 HTTP 错误只输出固定分类，不序列化凭据、底层异常或请求正文。 */
@RestControllerAdvice(assignableTypes = IdentityController.class)
public final class IdentityErrors {
    /**
     * 固定错误外壳。
     * @param code 安全分类
     * @param message 固定说明
     * @param requestId 新的关联 ID，不是会话 ID
     * @param retryable 是否可在依赖恢复后重试
     */
    public record ErrorBody(String code, String message, String requestId, boolean retryable) { }
    /**
     * 应用拒绝转换为明确状态。
     * @param failure 固定身份错误
     * @return 脱敏响应
     */
    @ExceptionHandler(IdentityFailure.class)
    ResponseEntity<ErrorBody> identityFailure(IdentityFailure failure) {
        int status = switch (failure.code()) {
            case INVALID_INPUT -> 400;
            case REGISTRATION_REJECTED -> 409;
            case INVALID_CREDENTIALS -> 401;
            case NOT_FOUND -> 404;
            case RATE_LIMITED -> 429;
            case DEPENDENCY_UNAVAILABLE -> 503;
        };
        return ResponseEntity.status(status).body(body(failure.code().name(), status == 503 || status == 429));
    }
    /**
     * 认证失败不区分账号或密码原因。
     * @param ignored 不对外传播的框架异常
     * @return 固定 401
     */
    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ErrorBody> badCredentials(BadCredentialsException ignored) { return ResponseEntity.status(401).body(body("INVALID_CREDENTIALS", false)); }
    /**
     * 无效 JSON 不回显解析片段。
     * @param ignored 不输出的解析异常
     * @return 固定 400
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> invalidJson(HttpMessageNotReadableException ignored) { return ResponseEntity.badRequest().body(body("INVALID_INPUT", false)); }
    /**
     * 创建固定说明。
     * @param code 仅内部固定分类
     * @param retryable 重试提示
     * @return 不含输入的错误
     */
    private static ErrorBody body(String code, boolean retryable) { return new ErrorBody(code, "请求无法完成", UUID.randomUUID().toString(), retryable); }
    /**
     * 安全过滤器不能依赖 MVC advice，直接输出固定 JSON。
     * @param response HTTP 响应
     * @param status 状态
     * @param code 固定分类，调用方不能使用用户文本
     * @throws IOException 客户端已经断开
     */
    public static void write(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"请求无法完成\",\"requestId\":\""
                + UUID.randomUUID() + "\",\"retryable\":" + (status == 503) + "}");
    }
}
