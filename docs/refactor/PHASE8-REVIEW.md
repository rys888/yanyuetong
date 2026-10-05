# Phase 8 Review

> 阶段六（复查）+ 阶段七（Code Review）：对 T1–T8 全部交付物做**跨 Task 独立复查**。
> 本文的每一条结论都来自我自己读到的代码、我自己跑出的数字；Codex 的自述只作为线索，不作为证据。

## Result

**PASS**（含 1 个 Review 发现的真实缺陷，已返工关闭）

| 口径 | 数字 |
| --- | --- |
| 全量回归（F-A 返工后） | `mvn -B test` → **136 run / 0 failures / 0 errors / 0 skipped**，BUILD SUCCESS |
| 受影响类复核 | `ReservationDlqGovernanceTest` 10 / `ReservationRequestStateTest` 8 → **18/0/0/0** |
| 生产文件变更面 | 相对 T8 结束时**恰好 2 个文件**（均为 F-A 修复） |
| 禁改清单 | 8 个文件 porcelain 复核**全部为空** |
| 其它 6 个生产基线 | SHA-256 与台账记录值**逐字节一致** |
| 判别力实验 | 台账既有 10 个变体 + 本轮新增 F-A 变体 |
| 引入的新缺陷 | 1 个（F-A，已关闭）；另登记 4 项非阻塞项 F-B/F-C/F-D/F-E |

---

## 1. 交付物核对（T1–T8）

| Task | 交付物 | 核对方式 | 结论 |
| --- | --- | --- | --- |
| T1 | 一致性 SQL（`tb_reservation_request` / `tb_reservation_dlq`） | 直读 DDL + `information_schema` 复核索引与列精度 | 一致 |
| T2 | `ReservationRequest` 实体 + 状态机 + 账本服务 | 直读：`@TableId(INPUT)`、5 状态、`NON_TERMINAL_STATUSES`、`casStatus`/`recordFailReason` | 一致 |
| T3 | 提交路径接入账本（`createPreAccepted` 早于 `tryAcquire`） | 直读 `ReservationServiceImpl` | 一致 |
| T4 | 补偿 Lua 幂等化（SREM 先行） | 直读 `reservationRollback.lua` + 变体 F1 | 一致且承重 |
| T5 | 发布结果三态（`PublishOutcome`） | 直读 `ReservationServiceImpl` | 一致 |
| T6 | 消费者侧收口（`COMPENSATED` 前置检查、失败原因、CAS 置 CONFIRMED） | 直读 `ReservationMessageConsumer` + 变体 F3 | 一致且承重 |
| T7 | 死信治理（台账、分页、重放、RBAC） | 直读 service/controller/consumer/config + 9→10 用例 | 一致（F-A 修复后） |
| T8 | 对账（`@Scheduled` + Redisson 锁 + CAS 补偿） | 直读 `ReservationReconcileServiceImpl` + 变体 F4/F5 | 一致 |

---

## 2. 高危风险 R1–R11 的裁定

「证据类型」一栏区分**变体实测**（把代码改坏，看测试是否变红）与**读码/取证**（静态确认）——
后者强度低于前者，凡属后者我一律标注，不混同。

