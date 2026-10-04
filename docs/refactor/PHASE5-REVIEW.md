# Phase 5 Review

## Result

PASS（含一轮「复现 → 修复 → 返工」的完整闭环）

审查对象：Phase 5「Reservation Batch 预约批次」——由 5.0 遗留清理、5.1 实验室归属、5.2 批次生命周期三次提交组成
规格依据：`docs/refactor/PHASE5-DESIGN.md`（用户已批准）+ `实验资源预约平台-分阶段重构Phase计划.md`
审查方式：逐行核对 diff、独立复跑编译与全量测试、**先复现两个竞态再用运行时实验证明修复**、**17 组变体实验逐条核验测试判别力**、启动应用做端到端 HTTP 实测、直连 DB / Redis 核验、DDL 幂等复跑
审查人：Claude（Control Plane），2026-10-04

本 Phase 与前三 Phase 的复核方式有一处根本不同：**风险不再主要来自「有没有实现」，而来自「并发下会不会互相拆台」**。因此本轮把重心放在「拿掉目标守卫，测试会不会变红」这件事上，并且**真的把两个竞态在运行时复现出来了**——不是靠代码推演下结论。

---

## 1. 交付物核对

| 类别 | 文件 |
| --- | --- |
| 修改（3） | `config/WebExceptionAdvice.java`、`utils/RedisConstants.java`、`test/java/com/hmdp/LaboratoryOwnershipTest.java` |
| 新增（14） | `entity/ReservationBatch.java`、`enums/ReservationBatchStatus.java`、`enums/BookingState.java`、`exception/BadRequestException.java`、`mapper/ReservationBatchMapper.java`、`vo/ReservationBatchVO.java`、`dto/ReservationBatchQueryDTO.java`、`service/IReservationBatchService.java`、`service/impl/ReservationBatchServiceImpl.java`、`controller/ReservationBatchController.java`、`resources/mapper/ReservationBatchMapper.xml`、`resources/db/phase5_reservation_batch.sql`、`test/java/com/hmdp/BookingStateTest.java`、`test/java/com/hmdp/ReservationBatchLifecycleTest.java` |

**改动面与设计 §7 完全一致**：14 new + 3 modified。审查过程中任何越出这 17 个文件的改动都被要求解释（见 §7.3）。

**禁止清单零触碰**：`pom.xml`、`application.yaml`、`application-local.yaml`、`unlock.lua`、`CacheClient.java`、`SimpleRedisLock.java`、Phase 3 的 `UserRole`/`RequireRole`/`RoleInterceptor`/`MvcConfig`、Phase 4 的设备缓存链路，`git diff` 均无输出。

**生产文件 SHA-256 与返工后存档逐一一致**，证明 Codex 在证伪步骤中的临时改动已逐字节恢复：

```text
ReservationBatchServiceImpl.java   f512b8c2a46820a31d9a033581449f26ec0989785a5713dd6906f4151e643f7a
ReservationBatchMapper.xml         61ee003940f57115bde6baf78061ddea6f1d2215da0087753eea8ce3e9d78855
ReservationBatchLifecycleTest.java 8209a21a0228af5ac82b309c968578f4a990797dc5ec410ba739f39e21211f37
```

---

## 2. 计划验收标准逐条核验

| 验收标准 | 结论 | 证据 |
| --- | --- | --- |
| 批次可创建（DRAFT） | ✅ | `ReservationBatchCreationTest`；端到端实测 `{"success":true,"data":<id>}` |
| 批次可发布，发布后 Redis 名额键就位 | ✅ | §3.1；键存在、值 == `totalQuota`、`TTL > 0`（实测 611999s） |
| 批次可关闭 / 取消，名额键被清理 | ✅ | `shouldClosePublishedBatchDeleteQuotaAndRejectRepeatedClose`、`shouldCancelPublishedBatchDeleteQuotaAndRejectFurtherTransitions`；变体实验（去掉 `DEL`）打红 |
| 学生只能看到已发布批次 | ✅ | §5.4；`/page` 隐藏 DRAFT 与 CANCELLED，`?status=DRAFT` → 400 |
| 管理员只能管理自己实验室的批次 | ✅ | §5.5；B 对 A 的批次 4 个写端点全 403 且 DB 无副作用 |
| 发布不变式 I1–I7 全部生效 | ✅ | §4；逐条变体（V9–V15）各自打红对应用例 |
| 状态持久化与时间派生分离 | ✅ | `BookingStateTest` 9 个纯单元用例，固定 `now` 注入，六态 + 闭区间边界 |
| Maven compile 成功 | ✅ | `mvn clean test-compile` BUILD SUCCESS（JDK 8 / Corretto 1.8.0_504） |
| `mvn test` 全绿 | ✅ | **Tests run: 49, Failures: 0, Errors: 0** —— 其中 40 个是 Codex 沙箱**跑不了**的 Spring 集成测试 |
| DDL 幂等 | ✅ | `phase5_reservation_batch.sql` 重复执行 3 次无报错，表数不增 |

