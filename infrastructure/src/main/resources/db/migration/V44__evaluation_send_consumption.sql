-- 发送前必须原子消耗许可，不能反复调用只读资格检查后重复发送。
-- V43如已有未决记录，一律按可能已发送处理；迁移不能把旧UNKNOWN重新开放。
ALTER TABLE evaluation_publication ADD COLUMN send_started BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE evaluation_publication ALTER COLUMN send_started SET DEFAULT false;
ALTER TABLE evaluation_publication ADD CONSTRAINT evaluation_publication_receipt_sent
    CHECK (state NOT IN ('ACCEPTED','CONFIRMED') OR send_started);
