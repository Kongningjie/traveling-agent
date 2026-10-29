-- LF-3台账准备：原始时间和配置不可被重导出刷新；不保存正文、密钥或原始URL。
-- 不级联账号/运行删除：最小删除标记须保留，避免恢复或同ID重放复活。
CREATE TABLE evaluation_retention_run (
    run_id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    dataset_hash CHAR(64) NOT NULL CHECK (dataset_hash ~ '^[0-9a-f]{64}$'),
    configuration_hash CHAR(64) NOT NULL CHECK (configuration_hash ~ '^[0-9a-f]{64}$'),
    revoked_at TIMESTAMPTZ,
    CHECK (expires_at = started_at + INTERVAL '720 hours')
);

-- 回执属于副本代次；迟到数据增加代次并清空旧回执，不恢复run访问权限。
CREATE TABLE evaluation_retention_copy (
    run_id UUID NOT NULL REFERENCES evaluation_retention_run(run_id),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('TRACE','DATASET','SCORE','OBJECT','CACHE','LOCAL','BACKUP')),
    locator_hash CHAR(64) NOT NULL CHECK (locator_hash ~ '^[0-9a-f]{64}$'),
    generation BIGINT NOT NULL DEFAULT 0 CHECK (generation >= 0),
    evidence_hash CHAR(64) CHECK (evidence_hash ~ '^[0-9a-f]{64}$'),
    attested_at TIMESTAMPTZ,
    PRIMARY KEY (run_id, kind, locator_hash),
    CHECK ((attested_at IS NULL) = (evidence_hash IS NULL))
);

-- 到期及主动撤销统一按更早时间处理；失败任务不因重试延后24小时期限。
CREATE INDEX evaluation_retention_deadline ON evaluation_retention_run
    ((LEAST(expires_at, revoked_at)));
CREATE INDEX evaluation_retention_pending ON evaluation_retention_copy(run_id)
    WHERE attested_at IS NULL;