| # | 裁定 | 证据类型 | 依据 |
| --- | --- | --- | --- |
| R1 | 通过 | 变体 F4（阈值改 0 → 新鲜非终态被误扫，目标用例变红）+ 实跑断言默认阈值不误判 | 台账 F4 |
| R2 | 通过 | 变体 F1（Lua 改回只认 `EXISTS` → I1/I3 变红，红集完全一致） | 台账 F1 |
| R3 | 通过 | 变体 F2（`TimeoutException` 并入回滚分支 → I6 变红，红集完全一致） | 台账 F2 + 直读 `PublishOutcome` 分支 |
| R4 | 通过 | **读码**：全仓 `@Transactional` 只出现在 `ReservationPersistenceServiceImpl:26`，账本 CAS 与之分属不同事务；`markConfirmedSafely` 抛异常被就地吞掉、不回滚已落库的预约 | grep + 直读 |
| R5 | 通过 | **读码**：`submit` 及其调用链无 `@Transactional`（全仓唯一一处在 `persist`） | grep |
| R6 | 通过 | 变体 F6（`persist` 内联 → I4 变红）+ **读码确认跨 bean**：`ReservationMessageConsumer:140` 经注入的 `IReservationPersistenceService` 接口调用，`@Transactional` 落在 `ReservationPersistenceServiceImpl` 上，无自调用 | 台账 F6 + grep |
| R7 | 通过 | 变体 F3（删 `COMPENSATED` 前置检查 → I10 变红，红集完全一致） | 台账 F3 |
| R8 | 通过 | 变体 F5（去掉对账补偿 CAS → I8 变红，红集完全一致） | 台账 F5 |
| R9 | 通过 | **读码**：`onDeadLetter(Message message, Channel channel)`，兜底分支只做 `log.error` + `basicNack`，全程不经过 Jackson | 直读 `ReservationDlqConsumer:31` |
| R10 | **未达变体强度** | 仅**读码 + 用例覆盖**：`replay` 的 7 步幂等校验（按 `reservationId` 查已落库、拒绝 `COMPENSATED`、`(id,status,replay_count)` 乐观 CAS）逐行核对无误；但**台账的 10 个变体里没有 F7**，I11 属"有覆盖、未验证判别力" | 直读 + 台账边界声明 |
| R11 | 通过 | **读码**：两处 `basicNack(deliveryTag, false, false)` 第三参数均为 `false`（不重回队列） | 直读 + grep |

**必须点名的强度差异**：R10 是 R1–R11 里**唯一**没做过判别力实验的一条。设计 §8 为它指定的验证方式是"判别实验 F7"，而 F7 **从未出现在台账的变体表里**——表格列了 F1–F6、F8/F8b/F8c、F9，独缺 F7。这不是"实验做失败了"，是"这条实验没做"。我已在 §9 登记为待补项，不将其算作已验证。

---

## 3. 变更面（相对 Phase 7 提交 `02daa19`）

`git status --porcelain` 共 **41** 条：`M` 10 条、`??` 31 条（其中 `src/test/resources/` 是一个新目录）。

**生产代码（`src/main`）**

- 新增 20 个：`config/{ReservationDlqListenerConfig,SchedulingConfig}`、`controller/ReservationDlqController`、
  `dto/{ReconcileSummaryDTO,ReservationDlqQueryDTO}`、`entity/{ReservationDlq,ReservationRequest}`、
  `enums/{ReservationDlqStatus,ReservationRequestStatus}`、`mapper/{ReservationDlqMapper,ReservationRequestMapper}`、
  `mq/ReservationDlqConsumer`、`service/{IReservationDlqService,IReservationReconcileService,IReservationRequestService}`、
  `service/impl/{ReservationDlqServiceImpl,ReservationReconcileServiceImpl,ReservationRequestServiceImpl}`、
  `vo/{ReservationDlqReplayVO,ReservationDlqVO}`
- 新增 1 个：`resources/db/phase8_consistency.sql`
- 修改 **6** 个：`mq/ReservationMessageConsumer`、`service/impl/ReservationServiceImpl`、`utils/RedisConstants`、
  `utils/ReservationQuotaRollbackExecutor`、`resources/application.yaml`、`resources/reservationRollback.lua`

**测试代码**

- 新增 5 个：`ReservationDlqGovernanceTest`、`ReservationPublishFailureTest`、`ReservationReconcileTest`、
  `ReservationRequestStateTest`、`src/test/resources/application.properties`
- 修改 3 个：`ReservationMqTest`、`ReservationPersistenceTest`、`ReservationSubmitTest`

**文档**：`MIGRATION_STATUS.md` + `docs/refactor/PHASE8-{DESIGN,T6-R-PACKET,T7-PACKET,T7-R-PACKET,T8-PACKET}.md`

**无越界改动**：没有出现设计 §11.2 未列出的生产文件；没有新增第三方依赖（`pom.xml` porcelain 为空）。

---

## 4. 禁止清单与零接触核验

