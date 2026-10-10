# 附录 · 实现细节与已知边界

> 本文件承接主 [README](../README.md) 中移出的**实现细节、实测记录与已知边界**。
> README 面向「快速了解与上手」，本附录面向「深入实现细节与面试追问」。
>
> 记录时间基准：**2026-10-07**（标注「实测」的条目均为当日在本机真实环境验证所得）。

---

## 目录

- [1. 项目来源：从黑马点评到研约通](#1-项目来源从黑马点评到研约通)
- [2. Redis 设计说明](#2-redis-设计说明)
- [3. RabbitMQ 可靠性细节](#3-rabbitmq-可靠性细节)
- [4. 数据一致性设计](#4-数据一致性设计)
- [5. 接口示例与错误语义](#5-接口示例与错误语义)
- [6. 实现亮点](#6-实现亮点)
- [7. 已知边界（逐条实测）](#7-已知边界逐条实测)

---

## 1. 项目来源：从黑马点评到研约通

本仓库前身是**黑马点评**课程项目——一个「探店笔记 + 优惠券秒杀 + 用户社交关注」的演示工程。它提供了一批很有价值的**基础设施能力**：

- Redis 缓存与缓存穿透/击穿防护（`CacheClient` + 互斥锁 + 逻辑过期）
- Redis 原子 Lua 脚本、Redisson 分布式锁
- RabbitMQ 异步下单与死信链路（原为「优惠券秒杀」的异步下单迁移产物）
- 登录会话（Redis token + 双拦截器续期）、MyBatis-Plus、全局异常兜底

重构的做法是：**保留并复用上述基础设施，整体替换业务域**。原课程的业务实体已**永久删除**，不复存在：

| 已删除（原课程） | 现在（研约通） |
|---|---|
| 商铺 `tb_shop` / 商铺类型 `tb_shop_type` | 实验室 `tb_laboratory` / 设备分类 `tb_equipment_category` |
| 探店笔记 `tb_blog` / 笔记评论 `tb_blog_comments` | 设备 `tb_equipment` |
| 优惠券 `tb_voucher` / 秒杀券 `tb_seckill_voucher` / 券订单 `tb_voucher_order` | 预约批次 `tb_reservation_batch` / 预约单 `tb_reservation` |
| 用户关注 `tb_follow` / 签到 `tb_sign` / 用户详情 `tb_user_info` | 预约请求 `tb_reservation_request` / 死信 `tb_reservation_dlq` |
| Redis GEO、Bitmap 签到、HyperLogLog UV、点赞、Feed 推流、共同关注、ZSet 热门榜 | 预约名额（String 计数）+ 去重集合（Set）+ 按日自增 ID（来自 `RedisIdWorker`） |

**保留的库名**：MySQL 库名仍是 `hmdp`（避免迁移期间改动用户既有数据；新装库建议自行改名并同步 `application.yaml`）。表前缀 `tb_` 亦保留。

**历史课程表的清理**：`phase10_drop_legacy_tables.sql` 提供 10 张已废弃课程表的幂等 `DROP`，仅从课程项目升级上来的旧库需要执行。

**关于 git 历史**：本仓库的**历史提交**中包含开发期的明文口令（源自原始课程提交）。当前工作区已全部脱敏，但**历史未改写**。因此**本仓库在公开前必须先改写历史或轮换口令**。

---

## 2. Redis 设计说明

- **会话 TTL 从 25 天压到 30 分钟**：因为每次请求都会续期，活跃用户完全无感；但 token 一旦泄漏，可用窗口从 25 天缩短到 30 分钟。改动只有 `RedisConstants.LOGIN_USER_TTL` 一行（单位是**分钟**）。
- **缓存三件套仍在用**：空值缓存（防穿透）、互斥锁重建（防击穿）、TTL 随机抖动（防雪崩）。这套能力由 `CacheClient` 提供，是重构中「保留基础设施」的典型。
- **Redis 与 DB 的职责边界**：Redis 负责**高并发准入判定**（快、原子、易失），DB 负责**账实相符**（慢、持久、可对账）。两者用 [§4 对账机制](#4-数据一致性设计) 缝合。
- **`CacheClient` 保留了两个没有调用方的查询实现**（`queryWithPassThrough` / `queryWithLogicalExpire`）。设备详情只走 `queryWithMutex`；这两个方法分别对应「缓存穿透」与「逻辑过期」两种范式，保留作参照，类注释已注明 `queryWithLogicalExpire` 为「未启用的备选实现」。这是全仓唯一一处有意保留的无调用方生产代码。

---

## 3. RabbitMQ 可靠性细节

**为什么关闭自动重试**：手动 ack 模式下，Spring AMQP 的默认 `RejectAndDontRequeueRecoverer` 并不会真正把消息 nack 进死信，反而会让消费者卡死。因此改为**监听器显式 `basicAck` / `basicNack(requeue=false)`**，并在容器级用 `ConditionalRejectingErrorHandler(rejectManual = true)` 兜住**反序列化失败**这类拿不到 `Channel` 的异常——否则坏消息会无限重投，堵死队列。

**投递侧的失败处理**：`ReservationServiceImpl.publishReservationMessage` 区分三类结果——投递确认失败、`publisher-returns` 退回、`AmqpConnectException`（MQ 不可达）——分别走不同的补偿路径（重试标记 PUBLISHED，或留给对账任务重投）。

**实测环境状态**（2026-10-07）：RabbitMQ 上只有本项目自己的队列 `reservation.create.queue` 与 `reservation.create.dlq`（各 0 条，durable、非 auto-delete），交换机 `reservation.exchange` / `reservation.dlx`。早期 README 提到的课程死队列 `hello,queue1` / `hello,queue2` **实测不存在**（已清理）。

**关于 `guest/guest` 未脱敏**：它是 RabbitMQ 官方文档公开的出厂默认值，且在本机实测**不可用**（管理 API 返回 401），不构成有效凭据，因此按「公开默认值」处理。

---

## 4. 数据一致性设计

这是「不丢单」的落点。三个机制叠加。

### ① 请求表 = 状态机 + 对账输入

`tb_reservation_request.status` 有明确的生命周期与终态判定：

```
PRE_ACCEPTED ──▶ PUBLISHED ──▶ CONFIRMED   （终态）
      │              │
      └──────────────┴──────▶ COMPENSATED  （终态，名额已回滚）
                    UNKNOWN                 （非终态，需对账）
```

`isTerminal()` 只认 `CONFIRMED` / `COMPENSATED`；`PRE_ACCEPTED` / `PUBLISHED` / `UNKNOWN` 都是非终态，即对账任务的扫描对象。

### ② 定时对账（`ReservationReconcileServiceImpl`）

每 `reconcile-interval-ms`（默认 5 分钟）扫描 `create_time < now - reconcile-threshold-ms`（默认 10 分钟）仍处非终态的请求，最多 `reconcile-limit`（默认 200）条：

- 该请求对应的预约**已经落库** → 补一个 `CONFIRMED`，收口；
- 未落库且**未超过阈值次数** → 记录失败原因并**推进 `update_time`**（让它退出「陈旧」窗口，下一轮再处理），等待重投；
- 失败次数**超过阈值** → 回滚 Redis 名额（`reservationRollback.lua`，幂等）并置 `COMPENSATED`。

> **两个易错点（已作为缺陷修复并留下回归用例）**
> 1. `update_time` 必须**显式赋值**。MySQL 的 `ON UPDATE CURRENT_TIMESTAMP` 在「写入值与当前值相同」时**不会**更新时间戳——只改 `fail_reason` 而不显式写 `update_time`，记录会永远留在陈旧窗口里，被反复扫描而无法退避。
> 2. 回滚写回 `update_time` 时不能写它自己的原值，否则等价于没写（同上原因）。

### ③ 数据库层的最后防线

`phase8_consistency.sql` / `phase9_reservation_lifecycle.sql` 在 `tb_reservation` 上提供唯一索引与 `active_flag` 条件更新：

```sql
UPDATE ... SET active_flag = 0 WHERE id = ? AND active_flag = 1
```

影响行数为 0 即说明已被并发修改，据此判定 `QUOTA_CONFLICT`。即使 Redis 判重被绕过，也不会出现两条「占用中」的预约。

**Redis 名额与 DB 的关系**：名额只在 Redis 扣减；DB 的预约行存在与否由 MQ 消费决定。二者的收敛由对账任务保证——这也是为什么 `COMPENSATED` 是一个**终态**而不是异常：它表示「这次 Redis 扣减已被明确撤销」。

---

## 5. 接口示例与错误语义

### 5.1 curl 示例

所有响应统一为 `Result`：`{ "success": true, "data": ..., "errorMsg": null, "total": null }`（`null` 字段默认不序列化）。登录后需在请求头带 `authorization: <token>`。

**登录**

```bash
curl -X POST 'http://localhost:8081/user/code?phone=13800000000'
# 验证码不回传响应体：它以 login:code:{phone} 写入 Redis（TTL 2 分钟）并打 debug 日志，
# 本地联调从应用日志里取，或直接 redis-cli GET login:code:13800000000
curl -X POST http://localhost:8081/user/login \
     -H 'Content-Type: application/json' \
     -d '{"phone":"13800000000","code":"<上一步拿到的 6 位验证码>"}'
# → { "success": true, "data": "<token>" }
curl http://localhost:8081/user/me -H "authorization: <token>"
```

**浏览（读路径，走缓存）**

```bash
# 除「登录」里的 /user/code 与 /user/login 两个端点外，其余端点一律需要登录
curl 'http://localhost:8081/laboratory/list?current=1' -H "authorization: <token>"
curl http://localhost:8081/equipment/1 -H "authorization: <token>"
curl 'http://localhost:8081/equipment/page?current=1&laboratoryId=1' -H "authorization: <token>"
curl 'http://localhost:8081/equipment-category/list' -H "authorization: <token>"
# 不带上面这行 header 时，这四个端点实测均为 401 且响应体为空（见下方「错误语义」）
```

**预约（写路径，Lua + MQ）**

```bash
# 提交预约（batchId 由管理员创建的批次给出）
curl -X POST http://localhost:8081/reservation \
     -H "authorization: <token>" -H 'Content-Type: application/json' \
     -d '{"batchId":1}'
# → { "success": true, "data": <reservationId> }

curl 'http://localhost:8081/reservation/page?current=1' -H "authorization: <token>"
curl -X POST http://localhost:8081/reservation/1/cancel -H "authorization: <token>"
```

**管理端（需 `LAB_ADMIN`）**

```bash
curl -X POST http://localhost:8081/reservation-batch \
     -H "authorization: <token>" -H 'Content-Type: application/json' \
     -d '{"equipmentId":1,"totalQuota":50,"bookingStartTime":"2026-10-07 08:00:00","bookingEndTime":"2026-10-07 18:00:00"}'
curl -X POST http://localhost:8081/reservation-batch/1/publish -H "authorization: <token>"
curl 'http://localhost:8081/reservation/manage/page?current=1' -H "authorization: <token>"
curl -X POST http://localhost:8081/reservation/1/verify -H "authorization: <token>"
curl 'http://localhost:8081/reservation-dlq/page?current=1' -H "authorization: <token>"
curl -X POST http://localhost:8081/reservation-dlq/1/replay -H "authorization: <token>"
```

### 5.2 错误语义（实测）

「空体」指响应体 0 字节。

| 情形 | HTTP | 响应体 |
|---|---|---|
| 无 token / token 失效 | `401` | **空体** —— `LoginInterceptor` 只 `setStatus(401)`，不写 body |
| 已登录但角色不符（USER 打 `⚙` 端点） | `403` | **空体** —— `RoleInterceptor` 只 `setStatus(403)`，不写 body |
| 角色正确但**无归属权限**（操作他室设备、他人预约） | `403` | `Result.fail("<中文原因>")`，如 `无权管理该实验室`、`无权核验该预约` |
| 业务校验失败（非草稿批次被改、时段重叠、设备状态值非法…） | `400` | `Result.fail("<中文原因>")` |
| 路径段不是数字（如 `/laboratory/abc`） | `500` | `Result.fail("服务器异常")` |
| 路径未映射 | `404` | Spring 默认错误体（**不是** `Result` 形状） |

### 5.3 「资源不存在」在详情端点上有两种形态

前端两种都要处理（实测）：

| 端点 | 资源不存在时 |
|---|---|
| `GET /equipment/{id}` | `Result.fail("设备不存在")` —— 业务失败形态 |
| `GET /reservation-batch/{id}` | `Result.fail("预约批次不存在")` |
| `GET /reservation-dlq/{id}` | `Result.fail("死信记录不存在")` |
| `GET /laboratory/{id}` | **`200` + `{"success":true}`**，`data` 字段被省略（即 `Result.ok(null)`）—— 与上三者不同形 |
| `GET /reservation/{id}` | `403` + `Result.fail("无权查看该预约")` —— 他人记录与不存在**同形**，不区分，避免泄露存在性 |

**新增管理员时的两个静默失败**（都实测过）：`phone` 不存在时 `UPDATE tb_user` 影响 **0 行**且不报错；紧接着的子查询返回 `NULL`，于是 `UPDATE tb_laboratory` 把 `admin_user_id` **写成 NULL**，该实验室的管理端随即变成空白——现象像「权限没生效」，实际是归属被清掉了。

**登录验证码不回传是有意为之**：曾考虑加一个「回传验证码」的开关，但那等于在配置层面开一个认证旁路，一旦误开在任何非本机 profile 就是真漏洞。本机单人演示下，「看一眼控制台」的成本更低。

---

## 6. 实现亮点

1. **Redis 与 DB 的职责被明确切开**：Redis 做高并发准入（Lua 原子），DB 做账实相符，中间用「请求状态机 + 定时对账」缝合，而不是把一致性寄托在某一个组件上。
2. **三套缓存工具各司其职，没有被「统一」掉**：`CacheClient`（穿透/击穿/雪崩三件套）、`RedisIdWorker`（全局唯一 ID）、Redisson 锁（看门狗续期）解决的是三个不同问题，重构中刻意都保留。
3. **消息可靠性是被实测过的，不是抄配置**：手动 ack 下默认 recoverer 不真正 nack 这个坑，是通过反编译 Spring AMQP 字节码核对后确定，并据此改为显式 `basicNack` + 容器级 `ConditionalRejectingErrorHandler`。
4. **每个修复都有「改坏后测试变红」的证据**：关键行为（依赖检查、字段不外泄、异常状态码、对账退避）都用**变体实验**验证过判别力——把修复改回坏状态，确认对应用例确实变红，而不是只看「测试全绿」。
5. **诚实的测试观**：对**没有判别力证据**的改动（纯常量、纯文案、无法确定性构造的竞态分支）明确登记为「无判别力证据」，不冒充已验证。
6. **验收清单是可复跑的脚本，不是散落的命令**：`bash scripts/verify-acceptance.sh` 把「旧命名零残留 / 黑马语义关键词 / 凭据 / 仓库形态 / **未跟踪面** / 禁改清单」编码进去，自带三项自证——探测范围里先找到一个已知存在的串，才允许采信它的 0 命中；**未跟踪面那一段另有一次外置的正向前置**（在 `/tmp` 造探针），免得该范围变空后退化成恒绿摆设。**凭据探测器的失败输出只给 `文件:行号`，不回显匹配内容**——报凭据风险只报位置与计数。可选 `SCAN_SECRETS='...'` 写字面量口令（脚本本身入库，所以不内嵌任何口令），`PHASE_BASE=<commit>` 查禁改文件是否被动过。

---

## 7. 已知边界（逐条实测）

### 7.1 设计取舍（有意不做）

- **不使用微服务 / 注册中心 / 分库分表 / ES / Kafka / 分布式事务框架**。当前业务量级下它们是纯负债；单库 + 两个中间件已足够，且可验证。
- **库名保留 `hmdp`**、**表前缀保留 `tb_`**：改名收益是「观感」，成本是全量 DDL 与 SQL 的机械 churn，并破坏历史脚本可追溯性。
- **设备名称搜索用 `LIKE '%name%'`**，不走索引。当前设备量级（十几行）下，搜索语义正确优先于索引。
- **`CacheClient` 保留两个无调用方的查询实现**（见 §2）。
- **缓存删除不做补偿**：`EquipmentServiceImpl.deleteEquipmentCache` 直接调 `stringRedisTemplate.delete`，既无 try/catch 也无重试——Redis 不可用时异常会直接抛出，表现是「库里的写已经生效、接口却报错」，缓存里的旧值要等 30 分钟 TTL 才过期。另外 `queryWithMutex` 抢锁重试 20 次仍失败时会直查数据库且**不回写缓存**，此时该热键的并发会全部压到 DB。两者都登记为已知边界：为它们引入补偿链路，代价大于收益。

### 7.2 行为与契约

- **删除实验室是行为变更**：现在会**拒绝**删除「仍挂有设备」的实验室（返回业务错误），而早期版本能删。这是有意的防御性变更。
- **管理员取权只能靠 SQL**（见 README 快速开始 §4）。系统没有管理员注册入口。
- **`RoleInterceptor` 的「类级注解」分支目前没有使用者**，且 403 响应体为空——属有意保留的扩展点。

### 7.3 数据与环境

- **`tb_user` 里有 1011 行课程时代遗留用户**（外加 2 个 `LAB_ADMIN`）。这些行不会被自动清理；新装库由迁移链建库时不会有它们。终验对用户表的判据是「行数未变」，不是「0 行」。
- **`phase10_drop_legacy_tables.sql` 是一次性脚本**：仅从课程项目升级的旧库需要执行，且**不可逆**（会删除 `tb_shop` 14 行 / `tb_blog` 4 行 / `tb_voucher` 1 行等课程演示数据）。新装库不需要它。它同时也是全仓库唯一集中写出课程表名的地方，保留它是为了让旧库仍可清理。
- **Redis 里的键族全部是在用键**（2026-10-07 实测，课程时代残留已清理干净）：`icr:reservation:<年>:<月>:<日>` 按日自增、`reservation:batch:quota:*` 批次剩余名额、`reservation:batch:users:*` 批次去重集合、`lab:equipment:<id>` 设备详情缓存、`login:token:*` 会话。**`icr:order:*` 实测 0 个**（早期 README 提到的 3 个残留计数键已删除）；`icr:reservation:*` 是在用键，不要误删。
- **管理端预约列表是「真实数据规模」下的样子**：压测夹具留在库里，`tb_reservation` 全表 **301350 行**；管理端 `/reservation/manage/page` 按 `l.admin_user_id` **归属过滤**，`13900000001`（名下实验室 1、2）看到 **301344 行**、另一位管理员（实验室 3、4）看到 6 行，每页固定 5 条即约 **60269 页**（后端所有分页端点固定 5 条/页、Query DTO 只有 `current` 没有 `pageSize`，故前端的分页控件不提供「每页条数」选择器——取舍见 `docs/frontend/FRONTEND-DESIGN.md` §11 的 D-6）。这**不是缺陷**：同页内与跨页的 18 位 id 均无重复，排序与分页经 bigint 安全解析复核过（早先「同一行重复 5 次」的现象是原生 `JSON.parse` 精度塌缩造成的假象）。
- **560px 以下的适配未实测**：响应式手机档定在 560px（`docs/frontend/FRONTEND-DESIGN.md` §8），而真机扫描的下限是 600px（20 条路由 × 5 档宽度，100 段全绿）。

### 7.4 演示链路的边界

两条演示链路的边界（2026-10-07 实测，详见 `docs/frontend/FRONTEND-DESIGN.md` §10 的 F12 记录）：

- **死信治理 / 重放无活数据**：`tb_reservation_dlq` 为 **0 行**，F10 的夹具已消耗，该链路只能靠 F10 的实测记录佐证。
- **核验的成功路径当天演不出来**，这是设计使然而非缺陷：publish 强制 `booking_end_time ≤ start_time`、booking 要求 `now < booking_end_time`，故新预约必然 `start_time > now`；而核验闸门要求 `now >= batch.start_time`。演示时展示的是**闸门本身**（管理员 `400 预约尚未开始，无法核验` / 学生 `403` 空体 / 无 token `401` 空体）。若确实要演成功路径，需把某批次的时段手工改到过去——`scripts/demo-readiness.sh` 的头部注释给了这条 SQL 与其逆操作，**本仓库不会自动执行**它。
- **`scripts/demo-readiness.sh` 是「基线态」检查，不是「健康」检查**：它把当前环境与**已记录的基线**逐项比对（`tb_reservation` 301350、`tb_reservation_batch` 23 等），因此**任何改数据的操作都会让它报 `[FAIL]`——包括演示本身**（下一次预约、发布一个批次、取消、核验）。正确用法是**先跑体检、后演示**；演示之后那几项 `[FAIL]` 的含义是「环境已偏离基线」，**不是环境损坏**（完整说明见脚本头部的「适用范围」段）。

### 7.5 安全

- **git 历史含明文口令**（源自原始课程提交），**未改写**。工作区已全部脱敏：文档中的口令字面量已替换为等义描述，真实口令只存在于被 `.gitignore` 忽略的 `application-local.yaml`。**若要公开此仓库，必须先改写历史或轮换口令。**
- **RabbitMQ 的 `guest/guest` 未脱敏**：它是 RabbitMQ 官方文档公开的出厂默认值，且在本机实测**不可用**（管理 API 返回 401），不构成有效凭据，因此按「公开默认值」处理。
- **`benchmark/` 目录被 `.gitignore` 整目录忽略**：13 个文件里 `password` 命中 4、`authorization` 命中 5，一次 `git add -A` 就会把真实口令写进不可撤销的历史。需要入库请用 `git add -f`。
