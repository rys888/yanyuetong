# Phase 6 Review

## Result

**PASS**（含一轮返工闭环）

审查对象：Phase 6「Redis + Lua 预约」——预约链路的原子名额抢占
规格依据：`docs/refactor/PHASE6-DESIGN.md`（用户已批准的三个决策点见其 §15）
审查方式：逐行核对 diff → 独立源码审查（Lua 契约、ARGV 类型、返回码映射、唯一调用点）→ **13 组变体实验**核验测试判别力 → **3 组补充实验**判定窗口守卫可达性 → 返工 → 全量 `mvn test` → 反向量复验 → 直连 Redis / MySQL 核验环境复原
审查人：Claude（Control Plane），2026-10-04

本 Phase 与前面几个 Phase 的复核重心不同：**Phase 6 的产物不是"一个功能"，而是"一条并发正确性论证"**。所以本次审查的核心问题只有两个：

1. **这条链路真的需要 Lua 吗？**——不是"用了 Lua 很好"，而是"不用 Lua 会不会错"。§3 用实验回答。
2. **测试真的能发现这条链路被破坏吗？**——「用例全绿」必须经得起"把守卫拿掉"的检验。§5 是总账，§6 是它抓出来的两个空洞。

---

## 1. 交付物核对

| 类别 | 文件 |
| --- | --- |
| 修改（2） | `service/impl/ReservationBatchServiceImpl.java`、`utils/RedisConstants.java` |
| 新增（8） | `resources/reservation.lua`、`utils/ReservationQuotaExecutor.java`、`enums/ReservationResultCode.java`、`service/IReservationService.java`、`service/impl/ReservationServiceImpl.java`、`controller/ReservationController.java`、`dto/ReservationSubmitDTO.java`、`test/java/com/hmdp/ReservationQuotaExecutorTest.java`、`test/java/com/hmdp/ReservationSubmitTest.java` |

**改动面与设计 §9 完全一致**（8 个新增里 2 个是测试，生产新增 6 个）。

**`publish` 的三道 CAS 守卫零触碰**：`ReservationBatchServiceImpl.java` 的 diff 只有三处——
`close`/`cancel` 的 `delete` 由单键改为双键（名单键 + 用户集键）、
`queryBatchById`/`queryPage` 各加一行读路径覆盖、以及两个新增的私有方法。
§8 要求的"对 Phase 5 的改动不得让 CAS 守卫降级"经逐行核对成立。

**禁止清单零触碰**（`git diff` 无输出）：`pom.xml`、`application.yaml`、`application-local.yaml`、
`unlock.lua`、`CacheClient.java`、`SimpleRedisLock.java`、Phase 3 的 `UserRole`/`RequireRole`/`RoleInterceptor`/`MvcConfig`、
Phase 4 的缓存链路、Phase 5.1 的归属校验链路。

**生产文件 SHA-256**（返工与全部证伪实验结束后逐字节复原）：

```text
reservation.lua                          ad93be83810886de4653635f848457368ef9b78d4fff7ff8777bea0831dce76e
utils/ReservationQuotaExecutor.java      f3e90491cf6c1019c122cbff3fe85059495625ab2a6c05287c8d55f1d36276be
enums/ReservationResultCode.java         b3f7fc287e8664af6fc2f48f9ae8360e7252bbca431c11e51c2d3add2d646ae9
service/impl/ReservationServiceImpl.java fc665293c65dfaad6ce65d27867ca1bb7bb26e109a6eccdf57b773ab2879c307
service/impl/ReservationBatchServiceImpl.java a961f3bd59cdba8f4565df045affca96e62241b19fa8ae367a640592f1e3f688
utils/RedisConstants.java                d5a3af5d6665318f20327c7e64b2b083803682d54257b81d044e0aeadd45d1a5
test/.../ReservationQuotaExecutorTest.java a18dc257f7a64904bbb0f2444ad228d6089788ea126ad8bbfa7c6f3b9d9fed0f
test/.../ReservationSubmitTest.java      629e2cf481dbb1f8ec5a6da46a1a461c87ce726c95f5ea590eb484831eff6ac6
```

