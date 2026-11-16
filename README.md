# 基于区块链的时间币服务系统

仓库发布配置：默认MySQL密码通过`DB_PASSWORD`环境变量注入，不在代码中保存；`dev`测试配置不受影响。发布快照排除构建目录、本地链部署产物、IDE配置和压缩包。

已移除未启用OSS工具类中的历史密钥注释；当前图片上传仍使用本地存储。未来接入OSS需通过环境变量提供凭据，历史暴露的密钥应在阿里云控制台撤销或轮换。

## P1实施与验证（2026-11-16）

P0的32项回归复跑通过，具备进入P1的条件。本轮范围为Redis缓存/限流与RabbitMQ可靠结算，不包含分库分表、RAG/Agent、K8s或性能达标声明。

- 第一阶段：35项后端测试通过，覆盖原P0场景，以及普通转账请求幂等、发布扣费超时恢复、消费者租约恢复和重试上限。原同步答谢测试已改为“受理→消费→检查最终状态”，链RPC仍由Mockito模拟。
- `settlement_order`与`settlement_outbox`在一个数据库事务提交。发布活动先保存为状态`0=待扣费`，成功扣费后与台账一起转为`1=待审核`；状态0不能审批、报名、编辑或删除。待结算答谢不能撤回或取消报名；关联答谢结算后冻结活动编辑和删除，避免已签名金额失去依据。
- 发布、用户转账、管理员转账/增发必须携带`Idempotency-Key`（8～100位字母、数字、冒号、下划线或横线）。同一用户、操作和编号复用原结算，参数变化拒绝；答谢使用活动＋志愿者作为业务唯一编号。客户端超时后必须保留原编号。
- 成功响应只代表受理，返回`settlementId/state/activityId`。状态为`PENDING/PROCESSING/RETRY/SUCCEEDED/REVIEW`；仅`SUCCEEDED`代表链回执、台账和业务状态完成。本人通过`GET /info/settlements`或`/{id}`查询；管理员通过`GET /administrator/chain/settlements`查看，核查后用`POST /administrator/chain/settlements/{id}/retry`恢复原结算。达到8次尝试转人工核查，不自动新建交易。
- RabbitMQ持久化队列、消息、publisher confirm＋mandatory return检查；消费方法在数据库状态提交后返回并ACK。outbox会重投未完成订单，重复消费靠数据库租约与业务幂等消除重复入账；RPC等待不持有业务行锁。默认单活消费者、prefetch=1，削峰而非宣称链上高吞吐。签名账户nonce由数据库行锁分配，链配置变化不能偷偷重建原结算。
- 公告缓存使用Redis JSON、空结果短TTL、TTL抖动、重建互斥锁，以及随公告事务提交的数据库版本号；旧版本缓存自然过期，读旧数据不能覆盖新版本。公告允许短暂旧读，Redis不可用时读数据库；余额、报名名额和权限决策不使用缓存。
- 登录/注册按来源IP合并限流，已鉴权写请求按用户限流，Lua一次完成计数与过期。默认分别10次/分钟、30次/分钟；超限429，Redis故障503，均含`Retry-After`。不信任客户端伪造的转发IP头，代理部署需配置可信真实IP边界。
- 默认配置启用Redis与MQ；`dev`关闭二者及链，`dev,p1`启用中间件。连接环境变量为`REDIS_HOST/PORT/PASSWORD`及`RABBITMQ_HOST/PORT/USER/PASSWORD`。新增表随已有SQL初始化加载，升级前备份数据库；结算、outbox、链凭据必须一起保留。
- 第二阶段：40项测试通过（0失败、0错误、0跳过）。其中5项使用独立真实Redis/RabbitMQ，验证重复消息只入账一次、无路由消息退回后outbox保留、无效消息进入死信队列、两个限流实例共享计数与过期时间、公告版本隔离与空结果缓存。数据库为H2，链确认仍为模拟，不代表实链联调。
- PC和移动端生产构建通过。新增结算记录、管理员恢复原结算、待扣费活动展示；发布与币操作在浏览器持久保存未确认请求编号，网络失败后复用原编号且不存密码。页面成功提示改为“已受理”，仅结算状态标记最终完成。
- 最终验证：48项后端测试通过，0失败、0错误、0跳过（39项业务/权限/链回执测试、2项限流故障策略测试、7项真实中间件测试）。补充了outbox写入失败回滚、20个请求并发受理只有一单、两个独立签名服务分配不同nonce、待结算活动不可修改、接口受理不等待RPC等场景。实际重启RabbitMQ后，已确认持久化消息无需outbox重发仍完成消费与入账。仅发布费结算完成、无人报名且仍待审核/被拒绝的活动，继续允许老人按原规则删除，结算审计记录保留。
- 在独立MySQL8.0＋Redis7＋RabbitMQ3.13上复跑6项中间件/并发受理测试通过；该轮跳过需外部协调的broker重启测试，重启场景已在上述完整48项运行中实测通过。链RPC及链确认仍使用模拟，未执行真实Geth联调、应用JVM强杀恢复、链重组验证或性能压测；不能将这些正确性测试写成TPS指标。
- 前端6项请求幂等测试通过，验证超时后刷新页面仍复用原编号、未确认请求不能改参数、明确受理后可开始下一笔独立操作。PC与移动端最终生产构建通过；原有Sass弃用和资源体积等告警仍保留。
- 独立开发中间件配置见`backend/compose.p1.yml`：`docker compose -f backend/compose.p1.yml up`，Redis端口6380、RabbitMQ端口5673，仅绑定回环地址。开发启动后端需设置`REDIS_PORT=6380`、`RABBITMQ_PORT=5673`并启用`dev,p1`；付费业务另外显式开启链及其原有配置。WSL环境需保持前台会话，避免发行版闲置关闭导致容器退出；测试专用队列会清理，不要指向生产broker。
- 升级需前后端同时发布：旧客户端不携带请求编号将收到400；旧“请求成功即支付完成”的语义不再适用。P0历史普通转账/mint凭据不会自动回填结算单，升级前先核查未入账记录；已明确失败的链回执不会通过重试生成新付款，业务撤销/重新授权仍需人工处置。分库分表、RAG/Agent、微服务拆分和K8s属于后续阶段。

