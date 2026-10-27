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

/**
 * 已认证聊天的HTTP入口：接收用户原文、稳定消息ID及显式附件选择，委托DialogueService处理。
 *
 * <p>阅读顺序：send → DialogueService.send → PostgresDialogueStore.receive；后台处理入口是
 * PersistentStageWorker调用DialogueService.claimWorkExcluding，而不是本Controller同步等待模型。
 * read用于读取已保存回复；act用于用户明确确认、撤回或重试。身份来自Spring Security，
 * 不接受正文自报userId；Controller不访问Mapper、不自行写需求，也不因收件成功宣称规划完成。
 */
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
     * ModelAttribute：禁止缓存私有聊天内容
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
     * 关联已有的旅行任务
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
     * 查询聊天列表 list()
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
     * 读取当前用户的聊天投影，包含已持久化的消息状态、回复、附件及关联旅行。
     *
     * <p>调用DialogueService.read，不启动模型或恢复任务；刷新页面和轮询不会额外产生推理请求。
     *
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
     * 接收一次用户输入并返回收件后的聊天视图，不在HTTP请求线程调用LLM。
     *
     * <p>路径chat与正文inputId分别承担会话身份和消息幂等身份；附件列表原样交给应用层核对归属、
     * 版本和重放一致性。后续回复由后台保存，再通过read读取；客户端不能用新的inputId冒充同一次重试。
     *
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
     * 接收用户对精确消息的明确动作，交给DialogueService.act执行事务内校验。
     *
     * <p>确认所指对象和版本必须仍有效；RETRY沿用原作业约束，不能因为前端点击而绕过预算或权限。
     *
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
