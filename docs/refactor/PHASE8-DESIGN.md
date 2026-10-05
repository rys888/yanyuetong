# Phase 8 Design — Consistency & Reliability 一致性与可靠性

> 依据：`实验资源预约平台-分阶段重构Phase计划.md` §11（8.1–8.9、必测异常 11 条、验收标准）
> 依据：`docs/refactor/PHASE7-DESIGN.md`（§17 决策点 / §18 已知边界）、`PHASE7-REVIEW.md`（§11 已知局限）
> 状态：Claude 阶段一~四产出，**待用户批准后**进入阶段五（Codex 实现）

---

## 0. 一页纸结论

Phase 8 的新增工作量**不是** 8.1–8.9 全部。Phase 7 已把 **8.1 / 8.2 / 8.3 / 8.4** 做掉（本 Phase 只做复核），
真正的缺口是 **8.5 + 8.6 + 8.7 + 8.8 + 8.9**，而且它们**是同一件事的五个面**，不能拆成五个独立补丁：

> 要能区分「消息**确定**没进 broker」与「**不知道**进没进」，就必须有**持久化的请求状态**；
> 要能事后对账 / 重放，补偿动作就必须**幂等**。

因此本 Phase 的骨架是一条链：

```
            请求状态账本 tb_reservation_request（新表，MySQL）
                     │
   ┌─────────────────┼──────────────────────────────────┐
   │ 8.6 五态状态机   │ 8.5 确定未投递→立即补偿·不确定→UNKNOWN 挂起
   │ 8.7 补偿 Lua 幂等（SREM 返回值为令牌）
   │ 8.9 对账：扫非终态 → 以 tb_reservation 存在性为事实来源 → 自愈
   └─ 8.8 DLQ 台账 tb_reservation_dlq + 管理 API + 重放前幂等校验
```

**Phase 7 当前实现正好是计划 §8.5 明令禁止的行为**（confirm 5s 超时 → 立即回滚 Redis 名额，
见 `ReservationServiceImpl:110-131`）——这是本 Phase 要拆掉的第一块。

---

## 1. 需求分析（阶段一）

### 1.1 Goal

把「能跑的预约」升级为「异常路径下仍能保证最终正确性」的预约系统，并给出可治理、可对账的运维入口。

### 1.2 Scope（本 Phase 做）

| # | 计划条目 | 内容 |
| --- | --- | --- |
| S1 | §8.5 | 区分 Confirm **NACK / Return / 连接失败**（确定未投递，可立即补偿）与 Confirm **Timeout**（UNKNOWN，**禁止**立即回滚） |
| S2 | §8.6 | 请求状态账本 + 五态状态机，持久化（MySQL），跨进程重启存活 |
| S3 | §8.7 | `reservationRollback.lua` 幂等化：同一 requestId **最多归还一次**名额 |
| S4 | §8.9 | 轻量对账：扫描长期非终态 → 查 `tb_reservation` → 修正 `CONFIRMED` 或幂等补偿 |
| S5 | §8.8 | DLQ 台账 + 管理 API（查询 / 详情 / 重放）+ 重放前幂等校验 + 重放次数限制 |
| S6 | §8.1–8.4 | **复核**（非新增）：唯一索引、事务、消费幂等、Redisson 定位 |
| S7 | §8.5 附带 | 消费者落库前读账本，若已 `COMPENSATED` 则拒绝落库 —— 闭合「对账误判 ⇒ 超发」 |
| S8 | Phase 7 §18.2 | 回滚脚本**自身失败**时不再"名额凭空消失"：不置 `COMPENSATED`，留给对账重试 |

### 1.3 Non-goals（本 Phase 明确不做）

| 不做 | 理由 |
| --- | --- |
| 取消 / 履约 / 爽约 | Phase 9 |
| `uk_user_batch` 迁移为「生成列 + 唯一键」以支持取消后重约 | 取消语义未定 ⇒ 索引形态无法定。**留给 Phase 9**（见 §6.1） |
| P2-18 设备缓存删除失败的补偿 | 属 Phase 4 缓存域，**不是计划 §11 的条目**；已由 30min TTL + 兜底直查缓解，是"惊群风险"而非"数据不一致"（见 §14 待裁定） |
| Seata / 任何分布式事务框架 | 计划 §8.9 明令不引入 |
| 状态账本参与跨系统事务 | 禁止长事务持有连接（见 §4.3） |
| 改动 Phase 4/5/6/7 的既有对外契约 | 唯一例外见 §7.2：一处**测试期望值因语义精化而变**，必须显式留痕 |
| `RedisIdWorker` 的 `icr:` 键 TTL、broker 高可用、`prefetch` 调整 | Phase 7 §18.5–18.7 已登记，本 Phase 不扩大范围 |

### 1.4 计划 §11「必须重点测试的异常」11 条 → 归属

| # | 异常 | 归属 |
| --- | --- | --- |
| 1 | MQ 重复投递 | Phase 7 已覆盖 → 本 Phase 回归 |
| 2 | Consumer 重复消费 | 同上 |
| 3 | Consumer 成功但 ACK 丢失 | Phase 7 已覆盖（幂等 → ACK 而非 DLQ）→ 本 Phase 回归 |
| 4 | MySQL 条件扣减失败 | Phase 7 已覆盖（`QUOTA_CONFLICT` → DLQ）→ 本 Phase 补 `fail_reason` |
| 5 | INSERT 失败 | **新增**（证明事务真的回滚，I4） |
| 6 | Confirm NACK | **新增**（→ 立即补偿 + `COMPENSATED`） |
| 7 | Return | Phase 7 已覆盖发送侧 → 本 Phase 补状态落点 |
| 8 | Confirm Timeout | **新增（核心）**（→ `UNKNOWN`，**不回滚**） |
| 9 | Redis 补偿重复调用 | **新增（核心）**（幂等 Lua，I1/I2） |
| 10 | DLQ 重放 | **新增** |
| 11 | 服务重启后的未完成请求 | **新增**（对账两分支） |

---

## 2. 源码分析（阶段二）— Phase 7 交付的现状盘点

已逐文件通读：`ReservationServiceImpl`、`ReservationMessageConsumer`、
`ReservationPersistenceServiceImpl`、`ReservationQuotaExecutor`、`ReservationQuotaRollbackExecutor`、
`reservation.lua`、`reservationRollback.lua`、`RabbitMQConfig`、`phase7_reservation.sql`、
`application.yaml`（rabbit 段）、`RedisConstants`。

### 2.1 计划 8.1–8.4 的复核结论

| 条目 | 现状 | 结论 |
| --- | --- | --- |
| 8.1 Unique Index | `tb_reservation`：`PRIMARY KEY(id)` + `UNIQUE KEY uk_user_batch(user_id,batch_id)`；**无 `request_id` 列** | ✅ 满足。Phase 6 决策 D3 把 `requestId` 与 `reservationId` 合一，故 `PRIMARY KEY(id)` **就是** requestId 唯一索引。**保留 `uk_user_batch`**（8.3 的最终防线） |
| 8.2 Transaction | `persist` 已标 `@Transactional`；UPDATE-then-INSERT | ✅ 满足，但**必须用实验证明注解真的生效**（R6），不接受"读一眼注解就算过" |
| 8.3 Consumer 幂等 | 先 `selectCount(userId,batchId)` 预检查 → 条件扣减 → INSERT，`uk_user_batch` 兜底 | ✅ 满足。**但有一处真缺口**：撞 `uk_user_batch` 的兜底路径会抛异常 → nack → **DLQ**，而它其实是"已成功过的重复消息"，**本应 ACK**（I9） |
| 8.4 Redisson 定位 | `lock:reservation:{userId}`，`tryLock(2,10,SECONDS)`；文档已明确「降低竞争 ≠ 正确性防线」 | ✅ 满足，口径正确，本 Phase 不改 |

### 2.2 四处确认的缺口（本 Phase 的靶子）

**缺口 A —— Confirm 超时被当作"确定失败"（计划 §8.5 明令禁止 / 登记项 P1-3）**

`ReservationServiceImpl` 第 110–131 行：

```java
CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);
if (!confirm.isAck()) { throw new AmqpException("预约消息未获得 RabbitMQ 确认：" + confirm.getReason()); }
if (correlationData.getReturnedMessage() != null) { throw new AmqpException("预约消息无法路由到队列"); }
} catch (Exception e) {
    reservationQuotaRollbackExecutor.rollback(batchId, userId);   // ← 超时也走到这里
    throw new ServiceUnavailableException("预约失败，请重试");
}
```

