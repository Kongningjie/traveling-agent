-- 为一期运行追加关联，不更改既有业务状态、计量或恢复语义。
ALTER TABLE execution_run ADD COLUMN request_id UUID;
ALTER TABLE execution_run ADD COLUMN trace_id UUID;
UPDATE execution_run SET request_id=id,trace_id=id;
ALTER TABLE execution_run ALTER COLUMN request_id SET NOT NULL;
ALTER TABLE execution_run ALTER COLUMN trace_id SET NOT NULL;

-- 用户彻底删除时诊断一并清理；call预算清理不会留下孤立归属。
ALTER TABLE execution_trace_span DROP CONSTRAINT execution_trace_span_user_id_fkey;
ALTER TABLE execution_trace_span ADD FOREIGN KEY(user_id) REFERENCES app_user(id) ON DELETE CASCADE;
ALTER TABLE problem_event DROP CONSTRAINT problem_event_user_id_fkey;
ALTER TABLE problem_event ADD FOREIGN KEY(user_id) REFERENCES app_user(id) ON DELETE CASCADE;
ALTER TABLE call_ownership DROP CONSTRAINT call_ownership_budget_id_fkey;
ALTER TABLE call_ownership ADD FOREIGN KEY(budget_id) REFERENCES execution_budget(run_id) ON DELETE CASCADE;
ALTER TABLE user_daily_quota DROP CONSTRAINT user_daily_quota_user_id_fkey;
ALTER TABLE user_daily_quota ADD FOREIGN KEY(user_id) REFERENCES app_user(id) ON DELETE CASCADE;

-- 同事务投影追踪；不读取prompt／异常正文，历史阶段不会被重新执行。
CREATE FUNCTION record_run_trace() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  INSERT INTO execution_trace_span(id,trace_id,user_id,request_id,run_id,kind,state)
  VALUES(NEW.id,NEW.trace_id,NEW.user_id,NEW.request_id,NEW.id,'TRAVEL_RUN',NEW.body->>'state');
 ELSE
  UPDATE execution_trace_span SET state=NEW.body->>'state',
   ended_at=CASE WHEN NEW.body->>'state' IN ('COMPLETED','CANCELLED','SUPERSEDED','FAILED') THEN clock_timestamp() ELSE NULL END
   WHERE id=NEW.id;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER run_trace AFTER INSERT OR UPDATE OF body ON execution_run FOR EACH ROW EXECUTE FUNCTION record_run_trace();

CREATE FUNCTION record_stage_trace() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  INSERT INTO execution_trace_span(id,trace_id,parent_id,user_id,request_id,run_id,token,kind,state,metadata)
  SELECT NEW.id,r.trace_id,r.id,NEW.user_id,r.request_id,r.id,NEW.fencing_token,'STAGE',NEW.state,jsonb_build_object('stepId',NEW.step_id)
  FROM execution_run r WHERE r.id=NEW.run_id;
 ELSE
  UPDATE execution_trace_span SET state=NEW.state,ended_at=clock_timestamp(),code=CASE WHEN NEW.state='FAILED' THEN 'STAGE_FAILED' ELSE '' END WHERE id=NEW.id AND ended_at IS NULL;
  IF NEW.state='FAILED' AND OLD.state<>NEW.state THEN
   INSERT INTO problem_event(id,user_id,trace_id,span_id,request_id,component,code,severity)
   SELECT gen_random_uuid(),NEW.user_id,trace_id,NEW.id,request_id,'STAGE','STAGE_FAILED','ERROR' FROM execution_run WHERE id=NEW.run_id;
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER stage_trace AFTER INSERT OR UPDATE OF state ON step_attempt FOR EACH ROW EXECUTE FUNCTION record_stage_trace();
