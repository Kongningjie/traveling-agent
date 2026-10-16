-- 请求缓存之外保留修改的永久业务身份，处理完输入后仍不能用新请求键重复应用。
CREATE TABLE change_request (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    task_id UUID NOT NULL,
    input_sequence BIGINT NOT NULL CHECK (input_sequence > 0),
    state VARCHAR(16) NOT NULL CHECK (state IN ('REGISTERED','APPLIED')),
    applied_revision BIGINT,
    content_hash CHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, task_id, input_sequence),
    CHECK ((state='REGISTERED' AND applied_revision IS NULL AND content_hash IS NULL)
        OR (state='APPLIED' AND applied_revision IS NOT NULL AND content_hash IS NOT NULL AND content_hash ~ '^[0-9a-f]{64}$')),
    FOREIGN KEY (user_id, task_id) REFERENCES travel_task(user_id, id),
    FOREIGN KEY (user_id, task_id, applied_revision)
        REFERENCES travel_constraint_revision(user_id, task_id, revision) DEFERRABLE INITIALLY DEFERRED
);
