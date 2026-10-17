package com.lawrence.travel.api.itinerary;

import com.lawrence.travel.application.itinerary.ItineraryService;
import com.lawrence.travel.application.itinerary.Planning;
import com.lawrence.travel.application.port.CandidateStore;
import com.lawrence.travel.domain.identity.UserIdentity;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 候选查看与显式接受，用户身份只取认证会话；无Mapper或模型访问。 */
@RestController
@Profile("local")
public final class ItineraryController {
    private final ItineraryService service;
    /** @param service 明确接受用例 */
    public ItineraryController(ItineraryService service) { this.service = service; }
    /** @param requestId 永久幂等请求 @param candidateId 候选 @param expectedCurrentVersionId 当前指针 @param constraintRevision 需求 @param generation 代次 @param snapshotHash 精确内容 */
    public record Accept(UUID requestId, UUID candidateId, UUID expectedCurrentVersionId, long constraintRevision, long generation, String snapshotHash) { }
    /** @param actor 会话身份 @param taskId 任务 @param response 禁止缓存 @return 可含问题的最近草案，不隐式接受 */
    @GetMapping("/api/v1/tasks/{taskId}/candidate")
    public Planning.View latest(@AuthenticationPrincipal UserIdentity actor, @PathVariable UUID taskId, jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); return service.latest(actor, taskId); }
    /** @param actor 会话身份 @param taskId 任务 @param body 用户明确的预期 @param response 禁止缓存 @return 已接受或可靠重校验中；需要用户再次确认 */
    @PostMapping("/api/v1/tasks/{taskId}/accept")
    public ResponseEntity<CandidateStore.Acceptance> accept(@AuthenticationPrincipal UserIdentity actor, @PathVariable UUID taskId, @RequestBody Accept body, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store"); var result = service.accept(actor, taskId, body.requestId(), body.candidateId(), body.expectedCurrentVersionId(), body.constraintRevision(), body.generation(), body.snapshotHash());
        return ResponseEntity.status(result.status() == CandidateStore.Status.ACCEPTED ? 200 : 202).body(result);
    }
    /** @param actor 认证 @param taskId 任务 @param body 精确候选前置条件 @param response 禁止缓存 @return 只重校验，不复用接受意图 */
    @PostMapping("/api/v1/tasks/{taskId}/candidate/revalidate")
    public ResponseEntity<CandidateStore.Acceptance> recheck(@AuthenticationPrincipal UserIdentity actor, @PathVariable UUID taskId, @RequestBody Accept body, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store"); return ResponseEntity.accepted().body(service.recheck(actor, taskId, body.requestId(), body.candidateId(), body.expectedCurrentVersionId(), body.constraintRevision(), body.generation(), body.snapshotHash()));
    }

}
