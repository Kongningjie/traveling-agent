-- 来源删除保留所属聊天/旅行。仅整聊天或整旅行墓碑禁止旧身份重建；
-- INSERT ... ON CONFLICT的BEFORE触发器也会执行，不能把来源墓碑误当成整聊天删除。
CREATE OR REPLACE FUNCTION reject_deleted_identity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE k text;
BEGIN
 k:=CASE TG_TABLE_NAME WHEN 'dialogue_chat' THEN 'CHAT' WHEN 'travel_task' THEN 'TASK' ELSE 'DOCUMENT' END;
 IF EXISTS(SELECT 1 FROM deletion_tombstone WHERE user_id=NEW.user_id AND
   ((kind=k AND target_id=NEW.id) OR
    (kind IN('CHAT','TASK') AND ((k='CHAT' AND chat_id=NEW.id) OR (k='TASK' AND task_id=NEW.id)))))
 THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='DELETED_IDENTITY';END IF;
 RETURN NEW;
END $$;
