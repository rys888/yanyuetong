# 实验资源预约平台重构：分阶段迁移计划

> **用途**：用于指导"审查 Agent 负责设计与验收、Codex
> 负责实际编码"的分阶段项目重构。
>
> **原项目**：黑马点评（已将 Redis Stream 异步订单迁移为 RabbitMQ）
>
> **目标项目**：高校实验资源预约平台
>
> **核心原则**：小步修改、逐阶段编译、逐阶段审查；任何 Phase
> 未通过验收，不进入下一 Phase。

------------------------------------------------------------------------

## 0. 总体改造目标

将现有黑马点评项目从"商户 + 优惠券秒杀 + 探店社交"业务，重构为：

> 面向高校学生、科研人员和实验室管理员的实验资源预约平台，围绕"实验设备信息
> → 开放批次 → 限量资格竞争 → RabbitMQ 异步确认 → MySQL 最终落库 →
> 取消/履约"建立完整业务链路。

重点保留并深化：

-   Spring Boot
-   MyBatis-Plus
-   MySQL
-   Redis Session
-   Redis 热点缓存
-   Cache Aside
-   缓存穿透/击穿
-   Redis String
-   Redis Set
-   Redis ID
-   Lua 原子资格校验
-   Redisson
-   RabbitMQ
-   Publisher Confirm / Return
-   Consumer Manual ACK
-   DLQ
-   MySQL Transaction
-   Unique Index
-   消息幂等
-   Redis / MySQL 最终一致性
-   补偿与对账

明确退出正式架构：

-   Redis GEO
-   Bitmap 签到
-   HyperLogLog UV
-   Blog / 探店笔记
-   点赞
-   评论
-   用户社交关注
-   共同关注
-   Feed / ZSet 社交动态流
-   商户评分/均价/商圈等点评业务
-   普通优惠券业务
-   Redis Stream 历史实现

------------------------------------------------------------------------

# 1. 总体执行流程

每个 Phase 都采用同一流程：

``` text
审查 Agent
   ↓
读取当前 Phase 相关代码
   ↓
设计当前 Phase
   ↓
生成 Codex 修改指令
   ↓
Codex 修改代码
   ↓
编译 / 测试
   ↓
审查 Agent 重新检查实际代码
   ↓
PASS ─────────→ 下一 Phase
 │
 FAIL
 ↓
生成最小修复指令
 ↓
Codex 定点修复
 ↓
再次 Review
```

## 基本纪律

1.  Codex 一次只处理一个 Phase。
2.  未经明确允许，不提前实现后续 Phase。
3.  Agent 负责设计和 Review，Codex 负责写代码。
4.  Codex 的修改总结不能代替代码审查。
5.  每个 Phase 完成后必须至少执行 Maven 编译。
6.  有条件时运行当前模块测试。
7.  每个 Phase 必须保持仓库处于可编译状态。
8.  优先小步迁移，不进行一次性全仓库重写。
9.  已经 PASS 的 Phase 原则上不重新分析。
10. 发现跨阶段严重 Bug 时才允许回溯。

------------------------------------------------------------------------

# 2. Phase 总览

  --------------------------------------------------------------------------------------------
  Phase                   名称                    核心目标
  ----------------------- ----------------------- --------------------------------------------
  Phase 0                 Baseline 基线建立       确认原项目可以稳定编译并建立重构起点

  Phase 1                 Legacy Cleanup          删除 GEO、签到、HLL、Blog、Follow 等无关业务
                          独立废弃模块清理        

  Phase 2                 Laboratory & Equipment  建立实验室、设备、设备分类核心领域模型

  Phase 3                 User & Authorization    重构用户角色、权限、登录与登出

  Phase 4                 Equipment Cache         将商户缓存体系迁移为实验设备热点缓存

  Phase 5                 Reservation Batch       将优惠券/秒杀券模型重构为设备预约批次

  Phase 6                 Redis + Lua Reservation 建立 Redis + Lua 高并发预约资格抢占

  Phase 7                 RabbitMQ Reservation    建立 RabbitMQ 异步预约确认链路

  Phase 8                 Consistency &           完善事务、幂等、补偿、DLQ、对账
                          Reliability             

  Phase 9                 Cancellation &          实现预约取消、名额恢复、管理员核验
                          Fulfillment             

  Phase 10                Final Cleanup &         清理黑马痕迹、测试、配置、README、最终验收
                          Delivery                
  --------------------------------------------------------------------------------------------

