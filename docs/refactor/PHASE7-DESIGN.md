# Phase 7 Design — RabbitMQ 异步预约落库

> 本 Phase 把 Phase 6 的「Redis 预接受」接上「MySQL 落库」：Lua 抢占成功后把预约请求
> 投给 RabbitMQ，由消费者完成 `tb_reservation` 落库与 `remaining_quota` 扣减。
> 计划文档的原话是「将 Redis 预接受成功后的预约请求异步投递到 RabbitMQ」。
>
> 本设计**不是**把 `433cdfb` 的秒杀 MQ 实现改个名字。可复用的是它那套**已被逐条审计过**的
> 可靠性机制（手动 ack + 关自动重试 + `ConditionalRejectingErrorHandler` + confirm/return +
> DLX 不 requeue），这部分按 `PHASE5-DESIGN.md §11` 的八条验收清单逐条继承；
> **不可复用**的是它的数据模型与失败语义——本 Phase 的批次名额键会被删除，
> 这引出一个参考实现根本不存在的正确性风险，见 §6。

---

## 0. 本 Phase 的交付与两条必须先说清的边界

**交付**：`POST /reservation` 在 Lua 抢占成功后，把一条 `ReservationMessageDTO` 投给
RabbitMQ；消费者幂等落库 `tb_reservation` 并扣减 `tb_reservation_batch.remaining_quota`。
对外接口与响应语义**完全不变**（仍是"抢占成功即返回 `reservationId`"）。

**边界一：本 Phase 引入的唯一新失败窗口，以及它的补偿口径。**

Phase 6 的抢占是原子的，但它不落库。Phase 7 把落库搬到 MQ 之后，"Lua 已扣名额 + 已标记用户"
与"消息确实进了 broker"之间出现了一个新的失败窗口（broker 不可用、路由不到队列、confirm 失败）。
本设计的处理是**参考实现的方案 A**：发送端同步等 confirm，失败则**回滚 Redis**（归还名额 + 移除用户标记），
向用户返回"预约失败，请重试"。

**边界二：本 Phase 不发明对账机制。**

即使有了回滚，仍存在一个**模糊态**：broker 已收到消息但 confirm 超时，此时回滚已经执行、
消费者随后却真的落了库。这会让 Redis 名额比 MySQL 多 1。这与参考实现 §8 登记的是同一类
固有代价（at-least-once 补偿），**本 Phase 只保证有界并把它测出来，不发明对账/补偿**——
对账属 Phase 8。见 §6.3、§9、§18。

---

## 1. 端到端链路：Phase 6 的接缝在哪

### 1.1 Phase 6 交付的链路（现状）

```
学生 POST /reservation {batchId}
  ↓ 参数 / 登录人 / 批次存在 / 状态闸门 / 设备闸门 / 窗口闸门
  ↓ 生成 reservationId = redisIdWorker.nextId("reservation")
  ↓ reservation.lua 一次原子抢占（EXISTS→窗口→容量→去重→DECR+SADD+EXPIRE）
  ↓ 返回 Result.ok(reservationId)          ← 到这里为止，MySQL 零写入
```

代码位置：`ReservationServiceImpl.submit`（`src/main/java/com/hmdp/service/impl/ReservationServiceImpl.java:68-75`）。
**Phase 7 就在 `tryAcquire` 返回 `SUCCESS` 之后、`return reservationId` 之前插入投递。**

### 1.2 Phase 7 之后的完整链路

```
学生 POST /reservation {batchId}
  ↓ …（Phase 6 全部前置校验，一行不改）
  ↓ reservation.lua 抢占成功 → reservationId
  ↓ 【新】投递 ReservationMessageDTO{reservationId,userId,batchId,createTime} 到 reservation.exchange
  │     ├─ 同步等 publisher confirm（≤5s）
  │     ├─ 检查 returned（路由不到队列）
  │     └─ 失败 → reservationRollback.lua 归还名额 → 抛「预约失败，请重试」
  ↓ 返回 Result.ok(reservationId)                    ← 响应语义与 Phase 6 完全一致
                                    ⋯⋯ 异步 ⋯⋯
  ↓ 【新】ReservationMessageConsumer 收到消息
  │     ① 幂等预检查（user_id + batch_id 已存在 → ACK 丢弃）
  │     ② Redisson lock:reservation:{userId}  tryLock(2, 10, SECONDS)
  │     ③ @Transactional：INSERT tb_reservation + UPDATE batch SET remaining_quota-1 WHERE remaining_quota > 0
  │     ④ 成功/幂等重复 → basicAck；名额冲突/异常 → basicNack(requeue=false) → DLQ
```

### 1.3 本 Phase 明确不做

| 不做 | 归属 |
| --- | --- |
| Redis ↔ MySQL 名额对账、补偿任务、告警 | Phase 8 |
| 取消预约、履约核销、我的预约列表 | Phase 9 |
| 重试计数 / 延迟重投 / 死信重放工具 | 无计划项，且与 §2.4 的重试口径冲突，见 §3.3 |
| 消息级 traceId / 链路追踪 | 非计划项 |
| 把设备可用性搬进 Redis | Phase 6 §4.4a 已否决，本 Phase 不动 |

---

## 2. 拓扑设计

### 2.1 命名（计划 §10 给出了推荐命名，本设计采纳并补全一处）

计划给出的推荐拓扑：

```text
reservation.exchange
reservation.create
reservation.create.queue
reservation.dlx
reservation.create.dlq
```

计划同时写明"最终命名由实际配置确定"，并要求"必须重新命名并审查"。本设计的落实：

| 角色 | 名称 | 类型/参数 |
| --- | --- | --- |
| 业务交换机 | `reservation.exchange` | `direct`，`durable=true` |
| 业务路由键 | `reservation.create` | — |
| 业务队列 | `reservation.create.queue` | `durable=true`，`x-dead-letter-exchange=reservation.dlx`，`x-dead-letter-routing-key=reservation.create.dlq` |
| 死信交换机 | `reservation.dlx` | `direct`，`durable=true` |
| 死信路由键 | `reservation.create.dlq` | — |
| 死信队列 | `reservation.create.dlq` | `durable=true` |

**计划未给出 DLX 路由键**，本设计补为 `reservation.create.dlq`。选择"死信路由键 = 死信队列名"
而非参考实现的 `<domain>.<entity>.dlx` 形式，理由：`direct` 交换机下路由键只需在**该交换机内唯一**，
而 `reservation.dlx` 上只挂一个队列，两套名字（`…dlx` 与 `…dlq`）只会制造"哪个是哪个"的阅读成本。
死信交换机名仍保留 `reservation.dlx`，与计划一致。

**为什么是 `direct` 而不是 `topic`/`fanout`**：只有一种消息、一个消费目的地。
`topic` 的通配符能力在这里没有消费者；`fanout` 会失去"路由键写错就进不了队列"的**可检测性**
（`fanout` 无条件路由，配错路由键仍然能进队列，于是 `mandatory` + return 这条防线就失去了意义）。
`direct` 让"路由键写错"表现为 return → 抛异常 → 回滚，可见而非静默。

### 2.2 与 API 的关系

`reservation.exchange` 是**内部**拓扑，不暴露给前端，不改任何 HTTP 契约。
`POST /reservation` 的请求/响应体、状态码、错误文案在成功路径上与 Phase 6 逐字一致。

### 2.3 三个配置项已经就位，本 Phase 不改 `application.yaml`

Phase 5.0 删除 `RabbitMQConfig.java` 时**保留了** `spring.rabbitmq` 段（`application.yaml:25-39`），实测内容与本 Phase 所需完全一致：

