# 研约通 · 高校实验资源预约平台

> 把「实验室 / 实验设备 / 预约批次 / 预约单」这套真实业务，用 Spring Boot + Redis + RabbitMQ 做成一个可运行、可验证、并发安全的后端服务。
> 本仓库由课程项目 **黑马点评（hm-dianping）** 重构而来，详见第 2 节与第 15 节。

---

## 1. 项目简介

**研约通** 面向高校实验室的开放预约场景：管理员为某台设备（如「高性能计算服务器」）创建**预约批次**（限定开放时段与名额），学生在该时段内提交预约。系统需要同时满足三条硬要求：

| 要求 | 实现要点 |
|---|---|
| **不超卖** | Redis Lua 原子脚本「校验 + 扣名额 + 去重」一步完成，数据库侧另有条件更新兜底 |
| **不丢单** | 请求先落 `tb_reservation_request`（PRE_ACCEPTED）再做异步落库，配合发布确认 + 对账补偿 |
| **可自愈** | 定时对账任务扫描卡在非终态的请求，自动补偿（回滚名额）或重新投递，超阈值进死信 |

技术选型刻意克制：**单体应用 + Maven + 单库**，依赖 Redis / RabbitMQ 两个中间件，不引入微服务、注册中心、分库分表、搜索引擎等（见第 14、15 节的设计取舍）。

---

## 2. 项目背景（诚实交代）

本仓库前身是**黑马点评**课程项目——一个「探店笔记 + 优惠券秒杀 + 用户社交关注」的演示工程。它提供了一批很有价值的**基础设施能力**：

- Redis 缓存与缓存穿透/击穿防护（`CacheClient` + 互斥锁 + 逻辑过期）
- Redis 原子 Lua 脚本、Redisson 分布式锁
- RabbitMQ 异步下单与死信链路（原为「优惠券秒杀」的异步下单迁移产物）
- 登录会话（Redis token + 双拦截器续期）、MyBatis-Plus、全局异常兜底

重构的做法是：**保留并复用上述基础设施，整体替换业务域**。原课程的业务实体已**永久删除**，不复存在：

| 已删除（原课程） | 现在（研约通） |
|---|---|
| 商铺 `tb_shop` / 商铺类型 `tb_shop_type` | 实验室 `tb_laboratory` / 设备分类 `tb_equipment_category` |
| 探店笔记 `tb_blog` / 笔记评论 `tb_blog_comments` | 设备 `tb_equipment` |
| 优惠券 `tb_voucher` / 秒杀券 `tb_seckill_voucher` / 券订单 `tb_voucher_order` | 预约批次 `tb_reservation_batch` / 预约单 `tb_reservation` |
| 用户关注 `tb_follow` / 签到 `tb_sign` / 用户详情 `tb_user_info` | 预约请求 `tb_reservation_request` / 死信 `tb_reservation_dlq` |
| Redis GEO、Bitmap 签到、HyperLogLog UV、点赞、Feed 推流、共同关注、ZSet 热门榜 | 预约名额（String 计数）+ 去重集合（Set）+ 按日自增 ID（来自 `RedisIdWorker`） |

**保留的库名**：MySQL 库名仍是 `hmdp`（避免迁移期间改动用户既有数据；新装库建议自行改名并同步 `application.yaml`）。表前缀 `tb_` 亦保留。

**关于 git 历史**：本仓库的**历史提交**中包含开发期的明文口令（源自原始课程提交）。当前工作区已全部脱敏（见第 15 节），但**历史未改写**。因此**本仓库在公开前必须先改写历史或轮换口令**——目前它只用于本地开发，未推送到任何公开远端。

---

## 3. 技术栈

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
| 构建 | Maven | — | 打包坐标 `yanyuetong:0.0.1-SNAPSHOT` |

主类：`com.yanyuetong.YanYueTongApplication`，默认端口 **8081**。

---

## 4. 核心功能

**学生侧**
- 手机号 + 验证码登录（验证码存 Redis，2 分钟有效；会话 token 30 分钟滑动过期）
- 浏览实验室列表与设备详情（读多写少，走 Redis 缓存）
- 提交预约：按批次预约，名额有限，同一批次不可重复预约
- 查看我的预约、取消预约

