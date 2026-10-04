# Phase 6 Design — Redis + Lua 高并发预约（名额原子抢占）

> 本 Phase 是计划里的核心技术 Phase 之一。目标不是「把秒杀 Lua 改个名字」，而是把
> **「一个用户对一批次最多占一个名额，且总占用不超过总名额」** 这条业务不变式，
> 用一次 Redis 原子执行来保证，并把「校验到执行之间状态会不会变」这件事逐条证明清楚。

---

## 0. 本 Phase 的交付与一条必须先说清的边界

**交付**：学生提交预约的入口 `POST /reservation`，其名额扣减由一段 Lua 脚本原子完成。
成功后返回 `reservationId`（预接受凭证），**不落 MySQL、不发 MQ**。

**一条必须先说清的边界（本 Phase 最容易做错的地方）**：

> 判断「这个批次现在能不能约」，靠的是 **`reservation:batch:quota:{batchId}` 这个键存不存在**，
> 而不是把 MySQL 的 `status` 再抄一份到 Redis。

理由：Phase 5.2 已经建立了「发布建键、关闭/取消删键」这条规则，所以「键存在」在语义上
**恰好等于**「批次处于可预约期」。如果 Phase 6 再往 Redis 里同步一份 status，
就凭空多出一份需要与 MySQL 对账的派生状态，而且它一定会在某个并发窗口里与 MySQL 不一致——
这正是重构文档第 24 节反复强调要避免的那类问题。

计划文档 §9 写的是「Lua 里校验批次状态/时间」。本设计对这一条的落实方式是：
**「批次状态」的校验 = `EXISTS 名额键`**（见 §2.2），语义等价、状态更少。
这一点是本设计对计划措辞的一处有意收敛，在此显式声明。

---

## 1. 预约链路

### 1.1 端到端主流程

```
学生 POST /reservation {batchId}
  ↓ LoginInterceptor（登录校验，已有）
  ↓ ReservationServiceImpl.submit
  │   ① 参数 / 登录人        → batchId 非空、userId 非空
  │   ② 批次存在 + 状态闸门   → 必须是 PUBLISHED           【承重，见 §4.3】
  │   ③ 设备可用闸门          → 直读 DB（不读缓存）        【承重，见 §4.1】
  │   ④ 时间窗口闸门          → bookingStart ≤ now ≤ bookingEnd
  │   ⑤ 生成 reservationId    → RedisIdWorker.nextId("reservation")
  ↓
  ↓ Lua 一次原子执行（真正的裁决点）
  │   ⑥ 名额键存在？          → 否：1 批次未开放
  │   ⑦ 窗口内？              → 否：4 未开始 / 5 已截止
  │   ⑧ 还剩名额？            → 否：2 名额已约满
  │   ⑨ 该用户没约过？        → 否：3 你已预约过该批次
  │   ⑩ DECR 名额 + SADD 用户  + EXPIRE Set
  │   → 0 成功
  ↓
返回 Result.ok(reservationId)
```

### 1.2 本 Phase 明确不做

| 不做 | 归属 |
| --- | --- |
| 写 `tb_reservation` 表、扣 MySQL `remaining_quota` | Phase 7 |
| 发 MQ、削峰、异步落库 | Phase 7 |
| 消息幂等、补偿、Redis/MySQL 对账 | Phase 8 |
| 取消预约、履约核销、我的预约列表 | Phase 9 |
| 限流（对已约满批次的重复请求不做额外拦截） | 非计划项，见 §16 |

---

## 2. Redis 数据模型

### 2.1 两个 Key

| Key | 类型 | 值 | 生命周期 |
| --- | --- | --- | --- |
| `reservation:batch:quota:{batchId}` | String | 剩余名额（整数） | `publish` 以 `SET NX` 创建；Lua `DECR`；`close`/`cancel` 删除；TTL = `endTime + 24h - now`（Phase 5.2 已实现） |
| `reservation:batch:users:{batchId}` | Set | 已抢到名额的 userId | 首次抢到名额时由 Lua `SADD` 懒创建；`close`/`cancel` 删除；TTL 同 quota 规则 |

`quota` 键沿用 Phase 5.2 的命名与常量 `RESERVATION_BATCH_QUOTA_KEY`，**不改名、不改类型**。
新增 `RESERVATION_BATCH_USERS_KEY = "reservation:batch:users:"`。

**Key 一律由 Java 通过 `KEYS[]` 传入 Lua，脚本内不出现任何字面量 Key。**
这条纪律顺手关闭了 P2-1（「Redis Key 重复定义：Java 常量 vs Lua 字面量」）在新脚本上的复发。

### 2.2 「名额键存在」＝「批次可预约」

`publish` 建键、`close`/`cancel` 删键，且状态机不允许 `CLOSED`/`CANCELLED` 回到 `PUBLISHED`。
因此下面三件事互为充要：

- 名额键存在
- 批次处于 `PUBLISHED` 且未被关闭/取消
- 系统认为「此刻可以扣名额」

**由此得到本设计最重要的一条性质**：撤销方向（`PUBLISHED → CLOSED/CANCELLED`）的并发安全性
由 Redis 单键的存在性天然保证，不需要任何跨存储的一致性机制。

**但「键存在」不等于「窗口开放」**：名额键的 TTL 是 `endTime + 24h`，比 `bookingEndTime` 长得多
（这是 Phase 5 有意为之——名额键要活到 Phase 9 履约结束）。所以窗口校验必须单独做，
见 §3.2 的 ⑦。

