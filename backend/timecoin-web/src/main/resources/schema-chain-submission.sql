-- 交易签名凭据必须在广播前独立提交；不得在业务回滚时删除。
-- H2与MySQL共用。保存的是签名交易，不包含签名私钥。
CREATE TABLE IF NOT EXISTS chain_submission (
  request_key VARCHAR(66) NOT NULL PRIMARY KEY,
  scope_key VARCHAR(66) NOT NULL,
  nonce_value DECIMAL(20,0) NOT NULL,
  payload_hash VARCHAR(66) NOT NULL,
  tx_hash VARCHAR(66) NOT NULL UNIQUE,
  raw_tx TEXT NOT NULL,
  state VARCHAR(16) NOT NULL,
  ledger_applied SMALLINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_chain_nonce UNIQUE (scope_key, nonce_value)
);
