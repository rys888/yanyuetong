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
| 响应式 | PC 优先；断点 1200 / 992 / 768；**≤992 管理端侧边栏折叠为图标**；表格允许横向滚动 |
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
| **F11** | ① 11 个页面都有 loading / 空态 / 错误态三种表现；② 无任何页面出现空白屏或未捕获异常；③ 窗口宽度 1440/1200/992/768 下布局不破（管理端侧栏在 ≤992 折叠）；④ 全站文案无"暂无数据"以外的不一致用词；⑤ **跑通两条完整演示链路**（学生全链路、管理员全链路）；⑥ 前端源码中无 `axios` 出现在 `views/` 目录（API 层隔离的可核验判据） | 按 §3/§4 的两条演示链路各走一遍；用 DevTools 设备模拟切换宽度；`grep -rn "axios" frontend/src/views` 应为空 |

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

**D-13 · 实验室列表被固定分页钳死，设备筛选的"实验室"下拉最多只能列 5 个**（**F3 实测发现**，即 G-F3-1）
1. **页面需要什么**：`/equipment` 的"全部实验室"下拉需要**全部**实验室作选项，否则用户选不到第 6 个及以后的实验室。
2. **后端缺什么**：`GET /laboratory/list` 是**分页端点**且页大小被 `SystemConstants.DEFAULT_PAGE_SIZE = 5` 硬钳（实测：带 `pageSize=2` 也被忽略，仍返回 4 条），**没有** `/laboratory/all` 之类的全量端点。当前种子只有 4 个实验室，一页装得下，所以**今天不露馅**；实验室数一旦超过 5，下拉就会静默少列（**是静默少列，不是报错**——这类缺陷最难发现）。
3. **是否必须**：**不必须**（当前数据完全够用），但属于"数据一涨就静默错"的类型，登记备查。
4. **推荐最小修改**：给 `/laboratory/list` 的 Query DTO 加**可选** `pageSize`（服务层钳上限，如 ≤100），前端下拉传 `pageSize=100`。**纯增量参数，不改既有返回结构**。注意这与 D-6「所有分页固定 5 条/页」是同一根因的两面——D-6 建议不改（影响管理端观感），本条建议改（影响正确性）。

### C 类（仅登记，不建议改）

**D-6 · 所有分页固定 5 条/页**：`SystemConstants.DEFAULT_PAGE_SIZE=5`，各 Query DTO 只有 `current` 没有 `pageSize`。管理端表格 5 行/页偏小。前端对策：**分页控件不提供"每页条数"选择器**。（若要改，最小方案是给 Query DTO 加 `pageSize` 并在服务层钳上限——**建议不改**。）

**D-7 · 实验室列表无 `status`/`name` 筛选，且 `campus` 无字典来源**：`GET /laboratory/list` 只支持 `campus` 精确匹配。当前仅 4 个实验室，演示无碍。（若数据量上来则需要，届时再加参数。）

**D-8 · 设备分类无管理端点**：`GET /equipment-category/list` 只读，无增删改。分类属基础数据，设备表单用它做下拉即可。（**不建议改**。）

**D-9 · 死信列表全局可见、无归属过滤**：**这不是缺陷**——死信治理是消息层运维视角，与实验室归属无关，且有 `@RequireRole(LAB_ADMIN)` 守护。登记为**设计说明**而非缺口。

**D-12 · `UserDTO.icon` 没有任何可用取值**（F2 实测发现，登记为**设计说明**而非缺口）：两种实测取值都不可用——USER(id=1) 是黑马遗留路径 `"/imgs/blogs/blog1.jpg"`（后端已无静态资源目录，带 token `404` / 不带 token `401`），LAB_ADMIN(id=1016) 是**空串**（非 null，故 `non_null` 不丢键）。**前端对策（F2 落地）**：**不渲染 `icon` 图片**，一律由 `nickName` 首字生成文字头像。理由：这既避开死图，又不用为"头像"引入上传端点或默认图资源；**不改后端**。

### D 类（文档缺口，由我修订，不涉及后端代码）

**D-10 · README 与实测不符两处**
1. **§13 的浏览类 curl 示例不带 `authorization`**（`curl 'http://localhost:8081/laboratory/list?current=1'`、`curl http://localhost:8081/equipment/1`）—— 实测全部 401 且空体。应补上 header 并说明"所有接口（除登录相关两个）都需要登录"。
2. **§15 两条已过时**：`icr:order:*` 残留键与 `hello,queue1/2` 课程死队列**已于 2026-10-06 清理完毕**（台账已记，README 未同步）。

**D-11 · `README` §13 的「接口总览」表可用**（与代码逐条核对一致，端点集合无遗漏、无杜撰），但缺少"权限/错误语义"两列，建议在 F11 一并补。

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