### 2.3 Set 的 TTL 为什么在 Lua 里设，而不是在 publish 里设

Redis 不保存空集合，「publish 时先建空 Set 再设 TTL」在 Redis 里根本无法表达。
所以 Set 只能等到**第一次有人抢到名额**时才诞生，TTL 也就在那一刻设。
每次 `SADD` 成功都重新 `EXPIRE` 一次是安全的：TTL 由 Java 每次用
`endTime + 24h - now` 重新算出来，刷新它只是把到期时刻**重新收敛到同一个绝对时间点**，
不会因为不断有人预约就把 Set 的寿命无限延长。

（不使用 `EXPIRE ... NX`：那是 Redis 7.0+ 才有的选项，本机会变成 Redis 8.10.2 当然支持，
但没必要把脚本绑到一个非必需的版本特性上。）

---

## 3. Lua 契约

### 3.1 KEYS / ARGV / 返回码

```
KEYS[1] = reservation:batch:quota:{batchId}    String
KEYS[2] = reservation:batch:users:{batchId}    Set
ARGV[1] = userId
ARGV[2] = now              (epoch 秒)
ARGV[3] = bookingStartTime (epoch 秒)
ARGV[4] = bookingEndTime   (epoch 秒)
ARGV[5] = usersKeyTtlSeconds
返回：整数返回码
```

| 码 | 名称 | 含义 | Java 侧文案 |
| --- | --- | --- | --- |
| 0 | `SUCCESS` | 抢占成功，名额已 −1、userId 已入 Set | — |
| 1 | `BATCH_NOT_OPEN` | 名额键不存在（未发布 / 已关闭 / 已取消） | `批次未开放预约` |
| 2 | `SOLD_OUT` | 名额已用尽 | `名额已约满` |
| 3 | `DUPLICATE` | 该用户已占本批次名额 | `你已预约过该批次` |
| 4 | `BOOKING_NOT_STARTED` | `now < bookingStartTime` | `预约尚未开始` |
| 5 | `BOOKING_EXPIRED` | `now > bookingEndTime` | `预约已截止` |

**所有 ARGV 必须传 `String`**（`String.valueOf(...)`），不能传 `Long`/`Integer`。

这不是风格问题，是一个会在运行时炸掉的陷阱，而且本项目的工作方式**恰好抓不到它**——
Codex 沙箱连不上 Redis，集成测试在它那边全部因连接失败报错，真正的 CCE 会被淹没在环境噪音里。
依据：`DefaultScriptExecutor.keysAndArgs` 对每个 ARGV 调 `argsSerializer.serialize(Object)`，
而 `StringRedisTemplate` 的 argsSerializer 就是 `StringRedisSerializer`，
其内部分桥接方法会把入参强转成 `String` → `ClassCastException: java.lang.Long cannot be cast to java.lang.String`。
（已用 `javap -c` 反编译 `spring-data-redis-2.6.2` 确认，非推测。既有的 `SimpleRedisLock`
与已删除的原秒杀代码也都只传字符串。）

**「返回码明确」的落实口径**：Lua 返回**互斥的整数码**，Java 侧用枚举
`ReservationResultCode` 一一映射为文案，非 0 即 `throw new BadRequestException(msg)` → HTTP 400。

**考虑过并否决的方案**：为「已约满」「重复预约」单独引入 409 Conflict。
否决理由——本项目既有约定是「业务拒绝一律 400 + `Result.fail(msg)` 文案」，
只为两个码引入第三种状态语义，会让客户端同时依赖状态码与文案两套判别逻辑，
收益（语义更细）小于成本（契约分裂）。文案本身已经能区分 UI 分支。

### 3.2 检查顺序及其理由

```
1) EXISTS KEYS[1]   → 0 则 return 1
2) now < bookingStart → return 4 ;  now > bookingEnd → return 5
3) GET 配额 ≤ 0 或 nil → return 2
4) SISMEMBER KEYS[2] userId → return 3
5) DECR KEYS[1]
6) SADD KEYS[2] userId ; EXPIRE KEYS[2] ARGV[5]
7) return 0
```

采用**漏斗顺序**：闸门是否打开 → 是否在窗口内 → 是否还有容量 → 这个用户是否已在其中。

- **为什么 `EXISTS` 排第一**：批次没开放时，后面三项的结论都没有意义，而且它是最便宜的一次调用。
- **为什么容量（2）排在重复（3）之前**：本链路的**热点形态**是「约满之后大量用户继续涌入」，
  此时绝大多数请求是**没约过的陌生用户**，容量检查能短路上掉这次 `SISMEMBER`。
  代价是「已约满时，一个已经约过的用户重试会收到『名额已约满』而非『你已预约过该批次』」。
  两者都为真，只是后者更贴合个人状态；本设计选择让**热点路径更短**，此处显式记录取舍。
