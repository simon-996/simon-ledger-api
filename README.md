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
```

部署账号永久删除接口前，先在目标数据库执行 `sql/006_anonymize_deleted_accounts.sql`。它将 `ledger_transaction.created_by_user_id` 和 `ledger_change_log.operator_user_id` 改为可空，以便保留共享历史流水而移除原账号，并创建短期的会话撤销重试队列表。迁移与新版 API 必须先于新版后台上线。

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
```

`POST /api/invites/{code}/join` 的 `data` 保留旧版邀请字段，并额外返回 `invite`、`ledger`、`member` 和 `person` 快照。服务端在同一事务中完成成员加入/恢复、按 `ledger_id + linked_user_id` 创建或复用参与人、版本更新和邀请码次数变更；已处于 active 的成员重复加入不会再次消耗次数。客户端应使用新的 `Idempotency-Key` 表示一次显式加入尝试，并优先使用快照恢复本地缓存。

后台管理接口：

```text
POST /api/admin/auth/login
POST /api/admin/auth/logout
GET  /api/admin/auth/me

GET  /api/admin/dashboard
GET  /api/admin/users?keyword=&page=&pageSize=
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

全新数据库直接执行当前 `001_init_schema.sql`（Fresh 001），其中已经包含流水付款人字段、五类实体的 `version` 字段和流水 `clientOperationId` 的 active-only 唯一键；随后按需执行 `003_add_admin_console.sql`。不要再对这类全新结构执行 `002`、`004` 或 `005`，否则会重复添加已有字段或索引。

历史数据库只执行尚未应用的增量脚本，并严格按编号顺序升级：

```text
sql/002_add_transaction_payer.sql
sql/003_add_admin_console.sql
sql/004_add_optimistic_versions.sql
sql/005_add_transaction_operation_uniqueness.sql
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

可选的 `AdminAccountDeletionMySqlIntegrationTests` 会清空测试库中的账本和账号表；只能对一次性 MySQL 实例运行。先创建名为 `simon_ledger_integration` 的独立库，用 `001`、`003`、`006` 的 DDL 初始化（将脚本中的库名替换为测试库名），然后设置 `LEDGER_DELETION_TEST_DB_URL`、`LEDGER_DELETION_TEST_DB_USER`、`LEDGER_DELETION_TEST_DB_PASSWORD` 并执行 `mvn -Dtest=AdminAccountDeletionMySqlIntegrationTests test`。未设置 URL 时，普通 `mvn test` 会跳过这组破坏性集成测试。

## Docker

Build image:

```bash
docker build -t simon-ledger-api:latest .
```

Run container:

```bash
docker run --rm -p 18080:18080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e JAVA_OPTS="-Xms256m -Xmx512m" \
  simon-ledger-api:latest
```

## Git

- Remote: `git@github.com:simon-996/simon-ledger-api.git`
- Default branch: `master`
- Commit message style: `feat: ...`、`fix: ...`、`docs: ...`