```yaml
spring.rabbitmq:
  publisher-confirm-type: correlated   # §5 生产者要等 confirm
  publisher-returns: true              # §5 要能区分「broker 没确认」与「路由不到队列」
  listener.simple:
    acknowledge-mode: manual           # §7 消费者显式 ack/nack
    prefetch: 1
    retry.enabled: false               # §7 手动 ack 下自动重试会卡死消费者
```

因此**本 Phase 对 `application.yaml` 零改动**。这是一处值得记的事实：它是 Phase 5.0 那次"只删代码、
保留配置"的裁决在本 Phase 收回的收益。

### 2.4 重试口径：不 requeue，不自动重试（继承 `PHASE5-DESIGN.md §11`）

`PHASE5-DESIGN.md §11` 把 `433cdfb` 的实测结论固化为八条。本 Phase 逐条继承，其中与重试有关的三条：

1. **手动 ACK** + `prefetch: 1`；
2. **`retry.enabled = false`**——手动 ack 模式下默认 recoverer 不会真正 nack（`ContainerUtils.isRejectManual`
   只对顶层异常做 `instanceof`、不穿透 cause 链），会**卡死消费者**；
3. **`ConditionalRejectingErrorHandler.setRejectManual(true)`**——把"监听器方法体之前"的致命异常
   （典型是 JSON 反序列化失败）转成 `basicNack(requeue=false)` 进死信。

补充第 4 条本设计自己的理由：**全程不使用 `requeue=true`**。RabbitMQ 的 requeue 无延迟，
消息立即回到队头；`prefetch=1` 下这就是一个打死消费者的热循环。不存在"退避重试"这个选项
（那需要延迟交换机插件或 TTL+DLX 两级队列，属新增组件，计划未要求）。

**这条口径直接决定了 §3.3 的 DTO 字段取舍。**

### 2.5 两条承重论断的字节码复核（本 Phase 自己跑的，不转述参考实现）

`PHASE5-DESIGN.md §11` 的八条来自 `433cdfb` 时期对 Spring AMQP 的反编译结论。本设计在
**当前 classpath 的实际 jar** 上重新核验了其中两条承重的（`spring-rabbit-2.2.18.RELEASE`，
与参考实现同一版本），结论一致：

**复核一：`RabbitTemplate.doSend` 确实不阻塞等待 confirm。**
`javap -c` 反汇编 `doSend(Channel, String, String, Message, boolean, CorrelationData)`
（131 条指令）后统计：对 `setupConfirm` 的调用 **1 次**，对 `getFuture` / `Future.get`
的调用 **0 次**。
→ 所以 §5.2 里那句 `correlationData.getFuture().get(5, TimeUnit.SECONDS)` 是**承重**的。
只调 `convertAndSend` 而不等待，nack 与超时**永远不会被观测到**，回滚补偿会静默失效——
失败表现是"名额被扣掉、库里有记录、用户却收到成功"，而不是任何可见的报错。

**复核二：`ContainerUtils.isRejectManual` 只看顶层异常，不穿透 cause 链。**
类位于 `org.springframework.amqp.rabbit.listener.support.ContainerUtils`（注意有 `support`
子包，不在 `listener` 下）。其字节码为：

```
0: aload_0
1: instanceof  AmqpRejectAndDontRequeueException
4: ifeq         21
7: aload_0
8: checkcast   AmqpRejectAndDontRequeueException
11: invokevirtual AmqpRejectAndDontRequeueException.isRejectManual()Z
14: ifeq        21
17: iconst_1
18: goto        22
21: iconst_0
22: ireturn
```

即 `ex instanceof AmqpRejectAndDontRequeueException && ((…) ex).isRejectManual()`。
同时确认 `AmqpRejectAndDontRequeueException` 有单参构造 `(Throwable)`（`rejectManual` 默认 false）
与四参构造 `(String, boolean, Throwable)`。
→ 所以 §2.4 第 2 条成立：`RejectAndDontRequeueRecoverer` 抛出的顶层异常是
`ListenerExecutionFailedException`（**不是** `AmqpRejectAndDontRequeueException`），
`ackRequired` 为 false、不执行 `basicNack`，消息永远 unacked，`prefetch=1` 下消费者**卡死**。
**`retry.enabled: false` 是必需项，不是优化项。**

---

## 3. 消息契约：`ReservationMessageDTO`

### 3.1 字段

```java
public class ReservationMessageDTO implements Serializable {
    private Long reservationId;   // tb_reservation 主键，RedisIdWorker 生成
    private Long userId;
    private Long batchId;
    private LocalDateTime createTime;
}
```

位置：`com.hmdp.mq.ReservationMessageDTO`。**新建 `com.hmdp.mq` 包**而非放进既有 `dto/`，
理由：`dto/` 装的是 HTTP 入参出参（`ReservationSubmitDTO`/`LoginFormDTO`…），MQ 载荷是另一条边界的契约。
计划明确要求"DTO 与 Entity 分离"，把它们放进不同包让这条分离在结构上可见，而不是靠约定。

### 3.2 明确不发数据库 Entity

计划原文："禁止直接发送数据库 Entity"。

参考实现发的正是 `VoucherOrder` **实体**（交付报告 §3.6）。本 Phase 的 `ReservationMessageDTO`
与 `com.hmdp.entity.Reservation` 是**两个类**，且 DTO 只带消费者真正需要的 4 个字段。
好处有二：(a) 实体加字段不会静默改变线上消息格式；(b) 消息里出现 `tb_reservation` 的
`updateTime` 这类纯 DB 记账字段是没有意义的。

### 3.3 不携带 `requestId`，不携带 `retryCount`

计划列了 `requestId`、`reservationId` 两个 ID，并注明"是否携带 `retryCount` 由 Agent 根据重试策略判断"。

- **不携带 `requestId`**：Phase 6 §5 已经裁定二者合一，理由已固化（一次提交最多一条预约，
  两个 ID 恒等；幂等真正需要的是 `(userId, batchId)`，已由 Redis Set + §4 的唯一索引保证）。
  本 Phase 沿用，不重开这个问题。
- **不携带 `retryCount`**：§2.4 的重试策略是**不重试**——失败即 `basicNack(requeue=false)` 进死信。
  一个"没有任何代码会写入、也没有任何代码会读取"的字段是纯仪式，与 Phase 6 拒绝
  `requestId` 的理由同构。**只有当将来引入延迟重投时它才有意义，那时它应随重投机制一起加入。**

### 3.4 序列化：必须复用 Boot 的 `ObjectMapper`

`ReservationMessageDTO` 带一个 `LocalDateTime` 字段。参考实现 §4.2 的结论必须继承：
`Jackson2JsonMessageConverter` **必须**注入 Spring Boot 自动装配的 `ObjectMapper`
（`new Jackson2JsonMessageConverter(objectMapper)`），否则 `JavaTimeModule` 没有注册，
`createTime` 一旦非空就会序列化/反序列化失败。

这个失败尤其危险：它发生在"监听器方法体之前"，靠 §2.4 第 3 条才进得了死信，
否则表现是**消费者静默卡死、消息永远 unacked**。所以这是一条硬约束，不是风格建议。

---

## 4. 数据模型：`tb_reservation`

### 4.1 DDL（新增 `src/main/resources/db/phase7_reservation.sql`）

沿用 Phase 5 的"每 Phase 一个 DDL 文件、幂等 `IF NOT EXISTS`"惯例：

```sql
CREATE TABLE IF NOT EXISTS `tb_reservation` (
  `id`          bigint unsigned NOT NULL COMMENT '预约id（RedisIdWorker 生成，非自增）',
  `user_id`     bigint unsigned NOT NULL COMMENT '用户id',
  `batch_id`    bigint unsigned NOT NULL COMMENT '预约批次id',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_batch` (`user_id`, `batch_id`) USING BTREE,
  KEY `idx_batch_id` (`batch_id`) USING BTREE,
  KEY `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='预约记录';
```

### 4.2 `id` 不是自增——这是最容易写错的一处

