# Phase 8 · T7-R 返工任务包（DLQ 治理 API 三项收口）

> 派发方式（硬性约定）：MCP `codex-reply` 续 thread `01a10b54-0f6a-7240-be30-8de0f238062e`，
> 沿用 `gpt-5.6-sol` / `effort=high` / `sandbox=workspace-write` / `cwd=/Users/eason/Desktop/hm-dianping`。
> 集成测试由 Claude 侧执行；Codex 沙箱连不上本机 MySQL/Redis/RabbitMQ，**不得声称跑过集成测试**。

## 背景：T7 首版验收结果（Claude 侧实测，非 Codex 自述）

**通过的部分**（无需返工，不要改动）：

- `ReservationDlqGovernanceTest` 8 run / 0 failures / 0 errors / 0 skipped；
  `ReservationMqTest` 21 run / 0 failures / 0 errors / 0 skipped；`mvn` 退出码 0。
- 零触碰清单全部文件 SHA-256 与派发前一致；只写了允许的 8 个路径。
- `replay` 无 `@Transactional`；三个端点均有 `@RequireRole(UserRole.LAB_ADMIN)`。
- 七步顺序与 §6.2 一致，步 1–5 全在投递之前。
- 判别力实验：删掉步 4 → 只有 `shouldDiscardAlreadyPersistedWithoutPublishing` 变红；
  删掉步 5 → 只有 `shouldDiscardCompensatedRequestWithoutPublishing` 变红。**两处校验承重、测试有判别力。**

**需要返工的三项**如下。

## R1（测试清理泄漏 —— 已实测复现，必须修）

**现象**：判别力实验的 F7c 变体（删掉步 2 的「重放次数上限」校验）除了目标用例变红，
还让 `shouldPageByStatusNewestFirstWithoutPayload` **连带变红**：`expected: <2> but was: <3>`。

**根因（日志证据，非推测）**：该变体下 `shouldRejectMissingHandledAndReplayLimitRowsWithoutChangingThem`
真的把「已耗尽」的那条报文投递了出去；因为测试没有为它插账本行、也没有对应批次，
主消费者落库失败（`QUOTA_CONFLICT`）后把报文 NACK 进死信通道，
**`ReservationDlqConsumer` 于是插入了一行新的 `tb_reservation_dlq` 记录**。
但 `ReservationDlqGovernanceTest.cleanUp()` 只按 `dlqIds` 里**显式追踪**的 id 删除，
这行"系统自己造出来的"台账行无人删除 → 泄漏到下一个用例 → `getTotal()` 多 1。

**为什么必须修**：交付版本目前恰好没有用例会触发它（唯一会投递的用例投的是能成功落库的报文），
所以今天全绿；但（a）任何后续新增用例只要让系统产生死信行就会互相污染，
（b）它使我用来**判定"测试是否有判别力"的实验本身不可靠**（无法区分"目标红"与"被泄漏污染的红"）。

**要求**：把清理改成**基线水位法**，与同项目 `ReservationMqTest` 既有做法对齐
（那里用 `dlqBaselineId` + `delete(... gt(id, baseline))`）：
在 `@BeforeEach` 中记录当前 `tb_reservation_dlq` 的最大 id（或先查出基线，
参考 `ReservationMqTest` 的写法），`cleanUp()` 改为删除 `id > baseline` 的**全部**行，
而不是只删 `dlqIds`。`reservationIds` / `requestIds` / `batchIds` 的按 id 清理可以保留
（这些只由测试自己插入），但死信台账必须用水位法。

修改后请自查：`grep -n "dlqIds" ` 应不再出现在删除逻辑里（或仅用于其它用途）。

## R2（`appendDiscardReason` 截断顺序会截掉 DISCARD 标记 —— 必须修）

**问题**：现有实现是「先拼接 `原值 + " | " + "DISCARD:" + code`，再整体截断到 512」。
`failure_reason` 列是 `varchar(512)`（见 `db/phase8_consistency.sql`），
且 `recordDeadLetter` 也把原值截到 512，所以**原值长度 480–512 是可达状态**。
此时追加的标记会被截掉。已实测的边界：

| 原值长度 | 结果 | `contains("DISCARD:PAYLOAD_NOT_REPLAYABLE")` |
| --- | --- | --- |
| 10 | 43 字符 | true |
| 500 | 512 字符，但 code 被截成 `PAYLOAD_NOT_REPL` | **false** |
| 512 | 512 字符，标记整段消失 | **false** |