------------------------------------------------------------------------

# 3. Phase 0：Baseline 基线建立

## 目标

在任何业务代码修改之前，确认当前项目状态并建立可靠基线。

## 本阶段任务

### 1. 编译验证

使用项目当前兼容环境：

``` bash
mvn clean compile -DskipTests
```

当前已知项目主要基于：

-   Java 8
-   Spring Boot 2.3.12.RELEASE
-   MyBatis-Plus 3.4.3

确认 Java 8 下项目可以正常编译。

### 2. 记录数据库现状

保存当前：

-   全部表；
-   主键；
-   索引；
-   唯一索引；
-   核心字段；
-   外键逻辑关系。

重点关注：

``` text
tb_user
tb_user_info
tb_shop
tb_shop_type
tb_voucher
tb_seckill_voucher
tb_voucher_order
tb_follow
tb_blog
...
```

### 3. 记录 Redis Key

梳理：

-   登录 Session；
-   验证码；
-   商户缓存；
-   秒杀库存；
-   秒杀用户 Set；
-   Feed；
-   Like；
-   GEO；
-   Bitmap；
-   ID Worker。

### 4. 记录 RabbitMQ

确认：

-   Exchange
-   Queue
-   RoutingKey
-   DLX
-   DLQ
-   Confirm
-   Return
-   ACK

### 5. 建立 Git Checkpoint

建议：

``` text
before-lab-reservation-refactor
```

## 禁止事项

本阶段不修改任何业务逻辑。

## 验收标准

-   JDK 8 Maven compile 成功；
-   数据库基线已记录；
-   Redis Key 已记录；
-   RabbitMQ 拓扑已记录；
-   Git 基线已建立。

## 完成标志

``` text
PHASE 0 — PASS
```

------------------------------------------------------------------------

# 4. Phase 1：Legacy Cleanup 独立废弃模块清理

## 目标

优先删除与新业务完全无关、且不会成为后续领域模型基础的黑马点评模块。

## 删除内容

### Redis GEO

删除：

-   附近商铺逻辑；
-   GEO 查询；
-   GEO 初始化测试；
-   `SHOP_GEO_KEY`；
-   x/y 查询参数；
-   distance 相关业务逻辑。

### Bitmap

删除：

-   `/user/sign`
-   `/user/sign/count`
-   `sign()`
-   `signCount()`
-   `USER_SIGN_KEY`
-   Bitmap 测试。

### HyperLogLog

删除：

-   HLL 测试；
-   无正式业务用途的相关代码。

### 社交内容模块

删除：

-   Blog；
-   Like；
-   Follow；
-   Common Follow；
-   Comment；
-   Feed；
-   热门探店内容；
-   对应 Controller / Service / Mapper / Entity / DTO；
-   对应 Redis Key；
-   对应测试。

## 暂时不要删除

这一阶段 **不要直接删除**：

-   Shop
-   ShopType
-   Voucher
-   SeckillVoucher
-   VoucherOrder

原因：

它们分别是后续：

``` text
Shop / ShopType
→ Laboratory / Equipment / EquipmentCategory

Voucher / SeckillVoucher
→ ReservationBatch

VoucherOrder
→ Reservation
```

的重要迁移参照。

应在对应 Phase 中逐步替换。

## 验收标准

-   GEO 正式业务代码完全退出；
-   Bitmap 完全退出；
-   HLL 完全退出；
-   Blog/Like/Follow/Comment/Feed 退出；
-   不存在残余 Bean/Mapper/XML 引用；
-   Maven compile 成功；
-   登录、缓存、Voucher、VoucherOrder、RabbitMQ 主链仍能编译。

