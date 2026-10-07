# 研约通 · 前端总体设计与 Phase 拆分（Round 1 · 只设计，不写代码）

> 角色：前端架构师与代码审查负责人。本轮**未修改任何前端/后端代码、数据库、Redis、RabbitMQ**。
> 事实来源：**后端当前代码 + 2026-10-06 在本机启动真实实例的实测**。README 只作为入口，与代码冲突处一律以代码与实际响应为准。

---

## 0. 证据等级与环境复原

本文每条结论都标了来源，避免把"读出来的"当成"测出来的"：

| 标记 | 含义 |
|---|---|
| **【实测】** | 2026-10-06 以 `Corretto 1.8` 启动后端实例（`--server.port=18081`，避开被占用的 8081/8082），用真实 HTTP 请求验证，本文给出的是**实际响应原文** |
| **【读码】** | 只从源码读出，本轮未做运行时验证 |
| **【推断】** | 由前两者推导，未直接验证 |

**实测方法**：`mvn spring-boot:run` 起实例 → `curl --noproxy '*'` 直连 → 用 `tb_user` 中**既有**用户（13686869696 / 13900000001）走 `/user/code` + `/user/login` 取 token（**未新建任何用户行**）。

**现场复原（已复核）**：实例已停（`18081` 释放、无残留 java 进程）；MySQL 8 张在用表行数与探测前逐表一致（`tb_user` 1013 / `tb_laboratory` 4 / `tb_equipment` 12 / `tb_equipment_category` 6 / 4 张预约表 0 行）；Redis 精确回到基线（只剩 3 个在用的 `icr:reservation:2026:10:04/05/06`）；RabbitMQ 两个本项目队列 `messages=0 consumers=0`；`git status --porcelain` 为空。

> 探测过程中产生的 `lab:equipment:999999`（**缓存空值防穿透**条目，`CACHE_NULL_TTL=2` 分钟）与 2 个 `login:token:*` 已删除。留档：该键 `TYPE=string`、值为空串、`TTL=97`（与 2 分钟窗口吻合）。

---

## 1. 后端能力审计

### 1.1 五条响应通道【实测】—— 这是整个前端的地基

前端**不能**只按 HTTP 状态码分发，也**不能**假定响应体一定是 `Result`。实测到的通道有 **5 条**：

| # | 形态 | 触发来源 | 实测原文 |
|---|---|---|---|
| **T1** | `HTTP 200` + `Result{success:true}` | 正常成功 | `POST /user/code` → `{"success":true}` |
| **T2** | `HTTP 200` + `Result{success:false,errorMsg}` | **Controller 直接 `Result.fail()`** | `GET /equipment/999999` → `200 {"success":false,"errorMsg":"设备不存在"}`；`GET /reservation-batch/999999` → 同形 |
| **T3** | `HTTP 400/403/503/500` + `Result{success:false,errorMsg}` | 抛异常经 `WebExceptionAdvice` | `POST /reservation {"batchId":999999999}` → `400 {"success":false,"errorMsg":"预约批次不存在"}` |
| **T4** | `HTTP 401/403` + **响应体为空**（长度 0） | `LoginInterceptor` / `RoleInterceptor` 直接 `setStatus` 后 `return false` | `GET /laboratory/list`（无 token）→ `401` body=`[]`；USER 访问 `/reservation/manage/page` → `403` body=`[]` |
| **T5** | `HTTP 400/404` + **Spring 默认错误 JSON（不是 Result）** | 参数类型转换失败 / 无匹配路由 | `GET /equipment/page?current=abc` → `400 {"timestamp":"2026-10-06T09:39:18.392+00:00","status":400,"error":"Bad Request","message":"","path":"/equipment/page"}` |

> **T5 的 404 分支必须有 token 才可达（F1 实测，此前写错）**：`LoginInterceptor` 注册在 `/**` 上，
> 对**没有匹配 handler 的路径**（Spring 兜到 `ResourceHttpRequestHandler`）**按路径模式的拦截器照样执行**
> → 未登录时先 `setStatus(401)` + 空体并 `return false`，**404 永远到不了**。
> 实测：`GET /no-such-endpoint-xyz` 无 token → `401`（`Content-Length: 0`）；
> 带有效 USER token → 才得到 `404` + T5 体。
>
> **下游含义（前端必须知道）**：接口路径写错时，用户看到的是**"登录已失效"并被踢去登录页**，
> 而不是 404。所以 F5 的轮询/F11 的排错**不能把 `unauthorized` 一律当成"会话过期"**——
> 它也可能是路径写错。反之，任何"无 token 也能打到 404"的假设都不成立。

**同一个"资源不存在"，5 个端点给出 4 种不同形状**【实测】：

| 端点 | HTTP | 响应体 |
|---|---|---|
| `GET /laboratory/999999` | **200** | `{"success":true}` ← **`success=true` 却无 `data` 键**，最阴的一个 |
| `GET /equipment/999999` | 200 | `{"success":false,"errorMsg":"设备不存在"}` |
| `GET /reservation-batch/999999` | 200 | `{"success":false,"errorMsg":"预约批次不存在"}` |
| `GET /reservation-dlq/999999` | 200 | `{"success":false,"errorMsg":"死信记录不存在"}` |
| `GET /reservation/999999` | **403** | `{"success":false,"errorMsg":"无权查看该预约"}` |

→ **结论**：Axios 响应层必须实现一个统一的"响应归一化"函数，把 5 条通道收敛成 `{ok, data, total, message, kind}`，页面永不直接读 `res.data.success`。

### 1.2 `Result` 契约【读码 + 实测】

```java
class Result { Boolean success; String errorMsg; Object data; Long total; }   // 无 code 字段
```
`application.yaml`：`spring.jackson.default-property-inclusion: non_null` → **null 字段整个键消失**。
实测 `{"success":true,"data":[...],"total":0}`：**只有分页接口才有 `total`**；`Result.ok()` → `{"success":true}`（`data`/`errorMsg`/`total` 三个键都不出现）。

**前端推论**：判断"有没有数据"必须用 `'data' in res` 或 `res.data == null`，**不能用 `res.data === undefined` 之外的宽松假值判断**；`total` 缺失时按 0 处理。

### 1.3 鉴权模型【读码 + 实测】

三个拦截器（`MvcConfig`，order 0/1/2）：

| 拦截器 | 路径 | 行为 |
|---|---|---|
| `RefreshTokenInterceptor` (0) | `/**` | 读 header **`authorization`**（值就是裸 token，无 `Bearer` 前缀）；命中 `login:token:{token}` 则填 `UserHolder` 并把 TTL 续到 **30 分钟**（滑动过期，`LOGIN_USER_TTL=30` 分钟）；未命中或空则**放行**（交后续拦截器判） |
| `LoginInterceptor` (1) | `/**` **除 `/user/code`、`/user/login`** | `UserHolder == null` → `401` + **空体** |
| `RoleInterceptor` (2) | `/**` | 无 `@RequireRole` → 放行；无 user → `401`；角色不匹配 → `403`（**空体**） |

> ⚠️ `LoginInterceptor` 只写了 `excludePathPatterns`、没写 `addPathPatterns`，按 Spring 语义等价于 `/**`。**实测已证实**：不带 token 访问 `GET /laboratory/list`、`/laboratory/1`、`/equipment/page`、`/equipment/1`、`/equipment-category/list`、`/reservation-batch/page`、`/reservation-batch/1`、`/user/me` **全部 401 且响应体为空**。

→ **结论（决定页面树）**：**除了登录页，前端没有任何公开页面。** 未登录用户看不到"实验室浏览/设备浏览"。**不存在"未登录首页"**，路由守卫必须是"未登录 → 一律跳 `/login`"。

> 📌 README §13 里那几条**不带 header 的浏览类 curl 示例**（`curl 'http://localhost:8081/laboratory/list?current=1'`）实际会返回 401 —— 这是**文档缺口**，见 §11 D-10（**订正**：此处原写 D-1，系笔误——D-1 是验证码缺口，与 README 示例无关）。

**登出**：`POST /user/logout` 读 `authorization` header 删除 token【实测：200 `{"success":true}`，随后同 token 访问 `/user/me` → 401】。

> ⚠️ **`/user/logout` 本身受 `LoginInterceptor` 保护**（它**不在** `excludePathPatterns` 的两个路径里）——【实测】无效 token → `401` 空体；**根本不带 token → 也是 `401` 空体**。
> **这条直接决定 F2 的登出实现顺序**：必须**先带 token 调后端**（否则 Redis 里的会话不会被删，旧 token 还能再用 30 分钟），**再**清本地状态；且**后端返回什么都必须清本地**（用户点"退出"的意图是"让我退出"，不是"请后端批准"）。
> F2 验收④（登出后旧 token 请求返回 401）正是在测这个顺序**有没有写反**——写反了该用例就会拿到 200。

**F2 用到的另外三条实测事实**（2026-10-06，`--server.port=18081`）：

| 事实 | 实测值 | 对 F2 的含义 |
|---|---|---|
| `POST /user/login` 成功体 | `{"success":true,"data":"54425a125e8145c4b9af349f817b4964"}` —— **`data` 是裸字符串**（32 位 UUID 去横线），**不是对象** | `store.login()` 取 `result.data` 即为 token；**不得**写成 `result.data.token` |
| `GET /user/me` 的 `icon` | USER(id=1) → `"/imgs/blogs/blog1.jpg"`；LAB_ADMIN(id=1016) → `""`（空串，**非 null**，故 `non_null` 不会丢键） | **两种取值都不可用**：前者是黑马遗留路径，后端**已不存在静态资源目录**【实测 `src/main/resources` 下只有 `db/`、`mapper/`】，`/imgs/blogs/blog1.jpg` 带 token 返回 **404**、不带 token 返回 **401**；后者是空串。→ **F2 不得把 `icon` 直接塞进 `<img src>`**，一律用昵称首字生成占位头像 |
| `POST /user/login` 请求体非法 JSON | **`400`** + Result 形状 `{"success":false,"errorMsg":"请求体格式错误"}` | 归一为 `kind='error'`（§7.3 第 5 行），页面展示 `errorMsg` 即可，**不需要为此写特例** |

### 1.4 端点全表（33 个，7 个 Controller）

**「权限」列**：`公开` = 无需登录（实测**不存在**此类端点，仅 `/user/code`、`/user/login` 两个）；`登录` = 任意已登录用户；`LAB_ADMIN` = 需 `@RequireRole(LAB_ADMIN)`；`归属` = 服务层按 `laboratory.admin_user_id` 校验。

#### 用户 `/user`

| 端点 | 方法 | 入参 | 返回 | 权限 |
|---|---|---|---|---|
| `/user/code` | POST | **query** `phone` | `{"success":true}`（**验证码只进服务端日志**） | 白名单 |
| `/user/login` | POST | body `LoginFormDTO{phone,code}` | `{"success":true,"data":"<token 字符串>"}` | 白名单 |
| `/user/logout` | POST | header `authorization` | `{"success":true}` | 登录 |
| `/user/me` | GET | — | `{"success":true,"data":{id,nickName,icon,role}}` | 登录 |

【实测】`/user/me` → `{"id":1,"nickName":"小鱼同学","icon":"/imgs/blogs/blog1.jpg","role":"USER"}`。
【读码】登录失败返回 **T1 形态的 200 + `success:false`**（`Result.fail("手机号格式错误！")` / `Result.fail("验证码错误")`）——不是 400。
【读码】**无注册端点**；`tb_user` 不存在该手机号时会自动建 `role=USER` 的新用户；**管理员只能靠 SQL 提权**，前端不得提供"注册管理员"。

#### 实验室 `/laboratory`

| 端点 | 方法 | 入参 | 返回 | 权限 |
|---|---|---|---|---|
| `/laboratory/{id}` | GET | path id | `LaboratoryVO`（**不存在时 `{"success":true}` 无 data**） | 登录 |
| `/laboratory/list` | GET | query `campus`(可选)、`current`(默认1) | `Result.ok(records,total)` | 登录 |
| `/laboratory` | POST | body `Laboratory` | `{"success":true,"data":<新id>}` | LAB_ADMIN |
| `/laboratory` | PUT | body `Laboratory`（含 id） | `{"success":true}` | LAB_ADMIN + **归属** |
| `/laboratory/{id}` | DELETE | path id | `{"success":true}` | LAB_ADMIN + **归属** |

【读码】列表**只支持 `campus` 精确匹配**，无 `status`/`name` 筛选；**无归属过滤**（实测管理员 1016 只负责实验室 1、2，却返回了 1/2/3/4）→ 见 §11 **D-2**。
【读码】删实验室时若下面还挂着设备 → `400 "该实验室下仍有 N 台设备，请先转移或删除设备"`。
【读码】`PUT /laboratory` 传 `{}` → `assertManageable(null)` → **403 `"无权管理该实验室"`**【实测】。

#### 设备 `/equipment`、分类 `/equipment-category`

| 端点 | 方法 | 入参 | 返回 | 权限 |
|---|---|---|---|---|
| `/equipment/{id}` | GET | path id | `EquipmentVO`（**走 Redis 缓存**） | 登录 |
| `/equipment/page` | GET | query `EquipmentQueryDTO` | `Result.ok(records,total)` | 登录 |
| `/equipment` | POST | body `Equipment` | `{"success":true,"data":<新id>}` | LAB_ADMIN + **归属** |
| `/equipment` | PUT | body `Equipment`（含 id） | `{"success":true}` | LAB_ADMIN + **归属** |
| `/equipment/{id}/status` | PUT | path id + **query** `status` | `{"success":true}` | LAB_ADMIN + **归属** |
| `/equipment/{id}` | DELETE | path id | `{"success":true}` | LAB_ADMIN + **归属** |
| `/equipment-category/list` | GET | — | `Result.ok(List<EquipmentCategory>)` | 登录 |

`EquipmentQueryDTO`：`laboratoryId`、`categoryId`、`campus`、`name`(**LIKE %name%**)、`status`、`bookingEnabled`、`current`(默认1)。
【实测】**非法枚举塞 query → 走 T5**：`?status=BOGUS` → `400` + **非 Result 体**（`message` 为空串，前端拿不到任何可展示文案）。
【实测】`PUT /equipment/9/status?status=BOGUS` → **200 + `success:false,"设备状态不合法"`**（Controller 内 try/catch，**与 query 绑定的行为不一致**）。
【实测】越权改他人设备：管理员 1016 改设备 11（属实验室 4、负责人 1019）→ `403 "无权管理该实验室"` ✓ 归属生效。
【实测】`POST /equipment {"name":"x"}`（缺 `laboratoryId`）→ `403 "无权管理该实验室"`（不是"实验室不能为空"）。

#### 预约批次 `/reservation-batch`

| 端点 | 方法 | 入参 | 返回 | 权限 |
|---|---|---|---|---|
| `/reservation-batch` | POST | body `ReservationBatch` | `{"success":true,"data":<新id>}` | LAB_ADMIN + **归属** |
| `/reservation-batch` | PUT | body（**仅 DRAFT 可改**） | `{"success":true}` | LAB_ADMIN + **归属** |
| `/reservation-batch/{id}/publish` | POST | path id | `{"success":true}` | LAB_ADMIN + **归属** |
| `/reservation-batch/{id}/close` | POST | path id | `{"success":true}` | LAB_ADMIN + **归属** |
| `/reservation-batch/{id}/cancel` | POST | path id | `{"success":true}` | LAB_ADMIN + **归属** |
| `/reservation-batch/{id}` | GET | path id | `ReservationBatchVO` | 登录 |
| `/reservation-batch/page` | GET | query `ReservationBatchQueryDTO` | `Result.ok(records,total)` | 登录 |
| `/reservation-batch/manage/page` | GET | query 同上 | `Result.ok(records,total)` | LAB_ADMIN |

`ReservationBatchQueryDTO`：`equipmentId`、`laboratoryId`、`status`、`current`。
【实测】**`status` 只能是 `PUBLISHED`/`CLOSED`**：`?status=DRAFT` → `400 {"success":false,"errorMsg":"批次状态不合法"}`；`?status=BOGUS` → T5 形态。
【读码】`GET /{id}` 对**非管理员且批次为 DRAFT/CANCELLED** 时返回 `null` → 实测 `200 {"success":false,"errorMsg":"预约批次不存在"}`（**注意是 200，不是 404**）。
【读码】`GET /page` 服务端强制 `status IN ('PUBLISHED','CLOSED')`；`GET /manage/page` 按 `l.admin_user_id = 当前用户` 过滤 ✓。
【读码】**日期必须是 ISO-8601**：实测 `{"startTime":"2026-10-06 20:36:34"}`（空格分隔）→ `400 "请求体格式错误"`；`"2026-10-06T20:36:34"` 才通过。
【读码】发布前置校验（失败均为 400，文案可直接展示）：总名额≥1 / 使用开始<结束 / 预约开放开始<截止 / 预约截止≤使用开始 / 预约截止>当前时间 / 设备 `status=AVAILABLE && bookingEnabled=true` / **同设备时段不得重叠**。发布成功会写 Redis `reservation:batch:quota:{id}`（TTL = 到 `endTime+24h`）。
【读码】`remainingQuota` 对 PUBLISHED 批次**从 Redis 读**（`multiGet`，缺失回落 MySQL）；`bookingState` **由服务端计算后下发**。

#### 预约 `/reservation`

| 端点 | 方法 | 入参 | 返回 | 权限 |
|---|---|---|---|---|
| `/reservation` | POST | body `ReservationSubmitDTO{batchId}` | `{"success":true,"data":<reservationId>}` | 登录 |
| `/reservation/page` | GET | query `ReservationQueryDTO` | `Result.ok(records,total)` | 登录（只返回**自己**的） |
| `/reservation/{id}` | GET | path id | `ReservationVO` | 登录 + **仅本人** |
| `/reservation/manage/page` | GET | query 同上 | `Result.ok(records,total)` | LAB_ADMIN（按 `l.admin_user_id` 过滤） |
| `/reservation/{id}/cancel` | POST | path id | `{"success":true}` | 登录 + **仅本人** |
| `/reservation/{id}/verify` | POST | path id | `{"success":true}` | LAB_ADMIN + **归属** |

`ReservationQueryDTO`：`batchId`、`status`、`current`。
【读码】**提交预约的失败语义（全是可直接展示的中文）**：

| 触发 | HTTP | errorMsg |
|---|---|---|
| `batchId` 为 null / 批次不存在 | 400 | `预约批次不存在` |
| 批次非 PUBLISHED | 400 | `批次当前不可预约` |
| 设备非 AVAILABLE 或未开启预约 | 400 | `设备当前不可预约` |
| 未到开放时间 / 已过截止 | 400 | `预约尚未开始` / `预约已截止` |
| Lua 返回 1 | 400 | `批次未开放预约` |
| Lua 返回 2 | 400 | `名额已约满` |
| Lua 返回 3 | 400 | `你已预约过该批次` |
| Lua 返回 4 / 5 | 400 | `预约尚未开始` / `预约已截止` |
| 账本落库失败 | 503 | `预约失败，请重试` |
| 消息**确定未投递**（已回滚名额） | 503 | `预约失败，请重试` |
| 消息**投递结果不确定**（名额不回滚） | 503 | `预约结果待确认，请稍后查询` |
| 请求体非法 JSON | 400 | `请求体格式错误` |

【实测】`{"batchId":999999999}` → `400 "预约批次不存在"`；`{}` → `400 "预约批次不存在"`；非法 JSON → `400 "请求体格式错误"`；重复提交 → `400 "你已预约过该批次"`。
【读码】`cancel`：批次已结束 → `400 "预约已结束，无法取消"`；非 RESERVED 且非 CANCELLED → `400 "当前状态不可取消"`；**已取消再取消 → 200 幂等返回，无任何提示**。
【读码】`verify`（核验）：未到 `batch.startTime` → `400 "预约尚未开始，无法核验"`；非 RESERVED（CANCELLED/NO_SHOW）→ `400 "当前状态不可核验"`；**已 COMPLETED 再核验 → 200 幂等返回，无任何提示**；无权 → 403 `"无权核验该预约"`。
→ **前端推论**：`cancel`/`verify` 的 200 **不区分"这次真的改了"和"本来就是那个状态"**，页面必须在成功后**重新拉取**才能显示真实状态。

#### 死信 `/reservation-dlq`

| 端点 | 方法 | 入参 | 返回 | 权限 |
|---|---|---|---|---|
| `/reservation-dlq/page` | GET | query `ReservationDlqQueryDTO{status,current}` | `Result.ok(records,total)` | LAB_ADMIN |
| `/reservation-dlq/{id}` | GET | path id | `ReservationDlqVO`（**含完整 payload**） | LAB_ADMIN |
| `/reservation-dlq/{id}/replay` | POST | path id | 成功 `Result.ok(record)`／失败 **200 + `Result.fail(reason)`** | LAB_ADMIN |

【读码】列表**不查 `payload`**（该字段在列表里恒为 null，避免放大响应体）；详情才返回完整 payload。
【读码】`replay` 的拒绝原因是**机器可读代码**，不是中文：`死信记录不存在`、`死信记录已处理，不能重复重放`、`已达到最大重放次数`（默认上限 3，配置 `yanyuetong.reservation.dlq-max-replay`）、`payload 无法重放`、`已落库，无需重放`、`请求已补偿，无法重放`、`REPLAY_CONFIRM_NACK`、`REPLAY_CONFIRM_RETURN`、`REPLAY_PUBLISH_UNKNOWN`、`死信记录已被并发重放` / `已被并发处理`。
→ **前端推论**：这里**混了中文与英文代码**，前端应做一层 `replayReasonMap`（中文原样展示，英文代码映射为中文 + 保留原文），不能直接展示 `REPLAY_CONFIRM_NACK`。

### 1.5 枚举清单（11 个，全部以后端为准）

| 枚举 | 取值 | 前端可达性 |
|---|---|---|
| `UserRole` | `USER`、`LAB_ADMIN` | 可达（`/user/me`） |
| `LaboratoryStatus` | `OPEN`、`CLOSED`、`MAINTENANCE` | 可达 |
| `EquipmentStatus` | `AVAILABLE`、`MAINTENANCE`、`DISABLED` | 可达 |
| `ReservationBatchStatus` | `DRAFT`、`PUBLISHED`、`CLOSED`、`CANCELLED` | 可达（管理端） |
| **`BookingState`** | `DRAFT`、`NOT_STARTED`、`OPEN`、`EXPIRED`、`CLOSED`、`CANCELLED` | 可达，**服务端计算后下发**（学生端主用） |
| `ReservationStatus` | `RESERVED`、`CANCELLED`、`COMPLETED`、`NO_SHOW` | 可达 |
| `ReservationDlqStatus` | `NEW`、`REPLAYED`、`DISCARDED` | 可达 |
| `ReservationCancelOutcome` | `TRANSITIONED`、`ALREADY_CANCELLED`、`NOT_CANCELLABLE`、`NOT_FOUND` | **不可达**（服务内部枚举，不出现于任何响应） |
| `ReservationPersistResult` | `PERSISTED`、`DUPLICATE`、`QUOTA_CONFLICT` | **不可达**（服务内部） |
| `ReservationRequestStatus` | `PRE_ACCEPTED`、`PUBLISHED`、`CONFIRMED`、`UNKNOWN`、`COMPENSATED` | **不可达**（无端点暴露，见 §11 **D-3**） |
| `ReservationResultCode` | `0..5` + 中文 message | **不可达**（仅用于生成 400 文案） |

### 1.6 序列化实测（三个必须提前处理的坑）

1. **null 键消失**：`EquipmentVO.image` 为空时**该键不存在**，不是 `null`。
2. **日期 = `yyyy-MM-ddTHH:mm:ss`（无时区、无毫秒）**：实测 `"2026-10-03T18:00:48"`。**提交时必须同格式**，空格格式会被判 `400 "请求体格式错误"`。
3. **⚠️ `reservationId` 是雪花长整型，超出 JS 安全整数范围**——本轮最严重的集成风险：

```
RedisIdWorker.nextId() = timestamp << 32 | count      // 任何 2022 年后的日期都 ≥ 2^53
后端返回原文         : 645589569487503751   （18 位）
JS JSON.parse 后打印 : 645589569487503700   ← ≠ 原值（同一个 double，Python 侧算出的是 ...744）
Number.MAX_SAFE_INTEGER = 9007199254740991  （16 位）
```

**解决方式已实测确认（`json-bigint@1.0.0` + `{ storeAsString: true }`）**：

| 输入 | 输出 | 类型 |
|---|---|---|
| `645589569487503751`（雪花 id） | `"645589569487503751"` | **string，与原文字节相同** ✅ |
| `1013` / `10999` / `0` / `total:0` | 原样 | number ✅ |

三个必须写进实现的边界（均已实测）：
1. **阈值是「≥16 位数字」而非「超出安全范围」**：`9007199254740991`（恰好 `MAX_SAFE_INTEGER`，本身是安全的）
   也会被转成字符串。本 API 没有任何 16 位的数值字段，故无害，且方向是安全的（宁可变字符串也不丢精度）。
2. **空字符串 body 会抛异常**（`Unexpected ''`），而后端 **401/403 正是空响应体** → 解析必须 try/catch 兜底。
3. 日期字符串、`null`、中文 `errorMsg` 均不受影响（已实测）。

**后果**：前端若用默认 `JSON.parse`，`POST /reservation` 拿到的 id 回传时会变成**另一个 id** → `GET /reservation/{id}`、`/cancel`、`/verify` 全部 403，**整条核心演示链路直接断掉**。批次 id 是自增（实测 10999）不受影响，**只有 `reservationId` 受此影响**。
**处理（不改后端）**：Axios 层用 big-int 安全的 JSON 解析（`json-bigint` 的 `storeAsString:true`，或 `transformResponse` 里先给 ≥16 位的整数字面量加引号），让雪花 id 以**字符串**进入前端并在所有 URL 里原样拼接。

### 1.7 异步预约链路实测时间线【实测】

一次完整提交（`POST /reservation`）的实测：

```
POST /reservation                    → 200, 耗时 46ms, data=645589569487503751
POST 返回瞬间 tb_reservation 行数     → 0        ← 事实表还没有这行
POST 返回瞬间 tb_reservation_request → CONFIRMED（账本 ≈100ms 时已推进）
+114ms  GET /reservation/{id}        → 200, 返回完整 ReservationVO
批次 remainingQuota                  → Redis 与 MySQL 同为 2（3→2 一致）
```

- **异步窗口真实存在但很短**（实测 ≈46ms→≈114ms 之间，即 `POST` 返回时**行确实还不存在**）。窗口长度取决于消费者与 broker 负载，**代码路径使该窗口可达**，前端必须按"可能查不到"设计。
- **"查不到"的表现是 403**（`GET /reservation/{id}` 命中 `reservation == null` 分支 → `ForbiddenException`），且 403 的响应体**有** `errorMsg`（`"无权查看该预约"`）——**与 T4 的空体 403 必须区分**。
- 重复提交实测 → `400 "你已预约过该批次"`，且**账本会多留一行**：实测两次提交留下 2 行（`…751 CONFIRMED` / `…752 COMPENSATED, fail_reason=DUPLICATE`）——**账本是"尝试流水"，不是"每人每批次唯一"**。
- 对账任务：`reconcile-interval-ms=300000`（5 分钟）、`reconcile-threshold-ms=600000`（10 分钟）→ **UNKNOWN 状态的收敛最多要等 ~10–15 分钟**，前端**不能**承诺"稍后自动刷新就能看到结果"。

---

## 2. API → 页面映射

| 前端功能 | 用到的端点 |
|---|---|
| 登录 | `POST /user/code`、`POST /user/login` |
| 会话/角色 | `GET /user/me`、`POST /user/logout` |
| 实验室浏览 | `GET /laboratory/list`、`GET /laboratory/{id}` |
| 设备浏览/详情 | `GET /equipment/page`、`GET /equipment/{id}`、`GET /equipment-category/list` |
| 批次浏览/详情 | `GET /reservation-batch/page`、`GET /reservation-batch/{id}` |
| 提交预约 | `POST /reservation` → 轮询 `GET /reservation/{id}` |
| 我的预约 | `GET /reservation/page`、`GET /reservation/{id}`、`POST /reservation/{id}/cancel` |
| 管理·实验室 | `GET /laboratory/list`、`POST/PUT /laboratory`、`DELETE /laboratory/{id}` |
| 管理·设备 | `GET /equipment/page`、`GET /equipment/{id}`、`GET /equipment-category/list`、`POST/PUT /equipment`、`PUT /equipment/{id}/status`、`DELETE /equipment/{id}` |
| 管理·批次 | `GET /reservation-batch/manage/page`、`GET /reservation-batch/{id}`、`POST/PUT /reservation-batch`、`POST /{id}/publish\|close\|cancel` |
| 管理·预约与核验 | `GET /reservation/manage/page`、`POST /reservation/{id}/verify` |
| 管理·死信 | `GET /reservation-dlq/page`、`GET /reservation-dlq/{id}`、`POST /reservation-dlq/{id}/replay` |

**33 个端点全部被覆盖，无一闲置、无一缺口。**

---

## 3. USER（学生）页面树

```
/login                      登录（唯一免登录页）
├── /                       首页（角色分流）
├── /laboratories           实验室浏览（卡片网格 + 分页 5/页）
│   └── /laboratories/:id   实验室详情（含该实验室设备列表）
├── /equipment              设备列表（筛选：实验室/分类/名称/状态；分页 5/页）
│   └── /equipment/:id      设备详情（含该设备的预约批次列表）
│       └── → 跳 /batches/:id
├── /batches                预约批次（进行中/已结束）
│   └── /batches/:id        批次详情（时段/开放窗口/剩余名额/bookingState）→ 「立即预约」
├── /reservations           我的预约（状态筛选）
│   └── /reservations/:id   预约详情（含「取消预约」）
└── /profile                个人中心（只读：昵称/角色/ID + 退出登录）
```

**核心演示链路**：登录 → 实验室 → 设备 → 设备详情 → 批次 → 提交预约（异步体验）→ 结果 → 我的预约 → 取消。

## 4. LAB_ADMIN（管理员）页面树

```
/login
└── /admin                                  管理首页（负责范围内的统计概览）
    ├── /admin/laboratories                 实验室管理（我的实验室）
    │   └── 新建/编辑（弹窗表单）、删除（有设备时会被拒）
    ├── /admin/equipment                    设备管理（我的设备）
    │   └── 新建/编辑、状态切换（AVAILABLE/MAINTENANCE/DISABLED）、删除
    ├── /admin/batches                      预约批次管理
    │   └── 新建（草稿）→ 编辑草稿 → 发布/关闭/取消
    ├── /admin/reservations                 预约记录（按批次/状态筛选）+ 行内「核验」
    └── /admin/dlq                          死信治理
        └── /admin/dlq/:id                  死信详情（payload/failureReason/replayCount）
                                            + 「重放」
```

**核心演示链路**：管理员登录 → 我的实验室 → 设备管理 → 建批次 → 发布 → 学生预约 → 预约记录 → 核验 → 死信治理/重放。

> **管理员同时可用学生端页面**：`POST /reservation`、`GET /reservation/page` 等**只要求登录、不要求 `USER` 角色**【读码】，LAB_ADMIN 也能预约。菜单按"公共区 + 管理区"两段呈现，不做互斥。

---

## 5. 路由设计