**管理员侧（`LAB_ADMIN` 角色）**
- 实验室与设备的增删改（仅能操作自己负责的实验室；删除前校验无设备依赖）
- 创建设备的预约批次，设定开放时段与名额
- 发布 / 关闭 / 取消批次
- 查看本实验室的预约列表、核销预约（verify）
- 查看与重放死信队列消息

**系统侧**
- 定时对账：补偿卡住的下单请求（超阈值进死信）
- 死信治理：DLQ 落库 + 人工重放 + 最大重放次数限制

---

## 5. 系统架构

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

**源码规模**：`src/main/java` 99 个文件 / `src/test/java` 16 个测试类。

---

## 6. 高并发预约流程（本项目的核心）

`POST /reservation` 的完整链路：

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
- **先落 request 再投递**：Redis 扣减成功后进程若崩溃，名额已扣但没有任何数据库凭据——请求表就是这份凭据，也是第 9 节对账的输入。
- **数据库不是唯一防线**：`tb_reservation` 上有唯一索引与 `active_flag` 条件更新（见 `phase8_consistency.sql` / `phase9_reservation_lifecycle.sql`），即使 Redis 判重失效也不会写入两条占用中的预约。

---

## 7. Redis 设计

| Key | 类型 | TTL | 用途 |
|---|---|---|---|
| `login:code:{phone}` | String | 2 min | 登录验证码 |
| `login:token:{token}` | Hash | **30 min（滑动）** | 登录会话；`RefreshTokenInterceptor` 每次请求续期 |
| `lab:equipment:{id}` | String(JSON) | 30 min ± 5 min 抖动 | 设备详情缓存 |
| `lab:equipment:null:{id}` | String | 2 min | 空值缓存，防穿透 |
| `lock:cache:{id}` | String | 短 | 缓存重建互斥锁（防击穿） |
| `lock:reservation:reconcile` | String | 短 | 对账任务互斥（多实例只有一个跑） |
| `reservation:batch:quota:{batchId}` | String | 批次截止 + 24h | **预约名额计数器** |
| `reservation:batch:users:{batchId}` | Set | 同上 | **已预约用户去重集合** |
| `icr:reservation:yyyy:MM:dd` | String | 按日 | `RedisIdWorker` 的按日自增序号 |

**设计说明**

- **会话 TTL 从 25 天压到 30 分钟**：因为每次请求都会续期，活跃用户完全无感；但 token 一旦泄漏，可用窗口从 25 天缩短到 30 分钟。改动只有 `RedisConstants.LOGIN_USER_TTL` 一行（单位是**分钟**）。
- **缓存三件套仍在用**：空值缓存（穿透）、互斥锁重建（击穿）、TTL 随机抖动（雪崩）。这套能力由 `CacheClient` 提供，是重构中「保留基础设施」的典型。
- **Redis 与 DB 的职责边界**：Redis 负责**高并发准入判定**（快、原子、易失），DB 负责**账实相符**（慢、持久、可对账）。两者用第 9 节的对账机制缝合。

---

## 8. RabbitMQ 可靠性

| 项 | 配置 |
|---|---|
| Exchange | `reservation.exchange`（Direct，durable） |
| 队列 | `reservation.create.queue`（durable，绑定 `reservation.create`） |
| 死信 | `reservation.create.dlq.queue`，绑定 `x-dead-letter-routing-key = reservation.create.dlq` |
| 发布确认 | `publisher-confirm-type: correlated` + `publisher-returns: true` |
| 消费 ack | `acknowledge-mode: manual`，`prefetch: 1` |
| 重试 | `listener.simple.retry.enabled: false`——**有意关闭**，见下 |

**为什么关闭自动重试**：手动 ack 模式下，Spring AMQP 的默认 `RejectAndDontRequeueRecoverer` 并不会真正把消息 nack 进死信，反而会让消费者卡死。因此改为**监听器显式 `basicAck` / `basicNack(requeue=false)`**，并在容器级用 `ConditionalRejectingErrorHandler(rejectManual = true)` 兜住**反序列化失败**这类拿不到 `Channel` 的异常——否则坏消息会无限重投，堵死队列。

**投递侧的失败处理**：`ReservationServiceImpl.publishReservationMessage` 区分三类结果——投递确认失败、`publisher-returns` 退回、`AmqpConnectException`（MQ 不可达）——分别走不同的补偿路径（重试标记 PUBLISHED，或留给对账任务重投）。

---

## 9. 数据一致性设计

