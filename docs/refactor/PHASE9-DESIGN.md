# Phase 9 技术设计 — 取消与履约（Cancellation & Fulfillment）

> 上游依据：`实验资源预约平台-分阶段重构Phase计划.md` §12
> 本文件覆盖 codex-implementer 工作流的**阶段一（需求分析）/ 阶段二（源码分析）/ 阶段三（技术设计）/ 阶段四（任务拆解）**。
> 阶段五由 Codex 执行；阶段六/七复查结论另出一份 `PHASE9-REVIEW.md`。
> 文中所有标 **[决策]** 的条目为 Claude 自拟判断，**供用户否决**。

---

## 阶段一：需求分析

### Goal

让预约从"只能创建"变成**有完整生命周期**：可查询、可取消、可核验、可判定爽约，且取消时 **MySQL 名额 / Redis 名额 / Redis 占用集合 / 请求账本** 四处状态保持一致。

### Expected Behavior

| # | 行为 | 说明 |
|---|---|---|
| B1 | 查询预约 | 用户查自己的预约（带批次/设备/实验室信息）；管理员查其管辖实验室下的预约 |
| B2 | 取消预约 | 本人取消自己的预约；`RESERVED → CANCELLED`；MySQL `remaining_quota + 1`；Redis 名额 +1 且从占用集合移除 |
| B3 | 重复取消幂等 | 第二次取消返回成功，但**不得**再次 +1 名额、**不得**再动 Redis |
| B4 | 非法状态迁移被拒绝 | `COMPLETED`/`NO_SHOW` 的预约不可取消；非本人在 403 语义下被拒 |
| B5 | 履约核验 | 实验室管理员把 `RESERVED → COMPLETED`（第一版：按预约 id 核验，不做二维码） |
| B6 | 爽约 | 批次结束时间已过且仍未核验的 `RESERVED → NO_SHOW`；**不做**复杂自动任务 |
| B7 | 取消后可重新预约 | 取消释放名额后，同一用户可再次预约同一批次（否则为**名额泄漏**，见 §3.1） |

### Scope（Phase 9 做的）

- `tb_reservation` 增加生命周期状态与"名额已归还"标记（DDL 迁移）
- 唯一键从 `uk_user_batch(user_id,batch_id)` 迁移为**只约束"活跃预约"**的唯一键
- 取消 / 核验 / 爽约三个状态迁移 + 取消的 MySQL 名额归还
- 取消后的 Redis 名额幂等归还与"归还未成功"的补偿重试
- 预约查询（用户侧 + 管理侧）
- 落库前置查重改为**状态感知**（Phase 8 finding F-D 的必修部分）
- 修掉 Phase 8 finding F-B（`@Scheduled` 缺 `initialDelay`，测试上下文启动即跑一轮对账）——**这是 Phase 9 新增第二个定时写任务的前置安全条件**

### Non-goals（Phase 9 明确不做）

- **不删/不改** `reservation.lua`、`reservationRollback.lua`（复用）
- 不改 Phase 8 已交付的对账主流程 `reconcile(limit)` 的行为（新增独立方法，不侵入原方法）
- 不做二维码 / 签到码 / 短信通知 / 支付 / 违约金 / 信用分
- 不做自动候补、排队、名额转让
- 不把 `PROCESSING`/`FAILED` 复制进 `tb_reservation`（那是 `tb_reservation_request` 账本的职责，见 §3.2）
- 不修 Phase 8 finding F-C（`listStale` 的 `LIMIT` 无游标导致饥饿）——非取消链路的阻塞项，留 Phase 10
- 不做前端

### Constraints

- JDK 8；Spring Boot 2.3.12；MyBatis-Plus 3.4.3；MySQL 9.7.1；不改技术选型
- 一个 Phase 一次本地提交，**不 push**
- 不引入微服务 / 新中间件 / 大型 RBAC
- 状态数**克制**：只加 4 个生命周期状态
- Phase 0–8 的 136 个测试必须继续全绿

### Potential Risks

| 风险 | 处理 |
|---|---|
| 取消顺序错误导致超卖 | MySQL 优先 + Redis 后置，且只在 CAS 真的迁移时动 Redis（§3.3） |
| 重复取消把"新预约"的名额误释放 | 同上：Redis 步骤由 CAS 结果守卫 |
| 唯一键迁移影响存量数据 | 迁移脚本含回填与回滚说明；本机表为空（§3.1） |
| 新增定时任务污染测试 | 加 `initialDelayString`，测试期间不触发（§3.6） |
| 落库前置查重改动影响 Phase 8 测试 | 只做"状态感知 + 按预约 id 先判重"，语义对现有用例是超集 |

---

## 阶段二：源码分析

### 2.1 现状：预约只有"出生"，没有"一生"

