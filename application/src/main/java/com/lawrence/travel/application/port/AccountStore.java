package com.lawrence.travel.application.port;

import com.lawrence.travel.domain.identity.UserIdentity;
import java.util.Optional;
import java.util.UUID;

/** 账号仓储端口，唯一登录名必须由数据库约束保证。 */
public interface AccountStore {
    /**
     * 受限内部凭据，仅供认证用例；禁止返回给 Controller 或写入日志。
     * @param id 账号 ID
     * @param loginName 规范登录名
     * @param passwordHash 自适应哈希，不能是明文
     * @param enabled 账号可用状态
     * @param preferenceRevision 稳定偏好版本
     */
    record Account(
            UUID id,
            String loginName,
            String passwordHash,
            boolean enabled,
            long preferenceRevision) {
        /**
         * @return 仅可向外层返回的非凭据身份
         */
        public UserIdentity identity() {
            return new UserIdentity(id, loginName);
        }

        /**
         * @return 固定脱敏描述，避免默认 record 文本泄露哈希
         */
        @Override
        public String toString() {
            return "Account[redacted]";
        }
    }

    /**
     * 原子创建账号，同规范名竞争至多一方成功；不自动登录。
     * @param id 服务端生成身份
     * @param loginName 规范名
     * @param passwordHash 已计算的哈希，不在数据库事务内计算
     * @return 是否成功插入
     */
    boolean insert(UUID id, String loginName, String passwordHash);

    /**
     * 查找内部凭据。
     * @param loginName 已规范化登录名
     * @return 账号或空
     */
    Optional<Account> findByLoginName(String loginName);

    /**
     * 校验当前账号状态。
     * @param id 从已认证会话取得的账号 ID
     * @return 账号或空
     */
    Optional<Account> findById(UUID id);
}
