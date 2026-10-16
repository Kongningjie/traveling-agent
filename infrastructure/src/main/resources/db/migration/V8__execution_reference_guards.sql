-- 补强领域 JSON 身份与关系键的一致性；裸 UUID 不足以建立跨资源关联。
ALTER TABLE execution_run
    ADD COLUMN constraint_revision BIGINT GENERATED ALWAYS AS ((body->>'constraintRevision')::bigint) STORED NOT NULL,
    ADD CONSTRAINT run_constraint_revision FOREIGN KEY(user_id,task_id,constraint_revision)
        REFERENCES travel_constraint_revision(user_id,task_id,revision),
    ADD CONSTRAINT run_body_identity CHECK (
        body->>'id' IS NOT NULL AND body->>'id'=id::text
        AND body->'scope'->>'userId' IS NOT NULL AND body->'scope'->>'userId'=user_id::text
        AND body->'scope'->>'taskId' IS NOT NULL AND body->'scope'->>'taskId'=task_id::text
        AND body->>'generation' IS NOT NULL AND (body->>'generation')::bigint>0 AND constraint_revision>0
    );
ALTER TABLE execution_step
    ALTER COLUMN type SET NOT NULL, ALTER COLUMN round SET NOT NULL, ALTER COLUMN branch_key SET NOT NULL,
    ADD CONSTRAINT step_body_identity CHECK (
        body->>'id' IS NOT NULL AND body->>'id'=id::text
        AND body->>'runId' IS NOT NULL AND body->>'runId'=run_id::text
        AND body->'scope'->>'userId' IS NOT NULL AND body->'scope'->>'userId'=user_id::text
        AND body->'scope'->>'taskId' IS NOT NULL AND body->'scope'->>'taskId'=task_id::text
        AND type IN ('UNDERSTAND','RESEARCH','PLAN','VALIDATE','REVIEW','PRESENT')
        AND round>=0 AND length(branch_key) BETWEEN 1 AND 128
    );
ALTER TABLE decision_request ADD CONSTRAINT decision_same_run UNIQUE(user_id,task_id,run_id,id);
ALTER TABLE decision_response ADD CONSTRAINT answer_same_decision
    FOREIGN KEY(user_id,task_id,run_id,decision_id) REFERENCES decision_request(user_id,task_id,run_id,id);
ALTER TABLE artifact_record ADD CONSTRAINT artifact_hash_format CHECK(content_hash ~ '^[0-9a-f]{64}$');
