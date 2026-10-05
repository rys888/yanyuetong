# Phase 8 · T7 任务包（DLQ 治理 API）

> 派发方式（硬性约定）：MCP `codex`，`model = "gpt-5.6-sol"`，`effort = "high"`，
> `sandbox = "workspace-write"`，`cwd = /Users/eason/Desktop/hm-dianping`。
> 集成测试由 Claude 侧执行；Codex 沙箱连不上本机 MySQL/Redis/RabbitMQ，**不得声称跑过集成测试**。

## Goal

交付计划 §8.8 的 DLQ 治理 API：**分页查询 / 单条详情 / 人工重放** 三个端点（全部 `LAB_ADMIN`），
并把设计 §6.2 的**七步重放前置校验**落成可验证代码。

## Context

项目：Spring Boot 2.3.12 + Spring AMQP 2.2.18 + JDK 8 + MyBatis-Plus 3.4.3 的「高校实验资源预约平台」。
T6 已交付 DLQ 台账（`tb_reservation_dlq`、`ReservationDlq`、`ReservationDlqStatus`＝`NEW/REPLAYED/DISCARDED`、
`ReservationDlqMapper`（裸 `BaseMapper`）、`mq/ReservationDlqConsumer`、`IReservationDlqService`）
并已验收（`ReservationMqTest` 21 条全绿）。本任务是**增量**：台账的既有两法（`extractReservationId`、
`recordDeadLetter`）与消费者**语义不得改变**。

设计 §6.2 的七步校验原文（**这是本任务的功能规格**）：

1. 台账 `status == NEW`（已重放 / 已丢弃的不再重放）；
2. `replay_count < MAX_REPLAY(3)`；
3. payload 能解析出 `reservationId`；解析失败 → 标 `DISCARDED`，说明"无法重放"；
4. **幂等校验**：`tb_reservation` 已有该 id → 标 `DISCARDED`，返回"已落库，无需重放"；
5. 账本 `status == COMPENSATED` → 名额已归还 → 标 `DISCARDED`；
6. 重新投递到主队列（**带 confirm 校验，与生产者同款**）；
7. 成功 → `replay_count + 1`，`status = REPLAYED`。

### 已核对的现成事实（不要重复造）

- 分页约定：查询 DTO 用 `current`（**不是** `page/size`），页大小固定取
  `SystemConstants.DEFAULT_PAGE_SIZE`；Service 返回 `IPage<VO>`；Controller 返回
  `Result.ok(page.getRecords(), page.getTotal())`。参照 `ReservationBatchController#queryBatchPage`
  与 `ReservationBatchServiceImpl#queryPage`。
- CAS 惯用法：`mapper.update(null, new LambdaUpdateWrapper<X>().eq(...).in(...).set(...)) == 1`
  （见 `ReservationRequestServiceImpl#casStatus`）。
- 生产者确认投递的写法（**照此形状**，`ReservationServiceImpl#publishReservationMessage`）：
  `CorrelationData correlationData = new CorrelationData(String.valueOf(id));`
  `rabbitTemplate.convertAndSend(RESERVATION_EXCHANGE, RESERVATION_CREATE_ROUTING_KEY, dto, correlationData);`
  `CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);`
  → `!confirm.isAck()` 记 `CONFIRM_NACK`；`correlationData.getReturnedMessage() != null` 记 `CONFIRM_RETURN`。
- 主消费者 `ReservationMessageConsumer` 的入参是 `@Payload ReservationMessageDTO messageDTO`，
  靠 **Jackson 转换器 + `__TypeId__` 头**反序列化 —— 台账只存 body，**没有 headers 列**。
- 主消费者已有前置检查：账本 `COMPENSATED` → 拒绝落库并 ACK（T4 交付）；落库走
  `ReservationPersistenceServiceImpl#persist`，已有 `(userId,batchId)` 存在的 `DUPLICATE` 分支。
- `application.yaml` 的 `hmdp.reservation` 下已有 `reconcile-interval-ms/threshold-ms/limit`，
  **尚无 `dlq-max-replay`**。
- `@RequireRole` 由 `RoleInterceptor` 执行；无 token → 401，角色不符 → 403；token 是 Redis 哈希
  `login:token:<token>`，请求头名 `authorization`（见 `RefreshTokenInterceptor`）。

## Scope

**新增**：
- `src/main/java/com/hmdp/dto/ReservationDlqQueryDTO.java`（`status`、`current` 默认 1）
- `src/main/java/com/hmdp/vo/ReservationDlqVO.java`
- `src/main/java/com/hmdp/vo/ReservationDlqReplayVO.java`
- `src/main/java/com/hmdp/controller/ReservationDlqController.java`
- `src/test/java/com/hmdp/ReservationDlqGovernanceTest.java`

**修改（仅这两处，且只做增量）**：
- `src/main/java/com/hmdp/service/IReservationDlqService.java`（新增 3 个方法声明）
- `src/main/java/com/hmdp/service/impl/ReservationDlqServiceImpl.java`（实现新方法；既有两法语义不变）
- `src/main/resources/application.yaml`（`hmdp.reservation` 下新增 `dlq-max-replay: 3`）

