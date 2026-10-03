# Phase 3 Review

## Result

PASS

审查对象：Phase 3「User & Authorization 用户与权限」
规格依据：`docs/refactor/PHASE3-DESIGN.md`（用户已批准）+ `实验资源预约平台-分阶段重构Phase计划.md` §6
审查方式：逐行核对 diff、直连数据库核验、启动应用亲自跑 401/403/200 全矩阵、构造遗留 Session 验 fail-safe
审查人：Claude（Control Plane），2026-10-04

---

## 1. 交付物核对

**改动范围精确**：8 个修改文件 + 4 个新增文件，与设计 §Scope 完全一致。

| 类别 | 文件 |
| --- | --- |
| 新增 | `enums/UserRole.java`、`annotation/RequireRole.java`、`utils/RoleInterceptor.java`、`resources/db/phase3_user_role.sql` |
| 修改 | `entity/User.java`、`dto/UserDTO.java`、`service/IUserService.java`、`service/impl/UserServiceImpl.java`、`controller/UserController.java`、`config/MvcConfig.java`、`controller/EquipmentController.java`、`controller/LaboratoryController.java` |

`git diff --stat`：8 files changed, **+38 / −3**（`UserController` 的 −3 为移除 `TODO` 桩）。

**禁止清单零触碰**（逐文件核对，全部无输出）：`CacheClient`、`LoginInterceptor`、`RefreshTokenInterceptor`、
`UserHolder`、`RedisConstants`、`SystemConstants`、`VoucherOrderServiceImpl`、`RabbitMQConfig`、`MybatisConfig`、
`RedissonConfig`、`UploadController`、`UserInfo` 四件套、`pom.xml`、`application.yaml`、Phase 2 域模型、`*.lua`。

---

## 2. 计划 §6 验收标准逐条核验

| 验收标准 | 结论 | 证据 |
| --- | --- | --- |
| 登录流程正常 | ✅ | 三种身份（管理员/既有用户/全新手机号）均成功取得 token |
| Redis Session 正常 | ✅ | `HGETALL login:token:{t}` 含 `role`，值 `LAB_ADMIN` / `USER` |
| 普通用户和管理员权限边界明确 | ✅ | 普通用户 **7/7 管理端点 → 403**；管理员同 7 个 → 200；查询端点双方均 200 |
| 登出完成 | ✅ | logout→`{"success":true}`；`EXISTS login:token:{t}`=**0**；旧 token 再请求→**401** |
| 未登录接口控制正确 | ✅ | 无 token 访问查询/管理/logout → **401** |
| 不引入大型权限框架 | ✅ | `grep -rniE "jwt\|shiro\|spring-security\|sa-token" src/ pom.xml` → **0 hits**；`pom.xml` 零改动 |
| Maven compile 成功 | ✅ | `mvn clean test-compile` BUILD SUCCESS（68 main + 2 test） |

---

## 3. 运行时实测记录（Claude 独立执行，非采信 Codex）

应用以 JDK 8 启动于 `18081`（先 `lsof` 确认监听者为 java 进程；本机 8081/8082 被他项目占用）。
所有 curl 加 `--noproxy '*'`。

### 3.1 未登录（401）

```
GET  /equipment/page    → 401
GET  /laboratory/list   → 401
POST /laboratory        → 401
POST /user/logout       → 401
```

### 3.2 普通用户 `13800138000`（既有用户）

```
Redis HGETALL:  role=USER  nickName=user_yxs3wqjv0q  icon=  id=1015
GET /user/me → {"success":true,"data":{...,"role":"USER"}}

管理端点（应 403，关键是不能退化成 401）
  POST   /laboratory         → 403
  PUT    /laboratory         → 403
  DELETE /laboratory/999     → 403
  POST   /equipment          → 403
  PUT    /equipment          → 403
  PUT    /equipment/1/status → 403
  DELETE /equipment/999      → 403

查询端点（应放行）
  GET /equipment/page          → 200
  GET /equipment/1             → 200
  GET /laboratory/list         → 200
  GET /laboratory/1            → 200
  GET /equipment-category/list → 200
```

