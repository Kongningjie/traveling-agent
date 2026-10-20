-- 最小技术墓碑与独立删除清单水位；不保存被删除正文，不受诊断采样影响。
CREATE TABLE lifecycle_state(id integer PRIMARY KEY CHECK(id=1),instance uuid NOT NULL DEFAULT gen_random_uuid(),sequence bigint NOT NULL DEFAULT 0,hash char(64) NOT NULL DEFAULT repeat('0',64),initialized boolean NOT NULL DEFAULT false);
INSERT INTO lifecycle_state(id) VALUES(1);
CREATE TABLE deletion_tombstone(sequence bigint PRIMARY KEY,id uuid NOT NULL UNIQUE,user_id uuid NOT NULL,kind varchar(16) NOT NULL CHECK(kind IN('CHAT','TASK','DOCUMENT')),target_id uuid NOT NULL,chat_id uuid,task_id uuid,deleted_at timestamptz NOT NULL);
CREATE INDEX deleted_identity ON deletion_tombstone(user_id,kind,target_id);
CREATE TABLE deleted_file(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),user_id uuid NOT NULL,file_key text NOT NULL UNIQUE,document boolean NOT NULL,deleted_at timestamptz NOT NULL,purged_at timestamptz,attempts integer NOT NULL DEFAULT 0,next_at timestamptz NOT NULL DEFAULT clock_timestamp(),code varchar(32) NOT NULL DEFAULT '');
CREATE INDEX deleted_file_pending ON deleted_file(next_at,deleted_at) WHERE purged_at IS NULL;
CREATE FUNCTION reject_deleted_identity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE k text;
BEGIN
 k:=CASE TG_TABLE_NAME WHEN 'dialogue_chat' THEN 'CHAT' WHEN 'travel_task' THEN 'TASK' ELSE 'DOCUMENT' END;
 IF EXISTS(SELECT 1 FROM deletion_tombstone WHERE user_id=NEW.user_id AND ((kind=k AND target_id=NEW.id) OR (k='CHAT' AND chat_id=NEW.id) OR (k='TASK' AND task_id=NEW.id))) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='DELETED_IDENTITY'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER deleted_chat_guard BEFORE INSERT ON dialogue_chat FOR EACH ROW EXECUTE FUNCTION reject_deleted_identity();
CREATE TRIGGER deleted_task_guard BEFORE INSERT ON travel_task FOR EACH ROW EXECUTE FUNCTION reject_deleted_identity();
CREATE TRIGGER deleted_document_guard BEFORE INSERT ON uploaded_document FOR EACH ROW EXECUTE FUNCTION reject_deleted_identity();

