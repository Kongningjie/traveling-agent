-- 本轮明确选中的附件与消息原子保存；名称始终从当前索引读取，不复制删除前文件名。
-- 复合外键保证引用不能跨用户或会话；版本哈希防止重试静默指向不同内容。
ALTER TABLE uploaded_document ADD CONSTRAINT document_owner_chat_identity UNIQUE(user_id,chat_id,id);
CREATE TABLE dialogue_input_document (
 user_id UUID NOT NULL, chat_id UUID NOT NULL, input_id UUID NOT NULL, document_id UUID NOT NULL,
 content_hash CHAR(64) NOT NULL,
 PRIMARY KEY(user_id,chat_id,input_id,document_id),
 FOREIGN KEY(user_id,chat_id,input_id) REFERENCES dialogue_input(user_id,chat_id,id) ON DELETE CASCADE,
 FOREIGN KEY(user_id,chat_id,document_id) REFERENCES uploaded_document(user_id,chat_id,id) ON DELETE CASCADE
);
CREATE INDEX input_document_reverse ON dialogue_input_document(document_id);
