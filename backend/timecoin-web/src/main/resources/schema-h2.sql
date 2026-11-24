-- H2（MySQL 模式）；幂等脚本：可重复执行，不会在每次启动时清空已有数据
-- 数据库文件见 application-dev.properties（./data/timecoin）

CREATE TABLE IF NOT EXISTS `user` (
  id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  name VARCHAR(64) DEFAULT NULL,
  password VARCHAR(255) NOT NULL,
  role SMALLINT NOT NULL,
  email VARCHAR(128) DEFAULT NULL,
  age SMALLINT DEFAULT NULL,
  phone VARCHAR(32) DEFAULT NULL,
  address VARCHAR(255) DEFAULT NULL,
  create_time TIMESTAMP DEFAULT NULL,
  update_time TIMESTAMP DEFAULT NULL,
  image VARCHAR(512) DEFAULT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_user_username ON `user`(username);

CREATE TABLE IF NOT EXISTS old (
  id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id INT NOT NULL,
  CONSTRAINT fk_old_user FOREIGN KEY (user_id) REFERENCES `user`(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_old_user ON old(user_id);

CREATE TABLE IF NOT EXISTS administrator (
  id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id INT NOT NULL,
  CONSTRAINT fk_admi_user FOREIGN KEY (user_id) REFERENCES `user`(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_admi_user ON administrator(user_id);

CREATE TABLE IF NOT EXISTS volunteer (
  id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id INT NOT NULL,
  CONSTRAINT fk_vol_user FOREIGN KEY (user_id) REFERENCES `user`(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_vol_user ON volunteer(user_id);

CREATE TABLE IF NOT EXISTS activity (
  id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(255) NOT NULL,
  quota SMALLINT NOT NULL,
  deadline TIMESTAMP NOT NULL,
  date DATE NOT NULL,
  `begin` TIME NOT NULL,
  `end` TIME NOT NULL,
  address VARCHAR(255) NOT NULL,
  old_id INT NOT NULL,
  phone VARCHAR(32) DEFAULT NULL,
  description CLOB,
  status SMALLINT NOT NULL DEFAULT 1,
  administrator_id INT NOT NULL,
  create_time TIMESTAMP DEFAULT NULL,
  update_time TIMESTAMP DEFAULT NULL,
  message VARCHAR(512) DEFAULT NULL,
  service_type VARCHAR(64) NOT NULL DEFAULT 'other_service',
  extra_json CLOB DEFAULT NULL,
  remain SMALLINT NOT NULL DEFAULT 0,
  volunteer_reward INT NOT NULL DEFAULT 0,
  CONSTRAINT ck_act_quota CHECK (quota > 0),
  CONSTRAINT ck_act_reward CHECK (volunteer_reward >= 0),
  CONSTRAINT fk_act_old FOREIGN KEY (old_id) REFERENCES old(id),
  CONSTRAINT fk_act_admi FOREIGN KEY (administrator_id) REFERENCES administrator(id)
);

-- 历史库迁移：activity 旧表补列
ALTER TABLE activity ADD COLUMN IF NOT EXISTS service_type VARCHAR(64) DEFAULT 'other_service';
ALTER TABLE activity ADD COLUMN IF NOT EXISTS extra_json CLOB;

CREATE TABLE IF NOT EXISTS notice (
  id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(255) NOT NULL DEFAULT '',
  content CLOB NOT NULL,
  create_time TIMESTAMP DEFAULT NULL
);
CREATE INDEX IF NOT EXISTS idx_notice_create_time ON notice(create_time);

CREATE TABLE IF NOT EXISTS activity_volunteer (
  id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  activity_id INT NOT NULL,
  volunteer_id INT NOT NULL,
  status SMALLINT DEFAULT NULL,
  sign SMALLINT DEFAULT NULL,
  reward_paid SMALLINT NOT NULL DEFAULT 0,
  create_time TIMESTAMP DEFAULT NULL,
  update_time TIMESTAMP DEFAULT NULL,
  CONSTRAINT uk_act_vol UNIQUE (activity_id, volunteer_id),
  CONSTRAINT fk_av_act FOREIGN KEY (activity_id) REFERENCES activity(id) ON DELETE CASCADE,
  CONSTRAINT fk_av_vol FOREIGN KEY (volunteer_id) REFERENCES volunteer(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS compensation_record (
  id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  activity_id INT NOT NULL,
  volunteer_table_id INT NOT NULL,
  elder_user_id INT NOT NULL,
  amount INT NOT NULL,
  status SMALLINT NOT NULL DEFAULT 0,
  create_time TIMESTAMP DEFAULT NULL,
  update_time TIMESTAMP DEFAULT NULL,
  CONSTRAINT ck_comp_amount CHECK (amount > 0)
);
ALTER TABLE compensation_record ADD COLUMN IF NOT EXISTS volunteer_user_id INT;
CREATE INDEX IF NOT EXISTS idx_compensation_status ON compensation_record(status);

-- 默认管理员（仅当不存在时插入；登录口令 admin / 123456，密码以 bcrypt 加盐存储）
INSERT INTO `user` (username, name, password, role, email, age, phone, create_time, update_time)
SELECT 'admin', '系统管理员', '$2a$10$tgLSIdng6XOqDRDtZADrDecMFz/NO5UleYlYx/y2bTW7J8Fyo4ClC', 3, 'admin@local', 30, '13800000000', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM `user` WHERE username = 'admin');
INSERT INTO administrator (user_id)
SELECT u.id FROM `user` u WHERE u.username = 'admin'
AND NOT EXISTS (SELECT 1 FROM administrator a WHERE a.user_id = u.id);

ALTER TABLE `user` ADD COLUMN IF NOT EXISTS coin_balance BIGINT NOT NULL DEFAULT 0;
ALTER TABLE `user` ADD COLUMN IF NOT EXISTS balance_sync_time TIMESTAMP NULL;
-- J4：用户冻结状态，1 正常 / 0 冻结；冻结用户被登录拦截器拒绝
ALTER TABLE `user` ADD COLUMN IF NOT EXISTS status SMALLINT NOT NULL DEFAULT 1;
-- N1：令牌版本，登出/改密/冻结时递增，使已签发 token 立即失效
ALTER TABLE `user` ADD COLUMN IF NOT EXISTS token_version INT NOT NULL DEFAULT 0;
-- N2：登录失败锁定 + 管理员首登强制改密
ALTER TABLE `user` ADD COLUMN IF NOT EXISTS failed_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE `user` ADD COLUMN IF NOT EXISTS locked_until TIMESTAMP NULL;
ALTER TABLE `user` ADD COLUMN IF NOT EXISTS must_change_password SMALLINT NOT NULL DEFAULT 0;
-- J10：余额不为负的 DB 兜底约束
ALTER TABLE `user` ADD CONSTRAINT IF NOT EXISTS ck_user_balance CHECK (coin_balance >= 0);

CREATE TABLE IF NOT EXISTS chain_account_mirror (
  account_id VARCHAR(64) NOT NULL PRIMARY KEY,
  coin_balance BIGINT NOT NULL DEFAULT 0,
  balance_sync_time TIMESTAMP NULL,
  update_time TIMESTAMP NULL
);

INSERT INTO chain_account_mirror (account_id, coin_balance)
SELECT 'platform', 0 WHERE NOT EXISTS (SELECT 1 FROM chain_account_mirror WHERE account_id = 'platform');

CREATE TABLE IF NOT EXISTS chain_tx_log (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tx_type VARCHAR(16) NOT NULL,
  from_account VARCHAR(64),
  to_account VARCHAR(64) NOT NULL,
  amount BIGINT NOT NULL,
  tx_hash VARCHAR(128),
  biz_type VARCHAR(64),
  biz_ref VARCHAR(128),
  create_time TIMESTAMP DEFAULT NULL
);
CREATE INDEX IF NOT EXISTS idx_chain_tx_hash ON chain_tx_log(tx_hash);
CREATE INDEX IF NOT EXISTS idx_chain_tx_to ON chain_tx_log(to_account);
CREATE INDEX IF NOT EXISTS idx_chain_tx_from ON chain_tx_log(from_account);
CREATE INDEX IF NOT EXISTS idx_chain_tx_time ON chain_tx_log(create_time);

CREATE TABLE IF NOT EXISTS chain_reconcile_log (
  id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  run_time TIMESTAMP NOT NULL,
  total_checked INT NOT NULL DEFAULT 0,
  mismatch_count INT NOT NULL DEFAULT 0,
  fixed_count INT NOT NULL DEFAULT 0,
  chain_ready TINYINT NOT NULL DEFAULT 0,
  detail_json CLOB,
  create_time TIMESTAMP DEFAULT NULL
);
CREATE INDEX IF NOT EXISTS idx_reconcile_run_time ON chain_reconcile_log(run_time);