```js
// src/router/index.js（设计，非代码）
routes = [
  { path: '/login', component: Login, meta: { public: true, title: '登录' } },
  { path: '/', component: AppLayout, redirect: '/home', children: [
      { path: 'home',            component: Home,            meta: { title: '首页' } },
      { path: 'laboratories',    component: LabList,         meta: { title: '实验室' } },
      { path: 'laboratories/:id',component: LabDetail,       meta: { title: '实验室详情' } },
      { path: 'equipment',       component: EquipmentList,   meta: { title: '设备' } },
      { path: 'equipment/:id',   component: EquipmentDetail, meta: { title: '设备详情' } },
      { path: 'batches',         component: BatchList,       meta: { title: '预约批次' } },
      { path: 'batches/:id',     component: BatchDetail,     meta: { title: '批次详情' } },
      { path: 'reservations',    component: MyReservations,  meta: { title: '我的预约' } },
      { path: 'reservations/:id',component: ReservationDetail,meta:{ title: '预约详情' } },
      { path: 'profile',         component: Profile,         meta: { title: '个人中心' } },
  ]},
  { path: '/admin', component: AdminLayout, meta: { roles: ['LAB_ADMIN'] }, children: [
      { path: '',                 component: AdminHome,        meta: { title: '管理首页' } },
      { path: 'laboratories',     component: AdminLabs,        meta: { title: '实验室管理' } },
      { path: 'equipment',        component: AdminEquipment,   meta: { title: '设备管理' } },
      { path: 'batches',          component: AdminBatches,     meta: { title: '批次管理' } },
      { path: 'reservations',     component: AdminReservations,meta: { title: '预约记录' } },
      { path: 'dlq',              component: AdminDlq,         meta: { title: '死信治理' } },
      { path: 'dlq/:id',          component: AdminDlqDetail,   meta: { title: '死信详情' } },
  ]},
  { path: '/403', component: Forbidden, meta: { public: true } },
  { path: '/:pathMatch(.*)*', component: NotFound, meta: { public: true } },
]
```

- **守卫逻辑**：`meta.public` 直接放行；无 token → `redirect /login?redirect=<fullPath>`；`meta.roles` 不含当前角色 → `/403`。
- **不在前端做 ownership 判断**（前端拿不到 `adminUserId`，见 §11 D-2）：管理页面对后端返回的行一律展示，操作失败按 T3 的 `errorMsg` 提示。
- **`/login?redirect=`** 必须校验为站内路径（防开放重定向）。
- 页面组件**懒加载**（`() => import(...)`），避免首屏打包全部页面。

---

## 6. 前端目录设计

**单 Vue 工程，位置 `frontend/`**（与后端仓库同级子目录；不放进 `src/main/resources/static`，避免与 Maven 构建耦合）。

```
frontend/
├── index.html
├── vite.config.js          # dev proxy: /api → http://127.0.0.1:8081（必须，后端无 CORS）
├── package.json
├── .env.development        # VITE_API_BASE=/api
├── .env.production         # VITE_API_BASE=/api
└── src/
    ├── main.js             # 挂载 Vue / ElementPlus / Pinia / Router
    ├── App.vue
    ├── api/                # ★ 唯一允许出现 axios 的地方
    │   ├── request.js      # axios 实例：baseURL、timeout、authorization、bigint 解析、五通道归一化、401 处理
    │   ├── user.js         # login / sendCode / logout / me
    │   ├── laboratory.js
    │   ├── equipment.js    # 含 equipment-category
    │   ├── reservationBatch.js
    │   ├── reservation.js
    │   └── dlq.js
    ├── constants/
    │   ├── enums.js        # 后端 11 个枚举的镜像（值必须与后端逐字一致）
    │   ├── statusMap.js    # ★ 统一状态映射：label / tagType / 可执行操作 —— 唯一允许写死状态文案的地方
    │   └── messages.js     # 错误码→文案、DLQ replay reason→中文
    ├── layouts/
    │   ├── AppLayout.vue   # 学生端：顶部导航 + 内容区
    │   └── AdminLayout.vue # 管理端：左侧菜单 + 顶部导航 + 内容区
    ├── components/
    │   ├── PageHeader.vue、EmptyState.vue、LoadingBlock.vue
    │   ├── StatusTag.vue   # 只吃 statusMap，页面不得自写 if(status===...)
    │   └── ConfirmButton.vue（二次确认 + loading 防重复提交）
    ├── router/index.js     # 路由 + 守卫
    ├── stores/
    │   ├── user.js         # token / profile / role
    │   └── dict.js         # 设备分类等基础数据
    ├── utils/
    │   ├── datetime.js     # dayjs 封装：统一 'YYYY-MM-DDTHH:mm:ss'
    │   └── format.js
    └── views/
        ├── login/          home/  laboratory/  equipment/  batch/  reservation/  profile/
        └── admin/          home/  laboratory/  equipment/  batch/  reservation/  dlq/
```

**依赖与版本（已锁定，不带 `^`，2026-10-06 实测安装通过：97 包 / 0 漏洞 / 无 peer 冲突）**

| 包 | 版本 | 说明 |
|---|---|---|
| `vue` | 3.5.43 | |
| `vue-router` | **4.6.4** | 见下方选型说明 |
| `pinia` | **3.0.4** | 见下方选型说明 |
| `element-plus` | 2.14.7 | |
| `@element-plus/icons-vue` | 2.3.2 | 唯一图标库 |
| `axios` | 1.20.0 | |
| `dayjs` | 1.11.23 | 统一日期格式化 |
| `json-bigint` | 1.0.0 | F1 引入，§1.6 必需 |
| `vite` | 8.3.3 | devSependency |
| `@vitejs/plugin-vue` | 6.0.9 | devDependency |

**为什么 `vue-router` / `pinia` 不用最新 major**：npm 7+ 会**自动安装非 optional 的 peer**。实测 peer 矩阵——
`vue-router@5.3.1` 的核心 API 未变（`createRouter`/`createWebHistory`/`beforeEach` 均在），但额外提供
`./vite`、`./unplugin`、`./auto-routes`、`./experimental/pinia-colada` 等**子路径模块**，全是本项目用不到的机械；
`pinia@4.0.3` 则把 `@vue/devtools-api` 标为 **`optional: false`**，会被强装。
而 `vue-router@4.6.4` + `pinia@3.0.4` 的**必需 peer 只有 `vue` 自己**。
`pinia` 各版本的 `typescript` peer 都是 **optional**，因此**不会**把 TypeScript 拖进项目（技术栈红线未被触碰）。

**硬性禁止引入**：TypeScript（含 `tsconfig`/`.ts`）、Tailwind、Sass/Less、额外图标库、额外动画库、
mock 框架、测试框架（F0–F11 均不引入）、`unplugin-auto-import` / `unplugin-vue-components`（F0 用全量引入 Element Plus 换取零额外依赖）。

**`vite.config.js` 的代理是硬性要求**：后端**全仓无 CORS 配置**【实测：`git grep -E 'Cors|CrossOrigin|allowedOrigins|addCorsMappings' -- src/` 零命中】，若前端直连 `http://localhost:8081`，浏览器会直接拦掉全部请求。

---

## 7. 前端状态管理设计

### 7.1 统一状态映射（`constants/statusMap.js`）—— 本项目禁止散落 `if (status === 'XXX')`

```js
// 形状（设计，非代码）：
// statusMap.ReservationStatus = {
//   RESERVED: { label: '已预约', tag: 'success', actions: ['cancel'] },
//   ...
// }
```

| 枚举 | 值 | label | tag | 可执行操作 |
|---|---|---|---|---|
| **`ReservationStatus`** | `RESERVED` | 已预约 | success | 取消预约 |
| | `CANCELLED` | 已取消 | info | — |
| | `COMPLETED` | 已核验 | primary | — |
| | `NO_SHOW` | 未到场 | warning | — |
| **`ReservationBatchStatus`** | `DRAFT` | 草稿 | info | 编辑草稿、发布、取消 |
| | `PUBLISHED` | 已发布 | success | 关闭、取消 |
| | `CLOSED` | 已关闭 | warning | — |
| | `CANCELLED` | 已取消 | info | — |
| **`BookingState`** | `DRAFT` | 未发布 | info | — |
| | `NOT_STARTED` | 未开始预约 | warning | — |
| | `OPEN` | 可预约 | success | **提交预约** |
| | `EXPIRED` | 已截止 | info | — |
| | `CLOSED` | 已关闭 | warning | — |
| | `CANCELLED` | 已取消 | info | — |
| **`EquipmentStatus`** | `AVAILABLE` | 可用 | success | 设为维护、停用 |
| | `MAINTENANCE` | 维护中 | warning | 设为可用、停用 |
| | `DISABLED` | 已停用 | info | 设为可用 |
| **`LaboratoryStatus`** | `OPEN` | 开放 | success | — |
| | `CLOSED` | 关闭 | info | — |
| | `MAINTENANCE` | 维护中 | warning | — |
| **`ReservationDlqStatus`** | `NEW` | 待处理 | danger | **重放** |
| | `REPLAYED` | 已重放 | success | — |
| | `DISCARDED` | 已丢弃 | info | — |
| **`UserRole`** | `USER` | 学生 | info | — |
| | `LAB_ADMIN` | 实验室管理员 | warning | — |

**规则**：① `label`/`tag` 只在此文件维护；② `StatusTag.vue` 是唯一的渲染入口；③ 未知值一律降级为"未知（原值）"并把原值显示出来，**不得静默显示空白**；④ **学生端主用 `bookingState`（服务端已算好），管理端主用 `ReservationBatchStatus`**，两者不得混用。

**`statusMap` 的范围 = 上表 7 个枚举 / 25 个取值，这是「VO/DTO 里真的有枚举字段」的全部**（`git grep` 实测）：`EquipmentVO.status`、`LaboratoryVO.status`、`ReservationBatchVO.status`、`ReservationBatchVO.bookingState`、`ReservationDlqVO.status`、`ReservationVO.status`、`UserDTO.role`。

后端另有 4 个枚举 **`ReservationResultCode` / `ReservationPersistResult` / `ReservationRequestStatus` / `ReservationCancelOutcome` 不进 `statusMap`** —— 它们是内部状态机，**不出现在任何 VO/DTO 字段中**（如 `POST /reservation/{id}/cancel` 只返回裸 `Result.ok()`）。其中 `ReservationResultCode` 只以其 `message` 中文串经 `errorMsg` 间接到达前端。给它们编 label/tag 属于凭空造枚举，禁止。

### 7.2 Pinia stores —— 只放"跨页面共享"的状态

| store | state | actions | getters |
|---|---|---|---|
| `user` | `token`(持久化到 localStorage)、`profile{id,nickName,icon,role}` | `login()`、`fetchMe()`、`logout()`、`reset()` | `isLogin`、`isAdmin` |
| `dict` | `categories[]`（设备分类） | `loadCategories()`（带缓存，只请求一次） | `categoryName(id)` |

**明确不放进 Pinia**（避免过度封装）：列表数据、分页游标、表单状态、loading 开关、错误消息——全部留在组件内；错误提示由 Axios 拦截器 + `ElMessage` 统一处理，**不建全局 error store**。

### 7.3 Axios 层设计（`api/request.js`）—— 五通道归一化

**`request()` 永不抛异常**（`Promise` 恒 resolve），统一返回判别联合：

```js
{
  ok:      boolean,        // kind === 'ok'
  kind:    'ok' | 'business' | 'error' | 'unauthorized'
         | 'forbidden' | 'denied' | 'clientError' | 'network',
  data:    any | null,     // 仅 kind === 'ok' 时取用
  total:   number | null,  // 仅分页接口有值
  message: string,         // 可直接展示的中文文案；kind === 'ok' 时为 ''
  status:  number | null,  // HTTP 状态码；network 时为 null
}
```

判别**顺序不可调换**（`bigintJson.js` / `normalize.js` / `createRequest.js` 三文件拆分见 §6）：

| 顺序 | 条件 | kind | message |
|---|---|---|---|
| 1 | 无 `response`（超时／连接被拒／断网） | `network` | 网络错误文案 |
| 2 | HTTP **401**（无论有无体） | `unauthorized` | 登录已失效，请重新登录 |
| 3 | HTTP **403 且有 `errorMsg`** | `denied` | 后端原文（F5 轮询**必须重试**这一种） |
| 4 | HTTP **403 且无 `errorMsg`**（拦截器空体） | `forbidden` | 无权限执行该操作 |
| 5 | 非 2xx 且体是 **Result 形状** | `error` | 后端 `errorMsg` |
| 6 | 非 2xx 且体**不是** Result 形状（T5） | `clientError` | 请求参数不合法 |
| 7 | HTTP 2xx 且 `success === true` | `ok` | `data` 取 `'data' in body ? body.data : null`，`total` 取 `body.total ?? null` |
| 8 | HTTP 2xx 且 `success === false` | `business` | 后端 `errorMsg` |
| 9 | HTTP 2xx 但体不是 Result 形状 | `clientError` | 响应格式不符合预期 |

**Result 形状的判据**：`body && typeof body === 'object' && typeof body.success === 'boolean'`。
（Spring 默认错误体 T5 含 `timestamp/status/error/message/path` 但**没有 `success`**，据此区分；
`Result` 无 `code` 字段，且 `non_null` 序列化会整键丢掉 `data`/`total`/`errorMsg`。）

**必须处理的三个实测陷阱**：
- **`json-bigint` 的 `parse('')` 会抛异常**，而后端 401/403 正是空体 → `bigintJson.js` 内部必须 `try/catch`，失败时**原样返回未解析的字符串**（交给上面的形状判据归一到 `clientError`，绝不向上抛）。
- **403 与 401 都要先于形状判据**：空体不是 Result 形状，若先判形状会被误归成 `clientError`，F2 的跳登录与 F5 的轮询重试都会失效。
- **`json-bigint` 返回的是 null-prototype 对象**（`Object.create(null)`），**不是普通对象**。
  两个后果：① 用 `assert.deepStrictEqual` / `deepEqual` 与字面量比较会因**原型不同**而失败，
  断言必须逐字段写（F1 的 `ok` 用例即如此），**但这不代表可以放宽断言强度**；
  ② **`body.hasOwnProperty(...)` 会抛 `TypeError`**，必须一律用 `Object.hasOwn(body, key)` 或 `'key' in body`。
  目前 `normalize.js` 用的正是 `Object.hasOwn`，所以是安全的——**后续 Phase 新增任何读响应体的代码都要守住这一条**。

---

## 8. UI 风格规范（高校科研感 · 克制）

| 维度 | 规定 |
|---|---|
| 主色 | 深青蓝 `#2C5F8D`（Element Plus `--el-color-primary` 覆写）；成功/警告/危险沿用 EP 语义色 |
| 背景 | 页面底 `#F5F7FA`，卡片纯白 `#FFFFFF` |
| 文字 | 主 `#1F2D3D`、次 `#5E6D82`、弱 `#909399`；正文 14px，标题 16/18/20px |
| 边框/圆角 | 卡片 `1px solid #E4E7ED`、圆角 **4px**；按钮圆角 4px；**禁止 12px+ 大圆角** |
| 阴影 | 仅卡片一层极浅阴影 `0 1px 2px rgba(0,0,0,.04)`；**禁止发光/多层投影** |
| 渐变 | **仅登录页允许一处极淡的纯色/单向渐变背景**，业务页面**全程禁止渐变** |
| 图标 | 只用 `@element-plus/icons-vue`，**不引入第二套图标库**；**不用 emoji 当图标** |
| 动画 | 只用 EP 内置过渡（弹窗/抽屉/loading）；**禁止自研动画、禁止视差、禁止大屏滚动数字** |
| 学生端 | 顶部导航 + 内容区；实验室/设备用**等高网格卡片**（名称 / 校区·楼·房间 / 状态标签 / 分类）；重点突出**剩余名额、开放窗口** |
| 管理端 | 左侧固定菜单 220px + 顶部（面包屑 + 用户区）+ 内容区；表格用 EP 默认紧凑风格，**操作列固定右侧** |
| 分页 | **固定 5 条/页**（后端 `DEFAULT_PAGE_SIZE=5` 且无法传 `pageSize`），分页控件**不提供"每页条数"选择器**（提供即为撒谎） |
| 响应式 | PC 优先；断点 **1200 / 992 / 768 / 560**（四档；原文只写三档，560 是 F11 由既有 4 处 `560` 归并而来的手机档，见 §10 的 F11 订正⑥）；**≤992 管理端侧边栏折叠为图标**（实测 220px → 64px）；表格允许横向滚动 |
| 明令避免 | 电商/外卖/大众点评/黑马点评风、彩色渐变按钮、花哨数据大屏、移动 App 风、大面积卡片嵌套 |

---

## 9. Phase F0–F11（12 个 Phase）

每个 Phase 都满足：范围有限、可独立运行、可独立验收、可独立提交。

| Phase | 目标 | 新增文件（主要） | 对应 API | 禁止修改范围 |
|---|---|---|---|---|
| **F0** 工程初始化 | Vite+Vue3+EP+Router+Pinia 骨架；**dev proxy 打通**；空壳布局；`.gitignore` | `frontend/**`（骨架）、`vite.config.js`、`package.json`、`.env.*` | 仅用于冒烟：`POST /user/code` | 后端全部；`README.md`；`scripts/**` |
| **F1** 请求层与状态常量层 | `request.js` 五通道归一化 + **bigint 安全解析**；`enums.js`/`statusMap.js`；`StatusTag.vue` | `api/bigintJson.js`、`api/normalize.js`、`api/createRequest.js`、`api/request.js`、`constants/*`、`components/StatusTag.vue`、`scripts/verify-request-layer.mjs` | 全部（作为基础设施，无具体业务调用） | 同上；且**不得**在此 Phase 写任何业务页面 |
| **F2** 登录与会话 | 登录页、验证码倒计时、token 持久化、路由守卫、**两个 Layout 与角色菜单分流**、退出登录、403/404 页 | **新增**`views/login/LoginView.vue`、`views/home/HomeView.vue`、`views/admin/AdminHomeView.vue`、`views/error/{ForbiddenView,NotFoundView}.vue`、`layouts/{AppLayout,AdminLayout}.vue`、`stores/user.js`、`router/guard.js`、`scripts/verify-session.mjs`；**修改**`api/user.js`、`api/request.js`、`router/index.js`、`main.js`；**删除**`views/SystemCheck.vue` | `POST /user/code`、`POST /user/login`、`GET /user/me`、`POST /user/logout`（**仅此 4 个**） | 后端全部；`user/me` 之外的用户接口；**F1 的 `api/{bigintJson,normalize,createRequest}.js`、`constants/*`、`components/StatusTag.vue` 一个字节不许动**；不得引入任何新依赖 |
| **F3** 学生·实验室与设备浏览 | 实验室列表/详情、设备列表（筛选）/详情；分页 5/页；空态与 loading | `views/laboratory/*`、`views/equipment/*`、`api/laboratory.js`、`api/equipment.js` | `GET /laboratory/list`、`/laboratory/{id}`、`GET /equipment/page`、`/equipment/{id}`、`GET /equipment-category/list` | 后端；管理端页面 |
| **F4** 学生·批次浏览（只读） | 批次列表（按设备/实验室过滤）、批次详情；`bookingState` 驱动按钮可用性 | `views/batch/*`、`api/reservationBatch.js` | `GET /reservation-batch/page`、`/reservation-batch/{id}` | 后端；提交动作（F5 才做） |
| **F5** 提交预约与异步体验 ★ | **本项目技术展示核心**：提交 → loading → 受理态 → 轮询 `GET /reservation/{id}` → 终态；503 两种文案分别处理；防重复提交 | `views/batch/SubmitResult.*`、`api/reservation.js` | `POST /reservation`（+ 轮询 `GET /reservation/{id}`） | 后端；F6 的"我的预约"页面 |
| **F6** 学生·我的预约 | 我的预约列表（状态筛选）/详情/取消；取消后**重新拉取**确认状态 | `views/reservation/*`（学生侧） | `GET /reservation/page`、`/reservation/{id}`、`POST /reservation/{id}/cancel` | 后端；管理端 |
| **F7** 管理·实验室与设备 | 我的实验室 CRUD、我的设备 CRUD + 状态切换；删除实验室被拒的文案展示 | `views/admin/laboratory/*`、`views/admin/equipment/*` | `POST/PUT /laboratory`、`DELETE /laboratory/{id}`、`POST/PUT /equipment`、`PUT /equipment/{id}/status`、`DELETE /equipment/{id}` | 后端；批次与死信 |
| **F8** 管理·批次管理 | 批次列表（管理端）/新建草稿/编辑草稿/发布/关闭/取消；**发布前校验文案**；表单日期用 ISO 格式 | `views/admin/batch/*` | `GET /reservation-batch/manage/page`、`POST/PUT /reservation-batch`、`POST /{id}/publish\|close\|cancel` | 后端；实验室与设备管理 |
| **F9** 管理·预约记录与核验 | 预约记录列表（按批次/状态筛选）、行内核验（含二次确认与**成功后重取**） | `views/admin/reservation/*` | `GET /reservation/manage/page`、`POST /reservation/{id}/verify` | 后端；DLQ |
| **F10** 管理·死信治理 | 死信列表/详情（payload 格式化展示）/重放（含 `reason` 中英映射与二次确认） | `views/admin/dlq/*`、`api/dlq.js`、`constants/messages.js` | `GET /reservation-dlq/page`、`/reservation-dlq/{id}`、`POST /reservation-dlq/{id}/replay` | 后端；其余管理页 |
| **F11** 统一打磨与终验 | 全站异常/空态/loading 一致性、响应式、导航与面包屑、404/403 页、演示脚本走查 | 全站微调、`components/EmptyState.vue` 等 | 无新增 | 不得引入新依赖；不得借"打磨"改业务逻辑 |

> **`npm run dev` 必须始终可用**：每个 Phase 结束时前端都要能独立跑起来，且用真实 token 至少走通该 Phase 涉及的链路。

**F1 相对本表初稿的两处偏差（派发前记录，非事后追认）**：

① **`AppLayout`/`AdminLayout` 由 F1 后移到 F2**。理由：这两个布局的核心内容是**角色驱动的菜单**，而角色在 F2 才进入（`GET /user/me`）。放 F1 只能写死两份菜单，F2 必须推倒重写 —— 属于自造返工。

② **F1 验收判据从「页面里手工触发 5 种场景」改为「Node 脚本打真实后端」**。理由：五通道里有三条（401 空体、403 空体、403 有体）**无法从页面自然触发**，需要伪造响应；而伪造出来的响应只能证明"我按我的理解解析了我的伪造体"，是自证循环。改为把请求层拆成**不依赖 `import.meta.env` 的纯模块**（`bigintJson.js` / `normalize.js` / `createRequest.js`），由 `scripts/verify-request-layer.mjs` 用真实 axios 打**真实后端**取真实响应，再断言 `kind`。这同时使 ① 的 bigint 往返成为**可判别**的（对照：同一响应体用原生 `JSON.parse` 解析后 `id` 必然 ≠ 原值，见 §1.6）。③ **验收③的范围由「11 个枚举」订正为「7 个枚举 / 25 个取值」**。理由：`git grep` 实测只有 7 个枚举出现在 VO/DTO 字段上；另 4 个（`ReservationResultCode`/`ReservationPersistResult`/`ReservationRequestStatus`/`ReservationCancelOutcome`）是内部状态机，前端**根本收不到**它们的枚举名。原判据要求"11 个枚举每一个取值都有条目"，会迫使实现方**凭空为 4 个不可达枚举编造中文 label** —— 这正是"不得自行创造"红线。附带地，验收④（"无 `if (status ===` 字面量"）**不适用于 F1**：`SystemCheck.vue` 里的 `'idle'|'success'|'failure'` 是页面本地 UI 状态、不是后端枚举，该判据从 F3 起对业务状态渲染生效。

**F2 相对本表的三处设计决定（派发前记录，非事后追认）**：

① **删除 `views/SystemCheck.vue`**。理由：它是 F0 的"代理是否打通"自检页，其职责已被 F2 的真实登录链路**更强地**覆盖；F2 之后不再有任何路由指向它，留着就是不可达死代码。**代价已登记、不粉饰**：F1 人工流程里"再走一遍 F0 的代理自检页"那句随之失效，§10 的 F1 行已同步改为"走登录页复验代理"。

② **守卫判定抽成纯函数 `router/guard.js`**：`resolveNavigation({ to, token, profile })` → `true | RouteLocationRaw`。理由：§10 F2 的 ①③ 两条是全 Phase 判别性最强的验收点，但在浏览器里既难自动跑、也无法在 Node 里断言。抽成**不依赖 vue-router 运行时、不依赖 DOM** 的纯函数后，`scripts/verify-session.mjs` 可以对它直接断言真值表。这不是过度设计——它就是 F1"把请求层拆成纯模块以便真机断言"的同一手法。

> **2026-10-06 F2 Review 补正（重要，别把它当已完备）**：只抽 `resolveNavigation` **不够**。第一次交付时 `beforeEach` 里的接线仍是 `if (!to.meta.public && store.token && !store.profile)`，而 `/login` 是 `meta.public:true`，导致真值表**第 1 行在硬刷新下永不触发**（带着有效 token 打开 `/login` 会看到登录表单）。更关键的是：验收脚本直接拿**手工构造的入参**喂纯函数，所以它**对这个接线完全瞎**——实测把接线换成三种截然不同的版本（缺陷在位 / 修好 / `fetchMe` 彻底摘掉即全站不可用），脚本判决**都是 `19 PASS, 0 FAIL`，一模一样**。故补上第二个纯导出 **`needsProfileFetch({ to, token, profile })`（语义 `token && !profile`，**不含** `to.meta.public`）**，并额外加一条**静态文本断言**（脚本读 `router/index.js` 源码，断言 `beforeEach` 段含 `needsProfileFetch(` 且不含 `to.meta.public`）。静态断言在输出里必须标注"不是行为验证"——因为 `router/index.js` import 了 `.vue`，Node 里加载不了，调用的存在性只能这样锁。**教训一句话：抽了判定不等于抽了接线；判据的作用域必须和缺陷的作用域对齐。**（该修法已于同日落地并由我独立复核：四个变体实测见 §10 的"返工复核"。）

③ **菜单由路由表驱动**（`meta.menu = { title, icon, order }`），Layout 只渲染「当前角色可见 **且** 已注册」的项。理由：F7–F10 会陆续加管理端页面，硬编码菜单意味着**每个 Phase 都要回头改 Layout**，且中间态会出现"菜单点进去 404"的死链。路由驱动后 F2 管理端只有"管理首页"一项，后续 Phase 加路由即自动出现。

**F2 的唯一跨 Phase 耦合点（Review 时重点看）**：`api/request.js` 的 `getToken` 要从 F1 的 `() => null` 换成读 Pinia，于是形成循环导入 `stores/user.js → api/user.js → api/request.js → stores/user.js`。安全条件是**只在 `getToken` 的函数体内读 store**、不得在模块顶层解构（ESM 循环里顶层解构会拿到 TDZ 绑定）。**同时禁止** `api/request.js` 直接 import `router` —— 401 跳转改为由 `main.js` 注入 handler，否则循环会绕回路由层。

**F7 相对本表的七处设计决定（派发前记录，非事后追认）**：

① **D-2 的裁决：不动后端。** 用户裁定 F7 为**纯前端 Phase**，按 §10 判据⑤ 执行 —— 管理端列表**照常展示后端返回的全部行**，不做任何前端归属过滤或归属判断（`LaboratoryVO` 与 `EquipmentVO` 都拿不到 `adminUserId`，前端**没有**做归属判断的输入）；越权由**后端 403 文案**兜底并在页面上原样展示。**D-2 继续挂账**，不在本 Phase 解决。这条与 §5 既有的那句「不在前端做 ownership 判断」是同一决定，此处只是把它落成 F7 的实现约束。

② **判据②的验证边界必须如实登记。** 判据②写的是"新建实验室成功且创建者成为负责人"，但 `LaboratoryVO` 不含 `adminUserId`，**前端无法直接验证归属**。前端能验证的只有"创建成功 + 重取后列表出现新行"。为拿到**判别性**证据，真机脚本改为：创建后**立刻 `PUT /laboratory`** —— 服务层 `updateLaboratory` 会先 `assertManageable`，若创建者不是负责人这里必然是 403 而不是 200。**这是间接证据，验收记录里必须写明"间接"，不得声称前端直接验过。**

③ **写操作 2xx 后一律重新拉取列表，不做本地乐观更新。** 这是把 F6「200 不得当成功凭据」的教训**强制推广**到 CRUD：后端 `deleteLaboratory`/`deleteEquipment` 都忽略 `removeById` 的返回值，理论上 0 行受影响也返回 `Result.ok()`（与 F6 的 `TRANSITIONED`/`ALREADY_CANCELLED` 同构）。重取使列表恒为服务端真值，成功提示与列表若矛盾会立刻暴露。成功文案用中性词（`已创建`/`已保存`/`已删除`），**不写"操作成功"这类无法证伪的措辞**。

④ **写失败时对话框保持打开。** 关掉会让用户丢掉已填内容；后端 400（如删除仍有设备的实验室）应当在表单仍可见的情况下展示文案。

⑤ **设备状态切换由 `statusMap` 的 actions 驱动，目标状态只能来自一张静态映射表。** `statusMap.EquipmentStatus` 已定义好三个取值的 `actions`（`AVAILABLE:['maintain','disable']` 等），F7 为动作名建一张 `动作 → 目标状态` 映射表，页面按 `actions` 渲染按钮。**这样判据⑥（"非法值不会由前端发出"）就是结构性成立的**：目标值不可能来自映射表以外的地方，而映射表的每个值被静态断言锁死在 `ENUM_VALUES.EquipmentStatus` 内。**不用行内硬编码按钮**，也不用给设备状态在表单里再开一个下拉（状态切换是行内动作，与实验室用表单改 `status` 的分工不同 —— 因为 `LaboratoryStatus` 的 `actions` 全是空数组，没有动作语义可用）。

⑥ **编辑设备时实验室下拉 = 前 5 条 ∪ 当前行的实验室。** 后端 `/laboratory/list` 页大小被硬钳在 5（§11 D-13），若只喂前 5 条，编辑第 6 个及以后实验室的设备时 `el-select` 会显示裸 id。补入当前行是为了让**编辑路径不因 D-13 而静默错显**；D-13 本身仍挂账。**同时**：暴露 `laboratoryId` 的编辑入口意味着"把设备搬进别人的实验室"可被触发 → 后端 403 `无权管理该实验室` → 页面原样展示，这**正好**是判据⑤的展示面之一，不是副作用。

⑦ **`verify-batch.mjs` 那条 `admin routes and views remain outside this phase` 断言被 F7 合法击穿，处理方式是"收紧"而非删除。** 它原本断言两件事：`/admin` 的子路由恰好是 `[['', 'admin-home']]`；且 `frontend/src/views/admin` 的 `git status` 为空。前者 F7 必然破坏（要加两条路由），后者更荒谬 —— **F7 就是这个目录的 owner，留着它等于断言 F7 不许做自己**。改法：把语义从"管理端整体尚未实现"收紧为"**F8/F9/F10 的管理端域尚未越界**" —— 子路由白名单从 1 项**精确**更新为 3 项（仍是深等白名单，不是"非空即可"），`git status` 那条**替换**为"`src/views/admin/**` 下不存在文件名匹配 `/batch|reservation|dlq/i` 的文件"。**一条换一条，A 组条数维持 13 不变**（与 F5 的 R5、F6 的同类先例一致：边界改法只能收紧、不能靠删除断言来"通过"）。

#### F8 相对本表的设计决定（派发前记录，非事后追认）

① **归属过滤完全在后端，前端零过滤 —— D-2 裁定的直接延续。** `ReservationBatchMapper.xml` 的 manage 分页 SQL 带 `l.admin_user_id = #{adminUserId}`，F8 不新增任何前端侧归属判断（前端也**没有**字段可判：`ReservationBatchVO` 只有 `laboratoryId`/`equipmentId`）。越权由后端 `assertEquipmentManageable` 拦下并透传 `无权管理该实验室`。附带事实：现有 15 个批次**全部**属于设备 8/11/12（实验室 3/4，属管理员 1019），故 token 持有者 1016 的 manage 页**初始为空** —— 判据①的对比证据天然存在，判据③必须靠新建夹具。

