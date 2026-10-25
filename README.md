# Simon Ledger API

Simon Ledger 的后端 API，负责用户登录、云端账本、成员权限、参与人、流水、邀请、统计、幂等和变更日志。

## 技术栈

- Java 21
- Spring Boot 3.5
- Maven
- MySQL 8
- Redis
- MyBatis-Plus
- Sa-Token
- springdoc-openapi
- Log4j2

## 核心能力

- 账号：注册、登录、退出、当前用户资料和资料更新。
- 账本：创建、查询、编辑、删除、退出共享账本。
- 成员：账本维度的 owner/admin/editor/viewer 权限。
- 参与人：账本内分摊对象，支持绑定真实用户，也支持手动创建的普通参与人。
- 流水：收入/支出 CRUD，支持参与人列表、垫付人、版本冲突检查。
- 邀请：查询当前邀请码、重新生成邀请码、预览邀请、加入账本。
- 统计：汇总、分类统计、人员结余和代付结算。
- 同步：写接口支持幂等 key，变更写入 `ledger_change_log` 并提供增量查询。
- 后台：提供独立 `/api/admin/*` 管理接口，用于运营总览、用户/账本查询、账号删除预览与执行、审计日志和系统健康检查。
- AI 记账：后台逐用户授权，云端账本写入成员可将文字或短语音转换为待复核草稿；API 不自动创建流水。

## 目录结构

```text
src/main/java/com/simon/ledger/
  common/          # Result、ErrorCode、业务异常、角色常量
  config/          # Web、CORS、异常处理、MyBatis 配置
  controller/      # REST API
  dto/             # 请求和响应对象
  entity/          # MyBatis-Plus 实体
  mapper/          # Mapper
  service/         # 业务接口和实现
src/main/resources/
  application.yml
  application-prod.yml.example
  log4j2-spring.xml
sql/
  001_init_schema.sql
  002_add_transaction_payer.sql
  003_add_admin_console.sql
  004_add_optimistic_versions.sql
  005_add_transaction_operation_uniqueness.sql
  006_anonymize_deleted_accounts.sql
  007_add_ai_bookkeeping_access.sql
```

部署账号永久删除接口前，先在目标数据库执行 `sql/006_anonymize_deleted_accounts.sql`。它将 `ledger_transaction.created_by_user_id` 和 `ledger_change_log.operator_user_id` 改为可空，以便保留共享历史流水而移除原账号，并创建短期的会话撤销重试队列表。迁移与新版 API 必须先于新版后台上线。

部署 AI 记账前，先在目标数据库执行 `sql/007_add_ai_bookkeeping_access.sql`，现有及新账号默认无权使用。密钥只在 API 服务器部署环境中设置：`DEEPSEEK_API_KEY`、`TENCENT_ASR_SECRET_ID`、`TENCENT_ASR_SECRET_KEY`。可选 `DEEPSEEK_MODEL`，默认为 `deepseek-flash`。请使用部署平台的 Secret/环境变量管理与轮换功能；不要写入配置文件、Git、后台页面或 Flutter 构建参数。缺少 DeepSeek 密钥时文字及语音能力均不可用；缺少腾讯密钥时仅语音能力不可用。测试不调用付费供应商。

AI 调用使用 Redis 按上海自然日限额，默认每用户每日各 30 次文字解析和语音转写、全局各 1000 次，可通过 `ledger.ai.daily-user-limit` 与 `ledger.ai.daily-global-limit` 配置。Redis 不可用时请求拒绝而不放行。语音仅接受 16 kHz、单声道、16-bit PCM；最长 60 秒，Base64 后不超过 3 MB；音频仅在请求内存中使用，不上传对象存储。文字最多 1000 个 Unicode 字符，一次最多返回 10 条草稿。真实设备的麦克风权限提示与录音格式须在发布前另行验收。

## 主要接口

公开接口：

```text
POST /api/auth/register
POST /api/auth/login
GET  /health
GET  /api/health
GET  /api/invites/{code}
```

登录后接口：

