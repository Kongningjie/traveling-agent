package com.lawrence.travel.api.lifecycle;

import com.lawrence.travel.application.lifecycle.LifecycleFailure;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 删除失败不虚称已清理，不回显PG/文件错误，旧附件入口也沿同一失败契约。 */
@RestControllerAdvice(
        assignableTypes = {
            LifecycleController.class,
            com.lawrence.travel.api.document.DocumentController.class
        })
public final class LifecycleErrors {
    /**
     * @param e 固定原因
     * @return 安全失败状态，无原始异常
     */
    @ExceptionHandler(LifecycleFailure.class)
    public ResponseEntity<Map<String, Object>> error(LifecycleFailure e) {
        String c = e.getMessage();
        int status =
                switch (c) {
                    case "DELETE_NOT_FOUND" -> 404;
                    case "DELETE_FORBIDDEN" -> 403;
                    case "DELETE_SCOPE_CHANGED", "DELETE_CONFLICT" -> 409;
                    case "DELETE_INPUT_INVALID" -> 400;
                    default -> 503;
                };
        return ResponseEntity.status(status).body(Map.of("saved", false, "code", c));
    }

    /**
     * @param e 依赖不可用
     * @return 未确认保存，不输出SQL或异常原文
     */
    @ExceptionHandler({com.lawrence.travel.application.travel.TravelStorageFailure.class})
    public ResponseEntity<Map<String, Object>> storage(RuntimeException e) {
        return ResponseEntity.status(503)
                .body(Map.of("saved", false, "code", "DELETE_UNAVAILABLE"));
    }

    /**
     * @param e 无效协议
     * @return 固定输入错误
     */
    @ExceptionHandler({
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Map<String, Object>> invalid(Exception e) {
        return ResponseEntity.badRequest()
                .body(Map.of("saved", false, "code", "DELETE_INPUT_INVALID"));
    }
}