② **管理端状态白名单是全部 4 个状态，因此 `buildManageBatchQuery` 与 `buildBatchQuery` 并存、各自单点。** 学生端 `buildBatchQuery` 的白名单是 `['PUBLISHED','CLOSED']`（镜像后端 `queryBatchPage` 的闸门），而 `queryManagePage` **不做状态闸门**，4 个状态都能筛。复用学生端 builder 会让管理端**永远筛不到草稿**。两条 builder 同行并列，白名单分别锁死。

③ **判据⑥ 靠表单的 `value-format` 结构性保证：4 个时间字段一律 `el-date-picker type="datetime" value-format="YYYY-MM-DD[T]HH:mm:ss"`。** 机理已用**零副作用对照实验**实测确证（不采信推断）：发空格格式 `"2027-06-01 10:00:00"` → **400 `请求体格式错误`**（Jackson `HttpMessageNotReadableException` → `WebExceptionAdvice`）；发 `T` 格式但故意缺 `totalQuota` → **400 `批次必填字段不能为空`**，后者证明 `T` 格式**能被 Jackson 接受**（否则会停在格式错误那一关）。两条都不建任何行（批次表前后同为 15 行）。

④ **表单只做 required 校验，不做任何跨字段校验**（不校验 `endTime > startTime`，也不校验 `bookingEndTime ≤ startTime`）。理由：判据④要求「发布校验逐条可触发」——前端若先把这些组合拦下，**就再也触发不到后端那条文案**了。后端 `createBatch` 本身也只做非空校验（`validateCreateShape`），因此"能建成形状合法但语义非法的草稿"是**后端既有设计**，前端不该替它兜。后端是唯一权威。

⑤ **设备下拉做成二级的（先选实验室，再按 `laboratoryId` 取设备）**，因为 `/equipment/page` 每页大小在后端硬编码为 5、`EquipmentQueryDTO` **没有 size 字段**、也没有任何非分页端点。当前种子数据每室 ≤4 台设备，按室过滤后可完整列出；若直接列全库 12 台，下拉只会有前 5 台。编辑时把当前行的设备**并入选项**（F7 的 D-13 兜底复用），避免显示裸 id。**残留缺口如实登记**：某实验室设备超过 5 台时，新建路径的下拉仍会静默少列（D-13 同族，只兜住了编辑路径）。

⑥ **四条既有断言被 F8 合法击穿，全部"收紧而非删除"，A 组条数不变。** 两条在 `verify-batch.mjs`（`/admin` 子路由白名单、`reservationBatch` API 只许两个只读 GET），两条在 `verify-admin.mjs`（三条菜单项、后续管理端域尚不存在）。收紧方向：子路由白名单 3→4 项（仍是排序后精确集合相等）；`views/admin/**` 的禁止正则 `/batch|reservation|dlq/i` **缩窄为** `/reservation|dlq/i`；`reservationBatch` 那条从"恰好 2 个只读导出"改为**导出名精确集合（8 个）+ 方法计数（get 3 / post 4 / put 1）+ URL 字面量精确集合（7 条）**，并**新增**对 F9/F10 端点的全仓禁止。**这是 F5 R5 → F6 → F7 的第四次复用**：`batch` 从禁止集移出，`reservation`/`dlq` 留在里面。

⑦ **判据⑤ 的夹具与清理口径 —— 我的判据写错了，由 Codex 以 `DESIGN DECISION REQUIRED` 挡下。** 我在 Packet 里同时要求「夹具执行 close 并确认 `CLOSED`」和「本轮所有自建批次终态为 `CANCELLED`」，二者**互斥**：后端 `cancel` 只接受 `DRAFT`/`PUBLISHED`，`CLOSED` **原理上无法取消**，而批次又没有删除端点。Codex 没有自行拍板，而是显式标出卡点要求裁定 —— 这是 Packet 协议期望的行为。**修正后的口径**：恰好一个夹具终态为 `CLOSED`（单独断言），其余全部 `CANCELLED`，且**没有任何夹具停留在 `DRAFT`/`PUBLISHED`**；清理逻辑按「读回 status，仅 `DRAFT`/`PUBLISHED` 才调 cancel」判定跳过。那条 `CLOSED` 夹具行会永久留在库里，**由主控事后直接清库**（同 F7 手工删除孤儿实验室 9348 的先例）。

#### F11 相对本表与 §6 目录设计的三处订正 + 两处范围裁定（派发前记录，非事后追认）

① **判据① 的「11 个页面」是过期数字，订正为 15 个，并给出客观判定法。** 原数字既非路由数也非 view 数，无法核验。改为**由代码自身决定**：凡 `src/views/**/*.vue` 内含 `import … from '<任意层级>/api/…'` 者即为数据页，实测恰好 **15** 个 —— `admin/batch/AdminBatchListView`、`admin/dlq/DlqDetailView`、`admin/dlq/DlqListView`、`admin/equipment/AdminEquipmentListView`、`admin/laboratory/AdminLaboratoryListView`、`admin/reservation/AdminReservationListView`、`batch/BatchDetailView`、`batch/BatchListView`、`equipment/EquipmentDetailView`、`equipment/EquipmentListView`、`laboratory/LaboratoryDetailView`、`laboratory/LaboratoryListView`、`login/LoginView`、`reservation/ReservationDetailView`、`reservation/ReservationListView`。其中 **`LoginView` 是有意例外**：它没有 `state` 三态，而用 `sendingCode`/`submitting`/`countdown` + `ElMessage.error`（登录页的"空态"语义不成立）。**断言必须显式写出这个例外**，不得为凑数在登录页硬造一个 `state`。

② **判据④ 的「不一致用词」口径澄清 —— 原表述可被读成两种相反做法，必须钉死一种。** 字面直读（"统一成同一句话"）会与一条**已锁定的设计相冲突**：`verify-admin.mjs` 把预约记录页的**两分支空态**当契约锁住了 —— `activeFilter ? '没有符合条件的预约' : '暂无预约记录'`。若把全站文案抹平成一句通用文案，会同时打掉"有筛选 vs 无数据"的区分与各实体名词（"实验室/设备/批次/预约"），属于**把信息量改掉而不是打磨**。故 F11 采纳的口径是：**④ 的可执行内核是"结构与前缀统一"，不是"用词归一"** —— (a) 所有空态/错误态一律经 `EmptyState`（loading 经 `LoadingBlock` 或 `v-loading`），不再有手写的 `<p class="…msg">`；(b) 错误文案前缀统一为 `…加载失败：<后端/请求层 message>`（如 `加载失败：`、`批次加载失败：`），不再有裸 `errorMessage` 直出；(c) **保留按实体区分的名词**，并保留"筛选无结果 / 真的没有数据"两种分支；学生侧 `你还没有预约记录` 是**有意变体**（第二人称比"暂无预约记录"更贴合学生视角），不视为不一致。

③ **§6 目录设计声明的 6 项里只实施 1 项，另 4 项登记为「已评估、不实施」（经用户确认，2026-10-07）。** 先量后改的结果，不是漏做：
- **实施 `components/LoadingBlock.vue`（新增）**：替换 `EquipmentDetailView.vue` 批次子区原先的 `<div class="batch-loading">` 与两段专用 CSS（`.batch-loading{min-height:130px}`、`.batch-message{margin:0;padding:34px 12px;text-align:center}`），使该子区与全站同构。**它只有一个真实调用点，旧 CSS 已随之删除**，不是为将来预留的抽象。
- **不实施 `views/profile/` 与 §5 的 `{ path: 'profile' }` 路由**：后端**没有任何**可改资料端点（`/user/me` 只读；4 个 User 端点里没有更新资料/改密码）。做一个只能看不能改的"个人中心"是凭空造页；为它有东西可改而去加后端接口，直接违反「以后端为唯一事实来源」。账户信息在 Layout 顶栏（昵称 + 角色 + 退出登录）已可见。
- **不实施 `stores/dict.js`**：枚举渲染全站一律走 `resolveStatus(枚举名, 值)` 这个**同步纯函数**，无异步加载、无跨页共享缓存、无失效时机；§7.2 本身也写了「明确不放进 Pinia」清单。为同步常量包一层 Pinia 是纯中转站，且 F1 已把 25 个取值全覆盖的责任交给 `constants/statusMap.js`。
- **不实施 `components/ConfirmButton.vue`**：三处二次确认（F6 取消 / F9 核验 / F10 重放）的确认文案、按钮文案与**后置行为各不相同**（取消要重取并区分 `TRANSITIONED`/`ALREADY_CANCELLED`；重放要区分拒绝原因码；核验要区分幂等）。抽出来要传 4–5 个 props + 事件回传，调用点比直接用 `ElMessageBox.confirm` 更长；共性只有"弹一次"一个词。
- **不实施 `utils/format.js`**：格式化逻辑已各自落在最小使用域（`formatJsonText` 在 F10 建的 `utils/jsonText.js`，有专属断言），新建 `utils/format.js` 只是搬家，**搬完还要改所有 import**，零收益。

④ **判据⑥ 的断言范围从 `views/` 扩到全站。** 原判据只查 `views/`，而 `layouts/`、`components/` 同样属于"渲染层"，且原判据无法证明"`api/` 是唯一入口"（`views/` 干净不代表别处不绕）。F11 收紧为两条合取：渲染层三目录零 `axios`，**且** 全 `src/` 中 import `axios` 的文件集合**恰好等于** `{api/createRequest.js}`。**这是 F5 R5 → F6 → F7 → F8 的第五次同类复用：只能收紧，不能靠删断言通过。**

⑤ **两处范围裁定（实测无缺陷，故不实施）。** (a) **`/403` 与 404 页不动**：CDP 实测两页在 1440/1200/992/768 四档下 `overflow` 均为 0、无控制台错误，且 `返回首页` 已按角色取 `defaultRouteFor(role)`。(b) **不改任何 `el-descriptions` 的 `:column`**：实测 768 宽度下 `laboratory/LaboratoryDetailView` 的 `:column="3"` 表**没有**出现在横向溢出元素名单里（溢出的元凶是 Layout 而非表格），加响应式列数属于无证据的过度设计。

---

## 10. 每个 Phase 的验收标准与人工测试流程

**通用前置**：后端以 `--server.port=18081` 启动（8081/8082 被本机其他项目占用）；`curl/http` 一律 `--noproxy '*'`；前端 `npm run dev`（5173）经 Vite 代理访问后端。

| Phase | 验收标准（全部满足才算 PASS） | 人工测试流程 |
|---|---|---|
| **F0** | ① `npm run dev` 启动无报错；② 页面加载出空壳布局；③ **从浏览器发出 `POST /user/code` 返回 `{"success":true}`**（证明代理打通，若直连后端会被 CORS 拦掉）；④ `npm run build` 成功；⑤ 后端目录 `git status` 无变化 | 起后端 → 起前端 → 打开 `http://localhost:5173` → 打开 DevTools Network，点"发送验证码"，看到请求 URL 是 `http://localhost:5173/api/user/code`（经代理）且 200 |
| **F1** | ① **把 `645589569487503751` 这类 id 经请求层往返后与后端值逐位相同**（bigint 处理生效的**判别性**验收，见下方偏差②）；② 5 条通道各自归一化到正确的 `kind`（401 空体 → `unauthorized` / 403 空体 → `forbidden` / 403 有体 → `denied` / 200+`success:false` → `business` / 非 Result 体 → `clientError`）；③ `statusMap` 对 **7 个枚举的全部 25 个取值**都有条目（见下方偏差③），未知值降级为"未知（原值）"；④ `npm run check:request` 退出码 0；⑤ 后端目录 `git status` 无变化 | 起后端（18081）→ `VERIFY_BACKEND=http://127.0.0.1:18081 npm run check:request`（脚本用真实 axios **直连**后端，**不经 Vite 代理、非 mock**）→ 再取一个真实 USER token 跑 `VERIFY_TOKEN=<token> VERIFY_BACKEND=... npm run check:request`，使 `forbidden`/`denied`/`clientError` 三条**无 token 到不了的**通道也得到真机验证；最后 `npm run dev` 走一遍 F0 的代理自检页确认未回归（**F2 起该页已删除**，届时改走登录页复验代理；见 §9 的 F2 设计决定①） |
| **F2** | ① 未登录访问任意**已注册**路由（含 `/admin/**`）→ 全部跳 `/login?redirect=<原路径>`；且 `redirect` 必须校验为**站内路径**（`//evil.com`、`https://…`、`javascript:` 一律忽略并回落默认首页）。**排除 `/:pathMatch(.*)*` 兜底页**：未登录访问不存在的路径看到 404 页，比看到登录页更正确，不作为缺陷（此条为 2026-10-06 F2 实测后订正——原文写「任意路由」，与规格里兜底页 `meta.public:true` 自相矛盾）；② 登录成功后有 token，且 `GET /user/me` 显示昵称与角色（角色文案取 `statusMap.UserRole`，**不得**在页面里写 `role === 'LAB_ADMIN' ? … : …`）；③ USER 访问 `/admin/**` → 403 页；LAB_ADMIN 访问 `/admin/**` → 正常；④ **退出登录后回 `/login`，且旧 token 再请求 `/user/me` 得到 401**——该条测的是"**先带 token 调后端、再清本地**"的**顺序**，写反了会拿到 200（因为 Redis 会话没被删）；⑤ 验证码错误时展示后端 `errorMsg`（T2 形态 `200`+`success:false` → `kind='business'`）；⑥ **`node scripts/verify-session.mjs` 退出码 0**，且四组断言全过：**守卫真值表（6）／redirect 消毒与默认路由（15）／`needsProfileFetch` 谓词行为（4）／静态接线文本检查（1）**。**光看退出码不算过**——`VERIFY_BACKEND` 未设置时脚本会打印 SKIP 并**照样退 0**（实测），必须确认输出里真的出现了那 3 条真机会话断言（`backend login…` / `backend /user/me…` / `backend logout…`），而不是 SKIP 文案。无 env 基线 = **24 PASS**；带真机 = **27 PASS**；⑦ `npm run build` 成功、`npm run check:request` 仍退出码 0（F1 未回归）、后端目录 `git status` 无变化 | 后端 `--server.port=18081` 启 → **取码前先确认没有别的脚本刚调过 `/user/code`** → 从**后端日志**取 6 位码 → `VERIFY_BACKEND=http://127.0.0.1:18081 VERIFY_CODE=<6位码> npm run check:session`；**再**跑 `npm run check:request`。**顺序不可颠倒**：`check:request` 内部会 `POST /user/code`（F1 契约用例），会把你手上的码顶掉，颠倒后 `check:session` 会因"验证码错误"失败，看起来像实现 bug（2026-10-06 实测发现的运行顺序约束）；再 `npm run dev` 手工走：13686869696（USER）与 13900000001（LAB_ADMIN）各登录一次；开两个标签页，A 登出后回 B 点任意链接应被踢回登录页；手工把地址栏改成 `/admin` 验证 USER 落到 403 页、LAB_ADMIN 正常进入 |
| **F3** | ① 实验室列表显示 4 条、分页控件显示总数；② 点进详情正确显示；③ 设备列表 12 条分 3 页，`name`/`categoryId`/`status` 筛选各自生效（筛选后 total 变化）；④ `GET /laboratory/999999` 显示"实验室不存在"（**不得白屏**）；⑤ `GET /equipment/999999` 显示"设备不存在" | 依次切换筛选条件并核对 total；手工把 URL 的 id 改成 999999 验证两个详情页的容错 |
| **F4** | ① 批次列表在无数据时显示空态（当前库中 `tb_reservation_batch` 为 0 行，属正常）；② 管理端发布一个批次后，学生端能看到；③ 详情页的**按钮可用性完全由 `bookingState` 驱动**（`OPEN` 才可点"立即预约"，其余为禁用+原因提示）；④ `GET /reservation-batch/999999` 显示"预约批次不存在"（200 形态）；⑤ 前端**不发送** `status=DRAFT/CANCELLED`（会 400） | 用管理员发一个批次；回学生端点进去；观察 `bookingState=OPEN` 时按钮可用、把系统时间理解成 `NOT_STARTED` 场景时按钮禁用提示"未开始预约" |
| **F5** ★ | ① 点击"立即预约"后按钮进入 loading 且**不可重复点击**；② 成功后展示"预约请求已提交"并**在 ≤10s 内**自动给出终态（实测窗口 ≈46–114ms，10s 上限是安全余量）；③ **轮询期间遇到 403（`"无权查看该预约"`）继续重试而不是直接报错**；④ 400 时直接展示后端中文 `errorMsg`（如"你已预约过该批次"）；⑤ 503 两种文案分别提示，`预约结果待确认，请稍后查询` 时引导到"我的预约"；⑥ **提交成功后跳转/刷新"我的预约"能看到该条记录**；⑦ 全链路中 `reservationId` 未被 JS 精度破坏（否则会出现莫名 403） | 学生登录 → 设备 → 批次 → 立即预约 → 观察结果面板；再点一次验证"你已预约过该批次"；把批次名额消耗完后提交验证"名额已约满" |
| **F6** | ① 列表只出现**自己**的预约；② 详情页字段与 `GET /reservation/{id}` 一致；③ 取消前二次确认，取消成功后**重新拉取**并把状态显示为 CANCELLED；④ 对已取消的记录再点取消 → 后端 200 幂等，页面**通过重取**显示已取消（不得显示"取消成功"这种可能误导的文案）；⑤ 越权访问他人预约 id → 403 且有 `errorMsg` | 预约后取消，刷新页面确认状态持久；用另一个账号的 token 手工请求他人 id 确认 403 提示正确 |
| **F7** | ① 列表与操作只对 LAB_ADMIN 可见；② 新建实验室成功且创建者成为负责人；③ 删除仍有设备的实验室 → 展示后端 400 文案（含设备台数）；④ 设备状态切换 3 个值均可成功；⑤ **越权操作他室设备必须得到 403 且页面展示后端文案**（当前 `/equipment/page` 无归属过滤，他室设备**会出现**在列表里）；⑥ `PUT /equipment/{id}/status` 非法值不会由前端发出 | 用 1016 登录，对实验室 4 的设备尝试改状态（应为 403 文案）；新建实验室 → 建设备 → 尝试删实验室（应被拒）→ 删设备 → 再删实验室（应成功） |
| **F8** | ① 新建批次后为 `DRAFT`；② **草稿可编辑，非草稿编辑被拒**（400 "仅草稿批次可修改"）；③ 发布成功后状态变 `PUBLISHED` 且剩余名额=总名额；④ 发布校验逐条可触发并展示对应中文（时段重叠 / 预约截止晚于使用开始 / 设备不可预约 / 总名额<1）；⑤ 关闭后批次不可再被预约；⑥ **表单提交的日期字符串带 `T`**（把空格格式发出去会得到"请求体格式错误"） | 建一个时段与既有批次重叠的批次并发布（应报"同一设备的使用时段不能重叠"）；再建一个正常批次发布成功，回学生端确认可预约 |
| **F9** | ① 管理端列表只显示**自己实验室**的预约（`l.admin_user_id` 过滤）；② 未到 `startTime` 核验 → 400 "预约尚未开始，无法核验"；③ 核验成功后**重新拉取**显示"已核验"；④ 再次核验返回 200 但页面**不得**提示"核验成功"（后端幂等无提示）；⑤ 对自己无权实验室的预约核验 → 403 | 造一个 `startTime` 在未来的批次预约，尝试核验（应报未开始）；对已到时间的预约核验两次，观察第二次的提示是否诚实 |
| **F10** | ① 列表不显示 payload（后端列表恒 null），详情才显示且**格式化后不溢出**；② `replay` 成功与各拒绝原因分别有正确提示（含 `REPLAY_CONFIRM_NACK` 等英文代码被映射为中文，同时可展开看原文）；③ 对 `REPLAYED`/`DISCARDED` 记录重放被拒；④ 重放前二次确认；⑤ 页面不对 `payload` 做任何"解析后重写" | 制造一条死信（可停掉消费者后用 `basicNack` 或直接改库造一条 `NEW` 死信）→ 详情查看 payload → 重放 → 观察状态变 `REPLAYED`；再点一次验证被拒文案 |
| **F11** | ① **15 个数据页**（客观判定：文件内有 `import …/api/`；清单与原文「11 个页面」的订正见下方偏差①）都有 loading / 空态 / 错误态三种表现；② 无任何页面出现空白屏或未捕获异常；③ 窗口宽度 **1440/1200/992/768** 下布局不破（可核验判据 = `documentElement.scrollWidth - clientWidth ≤ 1`；管理端侧栏在 ≤992 折叠为 64px）；④ 全站空态/错误态的**结构与错误前缀统一**（一律走 `EmptyState`/`LoadingBlock`，错误文案前缀 `…加载失败：`；**口径见偏差②** —— 要的是结构统一，不是把各实体名词抹平成一句通用文案）；⑤ **跑通两条完整演示链路**（学生全链路、管理员全链路）；⑥ 前端源码中 `axios` **零出现**于 `views/`、`layouts/`、`components/`，且整个 `src/` 里 import `axios` 的文件**恰为** `api/createRequest.js` 一个（API 层隔离的可核验判据；范围订正见偏差④） | 按 §3/§4 的两条演示链路各走一遍；用 DevTools 设备模拟切换宽度；`grep -rn "axios" frontend/src/views frontend/src/layouts frontend/src/components` 应为空；`npm run check:ui` 退出码 0 |

---

### F2 验收实测记录与**覆盖边界**（2026-10-06，Claude 独立实测）

**跑通的命令与结果**（后端 `java` pid 77090 监听 `*:18081`；码 `193087` 取自 `/tmp/f2-backend.log` 的 `验证码：`）：

| 命令 | 结果 |
|---|---|
| `VERIFY_BACKEND=http://127.0.0.1:18081 VERIFY_CODE=<码> node scripts/verify-session.mjs` | **22 PASS / 0 FAIL / exit 0**，三套件（守卫真值表 + 静态冒烟 + 真机会话）**都真的执行了** |
| `npm run check:request` | **exit 0**，22 PASS / 0 FAIL（F1 未回归） |
| `npm run build` | **exit 0**，1685 modules，242 ms |
| `git status --porcelain -- src pom.xml README.md` | 空（后端零接触） |
| `command grep -rnE "if *\( *status *===|role *=== *'LAB_ADMIN'" src/views src/layouts` | 空（状态/角色一律走 `resolveStatus`） |
| `command grep -rl "后端控制台日志" dist/` | 空（`import.meta.env.DEV` 被正确剔除，DEV 提示不进生产包） |
| `git diff -- frontend/package.json` | 只多 `check:session` 一行；`dependencies`/`devDependencies` 零改动 |

**证伪实验（证明那 22 条"绿"是有判别力的，不是恒真）** —— 逐个变体单独跑、逐个验证守卫串、复原后 `shasum -a 256` 比对 byte-exact：

| 变体 | 预期 | 实测 | 结论 |
|---|---|---|---|
| 改坏守卫真值表第 1 行条件 | 恰好 1 条失败 | `18 PASS, 1 FAIL`（就是第 1 行那条） | 有判别力 |
| 去掉 `sanitizeRedirect` 对 `/login` 的拦截 | 恰好 2 条失败 | `17 PASS, 2 FAIL`（两条 `/login` 用例） | 有判别力 |
| 删掉真值表第 5 行角色校验 | 恰好 1 条失败 | `18 PASS, 1 FAIL` | 有判别力 |
| **把 `beforeEach` 接线换三种版本**（缺陷在位 / 修好 / `fetchMe` 彻底摘掉） | —— | **三种都是 `19 PASS, 0 FAIL`** | **判据对这段接线没有覆盖**（就是上面 §9 ② 补正里那条缺陷） |

> **一次变体作废的记录**：第一个变体我用 `sed` 把 `&& profile` 换成 `&& !profile`，但 `&` 在 sed 替换串里是"整个匹配"占位符，转义后仍没生成目标串，`guard.js` 变成语法错误 → 输出 **18 FAIL**。而我的预期是"恰好 1 FAIL"——**签名不符**，所以我没有把它当成"判据有判别力"的证据，而是先怀疑变体本身、重做（改用不含 `&` 的替换串，得 `18 PASS, 1 FAIL`）。教训与台账里"守卫串/正则必须先自证"同族：**失败数量与预期不符时，先怀疑自己的实验装置**。

**覆盖边界（必须分清，否则下一轮又会把"脚本全绿"误读成全覆盖）**：

| 层级 | 覆盖内容 |
|---|---|
| **A. 脚本行为断言（真机可复跑）** | `resolveNavigation` 6 行真值表、`sanitizeRedirect` 10 条 + 3 类非字符串输入、`defaultRouteFor`、`api/request.js` 能在裸 Node 里 import 不抛、以及 3 条真机会话断言（登录返回裸串 token / `/user/me` 的昵称角色 / **登出后旧 token 立刻 401**）。末条是验收④的**判别性**证据：顺序写反（先清本地、不调后端）会拿到 200。 |
| **B. 脚本静态文本断言（**不是**行为验证）** | `router/index.js` 的 `beforeEach` 段含 `needsProfileFetch(`、不含 `to.meta.public`。这是妥协：该模块 import 了 `.vue`，Node 加载不了。输出里必须标注其性质。**它的能力边界已实测**（见下方 W3）：它锁的是"文本特征在不在"，**不锁调用参数**。 |
| **C. 仅由我读码确认（无自动判据）** | `main.js` 的 `setUnauthorizedHandler(handleUnauthorized)` 注入时机与 `wasLoggedIn` 判定；`api/request.js` 的 `getToken` 只在箭头函数体内读 store（ESM 循环安全条件）；`stores/user.js` 的 `logout` 用 `try { await logoutApi() } finally { this.reset() }` **先调后端再清本地**——注意脚本只证明了**后端**"登出后旧 token 401"，**没有**证明 store 的调用顺序，两者别混。 |
| **D. 曾只能人工点（脚本测不到）——2026-10-06 已补证，该档覆盖关闭** | 三个接线点的浏览器实际行为：① 401 时是否真的跳登录页并带上 `redirect`；② 请求头里是否真的挂上了 `Authorization`；③ `beforeEach` 的真实导航结果（含硬刷新 `/login` 带有效 token 是否被送回首页——即缺陷 1 的修复效果）。F2 提交前这几条以"人工点过"汇报，**不得**记作"脚本证明"。**2026-10-06 用零依赖 CDP 仪器在真实浏览器中把这三条实测并证伪，D 档至此关闭**（证据见下方「F2 三个接线点浏览器实测补录」）。 |

#### 返工复核（2026-10-06，第 2 轮：修缺陷 1 之后）

**范围证明**（不靠"Codex 说只改了三个文件"）：用 mtime 定位返工窗口内的写入，只有 `guard.js`、`index.js`、`verify-session.mjs` 三个文件的 mtime 落在返工窗口（18:51–18:52）；`api/request.js`、`layouts/`、`views/`、`stores/`、`main.js`、`package.json` 的 mtime 仍是首次交付时的 18:41–18:44，未被动过；`docs/` 的 18:50:39 是我自己的文档修改（早于 Codex 的写入），故"Codex 未触碰 docs"得到证实而非采信。

**命令结果**：`node scripts/verify-session.mjs`（无 env）**24 PASS / 0 FAIL / exit 0**；加真机 `VERIFY_BACKEND`+`VERIFY_CODE` → **27 PASS / 0 FAIL / exit 0**，且 3 条真机会话断言**实际执行**（非 SKIP）；`npm run check:request` **exit 0 / 22 PASS**（F1 未回归）；`npm run build` **exit 0**。已通过的断言未被放宽——前 150 行与返工前**逐字相同**，`icon` 的精确断言**保留**并**追加**了类型/非空断言与 D-12 注释。

**四个接线变体（我自己复现 RED/GREEN，不采信自述）**：

| 变体 | 实测 | 说明 |
|---|---|---|
| W1 调用点改回旧门控 `!to.meta.public && token && !profile` | `23 PASS, 1 FAIL` | 静态文本锁抓到 |
| W2 调用点改成 `if (false)`（永不 fetchMe） | `23 PASS, 1 FAIL` | 静态文本锁抓到 |
| W3 调用点传错参数 `needsProfileFetch({ to, token: null, profile })` | **`24 PASS, 0 FAIL`** | **残留缺口：文本特征齐全、接线已坏，脚本全绿** |
| W4 把公开路由门控塞回**纯函数内部**（`index.js` 不动，绕过文本锁） | `22 PASS, 2 FAIL` | **行为断言**抓到（比文本锁强得多） |

**W3 的处置（记录为已知代价，不再为此加一轮返工）**：文本锁锁的是标记存在性、锁不住调用参数。要堵 W3 需把断言收紧成钉死整串 `needsProfileFetch({ to, token, profile })` 的正则——但那种写法对格式化极脆，且 W3 是"显式写 `token: null`"这种没人会顺手犯的错；而现实中最可能的回归（"有人又把公开路由门控加回来"）**W1 与 W4 都抓得到**，且 W4 是行为级抓捕。**权衡结论：静态锁保持现状，其边界在此显式登记，不粉饰。**

**这一版 `needsProfileFetch` 语义（`token && !profile`，无公开路由门控）的一个已接受副作用**：带**过期 token** 打开公开页（如不存在的 URL → 404 页）也会触发一次 `fetchMe` → 401 → `handleUnauthorized` 清本地并跳 `/login`。即"会话已死时访问错 URL 会被引导去登录"而不是停在 404 页。**接受理由**：行为统一为"硬进入任何路由恰好校验一次会话"，无特例分支，且能自愈客户端的过期会话；代价仅是一次 GET。

---

### F2 三个接线点浏览器实测补录（2026-10-06，Claude 独立实测，D 档覆盖由此关闭）

**方法**：`git archive HEAD frontend` 取 F2 已提交代码（HEAD = `88f008a`）的**快照**，另起 vite 于 5174 代理到 `127.0.0.1:18081`。用快照而非工作树，是因为此刻 Codex 正在同一仓库并行实现 F3——避免它的中途写入污染证据。仪器为**零依赖** CDP 脚本（Node 22 自带全局 `WebSocket` + headless Chrome `--remote-debugging-port`），不引任何测试框架。脚本：`/tmp/f2-cdp-verify.mjs`。

**三条接线点的实测结果**（11 条断言，全部真机、真后端）：

| 接线点 | 实测证据 |
|---|---|
| ① 失效 token 访问受保护路由 | `/api/user/me` **实测返回 401**（确认走的是 401 通道而非别的失败）→ `localStorage` token 变 `null` → 最终 URL `http://localhost:5174/login?redirect=/home` → 落点为真实渲染的登录表单（`input` 存在、body 非空，**非白屏**） |
| ② 请求头真的挂上 `Authorization` | `/api/user/me` 请求头实测 `authorization` 值**逐字节等于** store 里的 token；后端应答 **200**，首页渲染出真实昵称「小鱼同学」——证明头不只"发出去了"，而是被后端接受并生效 |
| ③ 硬刷新 `/login` 带有效 token | 整页 `Page.reload` 后停在 `http://localhost:5174/home`，首页正文已渲染——**缺陷 1 的修复效果确认** |

**证伪实验（证明这 11 条"绿"有判别力）** —— 每个变体单独跑，复原后 4 个文件对 `git show HEAD:` 用 `diff -q` 验证 byte-exact：