**零触碰（验收会比对 SHA-256）**：
- `src/main/java/com/hmdp/config/RabbitMQConfig.java`、`src/main/java/com/hmdp/mq/ReservationMessageConsumer.java`、
  `src/main/java/com/hmdp/service/impl/ReservationServiceImpl.java`、`src/main/java/com/hmdp/config/WebExceptionAdvice.java`
  （**派发时误写为 `utils/`，真实路径是 `config/`**）、
  `src/main/java/com/hmdp/config/MvcConfig.java`、`src/main/java/com/hmdp/utils/RoleInterceptor.java`、
  `src/main/resources/reservation.lua`、`src/main/resources/db/phase8_consistency.sql`（表结构不改）、
  `src/test/java/com/hmdp/ReservationMqTest.java`（**回归基线，一个字都不许改**）
- 不引入任何新 Maven 依赖；不改 `pom.xml`。

## Constraints（本设计的直接裁定，实现必须照此）

- **C1（可重放性判据）**：步 3 的判据是 **payload 能解析出 `reservationId`、`userId`、`batchId` 三个数字字段**，
  缺任一 → 标 `DISCARDED`，原因码 `PAYLOAD_NOT_REPLAYABLE`。理由：台账 payload 可能已被 60000 字节截断
  （截断后不是合法 JSON），且"只有 reservationId"的报文重投回主队列后必然再次死信 —— 每次重放都会
  **新增一行台账**（`replay_count` 只在原行累加，新行从 0 开始），形成人工可无限重复的堆积。
- **C2（怎么投递）**：把 payload 用注入的 `ObjectMapper` 解析成 `com.hmdp.mq.ReservationMessageDTO`，
  再用 `rabbitTemplate.convertAndSend(RESERVATION_EXCHANGE, RESERVATION_CREATE_ROUTING_KEY, dto, correlationData)`
  重投。**不得手工拼 `__TypeId__` 头**：台账没有 headers 列，且把 DTO 包名硬编码进业务代码会在改名时静默失效；
  交给转换器就能自动带上正确消息属性。
- **C3（列表不返回 payload）**：分页用 `LambdaQueryWrapper.select(...)` **只选**
  `id/reservation_id/failure_reason/replay_count/status/create_time/update_time`，`payload` 字段保持 `null`
  （单条 payload 最长 60000 字符，一页 5 条就是 300KB）；**详情返回完整 payload**。此约定写进 VO 的类注释。
- **C4（丢弃原因留痕）**：凡因步 3/4/5 置 `DISCARDED`，把原因码**追加**到 `failure_reason`：
  新值 = `<原值> | DISCARD:<code>`，总长截断到 **512**，且**原值必须完整保留为前缀**（审计不丢）。
  code ∈ `PAYLOAD_NOT_REPLAYABLE` / `ALREADY_PERSISTED` / `COMPENSATED`。
- **C5（步 7 用乐观 CAS）**：`update(null, new LambdaUpdateWrapper<ReservationDlq>()
  .eq(id).eq(status, NEW).eq(replayCount, <预检读到的值>).set(status, REPLAYED)
  .set(replayCount, <预检值 + 1>)) == 1`；返回 0 即"并发重放"，返回拒绝。
  **不要**做成"先占位改状态、投递失败再回退"：回退本身可能失败，会把台账留成"看起来已重放但其实没投递"的
  更坏状态；现方案最坏情况只是多发一条消息，由消费端幂等兜底。
- **C6（投递失败不改台账）**：confirm NACK / Return / 超时 / 异常 → 台账**保持 `NEW`、`replay_count` 不变**，
  返回拒绝原因（码：`REPLAY_CONFIRM_NACK` / `REPLAY_CONFIRM_RETURN` / `REPLAY_PUBLISH_UNKNOWN`），
  并 `log.error` 记录。§6.2 步 7 只在**成功**时 +1。
- **C7（无事务）**：`replay` 方法**禁止** `@Transactional` —— 内部有最长 5 秒的 broker 确认等待，
  且每次 DB 写都只是单条 CAS UPDATE（与设计 D15 同一纪律）。
- **C8（HTTP 形状）**：三端点均 `@RequireRole(UserRole.LAB_ADMIN)`。返回一律用既有 `Result`：
  成功 `Result.ok(data)`；拒绝（含"记录不存在"）用 `Result.fail(原因文案)`（HTTP 200，`success=false`）。
  **不新增异常类型、不改 `WebExceptionAdvice`**。重放端点形状：
  `ReservationDlqReplayVO { boolean replayed; String reason; ReservationDlqVO record; }`
  → 控制器 `return result.isReplayed() ? Result.ok(result.getRecord()) : Result.fail(result.getReason());`
- **C9（账本缺失不阻止重放）**：与主消费者"账本不存在仍然继续落库"的既有姿态一致；
  账本查询异常按"未知"处理并 `log.error`，**不**因此拒绝（否则一次 DB 抖动会让管理员无法重放）。
