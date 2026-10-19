-- 建议是分析产物；与聊天/附件绑定，状态回执和业务变更由应用同一事务提交。
CREATE TABLE plan_advice (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    chat_id uuid NOT NULL REFERENCES dialogue_chat(id) ON DELETE CASCADE,
    message_id uuid NOT NULL,
    document_id uuid NOT NULL REFERENCES uploaded_document(id) ON DELETE CASCADE,
    body jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(user_id,chat_id,message_id) REFERENCES dialogue_input(user_id,chat_id,id) ON DELETE CASCADE
);
CREATE INDEX plan_advice_chat ON plan_advice(user_id,chat_id,created_at DESC);

-- 删除任何来源会使聊天资料水位变化；同时清除全部派生建议，避免混合解释残留。
CREATE FUNCTION clear_plan_advice_sources() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.deleted AND NOT OLD.deleted THEN
        DELETE FROM plan_advice WHERE user_id=NEW.user_id AND chat_id=NEW.chat_id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER plan_advice_document_deleted AFTER UPDATE OF deleted ON uploaded_document
    FOR EACH ROW EXECUTE FUNCTION clear_plan_advice_sources();

-- 删除/修改偏好后不保留可重新注入的旧建议，已确认业务变更仍由原旅行数据保存。
CREATE FUNCTION clear_plan_advice_preferences() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.preference_revision<>OLD.preference_revision THEN
        DELETE FROM plan_advice WHERE user_id=NEW.id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER plan_advice_preference_changed AFTER UPDATE OF preference_revision ON app_user
    FOR EACH ROW EXECUTE FUNCTION clear_plan_advice_preferences();