| 变体 | 改动 | 实测 | 结论 |
|---|---|---|---|
| 基线 | 无 | **11 PASS / 0 FAIL** | —— |
| V1 | 缺陷 1 改回 `if (!to.meta.public && needsProfileFetch(...))` | 9 PASS / **2 FAIL**，恰好测试③两条；body 文本实测变为登录表单 | 有判别力 |
| V2 | `authorization: token + "X"` | 6 PASS / **5 FAIL**（②三条 + ③两条） | 有判别力 |
| V3 | `handleUnauthorized` 改空操作 | 11 PASS / **0 FAIL** | **未抓住——该实现冗余** |
| V4 | 抽掉 `fetchMe` 的 `this.reset()` | 11 PASS / **0 FAIL** | **未抓住——该实现冗余** |
| V5 | V3 + V4 双抽 | 10 PASS / **1 FAIL**，恰好①的「本地会话已清空」，实测残留 `token = "deadbeefdeadbeefdeadbeefdeadbeef"` | 有判别力（V3/V4 是被另一套实现互相掩盖） |
| 复原 | 4 文件字节级复原 | **11 PASS / 0 FAIL** | —— |

**V3/V4 是诚实阴性结果**：单独改坏任意一处，断言全绿。两者是**互相掩盖的两套会话清理实现**——`stores/user.js` 的 `fetchMe` 内 `this.reset()` 与 `main.js` 的 `handleUnauthorized`，任一单独存在即满足断言。V5 补的这一刀证明断言非空转。

**由此得到的结构性结论（对 F3 及以后有约束力）**：在 F2 阶段，全局 401 handler 的**效果完全冗余**——`fetchMe` 与路由守卫已覆盖；它的跳转被守卫遮蔽。**它从 F3 起才承重**：列表页是在已渲染页面上发请求，没有守卫会再跑一遍。这正是 F3 任务包要求「页面遇到 `unauthorized` 必须静默 `return`，把处理权交给全局 handler」的原因。

**一次测试装置的缺陷记录**：测试②最初寄生在测试③的导航上（缺陷恢复后 `/login` 不再触发 `fetchMe`，②被殃及），表现为 V1 跑出 5 FAIL 而非预期的精确 2 FAIL。**失败数量与预期不符时先怀疑装置**——给②加了自足前置（`navigate('/login')` → 写 token → `navigate('/home')`）后，V1 才回到预期的 2 FAIL。"跑绿的仪器"本身也必须被证伪。

---

### F3 验收实测记录（2026-10-06，Claude 独立实测）

**边界证明（我自己数的，不采信 Codex 自述）**：`git status --porcelain -uall` 恰好 **17 项 = 13 新增 + 4 修改**，与任务包白名单逐条吻合；`src/**`、`pom.xml`、`README.md`、`docs/**`、`package-lock.json`、`vite.config.js` 零接触；F1/F2 已交付文件（含 `statusMap.js`、`enums.js` 与三个 verify 脚本）零接触；`package.json` 只多一行 `check:browse`。17 个文件**逐个读过全文**，不是只看 diff。

**最关键的一条证据——尺子没有被改短**：Codex 交付的 `verify-browse.mjs` 与**实现之前**写下的那份 RED 草稿 **sha256 逐字节相同**（`73be429874016dacb134253fd193f4adb58443e2606b595e7c0de5043162d9c6`）。草稿成文于实现之前，它相同即证明 Codex **没有在实现完成后回头改写断言来迁就实现**——正是台账里"实现过度概括并改写上个 Phase 契约用例"那个坑。RED→GREEN 是真实的。

| 命令 | 结果 | 备注 |
|---|---|---|
| `node scripts/verify-browse.mjs`（无 env） | **19 PASS / 0 FAIL / exit 0** | 纯函数 14 + 路由表 4 + 静态文本 1 |
| `VERIFY_BACKEND=…:18081 VERIFY_TOKEN=<token> node scripts/verify-browse.mjs` | **37 PASS / 0 FAIL / exit 0** | B 组真机契约；Codex 沙箱连不上 loopback，**全部 SKIP**，由我补跑 |
| `npm run check:request` | **22 PASS / 0 FAIL**（带 token 26） | 验收③。Codex 侧因沙箱 `listen EPERM` 崩溃、**从未跑通** |
| `npm run check:session` | **24 PASS / 0 FAIL** | 3 条需登录码的检查未跑（非 F3 判据） |
| `npm run build` | **exit 0**，248 ms | 仅既存大 chunk 警告 |
| **浏览器行为验证（我另写）** | **40 PASS / 0 FAIL** | 覆盖验收⑪的 14 步人工项 |
| **控制台噪音扫描** | **0 条** error/warning/异常 | 走遍 20 条路径，含全部边界 |
| 静态判据 A–E（视图无 HTTP 配置/无裸 url‑method/无接口路径/无裸状态比较；api·utils·constants 无 `pageSize`） | **全部为空** | 我自己复跑，未采信 Codex 侧结论 |

**Codex 侧的一处盲区，我补上了**：B 组对 `createTime` **只断言字段"存在"、没断言"形状"**。若后端把 `LocalDateTime` 发成数组，`formatDateTime` 会一声不响地把所有时间渲染成「—」，而 B 组照样全绿。直接看原始 JSON 关掉该缺口：`createTime`/`updateTime` 是 ISO 字符串 `"2026-10-03T18:00:48"`（dayjs 可解析，浏览器实测渲染出 `2026-10-03 18:00:48`）、`status` 是枚举**名字符串**、`bookingEnabled` 是真**布尔**、id 经 json-bigint 仍是 **number**。字段名同时对 `EquipmentVO.java`/`LaboratoryVO.java` 源码与原始 JSON 双向核对——`laboratoryId`/`usageRequirement`/`laboratoryName` 这类若拼错，详情页会静默渲染空白。

**两条 not-found 通道的行为分支实测区分**（F3 的核心不对称，§1.1 的五通道落到页面上）：`/laboratories/999999` → `Result.ok(null)` 200 → 「实验室不存在」且**未被误判成「加载失败」**；`/equipment/999999` → `Result.fail` 200 → 「设备不存在」；`/equipment/abc` 与 `/laboratories/abc` → **零请求、零 5xx**，本地 `isNumericId` 拦住了后端必 500 的那条路。

**证伪实验（浏览器仪器，6 个变体各自针对一个独立主张；复原后 16 文件对冻结指纹 `shasum -c` 全 OK）**：

| 变体 | 改坏什么 | 实测 | 结论 |
|---|---|---|---|
| 基线 | 无 | **40 PASS / 0 FAIL** | —— |
| V1 | 实验室详情 ok+null 分支改成报错态 | 39 PASS / **1 FAIL**，恰好 3b | 有判别力 |
| V2 | 去掉非数字 id 本地拦截 | 37 PASS / **3 FAIL**（9a/9b/9c）——**后端真的吐了 500** | 有判别力，且证明 9c 非空转 |
| V3 | 菜单高亮退回 `route.path` | 38 PASS / **2 FAIL**，恰好两条详情页失高亮 | 有判别力 |
| V4 | 去掉越界页码自愈 | 38 PASS / **2 FAIL**（5a/5b） | 有判别力 |
| V5 | `buildEquipmentQuery` 状态白名单失效 | node 仪器 18 PASS / **1 FAIL** | 有判别力 |
| V6 | `PAGE_SIZE` 改 10 | node 仪器 17 PASS / **2 FAIL** | 有判别力 |

**我的仪器自身先有 3 个缺陷（一并记录，与 F2 那条同族）**：① 名称输入框选择器取到了 `el-select` 的内部 `input`（筛选面板前两个 `input` 都是 select 的），必须按 `placeholder` 定位；② 9c 按 **URL** 而非**状态码**判定，把文档响应 `http://localhost:5175/equipment/abc`（200）数成了"500"。**基线跑出的 3 个 FAIL 全是我的错、不是 F3 的错**，定位修正后才得到 40/0。再次印证：失败数量与预期不符时先怀疑装置。

**Review 发现的问题（均为 nit，不构成返工）**
1. `LaboratoryListView.changePage` 用原始 `route.query`，而 `EquipmentListView.changePage` 用清洗后的 `params`——同一功能两套写法。无害（`buildLaboratoryQuery` 只认 `current`），但应统一。
2. 名称输入框的 `clearable` 清除叉**不提交**，而三个下拉框清除**即刻生效**。符合任务包（名称经 Enter/查询提交），但与下拉框行为不一致。
3. `unauthorized` 静默 `return` 会让 `state` 停在 `loading`（转圈不结束）。这是任务包要求的写法、且全局 handler 确实会跳转；但万一跳转失败，失败形态是**无限转圈而非报错**。F3 起全局 handler 才承重（见 F2 补录的结构性结论），这条留待 F11 统一收口。

**仍未覆盖**：`check:session` 的 3 条需登录码的检查（属 F2 范畴）；四个页面的「简洁/现代/高校科研感/克制」观感判断**只能由人眼确认**，不在任何自动判据内。

---

### F4 验收实测记录（2026-10-06，Claude 独立实测）

**边界证明（我自己数的，不采信 Codex 自述）**：`git status --porcelain -uall -- frontend` 恰好 **9 项 = 4 新增 + 5 修改**，与任务包白名单逐条吻合（改：`package.json`、`router/routes.js`、`utils/query.js`、`views/equipment/EquipmentDetailView.vue`、`views/home/HomeView.vue`；增：`api/reservationBatch.js`、`views/batch/BatchListView.vue`、`views/batch/BatchDetailView.vue`、`scripts/verify-batch.mjs`）。

**后端零接触**：`git status --porcelain -- src pom.xml README.md` **为空**。该命令在**写本记录之前**跑的，故现已必然非空的是 `docs/frontend/FRONTEND-DESIGN.md` 本身——**那是本次记录的写入，不是 Codex 的改动**（复跑时请把 `docs/` 排除在上述命令外，或对照本行说明）。`package-lock.json`、`vite.config.js` 未动；`package.json` 只多一行 `check:batch`，`dependencies`/`devDependencies` 逐字节未变。

| 命令 | 结果 | 备注 |
|---|---|---|
| `node scripts/verify-batch.mjs`（带真机 token + C 组 fixture） | **22 PASS / 0 FAIL / 0 SKIP** | A 组 13（静态接线文本）+ B 组 6（真机契约）+ **C 组 3（已发布批次形状）不再是 SKIP** |
| **浏览器仪器 1**（无 fixture，47 条断言） | **47 PASS / 0 FAIL** | 覆盖菜单/空态/状态白名单/URL 同步/越界页/business 通道/非数字 id 等 |
| **浏览器仪器 2**（fixture，32 条断言） | **32 PASS / 0 FAIL** | 覆盖验收③的按钮可用性与列表/详情字段形状 |
| `npm run check:browse`（F3） | **37 PASS / 0 FAIL** | F3 未回归 |
| `npm run check:request`（F1，带 token） | **26 PASS / 0 FAIL** | F1 未回归 |
| `npm run build` | **exit 0**，266 ms | 仅既存的大 chunk 体积警告 |
| 控制台噪音扫描（仪器走遍全部路径） | **非 favicon 噪音 0 条**；favicon 既存噪音本轮回 **0 条** | 仪器对二者**分别计数、不静默丢弃**（见下缺陷①）；favicon 那条 F0 起既有，非 F4 引入 |

**§10 判据 ①–⑤ 的落点**：

| 判据 | 落点与实测证据 |
|---|---|
| ① 无数据时空态 | 仪器 1 组 2（2a–2e）：显示「暂无可预约的批次」+ 说明文案，零表格行；带筛选无结果时改显「没有符合条件的批次」+「重置筛选」（组 4） |
| ② 管理端发布后学生端可见 | fixture 走**真实链路**造批次（`sendCode` → Redis 取码 → LAB_ADMIN 登录 → `createBatch` → `publish`），直连后端核对 `bookingState` 为 `OPEN`（id 11003）/`NOT_STARTED`（id 11004）；仪器 2 组 18（18a–18i）核对列表行与列形状，组 19（19a–19h）核对详情字段 |
| ③ 按钮可用性由 `bookingState` 驱动 | 仪器 2 组 11/12：OPEN 态按钮 `disabled=false`、点击出 `ElMessage.info('提交预约功能将在后续版本开放')`；NOT_STARTED 态 `disabled=true` + 「当前不可预约：未开始预约」。另有 verify-batch A 组两条**静态锁**：`submit availability comes only from BookingState actions`、`never recomputes bookingState with the client clock` |
| ④ `/reservation-batch/999999` | 仪器 1 组 7：实测 **200 + `{"success":false,"errorMsg":"预约批次不存在"}`** → `kind='business'`，页面显示该文案且**未被误判成「加载失败」** |
| ⑤ 不发送 `status=DRAFT/CANCELLED` | 仪器 1 组 3 在**请求层**断言：这三个值全程零请求。实现是 `utils/query.js:66` 的白名单 `BATCH_PUBLIC_STATUSES.includes(status) ? status : undefined`（`BATCH_PUBLIC_STATUSES = ['PUBLISHED','CLOSED']`，`query.js:49`），即**非法值根本不发**，而非"发了让后端拒" |

**为什么 `status` 白名单是硬需求而非优化**：实测 `DRAFT`/`CANCELLED` → **400 + Result 体 `批次状态不合法`**；`BOGUS` → 400 但走 Spring ErrorAttributes 体（`message` 为空）→ 落到 `clientError` 通道。变体 V1 把白名单摘掉后，6 条状态断言与**控制台噪音断言同时变红**——即真的发出了 400 请求。同理 `isNumericId` 前置过滤也是硬需求：`/reservation-batch/abc` 实测 **HTTP 500**（后端不做类型校验），变体 V2 摘掉后 8a/8b/8c 与噪音断言一起变红。`/reservation-batch/{id}` 需要登录态，无 `authorization` 头时是 **401 空体**。

#### 两支浏览器仪器，以及它们**互斥的数据前提**

F4 的浏览器层判据被拆成两支仪器，原因是**判据 21 的前后半天然要求相反的数据前提**：前半（`total>0` 时越界页自愈）必须有已发布批次，后半（`total=0` 时**不**自愈、保持 `current=99`）必须没有。

| | 仪器 1 | 仪器 2 |
|---|---|---|
| 前提 | 库中**无**已发布批次 | 库中**有**已发布批次（fixture） |
| 覆盖 21 | **后半**（组 6：6a–6d，含「total=0 时未自愈改写 URL」） | **前半**（21a–21c，实测 `?current=99` 被改写为 `?current=1` 且渲染出 2 行） |
| 设备 12 内嵌区块 | 9b：显示「该设备暂无预约批次」 | S1：**不再**显示该文案；S2：内嵌表含批次行 |
| 断言数 | 47 | 32 |

9b 与 S1 是同一件事的正反两面，**正好互补**——这本身就是两条断言都真实有效的旁证。代价是**复跑有顺序要求**：必须先让库回到「无已发布批次」跑仪器 1，再造 fixture 跑仪器 2。这一条已实测踩到：fixture 在位时仪器 1 报 **37 PASS / 10 FAIL**，10 条红色**全部**是空态相关断言（2a–2c、3e、4c/4d/4f、6b/6c、9b），**没有一条指向产品缺陷**。

**fixture 的造与清都走真实 API，不做库内直改**：造 = `sendCode` → Redis 取码 → LAB_ADMIN 登录 → `createBatch` → `publish`；清 = `POST /reservation-batch/{id}/cancel`（`ReservationBatchServiceImpl.java:176`，允许 `DRAFT`/`PUBLISHED` → `CANCELLED`，带 `assertEquipmentManageable` 所有权校验，并连带清掉 Redis 的 quota/users key）。

#### 证伪实验（15 个变体，复原后 6 个文件 `shasum -a 256 -c` 全 OK）

**仪器 1（无 fixture）——9 个变体，各自单独跑**：

| 变体 | 改坏什么 | 实测变红 | 结论 |
|---|---|---|---|
| V1 | 摘掉 `status` 白名单 | 3a/3a-2/3b/3b-2/3c/3c-2 **+ 噪音断言** | 有判别力（证明白名单是唯一拦住 400 的东西，且噪音断言承重） |
| V2 | 摘掉 `isNumericId` 前置 | 8a/8b/8c + 噪音 | 有判别力（后端真的吐了 500） |
| V3 | 空态忽略 `activeFilter` | 3e/4c | 有判别力 |
| V4 | 内嵌请求不带 `equipmentId` | 9c | 有判别力 |
| V5 | 把 `business` 判成 `error` | 7b | 有判别力 |
| V6 | 菜单 `order` 4→2 | 1b | 有判别力 |
| V7 | 空态标题恒为「没有符合条件的批次」 | 2a/2d/4f/6c | 有判别力 |
| V8 | 空态标题恒为「加载失败：测试」 | 2a/2e/3a-2/3b-2/3c-2/3e/4c/4f/6c | 有判别力 |
| V9 | 设备内嵌空态文案改成「批次加载失败：测试」 | 9b/9f | 有判别力 |

**仪器 2（fixture）——6 个变体**：

| 变体 | 改坏什么 | 实测变红 | 结论 |
|---|---|---|---|
| V10 | `:disabled="!canSubmit"` → `"false"` | 12b | 有判别力 |
| V11 | `:disabled="!canSubmit"` → `"true"` | 11b/11c/11d/11e | 有判别力 |
| V12 | 提示语 → `void 0` | **只有 11d** | 有判别力 |
| V13 | `handleSubmit` 里加一行 `fetch POST /reservation` | **11e + 噪音断言** | 有判别力（越界发的 POST 真的在控制台炸出错） |
| V14 | 列表时间原样渲染 `{{ row.startTime }}` | 18g/18h | 有判别力 |
| V15 | 停掉越界页自愈 | 21a **+ 21b/21c** | 有判别力（21b/21c 是真实后果：页面真停在空页 `current=99`） |

#### 仪器与装置自身的缺陷（9 条，与 F2/F3 那几条同族）

1. **仪器 1 favicon 误报**：唯一噪音是隐式 `/favicon.ico` 404（F0 起既有：`index.html` 无 `<link rel="icon">`、无 `public/` 目录，不在 F4 改动集内）。处置**不是静默丢弃**，而是显式分类计数并把 favicon 单列。
2. **仪器 1 断言标签歧义**：`3${bad[0].toLowerCase()}` 让 `DRAFT` 与 `PUBLISHED` 都成了 `3d`。改为索引式 `3${'abc'[i]}`。
3. **仪器 2 四条纯否定式断言是空转的**（18h/19b/19g/11c）：在**完全坏掉的空页**上照样全绿。全部补上正向前置条件后才成立。**否定式断言必须与正向前提合取**——这是本轮最有普适价值的一条。
4. **仪器 2 在按钮不存在时直接 `.click()`** → `TypeError` 崩溃，**后续 12/21/S 组与 Summary 全部丢失**。加 `if (btnA === false)` 守卫。仪器不能在退化状态下崩——崩了会连带丢掉还没跑的证据。
5. **仪器 2 断言写错实验室名**：18f 期望「材料科学实验中心」，而设备 12 实际属于**实验室 4 = 计算与样品制备中心**。运行前核对种子数据时抓到。
6. **复苏器的单行协议被多行替换串破坏**：`IFS=$'\t' read -r file old new expect` 只吃一行，而 V12/V13 的替换串含换行，于是实际施加的是「删掉 `function handleSubmit() {` 这一行」。**症状是「期望变红」字段为空 + 两个变体跑出一模一样的 17 FAIL**。处置：只写单行替换串，并加 `expect` 空值守卫——为空即拒绝施加。**一个坏掉的变体跑出 17 个 FAIL，看起来比 1 个 FAIL 更有说服力**，这正是它危险的地方。
7. **fixture 脚本的 CJK 吞并 `$VAR`**：`$TOKEN_FILE（`、`$id（`、`$eq，` 里全角标点被 bash 吞进变量名 → `unbound variable`。全部改用 `${VAR}`，并用 `grep -oP '\$[A-Za-z_][A-Za-z0-9_]*[^\x00-\x7F]+'` 自检（注意 `grep` 按行报，**一行里藏多个匹配会被漏掉**，须用 `-oP` 枚举）。
8. **fixture 脚本第 ⑥ 步漏带 `authorization` 头** → 401 空体 → `json.JSONDecodeError` 崩。补头并容忍非 JSON 响应。
9. **残留 DRAFT 批次挡住发布**：上一次崩溃在 `say` 之前就已执行了 `curl` POST，留下 DRAFT 11000 占着设备 12，导致发布 11001 报「同一设备的使用时段不能重叠」。机理是同一条 `validatePublishInvariants`（`ReservationBatchServiceImpl.java:280`）里的重叠校验（`:307`–`:314`）**只排除 `CANCELLED`**（`:309` 的 `.ne(…, CANCELLED)`），`DRAFT`/`PUBLISHED`/`CLOSED` **全都会参与**，所以草稿批次同样会挡住同一设备的发布。清掉 DRAFT 后才发布成功。**教训：脚本崩在中途会留下半成品状态，而报错信息指向的方向（时段冲突）完全不是真因。**（此条我在记录初稿里曾写成"重叠检查没有状态过滤"，核对源码后订正为"只排除 CANCELLED"——**结论对、机理错**也是要改的，否则下一个人会照错的机理去推理。）

#### Review 发现的问题

1. **`views/batch/BatchDetailView.vue:3` 是一处死三元**：
   ```html
   <PageHeader :title="batch?.equipmentName ? `预约批次详情` : '预约批次详情'">
   ```
   两个分支**逐字相同**，条件无意义。**源头是我任务包里的文字**——我写了「标题在有设备名时显示…」，Codex 照字面把两个分支都填成了同一个字面量。属可读性缺陷、无功能影响（两分支同值），**待 Codex 额度恢复后返工**。这条也记入台账：任务包里描述性的措辞会被逐字实现成代码，写约束时应直接给断言式的目标（"标题恒为 X"），不要给条件式叙述。
2. 三条 `check:session` 的需登录码检查仍未跑（属 F2 范畴，非 F4 判据）。

**F4 未新增 `FRONTEND_BACKEND_GAP`**：本轮实测未发现需要后端配合的新缺口。既有的 D-13/G-F3-1（`/laboratory/list` 被 `DEFAULT_PAGE_SIZE=5` 钳死，实验室超过 5 个时筛选下拉会**静默少列**）在 F4 期间复查仍是**潜伏**状态——种子只有 4 个实验室，一页装得下，未触发。

#### 迄今仍未覆盖

- **死三元那条不在任何自动判据内**：仪器不检查"标题是否有意义"，它只检查页面是否渲染正确。修掉它会改 `BatchDetailView.vue`，**修完必须复跑仪器 2**（32 条）。
- **「简洁/现代/高校科研感/克制」的观感判断只能由人眼确认**，不在任何自动判据内（同 F3）。
- **fixture 依赖的四组判据（11/12/18/19/21前半）只有在库中有已发布批次时才能跑**，空库上它们会正确地变红。这不是缺陷，但复跑有顺序要求（见上）。
- **D-13 仍潜伏**（4 个实验室，未触发 5 条上限）。

---

### F5 验收实测记录（2026-10-07，Claude 独立实测）

**边界证明（我自己数的，不采信 Codex 自述）**：`git status --porcelain -uall -- frontend` 恰好 **9 项 = 5 修改 + 4 新增**（改：`package.json`、`scripts/verify-batch.mjs`、`router/routes.js`、`utils/query.js`、`views/batch/BatchDetailView.vue`；增：`api/reservation.js`、`views/batch/SubmitResult.vue`、`views/reservation/ReservationListView.vue`、`scripts/verify-reservation.mjs`）。

**其中 `views/reservation/ReservationListView.vue` 是 §9 表里划给 F6 的文件**——这是**经用户明确批准的 F5 边界扩大**，不是越界：判据⑤与⑥都依赖「我的预约」页面（⑤要引导过去、⑥要确认记录可见），而该页是 F6 的交付物。批准的范围**严格限定为最小只读列表**（4 列 + 分页 + 空态 + 越界页自愈），**状态筛选、详情、取消全部留给 F6**。F6 因此只需在此基础上做增量（筛选/详情/取消），不必重写。同类先例：F2 的 `AdminHomeView.vue` 占位页。

**后端零接触**：`git status --porcelain -- src pom.xml README.md` **为空**（该命令在写本记录之前跑；复跑时请把 `docs/` 排除，本文件自身的改动是记录写入，不是 Codex 的改动）。`package.json` 只多一行 `check:reservation`，依赖逐字节未变。

| 命令 | 结果 | 备注 |
|---|---|---|
| `node scripts/verify-reservation.mjs` | **23 PASS / 0 FAIL / exit 0** | 走**真 socket**路径（输出无降级 `INFO:` 行） |
| **浏览器仪器（F5，24 条断言）** | **24 PASS / 0 FAIL** | CDP 直连，覆盖提交/轮询/终态/列表/400 分支 |
| `npm run check:batch`（真机 token + OPEN fixture 11005） | **22 PASS / 0 FAIL / 0 SKIP** | A 13 + B 6 + **C 3 不再是 SKIP** |
| `npm run check:browse`（F3，学生 token） | **37 PASS / 0 FAIL** | F3 未回归 |
| `npm run build` | **exit 0**，258 ms | 仅既存大 chunk 警告 |
| 控制台噪音扫描（仪器走遍全部路径） | **非 favicon 噪音 0 条** | 见下「仪器自身的坑」第 3 条 |

#### §10 判据 ①–⑦ 的落点

| 判据 | 落点与实测证据 |
|---|---|
| ① 提交后按钮 loading 且不可重复点击 | 仪器组 2：双击（60ms 间隔）后 `2a` 第一次点击命中、`2b` 60ms 后 `disabled=true`、`2c` 第二次点击**被守卫拦下未产生交互**、`2d` 全程**恰好 1 次** `POST /reservation`。另有 verify-batch A 组静态锁 `submit availability comes only from BookingState actions` |
| ② ≤10s 内给终态 | 仪器 `4c` settled=true，`4d` 终态为「预约成功」、`4e` 进行中文案已消失。实测窗口仍是**几十毫秒级**，10s 是安全余量 |
| ③ 轮询遇 403 继续重试 | **本轮最强证据**：仪器捕获轮询状态序列 **`[403, 200]`**——即首次轮询真的吃到了 403（`无权查看该预约`，行尚未落库）后**继续轮询**，第二次才 200 终结。这把「denied 重试」从纸面断言变成了**观测到的事实**，同时反证 **「首次轮询必须立即发起」是承重设计**：若首次等待 500ms，序列只会剩 `[200]`，该分支成为永不可达的死代码 |
| ④ 400 直接展示后端中文 `errorMsg` | 仪器组 6：重复提交 → `6a` 实测 **400**、`6b` 面板显示「预约失败」、`6c` 原样透出 **「你已预约过该批次」**、`6d` 该分支**不提供重试按钮**（`action=none`） |
| ⑤ 503 两种文案分别提示 | **分类层验证**（未端到端挑起，理由见「迄今仍未覆盖」）：`verify-reservation.mjs` 的 11 例分类表覆盖 `预约失败，请重试 → action=retry`、`预约结果待确认，请稍后查询 → action=mine`，以及**未知 503 保守归入 mine**。代码级证据：三个抛出点在 `ReservationServiceImpl.java:114 / :311 / :328`，**全部在 `return reservationId` 之前** → 503 时前端拿不到 id，这正是判据⑤只能引导「我的预约」而不能轮询的根因（**D-4 由此从推测变为确证**） |
| ⑥ 成功后「我的预约」能看到该记录 | 仪器组 5：第 1 页渲染出记录行，首行为新预约（`ORDER BY r.id DESC`），设备名与状态列「已预约」均正确 |
| ⑦ `reservationId` 未被精度破坏 | 仪器 `3a` 后端 data 为**字符串且 18 位**、`3b` **轮询 URL 里的 id 与后端字符串逐字节一致**（数值化会变成 `…740`）、`3c` **终态面板展示的编号与后端 id 逐字节一致**（实测 `645888915823132677`）。三者串成完整精度链 |

#### 一个仪器自身的坑（值得单独记）

我的 CDP 仪器**第一版**用裸 `JSON.parse` 读 POST 响应体，把 18 位 id 读成 `645881622968664000`（超过 `2^53` 被静默截断），于是**把应用正确的字符串精度误判为缺陷**（3a/3b/3c 三条齐红）。换成与应用同一个 `json-bigint({storeAsString:true})` 后才对齐——**应用是对的，仪器错了**。这反过来证明 F1 那层 bigint 处理是承重的：任何绕过它的读取路径都会立刻暴露精度损失。另有两个仪器自身错误：主操作按钮文案是**「立即预约」**而非「提交预约」（导致首轮 18 条假红）；浏览器会把刻意挑起的 403/400 记成 `Failed to load resource` 控制台错误，处置是**只放行这两个已预期状态码**，其余任何状态码与任何非 resource-load 报错仍计噪音（不搞一刀切豁免）。

#### 证伪实验（7 个变异，复原后 `shasum -a 256 -c` 逐字节一致）

**第一轮：复核返工是否真落地（打在生产文件 `BatchDetailView.vue`，4/4 被抓）**

| 变异 | 改坏什么 | 变红断言 |
|---|---|---|
| M1 | 从 `:disabled` 表达式里**删掉** `submitting` | `submit availability comes only from BookingState actions` |
| M2 | 往 `:disabled` 表达式里**再加**第三个条件 | 同上（锚定 `$` + token 集合 `deepEqual` 两个方向都拦） |
| M3 | 在视图里**重新抄回** 503 文案常量 | `503 messages have one exported API source of truth` |
| M4 | **删掉** `if (!result.data)` 分支 | `an ok polling result without data uses the pending exit` |

**第二轮：复核终态编号（打在 `SubmitResult.vue`，3/3 被抓）**

| 变异 | 改坏什么 | 变红断言 |
|---|---|---|
| M5 | 编号**写死成常量字符串** | `done result keeps the exact reservation id visible` |
| M6 | 把编号项**从首位挪到设备之后** | 同上 |
| M7 | 编号**被数值化** `{{ Number(reservationId) }}` | 同上 |

M7 是这轮最该测的一条：它证明「禁止数值化」这条精度约束**真的被断言锁住**，而不是只写在注释里的口号。

#### Review 发现的问题（两轮，均已返工并由上述变异复核）

1. **R1 · 503 文案有两个来源**（`api/reservation.js` 与 `BatchDetailView.vue` 各定义一份）：视图那份管生产、仪器那份管测试，**漂移时两边永远一致地错**。已收敛为视图侧完全删除、由 API 层单点导出。
2. **R2 · `ok` 但 `data` 为空时落进死路**：面板无文案无按钮。已改走 `finishAsPending(sequence)`，与 `pending` 相合流。
3. **R3 · `Number(route.params.id)` 语义易被误读**为预约 id。已就地加注释说明此处数值化的是 `batchId`（AUTO 小整数），`reservationId` 必须全程保持字符串。
4. **R5 · 三条 F4 断言被合法失效后需重写**：F5 新增 `api/reservation.js` 确实让 `/reservation` 字面量从 1 处变 2 处，但**不能因此把断言删弱**。已重写为：`submitReservation` 形状正则 + `/reservation` 字面量**恰为 1 处** + 全仓禁止 `/reservation/manage/page` 与 `/reservation/{id}/(request|cancel|verify)` + **`/admin` 子路由仍仅 `admin-home`**（F7 边界未被 F5 侵蚀）。
5. **R6 · 预约编号在任何时刻都看不见**：`accepted|polling` 相显示编号但在屏仅 46–114ms，`done` 相不显示，`ReservationListView` 的 4 列里也没有 → 用户**全程看不到自己的编号**。经用户裁定**并入 F5 补上**：`done` 面板首位新增 `预约编号` 行，绑定原样字符串。附带收益是判据⑦从"只存在于百毫秒窗口"变为**终态可确定性核验**（仪器 `3c`）。

#### 迄今仍未覆盖