`tb_reservation` 只有 `id / user_id / batch_id / create_time / update_time`，**没有状态列**。`Reservation` 实体同样只有这字段。也就是说：一行 `tb_reservation` 存在 = 这次预约成功；没有任何方式表达"已取消 / 已核验 / 爽约"。

### 2.2 两本账，各记一半

| 账本 | 表 | 状态枚举 | 职责 |
|---|---|---|---|
| 请求账本 | `tb_reservation_request` | `PRE_ACCEPTED/PUBLISHED/CONFIRMED/UNKNOWN/COMPENSATED` | 记录"一次提交请求"的异步终局 |
| 预约事实 | `tb_reservation` | **无** | 记录"一次真实占用" |

`ReservationRequestStatus.isTerminal()` = `CONFIRMED || COMPENSATED`；`NON_TERMINAL_STATUSES = [PRE_ACCEPTED, PUBLISHED, UNKNOWN]`（`nonTerminalStatuses()` 暴露不可变列表，Phase 9 的新枚举沿用这个写法）。

`ReservationRequest` 是**请求**级：同一次提交只有一个 requestId。`Reservation` 是**占用**级：一次取消会让占用结束，但请求已经终局、不可改。所以生命周期状态**必须**落在 `tb_reservation` 上，不能借用请求账本的状态。

### 2.3 名额在哪里被扣、被还

| 时点 | MySQL `tb_reservation_batch.remaining_quota` | Redis `reservation:batch:quota:{batchId}` | Redis `reservation:batch:users:{batchId}` |
|---|---|---|---|
| 用户提交 | 不变 | `DECR`（`reservation.lua`） | `SADD userId` |
| MQ 消费落库 | `-1`（`AND remaining_quota > 0` 条件扣减） | 不变 | 不变 |
| 落库失败 | 不变 | `INCR` + `SREM`（`reservationRollback.lua`） | `SREM` |
| **取消（本 Phase 新增）** | **`+1`** | **`INCR`** | **`SREM`** |

关键点：**Redis 扣减发生在"提交"时刻，MySQL 扣减发生在"落库"时刻**，两者之间有 MQ 延迟。因此取消时两处都要还，且 MySQL 还名额外必须**同时**完成 `RESERVED→CANCELLED` 的状态迁移（同一事务），否则会出现"预约还在、名额已还"。

### 2.4 已有的可复用能力（不重造）

- **`ReservationQuotaRollbackExecutor.rollback(batchId, userId) -> int`**（`com.hmdp.utils`）
  已有，且**天然幂等**：`SREM` 返回 0 即"本来就未占用"，直接返回 0 不动名额；名额键不存在返回 2。返回码 0/1/2 语义在类注释里写清了。**取消直接用这个，不写第二个 Lua。**
- **`ReservationReconcileServiceImpl.scheduledReconcile()`** 已经是一个带 Redisson 分布式锁的定时薄壳，内部调用纯方法 `reconcile(limit)`；纯方法**可被测试直接调用**，这是 Phase 9 放新定时任务的正确位置。
- **`ReservationBatchServiceImpl.fillRemainingQuotaFromRedis(...)`**：Redis 名额键存在时会**把 Redis 值写回 MySQL**。这条既有行为直接决定了取消失败时的漂移方向（§3.3）。
- **落库事务拆分先例**：`ReservationServiceImpl`（编排 + Redis）与 `ReservationPersistenceServiceImpl`（`@Transactional`，全项目唯一）分离。Phase 9 的 MySQL 事务部分沿用这个拆分（避免自调用绕过代理）。

### 2.5 归属校验与返回约定（新端点必须对齐）

- **鉴权**：`MvcConfig` 注册三个拦截器 —— `RefreshTokenInterceptor`(order 0, `/**`) → `LoginInterceptor`(order 1, 除 upload/user/code/user/login 外全拦) → `RoleInterceptor`(order 2, `/**`)。`RoleInterceptor` 读 `@RequireRole`（方法优先、类兜底），未登录写 **401**、角色不符写 **403**，都是**裸状态码无响应体**。
- **身份来源**：service 层用 `UserHolder.getUser()`（`ThreadLocal<UserDTO>`）取 `id` / `role`，**不通过 Controller 传 userId**。
- **归属失败约定**：`LaboratoryServiceImpl` / `EquipmentServiceImpl` / `ReservationBatchServiceImpl` 统一抛 `ForbiddenException("无权管理该对象")`，且注释明确写了**故意不区分"不存在"与"无权限"**，避免泄露资源是否存在。Phase 9 的取消/查询沿用。
- **异常→HTTP**：`WebExceptionAdvice`（`com.hmdp.config`，`@RestControllerAdvice`）：`BadRequestException`→400、`ForbiddenException`→403、`ServiceUnavailableException`→503、其余 `RuntimeException`→200 + `Result.fail("服务器异常")`。
- **分页约定**：查询 DTO 内自带 `Integer current = 1`（**没有** `PageDTO`），service 里 `new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE)`（=5），Controller 返回 `Result.ok(page.getRecords(), page.getTotal())`。
- **VO 约定**：`*VO implements Serializable` + `@Data` + `@Accessors(chain = true)` + `@EqualsAndHashCode(callSuper = false)`。联表字段靠 Mapper XML（`<sql id="...Columns">` + 复用 `<include>`），返回 `resultType` 直指 VO（例：`ReservationBatchMapper.xml` 三表 left join 取 `equipment_name` / `laboratory_name`）。

