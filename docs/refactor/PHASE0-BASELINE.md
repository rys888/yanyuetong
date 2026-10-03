# Phase 0 — Baseline 基线记录

> 记录时间：2026-10-03
> 记录时的 Git HEAD：`433cdfb feat(seckill): 秒杀异步下单迁移至 RabbitMQ`
> 分支：`master`
> 本阶段未修改任何业务代码。

---

## 1. 构建环境基线

### 1.1 本机 JDK 情况

| JDK | 路径 | 编译本项目 |
| --- | --- | --- |
| OpenJDK 25.0.3（Homebrew，**默认**） | `/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home` | ❌ **失败** |
| OpenJDK 17.0.20.1（Homebrew） | `/opt/homebrew/Cellar/openjdk@17/...` | 未验证 |
| Amazon Corretto 1.8.0_504 | `/Users/eason/Library/Java/JavaVirtualMachines/corretto-1.8.0_504/Contents/Home` | ✅ **成功** |

Maven：`Apache Maven 3.9.16`

### 1.2 编译命令与结果

```bash
export JAVA_HOME=/Users/eason/Library/Java/JavaVirtualMachines/corretto-1.8.0_504/Contents/Home
mvn clean compile -DskipTests
```

**结果：BUILD SUCCESS**，编译 72 个源文件（`src/main/java`），耗时约 1.6s。

### 1.3 已验证的硬约束

**所有 Phase 必须使用 JDK 8 编译。**

已实测 JDK 25 下 `mvn clean compile` 失败，错误全部是 Lombok 注解处理器未生效导致的
「找不到符号」（`getXxx` / `setXxx` / 构造器 / `log` 全部缺失），例如：

```
[ERROR] .../dto/Result.java:[19,16] 无法将类 com.hmdp.dto.Result中的构造器 Result应用到给定类型
[ERROR] .../service/impl/VoucherOrderServiceImpl.java:[114,35] 找不到符号
[ERROR] .../service/impl/VoucherOrderServiceImpl.java:[139,20] 无法将接口 org.apache.logging.log4j... 中的方法 warn 应用到给定类型
```

原因：`pom.xml` 未固定 `lombok` 版本，由 `spring-boot-starter-parent 2.3.12.RELEASE`
传递得到 lombok `1.18.20`，该版本不支持 JDK 25 的注解处理。

> 后续每个 Phase 的编译验证都必须显式设置 `JAVA_HOME` 指向 Corretto 8。

### 1.4 建议的构建封装（供后续 Phase 使用）

```bash
JAVA8=/Users/eason/Library/Java/JavaVirtualMachines/corretto-1.8.0_504/Contents/Home
JAVA_HOME=$JAVA8 mvn clean compile -DskipTests
```

---

## 2. 依赖服务可达性基线（实测）

| 服务 | 项目配置指向 | 实测结果 | 结论 |
| --- | --- | --- | --- |
| MySQL | `127.0.0.1:3306/hmdp`，`root/123` | 端口可连接；`root/123` **Access denied**；`root` 空密码亦 denied | ⚠️ **凭据不匹配，需人工确认** |
| Redis | `192.168.150.101:6379`，密码 `123321` | **不可连接**（该内网地址在本机不可达）；本机 `127.0.0.1:6379` 可连接，但需认证且 `123321` 不正确 | ⚠️ **地址与密码均需修正** |
| RabbitMQ | `localhost:5672`，`guest/guest` | 端口可连接（Docker 容器 `rabbitmq:4-management`，已运行 25h，healthy，映射 5672/15672） | ✅ AMQP 可达 |
| RabbitMQ 管理 API | `localhost:15672` | `guest:guest` 返回 `401 not_authorized` | ⚠️ 无法用配置中的账号查询管理 API |

> 环境对齐（Redis 地址/密码、MySQL 凭据、MQ 管理账号）属于 **Phase 10 配置清理** 的正式范围，
> 但为了让中间 Phase 具备「可运行验证」能力，建议尽早处理，详见第 7 节。

---

## 3. 数据库基线

来源：`src/main/resources/db/hmdp.sql`（Navicat 导出，原始 schema 名 `hmdp2`，
而 `application.yaml` 连接的是 `hmdp`）。

### 3.1 表清单与索引