## 完成标志

``` text
PHASE 1 — PASS
```

------------------------------------------------------------------------

# 5. Phase 2：Laboratory & Equipment 领域模型

## 目标

正式建立新项目最基础的业务领域：

``` text
Laboratory
    ↓
Equipment
    ↓
EquipmentCategory
```

## 新增/重构实体

### Laboratory

实验室。

建议至少包含：

-   id
-   name
-   campus
-   building
-   room
-   description
-   status
-   createTime
-   updateTime

### EquipmentCategory

设备分类。

例如：

-   显微成像
-   化学分析
-   机械加工
-   计算资源

### Equipment

实验设备。

建议至少考虑：

-   id
-   laboratoryId
-   categoryId
-   name
-   description
-   location
-   image
-   usageRequirement
-   status
-   bookingEnabled
-   createTime
-   updateTime

## 迁移原则

不能简单：

``` text
Shop → Equipment
```

只修改类名。

必须真正建立：

``` text
Laboratory 1 —— N Equipment
EquipmentCategory 1 —— N Equipment
```

## 本阶段业务

实现基础：

-   实验室 CRUD；
-   设备分类查询；
-   设备 CRUD；
-   按校区筛选；
-   按实验室筛选；
-   按设备分类筛选；
-   按设备名称查询；
-   分页；
-   设备状态管理。

## 暂时不做

-   Redis 热点缓存；
-   预约；
-   Lua；
-   RabbitMQ；
-   复杂权限。

这些属于后续 Phase。

## 验收标准

-   新数据库表设计正确；
-   Entity/Mapper/Service/Controller 对应正确；
-   Laboratory 与 Equipment 关系明确；
-   黑马 Shop 语义开始退出；
-   查询接口可用；
-   Mapper/XML 无错误；
-   Maven compile 成功。

## 完成标志

``` text
PHASE 2 — PASS
```

------------------------------------------------------------------------

# 6. Phase 3：User & Authorization 用户与权限

## 目标

将原点评用户体系调整为实验资源预约平台用户体系。

## 第一版角色

至少：

``` text
USER
LAB_ADMIN
```

可根据实际设计决定是否增加：

``` text
SYSTEM_ADMIN
```

不建设复杂 RBAC。

## 普通用户

可以：

-   查询实验室；
-   查询设备；
-   查询预约批次；
-   提交预约；
-   查询自己的预约；
-   取消自己的预约。

## 实验室管理员

可以：

-   管理自己负责的实验室；
-   管理设备；
-   发布预约批次；
-   关闭/取消预约批次；
-   核验预约。

## 保留

-   Redis Session；
-   UUID Token；
-   RefreshTokenInterceptor；
-   LoginInterceptor；
-   ThreadLocal 用户上下文。

## 修正

完成当前缺失的：

``` text
logout
```

## 注意

当前项目并不是 JWT。

不要为了"技术升级"擅自改 JWT。

## 验收标准

-   登录流程正常；
-   Redis Session 正常；
-   普通用户和管理员权限边界明确；
-   登出完成；
-   未登录接口控制正确；
-   不引入大型权限框架；
-   Maven compile 成功。

## 完成标志

``` text
PHASE 3 — PASS
```

------------------------------------------------------------------------

# 7. Phase 4：Equipment Cache 热点设备缓存

## 目标

将原 Shop Cache 中真正有价值的缓存设计迁移到 Equipment。

## 正式缓存对象

优先：

``` text
Equipment Detail
```

根据实际访问情况决定 Laboratory 是否缓存。

不要缓存所有列表。

## 技术

保留：

-   Redis String；
-   JSON；
-   Cache Aside；
-   空值缓存；
-   TTL；
-   缓存穿透；
-   热点 Key 击穿处理；
-   更新数据库后删除缓存。

## Redis Key 示例

