# Phase 8 · T8 任务包（§1.4 必测异常 11 条 + 不变式 I1–I12 的收口）

> 派发方式（硬性约定）：MCP `codex` 新开 thread，
> `model="gpt-5.6-sol"` / `effort="high"` / `sandbox="workspace-write"` / `cwd=/Users/eason/Desktop/hm-dianping` / `timeout=600000`。
> 集成测试由 Claude 侧执行；Codex 沙箱连不上本机 MySQL/Redis/RabbitMQ，**不得声称跑过集成测试**。

## Goal

把 Phase 8 的验收清单（设计 §13）补齐到"可逐条判别"的程度：
**§1.4 的 11 条必测异常 + 不变式 I1–I12 每一条都必须有一个能在被破坏时变红的用例。**

本任务**不是**从零写一遍测试。T1–T7 每个任务都随交付写了自己的测试，
Claude 已逐条把 11 条异常与 I1–I12 映射到**已存在的**用例上并确认覆盖，
**只剩 2 个真实缺口**（下文 G1、G2）。请只补这两个缺口，不要重写已有用例。

## Context

### 已经覆盖的部分（Claude 逐条核对过源码与用例，**不要改动**）

| §1.4 | 异常 | 已覆盖于 |
| --- | --- | --- |
| 1 / 2 / 3 | MQ 重复投递 / Consumer 重复消费 / ACK 丢失 | `ReservationMqTest#shouldTreatRepeatedMessageAsDuplicateWithoutSecondQuotaDeduction`、`#shouldAckRepeatedMessageAndKeepRequestConfirmed`、`#shouldTreatSameUserAndBatchWithDifferentReservationIdAsDuplicate` |
| 4 | MySQL 条件扣减失败（补 `fail_reason`） | `ReservationMqTest#shouldRecordQuotaConflictWithoutChangingRequestStatus` |
| 5 | INSERT 失败（证明事务真回滚，I4） | `ReservationMqTest#shouldDeadLetterPrimaryKeyConflictAndRollbackQuotaDeduction` |
| 6 / 7 | Confirm NACK / Return | `ReservationPublishFailureTest#shouldCompensateWhenConfirmIsNack`、`#shouldCompensateWhenMessageIsReturned`、`#shouldCompensateWhenAmqpConnectionFails` |
| 8 | Confirm Timeout → `UNKNOWN` | `ReservationPublishFailureTest#shouldKeepQuotaAndMarkUnknownWhenConfirmTimesOut`（stub 成空操作 ⇒ 真实 `get(5s)` 超时，非造异常） |
| 9 | Redis 补偿重复调用（Lua 返回码 0/1/2） | `ReservationMqTest#shouldRestoreQuotaAndRemoveUserMarkerWhenQuotaKeyExists`(1)、`#shouldNotRestoreQuotaTwiceForSameUser`(1→0)、`#shouldNotRecreateQuotaKeyWhenRollbackRunsAfterClose`(2) |
| 10 | DLQ 重放 | `ReservationDlqGovernanceTest#shouldReplayValidPayloadAndMarkLedgerReplayed` |
| 11 | 服务重启后未完成请求（对账两分支） | `ReservationReconcileTest#shouldConfirmPersistedReservationWithoutRollingBackQuota`、`#shouldCompensateMissingReservationOnceAcrossRepeatedRuns` |

| 不变式 | 已覆盖于 |
| --- | --- |
| I1 | `ReservationMqTest#shouldNotRestoreQuotaTwiceForSameUser` |
| I2 | 同上（第二次调用 = 用户不在标记集合 ⇒ 返回 0、名额不变） |
| I3 | `ReservationMqTest#shouldNotRecreateQuotaKeyWhenRollbackRunsAfterClose` |
| I4 | `ReservationMqTest#shouldDeadLetterPrimaryKeyConflictAndRollbackQuotaDeduction` |
| **I5** | **仅单条路径** —— 见 G2 |
| I6 | `ReservationPublishFailureTest#shouldKeepQuotaAndMarkUnknownWhenConfirmTimesOut` |
| I7 | `ReservationPublishFailureTest#shouldCompensateWhenConfirmIsNack` / `#shouldCompensateWhenMessageIsReturned` |
| I8 | `ReservationReconcileTest#shouldCompensateMissingReservationOnceAcrossRepeatedRuns` |
| I9 | `ReservationMqTest` 重复投递用例中的"死信队列为空"断言 |
| I10 | `ReservationMqTest#shouldAckCompensatedRequestWithoutPersistingOrDeadLettering` |
| I11 | `ReservationDlqGovernanceTest#shouldReplayValidPayloadAndMarkLedgerReplayed` + `#shouldDiscardAlreadyPersistedWithoutPublishing` |
| I12 | `ReservationReconcileTest#shouldLeaveRequestNonTerminalWhenRollbackThrows`、`ReservationPublishFailureTest#shouldKeepRequestNonTerminalWhenRollbackFails` |
| **非终态扫描的取值覆盖** | **无** —— 见 G1 |