```text
GET    /api/auth/me
PUT    /api/auth/me
POST   /api/auth/logout

GET    /api/ledgers
POST   /api/ledgers
POST   /api/ledgers/with-people
GET    /api/ledgers/{ledgerUuid}
PUT    /api/ledgers/{ledgerUuid}
DELETE /api/ledgers/{ledgerUuid}
POST   /api/ledgers/{ledgerUuid}/restore
POST   /api/ledgers/{ledgerUuid}/leave

GET    /api/ledgers/{ledgerUuid}/members
PUT    /api/ledgers/{ledgerUuid}/members/{memberUuid}/role
DELETE /api/ledgers/{ledgerUuid}/members/{memberUuid}
POST   /api/ledgers/{ledgerUuid}/members/{memberUuid}/restore

GET    /api/ledgers/people?ledgerUuids=uuid1,uuid2
GET    /api/ledgers/{ledgerUuid}/people
POST   /api/ledgers/{ledgerUuid}/people
PUT    /api/ledgers/{ledgerUuid}/people/{personUuid}
DELETE /api/ledgers/{ledgerUuid}/people/{personUuid}
POST   /api/ledgers/{ledgerUuid}/people/{personUuid}/restore

GET    /api/ledgers/{ledgerUuid}/transactions
POST   /api/ledgers/{ledgerUuid}/transactions
GET    /api/ledgers/{ledgerUuid}/transactions/{transactionUuid}
PUT    /api/ledgers/{ledgerUuid}/transactions/{transactionUuid}
DELETE /api/ledgers/{ledgerUuid}/transactions/{transactionUuid}
POST   /api/ledgers/{ledgerUuid}/transactions/{transactionUuid}/restore

GET    /api/ledgers/{ledgerUuid}/invites/current
POST   /api/ledgers/{ledgerUuid}/invites
POST   /api/ledgers/{ledgerUuid}/invites/regenerate
POST   /api/invites/{code}/join

GET    /api/ledgers/{ledgerUuid}/stats/summary
GET    /api/ledgers/{ledgerUuid}/stats/categories
GET    /api/ledgers/{ledgerUuid}/stats/people-balances

GET    /api/ledgers/{ledgerUuid}/changes

GET    /api/ledgers/{ledgerUuid}/ai-bookkeeping/capability
POST   /api/ledgers/{ledgerUuid}/ai-bookkeeping/parse
POST   /api/ledgers/{ledgerUuid}/ai-bookkeeping/transcribe
```

`POST /api/invites/{code}/join` 的 `data` 保留旧版邀请字段，并额外返回 `invite`、`ledger`、`member` 和 `person` 快照。服务端在同一事务中完成成员加入/恢复、按 `ledger_id + linked_user_id` 创建或复用参与人、版本更新和邀请码次数变更；已处于 active 的成员重复加入不会再次消耗次数。客户端应使用新的 `Idempotency-Key` 表示一次显式加入尝试，并优先使用快照恢复本地缓存。

后台管理接口：

```text
POST /api/admin/auth/login
POST /api/admin/auth/logout
GET  /api/admin/auth/me

GET  /api/admin/dashboard
GET  /api/admin/users?keyword=&page=&pageSize=
PUT  /api/admin/users/{uuid}/ai-bookkeeping-access
GET  /api/admin/users/{uuid}/deletion-preview
DELETE /api/admin/users/{uuid}
GET  /api/admin/ledgers?keyword=&page=&pageSize=
GET  /api/admin/audit-logs?page=&pageSize=
GET  /api/admin/system/health
```

后台登录使用独立 `admin_user` 表，登录 ID 使用 `admin:` 前缀与普通 App 用户隔离。除 `POST /api/admin/auth/login` 外，后台接口都需要有效后台登录态。

账号删除请求需携带预览返回的 `fingerprint`、与路径相同的 `confirmUuid`，以及每本转交账本的 `successors: [{ledgerUuid, userUuid}]`。服务端会重新校验预览和接手人，并在一个事务内处理账本、参与人、流水引用和账号；成功后撤销原账号会话。撤销暂时失败时，`account_session_revocation_queue` 保留内部登录 ID 供定时重试，成功后移除；普通 App 接口同时会拒绝已删除账号的旧 token。其他成员可能缓存了旧账号资料的幂等响应会失效，同一幂等键返回冲突，需在确认当前数据后换新键操作。仅云端在线数据库和活动会话属于这次清理范围，离线设备、备份和历史服务器日志不随请求即时清除。