- **窗口检查（2）当前是否承重——实测判定（2026-10-04 补，见 `PHASE6-REVIEW.md` §4.3）**：
  结论是**被 Java 遮蔽的冗余，不是承重的校验**，但保留。
  理由：Java 在校验窗口时与传给 Lua 的是**同一个 `now`**，且两侧条件互相蕴含
  （`floor(now) < floor(start) ⟹ now < start`，秒级截断是单调的），
  所以在交付的代码里 Lua 的窗口守卫**不可能先于 Java 触发**。三组对照实验：
  只删 Lua 的 → 服务层仍全绿（Java 单独足够）；只删 Java 的 → 服务层**也**全绿（**Lua 单独也足够，非死代码**）；
  两处都删 → 服务层打红（不存在第三个拦截机制）。
  之所以保留：§3.4 把执行器钉为唯一调用点，而这个前提一旦变化（例如 Phase 9 新增调用方），
  Lua 的窗口守卫会立刻变成承重的。**注意这与 `EXISTS`（1）和容量（3）/去重（4）不同**——
  后三者是真正承重的，因为 Java 侧没有等价的校验。

- **为什么 `DECR` 可以直接用、不需要 `DECRBY` 或事后夹取**：因为 1)~5) 全在同一次 Lua 执行内，
  Redis 单线程不会在它们之间插入别的客户端命令。**这正是必须写成脚本的原因**——
  若把「检查」和「扣减」拆成两次 Java 调用，两个请求可以同时读到 `1`，然后各扣一次，变成 `-1`。
  这条性质由 §12.4 的 F7 实验直接证伪验证（把脚本换成两段式 Java 实现 → 并发用例必须变红）。

### 3.3 明确**不**加的一处防御

不在 `DECR` 之后加 `if left < 0 then INCR back end`（把配额夹回非负）。

理由：**加了它，「名额永不为负」这条断言就失去判别力**——即使 ⑧ 的容量守卫被删除，
请求方也已先行拦截，负数永远不会出现在数据里，测试会恒绿。
本设计选择让不变式由「守卫 + 原子性」保证，并在 §12.4 用 F3 实验证明守卫确实承重。

（已知副作用：若有人用错误类型写脏了 `quota` 键，`tonumber(quota)` 得到 `nil`，
`nil <= 0` 触发 Lua 错误 → 脚本执行抛出运行时异常 → 由全局兜底处理器
`WebExceptionAdvice.handleRuntimeException` 接住，返回 `Result.fail("服务器异常")`。

**实测更正（2026-10-04，由 Codex 自查提出、Claude 复核）**：该兜底处理器**未声明 `@ResponseStatus`**，
所以真实 HTTP 状态是 **200** 而不是 500。本设计初稿在此处断言 500，属未经核实的假设，现更正。

设计意图「响亮失败」并未失效——`log.error` 打全栈、调用方拿不到 `reservationId`、业务上没有任何
静默兜底；但有两点必须记在账上：
① 「服务器故障返回 200」本身是缺陷；
② 同一个兜底还会接住 `HttpMessageNotReadableException` 这类**客户端**错误，也一并返回 200 + 「服务器异常」。
这属于**跨切面的全局异常→状态码契约**，不属于本 Phase 的改动范围（§9），
已登记为 P2-25 交 Phase 10 统一决定，而不是在本 Phase 顺手改掉。）

### 3.4 脚本的唯一调用点：`ReservationQuotaExecutor`

脚本**不允许**被 `ReservationServiceImpl` 直接持有并调用，而是抽一个组件出来：

```java
// com.hmdp.utils.ReservationQuotaExecutor
public ReservationResultCode tryAcquire(ReservationBatch batch, Long userId, LocalDateTime now)
```

它负责：持有 `DefaultRedisScript<Long>`（静态块加载 `reservation.lua`）、拼 KEYS/ARGV、
算 `users` Set 的 TTL、把脚本返回的整数映射为 `ReservationResultCode`。

抽出来的理由不是为了分层好看，而是**为了可测**：窗口类守卫（返回码 4/5）如果只能经由
`ReservationServiceImpl` 触发，就必须去构造「已过期但仍在可约状态」的数据库状态，
只能靠 sleep 或者直改库。抽成组件后，测试可以传入一个**自造的 `ReservationBatch` + 显式 `now`**，
不碰数据库、不等时间，把六个返回码全部做成确定性用例（§12.1）。

**关键性质：生产路径与测试路径走的是同一个调用点**——单测绕过的是 Java 前置校验，
而不是脚本加载与返回码映射本身。因此加载方式写错（例如漏了 `setResultType(Long.class)`）
也会被这些用例抓到。

**前置条件（写在 javadoc，不做运行时兜底）**：调用方必须已确认窗口开放，
即 `now ≤ bookingEndTime ≤ startTime < endTime`，从而 `endTime > now`、Set 的 TTL 恒为正。
因此脚本**不需要**处理「TTL ≤ 0」这种输入——它是不可达的，
凭空加一个分支只会让不变式变模糊。

**`code == null` 或返回了未知码**（脚本被换坏、Redis 返回异常类型）：
抛 `BadRequestException("预约失败，请稍后重试")`，同时 `log.error` 打出原始值。响亮失败，不静默当作成功。

### 3.5 时间语义

`now`、`bookingStartTime`、`bookingEndTime` 三者统一用
`LocalDateTime.atZone(ZoneId.systemDefault()).toEpochSecond()` 转成 epoch 秒。

**为什么 `now` 由 Java 传入，而不是在 Lua 里 `redis.call('TIME')`**：

1. 窗口本身是 Java 用 `LocalDateTime.now()` 写入 MySQL 的。**用同一个时钟去比较，天然自洽**；
   换成 Redis 的时钟就引入了两时钟偏移，而这两个时钟谁快谁慢对本业务毫无意义。
