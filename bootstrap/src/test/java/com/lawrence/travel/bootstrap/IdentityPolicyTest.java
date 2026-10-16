package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.identity.IdentityService;
import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.AuthRateLimiter;
import com.lawrence.travel.application.port.PasswordHasher;
import com.lawrence.travel.domain.identity.CredentialRules;
import com.lawrence.travel.infrastructure.identity.BcryptPasswordHasher;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 身份规则与失败分类测试，不访问网络或输出原始密码／哈希。 */
class IdentityPolicyTest {
    /** 大小写一致归一，BCrypt 上界和非法 Unicode 不可被静默截断。 */
    @Test
    void rejectsAmbiguousCredentials() {
        assertThat(CredentialRules.loginName("  Abc_123  ")).isEqualTo("abc_123");
        assertThatThrownBy(() -> CredentialRules.loginName("../abc")).hasMessage("INVALID_INPUT");
        assertThatThrownBy(() -> CredentialRules.requirePassword("界".repeat(25))).hasMessage("INVALID_INPUT");
        assertThatThrownBy(() -> CredentialRules.requirePassword("test-" + Character.toString(0xD800) + "placeholder"))
                .hasMessage("INVALID_INPUT");
    }

    /** 随机盐不产生相同哈希，验证匹配但不把凭据作为断言输出。 */
    @Test
    void hashesWithIndependentSaltAndRejectsMismatch() {
        var hasher = new BcryptPasswordHasher(10);
        String password = "test-placeholder";
        String first = hasher.hash(password); String second = hasher.hash(password);
        assertThat(!first.equals(second)).isTrue();
        assertThat(hasher.matches(password, first)).isTrue();
        assertThat(hasher.matches("other-test-placeholder", first)).isFalse();
        assertThat(hasher.matches(password, "invalid-test-hash")).isFalse();
        assertThat(hasher.matches(password, "$2a$99$" + "a".repeat(53))).isFalse();
    }

    /** 未知、停用及密码不匹配使用同一拒绝类型；未知账号也执行同算法比较。 */
    @Test
    void accountStatusDoesNotLeakThroughAuthenticationErrors() {
        var accounts = mock(AccountStore.class); var hasher = mock(PasswordHasher.class); var rates = mock(AuthRateLimiter.class);
        when(hasher.hash(anyString())).thenReturn("dummy-test-hash");
        when(hasher.matches(anyString(), anyString())).thenReturn(true);
        var service = new IdentityService(accounts, hasher, rates, new IdentityService.Limits(30, 10, 10, Duration.ofMinutes(1)));
        when(accounts.findByLoginName("tester")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.authenticate("tester", "test-placeholder", "loopback")).hasMessage("INVALID_CREDENTIALS");
        verify(hasher).matches("test-placeholder", "dummy-test-hash");
        when(accounts.findByLoginName("tester")).thenReturn(Optional.of(new AccountStore.Account(
                UUID.randomUUID(), "tester", "disabled-test-hash", false, 0)));
        assertThatThrownBy(() -> service.authenticate("tester", "test-placeholder", "loopback")).hasMessage("INVALID_CREDENTIALS");
        when(hasher.matches(anyString(), anyString())).thenReturn(false);
        assertThatThrownBy(() -> service.authenticate("tester", "test-placeholder", "loopback")).hasMessage("INVALID_CREDENTIALS");
    }
}