| 文件 | 核验 | 结论 |
| --- | --- | --- |
| `resources/reservation.lua` | porcelain | 未改 |
| `config/RabbitMQConfig` | porcelain | 未改 |
| `utils/ReservationQuotaExecutor` | porcelain | 未改 |
| `utils/WebExceptionAdvice` | porcelain | 未改 |
| `interceptor/LoginInterceptor` | porcelain | 未改 |
| `interceptor/RoleInterceptor` | porcelain | 未改 |
| `pom.xml` | porcelain | 未改 |
| `service/impl/ReservationPersistenceServiceImpl` | porcelain + SHA-256 | 未改，`35524b8c082401f6…` = 台账记录值 |

**方法学更正（我自己的失误）**：我第一次做这项核验时用的是 `git diff --quiet HEAD -- <file>`。
对**未跟踪**文件，`git diff HEAD` 恒为空 → 会报"未改"的**假绿**。本清单里 8 个文件恰好都是已跟踪文件，
所以本轮结论碰巧正确，但判据本身是错的；已改用 `git status --porcelain`（未跟踪会显 `??`）重跑，结论不变。
这条记在这里，是因为它对后续任何"零接触"核验都成立。

**基线复核（F-A 之后）** —— 台账 §574 记录的 8 个 `src/main` 基线，其中 6 个本轮零触碰、逐字节一致：

```
OK  2382c962227ca809  enums/ReservationRequestStatus.java
OK  35524b8c082401f6  service/impl/ReservationPersistenceServiceImpl.java
OK  5803e4bb7e085e43  service/impl/ReservationReconcileServiceImpl.java
OK  ae843e1ab599fb10  service/impl/ReservationServiceImpl.java
OK  3de59bbf921a05ce  mq/ReservationMessageConsumer.java
OK  93cd1f0586067abb  resources/application.yaml
OK  7bc238f05523282d  resources/reservationRollback.lua
```

第 8 个 `ReservationRequestServiceImpl`（台账 `d88269958b6b9b89…`）是本轮 F-A **有意**改动的对象，
现为 `2dd51917489cf94c…`；另加 `ReservationDlqServiceImpl`（T7 交付物）同因 F-A 改动。

---

## 5. 判别力实验

### 5.1 台账既有 10 个变体（T8 阶段已跑，本节仅引用不作新证据）

F1/F2/F3/F4/F5/F6 红集与期望**完全一致**；F8/F8b/F8c/F9 有判别力但带连带红（同一宽改动的必然结果）。
⇒ 已证明"承重"的不变式：**I1、I3、I4、I5、I6、I8、I10**。
**I2/I7/I9/I11/I12 未做变体实验**，属"有覆盖、未验证判别力"。

### 5.2 本轮新增变体（F-A 修复的判别力）

harness `/tmp/p8fa-variants.py`：把 F-A 新加的两个次级排序键**分别**删掉，看新用例会不会红。
**每个变体重复 3 次** —— 并列排序的失败可能是概率性的，单次不足以判定。

| 变体 | 改写内容 | 目标用例 | 结果 |
| --- | --- | --- | --- |
| V1 | 删 `ReservationDlqServiceImpl` 的 `.orderByDesc(getId)` | `shouldOrderTiedCreateTimesDeterministicallyByDescendingId` | **3/3 变红**，且红集**只有这一条**（其余 9 条全绿） |
| V2 | 删 `ReservationRequestServiceImpl` 的 `.orderByAsc(getRequestId)` | `shouldOrderTiedUpdateTimesDeterministicallyByAscendingRequestId` | **3/3 变红**，且红集**只有这一条**（其余 7 条全绿） |

- **无连带红**：两个变体各自只打红了它该打红的那一条 → 两条用例的断言是**定向**的，不是靠宽泛断言蹭红。
- **三次一致**：失败是确定性的（不是偶发），说明这不是"运气不好才红"的脆弱用例。
- **Codex 自述的担忧被排除**：它曾提出"`idx_status_create_time` 隐含主键，删掉显式 `id DESC` 后
  优化器可能仍碰巧按 `id DESC` 返回，削弱判别力"。实测 V1 三次全红 → 该巧合在真实数据分布下**不发生**。