2. 校验逻辑与 Redis 内部时钟解耦，脚本成为「给定入参即确定」的纯函数，**可以直接单测**
   （§12.1 就是靠这一点把窗口守卫做成确定性用例的）。

三个值用同一个 helper 转换，因此无论 JVM 默认时区是什么，比较结果都正确（只用到差值）。

---

## 4. Java 校验 vs Lua 校验：边界，以及「校验到执行之间状态变化」的逐条证明

这是交接文档 §13 点名要求接手 Agent 回答的问题。下面逐条给出结论，而不是笼统地说「Java 先校验一下」。

### 4.1 五个相关事实的可变性分析

| 事实 | 在批次可预约期间会变吗 | 由谁兜底 | 说明 |
| --- | --- | --- | --- |
| 批次 `status`：`PUBLISHED → CLOSED/CANCELLED` | **会**（这正是关闭/取消的含义） | **Lua**（`EXISTS` 名额键） | §4.2 |
| 批次 `status`：`DRAFT → PUBLISHED` | **会**（发布瞬间） | **Java 前置校验** | §4.3。这是本设计里最反直觉的一条 |
| `bookingStartTime` / `bookingEndTime` | **不会** | 不可变性 | `updateDraft` 要求 `DRAFT`，且状态机没有 `PUBLISHED → DRAFT`。所以一经发布即冻结 |
| `equipment.status` / `bookingEnabled` | **会**（管理员可随时停用设备） | Java 前置校验 | **残留竞态，显式登记**，见 §4.4 |
| Redis 名额、该用户是否已占 | **会** | **Lua** | 本 Phase 存在的全部理由 |

### 4.2 撤销方向：Lua 的 `EXISTS` 是权威

时序：Java 读到 `PUBLISHED` → 放行 → 【管理员关闭批次：删名额键】→ Lua 执行。

结果：`EXISTS = 0` → 返回 1 → 拒绝。**Java 那一次读到的是过期快照，但它无法造成错误接受**，
因为最终裁决在 Lua。这就是「校验与执行之间状态会变」在撤销方向上的答案：
**让权威判断发生在原子执行内部，前置校验只负责给出更好的错误文案。**

### 4.3 激活方向：Java 的前置状态校验是**承重**的，不能删

这是我推导过程中发现的、**单看 Lua 会漏掉的一个洞**。

Phase 5.2 的 `publish` 执行顺序是：

```
1. validatePublishInvariants
2. SET NX 名额键          ← 此刻键已存在，但 MySQL status 还是 DRAFT
3. 条件 UPDATE DRAFT → PUBLISHED   ← 到这里 MySQL 才可见
```

在 2 与 3 之间存在一个窗口：**名额键已经存在，但批次在 MySQL 里仍是草稿。**

如果没有 Java 的前置状态校验直接进 Lua，Lua 的 `EXISTS` 会通过（键在），
窗口校验会通过（I5 保证 `bookingEndTime > now`），容量校验会通过（刚发布，名额满）——
于是**一个学生能约到一个还没发布的批次**。

闭合它的正是 `submit` 的第一步状态闸门：`status != PUBLISHED → 拒绝`。
因为 `publish` 只会「先建键、后提交状态」，所以：

- Java 读到 `DRAFT` → 直接拒绝，**不进入 Lua**，窗口被闭合；
- Java 读到 `PUBLISHED` → 说明 CAS 已提交，而键必然更早写入，键一定存在；
- Java 读到 `CLOSED`/`CANCELLED` → 拒绝，无风险。

**结论：Java 前置状态校验与 Lua 的 `EXISTS` 是互补的两半，各守一个方向，一个都不能少。**
§12.2 的 T12 用一个确定性用例（草稿批次 + 人工造出名额键 → 必须拒绝，且报错文案必须是
Java 层那句而不是 Lua 层那句）把这条钉死。

### 4.4 两组残留竞态，显式登记为已知限制

**（a）设备停用竞态**：Java 读设备可用 → 【管理员停用设备】→ Lua 执行成功。
后果：约到了一个刚被停用的设备。评估：

- 它**不会**造成超卖，也**不会**造成重复预约——配额与去重仍在 Lua 内原子保证；
- 时间尺度是秒级，且需要管理员恰好在同一瞬间停用；
- 关闭口径：本 Phase **不**把设备可用性搬进 Redis（那会引入第三份派生状态），
  接受该竞态并登记，交由 Phase 8 的对账/告警视角处理。

**（b）关闭/取消与已接受请求**：Lua 已成功（名额已扣），随后 `close` 删掉两个键。
学生手里有 `reservationId`，但 Redis 里已无任何痕迹。评估：这是
**「预接受已发生、异步落库尚未发生」** 的固有一致性窗口，属于 Phase 7（异步落库）与
Phase 8（补偿/对账）的职责范围，本 Phase 只保证「抢占本身原子正确」，
在此登记交接点，不在 Phase 6 内自行发明补偿机制。

---

## 5. ID 设计：`requestId` 与 `reservationId` 是否同一个

交接文档 §Phase 7 的消息体建议里同时列了 `requestId` 与 `reservationId`，并要求 Phase 6
明确二者关系。**本设计的裁定：合一，只保留 `reservationId`。**

理由：

1. 本系统里一次提交最多产生一条预约（单批次、单用户、单名额），
   两个 ID 会**恒等**——恒等的两个字段是纯仪式。
