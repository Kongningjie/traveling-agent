package com.lawrence.travel.infrastructure.identity;

import com.lawrence.travel.application.identity.IdentityFailure;
import com.lawrence.travel.application.port.AccountStore;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;

/** 账号仓储适配；不传播包含 SQL 参数或凭据的底层异常。 */
public final class PostgresAccountStore implements AccountStore {
    private final AccountMapper mapper;

    /**
     * 装配显式 Mapper。
     * @param mapper 同模块账号 Mapper
     */
    public PostgresAccountStore(AccountMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} 单条 INSERT 原子提交，不在事务内做 BCrypt。 */
    @Override
    public boolean insert(UUID id, String loginName, String passwordHash) {
        try {
            return mapper.insert(id, loginName, passwordHash) == 1;
        } catch (DataAccessException failure) {
            throw unavailable();
        }
    }

    /** {@inheritDoc} 不把缺失账号视为依赖故障。 */
    @Override
    public Optional<Account> findByLoginName(String loginName) {
        try {
            return Optional.ofNullable(mapper.findByName(loginName));
        } catch (DataAccessException failure) {
            throw unavailable();
        }
    }

    /** {@inheritDoc} 状态由 PostgreSQL 权威记录决定。 */
    @Override
    public Optional<Account> findById(UUID id) {
        try {
            return Optional.ofNullable(mapper.findById(id));
        } catch (DataAccessException failure) {
            throw unavailable();
        }
    }

    /**
     * @return 不带底层异常链的依赖失败
     */
    private static IdentityFailure unavailable() {
        return new IdentityFailure(IdentityFailure.Code.DEPENDENCY_UNAVAILABLE);
    }
}