-- 原删除和备份重放共用此函数；调用者先持user锁，恢复期间尚未开放任何旧数据。
CREATE FUNCTION apply_deletion(s bigint,request uuid,u uuid,k text,target uuid,c uuid,t uuid,at_time timestamptz) RETURNS void LANGUAGE plpgsql AS $$
DECLARE budgets uuid[];traces uuid[];table_name text;
BEGIN
 IF EXISTS(SELECT 1 FROM deletion_tombstone WHERE id=request) THEN RETURN; END IF;
 INSERT INTO deletion_tombstone VALUES(s,request,u,k,target,c,t,at_time);
 IF k='DOCUMENT' THEN
  -- 已确认业务字段/生效指针保留；来源撤销触发器遮蔽派生回复、引用和框架工作记忆。
  UPDATE user_feedback SET description='来源已删除，说明已清理。',reference_id=NULL,consent=false WHERE user_id=u AND chat_id=c AND (reference_id IS NOT NULL OR target_kind IN('DOCUMENT','ADVICE'));
  UPDATE uploaded_document SET deleted=true,name='已删除附件',deleted_at=COALESCE(deleted_at,at_time),report=NULL WHERE user_id=u AND id=target AND NOT deleted;
  INSERT INTO deleted_file(user_id,file_key,document,deleted_at) SELECT user_id,file_key,true,at_time FROM uploaded_document WHERE user_id=u AND id=target AND NOT purged ON CONFLICT(file_key) DO NOTHING;
  IF t IS NOT NULL THEN
   UPDATE itinerary_candidate SET state='STALE',validation_id=NULL,review_id=NULL WHERE user_id=u AND task_id=t AND state<>'ACCEPTED';
   DELETE FROM conversation_summary WHERE user_id=u AND task_id=t;
   -- 只撤销执行代次，不修改已确认约束或当前生效版本。旧在途结果即使返回也没有提交资格。
   UPDATE travel_task SET execution_generation=execution_generation+1,current_run_id=NULL,lock_version=lock_version+1 WHERE user_id=u AND id=t;
  END IF;
  UPDATE background_job SET state='CANCELLED',token=token+1,lease_until=NULL,active_since=NULL,error='SOURCE_CHANGED' WHERE user_id=u AND chat_id=c AND state NOT IN('COMPLETED','FAILED','CANCELLED','SUPERSEDED');
  UPDATE dialogue_input SET state='WITHDRAWN',token=token+1,lease_until=NULL,active_since=NULL,error='SOURCE_CHANGED' WHERE user_id=u AND chat_id=c AND state IN('QUEUED','RUNNING','PAUSED','CONFIRM');
  RETURN;
 END IF;
 SELECT COALESCE(array_agg(budget_id),ARRAY[]::uuid[]) INTO budgets FROM (
  SELECT budget_id FROM operation_context WHERE user_id=u AND chat_id=c UNION SELECT budget_id FROM dialogue_chat WHERE user_id=u AND id=c UNION SELECT id FROM execution_run WHERE user_id=u AND task_id=t) b;
 SELECT COALESCE(array_agg(trace_id),ARRAY[]::uuid[]) INTO traces FROM (SELECT trace_id FROM operation_context WHERE user_id=u AND chat_id=c UNION SELECT trace_id FROM execution_run WHERE user_id=u AND task_id=t) a;
 INSERT INTO deleted_file(user_id,file_key,document,deleted_at) SELECT user_id,file_key,true,at_time FROM uploaded_document WHERE user_id=u AND chat_id=c AND NOT purged ON CONFLICT(file_key) DO NOTHING;
 INSERT INTO deleted_file(user_id,file_key,document,deleted_at) SELECT user_id,file_key,false,at_time FROM retained_artifact WHERE user_id=u AND task_id=t AND kind='FILE' ON CONFLICT(file_key) DO NOTHING;
 DELETE FROM problem_event WHERE trace_id=ANY(traces) OR id IN(SELECT problem_id FROM user_feedback WHERE user_id=u AND (chat_id=c OR target_scope IN(c,t)));
 DELETE FROM user_feedback WHERE user_id=u AND (chat_id=c OR target_scope IN(c,t));
 DELETE FROM execution_trace_span WHERE user_id=u AND (chat_id=c OR run_id=ANY(budgets) OR operation_id IN(SELECT id FROM operation_context WHERE user_id=u AND chat_id=c));
 DELETE FROM session_context_summary WHERE user_id=u AND (scope_id IN(c,t) OR source_budget=ANY(budgets));
 DELETE FROM source_read_result WHERE user_id=u AND chat_id=c;
 DELETE FROM source_read_attempt WHERE user_id=u AND chat_id=c;
 DELETE FROM plan_advice WHERE user_id=u AND chat_id=c;
 DELETE FROM uploaded_document WHERE user_id=u AND chat_id=c;
 DELETE FROM dialogue_input WHERE user_id=u AND chat_id=c;
 DELETE FROM call_ownership WHERE user_id=u AND budget_id=ANY(budgets);
 DELETE FROM job_attempt WHERE job_id IN(SELECT id FROM background_job WHERE user_id=u AND chat_id=c);
 UPDATE background_job SET dependency_id=NULL WHERE user_id=u AND chat_id=c;
 DELETE FROM background_job WHERE user_id=u AND chat_id=c;
 DELETE FROM operation_context WHERE user_id=u AND chat_id=c;
 DELETE FROM dialogue_chat WHERE user_id=u AND id=c;
 IF t IS NOT NULL THEN
  UPDATE travel_task SET current_run_id=NULL,current_version_id=NULL WHERE user_id=u AND id=t;
  UPDATE itinerary_candidate SET validation_id=NULL,review_id=NULL WHERE user_id=u AND task_id=t;
  UPDATE execution_step SET artifact_id=NULL,committed_attempt_id=NULL WHERE user_id=u AND task_id=t;
  DELETE FROM artifact_content WHERE artifact_id IN(SELECT id FROM artifact_record WHERE user_id=u AND task_id=t);
  FOREACH table_name IN ARRAY ARRAY['evidence_notice','field_evidence','itinerary_acceptance_request','itinerary_acceptance','itinerary_report','itinerary_candidate','negotiation_card','conversation_message','conversation_summary','decision_response','decision_request','run_event','execution_start_request','retained_artifact','artifact_record','step_attempt','outbox_event','execution_step','execution_run','change_request','itinerary_snapshot','travel_constraint_revision'] LOOP
   EXECUTE format('DELETE FROM %I WHERE user_id=$1 AND task_id=$2',table_name) USING u,t;
  END LOOP;
  DELETE FROM travel_idempotency WHERE user_id=u AND (resource_id=t OR strpos(COALESCE(result::text,''),t::text)>0);
  DELETE FROM travel_task WHERE user_id=u AND id=t;
 END IF;
 DELETE FROM tool_evidence WHERE run_id=ANY(budgets);
 DELETE FROM call_meter WHERE run_id=ANY(budgets);
 DELETE FROM execution_budget WHERE run_id=ANY(budgets);
 -- user_preference/preference_request/user_daily_quota均不级联删除或退款。
