package com.lawrence.travel.api.interaction;

import com.lawrence.travel.application.interaction.Dialogue;
import com.lawrence.travel.application.interaction.DialogueService;
import com.lawrence.travel.domain.identity.UserIdentity;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 已认证独立聊天接口；Cookie/CSRF沿用身份模块，禁止私有回复被缓存。 */
@RestController
@Profile("local")
@RequestMapping("/api/v1/chats")
public final class DialogueController {
    private final DialogueService service;

    /**
     * @param service 应用用例，Controller不接触Mapper
     */
    public DialogueController(DialogueService service) {
        this.service = service;
    }

    /**
     * @param response 私有缓存控制
     */
    @ModelAttribute
    public void noStore(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    /**
     * @param inputId 幂等输入
     * @param text 用户完整原文
     * @param documentIds 本轮选中文件ID，省略时为空；由应用验证归属
     */
    public record Input(UUID inputId, String text, List<UUID> documentIds) {}

    /**
     * @param action CONFIRM、WITHDRAW或RETRY，未知动作拒绝
     */
    public record Command(String action) {}

    /**
     * @param taskId 已存在的私有任务
     */
    public record Link(UUID taskId) {}

    /**
     * @param actor 身份
     * @param chat 新会话幂等键
     * @param body 历史旅行归属
     * @return 唯一关联聊天
     */
    @PostMapping("/{chat}/link")
    public Dialogue.View attach(
            @AuthenticationPrincipal UserIdentity actor,
            @PathVariable UUID chat,
            @RequestBody Link body) {
        return service.attach(actor, chat, body.taskId());
    }

    /**
     * @param actor 已认证用户
     * @param offset 分页
     * @return 本人独立聊天
     */
    @GetMapping
    public List<Dialogue.Chat> list(
            @AuthenticationPrincipal UserIdentity actor,
            @RequestParam(defaultValue = "0") int offset) {
        return service.list(actor, offset);
    }

    /**
     * @param actor 身份
     * @param chat 聊天
     * @return 可靠保存的消息及关联任务
     */
    @GetMapping("/{chat}")
    public Dialogue.View read(
            @AuthenticationPrincipal UserIdentity actor, @PathVariable UUID chat) {
        return service.read(actor, chat);
    }

    /**
     * @param actor 身份
     * @param chat 幂等聊天ID
     * @param input 消息
     * @return 收到不等于模型解释完成
     */
    @PostMapping("/{chat}/messages")
    public Dialogue.View send(
            @AuthenticationPrincipal UserIdentity actor,
            @PathVariable UUID chat,
            @RequestBody Input input) {
        return service.send(actor, chat, input.inputId(), input.text(), input.documentIds());
    }

    /**
     * @param actor 身份
     * @param chat 聊天
     * @param input 精确消息
     * @param command 用户明确点击的动作
     * @return 确认、撤回或同预算重试后的视图
     */
    @PostMapping("/{chat}/messages/{input}/actions")
    public Dialogue.View act(
            @AuthenticationPrincipal UserIdentity actor,
            @PathVariable UUID chat,
            @PathVariable UUID input,
            @RequestBody Command command) {
        return service.act(actor, chat, input, command.action());
    }
}
