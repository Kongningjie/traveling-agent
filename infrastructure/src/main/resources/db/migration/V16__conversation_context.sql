-- 消息接收时记录观察到的偏好版本；旧消息保留作审计，但不能跨偏好变更自动召回。
ALTER TABLE conversation_message ADD COLUMN preference_revision BIGINT NOT NULL DEFAULT 0 CHECK(preference_revision>=0);
CREATE INDEX conversation_context_lookup ON conversation_message(user_id,task_id,preference_revision,sequence DESC);
-- 摘要为独立可复用产物，不代表业务阶段已完成；完整来源仍在消息表，不写框架日志恢复。
CREATE TABLE conversation_summary (
    id UUID PRIMARY KEY,user_id UUID NOT NULL,task_id UUID NOT NULL,run_id UUID NOT NULL,
    role VARCHAR(16) NOT NULL CHECK(role IN ('coordinator','research','planner')),
    binding_hash CHAR(64) NOT NULL,body JSONB NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'VALID' CHECK(state IN ('VALID','INVALID')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(user_id,task_id,run_id,role,binding_hash),
    FOREIGN KEY(user_id,task_id,run_id) REFERENCES execution_run(user_id,task_id,id) ON DELETE CASCADE
);
CREATE INDEX conversation_summary_lookup ON conversation_summary(user_id,task_id,run_id,role,state);
