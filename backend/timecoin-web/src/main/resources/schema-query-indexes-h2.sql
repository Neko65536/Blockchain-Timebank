CREATE INDEX IF NOT EXISTS ix_settlement_owner ON settlement_order (owner_id, created_at);
CREATE INDEX IF NOT EXISTS ix_settlement_activity ON settlement_order (activity_id, volunteer_id, biz_type);
CREATE INDEX IF NOT EXISTS ix_settlement_created ON settlement_order (created_at);
CREATE INDEX IF NOT EXISTS ix_activity_status_updated ON activity (status, update_time);
CREATE INDEX IF NOT EXISTS ix_activity_owner_updated ON activity (old_id, update_time);
