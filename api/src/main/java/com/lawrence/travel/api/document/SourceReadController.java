package com.lawrence.travel.api.document;

import com.lawrence.travel.application.document.DocumentFailure;
import com.lawrence.travel.application.document.SourceRead;
import com.lawrence.travel.application.document.SourceReadService;
import com.lawrence.travel.domain.identity.UserIdentity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 私有资料及引用预览；只返回有界纯文本，不解释HTML，不接受URL导入。 */
@RestController
@Profile("local")
@RequestMapping("/api/v1/chats/{chat}")
public final class SourceReadController {
    private final SourceReadService service;
    /** @param service 归属/来源校验后的应用用例 */
    public SourceReadController(SourceReadService service) { this.service = service; }
    /** @param query 字面词 */
    public record Query(String query) { }
    /** @param position 页段 @param start 字符起点 @param length 有界长度 */
    public record Range(int position, int start, int length) { }
    /** @param actor 身份 @param chat 当前聊天 @param id 原件 @param request 字面查询 @return 有界来源与覆盖；生成引用须CSRF保护 */
    @PostMapping("/documents/{id}/search")
    public SourceRead.Matches search(@AuthenticationPrincipal UserIdentity actor, @PathVariable UUID chat,
            @PathVariable UUID id, @RequestBody Query request) { return service.search(actor.id(), chat, id, request.query()); }
    /** @param actor 身份 @param chat 聊天 @param id 文档 @param request 页段与有界范围 @return 实读范围 */
    @PostMapping("/documents/{id}/section")
    public SourceRead.Fragment section(@AuthenticationPrincipal UserIdentity actor, @PathVariable UUID chat,
            @PathVariable UUID id, @RequestBody Range request) { return service.section(actor.id(), chat, id, request.position(), request.start(), request.length()); }
    /** @param actor 身份 @param chat 聊天 @param id 引用 @return 当前有效原文，删除后统一拒读 */
    @GetMapping("/references/{id}")
    public SourceRead.Fragment reference(@AuthenticationPrincipal UserIdentity actor, @PathVariable UUID chat,
            @PathVariable UUID id) { return service.reference(actor.id(), chat, id); }
    /** @param actor 身份 @param chat 聊天 @param message 原输入 @return 本回复实际读取的有效引用 */
    @GetMapping("/messages/{message}/references")
    public List<UUID> references(@AuthenticationPrincipal UserIdentity actor, @PathVariable UUID chat,
            @PathVariable UUID message) { return service.references(actor.id(), chat, message); }
    /** @param failure 固定分类 @return 脱敏错误；不能通过错误区分其他用户资料存在与否 */
    @ExceptionHandler(DocumentFailure.class)
    public ResponseEntity<Map<String,String>> failure(DocumentFailure failure) {
        String code = failure.getMessage();
        int status = code.endsWith("NOT_FOUND") ? 404 : code.equals("SOURCE_CAPACITY") ? 429 : code.endsWith("UNAVAILABLE") ? 503 : 409;
        return ResponseEntity.status(status).body(Map.of("code", code));
    }
}