`Reservation` 实体的主键必须写 `@TableId(value = "id", type = IdType.INPUT)`，
**不能**照抄 `ReservationBatch` 的 `IdType.AUTO`。

因为 `reservationId` 由 `redisIdWorker.nextId("reservation")` 在 **Lua 之前**生成（Phase 6 §5 的裁定：
"Lua 若因超时/结果未知，必须有一个事先存在的凭证"）。这个 ID 必须原样落库，否则
"学生手里的 `reservationId`" 与"库里的主键"就是两个东西，Phase 9 的取消/核销会立刻断链。

写成 `AUTO` 的后果很具体：MyBatis-Plus 会忽略传入的 id 而用数据库自增值，
于是 `submit` 返回的凭证查不到任何记录。**这是 §13 必须有一条断言覆盖的点**
（断言"返回的 reservationId 能在 DB 里按主键查到"，而不是断言"落了一条记录"）。

### 4.3 `uk_user_batch` 是本 Phase 的最后一道防线

参考实现 §5 用 `(user_id, voucher_id)` 唯一索引兜底重复落库，本 Phase 用 `(user_id, batch_id)`。
而且这里的语义比参考实现更**贴切**：Phase 6 的业务不变式原文就是"一个用户对一批次最多占一个名额"，
`(user_id, batch_id)` 恰好是它的 DB 表达。Redis 的 users Set 与这个唯一索引是同一约束的
两种实现（快路径 / 兜底），不是两个不同的规则。

对现有代码的影响：`save()` 仅在（已被 Redisson 锁与幂等预检查挡掉的）极端竞态下抛
`DuplicateKeyException`，会被 §7 的 try/catch 捕获 → `basicNack(requeue=false)` → 进死信，**可见而非静默**。

### 4.4 不含 `status` 列（待用户裁定，见 §17-D1）

计划 §10 的 Phase 7 验收标准里没有任何一条涉及"预约状态"。预约的状态机（取消、核销）
是计划 §12 Phase 9 的标题本身（"Cancellation & Fulfillment"）。

因此本设计主张：**Phase 7 只建"存在即可用"的预约表，不加只有一个取值的 `status` 列。**
一个 `ReservationStatus { RESERVED }` 单值枚举是纯仪式，与 §3.3 拒绝 `retryCount` 的理由同构。
Phase 9 需要一个 DDL 文件来加状态列，而这正是本项目既有的"每 Phase 一个 DDL"惯例。

**这一条列入 §17 决策点，因为它是对 schema 的承诺，属用户裁定范围。**

同理**不加 `equipment_id`**：它是从 `batch_id` 可 join 得到的派生字段（设备不会被删除、
批次没有删除接口），提前反规范化会引入"批次改挂设备后历史记录跟着变"的问题。
Phase 9 若确证"我的预约列表"有性能需要，那时再加列也不迟。

---

## 5. 生产者

### 5.1 位置：留在 `ReservationServiceImpl`，不抽独立 Producer 类

投递逻辑与"回滚"必须在同一个 catch 里：Lua 已扣名额，投递失败就必须在同一处归还。
把投递抽到 `ReservationMessageProducer` 之后，`submit()` 仍要拿到失败信号并调用回滚，
**耦合没有减少，只多了一层间接**。

因此：`ReservationServiceImpl` 新增一个私有方法 `publishReservationMessage(...)`，
`RabbitTemplate` 直接注入该 Service。这与参考实现的做法一致。

> 对照物：§7 的**消费者**则必须拆成两个 bean，理由完全不同（Spring 事务自调用陷阱），见 §7.3。
> 两处的不同结论来自不同的约束，不是风格摇摆。

### 5.2 发送与确认

```java
CorrelationData correlationData = new CorrelationData(String.valueOf(reservationId));
try {
    rabbitTemplate.convertAndSend(
            RabbitMQConfig.RESERVATION_EXCHANGE,
            RabbitMQConfig.RESERVATION_CREATE_ROUTING_KEY,
            message,
            correlationData);
    // Spring AMQP 2.2.18 的 doSend 只「异步注册」confirm，不会阻塞等待；
    // 不显式 get() 就永远捕获不到 nack / 超时，回滚补偿会静默失效。
    CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);
    if (!confirm.isAck()) {
        throw new AmqpException("预约消息未获得 RabbitMQ 确认：" + confirm.getReason());
    }
    if (correlationData.getReturnedMessage() != null) {
        throw new AmqpException("预约消息无法路由到队列");
    }
} catch (Exception e) {
    log.error("预约消息发送失败，回滚名额：reservationId={}, userId={}, batchId={}",
            reservationId, user.getId(), batchId, e);
    reservationQuotaRollbackExecutor.rollback(batch, user.getId());
    throw new ServiceUnavailableException("预约失败，请重试");   // §17-D2
}
```

三处关键点，逐条对应 `PHASE5-DESIGN.md §11` 第 4 条：

1. **`getFuture().get(5s)` 必须显式等待**——参考实现 §4.1 反编译确认 `doSend` 只调 `setupConfirm`
   注册回调，不阻塞。只调 `convertAndSend` 不等待，nack 与超时都不会被捕获。
2. **`mandatory=true` + return 检查**——`publisher-returns: true` 只让 broker 把不可路由的消息退回，
   还必须在 `RabbitTemplate` 上 `setMandatory(true)`，否则退回根本不会发生。
3. **`CorrelationData` 用 `reservationId` 作 id**——它同时承担"哪一笔预约的投递"这个关联语义。

### 5.3 同步等待的代价，如实记账

`get(5s)` 会把请求线程最多阻塞 5 秒。这与"MQ 削峰"的直觉相反，但**削的是 MySQL 的峰，不是请求延迟**。
broker 正常时 confirm 在毫秒级返回；只有 broker 异常时才会走到 5 秒上限，而那条路径本来就要失败。
5 秒取值与参考实现一致，不另行调整。

### 5.4 `@PostConstruct` 注册回调（继承参考实现）

`RabbitMQConfig` 中 `@PostConstruct` 里设置 `setConfirmCallback` / `setReturnCallback` / `setMandatory(true)`。
注意：这两个回调**只负责记录日志**，真正的失败判定靠 §5.2 的显式等待——
因为 confirm 回调是异步的，它可能在 `submit()` 返回之后才触发，不能用来决定返回值。

---

## 6. 回滚脚本 `reservationRollback.lua`——本 Phase 最关键的一处防御

### 6.1 参考实现的回滚脚本在这里会出错

参考实现（交付报告 §3.5）的回滚是"库存 +1 + 移除用户标记"。**那套逻辑搬到本 Phase 会产生一个
会破坏 Phase 6 核心不变式的 bug**：

Phase 6 §2.2 确立的规则是 **「`reservation:batch:quota:{id}` 键存在」⟺「批次可预约」**。
`close`/`cancel` 的动作是**删除**这个键（`ReservationBatchServiceImpl` 的双键删除）。
而 Redis 的 `INCR` 对一个**不存在的键**会**创建它并置为 1**。

于是这条时序是致命的：

```
T1  Lua 抢占成功                      quota 4→3
T2  管理员 close 批次                  两个键被 DEL
T3  MQ 投递失败 → 回滚 INCR            quota 键被「复活」，值 = 1
    ↓
    键存在 ⟺ 批次可预约  →  一个已 CLOSED 的批次重新变成可预约
    且因为 §1.2 的 Java 状态闸门读的是 MySQL（status=CLOSED），
    正常的 submit 会被 Java 拦下——但只要出现任何绕过 Java 闸门的路径
    （例如 Phase 8/9 引入的新调用点），Lua 的 EXISTS 会放行。
```

### 6.2 脚本本体：`EXISTS` 守卫