---

## 3. 双竞态：先复现，再证明修复

Phase 5.2 的第一次交付里，`publish` 的 Redis 名额初始化与 `updateDraft` 的读改写各有一个竞态。**这两个都不是靠读代码发现的，是靠运行时实验打出来的**——先复现，再修，再用同一套实验证明修复有效。

### 3.1 H9：并发 publish 时失败方删掉成功方的名额键

**危害**：已发布批次**没有名额键**。Phase 6 的 Lua 脚本读不到键，学生全部预约失败，而且没有自愈路径——批次状态是 `PUBLISHED` 但系统认为它零名额。

**复现实验（修复前）**：对同一 DRAFT 批次同时发 20 个 `publish`。

```text
20 并发 publish  →  HTTP 码混杂（多个 200）
DB   PUBLISHED | total=4 | remaining=4
EXISTS reservation:batch:quota:{id}  =  0        ← 键被失败方删掉了
```

**修复**：`SET` 改为 `SET NX`（**NX 是强制的，不是可选**），并且 `DEL` 只允许由「本次调用成功写入键」的那一方执行。

**验证实验（修复后）**：

```text
20 并发 publish  →  1 × 200 + 19 × 400
DB   PUBLISHED | total=4 | remaining=4
Redis value = 4 ,  TTL = 611999
```

**设计文档因此在 §6.3 被修正过一处**：原本写的「顺序本身是设计决策」（先 `SET` 后 `UPDATE`）**是错的**——单靠顺序不构成安全性。失败方无条件 `DEL` 会把一个**正确排序**的实现重新变成 ❌ 的后果。真正承重的是 NX + 删除的所有权判定。这条修正已回写设计文档。

### 3.2 H10：`updateDraft` 的 TOCTOU——草稿字段写进已发布批次

**危害**：DB 名额与 Redis 名额失配，同时 I7「同设备时段不重叠」的保证失效（草稿改到别的时段并生效，但 Redis 仍是旧名额）。终态形如 `PUBLISHED | total=99 | remaining=4`——一个自相矛盾的撕裂行。

**复现实验（修复前）**：`publish` 与 `updateDraft`（把 `totalQuota` 改成 99）对撞。

```text
PUBLISHED | total=99 | remaining=99 | redis=4     ← 草稿写入整个生效了
PUBLISHED | total=99 | remaining=4  | redis=4     ← 撕裂行
```

> 第一次实验全部轮次都是 `DRAFT|99|99`，**没有复现**。原因不是窗口不存在，而是实验本身有缺陷：上一轮遗留的 PUBLISHED 批次 17 占着设备 1 的同一时段，新建批次的 publish 每次都被 I7 拦掉。删掉批次 17 并加入错开的 delay 后立即复现。**记录此点**：一次失败的复现实验不能证明窗口不可达，要先排除实验装置自身的干扰。

**修复**：`updateDraft` 的 UPDATE 带上 `.eq(status, DRAFT)`；`publish` 的条件 UPDATE 额外绑定六个快照字段（`equipmentId` / `startTime` / `endTime` / `bookingStartTime` / `bookingEndTime` / `totalQuota`）。两者都是 CAS，Service 层的前置检查不再是裁决点。

**验证实验（修复后）**：6 轮对撞，只出现两种合法终态，0 轮异常。

```text
DRAFT     | 99 | 99 | <nil>
PUBLISHED |  4 |  4 | 4        (TTL 1130399)
```