| 表名 | 主键 | 唯一索引 | 普通索引 | 说明 |
| --- | --- | --- | --- | --- |
| `tb_user` | `id` | **`uniqe_key_phone(phone)`** | — | 用户 |
| `tb_user_info` | `user_id` | — | — | 用户资料（粉丝/关注/积分/等级 → 社交语义） |
| `tb_shop` | `id` | — | `foreign_key_type(type_id)` | 商铺（含 `x`/`y` 经纬度、`avg_price`、`sold`、`score`、`area`） |
| `tb_shop_type` | `id` | — | — | 商铺分类 |
| `tb_voucher` | `id` | — | — | 优惠券（`pay_value`/`actual_value`/`type`） |
| `tb_seckill_voucher` | `voucher_id` | — | — | 秒杀券（`stock`/`begin_time`/`end_time`） |
| `tb_voucher_order` | `id` | **无** | **无** | 订单 —— **缺唯一约束（问题 2）** |
| `tb_blog` | `id` | — | — | 探店笔记 |
| `tb_blog_comments` | `id` | — | — | 评论 |
| `tb_follow` | `id` | **无** | **无** | 关注（同一对 user 可重复插入，无唯一约束） |

### 3.2 关键结论

- **`tb_voucher_order` 只有主键，没有 `UNIQUE(request_id)`，也没有 `UNIQUE(user_id, voucher_id)`。**
  当前「一人一单」完全依赖应用层 `count()` 查询 + Redisson 锁，数据库层没有任何兜底。
  与文档「当前项目已经发现的技术问题」第 2 条完全一致。
- 时间字段全部是 `timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP`，带 `ON UPDATE CURRENT_TIMESTAMP`。
- `tb_seckill_voucher.stock` 为 `int(8) NOT NULL`，无 `>= 0` 检查约束。

### 3.3 数据库凭据

`root/123` 与 `root` 空密码均被拒绝，**本机 MySQL 的真实凭据未知**，
因此本次无法导出「运行时实际库」的 `SHOW CREATE TABLE` 结果。
上表基于项目自带 SQL 导出文件，与代码中的实体映射一致，可作为基线。
如需实测库结构，请提供本机 MySQL 凭据。

---

## 4. Redis Key 基线

### 4.1 由 `RedisConstants` 统一定义的 Key

| Key Pattern | 类型 | Value | TTL | 定义位置 | 用途 |
| --- | --- | --- | --- | --- | --- |
| `login:code:{phone}` | String | 6 位验证码 | 2 分钟 | `RedisConstants:4` | 短信验证码 |
| `login:token:{token}` | Hash | UserDTO 字段 | 36000 分钟 | `RedisConstants:6` | 登录会话 |
| `cache:shop:{id}` | String | Shop JSON | 30 分钟 | `RedisConstants:12` | 商铺缓存 |
| `cache:shop:{id}`（空值） | String | `""` | 2 分钟 | `CACHE_NULL_TTL` | 空值缓存防穿透 |
| `lock:shop:{id}` | String | `"1"` | 10 秒 | `RedisConstants:14` | 缓存重建互斥锁 |
| `seckill:stock:{voucherId}` | String | 剩余库存 | 无 | `RedisConstants:17` | 秒杀库存 |
| `blog:liked:{blogId}` | ZSet | userId → 时间戳 | 无 | `RedisConstants:18` | 点赞 |
| `feed:{userId}` | ZSet | blogId → 时间戳 | 无 | `RedisConstants:19` | 关注 Feed 收件箱 |
| `shop:geo:{typeId}` | GEO | shopId + 经纬度 | 无 | `RedisConstants:20` | 附近商铺 |
| `sign:{userId}:{yyyyMM}` | Bitmap | 签到位 | 无 | `RedisConstants:21` | 用户签到 |

### 4.2 代码内联（未收敛到常量类）的 Key

| Key Pattern | 类型 | 位置 | 用途 |
| --- | --- | --- | --- |
| `seckill:order:{voucherId}` | Set | `seckill.lua:11` | 已抢到资格的用户 |
| `seckill:stock:{voucherId}` | String | `seckill.lua:9`、`seckillRollback.lua:7` | Lua 内的库存键（与 Java 常量重复定义） |
| `blog:liked:{blogId}` | ZSet | `BlogServiceImpl.java:92` | 与常量类重复的硬编码字面量 |
| `follows:{userId}` | Set | `FollowServiceImpl.java:42,80,82` | 关注集合（未定义常量） |
| `lock:order:{userId}` | Redisson RLock | `VoucherOrderServiceImpl.java:117` | 消费端一人一单锁 |
| `lock:{name}` | String | `SimpleRedisLock.java:21` | 自研分布式锁前缀 |
| `icr:{keyPrefix}:{yyyy:MM:dd}` | String | `RedisIdWorker.java:37` | Redis 全局自增 ID |

### 4.3 需要记录的问题

- Redis Key **存在重复定义**：`seckill:stock:` 同时存在于 Java 常量与 Lua 字面量中；
  `blog:liked:` 同时存在于常量类与 `BlogServiceImpl` 硬编码中。后续若 key 改名需同步多处。
