-- 用户行是稳定版本及锁，删除偏好不删除该行；主键身份墓碑防止旧建议确认复活。
CREATE TABLE user_preference (
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    id UUID NOT NULL,
    category VARCHAR(32) NOT NULL CHECK (category IN ('pace','diet','interests','transport')),
    city VARCHAR(80) NOT NULL DEFAULT '',
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','CONFIRMED','DELETED')),
    body JSONB NOT NULL,
    PRIMARY KEY(user_id,id)
);
-- 同一类别及范围只能存在一个当前事实或建议；冲突交给用户修改，不并列注入。
CREATE UNIQUE INDEX user_preference_active_scope ON user_preference(user_id,category,city) WHERE state <> 'DELETED';
CREATE TABLE preference_request (
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    command_hash CHAR(64) NOT NULL,
    result JSONB NOT NULL,
    PRIMARY KEY(user_id,request_id)
);