- **503 未端到端挑起**，只在分类层验证。理由：`:311` 需要「消息**确定未投递**」、`:328` 需要「投递结果**不确定**」，二者都要求对 MQ 制造特定故障态，无法在不破坏本机验证环境的前提下确定性复现；§10 F5 的人工测试流程本身也只要求「观察结果面板」，未要求挑起 503。**这是覆盖边界，不是已通过。**
- **「名额已约满」400 未端到端复现**。它是并发竞态下的补偿路径：剩余名额为 0 时 `bookingState` 已使按钮禁用，正常 UI 路径到不了。不过它与「你已预约过该批次」**共用同一条 400 处理路径**（后端文案透传），后者已由仪器组 6 端到端验证。
- **`check:batch` 的 C 组只接受 OPEN 前提**：我误传 NOT_STARTED 的 11006 时它如实报 `'NOT_STARTED' !== 'OPEN'`。这是**仪器在拒绝错误前置条件**，同时反证 C 组断言不是空转的（它真去后端取 `bookingState` 比对）。
- **fixture 不能连跑同一设备**：后端「同一设备的使用时段不能重叠」会正确拒绝（`ReservationBatchServiceImpl` 的重叠校验只排除 `CANCELLED`，`DRAFT`/`PUBLISHED`/`CLOSED` 全都参与——同 F4 记录第 9 条）。故 F5 的 fixture 脚本把**使用时段随机推到 30~330 天后**（预约开放窗口仍覆盖当前时刻以保 `OPEN`），使脚本可重复运行。
- **浏览器仪器仍是 `/tmp` 下的临时件、不入库**（同 F2/F3/F4 惯例）：`/tmp/f5-cdp-verify.mjs`（24 断言，零依赖 CDP）+ `/tmp/f5-fixture.sh`（可重复运行的 OPEN 批次 fixture）。
- **观感判断只能人眼确认**（同 F3/F4）；**D-2**（`mine` 查询参数与 `adminUserId`，影响 F7）与 **D-13**（`/laboratory/list` 被钳 5 条，仍潜伏）继续挂账。

---

### F6 验收实测记录（2026-10-07，Claude 独立实测）

**边界证明（我自己数的，不采信 Codex 自述）**：`git status --porcelain` 恰好 **7 项 = 6 修改 + 1 新增**（改：`scripts/verify-batch.mjs`、`scripts/verify-reservation.mjs`、`src/api/reservation.js`、`src/router/routes.js`、`src/utils/query.js`、`src/views/reservation/ReservationListView.vue`；增：`src/views/reservation/ReservationDetailView.vue`）。F6 在 §9 划定的 `views/reservation/*`（学生侧）范围内，**未越界到管理端**。

**后端零接触**：`git status --porcelain` 里没有任何后端路径。`ReservationDetailView.vue` 复用 F5 已有的 `api/reservation.js`，只新增一个 `cancelReservation(id)`——**没有为前端方便新增或改写任何后端接口**。

| 命令 | 结果 | 备注 |
|---|---|---|
| `node scripts/verify-reservation.mjs` | **32 PASS / 0 FAIL / exit 0** | F5 原 23 条**一条未删未改**，新增 9 条（8 条 F6 + 1 条 Review 返工） |
| **浏览器仪器（F6，20 条断言）** | **20 PASS / 0 FAIL** | CDP 直连，覆盖筛选/详情/取消/刷新持久化 |
| `npm run check:batch` | **A 组 13 PASS / 0 FAIL / 0 SKIP**；总计 13 PASS / 0 FAIL / 9 SKIP | A 组条数与 F5 持平，**无断言丢失**；B/C 因本轮未提供 `VERIFY_TOKEN` 跳过 |
| `npm run check:request` | **22 PASS / 0 FAIL** | 含 `statusMap exactly matches the approved labels, tags, and actions`——F6 未偷改状态表 |
| `npm run check:session` | **24 PASS / 0 FAIL** | 未回归 |
| `npm run check:browse` | **19 PASS / 0 FAIL** | 未回归 |
| `npm run build` | **exit 0**，283 ms | 仅既存大 chunk 警告 |
| 控制台噪音扫描（仪器走遍全部路径） | **非 favicon 噪音 0 条** | 本轮无刻意挑起的错误码，**无需任何豁免** |

#### §10 判据 ①–⑤ 的落点

| 判据 | 落点与实测证据 |
|---|---|
| ① 列表只出现**自己**的预约 | 这是**后端保证**（`queryMyPage` 从 token 取 userId），前端**没有任何参数可以传别人的 id**，故前端侧不存在越权路径。前端证据：仪器 `1d`（筛选「已预约」后 4 行状态列全为「已预约」）、`4b`（筛选「已取消」查到该记录且全为该状态） |
| ② 详情页字段与 `GET /reservation/{id}` 一致 | 仪器 `2c` 实测渲染出 **8 个字段**：预约编号/设备/所属实验室/状态/使用时段/预约开放窗口/创建时间/更新时间（`设备=超速离心机`、`所属实验室=计算与样品制备中心`、`状态=已预约`）。静态锁：`verify-reservation.mjs` 的 10 字段循环断言（含 `startTime`/`endTime`/`bookingStartTime`/`bookingEndTime`/`createTime`/`updateTime`）。注：未展示 `userId`/`quotaReleased`（内部字段）与 `batchId`（VO 无 `equipmentId`，且管理端详情见 **D-5**） |
| ③ 取消前二次确认，取消后**重新拉取**并显示 CANCELLED | 仪器 `3b` **确认前 POST 数为 0**（先弹窗、未提前发请求）→ `3c` 确认后**恰好 1 次** `POST /reservation/{id}/cancel` → `3d` **重新拉取** `GET /reservation/{id}` → `3e` 终态显示「已取消」→ `4a` **刷新页面仍是「已取消」**（状态真已持久化，非本地乐观更新）。仪器 `2a/2b` 另证 18 位 id 进 URL 与上屏均**逐字节未数值化** |
| ④ 对已取消记录再取消 → 200 幂等，页面**通过重取**显示已取消，**不得显示「取消成功」** | **API 级实测**：对已取消记录再 `POST …/cancel` → **HTTP 200 `{"success":true}`**，复查仍 `CANCELLED` 且 `quotaReleased=True`（名额**未被二次归还**）。这正是判据④的机理根据：`TRANSITIONED` 与 `ALREADY_CANCELLED` 的 200 **响应体完全同形、不可区分**，所以 200 不能作为"本次发生迁移"的凭据。UI 级：仪器 `3f` 以 **60ms 间隔全程采样**，**从未出现「取消成功」**，同时 `3e` **正向**确认显示「已取消」（否定式断言与正向前提同 check 合取，不会在坏页上空转）。静态锁三条：`doesNotMatch(/取消成功/)`、`doesNotMatch(/\bstatus\s*===/)`、`actions.includes('cancel')` |
| ⑤ 越权访问他人预约 id → 403 且有 `errorMsg` | **API 级实测**（§10 人工流程要求的手工请求）：用**另一个账号**（管理员）的 token —— `GET /reservation/{id}` → **403 `无权查看该预约`**；`POST …/cancel` → **403 `无权取消该预约`**，两者均带 `errorMsg`。前端侧：`denied` 通道 → `errorMessage.value = result.message` → `EmptyState` **原样展示后端文案**。注意后端对「不存在」与「不是你的」返回**同一个** 403（无 404），故详情页**不得**自造"该预约不存在"这类文案 |

#### 仪器自身的坑（第 6 个，同类于 F5 记录的第 3 条）

我的 CDP 仪器**第一版**把 `设备`/`状态` 判为"未渲染"（`2c` 红）。但提取到的字段是 `{预约编号, 所属实验室, 使用时段, 创建时间}`——**恰好是每个 `<tr>` 的第 1 对**。根因：`el-descriptions :column="2"` 让**每个 `<tr>` 容纳两对** label/content，而我的提取器每行只取第一个 `querySelector`，于是 4 个 `tr` 各丢一对，正好丢掉 设备/状态/预约开放窗口/更新时间。改成按下标配对遍历后 **20 PASS / 0 FAIL**。**又一次：应用是对的，仪器错了。**

#### 证伪实验（10 个变异，复原后逐字节一致）

**第一轮：9 个变异，打在生产文件上（`ReservationDetailView.vue` / `ReservationListView.vue` / `query.js` / `routes.js`），9/9 被抓**

| 变异 | 改坏什么 | 变红断言 |
|---|---|---|
| N1 | `canCancel` 改用 `status === 'RESERVED'` 字面量 | `reservation cancellation is action-driven and always refetches after 2xx` |
| N2 | 2xx 后**不重取**、改弹「取消成功」 | 同上 |
| N3 | 详情链接 `Number(row.id)` | `reservation list detail link preserves the row id string` + `reservation ids are never numerically coerced in F6 views` |
| N4 | builder 内联 `ENUM_VALUES` 绕开单点来源 | `reservation status whitelist is the exact enum source of truth` |
| N5 | 详情路由**偷偷加上 `menu`** | `reservation route registers a menu-less detail page` |
| N6 | 详情页**丢弃 `updateTime`** | `reservation detail renders all readable VO fields and guarded empty states` |
| N8 | **删掉 `isNumericId` 前置守卫** | 同上 |
| N9 | 在视图里**再抄一份** `/reservation/{id}/cancel` | `[A] reservation submit API is exact and later-phase calls remain absent` |
| N10 | **新增第三个 reservation 视图**（模拟 F7/F9 越界） | 同上 |

N2 是这轮最该测的一条：它证明「200 不得当成功凭据」**真的被断言锁住**，而不是只写在注释里的口号。N9/N10 证明收紧后的边界锁有实效——**若照 F5 原样保留禁止集，N9 会漏网**。

**第二轮：1 个变异，打在生产文件上，1/1 被抓**（复核 Review 返工是否真落地）

| 变异 | 改坏什么 | 变红断言 |
|---|---|---|
| N11 | 取消失败透传改成硬编码 `ElMessage.error('取消失败')` | `reservation cancel failure surfaces the backend message verbatim` |

复原一律用 `cp` 快照 + `shasum -a 256` 校验（**不用 `git checkout`**——本次 F6 改动全部未暂存，从索引恢复会把整个 F6 抹掉）。

#### 边界改法：F6 **合法击穿** F5 两条越界防护，收紧而非删除

F6 需要 `POST /reservation/{id}/cancel` 与一个新的 reservation 视图，恰好命中 F5 立下的两条越界防护。**这类"后续 Phase 合法失效既有断言"的处理方式，是 F5 的 R5 教训的直接复用**：

1. 禁止集 `/reservation/{id}/(request|cancel|verify)` **缩窄为** `(request|verify)`——`cancel` 归 F6 合法范围，`verify` 留给 F9、`request` 是 **D-3** 未批准项。
2. **新增收紧**：`/reservation/{id}/cancel` 在全仓**恰好出现 1 处**且只在 `api/reservation.js`（单点来源锁，防视图里再抄一份——即 N9）。
3. `reservationNamedViews` 从 1 个文件改为**恰为 2 个**（排序后精确集合相等），**没有退化成"包含"**——否则 F7/F9 悄悄加管理端预约视图时不会被发现（即 N10）。

#### Review 发现的问题（1 项，已返工并由 N11 复核）

1. **R1 · 取消失败的文案透传无任何覆盖**：`ElMessage.error(result.message)` 既无静态断言、**也在全新环境里不可达**（理由见下"迄今仍未覆盖"），把 `result.message` 换成硬编码 `'取消失败'` **不会有任何东西变红**，后端那条 400 文案会静默丢失。已新增一条 check，**正向锁 `ElMessage.error(result.message)` + 同一 check 内负向排除字符串字面量**（否定式必须与正向前提合取，否则在坏页上空转）。

#### 迄今仍未覆盖

- **取消路径的两条 400 在全新环境里时间上不可达**，只做静态锁。这**不是测试没做，而是后端窗口不变量的必然结果**：实测 `publish` 报 `预约截止时间不能晚于使用开始时间`，即 `bookingEndTime ≤ startTime ≤ endTime`；而能**提交**预约又要求 `now < bookingEndTime`，故预约成功那一刻必有 `now < startTime`（→ 管理员核验必回 `预约尚未开始，无法核验`，已实测 400）且 `now < endTime`。**所以 `当前状态不可取消`（COMPLETED/NO_SHOW）与 `预约已结束，无法取消` 只能靠真实时间流逝或直接改库触发。**
- **判据①是后端保证**，前端无从越权（没有传别人 userId 的参数），故只有"筛选结果与状态自洽"这类间接证据。
- **`check:batch` 的 B/C 组本轮 SKIP**（未提供 `VERIFY_TOKEN`）。静态 A 组已全绿；F5 记录里的 B/C（真机 token + OPEN fixture）仍是最近一次真机结论。**这是本轮的覆盖边界，不是已通过。**
- **浏览器仪器与 fixture 仍是 `/tmp` 下的临时件、不入库**（同 F2/F3/F4/F5 惯例）：`/tmp/f6-cdp-verify.mjs`（20 断言，零依赖 CDP）+ 复用 `/tmp/f5-fixture.sh`。
- **观感判断只能人眼确认**（同 F3/F4/F5）；**D-2**（`mine` 查询参数与 `adminUserId`，影响 F7）、**D-5**（管理员无法查看单个预约详情——学生侧详情页已证明用 `GET /reservation/{id}` 足够，管理端 F9 建议同样走行内抽屉而非新增端点）、**D-13**（`/laboratory/list` 被钳 5 条）继续挂账。

---

### F7 验收实测记录（2026-10-07，Claude 独立实测）

**边界证明（我自己数的，不采信 Codex 自述）**：`git status --porcelain` 共 11 项，其中 **7 修改 + 3 新增 = 10 项属 F7**（改：`docs/frontend/FRONTEND-DESIGN.md`、`frontend/package.json`、`frontend/scripts/verify-batch.mjs`、`frontend/src/api/equipment.js`、`frontend/src/api/laboratory.js`、`frontend/src/router/routes.js`、`frontend/src/views/admin/AdminHomeView.vue`；增：`frontend/scripts/verify-admin.mjs`、`frontend/src/views/admin/equipment/`、`frontend/src/views/admin/laboratory/`）。第 11 项 `docs/study/` **与 F7 无关、且是凭据地雷**，提交时必须逐条显式列路径排除（**绝不能用 `git add -A`**）。

**后端零接触**：`git status --porcelain` 中无任何后端路径（无 `src/main`、无 `.java`、无 `pom.xml`）。判据②③⑤所依赖的"后端已具备的能力"**全部是既有实现**——F7 只新增/补齐了前端侧的调用与 UI。

| 命令 | 结果 | 备注 |
|---|---|---|
| `node scripts/verify-admin.mjs`（带真实后端） | **A 11 PASS + B 8 PASS / 0 FAIL / 0 SKIP（合计 19）** | F7 新增仪器 |
| **浏览器仪器（F7，19 断言）** | **19 PASS / 0 FAIL / 0 崩溃** | CDP 直连 5176，六段全跑完 |
| `npm run check:batch` | **A 组 13 PASS / 0 FAIL** | A 组条数与 F6 持平，**无断言丢失**（被击穿那条为"收紧"而非删除，见下） |
| `npm run check:browse` | **19 PASS / 0 FAIL** | 未回归 |
| `npm run check:reservation` | **32 PASS / 0 FAIL** | 未回归 |
| `npm run check:request` | **22 PASS / 0 FAIL** | F7 未偷改状态表 |
| `npm run check:session` | **24 PASS / 0 FAIL** | 未回归 |
| `npm run build` | **exit 0**，269 ms | 两个新页面各自懒加载分包；仅既存大 chunk 警告 |
| 控制台噪音扫描 | **非 favicon 噪音 0 条** | 无需任何豁免 |
| DB 复核 | **4 实验室 / 12 设备 / 0 孤儿 / 0 残留夹具** | 设备 9 状态已复原为 `AVAILABLE` |

#### §10 判据 ①–⑥ 的落点

| 判据 | 落点与实测证据 |
|---|---|
| ① 管理端菜单由角色驱动 | 仪器 `1a`：LAB_ADMIN 菜单**恰好**是 `["管理首页","我的实验室","我的设备"]`（集合并列相等，不是"包含"）。路由侧：`/admin` 带 `meta.roles: ['LAB_ADMIN']`，两条新子路由**不带 `meta.roles`**、继承父级；`AdminLayout` 菜单由 `router.getRoutes()` 扫描 `meta.menu` 生成，故**加路由即自动加菜单**，未改 Layout。后端侧：USER 身份对 6 个管理端写接口（`POST/PUT/DELETE /laboratory`、`POST /equipment`、`PUT /equipment/{id}/status`、`DELETE /equipment/{id}`）**全部 403**——前端权限只是 UI 控制，真边界是后端 `RoleInterceptor` |
| ② 管理员只能改自己负责的实验室 | **这条前端验不了，必须在 DB 侧验，如实登记。** 根因是 **D-2**：`LaboratoryVO` 不含 `adminUserId`，前端**没有任何字段**可判归属。DB 侧直接证据：token 持有者 = `id=1016, role=LAB_ADMIN`，`tb_laboratory.admin_user_id` 显示其实验室为 `1,2`（另 `3,4` 属 `1019`）。**间接证据（仪器 B2）**：新建实验室后**立即 PUT 成功** ⇒ 已通过 `assertManageable`；**B5**：改实验室 4 的设备状态 → 403 `无权管理该实验室`（仪器 `3b` 复现同一文案） |
| ③ 删除仍有设备的实验室被拒 + 展示后端文案 | **仪器 `2d` 端到端实测**：点「删除」→ 二次确认 → 页面展示 **`该实验室下仍有 3 台设备，请先转移或删除设备`**（后端 `BadRequestException`）。`2e`：删除被拒后**重新拉取**，前后均 4 行、该行仍在（**无本地乐观删除**）。`2f`：**未出现「已删除」**。仪器 B4：`DELETE /laboratory/1` → 400 且响应体含「台设备」。静态锁三条：删除路径必须含 `await loadLaboratories()`、必须含 `showError(result)`、**不得**出现 `records.value.filter` |
| ④ 设备状态可切、可逆 | **仪器 `4a/4b/4c` 用真实按钮走完一个来回**：设备 9「X 射线衍射仪」（实验室 1，本人）`可用 → 点「设为维护中」→ 重取后状态列「维护中」→ 点「设为可用」→ 重取后「可用」`。**DB 复核该设备最终为 `AVAILABLE`，环境已复原。** 仪器 B6 另在自建实验室里跑完 `建实验室→建设备→切状态→抓取→清理` 全链 |
| ⑤ 越权改他室设备 → 403 + 展示文案 | **仪器 `3a/3b/3c/3d`**：他室设备（超速离心机，实验室 4）**照常出现在列表**（D-2 裁定：前端不做归属过滤）→ 点「设为维护中」→ 展示后端 **`无权管理该实验室`** → 重取后该行状态**未变**且行仍在 → **未出现「状态已更新」**。仪器 B5/B8 复现同一 403（后者为 `laboratoryId=999999` 建设备） |
| ⑥ 前端不可能发出非法状态值 | **结构性成立，且有锁**：动作按钮由 `resolveStatus('EquipmentStatus', row.status).actions` 驱动（`AVAILABLE: [maintain,disable]`／`MAINTENANCE: [enable,disable]`／`DISABLED: [enable]`），目标是**静态映射表** `EQUIPMENT_STATUS_ACTION_TARGETS` 的查表结果。仪器 `5a`：表格里出现过的动作文案 `["设为维护中","停用","设为可用"]` **全部在固定集合内**；`5b`：设备表单状态是**受控下拉**（与实验室、分类合计 3 个 select），非自由输入。**注意这条本来是"看起来锁住了"**——见下方 M8 |

#### 仪器自身的坑（第 7、8 个）

1. **第 7 个 · 用组件目录名当路由路径，让仪器错误伪装成应用缺陷。** 仪器初版把实验室页写成 `/admin/laboratory`（组件目录名），而路由 `path` 是 **`laboratories`（复数）**。该 URL 命中 404 页，而 404 页**既不报控制台错误、`.el-table` 也只是数量为 0**，于是产出两条看起来完全像应用缺陷的 FAIL（`1b 表格就绪=false`、`2a 行数=0`）。**实际应用从头到尾是对的**——`/admin/laboratories` 实测 4 行、列正确、编辑/删除按钮齐全。已修：改正 URL，并在 `navigate()` 里加 **404 断言**（body 含「页面不存在」即立刻抛"仪器错误"，不再让拼错 URL 静默降级为一条 FAIL）。
2. **第 8 个 · 仪器崩溃会连带丢掉它之后跑出的全部证据。** 上面那次失败后脚本在取"首行操作按钮"时因 `document.querySelector(...)` 返回 `null` 而抛 `TypeError`，**直接终止进程**——段 3–6 的 12 条断言**一条都没跑**（不是"失败"，是"没跑"，两者在报告里长得一样）。已修两处：无行时返回空数组让断言自己失败；并给六个段各套一层 `guard()`，**段内异常只让该段变红，不再吞掉下游证据**。

#### 证伪实验（9 个变异，复原后逐字节一致）

**打在生产文件上**（`AdminLaboratoryListView.vue` / `AdminEquipmentListView.vue` / `api/equipment.js` / `routes.js`），复原用 `cp` 快照 + `sha256` 校验（**不用 `git checkout`**——F7 改动全部未暂存，从索引恢复会整段抹掉）。

| 变异 | 改坏什么 | 期望 | 实际 | 变红断言 |
|---|---|---|---|---|
| M1 | 映射表目标改成枚举外的 `BOGUS` | FAIL | **CAUGHT** | 判据⑥ `equipment actions map only to valid enum targets` |
| M2 | 状态切换后**不重取**（乐观更新） | FAIL | **CAUGHT** | `equipment status changes always refetch` |
| M3 | 页面里写 `role` 字面量判等 | FAIL | **CAUGHT** | 判据① `admin views never consult the role directly` |
| M4 | `status` 走 `data` 不走 `params` | FAIL | **CAUGHT** | `equipment status API uses a query parameter` |
| M5 | 删除后本地 `filter` 不重取 | FAIL | **CAUGHT** | 重取锁（反向） |
| M6 | 摘掉 `/admin` 的 `meta.roles` | FAIL | **CAUGHT** | 判据① + `verify-batch` |
| M7 | 新增 `views/admin/batch/` 文件（模拟 F8 越界） | FAIL | **CAUGHT** | F8 边界锁 + `verify-batch` |
| M8 | 绕过映射表、把目标**写死**成 `'MAINTENANCE'` | FAIL | ~~SURVIVED~~ → **CAUGHT** | 首轮**全绿通过**，返工后新增加锁（见 R2） |
| M9 | 摘掉 D-13 的"当前行实验室并入下拉"兜底 | PASS | **SURVIVED** | **无断言——记录在案的已知缺口**，不上锁 |

**M3 首轮是"我的变体写错了"，不是锁失效。** 我注入的是 `route_placeholder === 'LAB_ADMIN'`，左边名字**不含 `role`**，所以 A7 的正则 `/role\s*(?:===|!==)/` 合法地没匹配。若就此收手会**误判 A7 失效**。改用三种真实形态（`userStore.profile?.role === 'LAB_ADMIN'`、`roles.includes('LAB_ADMIN')`、`row.role !== 'LAB_ADMIN'`）重测，**全部 CAUGHT**。

**M8 是这轮最有价值的一条**：它证明判据⑥ **并非结构性成立**——把查表结果换成字面量，原本所有断言全绿。"动作集合受控"在**按钮文案**层确实受控，但**发给后端的值**可以绕过映射表。这正是"写在注释里的口号"与"真被断言锁住"的差别。

#### Review 发现的问题（3 项，已一次性批量返工）

1. **R1 · 判据②当时零真机覆盖，且在真实库里留了脏数据。** B1 断言"新建返回 16–18 位字符串 id"——**这个前提是我在 Packet 里给错的**：`Laboratory.id`/`Equipment.id` 都是 `@TableId(type = IdType.AUTO)` **MySQL 自增**（实测返回 `9348`、`9355`、`9356`），**不走 `RedisIdWorker`**，我把预约 id 的 18 位前提误套到了实验室/设备上。后果有三：B1 FAIL；B2/B3 被连带 SKIP（**判据②当时零真机覆盖**）；断言在 `createdLaboratoryId` 赋值**之前**抛出 → 清理没跑 → **在真实库留下一个孤儿实验室**。已修：id 紧跟成功之后赋值、接受 number 或纯数字字符串的正整数、B1–B3 整体套 `try/finally` + `cleanupCreatedLaboratoryFixture`。我另手工删除了残留的 `9348` 并核验 DB 回到 4 实验室 / 0 残留。
2. **R2 · M8 暴露的锁缺口**（见上）：新增断言强制 `handleStatusChange` 体内**必须含 `EQUIPMENT_STATUS_ACTION_TARGETS[action]`** 且**不得含** `AVAILABLE`/`MAINTENANCE`/`DISABLED` 三个字面量。返工后重跑变异，**M8 已 CAUGHT**。
3. **R3 · 列表加载失败文案展示两遍**（toast + 内联 `EmptyState`），与学生端 `EquipmentListView.vue` 的"只内联"不一致。已改：**加载路径去掉 toast、写路径保留**；并加**双向静态锁**（加载路径不得含 `showError(`、`handleSave`/`handleDelete`/`handleStatusChange` 必须含）防回退。

**Codex 本轮未出现"改测试迁就实现"**——恰恰相反，`verify-admin.mjs` 的白名单断言比首版**更严**，还新增了 `meta.roles` 断言。

#### 边界改法：F7 **合法击穿** F5 立下的一条越界防护，收紧而非删除

`verify-batch.mjs` 原有一条断言 **`admin routes and views remain outside this phase`**（"admin 目录不许有改动"）。F7 的整个交付面就是 admin 目录，这条被**合法击穿**。处理方式是 **F5 R5 / F6 教训的直接复用——收紧而不是删除**：

1. 原断言的**意图**是"后续 Phase 的管理端域尚未进入"，不是"admin 目录永远不许动"。故从"目录不许有改动"收紧为 **"目录里不许出现后续 Phase 的域"**：`assert.doesNotMatch(file, /batch|reservation|dlq/i)`。
2. 子路由改为**精确集合并列相等**（`[['','admin-home'],['equipment','admin-equipment-list'],['laboratories','admin-laboratory-list']]`），**没有退化成"非空即可"**——否则 F8 加批次管理、F9 加预约记录时不会被发现。**F8/F9/F10 加路由时必须显式更新这里。**
3. **A 组条数维持 13**：一条换一条，无净丢失（M6/M7 证明收紧后仍有实效）。

#### 迄今仍未覆盖

- **判据②只有间接证据**：`LaboratoryVO` 不含 `adminUserId`（**D-2**），前端**原理上无法**判断归属。直接证据在 DB 侧（`admin_user_id=1016`），已如实登记为"前端验不了、我在 DB 侧验了"。**这是覆盖边界，不是已通过。**
- **M9 已知缺口**：D-13 兜底（编辑设备时把当前行的实验室并入下拉）**无任何断言**，摘掉它仪器全绿。不上锁的理由是它属"体验兜底而非正确性"，但**这是记录在案的缺口**。
- **D-13 仍在**：`/laboratory/list` 被后端钳每页 5 条，实验室超过 5 个时建设备的下拉会**静默少列**。F7 的"∪ 当前行"只兜住了**编辑**路径，**新建**路径仍受限。
- **仪器 `4c` 的提示采集为空**（`提示=""`）：ElMessage 去重窗口把上一条「状态已更新」也滤掉了。**该条断言的是状态列而非提示**，故不影响结论；但提示采集在"连续两次同类操作"下不可靠，是仪器的已知弱点。
- **浏览器仪器与判别脚本仍是 `/tmp` 下的临时件、不入库**（同 F2–F6 惯例）：`/tmp/f7-cdp-verify.mjs`（19 断言，零依赖 CDP）+ `/tmp/f7-mutation-check.py`（9 变异）。
- **观感判断只能人眼确认**（同 F3–F6）；**D-2**（已裁定 F7 不动后端、按判据⑤走）、**D-5**（管理员看单个预约详情，F9 建议行内抽屉）、**D-13**（见上）继续挂账。

---

### F8 验收实测记录（2026-10-07，Claude 独立实测）

**边界证明（我自己数的，不采信 Codex 自述）**：`git status --porcelain` 共 8 项，其中 **7 项属 F8**（改 6：`docs/frontend/FRONTEND-DESIGN.md`、`frontend/scripts/verify-admin.mjs`、`frontend/scripts/verify-batch.mjs`、`frontend/src/api/reservationBatch.js`、`frontend/src/router/routes.js`、`frontend/src/utils/query.js`；增 1：`frontend/src/views/admin/batch/`）。第 8 项 `docs/study/` **与 F8 无关、且是凭据地雷**，提交时必须逐条显式列路径排除（**绝不能用 `git add -A`**）。

**后端零接触**：`git status --porcelain` 中无任何后端路径（无 `src/main`、无 `.java`、无 `pom.xml`）。判据①–⑥ 所依赖的"后端已具备的能力"**全部是既有实现**——F8 只新增了前端侧的调用与 UI，没有为了前端方便改任何后端接口。`frontend/src/constants/`（含 `statusMap.js` 的 `ReservationBatchStatus.actions`）**也是零改动**：F8 是该表的**第一个消费者**。

| 命令 | 结果 | 备注 |
|---|---|---|
| `node scripts/verify-admin.mjs`（带真实后端） | **A 20 PASS + B 24 PASS / 0 FAIL / 0 SKIP（合计 44）** | **连跑两次均 44/44**，幂等成立 |
| **浏览器仪器（F8，32 断言）** | **32 PASS / 0 FAIL / 0 崩溃** | CDP 直连，五段全跑完 |
| `npm run check:batch`（带真实后端 + C 组夹具 11011） | **A 13 + B 6 + C 3 = 22 PASS / 0 FAIL / 0 SKIP** | C 组此前一直 SKIP，本次用现成的 PUBLISHED+OPEN 批次补齐 |
| `npm run check:browse` | **37 PASS / 0 FAIL** | 未回归 |
| `npm run check:reservation` | **32 PASS / 0 FAIL** | 未回归 |
| `npm run check:session` | **24 PASS / 0 FAIL** | 另 3 条需 `VERIFY_CODE`，未跑 |
| `npm run check:request` | **26 PASS / 0 FAIL** | **本机无 Codex 沙箱的 `listen EPERM`**（沙箱禁绑 127.0.0.1，故该组在 Codex 侧永远是失败项） |
| `npm run build` | **exit 0** | 生成独立 `AdminBatchListView-*.js` 懒加载分包（10.19 kB），路由级切分生效；仅既存大 chunk 警告 |
| DB 复核 | **基线 15 行 / max_id 11015 / 0 残留夹具** | 我建的 41 行残留已核验零引用后清除 |

#### §10 判据 ①–⑥ 的落点

