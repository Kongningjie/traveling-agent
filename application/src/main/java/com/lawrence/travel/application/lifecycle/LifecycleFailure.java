package com.lawrence.travel.application.lifecycle;

/** 固定生命周期错误，禁止携带SQL、原文、路径或凭据。 */
public final class LifecycleFailure extends RuntimeException {
    /**
     * @param code 固定代码
     */
    public LifecycleFailure(String code) {
        super(code, null, false, false);
    }
}
