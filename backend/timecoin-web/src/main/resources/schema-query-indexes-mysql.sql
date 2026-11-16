-- 已有库也执行索引迁移；按名称判断存在性，不依赖CREATE TABLE IF NOT EXISTS。
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
