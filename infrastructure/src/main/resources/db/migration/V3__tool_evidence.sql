-- 工具资源按服务组持久化预占；原文 JSON 与来源留在 PostgreSQL，不依赖工作区文件恢复。
CREATE TABLE traveling_agent.tool_evidence (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES traveling_agent.execution_budget(run_id),
    resource_group TEXT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    status TEXT NOT NULL DEFAULT 'RESERVED' CHECK (status IN ('RESERVED','COMPLETED','UNKNOWN')),
    source_url TEXT,
    retrieved_at TIMESTAMPTZ,
    payload JSONB,
    content_hash TEXT,
    CHECK ((status = 'COMPLETED' AND source_url IS NOT NULL AND retrieved_at IS NOT NULL
        AND payload IS NOT NULL AND content_hash IS NOT NULL)
        OR (status <> 'COMPLETED' AND payload IS NULL))
);
-- 同一服务组快照之后的调用都占一单位；失败也不退还，避免不确定消耗穿透免费边界。
CREATE INDEX tool_evidence_quota_idx ON traveling_agent.tool_evidence(resource_group, started_at);
