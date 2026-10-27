package com.lawrence.travel.api.itinerary;

import com.lawrence.travel.application.itinerary.VersionService;
import com.lawrence.travel.application.itinerary.Versions;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.itinerary.VersionDiff;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

/** 私有版本浏览、比较及恢复；历史查看无写副作用，恢复只排队新候选。 */
@RestController
@Profile("local")
@RequestMapping("/api/v1/tasks/{taskId}")
public final class VersionController {
    private final VersionService service;

    /**
     * @param service 版本用例，无Mapper访问
     */
    public VersionController(VersionService service) {
        this.service = service;
    }

    /**
     * @param response 私有内容禁止浏览器缓存复用
     */
    @ModelAttribute
    public void noStore(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    /**
     * @param actor 认证
     * @param taskId 任务
     * @param offset 分页
     * @param limit 页大小
     * @return 私有摘要
     */
    @GetMapping("/versions")
    public List<Versions.Summary> list(
            @AuthenticationPrincipal UserIdentity actor,
            @PathVariable UUID taskId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        return service.list(actor, taskId, offset, limit);
    }

    /**
     * @param actor 认证
     * @param taskId 任务
     * @param id 快照
     * @return 历史只读正文
     */
    @GetMapping("/versions/{id}")
    public Versions.Detail detail(
            @AuthenticationPrincipal UserIdentity actor,
            @PathVariable UUID taskId,
            @PathVariable UUID id) {
        return service.detail(actor, taskId, id);
    }

    /**
     * @param actor 认证
     * @param taskId 任务
     * @param from 原快照
     * @param to 新快照
     * @return 对象差异
     */
    @GetMapping("/version-diff")
    public List<VersionDiff.Change> diff(
            @AuthenticationPrincipal UserIdentity actor,
            @PathVariable UUID taskId,
            @RequestParam UUID from,
            @RequestParam UUID to) {
        return service.diff(actor, taskId, from, to);
    }

    /**
     * @param requestId 永久幂等键
     * @param expectedCurrent 当前生效版本
     * @param revision 当前需求
     * @param generation 当前代次
     */
    public record Restore(UUID requestId, UUID expectedCurrent, long revision, long generation) {}

    /**
     * @param actor 认证
     * @param taskId 任务
     * @param id 历史目标
     * @param body 版本预期
     * @return 已排队运行，不表示已恢复生效
     */
    @PostMapping("/versions/{id}/restore")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> restore(
            @AuthenticationPrincipal UserIdentity actor,
            @PathVariable UUID taskId,
            @PathVariable UUID id,
            @RequestBody Restore body) {
        var run =
                service.restore(
                        actor,
                        taskId,
                        body.requestId(),
                        id,
                        body.expectedCurrent(),
                        body.revision(),
                        body.generation());
        return Map.of("runId", run.id(), "status", "QUEUED");
    }
}
