package com.lawrence.travel.infrastructure.identity;

import com.lawrence.travel.application.identity.IdentityFailure;
import com.lawrence.travel.application.port.AuthRateLimiter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import static com.lawrence.travel.application.identity.IdentityFailure.Code.DEPENDENCY_UNAVAILABLE;
import static com.lawrence.travel.application.identity.IdentityFailure.Code.RATE_LIMITED;

/** Redis 原子固定窗口，桶键先哈希，故障不自动降级为无限放行。 */
public final class RedisAuthRateLimiter implements AuthRateLimiter {
    private static final DefaultRedisScript<Long> SCRIPT =
            new DefaultRedisScript<>(
                    "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('PEXPIRE',KEYS[1],ARGV[1]); end; return n",
                    Long.class);
    private final StringRedisTemplate redis;
    private final Clock clock;

    /**
     * 装配会话共用的 Redis 连接和服务端时钟。
     * @param redis 字符串 Redis 适配
     * @param clock 固定窗口时钟
     */
    public RedisAuthRateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** {@inheritDoc} 窗口键附加时间片并自动过期，不存原始账号或来源。 */
    @Override
    public void take(String bucket, int maximum, Duration window) {
        if (bucket == null || maximum < 1 || window == null || window.toMillis() < 1000)
            throw new IllegalArgumentException("INVALID_AUTH_LIMITS");
        String key =
                "traveling-agent:auth-rate:"
                        + digest(bucket)
                        + ":"
                        + Math.floorDiv(clock.millis(), window.toMillis());
        Long count;
        try {
            count = redis.execute(SCRIPT, List.of(key), Long.toString(window.toMillis() + 1000));
        } catch (RuntimeException failure) {
            throw new IdentityFailure(DEPENDENCY_UNAVAILABLE);
        }
        if (count == null) throw new IdentityFailure(DEPENDENCY_UNAVAILABLE);
        if (count > maximum) throw new IdentityFailure(RATE_LIMITED);
    }

    /**
     * 哈希桶输入，拒绝向 Redis 键泄露登录名和 IP。
     * @param value 内存桶标识
     * @return SHA256
     */
    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("HASH_UNAVAILABLE");
        }
    }
}