这是「不丢单」的落点。三个机制叠加：

**① 请求表 = 状态机 + 对账输入**

`tb_reservation_request.status` 有明确的生命周期与终态判定：

```
PRE_ACCEPTED ──▶ PUBLISHED ──▶ CONFIRMED   （终态）
      │              │
      └──────────────┴──────▶ COMPENSATED  （终态，名额已回滚）
                    UNKNOWN                 （非终态，需对账）
```

`isTerminal()` 只认 `CONFIRMED` / `COMPENSATED`；`PRE_ACCEPTED` / `PUBLISHED` / `UNKNOWN` 都是非终态，即对账任务的扫描对象。

**② 定时对账（`ReservationReconcileServiceImpl`）**

每 `reconcile-interval-ms`（默认 5 分钟）扫描 `create_time < now - reconcile-threshold-ms`（默认 10 分钟）仍处非终态的请求，最多 `reconcile-limit`（默认 200）条：

- 该请求对应的预约**已经落库** → 补一个 `CONFIRMED`，收口；
- 未落库且**未超过阈值次数** → 记录失败原因并**推进 `update_time`**（让它退出「陈旧」窗口，下一轮再处理），等待重投；
- 失败次数**超过阈值** → 回滚 Redis 名额（`reservationRollback.lua`，幂等）并置 `COMPENSATED`。

> **两个易错点（已作为缺陷修复并留下回归用例）**
> 1. `update_time` 必须**显式赋值**。MySQL 的 `ON UPDATE CURRENT_TIMESTAMP` 在「写入值与当前值相同」时**不会**更新时间戳——只改 `fail_reason` 而不显式写 `update_time`，记录会永远留在陈旧窗口里，被反复扫描而无法退避。
> 2. 回滚写回 `update_time` 时不能写它自己的原值，否则等价于没写（同上原因）。

**③ 数据库层的最后防线**

`phase8_consistency.sql` / `phase9_reservation_lifecycle.sql` 在 `tb_reservation` 上提供唯一索引与 `active_flag` 条件更新：`UPDATE ... SET active_flag = 0 WHERE id = ? AND active_flag = 1`，影响行数为 0 即说明已被并发修改，据此判定 `QUOTA_CONFLICT`。即使 Redis 判重被绕过，也不会出现两条「占用中」的预约。

**Redis 名额与 DB 的关系**：名额只在 Redis 扣减；DB 的预约行存在与否由 MQ 消费决定。二者的收敛由对账任务保证——这也是为什么 `COMPENSATED` 是一个**终态**而不是异常：它表示「这次 Redis 扣减已被明确撤销」。

---

## 10. 数据库设计

**8 张表**（`tb_` 前缀保留）：

| # | 表 | 说明 | 建表脚本 |
|---|---|---|---|
| 1 | `tb_user` | 用户（`role`: `USER` / `LAB_ADMIN`；手机号唯一索引 `uniqe_key_phone`） | `phase0_baseline.sql` |
| 2 | `tb_laboratory` | 实验室（`admin_user_id` 指向负责管理员） | `phase2_laboratory_equipment.sql`、`phase5_lab_ownership.sql` |
| 3 | `tb_equipment_category` | 设备分类 | `phase2_laboratory_equipment.sql` |
| 4 | `tb_equipment` | 设备（归属实验室 + 分类，`booking_enabled` 控制可否预约） | `phase2_laboratory_equipment.sql` |
| 5 | `tb_reservation_batch` | 预约批次（开放时段 + 名额 + 状态机 DRAFT/PUBLISHED/CLOSED/CANCELLED） | `phase5_reservation_batch.sql` |
| 6 | `tb_reservation` | 预约单（RESERVED/CANCELLED/COMPLETED/NO_SHOW，含 `active_flag`） | `phase7_reservation.sql`、`phase9_reservation_lifecycle.sql` |
| 7 | `tb_reservation_request` | 预约请求状态机（对账输入） | `phase8_consistency.sql` |
| 8 | `tb_reservation_dlq` | 死信记录（NEW/REPLAYED/DISCARDED） | `phase8_consistency.sql` |

**种子数据**：`tb_laboratory` 4 行、`tb_equipment` 12 行、`tb_equipment_category` 若干；脚本**幂等**，可重复执行不产生主键冲突、不改变行数。

**历史课程表**：`phase10_drop_legacy_tables.sql` 提供 10 张已废弃课程表的幂等 `DROP`（见第 15 节说明）。

