package com.lawrence.travel.api.support;

import com.lawrence.travel.application.execution.ExecutionCorrelation;
import com.lawrence.travel.application.safety.SafetyGate;
import com.lawrence.travel.application.support.Support;
import com.lawrence.travel.application.support.SupportFailure;
import com.lawrence.travel.application.support.SupportService;
import com.lawrence.travel.domain.identity.UserIdentity;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 已认证反馈和维护入口；权限来自用例端口，浏览器字段不能提权。 */
@RestController
@Profile("local")
@RequestMapping("/api/v1/support")
public final class SupportController {
    private final SupportService service;

    /**
     * @param service 反馈用例，无Mapper权限
     */
    public SupportController(SupportService service) {
        this.service = service;
    }

    /**
     * @param response 私有读取禁止缓存
     */
    @ModelAttribute
    public void noStore(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    /**
     * @param user 会话主体
     * @param input 主动填写的有界反馈
     * @return 已持久化回执
     */
    @PostMapping("/feedback")
    public Support.Receipt create(
            @AuthenticationPrincipal UserIdentity user, @RequestBody Support.Input input) {
        return service.create(user.id(), input);
    }

    /**
     * @param user 当前身份
     * @param offset 分页
     * @return 仅本人摘要
     */
    @GetMapping("/feedback")
    public List<Support.Feedback> list(
            @AuthenticationPrincipal UserIdentity user,
            @RequestParam(defaultValue = "0") int offset) {
        return service.list(user.id(), offset);
    }

    /**
     * @param user 当前身份
     * @param id 私有反馈
     * @return 留审计的本人正文
     */
    @GetMapping("/feedback/{id}")
    public Support.Detail detail(
            @AuthenticationPrincipal UserIdentity user, @PathVariable UUID id) {
        return service.detail(user.id(), id, false);
    }

    /**
     * @param user 当前身份
     * @return 实时维护权限
     */
    @GetMapping("/access")
    public Map<String, Boolean> access(@AuthenticationPrincipal UserIdentity user) {
        return service.access(user.id());
    }

    /**
     * @param user 维护者
     * @param offset 分页
     * @return 固定聚合元数据
     */
    @GetMapping("/maintenance/groups")
    public List<Support.Group> groups(
            @AuthenticationPrincipal UserIdentity user,
            @RequestParam(defaultValue = "0") int offset) {
        return service.groups(user.id(), offset);
    }

    /**
     * @param user 维护者
     * @param id 问题/聚合ID
     * @return 详情
     */
    @GetMapping("/maintenance/groups/{id}")
    public Support.Group group(@AuthenticationPrincipal UserIdentity user, @PathVariable UUID id) {
        return service.group(user.id(), id);
    }

    /**
     * @param user 维护者
     * @param id 问题/聚合ID
     * @return 有界原始事件
     */
    @GetMapping("/maintenance/groups/{id}/events")
    public List<Support.Event> events(
            @AuthenticationPrincipal UserIdentity user, @PathVariable UUID id) {
        return service.events(user.id(), id);
    }

    /**
     * @param user 维护者
     * @param id 问题组
     * @param change 版本及回归凭据
     * @return 已提交状态
     */
    @PostMapping("/maintenance/groups/{id}")
    public Support.Group change(
            @AuthenticationPrincipal UserIdentity user,
            @PathVariable UUID id,
            @RequestBody Support.Change change) {
        return service.change(user.id(), id, change);
    }

    /**
     * @param user 维护者
     * @param id 链路
     * @return 固定执行步骤
     */
    @GetMapping("/maintenance/traces/{id}")
    public Support.Trace trace(@AuthenticationPrincipal UserIdentity user, @PathVariable UUID id) {
        return service.trace(user.id(), id);
    }

    /**
     * @param user 额外敏感授权维护者
     * @param id 反馈
     * @return 显式读取且审计
     */
    @GetMapping("/maintenance/feedback/{id}")
    public Support.Detail privateDetail(
            @AuthenticationPrincipal UserIdentity user, @PathVariable UUID id) {
        return service.detail(user.id(), id, true);
    }

    /**
     * @param user 维护者
     * @return 降级计数
     */
    @GetMapping("/maintenance/health")
    public Support.Health health(@AuthenticationPrincipal UserIdentity user) {
        return service.health(user.id());
    }

    /**
     * @param id 幂等ID
     * @param code 固定分类
     * @param component 固定位置
     */
    public record ClientInput(UUID id, String code, String component) {}

    /**
     * @param user 登录主体
     * @param input 无正文的固定协议
     * @return 实际保存，失败不宣称记录
     */
    @PostMapping("/client")
    public Map<String, Object> client(
            @AuthenticationPrincipal UserIdentity user, @RequestBody ClientInput input) {
        return Map.of(
                "saved",
                true,
                "problem",
                service.client(user.id(), input.id(), input.code(), input.component()));
    }

    /**
     * @param failure 固定代码异常
     * @return 无正文和底层异常的失败，禁止显示已记录
     */
    @ExceptionHandler(SupportFailure.class)
    public ResponseEntity<Map<String, Object>> failure(SupportFailure failure) {
        String c = failure.getMessage();
        int status =
                switch (c) {
                    case "SUPPORT_FORBIDDEN" -> 403;
                    case "SUPPORT_NOT_FOUND" -> 404;
                    case "SUPPORT_CONFLICT" -> 409;
                    case "SUPPORT_RATE_LIMIT" -> 429;
                    case "SUPPORT_UNAVAILABLE", "SUPPORT_CAPACITY" -> 503;
                    default -> 400;
                };
        return ResponseEntity.status(status)
                .body(
                        Map.of(
                                "saved",
                                false,
                                "code",
                                c,
                                "request",
                                ExecutionCorrelation.current().requestId()));
    }

    /**
     * @param failure 文本安全拒绝
     * @return 不回显原文，也不误称反馈保存成功
     */
    @ExceptionHandler(SafetyGate.Failure.class)
    public ResponseEntity<Map<String, Object>> unsafe(SafetyGate.Failure failure) {
        return ResponseEntity.badRequest()
                .body(Map.of("saved", false, "code", "SUPPORT_SAFETY_REJECTED"));
    }

    /**
     * @param failure 无效JSON/字段
     * @return 固定错误，不序列化框架原异常
     */
    @ExceptionHandler({
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Map<String, Object>> invalid(Exception failure) {
        return ResponseEntity.badRequest()
                .body(Map.of("saved", false, "code", "SUPPORT_INPUT_INVALID"));
    }
}