### 2.6 阻塞点：`uk_user_batch` 让"取消后可重约"变成名额泄漏

`tb_reservation` 现有 `UNIQUE KEY uk_user_batch(user_id, batch_id)`，**不含状态**。`ReservationPersistenceServiceImpl.persist` 的前置查重也是：

```java
reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
        .eq(Reservation::getUserId, ...).eq(Reservation::getBatchId, ...));   // 不区分状态
if (existingCount > 0) return ReservationPersistResult.DUPLICATE;
```

推论（**这是 Phase 9 必须一起修的硬理由**）：如果只实现"取消时把名额还回去"，用户取消后 Redis 集合标记被 `SREM`、Redis 名额 +1，于是他能**再次通过 Lua 拿到名额**；但落库时前置查重发现旧的（已取消的）行仍在 → 返回 `DUPLICATE`；而 `ReservationMessageConsumer` 对 `DUPLICATE` 的处理是 `markConfirmedSafely + basicAck`。结果：**Redis 名额被永久消耗、账本记为 CONFIRMED、却没有预约事实行**。这是一次实打实的名额泄漏，必须连唯一键一起改，取消才算做对。

### 2.7 已核实的环境事实

- `tb_reservation` 的 `uk_user_batch` 在 `information_schema` 中确认存在（`NON_UNIQUE=0`）
- 四张预约域表当前均 0 行（迁移无存量负担）
- 迁移方式是**手工执行 SQL 脚本**（`src/main/resources/db/phase*.sql`），无 Flyway/Liquibase
- MySQL 服务端 9.7.1，支持 STORED 生成列上的唯一键

### 2.8 阶段二收尾：修正 Phase 8 的一处判据空转

Phase 8 的"禁改清单/零接触"核验里，我把 `RoleInterceptor` 和 `WebExceptionAdvice` 的路径**猜错**了（猜成 `com/hmdp/interceptor/` 与 `com/hmdp/utils/`）。真实路径是：

- `src/main/java/com/hmdp/utils/RoleInterceptor.java`
- `src/main/java/com/hmdp/config/WebExceptionAdvice.java`

对猜错的路径做 `git status --porcelain` 必然是空 → 这两条**判据是空转的**。现已用真实路径复核：两者最后被改动的提交是 `02daa19`（Phase 7），工作区 `git status --porcelain` 全空，故 **"未被 Phase 8 触碰"的结论成立**，但 Phase 8 结论里这两条的证据等级应从"零接触核验通过"降为"事后复核通过"。已记入 `PHASE9-DESIGN.md` 与 memory，Phase 9 的禁改清单一律使用**已存在的真实路径**。

---

## 阶段三：技术设计

### 3.1 [决策] 状态模型与唯一键迁移

**状态只加 4 个**，落在 `tb_reservation.status`：

```
RESERVED  ──取消──▶  CANCELLED
          ──核验──▶  COMPLETED
          ──爽约──▶  NO_SHOW
```

三个终态互不迁移（`CANCELLED` / `COMPLETED` / `NO_SHOW` 均为终态）。

**不把 `PROCESSING` / `FAILED` 复制进这张表**：`tb_reservation` 的行**只在落库成功后才存在**（`persist` 的 insert 是事务最后一步），所以这张表里不存在"处理中"或"落库失败"的行——那两个状态天然属于 `tb_reservation_request`。硬塞进来会造成两本账记同一件事，正是要避免的过度设计。

**唯一键迁移**（新 DDL `src/main/resources/db/phase9_reservation_lifecycle.sql`）：

```sql
ALTER TABLE `tb_reservation`
  ADD COLUMN `status` varchar(16) NOT NULL DEFAULT 'RESERVED' COMMENT '生命周期状态',
  ADD COLUMN `quota_released` tinyint NOT NULL DEFAULT 0 COMMENT '取消时名额是否已归还Redis',
  ADD COLUMN `active_flag` tinyint
      GENERATED ALWAYS AS (CASE WHEN `status` = 'CANCELLED' THEN NULL ELSE 1 END) STORED
      COMMENT '仅约束活跃预约：已取消为NULL，唯一键因此不拦同用户同批次再次预约',
  ADD KEY `idx_status_quota_released` (`status`, `quota_released`);

ALTER TABLE `tb_reservation`
  DROP INDEX `uk_user_batch`,
  ADD UNIQUE KEY `uk_user_batch_active` (`user_id`, `batch_id`, `active_flag`);
```

