-- 原代次是事件事实，不随当前任务代次改变；旧记录没有当时状态，不伪造历史状态。
ALTER TABLE run_event ADD COLUMN generation BIGINT;
UPDATE run_event e SET generation=(r.body->>'generation')::bigint FROM execution_run r WHERE r.id=e.run_id;
ALTER TABLE run_event ALTER COLUMN generation SET NOT NULL;
ALTER TABLE run_event ADD CONSTRAINT run_event_generation_positive CHECK(generation>0);
ALTER TABLE run_event ADD COLUMN payload JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE run_event ADD CONSTRAINT run_event_public_payload CHECK(jsonb_typeof(payload)='object');