### 设计文档与实现的既有偏差（Claude 已裁定，**本任务不要求消除**）

以下是设计成稿时写的"预计交付形态"与实现的实际落点不一致之处。**都已核对无误，属形式差异而非覆盖缺失，不要为了对齐文档去新建类或搬移用例**：

- §9.1 列的 `ReservationUnknownStateTest`（新）**未单独建类**：其要求的 `@SpyBean RabbitTemplate` + `convertAndSend` 空操作 + 真实 5s 超时，已实现在 `ReservationPublishFailureTest#shouldKeepQuotaAndMarkUnknownWhenConfirmTimesOut`，断言项（账本 `UNKNOWN`、Redis 名额未变、503 文案）完全一致。
- §9.1 把 I4/I5/必测 #5 归给 `ReservationPersistenceTest`（扩），实际落在 `ReservationMqTest`（端到端更可信）。`ReservationPersistenceTest` 保留的是 `persist` 的直测。
- §9.2 的"`classify(Throwable)` 纯单测"**没有对应函数**：§3.3 的五类信号是以 `catch` 子句内联实现的，五行分别由 `ReservationPublishFailureTest` 的 NACK / Return / AmqpConnectException / Timeout / 其它异常五个用例覆盖。**不要**为了可单测而把内联 catch 抽成函数（那是生产代码改动，超出 T8）。
- §9.1 ④ 写的"**四种**非终态各一条"是笔误：`ReservationRequestStatus` 五态中终态为 `CONFIRMED`/`COMPENSATED`，非终态只有 **3 个**（`PRE_ACCEPTED`/`PUBLISHED`/`UNKNOWN`）。G1 以 3 为准。

---

## G1（缺口 1）非终态集合的扫描覆盖 —— 必须补

**问题（Claude 已实测确认，非推测）**：

`ReservationRequestStatus.nonTerminalStatuses()` 返回 3 个值，扫描谓词
`ReservationRequestServiceImpl#listStale` 与 `ReservationReconcileServiceImpl` 都用它。

但现有夹具的取值分布是：

- `ReservationReconcileTest` 的**每一条** `createRequest(...)` 实参都是 `PUBLISHED`
  （共 8 处，已用 `grep -n "createRequest("` 逐条确认）→ **`PRE_ACCEPTED` 与 `UNKNOWN` 从未经过对账扫描**；
- `ReservationRequestStateTest#shouldListOnlyStaleNonTerminalRequestsWithinLimit` 只造了 `PRE_ACCEPTED`（正例）与 `COMPENSATED`（负例）
  → **`PUBLISHED` 与 `UNKNOWN` 从未经过 `listStale`**。

**后果**：一个漏扫的状态意味着**该状态的请求永远无人对账**，名额永久少一个。
这是本 Phase 最不能容忍的静默失效。而该方法正是"服务重启后未完成请求必须被扫到"（§1.4 #11）的承载物。