---

## 11. 启动方式

### 11.1 前置条件

- **JDK 8**（必须；`export JAVA_HOME=/path/to/corretto-1.8.0_504/Contents/Home`）
- MySQL（本地 3306）、Redis（本地 6379，可设密码）、RabbitMQ（本地 5672，需管理台可另开 15672）
- Maven ≥ 3.6

### 11.2 配置真实口令（不入库）

`application.yaml` 里所有口令都是 `change_me` 占位，真实值放在 **`application-local.yaml`**（已被 `.gitignore` 忽略）：

```bash
cp src/main/resources/application-local.yaml.example src/main/resources/application-local.yaml
# 编辑 application-local.yaml，填入本机 MySQL / Redis / RabbitMQ 口令
```

`application.yaml` 中 `spring.profiles.active: local`，因此启动时该文件自动生效。

### 11.3 初始化数据库（按顺序执行）

```bash
for f in phase0_baseline phase2_laboratory_equipment phase3_user_role \
         phase5_lab_ownership phase5_reservation_batch phase7_reservation \
         phase8_consistency phase9_reservation_lifecycle; do
  mysql -h127.0.0.1 -uroot -p hmdp < "src/main/resources/db/$f.sql"
done

# 可选：清理历史课程死表（仅从课程项目升级上来的旧库需要；不可逆，先备份）
mysql -h127.0.0.1 -uroot -p hmdp < src/main/resources/db/phase10_drop_legacy_tables.sql
```

### 11.4 授予管理员

系统不提供「注册管理员」的接口，管理员由 SQL 直接指定（与角色拦截器 `@RequireRole(LAB_ADMIN)` 配套）：

```sql
UPDATE tb_user SET role = 'LAB_ADMIN' WHERE phone = '13800000000';
-- 实验室负责人：把实验室挂到该管理员名下
UPDATE tb_laboratory SET admin_user_id = (SELECT id FROM tb_user WHERE phone='13800000000')
WHERE id = 1;
```

### 11.5 启动

```bash
mvn spring-boot:run
# 或：mvn -DskipTests package && java -jar target/yanyuetong-0.0.1-SNAPSHOT.jar
```

应用监听 **8081**。首次启动会自动声明 RabbitMQ 交换机/队列，并注册定时对账任务（可用 `yanyuetong.reservation.reconcile-*` 调整周期与阈值）。

### 11.6 跑测试

```bash
mvn test
```

> 测试会**真实连接**本机 MySQL / Redis / RabbitMQ（不是内存替身），因此前置服务必须可用。测试用的手机号夹具在 `@AfterEach` 中自行清理。

---

## 12. 环境依赖

| 服务 | 默认地址 | 配置位置 | 备注 |
|---|---|---|---|
| MySQL | `127.0.0.1:3306/hmdp` | `application-local.yaml` | JDBC 需 `allowPublicKeyRetrieval=true`（`caching_sha2_password`） |
| Redis | `127.0.0.1:6379` | `application-local.yaml` | 默认 **db0** |
| RabbitMQ | `localhost:5672`，vhost `/` | `application-local.yaml` | 管理台 `http://localhost:15672` |

应用自身的可调项（`application.yaml`）：

```yaml
yanyuetong:
  reservation:
    reconcile-interval-ms: 300000    # 对账任务执行间隔
    reconcile-threshold-ms: 600000   # 多久没进展算「陈旧」
    reconcile-limit: 200             # 单轮最多处理多少条
    dlq-max-replay: 3                # 死信最大重放次数
```

---

## 13. API 示例

所有响应统一为 `Result`：`{ "success": true, "data": ..., "errorMsg": null, "total": null }`（`null` 字段默认不序列化）。
登录后需在请求头带 `authorization: <token>`。

**登录**

```bash
curl -X POST 'http://localhost:8081/user/code?phone=13800000000'
# 验证码不回传响应体：它以 login:code:{phone} 写入 Redis（TTL 2 分钟）并打 debug 日志，
# 本地联调从应用日志里取，或直接 redis-cli GET login:code:13800000000
curl -X POST http://localhost:8081/user/login \
     -H 'Content-Type: application/json' \
     -d '{"phone":"13800000000","code":"<上一步拿到的 6 位验证码>"}'
# → { "success": true, "data": "<token>" }
curl http://localhost:8081/user/me -H "authorization: $TOKEN"
```

