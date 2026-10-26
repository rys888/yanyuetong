# 研约通 · 高校实验资源预约平台

<p>
  <img alt="Java" src="https://img.shields.io/badge/Java-8-orange?logo=openjdk&logoColor=white">
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring_Boot-2.3.12-brightgreen?logo=springboot&logoColor=white">
  <img alt="MyBatis-Plus" src="https://img.shields.io/badge/MyBatis--Plus-3.4.3-blue">
  <img alt="MySQL" src="https://img.shields.io/badge/MySQL-8.x-4479A1?logo=mysql&logoColor=white">
  <img alt="Redis" src="https://img.shields.io/badge/Redis-6.x-DC382D?logo=redis&logoColor=white">
  <img alt="RabbitMQ" src="https://img.shields.io/badge/RabbitMQ-3.x-FF6600?logo=rabbitmq&logoColor=white">
  <img alt="Vue" src="https://img.shields.io/badge/Vue-3.5-4FC08D?logo=vuedotjs&logoColor=white">
  <img alt="Vite" src="https://img.shields.io/badge/Vite-8.3-646CFF?logo=vite&logoColor=white">
  <img alt="Maven" src="https://img.shields.io/badge/Maven-3.6+-C71A36?logo=apachemaven&logoColor=white">
</p>

> 面向高校实验室场景的**实验资源预约平台**。以 **Redis Lua 原子准入 + RabbitMQ 异步落库 + 定时对账自愈**，
> 在单体架构下实现 **不超卖、不丢单、可自愈** 的高并发预约服务。

---

## 目录

