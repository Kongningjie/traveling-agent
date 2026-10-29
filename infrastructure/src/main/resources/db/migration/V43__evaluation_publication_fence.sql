-- 最小删除标记与运行明细分离；不保存账号、正文、分数、数据或配置指纹，不随run删除级联。
-- 恢复流程必须先加载删除标记并保持访问关闭；本迁移不代表备份回放/平台物理清理已经完成。
CREATE TABLE evaluation_deletion_marker (
    run_id UUID PRIMARY KEY,
    blocked_at TIMESTAMPTZ NOT NULL
);

-- 为历史已阻断运行补标记，不把迁移时间当作新的保留起点。
INSERT INTO evaluation_deletion_marker(run_id,blocked_at)
SELECT run_id,LEAST(expires_at,revoked_at) FROM evaluation_retention_run
WHERE revoked_at IS NOT NULL OR expires_at<=clock_timestamp();

-- 一次发布登记对应一次尝试。UNKNOWN从提交登记开始生效，不能因进程重启或换attempt自动重发。
-- 这里只存指纹，不存待发送正文；未实现实际发送器，也不提供重置尝试状态的入口。
CREATE TABLE evaluation_publication (
    run_id UUID NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('TRACE','DATASET','SCORE')),
    locator_hash CHAR(64) NOT NULL CHECK (locator_hash ~ '^[0-9a-f]{64}$'),
    payload_hash CHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    attempt_id UUID NOT NULL UNIQUE,
    state VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' CHECK (state IN ('UNKNOWN','ACCEPTED','CONFIRMED','BLOCKED')),
    evidence_hash CHAR(64) CHECK (evidence_hash ~ '^[0-9a-f]{64}$'),
    late_reported BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY(run_id,kind,locator_hash),
    FOREIGN KEY(run_id,kind,locator_hash) REFERENCES evaluation_retention_copy(run_id,kind,locator_hash),
    CHECK (state NOT IN ('ACCEPTED','CONFIRMED') OR evidence_hash IS NOT NULL),
    CHECK (NOT late_reported OR state='BLOCKED')
);