```lua
-- KEYS[1] = reservation:batch:quota:{batchId}
-- KEYS[2] = reservation:batch:users:{batchId}
-- ARGV[1] = userId
-- 返回 1 = 名额已归还；0 = 名额键不存在（批次已关闭/取消），只清理用户标记
if redis.call('EXISTS', KEYS[1]) == 0 then
    redis.call('SREM', KEYS[2], ARGV[1])
    return 0
end
redis.call('INCR', KEYS[1])
redis.call('SREM', KEYS[2], ARGV[1])
return 1
```

> **勘误（2026-10-04，Task 2 在途时发现）**：本段初稿的两个分支都写了 `return 0`，与上一行注释
> 及 §13.2 的断言（"键存在 → 返回 1"）自相矛盾。返回码恒为 0 会让 §13.2 的 T6 **退化成恒真空断言**——
> 恰好是本 Phase 一直在防的"无判别力测试"。已修正为归还分支 `return 1`。
> 两个返回码是脚本唯一的可观测输出，也是区分「正常补偿」与「与管理员 close 竞态」的唯一信号（§11-R1），
> 不能省。**若 Task 2 交付的 `reservationRollback.lua` 仍是双 `return 0`，按 §6.2 修正后返工。**

写成 Lua（而非 Java 的 `hasKey` + `increment`）的理由：`EXISTS` 与 `INCR` 之间存在
与 `close` 的 `DEL` 竞态，必须原子。这与 Phase 6 用 Lua 的理由是同一类。

**用户标记那一半为什么不需要守卫**：`SREM` 对不存在的键是 no-op，且**不会创建键**。
若批次已关闭，两个键都没了，`SREM` 静默返回 0，正是期望行为。
（对照 `INCR`/`SADD` 会创建键——只有"增"的操作需要守卫。）

### 6.3 为什么不夹取"归还后不超过总名额"——与 Phase 6 的取舍一致

Phase 6 §3.3 刻意**不加** `DECR` 之后的负数夹取，理由是"加了它，『名额永不为负』这条断言就失去判别力，
会把『守卫被删掉』伪装成绿"。**这里必须做对称的取舍**：不加"INCR 后不超过 `totalQuota`"的夹取。

- 夹取会掩盖"同一笔预约被回滚两次"这类真实缺陷；
- 它防护的场景（§0 边界二的模糊态）本来就不是"回滚错了"，而是"confirm 超时下的固有歧义"，
  夹取只是把症状藏起来，对账（Phase 8）反而更难发现。

**模糊态的上界，如实推导**（这是本 Phase 最重要的量化结论）：

```
总名额 4。用户 A 抢占成功（Redis 4→3），broker 已收到消息但 confirm 超时
  → 回滚（Redis 3→4），A 收到「失败，请重试」，但消费者随后真的落了 A 的预约
  → MySQL remaining_quota 4→3，Redis 仍是 4          【Redis 比 MySQL 多 1】

此后 B、C、D 依次抢占：Redis 3/2/1，MySQL 2/1/0     【同步】
再 E 抢占：Redis 的 Lua 看到 1 > 0 → 放行，Redis 0
  → 消费者 UPDATE … WHERE remaining_quota > 0 影响 0 行
  → 判为 QUOTA_CONFLICT → basicNack(requeue=false) → 死信
```

结论两条，都要进 Review 的实测清单：

1. **MySQL 永不超卖**——`WHERE remaining_quota > 0` 这条守卫把这个固有偏差**转换成死信**，
   而不是转换成超卖。这正是它必须存在的原因（§7.2）。
2. **偏差有界**：每发生一次 confirm 超时，偏差 +1；不会自发放大。代价是 E 这种"Redis 说成功、
   库说没有"的滞留学员，由 Phase 8 的对账/告警兜住。**本 Phase 只把它测出来并登记，不发明补偿。**

---

## 7. 消费者

### 7.1 幂等预检查：为什么用 `(user_id, batch_id)` 而不是 `reservationId`

```
SELECT COUNT(*) FROM tb_reservation WHERE user_id = ? AND batch_id = ?
  > 0  → DUPLICATE（已落库）→ basicAck 丢弃
```

用 `reservationId` 做幂等键是**错的**：它是每次 `submit` 新生成的，重投的同一条消息当然带同一个
`reservationId`，但真正要防的是"同一用户对同一批次落两条"——那正是 `(user_id, batch_id)`。
这与 §4.3 的唯一索引是同一个约束，一快一慢。

### 7.2 扣减 SQL、语句顺序，以及"能不能自愈"的判定

```sql
UPDATE tb_reservation_batch
   SET remaining_quota = remaining_quota - 1
 WHERE id = #{batchId} AND remaining_quota > 0
```

**语句顺序必须是「先 UPDATE，再 INSERT」，不能反过来。** 这是一处会让 `@Transactional`
失效于无声处的陷阱：

> `@Transactional` 只在**抛异常**时回滚，**返回一个值不会回滚**。
> 若先 INSERT 再 UPDATE、发现影响 0 行后 `return QUOTA_CONFLICT`，
> 那笔 INSERT 会被正常提交——库里留下一条**没有扣减对应名额**的预约记录。
> 把顺序倒过来则天然安全：影响 0 行时什么都还没写，直接返回即可。

若先 UPDATE 成功后 INSERT 抛 `DuplicateKeyException`，`@Transactional` 会把 UPDATE 一并回滚，
这正是"唯一索引 + 事务"应有的协同（§4.3）。

影响行数 0 → `QUOTA_CONFLICT`。**这个判定必须区分于"异常"**：

| 场景 | 结论 | 原因 |
| --- | --- | --- |
| UPDATE 影响 1 行 + INSERT 成功 | `PERSISTED` | 正常 |
| 幂等预检查命中 | `DUPLICATE` | 重投，正常 |
| UPDATE 影响 0 行 | `QUOTA_CONFLICT` | Redis/MySQL 名额已不一致，**无法自愈**；此时**未写入任何行** |
| 抛异常（DB 不可用、唯一索引冲突） | 异常 → 死信 | 同上 |

`QUOTA_CONFLICT` **不能**当成成功 ack 掉：它意味着"这笔预接受落不了库"，
静默 ack 会让学员的预约凭空消失而无任何痕迹。进死信 → 人工可见，是唯一诚实的处理。

### 7.3 `@Transactional` 与 Spring 自调用陷阱——消费者必须拆成两个 bean

落库是**两次写入**（INSERT `tb_reservation` + UPDATE `tb_reservation_batch`），必须同事务。
参考实现 §8 把"未包 `@Transactional`"列为已知风险；本设计直接修掉它，但修法有一个陷阱：

> **`@Transactional` 通过 Spring 代理生效。若监听器方法与事务方法在同一个类里，
> 监听器里 `this.persist(...)` 是自调用，不经过代理，事务静默失效。**

因此结构必须是两个 bean：

| 类 | 职责 | 是否带 `@Transactional` |
| --- | --- | --- |
| `com.hmdp.mq.ReservationMessageConsumer` | `@RabbitListener`、决定 ack/nack | 否 |
| `ReservationPersistenceServiceImpl`（`service.impl`） | 幂等预检查 + INSERT + UPDATE | **是** |

**ack 与 commit 的先后必须核对**：监听器先调持久化方法（代理方法返回时事务已提交），
再 `basicAck`。顺序是"先提交、后 ack"，正确；反过来会在提交前 ack，进程崩溃即丢消息。
这一点在 Review 时要盯着实际代码的语句顺序，不能只看有没有注解。

### 7.4 Redisson 边界：`tryLock(2, 10, SECONDS)`（继承参考实现 §4.4）

锁 `lock:reservation:{userId}`，`waitTime=2s`、`leaseTime=10s`。

- **`leaseTime > 0` 会关闭 watchdog**——即使持锁线程挂死，锁最多 10 秒自动过期，**有上界**。
  无参 `tryLock()` 是 `waitTime=0` + watchdog，持有者挂死会无限续期。