| 判据 | 落点与实测证据 |
|---|---|
| ① 新建批次后为 `DRAFT` | 仪器建批次后**未发布**即重取列表，状态列显示 `草稿`；仪器 B 组 `created draft is owned by the admin and absent from the public page` 另证 **DRAFT 不出现在公开页**（`publicOnly` 只放 PUBLISHED/CLOSED）——两条一起证明"草稿是草稿、且学生看不见" |
| ② 草稿可编辑、非草稿编辑被拒 | 仪器 `2b/2c`：草稿行动作集**恰好** `编辑/发布/取消`；点「编辑」对话框带出原值并可保存。发布后同一行动作集变为 **`关闭/取消`（无编辑）**——**按钮层面就不可达**。后端侧：B 组 `published batch rejects further edits with the exact backend message` 绕过 UI 直接 `PUT /reservation-batch` → 400 **`仅草稿批次可修改`**。第三条独立证据：仪器确认**表单里没有跨字段校验**，非草稿的非法值能一路发到后端（见判据④） |
| ③ 发布成功后 `PUBLISHED` 且剩余名额=总名额 | B 组 `draft total quota can be updated`（改草稿名额 5→6 成功）→ `publishing initializes remaining quota and status from the saved draft`：发布后重取 `status=PUBLISHED`、`totalQuota=6`、**`remainingQuota=6`**（等于总名额，不是沿用旧值）。这条的**前提是夹具窗口没被既有批次挡住**——见下方 R2 |
| ④ 四条发布校验逐条可触发并展示对应中文 | **仪器做法：先证明"前端不拦截"，再证明"后端文案经 UI 透传"**——四条夹具**都能被前端建成草稿**（表单只有 `required`，无跨字段校验），点「发布」后页面逐条展示后端原文：**`同一设备的使用时段不能重叠`**（时段重叠）／**`预约截止时间不能晚于使用开始时间`**（预约截止晚于使用开始）／**`设备当前不可预约`**（设备 2 处于维护中）／**`总名额必须大于等于1`**（名额 0）。B 组另用 HTTP 直连对同样四条各断言一次**精确文案**（不经过 UI），两层互为对照 |
| ⑤ 关闭后批次不可再被预约 | **两层证据。** 后端闸门矩阵（零副作用，`tb_reservation` 前后均 6 行）：`DRAFT`→`批次当前不可预约`；`CANCELLED`→`批次当前不可预约`；**`CLOSED`→`批次当前不可预约`**；`999999`→`预约批次不存在`。**阳性对照**：`PUBLISHED 但已截止`→`预约已截止`、`PUBLISHED 但未开始`→`预约尚未开始`——这两条**穿过了状态闸门**，证明闸门是按状态判别而非"非 PUBLISHED 一律拒"。机理：`ReservationServiceImpl:92` 的 `status != PUBLISHED` 判断位于 `:108` 的 `redisIdWorker.nextId()` **之前**，故一切状态拒绝都零副作用。UI 侧：仪器 `3c` 关闭后该行动作集**为空集**，且`关闭`**不回写名额列**（仍显示 `5 / 5`）——与后端"只清 Redis 键、不回写 MySQL"的实现一致 |
| ⑥ 表单提交的日期字符串带 `T` | **最强证据是浏览器实际发出的请求体**（CDP `Network.requestWillBeSent.postData`）：`POST /api/reservation-batch`，`{"equipmentId":"1","startTime":"2027-06-01T10:00:00","endTime":"2027-06-01T12:00:00","bookingStartTime":"2026-10-08T09:00:00","bookingEndTime":"2027-05-31T09:00:00","totalQuota":5}` —— **四个日期字段全部带 `T`**。静态锁：四个 `el-date-picker` 必须逐个含 `value-format="YYYY-MM-DD[T]HH:mm:ss"`（**逐个**断言，缺一个就红——变异 M3 证明）。反向对照：B 组 `space-separated batch datetimes are rejected without creating a fixture` 把同一载荷的 `T` 换成空格 → 400 **`请求体格式错误`**，且**夹具总数不变**（先证零副作用）；立刻再用带 `T` 的同一载荷（删掉 `totalQuota`）→ 400 **`批次必填字段不能为空`**，证明"能穿过反序列化、走到业务校验" |

#### 仪器自身的坑（第 9–11 个）

1. **第 9 个 · ElMessage 的存活窗口会让"应用正确"伪装成"提示为空"。** 试跑第二轮 27 PASS / 5 FAIL，**5 条全是 `提示=""`**，且**紧随其后的断言全部 PASS**（行确实落库、状态正确、拒绝文案正确）——即应用是对的。根因：`withMessages` 内层 `sleep(1200)` + 外层 `sleep(2400)` = **3600 ms > ElMessage 默认 3000 ms**，读 `after` 时 toast 已消失。**这是 F7 记录过的同类陷阱的第二次现身。** 已修两处：内层不再 sleep；**主证据改用"行出现"这种确定性判据，toast 只当旁证**。教训：**不要用短命 UI 元素当断言主证据。**
2. **第 10 个 · 把实验室认错，让仪器错误伪装成应用缺陷。** 我初版把 `计算与样品制备中心` 当成实验室 2，实际它是**实验室 4（属 1019）**，于是设备下拉里出现的是 `超速离心机|冷冻切片机|高性能计算节点|GPU 工作站`，看起来像"二级联动错了"。真值是 **lab 2 = 分析测试实验室**。已修：改正常量。**同族于 F7 的"用组件目录名当路由路径"。**
3. **第 11 个 · 段内异常留下的对话框会毒害下一段。** 段 4 抛错后**对话框留在打开状态**，段 5 点「新建批次」撞上陈旧 popper → 取到空下拉（`no-item:`）。已修：新增 `closeAnyDialog()` 并在每节开始前调用；同时补 `fillBatchForm()` 收敛重复填充逻辑。**这是"段级隔离"只隔离了断言、没隔离 DOM 状态**——`guard()` 保住了下游证据，但没保住下游的**前提干净**。

#### 证伪实验（12 个变异，复原后逐字节一致）

**打在生产文件上**（`AdminBatchListView.vue` / `utils/query.js` / `api/reservationBatch.js` / `router/routes.js`），复原用内存快照 + `sha256` 校验（**不用 `git checkout`**——F8 改动全部未暂存，且 `views/admin/batch/` 是**未跟踪**目录，从索引根本恢复不了）。跑变异时**不注入 `VERIFY_BACKEND`/`VERIFY_TOKEN`**，只跑 A 组，**零 DB 副作用**。

| 变异 | 改坏什么 | 期望 | 实际 | 变红断言 |
|---|---|---|---|---|
| M1 | 管理端状态白名单退化成公开两态 | FAIL | **CAUGHT** | `manage batch query accepts all four statuses...` |
| M2 | 绕过状态元数据，动作写死字面量 | FAIL | **CAUGHT** | `batch actions come only from status metadata...` |
| M3 | 四个日期选择器之一丢掉 `T` | FAIL | **CAUGHT** | `all four batch datetime pickers preserve the required T separator` |
| M4 | `handlePublish` 写后不重取（乐观更新） | FAIL | **CAUGHT** | `batch writes reload, surface backend errors...` |
| M5 | 加载路径也弹 toast（F7 R3 已判定的回退） | FAIL | **CAUGHT** | 同上（反向半边） |
| M6 | 摘掉设备选项的乱序覆盖守卫 | FAIL | **CAUGHT** | `dependent equipment options discard stale laboratory responses` |
| M7 | 管理端分页端点改名 | FAIL | **CAUGHT** | `reservationBatch API exposes the exact F8 read and write surface` |
| M8 | 摘掉 `/admin` 的 `meta.roles` | FAIL | **CAUGHT** | `new admin routes inherit roles from the parent` + `verify-batch` 白名单 |
| M9 | 越界预埋 F9 的管理端预约记录域 | FAIL | **CAUGHT** | 菜单四项精确集合 + 后续域禁止 |
| M10 | 管理端改用学生端 builder | FAIL | ~~SURVIVED~~ → **CAUGHT** | 首轮**全绿通过**，返工后新增加锁（见 R3） |
| M11 | 取消批次不尊重二次确认的「返回」 | FAIL | ~~SURVIVED~~ → **CAUGHT** | 同上 |
| M12 | 载荷多带 `laboratoryId` | FAIL | **SURVIVED** | **无断言——记录在案的已知缺口**，不上锁（见下） |

**M10 是这轮最有价值的一条**，与 F7 的 M8 同族：`manage batch query accepts all four statuses...` 锁住的是 **`query.js` 里的函数本体**，**没有任何断言锁住视图调用的是哪一个**。把视图换成学生端 builder（连 import 一起换），**整套 A+B 组全绿**——而后果是实质性的：管理端会退化成只能筛 PUBLISHED/CLOSED，**草稿与已取消批次永远筛不出来**，正是本 Phase 要防的那个 bug。**"函数被测过"≠"函数被用对了"**：单测的绿光保护不了接线。

**M12 是故意不上锁的**：`formPayload()` 多带 `laboratoryId` 无任何断言拦，但后端 `createBatch` **从不写 `laboratory_id`**（DB 里该列为 null，`laboratoryName` 是靠 `LEFT JOIN tb_equipment → tb_laboratory` 解析出来的），`updateDraft` 也**从不触碰**该列，故当前**无实际影响**。按"不过度设计"原则登记为已知缺口，不加锁。

#### Review 发现的问题（3 项，两轮批量返工）

1. **R1 · `verify-batch.mjs` 里有一条永久空转的守卫。** 原断言 `assert.doesNotMatch(fileSource, /\/reservation\/\{id\}\/verify/)` 要求**字面量** `{id}`，而 F9 真实写法是模板字面量 `` `/reservation/${id}/verify` ``（`$` 夹在 `/` 与 `{` 之间）。**正负对照实测**：`` "url: `/reservation/${id}/verify`" `` → **NO-MATCH（守卫放过）**；`"url: \"/reservation/{id}/verify\""` → MATCH。且 `command grep -rn 'reservation/{id}' src/ scripts/` **全仓 0 命中** —— 该断言在任何现在或未来的代码上都永远为真。已修：正则改为匹配真实模板写法，并**在正则使用点正上方加一条纯字符串阳性对照**（`assert.match('url: \`/reservation/${id}/verify\`', pattern)`）——**正则一旦退化回字面量形式，这条自证立刻变红**，不会再悄悄失效。
2. **R2 · 自适应选窗漏扫 `DRAFT`，且设备 9 仍在用硬编码窗口。** `nextAvailablePrimaryPayload()` 只遍历 `['PUBLISHED','CLOSED']`，但重叠查询是 `.ne(status, CANCELLED)` —— **`DRAFT` 是算数的**。首轮运行前设备 1 上恰有一个孤儿 DRAFT（`2027-06-01T11:00:00`），而主夹具落在 `2027-06-02T04:00:00`，**只差约 15 小时侥幸没重叠**；若它再晚几小时，`publishing initializes remaining quota...` 就会以 `同一设备的使用时段不能重叠` 失败——**看起来像应用缺陷，实际是夹具不自洽**。已修：扫描集合加入 `DRAFT`，helper 泛化为 `nextAvailablePrimaryPayload(backendURL, token, equipmentId = 1)`，并把**唯一必须发布成功**的设备 9 取消夹具也改为动态选窗。**修复的硬证据**是我清除的 41 行残留：设备 1 的 CLOSED 链 `06-01T10:00 → 06-02T04:00 → 06-02T22:00 → 06-03T16:00 → 06-04T10:00 → 06-05T04:00 → 06-05T22:00` **每步 +18 小时单调后移、永不重叠**——**按构造成立**，连跑两次 44/44 亦独立印证。
3. **R3 · M10/M11 两条真缺口无锁**（见上表）。已新增两条 A 组断言（`admin batch params use the manage query builder`、`batch cancellation stops when confirmation is rejected`），**A 组条数 18 → 20**，重跑变异后两条均翻为 **CAUGHT**。

**Codex 本轮出现"改测试迁就实现"吗？** 没有。而且这次它的**自述与我的独立实测出现分歧时，站在我这边的总是实测**：它报"仅修改两个测试脚本"，我用 `git status` 复核确认生产代码零改动；但它随后**在 M10 注入中途撞上用量上限被切断**，把**故意改坏的 `AdminBatchListView.vue` 留在了工作区**（import 与 `params` computed 都指向学生端 builder）——**这正是"测试全绿"与"代码已坏"能同时成立的那种状态**（若此时有人跑一次 `check:admin` 的 B 组，A 组全绿、B 组全绿，而管理端已经废了）。我靠**变异前用 `sha256` 拍的快照**发现（`43ae5f99…` ≠ `931d1666…`），逐行回补两处后**哈希逐字节回到 `931d1666…`**才算通过。**教训：变异实验必须在跑之前拍哈希、跑完对哈希；且中断信号要当成"工作区可能已坏"来处理，而不是"这次没跑完"。**

#### 边界改法：F8 合法击穿四条既有断言，全部"收紧而非删除"

同 F5 R5 → F6 → F7 的**第四次复用**，明细已写在 §9 的 F8 设计决定⑥（子路由白名单 3→4 项精确集合；`views/admin/**` 禁止正则 `/batch|reservation|dlq/i` 缩窄为 `/reservation|dlq/i`；`reservationBatch` 从"恰好 2 个只读导出"改为**导出名精确集合（8 个）+ 方法计数（get 3 / post 4 / put 1）+ URL 字面量精确集合（7 条）**）。**A 组条数在 F8 之前 18、之后 20（+2 条新锁），无一条被删除或弱化**——`git diff` 的删除行全部是改名与更强的替换。

#### 迄今仍未覆盖

- **判据① 的归属过滤仍只有后端证据**：前端**原理上无字段可判**（`ReservationBatchVO` 只有 `laboratoryId`/`equipmentId`，不含 `adminUserId`，**D-2**）。且现有 15 个批次**全部属于设备 8/11/12（实验室 3/4，属管理员 1019）**，故 token 持有者 1016 的 manage 页**初始为空**——判据①的"越权看不见"与"自己也看不见草稿"在初始数据上**无法区分**，**必须靠新建夹具才算验过**（B 组已如此做）。
- **判据⑤ 的浏览器端没点**：学生端没有指向 CLOSED 批次的入口可点，该判据在 **API 层**验（`POST /reservation` → `批次当前不可预约`，`tb_reservation` 6→6 零副作用）。另有 `PUBLISHED 已截止`/`PUBLISHED 未开始` 两条阳性对照。
- **M12 已知缺口**：`formPayload()` 多带 `laboratoryId` **无断言**，摘/加都全绿；理由是后端当前忽略该字段（见上）。**这是记录在案的缺口。**
- **D-13 族在本 Phase 再次现身**：`/equipment/page` 被后端钳每页 5 条，故**实验室设备超过 5 台时，新建批次的设备二级下拉会静默少列**。F8 只兜住了**编辑**路径（把当前行的设备并入下拉），**新建**路径仍受限——与 F7 的 D-13 同一根因、同一处理（挂账）。
- **仪器与判别脚本仍是 `/tmp` 下的临时件、不入库**（同 F2–F7 惯例）：`/tmp/f8-cdp-verify.mjs`（32 断言，零依赖 CDP）+ `/tmp/f8-mutation-check.mjs`（12 变异，含自动复原与哈希校验）+ `/tmp/f8-evidence.md`。
- **观感判断只能人眼确认**（同 F3–F7）；**D-2**（已裁定 F8 不动后端）、**D-5**（管理员看单个预约详情，F9 建议行内抽屉）、**D-13**（见上）继续挂账。

---

### F9 验收实测记录（2026-10-07，Claude 独立实测）

**边界证明（我自己数的，不采信 Codex 自述）**：`git status --porcelain` 共 12 项，其中 **F9 占 9 项** —— 改 8：`docs/frontend/FRONTEND-DESIGN.md`、`frontend/scripts/verify-admin.mjs`、`frontend/scripts/verify-batch.mjs`、`frontend/scripts/verify-request-layer.mjs`、`frontend/src/api/reservation.js`、`frontend/src/constants/statusMap.js`、`frontend/src/router/routes.js`、`frontend/src/utils/query.js`；增 1 目录：`frontend/src/views/admin/reservation/`（内含**恰好 1 个**文件 `AdminReservationListView.vue`，7638 B）。其余 4 项**与 F9 无关**：`.gitignore`（**并发会话**为 `benchmark/.runtime/` 加的忽略行）、`benchmark/`、`docs/study/`（**凭据地雷**：18 个文件里 12 个命中凭据模式）、`jmeter.log` —— 提交时必须**逐条显式列路径**排除，**绝不能用 `git add -A`**。

**后端零接触**：`git status --porcelain` 中无任何后端路径（无 `src/main`、无 `.java`、无 `pom.xml`）。判据①–⑤ 依赖的"后端已具备的能力"**全部是既有实现**：`ReservationController:43/56` 的 `@RequireRole(LAB_ADMIN)` 两个端点、`ReservationServiceImpl.verify` 的闸门顺序、`ReservationLifecycleServiceImpl.complete` 的幂等分支、`ReservationMapper.xml` 的 `adminUserId` 过滤，**F9 一行未改**。

| 命令 | 结果 | 备注 |
|---|---|---|
| `node scripts/verify-admin.mjs`（带真实后端 + F9 夹具） | **A 组 30 PASS / 0 FAIL** | A 组是 F9 的全部静态与接口面锁 |
| 同上 B 组（真机 + 夹具） | **A 30 PASS / 0 FAIL；B 组 8 条 F9 专属断言全绿**（判据①–⑤ 落点见下表）；余下为 F8 继承的批次生命周期断言，受**并发压测会话**阻断（见下） | B 组满编 32 条 |
| **浏览器仪器（F9，12 断言，零依赖 CDP）** | **12 PASS / 0 FAIL / 0 崩溃** | 段 1（夹具 A，7 条）+ 段 2（夹具 B，5 条），两段全跑完 |
| `npm run check:batch`（C 组指向 OPEN 夹具 11066） | **A 13 + B 6 + C 3 = 22 PASS / 0 FAIL** | C 组原用的 11005 预约窗口已过期（`EXPIRED`），改用夹具 A 后 **3/3** |
| `npm run check:request` | **26 PASS / 0 FAIL** | 未回归 |
| `npm run check:browse` | **37 PASS / 0 FAIL** | 未回归 |
| `npm run check:session` | **24 PASS / 0 FAIL** | 另 3 条需 `VERIFY_CODE`，未跑 |
| `npm run check:reservation` | **32 PASS / 0 FAIL** | 未回归 |
| `npm run build` | **exit 0** | 生成独立 `AdminReservationListView-BC96uoEt.js`（4.59 kB）+ 配套 CSS（529 B），路由级切分生效 |
| DB 复核 | **8 行预约 / 17 个批次**；夹具 `11066/11067` 各 1 行、均 `RESERVED` | 无遗留运行残留 |

#### §10 判据 ①–⑤ 的落点

| 判据 | 落点与实测证据 |
|---|---|
| ① 管理端列表只显示**自己实验室**的预约 | B 组 `managed reservation page includes own ids and excludes foreign ids and batches` —— **一条断言里同时断言"含自己的 id"与"不含外来 id 与外来批次"**：只断言"看不见"会在**空列表上恒真**，故必须带阳性对照。正面证据由 CDP 仪器独立给出：`?batchId=11066` 过滤后表格**恰好 1 行**、预约人列=`1`、实验室列=`分析测试实验室`（属管理员 1016）。**前端侧原理上无从判别归属**——`ReservationVO` 不含 `adminUserId`，过滤只可能发生在后端 SQL |
| ② 未到 `startTime` 核验 → 400 `预约尚未开始，无法核验` | 两层。B 组 `not-started verification returns the exact 400 message with zero side effects`（断言**精确文案** + `status`/`update_time` 前后不变）。**页面级**：CDP 段 1 点行内「核验」→ 二次确认 → 点「核验」→ 页面 toast **逐字**出现 `预约尚未开始，无法核验`，且该行仍是「已预约」、「核验」按钮仍在（**零副作用在 UI 上可见**） |
| ③ 核验成功后**重新拉取**显示「已核验」 | 两层。B 组 `started verification completes the exact reservation id and reload observes COMPLETED`（**用 18 位 id 精确断言**，不是模糊匹配）。**页面级**：CDP 段 2 点「核验」后轮询该行状态**由「已预约」变为「已核验」**，且**整页刷新后仍为「已核验」**（证明是服务端真值，不是本地乐观更新）——两条合起来即"成功后重取" |
| ④ 再次核验返回 200 但页面**不得**提示"核验成功" | **三层证据，缺一层都不够**：① B 组 `repeated verification stays 200 without changing completed update time`（幂等：**响应体与首次成功完全相同、`update_time` 未被触碰**，故"200 了但什么都没发生"）；② A 组 `reservation verification reloads before success and gates the toast on a status difference` 从**源码层面**锁住"提示以状态差为条件"（变异 M3 证明这条有牙）；③ CDP 段 2 证明 `COMPLETED` 行的**操作列按钮数为 0** ⇒ **二次点击在 UI 上根本不可达**（"入口消失"比"点了不给提示"更强） |
| ⑤ 对自己无权实验室的预约核验 → 403 | B 组 `foreign not-started reservation is denied before the lifecycle gate`：外来夹具取的是"**批次 `start_time` 在未来** + `RESERVED`"的行 —— 这样 403 与 400 才构成有效对照（若批次已开始，400 会先命中，断言就测不到 403）。**值得单独记的是闸门顺序**：`ReservationServiceImpl.verify` 里 `assertManageable` **排在** `reservationLifecycleService.complete` **之前**，故越权核验**不可能产生任何状态变更**。另两条同族：`student role cannot access reservation management or verification`（学生 403）、`reservation management and verification require authentication`（无 token 401） |

#### 并发写入者：本 Phase 遇到的新环境变量

另一个 Claude 会话在 `benchmark/` 下建压测链，**共享同一个 `hmdp` 库**，在**设备 1（实验室 1 → 管理员 1016）**建 8 个 PUBLISHED 批次并灌入预约，实测写入速率 **~137–330 行/秒**。后果不是数据被污染那么简单：

- `verify-admin.mjs` 的 `fetchManagedReservations` 是 `do { … } while ((current - 1) * 5 < total)`：当 `total` 在 **40002** 且仍在增长时，**翻页速率（~125 行/秒）追不上写入速率** ⇒ 该循环**永不终止**。B 组在压测期间**绝对不能跑**（我加了 600 s 看门狗才敢在低位窗口尝试）。
- 该会话的 cleanup **会把设备 1 上的既有批次一并删掉**：F8 留下的夹具批次 **11065 不复存在**（已核验全仓 `scripts/` 与 `src/` **零引用**，`VERIFY_BATCH_ID=11005` 来自 env，故对验证套件无害）。
- **它自己的风险**：`benchmark/` 整目录**未被 gitignore**（13 个文件里 `password` 命中 4 个、`authorization` 命中 5 个）——一次 `git add -A` 会把真实口令写进不可撤销的历史。已向用户报告，建议整目录加入 `.gitignore`。

#### 仪器自身的坑（第 12 个）

**`v-loading` 的淡出遮罩会吞掉"看不见"的第一次点击。** 首轮跑出 `段 1 首次点击 → 对话框未出现`，而**同一页面状态的第二次点击正常**（段 2 的首次点击也正常）——看起来像应用"第一次点核验没反应"，**实际是仪器自己的竞态**。用 `elementFromPoint` 逐 200 ms 采样直接抓到真凶：行一出现在 DOM 的那一刻（`t=0.0s`），按钮坐标上的顶层元素是 `DIV.el-loading-mask`，类名 `el-loading-fade-leave-active`、**`opacity: 0` 但 `display: block`** —— `v-loading` 的遮罩**在淡出的 300 ms 里仍在 DOM 中**并照常吃点击；`t=0.2s` 遮罩消失，同一坐标立刻可点。

两处已修（否则这条会伪装成应用缺陷）：
1. **点击前必须等 `.el-loading-mask` 从 DOM 消失**，且 `elementFromPoint` 的落点必须落在**按钮子树内**。判据不能写成"顶层元素就是 button"——健康状态下返回的是按钮内层 `SPAN`（实测 `topIsButton` **恒为 false**），照那样写**健康页也会被误判**。两条合起来才是"可点"的充分条件。
2. 附带发现同类：**失败断言会被打印两遍**（跑动时一次、末尾汇总一次），解析时必须按标签去重，否则会把 1 个失败数成 2 个。

**教训与 F5 第 3 条、F7 第 9 条同族：短命 UI 元素（toast、遮罩）不能当作"前提已就绪"的判据。**

#### 证伪实验（18 个 A 组变异 + 3 个页面级变异，复原后逐字节一致）

**打在生产文件上**（`AdminReservationListView.vue` / `statusMap.js` / `routes.js` / `query.js` / `api/reservation.js`），复原用**内存字节快照 + `sha256` 逐字节比对**（**不用 `git checkout`**——F9 改动全部未暂存，且 `views/admin/reservation/` 是**未跟踪目录**，从索引恢复等于把整个 F9 抹成空）。跑变异时**不注入 `VERIFY_BACKEND`/`VERIFY_ADMIN_TOKEN`**，只跑 A 组，**零 DB 副作用**，与并发压测会话互不干扰。每条变异跑完**立刻**从快照复原再跑下一条；`SIGINT`/`SIGTERM` 也挂复原钩子。

| 变异 | 改坏什么 | 期望 | 实际 | 变红断言 |
|---|---|---|---|---|
| M1 | 视图 import 换成学生端 `buildReservationQuery` | FAIL | **CAUGHT** | `admin reservation view has no status literal branches and uses the manage query builder` |
| M2 | 摘掉二次确认 | FAIL | **CAUGHT** | `reservation verification stops when confirmation is rejected` |
| M3 | 状态差守卫退化为"200 即提示成功" | FAIL | **CAUGHT** | `reservation verification reloads before success and gates the toast on a status difference` |
| M4 | 核验成功后不重取（乐观更新） | FAIL | **CAUGHT** | 同上 + `…stops when confirmation is rejected` |
| M5 | 确认被拒后丢掉 `return`（`catch {}`） | FAIL | **CAUGHT** | `…stops when confirmation is rejected` |
| M6 | 视图写状态字面量分支 | FAIL | **CAUGHT** | `reservation actions come only from manage metadata…` + `…no status literal branches…` |
| M7 | 视图直接 import `axios` | FAIL | **CAUGHT** | `admin views use the API layer instead of axios` |
| M8 | 越界预埋 F10 的 DLQ 管理端域 | FAIL | **CAUGHT** | `admin route registers the five ordered menu entries with real icons` |
| M9 | 管理端预约记录子路由改名 | FAIL | **CAUGHT** | 同上 |
| M10 | 菜单 `order: 5 → 6` | FAIL | **CAUGHT** | 同上 |
| M11 | 摘掉 `/admin` 的 `meta.roles` | FAIL | **CAUGHT** | `new admin routes inherit roles from the parent` |
| M12 | `RESERVED.manageActions` 清空 | FAIL | **CAUGHT** | `reservation actions come only from manage metadata…` + `reservation manage actions are orthogonal…` |
| M13 | `COMPLETED.manageActions` 改成 `['verify']` | FAIL | **CAUGHT** | `reservation manage actions are orthogonal…` |
| M14 | `manageActions` 泄漏进学生端 `actions` | FAIL | **CAUGHT** | 同上 |
| M15 | 管理端状态白名单退化为公开两态 | FAIL | **CAUGHT** | `manage reservation query accepts numeric batch ids and all reservation statuses only` |
| M16 | 摘掉 `batchId` 的数字校验 | FAIL | **CAUGHT** | 同上 |
| M17 | 活跃筛选谓词把 `current` 也算进去 | FAIL | **CAUGHT** | `manage reservation active-filter predicate recognizes only batch and status` |
| M18 | 核验端点 `POST → GET` | FAIL | **CAUGHT** | `reservation API exposes the exact F9 surface and transport contract` |

**18/18 CAUGHT，0 SURVIVED，0 INVALID，复原后 5 个文件哈希逐字节一致。** 与 F8 的 M10/M11 两条"首轮全绿"相比，F9 的 A 组**没有留缺口**——原因不是运气，是 F9 把"视图调用了哪个 builder"和"二次确认被拒后是否中断"这两类**接线缺陷**先写成了断言（A 组第 18、17 条），而这两条正是 F8 事后才补的。

**页面级变异（证明 CDP 那 12 条不是空转）**：只跑段 1（`F9_CDP_ONLY=A`，只读夹具 A、不改其状态）。

| 变异 | 改坏什么 | 期望 | 实际 |
|---|---|---|---|
| C1 | 摘掉二次确认 | FAIL | **CAUGHT**（`弹出二次确认对话框` 及后续 2 条） |
| C2 | 确认被拒后丢掉 `return` | FAIL | **CAUGHT**（`点「返回」…零 /verify 请求`） |
| C3 | `RESERVED.manageActions` 清空 | FAIL | **CAUGHT**（前置「提供核验按钮」及其后 5 条） |

**C2 是这轮最有价值的一条**：它证明那条**否定式断言**（"点「返回」后 `/verify` 请求增量必须为 0"）真的会红——否定式断言最容易在坏页/空页上**空转通过**，故它被刻意写成与"对话框确实出现过"**合取**（前置不成立时断言直接判失败，而不是判通过）。

#### 提交字节校验（快照陈旧 ≠ 变异残留）

提交前用交付哈希复核「**提交的字节 == 实测过的字节**」，发现 `frontend/scripts/verify-admin.mjs` 与 17:29:14 拍的快照**不符**（`ba975d11…` vs `8ad55307…`），其余 5 个文件逐字节一致。

**定性过程（三条互不依赖的证据）**：① `mtime` 时间线 —— 快照 17:29:14、run 1 17:34:12、**该文件 17:36:10 被改**、run 2 17:37:03 ⇒ 快照**早于** R2 修复，run 2 与 17:51 的回归跑的都是**最终版**；② 内容标记 —— R1 断言与 `parseBigintJson` 各命中、**裸 `JSON.parse` 归零**，与 R2 的修法一致；③ 该文件**从不在变异清单内**（M1–M18 只打 `vue`/`statusMap`/`routes`/`query`/`api` 五个文件），故「变异残留」在原理上不成立。

**决定性复核**：拿**已提交的字节**重跑 A 组（离线、零 DB 副作用）⇒ **A 组 30 PASS / 0 FAIL / 0 SKIP**；B 组 32 条全 SKIP（未注入后端，符合预期）。另跑残留探针：4 类视图变异串（`Promise.resolve`、`import axios from 'axios'`、builder 别名替换、`row.status === 'COMPLETED' ? [] : ['verify']`）在 6 个交付文件上**零命中**。

**教训（与「变异残留」同族，但方向相反）**：交付哈希快照必须**在最后一次编辑之后**拍，否则「哈希不符」这一信号会与「变异残留」**同形**——把真警号淹没在假警号里。本次靠 `mtime` + 内容标记 + 「该文件不在变异清单」三条互不依赖的证据才判成假警号；只凭其中一条都会误判。**变异前自拍快照这条纪律仍成立，但必须连着「拍完不许再改」一起执行**。

#### Review 发现的问题（4 项）

