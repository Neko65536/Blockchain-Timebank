-- 交易签名凭据必须在广播前独立提交；不得在业务回滚时删除。
-- H2与MySQL共用。保存的是签名交易，不包含签名私钥。
CREATE TABLE IF NOT EXISTS chain_submission (
  request_key VARCHAR(66) NOT NULL PRIMARY KEY,
  scope_key VARCHAR(66) NOT NULL,
  nonce_value DECIMAL(20,0) NULL,
  payload_hash VARCHAR(66) NOT NULL,
  tx_hash VARCHAR(66) NOT NULL UNIQUE,
  raw_tx TEXT NOT NULL,
  state VARCHAR(16) NOT NULL,
  ledger_applied SMALLINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_chain_nonce UNIQUE (scope_key, nonce_value)
);

-- 管理操作数量较少，不按月分片；结算通过settlement_id关联原分片。
CREATE TABLE IF NOT EXISTS governance_proposal (
  id VARCHAR(36) PRIMARY KEY,
  request_key VARCHAR(66) NOT NULL UNIQUE,
  settlement_id VARCHAR(36) UNIQUE,
  action SMALLINT NOT NULL,
  account_id VARCHAR(64) NOT NULL,
  amount BIGINT NOT NULL,
  deadline BIGINT NOT NULL,
  digest VARCHAR(66) NOT NULL,
  contract_address VARCHAR(42) NOT NULL,
  state VARCHAR(16) NOT NULL,
  tx_hash VARCHAR(66),
  created_by INT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS governance_approval (
  proposal_id VARCHAR(36) NOT NULL,
  approver VARCHAR(42) NOT NULL,
  signature VARCHAR(132) NOT NULL,
  submitted_by INT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(proposal_id,approver)
);