- 等 2 秒仍拿不到 → 持有者异常 → `QUOTA_CONFLICT` 同级地进死信，**不用 `requeue=true`**（§2.4）。
- **它是快路径去重，不是正确性保障**：正确性由 §4.3 的唯一索引保证。多消费者下它把同一用户的消息
  串行化、减少无效 DB 压力；`prefetch=1` 下几乎不竞争。

**锁前缀进 `RedisConstants`**（`LOCK_RESERVATION_KEY = "lock:reservation:"`），不写字面量。
这是 Phase 6 关闭 P2-1（"Key 重复定义 Java 常量 vs Lua 字面量"）时立下的规矩的延续。

### 7.5 消费者**不**复查批次状态（一处刻意的设计裁定）

消费者只检查 `remaining_quota > 0`，**不**复查 `status = 'PUBLISHED'`，也**不**复查时间窗口。

理由：这笔预约在 Lua 抢占成功的那一刻是**合法的**——批次开着、名额有、用户在窗口内。
批次随后被 `close`（预约截止时间到了）是**正常的时间流逝**，不是"这笔预约作废"。
若消费者此时拒收，后果是"Redis 名额已扣、学员已被告知成功、库里却没有记录"，
比落库更糟（且把 §6.3 的偏差变成必然而非偶然）。

`CANCELLED` 的情况同理：**学员在批次作废前抢到的名额是既成事实**，
撤销它是 Phase 9 的取消流程或 Phase 8 的补偿该做的事，不是消费者能私自决定的。
§13 有一条断言专门覆盖"接受后 close，消费者仍落库"。

### 7.6 监听器方法**必须**自己捕获一切异常——逃逸到容器＝消息永久卡死

这一条是本 Phase 实测字节码后补写的**正确性要求**（不是风格建议），Review 时要盯住。
它把 §2.4 "不 requeue" 的口径补全成一个完整的故障模型。

`AbstractMessageListenerContainer.invokeErrorHandler(Throwable)` 的字节码是：

```
if (errorHandler == null) { log.warn("...no ErrorHandler has been set."); }
else {
    try { errorHandler.handleError(t); }
    catch (Exception e) { log.error("...error handler threw an exception"); throw e; }
}
return;          // ← handleError 正常返回时，这里也正常返回
```

而 `ConditionalRejectingErrorHandler.handleError(Throwable)` 的字节码是：

```
log(t);
if (!causeChainContainsARADRE(t) && exceptionStrategy.isFatal(t)) {
    if (discardFatalsWithXDeath && 有 x-death) throw new ImmediateAcknowledgeAmqpException(...);
    throw new AmqpRejectAndDontRequeueException("Error Handler converted exception to fatal",
                                                this.rejectManual, t);   // rejectManual 在此注入
}
return;          // ← 非致命：静默返回，不重抛
```

两者合起来给出一个**反直觉但确定的**结论：

| 逃逸到容器的异常 | 结果 |
| --- | --- |
| 被 `isFatal` 判为**致命** | 抛 `AmqpRejectAndDontRequeueException` → `basicNack(requeue=false)` → **进 DLQ** ✅ |
| 被 `isFatal` 判为**非致命** | `handleError` 吞掉、`invokeErrorHandler` 正常返回 → **既不 ack 也不 nack** → 消息永远 unacked → `prefetch=1` 下**消费者永久卡死** ❌ |

第二条与"`retry.enabled=true`"是**同一个故障模式**，只是入口不同。而且 `isFatal` 的判定很窄——
`DefaultExceptionStrategy.isFatal` 要求 `t instanceof ListenerExecutionFailedException` **且** 归因到
`MessageConversionException` / `MethodArgumentResolutionException` / `NoSuchMethodException` /
`ClassCastException` 之一：

```
isCauseFatal(c): c instanceof { spring-amqp MessageConversionException,
                                messaging  MessageConversionException,
                                MethodArgumentResolutionException,
                                NoSuchMethodException, ClassCastException } || isUserCauseFatal(c)
```

因此：

1. **§13.1 的 T6（反序列化失败入死信）是成立的**——`Jackson2JsonMessageConverter` 把 Jackson 的失败
   包成 `org.springframework.amqp.support.converter.MessageConversionException`，恰好命中上表第一个分支。
2. **但任何业务代码抛出的普通异常（`IllegalStateException`、`NullPointerException`、`AmqpException`…）
   都不致命**。若它从监听器方法逃逸出去，消费者就卡死了，且日志里只有一条 `warn`，看起来像"消息没到"。

所以 §7 里"监听器方法决定 ack/nack"不是一句组织方式，而是**必须做到的**：
`ReservationMessageConsumer.onMessage` 的 `try` 必须包住**整个方法体**（含 Redisson 加锁、DTO→Entity 转换、
`persist` 调用），`catch` 里统一 `basicNack(tag, false, false)` 进死信，**绝不让异常穿透到容器**。
`persist` 内部抛异常（如 `DuplicateKeyException`）也由这一层兜住——这正是 §7.2 表格最后一行"异常 → 死信"的落点。

> 与 §2.4 的关系：`ConditionalRejectingErrorHandler.setRejectManual(true)` 负责让**真正致命**的异常
> （反序列化失败）走对路；它**不负责**兜住业务异常——业务异常压根到不了它那里被判致命。
> 两道防线各管一段，缺一不可。

**完整链路（逐跳字节码实证，2026-10-04）**，两条分支的唯一分岔点在 `isFatal`：

```
监听器抛异常
 └─ AbstractMessageListenerContainer.invokeListener
      catch (Exception) → wrapToListenerExecutionFailedExceptionIfNeeded   → 顶层必是 LEFE
 └─ executeListener: catch (RuntimeException ex)
      checkStatefulRetry(ex, msg);  handleListenerException(ex);  athrow ex
        └─ invokeErrorHandler(ex) → ConditionalRejectingErrorHandler.handleError(ex)
             ├─[致命] 抛 ARADRE("...converted exception to fatal", rejectManual, ex)
             │    → invokeErrorHandler 捕获后重抛（athrow ex 那行不再执行）
             │    → rollbackOnExceptionIfNecessary(ARADRE)
             │    → isRejectManual(ARADRE)=true → basicNack(tag, false, shouldRequeue(...))
             │    → shouldRequeue 链上见 ARADRE → false  ⇒ basicNack(requeue=false) → DLQ ✅
             └─[非致命] handleError 静默 return
                  → invokeErrorHandler 正常 return → executeListener 执行 athrow ex
                  → rollbackOnExceptionIfNecessary(LEFE)
                  → isRejectManual(LEFE)=false  ⇒ **一个 nack 都不发** → 永久 unacked → 卡死 ❌
```

**分岔点就是 `isFatal`**。这也解释了为什么 `setRejectManual(true)` 是必需的：它让**致命**分支产出的
ARADRE 带上 `rejectManual=true`，容器才愿意发那次 `basicNack`；否则连致命异常也只会静默 unacked。

---

## 8. 消费者返回二态 → 三态：对参考实现的一处有意改进

参考实现的 `OrderHandleResult { ACK, DEAD_LETTER }` 把**业务结果**与**确认动作**揉在一个枚举里。
本设计拆开：

```java
public enum ReservationPersistResult { PERSISTED, DUPLICATE, QUOTA_CONFLICT }
```

- 持久化服务只回答**业务结果**，不知道 AMQP 的存在；
- 消费者的 ack/nack 映射是三行 switch。

好处是可测性的实质提升：`ReservationPersistResult` 可以被单元/集成测试直接断言
（"这个场景必须返回 `DUPLICATE`"），而 `ACK/DEAD_LETTER` 只能通过"消息有没有进死信队列"间接观察。
让"业务判定"与"传输层动作"各自可断言，是本 Phase 唯一一处对参考实现的结构性改动。

