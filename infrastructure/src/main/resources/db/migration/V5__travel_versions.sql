-- 需求与快照为不可变正文；行版本、需求版本和执行代次各自具有独立语义。
CREATE TABLE travel_task (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    title VARCHAR(200) NOT NULL CHECK (length(trim(title)) > 0),
    constraint_revision BIGINT NOT NULL CHECK (constraint_revision > 0),
    execution_generation BIGINT NOT NULL CHECK (execution_generation > 0),
    current_version_id UUID,
    pending_inputs JSONB NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(pending_inputs) = 'object'),
    event_sequence BIGINT NOT NULL DEFAULT 0 CHECK (event_sequence >= 0),
    lock_version BIGINT NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, id)
);
CREATE INDEX travel_task_user_page ON travel_task(user_id, created_at DESC, id);

CREATE TABLE travel_constraint_revision (
    user_id UUID NOT NULL,
    task_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    source TEXT NOT NULL,
    difference TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, task_id, revision),
    FOREIGN KEY (user_id, task_id) REFERENCES travel_task(user_id, id)
);
ALTER TABLE travel_task ADD CONSTRAINT travel_task_current_requirement
    FOREIGN KEY (user_id, id, constraint_revision)
    REFERENCES travel_constraint_revision(user_id, task_id, revision) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE itinerary_snapshot (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    task_id UUID NOT NULL,
    constraint_revision BIGINT NOT NULL,
    base_version_id UUID,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    payload_hash CHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    metadata JSONB NOT NULL CHECK (jsonb_typeof(metadata) = 'object'),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    origin VARCHAR(32) NOT NULL DEFAULT 'DRAFT' CHECK (origin = 'DRAFT'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, task_id, id),
    CHECK (base_version_id IS NULL OR base_version_id <> id),
    FOREIGN KEY (user_id, task_id, constraint_revision)
        REFERENCES travel_constraint_revision(user_id, task_id, revision),
    FOREIGN KEY (user_id, task_id, base_version_id)
        REFERENCES itinerary_snapshot(user_id, task_id, id)
);
CREATE INDEX itinerary_snapshot_task_page ON itinerary_snapshot(user_id, task_id, created_at DESC, id);
ALTER TABLE travel_task ADD CONSTRAINT travel_task_current_snapshot
    FOREIGN KEY (user_id, id, current_version_id) REFERENCES itinerary_snapshot(user_id, task_id, id);

-- 请求记录与业务写入同事务；不自动清理，故保留期不会短于冻结基线的七天。
CREATE TABLE travel_idempotency (
    user_id UUID NOT NULL REFERENCES app_user(id),
    operation VARCHAR(32) NOT NULL,
    resource_id UUID NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    result JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, operation, resource_id, request_key)
);

CREATE FUNCTION reject_immutable_travel_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'IMMUTABLE_TRAVEL_VERSION' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER immutable_constraint_revision BEFORE UPDATE ON travel_constraint_revision
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_travel_update();
CREATE TRIGGER immutable_itinerary_snapshot BEFORE UPDATE ON itinerary_snapshot
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_travel_update();