---

## 2. 计划验收标准逐条核验

| 验收标准 | 结论 | 证据 |
| --- | --- | --- |
| 名额校验与扣减原子完成 | ✅ | §3 的 F7 实验：**两段式实现同一用例直接超卖**（20 并发抢 5 名额 → 9 人约到） |
| 名额不会超卖 | ✅ | `shouldPreserveCapacityInvariantForDifferentConcurrentUsers`：20 个不同用户抢 5 个名额，恰好 5 人成功、Redis 值 `"0"`、用户集 size 5、两侧集合完全相等、全程观察到的名额最小值 ≥ 0 |
| 同一用户不会重复预约 | ✅ | `shouldPreserveDuplicateInvariantForSameConcurrentUser`：同一用户 20 并发恰好 1 次成功、名额只扣 1、用户集 size 1 且确实包含他 |
| 批次未开放时拒绝 | ✅ | Lua `EXISTS` → 码 1；**服务层新用例**（§6.1） |
| 名额约满时拒绝 | ✅ | Lua 容量守卫 → 码 2；F3 变体打红（执行器级 + 服务级双打红） |
| 重复预约时拒绝 | ✅ | Lua `SISMEMBER` → 码 3；F4 变体打红（执行器级 + 服务级双打红） |
| 预约窗口未开始/已截止时拒绝 | ✅ | Lua 窗口守卫 → 码 4/5；F2 变体打红执行器级；窗口可达性见 §4.3 |
| 设备不可约时拒绝（读 DB 不用缓存） | ✅ | `shouldReadEquipmentFromDatabaseInsteadOfStaleCache`：先预热缓存，再直改 DB 把 `bookingEnabled` 置 false，接口仍拒绝 |
| 未登录拒绝 | ✅ | `shouldRejectWhenUserIsNotLoggedIn` → `ForbiddenException` |
| 读路径显示真实剩余名额 | ✅ | `shouldReadPublishedRemainingQuotaFromRedis`（详情）+ §6.2 新增（分页），且都带"MySQL 仍是 4"的对比断言 |
| 关闭/取消后回落 MySQL 口径 | ✅ | `shouldFallBackToMysqlRemainingQuotaAfterClose` + §6.2 的 CLOSED 分页分支 |
| 非 PUBLISHED 批次对学生不可见 | ✅ | `shouldApplyBatchDetailVisibilityByRoleAndStatus`（用户裁定 D2） |
| ARGV 类型不触发运行时 CCE | ✅ | §7.1 源码级核验 |
| Lua 脚本无字面量 key | ✅ | §7.2 |
| Maven compile 成功 | ✅ | `mvn clean test` BUILD SUCCESS（JDK 8 / Corretto 1.8.0_504） |
| `mvn test` 全绿 | ✅ | **Tests run: 76, Failures: 0, Errors: 0, Skipped: 0**（其中 74 个是 Codex 沙箱**跑不了**的 Spring 集成测试） |

---

## 3. 核心命题：这条链路为什么**必须**是 Lua

这是本 Phase 唯一值得单独成节的问题。设计 §3.2 给出了检查顺序，但如果拿掉原子性、只保留同样的守卫，会不会一样正确？**实验回答：不会，而且错得很惨。**

**F7 变体**：把 `ReservationQuotaExecutor.tryAcquire` 整个替换为等价的**两段式 Java 实现**——
守卫一个不少（`EXISTS` → 窗口 → 容量 → 去重），唯一区别是"校验"与"扣减"分成两次 Redis 往返
（`GET` 判断 → `DECR` + `SADD`）。这模拟的就是"有守卫、但没有 Lua"。

结果（同一个测试类，同一批用例）：

