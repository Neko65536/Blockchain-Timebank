-- 结算与outbox在同一业务事务提交。金额、收付款人和业务编号提交后不可修改。
CREATE TABLE IF NOT EXISTS settlement_order (
  id VARCHAR(36) PRIMARY KEY,
  request_key VARCHAR(66) NOT NULL UNIQUE,
  payload_hash VARCHAR(66) NOT NULL,
  owner_id INT NOT NULL,
  biz_type VARCHAR(32) NOT NULL,
  from_user VARCHAR(64),
  to_user VARCHAR(64) NOT NULL,
  amount BIGINT NOT NULL,
  activity_id INT,
  volunteer_id INT,
  state VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  lease_token VARCHAR(36),
  lease_until TIMESTAMP NULL,
  next_attempt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  tx_hash VARCHAR(66),
  last_error VARCHAR(500),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX ix_settlement_due (state, next_attempt),
  INDEX ix_settlement_owner (owner_id, created_at),
  INDEX ix_settlement_activity (activity_id, volunteer_id, biz_type)
);
CREATE TABLE IF NOT EXISTS settlement_outbox (
  order_id VARCHAR(36) PRIMARY KEY,
  next_send TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  send_count INT NOT NULL DEFAULT 0,
  confirmed_at TIMESTAMP NULL,
  INDEX ix_outbox_due (next_send)
);
-- 按签名账户锁定nonce分配；网络调用在锁外。
CREATE TABLE IF NOT EXISTS chain_signer (
  scope_key VARCHAR(66) PRIMARY KEY
);
CREATE TABLE IF NOT EXISTS cache_revision (
  cache_name VARCHAR(32) PRIMARY KEY,
  version_value BIGINT NOT NULL DEFAULT 0
);
INSERT INTO cache_revision(cache_name,version_value)
SELECT 'notices',0 WHERE NOT EXISTS (SELECT 1 FROM cache_revision WHERE cache_name='notices');
