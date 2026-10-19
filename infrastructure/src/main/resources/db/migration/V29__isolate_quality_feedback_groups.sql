-- 相同质量分类不等于同一根因；每条主动反馈独立建单，维护者可显式标记重复。
ALTER TABLE problem_group ADD COLUMN fingerprint varchar(64) NOT NULL DEFAULT '';
ALTER TABLE problem_group DROP CONSTRAINT problem_group_component_code_bucket_key;
ALTER TABLE problem_group ADD UNIQUE(component,code,bucket,fingerprint);
INSERT INTO problem_group(component,code,bucket,severity,occurrences,first_at,last_at,representative,state,revision,cause,repair,repair_version,regression,maintained_by,fingerprint)
SELECT g.component,g.code,g.bucket,g.severity,1,e.created_at,e.created_at,e.id,g.state,g.revision,g.cause,g.repair,g.repair_version,g.regression,g.maintained_by,e.id::text
FROM problem_event e JOIN problem_group g ON g.id=e.group_id WHERE g.component='FEEDBACK';
UPDATE problem_event e SET group_id=g.id FROM problem_group g WHERE g.component='FEEDBACK' AND g.fingerprint=e.id::text;
UPDATE problem_group g SET occurrences=0 WHERE component='FEEDBACK' AND fingerprint='' AND NOT EXISTS(SELECT 1 FROM problem_event e WHERE e.group_id=g.id);
CREATE OR REPLACE FUNCTION aggregate_problem_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE key varchar(64);
BEGIN
 IF EXISTS(SELECT 1 FROM problem_event WHERE id=NEW.id) THEN RETURN NULL; END IF;
 NEW.component:=CASE WHEN NEW.component ~ '^[A-Z0-9_]{1,32}$' THEN NEW.component ELSE 'OTHER' END;
 NEW.code:=CASE WHEN NEW.code ~ '^[A-Z0-9_]{1,64}$' THEN NEW.code ELSE 'UNKNOWN' END;
 IF NEW.code IN('USER_CANCELLED','SOURCE_CHANGED','TRACE_CANCELLED','TRACE_REJECTED','EXTERNAL_DISABLED','QUOTA_UNVERIFIED','CALL_BUDGET_EXHAUSTED') THEN NEW.severity:='INFO'; END IF;
 key:=CASE WHEN NEW.component='FEEDBACK' THEN NEW.id::text ELSE '' END;
 INSERT INTO problem_group(component,code,bucket,fingerprint,severity,first_at,last_at,representative)
 VALUES(NEW.component,NEW.code,date_trunc('hour',NEW.created_at),key,NEW.severity,NEW.created_at,NEW.created_at,NEW.id)
 ON CONFLICT(component,code,bucket,fingerprint) DO UPDATE SET occurrences=problem_group.occurrences+1,last_at=GREATEST(problem_group.last_at,EXCLUDED.last_at),severity=CASE WHEN problem_group.severity='ERROR' OR EXCLUDED.severity='ERROR' THEN 'ERROR' WHEN problem_group.severity='WARN' OR EXCLUDED.severity='WARN' THEN 'WARN' ELSE 'INFO' END
 RETURNING id INTO NEW.group_id;
 RETURN NEW;
END $$;