``` text
lab:equipment:{equipmentId}
```

空值也设置较短 TTL。

正常数据设置正常 TTL。

可加入轻微 TTL 随机扰动。

## 缓存击穿

正式业务只选一种主方案：

### 方案 A

互斥锁重建。

或：

### 方案 B

逻辑过期。

Agent 根据现有 `CacheClient` 代码决定。

不要为了简历在正式业务中同时使用两套。

## 更新策略

推荐：

``` text
更新 MySQL
↓
删除 Redis Cache
```

## 验收标准

-   Equipment 查询优先 Redis；
-   Cache Miss 正确查询数据库；
-   不存在数据时有空值缓存；
-   热点 Key 击穿有明确处理；
-   Equipment 更新后缓存正确失效；
-   Key 命名统一；
-   Maven compile 成功；
-   有条件增加缓存测试。

## 完成标志

``` text
PHASE 4 — PASS
```

------------------------------------------------------------------------

# 8. Phase 5：ReservationBatch 预约批次

## 目标

彻底将：

``` text
Voucher
SeckillVoucher
```

业务模型替换为：

``` text
ReservationBatch
```

## ReservationBatch 定义

一个预约批次代表：

> 某台实验设备在某个确定时间段开放固定数量的预约名额。

例如：

``` text
设备：扫描电子显微镜
使用时间：2026-10-10 14:00 - 16:00
预约开放：2026-10-08 12:00
预约截止：2026-10-10 10:00
总名额：4
```

## 建议字段

至少：

-   id
-   equipmentId
-   startTime
-   endTime
-   bookingStartTime
-   bookingEndTime
-   totalQuota
-   remainingQuota
-   status
-   createTime
-   updateTime

## 状态

可采用：

``` text
DRAFT
WAITING
OPEN
CLOSED
CANCELLED
```

Agent 应进一步检查是否可以简化。

## 管理员能力

-   创建批次；
-   修改草稿；
-   发布；
-   查询；
-   关闭；
-   取消。

## Redis 初始化

发布批次时，为后续高并发预约准备 Redis quota。

但本阶段只建立批次生命周期。

复杂 Lua 逻辑放到 Phase 6。

## 验收标准

-   Voucher 业务语义正式退出；
-   ReservationBatch 数据模型成立；
-   时间字段清晰；
-   quota 字段清晰；
-   批次状态机合理；
-   管理员可以发布/管理批次；
-   Maven compile 成功。

## 完成标志

``` text
PHASE 5 — PASS
```

------------------------------------------------------------------------

# 9. Phase 6：Redis + Lua 高并发预约

## 目标

建立新的高并发预约资格竞争入口。

这是核心技术 Phase 之一。

## 主流程

``` text
用户提交预约
↓
权限与基础参数检查
↓
生成 requestId / reservationId
↓
执行 Lua
↓
校验批次状态/时间
↓
校验 Redis quota
↓
校验用户是否重复预约
↓
quota - 1
↓
Set 加入 userId
↓
返回预接受成功
```

## Redis 数据结构

### Quota

``` text
reservation:batch:quota:{batchId}
```

类型：

``` text
String
```

### 已获得资格用户

``` text
reservation:batch:users:{batchId}
```

类型：

``` text
Set
```

## Lua

需要重新编写预约 Lua。

不能继续保留：

``` text
seckill.lua
```

业务命名。

至少需要完成：

1.  批次可预约校验；
2.  quota \> 0；
3.  用户未重复预约；
4.  quota - 1；
5.  userId 写入 Set。

## Redis ID

继续复用：

``` text
RedisIdWorker
```

但必须明确：

``` text
requestId
reservationId
```

是否相同。

## 暂时不做

本阶段先建立 Redis 原子抢占。

RabbitMQ 完整异步确认属于 Phase 7。

## 必须测试

至少：

-   quota = 0；
-   同用户重复预约；
-   多用户竞争；
-   quota 不出现负数；
-   批次未开放；
-   批次已结束。

