package com.lawrence.travel.api.itinerary;

import com.lawrence.travel.application.execution.RunService;
import com.lawrence.travel.application.port.TravelStore;
import com.lawrence.travel.application.travel.TravelStorageService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 工作台私有任务列表与显式取消，页面断线不调用取消。 */
@RestController
@Profile("local")
@RequestMapping("/api/v1/tasks")
public final class TaskWorkspaceController {
    private final TravelStorageService travel;
    private final RunService runs;
    /** @param travel 私有任务 @param runs 取消用例 */
    public TaskWorkspaceController(TravelStorageService travel, RunService runs) { this.travel = travel; this.runs = runs; }
    /** @param response 私有数据禁止缓存 */
    @ModelAttribute public void noStore(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    /** @param actor 认证 @param offset 分页 @param limit 页大小 @return 本人任务摘要 */
    @GetMapping public List<TravelStore.StoredTask> list(@AuthenticationPrincipal UserIdentity actor, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "20") int limit) { return travel.tasks(actor, offset, limit); }
    /** @param actor 认证 @param taskId 任务 @param runId 精确运行 @return 已提交取消结果，只返回安全状态 */
    @PostMapping("/{taskId}/runs/{runId}/cancel") public Map<String, Object> cancel(@AuthenticationPrincipal UserIdentity actor, @PathVariable UUID taskId, @PathVariable UUID runId) { var run = runs.cancel(actor, taskId, runId); return Map.of("runId", run.id(), "state", run.state().name()); }
}