| 用例 | Lua 实现 | 两段式实现 |
| --- | --- | --- |
| `shouldPreserveCapacityInvariantForDifferentConcurrentUsers`（20 用户抢 5 名额） | 5 人成功 ✅ | **9 人成功 ❌** `expected: <5> but was: <9>` |
| `shouldPreserveDuplicateInvariantForSameConcurrentUser`（同一用户 20 并发） | 1 次成功 ✅ | **13 次成功 ❌** `expected: <1> but was: <13>` |

**这就是本 Phase 用 Lua 的全部理由，且它现在有数字**：名额 5 的情况下放进 9 个人（超卖 80%），
同一用户被接受 13 次。两条用例都是不变式断言（而不是"成功数大约是 5"这种概率断言），
所以它们**只在原子性真正被破坏时**才变红——这正是判别力该有的形状。

---

## 4. Java 校验 vs Lua 校验的边界（设计 §4 逐条核验）

设计 §4 是回答交接文档 §13 那个问题的核心：**"校验到执行之间状态会变，怎么办？"**
逐条核验结论如下。

### 4.1 撤销方向：Lua 的 `EXISTS` 是权威 —— ✅ 成立，但**服务层原本没有验证**

设计主张：Java 读到 `PUBLISHED` 后、Lua 执行前，管理员关闭批次（删名额键），
此时 Java 手里的快照已过期，但**最终裁决在 Lua**，所以不会错误接受。

**F1 变体**（删掉 Lua 的 `EXISTS` 守卫，其余全留）暴露出一个真问题：

| 层级 | 结果 |
| --- | --- |
| 执行器级 `ReservationQuotaExecutorTest` | ✅ 打红 `shouldReturnBatchNotOpenWhenQuotaKeyDoesNotExist` |
| 服务级 `ReservationSubmitTest` | ❌ **15 个用例全绿** |

原因：现有的 `shouldRejectReservationAndDeleteBothKeysAfterClose` / `...AfterCancel` 只断言
`assertThrows(BadRequestException.class, ...)`、**不校验文案**，而这两条路径其实是被
**Java 的状态闸门**（`status != PUBLISHED` → "批次当前不可预约"）拦下的，**根本没走到 Lua**。

**结论：设计 §4.2 的核心主张在服务层完全未被验证**，已作为返工项 §6.1 关闭。

### 4.2 激活方向：Java 的状态闸门是**承重**的 —— ✅ 成立且被精确隔离

设计主张：Phase 5.2 的 `publish` 是"先 `SET NX` 建名额键、后 CAS 提交 `PUBLISHED`"，
因此存在"键已存在、MySQL 还是 DRAFT"的窗口；闭合它的是 Java 的第一步状态闸门。

**F5 变体**：只把 Java 的状态闸门放行 `DRAFT`（其余状态判断不动，即只拆掉这一个方向）。
结果：`shouldRejectDraftEvenWhenArtificialQuotaKeyExists` **单独变红**，其余 14 个用例全绿。

这是一次**精确隔离**的变体——它同时证明了两件事：该守卫确实是承重的，且测试确实只盯这一条。

### 4.3 预约窗口：Lua 的窗口守卫**可达但被 Java 遮蔽** —— 已实测判定

设计 §4.1 把窗口归给"不可变性"兜底，但 §3.2 的漏斗又把窗口检查列在其中，读起来像是承重的。
这个含糊必须消掉，于是做了三组对照实验：

| 变体 | 改动 | 结果 | 结论 |
| --- | --- | --- | --- |
| F2 | 删 Lua 窗口守卫（Java 保留） | 执行器级打红 2 个；**服务级全绿** | Java 单独足够 |
| F2b | 删 Java 窗口校验（Lua 保留） | **服务级 15 个全绿** | **Lua 单独也足够** → 不是死代码 |
| F2c | 两处都删 | 服务级打红 2 个 | 不存在第三个拦截机制 |