## 验收标准

-   Lua 原子操作正确；
-   不超卖 Redis quota；
-   同用户不能重复获得资格；
-   Redis Key 统一；
-   返回码明确；
-   Maven compile 成功；
-   并发测试结果合理。

## 完成标志

``` text
PHASE 6 — PASS
```

------------------------------------------------------------------------

# 10. Phase 7：RabbitMQ 异步预约

## 目标

将 Redis 预接受成功后的预约请求异步投递到 RabbitMQ，由 Consumer 完成
MySQL 持久化。

## 推荐拓扑

例如：

``` text
reservation.exchange
reservation.create
reservation.create.queue

reservation.dlx
reservation.create.dlq
```

最终命名由实际配置确定。

## MQ DTO

新增专用：

``` text
ReservationMessageDTO
```

禁止直接发送数据库 Entity。

建议包含：

-   requestId
-   reservationId
-   userId
-   batchId
-   createTime

是否携带 retryCount 由 Agent 根据重试策略判断。

## Producer

需要：

-   发布预约消息；
-   Publisher Confirm；
-   Return；
-   correlation/requestId 关联。

## Consumer

基本流程：

``` text
收到消息
↓
解析 DTO
↓
幂等检查
↓
Redisson
↓
MySQL 持久化
↓
成功
↓
Manual ACK
```

## 本阶段保留

原 RabbitMQ 迁移中已经实现的成熟配置可以复用：

-   Confirm；
-   Return；
-   Manual ACK；
-   DLX。

但必须重新命名并审查。

## 验收标准

-   新 Exchange/Queue/RoutingKey 正确；
-   Producer 可以发送预约消息；
-   Consumer 正确接收；
-   DTO 与 Entity 分离；
-   Manual ACK 正确；
-   DLX/DLQ 路由正确；
-   不再存在 VoucherOrder MQ 业务语义；
-   Maven compile 成功。

## 完成标志

``` text
PHASE 7 — PASS
```

------------------------------------------------------------------------

# 11. Phase 8：Consistency & Reliability 一致性与可靠性

## 目标

把"能跑的预约"升级为"异常情况下仍然能够保证最终正确性"的预约系统。

这是整个项目技术深度最高的 Phase。

------------------------------------------------------------------------

## 8.1 MySQL Unique Index

至少考虑：

``` sql
UNIQUE(request_id)
```

以及：

``` sql
UNIQUE(user_id, batch_id)
```

但必须结合"取消后能否重新预约"重新评估第二个索引。

不能机械添加。

------------------------------------------------------------------------

## 8.2 Transaction

Consumer 中：

``` text
条件扣减 remainingQuota
+
INSERT Reservation
```

必须位于同一事务。

避免：

``` text
扣库存成功
↓
INSERT 失败
```

------------------------------------------------------------------------

## 8.3 Consumer 幂等

必须解决：

-   MQ 重复投递；
-   Consumer 重试；
-   ACK 丢失；
-   Producer 使用同 requestId 重投。

最终防线：

``` text
requestId Unique Index
```

------------------------------------------------------------------------

## 8.4 Redisson

定位：

``` text
Redisson
= 降低同一用户/批次并发竞争
≠ 最终正确性防线
```

最终正确性：

``` text
Unique Index
+
Transaction
+
Idempotency
```

------------------------------------------------------------------------

## 8.5 Confirm 模糊状态

禁止：

``` text
Confirm Timeout
→ 立即 Redis 回滚
```

因为 Broker 可能已经收到消息。

应将 Confirm Timeout 视为：

``` text
UNKNOWN
```

------------------------------------------------------------------------

## 8.6 Request / Message 状态

评估是否需要：

``` text
PRE_ACCEPTED
PUBLISHED
CONFIRMED
UNKNOWN
COMPENSATED
```

Agent 可以简化，但必须保证能够处理模糊状态。

------------------------------------------------------------------------

## 8.7 幂等补偿

Redis 补偿必须：

