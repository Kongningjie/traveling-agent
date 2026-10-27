package com.lawrence.travel.domain.identity;

import com.lawrence.travel.domain.rule.DomainViolation;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.INVALID_INPUT;

/** 账号输入规则；不持有、记录或返回密码。 */
public final class CredentialRules {
    /** 规则工具不允许实例化。 */
    private CredentialRules() {}

    /**
     * 登录名大小写不敏感，首版允许 3～32 位 ASCII 字母、数字及下划线。
     * @param raw 用户输入
     * @return 数据库唯一键使用的规范名
     */
    public static String loginName(String raw) {
        DomainViolation.require(raw != null && raw.length() <= 64, INVALID_INPUT);
        String normalized = raw.strip().toLowerCase(Locale.ROOT);
        DomainViolation.require(normalized.matches("[a-z0-9_]{3,32}"), INVALID_INPUT);
        return normalized;
    }

    /**
     * 密码不裁剪或规范化；拒绝 BCrypt 超长截断和非法 Unicode 的歧义。
     * @param raw 原始密码，仅在请求栈内使用
     */
    public static void requirePassword(String raw) {
        DomainViolation.require(
                raw != null && !raw.isBlank() && raw.length() >= 8 && raw.length() <= 64,
                INVALID_INPUT);
        DomainViolation.require(
                raw.codePoints().noneMatch(c -> c >= 0xD800 && c <= 0xDFFF)
                        && raw.getBytes(StandardCharsets.UTF_8).length <= 72,
                INVALID_INPUT);
    }
}
