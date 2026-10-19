-- 资料水位独立于作业调度代次；原件删除立即撤销引用、工作文件和可能派生的历史回复。
ALTER TABLE dialogue_chat ADD COLUMN document_revision BIGINT NOT NULL DEFAULT 0;
CREATE TABLE source_read_attempt (
    budget_id UUID NOT NULL REFERENCES execution_budget(run_id) ON DELETE CASCADE,
    token BIGINT NOT NULL, user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    chat_id UUID NOT NULL, revision BIGINT NOT NULL,
    PRIMARY KEY(budget_id,token),
    FOREIGN KEY(user_id,chat_id) REFERENCES dialogue_chat(user_id,id) ON DELETE CASCADE
);
CREATE TABLE source_read_result (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    chat_id UUID NOT NULL, budget_id UUID REFERENCES execution_budget(run_id) ON DELETE CASCADE,
    token BIGINT NOT NULL, revision BIGINT NOT NULL, preference BIGINT NOT NULL,
    document_id UUID REFERENCES uploaded_document(id) ON DELETE CASCADE,
    path VARCHAR(240), body TEXT NOT NULL CHECK(char_length(body)<=100000), hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(user_id,chat_id) REFERENCES dialogue_chat(user_id,id) ON DELETE CASCADE,
    UNIQUE(budget_id,token,path)
);
CREATE INDEX source_read_scope ON source_read_result(user_id,chat_id,created_at);
CREATE INDEX source_attempt_scope ON source_read_attempt(user_id,chat_id);

-- 保守传播：混合回复不能可靠拆开，因此遮蔽首次资料读取起的整个回复链。
-- 原始用户消息、已确认业务需求不删除；未完成操作卡转澄清，不能借旧卡继续授权。
CREATE FUNCTION revoke_document_sources() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.deleted AND NOT OLD.deleted THEN
        UPDATE dialogue_chat SET document_revision=document_revision+1 WHERE user_id=NEW.user_id AND id=NEW.chat_id;
        UPDATE dialogue_input SET reply='资料已删除，相关历史回复已隐藏。', operation=NULL,
            state=CASE WHEN state='CONFIRM' THEN 'CLARIFY' ELSE state END
        WHERE user_id=NEW.user_id AND chat_id=NEW.chat_id AND sequence >= (
            SELECT min(i.sequence) FROM dialogue_input i JOIN background_job bj ON bj.id=i.job_id JOIN operation_context o ON o.id=bj.operation_id
            JOIN source_read_attempt a ON a.budget_id=o.budget_id
            WHERE a.user_id=NEW.user_id AND a.chat_id=NEW.chat_id
        ) AND reply<>'';
        DELETE FROM session_context_summary WHERE user_id=NEW.user_id;
        DELETE FROM source_read_result WHERE user_id=NEW.user_id AND chat_id=NEW.chat_id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER uploaded_document_revoke AFTER UPDATE OF deleted ON uploaded_document
    FOR EACH ROW EXECUTE FUNCTION revoke_document_sources();