---

## 9. 改动范围（文件清单）

**新增（12）**

| 文件 | 说明 |
| --- | --- |
| `src/main/resources/db/phase7_reservation.sql` | §4.1 DDL |
| `src/main/resources/reservationRollback.lua` | §6.2 回滚脚本 |
| `src/main/java/com/hmdp/config/RabbitMQConfig.java` | §2.1 拓扑 + §3.4 转换器 + §5.4 回调 + §2.4 容器工厂 |
| `src/main/java/com/hmdp/entity/Reservation.java` | §4.2 `IdType.INPUT` |
| `src/main/java/com/hmdp/mapper/ReservationMapper.java` | `BaseMapper<Reservation>` |
| `src/main/java/com/hmdp/mq/ReservationMessageDTO.java` | §3.1 |
| `src/main/java/com/hmdp/mq/ReservationMessageConsumer.java` | §7.3 监听器 |
| `src/main/java/com/hmdp/enums/ReservationPersistResult.java` | §8 |
| `src/main/java/com/hmdp/service/IReservationPersistenceService.java` | §7.3 事务边界 |
| `src/main/java/com/hmdp/service/impl/ReservationPersistenceServiceImpl.java` | §7.1/§7.2/§7.3 |
| `src/main/java/com/hmdp/utils/ReservationQuotaRollbackExecutor.java` | §6.2 脚本调用点（与 `ReservationQuotaExecutor` 对称） |
| `src/test/java/com/hmdp/ReservationMqTest.java`（+ 可能一个回滚测试类） | §13 |

**修改（3）**

| 文件 | 改动 |
| --- | --- |
| `src/main/java/com/hmdp/service/impl/ReservationServiceImpl.java` | §5.1 新增私有投递方法 + 成功路径调用 + catch 回滚 |
| `src/main/java/com/hmdp/utils/RedisConstants.java` | §7.4 新增 `LOCK_RESERVATION_KEY` |
| `docs/refactor/*`、`MIGRATION_STATUS.md` | 设计/Review/状态 |

**不改（重要）**

- `src/main/resources/reservation.lua` —— **Phase 6 的抢占脚本一行不动**。
  F7 实验证明了它的判别力（非原子实现 20 抢 5 放进 9 个人），Phase 7 没有理由碰它。
  这是"零改动"清单里最需要盯住的一项：本 Phase 的风险全在**抢占之后**。
- `application.yaml` —— §2.3，已就位。

---

## 10. 禁止清单（零触碰）

- 不引入 Sharding / 延迟交换机插件 / TTL+DLX 两级重试队列 / 死信重放工具；
- 不改 `POST /reservation` 的请求体、成功响应体、成功状态码；
- 不改 Phase 6 的 Lua 与 `ReservationResultCode` 的六个返回码语义；
- 不动 Phase 5 的批次状态机（`publish`/`close`/`cancel` 的 CAS 守卫）；
- 不动 §2.3 之外的 MQ 配置；
- 不删除 broker 上的任何既有资源（§12）；
- 不把"Redis ↔ MySQL 对账"实现进来（Phase 8 的职责，§0 边界二）。

---

## 11. 高危风险点（Review 必须逐条实测）

| # | 风险 | 为什么高危 | 验证方式 |
| --- | --- | --- | --- |
| **R1** | 回滚 `INCR` 复活已删除的名额键 | 破坏 Phase 6 的"键存在 ⟺ 可预约"，使 **CLOSED 批次重新可约** | §13-T5 直测脚本；断言 `close` 后回滚**不会**让键重新出现 |
| **R2** | `Reservation.id` 误写 `IdType.AUTO` | `submit` 返回的凭证在库里查不到，Phase 9 断链 | §13-T1 断言"按返回的 ID 能查到" |
| **R3** | `Jackson2JsonMessageConverter` 未复用 Boot `ObjectMapper` | `LocalDateTime` 反序列化失败 → **消费者静默卡死** | §13-T6 反序列化失败进死信 + 队列 unacked 归零 |
| **R4** | 监听器与事务方法同类 ⇒ `@Transactional` 静默失效 | INSERT 成功但 UPDATE 失败时留下半个状态 | 读实际代码：两个类；且核对"先 commit 后 ack"的语句顺序 |
| **R5** | 未显式等 confirm | nack/超时捕获不到，**回滚静默失效**，名额凭空消失 | §13-T4 停 broker 后确认回滚真的执行了（名额归还） |
| **R6** | `requeue=true` 出现在任何一处 | 毒消息热循环打死消费者 | 全仓 grep `basicNack` / `basicReject` 的第三参数；§13-T6 断言队列 ready 不增长 |
| **R7** | `remaining_quota > 0` 守卫被删 | §6.3 的固有偏差从"死信"变成 **MySQL 超卖** | §13-T7 变体实验（删守卫 → 必须变红） |
| **R8** | 幂等键用了 `reservationId` | 重投不被识别 → 唯一索引冲突 → 全量进死信 | §13-T2 断言重复投递返回 `DUPLICATE` 而非异常 |
| **R9** | `Docker` 里的 broker 未启动时跑测试 | 测试全红，误判为实现缺陷 | §14 环境前提先核对 |
| **R10** | 监听器方法**未**包住全部逻辑，业务异常逃逸到容器 | `isFatal` 判其非致命 → 既不 ack 也不 nack → **消息永久 unacked、消费者卡死**，日志只有一条 warn（**§7.6 实测字节码结论**） | 读实际代码：`try` 是否包住加锁/转换/`persist` 全程；`catch` 是否统一 `basicNack(tag,false,false)`；并核对 §13-T6 的 "unacked 归 0" 断言 |
| **R11** | 回滚脚本两个分支都 `return 0` | §13.2 的返回码断言退化为**恒真空断言**（§6.2 勘误） | 直读 `reservationRollback.lua`：归还分支必须 `return 1` |

---

## 12. broker 上的存量资源（**已由用户授权并清理完毕**）

`PHASE5-DESIGN.md §11` 登记"Phase 7 建立 `reservation.*` 拓扑时一并清理"。
删除是对共享 broker 的**破坏性且不可逆**操作（队列里的消息会一并消失），
故本设计先列出清单、取得明确授权（§17-D3）后执行。

实测与清理记录（2026-10-04，容器 `rabbitmq` 启动后，管理台 API）：

| 资源 | 清理前 | 归属 | 动作 |
| --- | --- | --- | --- |
| `seckill.order.exchange`（direct） | 0 消息，无消费者 | 本项目 `433cdfb` 遗留，Phase 5.0 删代码后成孤儿 | **已删除**（HTTP 204） |
| `seckill.order.queue` | 0 消息，0 消费者 | 同上 | **已删除**（HTTP 204） |
| `seckill.order.dlx.exchange`（direct） | 0 消息 | 同上 | **已删除**（HTTP 204） |
| `seckill.order.dlx.queue` | 0 消息，0 消费者 | 同上 | **已删除**（HTTP 204） |
| `hello,queue1` / `hello,queue2` | 各 1 条消息 | **与本项目无关**（非本仓产生） | **未触碰**（明确不动） |

**清理后实测**：Broker 上非默认 exchange 数量 = 0，非默认 binding = 0，
队列只剩 `hello,queue1` / `hello,queue2`。`reservation.*` 拓扑由本 Phase 声明时全新创建，
不会与任何既有资源冲突。**这是 Phase 5 §15.7 登记项（"broker 上 `seckill.*` 成为孤儿 → Phase 7 清理"）的关闭。**

---

## 13. 测试计划

