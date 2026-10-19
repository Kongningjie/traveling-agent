-- 问题聚合不保存原文；业务状态/接受/授权审计不受诊断采样和容量影响。
CREATE TABLE problem_group (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),component varchar(32) NOT NULL,code varchar(64) NOT NULL,
 bucket timestamptz NOT NULL,severity varchar(8) NOT NULL,occurrences bigint NOT NULL DEFAULT 1,
 first_at timestamptz NOT NULL,last_at timestamptz NOT NULL,representative uuid NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'NEW' CHECK(state IN('NEW','TRIAGED','IN_PROGRESS','RESOLVED','VERIFIED','REOPENED','DUPLICATE')),
 revision bigint NOT NULL DEFAULT 0,cause varchar(32) NOT NULL DEFAULT 'UNKNOWN',repair varchar(24) NOT NULL DEFAULT '',
 repair_version varchar(120) NOT NULL DEFAULT '',regression varchar(120) NOT NULL DEFAULT '',duplicate_of uuid REFERENCES problem_group(id),
 maintained_by uuid REFERENCES app_user(id),UNIQUE(component,code,bucket)
);
ALTER TABLE problem_event ADD COLUMN group_id uuid REFERENCES problem_group(id) ON DELETE SET NULL;
INSERT INTO problem_group(component,code,bucket,severity,occurrences,first_at,last_at,representative)
 SELECT component,code,date_trunc('hour',created_at),CASE max(CASE severity WHEN 'ERROR' THEN 3 WHEN 'WARN' THEN 2 ELSE 1 END) WHEN 3 THEN 'ERROR' WHEN 2 THEN 'WARN' ELSE 'INFO' END,count(*),min(created_at),max(created_at),(array_agg(id ORDER BY created_at,id))[1]
 FROM problem_event GROUP BY component,code,date_trunc('hour',created_at);
UPDATE problem_event e SET group_id=g.id FROM problem_group g WHERE g.component=e.component AND g.code=e.code AND g.bucket=date_trunc('hour',e.created_at);
CREATE INDEX problem_group_lookup ON problem_group(last_at DESC,id);
CREATE INDEX problem_event_group ON problem_event(group_id,created_at);

CREATE TABLE maintenance_grant(user_id uuid PRIMARY KEY REFERENCES app_user(id),sensitive boolean NOT NULL DEFAULT false);
-- 默认空表；授权只能由受控DB管理操作执行，不提供注册/HTTP提权入口。
CREATE TABLE maintenance_audit(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),actor_id uuid,principal varchar(128) NOT NULL DEFAULT current_user,
 action varchar(32) NOT NULL,target_id uuid,request_id uuid,created_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE FUNCTION audit_maintenance_grant() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO maintenance_audit(action,target_id) VALUES(CASE WHEN TG_OP='DELETE' THEN 'REVOKE_GRANT' ELSE 'SET_GRANT' END,CASE WHEN TG_OP='DELETE' THEN OLD.user_id ELSE NEW.user_id END);
 RETURN NULL;
END $$;
CREATE TRIGGER maintenance_grant_audit AFTER INSERT OR UPDATE OR DELETE ON maintenance_grant FOR EACH ROW EXECUTE FUNCTION audit_maintenance_grant();
CREATE TABLE user_feedback(id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 problem_id uuid NOT NULL REFERENCES problem_event(id) ON DELETE CASCADE,request_hash varchar(64) NOT NULL,
 target_kind varchar(16) NOT NULL,target_scope uuid NOT NULL,target_id uuid NOT NULL,category varchar(24) NOT NULL,
 description varchar(1000) NOT NULL,consent boolean NOT NULL DEFAULT false,reference_id uuid,chat_id uuid,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),CHECK(consent=(reference_id IS NOT NULL)));
CREATE INDEX feedback_private ON user_feedback(user_id,created_at DESC,id);
CREATE TABLE client_issue_receipt(id uuid NOT NULL,user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,code varchar(32) NOT NULL,component varchar(32) NOT NULL,
 problem_id uuid NOT NULL REFERENCES problem_event(id) ON DELETE CASCADE,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(user_id,id));
CREATE TABLE diagnostic_capacity(id integer PRIMARY KEY CHECK(id=1),limit_bytes bigint NOT NULL CHECK(limit_bytes BETWEEN 1 AND 1073741824),dropped bigint NOT NULL DEFAULT 0);
INSERT INTO diagnostic_capacity VALUES(1,1073741824,0);
-- 部署诊断1GiB上限；只丢弃诊断新增，不抛异常中断已完成业务。按事务串行容量检查，不无限积累。
CREATE FUNCTION bound_diagnostic_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE used bigint;
BEGIN
 PERFORM pg_advisory_xact_lock(78231);
 SELECT pg_total_relation_size('problem_event')+pg_total_relation_size('execution_trace_span')+pg_total_relation_size('problem_group')+pg_total_relation_size('user_feedback') INTO used;
 IF used >= (SELECT limit_bytes FROM diagnostic_capacity WHERE id=1) THEN
  UPDATE diagnostic_capacity SET dropped=dropped+1 WHERE id=1;
  RETURN NULL;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER a_problem_capacity BEFORE INSERT ON problem_event FOR EACH ROW EXECUTE FUNCTION bound_diagnostic_insert();
CREATE TRIGGER a_trace_capacity BEFORE INSERT ON execution_trace_span FOR EACH ROW EXECUTE FUNCTION bound_diagnostic_insert();
CREATE FUNCTION aggregate_problem_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM problem_event WHERE id=NEW.id) THEN RETURN NULL; END IF;
 -- 仅固定组件/错误指纹，不使用反馈正文、提示词或原始异常字符串。
 NEW.component:=CASE WHEN NEW.component ~ '^[A-Z0-9_]{1,32}$' THEN NEW.component ELSE 'OTHER' END;
 NEW.code:=CASE WHEN NEW.code ~ '^[A-Z0-9_]{1,64}$' THEN NEW.code ELSE 'UNKNOWN' END;
 IF NEW.code IN('USER_CANCELLED','SOURCE_CHANGED','TRACE_CANCELLED','TRACE_REJECTED','EXTERNAL_DISABLED','QUOTA_UNVERIFIED','CALL_BUDGET_EXHAUSTED') THEN NEW.severity:='INFO'; END IF;
 INSERT INTO problem_group(component,code,bucket,severity,first_at,last_at,representative)
 VALUES(NEW.component,NEW.code,date_trunc('hour',NEW.created_at),NEW.severity,NEW.created_at,NEW.created_at,NEW.id)
 ON CONFLICT(component,code,bucket) DO UPDATE SET occurrences=problem_group.occurrences+1,last_at=GREATEST(problem_group.last_at,EXCLUDED.last_at),severity=CASE WHEN problem_group.severity='ERROR' OR EXCLUDED.severity='ERROR' THEN 'ERROR' WHEN problem_group.severity='WARN' OR EXCLUDED.severity='WARN' THEN 'WARN' ELSE 'INFO' END
 RETURNING id INTO NEW.group_id;
 RETURN NEW;
END $$;
CREATE TRIGGER b_problem_aggregate BEFORE INSERT ON problem_event FOR EACH ROW EXECUTE FUNCTION aggregate_problem_event();
