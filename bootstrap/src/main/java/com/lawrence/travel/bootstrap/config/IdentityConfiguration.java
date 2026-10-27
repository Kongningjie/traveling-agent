package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.application.identity.IdentityService;
import com.lawrence.travel.application.identity.OwnershipGuard;
import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.AuthRateLimiter;
import com.lawrence.travel.application.port.PasswordHasher;
import com.lawrence.travel.infrastructure.identity.BcryptPasswordHasher;
import com.lawrence.travel.infrastructure.identity.RedisAuthRateLimiter;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;

/** local 身份用例装配；不生成默认账号或输出凭据。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class IdentityConfiguration {
    /**
     * 装配单向密码算法。
     * @param strength 自适应强度
     * @return 密码端口
     */
    @Bean
    PasswordHasher passwordHasher(@Value("${travel.identity.password-strength:12}") int strength) {
        return new BcryptPasswordHasher(strength);
    }

    /**
     * 装配原子限流。
     * @param redis 既有 Redis 连接
     * @return 不降级放行的限流端口
     */
    @Bean
    AuthRateLimiter authRateLimiter(StringRedisTemplate redis) {
        return new RedisAuthRateLimiter(redis, Clock.systemUTC());
    }

    /**
     * 明确认证用例及窗口，不在 Controller 中直调基础设施。
     * @param accounts 账号端口
     * @param passwords 哈希端口
     * @param rates 限流端口
     * @param addressLogins 每地址登录上限
     * @param accountLogins 每账号登录上限
     * @param registrations 每地址注册上限
     * @param window 窗口长度
     * @return 身份用例
     */
    @Bean
    IdentityService identityService(
            AccountStore accounts,
            PasswordHasher passwords,
            AuthRateLimiter rates,
            @Value("${travel.identity.address-logins:30}") int addressLogins,
            @Value("${travel.identity.account-logins:10}") int accountLogins,
            @Value("${travel.identity.address-registrations:10}") int registrations,
            @Value("${travel.identity.rate-window:1m}") Duration window) {
        return new IdentityService(
                accounts,
                passwords,
                rates,
                new IdentityService.Limits(addressLogins, accountLogins, registrations, window));
    }

    /**
     * @return 无状态应用归属守卫，后续资源用例复用
     */
    @Bean
    OwnershipGuard ownershipGuard() {
        return new OwnershipGuard();
    }

    /**
     * Cookie 仅含会话 ID；本地 HTTP 是明确例外，默认配置仍要求 Secure。
     * @param secure 是否要求 HTTPS
     * @return 统一会话 Cookie 策略
     */
    @Bean
    org.springframework.session.web.http.DefaultCookieSerializer sessionCookieSerializer(
            @Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        var cookies = new org.springframework.session.web.http.DefaultCookieSerializer();
        cookies.setCookieName("SESSION");
        cookies.setCookiePath("/");
        cookies.setUseHttpOnlyCookie(true);
        cookies.setSameSite("Lax");
        cookies.setUseSecureCookie(secure);
        return cookies;
    }
}