- [项目简介](#项目简介)
- [功能特性](#功能特性)
- [技术栈](#技术栈)
- [系统架构](#系统架构)
- [核心预约流程](#核心预约流程)
- [快速开始](#快速开始)
- [项目结构](#项目结构)
- [接口概览](#接口概览)
- [配置说明](#配置说明)
- [测试与验收](#测试与验收)
- [已知边界](#已知边界)
- [安全提示](#安全提示)
- [许可证](#许可证)

---

## 项目简介

**研约通** 面向高校实验室的开放预约场景：管理员为一台设备（如「高性能计算服务器」）创建**预约批次**（限定开放时段与名额），学生在时段内提交预约。系统需要同时满足三条硬要求：

| 要求 | 实现要点 |
|---|---|
| **不超卖** | Redis Lua 原子脚本「校验 + 扣名额 + 去重」一步完成，数据库侧另有唯一索引与条件更新兜底 |
| **不丢单** | 请求先落 `tb_reservation_request`（PRE_ACCEPTED）再做异步落库，配合发布确认 + 定时对账补偿 |
| **可自愈** | 定时对账任务扫描卡在非终态的请求，自动回滚名额或重新投递，超阈值转死信 |

技术选型刻意克制：**单体应用 + Maven + 单库**，只依赖 Redis / RabbitMQ 两个中间件，不引入微服务、注册中心、分库分表、搜索引擎。

> **项目来源**：本仓库由课程项目 **黑马点评（hm-dianping）** 重构而来——复用其基础设施（缓存、分布式锁、Lua 脚本、MQ、会话），**整体替换业务域**。原课程业务实体已全部删除。

---

## 功能特性

**学生端**

- 手机号 + 验证码登录（验证码存 Redis，2 分钟有效；会话 token 30 分钟滑动过期）
- 浏览实验室列表与设备详情（读多写少，走 Redis 缓存）
- 提交预约：按批次预约，名额有限，同一批次不可重复预约
- 查看我的预约、取消预约

**管理端**（`LAB_ADMIN` 角色）

- 实验室与设备的增删改（**仅能操作自己负责的实验室**，删除前校验无设备依赖）
- 为设备创建预约批次，设定开放时段与名额；发布 / 关闭 / 取消批次
- 查看本实验室的预约列表、核销预约
- 查看与重放死信队列消息

**系统能力**

- **定时对账**：补偿卡住的下单请求（超阈值回滚名额并置终态，再超阈值转死信）
- **死信治理**：DLQ 落库 + 人工重放 + 最大重放次数限制
- **缓存三件套**：空值缓存防穿透、互斥锁重建防击穿、TTL 随机抖动防雪崩

---

## 技术栈

| 层次 | 选型 | 版本 | 说明 |
|---|---|---|---|
| 语言 / 运行时 | Java | **8**（Corretto 1.8.0_504 实测） | 老版本 Lombok 在 JDK 17+ 会挂，必须 JDK 8 |
| 框架 | Spring Boot | 2.3.12.RELEASE | 单体 Web 应用 |
| ORM | MyBatis-Plus | 3.4.3 | 分页插件 + 枚举 `EnumTypeHandler` |
| 数据库 | MySQL | 8.4.0 驱动（`mysql-connector-j`） | 服务端需支持 `caching_sha2_password` |
| 缓存 | Spring Data Redis + Lettuce | 2.6.2 / 6.1.6.RELEASE | 连接池 max-active 10 |
| 分布式锁 | Redisson | 3.13.6 | 看门狗续期 |
| 消息队列 | Spring AMQP / RabbitMQ | starter-amqp | Direct Exchange + 死信队列，手动 ack |
| 工具 | Hutool | 5.7.17 | 校验、Bean 拷贝、日期 |
| 测试 | JUnit 5 + Spring Boot Test + Mockito | 3.3.3 | 含 `MockMvc` 行为级用例 |
| 构建 | Maven | ≥ 3.6 | 打包坐标 `yanyuetong:0.0.1-SNAPSHOT` |
| 前端 | Vue 3 + Element Plus + Pinia + Vite | 3.5.43 / 2.14.7 / 3.0.4 / 8.3.3 | 独立工程，位于 `frontend/` |

主类：`com.yanyuetong.YanYueTongApplication`，默认端口 **8081**。

---

## 系统架构

```
                    ┌────────────────────────────────────────────┐
   HTTP  ──────────▶│  Controller 层（7 个 RestController）        │
                    │  EquipmentCategory / Equipment / Laboratory │
                    │  ReservationBatch / Reservation / Dlq / User│
                    └───────────────┬────────────────────────────┘
                                    │ 拦截器链：RefreshToken(0) → Login(1) → Role(2)
                    ┌───────────────▼────────────────────────────┐
                    │  Service 层                                 │
                    │  ┌──────────────┬───────────────────────┐  │
                    │  │ 读路径        │ 写路径（预约提交）      │  │
                    │  │ Equipment    │ ReservationService    │  │
                    │  │  CacheClient │  ├ ReservationQuota   │  │
                    │  │  + 逻辑过期   │  │   Executor (Lua)   │  │
                    │  │  + 互斥锁     │  ├ ReservationRequest │  │
                    │  └──────────────┤  ├ Persistence        │  │
                    │                 │  └ Lifecycle          │  │
                    │                 ├───────────────────────┤  │
                    │                 │ Reconcile（定时对账）   │  │
                    │                 └───────────────────────┘  │
                    └───┬───────────────┬──────────────┬─────────┘
                        │               │              │
                 ┌──────▼─────┐  ┌──────▼─────┐  ┌─────▼──────┐
                 │   MySQL    │  │   Redis    │  │  RabbitMQ  │
                 │ 8 张业务表  │  │ 名额/去重/  │  │ create +   │
                 │            │  │ 会话/缓存   │  │ dlq 队列    │
                 └────────────┘  └────────────┘  └────────────┘
```

**分层职责**

| 层 | 职责 |
|---|---|
| Controller | 参数校验与鉴权入口，统一返回 `Result` |
| 拦截器链 | `RefreshTokenInterceptor`(0) 会话续期 → `LoginInterceptor`(1) 登录校验 → `RoleInterceptor`(2) 角色/归属校验 |
| Service | 读路径走 `CacheClient`；写路径由 `ReservationService` 编排「请求落库 → Lua 准入 → MQ 投递」；`Reconcile` 定时收口 |
| 中间件 | Redis 管准入与缓存、RabbitMQ 管异步落库与死信、MySQL 管账实相符 |

**源码规模**：`src/main/java` 99 个文件，`src/test/java` 16 个测试类，REST 端点 33 个，业务表 8 张。

---

## 核心预约流程

`POST /reservation` 的完整链路——这是本项目所有并发设计汇合的地方：

```
① 取用户、取批次（批次走缓存），计算 BookingState（DRAFT/NOT_STARTED/OPEN/EXPIRED/CLOSED/CANCELLED）
② RedisIdWorker.nextId("reservation") 生成全局唯一预约 id（Redis 按日自增，避免自增主键暴露业务量）
③ 写入 tb_reservation_request，状态 = PRE_ACCEPTED      ← 「先记账」：请求先落库，防止 Redis 扣了名额却没有凭据
④ Lua 脚本 tryAcquire（原子）：                          ← 关键临界区，全部在一个脚本里完成
     - 名额 key 不存在           → BATCH_NOT_OPEN(1)
     - now < bookingStartTime    → BOOKING_NOT_STARTED(4)
     - now > bookingEndTime      → BOOKING_EXPIRED(5)
     - 名额 <= 0                 → SOLD_OUT(2)
     - 已在本批次去重集合里        → DUPLICATE(3)
     - 否则：DECR 名额 + SADD 去重 + EXPIRE 去重集合
⑤ 返回 0 后：投递 MQ 消息（publisher-confirm + publisher-returns 确认投递）
   → CAS 把请求置为 PUBLISHED（带重试）
⑥ 消费端 ReservationMessageConsumer 落库 tb_reservation（执行在事务内）
   → CAS 请求为 CONFIRMED（终态）；失败则 basicNack → 死信队列
```

**为什么这么设计**

- **临界区收在 Lua 里**：`DECR` + `SISMEMBER` 若分两次调用，两次之间就是超卖窗口。Lua 脚本在 Redis 单线程内原子执行，把「查名额 / 查时段 / 查重复 / 扣减 / 记录」合成一个不可分割的操作。
- **先落 request 再投递**：Redis 扣减成功后进程若崩溃，名额已扣但没有任何数据库凭据——请求表就是这份凭据，也是对账任务的输入。
- **数据库不是唯一防线，而是最后防线**：`tb_reservation` 上有唯一索引与 `active_flag` 条件更新，即使 Redis 判重失效也不会写入两条「占用中」的预约。

**请求状态机**（`tb_reservation_request.status`）

```
PRE_ACCEPTED ──▶ PUBLISHED ──▶ CONFIRMED   （终态）
      │              │
      └──────────────┴──────▶ COMPENSATED  （终态，名额已回滚）
                    UNKNOWN                 （非终态，需对账）
```

`isTerminal()` 只认 `CONFIRMED` / `COMPENSATED`；其余三种都是对账任务的扫描对象。

---

## 快速开始

### 1. 环境要求

| 服务 | 版本 / 地址 | 备注 |
|---|---|---|
| JDK | **8**（必须） | 例如 `export JAVA_HOME=/path/to/corretto-1.8.0_504/Contents/Home` |
| Maven | ≥ 3.6 | — |
| MySQL | `127.0.0.1:3306`，库名 `hmdp` | JDBC 需 `allowPublicKeyRetrieval=true` |
| Redis | `127.0.0.1:6379`，默认 db0 | 可设密码 |
| RabbitMQ | `localhost:5672`，vhost `/` | 管理台 `http://localhost:15672` |
| Node.js | **≥ 20.19**（仅前端需要） | `vite@8.3.3` 的要求，本机 v22.23.1 实测 |

> 库名沿用 `hmdp`、表前缀沿用 `tb_`，是为避免重构期间改动既有数据与破坏历史脚本可追溯性。

### 2. 配置凭据（真实口令不入库）

`application.yaml` 中所有口令都是 `change_me` 占位，真实值放在 **`application-local.yaml`**（已被 `.gitignore` 忽略）：

```bash
cp src/main/resources/application-local.yaml.example src/main/resources/application-local.yaml
# 编辑 application-local.yaml，填入本机 MySQL / Redis / RabbitMQ 口令
```

`application.yaml` 中 `spring.profiles.active: local`，启动时该文件自动生效。

### 3. 初始化数据库（按顺序执行）

```bash
for f in phase0_baseline phase2_laboratory_equipment phase3_user_role \
         phase5_lab_ownership phase5_reservation_batch phase7_reservation \
         phase8_consistency phase9_reservation_lifecycle; do
  mysql -h127.0.0.1 -uroot -p hmdp < "src/main/resources/db/$f.sql"
done

# 可选：清理历史课程死表（仅从课程项目升级上来的旧库需要；不可逆，先备份）
mysql -h127.0.0.1 -uroot -p hmdp < src/main/resources/db/phase10_drop_legacy_tables.sql
```

脚本**幂等**，可重复执行。种子数据含 `tb_laboratory` 4 行、`tb_equipment` 12 行及若干设备分类。

### 4. 授予管理员

系统不提供「注册管理员」的接口，管理员由 SQL 直接指定。**迁移链已内置两个种子管理员**，直接登录即为管理员，无需额外 SQL：

| 手机号 | 昵称 | 负责的实验室 |
|---|---|---|
| `13900000001` | 实验室管理员 | 实验室 `1`、`2` |
| `13900000002` | 实验室管理员B | 实验室 `3`、`4` |

若要让**别的**手机号成为管理员，注意 `phone` 必须已存在于 `tb_user`（先登录一次即可）：

```sql
UPDATE tb_user SET role = 'LAB_ADMIN' WHERE phone = '13900000001';
UPDATE tb_laboratory
   SET admin_user_id = (SELECT id FROM tb_user WHERE phone = '13900000001')
 WHERE id = 1;

-- 自查：两行都应返回非空
SELECT phone, role FROM tb_user WHERE phone = '13900000001';
SELECT id, admin_user_id FROM tb_laboratory WHERE id = 1;
```

> ⚠️ 这两句 UPDATE 有**静默失败**风险：`phone` 不存在时影响 0 行且不报错，紧随其后的子查询返回 `NULL`，会把 `admin_user_id` 写成 NULL，实验室变成「无主」（现象像权限没生效）。所以务必按上面的自查 SQL 确认。

### 5. 启动后端

```bash
mvn spring-boot:run
# 或：mvn -DskipTests package && java -jar target/yanyuetong-0.0.1-SNAPSHOT.jar
```

应用监听 **8081**。首次启动会自动声明 RabbitMQ 交换机/队列，并注册定时对账任务。

### 6. 启动前端

前端是**独立的 Vue 3 + Vite 工程**，位于 `frontend/`（后端需同时运行）：

```bash
cd frontend
npm install
npm run dev            # → http://localhost:5173
```

开发服务器把 `/api` 前缀的请求代理到后端，**并会剥掉 `/api` 前缀**。默认代理目标是 `http://127.0.0.1:8081`，后端不在 8081 时用环境变量覆盖：

```bash
VITE_API_PROXY_TARGET=http://127.0.0.1:18081 npm run dev
```

**关于登录验证码**：后端**不把验证码放进响应体**（只写 Redis 与 debug 日志），本地联调时看后端控制台那行「发送短信验证码成功，验证码：……」，或直接从 Redis 取：

```bash
redis-cli -a '<Redis 口令>' GET login:code:13800000000
```

生产构建：`npm run build`（产物 `frontend/dist/`）。

---

## 项目结构

```
yanyuetong/
├── src/
│   ├── main/
│   │   ├── java/com/yanyuetong/
│   │   │   ├── controller/            # 7 个 RestController（33 个端点）
│   │   │   ├── service/impl/          # 业务层：读路径 / 预约写路径 / 对账
│   │   │   ├── mq/                    # RabbitMQ 消费者（正常链路 + 死信链路）
│   │   │   ├── mapper/                # MyBatis-Plus Mapper 接口
│   │   │   ├── entity/ dto/ vo/ enums/ exception/
│   │   │   ├── config/                # MyBatis / MVC / 定时任务 配置
│   │   │   ├── annotation/            # @RequireRole 角色注解
│   │   │   └── utils/                 # CacheClient、RedisIdWorker、Lua 执行器、拦截器
│   │   └── resources/
│   │       ├── application.yaml               # 主配置（口令为占位符）
│   │       ├── application-local.yaml.example # 本地凭据模板
│   │       ├── db/                            # 迁移链 phase0 … phase10
│   │       ├── mapper/                        # Mapper XML
│   │       └── reservation.lua 等             # Redis Lua 脚本
│   └── test/java/com/yanyuetong/              # 16 个测试类
├── frontend/                          # 独立 Vue 3 + Vite 应用
│   ├── src/{api,assets,components,constants,layouts,router,stores,utils,views}/
│   └── scripts/verify-*.mjs           # 9 个前端验收脚本
├── scripts/
│   ├── verify-acceptance.sh           # 仓库级终验闸门
│   └── demo-readiness.sh              # 演示前基线体检
└── pom.xml
```

---

## 接口概览

所有响应统一为 `Result`：`{ "success": true, "data": ..., "errorMsg": null, "total": null }`（`null` 字段默认不序列化）。
登录后需在请求头带 `authorization: <token>`。

共 **33** 个端点。`⚙` = 除登录外还要求 `LAB_ADMIN` 角色（共 18 个）；不带 `⚙` 的只需登录，任意角色即可；仅 `/user/code` 与 `/user/login` 是公开端点。

| 模块 | 端点 |
|---|---|
| 用户 | `POST /user/code`（公开）、`POST /user/login`（公开）、`POST /user/logout`、`GET /user/me` |
| 实验室 | `GET /laboratory/{id}`、`GET /laboratory/list`、`POST /laboratory` ⚙、`PUT /laboratory` ⚙、`DELETE /laboratory/{id}` ⚙ |
| 设备 | `GET /equipment/{id}`、`GET /equipment/page`、`POST /equipment` ⚙、`PUT /equipment` ⚙、`PUT /equipment/{id}/status` ⚙、`DELETE /equipment/{id}` ⚙ |
| 设备分类 | `GET /equipment-category/list` |
| 预约批次 | `POST /reservation-batch` ⚙、`PUT /reservation-batch` ⚙、`POST /reservation-batch/{id}/publish` ⚙、`/close` ⚙、`/cancel` ⚙、`GET /reservation-batch/{id}`、`GET /reservation-batch/page`、`GET /reservation-batch/manage/page` ⚙ |
| 预约 | `POST /reservation`、`GET /reservation/page`、`GET /reservation/{id}`、`GET /reservation/manage/page` ⚙、`POST /reservation/{id}/cancel`、`POST /reservation/{id}/verify` ⚙ |
| 死信 | `GET /reservation-dlq/page` ⚙、`GET /reservation-dlq/{id}` ⚙、`POST /reservation-dlq/{id}/replay` ⚙ |

鉴权失败统一返回**空体**：无 token / token 失效 → `401`；已登录但角色不符 → `403`。业务校验失败 → `400` + `Result.fail("<中文原因>")`；无归属权限 → `403` + `Result.fail("<中文原因>")`。

---

## 配置说明

**`application.yaml`（主配置）**

```yaml
yanyuetong:
  reservation:
    reconcile-interval-ms: 300000    # 对账任务执行间隔
    reconcile-threshold-ms: 600000   # 多久没进展算「陈旧」
    reconcile-limit: 200             # 单轮最多处理多少条
    dlq-max-replay: 3                # 死信最大重放次数
```

**前端环境变量**

| 变量 | 默认 | 用途 |
|---|---|---|
| `VITE_API_PROXY_TARGET` | `http://127.0.0.1:8081` | Vite 开发服务器代理目标（**后端不在 8081 时必须覆盖**） |

**Redis Key 设计**

| Key | 类型 | TTL | 用途 |
|---|---|---|---|
| `login:code:{phone}` | String | 2 min | 登录验证码 |
| `login:token:{token}` | Hash | **30 min（滑动）** | 登录会话，每次请求续期 |
| `lab:equipment:{id}` | String(JSON) | 30 min ± 5 min 抖动 | 设备详情缓存 |
| `lab:equipment:null:{id}` | String | 2 min | 空值缓存，防穿透 |
| `lock:cache:{id}` | String | 短 | 缓存重建互斥锁（防击穿） |
| `lock:reservation:reconcile` | String | 短 | 对账任务互斥（多实例只有一个跑） |
| `reservation:batch:quota:{batchId}` | String | 批次截止 + 24h | **预约名额计数器** |
| `reservation:batch:users:{batchId}` | Set | 同上 | **已预约用户去重集合** |
| `icr:reservation:yyyy:MM:dd` | String | 按日 | `RedisIdWorker` 的按日自增序号 |

**RabbitMQ 拓扑**

| 项 | 配置 |
|---|---|
| Exchange | `reservation.exchange`（Direct，durable） |
| 队列 | `reservation.create.queue`（durable，绑定 `reservation.create`） |
| 死信 | `reservation.create.dlq.queue`，绑定 `x-dead-letter-routing-key = reservation.create.dlq` |
| 发布确认 | `publisher-confirm-type: correlated` + `publisher-returns: true` |
| 消费 ack | `acknowledge-mode: manual`，`prefetch: 1` |
| 自动重试 | `listener.simple.retry.enabled: false`——**有意关闭**：手动 ack 下默认 `RejectAndDontRequeueRecoverer` 不会真正 nack 进死信，反而会卡死消费者，故改为监听器显式 `basicNack(requeue=false)` + 容器级 `ConditionalRejectingErrorHandler` |

**数据库**（8 张表，`tb_` 前缀保留）

| # | 表 | 说明 | 建表脚本 |
|---|---|---|---|
| 1 | `tb_user` | 用户（`role`: `USER` / `LAB_ADMIN`） | `phase0_baseline.sql` |
| 2 | `tb_laboratory` | 实验室（`admin_user_id` 指向负责管理员） | `phase2_*`、`phase5_lab_ownership.sql` |
| 3 | `tb_equipment_category` | 设备分类 | `phase2_laboratory_equipment.sql` |
| 4 | `tb_equipment` | 设备（归属实验室 + 分类） | `phase2_laboratory_equipment.sql` |
| 5 | `tb_reservation_batch` | 预约批次（开放时段 + 名额 + 状态机） | `phase5_reservation_batch.sql` |
| 6 | `tb_reservation` | 预约单（含 `active_flag`） | `phase7_reservation.sql`、`phase9_*.sql` |
| 7 | `tb_reservation_request` | 预约请求状态机（对账输入） | `phase8_consistency.sql` |
| 8 | `tb_reservation_dlq` | 死信记录（NEW/REPLAYED/DISCARDED） | `phase8_consistency.sql` |

---

## 测试与验收

**后端**

```bash
mvn test                        # 16 个测试类（JUnit 5 + MockMvc）
bash scripts/verify-acceptance.sh   # 仓库级终验闸门
```

> 测试会**真实连接**本机 MySQL / Redis / RabbitMQ（不是内存替身），因此前置服务必须可用；测试用的手机号夹具在 `@AfterEach` 中自行清理。

`verify-acceptance.sh` 把「旧命名零残留 / 黑马语义关键词 / 凭据泄漏 / 仓库形态 / 未跟踪面 / 禁改清单」编码为可复跑脚本，自带三项自证机制（先验证探测范围本身非空，才采信其 0 命中）。凭据探测器的失败输出只给 `文件:行号`，**不回显匹配内容**。

**前端**（先 `cd frontend`）

| 命令 | 需要什么 |
|---|---|
| `npm run check:ui` | **完全离线**——纯静态断言，不连任何服务 |
| `npm run check:request` · `check:batch` | 后端在跑（默认 `http://127.0.0.1:18081`，可用 `VERIFY_BACKEND` 覆盖）；`check:request` 还需 `VERIFY_TOKEN` |
| `npm run check:browse` | `VERIFY_BACKEND` + `VERIFY_TOKEN` |
| `npm run check:session` | `VERIFY_BACKEND` + `VERIFY_CODE` |
| `npm run check:admin` · `check:reservation` | `VERIFY_BACKEND` + token，部分组还需 `VERIFY_*` 指定的真实 id |
| `npm run check:dlq` | `VERIFY_BACKEND` + `VERIFY_ADMIN_TOKEN` + `VERIFY_DLQ_*` |

**没有环境变量也能跑**：需要真实数据的检查组会打印 `SKIP: … is not set`，而不是假装通过；部分套件另带空转护栏（数量对不上就变红），所以「全绿」里不会混进「根本没查到」。

---

## 已知边界

**设计取舍（有意不做）**

- 不使用微服务 / 注册中心 / 分库分表 / ES / Kafka / 分布式事务框架——当前业务量级下它们是纯负债。
- 库名保留 `hmdp`、表前缀保留 `tb_`：改名收益是观感，成本是全量 DDL 与 SQL 的机械变更。
- 设备名称搜索用 `LIKE '%name%'`，不走索引：当前设备量级（十几行）下搜索语义正确优先于索引。

**其他边界**

- 删除实验室会**拒绝**删除「仍挂有设备」的实验室，这是有意的防御性变更。
- 管理员取权只能靠 SQL，系统没有管理员注册入口。
- 缓存删除不做补偿：`EquipmentServiceImpl.deleteEquipmentCache` 无 try/catch 与重试。
- 仓库内 `benchmark/` 目录被 `.gitignore` 整目录忽略（其中含真实口令与 token）。

---

## 安全提示

> **当前 HEAD 已脱敏，但历史尚未清理干净 —— 本仓库目前仍不可公开。**
>
> - **工作区**：`application.yaml` 全部为 `change_me` 占位，真实口令只存在于被 `.gitignore` 忽略的 `application-local.yaml`。
> - **`docs/`**：已于 2026-10-26 从全部提交中清除（历史已改写，所有 SHA 变更），仓库现只托管代码。
> - **仍未清理**：`src/main/resources/application.yaml` 的**历史版本**（37 个提交）仍保留课程时代的明文口令。
>
> 转为公开前必须完成：① 清理 `application.yaml` 的历史版本；② 轮换全部口令。

---

## 许可证

本仓库暂未声明开源许可证。如需公开，建议先完成上方「安全提示」中的历史清理与口令轮换，再补充 `LICENSE`。
