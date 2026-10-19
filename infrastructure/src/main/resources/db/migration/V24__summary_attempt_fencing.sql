-- 摘要是attempt缓存；仅源业务已提交或当前同一有效尝试可读取，取消／失败／重领不能hydrate旧缓存。
ALTER TABLE session_context_summary ADD COLUMN source_token BIGINT NOT NULL DEFAULT 0;
ALTER TABLE session_context_summary ADD COLUMN source_attempt UUID;
