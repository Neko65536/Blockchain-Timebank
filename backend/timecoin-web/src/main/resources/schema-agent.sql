CREATE TABLE IF NOT EXISTS agent_task (
 id VARCHAR(36) PRIMARY KEY,
 owner_id INT NOT NULL,
 request_key VARCHAR(100) NOT NULL,
 input_hash VARCHAR(66) NOT NULL,
 state VARCHAR(24) NOT NULL,
 revision INT NOT NULL DEFAULT 0,
 draft TEXT NOT NULL,
 feedback TEXT NOT NULL,
 result_json TEXT,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE (owner_id,request_key)
);
CREATE TABLE IF NOT EXISTS agent_audit (
 id VARCHAR(36) PRIMARY KEY,
 task_id VARCHAR(36) NOT NULL,
 actor_id INT NOT NULL,
 tool VARCHAR(40) NOT NULL,
 detail TEXT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX ix_agent_audit_task(task_id,created_at)
);
CREATE TABLE IF NOT EXISTS agent_review_batch (
 id VARCHAR(36) PRIMARY KEY,
 actor_id INT NOT NULL,
 request_key VARCHAR(100) NOT NULL,
 input_hash VARCHAR(66) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(actor_id,request_key)
);
CREATE TABLE IF NOT EXISTS agent_review (
 id VARCHAR(36) PRIMARY KEY,
 batch_id VARCHAR(36) NOT NULL,
 activity_id INT NOT NULL,
 snapshot_hash VARCHAR(66) NOT NULL,
 snapshot TEXT NOT NULL,
 risk VARCHAR(12) NOT NULL,
 findings TEXT NOT NULL,
 state VARCHAR(24) NOT NULL,
 reviewer_id INT,
 decision VARCHAR(12),
 note VARCHAR(500),
 decided_at TIMESTAMP NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX ix_agent_review_batch(batch_id)
);
