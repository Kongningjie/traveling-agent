-- 原件不可变；删除标记立即生效，容量在物理清理完成后才释放。
CREATE TABLE uploaded_document (
 id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_user(id),chat_id UUID NOT NULL REFERENCES dialogue_chat(id),
 job_id UUID NOT NULL UNIQUE REFERENCES background_job(id),name VARCHAR(160) NOT NULL,kind VARCHAR(8) NOT NULL,
 hash CHAR(64) NOT NULL,size BIGINT NOT NULL CHECK(size>0 AND size<=10485760),file_key TEXT NOT NULL UNIQUE,
 deleted BOOLEAN NOT NULL DEFAULT false,purged BOOLEAN NOT NULL DEFAULT false,report JSONB,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),deleted_at TIMESTAMPTZ
);
CREATE INDEX document_scope ON uploaded_document(user_id,chat_id,created_at);