> **⚠️ 事后更正（2026-10-05，T8 判别力实验后回填，Claude 自己的错）**
>
> 本任务包初稿在这里写的是「把 `nonTerminalStatuses()` 收窄成 `singletonList(PUBLISHED)`，
> 全量测试**仍然全绿**」。**这句话是错的**，实测（变体 F8）会红 4 条。当时的推理漏洞是：
> 我只核对了**夹具取值分布**（全部为 `PUBLISHED`），没有核对**桩的匹配方式**。
> 实际情况比"全绿"复杂，分三种：
>
> | 改动 | 实测红集 | 说明 |
> | --- | --- | --- |
> | 丢掉 `PUBLISHED`（F8b：换成 `[PRE_ACCEPTED, UNKNOWN, UNKNOWN]`，元素数仍为 3） | 6 条对账用例 + 新增的 2 条 | 既有对账夹具**全部**是 `PUBLISHED`，丢掉它等于全部夹具不再被扫 —— 这条**本来就有覆盖** |
> | 改变元素个数（F8：`[PUBLISHED]`；F8c：`[PRE_ACCEPTED, PUBLISHED]`） | 2 条桩用例 + 新增的 2 条 | `shouldCountCasConflictAsSkippedAndConvergeOnNextRun` 与 `shouldNotReturnQuotaTwiceWhenCompensationCasIsSkipped` 的 Mockito 可变参数桩**硬编码 3 个匹配器**，元素数一变即失配 —— 这是**元数跳闸**，与丢掉哪个状态无关，不构成语义覆盖 |
> | **丢掉 `UNKNOWN` 且元素数不变** | 仅新增的 2 条 | ← **G1 真正且唯一补上的缺口** |
>
> 所以 G1 的价值是真的，但**理由要收窄成后者**：`UNKNOWN` 是 §3.4 步 4c 在核心的 Confirm Timeout 路径上
> 主动制造的状态，而在 G1 之前**没有任何夹具处于 `UNKNOWN`**（对账类全为 `PUBLISHED`，
> 状态类只有 `PRE_ACCEPTED` 正例）。没有夹具就无从观测，这是论证而非实测。
> F8c 反过来证明：新补的用例确实会在 `UNKNOWN` 被丢掉时变红。
>
> **对本任务范围的影响：无。** G1/G2 要补的用例不变，只是其中 G1 的"为什么必须补"从
> "否则全绿"更正为"否则只有元数跳闸能发现，而元数不变地丢掉 `UNKNOWN` 无人能发现"。

**要求**：

1. `ReservationReconcileTest` 新增一条用例（建议名 `shouldScanEveryNonTerminalStatusAndSkipTerminalOnes`）：
   - 在**同一批次**下造 **3 条陈旧夹具**（`update_time` 早于阈值，沿用既有 `makeStale`），状态分别为
     `PRE_ACCEPTED` / `PUBLISHED` / `UNKNOWN`，且**都不预先落预约行**（三条都应走"无行 ⇒ 归还名额 + `COMPENSATED`"分支）；
   - 再造 **2 条陈旧夹具**处于终态 `CONFIRMED` / `COMPENSATED`，同样不落预约行；
   - 断言 `summary` 为 `scanned==3, confirmed==0, compensated==3, skipped==0, failed==0`；
   - 断言 3 条非终态夹具**都**变成 `COMPENSATED`，2 条终态夹具**状态与 `fail_reason` 均未变**。
   - 注意：`@AfterEach` 只按 `requestIds` 清理，三条夹具都要登记进去；终态夹具需直接 `insert`（`createRequest` 已支持传状态）。
2. `ReservationRequestStateTest#shouldListOnlyStaleNonTerminalRequestsWithinLimit` 改造为**三值正例**：
   - 陈旧夹具扩到三种非终态各一条，断言 `listStale(before, 200)` 返回的夹具集合**恰好是这三条**（用现成的 `filter` 手法）；
   - 保留 `COMPENSATED` 负例，并**再加一条 `CONFIRMED` 负例**，断言两者都不被扫到；
   - 保留既有的"阈值取过去 ⇒ 一条都扫不到"的谓词判别断言（那条是防"删掉 `update_time < before` 谓词"的，**不得删**）。

**判别力（Claude 侧跑，不由你执行）**：把 `nonTerminalStatuses()` 收窄为 `Collections.singletonList(PUBLISHED)`
⇒ 上述两条用例必须变红，其余用例保持绿。

## G2（缺口 2）I5「`remaining_quota` 永不为负」缺少并发判别 —— 必须补

**问题**：设计 §5 对 I5 的判别方式写明是「**并发下**断言 `>= 0`」。现状：

- `ReservationSubmitTest#shouldPreserveCapacityInvariantForDifferentConcurrentUsers` 确实有并发断言，
  但它断言的是 **Redis** 里的名额（`minimumObservedQuota`），**不是** MySQL 的 `remaining_quota`；
