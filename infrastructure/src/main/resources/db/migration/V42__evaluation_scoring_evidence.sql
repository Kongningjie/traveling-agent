-- 只保存固定程序证据和人工结构化评分，不保存聊天/附件/模型正文；随原run清理。
CREATE TABLE evaluation_scoring_source (
    run_id UUID PRIMARY KEY REFERENCES evaluation_retention_run(run_id) ON DELETE CASCADE,
    source JSONB NOT NULL CHECK (jsonb_typeof(source)='object' AND octet_length(source::text)<=65536),
    fingerprint CHAR(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision>=0),
    current_request_id UUID,
    CHECK ((revision=0)=(current_request_id IS NULL))
);

-- 幂等键跨run唯一；修订追加，不以重试覆盖旧评分或刷新run期限。
CREATE TABLE evaluation_scoring_review (
    request_id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES evaluation_scoring_source(run_id) ON DELETE CASCADE,
    reviewer_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision>0),
    request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    receipt JSONB NOT NULL CHECK (jsonb_typeof(receipt)='object' AND octet_length(receipt::text)<=8192),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (run_id,revision),
    UNIQUE (run_id,request_id)
);
ALTER TABLE evaluation_scoring_source ADD CONSTRAINT evaluation_scoring_current_owner
    FOREIGN KEY (run_id,current_request_id) REFERENCES evaluation_scoring_review(run_id,request_id)
    DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX evaluation_scoring_reviewer ON evaluation_scoring_review(reviewer_id,request_id);
