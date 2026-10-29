package com.lawrence.travel.api.evaluation;

import com.lawrence.travel.application.evaluation.EvaluationScoring;
import com.lawrence.travel.application.evaluation.EvaluationScoringService;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.domain.identity.UserIdentity;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 认证账号的私有复核接口；不接受用户提供程序检查结果、归属或综合通过状态。 */
@RestController
@Profile("local & langfuse-scoring-validation")
@RequestMapping("/api/v1/evaluations")
public final class EvaluationScoringController {
    private final EvaluationScoringService service;

    /**
     * 绑定评分应用服务，Controller不访问Mapper。
     *
     * @param service 评分服务
     */
    public EvaluationScoringController(EvaluationScoringService service) {
        this.service = service;
    }

    /**
     * 所有私有状态禁止浏览器缓存。
     *
     * @param response HTTP响应
     */
    @ModelAttribute
    public void noStore(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    /**
     * 查询私有程序门禁与当前评分，未运行或待评不会变成通过。
     *
     * @param actor 服务端登录身份
     * @param run 原运行
     * @return 最小评分状态，不包含执行正文
     */
    @GetMapping("/{run}/review")
    public EvaluationScoring.View read(
            @AuthenticationPrincipal UserIdentity actor, @PathVariable UUID run) {
        return service.read(actor, run);
    }

    /**
     * 提交用户明确确认的分项；沿用登录会话和CSRF防护。
     *
     * @param actor 服务端登录身份
     * @param run 原运行
     * @param command 分项、来源指纹及预期修订
     * @return 幂等保存的评分，不会触发平台发布或模型调用
     */
    @PostMapping("/{run}/review")
    public EvaluationScoring.Receipt review(
            @AuthenticationPrincipal UserIdentity actor,
            @PathVariable UUID run,
            @RequestBody EvaluationScoring.Command command) {
        return service.review(actor, run, command);
    }

    /**
     * 返回固定分类，不把底层SQL或来源JSON写入响应。
     *
     * @param failure 固定存储错误
     * @return 400/404/409/503
     */
    @ExceptionHandler(TravelStorageFailure.class)
    public ResponseEntity<Map<String, String>> storage(TravelStorageFailure failure) {
        int status =
                switch (failure.code()) {
                    case NOT_FOUND -> 404;
                    case STALE_VERSION, IDEMPOTENCY_CONFLICT -> 409;
                    case DEPENDENCY_UNAVAILABLE -> 503;
                    default -> 400;
                };
        return ResponseEntity.status(status).body(Map.of("code", failure.code().name()));
    }

    /**
     * 拒绝无效JSON、UUID或协议字段，不回显请求正文。
     *
     * @param ignored 参数或JSON错误
     * @return 固定400
     */
    @ExceptionHandler({
        IllegalArgumentException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Map<String, String>> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_INPUT"));
    }
}