`get(5s)` 抛 `TimeoutException` 时**无从判断消息是否已进 broker**，却执行了回滚。
若消息其实已投递并被消费 → 「Redis 名额已归还 + MySQL 已有预约行」的**反向漂移**
（连带的次生后果：用户看到失败后可能重复提交，此时 Redis 名额虚高会放行，`uk_user_batch` 才拦住）。

**缺口 B —— 补偿不幂等（登记项 P1-4）**

`reservationRollback.lua`：

```lua
if redis.call('EXISTS', KEYS[1]) == 0 then redis.call('SREM', KEYS[2], ARGV[1]); return 0 end
redis.call('INCR', KEYS[1])
redis.call('SREM', KEYS[2], ARGV[1])
return 1
```

名额键存在时**连调两次就 `+2`** → 超发。而且它**不看 `SREM` 的返回值**：
对**从未占用名额**的用户回滚，只要键在就凭空 `+1`。

**缺口 C —— 无状态账本，因而无法对账、无法治理（P1-6）**

- 没有任何"请求状态"的持久化记录 → 无法区分「还在排队 / 已落库 / 已丢失」；
- 因此计划 §8.9 的「扫描长期 `UNKNOWN` / `PRE_ACCEPTED`」**无对象可扫**；
- DLQ 只有一个裸队列，无台账、无失败原因、无重放入口。

**缺口 D —— 回滚自身失败 ⇒ 名额凭空消失（Phase 7 §18.2 已登记）**

`publishReservationMessage` 的 catch 里，回滚本身若也抛异常，当前只是 `log.error`，
**名额永久少一个且无人知晓**。Phase 7 明确"不发明补偿，交 Phase 8"。

### 2.3 一个被低估的可靠性红利

`requestId` 与 `reservationId` 合一（Phase 6 D3）在 Phase 8 产生直接好处：
「按 requestId 查是否已落库」= `reservationMapper.selectById(requestId)`，**走主键，零额外索引成本**。
对账的核心动作因此是 O(1) 点查，而不是范围扫描。

---

## 3. 技术设计（阶段三）

### 3.1 决策总表

| # | 决策 | 备选与否决理由 |
| --- | --- | --- |
| **D1** | 请求状态账本落 **MySQL** 新表 `tb_reservation_request` | 否决 Redis 方案：Redis 是"可失的数据"，把它当一致性账本违反 §8.4 的定位论；且必测异常 #11 要求状态**跨进程重启存活** |
| **D2** | 五态取计划原文命名 `PRE_ACCEPTED / PUBLISHED / CONFIRMED / UNKNOWN / COMPENSATED`；终态为 `CONFIRMED` 与 `COMPENSATED` | 不简化。§8.6 允许简化，但简化会丢掉"确定未投递 vs 不确定"的区分——而那正是 §8.5 的全部内容 |
| **D3** | **先写账本，后扣名额** | 反过来会产生"扣了名额但没有任何记录"的**孤儿名额**（静默泄漏、无人可对账）。正序时多写的那条会被幂等补偿安全地"补偿成无操作"——这恰是 D4 的价值所在 |
| **D4** | 补偿 Lua 以 **`SREM` 的返回值**为幂等令牌：`SREM==1` 才 `INCR` | `SREM` 返回 1 ⟺ 该用户此前确实占着名额（`reservation.lua` 的 `SADD` 与 `DECR` 在同一次原子调用内），是**服务端可自证的不变式**，无需任何外部状态。天然幂等，且顺带堵住"对从未占用的用户凭空 +1" |
| **D5** | 对账以 **`tb_reservation` 中该 id 是否存在**为唯一事实来源 | 不引入额外对账水位/版本号。申请表是"意图"，预约表是"事实" |
| **D6** | 对账用 **Spring `@Scheduled`** 薄壳调用可手动直调的纯方法 | 计划要求"轻量、不引入 XXL-JOB"。薄壳定时 + 可直调方法，使测试不依赖时钟、不用 `sleep` |
| **D7** | DLQ 消费者用**原始 `org.springframework.amqp.core.Message`** 接收，**不用** `@Payload` + Jackson | DLQ 里躺着的第一类消息就是**反序列化失败的消息**（Phase 7 已有该用例，R3）。用转换器接收会二次失败 → 死循环 |
| **D8** | 消费者失败时把 `fail_reason` 写进**请求状态账本**，**不改变** nack + DLX 的投递机制 | nack→DLX 是 broker 保证的原子动作；改成"主动 publish 到 DLX + ack"会引入"ack 了但死信没发出去"的**新丢失窗口**。复用 D1 的表拿精确原因，零新增风险 |
| **D9** | 保留 `uk_user_batch` **不动** | 它是 §8.3 的最终防线。取消后重约的索引迁移留给 Phase 9（§6.1）；Phase 8 提前改会让"重复预约被唯一键挡住"**无法验证** |
| **D10** | 任一状态写入失败**不得**导致已落库的预约回滚 | 账本是**辅助**，`tb_reservation` 才是事实。状态更新失败只记日志，由对账自愈；反之会把"预约其实成功"变成"进了 DLQ" |

### 3.2 状态机（§8.6）

```
   INSERT 账本 ──► PRE_ACCEPTED ──┬── Lua 未抢到名额 ──► COMPENSATED(fail_reason=拒绝码)
                                  │
   confirm ACK 且无 return ───────┴──► PUBLISHED
   confirm NACK / Return / 连接失败 ──► COMPENSATED（**立即**幂等归还名额）
   confirm Timeout / 其它异常 ────────► UNKNOWN（**不归还**）
                                  │
   PUBLISHED ── 消费者落库成功(PERSISTED/DUPLICATE) ──► CONFIRMED
   PUBLISHED ── 消费者失败(nack) ──► 留在 PUBLISHED + fail_reason
                                  │
   ┌──────────── 对账（扫非终态且超时）────────────┐
   │  tb_reservation 有该 id ──► CAS 改 CONFIRMED（名额不动）  │
   │  tb_reservation 无该 id ──► CAS 改 COMPENSATED + 幂等归还 │
   └───────────────────────────────────────────────┘
```

终态：`CONFIRMED`（预约成立）、`COMPENSATED`（请求终结，名额已归还或从未占用）。

### 3.3 异常分类：什么算"确定未投递"（§8.5 的可执行定义）

§8.5 只说"Confirm Timeout 视为 UNKNOWN"，但**"什么算确定失败"必须自己定义**，
否则实现时会退化成"除了超时全都回滚"，把反向漂移留在别处。本设计的定义：

| 信号 | 判定 | 依据 | 动作 |
| --- | --- | --- | --- |
| `confirm.isAck() == false`（NACK） | **确定未投递** | broker 明确否认 | 回滚 + `COMPENSATED`，抛 503「预约失败，请重试」 |
| `correlationData.getReturnedMessage() != null`（Return） | **确定未投递** | broker 明确退回（`mandatory=true`） | 同上 |
| `AmqpConnectException`（发送前连不上） | **确定未投递** | 消息**未曾上线** | 同上 |
| `TimeoutException`（`get(5s)` 超时） | **UNKNOWN** | 无从判断 | **不回滚**，置 `UNKNOWN`，抛 503「预约结果待确认，请稍后查询」 |
| **其它任何异常** | **UNKNOWN**（保守） | 无法证明"没上线" | 同上 |

关键取舍：**默认方向选"保守"**。把不确定的异常误判成"确定失败"会立刻造成反向漂移；
误判成 `UNKNOWN` 只是交给对账，而对账会查 `tb_reservation` 得出正确结论。**代价不对称，所以向安全侧倒。**

### 3.4 写入点与失败处理（逐点）

| 步 | 动作 | 失败处理 |
| --- | --- | --- |
| 1 | `reservationId = idWorker.nextId` | — |
| 2 | INSERT 账本 `PRE_ACCEPTED` | 抛 503。**此时未扣名额、未发消息**，零副作用——这是 D3 选此顺序的原因 |
| 3 | Lua 抢名额 | 非 SUCCESS → 账本 `COMPENSATED` + `fail_reason=拒绝码` → 抛 400（对外语义与 Phase 6 一致） |
| 4a | ACK 且无 return → 账本 `PUBLISHED` | 更新失败：**重试一次**，仍失败只记日志。对账会扫 `PRE_ACCEPTED` 自愈 |
| 4b | 确定未投递 → **先** rollback，**再** CAS 到 `COMPENSATED` | **回滚抛异常时不得置 `COMPENSATED`**（否则名额没还却标记为已还）→ 留在 `PRE_ACCEPTED`，由对账重试。对账的补偿是幂等的，重试安全（闭合 Phase 7 §18.2） |
| 4c | `UNKNOWN` → 置 `UNKNOWN`，**不 rollback** | 抛 503「预约结果待确认，请稍后查询」 |
| 5 | 消费者落库成功 → 账本 `CONFIRMED` | **只记日志，不抛错**（D10） |
| 6 | 消费者落库**前**读账本，若 `COMPENSATED` → 拒绝落库 + ACK | 闭合"对账误判 ⇒ 超发"（§3.6，I10） |