**关于快照绑定这处「超出返工包」的改动**：Codex 在返工中自行把 `publish` 的 CAS 也绑到了六个快照字段上，这是我原返工包没要求的。我判定**这处补充是必要的，予以接受**——它直接消灭了上面实测到的撕裂行 `PUBLISHED|99|4`（只加 `updateDraft` 的状态条件挡不住「`publish` 读到旧快照、`updateDraft` 已提交新值、`publish` 的 CAS 仍然命中」这条路径）。它唯一的代价是 `publish` 的 UPDATE 语句更啰嗦。

### 3.3 H12：并发 publish × cancel 把已取消批次「复活」成 PUBLISHED

**这是本轮最重要的发现，而且完全来自变体实验，不是人工推演。**

在核验测试判别力时，我逐条把守卫拿掉看测试会不会红。其中「拿掉 `publish` 的 `WHERE status='DRAFT'`」这一条，18 个用例**全绿**。一个守卫毫无测试覆盖，可能是它不重要，也可能是覆盖有洞——**不能靠猜，得用运行时实验证明它承重**。

推理：`cancel` 的条件 UPDATE 匹配 `status IN (DRAFT, PUBLISHED)`，所以在 publish × cancel 对撞中 **`cancel` 总会赢**，正确实现的最终状态**永远不可能是 `PUBLISHED`**。那么「终态 == PUBLISHED」就是「`publish` 的 CAS 匹配到了一个已取消的行」的充分证据。

**实验**（同一 DRAFT 批次同时发 publish 与 cancel，扫描 6 档 delay × 3 轮 = 18 轮）：

| 版本 | 复活轮数 |
| --- | --- |
| 修复版（带 `WHERE status='DRAFT'`） | **0 / 18** |
| 变体 V6（拿掉该条件） | **3 / 18**，delay=0 命中；其中两轮连带 quota 键也缺失 |

窗口真实可达，且后果与 H9 同型（「PUBLISHED 但无名额键」）。**这个守卫承重，而当时没有任何测试覆盖它**——这是第二轮返工的首要动因。

---

## 4. 变体实验台账（判别力总账）

规矩：**每个承担关键职责的测试都要能回答「把目标守卫拿掉，这个断言会不会失败」，并且要真的跑。** 全部实验由一个加固过的 harness 执行，harness 有一条硬规则：

> **变体只有在 surefire 摘要显示目标测试类确实跑过时才作数。** 编译失败同样产生零条 `[ERROR] <Class>.` 行，会被误读成「零失败 = 测试没有判别力」。

这条规则是踩坑换来的，见 §6.1。

| 变体（拿掉什么） | 结果 |
| --- | --- |
| V0 原样（基线） | 18 run / 0 failures（全绿，符合预期） |
| V1 Redis `SET NX` + 只删自己写的键 | ✅ 打红 `shouldPublishExactlyOnceAndPreserveQuotaUnderConcurrency` |
| V2 mapper 的学生端状态白名单 | ✅ 打红 `shouldOnlyReturnPublishedAndClosedBatchesOnPublicPage` |
| V3 service 层的 `批次状态不合法` 400 | ✅ 打红 `shouldRejectNonPublicStatusOnPublicPage` |
| V4 `/manage/page` 的 `admin_user_id` 过滤 | ✅ 打红 `shouldOnlyReturnOwnedBatchesOnManagePage` |
| V5 Redis quota 写入 | ✅ 打红 3 个用例 |
| V6 `publish` 的 `WHERE status='DRAFT'` | ❌ **18 个全过 → 覆盖空洞**（后果已由 §3.3 运行时实验证明承重） |
| V7 `publish` 的六个快照 `.eq()` 绑定 | ❌ **18 个全过 → 覆盖空洞** |
| V8 `updateDraft` 的 `.eq(status, DRAFT)` | ❌ **18 个全过 → 覆盖空洞** |
| V9–V15 不变式 I1–I7（逐条拿掉） | ✅ 每条各打红 `shouldRejectI<n>` 中对应的**那一个**，不多不少 |

**结论**：14 组变体有判别力，3 组暴露了真实的覆盖空洞。三处空洞都不是「代码写错了」，而是「守卫是对的但没人守得住它」——正因如此，它们只能靠变体实验发现，靠读代码和靠「测试全绿」都发现不了。第二轮返工针对这三处（§7.2）。

**三处空洞的最终状态**：

| 变体 | 首轮 | 第二轮后 | 第三轮后 |
| --- | --- | --- | --- |
| V6 | ❌ 全绿 | ✅ 打红 R6 | ✅ |
| V7 | ❌ 全绿 | ✅ 打红 R7 | ✅ |
| V8 | ❌ 全绿 | ❌ 20 轮绿、**200 轮仍绿** | ✅ **打红 R7**（需错开释放，见 §9.1） |

