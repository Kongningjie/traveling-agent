-- 输入先可靠落盘；原始内容与处理状态分列，收讫响应不能冒充需求采纳。
ALTER TABLE travel_task ADD COLUMN message_sequence BIGINT NOT NULL DEFAULT 0 CHECK(message_sequence>=0);
CREATE TABLE conversation_message (
 id UUID PRIMARY KEY,user_id UUID NOT NULL,task_id UUID NOT NULL,sequence BIGINT NOT NULL,
 request_hash CHAR(64) NOT NULL,body JSONB NOT NULL,answer JSONB,status VARCHAR(24) NOT NULL,
 receipt JSONB,processed_run_id UUID,created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(user_id,task_id,sequence),FOREIGN KEY(user_id,task_id) REFERENCES travel_task(user_id,id),
 FOREIGN KEY(user_id,task_id,processed_run_id) REFERENCES execution_run(user_id,task_id,id)
);
CREATE TABLE negotiation_card (
 decision_id UUID NOT NULL REFERENCES decision_request(id),revision BIGINT NOT NULL,user_id UUID NOT NULL,task_id UUID NOT NULL,
 specs JSONB NOT NULL,PRIMARY KEY(decision_id,revision),FOREIGN KEY(user_id,task_id) REFERENCES travel_task(user_id,id)
);
CREATE INDEX pending_conversation ON conversation_message(user_id,task_id,sequence) WHERE status='RECEIVED';