原理：唯一索引里 **NULL 互不相等**，所以同一 `(user_id,batch_id)` 可以有任意多条 `CANCELLED` 行，但**最多一条**活跃行。这样"取消后可重约"由数据库约束保证，`persist` 的 INSERT 路径完全不用改结构。

回滚（写入脚本注释）：`DROP INDEX uk_user_batch_active` → 删三列 → `ADD UNIQUE KEY uk_user_batch(user_id,batch_id)`（**回滚前必须先删掉同键的已取消行**，否则重建旧唯一键会失败——脚本注释里写明）。

**为什么不做"原地复用同一行（改回 RESERVED）"**：那样 `reservationId` 会指向两次不同的预约，一条延迟到达的重复取消请求可能把**新**预约取消掉。每次预约生成新的 `reservationId`（`redisIdWorker.nextId("reservation")`）、每次占用一行，才能让"取消"天然只作用于它那一行。代价是多几行历史数据，值得。

### 3.2 [决策] 状态枚举与实体

新增 `com.hmdp.enums.ReservationStatus`，写法对齐 `ReservationRequestStatus`（静态不可变集合 + `isTerminal()`）：

```java
public enum ReservationStatus { RESERVED, CANCELLED, COMPLETED, NO_SHOW;
    public boolean isActive()   { return this == RESERVED; }
    public boolean isTerminal() { return this != RESERVED; }
    public static List<ReservationStatus> activeStatuses() { ... }   // 供"状态感知查重"
}
```

`Reservation` 实体增加 `status`（`ReservationStatus`）与 `quotaReleased`（`Boolean`）。MyBatis-Plus 在 insert 时对 null 字段不写列，因此 **DDL 的 `DEFAULT 'RESERVED'` 是兜底**；同时要求消费端显式 `setStatus(RESERVED)`，让语义不依赖 DDL 默认值。

### 3.3 [决策] 取消的执行顺序（本 Phase 最关键的决策）

```
① 读预约（不存在或 userId 不匹配 → ForbiddenException("无权取消该预约")，不区分二者）
② 事务内：
     a. CAS  UPDATE tb_reservation SET status='CANCELLED'
             WHERE id=? AND user_id=? AND status='RESERVED'
        - 影响 1 行 → 迁移成功，继续 b
        - 影响 0 行 → 回读状态：
            CANCELLED              → 幂等成功，直接返回（**不动 MySQL 名额、不动 Redis**）
            COMPLETED / NO_SHOW    → BadRequestException("当前状态不可取消")
     b. UPDATE tb_reservation_batch SET remaining_quota = remaining_quota + 1
             WHERE id=? AND remaining_quota < total_quota
        （与 a 同一事务：不允许出现"预约还在、名额已还"）
     c. 顺带校验窗口：now > batch.endTime → BadRequestException（见 3.4）
③ 事务提交后（**仅当 a 真的迁移了**）：
     int code = rollbackExecutor.rollback(batchId, userId);
     code ∈ {0,1,2} → UPDATE tb_reservation SET quota_released=1 WHERE id=? AND quota_released=0
     rollback 抛异常 → 保持 quota_released=0，交给 §3.5 的补偿重试，接口仍返回成功
```

**为什么 MySQL 优先、Redis 后置**：两个方向的失败后果不对称。

- MySQL 先成功、Redis 失败 → MySQL 有 1 个空位，Redis 仍紧张（少 1）。Redis 是准入闸门（`reservation.lua` 判的是 Redis 名额），**Redis 更紧 ⇒ 不会超卖**；代价是用户此时既不能重约（集合标记还在）又白还了一个 MySQL 名额 —— 保守方向，可补偿。
- Redis 先成功、MySQL 事务回滚 → Redis 宽松、MySQL 紧张。而 `fillRemainingQuotaFromRedis` 会把**宽松的 Redis 值写回 MySQL**，同时那行预约仍是 `RESERVED` ⇒ 同一用户既持有预约又释放了名额 ⇒ **超卖**。

**为什么 Redis 步骤必须由 CAS 结果守卫**：若不过守卫，一条延迟到达的重复取消（用户双击、或重试）会在 `SREM` 时把该用户**后来新预约**的集合标记删掉，等于凭空多出一个名额。这一条是幂等性的核心，不是可选的优化。