- `seckill:stock:` 与 `seckill:order:` **没有设置 TTL**，批次结束后将成为永久残留键。
- `seckillRollback.lua` 直接对 `stock` 执行 `incrby`，**无幂等保护（问题 5）**。

---

## 5. RabbitMQ 拓扑基线

### 5.1 代码声明的拓扑（`RabbitMQConfig.java`）

```
Producer (VoucherOrderServiceImpl.seckillVoucher)
        │  convertAndSend(exchange, routingKey, VoucherOrder, CorrelationData=orderId)
        ▼
┌───────────────────────────────────────────────┐
│ Exchange: seckill.order.exchange              │  Direct, durable=true, autoDelete=false
└───────────────────────────────────────────────┘
        │ RoutingKey: seckill.order
        ▼
┌───────────────────────────────────────────────┐
│ Queue: seckill.order.queue                    │  durable
│   x-dead-letter-exchange    = seckill.order.dlx.exchange
│   x-dead-letter-routing-key = seckill.order.dlx
└───────────────────────────────────────────────┘
        │
        ▼
   Consumer: VoucherOrderServiceImpl#onOrderMessage
   @RabbitListener(queues = "seckill.order.queue")
   @Payload VoucherOrder  ← 直接发送数据库 Entity（违反文档第 14 节要求）
        │
        ├─ ACK          → channel.basicAck
        └─ DEAD_LETTER  → channel.basicNack(requeue=false)
        ▼
┌───────────────────────────────────────────────┐
│ DLX: seckill.order.dlx.exchange               │  Direct, durable
└───────────────────────────────────────────────┘
        │ RoutingKey: seckill.order.dlx
        ▼
┌───────────────────────────────────────────────┐
│ DLQ: seckill.order.dlx.queue                  │  durable，无消费者、无治理入口
└───────────────────────────────────────────────┘
```

### 5.2 可靠性配置现状

| 能力 | 配置位置 | 现状 |
| --- | --- | --- |
| Publisher Confirm | `application.yaml:29` `publisher-confirm-type: correlated` | ✅ 已开启 |
| Return | `application.yaml:30` `publisher-returns: true` + `setMandatory(true)` | ✅ 已开启 |
| Confirm 回调 | `RabbitMQConfig:95-99` | ⚠️ 仅 `log.error`，不做任何状态处理 |
| Return 回调 | `RabbitMQConfig:100-102` | ⚠️ 仅 `log.error`，不做补偿 |
| Consumer ACK | `application.yaml:33` `acknowledge-mode: manual` | ✅ 手动 ACK |
| prefetch | `application.yaml:34` `prefetch: 1` | ✅ |
| 消费者重试 | `application.yaml:36-37` `retry.enabled: false` | 已关闭（避免 recoverer 卡死） |
| 致命异常处理 | `RabbitMQConfig:87-89` `ConditionalRejectingErrorHandler(rejectManual=true)` | ✅ 反序列化失败 → basicNack 进死信 |
| DLX / DLQ | `RabbitMQConfig:56-71` | ✅ 已声明 |
| 消息幂等 | `VoucherOrderServiceImpl:135-141` | ⚠️ 仅 `count()` 查询，无 DB 唯一索引兜底 |
| DLQ 治理 | — | ❌ **完全缺失（问题 6）** |

### 5.3 Producer 端 Confirm 等待方式

`VoucherOrderServiceImpl:191`

```java
CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);
```

Producer **同步阻塞等待 Confirm 最多 5 秒**。这削弱了「异步削峰」的实际效果
（Tomcat 线程被占用），且超时后走 `catch` 分支立即回滚 Redis（**问题 4**）。
该设计在 Phase 7 / Phase 8 需要重新评估。

---

## 6. 文档第 24 节「已发现的技术问题」逐条核验

