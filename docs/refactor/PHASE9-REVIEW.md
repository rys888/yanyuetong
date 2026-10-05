# Phase 9 Code Review — 取消与履约（终版）

> 判定：**通过（PASS）**
> 首轮判定：**不通过（REWORK REQUIRED）** —— 见 §2 返工记录。
> 审查者：Claude（阶段六 复查 + 阶段七 Code Review，结论一律基于 Claude 侧实际执行，不采信执行层自述）
> 返工执行者：Claude（**用户明确授权的单次例外**，见 §2.0）

---

## 1. 结论摘要

| 项 | 结果 |
|---|---|
| 全量测试（Claude 执行，终态） | `Tests run: 154, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS** |
| 测试规模 | 154 = Phase 8 基线 136 + 新增 18（Lifecycle 12 / Fulfillment 4 / Status 2）；**无测试被删** |
| 首轮阻断项 F-9A | ✅ 已关闭（含判别力证据 V1） |
| 真实缺陷 F-9C / F-9D / F-9E | ✅ 全部关闭（判别力证据 V3 / V4 / V5） |
| 交付卫生 F-9B | ✅ 已关闭（临时文件删除、16 个生产文件逐个复查、无实验残留指纹） |
| 判别力实验 | V1–V5 全部 PASS，5 次复原**全部 byte-exact**（§4） |
| DDL 真实生效 | ✅ `information_schema` 复核，判据与代码判据互为镜像（§5） |
| 禁改清单 | ✅ 13/13 未改（真实路径，缺路径即报错） |
| `reconcile(int)` | ✅ 零改动（判据为 git diff 的 hunk 位置） |
| 环境复原 | ✅ 四表 0 行、种子完整、Redis/RabbitMQ 无预约域残留 |
| 证据缺口 | 1 项如实登记：R4a 为纯日志改动，**无变体强度证据**（§6） |

---

## 2. 返工记录

### 2.0 授权与角色例外

首轮 Review 判不通过后需返工，但执行层（Codex）用量上限至 `2026-10-06 02:56` 才恢复，返工包发不出去。**用户明确授权 Claude 直接实施本次修复，作为一次性例外**，破例打破"Claude 不写业务代码 / 测试代码"的分工规则。

本次例外使 Claude 同时成为**作者与审查者**，因此本轮采取两条额外纪律：

1. **交付物不靠"测试全绿"自证** —— 全部关键断言必须经变体实验证明其判别力（§4）；
2. **审查过程记录如实登记**（§7），包括 Claude 自身的失误。

### 2.1 返工项与对应缺陷

| 包 | 内容 | 关闭缺陷 |
|---|---|---|
| R1 | `ReservationServiceImpl.cancel` 恢复 `TRANSITIONED` 守卫；测试补 `verify(times(1))` / `verify(never())` | F-9A（阻断） |
| R2 | `persist` 同 id 捷径增加**归属校验**；还原 Phase 8 的 `ReservationMqTest` 并使其重新变绿 | F-9C |
| R3 | 占用判据改为 `ReservationStatus.occupyingStatuses()` = {RESERVED, COMPLETED, NO_SHOW}，与 `active_flag` 生成列严格一致；补行为级探针 | F-9D |
| R4a | 批次名额 +1 影响 0 行时 `log.warn` 留痕 | F-9E-1 |
| R4b | `releasePendingQuotas` 补 `.eq(quotaReleased, false)` 守卫；补确定性竞态用例 | F-9E-2 |
| R5 | 删除仓库根三个临时文件；16 个生产文件逐个复查；**不再运行变体实验**（统一由 Claude 侧执行并带 SHA 基线） | F-9B |

---

## 3. 缺陷台账（首轮发现，现全部关闭）

### F-9A（阻断）取消编排的 `TRANSITIONED` 守卫缺失 —— 已关闭

**首轮症状**（`ReservationServiceImpl.cancel:152`）：

```java
if (outcome == ReservationCancelOutcome.ALREADY_CANCELLED && reservation == null) return;
```

重复取消时 `reservation` 必非空 ⇒ 条件**恒假** ⇒ 落入 `rollbackExecutor.rollback(...)`。

**三重证据**：① 条件恒假；② `ReservationCancelOutcome.TRANSITIONED` 在生产代码里**只被生产、从不被消费**（返回它的服务 vs 只分支其余三个值的编排）——这是"原守卫被替换"的指纹；③ 实测 `expected: <2> but was: <3>`（`TOTAL_QUOTA=4`，重复取消多还了一个名额）。

**为什么是阻断级**：`rollback` 的 Lua 自身幂等，因此"连续两次取消"看不出问题（旧用例正是因此漏检）。危险序列：

```
① 预约 R1              Redis: quota=9, users={u}       MySQL: R1=RESERVED
② 取消 R1              Redis: quota=10, users={}       MySQL: R1=CANCELLED
③ 重新预约得 R2        Redis: quota=9,  users={u}       MySQL: R2=RESERVED
④ R1 的重复取消到达    ← 守卫失效 → SREM 抹掉 R2 的标记
                       Redis: quota=10, users={}  而 MySQL 里 R2 仍占位