V8 那一格是本 Phase 最费力气的一条：它不是「测试写得不够多」，而是**同时放行的对撞方式在结构上产不出 V8 需要的交错顺序**。加轮数、加断言、联合拿掉另一处守卫，三条路都试过且都无效；最后靠改变**交错的形状**而不是加大实验量解决。

---

## 5. 端到端 HTTP 实测（端口 18081）

### 5.1 鉴权矩阵

对 8 个批次端点逐一实测三种身份：

| 端点 | 未登录 | 普通用户 | 实验室管理员 |
| --- | --- | --- | --- |
| `POST /reservation-batch` | 401 | 403 | 200 |
| `PUT /reservation-batch` | 401 | 403 | 200 |
| `POST /{id}/publish` | 401 | 403 | 200 |
| `POST /{id}/close` | 401 | 403 | 200 |
| `POST /{id}/cancel` | 401 | 403 | 200 |
| `GET /{id}` | 401 | 200 | 200 |
| `GET /page` | 401 | 200 | 200 |
| `GET /manage/page` | 401 | 403 | 200 |

两层分离正确落地：**角色在拦截器边缘**（`RoleInterceptor`，401 无身份 / 403 角色不符），**归属在 Service 层**。

被拒的调用**零副作用**——实测被拒后批次仍为 DRAFT 且字段未变。

### 5.2 归属

管理员 B 对管理员 A 的批次调 `publish` / `close` / `cancel` / `PUT`：**四个全部 403**，且批次状态保持 `DRAFT|4|4|<nil>` 不变。

### 5.3 状态白名单

```text
GET /page（不带 status）      不含 DRAFT、不含 CANCELLED、含 PUBLISHED
GET /page?status=DRAFT       400 {"success":false,"errorMsg":"批次状态不合法"}
GET /page?status=PUBLISHED   200
GET /manage/page（本人实验室） 含 DRAFT、含 CANCELLED
GET /manage/page（B 的身份）   不含 A 的批次
```

### 5.4 一处实验装置缺陷（记录，非产品问题）

鉴权矩阵首轮出现两个假阳性：PUT 行的请求体漏了 `id`（400「批次id不能为空」）；create 行与 publish 行用了**同一天**的时段，两批次在设备 1 上重叠，publish 被 I7 拒 → 假 403。在隔离时段（day 40）单独重探得到 `{"success":true}`，确认是探针问题；随后用每次调用都换唯一时段的干净重测得到上表结果。**记录此点**：矩阵式探针里共享时段会被 I7 干扰，必须每次用不重叠的时段。

---

## 6. 审查过程中的方法论事故（必须留档）

### 6.1 我自己的「零失败」误读——差点报出一个假发现

V1（拿掉 NX）的第一次实验，harness 报「0 failures」，我据此判定「并发测试没有判别力」。真相是：变体代码里用了 `ValueOperations.set(key, value, timeout, unit)`——这个方法返回 **`void`**，而还原代码需要 `Boolean`，**编译失败**。harness 数的是 `[ERROR] <TestClass>.` 行数，编译失败一条都没有，于是「编译不过」被读成了「测试全绿」。

我又跑了三次 V1 和一次 V6，全部显示 0，**几乎就把这个假发现写进结论**。直到查看 `diag8.log` 才看到 `COMPILATION ERROR ... void无法转换为java.lang.Boolean`。

**修正**：(1) 重写 harness，加入「目标测试类必须真的跑过」的硬前置校验；(2) 用**忠实还原**的方式构造变体（连同 `Boolean quotaCreated` 赋值与早抛块一起删掉，而不是换一个语义不同的 API）。重跑后 **V1 → 18 run / 1 failure**，测试确实有判别力。

**教训**：判别力实验的失效模式是**双向**的——既不只在「测试过不了」，也在「实验根本没跑起来」。没有「确认测试真的执行了」这一步，零失败什么都不能说明。

### 6.2 `-q` 抑制 surefire 摘要

`mvn -q test` 在成功时**不打印**测试摘要，导致 V0/V6/V7/V8 被判为 INVALID。改为直接读 `target/surefire-reports/com.hmdp.ReservationBatchLifecycleTest.txt`。这也是「从日志文本推断执行情况」不可靠的第二个例证。

