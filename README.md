# 基于区块链的时间币服务系统

## 改进规划执行记录（2026-11-16）

已同步目标仓库至`f79000f`，后续按A→B→C→D→E→F→G推进，每完成一大项后统一验证和更新现有文档。Agent部分保留规划中的“暂不实施”范围。规划中的工作量、兼容性和简历措辞需要以实际实现及验证为准。

- A项当前文件脱敏：安全报告移除姓名、学号；方案PDF移除封面团队编号、作者及XML元数据，并将第82～84页的6张人物照片替换为隐私说明。保留85页、原图注及参考文献，不删除公开文献作者信息。
- A项验证：PDF可重新打开，85页保持不变；未修改页面的提取文字与拉取版本完全一致；目标编号不再出现、作者/XML元数据为空、无附件、6张人物照片不再作为图片对象出现在对应页面；已渲染检查封面和照片页；`git diff --check`通过。仅修改文档，没有变更后端业务代码，因此本阶段不重复运行业务测试。
- 公开发布边界：历史提交仍包含脱敏前的PDF及安全报告，本次仅清理当前文件，未修改仓库可见性，也未重写远程历史。转公开前仍须单独处理历史记录并复核；不能仅凭当前文件脱敏就判定可公开。
- A、B项提交为`dec8efd`，C项提交为`148f21b`。D项在`feature/sharding`分支实现单MySQL数据源内的按月分表，见下节；尚未实现多数据库分片、联盟链迁移或Agent，也没有新增性能数字。

## D项按月分表实施与验证（2026-11-16）

本轮使用ShardingSphere-JDBC5.3.2、Spring Boot2.6.13和Java11，保留默认非分片模式，通过`sharding`profile显式启用。实际验证发现Boot默认SnakeYAML1.29缺少`setCodePointLimit`，已固定为ShardingSphere所用的1.33；Maven优先中央仓库，OSGeo仍供原地理依赖使用。

相对规划的必要调整：outbox和chain_submission原来没有创建时间，UUID也不能推出月份，所以无法“业务代码零改动”。已补齐时间列与路由目录，改造受理、查询、重试、消费、回执恢复及入账SQL。当前只有一个物理数据源`ds`，三个金融业务表按月拆表，共享原数据库本地事务；没有启用多数据源、XA或跨库余额双写。`user/activity`按广播规则配置，但单数据源下仍各只有一份，不代表已验证多库广播一致性。

- 分片表：`settlement_order_yyyyMM`、`settlement_outbox_yyyyMM`、`chain_submission_yyyyMM`；使用`created_at`与内置`INTERVAL`算法，默认预留`202601`～`202612`，不改UUID。结算与outbox显式写入同一秒级时间，配置binding group，关联查询增加创建时间相等条件，避免跨月份错误组合。
- `shard_locator`是非分片的全局目录，以类型＋查询键为主键，保存UUID、幂等请求键或交易哈希对应的原创建时间；目录与实体在同一事务提交，失败一并回滚。目录也为相同类型的请求键提供跨月唯一约束。查询原结算/回执使用目录得到的时间路由，不用当前月份猜测；客户端传原UUID即可。
- 列表、活动关联核验、outbox扫描、兼容历史请求键查找及nonce最大值查询，显式限定在全部已配置月份范围；仍会扫描该范围内多个分片，未实现用户传入月份过滤、历史归档或消除列表逐条加载。签名账户仍使用非分片`chain_signer`行锁，nonce根据保留范围内的最大值分配；未声称链吞吐提升。
- 范围采用`SHARDING_START`包含、`SHARDING_END`不包含的月份，最多120个月。新写入超出范围直接拒绝；启动时验证物理表存在，并拒绝目录中存在配置范围外的历史记录。所有实例的JVM、JDBC连接与数据库会话须使用一致时区，例如Asia/Shanghai；INTERVAL本身不代替时区治理。
- 旧非分片库升级时给outbox补创建时间并从对应结算回填；旧链凭据没有可靠创建时间，新增列保留NULL，不把部署时间伪装为交易历史。非分片业务继续可用；启用分片时如果逻辑模板表仍有记录，会拒绝启动，避免静默隐藏已付款记录。