``` text
同一个 requestId 最多执行成功一次
```

不能重复：

``` text
quota + 1
```

------------------------------------------------------------------------

## 8.8 DLQ 治理

至少提供：

-   查询死信；
-   记录失败原因；
-   requestId 查询；
-   人工重放；
-   重放前幂等校验；
-   重放次数限制。

不要求完整前端后台。

Service + Admin API 即可。

------------------------------------------------------------------------

## 8.9 对账

针对：

``` text
Redis 已预扣
+
MySQL 无 Reservation
```

建立轻量对账机制。

流程：

``` text
扫描长期 UNKNOWN / PRE_ACCEPTED
↓
查询 MySQL requestId
├─ 已存在 → 修正 CONFIRMED
└─ 不存在 → 幂等 Redis 补偿
```

不引入 Seata 等大型分布式事务框架。

------------------------------------------------------------------------

## 必须重点测试的异常

1.  MQ 重复投递；
2.  Consumer 重复消费；
3.  Consumer 成功但 ACK 丢失；
4.  MySQL 条件扣减失败；
5.  INSERT 失败；
6.  Confirm NACK；
7.  Return；
8.  Confirm Timeout；
9.  Redis 补偿重复调用；
10. DLQ 重放；
11. 服务重启后的未完成请求。

## 验收标准

-   Transaction 正确；
-   Unique Index 正确；
-   Consumer 幂等；
-   补偿幂等；
-   Confirm Timeout 不误回滚；
-   DLQ 可治理；
-   对账链路存在；
-   不存在明显超卖/重复预约漏洞；
-   Maven compile 成功；
-   核心异常测试通过。

## 完成标志

``` text
PHASE 8 — PASS
```

------------------------------------------------------------------------

# 12. Phase 9：Cancellation & Fulfillment 取消与履约

## 目标

让预约从"抢到资格"发展为完整业务闭环。

## Reservation 状态

建议围绕：

``` text
PROCESSING
RESERVED
CANCELLED
COMPLETED
NO_SHOW
FAILED
```

具体由 Agent 优化。

不要设计几十个状态。

------------------------------------------------------------------------

## 9.1 查询预约

用户：

-   查询自己的预约；
-   查询预约详情；
-   查看当前状态。

管理员：

-   查询设备某批次预约；
-   查询待核验预约。

------------------------------------------------------------------------

## 9.2 取消预约

基本规则：

``` text
预约截止时间前
+
当前 Reservation 状态允许取消
```

流程：

``` text
用户取消
↓
幂等校验
↓
MySQL Transaction
├─ Reservation → CANCELLED
└─ remainingQuota + 1
↓
Redis 幂等恢复
├─ quota + 1
└─ Set remove userId
```

需要处理重复取消。

------------------------------------------------------------------------

## 9.3 履约核验

用户到实验室后：

``` text
reservationId
↓
管理员查询
↓
确认用户实际使用
↓
RESERVED → COMPLETED
```

第一版不做复杂二维码系统。

------------------------------------------------------------------------

## 9.4 爽约

如果项目需要，可支持：

``` text
RESERVED → NO_SHOW
```

但不要为了增加状态强行实现复杂自动任务。

------------------------------------------------------------------------

## 验收标准

-   用户可以查询自己的预约；
-   合法预约可以取消；
-   重复取消不会重复恢复 quota；
-   Redis/MySQL 名额恢复一致；
-   管理员可以核验；
-   非法状态迁移被拒绝；
-   Maven compile 成功；
-   取消和履约测试通过。

## 完成标志

``` text
PHASE 9 — PASS
```

------------------------------------------------------------------------

# 13. Phase 10：Final Cleanup & Delivery 最终清理

## 目标

彻底消除教程残留，并把项目整理成可以放
GitHub、简历和用于面试的完整项目。

------------------------------------------------------------------------

## 10.1 删除黑马点评残留

全仓库搜索：

``` text
shop
voucher
seckill
blog
follow
dianping
hmdp
geo
sign
bitmap
hyperloglog
```