### 6.3 测试内插桩输出捕获不到

两次尝试在多线程测试里插桩（`System.out` 与 `Files.write` 到 `/tmp/loser-msgs.txt`）都没拿到输出：surefire 的 XML 里**没有 `system-out` 元素**，`pom.xml` 也没有任何 surefire 配置。改用哨兵文件变体后证明插桩代码**确实被编译并执行了**（8 行哨兵、7 次拒绝）。

但拿到的数据恰恰暴露了一个**产品设计缺陷**：7 个失败方**全部**报「批次状态已变化，发布失败」——因为这个文案同时被 NX 拒绝分支和 CAS 拒绝分支复用，**从报错无法区分是哪一层拦下的**。这削弱了排障可观测性。之所以不在本轮改动它，是因为它不影响正确性，且改动会波及多处既有断言；已登记为 §8 的可观测性改进项。

### 6.4 Codex 用量上限事故

返工包 #2 首次派发时（在额度恢复点之前）被 Codex 侧的用量上限挡住。我**没有**改用低配模型或降级通道绕过——按硬性约定，执行层只允许 `gpt-5.6-sol` / `effort=high`，额度不足时的正确动作是等待而不是降级。经用户确认后改为等额度恢复再派发。

同期一次派发因遗漏 `threadId` 参数被 MCP 以 `-32602 Required at threadId` 拒绝，属调用形式错误，补上参数后正常。

---

## 7. Review 发现并要求返工的问题

### 7.1 第一轮返工（已完成并验证）

1. **H9**：`publish` 的 `SET` 改 `SET NX`，`DEL` 加所有权判定。
2. **H10**：`updateDraft` 加 `.eq(status, DRAFT)`；`publish` 条件 UPDATE 绑定六个快照字段。
3. **`/page` 状态白名单**：学生端不得出现 DRAFT / CANCELLED（用户裁定，见 §7.4），`?status` 非白名单值 → 400。
4. **测试补齐**：并发 publish 用例、白名单用例、`/manage/page` 归属用例；修正一处错误断言（`BookingState.NOT_STARTED` → `DRAFT`——草稿批次的派生状态是 `DRAFT`，不是 `NOT_STARTED`）。

返工后：`mvn test` 49 run / 0 failure；§3 两组实验由「复现」转为「证明修复」；§4 中 V1–V5 与 V9–V15 全部恢复判别力。

### 7.2 第二轮返工（覆盖 §4 的三处空洞）

| 编号 | 内容 | 判别实验（必须让新测试变红） |
| --- | --- | --- |
| **R6** | 新增并发 publish × cancel 的**不变式**测试：≥20 轮，断言终态**永不为 `PUBLISHED`**；若为 `CANCELLED` 则 quota 键必须不存在 | V6 |
| **R7** | 新增并发 publish × updateDraft 的**不变式**测试：≥20 轮，断言终态必居其一（`DRAFT|99|99|<nil>` 或 `PUBLISHED|4|4|"4"`） | V7 **与** V8（两个守卫各需一个） |
| **R8** | `ttlSeconds <= 0` 分支的报错文案由「批次状态已变化，发布失败」改为语义准确的「批次时间设置不合法」（该分支在 I1+I4+I5 下不可达，保留为防御） | — |
| **R9** | 去掉学生端白名单与 `adminUserId` 的**隐式耦合**：`<if test="adminUserId == null">` 改为显式 `publicOnly` 参数，`queryBatchPage` 传 `true`、`queryManagePage` 传 `false` | — |

关于 R6/R7 的测试形态：明确要求写成**对所有交错都成立的不变式**，而不是「靠 sleep 侥幸命中某个交错」。这样在正确实现下它恒为绿（不 flaky），只有守卫缺失时才会红——判别力与稳定性可以同时拿到，不需要在两者之间取舍。

关于 R9：`adminUserId == null` 同时承担「这是学生端查询」这一个含义，是**隐式耦合**。当前正确，但若将来有第三个调用方传 `adminUserId = null` 却想看草稿，会**静默拿不到数据**——这类缺陷不报错，最难查。

**第二轮返工的验证结果**：见 §9——补掉了三个空洞中的两个（V6、V7），**V8 仍未覆盖**。

### 7.2.1 第三轮返工（R10）