**这条同时是任务包 C4 自身的规格矛盾**（我的错）：当原值本身已达 512 时，
"总长 ≤512"与"原值完整保留为前缀"不可能同时成立。**Claude 裁定方向（见设计 D31）**：
> **`DISCARD:<code>` 必须完整出现；为了让位，允许把原值截得更短。**
> 理由：`failure_reason` 在**落库时就已经被截到 512**，它本来就是一个有损字段；
> 而治理结论（为什么被判不可重放）是这一步**新增的、管理员唯一可检索的信息**，绝不能丢。
> 因此"原值是结果的前缀"仍然保持（只是可能比原值短），但"原值逐字完整"让位于"标记完整"。

**要求**：
1. 先算出后缀 `" | DISCARD:" + code` 的长度，把原值截到 `512 - 后缀长度` 再拼接，
   保证结果总长 ≤512 且**完整包含** `DISCARD:<code>`；原值仍是结果的前缀。
2. 保留 `MAX_FAILURE_REASON_LENGTH = 512` 这一常量与列宽一致，不要新增魔法数。
3. 该行为要写进方法的注释（说明为何是"截原值保标记"而不是反过来）。

## R3（`@RequireRole` 在详情/重放端点上零测试覆盖 —— 必须补）

**问题**：`shouldGuardAllDlqEndpointsWithLabAdminRole` 只测了 `GET /reservation-dlq/page`
的「无 token → 401」与「LAB_ADMIN → 200」。
`GET /reservation-dlq/{id}` 与 `POST /reservation-dlq/{id}/replay` 上的
`@RequireRole(UserRole.LAB_ADMIN)` **没有任何测试**：把这两个方法上的注解删掉，全部测试仍会全绿。
而这是一个会**返回死信完整 payload**、并能**触发真实消息重投**的管理接口，鉴权正是它最主要的风险面。

**要求**：把守卫测试扩到**三个端点**，并补一个**角色不符 → 403** 的用例：
- 无 `authorization` 头 → 三个端点均 401；
- 会话 `role=LAB_ADMIN` → 三个端点均 200；
- 会话 `role=USER`（`UserRole` 里非管理员的值；`RoleInterceptor` 对该情形置 403）→ 三个端点均 403。

提示：`POST /{id}/replay` 的 200 分支会真的走重放逻辑，请用一个**必然被拒但不会抛异常**的 id
（例如 `Long.MAX_VALUE`，即"记录不存在"→ `Result.fail`，HTTP 仍为 200 / `success=false`），
以免测试依赖真实投递。

## 硬性边界（与首版相同，不得放宽）

- 集成测试你**跑不了**，只允许 `export JAVA_HOME=/Users/eason/Library/Java/JavaVirtualMachines/corretto-1.8.0_504/Contents/Home && mvn -q clean test-compile`。
- 生产代码**只允许改** `service/impl/ReservationDlqServiceImpl.java`（且只改 `appendDiscardReason`，其余方法语义不得变动）；
  测试**只允许改** `test/ReservationDlqGovernanceTest.java`。
- **零触碰**：`config/RabbitMQConfig.java`、`config/ReservationDlqListenerConfig.java`、
  `config/WebExceptionAdvice.java`（注意在 `config/` 不是 `utils/`）、`config/MvcConfig.java`、
  `mq/ReservationMessageConsumer.java`、`mq/ReservationDlqConsumer.java`、
  `utils/RoleInterceptor.java`、`service/impl/ReservationServiceImpl.java`、
  `resources/reservation.lua`、`resources/db/phase8_consistency.sql`、`test/ReservationMqTest.java`、
  `pom.xml`、以及 T7 首版已交付的 DTO/VO/Controller/接口文件（无需再动）。
- 不引入新依赖；不加 `@Disabled`；不删改既有用例的断言（只做增量与 R1 的清理改造）。

## 验收标准

- AC1 `mvn -q clean test-compile` 退出码 0（JDK 8）。
- AC2 T7 首版已通过的 8 条用例**保持通过**（Claude 侧会跑）。
- AC3 R1：`cleanUp` 用水位法删死信台账；Claude 侧会用 F7c 变体复验——改造后
  **只有**目标用例变红，不再连带污染 `shouldPageByStatusNewestFirstWithoutPayload`。
- AC4 R2：新增用例——插入 `failure_reason` 长度为 **512** 的台账行，
  断言重放被丢弃后 `failure_reason` **完整包含** `DISCARD:PAYLOAD_NOT_REPLAYABLE`；
  该用例在修复前必红。
- AC5 R3：守卫测试覆盖三端点 × {401, 200, 403}。

## 汇报要求

改动文件清单 + 每处改动的关键点 + `test-compile` 结果 + 对 R1/R2/R3 的自查 + 仍存在的不确定性。
**不要声称跑过集成测试。**
