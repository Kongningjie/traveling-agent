-- 独立聊天与可靠理解请求；不创建占位旅行，沿用execution_budget和唯一Worker。
CREATE TABLE dialogue_chat (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    title VARCHAR(120) NOT NULL,
    task_id UUID,
    budget_id UUID NOT NULL UNIQUE REFERENCES execution_budget(run_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(user_id,id), UNIQUE(user_id,task_id),
    FOREIGN KEY(user_id,task_id) REFERENCES travel_task(user_id,id)
);
CREATE TABLE dialogue_input (
    id UUID NOT NULL, user_id UUID NOT NULL, chat_id UUID NOT NULL,
    sequence BIGINT GENERATED ALWAYS AS IDENTITY,
    text VARCHAR(4000) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','RUNNING','DONE','CONFIRM','CLARIFY','PAUSED','WITHDRAWN')),
    reply TEXT NOT NULL DEFAULT '', operation JSONB, error VARCHAR(48) NOT NULL DEFAULT '',
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_until TIMESTAMPTZ, token BIGINT NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(user_id,chat_id,id),
    FOREIGN KEY(user_id,chat_id) REFERENCES dialogue_chat(user_id,id)
);
CREATE INDEX dialogue_user_list ON dialogue_chat(user_id,created_at DESC,id);
CREATE INDEX dialogue_pending ON dialogue_input(state,sequence) WHERE state IN ('QUEUED','RUNNING','CLARIFY','PAUSED');