启用步骤（使用新建的独立MySQL库，不直接指向已有业务库）：

1. 设置`SHARDING_JDBC_URL`、`DB_USERNAME`、`DB_PASSWORD`和原有`JWT_SECRET`等配置，JDBC URL指定正确时区；启动profile为`sharding`，本地中间件端口仍通过`REDIS_PORT/RABBITMQ_PORT`提供。
2. 首次设置`SHARDING_INITIALIZE=true`：先运行原有MySQL结构初始化与迁移，再创建目录，并按`SHARDING_START=2026-01`、`SHARDING_END=2027-01`为三个表预建12个月物理表。只克隆空逻辑模板的结构，不复制历史行。数据库本身须提前创建。
3. 建表后改回`SHARDING_INITIALIZE=false`运行；每次启动都会校验模板表为空、目录范围和目标物理表存在。扩大月份范围时，先用初始化模式预建新表再统一更新全部实例配置，保留旧月份。后续字段/索引升级必须覆盖已有物理表，`CREATE TABLE ... LIKE`不会迁移已经存在的分片结构。
4. 默认`dev`及原中间件测试继续使用非分片数据源；不要把`dev`和`sharding`混用。链、模型和MQ开关沿用原配置，分片profile不会自动开启真实链或提供模型密钥。

分片键取舍：按月有利于时间段查询、预建与后续归档，但当前月份形成写热点；ID取模更利于均匀写入，却不利于按时间归档，时间查询也更容易散到多个分片。UUID不含月份，目录查询是保持旧API与跨月幂等所需的额外成本。单库分表不突破单MySQL实例的资源上限，目录和签名锁仍是集中访问点；本项未作容量或性能达标声明。

历史迁移与双写流程是操作设计，**没有实现自动双写迁移工具**：先备份并冻结金融写入，核对所有已签名和待确认交易；为缺失时间的历史凭据建立可审计的映射，再按原时间复制记录，保持UUID、幂等键、原交易字节、哈希、nonce和入账标记不变，同时构建目录。逐月核对行数、金额汇总、请求键唯一性、order/outbox配对和链凭据，切换前处理差异。若将来采用不停写迁移，应先在原事务写迁移outbox，以幂等消费者增量复制、追平并双读比对，短暂冻结最终切换；不能直接让请求线程分别写两库后声称一致。切换后回滚需先反向追平增量，不能简单切回旧profile。当前程序拒绝带数据的逻辑模板；离线迁移完成后应将原表保留在只读备份中，并在新库保留空模板，禁止为绕过检查直接删除原业务数据。

本项统一验证使用独立MySQL8.0.46、Redis7和RabbitMQ3.13：完整回归73项通过、2项条件测试跳过（外部broker重启和MySQL专用EXPLAIN），见`backend/timecoin-web/target/d-final-tests.log`。6项分片专项覆盖1月/2月真实物理表、跨月同键返回原单、绑定表查询、目录与业务回滚、旧月消费只入账一次、跨月复用签名与nonce递增、越界月份拒绝及启动拒绝遗漏历史/未迁移数据。补充真实RabbitMQ跨月派发后6项再次通过，见`d-sharding-mq-tests.log`；旧非分片MySQL升级联调10项通过、1项重启演练跳过，见`d-mysql-upgrade-tests.log`。链RPC仍为测试替身，不代表实链或多库XA验证。

复现：先在独立MySQL3307创建`timebank_sharding_test`，启动原独立Redis6380/RabbitMQ5673；测试使用已有本地测试账户配置。执行`mvn -o -f backend/pom.xml test '-Dp1.integration=true' '-Dsharding.integration=true'`；仅运行分片专项可再加`'-Dtest=ShardingIntegrationTests' '-Dsurefire.failIfNoSpecifiedTests=false'`。不传`sharding.integration=true`时跳过分片专项，常规测试仍不分片。测试会清理专用库的月表和专用MQ队列，勿指向业务环境。

最终执行`mvn -o -f backend/pom.xml package '-Dp1.integration=true' '-Dsharding.integration=true'`成功，73项通过、2项条件测试跳过，生成可执行后端JAR；证据为`d-package-tests.log`。本项没有前端改动，不重复前端构建。