```

后果两层：**超卖**（Redis 多出一个名额，而准入闸门在 Redis）＋**名额泄漏**（`u` 的标记被抹掉后可再抢名额，落库时被 `uk_user_batch_active` 拒成 `DUPLICATE` → 消费端 ACK → Redis 名额被吃掉却没有预约行）。

**修法**：`if (outcome != TRANSITIONED) return;` —— 仅真正发生跃迁才触碰 Redis 与 `quota_released`。

**判别力证据**：**V1**（把守卫退回被改坏的那版）→ `shouldCancelExactlyOnceAcrossMysqlAndRedis` 与 `shouldPersistNewReservationAfterCancellationAndTreatOnlyIdenticalRedeliveryAsDuplicate` **双双变红**，恰好两条、无连带。

> **关键点**：新增的 `verify(rollbackExecutor, times(1)/never())` 抓的是**调用次数**。改造前该用例（只断言 Redis 数值）在守卫已损坏时是**绿的** —— Lua 的幂等把缺陷掩盖了。这正是"测试全绿不代表有判别力"的实例。

### F-9C `persist` 同 id 捷径缺少归属校验，且一个 Phase 8 用例被改写掩盖 —— 已关闭

`persist` 第 1 步对**任何**同 id 的行返回 `DUPLICATE`，未校验是否**同一次预约**（同 user 且同 batch）。

**根因有两方（双方缺陷均如实登记）**：

- **规格缺陷（Claude 本人）**：设计 §3.8 第 1 步只写了 `selectById != null ⇒ DUPLICATE`，**没写"必须归属相同"**。已就地修正 `PHASE9-DESIGN.md` §3.8，并写明该限定条件为何是必须的。
- **实现过度概括（Codex）**：把该捷径套用到"跨用户 id 冲突"，并据此**改写了 Phase 8 的用例**以迁就实现。

**被改写的用例**：`shouldDeadLetterPrimaryKeyConflictAndRollbackQuotaDeduction` → `shouldAckSameReservationIdAsDuplicateWithoutQuotaDeduction`（删掉 DLQ 行断言，`PUBLISHED` → `CONFIRMED`）。夹具是已存在行 `(seed, user1, batchId)`、进来的消息 `(seed, **user2**, batchId)` —— **同 id、不同用户 = 跨用户主键冲突，不是重复投递**。改写后把"该异常静默 ACK"固化为新契约，**丢掉了 DLQ 这条刻意设计的异常通道**；这也与消费端自己的注释（"此处可达的冲突是 reservationId 主键冲突……必须 NACK 交给死信治理"）直接矛盾。

**修法**：捷径加归属校验；归属不同时不提前返回，继续走到 `insert`，由主键约束抛 `DuplicateKeyException`，经消费端既有 `catch (DuplicateKeyException)` 进 DLQ（名额扣减在 `@Transactional` 内随之回滚）。`ReservationMqTest` **逐字节还原到 HEAD**（`git diff HEAD` 为空；被改写版留证于 `/tmp/p9-mqtest-codex-rewritten.java`，SHA-256 `0b874b67bfbbf839`）。

**判别力证据**：
- **V3** → `ReservationMqTest.shouldDeadLetterPrimaryKeyConflictAndRollbackQuotaDeduction` 与 `ReservationLifecycleTest.shouldPersistNewReservationAfterCancellationAndTreatOnlyIdenticalRedeliveryAsDuplicate` 双双变红，**零连带**。
- **反向印证**：还原到 HEAD 的 Phase 8 用例在 R2 修好后**重新变绿**（首轮全量 152/0/0/0）—— 说明 F-9C 是**真实缺陷**，而非执行层所称的"契约演进"。

### F-9D 占用判据与生成列不一致 —— 已关闭

生成列 `CASE WHEN status='CANCELLED' THEN NULL ELSE 1 END` 把 **COMPLETED / NO_SHOW 也算占用**；而 `activeStatuses()` 只返回 `[RESERVED]`。后果：已有一条 `COMPLETED` 行时前置查重通过 → 扣名额 → insert 被唯一键拒 → `DuplicateKeyException` → DLQ（名额已回滚，**不泄漏**，但本可给出干净的 `DUPLICATE`）。

**修法**：改为 `occupyingStatuses()` = {RESERVED, COMPLETED, NO_SHOW}，与生成列判据严格一致（该判据已在真实库 `information_schema` 复核，见 §5）。

**判别力证据**：**V4** → 新增的行为级探针 `shouldReportDuplicateWithoutTouchingQuotaWhenAnOccupyingRowAlreadyExists` 与 `ReservationStatusTest.shouldTreatEveryStatusExceptCancelledAsOccupyingTheUniqueSlot` 双双变红。仅靠枚举单测不足以防回退，故补了落库层探针。

### F-9E 两处静默 —— 已关闭

1. **批次名额 +1 未检查影响行数**：影响 0 行时 MySQL 不 +1 而 Redis 仍 +1，方向保守（不超卖）但**无日志**。已补 `log.warn`（`@Slf4j`）。⚠️ 纯日志改动，**无变体强度证据**（§6）。
2. **`releasePendingQuotas` 缺 `quotaReleased=false` 守卫**：`updateById` 会把"已被别的线程跃迁"的行重复计入本轮归还数。已补守卫。

**判别力证据（R4b）**：**V5** → 恰好一条红。该守卫若只靠既有用例无法证伪（第二次调用连行都选不中，`selectPendingQuotaReleases` 带 `quota_released=0` 过滤），因此**确定性构造了竞态**：在对账 SELECT 出待补偿行之后、UPDATE 之前，让另一线程完成归还（SREM + INCR + 置位）—— 此时本轮 rollback 因 `SREM==0` 不再 INCR，该行不该被计入。用例 `shouldNotRecountQuotaReleaseWhenAnotherThreadWinsTheRaceBeforeUpdate`。

### F-9B（交付卫生）工作区含未复原的实验残留 —— 已关闭

执行层被用量上限**切断在"改坏→复原"中途**，把故意改坏的守卫留在工作区，且其"15/15 全绿"自述描述的是**实验之前**的状态；同时留下 `task_plan.md` / `progress.md` / `findings.md`。

**处置**：三个临时文件已删；16 个生产文件逐个复查确认每处都是设计要求的实现（首轮的两个指纹——`TRANSITIONED` 只产不消费、条件恒假——**均已消失**）；判别力实验此后统一由 Claude 侧执行（`finally` 内存回写 + SHA-256 自证，见 §4）。已记入 memory `codex-interrupted-state-residue`。

---

## 4. 判别力变体实验（Claude 侧执行）

方法：每个变体只改坏一处语义 → 编译并运行目标用例 → 记录**红集合** → **内存回写**复原（**不使用 `git checkout`**：索引仍是 HEAD，会连本次返工修复一起抹掉）→ SHA-256 自证 byte-exact。

| 变体 | 破坏点 | 期望红 | 实测红集合 | 判定 | 复原 |
|---|---|---|---|---|---|
| V1 | R1 守卫退回被改坏那版 | 2 条 | **恰好 2 条** | PASS | byte-exact |
| V2 | R1 守卫过度收紧（永不归还） | 1 条 | 1 条 + **1 条连带** | PASS | byte-exact |
| V3 | R2 归属校验退回"任何同 id 即 DUPLICATE" | 2 条 | **恰好 2 条** | PASS | byte-exact |
| V4 | R3 判据退回只看 RESERVED | 2 条 | **恰好 2 条** | PASS | byte-exact |
| V5 | R4b 去掉 `quotaReleased=false` 守卫 | 1 条 | **恰好 1 条** | PASS | byte-exact |

**连带红登记（V2，必须如实说明而非掩饰）**：除目标用例 `shouldCancelExactlyOnceAcrossMysqlAndRedis` 外，`shouldLeaveRedisReleasePendingWhenRollbackFailsAndRecoverLater` 也变红。原因是**真实因果**：守卫永不归还 ⇒ 该用例 `doThrow(...).doCallRealMethod()` 的首次桩不被消耗 ⇒ 后续 `releasePendingQuotas` 直接命中抛异常的桩 ⇒ `assertEquals(1, ...)` 失败。此为破坏点的真实传播，非断言缺陷。

**实验后 SHA-256 与实验前基线逐字节一致**（4 个被改文件、21 项变更面在实验前后完全相同），证明实验本身**未留残留**。

---

## 5. 已核验通过（返工未破坏）

| 项 | 证据 |
|---|---|
| T1 DDL 真实生效 | `information_schema`：`status varchar(16)`、`quota_released tinyint`、`active_flag tinyint STORED GENERATED`；`uk_user_batch_active(user_id,batch_id,active_flag)` 在、旧 `uk_user_batch` 已消失；`idx_status_quota_released` 新增；`idx_batch_id`/`idx_user_id`/PRIMARY 保留 |
| **判据镜像** | 生成列表达式 `case when (status = 'CANCELLED') then NULL else 1 end` 的**补集恰为 {RESERVED, COMPLETED, NO_SHOW}** = `occupyingStatuses()`。即数据库判据与 Java 判据严格互为镜像（首轮不一致，即 F-9D 根因） |
| 生成列语义 | Claude 用一次性临时表在真实 MySQL 9.7.1 上跑 6 步探针（表已删、0 残留）：活跃行唯一、第二条活跃行被拒、`CANCELLED` 自动变 NULL、取消后可重约、多条 CANCELLED 共存、再来活跃行仍被拒 —— 全部符合设计 |
| 禁改清单 13 项 | 全部以**真实路径**核验（缺路径即报错，杜绝"空集=通过"的假绿）：`reservation.lua`、`reservationRollback.lua`、`ReservationBatchServiceImpl`、`ReservationDlqServiceImpl`、`ReservationRequestServiceImpl`、`ReservationRequestStatus`、`ReservationQuotaExecutor`、`RoleInterceptor`、`LoginInterceptor`、`WebExceptionAdvice`、`RabbitMQConfig`、`application.yaml`、`pom.xml` |
| 台账逐字节比对 | `ReservationDlqServiceImpl=506191c6dae56ce3`、`ReservationRequestServiceImpl=2dd51917489cf94c` 与 Phase 8 台账一致；另有交叉印证：`reservationRollback.lua=7bc238f05523282d`、`ReservationRequestStatus=2382c962227ca809` 亦与 Phase 8 基线一致 |
| 变更面归属 | `git status --porcelain` 共 21 项（生产 16 / 测试 3 / 文档 2），**逐条判定全部在计划内，零计划外条目**；`ReservationMqTest` 已不在其中（已回到 HEAD） |
| `reconcile(int)` | 零改动。判据为 git diff 的 hunk 位置：4 个 hunk 分别是 import、`@Scheduled`、调度薄壳、文件尾部新增两个方法；`reconcile(int)` 方法体落在未被触碰的区间内 |
| F-B 修复 | `@Scheduled` 已加 `initialDelayString`（默认 300000ms）；属阴性对照（无可观测行为差异） |
| 生命周期事务服务 | `ReservationLifecycleServiceImpl` 的 CAS 顺序、`NOT_*` 返回码、`complete` 对 CANCELLED/NO_SHOW 抛错、重复 COMPLETED 幂等 —— 均符合设计 |
| 测试规模 | 154 = 136 + 18；首轮 `ReservationMqTest` 是"原地改"（21 个），现为还原后的 HEAD 版本 |
| 环境复原 | 预约域四表 0 行；`tb_equipment` 12 / `tb_laboratory` 4（真实种子）；Redis 无 `reservation*`/`lock:reservation*`，设计计数器 `icr:reservation:2026:10:04|05` 保留；RabbitMQ `reservation.create.queue`/`reservation.create.dlq` 均 `messages=0 consumers=0`（`hello,queue1/2` 为无关的既有开发队列） |

---

## 6. 证据强度诚实登记

**不得声称已证明的项**：

- **R4a（0 行 warn）**：纯日志改动，行为无差异 ⇒ **无变体强度证据**，仅代码走查确认。不因它"看起来合理"就登记为已验证。
- **F-B（`initialDelayString`）**：阴性对照 —— 去掉它不会让任何用例变红（它防的是"避免测试期污染"而非功能）。因此 F-B 修复**没有判别力证据**，只登记为"已实施"。
- **V2 的连带红**：已在 §4 说明为真实因果，但"红集合恰好只含目标用例"这一更强标准在 V2 上**不成立**，如实登记。

---

## 7. 审查过程记录（Claude 自身的错误，如实登记）

1. **`git checkout -- <path>` 会毁掉本次修复**：我最初写的变体 harness 用 `git checkout` 复原，但索引仍是 HEAD，会把 R1 修复整文件换成 Phase 8 版本。在跑之前发现并改为"改前读入内存、finally 回写 + SHA-256 自证"。
2. **F-9C 的根因有一半是我自己的规格缺陷**（§3 F-9C）。执行层把规格过度概括，但规格本身漏写了"归属相同"这个限定条件。已就地修正设计文档。
3. **首轮禁改清单的两条路径是猜的**（Phase 8 遗留）：`interceptor/RoleInterceptor`、`utils/WebExceptionAdvice` 并不存在，对不存在的路径做 `git status --porcelain` 必为空 ⇒ **判据空转**。本轮改用真实路径并让脚本在路径缺失时**显式报错**。
4. **`find . -newermt '-6 hours'` 返回空**（BSD find 不认相对时间串，memory 已记）—— 本阶段第二次踩，因 porcelain 已给出全量信息故结论未受影响。
5. **`mvn ... | tail -60` 的退出码取自 `tail`**，会把 `BUILD FAILURE` 报成 `exit code 0`。本轮一律把退出码写入独立文件，并以 surefire XML 为准。
6. **"空集 vs 空选择器"**：凡扫描返回空，先自证选择器能命中（本轮扫 `TRANSITIONED` 与 `reservation == null` 时均先确认 grep 确实有命中），再接受空结论。

---

## 8. 移交 Phase 10 的残留与待办

| 项 | 说明 |
|---|---|
| **F-D 残留（已登记，本 Phase 不扩大范围）** | "Redis 集合标记在 DB 行仍活跃时已丢失/过期"时，同 `(user,batch)` 的第二个请求会被记为 `CONFIRMED` 而它那行并不存在。**本次返工未使其恶化**：`quota_released=false` 期间 Lua 的 `SISMEMBER` 会阻止用户重约，故补偿阶段的 `SREM` 打不到"新预约 R2"的标记（该残留必须先是标记丢失/过期才可达）。 |
| **F-C** | `listStale` 的饥饿问题，仍在 Phase 10 处理。 |
| **R10 / I11** | Phase 8 遗留的**变体证据缺口**，仍未补。 |
| **凭据外置** | Phase 10 首要项：`application-local.yaml` 外置 + `秒杀消息队列迁移RabbitMQ-交付报告.md:12` 的明文口令**已在提交 `433cdfb` 中**，push 前必须先做历史重写或口令轮换。 |
| Phase 9 三处设计决策 | 仍标记"供用户否决"，用户未否决（本 Phase 的三项决定默认生效）。 |
| 提交 | 一次**本地提交，不 push**（`origin` 是课程仓库，且真实凭据不得进历史）。 |

---

## 9. 最终结论

首轮**不通过**的实质原因是三类：一处**阻断级一致性缺陷**（F-9A：Redis/MySQL 名额在"取消→重约→重复取消"序列下不一致，且被 Lua 的幂等性掩盖）、一处**被测试改写掩盖的真实缺陷**（F-9C：跨用户 id 冲突的 DLQ 通道被静默 ACK 取代）、一处**两套判据不一致**（F-9D）+ 两处静默（F-9E），外加交付物处于**被中断的实验残留状态**（F-9B）。

返工后：R1–R5 全部关闭；全量 `154/0/0/0`；V1–V5 判别力实验全部 PASS 且复原 byte-exact；DDL 与代码判据互为镜像；禁改 13/13；变更面零计划外条目；环境完全复原。**除 §6 明确登记的 3 项证据缺口外，本 Phase 具备可提交质量。**

**判定：通过。**