复现与证据（从项目根目录执行，PowerShell的`-D`参数建议整体加单引号）：

| 检查 | 命令/证据 |
|---|---|
| 无外部服务回归 | `mvn -f backend/pom.xml test`：41项执行，7项中间件测试跳过 |
| 常规中间件回归 | `mvn -f backend/pom.xml test '-Dp1.integration=true'`：47项执行，1项外部重启演练跳过 |
| 完整重启演练 | 再加`'-Dp1.broker-restart=true'`；看到`P1_BROKER_RESTART_READY`后45秒内在另一终端执行`docker compose -f backend/compose.p1.yml restart rabbitmq`；48项全部执行 |
| 浏览器请求幂等 | `node --experimental-vm-modules --test vue-system/tests/settlement.test.cjs`：6项通过 |
| 本轮日志 | `backend/timecoin-web/target/p1-final-test.log`、`p1-mysql-test.log`、`p1-browser-request-test.log`、`p1-pc-build.log`、`p1-phone-build.log`（构建目录，不纳入Git） |

MySQL复跑使用独立测试库`timebank_p1_test`。在已运行的测试MySQL上设置下列参数；测试会清理其业务测试数据，不得连接现有业务库：

```powershell
mvn -f backend/pom.xml test '-Dp1.integration=true' '-Dtest=MiddlewareIntegrationTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest.datasource-url=jdbc:mysql://localhost:3307/timebank_p1_test?allowPublicKeyRetrieval=true' '-Dtest.datasource-driver=com.mysql.cj.jdbc.Driver' '-Dtest.datasource-user=root' "-Dtest.datasource-password=$env:P1_MYSQL_PASSWORD" '-Dtest.schema=classpath:schema-mysql.sql'
```

