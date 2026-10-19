package com.lawrence.travel.application.support;

/** 固定失败分类，无SQL、反馈正文或凭据异常链。 */
public final class SupportFailure extends RuntimeException {
    /**
     * @param code 固定协议码
     */
    public SupportFailure(String code) {
        super(code, null, false, false);
    }
}