配置依据：[ShardingSphere5.3.2分片规则](https://shardingsphere.apache.org/document/5.3.2/en/user-manual/shardingsphere-jdbc/java-api/rules/sharding/)、[INTERVAL算法参数](https://shardingsphere.apache.org/document/5.3.2/en/user-manual/common-config/builtin-algorithm/sharding/)。日志及构建产物保留在target，不新增开发说明文档。

## C项高并发设计补强（2026-11-16）

限流从固定窗口改为精确滑动日志：Redis ZSET保存已放行请求，Lua使用Redis服务端`TIME`计算毫秒时间，原子删除`时间戳≤当前时间−60000ms`的记录、检查额度并写入UUID成员。登录/注册仍按来源IP合并10次，其他已鉴权写请求按用户合并30次，统计区间为最近60秒；UUID避免同毫秒请求合并。满额返回429，`Retry-After`按最早记录距出窗的剩余时间向上取整，拒绝请求不写记录也不续期；Redis故障返回503，异步再分派不重复扣额度，非正额度配置启动失败。

新键使用`timebank:rate:v2:`，避免旧String计数器与新ZSET冲突，旧键按原TTL自然到期。升级时额度会重新起算；新旧实例混跑期间各用一套额度，应统一切换版本，不能宣称滚动升级窗口内仍有统一总额度。单个身份的集合最多保存配置额度数量，但活跃身份数量仍会影响Redis总内存。实现参考[Redis服务端时间](https://redis.io/docs/latest/commands/time/)和[按分值清理ZSET](https://redis.io/docs/latest/commands/zremrangebyscore/)。

MySQL核验发现旧库缺少源码已声明的`ix_settlement_owner`和`ix_settlement_activity`：`CREATE TABLE IF NOT EXISTS`只保证表存在，不迁移旧表索引。新增`schema-query-indexes-mysql.sql`按索引名称检查并补齐，另增加`settlement_order(created_at)`、`activity(status,update_time)`、`activity(old_id,update_time)`；H2使用对应幂等脚本。两者均接入初始化链路。已有同名但定义不同的索引不自动重建；生产大表建索引需预先评估DDL时间与锁影响，不应直接套用本地测试耗时。

EXPLAIN实测环境为MySQL8.0.46独立`timebank_p1_test`库，事务内加入1000条结算、1000条outbox和1000条活动，结束回滚。结算10%为PENDING、90%为SUCCEEDED，100个owner均分；活动5种状态均分，全部属于同一老人，时间字段相同。以下`rows`是优化器估计扫描量，不是实际耗时或QPS；该样本不能代表生产分布。

| 实际查询 | 修改前 | 索引迁移后 |
|---|---|---|
| 结算outbox轮询，含PENDING/RETRY和过期PROCESSING，按next_send排序取10条 | s表`range/ix_settlement_due/rows=102`，o表`eq_ref/PRIMARY/rows=1`；临时表＋filesort | 相同，保留跨表过滤与排序成本，未强制改变驱动表 |
| 活动status=2，按update_time倒序取10条 | `ALL/rows=1000`，where＋filesort | `ref/ix_activity_status_updated/rows=200`，Backward index scan，无filesort |
| 指定老人活动，按update_time倒序取10条 | `ALL/rows=1000`，where＋filesort | 仍为`ALL/rows=1000`；新复合索引是候选，但该老人拥有全部样本，优化器未选择它 |
| owner_id=1的结算，按created_at倒序取100条 | `ALL/rows=1000`，where＋filesort | `ref/ix_settlement_owner/rows=10`，Backward index scan＋Using index |
| 管理员全部结算，按created_at倒序取100条 | `ALL/rows=1000`，filesort | `index/ix_settlement_created/rows=100`，Backward index scan＋Using index |

完整SQL及计划由`MiddlewareIntegrationTests.recordMysqlCoreQueryPlansWithRollbackFixtures`输出`C_EXPLAIN`记录。原始证据在`backend/timecoin-web/target/c-mysql-tests.log`，迁移后在`c-mysql-indexed-tests.log`；不新增说明文档。活动标题/地址的`LIKE '%关键词%'`不因这些B-tree索引变成高效全文检索；本项未改造搜索，也未消除结算列表逐条加载的查询次数。

分布式锁与业务幂等的区别：锁解决某一时间段谁可以执行，例如RAG缓存重建互斥；租约过期或进程崩溃后，另一实例仍可能重做。幂等解决同一业务重复提交后结果是否重复生效：本项目通过请求键唯一约束、参数摘要比对、持久状态和业务事务复用原结算单；链上重试复用原签名交易，消费者通过租约令牌阻止过期任务提交。仅有Redis锁不足以保证不重复付款，outbox的至少一次投递也必须配合消费幂等。缓存版本号负责隔离更新前后的缓存条目，不能代替幂等或金融账务事务。

本大项统一验证：后端＋真实Redis/RabbitMQ执行67项通过、2项跳过（外部broker重启演练和MySQL专用EXPLAIN）；日志`c-final-tests.log`。独立MySQL联调启用EXPLAIN，10项通过、1项重启演练跳过；日志`c-mysql-indexed-tests.log`。真实Redis测试覆盖双实例20次并发仅放行10次、窗口滑动、拒绝不续期、动态等待时间和旧键兼容；原权限、RAG、结算回归同时通过。本项无前端改动，不重复前端构建。

另单独复跑EXPLAIN测试验证启动后再次执行索引迁移，1项通过，日志`c-migration-repeat-test.log`；SQL核查活动与结算样本均已回滚至0条。`git diff --check`通过。

复现普通回归：`mvn -o -f backend/pom.xml test '-Dp1.integration=true'`。MySQL回归在同一命令增加`'-Dtest=MiddlewareIntegrationTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dc.explain=true'`，并按独立测试库设置`test.datasource-url`、`test.datasource-driver=com.mysql.cj.jdbc.Driver`、`test.datasource-user`和`test.datasource-password`，同时指定`'-Dtest.schema=classpath:schema-mysql.sql' '-Dtest.index-schema=classpath:schema-query-indexes-mysql.sql'`。EXPLAIN测试仅允许库名以`_test`结尾，需使用独立Redis/RabbitMQ，勿指向业务库。

## B项RAG实施与验证（2026-11-16）

管理员在PC端“知识库管理”粘贴已核实的文档、FAQ或公告正文，设置标题与可见角色，提交后刷新查看状态。当前支持纯文本/Markdown正文，不支持PDF解析或URL抓取；公告按导入时的正文快照保存，修改公告后需禁用旧资料并重新导入。老人端和志愿者端沿用原AI问答入口，新增来源标题、片段编号与原文摘录。

- 可靠入库：文档与outbox同事务写入；相同幂等键与参数复用文档，参数变化返回409。独立持久化队列`timebank.rag.work`使用confirm/return确认，outbox重投未完成任务。消费者以180秒租约和随机令牌抢占，网络调用在事务外；全部向量完成后，文档、片段及知识库版本同事务提交。禁用或租约失效后旧消费者不能复活资料；失败退避重试，5次后转`REVIEW`，管理员可重试。
- 检索：按600个Unicode码点切分，重叠80码点，Embedding每批最多16段。向量持久化到数据库，`VectorIndex`的内存实现按版本重建快照，先过滤角色，再做全量余弦Top-5检索。正文最多16000个UTF-16代码单元，最多500份未禁用文档，适用于小规模知识库；未实现ANN、pgvector或Milvus适配。支持全部登录用户、老人、志愿者、管理员四种可见范围，不提供个人私有知识库。
- 生成与引用：检索证据进入Prompt，模型须返回严格JSON；引用编号须来自本次检索，引文须是原文连续片段，否则拒绝输出。保留输入/输出guardrail，无命中不调用生成模型。返回前复核知识库版本，更新或禁用期间返回409重试。引用校验仅证明来源存在，不能证明回答中每句话正确。
- 线程隔离：所有AI接口使用`DeferredResult`，独立4个工作线程、16个排队位置，满载返回503；120秒超时返回504并取消任务、移除排队项。模型HTTP总时限80秒，Embedding单次25秒。异步再分派不重复消耗写请求限流额度。
- 缓存：Redis键包含知识库版本、角色、问题摘要、Embedding/问答配置、模式和阈值。成功结果缓存120～150秒，无命中缓存10秒，同键互斥租约150秒并校验令牌释放；锁忙返回503。启用Redis但连接失败时拒绝生成，防止模型请求激增。不同环境须使用独立Redis实例。

默认生产配置使用远程Embedding和模型生成，已移除停用域名默认值。`dev`明确使用`local-hash`词面向量与`extractive`原文摘录，页面标注非大模型生成，不在远程失败时自动降级。

| 环境变量 | 用途 |
|---|---|
| `AI_RAG_EMBEDDING_MODE` | `remote`或`local-hash` |
| `AI_RAG_EMBEDDING_URL`、`AI_RAG_EMBEDDING_KEY` | OpenAI兼容Embedding完整端点与密钥 |
| `AI_RAG_EMBEDDING_MODEL`、`AI_RAG_EMBEDDING_DIMENSIONS` | 模型名与维度，默认`text-embedding-v3`、512；须与供应商支持一致 |
| `AI_RAG_ANSWER_MODE` | `model`生成＋引用，或`extractive`离线摘录 |
| `AI_CHAT_BASE_URL`、`AI_CHAT_API_URL` | 基础地址＋路径，后者也可填写完整URL |
| `AI_CHAT_API_KEY`、`AI_CHAT_MODEL` | 问答密钥与实际开通的模型名 |
| `AI_RAG_MQ_ENABLED` | 默认跟随`settlement.mq-enabled`；关闭时导入保持`PENDING`，不会同步索引 |
| `AI_RAG_MINIMUM_SCORE` | 默认0.2，上线前需针对知识库和模型评估 |

完整本地演示：按下文启动已有`backend/compose.p1.yml`，后端使用`dev,p1`、`REDIS_PORT=6380`和`RABBITMQ_PORT=5673`，JWT配置沿用现有方式。管理员导入资料并等到`READY`后，用可见角色提问。真实模型验证需显式覆盖上述两种模式并提供模型配置；Embedding配置变化后旧向量不参与检索，须禁用旧文档并重新导入。

管理API：`GET/POST /administrator/knowledge`（POST需`Idempotency-Key`）、`DELETE /administrator/knowledge/{id}`禁用、`POST /administrator/knowledge/{id}/retry`重试，沿用JWT管理员鉴权。问答仍使用`POST /users/old/ai/chat`和`POST /users/vol/ai/chat`，请求`{"message":"问题"}`；成功响应`data`保留`reply`并新增`mode/citations`。开放式聊天已改为知识库回答，尚未入库会提示依据不足；资料类型只是管理员填写的分类，不代表自动同步公告。

本项完成后统一验证，日志位于`backend/timecoin-web/target/`，不纳入Git：

| 检查 | 实际结果 |
|---|---|
| 后端＋真实Redis/RabbitMQ | `mvn -o -f backend/pom.xml test '-Dp1.integration=true'`：66项通过，1项需外部broker重启的旧演练跳过；`b-final-tests.log` |
| MySQL8＋真实Redis/RabbitMQ | 独立测试库执行`MiddlewareIntegrationTests`：8项通过，同一重启演练跳过；`b-mysql-tests.log`。首次测试连接缺少认证配置，修正测试JDBC参数后通过，未修改生产认证 |
| 新增验证 | Unicode切分、幂等、重复消费、租约恢复、禁用竞争、失败重试、角色隔离、版本失效、伪造引用拒绝、guardrail、Embedding协议、队列饱和/超时取消、异步鉴权/限流、真实MQ入库和Redis短缓存/互斥 |
| PC端与手机端 | 分别`npm run build`通过；`b-pc-build.log`、`b-phone-build.log`。原有Sass弃用与资源体积告警保留，未做浏览器交互验收 |
| 原请求幂等回归 | `node --experimental-vm-modules --test vue-system/tests/settlement.test.cjs`：6项通过；`b-request-tests.log` |

Embedding远程协议使用本地HTTP测试服务，生成模型使用测试替身；未调用真实云模型，没有Qwen效果或QPS达标声明。Agent仍暂不实施。下方P1章节保留当时的历史测试数量与范围，当前RAG进展以本节为准。

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
