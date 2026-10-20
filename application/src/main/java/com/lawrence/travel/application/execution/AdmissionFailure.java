package com.lawrence.travel.application.execution;

/** 固定排队容量错误；不是模型失败或自动重试指令。 */
public final class AdmissionFailure extends RuntimeException {
    /** 创建无原始SQL/正文/凭据的容量拒绝。 */
    public AdmissionFailure() {
        super("USER_PENDING_LIMIT", null, false, false);
    }
}