### 3.5 对账（§8.9）

- **扫描口径**：`status NOT IN ('CONFIRMED','COMPENSATED') AND update_time < now - threshold`
  —— 扫**所有**非终态（含 `PUBLISHED`）。因为步 5 的 `CONFIRMED` 更新本身可能失败，
  只扫 `UNKNOWN/PRE_ACCEPTED` 会让这类行**永久卡住**。
- **阈值**：默认 10 分钟（可配）。必须远大于"最坏排队 + 重试"时间，否则会把**还在排队**的请求误判为丢失。
- **单轮上限**：默认 200 条，按 `update_time` 升序，避免一次拉爆。
- **每条独立事务**：单条失败只记日志，下一轮重试，互不影响。
- **并发安全（双保险）**：
  1. 状态 **CAS**：`UPDATE ... SET status=? WHERE request_id=? AND status IN (非终态)`，CAS 失败即放弃；
  2. 补偿动作**本身幂等**（D4）。
  即使多实例同时跑对账也不会重复补偿。
- **多实例互斥**：Redisson 锁 `lock:reservation:reconcile`——**尽力而为，正确性不依赖它**（靠上面双保险）。

### 3.6 唯一的残余竞态与其收口（诚实登记）

对账在 `tb_reservation` 无行时归还名额，但消息可能**仍在队列里排队**（滞留超过阈值）。
若之后消费者落库 → 「MySQL 有行 + Redis 名额已归还」= **超发**。

**收口**：消费者落库**前**读账本，若已是 `COMPENSATED` 则**拒绝落库并 ACK**（步 6）。
剩余窗口是"消费者读完账本 → 对账改状态 → 消费者落库"之间的**毫秒级**竞态。

**权衡**：彻底消除需要分布式事务或"补偿性回滚"（发现被对账抢先后再删库、再扣回名额），
复杂度与收益不成比例，且违反 §8.9「不引入大型框架」。因此：
用**足够大的阈值**把概率压到实际不可达，把剩余窗口登记为已知局限，并给出可达性数字：
`prefetch=1` 单消费者下，消息滞留 > 10 分钟意味着队列积压**数千条**。

---

## 4. 数据结构设计

### 4.1 `tb_reservation_request`（新）— 幂等 DDL

```sql
CREATE TABLE IF NOT EXISTS `tb_reservation_request` (
  `request_id`  bigint unsigned NOT NULL COMMENT '请求id（RedisIdWorker，与预约id同源）',
  `user_id`     bigint unsigned NOT NULL COMMENT '用户id',
  `batch_id`    bigint unsigned NOT NULL COMMENT '预约批次id',
  `status`      varchar(16) NOT NULL COMMENT 'PRE_ACCEPTED/PUBLISHED/CONFIRMED/UNKNOWN/COMPENSATED',
  `fail_reason` varchar(255) DEFAULT NULL COMMENT '失败原因（DLQ 治理用）',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`request_id`) USING BTREE,
  KEY `idx_status_update_time` (`status`, `update_time`) USING BTREE,
  KEY `idx_user_batch` (`user_id`, `batch_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='预约请求状态账本';