**浏览（读路径，走缓存）**

```bash
curl 'http://localhost:8081/laboratory/list?current=1'
curl http://localhost:8081/equipment/1
curl 'http://localhost:8081/equipment/page?current=1&laboratoryId=1'
curl 'http://localhost:8081/equipment-category/list'
```

**预约（写路径，Lua + MQ）**

```bash
# 提交预约（batchId 由管理员创建的批次给出）
curl -X POST http://localhost:8081/reservation \
     -H "authorization: $TOKEN" -H 'Content-Type: application/json' \
     -d '{"batchId":1}'
# → { "success": true, "data": <reservationId> }

curl 'http://localhost:8081/reservation/page?current=1' -H "authorization: $TOKEN"
curl -X POST http://localhost:8081/reservation/1/cancel -H "authorization: $TOKEN"
```

**管理端（需 `LAB_ADMIN`）**

```bash
curl -X POST http://localhost:8081/reservation-batch \
     -H "authorization: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
     -d '{"equipmentId":1,"totalQuota":50,"bookingStartTime":"2026-10-07 08:00:00","bookingEndTime":"2026-10-07 18:00:00"}'
curl -X POST http://localhost:8081/reservation-batch/1/publish -H "authorization: $ADMIN_TOKEN"
curl 'http://localhost:8081/reservation/manage/page?current=1' -H "authorization: $ADMIN_TOKEN"
curl -X POST http://localhost:8081/reservation/1/verify -H "authorization: $ADMIN_TOKEN"
curl 'http://localhost:8081/reservation-dlq/page?current=1' -H "authorization: $ADMIN_TOKEN"
curl -X POST http://localhost:8081/reservation-dlq/1/replay -H "authorization: $ADMIN_TOKEN"
```

**接口总览**

| 模块 | 端点 |
|---|---|
| 用户 | `POST /user/code`、`POST /user/login`、`POST /user/logout`、`GET /user/me` |
| 实验室 | `GET /laboratory/{id}`、`GET /laboratory/list`、`POST /laboratory`、`PUT /laboratory`、`DELETE /laboratory/{id}` |
| 设备 | `GET /equipment/{id}`、`GET /equipment/page`、`POST /equipment`、`PUT /equipment`、`PUT /equipment/{id}/status`、`DELETE /equipment/{id}` |
| 设备分类 | `GET /equipment-category/list` |
| 预约批次 | `POST /reservation-batch`、`PUT /reservation-batch`、`POST /reservation-batch/{id}/publish`、`/close`、`/cancel`、`GET /reservation-batch/{id}`、`GET /reservation-batch/page`、`GET /reservation-batch/manage/page` |
| 预约 | `POST /reservation`、`GET /reservation/page`、`GET /reservation/{id}`、`GET /reservation/manage/page`、`POST /reservation/{id}/cancel`、`POST /reservation/{id}/verify` |
| 死信 | `GET /reservation-dlq/page`、`GET /reservation-dlq/{id}`、`POST /reservation-dlq/{id}/replay` |

---

## 14. 项目亮点

1. **Redis 与 DB 的职责被明确切开**：Redis 做高并发准入（Lua 原子），DB 做账实相符，中间用「请求状态机 + 定时对账」缝合，而不是把一致性寄托在某一个组件上。
2. **三套缓存工具各司其职，没有被"统一"掉**：`CacheClient`（穿透/击穿/雪崩三件套）、`RedisIdWorker`（全局唯一 ID）、Redisson 锁（看门狗续期）解决的是三个不同问题，重构中刻意都保留。
3. **消息可靠性是被实测过的，不是抄配置**：手动 ack 下默认 recoverer 不真正 nack 这个坑，是通过反编译 Spring AMQP 字节码核对后确定，并据此改为显式 `basicNack` + 容器级 `ConditionalRejectingErrorHandler`。
4. **每个修复都有"改坏后测试变红"的证据**：关键行为（依赖检查、字段不外泄、异常状态码、对账退避）都用**变体实验**验证过判别力——把修复改回坏状态，确认对应用例确实变红，而不是只看"测试全绿"。
5. **诚实的测试观**：对**没有判别力证据**的改动（纯常量、纯文案、无法确定性构造的竞态分支）明确登记为"无判别力证据"，不冒充已验证。
6. **验收清单是可复跑的脚本，不是散落的命令**：`bash scripts/verify-acceptance.sh` 把「旧命名零残留 / 黑马语义关键词 / 凭据 / 仓库形态 / 禁改清单」编码进去，自带三项自证——探测范围里先找到一个已知存在的串，才允许采信它的 0 命中。可选 `SCAN_SECRETS='...'` 写字面量口令（脚本本身入库，所以不内嵌任何口令），`PHASE_BASE=<commit>` 查禁改文件是否被动过。

