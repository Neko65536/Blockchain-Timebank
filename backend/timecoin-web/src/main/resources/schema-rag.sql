CREATE TABLE IF NOT EXISTS rag_revision (
  id INT PRIMARY KEY,
  version_value BIGINT NOT NULL DEFAULT 0
);
INSERT INTO rag_revision(id,version_value) SELECT 1,0 WHERE NOT EXISTS (SELECT 1 FROM rag_revision WHERE id=1);
CREATE TABLE IF NOT EXISTS rag_document (
  id VARCHAR(36) PRIMARY KEY,
  request_key VARCHAR(66) NOT NULL UNIQUE,
  payload_hash VARCHAR(66) NOT NULL,
  title VARCHAR(200) NOT NULL,
  content TEXT NOT NULL,
  audience INT NOT NULL,
  source_type VARCHAR(16) NOT NULL,
  source_ref VARCHAR(64),
  embedding_key VARCHAR(100) NOT NULL,
  state VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  lease_token VARCHAR(36),
  lease_until TIMESTAMP NULL,
  next_attempt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_error VARCHAR(200),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX ix_rag_state (state,next_attempt)
);
CREATE TABLE IF NOT EXISTS rag_chunk (
  id VARCHAR(48) PRIMARY KEY,
  document_id VARCHAR(36) NOT NULL,
  ordinal_value INT NOT NULL,
  content TEXT NOT NULL,
  vector_json MEDIUMTEXT NOT NULL,
  INDEX ix_rag_chunk_document (document_id)
);
CREATE TABLE IF NOT EXISTS rag_outbox (
  document_id VARCHAR(36) PRIMARY KEY,
  next_send TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  send_count INT NOT NULL DEFAULT 0,
  confirmed_at TIMESTAMP NULL,
  INDEX ix_rag_outbox_due (next_send)
);