- **逐字节复原**：实验后两个文件 SHA-256 = `506191c6dae56ce3` / `2dd51917489cf94c`，与实验前完全一致。

⇒ F-A 的两条新用例**具备判别力**，F-A 缺陷是可被本仓库测试捕获的（此前不能，因为旧用例刻意造了相隔一分钟的时间戳）。

---

## 6. 本轮 Review 发现的缺陷与处置

### F-A｜`queryPage` 的 `ORDER BY` 在秒级时间戳上不唯一（**真实缺陷，已返工关闭**）

- **缺陷**：`ReservationDlqServiceImpl.queryPage` 原先只按 `create_time DESC` 排序；
  `tb_reservation_dlq.create_time` 是 `timestamp`（**无小数秒**，经 `information_schema` 确认），
  同秒并列时 MySQL 不保证稳定顺序 → `LIMIT/OFFSET` 分页在并列行上可能**重复或漏行**。
  同类问题存在于 `ReservationRequestServiceImpl.listStale`（只按 `update_time ASC` 排序，每轮 `LIMIT 200` 选取不确定）。
- **为什么既有测试抓不到**：`shouldPageByStatusNewestFirstWithoutPayload` 刻意用
  `minusMinutes(2)` / `minusMinutes(1)` / `now()` 三个相隔一分钟的时刻造行，**结构上不可能**产生并列。
- **修复**：追加唯一打破者 —— `queryPage` → `ORDER BY create_time DESC, id DESC`；
  `listStale` → `ORDER BY update_time ASC, request_id ASC`。
  实测 SQL 已确认为 `ORDER BY create_time DESC,id DESC LIMIT ?`。
- **为什么不动 DDL 加小数秒**：`timestamp → datetime(3)` 是结构性变更，牵动 Phase 7 已交付的表与索引，
  且只能"减少"并列而非"消除"（同毫秒仍会并列）。并列打破者是正解。
- **新增 2 条用例**：`shouldOrderTiedCreateTimesDeterministicallyByDescendingId`、
  `shouldOrderTiedUpdateTimesDeterministicallyByAscendingRequestId`，均走**真实 SQL**、连续两次查询断言确定性。
- **交付方式**：Codex 实现（`codex-reply` 同一 thread，`gpt-5.6-sol` / `high`）。
  Codex 在**写完代码、未能观察到 RED** 时撞上用量上限中断——它自己在汇报里如实声明了这一点，
  判别实验由我在本机补做（§5.2）。

### F-B｜`@EnableScheduling` 在测试期无条件生效（登记，不阻塞）

`SchedulingConfig` 无 `@Profile` 守卫，`@Scheduled(fixedDelayString = ...)` **没有 `initialDelay`**
→ `fixedDelay` 语义下**每个 `@SpringBootTest` 上下文启动时立即跑一次对账**。
今日无害（各夹具要么新鲜、要么已终态），但只要将来出现"陈旧非终态"夹具，就会有一条后台线程
在与用例并行的时机去补偿它 → 环境相关的偶发失败。**建议 Phase 9 处理**：加 `initialDelay` 或把 `@EnableScheduling` 收进 profile。

### F-C｜`listStale` 的 `LIMIT` 无游标推进（登记，不阻塞）

长期失败的行 `update_time` 一直最旧，会**每轮都排在最前、独占 200 个名额**，
使其后达到阈值的陈旧行饥饿。F-A 的 `request_id` 次键只让选取**可复现**，**不解决饥饿**。
需要游标（记录上一轮处理到的 `update_time/request_id`）才能真正推进 → Phase 9/10。

### F-D｜`DUPLICATE` 分支把账本置为 CONFIRMED，但可能并无对应预约行（登记，不阻塞）

`ReservationMessageConsumer:68` 把 `DUPLICATE` 与 `PERSISTED` 同样走 `markConfirmedSafely`。
而 `persist` 返回 `DUPLICATE` 的条件是 **(userId, batchId) 已有行**（`ReservationPersistenceServiceImpl:32`），
**不是** `reservationId` 已有行 —— 于是这条消息对应的 `requestId` 被置为终态 CONFIRMED，
库里却没有 `id = reservationId` 的行。