END $$;

-- 诊断容量按在用逻辑负载计数并保留85/80滞回；回收旧行后可恢复，不要求VACUUM FULL阻塞业务。
ALTER TABLE diagnostic_capacity ADD COLUMN used_bytes bigint NOT NULL DEFAULT 0;
ALTER TABLE diagnostic_capacity ADD COLUMN stopped boolean NOT NULL DEFAULT false;
UPDATE diagnostic_capacity SET used_bytes=(SELECT count(*)*8192 FROM problem_event e)+(SELECT COALESCE(sum(pg_column_size(s)+512),0) FROM execution_trace_span s);
CREATE OR REPLACE FUNCTION bound_diagnostic_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE used bigint;lim bigint;closed boolean;cost bigint;
BEGIN
 IF TG_TABLE_NAME='problem_event' THEN
  IF EXISTS(SELECT 1 FROM problem_event WHERE id=NEW.id) THEN RETURN NULL;END IF;
 ELSE
  IF EXISTS(SELECT 1 FROM execution_trace_span WHERE id=NEW.id) THEN RETURN NULL;END IF;
 END IF;
 PERFORM pg_advisory_xact_lock(78231);
 SELECT used_bytes,limit_bytes,stopped INTO used,lim,closed FROM diagnostic_capacity WHERE id=1 FOR UPDATE;
 cost:=CASE WHEN TG_TABLE_NAME='problem_event' THEN 8192 ELSE pg_column_size(NEW)+512 END;
 IF closed AND used<=lim*0.80 THEN closed:=false; END IF;
 IF used+cost>=lim*0.85 THEN closed:=true; END IF;
 IF closed THEN UPDATE diagnostic_capacity SET stopped=true,dropped=dropped+1 WHERE id=1;RETURN NULL;END IF;
 UPDATE diagnostic_capacity SET used_bytes=used_bytes+cost,stopped=false WHERE id=1;
 RETURN NEW;
END $$;
CREATE FUNCTION release_diagnostic_capacity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE diagnostic_capacity SET used_bytes=GREATEST(0,used_bytes-CASE WHEN TG_TABLE_NAME='problem_event' THEN 8192 ELSE pg_column_size(OLD)+512 END) WHERE id=1;
 RETURN NULL;
END $$;
CREATE TRIGGER problem_capacity_release AFTER DELETE ON problem_event FOR EACH ROW EXECUTE FUNCTION release_diagnostic_capacity();
CREATE TRIGGER trace_capacity_release AFTER DELETE ON execution_trace_span FOR EACH ROW EXECUTE FUNCTION release_diagnostic_capacity();
CREATE TABLE dispatch_turn(user_id uuid PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,last_turn bigint NOT NULL);
CREATE SEQUENCE dispatch_sequence;
CREATE FUNCTION remember_dispatch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='background_job' THEN
  IF NEW.state<>'RUNNING' OR (OLD.state='RUNNING' AND NEW.token=OLD.token) THEN RETURN NEW;END IF;
 ELSE
  IF NEW.status<>'LEASED' OR (OLD.status='LEASED' AND NEW.fencing_token=OLD.fencing_token) THEN RETURN NEW;END IF;
 END IF;
 INSERT INTO dispatch_turn(user_id,last_turn) VALUES(NEW.user_id,nextval('dispatch_sequence')) ON CONFLICT(user_id) DO UPDATE SET last_turn=EXCLUDED.last_turn;
 RETURN NEW;
END $$;
CREATE TRIGGER job_dispatch_turn AFTER UPDATE OF state,token ON background_job FOR EACH ROW EXECUTE FUNCTION remember_dispatch();
CREATE TRIGGER stage_dispatch_turn AFTER UPDATE OF status,fencing_token ON outbox_event FOR EACH ROW EXECUTE FUNCTION remember_dispatch();