2. 幂等真正需要的是「**同一用户对同一批次不能重复占额**」，这条已由 `users` Set 保证，
   而且它比「请求级幂等键」更强：**客户端重试会生成新 ID，但仍然被 Set 挡住**。
   请求级 ID 在这里提供不了 Set 提供不了的东西。
3. `reservationId` **必须在 Lua 之前生成**。因为 Lua 若因超时/连接中断而结果未知，
   必须有一个事先存在的凭证去回答「我那次扣减到底发生了没有」。
   这个凭证只能是预约 ID，再引入一个 requestId 不解决任何问题。
4. 代价是「被拒绝的请求会烧掉一个 ID」。ID 是 `时间戳<<32 | 自增`，空洞是常态，
   无任何影响。
5. 若将来真的需要「每次尝试」的标识，那属于**可观测性**（网关 traceId），
   不是业务主键，不应与预约 ID 混为一谈。所以现在合并**不构成死路**。

生成方式：`redisIdWorker.nextId("reservation")`，复用既有 `RedisIdWorker`，不新造发号器。
（注意它内部的计数键是硬编码的 `icr:` 前缀——本 Phase 不改它，登记见 §16。）

---

## 6. 接口设计

```
POST /reservation
  Body: { "batchId": 123 }
  Auth: 需登录（LoginInterceptor 已覆盖，不额外标 @RequireRole——普通 USER 与 LAB_ADMIN 都可预约）
  200 : Result.ok(reservationId)      // 预接受
  400 : Result.fail("<返回码文案>")
```

新增 `ReservationSubmitDTO { Long batchId }`（对齐既有 DTO 约定，也为 Phase 7 留扩展位）。

**响应为什么只返回 `reservationId`、不返回剩余名额**：剩余名额是**批次**的属性，
学生端已经有 `GET /reservation-batch/{id}` 可以读，且 §7 会保证它读到的是 Redis 的实时值。
让「提交」只回答「我的凭证是什么」，让「查询」回答「还剩多少」，职责更干净，
也避免了在热点路径上多一次 GET。

---

## 7. 读路径的剩余名额（决策点 D1）

**问题**：Phase 6 只把名额扣在 Redis 上，MySQL 的 `remaining_quota` 到 Phase 7 才由消费者扣减。
于是 Phase 6 期间 `GET /reservation-batch/{id}` 与 `/page` 会显示「剩余 = 总名额」，
即使 Redis 里已经约满——**读路径在说谎**。

**方案**：批次读路径（`queryBatchById` / `queryPage` 两条）对 **`status == PUBLISHED`** 的记录，
用一次 `multiGet` 批量取名额键，**键存在则用 Redis 的值覆盖 `remainingQuota`**；
键不存在（已关闭/取消）或 Redis 不可用时，保留 MySQL 的值。

细节与理由：

- **一次 `multiGet`**，不是逐条 GET（一页最多 5 条，一次往返）。
- **只覆盖 `PUBLISHED`**：`CLOSED` 批次名额键已被删除，本就应回落到 MySQL。
- **Redis 异常时降级到 MySQL 并记日志**，不让读接口因 Redis 抖动而 500——
  与 Phase 5.2 `publish` 失败分支「删键失败只记日志」同一取舍口径。
- **这不是会立刻被 Phase 7 删掉的临时脚手架**：进入 Phase 7 后 MySQL 只是**最终**一致，
  在预约进行中 Redis 始终是**实时**值，所以「PUBLISHED 优先读 Redis、缺失回落 MySQL」
  这条读路径规则在 Phase 7 之后依然成立。

---

## 8. 对 Phase 5 既有代码的改动，以及我承诺过的「CAS 守卫是否降级为防御」

我在 Phase 5 收尾时向用户承诺：Phase 6 会把并发裁决点从 MySQL 移到 Redis Lua，
因此需要重新评估 Phase 5.2 那三处 CAS 守卫是否只剩防御价值。**结论：没有降级，一处都不动。**

| Phase 5.2 守卫 | Phase 6 之后是否仍承重 | 为什么 |
| --- | --- | --- |
| `updateDraft` 的 `.eq(status, DRAFT)` | **仍承重** | 守的是**管理端生命周期**（草稿编辑 vs 发布），与预约热点路径无交集 |
| `publish` 的六个快照 `.eq(...)` | **仍承重** | 守的是 H10（草稿字段写进已发布批次），纯 MySQL 侧竞态 |
| `publish` 的 `.eq(status, DRAFT)` | **仍承重** | 守的是 H12（并发 publish × cancel 复活已取消批次），不经过 Redis |

原因很清楚：Phase 6 移走的是**学生预约**这一个决策点，
而 publish/close/cancel/updateDraft 这条**批次生命周期**决策链仍然完全由 MySQL 条件更新守卫，
两者不重叠。**唯一的交叉是**：`close`/`cancel` 现在必须同时清掉 `users` Set（见下）。

**Phase 6 对 Phase 5 代码的实际改动，仅两条**：

1. `close` / `cancel`：把 `stringRedisTemplate.delete(quotaKey(id))` 改成
   **一次 `DEL` 删两个键**（`quota` + `users`）。
   顺序上「配额键与其他键一起删」天然把「键在但 Set 已清」的窗口压到最小；
   即便仍有并发请求在此瞬间成功，残留的 Set 成员也会随 TTL 到期消失，不会泄漏。