注意与既有对账的**顺序差异**：`ReservationReconcileServiceImpl.reconcile` 是"先 `rollbackExecutor.rollback` 再 `casStatus(COMPENSATED)`"（先还名额再推进终态，失败则保持非终态以便下轮重试）。取消**不能**照抄：取消要求"状态迁移 + MySQL 名额归还"原子，而 Redis 是可补偿的外部副作用，所以顺序必须是 MySQL 事务 → Redis，靠 `quota_released` 标记而不是靠"保持非终态"来做重试驱动。

### 3.4 [决策] 取消窗口

允许取消的条件：`status = RESERVED` 且 `now <= batch.endTime`。

- 上限取 `endTime` 而不是 `startTime`：已经开始的场次，用户仍可能确实用不了了；卡在 `startTime` 反而逼出无意义的爽约记录。
- 必须有上限：没有上限的话，用户可以在爽约之后补一次取消来**规避 NO_SHOW**，`NO_SHOW`（9.4）就失去了意义。
- 超过窗口 → `BadRequestException("预约已结束，无法取消")`。
- 备选（供否决）：把上限收紧到 `batch.startTime`。

### 3.5 [决策] 归还失败的补偿重试（`quota_released`）

残余漂移（MySQL 已 `CANCELLED` 且名额 +1，Redis 归还失败）方向是保守的**不超卖**，但会把用户卡住（集合标记还在 ⇒ 不能重约）。处理：

在 `ReservationReconcileServiceImpl` 中**新增独立方法**（不改 `reconcile(limit)` 一行）：

```java
/** 重试取消时未成功的 Redis 名额归还。返回本轮处理条数。 */
public int releasePendingQuotas(int limit);
```

挑选 `status='CANCELLED' AND quota_released=0 ORDER BY update_time ASC, id ASC LIMIT ?`（**沿用 Phase 8 F-A 的并列打破纪律**），逐条调 `rollbackExecutor.rollback(...)`，返回码 0/1/2 都视为终局（`2` = 名额键已随批次关闭/取消被删除，无需归还）并置 `quota_released=1`；抛异常则留在 0 等下一轮。由 `scheduledReconcile()` 在同一把分布式锁内、`reconcile(limit)` 之后调用；方法本身**纯、可测**。

**为什么用标记列而不是"只打日志"**：计划里的验收标准是"Redis/MySQL 名额恢复一致"，纯 best-effort 日志给不出这个保证；一个 `tinyint` 列 + 复用既有定时壳是成比例的，不构成过度设计。**供否决**：若认为不值，则降级为"只记日志"+ 在 Review 里登记为已知边界。

### 3.6 [决策] 爽约：一个集合式 UPDATE，不建新任务

不做"复杂自动任务"，只在既有定时壳里加一个方法：

```java
/** 批次已结束且仍未核验的预约置为 NO_SHOW。返回影响行数。 */
public int markNoShows(int limit);
```

```sql
UPDATE tb_reservation r JOIN tb_reservation_batch b ON r.batch_id = b.id
SET r.status = 'NO_SHOW'
WHERE r.status = 'RESERVED' AND b.end_time < #{now} AND b.status <> 'CANCELLED'
LIMIT #{limit}
```

- **不改名额**：爽约意味着名额已被合法占用，不归还。
- **天然无饥饿**：被更新的行立刻离开谓词，与 Phase 8 F-C（`listStale` 的 `LIMIT` 无游标导致同批陈旧行垄断每轮）不同——这是集合式 UPDATE 相对"逐行扫 + 分页"的优势，值得在 Review 里点明。
- 排除 `b.status='CANCELLED'`：整批被管理员取消时，不该给用户记爽约。

### 3.7 [决策] F-B 必修：给定时任务加 `initialDelay`

`SchedulingConfig` 无条件 `@EnableScheduling`，而 `@Scheduled(fixedDelayString=...)` 没有 `initialDelay` ⇒ **每个测试上下文启动即跑一轮对账**（Phase 8 finding F-B）。Phase 9 往同一个壳里加了第二个写任务（`markNoShows` 会 UPDATE `tb_reservation`），不修 F-B 就会在无关测试里改数据。修法一行：

```java
@Scheduled(fixedDelayString   = "${hmdp.reservation.reconcile-interval-ms:300000}",
           initialDelayString = "${hmdp.reservation.reconcile-initial-delay-ms:300000}")
```

初值 5 分钟：测试全程（单类约 70s）永不到点，生产等价于"启动 5 分钟后开始对账"，无损失。这让 Phase 9 的新定时任务在测试里天然安静，也让 F-B 从"登记项"变成"已修"。

### 3.8 [决策] 落库前置查重改为状态感知（F-D 必修部分）

