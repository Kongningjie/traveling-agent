package com.lawrence.travel.api.memory;

import com.lawrence.travel.application.memory.PreferenceService;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.memory.Preference;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 私有偏好管理；CSRF保护每次写入，确认建议是独立操作，不等于确认旅行方案。 */
@RestController
@Profile("local")
@RequestMapping("/api/v1/preferences")
public final class PreferenceController {
    private final PreferenceService service;

    /**
     * @param service 偏好用例
     */
    public PreferenceController(PreferenceService service) {
        this.service = service;
    }

    /**
     * @param response 禁止浏览器缓存私有偏好
     */
    @ModelAttribute
    public void noStore(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    /**
     * @param requestId 请求
     * @param revision 全局版本
     * @param id 新条目身份
     * @param category 类别
     * @param value 文本
     * @param city 范围
     * @param suggestion 是否仅提出待确认建议
     */
    public record Create(
            UUID requestId,
            long revision,
            UUID id,
            String category,
            String value,
            String city,
            boolean suggestion) {}

    /**
     * @param requestId 请求
     * @param revision 版本
     * @param category 类别
     * @param value 内容
     * @param city 范围
     */
    public record Update(
            UUID requestId, long revision, String category, String value, String city) {}

    /**
     * @param requestId 请求
     * @param revision 版本
     */
    public record Intent(UUID requestId, long revision) {}

    /**
     * @param actor 身份
     * @return 当前确认偏好与待确认建议
     */
    @GetMapping
    public Preference.View read(@AuthenticationPrincipal UserIdentity actor) {
        return service.read(actor);
    }

    /**
     * @param actor 身份
     * @param body 主动保存或仅建议
     * @return 权威状态
     */
    @PostMapping
    public Preference.View create(
            @AuthenticationPrincipal UserIdentity actor, @RequestBody Create body) {
        return service.change(
                actor,
                body.requestId(),
                body.revision(),
                body.id(),
                body.suggestion() ? "SUGGEST" : "SAVE",
                body.category(),
                body.value(),
                body.city());
    }

    /**
     * @param actor 身份
     * @param id 条目
     * @param body 显式修改
     * @return 当前状态
     */
    @PatchMapping("/{id}")
    public Preference.View update(
            @AuthenticationPrincipal UserIdentity actor,
            @PathVariable UUID id,
            @RequestBody Update body) {
        return service.change(
                actor,
                body.requestId(),
                body.revision(),
                id,
                "UPDATE",
                body.category(),
                body.value(),
                body.city());
    }

    /**
     * @param actor 身份
     * @param id 建议
     * @param body 明确确认
     * @return 确认后才参与召回
     */
    @PostMapping("/{id}/confirm")
    public Preference.View confirm(
            @AuthenticationPrincipal UserIdentity actor,
            @PathVariable UUID id,
            @RequestBody Intent body) {
        return service.change(
                actor, body.requestId(), body.revision(), id, "CONFIRM", null, null, null);
    }

    /**
     * @param actor 身份
     * @param id 条目
     * @param body 删除或拒绝建议
     * @return 墓碑不会出现在后续读取；本次独立旅行约束不删除
     */
    @DeleteMapping("/{id}")
    public Preference.View delete(
            @AuthenticationPrincipal UserIdentity actor,
            @PathVariable UUID id,
            @RequestBody Intent body) {
        return service.change(
                actor, body.requestId(), body.revision(), id, "DELETE", null, null, null);
    }
}
