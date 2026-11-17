-- 已有库也执行索引迁移；按名称判断存在性，不依赖CREATE TABLE IF NOT EXISTS。
SET @nonce_ddl := (SELECT IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chain_submission' AND COLUMN_NAME='nonce_value' AND IS_NULLABLE='NO'),'ALTER TABLE chain_submission MODIFY COLUMN nonce_value DECIMAL(20,0) NULL','SELECT 1'));
PREPARE nonce_stmt FROM @nonce_ddl;
EXECUTE nonce_stmt;
DEALLOCATE PREPARE nonce_stmt;
-- 旧链凭据无可追溯时间，不以当前时间伪造历史创建时间。
SET @shard_ddl := (SELECT IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chain_submission' AND COLUMN_NAME='created_at'),'SELECT 1','ALTER TABLE chain_submission ADD COLUMN created_at TIMESTAMP NULL'));
PREPARE shard_stmt FROM @shard_ddl;
EXECUTE shard_stmt;
DEALLOCATE PREPARE shard_stmt;
SET @shard_ddl := (SELECT IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='settlement_outbox' AND COLUMN_NAME='created_at'),'SELECT 1','ALTER TABLE settlement_outbox ADD COLUMN created_at TIMESTAMP NULL'));
PREPARE shard_stmt FROM @shard_ddl;
EXECUTE shard_stmt;
DEALLOCATE PREPARE shard_stmt;
UPDATE settlement_outbox o JOIN settlement_order s ON s.id=o.order_id SET o.created_at=s.created_at WHERE o.created_at IS NULL;
SET @query_index_ddl := (SELECT IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='settlement_order' AND INDEX_NAME='ix_settlement_owner'), 'SELECT 1', 'CREATE INDEX ix_settlement_owner ON settlement_order (owner_id, created_at)'));
PREPARE query_index_stmt FROM @query_index_ddl;
EXECUTE query_index_stmt;
DEALLOCATE PREPARE query_index_stmt;

SET @query_index_ddl := (SELECT IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='settlement_order' AND INDEX_NAME='ix_settlement_activity'), 'SELECT 1', 'CREATE INDEX ix_settlement_activity ON settlement_order (activity_id, volunteer_id, biz_type)'));
PREPARE query_index_stmt FROM @query_index_ddl;
EXECUTE query_index_stmt;
DEALLOCATE PREPARE query_index_stmt;

SET @query_index_ddl := (SELECT IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='settlement_order' AND INDEX_NAME='ix_settlement_created'), 'SELECT 1', 'CREATE INDEX ix_settlement_created ON settlement_order (created_at)'));
PREPARE query_index_stmt FROM @query_index_ddl;
EXECUTE query_index_stmt;
DEALLOCATE PREPARE query_index_stmt;

SET @query_index_ddl := (SELECT IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='activity' AND INDEX_NAME='ix_activity_status_updated'), 'SELECT 1', 'CREATE INDEX ix_activity_status_updated ON activity (status, update_time)'));
PREPARE query_index_stmt FROM @query_index_ddl;
EXECUTE query_index_stmt;
DEALLOCATE PREPARE query_index_stmt;

SET @query_index_ddl := (SELECT IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='activity' AND INDEX_NAME='ix_activity_owner_updated'), 'SELECT 1', 'CREATE INDEX ix_activity_owner_updated ON activity (old_id, update_time)'));
PREPARE query_index_stmt FROM @query_index_ddl;
EXECUTE query_index_stmt;
DEALLOCATE PREPARE query_index_stmt;