- 正常提交路径不可达：同一 (user, batch) 的第二次占用被 Redis 的 `SADD` 标记挡住。
- **但重放路径可达**：`replay` 的第 4 步只按 `reservationId` 查"是否已落库"，
  若死信携带的 `reservationId` 与库里那行的 id 不同（先补偿、后又提交的典型时序），
  重放会走通并落进 `DUPLICATE` 分支 → 账本终态与事实不符。
- 影响面在 Phase 9（取消/履约要按 `requestId` 找预约行）会放大 → **Phase 9 处理**。

### F-E｜设计 §8 对变体 F4 的预期命名与实测不符（文档勘误）

设计写"F4 阈值设为 0 时 **I8** 必须变红"，实测 F4 打红的是
`ReservationIgnoreFreshNonTerminalRequest` 一类用例（新鲜非终态被误扫），并非 I8 的重复补偿用例。
这是**设计文档的命名/指向错误**，不是代码缺陷，F4 的判别力本身成立。已在 `PHASE8-DESIGN.md` §8 表后附勘误。

---

## 7. 测试耗时评估（用户指定项，只评估不改代码）

**F-A 返工前全量**：`Total time: 01:07 min`（134 条）。**返工后**：`Total time: 01:10 min`（**136 条**）——
只多 2 条毫秒级用例却多 3s，说明**该口径的抖动大于增量**（`ReservationMqTest` 单类在不同轮次实测
43.575 / 44.204 / 44.262 / 46.411s），逐轮比较 `Total time` 无意义，只看量级与构成。
按 surefire 逐用例耗时统计（返工前那一轮）：

| 耗时 | 用例 | 性质 |
| --- | --- | --- |
| 5.10s | `ReservationMqTest#shouldAckCompensatedRequestWithoutPersistingOrDeadLettering` | 等待队列排空的轮询 |
| 5.04s | `ReservationPublishFailureTest#shouldKeepQuotaAndMarkUnknownWhenConfirmTimesOut` | **刻意**的真实 5s `future.get` 超时（设计决定） |
| 3.15s | `ReservationMqTest#shouldAckRepeatedMessageAndKeepRequestConfirmed` | 轮询 |
| 2.0–3.1s | `ReservationMqTest` 另外约 6 条 | 50–100ms 轮询 + 8s 上限 |
| 其余 | — | 毫秒级 |

**用例净耗时合计 62.6s / 134 条**；类级耗时（如 `ReservationMqTest` 单类 43.6–46.4s）**已包含各自上下文启动**，
两者不可相加。容器 start/stop 不单独可见。

**最大的单项开销是 `ReservationMqTest` 单类约 44s，占全量 wall-clock 的六成以上。**

**主要结论（推断，未单独测量）**：耗时大头是**RabbitMQ 管理端统计的刷新粒度（约 5s）**——
`getQueueInfo().getMessageCount()` 返回的不是实时值，那些"等队列排空"的轮询必须等到统计刷新才能通过。
这与"2–5s 的固定簇"、以及每次运行几乎相同的耗时高度吻合。若要提速，正确做法是**改判据**：
把"等队列消息数归零"换成直接查台账行（毫秒级）或 `rabbitTemplate.receive()`（立即返回），
而**不是**缩短轮询间隔（改间隔对统计刷新无效，只会烧 CPU）。

**不建议现在动**：测试耗时不是本 Phase 的验收项，且改判据会动到刚验收通过的 `ReservationMqTest`。
登记为 Phase 10 的可选优化。

---

## 8. 审查过程记录（我自己的错误，必须留档）

### 8.1 我一度用 `git diff HEAD` 做"零接触"核验（假绿判据）

见 §4。对未跟踪文件恒为空。本轮 8 个文件恰好都已跟踪，故结论未错，但判据已换。

### 8.2 我的 G1 变体实验一度存在混淆变量（已用 F8d 关闭）

