package com.lawrence.travel.api.lifecycle;

/** 排队容量拒绝不抹掉既有数据，不自动重发，不回显SQL。 */
@org.springframework.web.bind.annotation.RestControllerAdvice
public final class AdmissionControllerErrors {
    /**
     * @param failure 固定容量拒绝
     * @return 仍允许用户读取、停止和删除
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(
            com.lawrence.travel.application.execution.AdmissionFailure.class)
    public org.springframework.http.ResponseEntity<java.util.Map<String, String>> capacity(
            com.lawrence.travel.application.execution.AdmissionFailure failure) {
        return org.springframework.http.ResponseEntity.status(429)
                .body(java.util.Map.of("code", "USER_PENDING_LIMIT"));
    }
}
