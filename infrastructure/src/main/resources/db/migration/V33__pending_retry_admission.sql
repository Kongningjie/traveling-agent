-- 显式重试也是重新进入待处理集合；不能用暂停/重试绕过每用户10请求上限。
-- 既有活动请求续租/状态内推进不再占位，内部投影仍只计一次。调用方先锁用户行。
CREATE OR REPLACE FUNCTION pending_request_admission() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n bigint;
BEGIN
 IF TG_TABLE_NAME='dialogue_input' THEN
  IF NEW.state NOT IN('QUEUED','RUNNING') THEN RETURN NEW;END IF;
  IF TG_OP='UPDATE' THEN
   IF OLD.state IN('QUEUED','RUNNING') THEN RETURN NEW;END IF;
  ELSIF EXISTS(SELECT 1 FROM dialogue_input WHERE user_id=NEW.user_id AND chat_id=NEW.chat_id AND id=NEW.id) THEN RETURN NEW;END IF;
 ELSIF TG_TABLE_NAME='conversation_message' THEN
  IF NEW.status<>'RECEIVED' THEN RETURN NEW;END IF;
  IF TG_OP='UPDATE' THEN
   IF OLD.status='RECEIVED' THEN RETURN NEW;END IF;
  ELSIF EXISTS(SELECT 1 FROM conversation_message WHERE user_id=NEW.user_id AND task_id=NEW.task_id AND id=NEW.id) THEN RETURN NEW;END IF;
  IF EXISTS(SELECT 1 FROM dialogue_input i JOIN dialogue_chat c ON c.user_id=i.user_id AND c.id=i.chat_id WHERE i.user_id=NEW.user_id AND i.id=NEW.id AND c.task_id=NEW.task_id AND i.state IN('QUEUED','RUNNING')) THEN RETURN NEW;END IF;
 ELSE
  IF NEW.kind<>'DOCUMENT_PARSE' OR NEW.state NOT IN('QUEUED','RUNNING','WAITING_DEPENDENCY') THEN RETURN NEW;END IF;
  IF TG_OP='UPDATE' THEN
   IF OLD.state IN('QUEUED','RUNNING','WAITING_DEPENDENCY') THEN RETURN NEW;END IF;
  ELSIF EXISTS(SELECT 1 FROM background_job WHERE user_id=NEW.user_id AND operation_id=NEW.operation_id AND kind=NEW.kind AND branch=NEW.branch) THEN RETURN NEW;END IF;
 END IF;
 SELECT count(*) INTO n FROM (
 SELECT 'CHAT:'||chat_id::text||':'||id::text AS request FROM dialogue_input WHERE user_id=NEW.user_id AND state IN('QUEUED','RUNNING')
 UNION ALL SELECT 'TASK:'||m.task_id::text||':'||m.id::text FROM conversation_message m WHERE m.user_id=NEW.user_id AND m.status='RECEIVED' AND NOT EXISTS(SELECT 1 FROM dialogue_input i JOIN dialogue_chat c ON c.user_id=i.user_id AND c.id=i.chat_id WHERE i.user_id=m.user_id AND i.id=m.id AND c.task_id=m.task_id AND i.state IN('QUEUED','RUNNING'))
 UNION SELECT 'PARSE:'||chat_id::text||':'||operation_id::text FROM background_job WHERE user_id=NEW.user_id AND kind='DOCUMENT_PARSE' AND state IN('QUEUED','RUNNING','WAITING_DEPENDENCY')) p;
 IF n>=10 THEN RAISE EXCEPTION USING ERRCODE='P1001',MESSAGE='USER_PENDING_LIMIT';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER pending_dialogue_retry BEFORE UPDATE OF state ON dialogue_input FOR EACH ROW EXECUTE FUNCTION pending_request_admission();
CREATE TRIGGER pending_legacy_retry BEFORE UPDATE OF status ON conversation_message FOR EACH ROW EXECUTE FUNCTION pending_request_admission();
CREATE TRIGGER pending_document_retry BEFORE UPDATE OF state ON background_job FOR EACH ROW EXECUTE FUNCTION pending_request_admission();
