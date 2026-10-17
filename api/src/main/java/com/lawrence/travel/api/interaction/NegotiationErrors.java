package com.lawrence.travel.api.interaction;

import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.domain.rule.DomainViolation;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 固定拒绝类型；不回显用户原文、SQL 或模型产物。 */
@RestControllerAdvice(assignableTypes = {NegotiationController.class, com.lawrence.travel.api.knowledge.EvidenceController.class, com.lawrence.travel.api.itinerary.ItineraryController.class, com.lawrence.travel.api.itinerary.VersionController.class, com.lawrence.travel.api.itinerary.TaskWorkspaceController.class})
public final class NegotiationErrors {
    /**
     * @param failure 固定分类
     * @return 越权与缺失相同，旧回答冲突
     */
    @ExceptionHandler(TravelStorageFailure.class) public ResponseEntity<Map<String, String>> storage(TravelStorageFailure failure) {
        int status = switch (failure.code()) { case NOT_FOUND -> 404; case STALE_VERSION, IDEMPOTENCY_CONFLICT -> 409; case DEPENDENCY_UNAVAILABLE -> 503; default -> 400; };
        return ResponseEntity.status(status).body(Map.of("code", failure.code().name()));
    }
    /**
     * @param ignored 业务形状或 JSON 错误
     * @return 固定错误，不输出解析消息
     */
    @ExceptionHandler({IllegalArgumentException.class, DomainViolation.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> invalid(Exception ignored) { return ResponseEntity.badRequest().body(Map.of("code", "INVALID_INPUT")); }
}