**精确结论**：Java 用**同一个 `now`** 先判，且两边条件互相蕴含（`floor(now) < floor(start) ⟹ now < start`，
截断是单调的），所以**在交付的代码里，Lua 的窗口守卫不可能先于 Java 触发**——它被 Java 遮蔽了。
但 F2b 证明：一旦 Java 那一步被删/被削弱，Lua 会把窗口完整兜住。

因此定性为：**保留，且明确它当前是"被遮蔽的冗余"，不是"承重的校验"**。
保留而非删除的理由是 §3.4 已把执行器钉为唯一调用点——这个前提一旦变化（Phase 9 可能新增调用方），
Lua 的窗口守卫就会立刻变成承重的。设计 §3.2 已按此更正措辞。

### 4.4 两组残留竞态 —— ✅ 与设计登记一致，无新增

- **(a) 设备停用竞态**：Java 读设备可用 →【管理员停用】→ Lua 成功。不造成超卖/重复（配额与去重仍在 Lua 内原子），
  秒级窗口。本 Phase 未把设备可用性搬进 Redis（否则引入第三份派生状态），与设计 §4.4(a) 一致。
- **(b) 关闭/取消与已接受请求**：Lua 已扣名额 → `close` 删两键 → 学生手里的 `reservationId` 在 Redis 无痕迹。
  这是"预接受已发生、异步落库尚未发生"的固有窗口，属 Phase 7/8 职责，与设计 §4.4(b) 一致。

---

## 5. 变体实验台账（判别力总账）

**硬规则**：每个变体只有在 surefire 报告证明**目标测试类真的跑过**时才作数。
（这条规则来自 Phase 5 的一次事故：一个变体因编译失败而"零失败"，差点被误读成"没有判别力"。）

**第一轮（13 组，全部有效 / 0 组无效）**

| # | 变体 | 期望 | 实际结果 |
| --- | --- | --- | --- |
| V0 | 交付原样（基线） | 全绿 | 执行器 8/0、服务 15/0 ✅ |
| F1 | 删 Lua `EXISTS` | 打红 | 执行器 ✅ 1 红；**服务级 0 红 → 空洞 A** |
| F2 | 删 Lua 窗口守卫 | 打红 | 执行器 ✅ 2 红；服务级 0 红（见 §4.3，符合预期） |
| F3 | 删 Lua 容量守卫 | 打红 | 执行器 ✅ 1 红；服务级 ✅ 2 红 |
| F4 | 删 Lua 去重守卫 | 打红 | 执行器 ✅ 1 红；服务级 ✅ 2 红 |
| F5 | Java 状态闸门放行 DRAFT | 打红 | ✅ 精确 1 红 |
| F6 | 读路径 Redis 覆盖改空实现 | 打红 | ✅ 1 红（详情路径）；**分页路径 0 红 → 空洞 B** |
| F7 | 原子脚本换成两段式 Java | 打红 | ✅ 2 红（超卖 9/5、重复 13/1） |

**第二轮（2 组，窗口可达性）**：F2b 全绿、F2c 打红 2 个 —— 见 §4.3。

**第三轮（返工后复验，2 组）**

| 变体 | 改动 | 结果 |
| --- | --- | --- |
| F1new | 删 Lua `EXISTS`（同 F1） | ✅ 新增用例 1 **单独变红**（17 run / 1 failure）→ 空洞 A 关闭 |
| F6new | 读路径覆盖改空实现（同 F6） | ✅ 2 红：新增用例 2 + 详情路径用例（17 run / 2 failures）→ 空洞 B 关闭 |

---

## 6. 返工：变体实验抓出来的两个覆盖空洞

**这一轮的返工不是"Codex 写错了"，而是"我给的测试计划漏了两格"**——两处都是设计里有主张、
但服务层没有任何用例去兑现的地方。这正是跑变体实验的意义。

### 6.1 空洞 A：服务层没有验证"Lua 的 `EXISTS` 是撤销方向的权威"

