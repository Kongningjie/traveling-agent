-- 新修改取代解释执行时，保留尚未处理回答的原始语义和来源，不能丢弃其中有效要求。
ALTER TABLE conversation_message ADD COLUMN original_answer JSONB;
