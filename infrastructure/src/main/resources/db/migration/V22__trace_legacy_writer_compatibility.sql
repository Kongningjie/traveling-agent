-- 回滚应用或本机仍运行旧镜像时，旧INSERT不提供新增字段也能成功。
-- 新代码始终显式传播可信入口ID；旧写入只能生成独立关联，不伪称恢复了历史HTTP链路。
ALTER TABLE execution_run ALTER COLUMN request_id SET DEFAULT gen_random_uuid();
ALTER TABLE execution_run ALTER COLUMN trace_id SET DEFAULT gen_random_uuid();