**新增** `shouldRejectWhenQuotaKeyIsMissingWhileStatusIsPublished`：发布批次 → 手工删掉名额键 →
断言 MySQL 仍是 `PUBLISHED`（即 Java 读到的是"可开放"的过期快照）→ 提交必须被拒 →
**断言文案恰好是 `"批次未开放预约"`（Lua 层）而不是 `"批次当前不可预约"`（Java 层）** → 且用户集键不存在。

那条文案断言是关键：它把"到底哪一层拦下的"变成可观测的判别点，
而没有它，这个用例就会像 F1 暴露的那样被 Java 的闸门悄悄兜住、永远绿。
F1new 复验：删掉 Lua 的 `EXISTS` 后**只有它变红**。

### 6.2 空洞 B：分页路径的 Redis 覆盖零覆盖

`fillRemainingQuotaFromRedis` 被详情路径和分页路径共用，但 F6 只打红了详情路径的用例。
核对全测试树：除详情路径那两条外，所有 `getRemainingQuota()` 断言读的都是
`batchService.getById(...)`（实体 / MySQL 列），**没有任何一条断言过分页 VO 的 `remainingQuota`**。

**新增** `shouldReadPageRemainingQuotaFromRedisAndFallBackForNonPublished`，一个用例覆盖两条分支：
- A（`PUBLISHED`，被约过 1 次）→ 分页 VO `3`（来自 Redis），**同时断言 MySQL 仍是 `4`** ——
  这条对比断言是"覆盖真的发生了"的证据，缺了它只是断言了一个碰巧等于 3 的数；
- B（`CLOSED`）→ 分页 VO `4`（名额键已被 `close` 删除，必须回落 MySQL）——
  覆盖 `status == PUBLISHED` 的过滤分支。

F6new 复验：把覆盖改成空实现后，新用例与详情路径用例**都**变红。

**一处额外的加固（我复核时发现）**：初版用 `queryBatchPage(new ReservationBatchQueryDTO())`
查全库公开批次，而 `DEFAULT_PAGE_SIZE = 5`、mapper 是 `ORDER BY b.id DESC`，
所以它隐含依赖"两个夹具落在第 1 页"。当前开发库公开批次为 0 条所以能过，
但只要库里累积出 ≥5 条 id 更大的公开批次就会变红——**环境相关的偶发失败**。
已改用 DTO 的 `equipmentId` 收窄到本用例自己的设备（`@BeforeEach` 每个用例都新建独有设备），
使断言不再依赖分页大小与库中既有数据。

---

## 7. 关键约束的源码级核验

### 7.1 ARGV 全为 String（**这是一个差点上线才炸的坑**）

`DefaultScriptExecutor.keysAndArgs` 会把**每一个 ARGV** 交给 `argsSerializer.serialize(Object)`；
`StringRedisTemplate` 的 argsSerializer 是 `StringRedisSerializer`，其桥接方法会把参数强转成 `String`。
**因此只要往 Lua 的 ARGV 里传一个 `Long`，运行时就抛 `ClassCastException`。**
而 Codex 的沙箱连不上 Redis，这个异常会被埋在 65 条连接错误堆栈下面，极难发现。

我通过 `javap -c` 反汇编 `spring-data-redis-2.6.2.jar` 确认了这条链路，并把"ARGV 必须全为 String"
写成设计 §3.1 的硬约束。**交付代码逐项核对通过**：`userId.toString()`、
`Long.toString(toEpochSecond(...))` × 3、`Long.toString(ttlSeconds)` —— 5 个 ARGV 全是 String。

### 7.2 Lua 脚本无字面量 key

`reservation.lua` 全部通过 `KEYS[1]` / `KEYS[2]` 寻址，无任何硬编码 key。
这同时关闭了 MIGRATION_STATUS 的 **P2-1**（"Redis Key 重复定义：Java 常量 vs Lua 字面量"）——
Phase 6 是第一个不再产生该重复的模块。

### 7.3 唯一调用点