1. **R1 · 规格违背：核验没有二次确认。** `FRONTEND-DESIGN.md:593` 明写"行内核验（**含二次确认**与成功后重取）"，而交付视图里根本没有 `ElMessageBox.confirm`。已按 F8 `handleCancel` 同构补齐（`type:'warning'`、确认/取消文案「核验」「返回」），并新增 A 组断言 `reservation verification stops when confirmation is rejected`（**A 组 29 → 30 条**）。**这是"交付物通过了所有测试却仍未满足规格"的实例**——判定依据是规格原文，不是测试绿光。
2. **R2 · 校验脚本的雪花 id 精度缺陷（真缺陷，由真机跑出来）。** B 组首轮 5 FAIL，其中 4 条报的是 `managed reservation <id> not found`——**报错信息本身误导**。真因是 `rawRequest` 用裸 `JSON.parse`：18 位雪花 id `645959834323624548` 被舍入成 `…600`，而断言比较的是 `String(row.id) === process.env.VERIFY_RESERVATION_*_ID` ⇒ **永远为假**。修法：**import 生产同款解析器** `parseBigintJson`（`json-bigint@1.0.0` 本就是 `package.json:22` 的依赖，零新依赖），理由是**校验脚本必须与生产用同一个解析器**——若脚本自己另实现一份，两边精度行为一旦漂移，"绿"就失去意义。
3. **R3 · 非数字路径段实测走 500 通道（跨切面既有瑕疵，只记录不改）。** `POST /reservation/not-a-number/verify` 实测 **500 `服务器异常`**，而原断言只允许 `[400,403]`。这**不是 F9 引入的**：`@PathVariable Long` 类型转换失败被全局处理器兜成 500，与 `scripts/verify-browse.mjs:437` 记录的 `non-numeric equipment id produces the measured 500 channel` **是同一类实测通道**。F9 后端零接触，故断言改为**记录实测 500 通道**并在注释里指向先例。
4. **R4 · `nextAvailablePrimaryPayload` 的 UTC/本地 8 小时偏移（F8 遗留夹具缺陷，本轮暴露）。** B 组第二次运行出现 `publish` 返回 400 `同一设备的使用时段不能重叠`，根因是运行 1 在**设备 1** 留下 `batch 11068 CLOSED`（窗口 09:34–11:34），而本次选窗 09:37–11:37 与之重叠。更深一层：该 helper 用 `toISOString()`（**UTC**）生成载荷，而扫描用的是**本地** `endTime` ⇒ 实际发出的窗口比它推理的时刻**早 8 小时**，两者错位。**这不是 F9 缺陷**（run 1 里这 4 条是 PASS 的），处置是清除运行残留；**helper 本身未改**（属 F8 交付物，改动会牵动 F8 的变异记录），登记为已知缺口。

#### 迄今仍未覆盖

- **判据① 的页面级证据是"间接"的**：CDP 仪器能证明"筛出来的行确实属于自己"，但**证不了"本不该出现的行没出现"**——因为前端**没有字段**可判归属（`ReservationVO` 无 `adminUserId`，G-F9-1）。"排除外来行"这条**只有 B 组（后端 SQL）能证**。这与 F8 判据①的同族限制一致。
- **判据④ 的"重复核验"在 UI 上不可达**：`COMPLETED` 行的核验入口已消失，故**无法用真机点第二次**。该判据的页面半边靠**构造性证据**（入口消失）+ A 组源码锁，API 半边靠 B 组幂等断言。**这是"更强"而不是"没测"**，但要说清它没被"点"过。
- **`RESERVATION_STATUSES` 学生端与管理端共用**（D-F9-3）：管理端筛选下拉的四个取值来自与学生端同一个常量。当前**无误**（两端都该看到全部四个状态），但**若将来学生端要收窄可见状态，管理端会跟着一起窄**——登记备查。
- **管理端无单条预约详情端点**（D-5）：核验前看不到完整信息。已裁定**不新增端点**（列表行已含设备名/实验室名/时段/状态/`quotaReleased`）。F9 据此做成"行内直接核验 + 二次确认"，**D-5 结案**。
- **`ReservationVO` 不含 `userName`**（G-F9-1）：列表**只能显示预约人 ID**（页面列名如实写"预约人 ID"）。若演示需要姓名，最小改法是 `ReservationMapper.xml` 的 `selectReservationPage` 多 `LEFT JOIN tb_user`——**涉及后端，等批准**。
- **B 组的 F8 继承断言本轮未能拿到"满编 62/62"的干净记录**：并发压测会话在设备 1 持续写入 ~4 万行，使 `fetchManagedReservations` 的分页循环无法终止（见上）。**F9 专属的 8 条断言在污染前的 run 2 已全绿**（run 1：B 27 PASS / **5 FAIL，5 条全在 F9 簇**——夹具尚未就位；run 2：B 27 PASS / 4 FAIL / 1 SKIP，**F9 专属 8 条全绿**，4 FAIL + 1 SKIP **全在 F8 继承簇**——F8 的夹具批次 11065 已被并发会话删除）；A 组 30 条本轮全绿。缺的只是"同一轮里 62 条一起绿"这一条记录。
- **仪器与判别脚本仍是 `/tmp` 下的临时件、不入库**（同 F2–F8 惯例）：`/tmp/f9-cdp.mjs`（12 断言，零依赖 CDP）+ `/tmp/f9-mutation-check.mjs`（18 变异）+ `/tmp/f9-cdp-mutation.mjs`（3 页面级变异）+ `/tmp/f9-fixture.mjs`（夹具 prepare/reset/cleanup）+ `/tmp/f9-runb.sh`（带看门狗的低位窗口跑法）。
- **观感判断只能人眼确认**（同 F3–F8）。

#### F9 封版（2026-10-07，经用户确认）

**裁定：不再追「同轮满编 62/62」这条记录，F9 就此封死。** 理由是这条记录**不取决于本 Phase**：它卡在另一个会话的负载上（对方压测把 lab1 灌到 **30 万行且仍在涨**，`fetchManagedReservations` 的翻页永远追不上写入）。而已有的等价证据是充分的——F9 自身 8 条后端断言在污染前的 run 2 **全绿**，A 组 30 条另有一份**用已提交字节复跑**的记录（见「提交字节校验」）。

**收尾动作与复核**：停掉等窗口的后台轮询（`/tmp/f9-runb.sh`）→ `node /tmp/f9-fixture.mjs cleanup` 删除夹具批次 `11066`（设备 3）、`11067`（设备 4）及其预约。**删除前逐条核过引用数**（各 1 行，均为我自己的夹具预约，无他人数据）。复核结果：`id ≥ 11060` 的批次**只剩并发会话自己的 8 个**（`11088–11095`，设备 1）；夹具候选设备 `3/4/10/1/9` 上**无任何 F9 批次**；**孤儿预约 0 行**；`11066/11067` 各剩 0。

---

### F10 验收实测记录（2026-10-07，Claude 独立实测）

**边界证明（我自己数的，不采信 Codex 自述）**：`git status --porcelain` 共 **13 项**，其中 **F10 占 11 项**（`git status` 默认把新增的 `views/admin/dlq/` 目录**折叠成 1 项**，故"项数 11"与"文件数 12"并不矛盾）。按**文件**数是 **12 个 F10 文件**：**新增 6 个**（`scripts/verify-dlq.mjs`、`api/dlq.js`、`constants/messages.js`、`utils/jsonText.js`、`views/admin/dlq/{DlqListView,DlqDetailView}.vue`）、**修改 6 个**（`package.json`、`scripts/verify-{admin,batch}.mjs`、`router/routes.js`、`utils/query.js`、本文件）。非 F10 的 2 项是 `docs/study/`（未跟踪，非本 Phase 产物）与 `jmeter.log`（并发会话的产物）。

**后端零接触**：`git status --porcelain` 中无任何后端路径（无 `src/main`、无 `.java`、无 `pom.xml`）。判据①–⑤ 依赖的后端能力**全部是既有实现**：`ReservationDlqController` 的三个端点、`ReservationDlqStatus` 三值、`SystemConstants.DEFAULT_PAGE_SIZE = 5`（与前端 `PAGE_SIZE` 一致）。`ReservationDlqQueryDTO` 只有 `status` + `current`，**页大小由后端决定**，前端不传。

#### §10 判据 ①–⑤ 的落点

| 判据 | 落点 | 证据（哪一条在哪儿被验到） |
|---|---|---|
| ① 列表不显示 payload，详情才显示且格式化后不溢出 | A（`DlqListView.vue` 全文无 `payload`）+ **B1**（真机列表 payload 恒为 null 或缺失）+ CDP 1b/1d/2e | **真机实测**：列表条目字段恰为 7 个，`payload` 键**不存在**（非 null）；CDP 段 1 表头 7 列无 payload、**任一单元格都不含 payload 内容**；段 2 用 6077 字节夹具，格式化后 `pre` 1072/1072、文档 1440/1440，**两级都不横向溢出** |
| ② 成功与各拒绝原因提示正确，英文码映射为中文且**可展开看原文** | A（映射单测）+ **B3/B5/B6/B7/B8/B11**（真机拒绝原文逐字钉死）+ CDP 5c/5e + **合成响应仪器 7 条** | 5c 页面展示后端中文原文「死信记录已处理，不能重复重放」；5e 中文原因**不**渲染展开区（`code` 为 null 分支）；合成 `REPLAY_PUBLISH_UNKNOWN` ⇒ 告警标题为中文映射、展开后 `pre` 显示后端原文（见下「仍未覆盖」的限定） |
| ③ 对 `REPLAYED`/`DISCARDED` 重放被拒 | **B5/B6** + CDP 5a/5b/5d | 确认框**明说**「该记录当前状态为「已丢弃」，后端会拒绝本次重放」；提交后恰好 1 次 `POST /replay` |
| ④ 重放前二次确认 | **A11**（源码级：确认在请求之前、catch 体内无请求）+ CDP 4c/4e | 点「返回」后 `POST /replay` 计数 **0→0**，且对话框关闭、页面 payload 仍在 |
| ⑤ 页面不对 payload 做"解析后重写" | A（formatter 源码无 `JSON.parse`/`stringify`）+ **B2** + CDP 3a/3b | 详情页 DOM 里 18 位 `reservationId` **逐字符保留**；**阴性对照**确认不出现被舍成 `…000` 的版本 |

**成功半边（判据③）**：CDP 段 6 点「重放」→ 描述字段由「待处理」变为「**已重放**」、重放次数 0→1、**重取后 payload 仍在**、无残留失败告警。

#### 各套件实测结果

| 命令 | 结果 |
|---|---|
| `npm run check:dlq`（离线，无 `VERIFY_*`） | 退出码 0，**A 组 13 PASS / 0 FAIL / 0 SKIP**，B 组 12 SKIP |
| `npm run check:dlq`（真机 18081 + 重建夹具） | **A 13 PASS / 0 FAIL；B 12 PASS / 0 FAIL / 0 SKIP** |
| `npm run check:request`（真机 + 学生 token） | **26 PASS / 0 FAIL**（= F9 基线，无回归） |
| `npm run check:batch`（离线） | A 组 13 PASS / 0 FAIL；B 6 SKIP；C 3 SKIP |
| `npm run check:admin`（离线） | A 组 30 PASS / 0 FAIL；B 组 32 SKIP |
| 浏览器仪器（零依赖 CDP，**30 断言**） | **30 PASS / 0 FAIL**，全程无控制台错误 |
| 合成响应仪器（英文码展开分支，**7 断言**） | **7 PASS / 0 FAIL** |
| `npm run build` | 退出码 0，产出 `dist/assets/dlq-*.js` 等 5 个 chunk（仅既存 chunk-size 警告） |

#### 仪器自身的坑（第 13–15 个）

13. **同进程 stub + `execFileSync` ⇒ 仪器自杀式伪红。** B1 空洞实验的第一版把 stub 与**被测套件放在同一个 Node 进程**里：`execFileSync` 是同步的，会**阻塞事件循环**，stub 永远没机会回包 → 套件拿到 `AbortError: timeout` → `check()` 把异常记成 FAIL。而脚本的结论行还理直气壮地打印「断言已经长牙（修复已生效）」。**红必须来自被测对象，不能来自仪器**：现在 stub 起在独立进程，且脚本显式判 `AbortError|TimeoutError`，命中就记「实验无效」（退出码 2），不当作成功。
14. **拿 `document.body.innerText` 当"页面已更新"的等待条件。** CDP 段 6 第一版等 `body.innerText.includes('已重放')`，而成功 toast 的文案「重放已提交，状态已更新为「已重放」」**也含这三个字** ⇒ "重取失败但 toast 弹了"同样会变绿。改为只读 `el-descriptions` 的**状态字段**。
15. **把"夹具排在第几页"当已知条件。** 段 2 原本假定超大 payload 夹具 G 一定在第 1 页；实测列表按创建时间倒序、每页 5 条，G 是首行**纯属运气**。改为「用第一行验证 列表→详情 链路，G 直接开详情页」。

#### 证伪实验（1 个空洞断言实测 + 5 个 A 组变异，复原后逐字节一致）

1. **B1 空洞通过 —— 实测，不是读代码推断。** B1 的断言体只到 `assert.equal(Array.isArray(data), true)` 就进了 `for (const record of data)`，而 `[]` 恰好满足数组检查 ⇒ **后端返回 0 行时循环体一次都不执行，断言照样报绿**。用独立进程 stub 令 page 恒返回 `{success:true,data:[],total:0}`：修复前输出 `PASS: [B] list payload is always null or absent`（且 `AbortError` 判据为 false，排除伪红）；返工加 `data.length > 0` 前置后，同一个实验输出 `FAIL`。**它唯一抓不到的故障恰好是它最该抓的那个（列表页返回 0 行）。**
2. **A 组 5 个变异，5/5 CAUGHT，且每条都由我指名的断言捕获**：M1 拆掉二次确认（`ElMessageBox.confirm(` → 失效）→ `replay confirmation precedes…`；M2 让 `replayReservationDlq(` 的字面量出现在确认之前 → 同一条的顺序断言；M3 失败路径插入 `state.value = 'error'` → `replay failure uses a persistent alert…`；M4 引入 `status === 'NEW'` 硬编码 → `replay confirmation is driven by DLQ status actions…`；M5 改坏 NACK 的中文映射 → `known replay reason codes map to exact Chinese text…`。变异**打在生产文件上**（`DlqDetailView.vue` / `messages.js`），复原**不用 `git checkout`**（未跟踪文件从索引恢复不了），靠内存快照逐字节写回，事后 3 个文件 sha256 与快照一致。

#### Review 发现的问题（1 项返工 + 1 项范围说明 + 1 条被撤回的误记）

1. **B1 空洞通过**（同上，已批量返工修复：加一行 `assert.equal(result.body.data.length > 0, true)`，**不加** check 条数、**不降级为 SKIP**）。返工后复跑真机 B 组仍 **12/12 全绿**，确认新前置在真实数据上不误伤。
2. **范围：第二阶段改动了 `messages.js`。** 其 mtime 与 `DlqDetailView.vue` **完全相同（18:29:28）**，而 Packet 只授权改两个文件。改动内容是把英文码识别从"整个 reason 精确等于英文码"改成"在 reason 原文中查找英文码"，使被包在长句里的 `REPLAY_PUBLISH_UNKNOWN` 仍能映射。**已复核行为正确**（三个英文码的映射、中文与未知值透传且 `code: null`、`null`/`''` 回落「重放失败」，四条都有断言钉住），**予以保留、不回滚**；Codex 亦承认这一步应先标注范围扩展再动手。
3. **一条被我撤回的 Review 记录**：我此前记下 `verify-dlq.mjs:214` 有行尾空白，**提交前复核时该行尾空白已不存在**（`grep ' $'` 无命中），故未列进返工包。**先验证再下结论**，否则会把已消失的问题写成缺陷。

#### Packet 自身的一处事实错误（在此更正）

我在 F10 第二阶段的 Packet 里写了「replay 的返回值里不含 payload」。**实测相反**：`POST /reservation-dlq/{id}/replay` 成功时返回的 VO **含 payload**（夹具 A 为 67 字节）。所需的「成功后重新拉取」行为仍然正确且必要（状态与 `replayCount` 都要刷新），故**不产生返工**；但该表述本身是错的。

#### 迄今仍未覆盖

- **观感判断只能人眼确认**（同 F3–F9）。
- **英文码分支的"真机"证据是合成的（如实标注）。** 真机上**无法**从 UI 触发 `REPLAY_CONFIRM_NACK`/`RETURN`/`PUBLISH_UNKNOWN`（需要有本事让 MQ publish 失败）。故页面渲染是用 CDP `Fetch.fulfillRequest` 合成 `/replay` 响应验证的：**应用代码、路由、组件、详情 GET 全为真，只有那一个响应被替换**（拦截计数实测为 1 次）。映射函数本身另由 A 组单测覆盖。
- **失败路径不重取 ⇒ 状态可能陈旧（有意为之的取舍）。** 后端以「已处理」拒绝时，页面展示的仍是**本地**状态；若该记录是被别人抢先处理的，告警文案与描述面板会同屏矛盾。**不改**的原因是 `loadDetail()` 会把 `state` 置为 `loading`，从而**卸载承载告警的 `v-else-if="state === 'ready'"` 块** —— 即"失败后重取"会直接击穿判据② 的「告警必须持久」。两者兼得需把重取结果写进独立 ref 而不动 `state`，属超出 F10 范围的设计改动，记为已知取舍。
- **`docs/study/` 未跟踪目录仍在工作区**（12/18 文件命中凭据模式）。故本轮提交**逐条显式列路径**，不用 `git add -A`，并在暂存后复核文件清单、对暂存区做凭据自检（只数命中条数、不回显匹配内容）。

---

### F11 验收实测记录（2026-10-07，Claude 独立实测）

**边界证明**：`git diff --stat` 共 **21 个已跟踪文件**（190 插入 / 72 删除）+ **2 个新增未跟踪文件**（`frontend/scripts/verify-ui.mjs`、`frontend/src/components/LoadingBlock.vue`）。其中**改动落在 `views/` 的 14 个文件**（F11 第一阶段，17 处 unauthorized 闭包 + 死信不存在空态 + 批次子区同构化）、**3 个布局/路由文件**（`AppLayout.vue`、`AdminLayout.vue`、`router/routes.js`，第二阶段）、**1 个 `package.json`**（新增 `check:ui`）、**2 个文档**（本文件、`README.md`）。

**后端零接触**：`git status --porcelain` 中无任何 `src/main`、`.java`、`pom.xml` 路径。判据①–⑥ 依赖的后端能力全部是既有实现；F11 未新增任何 API（§9 的 F11 行本就写「无新增」）。

#### 一、17 处 unauthorized 闭包的逐点核对（我数的，不采信自述）

按「枚举每一个 `kind === 'unauthorized'` 分支」核，共 **17 处** = **13 处字面 `result.message` + 2 处具名变量**（`LaboratoryDetailView.vue` 的 `laboratoryResult.message` / `equipmentResult.message`）+ **1 处 `batchErrorMessage`**（`EquipmentDetailView.vue:210`）+ **1 处 `finishAsPending(sequence)`**（`BatchDetailView.vue:233`）。**我起初的 grep 只匹配字面形态，数出 14，是我漏算**；逐点枚举后与 Codex 的 17 完全一致，无遗漏。另有 3 个文件含**非 state 的** unauthorized 分支（`AdminBatchListView` 7 处、`AdminEquipmentListView` 4 处、`BatchDetailView` 3 处）——它们是**动作处理器**（发布/关闭/取消/删除/核验），不改 `state` 而弹 `ElMessage`，属正确设计，不计入 17。

#### 二、§10 判据 ①–⑥ 的落点

| 判据 | 落点 | 证据 |
|---|---|---|
| ① 15 个数据页三态齐备 | 静态断言 `check:ui` A/D 组 | `[A] 15 state loaders…`（`branchCount === 15` 空转护栏）+ `[D] …exactly 15 API-backed data pages`（`dataPages.length === 15`）+ `[D] all 14 non-login…`（14 页逐一含 `v-loading`/`error`/空态/`ready`）+ `[D] LoginView keeps its sending/submitting…`（例外显式写出） |
| ② 无空白屏 / 无未捕获异常 | **CDP 真机扫描 100 段** | 每段均判「文档横向溢出 ≤1px ∧ 锚文案出现 ∧ `innerText` 长度 >20 ∧ 控制台错误 0」的**合取**；阴性判据（空白屏）配锚文案正向前提，防坏页空转 |
| ③ 四档宽度布局不破 | **CDP 真机扫描** | **100 PASS / 0 FAIL**；侧栏矩阵：1440/1200 → **220px**，992/768/**600** → **64px**（`≤992` 折叠成立） |
| ④ 结构与错误前缀统一 | 静态断言 + 源码复核 | 一律 `EmptyState` / `LoadingBlock`；错误前缀 `…加载失败：`；**保留**按实体名词与「筛选无结果 / 真无数据」两分支（口径见偏差②） |
| ⑤ 两条演示链路 | **真机 API 实测**（见下节，含一处如实登记的缺口） | 学生链路的 6 条预约、4 实验室、12 设备、批次均在库；管理端链路的核验**闸门**已实测（400/403/401 三态） |
| ⑥ API 层隔离 | 静态断言 `check:ui` B 组 | `[B] views/layouts/components 零 axios`（`/axios/i` 全查）+ `[B] createRequest.js 是 src 中唯一 import axios 的文件`（集合精确相等 `['src/api/createRequest.js']`） |

#### 三、各套件实测结果（全部由我独立跑）

| 命令 | 结果 |
|---|---|
| `node scripts/verify-ui.mjs`（新增，离线） | 退出码 **0**，**12 PASS / 0 FAIL**（A 3 / B 2 / C 3 / D 4） |
| 6 套离线件（session/browse/admin/batch/reservation/dlq） | **131 PASS / 0 FAIL**（24 / 19 / 30 / 13 / 32 / 13；未设 `VERIFY_*` 的 B/C 组共 53 条 SKIP） |
| `verify-request-layer.mjs`（真机 18081） | **26 PASS / 0 FAIL**（= F9/F10 基线，无回归） |
| `npm run build` | 退出码 **0**（仅既存的 chunk-size 警告） |
| CDP 真机扫描（20 路由 × 5 宽度） | **100 PASS / 0 FAIL** |
| 变异实验（3 个，打在生产文件上） | **3/3 按预期变红**，复原后 SHA-256 逐字节一致 |

#### 四、证伪实验（变异，**不是**读代码推断）

三个变异各自打在**生产文件**上，每个自带**变异前字节快照 + SHA-256**，跑完从自己的快照写回并校验哈希（**不用 `git checkout`**——未跟踪/未暂存的修复从索引恢复不了）：

| 变异 | 打坏什么 | 结果 |
|---|---|---|
| **M1** | 删掉 `LaboratoryListView.vue` unauthorized 分支里的 `state.value = 'error'` | **A 组指定断言变红**：`…expected all 15 state-loader unauthorized branches` → 退出码 1；复原后 SHA-256 `728bfae9…` 一致 |
| **M2** | 把 `AppLayout.vue` 的 `992px` 改成白名单外的 `975px` | **C 组两条同时变红**（白名单 + 「定义了 992」）→ 退出码 1；复原后 SHA-256 `461800f6…` 一致 |
| **M3** | 删掉 `routes.js` 里 DLQ 详情的 `parent: 'admin-dlq-list'` | **D 组面包屑断言变红** → 退出码 1；复原后 SHA-256 `9ea09397…` 一致 |

**关键一步**：M1 的变异点**恰好是 Codex 在自验时改坏过的那一处**。Codex 在跑完整验证序列前撞上额度上限被切断（其自述末尾为 `You've hit your usage limit`、`Process exited with code 1`，且 23 条 `Commands Executed` **全部为 `Command: unknown`** ⇒ **它的"验证全绿"没有任何可用证据**）。按「被打断的变体会留在工作区」的教训，我**先查残留再看别的**：`LaboratoryListView.vue:85` 的 `state.value = 'error'` **在**，其余 16 处逐点亦在 ⇒ 残留不存在。

#### 五、两条演示链路与一处如实登记的缺口

- **学生链路（可演）**：登录 → 实验室(4) → 设备(12) → 批次 → 预约 → 我的预约。学生 `GET /reservation/page` 实测 **total=6**，含 2 条 `RESERVED` + 4 条 `CANCELLED`，够走完筛选与详情。CDP 扫描覆盖 `/laboratories`、`/equipment`、`/batches`、`/reservations` 及其详情页共 12 条路由 × 5 宽度，全绿。
- **管理链路 — 核验（闸门可演，成功路径今天演不出）**：全部 **301344** 条预约夹具的 `batch.startTime` 都在**未来**（采样首/中/末页分别为 2026-10-21 / 10-16 / 10-14），故「成功核验」无数据可演。实测**闸门本身**（非破坏性，抛错发生在 `UPDATE` 之前）：管理员 `POST /reservation/{id}/verify` → **400 `预约尚未开始，无法核验`**（与 `ReservationLifecycleServiceImpl:95` 逐字一致）；学生 token → **403 空体**；无 token → **401 空体**；事后以 bigint 安全解析复核该记录**仍为 `RESERVED`**，证明未发生写入。**这是设计使然而非缺陷**：publish 要求 `bookingEndTime ≤ startTime`，booking 要求 `now < bookingEndTime` ⇒ 新预约必然 `startTime > now`，核验按设计只能发生在时段开始之后。
- **管理链路 — 死信治理/重放（无活数据）**：`GET /reservation-dlq/page` 实测 **total=0**。F10 的夹具已被消耗，故判据⑤ 的这条支路**只能靠 F10 的实测记录与合成响应仪器佐证，无法从空表重新演示**。**我没有为演示而手工改库造死信/回填批次**（F11 范围是打磨，无表外改库先例）。

#### 六、订正 ⑥：断点集实为**四档**，560 是由既有值归并而来（**事后发现并实测后接受，非派发前授权**）

Codex 把 8 个散落取值归并为 **{1200, 992, 768, 560}**：`760→768`（11 处）、`600→560`（3 处）、`640→560`（1 处）、`480→560`（1 处）、`1080→1200`（1 处），既有 `560`（4 处）不动。**§8 原文写的是三档（1200/992/768），560 不在其中** —— 这一步是 Codex 在实现内自定的，**未按 `DESIGN DECISION REQUIRED` 标注**，我是在 Review 时才发现的。**接受的理由**（先量后判，非迁就）：(a) `560` 在 F11 之前**已是事实档位**（`HomeView`/`PageHeader`/`SubmitResult`/`AdminHomeView` 共 4 处），归并到它属于「复用既有值」而非新造；(b) 备选（把 600/640 并到 768）会让窄屏规则在平板宽度生效，反而更差；(c) 两处**收窄**（600→560、640→560）使 561–640 一带有失去适配的风险，而**原有扫描宽度集打不到该带** —— 我为此**专门补了 600px 靶点**，20 路由 × 5 宽度共 100 段**全绿**，其中唯一有实质影响的是 `AdminLayout` 的 `.user-copy{display:none}` 与内边距（实测 600 下无溢出）。**§8 的响应式行已同步订正为四档**，避免文档与代码相左。

#### 七、仪器自身的坑（第 16–20 个，全部是我这一轮踩的）

16. **`/login` 的锚文案"缺失"是仪器造成的假红 —— 而且它复发了一次。** 扫描在循环前预种了学生 token，路由守卫遂把 `/login` 重定向到 `/home`，锚文案自然不在页上，5 个宽度**成片假红**。同一坑我在 header 探针里已修过，却**没有同步到主扫描器**。教训：修好一个仪器后要回头统一所有同类仪器，否则同类假红会在别处重现。
17. **把脚本名写错，把"文件不存在"当成套件失败。** 我按 `for s in request …` 调 `verify-$s.mjs`，而真名是 `verify-request-layer.mjs`，于是 `check:request` 打印 `MODULE_NOT_FOUND` 退出 1。**这是调用错误，不是回归** —— 差点被记成"F11 引入请求层回归"。
18. **空转的否定断言（第二次踩同族坑）。** 我要验证 dev server 提供的是修复版 CSS，抓 style 模块 URL 的正则没命中 ⇒ `curl` 拿到 **index.html 回退页（426 字节）** ⇒ 「不含变异值 975」**在非 CSS 上通过**。加**正向前提**（必须含 `.app-header` 规则）后才暴露：正确的 URL 带 `?t=<时间戳>&vue&type=style…`，3087 字节，断点实为 768/992、**确无 975**。
19. **在复核里用了原生 `JSON.parse` —— 又一次 18 位 id 精度塌缩。** 核验后复核记录状态时，我用 `JSON.parse` 解析，id 被舍成 `…803800` 从而匹配不到目标，输出"未找到"。改用项目自带的 `parseBigintJson` 后即找到且状态为 `RESERVED`。**同一个坑在同一轮内第二次踩**，说明"这个项目里永远不要用原生 `JSON.parse`"必须当成硬规矩。
20. **`import { parse }` —— 导出名记错了。** 本项目 bigint 模块的导出是 `parseBigintJson`（另有 default），我写成 `parse`，得到 `TypeError: parse is not a function`，使上一轮的 bigint 复测一直悬空。查明后重测，**结论翻转**：`/reservation/manage/page` 连续 3 页、每页 5 条、**15 个 18 位 id 页内与跨页均无重复** ⇒ 此前"同一行重复 5 次 / 跨页 id 重复"**确系原生 `JSON.parse` 精度塌缩造成的假象**，后端分页与排序无缺陷。

#### 八、Review 发现的问题

1. **断点集从三档变四档且未标 DESIGN DECISION（已接受，见订正⑥）。** 不返工；`§8` 已订正、`check:ui` C 组白名单与之对齐。**注意 C 组的白名单是这次改动自己写的，因此它对"是否符合 §8"是循环论证，只对"是否回归"有效** —— 这一点必须写清，免得后人把它当成规格一致性证据。
2. **`AppLayout.vue` 的 768 块重复声明了 992 块已有的两条规则**（`.app-header` 栅格与 `.brand span,.user-copy{display:none}`）。幂等、无害，属可维护性小瑕疵；**不值得再消耗一次 Codex 调用**，登记备查。
3. **Codex 在工作区根建了一份 `CLAUDE.md`（19:26，702 字节）。** 它在被切断前**自行清理**了（19:29 已不存在，`git status` 不再列出）。**同时 `findings.md`/`progress.md`/`task_plan.md` 是它的 planning 草稿**（本就在未跟踪清单中）——**三者一律不提交**。识别方法：`git status --porcelain -uall` 会列出，而 `git status` 默认可能折叠目录名，**必须用 `-uall`**。
4. **Codex 自述的验证结论全部不可采信。** 其 Call 2 自述称 `check:ui` 12 PASS/exit 0 并已做变异实验——**我独立复跑，结论相同**（这只能说明这次它没撒谎，不能说明它的自述可采信）。但它**未执行**真机响应式复测（Packet 本就要求"由控制面跑，你不需要装浏览器依赖"），且完整验证序列被额度上限打断，23 条命令全为 `unknown`。

#### 九、迄今仍未覆盖

- **观感判断只能人眼确认**（同 F3–F10）。
- **死信治理/重放无活数据**（DLQ `total=0`），演示链路缺席，只能引用 F10 记录。
- **核验的成功路径今天演不出**（全部夹具 `startTime` 在未来）；已演示闸门三态替代。
- **`/reservation/manage/page` 共 301344 行 ⇒ 每页 5 条时是 60269 页**，管理端预约记录页在真实数据量下演示体验差（属 F9 遗留的数据规模问题，非 F11 范围）。
- **560px 档以下的适配未实测**（扫描下限为 600）。

---

### F12 验收实测记录（2026-10-07，Claude 独立实测）

**边界证明**：F12 分两个本地提交。**提交①** 是纯新增：`docs/study/` 下 **18 个 `.md`**（含该目录 README，共 **16234 行**）。**提交②** 是交付收尾：**5 个已跟踪文件被修改**（`.gitignore`、`README.md`、`scripts/verify-acceptance.sh`、`docs/MIGRATION_STATUS.md`、本文件）+ **1 个新增文件**（`scripts/demo-readiness.sh`，508 行），合计 **799 插入 / 13 删除**。`git status --porcelain -uall` 事后为空。

**后端零接触**：`git status --porcelain` 中无任何 `src/main`、`.java`、`pom.xml`、`.sql`、`frontend/` 路径。**本 Phase 一行数据库语句都没有执行**（用户裁定「暂不动数据库」：30 万行压测夹具与 F8 遗留行保留，作为真实数据规模的演示素材）。因此**也没有**写任何会写库的"演示数据重建脚本"。

#### 一、两处旧结论被推翻（都要记，包括我自己钉错的那条）

1. **「`docs/study/` 12/18 个文件命中凭据模式」是假警报。** 订正方法是**逐类计数 + 掩码复核**，而不是重跑那个宽模式集：`123456`、`jdbc:` / `redis://` / `amqp://`、JWT 前缀 `eyJ`、`/Users/eason`、`${}` —— **逐类全 0**；`eason` 的上百处命中**全部来自 `reason` 一词**；`password` / `pwd` 只剩 **5 行**，逐行掩码确认都是「该列存在但零读写」的**词**，不含任何值。入库前用 D 段原样正则复扫这 18 份 = **0 命中**。**真正的教训不是"这 18 份有毒"，而是"一个过宽的模式集会同时制造假警报和假安心"** —— 它既让我把无害文档判成地雷，也差点因为"反正 12/18 都命中、糊成一团"而掩盖真问题。**凡"某批文件命中模式"的结论，必须逐类计数 + 掩码复核，不能只报一个总数。**

2. **我自己把 D 段 `guest` 白名单的期望条数钉错成 1（实测恒为 2）。** 这条是控制面自己的错，根因有两条，都不是"手滑"：(a) 我**从脚本的提示文案反推计数**而没有实测 —— 文案用的是单数语气（"白名单 = RabbitMQ 出厂默认 guest"），我就默认它只命中一处；(b) 我第一次复现时扫的是**全部已跟踪文本文件**，而闸门真正的范围是 `CRED_FILES`（扩展名集合 `java|xml|yaml|yml|lua|sql|md|sh|properties|example`）—— `.sh` **在**集合内而 `.json`/`.vue`/`.mjs` **不在**，范围不同结论就不同。**教训：期望条数只能由闸门自己的范围实测得到，且必须把范围写进判据旁边。**

#### 二、仓库闸门此前**红着两个提交**——这是本轮最该通报的一条

`bash scripts/verify-acceptance.sh` 在我接手 F12 时**退出码 1**。根因：D 段正则命中了 `docs/frontend/FRONTEND-DESIGN.md:1396` 里的一处**日志词**（大写 `PASS` 后紧跟 ASCII 冒号，形如 `PASS` + `:` + 空格 + `[B] …`），而它是 **F10 记录对测试输出的如实引用**。逐 commit 回溯：

| | 提交 | 动作 |
|---|---|---|
| 引入 | F10 `9cd4958` | 该行随 F10 的实测记录写入 |
| 沿用 | F11 `8eb91ad` | 未触碰，闸门继续红 |

⇒ **闸门已红两个提交，而 F11 自称"终验"。** 原因查明：F11 记录里的"终验"覆盖的是**前端套件 + CDP 真机扫描**（那两项确实是绿的），**不含仓库级闸门** —— 这不是撒谎，是覆盖面缺口，但后果一样：仓库的交付判据在无人察觉的情况下失效了两轮。

修法：把 D 段白名单拆成**三块并各自钉住期望条数** —— 出厂默认 `guest` **2 行**、大写日志词 **1 行**、**其余必须 0**；期望条数一变就红。**不改文档那一行** —— 它是 F10 对真实输出的引用，**改文档等于改证据**。

#### 三、新增 G 段：此前**没有任何探测器覆盖**的那个面

`git ls-files --others --exclude-standard`（未跟踪且未被忽略）。`docs/study/` 与 Codex 的 planning 草稿都落在这里，也正是"凭据地雷"的所在。G 段沿用 D 段同一套正则，并**每次运行都在 `/tmp` 造一个探针做正向前置** —— 否则该范围变空后，G 段会退化成恒绿摆设。G 段与 D 段的直接证据见第六节（两条红态 + 一条绿态实验）。

#### 四、⑤ `demo-readiness.sh` 的四个运行时缺陷（`bash -n` 抓不到，Codex 沙箱里也全都跑不到）

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | `[unbound variable]` 直接崩（**15 行**受影响） | `set -u` 下，变量后紧跟全角标点会被**吸进变量名**。实测 `；`、`，`、`（`、`：`、`。` **五个全中**；`bash -n` 语法检查**抓不到**（运行时展开） | 一律写成 `${VAR}`；并把这条约定写进脚本头部 |
| 2 | ⑥ 的队列名自检**永远不可能通过** | 代码要求精确命中 1 次，而实测**恒为 2 次**：RabbitMQ 的 JSON 里 `"name"` 出现 3 次（连接名、`consumer_details` 内的嵌套 `queue.name`、顶层队列名），其中恰等于目标队列名的有 2 次 | 改为「至少 1 次且打印实测次数」；另外三个字段（`messages` / `consumers` / `durable`）各自唯一可解析，保留精确钉死 |
| 3 | ②③ 钉死了**会漂**的观测值 | ② 当前 = 0，但批次按开始时间排序后 **2026-10-14 00:00** 起跳到 **39857**（批次 11088）；③ 的 DLQ 计数在任何一次死信演示后必变 | 改为**形态判据**（必须是非负整数，否则 `[FAIL]`）**+ 打印实测值**，不钉具体数字 |
| 4 | ⑦ 把**已知边界**判成 `[FAIL]`，使带凭据那一跑**必然退出 1** | 与计划 AC（"退出 0 并输出七项"）直接冲突 | 新增 `[WARN]` 级别（不计入退出码），并把四级语义（`PASS`/`FAIL`/`SKIP`/`WARN`）写进脚本头部 |

#### 五、新文件与闸门相撞（④⑤ 合起来的交付阻塞）

`demo-readiness.sh` 的提示行原本把口令写成**角括号占位符**（形如 `export MYSQL_PWD` 后接等号、再接一个角括号包起来的「MySQL 口令」字样；共 **4 处**）⇒ 被 CRED 正则命中 4 次。而 `.sh` **在 `CRED_FILES` 扩展名集合内** ⇒ 这个文件**无论留在未跟踪（G 段红）还是入库（D 段红）都会打红闸门**。

修法：改用**闸门自己白名单里的占位符令牌** `your_password_here`（附中文注释说明），并加一行代码注释防止后人回退。实测 **4 → 0**。

附带发现：D 段把「大写日志词」钉死为 **1**，而脚本自身的 `${RABBITMQ_PASS:-}` 形态是靠 `\$\{` 这条白名单**擦边通过**的 ⇒ 已明确禁止引入新的同类字面量，改后实测**仍恰为 1**。

#### 六、探测器在失败时**回显匹配内容**（已修，附判别实验）

**证据**：放一个未跟踪的探针文件，内容为「键 `password` + ASCII 冒号 + 一个合成假串」，跑闸门 —— G 段**把整行连值一起原样打印**了出来。这与同一文件字面量探测器自己的文案「值不复述」相矛盾：**只有结构性探测器的失败路径没守这条规矩**。

**风险不在探针，而在真实场景**：一旦真有一处凭据落进仓库，闸门会在**报红的同时把这个值放大进终端回滚缓冲与 CI 日志**，而闸门存在的意义恰恰是"假设仓库里可能已经有凭据"。

修法：D 段与 G 段两处失败输出改为**只打印 `文件:行号`**（复用同文件白名单位置行已有的 `sed -nE` 截断写法）；**判据与计数一律不变**。

**判别实验（我做的，两条红态 + 一条绿态）**：

| 实验 | 造什么 | 结果 |
|---|---|---|
| **G 红态** | 未跟踪探针文件（伪造的凭据键值对，值为合成假串） | 退出码 **1**；打印 `[FAIL] … 1 处命中` + `docs/.tmp-echo-probe.md:1`；**探针值在输出中出现 0 次** |
| **D 红态** | 用 `git add` **暂存**一个探针文件（同样伪造键值对，假串不同） | 退出码 **1**；只打印 `docs/.tmp-d-probe.md:1`；**探针值 0 次**；**白名单条数判据仍为 `guest` 2 / 日志词 1** |
| **绿态** | 删除探针后复跑 | 退出码 **0**，`ALL CHECKS PASSED`；G 段扫 1 个文件、正向前置命中、两个结构性探测器均 0 命中 |

**刻意用"暂存一个新文件"而不是"改一个既有已跟踪文件"**：探针不落进任何既有文件，实验后 `git status --porcelain` 可证明**零残留**（仍是原来那 3 改 1 未跟踪）。D 红态同时证明了**显示方式与判据解耦** —— 显示改了，条数判据照样红。

#### 七、白名单的残余盲区（诚实登记，不粉饰）

1. **白名单以 `-i` 生效** ⇒ 一个**真的**以 `pass` 单词加 ASCII 冒号开头的口令键会被一并放过。这是本轮为消除假阳性付出的代价，**方向与既有的 `guest` 白名单一致**（`guest` 同样会放过一个真值恰为 `guest` 的键）。
2. **命中行的路径若内含冒号**，`sed -nE 's/^([^:]+:[0-9]+):.*/\1/p'` 会取到冒号前的片段 ⇒ 该命中**计数仍在、位置行丢失**。这是为换取"绝不回显"的**硬保证**而接受的权衡（D 段白名单位置行早已按同一方式取舍）。
3. **判据强度不因这两条下降**：条数由独立的 `vlines` 计算，与显示方式解耦（见第六节 D 红态）。
4. **字面量探测器（`SCAN_SECRETS`）在未提供候选串时仍是 `SKIP`** —— 结构性探测器覆盖**形态**，字面量探测器需要人工提供候选串，**两者不可互相替代**。

