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
- 后台：提供独立 `/api/admin/*` 管理接口，用于运营总览、用户/账本查询、审计日志和系统健康检查。

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
```

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
POST   /api/ledgers/{ledgerUuid}/leave

GET    /api/ledgers/{ledgerUuid}/members
PUT    /api/ledgers/{ledgerUuid}/members/{memberUuid}/role
DELETE /api/ledgers/{ledgerUuid}/members/{memberUuid}

GET    /api/ledgers/people?ledgerUuids=uuid1,uuid2
GET    /api/ledgers/{ledgerUuid}/people
POST   /api/ledgers/{ledgerUuid}/people
PUT    /api/ledgers/{ledgerUuid}/people/{personUuid}
DELETE /api/ledgers/{ledgerUuid}/people/{personUuid}

GET    /api/ledgers/{ledgerUuid}/transactions
POST   /api/ledgers/{ledgerUuid}/transactions
GET    /api/ledgers/{ledgerUuid}/transactions/{transactionUuid}
PUT    /api/ledgers/{ledgerUuid}/transactions/{transactionUuid}
DELETE /api/ledgers/{ledgerUuid}/transactions/{transactionUuid}

GET    /api/ledgers/{ledgerUuid}/invites/current
POST   /api/ledgers/{ledgerUuid}/invites
POST   /api/ledgers/{ledgerUuid}/invites/regenerate
POST   /api/invites/{code}/join

GET    /api/ledgers/{ledgerUuid}/stats/summary
GET    /api/ledgers/{ledgerUuid}/stats/categories
GET    /api/ledgers/{ledgerUuid}/stats/people-balances

GET    /api/ledgers/{ledgerUuid}/changes
```

后台管理接口：

```text
POST /api/admin/auth/login
POST /api/admin/auth/logout
GET  /api/admin/auth/me

GET  /api/admin/dashboard
GET  /api/admin/users?keyword=&page=&pageSize=
GET  /api/admin/ledgers?keyword=&page=&pageSize=
GET  /api/admin/audit-logs?page=&pageSize=
GET  /api/admin/system/health
```

后台登录使用独立 `admin_user` 表，登录 ID 使用 `admin:` 前缀与普通 App 用户隔离。除 `POST /api/admin/auth/login` 外，后台接口都需要有效后台登录态。

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

写接口使用 `Idempotency-Key` 或 `clientOperationId` 防止重复提交。流水编辑和删除需要携带 `version`，版本不一致返回 `409001`。

## 本地配置

`src/main/resources/application-dev.yml` 不提交到 Git。生产配置请参考：

```text
src/main/resources/application-prod.yml.example
```

数据库初始化需要执行：

```text
sql/001_init_schema.sql
sql/002_add_transaction_payer.sql
sql/003_add_admin_console.sql
```

`003_add_admin_console.sql` 会创建 `admin_user` 和 `admin_operation_log`。首个后台管理员不会自动创建，需要先生成 BCrypt 密码 hash，再手动插入 `admin_user`。

## 本地开发

```bash
mvn test
mvn spring-boot:run
```

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