`reservation.lua` 仅被 `ReservationQuotaExecutor` 引用（`ClassPathResource` 静态加载，
与 `SimpleRedisLock` 的既有模式一致）；`tryAcquire` 仅有 `ReservationServiceImpl.submit` 一个调用方。
设计 §3.4 的"唯一调用点"成立。

### 7.4 读路径的降级行为（一处**有意的不对称**）

写路径遇到脏名额值会抛错（响亮失败）；读路径遇到脏值则 `NumberFormatException` →
被 `catch (RuntimeException)` 接住 → `log.warn` + 回落 MySQL。这个不对称是对的：
读路径因为一个键损坏而整页 500 是不可接受的，且降级有日志留痕。
`multiGet` 的返回列表与 `keys` 下标对齐、`published` 与 `keys` 同序构造，索引对齐无误。

---

## 8. 文档更正：§3.3 的"500"是错的

Codex 在自查中提出：设计 §3.3 声称"脏名额值 → Java 收到异常 → **500**"，与实际不符。复核确认：

`WebExceptionAdvice.handleRuntimeException` **未声明 `@ResponseStatus`**，所以真实 HTTP 状态是 **200** +
`Result.fail("服务器异常")`。同一兜底还会接住 `HttpMessageNotReadableException` 这类**客户端**错误，也返回 200。

**裁定：更正文档，不在本 Phase 改代码。** 理由：这是**跨切面的全局异常→状态码契约**，
不属于设计 §9 声明的改动范围；且"顺手改一行"会把 JSON 解析失败从 200 变成 500，
等于用一个新错误换掉旧错误（正确做法应是 400），反而需要更大的契约设计。
设计 §3.3 已改写为陈述真实行为 + 说明为何接受，并登记为 **P2-25** 交 Phase 10。

> 附带说明：本 Phase 的"响亮失败"设计意图并未失效——`log.error` 打全栈、调用方拿不到 `reservationId`、
> 业务上没有任何静默兜底。失准的只是 HTTP 状态码这一层。

---

## 9. 审查过程记录（含我自己的一次事故）

### 9.1 我的 harness 有一个 bash 展开顺序 bug，导致第一轮 13 组结果全部作废

第一轮 campaign 的每一组都报"类没跑 → 变体无效"。原因是我在
`local label=$1 cls=$2 expect=$3 log=... report="...$cls.txt"` 这条语句里，
**用同一个 `local` 语句里刚赋值的 `cls` 去拼 `report`**。bash 会在 `local` 执行**之前**
展开所有参数，所以拼 `report` 时 `cls` 还是空的 → 报告路径变成 `com.hmdp..txt` → 校验门永远看不到报告。

这个 bug 的表现形式恰好是"变体无效"，所以它没有伪装成"没有判别力"——
**是 Phase 5 学到的那条有效性门（先证明测试类真的跑过）把它挡住的**。
修复方式是把 `report` 的构造挪到 `local` 之后的独立一行，并把失败用例名的提取
从 Maven 控制台文本改为直接解析 surefire 报告（更稳）。修复后重跑，13 组全部有效。

### 9.2 Codex 用量上限（第二次）

第三轮返工（分页查询收窄）时 Codex 撞上用量上限，进程以 exit 1 结束。
但**它已经先把改动写进了文件**（第 193–195 行），只是在执行最终验证时失败。
因此我没有采信它这一轮的"编译通过"结论，而是在自己的全量 `mvn test` 里一并核验（BUILD SUCCESS）。
（Phase 5 也发生过同类事故，见 `PHASE5-REVIEW.md` §6.4。）

---

## 10. 环境复原

- **Redis**：`--scan --pattern "reservation:*"` 无输出，无残留名额键 / 用户集键。
- **MySQL**：`tb_reservation_batch` 0 行、`tb_equipment` 12 行、`tb_laboratory` 4 行、
  `tb_user` 1013 行（1011 个既有用户 + 2 个种子管理员 1016 / 1019）、无 `预约测试%` 残留用户。
