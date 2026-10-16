-- 本地账号权威存 PostgreSQL；会话仍由 Redis 管理，不修改既有迁移。
CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    login_name VARCHAR(32) NOT NULL UNIQUE CHECK (login_name ~ '^[a-z0-9_]{3,32}$'),
    password_hash VARCHAR(100) NOT NULL CHECK (length(password_hash) >= 20),
    account_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (account_status IN ('ACTIVE','DISABLED')),
    preference_revision BIGINT NOT NULL DEFAULT 0 CHECK (preference_revision >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- UNIQUE 登录名索引服务认证查询；稳定用户行保留偏好版本，删除偏好不删除版本行。