判断是否仍有不应该存在的业务语义。

注意：

不能机械删除技术概念。

例如 Lua、Redis Set 等技术当然继续存在。

------------------------------------------------------------------------

## 10.2 删除历史代码

删除：

-   Redis Stream 历史实现；
-   多套旧秒杀实现注释；
-   废弃 Controller；
-   无用 Mapper；
-   无用 Entity；
-   无用 Redis Key；
-   无效测试；
-   教程注释。

------------------------------------------------------------------------

## 10.3 配置清理

处理：

-   明文密码；
-   固定 IP；
-   Windows 固定上传路径；
-   本地环境配置。

敏感信息使用：

``` text
Environment Variable
```

或：

``` text
application-local.yaml
```

并加入 `.gitignore`。

------------------------------------------------------------------------

## 10.4 测试

至少覆盖核心链路：

### Cache

-   Cache Hit
-   Cache Miss
-   Cache Penetration

### Reservation

-   正常预约
-   quota = 0
-   重复预约
-   并发预约
-   取消
-   重复取消

### RabbitMQ

-   正常消费
-   重复消息
-   Consumer 异常
-   DLQ
-   重放

### Consistency

-   Confirm NACK
-   Confirm Timeout
-   补偿
-   对账

------------------------------------------------------------------------

## 10.5 README

最终 README 至少包含：

1.  项目简介；
2.  项目背景；
3.  技术栈；
4.  核心功能；
5.  系统架构；
6.  高并发预约流程；
7.  Redis 设计；
8.  RabbitMQ 可靠性；
9.  数据一致性设计；
10. 数据库设计；
11. 启动方式；
12. 环境依赖；
13. API 示例；
14. 项目亮点；
15. 已知边界。

------------------------------------------------------------------------

## 10.6 最终 Maven 验证

至少：

``` bash
mvn clean compile
```

有完整测试后：

``` bash
mvn test
```

------------------------------------------------------------------------

## 10.7 最终代码审计

审查 Agent 对整个项目进行最后一次 Review：

### P0

-   超卖；
-   重复预约；
-   数据错误；
-   MQ 丢消息；
-   无法启动；
-   Transaction 错误。

必须全部清零。

### P1

-   业务逻辑漏洞；
-   权限问题；
-   缓存一致性问题；
-   MQ 可靠性问题。

原则上全部解决。

### P2

-   命名；
-   注释；
-   代码结构；
-   可读性。

在合理范围内处理。

## 验收标准

-   项目不再呈现明显黑马点评业务；
-   所有核心接口可以运行；
-   Maven 编译/测试通过；
-   README 完整；
-   配置无敏感信息；
-   核心并发链路有真实实现；
-   Agent 最终审计 PASS。

## 完成标志

``` text
PHASE 10 — PASS
PROJECT MIGRATION — COMPLETE
```

------------------------------------------------------------------------

# 14. 推荐的最终业务架构

``` text
                    实验资源预约平台
                           │
          ┌────────────────┼────────────────┐
          │                │                │
       普通用户        实验室管理员       系统管理
          │                │
          ▼                ▼
      查询设备          管理设备
          │                │
          │            发布预约批次
          │                │
          └───────┬────────┘
                  ▼
          ReservationBatch
                  │
             Redis Quota
                  │
                  ▼
              Lua 原子校验
                  │
        ┌─────────┴─────────┐
        │                   │
      失败                预接受
                            │
                            ▼
                       RabbitMQ
                            │
                            ▼
                  ReservationConsumer
                            │
                       Redisson
                            │
                            ▼
                    MySQL Transaction
                     ├── 扣减 quota
                     └── 创建 Reservation
                            │
                            ▼
                       Manual ACK
                            │
              ┌─────────────┼─────────────┐
              ▼             ▼             ▼
            查询          取消预约        到场核验
                            │
                            ▼
                    Redis/MySQL恢复
```

------------------------------------------------------------------------