第二轮暴露了 V8 不可证伪，因此追加一轮，**只改一个测试文件**：

- 把 R7 的放行方式从「同时放行」改为「**错开释放的扫描**」（`publish` 先放行，主线程自旋 0～2ms 六档后才放行 `updateDraft`），轮数 20 → 24；
- 把断言从「枚举两种合法终态」放宽为「**一致性不变式**」——这一条是接受 Codex 上一轮提出的意见，它指出的第三种合法态 `PUBLISHED|99|99|"99"`（等价于「先 PUT 再发布」）确实存在，枚举式断言会给它报假红。

这一轮的**设计先被原型化并逐个实测**（§9.1.3）才派发——因为它是本轮唯一的返工项，不能派一个我没验证过判别力的设计。结果：三个空洞全部关闭（§9.1.4）。

### 7.3 越界改动审查

Codex 在返工中自行加入的两处改动被单独审查：

- **`publish` 绑定六个快照字段** → **接受**，理由见 §3.2，且实测证明承重。
- **mapper 里 `<if test="adminUserId == null">` 的白名单写法** → **接受行为、要求改形式**（→ R9）。

不存在越出 17 个文件之外的改动。

### 7.4 需要用户裁定的问题

| 问题 | 用户裁定 |
| --- | --- |
| `GET /reservation-batch/page`（学生端）是否应排除 DRAFT 和 CANCELLED？ | **排除，只返回 PUBLISHED 和 CLOSED**（F1） |
| Codex 额度未恢复时如何推进 5.2 返工？ | **等恢复再派发**（不降级模型） |

**F1 落地的两点补充**：(1) `?status` 传非白名单值返回 400 而非静默忽略，避免「以为筛了其实没筛」；(2) 设计文档原话「返回全部批次」被明确为**「跨全部实验室」而非「全部状态」**——原表述有歧义，这是本轮澄清掉的。

---

## 8. 登记不修的问题（本 Phase 不解决）

| 编号 | 问题 | 为什么不修 |
| --- | --- | --- |
| **F3** | `/laboratory/list` 与 `/laboratory/{id}` 是公开读端点，直接序列化实体，会把新增的 `adminUserId` 暴露给任何已登录用户 | 属信息暴露而非越权（暴露的是「谁管这个实验室」，不构成写权限）；修法（返回 VO 或 `@JsonIgnore`）会改动 Phase 2 的既有接口面，留给最终清理 Phase |
| **F4** | `DELETE /laboratory/{id}` **不检查是否存在依赖设备/批次** | **这是 Phase 2 遗留的既有缺口，不是本 Phase 引入的**。本 Phase 的批次表让后果更实（删实验室会留下孤儿批次），但修它属于 Phase 2 的接口契约变更，不在本 Phase 范围 |
| **可观测性** | `publish` 的 NX 拒绝与 CAS 拒绝复用同一句报错文案（§6.3） | 不影响正确性；改动会波及多处既有断言 |
| **P2-7** | `秒杀消息队列迁移RabbitMQ-交付报告.md:12` 含明文本地口令，且**已在提交 `433cdfb` 中** | 改工作区文件无法消除历史记录；必须在 Phase 10 做历史重写或口令轮换，早于此处理没有意义 |
| **遗留队列** | broker 侧残留的 `seckill.order.*` 队列与交换机 | 属运行时资源而非代码，需单独授权；Phase 7 会重建消息链路，届时一并处理 |

---

## 9. 第二轮返工验证结果

| 验收项 | 结果 | 证据 |
| --- | --- | --- |
| 改动面 | ✅ 4 个文件，与返工包一致 | 生产文件哈希与第一轮返工后逐字节一致；R8/R9 已落地 |
| `mvn test` | ✅ **Tests run: 51, Failures: 0, Errors: 0** | 本地复跑（Codex 沙箱 42 个集成测试全部被 `Operation not permitted` 阻断，仅 `BookingStateTest` 可执行） |
| R6 判别力 | ✅ 打红 | V6（拿掉 publish 的状态 CAS）→ `shouldNeverResurrectCancelledBatchWhenPublishAndCancelRace` |
| R7 判别力 | ⚠️ 只覆盖 V7，**未覆盖 V8** | V7 打红；V8 拿掉后 20 轮全绿，**放大到 200 轮仍全绿** |
| R8 文案 | ✅ | `批次时间设置不合法`，与「状态已变化」区分开 |
| R9 显式参数 | ✅ | `@Param("publicOnly") boolean publicOnly`；`queryBatchPage` 传 `true`、`queryManagePage` 传 `false`；XML 改为 `<if test="publicOnly">` |
| V2/V3/V4 回归 | ✅ 均打红 | R9 改了 mapper 签名，这三组变体的模式已重建并重跑 |