`ReservationPersistenceServiceImpl.persist` 的查重改成两步，**先按预约 id 判重、再按业务唯一性判重**：

```java
// 1) 同 reservationId 已存在 **且归属相同（同 user 且同 batch）** ⇒ 真正的重复投递
//    （DLQ 重放等），幂等返回 DUPLICATE
//    （Phase 8 F-D：原实现只看 (userId,batchId)，会把"账本里另一个 requestId 的既有行"
//     误判成自己的重复，从而把一条其实没落库的请求标成 CONFIRMED）
Reservation existing = reservationMapper.selectById(reservation.getId());
if (existing != null
        && existing.getUserId().equals(reservation.getUserId())
        && existing.getBatchId().equals(reservation.getBatchId())) return DUPLICATE;
//    ↑ 「归属相同」这个限定条件是**必须的**：reservationId 全局唯一，同 id 但归属不同的行
//      是异常的主键冲突，不能当成重复投递静默 ACK —— 必须继续走到 insert 由主键约束抛出，
//      交给消费端既有的 catch(DuplicateKeyException) 进死信队列，否则 DLQ 上不留任何痕迹。
//      （本限定条件在 Phase 9 首轮设计里漏写，导致实现把它套用到跨用户 id 冲突上并把
//        Phase 8 的 DLQ 用例改写成 ACK；见 PHASE9-REVIEW.md F-9C。）

// 2) 业务唯一性：同用户同批次的**占用唯一键槽位**的预约只能有一条（已取消的不算）
//    判据必须与 tb_reservation.active_flag 生成列严格一致：
//    CASE WHEN status = 'CANCELLED' THEN NULL ELSE 1 END
//    ⇒ 占用集合 = {RESERVED, COMPLETED, NO_SHOW}，即 ReservationStatus.occupyingStatuses()
//    （首轮误用 activeStatuses()=[RESERVED]，漏掉 COMPLETED/NO_SHOW，见 F-9D）
Integer existingCount = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
        .eq(Reservation::getUserId,  ...)
        .eq(Reservation::getBatchId, ...)
        .in(Reservation::getStatus, ReservationStatus.occupyingStatuses()));
if (existingCount > 0) return DUPLICATE;
```

第 1 步修掉 F-D 的可达路径（DLQ 重放时按 `reservationId` 正确识别为幂等重复）；第 2 步与 §3.1 的新唯一键语义对齐，是"取消后可重约"能真正生效的一半（另一半是 DDL）。

**F-D 残留**：仍存在一条窄路径 —— 不同 `reservationId`、同 `(user,batch)` 且已有一条活跃行时，第二个请求会被记为 `CONFIRMED` 而它自己那行并不存在。要走到这条路径，必须"Redis 集合标记在 DB 行仍活跃时已丢失/过期"，而 `reservationRollback.lua` 的 `SREM` 只在补偿时发生。本 Phase **不扩大**处理范围，只登记，理由写在 Review 里。

### 3.9 [决策] 接口与查询

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/reservation` | 登录 | 已有（提交），**不改** |
| GET | `/reservation/page` | 登录 | 我的预约分页（`ReservationQueryDTO`：`batchId`、`status`、`current`） |
| GET | `/reservation/{id}` | 登录 | 我的预约详情；非本人 → `ForbiddenException` |
| GET | `/reservation/manage/page` | `@RequireRole(LAB_ADMIN)` | 管辖实验室下的预约分页（`ReservationQueryDTO` 复用） |
| POST | `/reservation/{id}/cancel` | 登录 | 本人取消 |
| POST | `/reservation/{id}/verify` | `@RequireRole(LAB_ADMIN)` | 核验：`RESERVED → COMPLETED` |

- 新增 `ReservationVO`（继承约定：`Reservation` 字段 + `batch`/`equipment`/`laboratory` 名称 + `startTime`/`endTime`/`bookingStartTime`/`bookingEndTime`）与 `ReservationMapper.xml`（照 `ReservationBatchMapper.xml` 的 `<sql id="...Columns">` + 三表 left join 写法，`ORDER BY r.id DESC`）。
- 管理侧按 `l.admin_user_id = #{adminUserId}` 收窄（与 `selectReservationBatchPage` 的 `adminUserId` 同款）。
- 核验归属校验复用 `laboratoryService.assertManageable(batch.equipment.laboratoryId)`，与 `ReservationBatchServiceImpl.assertEquipmentManageable` 一致。
- 核验窗口：`now >= batch.startTime` 才允许核验（不能提前签到）；**不设上界**，允许管理员在事后补核验。
- `NO_SHOW` **不提供用户/管理端点**，只由 §3.6 的定时方法产生 ⇒ 状态机不会被端点绕开。

### 3.10 事务边界与调用结构

