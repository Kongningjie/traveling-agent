package com.lawrence.travel.api.knowledge;

import com.lawrence.travel.application.knowledge.EvidenceService;
import com.lawrence.travel.application.knowledge.Research;
import com.lawrence.travel.domain.identity.UserIdentity;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 只读字段证据接口；不接受用户或模型自报证据通过状态，不直接读取 Mapper。 */
@RestController
@Profile("local")
public final class EvidenceController {
    private final EvidenceService service;

    /**
     * @param service 归属校验用例
     */
    public EvidenceController(EvidenceService service) {
        this.service = service;
    }

    /**
     * @param actor 当前身份
     * @param taskId 任务
     * @param response 私有响应
     * @return 有界证据与未知／冲突原因
     */
    @GetMapping("/api/v1/tasks/{taskId}/evidence")
    public List<Research.View> evidence(
            @AuthenticationPrincipal UserIdentity actor,
            @PathVariable UUID taskId,
            jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.list(actor, taskId);
    }
}
