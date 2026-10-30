package com.lawrence.travel.bootstrap;

import java.util.Map;
import java.util.regex.Pattern;

/** 仅为离线/授权定位报告提取长度和布尔特征，不保存正文、不改变SafetyGate的允许/拒绝决定。 */
final class ToolSafetyDiagnostics {
    /** 静态诊断入口，不持有响应。 */
    private ToolSafetyDiagnostics() {}

    /**
     * 记录安全检查可能命中的类别；这些特征只辅助定位，不作为新的安全策略。
     *
     * @param text 仅进程内使用的工具输出
     * @return 不含正文、地址、凭据或匹配片段的固定指标
     */
    static Map<String, Object> inspect(String text) {
        String value = text == null ? "" : text;
        return Map.of(
                "characters",
                value.length(),
                "missing",
                text == null,
                "oversized",
                value.length() > 262144,
                "nul",
                value.indexOf(0) >= 0,
                "activeMarkup",
                Pattern.compile("(?is)<\\s*(script|iframe|object)\\b").matcher(value).find(),
                "activeUri",
                Pattern.compile("(?is)(?:javascript|vbscript)\\s*:|data\\s*:\\s*text/html")
                        .matcher(value)
                        .find(),
                "eventAttribute",
                Pattern.compile("(?is)on(?:error|load)\\s*=").matcher(value).find(),
                "credentialLike",
                Pattern.compile(
                                "(?is)authorization\\s*[:=]\\s*bearer\\s+[a-zA-Z0-9._-]{8,}|\\bsk-[a-zA-Z0-9_-]{24,}")
                        .matcher(value)
                        .find());
    }
}