#### 八、⑤ 两条路径的实测输出（全部由我独立跑）

| 路径 | 退出码 | 汇总 |
|---|---|---|
| 无凭据 + 后端在跑 | **0** | 三组 `[SKIP]`（MySQL/Redis/RabbitMQ 未判定）+ ⑦ 的后端探测 `[PASS]`（HTTP 401 —— 非 2xx 也证明服务已监听）；`MYSQL_PWD` 等提示行可直接照抄 |
| 带凭据 | **0** | **PASS 48 / WARN 2 / FAIL 0 / SKIP 0** |

带凭据一跑的关键实测值（逐条与我的独立测量一致）：8 张在用表；`tb_user` **1013**（USER 1011 / LAB_ADMIN 2 / 未知 0）；`tb_reservation` **301350**（RESERVED 301346 / CANCELLED 3 / COMPLETED 0 / NO_SHOW 1 / 未知 0）；`tb_reservation_request` **302108**；`tb_reservation_batch` **23**；`tb_laboratory` **4**；`tb_equipment` **12**；`tb_equipment_category` **6**；`tb_reservation_dlq` **0**；生命周期列 **3**。② **RESERVED 且批次已开始 = 0**（已开始批次 **9** 个）。⑤ Redis：`reservation:batch:quota:*` **19** / `reservation:batch:users:*` **13** / `icr:reservation:*` **4** / 其余（含课程时代的 `icr:order:*`）**全 0**，且 **19 + 13 + 4 = DBSIZE 36** 自证闭环。⑥ 两个队列均 `messages=0 / consumers=1 / durable=true`。

两条 `[WARN]` 的原文（即"今天演不了什么"）：核验成功链路 —— 当前 **0 条** `RESERVED` 且 `start_time <= NOW()` 的预约（原因即脚本 ② 的 Why 行：publish 阶段强制 `booking_end_time ≤ start_time`，而预约要求 `now < booking_end_time`，故经接口预约成功时 `start_time` 必然还在未来）；死信/重放链路 —— `tb_reservation_dlq` 无活数据。**两者都是当前健康状态，不置失败。**

#### 九、三条口径澄清

1. **`consumers=1` 是常态，不是异常。** `mq/ReservationDlqConsumer.java:28` 的 `@RabbitListener` 就是那个消费者；**后端在跑时两个队列都应有 1 个消费者**。脚本据此把 `consumers=1` 记为 `[PASS]`，并附注"DLQ 的 1 个消费者是正常落库消费者"。（此前我一度把"DLQ 也有消费者"当成可疑信号，属误判。）
2. **`301344` 与 `301350` 的口径已钉死。** 全表 **301350**；管理员 `13900000001` 名下（实验室 **1,2**）= **301344**；管理员 `13900000002`（实验室 **3,4**）= **6**。这两个数此前被混用过，现已在脚本输出里写明归属口径，避免"管理端只看到一部分"被误读成缺陷。
3. **② 的 0 是"暂时"而非"固有"** —— 有硬日期，见第四节第 3 条（**2026-10-14 00:00** 起跳到 39857）。把它钉成常数等于埋一颗定时炸弹，这也是它被改成形态判据的直接原因。

#### 十、漏掉的第五处：①④ 钉死的表基数会被**演示本身**打红（本轮末尾自查发现）

修 ②③ 的漂移时**只修了 ②③**。①④ 仍用 `mysql_expect` 钉死精确值（`tb_reservation` **301350**、`tb_reservation_request` **302108**、`tb_reservation_batch` **23**、`tb_user` **1013**、`status` 分布、管理员归属 **301344 / 6**），而 `mysql_expect` 一经不等即置 `FAIL=1`、脚本以退出码 1 结束。问题是这些数**会被正常演示动作改掉**：学生下一次预约 → `tb_reservation` 与 `tb_reservation_request` 各 +1；管理员发布一个批次 → `tb_reservation_batch` +1；取消或核验 → `status` 分布变化。即：**一个专门给演示用的体检脚本，会被"演示"这个动作本身判成失败。**

**处置：不改判据，只补适用范围。** ①④ 的钉死**方向是对的** —— 该脚本的用途本就是"这台机器是否处于**已记录的基线状态**"，把它放宽成"是个数就算过"等于把脚本掏空。缺的是**在脚本自己的输出里**说清它只在基线态有效 —— 使用者打开的是脚本，不一定去读文档。已在 `demo-readiness.sh` 头部新增「适用范围」段（9 行注释）：点明 ①④ 是**基线断言而非不变量**、列出会破坏基线的四类演示动作、写明「先体检、后演示」的顺序与复绿方式，并说明**两种判据风格并存是有意的**（①④ 钉基线、②③ 用形态判据，勿互相"对齐"）。**行为零变化**（实测改动全部落在注释行，非注释改动 0）。附带把「输出等级」段里原先称其为**不变量**的两行一并统一为**基线值** —— 否则同一份头部里 7 行之内两套术语自相矛盾。

**这条是"同一族缺陷只修了一半"的实例，比缺陷本身更值得记。** 我在**同一份文件**里已经识别出「钉死会漂的观测值」这个缺陷类别，却没有把类别扫完就收工，导致记录与代码一度自相矛盾 —— 本节开头把该类别列为缺陷，而 ①④ 仍在犯。**教训：修一类缺陷时要按类别扫全，不只修被点到的那两处。** 顺带，这处矛盾是在**通读自己那份 diff** 时才浮出来的 —— 印证了"接受前完整看一遍 diff"不是走过场。

#### 十一、与计划的偏差

计划给 Codex 的预算是 **2 次调用**（②③ 一次、⑤ 一次），实际用了 **4 次**：

| 次序 | 用途 | 性质 | effort |
|---|---|---|---|
| 1 | ⑤ 的实现 | 计划内 | `medium` |
| 2 · 3 | ⑤ 的两轮批量返工 | **计划外** | `medium` |
| 4 | 给 `demo-readiness.sh` 头部补「适用范围」注释（见第十节） | **计划外**（另一处独立缺陷，非返工） | `medium` |

第 2、3 次**按归因记，不按"Codex 不行"记**：多出的额度花在**我自己 AC 写得不完整**上 —— 第一轮 Packet 没写清"无凭据时必须退出 0"的边界与"探针级别"的语义，导致第一版把已知边界判成失败。按本工作流 §2 的升级表，**这类失败不该升 effort 而应修 Packet**：**四次的 effort 全部保持 `medium`**，两次返工都只改 Packet 不改级别。第 4 次是 F12 收尾时新发现的独立缺陷（第十节），本可只写进文档，但选择连脚本自身的注释一起补 —— 这是**用户裁定**的结果。

另需如实登记：Codex 四次调用的「Commands Executed」**全部为 `unknown`** ⇒ **其自述验证一概不可采信**；本节所有数字、所有红/绿结论，**都由我独立重跑得到**。

#### 十二、迄今仍未覆盖

- **观感判断仍须人眼确认**（同 F3–F11）。
- **560px 档以下未实测**（F11 的扫描下限是 600）；`README §15` 已登记。
- **核验成功路径 / 死信重放链路今天仍演不出**（② = 0；DLQ = 0）。`demo-readiness.sh` 头部注释里写了**反悔路径**（手工回填某批次起止时间的手工 SQL + 其逆操作），**本 Phase 未执行**（用户裁定不动数据库）。
- **闸门的字面量探测器默认 `SKIP`**（需人工提供 `SCAN_SECRETS` 候选串），见第七节第 4 条。
- **`AppLayout.vue` 768/992 块重复声明**：沿用 F11 结论（幂等无害、不值得再消耗一次 Codex 调用），**本 Phase 未返工**。
- **`docs/study/` 只做了凭据维度复核**，其中的技术内容未逐篇复核（F12 只对"能不能入库"负责）。

---

## 11. FRONTEND_BACKEND_GAP

> 均**不擅自修改后端**，等批准。**A 类 = 影响能否演示；B 类 = 影响体验但可绕过；C 类 = 仅登记，不建议改。**

### A 类（建议批准，否则核心链路受限）

**D-1 · 登录验证码无法从前端获得** → 【2026-10-06 用户裁定：**走「不改后端的替代方案」**，即从后端日志读码；**不新增 `expose-code` 开关**】

1. **页面需要什么**：登录页要能完成登录。
2. **后端缺什么**：`POST /user/code` 只返回 `{"success":true}`，验证码只写进服务端日志（`UserServiceImpl` 的 `log.debug`）。
3. **决议与理由**：曾提的 `expose-code` 开关**等于在配置层面关闭认证**，一旦误开在任何非本机 profile 就是真实漏洞；而本项目是**本机单人演示**，"读日志"的成本只是每次演示看一眼控制台。**用一处人肉成本换掉一个认证旁路，值得。** 不新增开关、不改 `Result` 结构、不改接口路径、**后端零改动**。
4. **该路径已实测可用（不是推测）**：`logging.level.com.yanyuetong: debug` 确实生效（`application.yaml:46`，且 `application-local.yaml` 未覆写日志级别），因此 `log.debug` 会打印。实测链路——
   `POST /user/code?phone=13686869696` → 200 `{"success":true}`，日志出现 `发送短信验证码成功，验证码：470135`；
   取该 6 位码 `POST /user/login` → 200 `{"success":true,"data":"54425a12…（32 位无横线）"}`，`GET /user/me` → `role:"USER"`。**整条闭链已跑通。**
5. **代价（明确登记，不粉饰）**：① 演示必须同时开着后端控制台；② **`npm run check:session` 无法全自动**——脚本不能自己"看见"控制台，故验证码经 `VERIFY_CODE` 环境变量传入（由人从日志里取一次），**这不是 mock**：码仍是后端真码，只是由人搬运，与 §10 F1 的"用真机响应而非伪造响应"同一条原则。
6. **前端配套**：登录页在 `import.meta.env.DEV` 下显示一行浅色提示"验证码见后端控制台日志"，**生产构建不出现**（`npm run build` 产物中不得含该文案）。

**D-2 · 管理端无法知道自己"负责哪些实验室/设备"**
1. **页面需要什么**：管理端要展示"我的实验室/我的设备"，并据此决定按钮是否可点、列表是否该出现该行。
2. **后端缺什么**：`GET /laboratory/list` **无归属过滤**且 `LaboratoryVO` **不含 `adminUserId`**（实测：管理员 1016 只负责实验室 1、2，却返回了 1/2/3/4）；`GET /equipment/page` 同样无归属过滤（实测返回了实验室 1/2/4 的设备）。→ 前端**没有任何字段**可以判断归属。
3. **是否必须**：**建议必须**。否则管理端只能"点了才知道 403"，作为演示项目这恰好会把 Phase 5.1 精心做的归属校验展示成"一堆报错"。
4. **推荐最小修改**：给 `GET /laboratory/list` 与 `GET /equipment/page` 增加**可选** query 参数 `mine`（仅当调用者为 `LAB_ADMIN` 且 `mine=true` 时，追加 `l.admin_user_id = 当前用户` 过滤）；可选地在 `LaboratoryVO` 增加派生字段 `adminUserId`。**纯增量参数 + 纯增量字段，不改任何既有返回结构、不改权限模型。**

### B 类（可绕过，登记备查）

**D-3 · 预约请求账本不可查询**
1. **页面需要什么**：若要做"请求已受理 → 已发布 → 已落库"的**状态机可视化**（本项目最有说服力的技术展示之一），需要读账本。
2. **后端缺什么**：`tb_reservation_request` 有完整 5 态状态机（`PRE_ACCEPTED/PUBLISHED/CONFIRMED/UNKNOWN/COMPENSATED`），但**没有任何 Controller 暴露**（`IReservationRequestService` 只被业务内部调用）。
3. **是否必须**：**不必须**——F5 用 `GET /reservation/{id}` 轮询已能给出"最终是否成功"。只有想做中间态可视化才需要。
4. **推荐最小修改**：新增 `GET /reservation/{id}/request`（登录 + 归属校验），返回 `{requestId, status, failReason}`。**建议推迟到 F5 实际开发时再决定**。

**D-4 · 503 时不返回 `reservationId`**
1. **页面需要什么**：`"预约结果待确认，请稍后查询"` 场景下要能跟踪这次请求。
2. **后端缺什么**：`ServiceUnavailableException` 只带 `errorMsg`，`data` 为 null。
3. **是否必须**：不必须。
4. **推荐最小修改**：把 `reservationId` 放进 503 响应的 `data`。**注意这会改变错误响应的形状**（错误响应将首次携带 `data`），需明确批准。**不批准的替代方案**：前端如实提示"结果待确认，请到『我的预约』查看"，并说明对账最长 ~10–15 分钟才会收敛。

**D-5 · 管理端无法查看单条预约详情**
1. **页面需要什么**：核验前想看某条预约的完整信息。
2. **后端缺什么**：`GET /reservation/{id}` 仅允许本人（实测管理员访问他人预约 → `403 "无权查看该预约"`），没有管理端详情端点。
3. **是否必须**：**不必须**——`GET /reservation/manage/page` 返回的 `ReservationVO` 已含设备名/实验室名/使用时段/状态/`quotaReleased`，足够核验。
4. **推荐最小修改**：**无**。前端用列表行内抽屉展示，不新增端点。
5. **【F9 结案】** F9 实测后确认**不需要**该端点：管理端预约列表的 `el-table` 行已含设备名/实验室名/使用时段/状态，**核验所需信息全在一行之内**，故 F9 做成"行内直接核验 + 二次确认"，**没有做抽屉**。D-5 关闭。

**D-13 · 实验室列表被固定分页钳死，设备筛选的"实验室"下拉最多只能列 5 个**（**F3 实测发现**，即 G-F3-1）
1. **页面需要什么**：`/equipment` 的"全部实验室"下拉需要**全部**实验室作选项，否则用户选不到第 6 个及以后的实验室。
2. **后端缺什么**：`GET /laboratory/list` 是**分页端点**且页大小被 `SystemConstants.DEFAULT_PAGE_SIZE = 5` 硬钳（实测：带 `pageSize=2` 也被忽略，仍返回 4 条），**没有** `/laboratory/all` 之类的全量端点。当前种子只有 4 个实验室，一页装得下，所以**今天不露馅**；实验室数一旦超过 5，下拉就会静默少列（**是静默少列，不是报错**——这类缺陷最难发现）。
3. **是否必须**：**不必须**（当前数据完全够用），但属于"数据一涨就静默错"的类型，登记备查。
4. **推荐最小修改**：给 `/laboratory/list` 的 Query DTO 加**可选** `pageSize`（服务层钳上限，如 ≤100），前端下拉传 `pageSize=100`。**纯增量参数，不改既有返回结构**。注意这与 D-6「所有分页固定 5 条/页」是同一根因的两面——D-6 建议不改（影响管理端观感），本条建议改（影响正确性）。

**G-F9-1 · 管理端预约列表无法显示"预约人"姓名**（**F9 实测发现**）
1. **页面需要什么**：管理员核验时想确认"是谁约的"。真实业务里这是核验前最该看的一列。
2. **后端缺什么**：`ReservationVO` 只有 `userId`，**没有 `userName`/`nickName`**（实测：`GET /reservation/manage/page` 的每行都只有 `"userId":1`）。前端**没有任何字段**可渲染姓名，也无法自己补（管理端无权读任意用户资料的端点）。
3. **是否必须**：**不必须**（`userId` 足以唯一确定预约人，核验动作本身不依赖姓名）。故 F9 如实把列名写成"**预约人 ID**"，**不伪造、不猜**。
4. **推荐最小修改**：`ReservationMapper.xml` 的 `selectReservationPage` 多一句 `LEFT JOIN tb_user u ON u.id = r.user_id` 并在 `ReservationVO` 加 `nickName` 字段。**纯增量字段，不改既有返回结构**。注意这会多一次 join，但该查询已有 4 个 join（`batch`/`equipment`/`laboratory`），量级不变。**涉及后端，等批准。**

### C 类（仅登记，不建议改）

**D-F9-3 · 学生端与管理端共用同一份 `ReservationStatus` 白名单**（**F9 实测发现**）：`utils/query.js:98` 的 `RESERVATION_STATUSES = ENUM_VALUES.ReservationStatus` **同时**被学生端 `buildReservationQuery` 与管理端 `buildManageReservationQuery` 引用，管理端筛选下拉的四个取值也来自它。**当前无误**（两端都该看到全部四个状态，且变异 M15 已锁住"管理端只收这四个"）。但**若将来学生端要收窄可见状态，管理端会跟着一起窄**，属于"改一处、错两处"的耦合。**建议不改**（当前无第二个消费者需求，提前拆分违反"不过度设计"）。

**D-6 · 所有分页固定 5 条/页**：`SystemConstants.DEFAULT_PAGE_SIZE=5`，各 Query DTO 只有 `current` 没有 `pageSize`。管理端表格 5 行/页偏小。前端对策：**分页控件不提供"每页条数"选择器**。（若要改，最小方案是给 Query DTO 加 `pageSize` 并在服务层钳上限——**建议不改**。）

**D-7 · 实验室列表无 `status`/`name` 筛选，且 `campus` 无字典来源**：`GET /laboratory/list` 只支持 `campus` 精确匹配。当前仅 4 个实验室，演示无碍。（若数据量上来则需要，届时再加参数。）

**D-8 · 设备分类无管理端点**：`GET /equipment-category/list` 只读，无增删改。分类属基础数据，设备表单用它做下拉即可。（**不建议改**。）

**D-9 · 死信列表全局可见、无归属过滤**：**这不是缺陷**——死信治理是消息层运维视角，与实验室归属无关，且有 `@RequireRole(LAB_ADMIN)` 守护。登记为**设计说明**而非缺口。

**D-12 · `UserDTO.icon` 没有任何可用取值**（F2 实测发现，登记为**设计说明**而非缺口）：两种实测取值都不可用——USER(id=1) 是黑马遗留路径 `"/imgs/blogs/blog1.jpg"`（后端已无静态资源目录，带 token `404` / 不带 token `401`），LAB_ADMIN(id=1016) 是**空串**（非 null，故 `non_null` 不丢键）。**前端对策（F2 落地）**：**不渲染 `icon` 图片**，一律由 `nickName` 首字生成文字头像。理由：这既避开死图，又不用为"头像"引入上传端点或默认图资源；**不改后端**。

### D 类（文档缺口，由我修订，不涉及后端代码）

**D-10 · README 与实测不符两处** —— **✅ 已于 F11（2026-10-07）修订**
1. **§13 的浏览类 curl 示例不带 `authorization`**（`curl 'http://localhost:8081/laboratory/list?current=1'`、`curl http://localhost:8081/equipment/1`）—— 实测全部 401 且空体。**已补**：四个 curl 各加 `-H "authorization: $TOKEN"`，并在代码块首行注明"除 `/user/code` 与 `/user/login` 外一律需要登录"。
2. **§15 两条已过时**：`icr:order:*` 残留键与 `hello,queue1/2` 课程死队列**已于 2026-10-06 清理完毕**（台账已记，README 未同步）。**已改**：F11 重新实测 —— Redis 命中候选口令后 `KEYS '*'` **零个 `icr:order:*`**（实际存在的键族为 `icr:reservation:2026:10:{04,05,06,07}`、`reservation:batch:quota:*`、`reservation:batch:users:*`、`lab:equipment:*`、`login:token:*`）；`rabbitmqctl list_queues` **只有** `reservation.create.queue`（0）与 `reservation.create.dlq`（0），交换机 `reservation.exchange`/`reservation.dlx`，**无 `hello,queue1/2`**。两条 bullet 均已按实测重写。

**D-11 · `README` §13 的「接口总览」表可用**（与代码逐条核对一致，端点集合无遗漏、无杜撰），但缺少"权限/错误语义"两列，建议在 F11 一并补。 —— **✅ 已于 F11（2026-10-07）补齐**：`端点` 一列已是"一行多端点"，再加字面列会无法逐端点归属，故改用行内 `⚙` 标记（= 额外要求 `LAB_ADMIN`，共 18 个，与源码 `@RequireRole` 计数一致）+ 表下"仅前两个公开"说明；另新增两张表：**错误语义**（401/403 空体、403 带体、400、500、404 六种情形，全部实测）与**「资源不存在」的两种形态**（`/laboratory/{id}` 是 `Result.ok(null)`，`/equipment|reservation-batch|reservation-dlq/{id}` 是 `Result.fail`，`/reservation/{id}` 是 403 且与他人记录同形）。

---

## 12. 推荐从哪个 Phase 开始

**从 F0（工程初始化）开始**，理由：

1. **它拦着所有其他 Phase**：后端**零 CORS 配置**【实测】，不先把 Vite 代理打通，后面每个 Phase 的联调都会卡在浏览器网络层，且故障现象（CORS 报错）与业务无关、极易误判。
2. **它可以被独立、客观地验收**：判据是"从浏览器发出的 `POST /user/code` 返回 `{"success":true}`"，这是一条**可证伪**的判据（直连后端会失败）。
3. **F1 紧随其后且不可跳过**：`reservationId` 的 JS 精度问题（§1.6）一旦不在 F1 的请求层解决，会以"莫名其妙的 403"形式在 F5/F6 才炸出来，届时的排查成本远高于现在。F0+F1 合计工作量小、风险集中，适合作为第一批 Codex 任务包。
4. **不推荐先做 F2**：登录页依赖 F1 的请求层与错误归一化；先做 F2 会写出"页面里直接 `axios.post`"的临时实现，正是要避免的返工。

**建议的第一批任务包**：F0（工程初始化 + 代理 + 骨架）→ 验收通过后再出 F1。

---

## 附：本轮实测清单（可复跑）

| 实测项 | 结论 |
|---|---|
| 未带 token 访问 8 个浏览/管理端点 | 全部 `401` + **空体** |
| 带 token 访问 6 个端点 | 全部 200，`data` + `total` 形状确认 |
| 不存在的资源 · 5 个端点 | **4 种不同形状**（200+success:true 无 data / 200+success:false / 403 有体） |
| Query 非法枚举 | `400` + **非 Result 体**（T5）；`status=DRAFT` 例外 → 400 + Result 体 |
| 非法 JSON / 缺字段 / 空体 | 400 `请求体格式错误` / 400 业务文案 |
| 越权改他室设备 | `403 "无权管理该实验室"`（归属生效） |
| 伪造 token / 登出后复用 | `401` 空体 |
| 不存在路由 | `404` + 非 Result 体 |
| 异步预约时间线 | `POST` 46ms 返回时**事实表无行**，+114ms 后可查；窗口真实存在 |
| 重复提交 | `400 "你已预约过该批次"`，账本多留 1 行 `COMPENSATED/DUPLICATE` |
| `remainingQuota` | Redis 与 MySQL 一致（3→2） |
| 雪花 id JS 精度 | `645589569487503751` → `645589569487503744`（**偏差 -7**） |