```

`idx_status_update_time` 直接服务对账的扫描谓词（`status` 等值 + `update_time` 范围）。

### 4.2 `tb_reservation_dlq`（新）— 幂等 DDL

```sql
CREATE TABLE IF NOT EXISTS `tb_reservation_dlq` (
  `id`             bigint unsigned NOT NULL AUTO_INCREMENT,
  `reservation_id` bigint unsigned DEFAULT NULL COMMENT '解析出的预约id，解析失败为 NULL',
  `payload`        text COMMENT '死信消息体原文',
  `failure_reason` varchar(512) DEFAULT NULL COMMENT '失败原因（账本 JOIN 或 x-death）',
  `replay_count`   int NOT NULL DEFAULT 0 COMMENT '已重放次数',
  `status`         varchar(16) NOT NULL DEFAULT 'NEW' COMMENT 'NEW/REPLAYED/DISCARDED',
  `create_time`    timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`    timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_reservation_id` (`reservation_id`),
  KEY `idx_status_create_time` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='预约死信台账';
```

### 4.3 事务边界（明确声明，供 Review 核验）

- `submit` 路径**不加** `@Transactional`：跨 Redis / MySQL / MQ 三类资源，且在等待 confirm 的 5s 内持有事务会长时间占用连接。**用状态机 + 对账达成最终一致**——这正是 §8.9 的路线。
- `persist` 保持 `@Transactional`（条件扣减 + INSERT 原子）。
- 账本的每次状态更新是**独立**的一次写，**不与 `persist` 同事务**（D10）。
- 对账**每条请求独立事务**。
- DDL 用 `CREATE TABLE IF NOT EXISTS`（沿用 Phase 3/5/7 的幂等口径，重复执行无报错）。

---

## 5. 不变式（供测试逐条判别）

| # | 不变式 | 判别方式 |
| --- | --- | --- |
| I1 | 同一 `requestId` 的补偿**最多归还一个名额** | 连调 `rollback` N 次，断言名额只 +1 |
| I2 | 对**从未占用名额**的用户补偿，名额**不变** | 构造"账本有记录但未扣名额"，补偿后断言名额不变 |
| I3 | 补偿**不得复活**已关闭/取消批次的名额键 | Phase 7 已有该守卫，保留并回归 |
| I4 | `tb_reservation` 中**永不存在**"未扣名额"的行 | 条件扣减失败 → 事务回滚 → 无行 |
| I5 | `tb_reservation.remaining_quota` **永不为负** | 并发下断言 `>= 0` |
| I6 | Confirm **Timeout 不归还**名额 | 制造超时，断言 Redis 名额**未变**且账本为 `UNKNOWN` |
| I7 | Confirm **NACK / Return 立即归还**名额 | 移除 binding，断言名额回到初值且账本为 `COMPENSATED` |
| I8 | 对账**不重复补偿**（同一 request 多轮对账只归还一次） | 同一 fixture 连跑两轮对账，断言名额只 +1 |
| I9 | **已落库的重复消息 ACK 而非进 DLQ** | 重复投递后断言 DLQ 为空（Phase 7 已覆盖，本 Phase 回归） |
| I10 | 已被补偿的 request，其消息**不落库** | 手工造 `COMPENSATED` 账本 + 投递消息，断言无预约行且 ACK |
| I11 | DLQ 重放**不产生重复预约** | 重放一条已落库的死信，断言仍只有一行 |
| I12 | 回滚失败时账本**不置** `COMPENSATED` | 让 Lua 抛错，断言状态仍为非终态且对账能补救 |

---

## 6. 接口设计

### 6.1 索引决策（计划 §8.1 要求"必须评估，不能机械添加"）

**结论：Phase 8 保留 `uk_user_batch` 不变。**

- 它是"同一用户对同一批次不得重复预约"的**最终防线**（§8.3），I9 / I11 都依赖它。
- **但它使 Phase 9 的"取消后重新预约"不可行**：取消后行仍在，唯一键仍被占。
- **Phase 9 的迁移路径（本 Phase 只登记、不实施）**：把 `uk_user_batch` 迁移为
  `UNIQUE(user_id, batch_id, active_flag)`，其中 `active_flag` 是**生成列**
  （`CANCELLED` 时取 `id`，否则取 `0`）。这样同一 `(user,batch)` 同时最多一条"活跃"行，
  多条已取消行互不冲突。MySQL 5.7+/8 支持生成列 + 唯一索引，无需触发器。
- **不选**"取消即物理删除行"：会丢失履约审计轨迹（Phase 9 的爽约统计需要它）。

### 6.2 DLQ 治理 API（计划 §8.8：Service + Admin API 即可）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/reservation-dlq/page?status=NEW&page=&size=` | 分页查询死信台账，默认 `create_time DESC` |
| GET | `/reservation-dlq/{id}` | 单条详情（含完整 payload 与 `failure_reason`） |
| POST | `/reservation-dlq/{id}/replay` | 人工重放 |

三个端点全部标 `@RequireRole(LAB_ADMIN)`（沿用 Phase 5.1 的 `RoleInterceptor` 边缘守卫）。

**重放前置校验（逐条，任一不满足即拒绝并说明原因）**：

1. 台账 `status == NEW`（已重放 / 已丢弃的不再重放）；
2. `replay_count < MAX_REPLAY(3)` —— **重放次数限制**；
3. payload 能解析出 `reservationId`；解析失败 → 标 `DISCARDED`，说明"无法重放"；
4. **幂等校验**：`tb_reservation` 已有该 id → 其实已成功落库 → 标 `DISCARDED`，返回"已落库，无需重放"；
5. 账本 `status == COMPENSATED` → 名额已归还 → 标 `DISCARDED`（重放会造成超发）；
6. 重新投递到 `reservation.create.queue`（**带 confirm 校验**，与生产者同款）；
7. 成功 → `replay_count + 1`，`status = REPLAYED`。

### 6.3 对账（计划未要求对外 API）

- `ReservationReconcileService.reconcile(int limit)` —— 纯方法，可手动 / 测试直调，返回本轮处理摘要；
- `@Scheduled` 薄壳按 `hmdp.reservation.reconcile-interval-ms`（默认 `300000`）触发；
- 可配项：`hmdp.reservation.reconcile-threshold-ms`（默认 `600000`）、`hmdp.reservation.reconcile-limit`（默认 `200`）、`hmdp.reservation.dlq-max-replay`（默认 `3`）。

**测试可控性**：间隔默认 5 分钟，短测试内不会自动触发；测试一律直调 `reconcile()`，
不依赖时钟，也不 `Thread.sleep`。

---

## 7. 兼容性与已知影响

### 7.1 对外行为变化

| 变化 | 说明 |
| --- | --- |
| `POST /reservation` 的 503 文案**分两种** | 确定未投递 →「预约失败，请重试」（**与 Phase 7 §17-D2 裁定一致，不变**）；`UNKNOWN` →「预约结果待确认，请稍后查询」（新） |
| `submit` 多一次 MySQL 写 | 账本 INSERT。写放大增加，换来可对账。如实记账 |
| 新增 3 个管理端点 | 新增面，不破坏既有面 |
| `ReservationQuotaRollbackExecutor.rollback` 返回码 `0/1` → `0/1/2` | 内部契约，见 §7.2 |

### 7.2 必须同步修改的既有测试期望（语义精化，**不是**为过而过）

`ReservationMqTest.shouldNotRecreateQuotaKeyWhenRollbackRunsAfterClose` 当前断言 `rollback == 0`。
在 D4 的新返回码下，该场景（用户标记存在、名额键缺失）返回 **`2`**。

**这不是 bug，而是把原先混在同一个 `0` 里的两种情况分开**：

- `0` = 该用户**本来就没占**名额（无需归还）；
- `2` = 确实占了，但名额键已不存在（批次已 `CLOSE`/`CANCEL`，**不能**归还）。

语义更精确，且正是 I1/I2 可判别的前提。此修改**必须显式留痕**，
以免在 Review 时被误判为"改期望值让测试变绿"。

---

## 8. 高危风险点（Review 必须逐条实测）

| # | 风险 | 为什么高危 | 验证方式 |
| --- | --- | --- | --- |
| **R1** | 对账阈值过小 / 扫到还在排队的消息 → 误判为丢失 → **超发** | 直接破坏 I5，且是"静默"的 | 判别实验 F4（阈值设为 0 时 I8 必须变红）；实跑断言默认阈值下不误判 |
| **R2** | 补偿 Lua 又写成"看 `EXISTS` 不看 `SREM`" | 回到不幂等，连调即超发 | 判别实验 F1：改回旧写法，I1/I2 必须变红 |
| **R3** | `TimeoutException` 仍走回滚分支 | **§8.5 的核心要求未达成**，缺口 A 原样保留 | 判别实验 F2：把 `TimeoutException` 并入回滚分支，I6 必须变红 |
| **R4** | 账本状态更新与 `persist` 放进**同一个**事务 | 状态更新失败会连带回滚已成功的预约 → 反向漂移 | 读代码：两步分属不同事务；单测模拟状态更新失败后断言预约行**仍在** |
| **R5** | 给 `submit` 加了 `@Transactional` | 等待 confirm 的 5s 内持有数据库连接 | 全仓 grep：`submit` 及其调用链上无 `@Transactional` |
| **R6** | `@Transactional` 因**自调用**静默失效 | I4 退化为恒真空断言 | 判别实验 F6：把 `persist` 逻辑内联进调用方，I4 必须变红；并确认两个 bean |
| **R7** | 消费者未做 `COMPENSATED` 前置检查 | §3.6 的超发收口失效 | 判别实验 F3：删掉检查，I10 必须变红 |
| **R8** | 对账无 CAS，或补偿动作非幂等 | 多实例/多轮对账重复补偿 | 判别实验 F5：去掉 CAS，I8 必须变红 |
| **R9** | DLQ 消费者用了 `@Payload` + Jackson | 反序列化失败的消息二次失败 → **死循环** | 直读代码：参数类型必须是 `org.springframework.amqp.core.Message`；投递一条坏 JSON 进 DLQ 断言台账有行且队列清空 |
| **R10** | DLQ 重放未做幂等校验 | 重复预约 | 判别实验 F7：删掉校验，I11 必须变红 |
| **R11** | DLQ 消费者用 `requeue=true` | 毒消息热循环打死消费者 | 全仓 grep `basicNack`/`basicReject` 第三参数 |

> **勘误（2026-10-05，阶段六/七 Review 追加）**
>
> - **R1 的预期用例名有误**：上表 R1 写"判别实验 F4（阈值设为 0 时 **I8** 必须变红）"，指向错误。
>   F4 实测打红的是 `ReservationIgnoreFreshNonTerminalRequest` 一类用例（**新鲜非终态被误扫**），
>   并非 I8 的"多轮对账重复补偿"用例。F4 的判别力成立，改的是**指向**而非结论。
> - **R10 指定的判别实验 F7 从未执行**：台账的变体表只有 F1–F6、F8/F8b/F8c、F9。
>   即 I11（DLQ 重放不产生重复预约）目前**只有读码 + 用例覆盖**，属"有覆盖、未验证判别力"，
>   不得当作已由变体证明。见 `PHASE8-REVIEW.md` §2 / §9。
> - **R4/R5/R6/R9/R11 为读码或 grep 级证据**，强度低于变体实验，裁定见 `PHASE8-REVIEW.md` §2。

---

## 9. 测试计划

### 9.1 集成用例（真 MySQL + 真 Redis + 真 broker）

| 类 | 覆盖 |
| --- | --- |
| `ReservationMqTest`（扩） | 步 4b 的 `COMPENSATED` 落点、`fail_reason`、I9 回归 |
| `ReservationUnknownStateTest`（新） | **I6**：用 `@SpyBean RabbitTemplate` 把 `convertAndSend` stub 成空操作 ⇒ `get(5s)` 真实超时 5s ⇒ 断言账本 `UNKNOWN`、Redis 名额**未变**、503 文案正确（**忠实模拟，非造异常**） |
| `ReservationReconcileTest`（新） | **I8 / 必测 #11**：① 账本 `UNKNOWN` + 预约行存在 → 修正 `CONFIRMED`；② `UNKNOWN` + 无预约行 → 置 `COMPENSATED` 且名额归还；③ 连跑两轮，断言只归还一次；④ 四种非终态各一条，断言全部被扫到 |
| `ReservationDlqGovernanceTest`（新） | 台账落库、`failure_reason` JOIN、**I11** 重放幂等、次数上限、`COMPENSATED` 拒绝重放 |
| `ReservationRequestStateTest`（新） | 账本 Service 的 CAS 语义、终态不可回退 |
| `ReservationPersistenceTest`（扩） | **I4 / I5 / 必测 #5**：条件扣减失败、INSERT 失败 → 断言无行、无负库存 |

### 9.2 单元级

- `reservationRollback.lua` 返回码 `0/1/2` 直测（含 I1/I2/I3）；
- 异常分类函数 `classify(Throwable)` 的纯单测（§3.3 五行的每一行）。

### 9.3 环境前提（写进测试类注释）

沿用 Phase 7 §13.3：需真实 MySQL / Redis / RabbitMQ，且**必须先应用
`phase7_reservation.sql` 与 Phase 8 的新 DDL**；运行前必须关闭其它消费
`reservation.create.queue` / `reservation.create.dlq` 的应用实例；测试结束必须复原拓扑与清空队列。
`@AfterEach` 必须清理 `tb_reservation_request` 与 `tb_reservation_dlq`（Phase 7 的孤儿行教训）。

---

## 10. 判别力实验计划（Claude 亲自跑，不派给 Codex）

| # | 变体 | 期望变红 |
| --- | --- | --- |
| F1 | 补偿 Lua 改回"只看 `EXISTS`"（旧写法） | I1 / I2 |
| F2 | 把 `TimeoutException` 并入回滚分支 | I6 |
| F3 | 删掉消费者的 `COMPENSATED` 前置检查 | I10 |
| F4 | 对账阈值设为 0（把排队中的消息当丢失） | I8 或超发断言 |
| F5 | 去掉对账的状态 CAS | I8 |
| F6 | 把 `persist` 内联进调用方（制造自调用，`@Transactional` 失效） | I4 |
| F7 | 去掉重放前的幂等校验 | I11 |

判据沿用 Phase 5–7 的口径：**变体必须能编译且测试类真的跑起来**（`Tests run: 0` 或
`COMPILATION ERROR` 一律判为"变体无效，不产生结论"），收尾必须与交付版本逐字节一致。

---

## 11. 改动范围与禁止清单

### 11.1 新增文件（预计）

```
src/main/resources/db/phase8_consistency.sql          新表 × 2
src/main/java/com/hmdp/entity/ReservationRequest.java
src/main/java/com/hmdp/entity/ReservationDlq.java       （原写 ReservationDlqRecord，T6 实际交付为 ReservationDlq）
src/main/java/com/hmdp/enums/ReservationRequestStatus.java
src/main/java/com/hmdp/enums/ReservationDlqStatus.java  （T6 补入）
src/main/java/com/hmdp/config/ReservationDlqListenerConfig.java （T6-R 补入，见 D18）
src/main/java/com/hmdp/mapper/ReservationRequestMapper.java
src/main/java/com/hmdp/mapper/ReservationDlqMapper.java
src/main/java/com/hmdp/service/IReservationRequestService.java (+Impl)
src/main/java/com/hmdp/service/IReservationReconcileService.java (+Impl)
src/main/java/com/hmdp/service/IReservationDlqService.java (+Impl)
src/main/java/com/hmdp/mq/ReservationDlqConsumer.java
src/main/java/com/hmdp/controller/ReservationDlqController.java
src/main/java/com/hmdp/config/SchedulingConfig.java      @EnableScheduling
src/main/java/com/hmdp/dto/ReservationDlqQueryDTO.java   （T7 补入）
src/main/java/com/hmdp/vo/ReservationDlqVO.java          （T7 补入）
src/main/java/com/hmdp/vo/ReservationDlqReplayVO.java    （T7 补入）
src/main/java/com/hmdp/dto/ReconcileSummaryDTO.java      （T5 补入，见 D16）
```

### 11.2 修改文件（预计）

```
src/main/resources/reservationRollback.lua              D4 幂等化
src/main/java/com/hmdp/utils/ReservationQuotaRollbackExecutor.java   返回码 0/1/2
src/main/java/com/hmdp/service/impl/ReservationServiceImpl.java      §3.4 步 2→4 重排 + 异常分类
src/main/java/com/hmdp/mq/ReservationMessageConsumer.java            步 6 前置检查 + fail_reason
src/main/java/com/hmdp/config/RabbitMQConfig.java                    DLQ 消费者容器按需
src/main/java/com/hmdp/utils/RedisConstants.java                     LOCK_RESERVATION_RECONCILE_KEY（T5 补入）
src/main/resources/application.yaml                                  reconcile 配置项 + dlq-max-replay（T7 补入）
src/test/java/com/hmdp/ReservationMqTest.java                        §7.2 期望值 + I9 回归
src/main/java/com/hmdp/service/IReservationDlqService.java           T7 增量：queryPage/queryById/replay（T6 既有两法未动）
src/main/java/com/hmdp/service/impl/ReservationDlqServiceImpl.java   T7 增量：七步重放校验 + 丢弃审计 + confirm 判定
```

**落地修订（2026-10-05，T4 交付后回填）**：原列表中的两行
`ReservationPersistenceServiceImpl.java（步 6 的跳过分支）` 与 `ReservationPersistResult.java（新增跳过态）`
**实际未发生，也不需要**。T4 把「已补偿则拒绝落库」实现为**消费者落库前的一次账本查询**，
而不是在 `persist` 内部新增一个"跳过"返回值 —— 后者会要求改动 `persist` 的返回枚举与事务内部逻辑，
而 `persist` 的 `@Transactional` 边界正是「撞键时必须让异常穿透以便回滚名额扣减」这一前提的载体，
改它属于不必要的高风险动作。消费者侧实现的功能等价、改动面更小，且该分支可被
「同 ID 不同用户」的确定性用例覆盖（实测见 `MIGRATION_STATUS.md` 的 T4 条目）。
`ReservationPersistenceServiceImpl.java` 在本 Phase 中**零改动**。

### 11.3 禁止清单（零触碰）

- `reservation.lua`（Phase 6 的漏斗顺序、无字面量 key、无夹取）—— **不改**；
- `ReservationQuotaExecutor` 的 ARGV 全 String 约定 —— **不改**；
- `ReservationBatch` 的状态机、`BookingState`、发布不变式 I1–I7 —— **不改**；
- `CacheClient` / 设备缓存（Phase 4）—— **不改**（P2-18 见 §14）；
- `LoginInterceptor` / `RoleInterceptor` / 会话机制 —— **不改**；
- `WebExceptionAdvice` 既有异常→状态码映射 —— **不重排**（Phase 7 §17-D2 的边界，P2-25 留待独立处理）；
- `tb_reservation` 的 DDL 与 `uk_user_batch` —— **不改**（D9）；
- 不引入任何新 Maven 依赖（`@Scheduled` 是 Spring 内置）。

---

## 12. 任务拆解（阶段四，供 Codex 执行）

> 每个任务可独立编译、独立验证；顺序执行，前一个通过后再派下一个。
> 硬约束：JDK 8、不引入新依赖、**不得给 `submit` 加 `@Transactional`**、ARGV 全 String。

| # | 任务 | 交付 | 验收 |
| --- | --- | --- | --- |
| **T1** | 幂等补偿：`reservationRollback.lua` 改为 D4 语义（返回 `0/1/2`），同步改 `ReservationQuotaRollbackExecutor` 的返回码校验与 Javadoc | Lua + Executor | 单测 I1/I2/I3；`mvn -q clean test-compile` |
| **T2** | 状态账本：`tb_reservation_request` 幂等 DDL + 实体 + `ReservationRequestStatus` 枚举 + Mapper + Service（含 **CAS** 更新方法） | DDL + 实体 + Mapper + Service | DDL 重复执行 3 次无报错 |
| **T3** | 生产者改造：`submit` 按 §3.4 步 2→4 重排（**先写账本、后扣名额**）+ §3.3 异常分类 + 双 503 文案 | `ReservationServiceImpl` | 分类函数单测；不引入 `@Transactional` |
| **T4** | 消费者改造：步 6 前置检查（I10）、落库后写 `CONFIRMED`（只记日志）、失败时写 `fail_reason`、`uk_user_batch` 兜底改 ACK | Consumer + Persistence | I9 / I10 |
| **T5** | 对账：`ReservationReconcileService`（纯方法 + CAS + 幂等补偿）+ `@Scheduled` 薄壳 + 配置项 + Redisson 互斥 | Service + Config | I8 + 两分支 |
| **T6** | DLQ 台账：`tb_reservation_dlq` DDL + 实体 + Mapper + Service + **原始 `Message` 消费者**（D7） | DDL + MQ 消费者 | R9：坏 JSON 进 DLQ 后台账有行、队列清空 |
| **T7** | DLQ 管理 API：3 个端点 + §6.2 的 7 步重放校验 | Controller | I11 + 次数上限 + `COMPENSATED` 拒绝 |
| **T8** | 测试：§1.4 的 11 条 + I1–I12 逐条覆盖 + §9.3 的清理 | 测试类 | `mvn test` 全绿 |

**落地修订（2026-10-05，T5 交付后回填）**：

1. **T5 的落地形态**与上表一致，另需三个小件：`@EnableScheduling` 的配置类（项目原先没有定时开关）、
   `ReconcileSummaryDTO`（D16）、配置项 `hmdp.reservation.reconcile-interval-ms` / `reconcile-threshold-ms` /
   `reconcile-limit`（默认 300000 / 600000 / 200）。
   `reconcile(int)` **无事务、无锁**（D15），分布式锁只加在 `@Scheduled` 薄壳上、且用**非阻塞** `tryLock`。
2. **测试约定（T6–T8 必须遵守）**：**禁止在 varargs 位置使用 `any(X[].class)`** —— 在 Mockito 3.3.3 下
   它**永远不匹配**（按元素套用 → 每个元素都要 `isInstance(X[])`，元素是枚举 → 失败），
   结果是桩静默失效、调用落到真实方法。若真实方法的返回值恰好与预期桩值相同，测试会**零覆盖地通过**。
   T5 交付版正是在这里栽了：两条 `skipped` 断言的桩从未命中，靠"真实 CAS 恰好也成功"才没被立刻发现。
   可用形态：**每个 vararg 元素各写一个 `any()`**（推荐，元素数固定时最直白）、逐元素写裸值、
   或显式 `Mockito.<X>any()`。**新增/修改 varargs 桩后，必须用"把桩值改成与真实返回值相反"的变体确认它会红。**

**落地修订（2026-10-05，T6 交付后回填，**该条为实施中发现的阻塞**）**：

3. **T6 的 DLQ 监听器必须引用自己的容器工厂**（见 D18）：只把参数写成 `org.springframework.amqp.core.Message`
   **不足以**接收坏 JSON —— 共享的 Jackson 转换器在方法调用前就会失败。`ReservationDlqListenerConfig`
   新增 `reservationDlqListenerContainerFactory`（`SimpleMessageConverter`），监听器用
   `containerFactory = ReservationDlqListenerConfig.DLQ_CONTAINER_FACTORY` 引用它；`RabbitMQConfig` 与拓扑零改动。
   §14.2 的 D7 原文需按此理解。

**T6 落地结果（2026-10-05，返工 T6-R 后验收通过）**：实测 `ReservationMqTest` **21 run / 0 failures /
0 errors / 0 skipped**；判别力实验证明修复的两半**都承重**——V1 去掉 `containerFactory` 绑定、V2 去掉
`setMessageConverter(SimpleMessageConverter)` 覆盖，**两者各自都让新用例
`shouldKeepRecordingDeadLettersAfterUnparseableOne` 变红**，而主链路的对照用例保持绿。
机制补充（字节码确认）：Boot 2.3.12 的 `simpleRabbitListenerContainerFactoryConfigurer()` bean 方法
**无条件**执行 `setMessageConverter(messageConverter.getIfUnique())`，且它**不**随默认工厂名一起退让，
所以 `configure()` 会把唯一的 Jackson 转换器装到任何被它配置的工厂上 —— 覆盖必须放在 `configure` **之后**。
细节见 `MIGRATION_STATUS.md` 的 T6 条目与 `docs/refactor/PHASE8-T6-R-PACKET.md`。

---

## 13. 验收标准（对齐计划 §11）

- [ ] Transaction 正确（I4/I5，**用实验证明** `@Transactional` 真的生效，非读注解）
- [ ] Unique Index 正确（I9 / I11）
- [ ] Consumer 幂等（I9）
- [ ] 补偿幂等（I1 / I2 / I8）
- [ ] Confirm Timeout **不误回滚**（I6）
- [ ] DLQ 可治理（查询 / 详情 / 重放 + 次数限制 + 重放前幂等校验）
- [ ] 对账链路存在（两分支均验证）
- [ ] 不存在明显超卖 / 重复预约漏洞（I1–I12 全绿）
- [ ] `mvn compile` 成功（JDK 8）
- [ ] 核心异常测试通过（§1.4 的 11 条）
- [ ] 全量回归 `mvn test` 0 failures（含 §7.2 的期望值变更）
- [ ] 环境复原：DB / Redis / RabbitMQ 三方均无残留（含两张新表）

---

## 14. 决策点

### 14.1 决策点（已由用户裁定，2026-10-05）

| 编号 | 决策 | 裁定 | 落实位置 |
| --- | --- | --- | --- |
| **D-a** | P2-18（设备缓存删除失败无补偿）是否纳入 Phase 8？ | **不纳入，退回 Phase 10 评估** | §1.3 Non-goals / §11.3 禁止清单 |
| **D-b** | `UNKNOWN` 分支的 503 文案改为「预约结果待确认，请稍后查询」 | **采纳** | §3.3 / §3.4 步 4c / §7.1 |

D-b 的落实边界要说清：**只对 `UNKNOWN` 新增文案**，「确定未投递」那条仍保持
Phase 7 §17-D2 裁定的「预约失败，请重试」——因此**不推翻 D2**，也不改动 `WebExceptionAdvice`
里既有的异常→状态码映射（沿用 Phase 7 §17-D2 的边界，P2-25 留待独立处理）。

### 14.2 已由本设计直接裁定（供用户否决，截至 2026-10-05 未收到否决）

账本落 MySQL（D1）、五态命名（D2）、先写账本后扣名额（D3）、`SREM` 幂等令牌（D4）、
对账以 `tb_reservation` 为事实来源（D5）、`@Scheduled` 薄壳（D6）、DLQ 用原始 `Message`（D7）、
`fail_reason` 写账本而非改 nack 机制（D8）、保留 `uk_user_batch`（D9）、状态写失败不回滚预约（D10）、
保守异常分类（§3.3）、扫全部非终态（§3.5）、`ReconcileService` 不暴露 HTTP 端点（§6.3）、
DLQ 管理端点用 `LAB_ADMIN`（§6.2）、`MAX_REPLAY=3`。

### 14.3 T4–T7 执行期间新增的直接裁定（同样供用户否决，截至 2026-10-05 未收到否决）

> 这些是设计文档成稿后、在实现与复查中才浮现的决策点。按 §14.2 的同一规矩登记，逐条可单独否决。

| # | 裁定 | 理由 | 否决的代价 |
| --- | --- | --- | --- |
| D11 | 消费者的 `catch (DuplicateKeyException)` 由 `ACK` 改为 **`NACK` 进死信**，且**不写** `fail_reason` | 原注释假定撞 `uk_user_batch`，但该场景在消费者内**不可达**（`selectCount` 与唯一键同列 + 同一用户已被 `persistWithLock` 的 Redisson 锁串行化）；唯一可达触发是**主键撞键**，此时 `ACK` 会让未落库的消息**静默消失且 DLQ 无痕迹**，违反 §3.3「代价不对称 → 偏保守」。不写 `fail_reason` 是因为该 `requestId` 的账本行可能属于别人，写进去是污染 | 若坚持原 `ACK` 语义，需接受"消息可能静默丢失且无任何痕迹"，且该分支将继续零覆盖 |
| D12 | `fail_reason` 的语义定为「该请求**最近一次**失败的原因」，**允许**残留在最终 `CONFIRMED` 的行上（对账推进到 `CONFIRMED` 时**不**清空） | 保留审计线索；且清空需要额外一次写与额外语义，收益不成比例 | 若要求"`CONFIRMED` ⟺ `fail_reason` 为空"，需同时改 T4 的 `markConfirmedSafely` 与 T5 的对账分支（T4 已验收，属返工） |
| D13 | 对账「无行」分支的顺序是**先归还名额、后 CAS `COMPENSATED`** | 若先 CAS 再回滚而回滚抛异常，账本已是终态 → 对账**再也不会扫到它** → 名额永久少一个且无人知晓（正是本 Phase 要闭合的缺口 D）。先回滚后 CAS 则失败时账本留非终态、下轮重试，而回滚本身幂等不会二次归还 | 换取的是 §3.6 的残余窗口**略微变宽**（多出一次回滚的耗时）；若改为先 CAS，则必须额外发明"回滚失败后把终态改回非终态"的补偿，与 T2 的终态冻结语义冲突 |
| D14 | 对账「有行」分支**不回滚名额**、只用 `casStatus` 推进 `CONFIRMED` | 有行说明名额已被合法占用并落库，归还即是超发 | 无 |
| D15 | `reconcile(int limit)` **不加** `@Transactional`，每条请求独立 `try/catch` | 每条请求的 MySQL 写入只是单条 CAS UPDATE，无需跨语句原子性；整方法加事务会让一条失败回滚掉本轮全部已处理请求（§4.3 的同一条纪律） | 无 |
| D16 | 新增 `ReconcileSummaryDTO`（`scanned/confirmed/compensated/skipped/failed`）并对账无对外 API | 没有它，"把 `update_time < before` 谓词整行删掉"这类错误会让测试**照样全绿**（T2 实测过该空洞）；实测需要能断言"本轮扫到几条、各自去向" | 若要求对账保持"无返回值"，则失去对扫描谓词与上限的判别力，需另找断言手段 |
| D17 | `@Scheduled` 薄壳的互斥锁用**非阻塞** `tryLock(0, MILLISECONDS)`，**不设定固定租期**（由 Redisson watchdog 续期），且只在 `isHeldByCurrentThread()` 时 `unlock()` | 本轮对账是"扫一批陈旧请求"的短任务，抢不到锁直接跳过即可（下一轮还会来），阻塞等待只会白占调度线程；不设租期可避免"任务比租期长 → 锁提前释放 → 多实例同时跑"这个自造风险 | 若改为阻塞等锁或固定租期，需重新论证调度线程占用与多实例重叠的后果；对账本身的正确性不受影响（D15 已把正确性放在 CAS 与幂等补偿上） |
| D18 | DLQ 监听器使用**独立的容器工厂**（`ReservationDlqListenerConfig` 新增一个 `SimpleRabbitListenerContainerFactory`，复用 Boot 的 ack/prefetch/retry 配置但把转换器换成 `SimpleMessageConverter`）；**不改** `RabbitMQConfig` 的默认工厂 | §14.2 的 D7 写成"DLQ 消费者用原始 `Message` 接收"**并不充分**：Spring AMQP 2.2.18 的 `MessagingMessageListenerAdapter.onMessage` 在调用监听方法**之前**无条件执行一次共享的 `Jackson2JsonMessageConverter.fromMessage`，参数写成原始 `Message` 并不能绕过它。T6 实测：坏 JSON 在 DLQ 容器内二次转换失败 → `ConditionalRejectingErrorHandler` 的 x-death 分支抛 `ImmediateAcknowledgeAmqpException` → **被静默丢弃且台账 0 行**。死信通道的转换器不能解析业务报文，这是原则问题而非权宜 | 若坚持复用默认工厂，则"坏 JSON 必须留下台账痕迹"（R9 / §1.4）**不可实现**，且该分支永久零覆盖 |
| D19 | §6.2 步 3 的「可重放」判据是 payload 能解析出 **`reservationId`、`userId`、`batchId` 三个数字字段**，缺任一即标 `DISCARDED`（码 `PAYLOAD_NOT_REPLAYABLE`） | §6.2 原文只写了"能解析出 `reservationId`"，但那不足以判可重放：① 台账 payload 可能已被 60000 字节**截断**，截断后不是合法 JSON；② "只有 `reservationId`" 的报文重投回主队列后必然再次死信，而**每次重放都会新增一行台账**（`replay_count` 只在原行累加，新行从 0 开始），形成管理员可无限重复的堆积 | 若坚持只校验 `reservationId`，需接受"管理员点一次重放就多一行死信台账"的堆积，且截断 payload 会在重放路径抛出解析异常 |
| D20 | 重放用注入的 `ObjectMapper` 把 payload 解析成 `ReservationMessageDTO`，再 `rabbitTemplate.convertAndSend(exchange, routingKey, dto, correlationData)`；**不手工拼 `__TypeId__` 头** | 台账只存 body，**没有 headers 列**（T6 交付的 `tb_reservation_dlq` 无 headers 字段），无法还原原始消息属性；而把 DTO 包名硬编码进业务代码会在类改名/换包时**静默失效**（消息被主队列拒收 → 又进死信）。交给 `Jackson2JsonMessageConverter` 就自动带上正确的 `__TypeId__` 与 contentType | 若手工拼头，需同时新增 headers 列（改 `phase8_consistency.sql`，T6 已验收的表结构）并把 DTO 全限定名写进常量 |
| D21 | 分页查询**不返回 `payload`**（`LambdaQueryWrapper.select(...)` 只选 id/失败原因/重放次数/状态/时间），详情返回完整 payload | 单条 payload 上限 60000 字符，一页 5 条即 300KB 响应体；列表页从不展示报文正文 | 若列表也带 payload，列表接口的响应体积随死信条数线性膨胀 |
| D22 | 因步 3/4/5 置 `DISCARDED` 时，把原因码**追加**到 `failure_reason`：`<原值> \| DISCARD:<code>`，总长截断到 512，**原值完整保留为前缀**；code ∈ `PAYLOAD_NOT_REPLAYABLE`/`ALREADY_PERSISTED`/`COMPENSATED` | 覆盖原值会丢掉死信的原始失败原因（审计线索）；只改状态则事后无法区分"是人工判定不可重放"还是"重放成功" | 无 |
| D23 | 步 7 用**乐观 CAS**：`eq(status, NEW).eq(replay_count, 预检读到的值)` → `set(status, REPLAYED).set(replay_count, 预检值+1)`，返回 0 即判为并发重放并拒绝。**不**做"先占位改状态、投递失败再回退" | 回退本身可能失败，会把台账留成"看起来已重放但其实没投递"的更坏状态（且 `status != NEW` 会让管理员再也无法重放）；现方案最坏情况只是并发各发一条消息，由消费端幂等兜底 | 若改为占位+回退，需额外发明"回退失败"的补偿路径，并把 `DISCARDED`/`REPLAYED` 之外的中间态暴露给查询接口 |
| D24 | 投递失败（confirm NACK / Return / 超时 / 异常）→ 台账**保持 `NEW`、`replay_count` 不变**，返回拒绝原因码 `REPLAY_CONFIRM_NACK`/`REPLAY_CONFIRM_RETURN`/`REPLAY_PUBLISH_UNKNOWN` 并 `log.error` | §6.2 步 7 的 `+1` 只在**成功**时发生；投递未成功却记一次重放，会让"次数上限 3"这个保护被无效投放耗尽，管理员在真正需要重放时反被拒绝 | 无 |
| D25 | `replay` 方法**不加** `@Transactional` | 内部有最长 5 秒的 broker 确认等待，把事务开在持锁等待上会长时间占用连接；且每次 DB 写都只是单条 CAS UPDATE，无需跨语句原子性（与 D15 同一条纪律） | 无 |
| D26 | 三个端点均为 `@RequireRole(UserRole.LAB_ADMIN)`；成功 `Result.ok(data)`，**一切拒绝（含"记录不存在"）用 `Result.fail(文案)`**（HTTP 200 / `success=false`）；**不新增异常类型、不改 `WebExceptionAdvice`** | 与项目既有 `Result` 约定和全局异常兜底（P2-25，§15 第 8 条）一致；为治理接口单独造异常层级会引入本 Phase 不打算处理的全局兜底改动 | 若要求拒绝返回 4xx，需改 `WebExceptionAdvice` 与既有 `Result` 约定，波及全项目所有接口 |
| D27 | 账本查询**失败**（异常/无行）不阻止重放：无行按"未补偿"继续，异常按"未知"处理并 `log.error` 后继续 | 与主消费者"账本不存在仍然继续落库"的既有姿态一致；若因一次 DB 抖动就拒绝，管理员会在名额确实被归还时无法重放，且无从判断原因 | 若要求账本异常即拒绝，需接受"DB 抖动期间治理能力完全丧失"，且该拒绝与"名额已归还"在返回值上不可区分 |
| D28 | §6.2 的**七步执行顺序本身是规格**：步 1–5 全部在**投递之前**完成，任一拒绝立即返回 | 若把投递穿插在中间（例如先投递再查账本），"已落库/已补偿"的死信会被真实重投，虽然消费端幂等能兜住最终数据，但队列上会出现本不该有的消息，且 `replay_count` 与实际投递次数脱钩 | 无 |
| D29 | DLQ 分页**不实现** §6.2 表格里的 `?page=&size=`：改用项目既有约定的 `current` 查询参数 + 固定页大小 `SystemConstants.DEFAULT_PAGE_SIZE(5)` | 全项目三处翻页（`LaboratoryController` / `ReservationBatchServiceImpl` / `EquipmentServiceImpl`）无一例外都是 `current` + 固定 5 条，**没有任何调用方指定 `size` 的先例**；为单一治理接口新造一套翻页协议，会让前端与其余列表页不一致。§6.2 的 URL 是示意图而非接口契约。死信是"不该出现"的异常路径，正常量级接近 0，5 条/页可接受（响应含 `total`，管理员据此翻页） | 若要求按文档支持 `?size=`，需同时决定：是否给其余三个列表页也加上（否则协议分裂）、以及 `size` 的上下限校验（无上限时一次可拉全表 payload）；改这一条只影响 T7 的 DTO 与前端调用，不动 DB |
| D30 | 台账的 `replay_count` **不含**本次重放的投递尝试：只有成功投递（步 7）才 `+1`；次数上限判定用**读到的值** | 与 D24 配套——若把失败的投递也算作一次，管理员在 broker 短暂抖动后会发现"明明一次都没成功，额度却用完了"，而额度正是为了保护"重放已落库数据"这类真正危险的操作 | 无 |
| D31 | 置 `DISCARDED` 时若原 `failure_reason` 过长，**截原值、保标记**：先按 `512 - len(" \| DISCARD:<code>")` 截断原值再拼接，保证 `DISCARD:<code>` **完整出现**，结果总长 ≤512、原值仍为前缀（只是可能比原值短） | D22/C4 的原始表述在**原值本身就是 512 时自相矛盾**（"总长 ≤512"与"原值逐字完整"不可兼得，T7 实测：原值 500 → code 被截成 `PAYLOAD_NOT_REPL`；原值 512 → 标记整段消失）。二者必须择一：`failure_reason` 在**落库时**就被 `recordDeadLetter` 截到 512，本已是有损字段；而"为什么判不可重放"是治理这一步**新增的、也是管理员唯一可检索**的信息。故保标记、舍原值长度 | 若要求原值逐字完整，则需把 `failure_reason` 扩到 512+33 以上（改 `db/phase8_consistency.sql`，T6 已验收的表结构），或把丢弃原因另存一列（新增列 + 迁移）；两者都超出本 Task 范围 |

### 14.4 T8 执行期间新增的直接裁定（同样供用户否决，截至 2026-10-05 未收到否决）

> T8 是"覆盖收口"任务。Claude 在派发前把 §1.4 的 11 条异常与 I1–I12 逐条映射到**已存在**的用例上，
> 发现设计成稿时的"预计交付形态"与实际落点有若干不一致。这些不一致**都不降低覆盖**（判别力已逐条核对），
> 故一律按"文档让位于实现"处理，不做形式对齐 —— 但按 §14.2 的同一规矩逐条登记，可单独否决。

| # | 裁定 | 理由 | 否决的代价 |
| --- | --- | --- | --- |
| D32 | §9.1 列的 `ReservationUnknownStateTest`（新）**不单独建类**：其要求的 `@SpyBean RabbitTemplate` + `convertAndSend` 空操作 + 真实 `get(5s)` 超时，落在 `ReservationPublishFailureTest#shouldKeepQuotaAndMarkUnknownWhenConfirmTimesOut`，断言项（账本 `UNKNOWN`、Redis 名额未变、503 文案）与 §9.1 逐字一致 | 新建一个类只是把同一夹具换个壳，**不增加任何判别力**；§9.1 的类清单是设计成稿时的"预计形态"而非契约。真实 5s 阻塞超时（非造异常）这一关键点已满足 | 若要求类名与文档一致，需搬移用例并重建夹具，收益仅为命名一致性 |
| D33 | §9.2 的「`classify(Throwable)` 纯单测」**不实施**：§3.3 的五类信号是以 `catch` 子句**内联**实现的，五行分别由 `ReservationPublishFailureTest` 的 NACK / Return / `AmqpConnectException` / `TimeoutException` / 其它异常五个集成用例覆盖 | 为"可纯单测"而把内联 catch 抽成函数，是**生产代码改动**（新增一层间接、改变异常流经路径），而收益只是测试形式的纯度。§13 只要求"核心异常测试通过"，未要求纯单测 | 若要求纯单测，需改 T3 已验收的 `ReservationServiceImpl` 异常分类结构（返工），并把已有 5 个集成用例降级为对抽函数的直测，**总判别力不升反降** |
| D34 | §9.1 ④ 的「**四种**非终态各一条」修正为**三种**：`ReservationRequestStatus` 五态中终态为 `CONFIRMED`/`COMPENSATED`，非终态只有 `PRE_ACCEPTED`/`PUBLISHED`/`UNKNOWN`（`nonTerminalStatuses()` 即此三值） | 五态减两终态 = 三，原文"四种"是笔误。以 `nonTerminalStatuses()` 的实际返回值为准 | 无（除非新增第五个非终态，那时本条随之失效） |
| D35 | §5 的 I5 表述「`tb_reservation.remaining_quota` 永不为负」中的表名更正为 **`tb_reservation_batch`**：`remaining_quota` 是批次表的列，`tb_reservation` 无此列 | 表名笔误；不变式的**内容**（名额列永不为负）不变，判别方式（并发下断言 `>= 0`）不变。承重谓词是 `ReservationPersistenceServiceImpl` 条件扣减的 `.gt(remaining_quota, 0)` | 无 |
| D36 | §9.3 / §13 的「环境复原审计」由 Claude 在**全量回归之后手工执行**（查 `tb_reservation` / `tb_reservation_request` / `tb_reservation_dlq` / `tb_reservation_batch` + Redis 相关键 + 两个队列），**不**落地为测试类或脚本 | 审计的对象是"整套测试跑完之后"的状态，写进测试套件内部即自我指涉（审计代码本身也会留下夹具）；且 §11.1 的文件清单未含此产物。`RedisIdWorker` 的 `icr:reservation:<date>` 计数器键**无 TTL**是 §15 边界 7 的已登记项，**不计入**残留 | 若要求自动化，需新增一个独立于 surefire 生命周期的脚本产物，并把"无残留"变成可重复的 CI 断言 |
| D37 | `@Scheduled` 薄壳的 Redisson 锁纪律（D17 的非阻塞 `tryLock(0, MILLISECONDS)`）**不写测试**：正确性由 `reconcile(int)` 的状态 CAS + 幂等补偿承载（D15/D17 已把锁降级为"多实例去重优化"），`reconcile(int)` 本身有 7 条集成用例 | 锁的失效后果是"多跑一轮对账"，而对账是幂等的（I8 已测）。为一个纯优化项写用例，需要伪造多实例/调度线程，复杂度远超其风险面 | 若要求覆盖，需引入对 `RedissonClient` 的桩与调度线程控制；判别力仅及"锁没拿到时是否跳过" |

---

## 15. 已知边界（本 Phase 不解决，显式登记）

1. **对账误判的毫秒级残余窗口**（§3.6）：已用"足够大阈值 + 消费者前置检查"收口，但未形式化消除。交后续评估。
2. **`uk_user_batch` 阻断取消后重约**：Phase 9 必须迁移为生成列唯一键（§6.1）。
3. **DLQ 台账落库失败时消息会丢**（DLQ 无 DLX）：消费者只 `log.error` 完整 payload 后 `nack(requeue=false)`。
   DLQ 是治理通道而非数据通道，且 `tb_reservation` 才是事实来源。
4. **无告警通道**：对账/死信只有日志与查询接口，无邮件/钉钉告警（计划未要求）。
5. **单机 broker、无镜像队列**：Phase 7 §18.6 已登记，计划禁止引入集群。
6. **`prefetch=1` 吞吐上限**：Phase 7 §18.7 已登记，本 Phase 不调。
7. **`RedisIdWorker` 的 `icr:` 键无 TTL**：Phase 7 §18.5 已登记。
8. **P2-25**（全局异常兜底返回 HTTP 200）：本 Phase 不重排 `WebExceptionAdvice`。
9. **P2-7**（明文密码已在 `433cdfb` 中）→ Phase 10 历史改写 / 换密。
10. **手工确认模式 + `ImmediateAcknowledgeAmqpException` = 投递永久悬挂 → `prefetch=1` 下消费者失明**（T6 实测）。
    `SimpleMessageListenerContainer.doReceiveAndExecute`（2.2.18，字节码核对）在捕获该异常的分支只打一条 DEBUG 日志
    就 `return`，**这条路径里没有任何 `basicAck`** —— 投递永不结算，broker 因 `prefetch=1` 不再投递，
    消费者线程还在空转但从此收不到任何消息。触发条件只有一个：**错误处理器**把致命异常转成"立即确认"
    （`ConditionalRejectingErrorHandler` 在"致命转换错误 + 报文已带 `x-death`"时正是这么做的，
    见 D18 与 T6 日志原文）。D18 让 DLQ 通道不再触发它；**主消费者**若被投喂"带 `x-death` 且无法反序列化"
    的报文仍会中招（需运维手工把死信重投回 `reservation.create.queue` 才可达，T7 的重放不会——
    它要求 payload 可解析）。根治手段是把该致命策略改为只抛 `AmqpRejectAndDontRequeueException`
    （容器在手工确认模式下**能**真正 `basicNack`），本 Phase 不实施，登记待评估。
11. **DLQ 监听器仍有理论上会抛异常的输入**：`contentType=application/x-java-serialized-object` + 垃圾 body
    （`SimpleMessageConverter` 会走 `SerializationUtils.deserialize` 并抛出）。该异常发生在**监听方法之前**，
    监听器自身的 `try/catch` 救不了，会走进第 10 条的悬挂路径。本系统产生的死信都带 JSON content type
    （生产者用 `Jackson2JsonMessageConverter`），故当前不可达；登记为边界而非缺陷。

---

## 16. 提交计划

Phase 8 通过 Review 后**仅本地提交**，建议信息：

```
feat: add consistency reconciliation for reservation
```

**不 push**——凭据外部化与历史清洗归 Phase 10（`PHASE7-DESIGN.md §16` 同一口径）。