设计依据：[Spring AMQP确认与退回](https://docs.spring.io/spring-amqp/docs/2.4.12/reference/html/)、[Redis原子限流](https://redis.io/docs/latest/commands/incr/)。下文P0段落是上一阶段记录；异步结算与幂等行为以本节为准。

## P0修复与回归记录（2026-11-16）

本节记录代码实际实现与测试结果；方案中的Redis、MQ、微服务、RAG、Agent和性能目标不代表已完成能力。

- 报名与取消：后端校验活动状态和截止时间；事务中统一先锁活动，再操作报名记录。取消仅在实际删除报名记录后返还一个名额，重复取消不改变名额；名额异常时整笔取消回滚。
- 第一轮验证：8项Spring Boot＋H2内存库集成测试通过，覆盖重复取消、未报名取消、截止与状态校验、20个请求竞争5个名额、并发重复报名/取消、异常回滚。这是正确性回归，不是MySQL性能压测。
- 第二轮验证：累计19项测试通过，新增管理员注册边界、登录身份与数据库角色一致性、统计接口鉴权、密码字段不出参、活动归属、编辑字段限制、结束时间校验、签到不触发支付、已支付不可撤回等回归。
- 第三轮验证：累计24项测试通过。新增签名交易先持久化再广播、成功回执后入账、回执失败不完成服务、超时后恢复原答谢、台账失败整体回滚后安全重试、重复回执不重复入账和金额越界校验。链RPC使用Mockito模拟；未据此宣称已通过真实Geth/MySQL联调。
- 最终验证：32项后端测试全部通过（0失败、0错误、0跳过）。增加RPC断连、nonce分配、恢复接口鉴权、关键词路径绕过、JWT篡改、20个并发完成请求仅支付一次、完成状态写入失败后台账回滚、定时状态刷新前的过期活动拦截。答谢采用“短事务校验→事务外等待回执→短事务提交台账和完成状态”，网络等待不占用业务数据库连接。
- PC和移动端生产构建通过。构建仍有Sass弃用、样式和资源体积告警；本轮未处理这些无关问题。PC公开注册入口改为账号说明，已答谢服务隐藏撤回入口，401响应清除登录状态。单笔链写入请求默认等待90秒，可用`VUE_APP_CHAIN_REQUEST_TIMEOUT_MS`调整；批量请求等待180秒，超时后需刷新核对处理结果。
- 本轮仅新增交易凭据实现类和SQL初始化文件；回归测试扩展已有测试类，说明集中更新README、交易规则、Dashboard接口文档与原安全报告，未新建说明文档。真实MySQL/Geth联调、云端部署与压测尚未执行。
- 新增`schema-chain-submission.sql`随MySQL/H2初始化加载，保存PREPARED/SUBMITTED/CONFIRMED/FAILED及入账标记。备份数据库时必须保留该表；已有库只增表，不清空业务数据。仅活动答谢具备稳定业务编号幂等；普通转账和mint发生超时后不能另建请求重试。
- 管理员可通过`GET /administrator/chain/transactions`查看近期凭据，再用`POST /administrator/chain/transactions/{txHash}/resume`确认或重发原签名交易。该接口只恢复链上确认，不冒充业务入账完成；答谢恢复后回原活动确认完成，其他交易由管理员核对台账。
- 默认余额对账改为只读报告，避免自动覆盖并发入账。手工修正须暂停交易，并先核查未入账凭据；不能先把余额改成链上新余额，再重复应用该笔增减。当前为单应用实例同步确认，尚未实现MQ、后台自动恢复、链重组处理及多实例签名调度。
- 发布扣费当前配置为4个时间币（dev关闭链时跳过）。发布页面已延长回执等待并阻止处理中重复点击，但发布扣费与活动创建仍是跨系统的两步操作，尚未纳入完整结算单/请求幂等；超时后应先核查活动和扣费记录。该失败窗口与普通转账请求幂等将在P1可靠消息与结算流程中继续完善。
- 权限规则：公开注册仅允许老人/志愿者；管理员通过已有管理员的用户管理页面创建。统计接口仅管理员可访问；公告和图片仍允许匿名读取。老人仅能编辑/删除自己无人报名的待审核或已拒绝活动；完成确认必须在活动结束后进行，已答谢服务不可直接撤回。
- JWT密钥改为环境变量`JWT_SECRET`，至少32字节；未配置则启动失败。首次运行可在PowerShell用`$env:JWT_SECRET=[Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))`生成当前终端密钥（PowerShell 7），部署时保存到Secret并在重启时复用；修改密钥后已有登录令牌失效。
- 回归命令：在项目根目录执行`mvn -f backend/pom.xml test`；测试自行使用独立内存数据库，不读写本地业务数据库。结果见`backend/timecoin-web/target/surefire-reports/`。

## 一、项目结构（你需要开几个终端）

| 角色 | 目录 | 说明 |
|------|------|------|
| **后端 API** | `backend/timecoin-web` | Spring Boot，默认端口 **8080** |
| **PC 管理端** | `vue-system/pc` | 浏览器访问，默认端口 **7000** |
| **移动端 H5** | `vue-system/phone` | 手机浏览器访问，端口以终端为准（常见 **7001**） |

**建议顺序：** 先启动后端 → 再启动 PC 或手机端（可同时开）。

---

## 二、环境要求

| 组件 | 说明 |
|------|------|
| **JDK** | 11（与 `backend` 中 Maven 配置一致） |
| **Node.js** | 16+ / 18+（用于前端与 Hardhat） |
| **MySQL** | 默认配置使用MySQL；本地无MySQL时显式启用`dev`，使用H2文件库 |

项目内已附带 Maven：`tools/apache-maven-3.9.6/`（Windows 用 `mvn.cmd`；Linux / macOS 可改用 `./tools/apache-maven-3.9.6/bin/mvn`）。

---

## 三、启动后端（必须先做）

当前默认配置为MySQL＋启用区块链。纯本地开发可显式启用`dev`，使用H2文件库并关闭链连接；此模式不执行付费答谢。启动前必须设置`JWT_SECRET`，见顶部P0记录。首次构建请从根目录执行`mvn -f backend/pom.xml install -DskipTests`，安装本项目模块依赖。

在 **PowerShell** 中执行：

```powershell
cd backend\timecoin-web
..\..\tools\apache-maven-3.9.6\bin\mvn.cmd spring-boot:run '-Dspring-boot.run.profiles=dev'
```

成功标志：日志中出现 `Tomcat started on port(s): 8080` 与 `Started TimecoinWebApplication`。  
停止：在该终端按 `Ctrl+C`。

API 根地址：**http://localhost:8080**

---

## 四、启动 PC 端（管理端）

**新开一个终端**，执行：

```powershell
cd vue-system\pc
npm install
npm run serve
```

- 浏览器打开终端里提示的地址，一般为：**http://localhost:7000**
- 前端请求后端的地址在 `vue-system/pc/src/utils/request.js` 中配置，默认 **http://localhost:8080**

**说明：** PC端用于管理员登录与后台管理；公开管理员注册已关闭，由现有管理员在用户管理中创建账号。老人、志愿者请在移动端注册。

---

## 五、启动移动端（老人 / 志愿者 H5）

**再开一个终端**，执行：

```powershell
cd vue-system\phone
npm install
npm run serve
```

- 本机浏览器访问：以终端输出为准，常见为 **http://localhost:7001/**
- 手机与电脑在同一 Wi‑Fi 时，用手机浏览器访问：**http://你的电脑局域网IP:端口/**（端口与终端一致）

**重要：** 移动端 `vue-system/phone/src/utils/request.js` 里 `baseURL` 默认为 `http://localhost:8080`。  
在**真机**上访问时，`localhost` 指向手机自己，会导致无法连后端。请改为电脑的局域网 IP，例如：

```javascript
baseURL: 'http://192.168.1.100:8080'  // 换成你电脑的实际 IP
```

改完后保存并重新执行 `npm run serve`。

---

## 六、使用 MySQL（本机已安装 MySQL 时）

**核心原则：** 不启用`dev`时使用MySQL；命令行或环境变量显式启用`dev`时使用H2。

1. **启动 MySQL 服务**（Windows 可在「服务」里确认 MySQL 正在运行）。

2. **初始化库表（二选一）**

   - **推荐：** 未使用`dev`时，启动自动执行`schema-mysql.sql`和`schema-chain-submission.sql`。配置数据库用户名、密码、`JWT_SECRET`以及启用链时所需的RPC和私钥后启动；首次初始化含演示管理员`admin/123456`，对外使用前必须修改口令。

   - **手动：** 当前完整结构以`backend/timecoin-web/src/main/resources/schema-mysql.sql`和`schema-chain-submission.sql`为准。`backend/schema-timecoin.sql`是历史脚本，不能替代当前初始化文件。

3. **改后端配置**  
   编辑 `backend/timecoin-web/src/main/resources/application.properties`：

   - 如曾启用以下配置或对应启动参数，请关闭它，以免继续使用H2：

     ```properties
     # spring.profiles.active=dev
     ```

   - 按你本机修改 **用户名、密码**（连接 URL 若与仓库默认一致可不改，已含时区等参数）：

     ```properties
     spring.datasource.username=root
     spring.datasource.password=你的MySQL密码
     ```

4. **启动后端**

   ```powershell
   cd backend\timecoin-web
   ..\..\tools\apache-maven-3.9.6\bin\mvn.cmd spring-boot:run
   ```

   若仍报 `Access denied`，说明用户名或密码与 MySQL 不一致，请改 `application.properties` 后再启动。

5. **切回H2开发**（可选）：启动时传入`-Dspring-boot.run.profiles=dev`。

---

## 七、区块链合约（可选）

`dev`模式可以不启动链，但付费答谢、转账和发币必须连接真实节点并配置合约；使用默认启用链的配置时，在项目根目录：

```powershell
cd TimeCoinSystem
npm install
npx hardhat node
```

另开终端部署到本地节点：

```powershell
cd TimeCoinSystem
npx hardhat deploy --network localhost
```

测试网部署需在 `TimeCoinSystem` 下配置 `.env`（参考 `TimeCoinSystem/.env.example`）。

---

## 八、登录与角色说明

| 端 | 角色 | 说明 |
|----|------|------|
| **PC** | 管理员 | 管理员登录与后台操作；账号由现有管理员创建 |
| **移动端 H5** | 老人、志愿者 | 注册时选择类型；登录使用账号密码（与注册身份一致） |

初始化脚本仍含演示管理员`admin/123456`，仅供本地初始化；对外部署前必须修改默认口令。后续管理员账号通过受保护的用户管理接口创建。

---

## 九、常见问题

### 端口 8080 已被占用

```powershell
netstat -ano | findstr :8080
taskkill /PID <上一步最后一列的PID> /F
```

若修改后端端口，需同步修改各前端 `request.js` 中的 `baseURL`。

### 注册/登录提示「操作失败」或数据库连接错误

- 使用**dev（H2）**时，确认启动参数显式启用`dev`。
- 使用 **MySQL** 时，确认账号密码、库名与 `schema-timecoin.sql` 已导入。

### 移动端能打开页面但接口失败

检查 `vue-system/phone/src/utils/request.js` 的 `baseURL`：真机必须用**电脑局域网 IP + 8080**，不能用 `localhost`。

---

## 十、目录说明

| 目录 | 说明 |
|------|------|
| `backend/` | Spring Boot + MyBatis 后端 |
| `vue-system/pc/` | PC 端 Vue 管理前端 |
| `vue-system/phone/` | 移动端 Vue H5（老人/志愿者） |
| `TimeCoinSystem/` | Hardhat + Solidity 合约 |
| `tools/apache-maven-3.9.6/` | 捆绑的 Maven |
