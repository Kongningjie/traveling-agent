-- 历史成功节点可能没有完成时间；仅补录关联字段时不能把当前时间当作过去完成时间。
-- 新发生的终态转换记录时间，重复终态更新保留原值（包括历史NULL）。
CREATE OR REPLACE FUNCTION record_run_trace() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  INSERT INTO execution_trace_span(id,trace_id,user_id,request_id,run_id,kind,state,ended_at)
  VALUES(NEW.id,NEW.trace_id,NEW.user_id,NEW.request_id,NEW.id,'TRAVEL_RUN',NEW.body->>'state',
    CASE WHEN NEW.body->>'state' IN ('SUCCEEDED','COMPLETED','CANCELLED','SUPERSEDED','FAILED') THEN clock_timestamp() ELSE NULL END);
 ELSE
  UPDATE execution_trace_span SET state=NEW.body->>'state',
   ended_at=CASE WHEN NEW.body->>'state' IN ('SUCCEEDED','COMPLETED','CANCELLED','SUPERSEDED','FAILED') THEN
     CASE WHEN OLD.body->>'state' IS DISTINCT FROM NEW.body->>'state'
       THEN COALESCE(ended_at,clock_timestamp()) ELSE ended_at END
     ELSE NULL END
   WHERE id=NEW.id;
 END IF;
 RETURN NEW;
END $$;