> 分工（沿用既有裁定）：**测试代码由 Codex 写；判别力实验由 Claude 亲自跑。**
> 注意 Codex 沙箱**跑不了任何 Spring 集成测试**（连不上本机 Redis/broker），
> 它只能 `mvn -q test-compile`。因此 §13 的集成用例**必须由 Claude 在真机上执行**，
> Codex 的自报"测试通过"不能采信。

### 13.1 集成用例（`ReservationMqTest`，真 MySQL + 真 Redis + 真 broker）

| # | 用例 | 断言要点 |
| --- | --- | --- |
| **T1** | 提交成功 → 落库 → 扣名额 | 用**返回的 `reservationId` 按主键**查得到（R2）；`remaining_quota` 恰好 −1 |
| **T2** | 同一 DTO 重复投递 | 第二次返回 `DUPLICATE`；`tb_reservation` 仍只有 1 行；`remaining_quota` **只减了 1** |
| **T3** | 抢占 → `close` 批次 → 消费者仍落库 | 预约存在（§7.5 的裁定，防止"消费者复查状态"被误加进来） |
| **T4** | broker 不可用时提交 | 返回失败文案；**Redis 名额被归还**（Lua 抢占前的值）；`tb_reservation` 无行 |
| **T5** | R1 直测：`close` 后调用回滚 | 回滚返回码 = 0；`reservation:batch:quota:{id}` **仍然不存在**（没被复活） |
| **T6** | 反序列化失败入死信 | 手工投非法 body；消息出现在 `reservation.create.dlq`；`reservation.create.queue` 的 `Unacked` 归 0（消费者没卡死） |
| **T7** | 业务死信：名额冲突 | 人为把 MySQL `remaining_quota` 置 0 后投递；`QUOTA_CONFLICT`；消息进 DLQ；**MySQL 不为负** |
| **T8** | 回归：Phase 6 全绿 | 既有 76 个测试零回归 |

### 13.2 单元级（回滚脚本的返回码直测）

与 Phase 6 的 `ReservationQuotaExecutorTest` 同构：`ReservationQuotaRollbackExecutorTest`
——键存在 → 返回 1 且名额 +1；键不存在 → 返回 0 且**键仍不存在**。**确定性、不依赖时序。**

### 13.3 测试的环境前提（写进测试类注释）

1. MySQL / Redis / **RabbitMQ 三者都要在跑**（`docker start rabbitmq`）；
2. `phase7_reservation.sql` 已应用；
3. 测试会写 `tb_reservation` 并改 `tb_reservation_batch.remaining_quota`，必须有 `@AfterEach` 清理，
   且**基线要复原**（沿用 Phase 6 的教训：被中断的测试会留下 fixture，见 P2-28）；
4. `reservation.create.queue` 的消费者**只能有测试上下文这一个**——若开发进程也在 18081 上跑着，
   两个消费者会抢消息，测试将不可复现。这是"跑测试前先确认 app 没在跑"的硬前提。

---

## 14. 判别力实验（Claude 亲自跑，不派给 Codex）

沿用 Phase 5/6 的**变体实验**方法论，且必须保留"**目标测试类到底跑没跑**"的有效性闸门
（Phase 6 的教训：harness 的 bash 展开顺序 bug 让 13 个变体全部报"类没跑"却看起来像"无判别力"）。

| 变体 | 改动 | 期望 | **实测（2026-10-04）** |
| --- | --- | --- | --- |
| **F1** | 回滚脚本删掉 `EXISTS` 守卫 | T5b 变红（键被复活） | ✅ **有判别力**：恰好只有 `shouldNotRecreateQuotaKeyWhenRollbackRunsAfterClose` 变红 |
| **F2** | 扣减 SQL 删掉 `remaining_quota > 0` 守卫 | T7 变红（MySQL 变负） | ✅ **有判别力**：恰好只有 `shouldDeadLetterQuotaConflictWithoutMakingMysqlQuotaNegative` 变红 |
| **F3** | 幂等预检查改成按 `reservationId` | T2 变红 | ❌ **未变红，8 个测试全绿** → 见下方订正 F3 |
| **F4** | 删掉显式 `get(5s)` 等待 | T4 变红（回滚不再发生） | ❌ **未变红**：broker 停掉时 T4 仍通过 → 见下方订正 F4 |
| **F5** | `Reservation.id` 改成 `IdType.AUTO` | T1 变红 | ✅ **有判别力**：4 个测试变红（含 T1） |
| **F6** | 把持久化方法挪进监听器同一个类（模拟自调用） | 需要一个"INSERT 成功 / UPDATE 失败"的构造用例才可能变红；若构造不出，**如实登记为"无法用测试区分"，不硬凑** | 见下方 F6 |

每次变体跑完都用 `shasum` 校验工作区与交付版本**逐字节一致**；四次变体的复原校验均 OK。

**F6 要特别诚实**：它是本 Phase 唯一可能"测不出来"的风险。
如果构造不出判别用例，结论就是"该风险靠代码结构审查（R4）而非测试保障"，
写进 REVIEW，**不能反过来声称它被测住了**。

### 14.1 订正 F3：T2 对幂等键**没有判别力**（实测推翻原期望）

原期望写"改成按 `reservationId` → T2 变红"，**这是错的**，实测 8 个测试全绿。原因是 T2 的重复投递
用的是**同一个 DTO**（同一个 `reservationId`），按 `id` 查同样命中 `DUPLICATE`——两条实现在这个输入下
行为**完全相同**。而 §7.1 专门用一节论证必须用 `(user_id, batch_id)`，这个决策目前**零判别力**。

要建立判别力，必须补一个"**同一用户、同一批次、不同 `reservationId`**"的用例：
正解返回 `DUPLICATE`（干净 ack，**不进死信**，不重复扣名额）；`id` 版则绕过预检查 → 撞
`uk_user_batch` → `DuplicateKeyException` → 进死信。**判据是"死信队列必须为空"**。
→ 已作为返工项 R-A 交回。

### 14.2 订正 F4：broker 停掉**区分不出**显式等待（实测推翻原期望）

原期望写"删掉 `get(5s)` → T4 变红"，**这也是错的**。实测：把等待整段删掉后，broker 停止状态下的
T4 实验**依然通过**。原因是 **broker 不可用时异常由 `convertAndSend` 自己抛出**
（`AmqpConnectException`，连接被拒），代码根本走不到 `get(5s)`。

这暴露出一个更要紧的事实：**显式等待真正承重的是"已连接、但确认失败或消息不可路由"那条路径**
（broker 发出 `basic.return`，而 `CorrelationData.getReturnedMessage()` 由 IO 线程异步回填；
不 `get()` 就存在"读得比回填早"的竞态）。而这条路径**交付的测试一条都没覆盖**：

- `shouldReturnUnroutableMandatoryMessageToCorrelationData`（T4b）验证的是 **`RabbitTemplate` 的配置**，
  它自己在测试里调了 `get()`，因此**绕过了生产者的代码路径**；
- 我的 T4 实验验证的是**生产者回滚路径**，但走的是连接被拒分支。

两条合起来仍有缝：**没有任何测试会因为生产者里的 `get(5s)` 被删掉而变红** → R5 实际处于无保护状态。
→ 已作为返工项 R-C 交回（解除绑定→提交→断言 503 且名额归还 且无落库；`finally` 里重新绑定）。

### 14.3 §13.1-T4 实测结论：生产者回滚路径**确认有效**（由临时实验，非交付测试）

由于交付的 `ReservationMqTest` 无法在"broker 停止"下运行（上下文与队列清理都依赖 broker），
T4 由 Claude 写了一个**临时实验类**（`Phase7T4Experiment`，跑完即删）验证，结果：

| 断言 | 结果 |
| --- | --- |
| `submit` 抛 `ServiceUnavailableException` | ✅ |
| `reservation:batch:quota:{id}` 归还到抢占前的值 | ✅ |
| `reservation:batch:users:{id}` 中该用户标记被移除 | ✅ |
| `tb_reservation` 无行 | ✅ |
| MySQL `remaining_quota` 未被扣减 | ✅ |

