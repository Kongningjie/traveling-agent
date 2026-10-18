-- 阶段产物仍使用既有artifact_record/content；此表保存明确需保留或展示的独立产物。
-- 文本/JSON原始UTF8正文存TEXT以保持字节哈希；JSON语法另行检查，不因大小自动转文件。
CREATE TABLE retained_artifact (
    id UUID PRIMARY KEY, user_id UUID NOT NULL, task_id UUID NOT NULL, run_id UUID NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK(kind IN ('DB_TEXT','DB_JSON','FILE')),
    media_type TEXT NOT NULL, hash CHAR(64) NOT NULL CHECK(hash ~ '^[0-9a-f]{64}$'),
    size BIGINT NOT NULL CHECK(size BETWEEN 0 AND 10485760), file_key TEXT UNIQUE, body TEXT,
    visible BOOLEAN NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(user_id,task_id,run_id) REFERENCES execution_run(user_id,task_id,id),
    CHECK ((kind='FILE' AND body IS NULL AND file_key IS NOT NULL
               AND file_key='users/'||user_id::text||'/tasks/'||task_id::text||'/runs/'||run_id::text||'/'||id::text||'.bin'
               AND ((visible AND media_type IN ('image/png','image/jpeg','application/pdf')) OR (NOT visible AND media_type='application/octet-stream')))
        OR (kind IN ('DB_TEXT','DB_JSON') AND body IS NOT NULL AND file_key IS NULL AND octet_length(body)=size
               AND ((kind='DB_TEXT' AND media_type='text/plain') OR (kind='DB_JSON' AND media_type='application/json')))),
    CHECK (CASE WHEN kind='DB_JSON' THEN body::jsonb IS NOT NULL ELSE true END)
);
CREATE INDEX retained_artifact_owner ON retained_artifact(user_id,id) WHERE visible;
CREATE TRIGGER immutable_retained_artifact BEFORE UPDATE ON retained_artifact
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_travel_update();