- **C10（顺序即规格）**：七步的**执行顺序**必须与 §6.2 一致，步 1–5 全部在**投递之前**完成；
  拒绝时立刻返回，不得先投递再校验。

## Acceptance Criteria

- AC1 `export JAVA_HOME=/Users/eason/Library/Java/JavaVirtualMachines/corretto-1.8.0_504/Contents/Home && mvn -q clean test-compile` 退出码 0。
- AC2 `ReservationDlqGovernanceTest` 的下列用例**全部通过**（Claude 侧跑；你只需保证代码与断言语义正确）：
  1. 分页按 `create_time DESC`、`status` 过滤生效、**列表项 `payload` 为 null**、`total` 正确；
  2. 详情返回**完整 payload** 与 `failure_reason`；
  3. 不存在的 id → 拒绝；`status != NEW` → 拒绝且行不变；
  4. `replay_count == 3` → 拒绝且行仍为 `NEW/3`（次数上限）；
  5. **成功重放**：拒绝条件都不满足的死信 → 投递 → 主消费者真落库 → 台账 `REPLAYED` 且 `replay_count == 1`；
  6. **I11（已落库不重放）**：`tb_reservation` 已有该 id → 拒绝，台账 **`DISCARDED` 且 `replay_count == 0`**，
     `tb_reservation` 行数**仍为 1**，**且 `reservation.create.queue` 与 `reservation.create.dlq` 均为空**
     （证明"没有投递"，不是"投递了但恰好没重复"）；
  7. 账本 `COMPENSATED` → 拒绝、`DISCARDED`、`replay_count == 0`、无投递；
  8. payload 不可重放（缺 `userId`/`batchId`，或截断的 JSON）→ `DISCARDED`，且
     `failure_reason` **以原值为前缀**、含 `DISCARD:PAYLOAD_NOT_REPLAYABLE`；
  9. **权限守卫**（MockMvc）：无 `authorization` 头 → 401；带 `LAB_ADMIN` 会话（Redis 哈希
     `login:token:<token>` 种 `id`/`role`/`nickName`）→ 200。
- AC3 既有 `ReservationMqTest` **21 条保持全绿**（`ReservationMqTest.java` 零改动即是最强保证）。
- AC4 零触碰清单内文件的 SHA-256 与派发前一致。
- AC5 `replay` 无 `@Transactional`；三个端点方法上均有 `@RequireRole(UserRole.LAB_ADMIN)`。

## Validation

- 只允许：`export JAVA_HOME=.../corretto-1.8.0_504/Contents/Home && mvn -q clean test-compile`。
- **你无法运行集成测试**（沙箱连不上本机 MySQL/Redis/RabbitMQ），**不要声称跑过**。
- 汇报：改动/新增文件清单 + 每个文件关键实现要点 + `test-compile` 结果 +
  你对"七步顺序与三处拒绝码"的自查 + 仍存在的不确定性。

## Review 要点（Claude 侧验收）

0. **AC2 用例 6/7 的队列断言有竞态，验收时必须先确认容器状态**：若主监听容器在 `replay()` 期间**仍在运行**，
   则"两个队列皆空"这句断言**不具判别力** —— 变体（去掉步 4/步 5 校验）真的投递后，主消费者会立刻把消息
   消费掉（步 4 场景走 `persist` 的 `DUPLICATE` 分支后 ACK；步 5 场景走既有的 `COMPENSATED` 前置检查后
   ACK），队列照样是空的。"队列空"只有在**容器已 stop** 时才能证明"没有投递"。
   因此：
   - 用例 6/7 的**主判别力来自 `status == DISCARDED` 且 `replay_count == 0`**（同步 DB 写，无竞态），
     这两条必须断言；
   - 队列断言只有在容器 stop 的前提下才算证据。若 Codex 写的测试在容器运行时就断言队列空，
     **这属于需返工的弱断言**，返工要求：用例 6/7 在调用 `replay` 前 `stop()` 主监听容器、断言后再 `start()`。
   - 判别力的最终判据仍是 F7a/F7b：变体下用例 6/7 必须**稳定变红**（多跑两次确认非 flaky）。

1. 跑 `ReservationDlqGovernanceTest` + `ReservationMqTest`（真 MySQL/Redis/broker）。
2. 零触碰文件 SHA-256 比对；`grep -n "@Transactional" ` 确认 `replay` 上没有。
3. 判别力实验（Claude 亲自做，逐字节复原）：
   - **F7a** 去掉步 4 的幂等校验 → 用例 6 必红（其余保持绿）；这是**唯一**能把"不重放已落库死信"钉死的变体
     —— 注意：只断言"预约行数仍为 1"**不具判别力**，因为 `ReservationPersistenceServiceImpl` 本来就有
     `(userId,batchId)` 存在的 `DUPLICATE` 分支，去掉校验后行数照样是 1。判别力必须来自
     `DISCARDED + replay_count == 0 + 两个队列皆空`。
   - **F7b** 去掉步 5 的 `COMPENSATED` 校验 → 用例 7 必红。
   - **F7c** 去掉步 2 的次数上限 → 用例 4 必红。