```
ReservationController
  └─ ReservationServiceImpl            （编排：鉴权上下文、窗口校验、调用 tx 服务、事务后 Redis）
       ├─ IReservationLifecycleService （@Transactional：状态 CAS + MySQL 名额，新增）
       └─ ReservationQuotaRollbackExecutor（事务后，幂等 Redis 归还，复用）

ReservationReconcileServiceImpl.scheduledReconcile()
  ├─ reconcile(limit)          （Phase 8，行为不变）
  ├─ releasePendingQuotas(limit)（新增，§3.5）
  └─ markNoShows(limit)         （新增，§3.6）
```

`IReservationLifecycleService` 新增（新文件，避免自调用绕过 `@Transactional` 代理 —— 沿用 `ReservationPersistenceServiceImpl` 的既有拆分）：

```java
ReservationCancelOutcome cancel(Long reservationId, Long userId, LocalDateTime now);
boolean complete(Long reservationId, LocalDateTime now);   // RESERVED→COMPLETED
```

`ReservationCancelOutcome { TRANSITIONED, ALREADY_CANCELLED, NOT_CANCELLABLE, NOT_FOUND }`（同包 `com.hmdp.enums`），把"该不该动 Redis"的判据从异常流里拿出来，变成可断言的返回值。

### 3.11 兼容性

- `reservation.lua` / `reservationRollback.lua` **零修改**
- `ReservationBatchServiceImpl.publish/close/cancel` **零修改**（`close`/`cancel` 删 Redis 键的行为保持不变；`rollback` 返回码 2 已是这个场景的正确语义）
- `reconcile(limit)` **零修改**，Phase 8 对账测试预期不变
- `ReservationMessageConsumer` 只允许**一处**改动：构造 `Reservation` 时显式 `setStatus(ReservationStatus.RESERVED)`（行为等价，DDL 默认值兜底）
- Phase 0–8 的 136 个测试必须继续全绿；新测试只增不减

---

## 阶段四：任务拆解

> 每个任务都是"改什么 + 不许改什么 + 怎么验"。Codex 负责 How。

### T1 — DDL 迁移
**做什么**：新增 `src/main/resources/db/phase9_reservation_lifecycle.sql`，内容为 §3.1 的两条 `ALTER`，脚本内注释写明回滚步骤与"回滚前需先删掉已取消行"的前提；在本机 `hmdp` 库执行并确认 `information_schema` 中 `uk_user_batch` 已消失、`uk_user_batch_active` 与 `active_flag` 已存在。
**不许改**：`phase7_reservation.sql`、`phase8_consistency.sql` 一律不动。
**验收**：同一 `(user_id,batch_id)` 可插入多条 `status='CANCELLED'`，但第二条活跃行被唯一键拒绝。

### T2 — 状态枚举与实体
**做什么**：新增 `ReservationStatus`（§3.2，对齐 `ReservationRequestStatus` 的 helper 风格）；`Reservation` 加 `status` / `quotaReleased`。
**验收**：`isActive()/isTerminal()/activeStatuses()` 有单元测试；`mvn -q clean test-compile` 通过。

### T3 — 生命周期事务服务
**做什么**：新增 `IReservationLifecycleService` + `ReservationLifecycleServiceImpl`（`@Transactional`）与 `ReservationCancelOutcome`；实现 §3.3 的事务内部分（①②a ②b ②c）与 `complete`（`RESERVED→COMPLETED`，且 `now >= batch.startTime`）。
**不许改**：`ReservationPersistenceServiceImpl`（T5 单独改）、`ReservationServiceImpl`。
**验收**：`cancel` 对 `COMPLETED`/`NO_SHOW`/不存在/非本人分别返回 `NOT_CANCELLABLE`/`NOT_FOUND`；对已 `CANCELLED` 返回 `ALREADY_CANCELLED` 且**批次 `remaining_quota` 不变**。

### T4 — 取消编排 + Redis 归还
**做什么**：`IReservationService` 增加 `cancel(Long)`、`queryMyPage(...)`、`queryMine(Long)`、`queryManagePage(...)`、`verify(Long)`；`ReservationServiceImpl` 实现取消编排（§3.3 的 ①③），Redis 步骤严格由 `TRANSITIONED` 守卫，成功/失败分别置 `quota_released`。
**不许改**：`submit` 的既有流程与 `PublishOutcome` 语义。
**验收**：`TRANSITIONED` 时调 `rollbackExecutor`；`ALREADY_CANCELLED` 时**一次都不调**。