- **发现并清理了一组残留夹具**：我第一次 campaign 被中途 kill 时，JVM 被杀导致正在执行的用例
  `@AfterEach` 没跑，留下 1 个实验室 + 1 个设备 + 3 个用户 + 1 条 DRAFT 批次
  （phone 后缀 `9926958`）。已按 id 精确删除并复验回到基线。
  **教训登记**：中断 Spring 集成测试的运行会留下夹具残留，"跑完全量套件"这个收尾动作应包含
  一次 DB 残留检查，而不是只看 `mvn` 的退出码。
- **代码**：全部生产文件 SHA-256 与交付一致（§1），证伪实验中的临时改动已逐字节恢复。

---

## 11. 已知局限与登记项

| 编号 | 内容 | 归属 |
| --- | --- | --- |
| P2-25 | 全局 `RuntimeException` 兜底不设 HTTP 状态 → 服务器故障返回 200；且客户端错误（JSON 解析失败）也落到同一兜底 | Phase 10 |
| P2-26 | `ReservationBatchLifecycleTest.shouldIncludePublishedAndClosedBatchesOnPublicPage` 同样隐含"夹具在第 1 页"（`DEFAULT_PAGE_SIZE=5` + `ORDER BY id DESC`） | Phase 10（测试加固） |
| 新增 | 测试夹具手机号后缀取模空间 `% 10_000_000L`，对 `tb_user.phone` 唯一索引存在约 1e-5 量级的生日碰撞概率（与 Phase 5 既有写法同源，Phase 5 用的是 `% 100_000_000L`，空间大 10 倍） | 登记，暂不处理 |
| 设计 §16.7 | `close`/`cancel` 后读路径从 Redis 值"跳回"MySQL 值；**曾考虑把 Redis 余额回写 MySQL，因会在 Phase 7 造成重复扣减而否决** | 保留为已知边界 |
| §4.4(a) | 设备停用竞态 | Phase 8 |
| §4.4(b) | 关闭/取消与已接受请求的一致性窗口 | Phase 7 / 8 |

**本轮关闭的既有条目**：
- **P1-5**（"Lua 不校验预约时间窗口"）—— 本 Phase 的 `reservation.lua` 已实现窗口校验（码 4/5）；可达性见 §4.3。
- **P2-1**（Redis Key 重复定义）—— 见 §7.2。
- **P2-2 / P2-3**（`seckill:stock:` 无 TTL、秒杀实现注释残留）—— 全 `src/` 树核查：
  除保留的建库脚本 `db/hmdp.sql` 外，`秒杀` / `seckill` / `voucher` 在生产代码与 Lua 中**零残留**
  （实际已由 Phase 5.0 删除，只是状态文档没同步）。

---

## 12. 结论

Phase 6 的交付可以接受。三条判断：

1. **它真的需要 Lua，而且现在有数字**：同样的守卫、只把校验与扣减拆开，20 并发抢 5 个名额会放进 9 个人、
   同一用户会被接受 13 次。这条链路的价值不是"用了 Lua 这个技术"，而是"不用它就错"。
2. **Java 与 Lua 的分工被论证清楚，而且方向是对称的**：撤销方向由 Lua 的 `EXISTS` 守（Java 的快照过期也无害），
   激活方向由 Java 的状态闸门守（Lua 的 `EXISTS` 在这里恰恰会误放行）。
   两处都用"删掉它测试会不会红"验过。
3. **我对测试的信任是有依据的**：13 组变体里，凡是我以为有判别力的地方基本都真的有；
   而真正没判别力的两处（服务层的撤销守卫、分页路径的读覆盖）被实验抓了出来并补上、再用同一实验复验。
   **"用例全绿"这件事本身不构成证据，能被拿掉守卫打红才算。**

待用户批准后：本地提交（沿用"每个 Phase 独立提交"的既有约定），再决定是否进入 Phase 7。