Swagger:

```text
http://localhost:18080/swagger-ui.html
```

## 响应和认证

统一响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {}
}
```

Token Header：

```text
simon-ledger: <token>
```

写接口使用 `Idempotency-Key` 或 `clientOperationId` 防止重复提交。账户资料、账本、成员、参与人和流水的更新、软删除与恢复都需要携带当前 `version`；`DELETE` 和退出账本的请求体为 `{ "version": 3 }`，恢复请求沿用对应更新请求并携带 `version`。成功响应包含递增后的新版本，调用方必须立即保存。

版本或远端删除状态不一致时返回 HTTP 409 / 业务码 `409001`。响应 `data` 包含 `entityType`、`entityUuid`、`submittedVersion`、`remoteVersion`、`remoteDeleted` 和不含数据库主键、密码等内部信息的 `remoteSnapshot`，调用方应据此逐项处理冲突。

## 本地配置

`src/main/resources/application-dev.yml` 不提交到 Git。生产配置请参考：

```text
src/main/resources/application-prod.yml.example
```

全新数据库直接执行当前 `001_init_schema.sql`（Fresh 001），其中已经包含流水付款人字段、五类实体的 `version` 字段和流水 `clientOperationId` 的 active-only 唯一键；随后必须依次执行 `003_add_admin_console.sql`、`006_anonymize_deleted_accounts.sql`、`007_add_ai_bookkeeping_access.sql`、`008_add_currency_rates_to_cny.sql`。即使不启用 AI，当前实体查询也依赖 007 的授权列；008 的汇率和审计列同样是新版 API 的基本结构。不要再对这类全新结构执行 `002`、`004` 或 `005`，否则会重复添加已有字段或索引。

历史数据库只执行尚未应用的增量脚本，并严格按编号顺序升级：

```text
sql/002_add_transaction_payer.sql
sql/003_add_admin_console.sql
sql/004_add_optimistic_versions.sql
sql/005_add_transaction_operation_uniqueness.sql
sql/006_anonymize_deleted_accounts.sql
sql/007_add_ai_bookkeeping_access.sql
sql/008_add_currency_rates_to_cny.sql
```

`004_add_optimistic_versions.sql` 升级完成后，`user_account`、`ledger`、`ledger_member`、`ledger_person`、`ledger_transaction` 的 `version` 都应为 `INT NOT NULL DEFAULT 1`。

`005_add_transaction_operation_uniqueness.sql` 是一次性执行的历史库增量。在执行前，先运行下面的只读 preflight SQL，查找仍有效流水中重复的 `clientOperationId`：

```sql
SELECT ledger_id, created_by_user_id, client_operation_id, COUNT(*) AS duplicate_count
FROM ledger_transaction
WHERE deleted_at IS NULL
  AND client_operation_id IS NOT NULL
GROUP BY ledger_id, created_by_user_id, client_operation_id
HAVING COUNT(*) > 1;
```

如果查询返回记录，必须先人工核对并逐项处理，再执行 `005`；升级脚本不自动删除、修改或合并任何账目。该唯一键使用生成列令有效流水的 slot 为 `1`、已删除流水的 slot 为 `NULL`：同一账本、同一创建人、同一非空 operation id 只能有一条有效流水；历史删除记录可以保留多条，legacy 的 `client_operation_id = NULL` 记录也不会互相冲突。

2026-08-26 已完成可丢弃的 MySQL 8.4.11 实库验证：当前 Fresh 001 可直接初始化；历史 `001 + 002 + 003` 可依次升级到 `004 + 005`；`004` 会把历史已有数据的版本回填为 `1`；preflight 能发现有效流水的重复 operation id，`005` 会拒绝脏数据且失败后不留下部分 DDL，人工处理重复项后可重新执行。唯一键还实测确认了有效记录唯一、软删除历史可共存、legacy `NULL` operation id 可共存，以及不同账本或不同创建人的同名 operation id 互不冲突。

`003_add_admin_console.sql` 会创建 `admin_user` 和 `admin_operation_log`。首个后台管理员不会自动创建，需要先生成 BCrypt 密码 hash，再手动插入 `admin_user`。

## 本地开发

```bash
mvn test
mvn spring-boot:run
```

可选的 `AdminAccountDeletionMySqlIntegrationTests` 会清空测试库中的账本和账号表；只能对一次性 MySQL 实例运行。先创建名为 `simon_ledger_integration` 的独立库，用当前 `001`、`003`、`006`、`007`、`008` 的 DDL 初始化（将脚本中的库名替换为测试库名），然后设置 `LEDGER_DELETION_TEST_DB_URL`、`LEDGER_DELETION_TEST_DB_USER`、`LEDGER_DELETION_TEST_DB_PASSWORD` 并执行 `mvn -Dtest=AdminAccountDeletionMySqlIntegrationTests test`。未设置 URL 时，普通 `mvn test` 会跳过这组破坏性集成测试。

## Docker

Build image:

```bash
# Requires JDK 21; do not inherit destructive DB integration settings.
env -u LEDGER_DELETION_TEST_DB_URL -u LEDGER_DELETION_TEST_DB_USER -u LEDGER_DELETION_TEST_DB_PASSWORD \
  mvn -Dspring.profiles.active=test clean package