- MySQL 侧只在**单条**路径上被断言过：`ReservationPersistenceTest#shouldNotInsertReservationWhenQuotaIsExhausted`(==0)、
  `ReservationMqTest#shouldDeadLetterQuotaConflictWithoutMakingMysqlQuotaNegative`(==0)。

让"永不为负"成立的承重谓词是 `ReservationPersistenceServiceImpl#persist` 里条件扣减的
`.gt(ReservationBatch::getRemainingQuota, 0)`。**去掉它，现有全部测试不会有一条变红**（都会走到"扣减成功但只扣 1 次"的单线程路径）。

**要求**：`ReservationPersistenceTest` 新增一条并发用例（建议名 `shouldNeverMakeRemainingQuotaNegativeUnderConcurrentPersist`）：

- 造**新批次** `totalQuota = 1, remainingQuota = 1`（不要复用 `setUp` 里的 `INITIAL_QUOTA=2` 批次），
  并为每个并发线程**各造一个用户**（`uk_user_batch` 是 `(user_id, batch_id)`，同用户会先撞到幂等预检查，
  必须用不同用户才能真正并发抢同一个名额）；
- 用 `CountDownLatch` 让 **N ≥ 8** 个线程同刻调用 `persistenceService.persist(...)`，
  每线程一个不同用户 + 同一批次；
- 断言：
  1. 返回 `PERSISTED` 的次数**恰好为 1**，其余全部为 `QUOTA_CONFLICT`；
  2. `tb_reservation` 中该批次的落库行数**恰好为 1**（按 `batchId` 计数，不要按单用户计数）；
  3. 重新查库得到 `remainingQuota == 0`，且**显式**断言 `>= 0`（把不变式本身写进断言，而不是只断言等于 0）。
- 线程池/`ExecutorService` 的收尾（`shutdownNow`）与 `finally` 释放要照抄
  `ReservationSubmitTest#shouldPreserveCapacityInvariantForDifferentConcurrentUsers` 的既有写法。
- **清理**：`ReservationPersistenceTest#cleanUp()` 现在是**按 `setUp` 的那个单用户**删预约行
  （`countFixtureReservations()` 也是）；新用例引入了多用户同批次的行，
  必须让清理覆盖到它们（例如按新批次的 `batchId` 删除），否则会留下残留行并污染 §9.3 的环境复原审计。

**判别力（Claude 侧跑，不由你执行）**：删掉 `ReservationPersistenceServiceImpl` 里
`.gt(ReservationBatch::getRemainingQuota, 0)` ⇒ 该用例必须变红（会出现多条 `PERSISTED` 且 `remaining_quota` 为负）。

---

## Scope

**允许修改（仅这 3 个测试文件）**：

```
src/test/java/com/hmdp/ReservationReconcileTest.java     G1-1
src/test/java/com/hmdp/ReservationRequestStateTest.java  G1-2
src/test/java/com/hmdp/ReservationPersistenceTest.java   G2
```

**零触碰（一个字符都不要改）**：

- 全部生产代码：`src/main/java/**`（**尤其** `enums/ReservationRequestStatus.java`、
  `service/impl/ReservationRequestServiceImpl.java`、`service/impl/ReservationPersistenceServiceImpl.java`、
  `service/impl/ReservationReconcileServiceImpl.java`）；
- 其余测试类：`ReservationMqTest`、`ReservationPublishFailureTest`、`ReservationDlqGovernanceTest`、
  `ReservationSubmitTest`、`ReservationBatchLifecycleTest`、`ReservationQuotaExecutorTest`、
  `EquipmentCacheTest`、`LaboratoryOwnershipTest`、`CacheClientMutexTest`、`BookingStateTest` 及全部其它测试；
- `pom.xml`、`src/main/resources/**`（含 `db/phase8_consistency.sql` 与两个 `.lua`）、`application*.yaml`。

## Constraints

