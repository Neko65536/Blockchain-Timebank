CREATE INDEX IF NOT EXISTS ix_settlement_owner ON settlement_order (owner_id, created_at);
CREATE INDEX IF NOT EXISTS ix_settlement_activity ON settlement_order (activity_id, volunteer_id, biz_type);
CREATE INDEX IF NOT EXISTS ix_settlement_created ON settlement_order (created_at);
CREATE INDEX IF NOT EXISTS ix_activity_status_updated ON activity (status, update_time);
CREATE INDEX IF NOT EXISTS ix_activity_owner_updated ON activity (old_id, update_time);
-- 历史链凭据创建时间不可推测，保留NULL，分片切换前需离线核验。
ALTER TABLE chain_submission ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NULL;
ALTER TABLE settlement_outbox ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NULL;
UPDATE settlement_outbox SET created_at=(SELECT s.created_at FROM settlement_order s WHERE s.id=settlement_outbox.order_id) WHERE created_at IS NULL;