docker build -t simon-ledger-api:latest .
```

Run container:

```bash
docker run --rm -p 18080:18080 \
  --env-file /absolute/private/path/api.env \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e JAVA_OPTS="-Xms256m -Xmx512m" \
  simon-ledger-api:latest
```

## API 服务器部署与 AI 环境变量

服务器部署脚本为 `scripts/deploy-server.sh`，读取 `/apps/simon_ledger/app.tar.gz`，并通过 `--env-file` 将 `/apps/simon_ledger/secrets/api.env` 加载到新容器。可用 `ENV_FILE` 指定其他私有环境文件。环境文件缺失、打包文件缺失、日志目录不可写或镜像构建失败时，脚本会在停止现有容器前退出。部署压缩包仍使用根目录 `Dockerfile` 和 `app.jar`，服务器构建显式传入 `--build-arg APP_JAR=app.jar`；仓库直接构建默认读取 `target/app.jar`。

在环境文件中配置 `DEEPSEEK_API_KEY`、`TENCENT_ASR_SECRET_ID`、`TENCENT_ASR_SECRET_KEY`，可选配置 `DEEPSEEK_MODEL`。私有目录权限设为 `700`，环境文件权限设为 `600`。应用以 uid `10001` 运行，部署前确保 `/apps/simon_ledger/logs` 由该 uid 可写（例如由服务器管理员设置该目录所有权）；镜像将 `/app/logs` 链接到持久化 `/logs`，发布脚本会在停止旧容器前实测挂载目录写权限。真实密钥由每个部署环境单独维护；`*.env` 已加入 Git 忽略规则。

构建并上传新 `app.tar.gz` 后，安装并执行服务器脚本：

```bash
scp scripts/deploy-server.sh root@simon996.com:/apps/simon_ledger/scripts/deploy_api.sh
ssh root@simon996.com 'chmod +x /apps/simon_ledger/scripts/deploy_api.sh && bash /apps/simon_ledger/scripts/deploy_api.sh'
```

普通容器重启保留环境变量。重新部署会重建容器，因此必须再次使用这个脚本加载环境文件。脚本先构建带唯一发布标签的镜像，再停止并改名保留上一容器。新容器启动后会检查进程存活及本机 `/health` 的成功响应（默认最多 60 次、间隔 2 秒）；启动失败或健康检查失败则移除新容器并恢复上一容器。只有通过门禁后才替换应用目录并清理旧容器，原应用目录保留为带时间戳的 `api.previous.*`。`HEALTH_URL`、`HEALTH_ATTEMPTS`、`HEALTH_INTERVAL` 可按环境调整；健康接口只确认 API 存活，数据库结构检查和迁移必须在发布前完成。

数据库迁移不能靠回滚容器撤销。升级 008 前停止旧 API、备份并检查迁移状态，再对数据库执行一次 008，随后发布新版 API；本仓库不会自动连接或升级生产数据库。回退应用前确认它能兼容新增结构。迁移会将旧版遗留的 `response_code=-1` 标为失效并保留幂等键，避免不确定业务结果被重做。

## 生产跨域配置

管理后台 `https://ladmin.simon996.com` 请求 API `https://ledger-api.simon996.com` 时，API 必须允许后台的 Origin。生产配置示例：

