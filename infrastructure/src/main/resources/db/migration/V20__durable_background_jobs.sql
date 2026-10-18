-- 二期独立作业：保留旧聊天预算和计量，不重放已完成消息；新消息使用输入级预算。
ALTER TABLE dialogue_chat ADD COLUMN work_revision BIGINT NOT NULL DEFAULT 0 CHECK(work_revision>=0);
CREATE TABLE operation_context (
 id UUID PRIMARY KEY, user_id UUID NOT NULL, chat_id UUID NOT NULL,
 budget_id UUID NOT NULL UNIQUE REFERENCES execution_budget(run_id),
 legacy_budget_id UUID REFERENCES execution_budget(run_id),
 model_limit BIGINT NOT NULL CHECK(model_limit>0), tool_limit BIGINT NOT NULL CHECK(tool_limit>0),
 timeout_millis BIGINT NOT NULL CHECK(timeout_millis>0), active_limit_millis BIGINT NOT NULL CHECK(active_limit_millis>0),
 request_id UUID NOT NULL, trace_id UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(user_id,chat_id,id), FOREIGN KEY(user_id,chat_id) REFERENCES dialogue_chat(user_id,id)
);
CREATE TABLE background_job (
 id UUID PRIMARY KEY, user_id UUID NOT NULL, chat_id UUID NOT NULL, operation_id UUID NOT NULL,
 kind VARCHAR(24) NOT NULL CHECK(kind IN ('CHAT_INPUT','DOCUMENT_PARSE','DOCUMENT_ANALYZE','MEMORY_UPDATE')),
 branch VARCHAR(64) NOT NULL, payload JSONB NOT NULL, payload_hash CHAR(64) NOT NULL,
 dependency_id UUID REFERENCES background_job(id), source_version BIGINT NOT NULL,
 state VARCHAR(24) NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','RUNNING','WAITING_DEPENDENCY','WAITING_USER','PAUSED','COMPLETED','FAILED','CANCELLED','SUPERSEDED')),
 token BIGINT NOT NULL DEFAULT 0 CHECK(token>=0), attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts>=0),
 lease_until TIMESTAMPTZ, active_since TIMESTAMPTZ, error VARCHAR(64) NOT NULL DEFAULT '',
 artifact JSONB, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(user_id,chat_id,operation_id) REFERENCES operation_context(user_id,chat_id,id),
 UNIQUE(user_id,operation_id,kind,branch), CHECK((state='RUNNING')=(lease_until IS NOT NULL))
);
CREATE INDEX job_discovery ON background_job(state,created_at,id) WHERE state IN ('QUEUED','RUNNING','WAITING_DEPENDENCY');
CREATE TABLE job_attempt (
 job_id UUID NOT NULL REFERENCES background_job(id), token BIGINT NOT NULL, span_id UUID NOT NULL,
 started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), ended_at TIMESTAMPTZ,
 outcome VARCHAR(32), PRIMARY KEY(job_id,token)
);
CREATE TABLE user_daily_quota (
 user_id UUID NOT NULL REFERENCES app_user(id), quota_day DATE NOT NULL,
 kind VARCHAR(8) NOT NULL CHECK(kind IN ('MODEL','TOOL','PARSE')),
 reserved BIGINT NOT NULL DEFAULT 0 CHECK(reserved>=0), consumed BIGINT NOT NULL DEFAULT 0 CHECK(consumed>=0),
 PRIMARY KEY(user_id,quota_day,kind)
);
-- call_meter.id与解析attempt唯一绑定；UNKNOWN保留reserved，跨午夜结算回原预留日期。
CREATE TABLE call_ownership (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), budget_id UUID NOT NULL REFERENCES execution_budget(run_id),
 job_id UUID REFERENCES background_job(id), quota_day DATE NOT NULL, kind VARCHAR(8) NOT NULL,
 trace_id UUID NOT NULL, span_id UUID NOT NULL, parent_span_id UUID,
 state VARCHAR(16) NOT NULL DEFAULT 'RESERVED' CHECK(state IN ('RESERVED','COMPLETED','UNKNOWN')),
 FOREIGN KEY(user_id,quota_day,kind) REFERENCES user_daily_quota(user_id,quota_day,kind)
);
CREATE TABLE execution_trace_span (
 id UUID PRIMARY KEY, trace_id UUID NOT NULL, parent_id UUID, user_id UUID NOT NULL REFERENCES app_user(id),
 request_id UUID, chat_id UUID, operation_id UUID, run_id UUID, job_id UUID, token BIGINT,
 kind VARCHAR(32) NOT NULL, state VARCHAR(24) NOT NULL, code VARCHAR(64) NOT NULL DEFAULT '',
 started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), ended_at TIMESTAMPTZ,
 metadata JSONB NOT NULL DEFAULT '{}'
);
CREATE INDEX trace_private_lookup ON execution_trace_span(user_id,trace_id,started_at);
CREATE TABLE problem_event (
 id UUID PRIMARY KEY, user_id UUID REFERENCES app_user(id), trace_id UUID, span_id UUID,
 request_id UUID, component VARCHAR(32) NOT NULL, code VARCHAR(64) NOT NULL,
 severity VARCHAR(8) NOT NULL CHECK(severity IN ('INFO','WARN','ERROR')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX problem_trace ON problem_event(user_id,trace_id,created_at);
-- 全局解析并发资格在所有归属锁之后串行检查，不成为第二套调度器。
CREATE TABLE job_dispatch_guard (id INTEGER PRIMARY KEY);
INSERT INTO job_dispatch_guard VALUES(1);
ALTER TABLE dialogue_input ADD COLUMN job_id UUID REFERENCES background_job(id);
CREATE UNIQUE INDEX dialogue_job_identity ON dialogue_input(job_id) WHERE job_id IS NOT NULL;
