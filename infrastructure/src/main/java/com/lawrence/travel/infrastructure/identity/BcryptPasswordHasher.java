package com.lawrence.travel.infrastructure.identity;

import com.lawrence.travel.application.port.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 每次哈希带独立盐，不记录输入或输出，强度受配置限制。 */
public final class BcryptPasswordHasher implements PasswordHasher {
    private final BCryptPasswordEncoder encoder;
    /**
     * 初始化自适应工作因子。
     * @param strength 10～14，默认装配为 12
     */
    public BcryptPasswordHasher(int strength) {
        if (strength < 10 || strength > 14) throw new IllegalArgumentException("INVALID_PASSWORD_STRENGTH");
        encoder = new BCryptPasswordEncoder(strength);
    }
    /** {@inheritDoc} 原始密码只在调用栈中使用。 */
    @Override public String hash(String raw) { return encoder.encode(raw); }
    /** {@inheritDoc} 畸形哈希不交给编码器日志打印，工作因子限定 10～14 防止异常数据触发过量计算。 */
    @Override public boolean matches(String raw, String encoded) {
        return raw != null && encoded != null && encoded.matches("\\$2[aby]\\$1[0-4]\\$[./A-Za-z0-9]{53}") && encoder.matches(raw, encoded);
    }
}