2. 读路径按 §7 覆盖 `remainingQuota`。

**`publish` 不改。** 明确论证过「发布时是否需要顺手删一次 `users` Set」：
不存在 `CLOSED/CANCELLED → PUBLISHED` 的转移，且 `publish` 用 `SET NX` 建键、
重复发布会被 NX 拒绝，所以一个批次不可能被发布两次，也就不可能存在上一轮的残留 Set。**证毕，不动。**

---

## 9. 改动范围（文件清单）

**新增**

| 文件 | 作用 |
| --- | --- |
| `src/main/resources/reservation.lua` | 原子抢占脚本（§3） |
| `com.hmdp.dto.ReservationSubmitDTO` | `{ batchId }` |
| `com.hmdp.enums.ReservationResultCode` | Lua 返回码 ↔ 文案（§3.1） |
| `com.hmdp.service.IReservationService` | `Long submit(Long batchId)` |
| `com.hmdp.utils.ReservationQuotaExecutor` | 脚本唯一调用点：加载 / 拼参 / 返回码映射（§3.4） |
| `com.hmdp.service.impl.ReservationServiceImpl` | §1.1 主流程（前置校验 + 调 executor） |
| `com.hmdp.controller.ReservationController` | `POST /reservation` |
| `src/test/java/com/hmdp/ReservationQuotaExecutorTest` | 返回码直测，确定性（§12.1） |
| `src/test/java/com/hmdp/ReservationSubmitTest` | 服务/集成/并发（§12.2、§12.3） |

**修改**

| 文件 | 改动 |
| --- | --- |
| `com.hmdp.utils.RedisConstants` | 新增 `RESERVATION_BATCH_USERS_KEY` |
| `com.hmdp.service.impl.ReservationBatchServiceImpl` | `close`/`cancel` 双键删除；读路径覆盖 `remainingQuota`（§7、§8） |
| `src/main/java/com/hmdp/controller/ReservationBatchController.java` | **仅当 D2 采纳**：`GET /{id}` 对学生隐藏 `DRAFT`/`CANCELLED` |

Lua 脚本加载方式复用 Phase 4/5 既有模式（`SimpleRedisLock` 的
`DefaultRedisScript<Long>` + `setLocation(new ClassPathResource(...))` + `setResultType(Long.class)`），
不新造加载机制。

---

## 10. 禁止清单（零触碰）

- 不改 `RedisIdWorker` 的发号算法与 `icr:` 计数键。
- 不改 `SimpleRedisLock` / `unlock.lua` / `CacheClient` / 设备缓存（Phase 4 的成果）。
- 不改 `publish` 的执行顺序与三处 CAS 守卫（§8）。
- 不改 `Result` 的结构（不新增 `code` 字段）。
- 不引入 Redisson 分布式锁来解决名额扣减——**名额扣减的正确性由 Lua 原子性保证**，
  加锁只会把性能故事讲坏，且掩盖真正的原子性问题。
- 不建 `tb_reservation` 表、不写 MQ、不做补偿（§1.2）。
- 不把批次 `status` 同步到 Redis（§0）。

---

## 11. 高危风险点（Review 必须逐条实测）

| 编号 | 风险 | 实测方式 |
| --- | --- | --- |
| H13 | 脚本里 `EXISTS`/`GET`/`DECR` 被拆成多次 Java 调用 → 超卖 | F7 变体（改成两段式 Java 实现）必须让并发用例变红 |
| H14 | 激活方向漏洞：草稿批次因名额键先建而被约到 | T12 确定性用例（草稿 + 人造名额键 → 必须拒绝，且文案为 Java 层） |
| H15 | 重复预约守卫缺失 → 同一用户重试多次占多个名额 | F4 变体必须让 T9/T10 变红 |
| H16 | 容量守卫缺失 → 名额为负 | F3 变体必须让 T8 与并发不变式变红 |
| H17 | 窗口守卫缺失 → 窗口外仍可预约 | F2 变体必须让 T6/T7 变红 |
| H18 | Set 无 TTL → 已结束批次的用户集合永久堆积 | 直接 `TTL` 实测 + 断言 TTL 落在 `endTime+24h` 附近 |
| H19 | 读路径覆盖用错状态口径 → 已关闭批次显示 Redis 残值 | T13/T14 覆盖 `PUBLISHED` 与 `CLOSED` 两条路径 |

---

## 12. 测试计划

### 12.1 返回码直测（`ReservationQuotaExecutorTest`，确定性，一个返回码一个用例）

直接调 `reservationQuotaExecutor.tryAcquire(batch, userId, now)`，**自造 `ReservationBatch` 并显式传 `now`，
不碰数据库、不 sleep**。窗口类守卫因此完全确定性——不需要等时间流逝，也不需要直改库。

| 编号 | 用例 | 断言 |
| --- | --- | --- |
| T1 | 正常抢占 | 返回 `SUCCESS`；配额 4→3；Set 含该 userId；Set TTL ∈ (0, endTime+24h−now] |
| T2 | 名额键不存在 | 返回 `BATCH_NOT_OPEN`；Set 未创建 |
| T3 | 配额为 0 | 返回 `SOLD_OUT`；配额仍为 0；Set 未变 |
| T4 | 同一用户第二次 | 返回 `DUPLICATE`；配额只被扣过一次 |
| T5 | `now < bookingStart` | 返回 `BOOKING_NOT_STARTED`；配额未变 |
| T6 | `now > bookingEnd` | 返回 `BOOKING_EXPIRED`；配额未变 |
| T7 | 配额键值非数字（脏数据） | 抛异常（响亮失败），不被静默吞掉 |
| T7b | 六个返回码与 `ReservationResultCode` 的映射 | 直接断言 `fromCode` 逐个对应，且非 0 码都有非空文案 |

