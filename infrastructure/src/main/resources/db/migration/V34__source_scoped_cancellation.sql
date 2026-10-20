-- 单来源撤销不取消无关附件解析；在线删除先通过既有Job用例结算，离线重放使用同一精确集合围栏。
CREATE OR REPLACE FUNCTION apply_deletion(s bigint,request uuid,u uuid,k text,target uuid,c uuid,t uuid,at_time timestamptz) RETURNS void LANGUAGE plpgsql AS $$
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
  UPDATE background_job SET state='CANCELLED',token=token+1,lease_until=NULL,active_since=NULL,error='SOURCE_CHANGED' WHERE user_id=u AND chat_id=c AND (id IN(SELECT job_id FROM uploaded_document WHERE user_id=u AND id=target) OR (kind='CHAT_INPUT' AND operation_id IN(SELECT o.id FROM operation_context o JOIN source_read_attempt a ON a.budget_id=o.budget_id WHERE o.user_id=u AND o.chat_id=c))) AND state NOT IN('COMPLETED','FAILED','CANCELLED','SUPERSEDED');
  UPDATE dialogue_input SET state='WITHDRAWN',token=token+1,lease_until=NULL,active_since=NULL,error='SOURCE_CHANGED' WHERE user_id=u AND chat_id=c AND job_id IN(SELECT id FROM background_job WHERE user_id=u AND chat_id=c AND state='CANCELLED' AND error='SOURCE_CHANGED') AND state IN('QUEUED','RUNNING','PAUSED','CONFIRM');
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

