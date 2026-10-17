package com.lawrence.travel.api.interaction;

import com.lawrence.travel.application.interaction.Negotiation;
import com.lawrence.travel.application.interaction.NegotiationService;
import com.lawrence.travel.domain.identity.UserIdentity;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 认证身份和 CSRF 保护的协商入口；HTTP 接收不等于模型解释或业务采纳。 */
@RestController
@Profile("local")
@RequestMapping("/api/v1/tasks")
public final class NegotiationController {
    /**
     * @param response 私有协商结果禁止浏览器或代理复用旧用户缓存
     */
    @org.springframework.web.bind.annotation.ModelAttribute
    public void noStore(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    private final NegotiationService service;
    /**
     * @param service 协商用例，禁止 Controller 访问 Mapper
     */
    public NegotiationController(NegotiationService service) { this.service = service; }
    /**
     * @param requestId 创建幂等键
     * @param title 任务名
     * @param text 初始原文
     * @param facts 明确输入
     */
    public record Create(UUID requestId, String title, String text, List<Negotiation.Fact> facts) { }
    /**
     * @param inputId 幂等输入
     * @param text 用户修改
     * @param facts 明确字段
     */
    public record Message(UUID inputId, String text, List<Negotiation.Fact> facts) { }
    /**
     * @param inputId 幂等回答
     * @param response 明确选项、自由文本和有限授权
     */
    public record Answer(UUID inputId, Negotiation.Response response) { }
    /**
     * @param actor 认证
     * @param body 创建输入
     * @return 可靠登记的任务，不宣称已完成规划
     */
    @PostMapping public ResponseEntity<NegotiationService.Created> create(@AuthenticationPrincipal UserIdentity actor, @RequestBody Create body) { return ResponseEntity.accepted().body(service.create(actor, body.requestId(), body.title(), body.text(), body.facts())); }
    /**
     * @param actor 认证
     * @param taskId 任务
     * @param body 修改
     * @return 收到并可靠调度
     */
    @PostMapping("/{taskId}/messages") public ResponseEntity<Negotiation.Receipt> send(@AuthenticationPrincipal UserIdentity actor, @PathVariable UUID taskId, @RequestBody Message body) { return ResponseEntity.accepted().body(service.send(actor, taskId, body.inputId(), body.text(), body.facts())); }
    /**
     * @param actor 认证
     * @param taskId 任务
     * @param body 回答
     * @return 收讫或仅记录，采纳状态另行读取
     */
    @PostMapping("/{taskId}/answers") public ResponseEntity<Negotiation.Receipt> answer(@AuthenticationPrincipal UserIdentity actor, @PathVariable UUID taskId, @RequestBody Answer body) { return ResponseEntity.accepted().body(service.answer(actor, taskId, body.inputId(), body.response())); }
    /**
     * @param actor 认证
     * @param taskId 任务
     * @return 最近消息及处理状态
     */
    @GetMapping("/{taskId}/messages") public List<Negotiation.Message> messages(@AuthenticationPrincipal UserIdentity actor, @PathVariable UUID taskId) { return service.messages(actor, taskId); }
    /**
     * @param actor 认证
     * @param taskId 任务
     * @return 仅本任务有效问题，可空
     */
    @GetMapping("/{taskId}/decision") public Negotiation.Card card(@AuthenticationPrincipal UserIdentity actor, @PathVariable UUID taskId) { return service.card(actor, taskId); }
}
