package com.lawrence.travel.api.event;

import com.lawrence.travel.application.travel.TravelStorageFailure;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/** 订阅建立前返回固定错误，不回显任务参数、SQL 或会话内容。 */
@RestControllerAdvice(assignableTypes = RunEventController.class)
public final class EventErrors {
    /**
     * @param failure 固定应用分类
     * @return 缺失与越权相同 404；未来游标 409
     */
    @ExceptionHandler(TravelStorageFailure.class)
    public ResponseEntity<Map<String, String>> failure(TravelStorageFailure failure) {
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
     * @param ignored 不输出的非法路径或数字
     * @return 固定 400
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> invalid(
            MethodArgumentTypeMismatchException ignored) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_INPUT"));
    }

    /**
     * @param failure 固定传输拒绝
     * @return 不回显底层异常
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> transport(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode())
                .body(Map.of("code", "STREAM_UNAVAILABLE"));
    }
}
