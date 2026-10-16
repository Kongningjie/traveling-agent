package com.lawrence.travel.domain.identity;

import com.lawrence.travel.domain.rule.DomainViolation;
import java.io.Serializable;
import java.util.UUID;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.INVALID_INPUT;

/**
 * 服务端认证后的最小身份，可放入 Redis 会话；不含密码、哈希或工具凭证。
 * @param id 账号稳定 UUID
 * @param loginName 规范化登录名
 */
public record UserIdentity(UUID id, String loginName) implements Serializable {
    private static final long serialVersionUID = 1L;
    /** 身份必须来自账号记录，构造不意味着浏览器已被认证。 */
    public UserIdentity {
        DomainViolation.require(id != null && loginName != null && loginName.matches("[a-z0-9_]{3,32}"), INVALID_INPUT);
    }
}