1. **不引入新依赖**；JUnit 5 断言 + 既有工具类足够。
2. **JDK 8**（无 `var`、无 `List.of`、无 `switch` 表达式）。
3. **不新增 `@Disabled`**、不删改既有用例、不为"让新用例好写"而放宽既有断言。
4. 新用例的**名称用英文**、与同类既有用例风格一致；注释用中文，写明"这条断言防的是什么"，与同类现有注释风格对齐。
5. **可变参数桩陷阱（本项目已栽过）**：若你在新用例里写 Mockito 桩，**禁止**在 varargs 位置使用
   `any(X[].class)` —— 在 Mockito 3.3.3 下它永不匹配，会导致桩静默失效、用例零覆盖地通过。
   G1/G2 都是纯集成用例，**预期不需要任何 Mockito 桩**；若你发现非用不可，必须在汇报里明确说明并解释为何无法避免。
6. 并发用例**不得**依赖固定休眠来"等结果"（如 `Thread.sleep`）——用 `Future.get(timeout, unit)` 收敛，
   照抄既有并发用例的写法。
7. 集成测试你跑不了。**不要**为了"跑通"而把集成用例改成 mock 用例。

## Acceptance Criteria

- AC1 `export JAVA_HOME=/Users/eason/Library/Java/JavaVirtualMachines/corretto-1.8.0_504/Contents/Home && mvn -q clean test-compile` 退出码 0。
- AC2 只改了上面列出的 3 个测试文件；`git status --porcelain` 里没有其它改动（`docs/`、`MIGRATION_STATUS.md` 不计）。
- AC3 G1-1 落地：对账用例对三种非终态各有一条夹具且断言 `scanned==3 / compensated==3`，终态夹具断言未被触碰。
- AC4 G1-2 落地：`listStale` 用例对三种非终态各有一条夹具并断言三条都被返回，终态负例仍在，谓词判别断言仍在。
- AC5 G2 落地：并发用例断言"恰好 1 条 `PERSISTED` + 该批次恰好 1 行 + `remainingQuota == 0` 且 `>= 0`"，
  且 `cleanUp()` 能清掉该用例造出的多用户行。
- AC6 既有用例的断言**一条都没有被删改**（只做增量与 G1-2 的扩夹具改造）。

## Validation

```bash
export JAVA_HOME=/Users/eason/Library/Java/JavaVirtualMachines/corretto-1.8.0_504/Contents/Home
cd /Users/eason/Desktop/hm-dianping
mvn -q clean test-compile     # 唯一你能跑的验证；退出码必须为 0
git status --porcelain        # 自查改动范围
git diff --stat src/test/java/com/hmdp/ReservationReconcileTest.java \
               src/test/java/com/hmdp/ReservationRequestStateTest.java \
               src/test/java/com/hmdp/ReservationPersistenceTest.java
```

集成测试与判别力实验由 Claude 在真实 MySQL/Redis/RabbitMQ 上执行。

## Execution

1. 先读这 4 个文件再动手：`src/test/java/com/hmdp/ReservationReconcileTest.java`、
   `src/test/java/com/hmdp/ReservationRequestStateTest.java`、
   `src/test/java/com/hmdp/ReservationPersistenceTest.java`、
   `src/main/java/com/hmdp/enums/ReservationRequestStatus.java`；
   以及 `ReservationSubmitTest#shouldPreserveCapacityInvariantForDifferentConcurrentUsers`（并发用例的写法样板）。
2. 按 G1-1 / G1-2 / G2 补用例，**只改这 3 个文件**。
3. 跑上面 Validation 的两条命令并如实汇报退出码。
4. 汇报：改动文件清单 + 每处改动的关键点 + `test-compile` 结果 + 每条 AC 的自查 + 仍存在的不确定性。
   **不要声称跑过集成测试**，也不要声称判别力实验结果 —— 那是 Claude 侧的活。

## 附：Codex 明确不要做的事

- 不要新建 `ReservationUnknownStateTest` 或任何新测试类（见上文"既有偏差"）。
- 不要把 §3.3 的内联 `catch` 抽成可单测的 `classify` 函数。
- 不要为了让 `listStale` 更好测而改 `nonTerminalStatuses()` 或 `ReservationRequestServiceImpl`。
- 不要动 `ReservationMqTest` 的 §7.2 期望值（`rollback == 2` 那条）——那是 T1 已留痕的语义精化。
- 不要为"环境复原"新增审计脚本或测试类；环境审计由 Claude 在全量回归后手工执行。
