# Phase 5 Design — ReservationBatch 预约批次（含 P1-9 实验室归属约束）

状态：**待用户批准**
依据：`实验资源预约平台-分阶段重构Phase计划.md` §8 + `黑马点评 → 实验资源预约平台重构.md` §10/§23/§24 + Phase 3 Review 登记的 P1-9
日期：2026-10-04

---

## 0. 本 Phase 的交付与一个必须先做的判断

Phase 5 有三件事，**必须按 5.0 → 5.1 → 5.2 的顺序做**，因为后一件依赖前一件：

| 阶段 | 内容 | 为什么不能调换 |
| --- | --- | --- |
| **5.0** | 拆除遗留 `Voucher` / `SeckillVoucher` / `VoucherOrder` 垂直切片 | 不删则 `ReservationBatch` 与「优惠券」两套业务模型共存，且 `VoucherOrderServiceImpl` 焊死在 `SeckillVoucher` 上，编译不过 |
| **5.1** | 实验室归属模型（**P1-9**）：`tb_laboratory.admin_user_id` + 归属守卫 + 7 个既有管理端点补校验 | 它要改 **Phase 3 已存在的 7 个端点**；若放在 5.2 之后，新写的批次端点会有一段时间无归属校验 |
| **5.2** | `ReservationBatch` 领域模型 + 状态机 + 管理端 API + 发布时 Redis quota 初始化 | 新端点在编写时就带上归属校验，不需要回补 |

**必须先做的判断**：计划 §8 说「彻底将 Voucher / SeckillVoucher 业务模型替换为 ReservationBatch」，但 `VoucherOrder` 计划映射到 `Reservation`（Phase 7）。而 `VoucherOrderServiceImpl` 的实现**强依赖** `SeckillVoucher`：

```java
seckillVoucherService.update().setSql("stock = stock - 1")
        .eq("voucher_id", voucherId).gt("stock", 0).update();
```

**只要删掉 `SeckillVoucher`，`VoucherOrder` 的下单链路必然一起编译失败。** 所以本 Phase 必须决定：是把 `VoucherOrder` 全家桶一起删，还是给它留一个空壳。

**决定：一起删。** 理由见 §1.3。这是本设计中最需要用户确认的一条（见 §13 请批项）。

---

## 1. 遗留 voucher / 秒杀垂直切片的处置（5.0）

### 1.1 删除清单（共 16 个文件）

| 类别 | 文件 |
| --- | --- |
| 实体 | `entity/Voucher.java`、`entity/SeckillVoucher.java`、`entity/VoucherOrder.java` |
| Mapper | `mapper/VoucherMapper.java`、`mapper/SeckillVoucherMapper.java`、`mapper/VoucherOrderMapper.java` |
| Service | `service/IVoucherService.java`、`service/ISeckillVoucherService.java`、`service/IVoucherOrderService.java` |
| ServiceImpl | `service/impl/VoucherServiceImpl.java`、`service/impl/SeckillVoucherServiceImpl.java`、`service/impl/VoucherOrderServiceImpl.java` |
| Controller | `controller/VoucherController.java`、`controller/VoucherOrderController.java` |
| Lua | `resources/seckill.lua`、`resources/seckillRollback.lua` |
| 配置 | `config/RabbitMQConfig.java`（见 §1.2） |

**同时清理的常量与配置**：

- `RedisConstants.SECKILL_STOCK_KEY`（删除；`seckill:stock:` 命名正式退出）
- `MvcConfig` 中 `LoginInterceptor` 的 `excludePathPatterns("/voucher/**")`（删除）

> `/voucher/**` 曾在**免登录白名单**里。删除后未登录访问 `/voucher/**` 由「404」变为「401」。
> 这是**有意**的行为变化：该路径已无任何 Controller，把它留在白名单里等于给未来任何 `/voucher/**` 开免登录后门。
> 属于**缩小**攻击面，Review 必须实测确认（T10）。

### 1.2 `RabbitMQConfig` 为什么也删（而不是改名保留）

删掉 `VoucherOrderServiceImpl` 后，`RabbitMQConfig` 里**只剩 **`seckill.order.*` 的交换机/队列/绑定，**零生产者、零消费者**。

替代方案「把常量改名为 `reservation.*` 留着」被否决：

1. **与 Phase 4 已确立的原则冲突**。Phase 4 §3 否决「方案 B 逻辑过期」的理由正是「不建零消费者的预热设施」；
   现在建一个零生产零消费的队列拓扑，是同一个毛病。
2. **计划 §8 的验收是「Voucher 业务语义正式退出」**，而 `RabbitMQConfig` 通篇是 seckill 命名。
   只改常量名、不改语义= 用户明令禁止的「机械改名」。
3. **Phase 7 本来就要重新决定拓扑**（是否需要 TTL 队列、优先级、消费者并发、重试策略）。
   提前冻结拓扑反而限制 Phase 7 的设计空间。

**但这不等于扔掉这段工程经验**——Phase 5 必须把 `RabbitMQConfig` 现有关键设计**逐条记录**，作为 Phase 7 的输入（§11）。

**`application.yaml` 的 `spring.rabbitmq` 配置块保留**（Phase 7 需要，删除属机械删除）。

### 1.3 为什么 `VoucherOrder` 不保留到 Phase 7

计划 §4「暂时不要删除」把 `VoucherOrder` 列为「→ Reservation 的迁移参照」，Phase 1 因此保留了它。
现在到了要删的时刻，三个理由支持一起删：

1. **编译强制**：见 §0，`SeckillVoucher` 一走，`VoucherOrder` 的链路必然断。
2. **参照价值已经用尽**：`tb_voucher_order` 的字段与状态码已在 `docs/refactor/PHASE0-BASELINE.md` 与 git 历史（提交 `433cdfb`）中完整留存；
   保留一个**无 Service、无 Controller、只有 Entity+Mapper 的空壳**，是 Phase 10 一定会登记的残留。
3. **留空壳 = 留错误示范**：一个 `@TableName("tb_voucher_order")` 的实体挂在代码里，会让后来者以为它还是有效模型。

### 1.4 明确不做的（防止越界删除）

- **不 DROP** `tb_voucher` / `tb_seckill_voucher` / `tb_voucher_order` 三张表。
  沿用用户 2026-10-04 在 Phase 1 的裁决「**只删代码**」（同 Phase 1 的 `tb_blog` 等三表）。表结构删除登记到 Phase 10。
- **不删** `HmDianPingApplicationTests` 的 `RedisIdWorker` 测试（Phase 6 还要复用 `RedisIdWorker`）。
- **不删** `application.yaml` 的 `spring.rabbitmq` 配置块。

---

## 2. 实验室归属模型（5.1，关闭 P1-9）

### 2.1 现状缺陷

Phase 3 只做了**角色**校验：`RoleInterceptor` 看 `UserDTO.role` 是不是 `LAB_ADMIN`。
结果：**任意一个 `LAB_ADMIN` 可以修改任意实验室、任意设备**。而计划 §6 明写「管理**自己负责的**实验室」。