**结论：本 Phase 最核心的新机制（投递失败 → Redis 回滚补偿 + 503）端到端有效。**
但必须如实记账：**它是被一次性的临时实验验证的，交付的测试套件里没有任何一条覆盖它**（见 R-C）。
另外附带确认：**broker 停止时 Spring 上下文仍能正常启动**（监听容器后台重连、不阻塞启动），
所以这条路径本身是可自动化的——R-C 之外的"真·broker 停止"用例在技术上可行，
只是需要独立的上下文与队列清理策略。

---

## 15. 验收标准（对应计划 §10）

| 计划要求 | 本设计的落实 |
| --- | --- |
| 新 Exchange/Queue/RoutingKey 正确 | §2.1 六项资源；T1 端到端跑通即证明绑定正确 |
| Producer 可以发送预约消息 | §5；T1 |
| Consumer 正确接收 | §7；T1–T3 |
| DTO 与 Entity 分离 | §3.1/§3.2：`mq.ReservationMessageDTO` vs `entity.Reservation`，两个类 |
| Manual ACK 正确 | §2.4/§7；T6/T7 断言死信路由 + unacked 归零；R6 grep 无 `requeue=true` |
| DLX/DLQ 路由正确 | §2.1；T6 两条路径（反序列化失败 / 业务死信）都进 `reservation.create.dlq` |
| 不再存在 VoucherOrder MQ 业务语义 | `grep -ri "voucher\|seckill" src/main/java/` = 0（Phase 5.0 已达成，本 Phase 复核） |
| Maven compile 成功 | JDK 8 `mvn clean test-compile` |

**本设计自加的四条**：

- **T4**：broker 不可用时不静默丢名额（§5.2 的显式等待真的承重）；
- **T5**：回滚不复活已关闭批次的名额键（§6.2，**本 Phase 最重要的一条新增不变式**）；
- **T7**：MySQL 永不超卖，固有偏差被转换成死信而非超卖（§6.3）；
- Phase 6 §16.7 的**"关闭后名额跳回去"暂态得到消解**：消费者扣减 MySQL 之后，
  `close` 让读路径回落 MySQL 时拿到的是**已扣减的**值。这条要实测（T3 的延伸断言），
  它是 Phase 7 顺带闭合的一处 Phase 6 已知边界。

---

## 16. 提交计划

单个本地提交（不 push，凭据策略不变）：

```
feat: add rabbitmq async reservation persistence
```

**提交前**：`phase7_reservation.sql` 已应用到本机库；broker 拓扑已声明；
全量套件绿；工作区基线复原（`tb_reservation` 0 行）。

---

## 17. 决策点（已由用户裁定，2026-10-04）

| 编号 | 决策 | 裁定 | 落实位置 |
| --- | --- | --- | --- |
| **D1** | `tb_reservation` **不加** `status` 列，Phase 9 再用 `phase9_*.sql` 加 | **采纳** | §4.1 DDL / §4.4 |
| **D2** | 投递失败新增 `ServiceUnavailableException` → **503**，文案"预约失败，请重试" | **采纳** | §5.2 / `WebExceptionAdvice` |
| **D3** | 清理 broker 上孤儿 `seckill.*` 资源 | **授权并已执行** | §12（清理后 exchange/binding 归零） |
| **D4** | 消费者保留 Redisson 锁 `tryLock(2, 10, SECONDS)` | **采纳** | §7.4 |

四条均由用户于 2026-10-04 明确裁定，是 §9 改动范围与 §13 测试计划的组成部分，不再是可选项。
D2 的落实边界要说清：**只新增 `ServiceUnavailableException` → 503 这一个 handler**，
不顺手重排 `WebExceptionAdvice` 里既有的异常→状态码映射——P2-25 登记的全局契约问题
（`RuntimeException` 兜底返回 200）仍留待独立处理，本 Phase 不扩大范围。

**已由本设计直接裁定、不进决策点的（供用户否决）**：命名（§2.1）、无 `requestId`/`retryCount`（§3.3）、
`direct` 而非 `topic`（§2.1）、不加名额夹取（§6.3）、消费者不复查批次状态（§7.5）、
投递逻辑留在 Service（§5.1）、持久化拆成两个 bean（§7.3）、三态结果枚举（§8）、
`equipment_id` 不加（§4.4）。

---

## 18. 已知边界（本 Phase 不解决，显式登记）

1. **confirm 超时模糊态**（§6.3）：每发生一次，Redis 名额比 MySQL 多 1，有界；后果是一名学员
   的预接受最终进死信。**不发明补偿**，交 Phase 8 对账。
2. **回滚脚本允许失败**：§5.2 的 catch 里回滚本身若也抛异常，只是 `log.error`。
   此时名额凭空消失。与参考实现 §8 同类，交 Phase 8。
3. **`close`/`cancel` 与在途消息**：§7.5 裁定为"仍落库"。若批次被 `CANCELLED`，
   学员会拿到一条指向已作废批次的预约——撤销它属 Phase 9 的取消流程。
4. **无延迟重试、无死信重放工具**：死信只能人工查看（管理台）。计划未要求。
5. **`RedisIdWorker` 的 `icr:` 键仍无 TTL**（Phase 5 §15.11 / Phase 6 §16.5 的既有 P2），
   本 Phase 复用发号器，不新增问题也不解决它。
6. **broker 单机、无镜像队列**：计划禁止引入集群，本 Phase 不做高可用。
7. **`prefetch=1` 下吞吐天然受限**：这是"慢消费者不堆积大量未确认消息"的代价，
   与参考实现 §4.5 一致，本 Phase 不调。
8. **测试假设消费者唯一**（§13.3-4）：开发进程与测试同时消费会让测试不可复现。
   这是环境前提，不是代码缺陷。
9. **`tb_voucher*` 三表仍不 DROP**（Phase 5 §15.6 的裁决）→ Phase 10。
   附带订正：§15 原写的验收口径「`grep -ri "voucher\|seckill" src/` = 0」**并不成立**——
   `src/main/resources/db/hmdp.sql`（课程原始全量 dump，本 Phase 未改动）里仍有
   `tb_voucher` / `tb_seckill_voucher` / `tb_voucher_order` 的建表语句。该判据已收窄为
   `src/main/java/`（代码层为 0，成立）。仓库里仍留着一份会建出 voucher 表的 schema dump
   是**交付质量问题**，登记到 Phase 10。
10. **P2-7**（交付报告含明文密码，已在 `433cdfb` 中）→ Phase 10 的历史改写/换密。

---

## 19. 环境前提（本次实测，2026-10-04）

| 项 | 实测状态 | 备注 |
| --- | --- | --- |
| RabbitMQ | Docker 容器 `rabbitmq`（`rabbitmq:4-management`，数据卷持久化，`unless-stopped`）；**侦察时处于停止状态，已由 Claude 启动**；孤儿 `seckill.*` 四项资源已按 D3 授权清理（§12） | 用户若不想让它常驻，Phase 7 跑测试前需 `docker start rabbitmq` |
| MySQL | `127.0.0.1:3306/hmdp` 在跑；`tb_reservation` **不存在**（待 §4.1 应用）；基线 `tb_reservation_batch`=0 / `tb_equipment`=12 / `tb_laboratory`=4 / `tb_user`=1013 | 与 Phase 6 收尾基线一致 |
| Redis | 在跑；`reservation:*` 无残留 | 与 Phase 6 收尾一致 |
| 端口 | 8081/8082 被别的项目占用 → 验证用 18081；`curl` 一律 `--noproxy '*'` | 既有 P2 |
| JDK | `export JAVA_HOME=…/corretto-1.8.0_504/Contents/Home` 后才能 `mvn` | 既有约束 |
