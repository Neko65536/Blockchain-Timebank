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
 UNIQUE (owner_id,request_key),
 INDEX ix_agent_task_owner(owner_id,created_at)
);
-- 独立 Agent trace 实体：一次 run 一行，存计量与哈希，不存提示词/思维/原文。
CREATE TABLE IF NOT EXISTS agent_trace (
 trace_id VARCHAR(36) PRIMARY KEY,
 actor_id INT NOT NULL,
 status VARCHAR(16) NOT NULL,
 prompt_hash VARCHAR(66),
 result_hash VARCHAR(66),
 guardrail_result VARCHAR(24),
 model_calls INT NOT NULL DEFAULT 0,
 tool_calls INT NOT NULL DEFAULT 0,
 tool_failures INT NOT NULL DEFAULT 0,
 input_tokens BIGINT NOT NULL DEFAULT 0,
 output_tokens BIGINT NOT NULL DEFAULT 0,
 duration_ms BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX ix_agent_trace_actor(actor_id,created_at)
);

-- 用户协议版本留痕：版本文本哈希 + 用户同意记录（版本号 + 时间戳 + 来源哈希）。
CREATE TABLE IF NOT EXISTS agreement_version (
 version VARCHAR(40) PRIMARY KEY,
 title VARCHAR(120) NOT NULL,
 content_hash VARCHAR(66) NOT NULL,
 effective_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 active SMALLINT NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS user_agreement_accept (
 id VARCHAR(36) PRIMARY KEY,
 user_id INT NOT NULL,
 version VARCHAR(40) NOT NULL,
 accepted SMALLINT NOT NULL,
 accepted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 source_hash VARCHAR(66),
 UNIQUE (user_id,version)
);
INSERT INTO agreement_version(version,title,content_hash,active)
 SELECT 'v1','时光颐养时间银行用户协议与隐私说明',
        '0x0000000000000000000000000000000000000000000000000000000000000001',1
 WHERE NOT EXISTS (SELECT 1 FROM agreement_version WHERE version='v1');

-- N5 链上事件离线索引：后台增量同步，用户查询只读索引，避免 eth_getLogs 创世扫描打穿节点。
CREATE TABLE IF NOT EXISTS chain_event_index (
 tx_hash VARCHAR(66) NOT NULL,
 log_index VARCHAR(32) NOT NULL DEFAULT '0',
 event_type VARCHAR(16) NOT NULL,
 block_number VARCHAR(32),
 event_time VARCHAR(32),
 amount VARCHAR(64),
 parties VARCHAR(255),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (tx_hash,log_index)
);
CREATE TABLE IF NOT EXISTS chain_event_cursor (
 backend VARCHAR(16) PRIMARY KEY,
 last_block VARCHAR(32) NOT NULL DEFAULT '0',
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO chain_event_cursor(backend,last_block) SELECT 'ethereum','0'
 WHERE NOT EXISTS (SELECT 1 FROM chain_event_cursor WHERE backend='ethereum');
INSERT INTO chain_event_cursor(backend,last_block) SELECT 'fisco','0'
 WHERE NOT EXISTS (SELECT 1 FROM chain_event_cursor WHERE backend='fisco');

-- N4 批量答谢异步作业：API 只创建作业并返回 jobId，后台逐笔结算，前端轮询进度避免 180s 超时。
CREATE TABLE IF NOT EXISTS batch_reward_job (
 job_id VARCHAR(36) PRIMARY KEY,
 owner_id INT NOT NULL,
 activity_id INT NOT NULL,
 state VARCHAR(16) NOT NULL,
 total INT NOT NULL DEFAULT 0,
 done INT NOT NULL DEFAULT 0,
 failed INT NOT NULL DEFAULT 0,
 result_json TEXT,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 finished_at TIMESTAMP NULL,
 INDEX ix_batch_job_owner(owner_id,created_at)
);

-- N6 运维告警：卡单巡检/对账不一致落表，供人工工单消费；同一 (kind,ref) 未恢复只保留一条。
CREATE TABLE IF NOT EXISTS ops_alert (
 id VARCHAR(36) PRIMARY KEY,
 kind VARCHAR(32) NOT NULL,
 ref VARCHAR(128) NOT NULL,
 severity VARCHAR(16) NOT NULL,
 detail TEXT,
 resolved SMALLINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 resolved_at TIMESTAMP NULL,
 UNIQUE (kind,ref)
);

-- N1 刷新令牌：只存 SHA-256 哈希，不存明文；轮换时旧 token 标记 used，重放即视为盗用。
CREATE TABLE IF NOT EXISTS refresh_token (
 id VARCHAR(36) PRIMARY KEY,
 user_id INT NOT NULL,
 token_hash VARCHAR(64) NOT NULL,
 family_id VARCHAR(36) NOT NULL,
 used SMALLINT NOT NULL DEFAULT 0,
 revoked SMALLINT NOT NULL DEFAULT 0,
 expires_at TIMESTAMP NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX ix_refresh_user(user_id),
 INDEX ix_refresh_family(family_id)
);

-- 结算答谢 Agent 的只读预检提案：模型只生成草案，真实答谢在人工确认层执行。
CREATE TABLE IF NOT EXISTS agent_settlement (
 id VARCHAR(36) PRIMARY KEY,
 owner_id INT NOT NULL,
 request_key VARCHAR(100) NOT NULL,
 input_hash VARCHAR(66) NOT NULL,
 activity_id INT NOT NULL,
 state VARCHAR(24) NOT NULL,
 revision INT NOT NULL DEFAULT 0,
 proposal TEXT NOT NULL,
 result_json TEXT,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE (owner_id,request_key),
 INDEX ix_agent_settlement_owner(owner_id,created_at)
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