T8 阶段我用变体 F8c 论证"`UNKNOWN` 状态是承重的"，但 F8c **同时**删掉了 `UNKNOWN` **和**把
`NON_TERMINAL_STATUSES` 的元素数从 3 改成 2 —— 而既有桩用的是 `any(), any(), any()` 三元匹配，
红集可能由**元数跳闸**alone 解释。
我因此补做了 **F8d**：元素数保持 3、保留 `PUBLISHED`、只把 `UNKNOWN` 换成重复项
→ **恰好那 2 条 G1 用例变红，其余 132 条全绿**，混淆变量排除。

### 8.3 Codex 第三次撞上用量上限

本轮 Codex 在**已落盘生产改动、但未及自查 RED** 时中断（exit 1）。
它主动声明了"沙箱无法运行集成测试、RED 未能观察"——这个如实声明是有价值的，
但它同时提出过一句**自己的判别力担忧**（"`idx_status_create_time` 隐含主键，删掉显式 `id DESC` 后
优化器可能仍碰巧按 `id DESC` 返回，削弱判别力"）。这正说明：**交付方对自己交付的测试判别力没有把握时，
必须由非交付方实测**。§5.2 就是这件事的答案。

### 8.4 一个必须保留的判据

「测试全绿」不构成「代码正确」的证据。本轮 F-A 就是反例：**全量 134 条全绿的状态下**，
分页排序依然是错的。判据必须落在"把代码改坏，测试会不会红"上。

---

## 9. 已知局限与登记项

1. **R10 / I11 未经变体实验**（§2）。DLQ 重放的幂等性目前只有读码 + 用例覆盖支撑。
2. **I2/I7/I9/I11/I12 未做变体实验**（台账既有边界声明），属"有覆盖、未验证判别力"。
3. **F-B/F-C/F-D** 三项登记为 Phase 9/10 处理（§6）。
4. **设计 §14 的 D1–D37 自我裁定**仍未逐条经用户确认（原样保留该状态）。
5. **`ReservationDlqGovernanceTest#shouldPageByStatusNewestFirstWithoutPayload` 用绝对计数**
   （`assertEquals(2L, getTotal())`）：中途中断的测试进程会留下残留行，使其环境相关地变红。
   与 §9 的既有登记项 28（中断留残留）同源。
6. **`@SpyBean` 造成的多上下文并存**未消除：F-A 之前的缺陷 B 修复后，
   配对运行日志**仍有 2 个 Spring 上下文**——修复消除的是"活着的消费者"，不是"第二个上下文"。
   因此**"上下文个数"是误导性指标**，正确观测量是 `死信台账落库失败` 的日志条数（0 → 1）。

---

## 10. 环境复原

- 变体实验逐字节复原并复核 SHA-256（见 §5.2）：`506191c6dae56ce3` / `2dd51917489cf94c`，与实验前一致。
- 全量回归（136 条）跑完后实测：

| 检查项 | 实测 |
| --- | --- |
| MySQL `tb_reservation` | **0 行** |
| MySQL `tb_reservation_request` | **0 行** |
| MySQL `tb_reservation_dlq` | **0 行** |
| MySQL `tb_reservation_batch` | **0 行** |
| RabbitMQ `reservation.create.queue` | `messages=0`、`consumers=0` |
| RabbitMQ `reservation.create.dlq` | `messages=0`、`consumers=0` |
| Redis `lock:reservation*` | 无键 |
| Redis `icr:reservation:2026:10:04` / `:10:05` | **仍在**（设计内的每日计数器，D36 边界 7，**不得删除**） |

- `consumers=0` 尤其重要：它证明测试跑完没有留下**幽灵消费者**——
  这正是 T7 缺陷 B（`@SpyBean` 造成多上下文并存、各自持有消费者抢消息）的观测量。

---

## 11. 结论

Phase 8 的 T1–T8 交付物在我独立读码 + 实跑复核后**通过**，Review 发现的 1 个真实缺陷（F-A）
已通过 Codex 返工 + 我的判别力实验关闭。R1–R11 中 **6 条有变体强度证据**（R1/R2/R3/R6/R7/R8）、
**4 条为读码或 grep 级**（R4/R5/R9/R11）、**R10 明确标记为无强度证据**（其设计的 F7 实验从未执行）。
剩余 4 项（F-B/F-C/F-D/F-E）已定性登记并指派到后续 Phase，均不阻塞本 Phase 提交。
