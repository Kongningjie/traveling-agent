-- 候选及报告可追溯到权威阶段／尝试；生效指针只在用户接受事务更新。
CREATE TABLE itinerary_candidate (
    id UUID PRIMARY KEY, user_id UUID NOT NULL, task_id UUID NOT NULL, snapshot_id UUID NOT NULL,
    run_id UUID NOT NULL, generation BIGINT NOT NULL CHECK(generation>0), plan_step_id UUID NOT NULL,
    plan_attempt_id UUID NOT NULL, state VARCHAR(16) NOT NULL CHECK(state IN ('NEW','PRESENTED','STALE','ACCEPTED')),
    validation_id UUID, review_id UUID,
    UNIQUE(user_id,task_id,run_id,id),
    FOREIGN KEY(user_id,task_id,snapshot_id) REFERENCES itinerary_snapshot(user_id,task_id,id),
    FOREIGN KEY(user_id,task_id,run_id,plan_step_id,plan_attempt_id) REFERENCES step_attempt(user_id,task_id,run_id,step_id,id)
);
CREATE TABLE itinerary_report (
    id UUID PRIMARY KEY, user_id UUID NOT NULL, task_id UUID NOT NULL, run_id UUID NOT NULL, candidate_id UUID NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK(kind IN ('VALIDATION','REVIEW')), body JSONB NOT NULL,
    producer_step_id UUID NOT NULL, producer_attempt_id UUID NOT NULL, validation_id UUID,
    UNIQUE(user_id,task_id,run_id,candidate_id,id), UNIQUE(candidate_id,kind,producer_attempt_id),
    FOREIGN KEY(user_id,task_id,run_id,candidate_id) REFERENCES itinerary_candidate(user_id,task_id,run_id,id),
    FOREIGN KEY(user_id,task_id,run_id,producer_step_id,producer_attempt_id) REFERENCES step_attempt(user_id,task_id,run_id,step_id,id),
    FOREIGN KEY(user_id,task_id,run_id,candidate_id,validation_id) REFERENCES itinerary_report(user_id,task_id,run_id,candidate_id,id),
    CHECK((kind='REVIEW')=(validation_id IS NOT NULL))
);
ALTER TABLE itinerary_candidate ADD CONSTRAINT candidate_validation_report
    FOREIGN KEY(user_id,task_id,run_id,id,validation_id) REFERENCES itinerary_report(user_id,task_id,run_id,candidate_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE itinerary_candidate ADD CONSTRAINT candidate_review_report
    FOREIGN KEY(user_id,task_id,run_id,id,review_id) REFERENCES itinerary_report(user_id,task_id,run_id,candidate_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE TRIGGER immutable_itinerary_report BEFORE UPDATE ON itinerary_report
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_travel_update();

CREATE TABLE itinerary_acceptance (
    id UUID PRIMARY KEY, user_id UUID NOT NULL, task_id UUID NOT NULL, run_id UUID NOT NULL,
    candidate_id UUID NOT NULL UNIQUE, snapshot_id UUID NOT NULL, previous_version_id UUID,
    command_hash CHAR(64) NOT NULL, result JSONB NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(user_id,task_id,run_id,candidate_id) REFERENCES itinerary_candidate(user_id,task_id,run_id,id),
    FOREIGN KEY(user_id,task_id,snapshot_id) REFERENCES itinerary_snapshot(user_id,task_id,id),
    FOREIGN KEY(user_id,task_id,previous_version_id) REFERENCES itinerary_snapshot(user_id,task_id,id)
);
CREATE TRIGGER immutable_itinerary_acceptance BEFORE UPDATE ON itinerary_acceptance
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_travel_update();
CREATE TABLE itinerary_acceptance_request (
    user_id UUID NOT NULL, task_id UUID NOT NULL, request_id UUID NOT NULL,
    command_hash CHAR(64) NOT NULL, result JSONB NOT NULL,
    PRIMARY KEY(user_id,task_id,request_id),
    FOREIGN KEY(user_id,task_id) REFERENCES travel_task(user_id,id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