Phase 3 Review 登记为 P1-9，用户 2026-10-04 裁决**归属模型推迟到 Phase 5**。

### 2.2 数据模型：一实验室一负责人

```sql
ALTER TABLE `tb_laboratory`
  ADD COLUMN `admin_user_id` bigint unsigned NULL COMMENT '负责管理员用户id',
  ADD KEY `idx_admin_user_id` (`admin_user_id`);
```

| 设计选择 | 决定 | 理由 |
| --- | --- | --- |
| 归属放在 `tb_laboratory.admin_user_id`，而非 `tb_laboratory_admin` 中间表 | ✅ 单列 | 「小而深」：一个实验室一个负责人是本科实验室的真实形态；一个管理员可负责多个实验室，单列天然支持（一对多）。中间表要引入实体/Mapper/Service，收益为零 |
| 可空 | ✅ NULL 允许 | 历史实验室（Phase 2 的 4 个）在迁移前没有负责人；`NULL` = 未分配 = **任何人都不能管理**（而不是"所有人能管理"） |
| 多对多 | ❌ 不做 | 显式登记为非目标（§12.5） |

### 2.3 两层校验：角色在拦截器，归属在 Service

| 层 | 位置 | 判据 | 失败码 | 是否需要 DB |
| --- | --- | --- | --- | --- |
| 第一层：角色 | `RoleInterceptor`（**不改**） | `UserDTO.role == LAB_ADMIN` | 403（空体，Phase 3 既有行为） | 否 |
| 第二层：归属 | **Service 层**（新增） | `lab.admin_user_id == 当前用户 id` | 403（带 `Result.fail` 消息） | 是 |

**为什么归属必须在 Service 层而不是 `HandlerInterceptor`？**

拦截器只能拿到**路径参数**。但归属校验需要"目标资源 → 实验室 → 负责人"这条链，且不同端点的入口不同：

- `PUT /equipment/{id}/status` → 路径有 id，可查设备
- `POST /equipment` → 目标实验室在**请求体**里，拦截器看不到（除非解析 body，那等于把业务逻辑塞进拦截器）
- `POST /laboratory` → 根本无既有资源可查（新建）

**结论**：写一个「按资源反查实验室」的拦截器只能覆盖一半端点，而且会在拦截器里塞进 MyBatis 调用。不如把它放回领域服务。

### 2.4 归属守卫

`ILaboratoryService` 新增一个方法（`EquipmentServiceImpl` 已注入 `ILaboratoryService`，无新增依赖）：

```java
/**
 * 断言当前登录用户是目标实验室的负责人。
 * 失败与"实验室不存在"统一抛 ForbiddenException，不区分——避免向非负责人泄露资源是否存在。
 */
void assertManageable(Long laboratoryId);
```

```java
@Override
public void assertManageable(Long laboratoryId) {
    UserDTO user = UserHolder.getUser();
    if (user == null) {
        throw new ForbiddenException("无权管理该实验室");   // 兜底：正常已被 LoginInterceptor 拦下
    }
    Laboratory laboratory = getById(laboratoryId);
    if (laboratory == null || !user.getId().equals(laboratory.getAdminUserId())) {
        throw new ForbiddenException("无权管理该实验室");
    }
}
```

新增 `com.hmdp.exception.ForbiddenException extends RuntimeException`，
并在 `WebExceptionAdvice` 增加一个**更精确**的 handler（Spring 优先匹配具体类型，不影响既有的 `RuntimeException` → 500）：

```java
@ExceptionHandler(ForbiddenException.class)
@ResponseStatus(HttpStatus.FORBIDDEN)
public Result handleForbidden(ForbiddenException e) {
    log.warn("归属校验未通过：{}", e.getMessage());
    return Result.fail(e.getMessage());
}
```

### 2.5 端点归属矩阵（Phase 3 已标注的 7 个 + 新建的）

| # | 端点 | 角色（不变） | 归属校验（新增） |
| --- | --- | --- | --- |
| 1 | `POST /laboratory` | LAB_ADMIN | **无**——创建者**自动成为** `admin_user_id`（服务端强制，忽略请求体传入值） |
| 2 | `PUT /laboratory` | LAB_ADMIN | `assertManageable(body.id)`；**忽略请求体中的 `adminUserId`**（禁止自我转移） |
| 3 | `DELETE /laboratory/{id}` | LAB_ADMIN | `assertManageable(id)` |
| 4 | `POST /equipment` | LAB_ADMIN | `assertManageable(body.laboratoryId)` |
| 5 | `PUT /equipment` | LAB_ADMIN | `assertManageable(现有设备的 laboratoryId)`；**若 `laboratoryId` 变更，还要 `assertManageable(新 laboratoryId)`**——否则可把设备"搬"进别人的实验室 |
| 6 | `PUT /equipment/{id}/status` | LAB_ADMIN | 先查设备 → `assertManageable(设备.laboratoryId)` |
| 7 | `DELETE /equipment/{id}` | LAB_ADMIN | 同上 |
| 8 | 批次全部写端点（§5） | LAB_ADMIN | 批次 → 设备 → `assertManageable(设备.laboratoryId)` |

**所有 GET 端点依旧不加 `@RequireRole`，也不加归属过滤**（登录即可读；批次的管理端列表见 §5.3）。

### 2.6 已知不做的

- **不提供「负责人转移」接口**：`adminUserId` 只能由 DDL/种子脚本设置。登记为边界（§12.4）。
- **不校验 `admin_user_id` 指向的用户是不是 `LAB_ADMIN`**：DB 层无外键、无约束，属数据治理问题，登记为边界。

---

## 3. ReservationBatch 领域模型（5.2）

### 3.1 一个批次是什么

> 某台实验设备在某个确定时间段开放固定数量的预约名额。

```text
设备：扫描电子显微镜（id=1，属实验室 1「材料科学实验中心」）
使用时间：2026-10-10 14:00 - 16:00
预约开放：2026-10-08 12:00
预约截止：2026-10-10 10:00
总名额：4
```

### 3.2 表结构

```sql
CREATE TABLE IF NOT EXISTS `tb_reservation_batch` (
  `id`                 bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `equipment_id`       bigint unsigned NOT NULL COMMENT '设备id',
  `start_time`         datetime NOT NULL COMMENT '使用开始时间',
  `end_time`           datetime NOT NULL COMMENT '使用结束时间',
  `booking_start_time` datetime NOT NULL COMMENT '预约开放时间',
  `booking_end_time`   datetime NOT NULL COMMENT '预约截止时间',
  `total_quota`        int NOT NULL COMMENT '总名额',
  `remaining_quota`    int NOT NULL COMMENT '剩余名额',
  `status`             varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/PUBLISHED/CLOSED/CANCELLED',
  `create_time`        timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`        timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_equipment_id` (`equipment_id`) USING BTREE,
  KEY `idx_status` (`status`) USING BTREE,
  KEY `idx_booking_window` (`booking_start_time`, `booking_end_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='设备预约批次';