```yaml
ledger:
  web:
    allowed-origin-patterns: https://ledger.simon996.com,https://admin-ledger.simon996.com,https://ladmin.simon996.com
```

也可设置环境变量 `LEDGER_WEB_ALLOWED_ORIGIN_PATTERNS`，值为上面的逗号分隔域名列表。如果服务器的 `application-prod.yml` 或环境变量覆盖了默认配置，需要同步添加新域名。Origin 不包含末尾 `/` 或路径；使用明确域名列表。修改后重启 API（Docker 环境变量变更需要重新创建容器）。

部署后验证浏览器登录请求的预检：

```bash
curl -i -X OPTIONS 'https://ledger-api.simon996.com/api/admin/auth/login' \
  -H 'Origin: https://ladmin.simon996.com' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: content-type,simon-ledger'
```

应返回 `200`，且包含 `Access-Control-Allow-Origin: https://ladmin.simon996.com`。仅健康检查返回 `200` 不能验证跨域配置。

## Git

- Remote: `git@github.com:simon-996/simon-ledger-api.git`
- Default branch: `master`
- Commit message style: `feat: ...`、`fix: ...`、`docs: ...`


## 手动币种汇率与统计

账本请求和响应新增 `currencyRatesToCny`，例如 `{ "CNY": 1, "USD": 7, "EUR": 8 }`，表示一单位该币种可兑换多少人民币。`exchangeRateToCny` 继续表示当前 `baseCurrencyCode` 的汇率，并覆盖映射中的同名键。更新合并已有映射并保留旧基础币种汇率；CNY 永远为 1；旧 CNY 账本中非 1 的兼容汇率在迁移和读取时归一为 1，账本及邀请响应的兼容汇率也返回 1，新写入仍拒绝非 1 的 CNY 汇率。映射最多 32 项，代码兼容标准三字母及原有的 3–16 位字母数字代码（首位为字母，统一转为大写，例如 `USDT`），汇率至少 `0.00000001`，最多 10 位整数、8 位小数；错误或缺失汇率会返回 400，请先维护汇率，不使用 1:1 替代。

流水始终保留原币金额。三个统计接口统一返回 `currencyCode: "CNY"`；每笔先按最新映射换算并以 HALF_UP 量化到两位小数，再合计。人员分摊按最小单位分配，余数依 `personUuid` 字典序分配，确保每笔金额守恒。金额入口拒绝超过 16 位整数或 2 位小数的值。

008 会回填账本原基础币种汇率、统一账号/人员头像为 255 字符且默认空字符串，并增加审计授权前后值。数据库 JSON 映射内部使用十进制字符串，类型处理器还原为 `BigDecimal`，API 仍输出 JSON 数值。幂等响应改为 MEDIUMTEXT 保存精确序列化 JSON，避免 MySQL 二进制 JSON 把 18 位金额或汇率转换为浮点后丢失精度。后台接手人包含 `account`；审计包含 `targetUuid`、`targetAccount`、`previousValue`、`newValue`，账号永久删除后目标标识匿名化。

## AI 请求资源边界

音频在权限检查后最多读取 `1,920,001` 字节，实际 PCM 上限为 1,920,000 字节（16 kHz、16-bit、单声道 60 秒）；已知超长 Content-Length 在读取前拒绝，分块请求同样受读取上限约束。文字请求在反序列化前鉴权并限制为 32 KiB，文字描述仍为 1–1000 字。供应商调用共用并发门禁，默认每账号 1 个、每 API 实例 4 个，超限立即拒绝，不排队积压；使用 `LEDGER_AI_CONCURRENT_USER_LIMIT`、`LEDGER_AI_CONCURRENT_INSTANCE_LIMIT` 调整。多副本部署时该并发上限按实例生效，Redis 日额度仍为共享限制。请求完成或异常都会释放许可。供应商输入包含指定时区的 `referenceNow`，相对日期以该本地日期解释。