### 3.3 管理员 `13900000001`（幂等种子）

```
Redis HGETALL:  role=LAB_ADMIN  nickName=实验室管理员  id=1016
GET /user/me → {"success":true,"data":{...,"role":"LAB_ADMIN"}}

  POST   /laboratory        → {"success":true,"data":8}
  PUT    /laboratory        → {"success":true}
  GET    /laboratory/8      → 200，room 已更新为 R2（部分更新正确）
  POST   /equipment         → {"success":true,"data":14}
  PUT    /equipment/14/status?status=MAINTENANCE → {"success":true}
  DELETE /equipment/14      → {"success":true}
  DELETE /laboratory/8      → {"success":true}
```

### 3.4 登出闭环

```
POST /user/logout (带管理员 token) → {"success":true}
EXISTS login:token:{t}             → 0
旧 token 再请求 GET /equipment/page → 401
POST /user/logout (无 token)        → 401
```

### 3.5 全新用户默认角色（验证 `createUserWithPhone` 陷阱已堵）

用从未出现过的手机号登录，触发自动注册：

```
Redis role           → USER
DB tb_user.role      → USER
POST /laboratory     → 403
GET  /equipment/page → 200
```

这条是本 Phase 最容易被漏掉的缺陷点（MySQL 列默认值救不了内存中的 Java 对象），
**已确认 `user.setRole(UserRole.USER)` 落实且端到端生效**。测试用户已删除。

### 3.6 遗留 Session fail-safe（我追加的健壮性验证）

手工构造一个**不含 `role` 字段**的 Session（模拟 Phase 3 上线前登录、仍在 TTL 内的会话）：

```
HGETALL:  id=1015  nickName=legacy  icon=      ← 无 role
GET  /user/me        → 200（role 为 null，被 jackson non_null 省略）
GET  /equipment/page → 200
POST /laboratory     → 403   ← 关键：是 403，不是 500
DELETE /equipment/1  → 403
```

证明 Hutool `fillBeanWithMap` 在字段缺失时**不抛异常**（`isIgnoreError=false` 也不会），
且鉴权**失败关闭**（fail-safe）而非崩溃或误放行。设备 1 未被删除（复核 `COUNT(*)=1`）。

### 3.7 数据库核验（直连，非采信）

```
role 列：  varchar(16)  NOT NULL  DEFAULT 'USER'  COMMENT '角色：USER/LAB_ADMIN'
角色分布： USER=1011   LAB_ADMIN=1
种子：     id=1016  13900000001  实验室管理员  LAB_ADMIN
既有用户： id=1015  13800138000  USER
```

`1011` = Phase 2 时的既有用户总数，**全部自动获得默认 `USER`，无一破坏**。
`tb_laboratory=4`、`tb_equipment=12`、`tb_equipment_category=6` —— Phase 2 基线数据未被 Phase 3 破坏，
Codex 的临时数据已清理干净。

### 3.8 幂等性

`phase3_user_role.sql` 连跑两次均退出 0，第二次输出 `tb_user.role already exists`。
（吸取 Phase 2 Review 中"种子 INSERT 不幂等"的教训，本脚本用 `information_schema` 守卫 + `ON DUPLICATE KEY UPDATE`。）

### 3.9 回归

```
mvn test → Tests run: 2, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS
```

---

## 4. 实现细节核对

