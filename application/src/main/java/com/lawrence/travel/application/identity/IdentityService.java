package com.lawrence.travel.application.identity;

import com.lawrence.travel.application.port.AccountStore;
import com.lawrence.travel.application.port.AuthRateLimiter;
import com.lawrence.travel.application.port.PasswordHasher;
import com.lawrence.travel.domain.identity.CredentialRules;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.rule.DomainViolation;
import java.time.Duration;
import java.util.UUID;
import static com.lawrence.travel.application.identity.IdentityFailure.Code.INVALID_INPUT;
import static com.lawrence.travel.application.identity.IdentityFailure.Code.INVALID_CREDENTIALS;
import static com.lawrence.travel.application.identity.IdentityFailure.Code.REGISTRATION_REJECTED;

/** 账号用例；不依赖 Servlet、Spring Security、Mapper 或 Redis 客户端。 */
public final class IdentityService {
    /**
     * 可配置限流规则，非密码配置。
     * @param addressLogins 每地址登录次数
     * @param accountLogins 每账号登录次数
     * @param addressRegistrations 每地址注册次数
     * @param window 固定窗口
     */
    public record Limits(
            int addressLogins, int accountLogins, int addressRegistrations, Duration window) {
        /** 配额和窗口必须有界且为正。 */
        public Limits {
            if (addressLogins < 1
                    || accountLogins < 1
                    || addressRegistrations < 1
                    || window == null
                    || window.compareTo(Duration.ofSeconds(1)) < 0
                    || window.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("INVALID_AUTH_LIMITS");
            }
        }
    }

    private final AccountStore accounts;
    private final PasswordHasher passwords;
    private final AuthRateLimiter rates;
    private final Limits limits;
    private final String dummyHash;

    /**
     * 为不存在账号准备同算法虚拟哈希，减少未知账号的明显计算时差。
     * @param accounts 账号端口
     * @param passwords 密码端口
     * @param rates 速率端口
     * @param limits 配额配置
     */
    public IdentityService(
            AccountStore accounts, PasswordHasher passwords, AuthRateLimiter rates, Limits limits) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.rates = rates;
        this.limits = limits;
        dummyHash = passwords.hash(UUID.randomUUID().toString());
    }

    /**
     * 注册不自动建立登录会话；唯一性必须由数据库原子决定。
     * @param rawName 输入登录名
     * @param rawPassword 输入密码，不写日志
     * @param address Servlet 受信远端地址，不采信客户端任意转发头
     * @return 新账号非凭据身份
     */
    public UserIdentity register(String rawName, String rawPassword, String address) {
        rates.take("register:address:" + address, limits.addressRegistrations(), limits.window());
        String name = validate(rawName, rawPassword);
        UUID id = UUID.randomUUID();
        String hash = passwords.hash(rawPassword);
        if (!accounts.insert(id, name, hash)) throw new IdentityFailure(REGISTRATION_REJECTED);
        return new UserIdentity(id, name);
    }

    /**
     * 密码认证及停用检查，失败不区分账号缺失、密码错误或停用。
     * @param rawName 输入登录名
     * @param rawPassword 输入密码
     * @param address 服务端连接来源
     * @return 已认证身份，密码不得放入会话
     */
    public UserIdentity authenticate(String rawName, String rawPassword, String address) {
        rates.take("login:address:" + address, limits.addressLogins(), limits.window());
        String name = validate(rawName, rawPassword);
        rates.take("login:account:" + name, limits.accountLogins(), limits.window());
        var account = accounts.findByLoginName(name);
        boolean matches =
                passwords.matches(
                        rawPassword,
                        account.map(AccountStore.Account::passwordHash).orElse(dummyHash));
        if (!matches || account.isEmpty() || !account.get().enabled())
            throw new IdentityFailure(INVALID_CREDENTIALS);
        return account.get().identity();
    }

    /**
     * 每次使用私有身份重新检查账号状态，不能仅信任过期会话快照。
     * @param id 从会话主体取得的 ID
     * @return 当前可用身份
     */
    public UserIdentity current(UUID id) {
        return accounts.findById(id)
                .filter(AccountStore.Account::enabled)
                .map(AccountStore.Account::identity)
                .orElseThrow(() -> new IdentityFailure(INVALID_CREDENTIALS));
    }

    /**
     * 将领域输入拒绝映射为固定应用分类，不传播密码或原始输入。
     * @param name 输入登录名
     * @param password 输入密码
     * @return 规范名
     */
    private static String validate(String name, String password) {
        try {
            CredentialRules.requirePassword(password);
            return CredentialRules.loginName(name);
        } catch (DomainViolation invalid) {
            throw new IdentityFailure(INVALID_INPUT);
        }
    }
}