---

## 15. 已知边界

**设计取舍（有意不做）**

- **不使用微服务 / 注册中心 / 分库分表 / ES / Kafka / 分布式事务框架**。当前业务量级下它们是纯负债；单库 + 两个中间件已足够，且可验证。
- **库名保留 `hmdp`**、**表前缀保留 `tb_`**：改名收益是"观感"，成本是全量 DDL 与 SQL 的机械 churn，并破坏历史脚本可追溯性。
- **设备名称搜索用 `LIKE '%name%'`**，不走索引。当前设备量级（十几行）下，搜索语义正确优先于索引。
- **`CacheClient` 保留了两个没有调用方的查询实现**（`queryWithPassThrough` / `queryWithLogicalExpire`）。设备详情只走 `queryWithMutex`；这两个方法分别对应「缓存穿透」与「逻辑过期」两种范式，保留作参照，类注释已注明 `queryWithLogicalExpire` 为「未启用的备选实现」。这是全仓唯一一处有意保留的无调用方生产代码。
- **缓存删除不做补偿**：`EquipmentServiceImpl.deleteEquipmentCache` 直接调 `stringRedisTemplate.delete`，既无 try/catch 也无重试——Redis 不可用时异常会直接抛出，表现是「库里的写已经生效、接口却报错」，缓存里的旧值要等 30 分钟 TTL 才过期。另外 `queryWithMutex` 抢锁重试 20 次仍失败时会直查数据库且**不回写缓存**，此时该热键的并发会全部压到 DB。两者都登记为已知边界：为它们引入补偿链路，代价大于收益。

**行为与契约**

- **删除实验室是行为变更**：现在会**拒绝**删除「仍挂有设备」的实验室（返回业务错误），而早期版本能删。这是有意的防御性变更。
- **管理员取权只能靠 SQL**（第 11.4 节）。系统没有管理员注册入口。
- **`RoleInterceptor` 的「类级注解」分支目前没有使用者**，且 403 响应体为空——属有意保留的扩展点。

**数据与环境**

- **`tb_user` 里有 1011 行课程时代遗留用户**（外加 2 个 `LAB_ADMIN`）。这些行不会被自动清理；新装库由迁移链建库时不会有它们。终验对用户表的判据是"行数未变"，不是"0 行"。
- **`phase10_drop_legacy_tables.sql` 是一次性脚本**：仅从课程项目升级的旧库需要执行，且**不可逆**（会删除 `tb_shop` 14 行 / `tb_blog` 4 行 / `tb_voucher` 1 行等课程演示数据）。新装库不需要它。它同时也是全仓库唯一集中写出课程表名的地方，保留它是为了让旧库仍可清理。
- **Redis 里可能有 `icr:order:*` 残留键**（3 个，无 TTL）。这是**已删除的秒杀/订单域**留下的历史计数键，不会再产生，可安全删除。注意 `icr:reservation:*` 是**在用的**按日自增键，不要误删。
- **本机 RabbitMQ 上残留两个课程死队列** `hello,queue1` / `hello,queue2`（durable、非 auto-delete，各积压 1 条消息）。声明它们的课程时代监听器与测试类已在 Phase 10 删除，**仓库里已无任何引用**；属 broker 侧状态，不影响本项目运行（本项目只用 `reservation.*` 的交换机与队列）。

**安全**

- **git 历史含明文口令**（源自原始课程提交），**未改写**。工作区已全部脱敏：文档中的口令字面量已替换为等义描述，真实口令只存在于被 `.gitignore` 忽略的 `application-local.yaml`。**若要公开此仓库，必须先改写历史或轮换口令。**
- **RabbitMQ 的 `guest/guest` 未脱敏**：它是 RabbitMQ 官方文档公开的出厂默认值，且在本机实测**不可用**（管理 API 返回 401），不构成有效凭据，因此按"公开默认值"处理。