**结论：三个空洞补掉了两个。** V8（`updateDraft` 的 `.eq(status, DRAFT)`）仍然不可证伪。

---

## 9.1 第三轮返工（R10）：把 V8 也变成可证伪

### 9.1.1 为什么 V8 打不红——先排除「轮数不够」

V8 拿掉后 20 轮全绿，我先把轮数放大到 **200 轮**（实验 EXP-A），仍全绿。所以不是窗口窄，是**时序结构性压制**：

要命中 V8 的窗口，需要 `updateDraft` 的 UPDATE 落在 `publish` 的 UPDATE **之后**（这样 `publish` 已把行改成 `PUBLISHED`，`updateDraft` 的无条件 UPDATE 才会写进已发布的批次）。但 `publish` 在它的 UPDATE 之前多一次 Redis 往返回合，**恒更慢**。两个守卫因而是互补的：谁先提交，就由对面那侧的 CAS 兜住。

### 9.1.2 一次归因错误（我自己的）

我做了「V7+V8 一起拿掉」的实验（EXP-B），得到撕裂行 `PUBLISHED|99|4|4`。我最初以为这能同时证明两个守卫——**错了**。`total=99` 来自 `updateDraft`、`remaining=4` 来自 `publish`，说明顺序是 **updateDraft 先提交、publish 后提交**，因此这个签名**只归因于 V7**，对 V8 什么也没证明。修正是靠把签名逐字段回溯到各自的写入方做出的。

### 9.1.3 关键的实验洞察：窗口要的形状是「先读后写」

V8 需要的顺序是「`updateDraft` 先读（这样它看到 DRAFT、通过前置检查）、后写」。而同时放行给出的是「`updateDraft` 既先读又先写」。**把 updateDraft 的放行时点稍微错开**，恰好就是需要的形状——它的读还在 `publish` 提交之前，写被推到了之后。

在派发返工包之前，我先把目标形态原型化并逐个实测（这是本轮唯一的返工项，不能派一个我没验证过的设计）：

| 实验（错开释放 + 放宽断言） | 结果 |
| --- | --- |
| 原样代码 | ✅ 全绿，不引入 flakiness |
| V7 拿掉 | ✅ 打红 `PUBLISHED\|99\|4\|4` |
| V8 拿掉 | ✅ 打红 `PUBLISHED\|99\|99\|4` |

两条签名可区分，可据失败信息判断是哪一侧守卫缺失。

### 9.1.4 R10 落地与验收

**R10 内容**：(a) 把 R7 的「同时放行」改为**错开释放的扫描**（`publish` 先放行，主线程用 `System.nanoTime()` 自旋 0/100µs/200µs/500µs/1ms/2ms 六档后才放行 `updateDraft`），轮数 20 → **24**（使 6 档各被均匀行使 4 次）；(b) 把断言从「枚举两种终态」放宽为**一致性不变式**。

**硬约束的依据**：错开量必须子毫秒精度。`Thread.sleep` 在 macOS 上粒度约 1ms 以上，会把 100µs/200µs/500µs 三档全部压成同一个 ~1ms 睡眠，扫描退化成单档，V8 又变回不可证伪。

| 验收项 | 结果 | 证据 |
| --- | --- | --- |
| 改动面 | ✅ **恰好 1 个文件**（仅测试） | 三个生产文件哈希与第二轮返工后逐字节一致 |
| `mvn clean test-compile` | ✅ BUILD SUCCESS | 独立复跑 |
| `mvn test` | ✅ **Tests run: 51, Failures: 0, Errors: 0** | 本地复跑 |
| V6 → R6 | ✅ 打红 | 变体 V6 |
| V7 → R7 | ✅ 打红 | 签名 `PUBLISHED\|99\|4\|4` |
| V8 → R7 | ✅ 打红 | 签名 `PUBLISHED\|99\|99\|4` |
| 不 flaky | ✅ **25 遍 × 24 轮 = 600 轮 R7 + 600 轮 R6，零失败** | 重复执行 |
| 行为未变（R9 回归） | ✅ | §9.2 HTTP 复验 |

