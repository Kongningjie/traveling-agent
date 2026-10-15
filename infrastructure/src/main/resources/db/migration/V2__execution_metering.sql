-- M0 请求计量：预算身份由应用端建立，后续执行用例复用，不因重试或重启清零。
-- 本迁移不建立旅行任务、账号或完整状态机；不能作为这些业务已完成的证据。
CREATE TABLE traveling_agent.execution_budget (
    run_id UUID PRIMARY KEY,
    model_calls BIGINT NOT NULL DEFAULT 0 CHECK (model_calls >= 0),
    tool_calls BIGINT NOT NULL DEFAULT 0 CHECK (tool_calls >= 0),
    repair_rounds INTEGER NOT NULL DEFAULT 0 CHECK (repair_rounds >= 0),
    active_millis BIGINT NOT NULL DEFAULT 0 CHECK (active_millis >= 0)
);

CREATE TABLE traveling_agent.call_meter (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES traveling_agent.execution_budget(run_id),
    kind TEXT NOT NULL CHECK (kind IN ('MODEL', 'TOOL', 'REPAIR')),
    model_id TEXT NOT NULL,
    region TEXT NOT NULL,
    api TEXT NOT NULL,
    token_upper_bound BIGINT NOT NULL CHECK (token_upper_bound >= 0),
    started_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL DEFAULT 'RESERVED' CHECK (status IN ('RESERVED', 'COMPLETED', 'UNKNOWN')),
    input_tokens BIGINT CHECK (input_tokens >= 0),
    output_tokens BIGINT CHECK (output_tokens >= 0),
    latency_millis BIGINT CHECK (latency_millis >= 0),
    CHECK ((status = 'COMPLETED' AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL)
        OR (status <> 'COMPLETED' AND input_tokens IS NULL AND output_tokens IS NULL))
);

-- 精确型号、地域、接口与核实时间共同限定本项目快照之后的保守用量，不是账户实时余额。
CREATE INDEX call_meter_quota_scope_idx ON traveling_agent.call_meter(model_id, region, api, started_at)
    WHERE kind = 'MODEL';
CREATE INDEX call_meter_run_idx ON traveling_agent.call_meter(run_id);
