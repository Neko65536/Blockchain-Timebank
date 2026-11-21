# 现场演示 / 可复现运行说明

> 目标：在一台干净机器上跑通「编译 → 测试 → 后端启动 → Mint 端到端结算」，不依赖本机缓存。
> 这是**演示与回归说明**，不是生产部署文档；生产编排见 `k8s/`。

## 1. 前置环境

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 21（`<22`） | Maven enforcer 强制 `[21,22)`；可用 `JAVA_HOME` 指定 |
| Maven | 3.9.11 | 用仓库自带 `.mvn/wrapper` 或系统 Maven |
| MySQL | 8.0+ | 3306；表由 `schema-mysql.sql` 等自动初始化 |
| Redis | 6+ | 6379；限流/缓存用 |
| RabbitMQ | 3.12+ | 5672；Outbox 唤醒用 |
| Node | 18+ | 仅前端需要 |
| Hardhat | 本地 | 可选：无链时用 `application-dev.properties`（`blockchain.enabled=false`）|

设置环境变量（演示用，密钥不要入库）：

```bash
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
# 链与 AI 均为可选
export BLOCKCHAIN_ENABLED=false          # 无链演示设 false
export AI_CHAT_API_KEY=                   # 留空则用本地替身
```

## 2. 一键编译与测试

```bash
cd backend
mvn -q install -DskipTests                 # 首次编译（约 210MB fat jar）
mvn -q test                                # 全量测试；结果见各模块 target/surefire-reports
```

Windows + WorkBuddy 环境若 `mvn` 脚本报 classworlds 找不到，用 JDK 直接调 Launcher（等价于系统 Maven）：

```bash
JAVA_HOME=/e/JDK/21 bash .workbuddy/mvnw21.sh -f backend/pom.xml test
```

关键测试类（可单独跑，验证业务正确性而非仅"能启动"）：

| 测试 | 覆盖 |
|------|------|
| `TimecoinWebApplicationTests` | 注册/登录/报名/签到/答谢/转账/冻结全链路 |
| `MintQuotaTests` | J1 发行三上限（总量/单人/每日） |
| `RagEvaluationTests` | RAG 24 题标注评测（引用命中/无依据召回） |
| `AgentEvaluationTests` | Agent 协议层 36 题（工具选择/注入/越权/规则覆盖） |
| `AgentRuntimeTests` | ReAct 预算/身份伪造/超时/独立 trace |
| `SettlementAgentTests` | 结算答谢只读预检 + 人工确认发放 |
| `AgreementServiceTests` | 用户协议版本留痕 |
| `GovernanceTests` / `ShardingIntegrationTests` | 多签治理 / 按月分表 |

## 3. 启动后端

无链模式（最快，验证业务/AI/RAG）：

```bash
JAVA_HOME=/path/to/jdk-21 nohup java -jar \
  backend/timecoin-web/target/timecoin-web-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=dev > backend.log 2>&1 &
```

看到日志出现 `Started TimecoinWebApplication` 即成功（约 8 秒）。默认端口 8080。

带链模式：先起 MySQL/Redis/RabbitMQ/Hardhat，再启动后端（`blockchain.enabled=true` + 四个 J1 发行额度环境变量必填，否则启动失败——这是 fail-closed 设计）。

## 4. 端到端 Mint 结算（带链时）

1. 老人发布活动 → 管理员审核 → 志愿者报名 → 现场签到（J2 签到码）。
2. 老人确认完成 → `settlement_order` 落库 → Outbox → RabbitMQ → 签名器 PREPARED → 广播 → 回执 CONFIRMED → 入账 SUCCEEDED。
3. 对账：`ChainController` 的 `/administrator/chain/reconcile` 校验 DB 与链上余额一致、`settlementMismatches=0`、`supplyConservation=true`。

**nonce 排障**：若结算卡在 PREPARED 且链返回 `Nonce too high`，多为 Hardhat 重启后 nonce 归零、MySQL 残留旧 `chain_submission` 把 nonce 顶高。处理：备份后清空 `chain_submission`/`settlement_outbox` 并重置测试余额，重跑结算。这是环境脏数据，不是代码缺陷。

## 5. 现场演示脚本（可选）

`start-project.sh` / `stop-project.sh` 存放于 `.workbuddy/`（WorkBuddy 工作区数据，被 gitignore，不入库）。如需在本机一键起停五服务，参考其中的实现：逐服务端口探测 + 幂等跳过 + 日志落 `.workbuddy/logs/`。脚本内 `ROOT` 需按本机路径调整。

## 6. 措辞红线（演示时不要夸大）

- 向量检索是**内存实现**，不是 Milvus/pgvector/ANN。
- 三个 Agent 是 **AgentScope ReAct 有界循环 + 人在环中**，不是通用多 Agent 平台。
- 签名是**独立 signer 进程**，不是云 KMS/HSM。
- J1 额度默认值在 `application.properties` 为空串，带链时须显式配置。
- RAG/Agent 评测是**脚本/标注小样本基线**，不是真实付费模型准确率结论。
