package com.lawrence.travel.api.lifecycle;

import com.lawrence.travel.application.lifecycle.Lifecycle;
import com.lawrence.travel.application.lifecycle.LifecycleFailure;
import com.lawrence.travel.application.lifecycle.LifecycleService;
import com.lawrence.travel.application.port.SupportStore;
import com.lawrence.travel.domain.identity.UserIdentity;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** 删除必须展示当前影响并明确确认；不提供模型可直接执行的删除工具。 */
@RestController
@Profile("local")
@RequestMapping("/api/v1/lifecycle")
public final class LifecycleController {
    private final LifecycleService service;
    private final SupportStore support;

    /**
     * @param service 删除用例
     * @param support 实时维护授权端口
     */
    public LifecycleController(LifecycleService service, SupportStore support) {
        this.service = service;
        this.support = support;
    }

    /**
     * @param response 禁止私有预览缓存
     */
    @ModelAttribute
    public void noStore(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    /**
     * @param user 当前身份
     * @param kind 删除范围
     * @param target 服务器对象ID
     * @return 仅预览，不发删除
     */
    @GetMapping("/preview")
    public Lifecycle.Preview preview(
            @AuthenticationPrincipal UserIdentity user,
            @RequestParam Lifecycle.Kind kind,
            @RequestParam UUID target) {
        return service.preview(user.id(), kind, target);
    }

    /**
     * @param request 幂等ID
     * @param kind 范围
     * @param target 对象
     * @param revision 所见对象集摘要
     * @param confirmed 用户明确确认
     */
    public record Command(
            UUID request, Lifecycle.Kind kind, UUID target, String revision, boolean confirmed) {}

    /**
     * @param user 当前身份
     * @param c 所见确认
     * @return 提交成功但原件可能仍等待清理
     */
    @PostMapping("/deletions")
    public Lifecycle.Receipt delete(
            @AuthenticationPrincipal UserIdentity user, @RequestBody Command c) {
        return service.delete(
                user.id(), c.request(), c.kind(), c.target(), c.revision(), c.confirmed());
    }

    /**
     * @param user 维护者
     * @return 清理计数，不允许普通用户查询全局数据
     */
    @GetMapping("/health")
    public Map<String, Object> health(@AuthenticationPrincipal UserIdentity user) {
        if (!support.allowed(user.id(), false)) throw new LifecycleFailure("DELETE_FORBIDDEN");
        return Map.of("ready", service.ready(), "cleanup", service.health());
    }
}
