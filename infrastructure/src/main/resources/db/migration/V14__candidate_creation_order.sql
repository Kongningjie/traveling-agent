-- 当前运行可能经历有限次修订；按真实创建顺序展示最近草案，不根据随机UUID猜先后。
ALTER TABLE itinerary_candidate ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp();
CREATE INDEX candidate_current_draft ON itinerary_candidate(user_id,task_id,run_id,created_at DESC,id DESC);