### 12.2 服务/集成（`ReservationSubmitTest`）

| 编号 | 用例 | 断言 |
| --- | --- | --- |
| T8 | 约满 | 总名额 1，user1 成功 → user2 得 `名额已约满`；配额为 `"0"` |
| T9 | 重复预约 | 同一用户连提两次 → 第二次 `你已预约过该批次`；配额只减 1 |
| T10 | 关闭后预约 | `publish` → `close` → 预约被拒；`quota`/`users` 两键均已删除 |
| T11 | 取消后预约 | 同上，走 `cancel` 分支 |
| T12 | **草稿 + 人造名额键** | 必须拒绝；**文案必须是 Java 层「批次当前不可预约」而非 Lua 层「批次未开放预约」**（以此证明 Lua 根本没被执行） |
| T13 | 读路径覆盖 | `publish` 后约 1 次 → `queryBatchById().remainingQuota == 3`（来自 Redis，非 MySQL 的 4） |
| T14 | 读路径回落 | `close` 后 `queryBatchById().remainingQuota` 等于 MySQL 的值且不抛异常（该值在本 Phase 仍是总名额，属 §16.7 已登记的暂态，断言按 MySQL 值写，不要断言成 Redis 的残值） |
| T15 | 未登录 | `UserHolder` 为空 → `ForbiddenException` |
| T16 | 批次不存在 | 得 `预约批次不存在` |
| T17 | 设备不可用 | 设备 `bookingEnabled=false` → 拒绝；**断言读的是 DB 不是缓存**（先写缓存再改库，仍须拒绝） |
| T18 | 窗口未开始 / 已截止（服务层） | 分别得 `预约尚未开始` / `预约已截止`；已截止用例通过**发布后直改 `bookingEndTime` 到过去**构造（确定性，不 sleep） |
| T18b | D2：批次详情的角色可见性 | 学生读 `DRAFT` / `CANCELLED` → `null`（响应「预约批次不存在」）；学生读 `PUBLISHED` / `CLOSED` → 正常返回；管理员读 `DRAFT` → 正常返回 |
| T21 | **撤销方向的服务层证据**（返工时补，见 `PHASE6-REVIEW.md` §6.1） | 已发布批次 → 手工删除名额键（制造"MySQL 说开放、Redis 说已关闭"的过期快照，且断言 MySQL 仍是 `PUBLISHED`）→ 提交被拒，且**文案必须是 Lua 层「批次未开放预约」而非 Java 层「批次当前不可预约」**；用户集键不存在 |
| T22 | **分页路径的读覆盖与回落**（返工时补，见 `PHASE6-REVIEW.md` §6.2） | 用 `equipmentId` 收窄到本用例自己的设备（不依赖分页大小与库中既有数据）；`PUBLISHED` 且约过 1 次 → 分页 VO `remainingQuota == 3`（来自 Redis），**同时断言 MySQL 仍是 4**；`CLOSED` → 分页 VO `remainingQuota == 4`（回落 MySQL，覆盖 `status == PUBLISHED` 过滤分支） |

> **T21/T22 为什么是原计划的缺口**：T2 只在执行器级验证了 `EXISTS`（自造 `ReservationBatch`），
> T10/T11 在服务级验证的其实是 **Java 状态闸门**（它们不校验文案，所以并**没有**走到 Lua）；
> T13 只覆盖详情路径的读覆盖。三处缺口都是**变体实验打出来的**，不是读代码推出来的。

### 12.3 并发不变式（断言写成不变式，不依赖时序——Phase 5 的教训）

| 编号 | 用例 | 不变式 |
| --- | --- | --- |
| T19 | 20 个不同用户抢 5 个名额 | 成功数 **恰好 5**；配额 == `"0"`；`Set.size() == 5`；**成功返回的 userId 集合与 Set 成员完全一致**；配额从不为负 |
| T20 | 同一用户 20 个并发请求 | 成功数 **恰好 1**；配额恰好减 1；`Set.size() == 1` |

T19/T20 都是**不变式断言**（数量关系），不是「跑 N 轮撞概率」，
因此在正确实现下恒绿，不引入 Phase 5 遗留的时序依赖问题（P2-24）。

### 12.4 判别力实验（**Claude 亲自跑，不派给 Codex**）

沿用 Phase 5 的方法论：**先证伪，再相信**。每个变体都必须先确认
`target/surefire-reports/com.hmdp.ReservationSubmitTest.txt` 显示该测试类**真的跑过**
（防止编译失败伪装成「零失败 = 无判别力」）。

