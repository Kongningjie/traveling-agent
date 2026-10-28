-- 仅保存可观测ID映射及投递状态，不保存正文，不参与业务执行调度。
-- 64位OTEL身份在同一trace内唯一；冲突由适配层重新生成，重启后复用已有映射。
CREATE TABLE langfuse_span_mapping (
 span_id UUID PRIMARY KEY REFERENCES execution_trace_span(id) ON DELETE CASCADE,
 trace_id UUID NOT NULL,
 otel_span_id CHAR(16) NOT NULL CHECK(otel_span_id ~ '^[0-9a-f]{16}$' AND otel_span_id <> '0000000000000000'),
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','ACCEPTED','DROPPED')),
 attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 3),
 UNIQUE(trace_id,otel_span_id)
);
-- 只扫描结束节点，按用户、结束时间和原身份定位；映射随原诊断删除。
CREATE INDEX langfuse_ended_scan ON execution_trace_span(user_id,ended_at,id) WHERE ended_at IS NOT NULL;
