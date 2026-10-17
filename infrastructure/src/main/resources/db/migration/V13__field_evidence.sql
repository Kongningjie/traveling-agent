-- 字段证据与来源原文同处 PostgreSQL，正文只追加；有效性通知单独追加，不改写历史行程引用。
CREATE TABLE field_evidence (
    id UUID PRIMARY KEY, user_id UUID NOT NULL, task_id UUID NOT NULL,
    run_id UUID NOT NULL, step_id UUID NOT NULL, attempt_id UUID NOT NULL,
    query_key VARCHAR(64) NOT NULL, dependency_hash CHAR(64) NOT NULL,
    body JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(user_id,task_id,id),
    FOREIGN KEY(user_id,task_id,run_id,step_id,attempt_id) REFERENCES step_attempt(user_id,task_id,run_id,step_id,id)
);
CREATE TRIGGER immutable_field_evidence BEFORE UPDATE ON field_evidence
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_travel_update();
CREATE INDEX field_evidence_reuse ON field_evidence(user_id,task_id,query_key,dependency_hash,created_at DESC);
CREATE TABLE evidence_notice (
    id UUID PRIMARY KEY, user_id UUID NOT NULL, task_id UUID NOT NULL,
    evidence_id UUID NOT NULL, reason VARCHAR(24) NOT NULL CHECK(reason IN ('CONFLICT','SUPERSEDED','WITHDRAWN')),
    caused_by UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(evidence_id,reason,caused_by),
    FOREIGN KEY(user_id,task_id,evidence_id) REFERENCES field_evidence(user_id,task_id,id)
);
CREATE TRIGGER immutable_evidence_notice BEFORE UPDATE ON evidence_notice
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_travel_update();
CREATE INDEX evidence_notice_lookup ON evidence_notice(user_id,task_id,evidence_id);