### T5 — 落库查重状态感知（F-D 必修部分）
**做什么**：按 §3.8 改 `ReservationPersistenceServiceImpl.persist` 的两步判重；`ReservationMessageConsumer` 显式 `setStatus(RESERVED)`。
**不许改**：`persist` 的"先扣名额再 insert"顺序、`QUOTA_CONFLICT` 语义、消费端对 `DUPLICATE`/`QUOTA_CONFLICT`/异常的既有分支。
**验收**：已取消的旧行不再导致 `DUPLICATE`；`reservationId` 重复投递仍返回 `DUPLICATE`；Phase 8 的落库/MQ 测试全绿。

### T6 — 查询（用户侧 + 管理侧）
**做什么**：`ReservationVO`、`ReservationQueryDTO`、`ReservationMapper.xml`（三表 join）、`ReservationMapper` 的分页/详情方法；`queryMyPage`/`queryMine`/`queryManagePage` 实现；Controller 六个端点（§3.9）。
**不许改**：`ReservationBatchMapper.xml`。
**验收**：`/reservation/page` 只返回本人的；`/reservation/manage/page` 只返回管辖实验室下的；分页 `current` 与 `DEFAULT_PAGE_SIZE` 对齐既有约定。

### T7 — 核验
**做什么**：`verify` 的编排 + `@RequireRole(LAB_ADMIN)` + `assertManageable` 归属校验 + `POST /reservation/{id}/verify`。
**验收**：非管辖管理员 403/`ForbiddenException`；`COMPLETED` 重复核验幂等；`CANCELLED`/`NO_SHOW` 核验被拒。

### T8 — 定时补偿与爽约 + F-B
**做什么**：`ReservationReconcileServiceImpl` 新增 `releasePendingQuotas(int)` 与 `markNoShows(int)`（纯方法，可测），`scheduledReconcile()` 在同一把锁内按 §3.10 顺序调用；`@Scheduled` 加 `initialDelayString`（§3.7）。
**不许改**：`reconcile(int)` 的签名、返回结构与内部逻辑。
**验收**：`releasePendingQuotas` 对 `quota_released=0` 的已取消行重试并把 0/1/2 都置 1；`markNoShows` 只动 `end_time < now` 且批次非 `CANCELLED` 的 `RESERVED` 行、**不改任何名额**；测试期间无定时任务触发。

### T9 — 测试
**做什么**：集成测试覆盖 B1–B7 与验收标准，尤其是：重复取消不重复 +1（MySQL 与 Redis 双向断言）、取消后同用户可重约（唯一键 + 落库前置查重）、非法迁移被拒、`markNoShows` 不改名额、`releasePendingQuotas` 幂等。判别力要求：每个关键断言都要有对应的"改坏即红"的变体（Claude 侧执行，见 §5）。
**必须**：`mvn -B test` 全绿且总数 **≥ 136**。

---

## 5. 验收与验证计划（Claude 侧执行）

| 项 | 判据 |
|---|---|
| 全量测试 | `mvn -B test` BUILD SUCCESS，`Failures: 0`、`Errors: 0`，总数 ≥ 136 |
| 关键行为 | 重复取消：MySQL `remaining_quota` 与 Redis 名额**各只 +1 一次** |
| 一致性 | 取消后 Redis 名额 = MySQL `remaining_quota`；`quota_released=1` |
| 可重约 | 取消后同 `(user,batch)` 能落库成功（新唯一键 + 状态感知查重） |
| 非法迁移 | `COMPLETED`/`NO_SHOW` 取消、`CANCELLED`/`NO_SHOW` 核验均被拒 |
| 判别力 | 对"取消不 +1 / 重复取消 +1 两次 / 去掉 CAS 守卫 / 去掉状态感知查重 / 去掉 `initialDelay`"逐条做变体实验，确认目标用例变红且**只**目标用例变红；复原后 SHA-256 字节一致 |
| 禁改清单 | `reservation.lua`、`reservationRollback.lua`、`ReservationBatchServiceImpl`、`reconcile(int)` 用 `git status --porcelain`（**真实路径**）+ SHA-256 双重核验 |
| 环境复原 | 四张预约域表 0 行；RabbitMQ 两队列 `messages=0, consumers=0`；`icr:reservation:*` 设计计数器**不删** |
| 提交 | 一次本地提交，**不 push** |

## 6. 结论与待否决事项

本设计的三处关键判断，供用户否决：

1. **唯一键迁移到生成列**（§3.1）—— 取消功能想正确，这一条是硬依赖（否则泄漏名额）；代价是对 Phase 7 表做 DDL。
2. **`quota_released` 标记 + 补偿重试**（§3.5）—— 让"名额恢复一致"成为可保证而非 best-effort；若要"不过度设计"，可降级为只记日志并在 Review 登记为已知边界。
3. **F-B / F-D 纳入 Phase 9**（§3.7 / §3.8）—— F-B 是新增第二个定时写任务的**前置安全条件**，F-D 是 `persist` 查重改动的同一处代码；F-C 仍留 Phase 10。