# 15. 推荐 Git 提交方式

为了方便回滚，每个 Phase 独立提交。

例如：

``` text
chore: establish refactor baseline

refactor: remove legacy social and geo modules

feat: add laboratory and equipment domain

feat: add user roles and authorization

feat: add equipment caching

feat: add reservation batch management

feat: add redis lua reservation flow

feat: migrate reservation confirmation to rabbitmq

feat: improve reservation consistency and reliability

feat: add reservation cancellation and fulfillment

docs: finalize lab reservation platform
```

不要等十个 Phase 全部完成后才提交一次。

------------------------------------------------------------------------

# 16. MIGRATION_STATUS.md 推荐格式

项目根目录建议额外维护：

``` markdown
# Migration Status

## Current Phase

Phase 0 — Baseline

## Passed

None

## Current Work

- JDK 8 compile baseline
- Database baseline
- Redis baseline
- RabbitMQ baseline

## Pending

- Phase 1 Legacy Cleanup
- Phase 2 Laboratory & Equipment
- Phase 3 User & Authorization
- Phase 4 Equipment Cache
- Phase 5 Reservation Batch
- Phase 6 Redis + Lua Reservation
- Phase 7 RabbitMQ Reservation
- Phase 8 Consistency & Reliability
- Phase 9 Cancellation & Fulfillment
- Phase 10 Final Cleanup

## Known Issues

- None recorded yet

## Last Review

Not reviewed
```

每完成一个 Phase，由审查 Agent 更新。

------------------------------------------------------------------------

# 17. 每个 Phase Codex 的统一输出格式

要求 Codex 每次修改完成后只需要报告：

``` text
## Phase X 修改完成

### 修改文件
- ...

### 新增文件
- ...

### 删除文件
- ...

### 数据库变化
- ...

### Redis 变化
- ...

### RabbitMQ 变化
- ...

### 编译结果
PASS / FAIL

### 测试结果
PASS / FAIL / NOT RUN

### 尚未解决
- ...

### 是否进入下一 Phase
否，等待 Review Agent 审查。
```

这样可以明显减少无意义 Token。

------------------------------------------------------------------------

# 18. Review Agent 的统一验收格式

每次 Codex 完成后，Agent 输出：

``` text
# Phase X Review

## Result

PASS
```

或者：

``` text
# Phase X Review

## Result

FAIL

### P0
- ...

### P1
- ...

### P2
- ...

## Codex 最小修复指令
...
```

只有：

``` text
PASS
```

才能进入下一 Phase。

------------------------------------------------------------------------

# 19. 整体实施原则

整个改造过程中始终遵守：

> **先保证每一步正确，再追求最终项目完整。**

不要采用：

``` text
一次性：
Shop → Equipment
Voucher → Batch
Order → Reservation
Blog 删除
Follow 删除
Redis 改造
RabbitMQ 改造
数据库重建
```

这种大规模修改。

应该采用：

``` text
Phase 0
↓ PASS
Phase 1
↓ PASS
Phase 2
↓ PASS
Phase 3
↓ PASS
...
Phase 10
↓ PASS
完成
```

这样一旦出现问题，可以准确判断问题由哪个 Phase 引入。

------------------------------------------------------------------------

# 20. 最终目标

完成 Phase 10 后，项目应该从：

``` text
黑马点评
+
RabbitMQ 替换 Redis Stream
```

真正变成：

``` text
高校实验资源预约平台

实验室
↓
实验设备
↓
开放预约批次
↓
Redis + Lua 限量资格抢占
↓
RabbitMQ 异步确认
↓
Redisson 并发控制
↓
MySQL Transaction + Unique Index
↓
幂等 / 补偿 / DLQ / 对账
↓
预约取消
↓
管理员履约核验
```

最终判断标准不是"类名是否换掉"，而是：

> **业务模型、数据库模型、接口语义、状态机和核心业务规则已经属于实验资源预约领域；只复用了原项目中真正有价值的后端技术基础设施。**