| 变体 | 改动 | 必须变红的用例 |
| --- | --- | --- |
| F1 | 去掉 `if redis.call('EXISTS', KEYS[1]) == 0` 分支 | T2（**原写 T2/T10/T11 是错的**：T10/T11 不校验文案、实际被 Java 状态闸门拦下，根本走不到 Lua → 这正是返工补 T21 的原因） |
| F2 | 去掉窗口两处判断 | T5、T6（执行器级）。**服务级 T18 不会变红**——Java 用同一个 `now` 先判，见 §3.2 的窗口可达性判定 |
| F3 | 去掉容量判断 | T3、T8、T19 |
| F4 | 去掉 `SISMEMBER` 判断 | T4、T9、T20 |
| F5 | 删掉 Java 前置状态校验 | T12 |
| F6 | 去掉读路径 Redis 覆盖 | T13（**原写 T13 也只对了一半**：T13 是详情路径；分页路径零覆盖 → 这正是返工补 T22 的原因） |
| F7 | **把原子脚本换成「先 GET 再 DECR」的两段式 Java 实现** | T19（必须出现超卖或负配额）→ 实测**打红 T19 与 T20**（20 抢 5 → 9 人成功；同一用户 20 并发 → 13 次成功） |

F7 是这套实验的核心：它证明的不是「某个 if 有用」，而是
**「把校验与扣减拆开就会超卖」**——也就是这个 Phase 之所以要写 Lua 的根本原因。

---

## 13. 验收标准（对应计划 §9）

| 计划要求 | 本设计的落实 |
| --- | --- |
| Lua 原子操作正确 | 单脚本完成 5 步；F7 实验反证「非原子实现会超卖」 |
| Redis 不超卖 | T19 不变式：成功数 == 总名额 − 剩余，且剩余永不为负 |
| 用户不重复预约 | T9/T20：Set 去重，同一用户并发 20 次恰好 1 次成功 |
| Redis Key 统一 | §2.1 两个键，常量集中在 `RedisConstants`，脚本内零字面量 Key |
| 返回码明确 | §3.1 六个互斥整数码 + `ReservationResultCode` 映射 |
| Maven 编译通过 | `mvn clean test-compile` |
| 合理并发测试结果 | T19/T20 + 全部 51 个既有测试零回归 |

---

## 14. 提交计划

单个本地提交（不 push，凭据策略不变）：

```
feat: add atomic redis reservation
```

---

## 15. 决策点（已由用户裁定，2026-10-04）

| 编号 | 决策 | 裁定 |
| --- | --- | --- |
| **D1** | 批次读路径对 `PUBLISHED` 记录优先返回 Redis 实时名额（§7） | **采纳** |
| **D2** | `GET /reservation-batch/{id}` 对学生隐藏 `DRAFT`/`CANCELLED`（延伸 Phase 5 已裁定的 F1 规则） | **采纳** |
| **D3** | `requestId` 与 `reservationId` 合一，只保留 `reservationId`（§5） | **采纳** |

三条均由用户于 2026-10-04 明确裁定采纳，是 §9 改动范围与 §12 测试计划的组成部分，
不再是可选项。D2 的具体落实：非 `LAB_ADMIN` 读取 `DRAFT`/`CANCELLED` 批次时，
`queryBatchById` 返回 `null`（沿用既有「预约批次不存在」的响应口径，不泄露批次是否存在）。

---

## 16. 已知边界（本 Phase 不解决，显式登记）

1. **设备停用竞态**（§4.4a）：不接受把设备状态搬进 Redis，登记给 Phase 8 的对账视角。
2. **已接受请求 vs 关闭/取消**（§4.4b）：抢占原子正确，但「预接受已发生、落库未发生」的
   一致性窗口属于 Phase 7/8。
3. **重试丢失凭证**：客户端在超时后重试会得到「你已预约过该批次」，但拿不到上一次的
   `reservationId`。需要一个「按用户+批次查预约」的读接口才能彻底解决 → Phase 9 的
   「我的预约」列表。本 Phase 登记。
4. **对已约满批次的重复请求不做限流**：每个被拒请求仍会执行一次 Lua。
   计划未要求，且 Redis 单次脚本成本极低；若 Phase 8 认为需要，再引入。
5. **`RedisIdWorker` 内部 `icr:` 前缀硬编码**（P2-1 的另一半）：本 Phase 不动它，
   仅保证新脚本不新增字面量 Key。
6. **单机 Redis，Key 无 hash tag**：计划明确禁止引入 Redis Cluster，故不做集群同槽设计。
7. **关闭/取消后，显示的名额会「跳回去」**（Phase 6 特有的暂态，必须在 Review 时识别为已知行为而非缺陷）：
   Phase 6 只扣 Redis，MySQL 的 `remaining_quota` 到 Phase 7 才由消费者扣减。
   于是总名额 4 的批次约掉 1 个后，`PUBLISHED` 期间读路径显示 3（Redis，见 §7）；
   一旦 `close`，名额键被删除，读路径回落到 MySQL 的 4 —— **数字看起来回涨了**。
   这是 §7 那条「键不存在则回落 MySQL」规则的必然结果，不是超卖或回滚。

   **明确否决「在 close/cancel 时把 Redis 名额回写 MySQL」这个看起来更漂亮的修法**，
   理由不是「超出本 Phase 范围」这种笼统说法，而是一个具体的机制性风险：
   Phase 7 的消费者会**按每笔预约条件扣减** MySQL 的 `remaining_quota`。
   若 close 时先回写 `remaining = Redis 当前值`（该值已经包含了那一笔的扣减），
   而在途的消费者随后又扣一次，就会**重复扣减**。
   回写与 Phase 7 的消费者在语义上会打架，所以此处不做，交由 Phase 8 的对账统一决定口径。
