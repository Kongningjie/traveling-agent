-- 应用阶段持久引擎：任务→运行→阶段→事件锁序；JSONB 为领域状态，查询状态由生成列派生。
CREATE TABLE execution_run (
    id UUID PRIMARY KEY REFERENCES execution_budget(run_id),
    user_id UUID NOT NULL, task_id UUID NOT NULL,
    body JSONB NOT NULL, limits JSONB NOT NULL, goal VARCHAR(16) NOT NULL CHECK(goal IN ('PLANNING','CONSULTATION')),
    state TEXT GENERATED ALWAYS AS (body->>'state') STORED,
    continued_from UUID GENERATED ALWAYS AS ((body->>'continuedFromRunId')::uuid) STORED UNIQUE,
    continued_by UUID GENERATED ALWAYS AS ((body->>'continuedByRunId')::uuid) STORED UNIQUE,
    active_since TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(user_id,task_id,id),
    FOREIGN KEY(user_id,task_id) REFERENCES travel_task(user_id,id),
    FOREIGN KEY(user_id,task_id,continued_from) REFERENCES execution_run(user_id,task_id,id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY(user_id,task_id,continued_by) REFERENCES execution_run(user_id,task_id,id) DEFERRABLE INITIALLY DEFERRED,
    CHECK(state IN ('QUEUED','RUNNING','WAITING_USER','WAITING_ACCEPTANCE','PAUSED_BUDGET','PAUSED_QUOTA','SUCCEEDED','FAILED','CANCELLED','SUPERSEDED'))
);
CREATE INDEX execution_run_state_idx ON execution_run(state);
ALTER TABLE travel_task ADD COLUMN current_run_id UUID;
ALTER TABLE travel_task ADD CONSTRAINT current_task_run FOREIGN KEY(user_id,id,current_run_id) REFERENCES execution_run(user_id,task_id,id);

CREATE TABLE execution_start_request (
    user_id UUID NOT NULL, task_id UUID NOT NULL, input_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL, run_id UUID NOT NULL,
    PRIMARY KEY(user_id,task_id,input_id),
    FOREIGN KEY(user_id,task_id,run_id) REFERENCES execution_run(user_id,task_id,id)
);
CREATE TABLE execution_step (
    id UUID PRIMARY KEY, user_id UUID NOT NULL, task_id UUID NOT NULL, run_id UUID NOT NULL,
    body JSONB NOT NULL, definition JSONB NOT NULL,
    state TEXT GENERATED ALWAYS AS (body->>'state') STORED,
    type TEXT GENERATED ALWAYS AS (body->'key'->>'type') STORED,
    round INTEGER GENERATED ALWAYS AS ((body->'key'->>'round')::integer) STORED,
    branch_key TEXT GENERATED ALWAYS AS (body->'key'->>'branchKey') STORED,
    committed_attempt_id UUID, receipt_hash CHAR(64), artifact_id UUID,
    UNIQUE(user_id,task_id,run_id,id), UNIQUE(run_id,type,round,branch_key),
    FOREIGN KEY(user_id,task_id,run_id) REFERENCES execution_run(user_id,task_id,id),
    CHECK(state IN ('READY','RUNNING','COMPLETED','RETRY_WAIT','FAILED','CANCELLED','SUPERSEDED')),
    CHECK((state='COMPLETED')=(committed_attempt_id IS NOT NULL AND receipt_hash IS NOT NULL AND artifact_id IS NOT NULL))
);
CREATE TABLE outbox_event (
    id UUID PRIMARY KEY, user_id UUID NOT NULL, task_id UUID NOT NULL, run_id UUID NOT NULL, step_id UUID NOT NULL,
    causation_id UUID NOT NULL, event_type VARCHAR(32) NOT NULL, branch_key VARCHAR(128) NOT NULL,
    payload_hash CHAR(64) NOT NULL, expected_generation BIGINT NOT NULL, expected_constraint_revision BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'READY' CHECK(status IN ('READY','LEASED','RETRY_WAIT','DONE','OBSOLETE','DEAD')),
    available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count>=0),
    lease_owner VARCHAR(128), lease_until TIMESTAMPTZ, fencing_token BIGINT NOT NULL DEFAULT 0 CHECK(fencing_token>=0),
    last_error_code VARCHAR(48),
    UNIQUE(run_id,causation_id,event_type,branch_key), UNIQUE(user_id,task_id,run_id,step_id),
    UNIQUE(user_id,task_id,run_id,step_id,id),
    FOREIGN KEY(user_id,task_id,run_id,step_id) REFERENCES execution_step(user_id,task_id,run_id,id),
    CHECK(expected_generation>0 AND expected_constraint_revision>0 AND payload_hash ~ '^[0-9a-f]{64}$'),
    CHECK(status<>'LEASED' OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL AND fencing_token>0))
);
CREATE INDEX outbox_ready_idx ON outbox_event(available_at,id) WHERE status IN ('READY','RETRY_WAIT');
CREATE INDEX outbox_expiry_idx ON outbox_event(lease_until,id) WHERE status='LEASED';
CREATE TABLE step_attempt (
    id UUID PRIMARY KEY, user_id UUID NOT NULL, task_id UUID NOT NULL, run_id UUID NOT NULL, step_id UUID NOT NULL,
    event_id UUID NOT NULL, number INTEGER NOT NULL CHECK(number>0), fencing_token BIGINT NOT NULL CHECK(fencing_token>0),
    state VARCHAR(16) NOT NULL CHECK(state IN ('RUNNING','COMPLETED','FAILED','ABANDONED')),
    UNIQUE(step_id,number),
    UNIQUE(user_id,task_id,run_id,step_id,id),
    FOREIGN KEY(user_id,task_id,run_id,step_id,event_id) REFERENCES outbox_event(user_id,task_id,run_id,step_id,id),
    FOREIGN KEY(user_id,task_id,run_id,step_id) REFERENCES execution_step(user_id,task_id,run_id,id)
);
CREATE TABLE artifact_record (
    id UUID PRIMARY KEY, user_id UUID NOT NULL, task_id UUID NOT NULL, run_id UUID NOT NULL, step_id UUID NOT NULL,
    attempt_id UUID NOT NULL, storage_kind VARCHAR(16) NOT NULL CHECK(storage_kind='DB_JSON'),
    content_type TEXT NOT NULL, content_hash CHAR(64) NOT NULL, content_bytes BIGINT NOT NULL CHECK(content_bytes>=0),
    purpose VARCHAR(32) NOT NULL,
    UNIQUE(user_id,task_id,run_id,step_id,id),
    FOREIGN KEY(user_id,task_id,run_id,step_id) REFERENCES execution_step(user_id,task_id,run_id,id),
    FOREIGN KEY(user_id,task_id,run_id,step_id,attempt_id) REFERENCES step_attempt(user_id,task_id,run_id,step_id,id)
);
CREATE TABLE artifact_content (
    artifact_id UUID PRIMARY KEY REFERENCES artifact_record(id), body JSONB NOT NULL
);
ALTER TABLE execution_step ADD CONSTRAINT committed_stage_artifact
    FOREIGN KEY(user_id,task_id,run_id,id,artifact_id) REFERENCES artifact_record(user_id,task_id,run_id,step_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE execution_step ADD CONSTRAINT committed_stage_attempt
    FOREIGN KEY(user_id,task_id,run_id,id,committed_attempt_id) REFERENCES step_attempt(user_id,task_id,run_id,step_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE TRIGGER immutable_stage_artifact BEFORE UPDATE ON artifact_content
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_travel_update();
CREATE TRIGGER immutable_stage_artifact_metadata BEFORE UPDATE ON artifact_record
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_travel_update();

-- 引擎先写最小持久事件；鉴权分页和 SSE 由 DEV-0014 接入，序号在任务锁内分配。
CREATE TABLE run_event (
    user_id UUID NOT NULL, task_id UUID NOT NULL, sequence BIGINT NOT NULL CHECK(sequence>0),
    run_id UUID NOT NULL, step_id UUID, type VARCHAR(48) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(task_id,sequence),
    FOREIGN KEY(user_id,task_id,run_id) REFERENCES execution_run(user_id,task_id,id)
);
CREATE TABLE decision_request (
    id UUID PRIMARY KEY, user_id UUID NOT NULL, task_id UUID NOT NULL, run_id UUID NOT NULL, body JSONB NOT NULL,
    FOREIGN KEY(user_id,task_id,run_id) REFERENCES execution_run(user_id,task_id,id)
);
CREATE TABLE decision_response (
    id UUID PRIMARY KEY, user_id UUID NOT NULL, task_id UUID NOT NULL, run_id UUID NOT NULL,
    decision_id UUID NOT NULL REFERENCES decision_request(id), request_hash CHAR(64) NOT NULL, body JSONB NOT NULL,
    FOREIGN KEY(user_id,task_id,run_id) REFERENCES execution_run(user_id,task_id,id)
);
