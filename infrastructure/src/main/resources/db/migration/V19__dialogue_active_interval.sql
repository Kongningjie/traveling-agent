-- 独立咨询活跃区间随领取持久化，失联恢复及撤回保守结算，避免崩溃重启绕过时间预算。
ALTER TABLE dialogue_input ADD COLUMN active_since TIMESTAMPTZ;
