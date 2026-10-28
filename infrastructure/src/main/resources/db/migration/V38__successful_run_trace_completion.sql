-- 修复诊断投影把运行成功误写为COMPLETED判断的问题；领域实际终态为SUCCEEDED。
-- 只修复后续状态投影，不更改运行权威状态、不为历史缺失完成时间编造时间戳。
CREATE OR REPLACE FUNCTION record_run_trace() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  INSERT INTO execution_trace_span(id,trace_id,user_id,request_id,run_id,kind,state,ended_at)
  VALUES(NEW.id,NEW.trace_id,NEW.user_id,NEW.request_id,NEW.id,'TRAVEL_RUN',NEW.body->>'state',
    CASE WHEN NEW.body->>'state' IN ('SUCCEEDED','COMPLETED','CANCELLED','SUPERSEDED','FAILED') THEN clock_timestamp() ELSE NULL END);
 ELSE
  UPDATE execution_trace_span SET state=NEW.body->>'state',
   ended_at=CASE WHEN NEW.body->>'state' IN ('SUCCEEDED','COMPLETED','CANCELLED','SUPERSEDED','FAILED')
     THEN COALESCE(ended_at,clock_timestamp()) ELSE NULL END
   WHERE id=NEW.id;
 END IF;
 RETURN NEW;
END $$;