| # | 文档描述 | 代码证据 | 结论 |
| --- | --- | --- | --- |
| 1 | Lua 不检查活动开始/结束时间 | `seckill.lua` 只校验 stock 与 order set，无任何时间判断 | ✅ 属实 |
| 2 | 订单表缺少数据库唯一约束 | `tb_voucher_order` 仅 `PRIMARY KEY(id)` | ✅ 属实 |
| 3 | 消费端扣库存与保存订单无事务 | `VoucherOrderServiceImpl:113 createVoucherOrder` 无 `@Transactional` | ✅ 属实 |
| 4 | Confirm Timeout 立即回滚 Redis | `:198-202` `catch (Exception e) { rollbackSeckill(...) }`，超时与 NACK 不区分 | ✅ 属实 |
| 5 | 补偿 Lua 不幂等 | `seckillRollback.lua` 无条件 `incrby stockKey 1` + `srem` | ✅ 属实 |
| 6 | DLQ 无处理机制 | `RabbitMQConfig` 只声明队列，全仓库无 DLQ 消费者/查询/重放代码 | ✅ 属实 |
| 7 | Redisson 被当成防重复主要手段 | `:117` `lock:order:{userId}` + `:135` count 查询构成唯一防线 | ✅ 属实 |
| 8 | 登录没有完成 logout | `UserController:60-64` 返回 `Result.fail("功能未完成")` | ✅ 属实 |
| 9 | 配置存在明文密码 | `application.yaml` MySQL `password: 123`、Redis `password: 123321`；`RedissonConfig:16` 硬编码 IP+密码 | ✅ 属实 |
| 10 | 固定 Windows 图片上传目录 | `SystemConstants:4` `"D:\\lesson\\nginx-1.18.0\\html\\hmdp\\imgs\\"` | ✅ 属实 |

### 额外发现的基线问题（供后续 Phase 参考）

| # | 问题 | 位置 | 影响 |
| --- | --- | --- | --- |
| A | MQ 消息体直接使用数据库 Entity `VoucherOrder` | `VoucherOrderServiceImpl:95`、`:189` | 违反文档第 14 节「MQ Message 不允许直接发送数据库 Entity」 |
| B | `seckill.lua` 在库存 key 缺失时会运行时报错 | `seckill.lua:15` `tonumber(redis.call('get', stockKey))` 返回 `nil`，`nil <= 0` 抛 Lua 错误 | 批次未初始化 Redis 时请求直接 500 |
| C | Redis 库存与 DB 库存是两套独立扣减 | `seckill.lua` 扣 Redis；`VoucherOrderServiceImpl:144` 扣 DB | 无对账机制，不一致后永久漂移 |
| D | `seckillRollback.lua` 在「库存 key 已过期」时会把 key 重新 `incrby` 为 1 | `seckillRollback.lua:11` | 制造出「凭空 1 个名额」的脏数据 |
| E | `RedisConstants` 中大量常量在删除模块后将变为死代码 | `RedisConstants.java` | Phase 1 需同步清理 |
| F | `MvcConfig` 拦截器白名单引用了将被删除的路径 | `MvcConfig:22-30` 含 `/shop/**`、`/blog/hot`、`/voucher/**` | Phase 1 必须同步修改，否则新接口鉴权行为错误 |
| G | 全仓库存在大量被注释掉的「多套历史实现」 | `VoucherOrderServiceImpl:68-84, 219-419` | Redis Stream/阻塞队列/`synchronized`/自研锁四套历史实现并存，Phase 10 需清理 |

---

## 7. 待用户确认的环境问题（阻塞项）

以下三项无法由代码分析解决，需要用户提供信息或授权：

1. **本机 MySQL 凭据**：`root/123` 与空密码均被拒绝。需要真实账号密码，
   否则无法验证真实库结构、无法运行集成测试、无法验证 Phase 8 的唯一索引是否生效。
2. **Redis 连接信息**：项目配置指向 `192.168.150.101:6379`（不可达），
   本机 `127.0.0.1:6379` 需认证但 `123321` 不正确。需要正确的密码，
   或授权把配置改为本机地址。
3. **RabbitMQ 管理账号**：`guest/guest` 无法访问管理 API（AMQP 5672 可达）。
   若需要查询/重放 DLQ，需要可用的管理账号。

> 这三项不会阻塞 Phase 1（纯删除 + 编译验证），
> 但会阻塞任何「运行时验证」类验收标准。

---

## 8. Git 基线

- 当前分支：`master`
- HEAD：`433cdfb feat(seckill): 秒杀异步下单迁移至 RabbitMQ`
- 已有 tag：无
- 未跟踪文件：
  - `黑马点评 → 实验资源预约平台重构.md`
  - `实验资源预约平台-分阶段重构Phase计划.md`
- 计划建立的 checkpoint tag：`before-lab-reservation-refactor`

---

## 9. Phase 0 验收对照

| 验收标准 | 状态 |
| --- | --- |
| JDK 8 Maven compile 成功 | ✅ BUILD SUCCESS（72 文件） |
| 数据库基线已记录 | ✅ 基于 `db/hmdp.sql`；实测库待凭据 |
| Redis Key 已记录 | ✅ 15 个 Key Pattern 全部登记 |
| RabbitMQ 拓扑已记录 | ✅ Exchange/Queue/RoutingKey/DLX/DLQ 全部登记 |
| Git 基线已建立 | ⏳ 待用户批准后打 tag |

## 10. 完成标志

```
PHASE 0 — PASS（Git checkpoint 待批准）
```