```

**字段取舍说明**：

- **四个时间字段全部 `datetime`（业务时间）**，与 `create_time/update_time` 的 `timestamp`（系统时间）区分开：
  `timestamp` 有 2038 上限且受时区会话影响，不适合承载"2027 年秋季学期"这类业务排期。
- **不加 `booking_enabled`**：批次是否可约 = 状态 + 时间窗口，不需要第三个开关。
- **不加 `version` 乐观锁列**：本 Phase 无并发扣减（Phase 6 才引入），加空列 = 过度设计。
- **无外键**：与 Phase 2 既有三表一致（`tb_equipment` 也没有 FK 约束）。

### 3.3 实体与枚举

```java
@Data @EqualsAndHashCode(callSuper = false) @Accessors(chain = true)
@TableName("tb_reservation_batch")
public class ReservationBatch implements Serializable {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private Long equipmentId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime bookingStartTime;
    private LocalDateTime bookingEndTime;
    private Integer totalQuota;
    private Integer remainingQuota;
    private ReservationBatchStatus status;      // 全局 EnumTypeHandler 落库为字符串，与 Equipment.status 一致
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

```java
public enum ReservationBatchStatus { DRAFT, PUBLISHED, CLOSED, CANCELLED }
```

---

## 4. 状态机（计划要求「Agent 应进一步检查是否可以简化」）

### 4.1 简化结论：把「时间派生」的状态从持久化状态里拿掉

计划给了 5 个状态：`DRAFT / WAITING / OPEN / CLOSED / CANCELLED`。**`WAITING` 与 `OPEN` 不能持久化**：

> 它们是 `now` 与 `[bookingStartTime, bookingEndTime)` 的函数。要持久化就必须有一个**定时任务**在正确时刻翻转它们，
> 否则 `booking_end_time` 已过、`status` 仍写着 `OPEN`——这正是源文档 §24「问题 1」的翻版（旧 Lua 不检查时间窗口）。
> 而定时任务（XXL-JOB 类）是计划明确禁止的过度设计。

**所以：持久化 4 态，派生 6 态。**

### 4.2 持久化状态机

```text
                   publish                close
        DRAFT ─────────────▶ PUBLISHED ─────────────▶ CLOSED（终态）
          │                     │
          │ cancel              │ cancel
          ▼                     ▼
      CANCELLED（终态）      CANCELLED（终态）
```

| From | 动作 | To | 前置条件 |
| --- | --- | --- | --- |
| DRAFT | `update` | DRAFT | **只有 DRAFT 可改**（见下） |
| DRAFT | `publish` | PUBLISHED | §4.4 全部不变式通过 |
| DRAFT | `cancel` | CANCELLED | — |
| PUBLISHED | `close` | CLOSED | 手工停止接受预约 |
| PUBLISHED | `cancel` | CANCELLED | — |
| CLOSED / CANCELLED | — | 终态 | 不允许任何变更 |

**语义区分（Phase 9 才会产生实际后果，本 Phase 只落状态）**：

- `CLOSED` = 停止接受**新**预约；已产生的预约**仍然有效**，照常履约。
- `CANCELLED` = 批次作废；已产生的预约在 Phase 9 应被释放/通知。

### 4.3 派生状态 `BookingState`（不入库，查询时计算）

```java
public enum BookingState { DRAFT, NOT_STARTED, OPEN, EXPIRED, CLOSED, CANCELLED }
```

```java
public static BookingState of(ReservationBatchStatus status,
                              LocalDateTime bookingStartTime,
                              LocalDateTime bookingEndTime,
                              LocalDateTime now) {
    if (status == ReservationBatchStatus.DRAFT)     return DRAFT;
    if (status == ReservationBatchStatus.CLOSED)    return CLOSED;
    if (status == ReservationBatchStatus.CANCELLED) return CANCELLED;
    if (now.isBefore(bookingStartTime))             return NOT_STARTED;
    if (!now.isAfter(bookingEndTime))               return OPEN;   // 闭区间：截止时刻当秒仍可预约
    return EXPIRED;
}
```

- **纯函数**（`now` 作参数传入）= 可以写**不依赖 Spring/DB/系统时钟**的确定性单元测试（T7）。
- `VO` 同时返回 `status`（持久态，管理端语义）与 `bookingState`（派生态，用户端语义），二者职责不同，**不是冗余**。

### 4.4 发布时必须成立的不变式

| # | 不变式 | 失败的后果 |
| --- | --- | --- |
| I1 | `totalQuota >= 1` | 零名额批次 |
| I2 | `startTime < endTime` | 使用时段为空/倒挂 |
| I3 | `bookingStartTime < bookingEndTime` | 预约窗口为空 |
| I4 | **`bookingEndTime <= startTime`** | 允许"预约一个已经开始的时段"——**这条是源文档 §24「问题 1」的直接防线** |
| I5 | `bookingEndTime > now` | 批次一发布就已过期，毫无意义 |
| I6 | 设备 `status == AVAILABLE` 且 `bookingEnabled == true` | 给维护中/未开放预约的设备发批次 |
| I7 | 同设备**不存在时间重叠**的非 CANCELLED 批次：`start < 候选.end AND end > 候选.start` | 同一台仪器同一时段被两个批次重复放出 |

> **I5 蕴含 `endTime > now`**：由 I4（`bookingEndTime <= startTime`）+ I5（`bookingEndTime > now`）得 `startTime > now`，再由 I2 得 `endTime > now`。
> 因此**不需要**单独校验 `startTime > now`——不引入冗余规则。
>
> **I7 的 TOCTOU**：两个管理员同时发布重叠批次时，检查与写入之间存在竞态窗口。本 Phase 用**条件更新**缩小但不能消除（见 §6.3），
> 显式登记为 Phase 8（引入 Redisson 后）处理。**不假装它是强一致的。**

---

## 5. 批次管理 API

### 5.1 写端点（全部 `@RequireRole(LAB_ADMIN)` + 归属校验）

| Method | Path | 语义 |
| --- | --- | --- |
| POST | `/reservation-batch` | 创建，**强制** `status=DRAFT`、`adminUserId` 类字段不可指定、`remainingQuota=totalQuota` |
| PUT | `/reservation-batch` | 修改草稿（**仅 DRAFT**）。只允许改 `equipmentId/startTime/endTime/bookingStartTime/bookingEndTime/totalQuota` |
| POST | `/reservation-batch/{id}/publish` | DRAFT → PUBLISHED，并初始化 Redis quota（§6） |
| POST | `/reservation-batch/{id}/close` | PUBLISHED → CLOSED，并删除 Redis quota |
| POST | `/reservation-batch/{id}/cancel` | DRAFT/PUBLISHED → CANCELLED，并删除 Redis quota |

**为什么用 `/publish`、`/close`、`/cancel` 三个动作端点，而不是 `PUT /{id}/status?status=X`（`EquipmentController` 的风格）？**

- `publish` 的副作用不止改状态（还要写 Redis、跑 7 条不变式、做重叠检查）；
- `close` / `cancel` 有**不同的**副作用与前置条件；
- 通用 status setter 会让客户端**直接指定 `CLOSED`** 从而绕过状态机（`DRAFT → CLOSED` 不是合法迁移）。

三条显式动作端点把合法迁移**编译进 API 形状**，比在 setter 里堆 if-else 更难写错。

**防"请求体越权改状态"**：`createBatch` / `updateDraft` **不得**直接 `save(body)` / `updateById(body)`——
`ReservationBatch` 含 `status` 与 `remainingQuota`，直接落库等于让客户端指定状态（同 Phase 3 已实测堵住的「内存对象陷阱」）。
必须构造**白名单字段**的更新对象。

**但白名单字段还不够，`updateDraft` 的写必须是条件写**（Review 期间修正，F2）：

初版实现是"先 `getById` 检查 `status == DRAFT`，再无条件 `updateById`"。检查与写入之间存在 TOCTOU 窗口：若 `publish` 在窗口内提交，草稿字段会被写进一个**已发布**的批次。运行时实验证伪：

> `publish` 与 `updateDraft` 并发对撞（草稿的 `totalQuota=99`），10 轮中复现 2 轮：
> - `PUBLISHED | total_quota=99 | remaining_quota=99`，而 Redis = `4`
> - `PUBLISHED | total_quota=99 | remaining_quota=4`，而 Redis = `4`（撕裂行）
>
> 即：已发布批次的名额被草稿值改写，DB 与 Redis 名额失配；由于 `updateDraft` 同样接受 `startTime`/`endTime`，
> 使用时段也是同一路径被改写的，§4.4 的 I7「同一设备时段不重叠」保证随之失效。

修正口径：`updateDraft` 的更新语句必须带 `WHERE id=? AND status='DRAFT'`，影响行数为 0 即抛错（"批次状态已变化，修改失败"）。
前置的 `getById` + 状态检查保留，但它只是快速失败与"仅草稿批次可修改"这条具体文案的来源，**不是**权威裁决点——权威裁决点是条件 `UPDATE` 本身。这与 `publish`/`close`/`cancel` 已有的条件更新风格一致。

注意条件写不得破坏部分更新语义：入参为 null 的字段应**跳过**（不写），不能落成 `NULL`（`updateById` 的默认 `NOT_NULL` 字段策略等价行为）；`totalQuota` 非 null 时同时把 `remainingQuota` 设为同一值，与创建/发布时的联动保持一致。

### 5.2 读端点

| Method | Path | 角色 | 说明 |
| --- | --- | --- | --- |
| GET | `/reservation-batch/{id}` | 登录 | 批次详情（VO，含派生 `bookingState`） |
| GET | `/reservation-batch/page` | 登录 | 按 `equipmentId` / `laboratoryId` / `status` 分页；**只返回 `status IN (PUBLISHED, CLOSED)`**（见下方 §5.2.1） |
| GET | `/reservation-batch/manage/page` | LAB_ADMIN | **只返回当前管理员负责的实验室**的批次（`JOIN tb_laboratory ON admin_user_id = 当前用户`） |

`/manage/page` 是归属模型在**读**路径上的体现——只查不写也算越权面。它同时是 T3 的观测点。

#### 5.2.1 `/page` 的状态白名单（Review 期间裁定，F1）

初版实现让 `/page` 无状态过滤，会一并返回 `DRAFT` 与 `CANCELLED` 批次（`ReservationBatchMapper.xml` 的 `<if>` 只在请求显式传 `status` 时才过滤）。裁定为**缺陷并修正**：

- 草稿是尚未发布的内部门表，不属于学生可见的场次列表；已取消批次是作废场次。两者都不该出现在学生端。
- `/page` 恒定只返回 `status IN (PUBLISHED, CLOSED)`；请求不带 `status` 时同样受此约束；请求带 `status` 时取交集，传入白名单外的取值（`DRAFT` / `CANCELLED`）**直接 400**（"批次状态不合法"），不静默返回空集——静默空集会让调用方分不清"没有批次"和"参数被丢弃"。
- `/manage/page` 不受此约束，仍可查全部状态：管理员必须能看到自己的草稿与已取消批次。
- `CLOSED` 保留在学生端可见，是因为"已关闭"是场次的终态语义（报名已截止），与"作废"不同；Phase 9 之前学生需要能看到它。

原设计措辞"返回全部批次"的本意是"跨全部实验室"，不是"跨全部状态"——此处措辞有歧义，以本节为准。

### 5.3 VO 与查询 DTO

```java
public class ReservationBatchVO implements Serializable {
    private Long id;
    private Long equipmentId;
    private String equipmentName;
    private Long laboratoryId;
    private String laboratoryName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime bookingStartTime;
    private LocalDateTime bookingEndTime;
    private Integer totalQuota;
    private Integer remainingQuota;
    private ReservationBatchStatus status;   // 持久态
    private BookingState bookingState;       // 派生态
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

public class ReservationBatchQueryDTO {
    private Long equipmentId;
    private Long laboratoryId;
    private ReservationBatchStatus status;
    private Integer current = 1;
}
```

**不缓存批次**（与 Phase 4 的设备详情缓存无关）：

- 计划 Phase 4 只要求缓存设备详情，Phase 5 未要求缓存；
- 批次的"热点读"是**抢名额**（Phase 6 走 Redis Lua，不读这个 VO）；
- 没有访问证据就加缓存 = 过度设计。若 Phase 6 之后出现真实热点再补。

---

## 6. 发布时的 Redis quota 初始化

计划 §8：「发布批次时，为后续高并发预约准备 Redis quota。但本阶段只建立批次生命周期，复杂 Lua 逻辑放到 Phase 6。」

### 6.1 Key 与 TTL

| 项 | 取值 |
| --- | --- |
| Key | `reservation:batch:quota:{batchId}`（String） |
| 值 | `remainingQuota` |
| TTL | `endTime + 24h - now`（秒） |
| 常量 | `RedisConstants.RESERVATION_BATCH_QUOTA_KEY`、`RESERVATION_BATCH_QUOTA_TTL_BUFFER_HOURS = 24L` |

**为什么必须给 TTL**：Phase 1 登记的 **P2-2「`seckill:stock:` / `seckill:order:` 无 TTL」** 就是这个坑。
新 Key 空间**不能重复**：批次不可再预约后（`endTime + 24h`），quota 键自动消失，不需要任何清理任务。

**为什么是 `endTime` 而不是 `bookingEndTime`**：预约窗口结束后 quota 对"抢名额"已无用，
但 Phase 8 的对账可能还要交叉核对，留 24 小时缓冲足够且不构成长期垃圾。

### 6.2 不变式

> **Redis quota 键存在 ⟺ 批次处于 `PUBLISHED` 且未超过 TTL。**

- `publish` → 写入
- `close` / `cancel` → 删除（`DEL` 幂等，DRAFT 本来就没有）
- TTL 到期 → 自动消失

### 6.3 `publish` 的执行顺序（顺序本身是设计决策）

```text
1. 取批次；status 必须为 DRAFT                      ← 否则直接失败
2. 归属校验：batch → equipment → assertManageable(equipment.laboratoryId)
3. 校验 I1–I7 全部不变式
4. 先写 Redis：SET reservation:batch:quota:{id} totalQuota EX <ttl> **NX**   ← NX 是强制的，不是可选
5. 再更新 MySQL：UPDATE ... SET status='PUBLISHED', remaining_quota=total_quota
                          WHERE id=? AND status='DRAFT'      ← 条件更新
   影响行数 != 1 → **仅当第 4 步的 SET NX 由本次调用成功写入时**才 DEL，然后返回失败
```

**为什么"先 Redis 后 MySQL"**：两种失败模式的代价不对称。

| 顺序 | 失败模式 | 后果 |
| --- | --- | --- |
| **先 MySQL**（❌） | MySQL 已 PUBLISHED，Redis 写失败 | 批次已发布但无 quota → 学生**全部预约失败**，且无自愈路径，必须人工修 |
| **先 Redis**（✅） | Redis 已写，MySQL 条件更新失败（并发发布/异常） | MySQL 仍是 DRAFT（**权威状态一致**），残留一个带 TTL 的 quota 键，TTL 到期自动消失 |

**`WHERE status='DRAFT'` 条件更新**：让"两个管理员同时点发布"只有一个成功，另一个影响行数为 0 而失败——
不需要分布式锁就能挡住最朴素的重复发布。

**但这个条件更新单独并不构成安全保证**，必须与第 4 步的 `NX` + "只 DEL 自己写的键"配套。初版实现漏掉了后者（无条件 `set` + 无条件 `delete`），被运行时实验证伪：

> 20 个并发 `POST /reservation-batch/{id}/publish` 打同一个 DRAFT 批次，实测结果：
> `tb_reservation_batch: PUBLISHED | total_quota=4 | remaining_quota=4`，
> 而 `EXISTS reservation:batch:quota:{id}` = **0**（键不存在），19 个失败方返回"批次状态已变化，发布失败"。
>
> 机理：19 个失败方都先执行了无条件 `SET`（覆盖成功方写入的同一个键，值相同因而无害），
> 再执行条件 `UPDATE`（影响 0 行），然后**无条件 `DELETE(key)`**——最后一个 DEL 落在成功方的 SET 之后，
> 于是把一个已发布批次的名额键删掉了。

这说明上面的失败模式表并不能靠"顺序"单独兑现：失败方对 Redis 的**写副作用**（`DEL`）会把 ✅ 顺序退化成 ❌ 顺序的后果——**已发布但无 quota 键**，学生全部预约失败且无自愈路径。`NX` 保证只有一个调用认为自己"拥有"这个键，而"只 DEL 自己写的键"保证失败方不会误删他方成果。二者缺一不可。

`SET NX` 返回 false 还有一层语义：说明键已存在，即并发中已有他方写入并即将/已经胜出。此时本调用不得 DEL，直接让条件 `UPDATE` 去裁决——若它意外成功（键来自更早的、已被回滚的发布），`NX` 也不会让本调用产生第二个写入者，键值仍与胜出方一致。

**这里没有 `@Transactional`**：只有一条 UPDATE 语句，本身就是原子的。
Redis 与 MySQL 之间的关系是**最终一致**，本 Phase **不做补偿/对账**（Phase 8 范围），
所以 `publish` 的补偿 `DEL` 也允许失败——登记为已知边界（§12.2）。

---

## 7. 改动范围

### 5.0 `refactor: remove legacy voucher and seckill modules`

| 动作 | 文件 |
| --- | --- |
| D | 16 个文件（§1.1） |
| M | `utils/RedisConstants.java`（删 `SECKILL_STOCK_KEY`） |
| M | `config/MvcConfig.java`（删 `/voucher/**` 免登录白名单） |

### 5.1 `feat: enforce laboratory ownership for lab admins`

| 动作 | 文件 |
| --- | --- |
| A | `resources/db/phase5_reservation_batch.sql`（本阶段先只含负责人字段 + 种子；表定义在 5.2 追加） |
| A | `exception/ForbiddenException.java` |
| M | `config/WebExceptionAdvice.java`（+1 handler） |
| M | `entity/Laboratory.java`（+`adminUserId`） |
| M | `service/ILaboratoryService.java`（+`assertManageable`） |
| M | `service/impl/LaboratoryServiceImpl.java`（实现 + 创建者即负责人 + 忽略请求体 `adminUserId`） |
| M | `service/impl/EquipmentServiceImpl.java`（4 个写方法前置归属校验） |
| M | `controller/LaboratoryController.java`（PUT/DELETE 走归属；POST 落到 Service 强制赋值） |

### 5.2 `feat: add reservation batch lifecycle`

| 动作 | 文件 |
| --- | --- |
| A | `entity/ReservationBatch.java`、`enums/ReservationBatchStatus.java`、`enums/BookingState.java` |
| A | `mapper/ReservationBatchMapper.java` + `resources/mapper/ReservationBatchMapper.xml` |
| A | `dto/ReservationBatchQueryDTO.java`、`vo/ReservationBatchVO.java` |
| A | `service/IReservationBatchService.java` + `service/impl/ReservationBatchServiceImpl.java` |
| A | `controller/ReservationBatchController.java` |
| M | `utils/RedisConstants.java`（+quota Key 与 TTL 缓冲常量） |
| M | `resources/db/phase5_reservation_batch.sql`（追加建表） |
| A | 测试（§9） |

**DDL 脚本必须在 5.1 与 5.2 两个提交里各追加一次**——保证每个提交都能独立部署到干净库。

---

## 8. 禁止清单（零触碰）

`utils/CacheClient.java`、`utils/RedisConstants.java` 的**缓存相关常量**、`EquipmentCacheTest` / `CacheClientMutexTest`、
`unlock.lua`、`SimpleRedisLock` / `ILock` / `RedisData`、`LoginInterceptor` / `RefreshTokenInterceptor` / `UserHolder` /
`RoleInterceptor` / `RequireRole` / `UserRole`、`EquipmentMapper.xml`、`Equipment`/`EquipmentCategory` 实体字段、
`EquipmentVO`、`application*.yaml`、`pom.xml`、`MybatisConfig`、`RedissonConfig`、`UploadController`、`UserInfo` 四件套、
`tb_blog` / `tb_follow` / `tb_shop` 等既有残留。

**本 Phase 不允许**：

- 引入任何新依赖（含 Redisson 之外的一切）；
- 编写/修改预约 Lua（**Phase 6**）；
- 创建 `Reservation` 实体或 `tb_reservation` 表（**Phase 6/7**）；
- 建 RabbitMQ 拓扑或 `ReservationMessageDTO`（**Phase 7**）；
- 实现取消后恢复名额（**Phase 9**）；
- 实现事务幂等 / 对账 / DLQ 治理（**Phase 8**）；
- 引入任何调度器（`@Scheduled`、XXL-JOB 等）；
- 给 GET 端点加 `@RequireRole`；
- DROP 任何表。

---

## 9. 高危风险点（Review 必须逐条实测）

| 编号 | 风险 | 为什么危险 | 验证方式 |
| --- | --- | --- | --- |
| **H1** | **归属校验漏端点或可绕过** | 漏一个端点就等于没做 P1-9；"搬设备进别人实验室"（PUT 改 `laboratoryId`）是最容易被漏的 | T1/T2/T3 逐端点实测 403；**并做判别实验**：注释掉 `assertManageable` 调用，对应测试必须变红 |
| **H2** | **状态机可被请求体绕过** | `updateById(body)` 会让客户端直接写 `status`/`remainingQuota` | 代码审查 + T5：向 PUT 传 `status=PUBLISHED` 必须**不生效** |
| **H3** | **删除遗留代码引发 Bean/编译残留** | 16 个文件含 Bean、Mapper、Lua、配置，删漏一个会启动失败或留下 `seckill` 命名 | `mvn clean test-compile` + 全局 `grep -ri "voucher\|seckill" src/` 结果为零 + 应用启动实测 |
| **H4** | **`/voucher/**` 白名单移除改变既有行为** | 未登录访问该路径 404 → 401 | 实测 `/voucher/seckill` 与 `/voucher`：确认新行为；确认其他白名单路径（`/user/code` 等）未受影响 |
| **H5** | **`LocalDateTime` ↔ `datetime` 往返与 JSON 序列化** | 4 个时间字段 + 枚举 + 派生状态，经 Jackson 与 MyBatis 两条链路 | 运行时实测创建→查询，4 个时间字段**逐字段核对**（秒级）；`bookingState` 三态按真实时钟各构造一次 |
| **H6** | **归属模型破坏既有 1012 个用户与 Phase 3 鉴权** | `UserDTO` 未变，但写路径多了 403 分支 | 401/403/200 全矩阵回归（普通用户、管理员、未登录、无 token） |
| **H7** | **删除 `RabbitMQConfig` 后应用是否仍能启动** | Spring Boot 的 Rabbit 自动配置仍在（`spring.rabbitmq.*` 还在 yaml），但无自定义 Bean | `mvn spring-boot:run` 启动实测 + 启动日志无 Rabbit 相关 ERROR |
| **H8** | **测试污染 Phase 2 基线数据** | 设备 1–12、实验室 1–4 是后续 Phase 的依赖；归属种子会**写** `tb_laboratory` | 测试自建的实验室/设备用 id 段外并 `@AfterEach` 清理；Review 后复核实验室 1–4 的 `admin_user_id` 与设备/实验室计数与基线一致 |
| **H9** | **并发发布时失败方删掉成功方的 Redis 名额键** | 死锁/重复点击/前端重试都会触发；结果是**已发布批次没有名额键**，学生全部预约失败且无自愈路径（Phase 6 的 Lua 读不到键） | 运行时判别实验：20 并发 publish 同一 DRAFT 批次 → 断言批次 PUBLISHED **且** quota 键存在；修复前实测键 `EXISTS`=0（已复现）。回归测试见 §10 T11 |
| **H10** | **`updateDraft` 的 TOCTOU：草稿字段写进已发布批次** | 检查与写入之间的窗口；后果是 DB/Redis 名额失配 + I7 时段不重叠保证失效 | 运行时判别实验：publish 与 updateDraft 并发对撞 → 断言批次要么 DRAFT 要么 `PUBLISHED 且 total_quota == remaining_quota == Redis 值`；修复前实测 2/10 轮出现 `PUBLISHED\|99\|99` 与 `PUBLISHED\|99\|4`（已复现） |
| **H11** | **学生端 `/page` 暴露草稿与已取消批次** | 未发布的内部排期表与作废场次出现在学生可见列表 | 实测 `GET /reservation-batch/page`（不带 `status`）不含 `bookingState ∈ {DRAFT, CANCELLED}`；`?status=DRAFT` 返回 400；`/manage/page` 仍可见草稿 |
| **H12** | **并发 publish × cancel 把已取消批次「复活」成 PUBLISHED**（由变体实验发现，非人工推演） | `cancel` 的 CAS 匹配 `DRAFT` 或 `PUBLISHED`，故在 publish×cancel 对撞中 `cancel` 总会赢，**正确实现的最终状态永远不可能是 `PUBLISHED`**。若 `publish` 的条件 UPDATE 缺少 `WHERE status='DRAFT'`，交错 `publish 读到 DRAFT → cancel 提交 CANCELLED → publish 的 CAS 命中` 会把取消操作整个抹掉，且 `cancel` 已删掉的 quota 键不会重建——终态是「PUBLISHED 但无名额键」，与 H9 同型但成因不同 | 运行时判别实验：对同一 DRAFT 批次同时发 publish 与 cancel，扫描多档 delay × 多轮，断言终态**永不为 PUBLISHED**。**已实测**：修复版 **0/18** 复活；去掉该条件后 **3/18** 复活（delay=0 命中，其中两轮连带 quota 键缺失）。回归测试见 §10 T13 |

---

## 10. 测试计划（验收硬要求）

沿用 Phase 4 复核确立的规矩：**每个承担关键职责的测试都要能回答「把目标缺陷拿掉，这个断言会不会失败」，并且要真的跑变体实验。**

| 编号 | 测试 | 断言 | 判别力（必做的变体实验） |
| --- | --- | --- | --- |
| **T1** | `EquipmentOwnershipTest`：管理员 A 修改**管理员 B** 实验室下的设备状态 | 403 | **删掉 `assertManageable` 调用 → 必须变 200/失败**。★P1-9 的直接证据 |
| **T2** | 同上：A `PUT /laboratory` 改 B 的实验室、`DELETE /laboratory/{id}` | 均 403 | 同上 |
| **T3** | 同上：A 在 B 的实验室下 `POST /equipment`；A 的 `/reservation-batch/manage/page` **看不到** B 的批次 | 403；列表不含 B 的批次 | 同上；`/manage/page` 的判别实验 = 去掉 `admin_user_id` 过滤条件 → 必须能看到 B 的批次 |
| **T4** | `ReservationBatchStateTest`：DRAFT → publish | 状态 PUBLISHED；`remainingQuota == totalQuota`；Redis `reservation:batch:quota:{id} == totalQuota` 且 `TTL > 0` | 去掉 Redis 写入 → 必须失败。注意：T4 是**单线程**路径，抓不到 H9（把它换成"无条件 SET + 无条件 DEL"T4 仍然全绿）——H9 由 T11 覆盖 |
| **T5** | 状态机非法迁移 | 对 PUBLISHED 调 `PUT`（含请求体里塞 `status=PUBLISHED` 的 DRAFT 修改）→ 失败且 `status` 与 `remainingQuota` 未被改写；对 CLOSED/CANCELLED 调 publish/close → 失败 | 把 `updateById(body)` 换回白名单 → 必须失败 |
| **T6** | 发布不变式 I1–I7 逐条 | 每条各一个用例，全部必须在 publish 时被拒绝 | 逐条注释掉对应校验 → 对应用例必须失败 |
| **T7** | `BookingStateTest`：**纯单元测试**，固定 `now` | 六态全覆盖；边界：`now == bookingStartTime` → OPEN，`now == bookingEndTime` → OPEN（闭区间），`+1s` → EXPIRED | 把 `!now.isAfter(bookingEndTime)` 改成 `now.isBefore(bookingEndTime)` → 边界用例必须失败 |
| **T8** | close / cancel 清理 Redis | `EXISTS quota key == 0`；重复 close → 失败且无副作用 | 去掉 `DEL` → 必须失败 |
| **T9** | 重复 publish（**串行**） | 第二次直接失败；Redis quota **值未被改写** | 判别实验 = 去掉**前置状态快速失败检查**（`仅草稿批次可发布`）→ 必须失败。**局限（已实测修正）**：本行原先写的"去掉 `WHERE status='DRAFT'` → 必须失败"是**错的**。串行第二次在 Service 前置检查处就被拒，根本走不到那条条件 UPDATE，所以该条件在串行路径上不是裁决点。实测：删掉它后 18 个用例**全绿**（变体 V6）。T9 在结构上既无法覆盖 H9，也无法覆盖该条件——前者由 T11 覆盖，后者由 **T13** 覆盖 |
| **T11** | **并发 publish（H9 的直接判别实验）**：`ExecutorService` + `CountDownLatch` 对同一 DRAFT 批次同时放行 **8** 次 `publish` | 恰好 1 次成功；批次为 `PUBLISHED`；`reservation:batch:quota:{id}` **存在**、值 `== totalQuota`、`TTL > 0` | 把实现改回"无条件 `set` + 失败时无条件 `delete`"→ **必须失败**（已实测：修复前 20 并发下键 `EXISTS`=0）。这是 T9 无法替代的覆盖 |
| **T12** | **`/page` 状态白名单（H11）**：`GET /reservation-batch/page` 不带 `status`，以及带 `status=DRAFT` | 不带 `status` 时结果不含 `bookingState ∈ {DRAFT, CANCELLED}`；`?status=DRAFT` → 400；`/manage/page` 仍能查到自己的 DRAFT | 去掉白名单约束 → 断言必须失败 |
| **T13** | **并发 publish × cancel 不变式（H12）**：`CountDownLatch` 对撞 ≥20 轮，断言一条**对所有交错都成立**的不变式而非某一种交错 | 最终状态**永不为 `PUBLISHED`**（`cancel` 的 CAS 允许 `DRAFT` 与 `PUBLISHED`，故它总会赢；出现 `PUBLISHED` 即证明 `publish` 的 CAS 匹配到了一个已取消的行）；若为 `CANCELLED` 则 quota 键必须不存在 | 去掉 publish 条件 UPDATE 的 `WHERE status='DRAFT'` → 必须失败。**已实测该窗口真实可达**：修复版 0/18 复活；去掉该条件后 **18 轮有 3 轮把已取消的批次复活成 `PUBLISHED`**（delay=0 命中，其中两轮连带 quota 键也缺失）。这是 T9 无法替代的覆盖 |
| **T14** | **并发 publish × updateDraft 一致性不变式（H10）**：24 轮，**错开释放**（publish 先放行，主线程自旋 0/100µs/200µs/500µs/1ms/2ms 六档后才放行 updateDraft） | 不变式（对**所有**合法交错成立）：若终态 `DRAFT` → `total==remaining==99` 且 quota 键不存在；若 `PUBLISHED` → `total==remaining` **且** quota 键值 `== total.toString()`；其它状态一律失败 | 两个判别实验，缺一不可：**(a)** 去掉 `updateDraft` 的 `.eq(status, DRAFT)` → 打红，签名 `PUBLISHED\|99\|99\|4`；**(b)** 去掉 publish 条件 UPDATE 的六个快照 `.eq()` 绑定 → 打红，签名 `PUBLISHED\|99\|4\|4`。**错开释放是必需的**：同时放行时 `publish` 因在 UPDATE 前多一次 Redis 往返而恒更慢，`updateDraft` 的 UPDATE 总先落地、随后被快照 CAS 拒掉——实测**同时放行下 20 轮与 200 轮都无法证伪 (a)**；改为错开释放后立即打红。两条签名可区分，可据失败信息判断是哪一侧守卫缺失 |
| **T10** | `LoginInterceptor` 白名单变更 | `/voucher/**` 未登录 → 401；`/user/login`、`/user/code`、`/upload/**` 行为不变 | **H4** |

**测试纪律**：`mvn test` 全绿；**确定性**（T7 靠注入 `now`，不依赖真实时钟；其余不依赖 sleep 侥幸）；
**自清理**（不留 DB/Redis 残渣）；**不删除或放宽** `CacheClientMutexTest` / `EquipmentCacheTest` / `RedissonTest` / `HmDianPingApplicationTests` 的既有断言。

**并发测试的形态纪律（本 Phase 用两个反例换来的）**：

1. **断言不变式，不要枚举终态**。T14 初稿枚举了两种「合法终态」，但漏了第三种：`updateDraft(99)` 完整提交后 `publish` 才首次读取草稿 → `PUBLISHED|99|99|"99"`，这是**等价于「先 PUT 再发布」的合法结果**。枚举式断言会给合法交错报假红，而**假红的并发测试比没有测试更糟**——团队会学会「重跑直到变绿」。改成一致性不变式后，合法态被正确接受，两个真实撕裂签名（`PUBLISHED|99|4`、`PUBLISHED|99|99|"4"`）仍被拒。
2. **判别力实验必须确认测试真的跑过**。编译失败同样产出零条 `[ERROR] <Class>.` 行，会被误读成「零失败 = 测试没有判别力」。变体 harness 必须先确认目标测试类确实执行，再看失败集合。
3. **「拿掉守卫测试仍全绿」不等于守卫不重要**，也不等于必须改测试——先用运行时实验证明守卫承重（本 Phase 的 H12 就是这样从「零失败」变成「3/18 复活」的），再决定是补测试还是登记局限。

---

## 11. Phase 7 的输入：现 `RabbitMQConfig` 必须恢复的能力

删除 `RabbitMQConfig.java` 的同时，把它的关键设计固化为 Phase 7 的**验收清单**（逐条来自 `433cdfb` 的实测结论）：

1. **手动 ACK**：`acknowledge-mode: manual` + `prefetch: 1`，消费端显式 `basicAck` / `basicNack(requeue=false)`。
2. **`retry.enabled = false`**：手动 ack 模式下默认 recoverer 不会真正 nack，会**卡死消费者**；重试交给显式 nack→DLX。
3. **`ConditionalRejectingErrorHandler.setRejectManual(true)`**：把"监听器方法体之前"的致命异常（如反序列化失败）
   转成 `basicNack(requeue=false)` 进死信，否则同样卡死消费者。
4. **Publisher Confirm（`correlated`）+ Returns + `mandatory=true`**：发送端能区分"broker 未确认"与"路由不到队列"。
5. **`Jackson2JsonMessageConverter` 复用 Boot 的 `ObjectMapper`**（不要新建一个）。
6. **DLX/DLQ 拓扑**，且**不 requeue**（避免毒消息无限重投打死消费者）。
7. **消费端 Redisson 边界**：`tryLock(2, 10, SECONDS)` —— `leaseTime > 0` 会关闭 watchdog，
   持锁线程挂死时锁最多 10 秒自动过期，有上界。
8. 消息体**不允许**是数据库 Entity（Phase 7 要改成 `ReservationMessageDTO`）。

> **运维残留**：Spring 只声明队列、从不删除。删除 `RabbitMQConfig` 后，broker 上
> `seckill.order.queue` / `seckill.order.dlx.queue` / 两个 exchange 会成为孤儿（当前实测存在）。
> 本 Phase **不主动删除**（对共享 broker 的破坏性操作，需单独授权），登记为 Phase 7 建立 `reservation.*` 拓扑时一并清理。

---

## 12. 验收标准（对应计划 §8）

| 计划验收标准 | 本设计对应 |
| --- | --- |
| Voucher 业务语义正式退出 | §1 删除 16 文件 + 2 处常量/配置；`grep -ri "voucher\|seckill" src/` 结果为零（H3） |
| ReservationBatch 数据模型成立 | §3 表 + 实体 + 枚举 |
| 时间字段清晰 | §3.2 四个 `datetime` 业务时间字段 + §4.4 I2/I3/I4 |
| quota 字段清晰 | §3.2 `total_quota`/`remaining_quota` + §6 Redis quota 与 TTL |
| 批次状态机合理 | §4 持久化 4 态 + 迁移表 + 派生 `BookingState` |
| 管理员可以发布/管理批次 | §5 API + T5/T8/T9 |
| Maven compile 成功 | `mvn clean test-compile`（JDK 8） |

**附加（本设计自加）**：

- **P1-9 关闭**：7 个既有端点 + 批次端点全部归属受控，且**由判别实验证明测试可变红**（T1–T3）；
- `mvn test` 全绿；
- H4 的白名单行为变化**已实测确认且记录在案**。

---

## 13. 提交计划（本地，不 push）

```text
refactor: remove legacy voucher and seckill modules        ← 5.0
feat: enforce laboratory ownership for lab admins          ← 5.1
feat: add reservation batch lifecycle                      ← 5.2
```

三笔提交、一个 Phase、一次 Review。5.0 是纯删除（可独立回滚），5.1 是权限修复（可独立回滚），5.2 是新功能。

---

## 14. 需要用户确认的三条（其余按本设计执行）

1. **§1.3 连带删除 `VoucherOrder` 全家桶**：计划把它列为「→ Reservation 的迁移参照」并映射到 Phase 7，
   但它的实现焊死在 `SeckillVoucher` 上，不删就编译不过。本设计主张**一并删除**（参照已存于 git 与 `PHASE0-BASELINE.md`）。
   替代方案是给 Phase 7 留一个无 Service 的空壳实体——本设计认为那是残留而非资产。
   → **用户已确认：一并删除。**
2. **§4 状态机简化**：把计划的 5 态改写为「持久化 4 态（DRAFT/PUBLISHED/CLOSED/CANCELLED）+ 派生 6 态
   （含 NOT_STARTED/OPEN/EXPIRED）」。计划原文授权「Agent 应进一步检查是否可以简化」。
   → **用户已确认：采用简化方案。**
3. **§5.2.1 `/page` 状态白名单（F1）**：学生端场次列表是否排除 `DRAFT` / `CANCELLED`。
   本设计裁定为**排除**（只返回 `PUBLISHED` / `CLOSED`），因为草稿是未发布的内部门表、已取消批次是作废场次。
   → **用户已确认：排除，只返回 PUBLISHED 和 CLOSED。**

---

## 15. 已知边界（本 Phase 不解决，显式登记）

1. **Redis quota 与 MySQL `remaining_quota` 无对账/补偿**，`publish` 的失败补偿 `DEL` 也允许失败 → Phase 8。
2. **I7 重叠检查存在 TOCTOU**（检查→写入之间）→ Phase 8（Redisson）。
3. **不缓存批次**（无热点证据）→ 出现真实热点再补。
4. **无「负责人转移」接口**，`admin_user_id` 只能由 DDL/种子脚本设置。
5. **一实验室一负责人**（无多对多、无"实验室管理员列表"）。
6. **不 DROP** `tb_voucher` / `tb_seckill_voucher` / `tb_voucher_order` 三表（沿用「只删代码」裁决）→ Phase 10。
7. **broker 上 `seckill.*` 队列/exchange 成为孤儿** → Phase 7 清理（§11）。
8. **`spring.rabbitmq.*` 配置保留但本 Phase 无消费者** → Phase 7 启用。
9. **403 响应体不统一**：拦截器层 403 为空体（Phase 3 既有选择），Service 层 403 带 `Result.fail` 消息。
10. **Redis 中没有批次状态/时间快照**：Phase 6 的 Lua 若要原子校验批次状态与时间窗口，需要自行决定是否加 Redis 侧快照；
    若加，`publish`/`close`/`cancel` 路径必须同步写入（本 Phase 已预留：Redis 写入集中在 §6.3 一处）。
11. **`RedisIdWorker` 的 `icr:*` 键无 TTL**（既有 P2）→ Phase 6 复用时面对。
12. **`requestId` / `reservationId` 是否同一个 ID** 未决 → Phase 6 设计（计划 §9 明确要求给出答案）。

**随本 Phase 自动关闭的既有登记**：

- **P2-2**（`seckill:stock:` / `seckill:order:` 无 TTL）——键空间本身消失；
- **P2-3**（四套历史秒杀实现以注释形式残留）——`VoucherOrderServiceImpl` 整体删除；
- **P2-1**（Redis Key 重复定义 Java 常量 vs Lua 字面量）——`seckill.lua` 删除后只剩 `unlock.lua` 一处，范围缩减；
- **P1-9**（LAB_ADMIN 无实验室归属约束）——本 Phase 关闭（T1–T3 + 判别实验）。
