package com.lawrence.travel.api.document;

import com.lawrence.travel.application.document.Advice;
import com.lawrence.travel.application.document.AdviceService;
import com.lawrence.travel.domain.identity.UserIdentity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 已认证逐项建议接口；同源Cookie和CSRF沿原安全配置，不提供批量盲采纳。 */
@RestController
@Profile("local")
@RequestMapping("/api/v1/chats/{chat}/advice")
public final class AdviceController {
    private final AdviceService service;

    /**
     * @param service 业务确认端口，Controller不能访问Mapper
     */
    public AdviceController(AdviceService service) {
        this.service = service;
    }

    /**
     * @param response 禁止浏览器/代理缓存私有材料派生内容
     */
    @ModelAttribute
    public void noStore(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    /**
     * @param action ADOPT/CUSTOM/REJECT
     * @param changes 自定义值，仅限建议原字段
     * @param confirmed 明确确认，不采用预选隐含授权
     */
    public record Command(String action, Map<String, String> changes, boolean confirmed) {}

    /**
     * @param actor 当前身份
     * @param chat 私有聊天
     * @return 当前建议及过期状态
     */
    @GetMapping
    public List<Advice.View> list(
            @AuthenticationPrincipal UserIdentity actor, @PathVariable UUID chat) {
        return service.list(actor, chat);
    }

    /**
     * @param actor 当前身份
     * @param chat 私有聊天
     * @param id 精确建议
     * @param command 用户二次确认
     * @return 变更回执；并非接受行程
     */
    @PostMapping("/{id}/actions")
    public Advice.View act(
            @AuthenticationPrincipal UserIdentity actor,
            @PathVariable UUID chat,
            @PathVariable UUID id,
            @RequestBody Command command) {
        return service.act(
                actor, chat, id, command.action(), command.changes(), command.confirmed());
    }

    /**
     * @param failure 已记录问题编号的检查拒绝
     * @return 不含用户文本的固定原因
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(
            com.lawrence.travel.application.safety.SafetyGate.Failure.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> unsafe(
            com.lawrence.travel.application.safety.SafetyGate.Failure failure) {
        return org.springframework.http.ResponseEntity.badRequest()
                .body(
                        Map.of(
                                "code",
                                "ADVICE_SAFETY_REJECTED",
                                "problemId",
                                failure.result().problemId().toString()));
    }
}
