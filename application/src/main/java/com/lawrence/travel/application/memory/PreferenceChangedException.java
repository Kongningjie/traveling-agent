package com.lawrence.travel.application.memory;

/** 调用期间权威偏好已变化；执行器按原运行预算有界重试，不提交旧产物或泄露正文。 */
public final class PreferenceChangedException extends RuntimeException {
    /** 固定原因码，无用户文本；达到执行重试上限时由引擎保留可追溯的失败状态。 */
    public PreferenceChangedException() {
        super("PREFERENCE_CHANGED");
    }
}