**三处覆盖空洞至此全部关闭。** §4 变体台账中原本标 ❌ 的三行，现在都能打红。

### 9.2 R9 之后的 HTTP 端到端复验

R9 动的是查询路径（mapper 签名 + XML 条件），必须在控制器级确认行为未变：

```text
GET /page 不含 DRAFT、不含 CANCELLED、含 PUBLISHED         ✅
GET /page?status=PUBLISHED → 200    ?status=CLOSED → 200    ✅
GET /page?status=DRAFT     → 400 {"success":false,"errorMsg":"批次状态不合法"}  ✅
GET /manage/page（本人）含自己的 DRAFT 与 CANCELLED          ✅
GET /manage/page（B）不含 A 的批次                           ✅
GET /manage/page 未登录 → 401                                ✅
```

### 9.3 关于「放宽断言会不会削弱判别力」的核验

放宽只接受三类状态：`DRAFT|99|99|<nil>`、`PUBLISHED` 且 `total==remaining` 且 `redis==total`。两个真实撕裂签名都违反它（前者 `remaining≠total`，后者 `redis≠total`），实测均打红。**放宽的是「合法交错的枚举」，收紧的是「内部一致性」，判别力没有损失**——这也是 §4 三行 ❌ 能同时被关闭的原因。

---

## 10. 数据与环境复原

| 项 | 状态 |
| --- | --- |
| `tb_reservation_batch` | 0 行 |
| 设备 1–12 / 实验室 1–4 | 与 Phase 0 基线逐字段一致 |
| 实验室归属种子 | 实验室 1/2 → 管理员 1016；实验室 3/4 → 管理员 1019 |
| 孤儿实验室 | 0 |
| `reservation:*` Redis 键 | 0 个 |
| 实验产生的 `login:code:*` / `login:token:*` | 已清空 |
| 端口 18081 | 已释放 |
| 用户表 | 1013 = 1011 USER + 2 LAB_ADMIN；**max id = 1019**，无 >1100 的残留测试用户 |

**一处基线文档的偏差（非缺陷）**：`PHASE5-DESIGN.md` 记的「1012 个用户」**早于** 5.1 的归属种子，1019 这个管理员是 5.1 自己建的（`2026-10-04 12:32:29`）。已核实为时间差而非数据污染。

**5.0 验收口径修正**：原稿写的「`grep -ri "voucher" src/` 结果为零」过严——`src/main/resources/db/hmdp.sql` 是**按设计保留**的原始建库脚本，含历史表结构。正确的口径是 **production Java / Lua 零残留**，已按此口径核验通过。

---

## 11. 已知局限

1. **`mvn test` 里 40 个 Spring 集成测试在 Codex 沙箱中无法执行**（沙箱网络禁 localhost、`.git` 只读）。因此这些测试的绿灯**只由我在本机复跑提供**，Codex 自报的测试结果不构成证据。这是本闭环固定的分工，不是本次的偶发问题。
2. **并发测试的判别力依赖时序运气**。V6 的运行时实验 3/18 命中，说明真实窗口是「窄但可达」。不变式形式的测试在正确实现下恒绿、在有缺陷时**高概率**红而非必然红——这是并发测试的固有局限，不能通过增加轮数消除，只能降低漏检概率。轮数取 ≥20 是在「跑得快」与「漏检率低」之间取的折中。
3. **本 Phase 只做到「发布时把名额写进 Redis」**。Redis 与 DB 之间的最终一致性、消息可靠性、幂等消费全部是 Phase 6–8 的内容，本 Phase 不做，也不应据此评价。

---

## 12. 结论

Phase 5 的三个子阶段（5.0 遗留清理、5.1 实验室归属、5.2 批次生命周期）均达成设计目标，验收标准逐条通过。

本 Phase 的价值主要不在「写了多少功能」，而在**两个并发缺陷是被运行时实验打出来的，而不是被代码审阅推演出来的**——H9 与 H10 在「测试全绿」的状态下真实存在。而 H12 更进一步：它是一个**守卫正确、但完全没有测试覆盖**的缺陷，只能通过「拿掉守卫看测试会不会红」这种反向实验发现。

`mvn test` 全绿**不是**质量证据。Phase 5 的复核过程本身就是这句话的证明。
