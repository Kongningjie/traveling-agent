-- 旧消息的偏好归属未知时保持NULL，不把旧内容重新标为当前偏好。
ALTER TABLE dialogue_input ADD COLUMN preference_revision BIGINT;
CREATE TABLE session_context_summary (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 scope_id UUID NOT NULL, role VARCHAR(24) NOT NULL, dependency_hash CHAR(64) NOT NULL,
 source_budget UUID NOT NULL REFERENCES execution_budget(run_id) ON DELETE CASCADE,
 body JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX session_summary_lookup ON session_context_summary(user_id,scope_id,role,dependency_hash,created_at DESC);
-- 基础安全问题只关联固定策略，不把恶意载荷或被拦截秘密复制到诊断中。
ALTER TABLE problem_event ADD COLUMN policy_version VARCHAR(48);