| 项 | 设计 | 实际 | 结论 |
| --- | --- | --- | --- |
| `UserRole` | 两值最小枚举 | `USER, LAB_ADMIN` | ✅ |
| `RequireRole` | `@Target({METHOD,TYPE})` `RUNTIME` `UserRole[] value()` | 逐字一致 | ✅ |
| `RoleInterceptor` | 方法注解→类注解→放行；401 防御；命中放行；否则 403；无 body | 逐字一致 | ✅ |
| 拦截器注册 | `/**` + `order(2)` + 无 exclude | 一致（`RefreshToken=0`、`Login=1`） | ✅ |
| 管理端点标注 | Equipment 4 + Laboratory 3，**GET 一律不标** | 7/7 精确，GET 全未标注 | ✅ |
| `logout` | service 判空删除 + `UserHolder.removeUser()` + 幂等 | 一致 | ✅ |
| `login:token` 结构 | 多一个 `role` 字段 | 一致，`RedisConstants` 零改动 | ✅ |

`RoleInterceptor` 中「注解变量名 `requireRole` 与注解类型 `RequireRole` 同名」仅为命名重叠，
不影响正确性，与设计伪码一致。

---

## 5. 发现的问题

### P0

无。

### P1（不阻塞 Phase 3，须在指定 Phase 处理）

**P1-1｜LAB_ADMIN 无实验室归属约束（用户已裁决推迟）**

任何 LAB_ADMIN 可管理**任意**实验室与设备。计划 §6 称「管理**自己负责的**实验室」，
但归属模型经用户 2026-10-04 裁决**推迟到 Phase 5**（理由：其真实消费者"发布/关闭预约批次""履约核验"
在 Phase 5/9 才出现，现在建零消费者的关联表很可能建错）。

→ **Phase 5 引入归属时，需给已标注的 7 个端点补一层"且该实验室归我管"的判断**。
本 Review 明示此限制存在于 Phase 3–4 期间。

**P1-2｜Session TTL 过长（既有配置，非本 Phase 引入）**

`RedisConstants.LOGIN_USER_TTL = 36000L` 分钟 = **25 天**，且 `RefreshTokenInterceptor`
每次请求续期 → 永续滑动过期，无绝对上限。对一个作品集项目而言偏长。

未在本 Phase 改动：它不在批准的设计范围内，且当前演示流程需从 Redis 读验证码才能登录，
长 TTL 反而省事。**建议在 Phase 10 或专门的加固环节决定**是否缩短（如 30 分钟 + 绝对上限）。
登记为待决项，不作为 Phase 3 缺陷。

### P2

1. **`RoleInterceptor` 的类级注解分支当前无使用者**（两个 Controller 都用方法级）。
   按设计保留（标准的"方法优先、类兜底"语义，成本 2 行），非缺陷。
2. **403 响应体为空**，前端无法从 body 区分原因。与既有的 401 处理方式一致，系设计 §6.3 的有意选择。
3. **`/user/logout` 需要登录**：token 已过期时返回 401 而非干净的登出。设计 §7 的明确决策。
4. **`tb_user.password` 从未被使用** —— 密码登录未实现（验证码登录为主）。既有状态，非本 Phase 范围。
5. **种子管理员 `13900000001` 无密码**，只能走验证码登录。Phase 10 写 README 时需说明如何取得管理员身份。
6. **点评式用户接口仍在**：`tb_user_info`（0 行）、`UserInfo` 四件套、`GET /user/info/{id}`、`GET /user/{id}`
   —— 经用户 2026-10-04 裁决**归 Phase 10**。

---

## 6. 规格复核

本 Phase 设计下发后**未出现**需要中途改设计的冲突，Codex 亦未上报阻塞。
相比 Phase 2（我曾写错大小写敏感的扫描准则），本次任务包中的三个高危点
——`createUserWithPhone` 漏设 role、拦截器 order、枚举 Session 往返——
均在任务包中显式标注，且实测全部落实，无返工。

---

## 7. 结论

Phase 3 全部验收标准达成，无 P0 问题，改动范围精确，主链零影响，可以进入下一 Phase。

Phase 4 的准入前置：**P1-1（`CacheClient.queryWithMutex` 互斥缺陷）必须在 Phase 4 修复**
（该缺陷在 Phase 2 Review 中登记，Phase 4 将正式启用 `CacheClient`）。

```text
PHASE 3 — PASS
```
