# Phase 3 详细设计：User & Authorization 用户与权限

> 状态：待用户批准
> 上游依据：`实验资源预约平台-分阶段重构Phase计划.md` §6；`黑马点评 → 实验资源预约平台重构.md` M1
> 编制：Claude（Control Plane）· 2026-10-03
> 前一 Phase：Phase 2 `efa0ac4`（Review PASS）

---

## 1. 目标

把原点评用户体系改造为**实验资源预约平台的用户与鉴权体系**：

1. 引入**角色模型**（`USER` / `LAB_ADMIN`），落库 + 进入 Redis Session + 进入 ThreadLocal 上下文；
2. 用**注解式**鉴权替换"无差别放行"，明确普通用户与实验室管理员的边界；
3. 补齐 Phase 0 就登记为缺失的 `logout`；
4. **保留**现有认证基础设施：Redis Session、UUID Token、双拦截器、ThreadLocal —— 不改 JWT、不引框架。

**Non-goals（本阶段明确不做）**：JWT、Spring Security / Shiro / Sa-Token、复杂 RBAC（角色-权限-资源表）、
`SYSTEM_ADMIN`、实验室管理员与实验室的归属关系（见 §11）、用户注册/改密/找回。

---

## 2. 现状核验（本次实测，非推断）

### 2.1 认证链路（代码实测）

```
POST /user/code   → UserServiceImpl.sendCode    → Redis String  login:code:{phone}  TTL 2min
POST /user/login  → UserServiceImpl.login       → Redis Hash    login:token:{uuid}   TTL 36000min
                                                   字段 = UserDTO 的全部字段
GET  /user/me     → UserController.me           ← UserHolder.getUser()
```

- `RefreshTokenInterceptor`（order 0，`/**`）：读 `authorization` 头 → `login:token:{token}` → `fillBeanWithMap` → `UserHolder` → 续期
- `LoginInterceptor`（order 1）：`UserHolder` 为空则 **401**
- `UserHolder`：`ThreadLocal<UserDTO>`
- `MvcConfig` 白名单：`/voucher/**`、`/upload/**`、`/user/code`、`/user/login`

### 2.2 数据现状（直连数据库实测）

| 项 | 实测结果 |
| --- | --- |
| `tb_user` | 1011 行，`id` 最大 1015；列：`id/phone/password/nick_name/icon/create_time/update_time`，**无 `role` 列** |
| `tb_user` 索引 | `PRIMARY(id)`、`uniqe_key_phone(phone)` **UNIQUE** |
| `tb_user_info` | 10 列，**0 行**；字段为 `city/fans/followee/gender/birthday/credits/level`（点评社交语义） |
| `13800138000` | 已存在（id=1015，Phase 2 验证时自动创建） |

### 2.3 关键引用点

- `UserDTO` 被 5 处引用：`UserServiceImpl`（写入 Session）、`RefreshTokenInterceptor`（读回 Session）、`UserHolder`、`UserController`×2
- `UserInfo` 仅被 `UserController.info()`（`/user/info/{id}`）+ 自身 4 件套引用
- `pom.xml` **无** `spring-boot-starter-aop`（决定 §6 的鉴权实现方式）
- `Result` 无 403 语义 helper —— 拦截器直接写 HTTP 状态码（与 `LoginInterceptor` 的 401 一致）

---

## 3. 角色模型

```java
public enum UserRole {
    USER,        // 普通预约用户
    LAB_ADMIN    // 实验室管理员
}
```

**只有两个角色**，不建 `SYSTEM_ADMIN`。理由：计划 §6「不建设复杂 RBAC」；总纲「SYSTEM_ADMIN（如确有必要）」
—— 当前 M1–M6 六大模块中没有任何操作需要超出 LAB_ADMIN 的系统级权限。将来若确需，
代价是**枚举加一个值**，`varchar(16)` 列无需迁移。

**权限边界（第一版）**：

| 能力 | USER | LAB_ADMIN |
| --- | :---: | :---: |
| 查询实验室 / 设备 / 分类 | ✅ | ✅ |
| 查询自己的信息与预约（预约在 Phase 5+） | ✅ | ✅ |
| 实验室 增 / 改 / 删 | ❌ 403 | ✅ |
| 设备 增 / 改 / 删 / 改状态 | ❌ 403 | ✅ |
| 发布 / 关闭预约批次（Phase 5） | ❌ | ✅ |
| 履约核验（Phase 9） | ❌ | ✅ |

---

## 4. 数据模型变更

### 4.1 DDL（`resources/db/phase3_user_role.sql`）

```sql
-- 1) 加 role 列（幂等：先查 information_schema，已存在则跳过）
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_user' AND COLUMN_NAME = 'role');
SET @sql := IF(@col = 0,
  'ALTER TABLE `tb_user` ADD COLUMN `role` varchar(16) NOT NULL DEFAULT ''USER'' COMMENT ''角色：USER/LAB_ADMIN'' AFTER `icon`',
  'SELECT ''tb_user.role already exists'' AS skipped');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) 种子管理员账号（phone 已有 UNIQUE 键，故幂等）
INSERT INTO `tb_user` (`phone`, `nick_name`, `icon`, `role`)
VALUES ('13900000001', '实验室管理员', '', 'LAB_ADMIN')
ON DUPLICATE KEY UPDATE `role` = 'LAB_ADMIN';
```

- 1011 个既有用户自动获得 `DEFAULT 'USER'`，无需回填。
- **本脚本按 Phase 2 Review 的教训写成幂等**（Phase 2 的种子 INSERT 不幂等已登记为 P2）。
- 枚举落库沿用 Phase 2 已配置的 `EnumTypeHandler` → 存字符串 `'USER'` / `'LAB_ADMIN'`（非序号）。

### 4.2 实体与 DTO

| 文件 | 变更 |
| --- | --- |
| `entity/User.java` | 新增 `private UserRole role;` |
| `dto/UserDTO.java` | 新增 `private UserRole role;` |

`UserDTO` 带 `role` 是**整个鉴权链路的枢纽**：它被序列化进 Redis Hash，登录时写入、每次请求读回，
`RoleInterceptor` 从 `UserHolder` 取出判断。

---

## 5. 认证基础设施：全部保留

| 组件 | 处置 |
| --- | --- |
| Redis Session（`login:token:` Hash） | **保留**，仅多一个 `role` 字段 |
| UUID Token | **保留** |
| `RefreshTokenInterceptor` | **保留，零改动** |
| `LoginInterceptor` | **保留，零改动**（仍负责 401） |
| `UserHolder`（ThreadLocal） | **保留，零改动** |
| `RedisConstants` | **保留，零改动**（`LOGIN_USER_KEY` 已够用） |

> **不改 JWT**。计划 §6「注意」明确：当前项目不是 JWT，不要为"技术升级"擅自改。

### 5.1 Session 往返的隐性风险（必须运行时验证）

`UserServiceImpl` 写 Session 用：

```java
BeanUtil.beanToMap(userDTO, new HashMap<>(),
    CopyOptions.create().setIgnoreNullValue(true)
        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
```

`RefreshTokenInterceptor` 读回用：

```java
BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);   // isIgnoreError = false
```

`role` 是枚举：写入时 `toString()` → `"LAB_ADMIN"`；读回时 Hutool `Convert` 的 `EnumConverter`
按 `Enum.valueOf` 转换 → 应当成功。**但这是本 Phase 唯一"靠推断"的环节，必须实测**：
登录后 `HGETALL login:token:{token}` 应见 `role=LAB_ADMIN`，且 `/user/me` 能返回 `role`。

若实测转换失败，**Codex 不得自行改 `RefreshTokenInterceptor`**，须回报 Claude 重新设计
（备选：`UserDTO.role` 改存 `String`，或自定义 `CopyOptions` 转换器）。

`isIgnoreError=false` + 列 `NOT NULL DEFAULT 'USER'` ⇒ 新登录用户的 `role` 必非 null，
不会触发 `fieldValue.toString()` 的 NPE。**历史遗留 Session**（无 `role` 字段）会被判定为
无角色 → 管理操作 403（fail-safe），重新登录即恢复。

---

## 6. 鉴权机制：注解 + 拦截器

### 6.1 为什么不用 AOP

`pom.xml` 无 `spring-boot-starter-aop`。用 `HandlerInterceptor` 可直接从 `HandlerMethod`
读到方法/类注解，**零新增依赖**，且与既有 `RefreshTokenInterceptor`(0) / `LoginInterceptor`(1)
的顺序链天然衔接。AOP 需要引依赖 + 处理 `@RestControllerAdvice` 冲突，属过度设计。

### 6.2 新增 `annotation/RequireRole.java`

```java
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    UserRole[] value();
}
```

### 6.3 新增 `utils/RoleInterceptor.java`

职责：只做**角色**判断，不做登录判断（那是 `LoginInterceptor` 的职责）。

```
preHandle:
  1. handler 不是 HandlerMethod（静态资源等）→ 放行
  2. 取 @RequireRole：先方法，后类；都没有 → 放行（= 只要求登录）
  3. UserHolder.getUser() == null → 401（防御性；正常已被 LoginInterceptor 拦下）
  4. user.getRole() 命中 value() 中任一角色 → 放行
  5. 否则 → 403
```

**响应体约定**：只写 HTTP 状态码，不写 JSON body（与 `LoginInterceptor` 的 401 保持一致，
不引入新的错误响应格式）。401 = 未登录；403 = 已登录但角色不足 —— **必须区分**。

### 6.4 注册

`MvcConfig` 追加：

```java
registry.addInterceptor(new RoleInterceptor())
        .addPathPatterns("/**")
        .order(2);          // 必须晚于 LoginInterceptor(1)，否则 UserHolder 尚未填充
```

白名单路径无 `@RequireRole` 注解 → 自然放行，无需单独排除。

### 6.5 端点权限矩阵

| 端点 | 登录要求 | 角色 | 依据 |
| --- | --- | --- | --- |
| `POST /user/code`、`POST /user/login` | 白名单 | — | 保留 |
| `POST /user/logout` | 登录 | 任意 | §7 |
| `GET /user/me`、`GET /user/{id}`、`GET /user/info/{id}` | 登录 | 任意 | 保留（后两者 Phase 10 处置） |
| `GET /laboratory/{id}`、`GET /laboratory/list` | 登录 | 任意 | 查询 |
| `POST/PUT/DELETE /laboratory` | 登录 | **LAB_ADMIN** | 管理员能力 |
| `GET /equipment/{id}`、`GET /equipment/page`、`GET /equipment-category/list` | 登录 | 任意 | 查询 |
| `POST /equipment`、`PUT /equipment` | 登录 | **LAB_ADMIN** | 管理员能力 |
| `PUT /equipment/{id}/status`、`DELETE /equipment/{id}` | 登录 | **LAB_ADMIN** | 管理员能力 |
| `/voucher/**`、`/upload/**` | 白名单 | — | 保留 |

---

## 7. `logout` 实现

```java
// IUserService
void logout(String token);

// UserServiceImpl
@Override
public void logout(String token) {
    if (StrUtil.isNotBlank(token)) {
        stringRedisTemplate.delete(LOGIN_USER_KEY + token);
    }
}

// UserController
@PostMapping("/logout")
public Result logout(@RequestHeader(value = "authorization", required = false) String token) {
    userService.logout(token);
    UserHolder.removeUser();
    return Result.ok();
}
```

**语义决策**：

- `POST /user/logout` **不加入白名单** → 需要登录。理由：登出需要一个有效 Session 才有意义，
  也让"未登录接口控制正确"这条验收标准更容易自证。
- 幂等：token 为空或已失效时返回 `ok`，不报错。
- `UserHolder.removeUser()`：`RefreshTokenInterceptor.afterCompletion` 已会清理，此处显式调用是
  防御性冗余（线程池复用场景下更稳妥）。
- **只删当前 token**，不影响该用户其他设备的 Session（UUID Token 无"全端登出"概念，
  不额外引入 `user:tokens:` 集合 —— YAGNI）。

**新增常量**：无。`LOGIN_USER_KEY` 已存在于 `RedisConstants`（该文件**零改动**）。

---

## 8. 明确不改动清单

秒杀主链与 Phase 2 成果均不得触碰：

- `VoucherOrderServiceImpl`、`RabbitMQConfig`、`resources/*.lua`、`RedisIdWorker`、`SimpleRedisLock`
- `CacheClient`（含 Phase 2 Review 登记的 P1-1 互斥缺陷，**归属 Phase 4**，本阶段不修）
- `LoginInterceptor`、`RefreshTokenInterceptor`、`UserHolder`、`RedisConstants`、`SystemConstants`
- Phase 2 域模型：`Equipment`、`Laboratory`、`EquipmentCategory`、`EquipmentMapper.xml`、`EquipmentVO`、`EquipmentQueryDTO`
- `MybatisConfig`、`RedissonConfig`、`WebExceptionAdvice`、`UploadController`
- `UserInfo` 四件套 + `/user/info/{id}` + `/user/{id}`（**归 Phase 10**，见 §11）
- `db/hmdp.sql`、`application.yaml`、`pom.xml`（本阶段无新依赖）

---

## 9. 任务拆解（单个 Codex 任务包）

本 Phase 体量小且各环节强耦合（角色必须端到端打通才可验证），作为一个原子任务下发。

1. 新增 `enums/UserRole.java`
2. 新增 `resources/db/phase3_user_role.sql`（幂等）并**实际执行到 `hmdp` 库**
3. `User` / `UserDTO` 加 `role`
4. `UserServiceImpl.createUserWithPhone` **显式 `setRole(UserRole.USER)`**
   —— 否则内存对象 role 为 null，写进 Session 后管理员判定必失败（DB 默认值救不了内存对象）
5. 新增 `annotation/RequireRole.java` + `utils/RoleInterceptor.java`，`MvcConfig` 注册 order 2
6. `EquipmentController` / `LaboratoryController` 按 §6.5 加 `@RequireRole(LAB_ADMIN)`
7. 实现 `logout`（`IUserService` / `UserServiceImpl` / `UserController`）
8. 运行验证（§10），如实汇报

---

## 10. 验收标准

### 10.1 计划 §6 原始验收标准

| 标准 | 验证方式 |
| --- | --- |
| 登录流程正常 | 走 `/user/code` → `/user/login`，取得 token |
| Redis Session 正常 | `HGETALL login:token:{token}` 含 `role` 字段且值正确 |
| 普通用户和管理员权限边界明确 | USER 调管理端点 → **403**；LAB_ADMIN 同端点 → **200** |
| 登出完成 | logout 后 `login:token:{token}` 不存在，同 token 再请求 → **401** |
| 未登录接口控制正确 | 无 token 调任意受保护端点 → **401** |
| 不引入大型权限框架 | `pom.xml` 依赖无变化；`grep -rni "jwt\|shiro\|spring-security\|sa-token" src/ pom.xml` → 0 hits |
| Maven compile 成功 | `mvn clean test-compile` |

### 10.2 补充验收（本设计追加）

| 项 | 验证方式 |
| --- | --- |
| 枚举落库为字符串 | 直查 `tb_user.role` = `'LAB_ADMIN'`（非序号） |
| Session 往返不丢角色（§5.1 风险） | `/user/me` 返回 `role` |
| 401 与 403 语义区分 | 无 token → 401；USER 带 token 调管理端点 → 403 |
| 新注册用户默认 USER | 用全新手机号登录，`role` 为 `USER`，管理端点 403 |
| 既有 1011 用户未受影响 | `SELECT COUNT(*) FROM tb_user WHERE role='USER'` = 1011 |
| 幂等性 | `phase3_user_role.sql` 连跑两次不报错 |
| 主链零改动 | 秒杀/MQ/Lua 文件 diff 为空 |
| Phase 2 功能未回归 | `mvn test` 2/2 PASS；Phase 2 查询接口仍正常 |

---

## 11. 需用户裁决的两个边界（见批准提问）

### 11.1 实验室管理员与实验室的归属关系 —— 建议**推迟到 Phase 5**

计划 §6 称 LAB_ADMIN「管理**自己负责的**实验室」。落实它需要一个归属模型
（`tb_laboratory.admin_id` 或 `tb_laboratory_admin` 关联表）。

**建议本阶段只建"角色"，不建"归属"**：Phase 3 交付的是鉴权**机制**；归属是叠加其上的**策略**，
而它的真实消费者（发布/关闭预约批次、履约核验）在 Phase 5 / Phase 9 才出现。
现在设计一个零消费者的关联表，很可能建错。Phase 5 引入归属时，
只需给已有 `@RequireRole(LAB_ADMIN)` 端点补一层"且该实验室归我管"的判断。

代价：本阶段 LAB_ADMIN 可管理**任意**实验室的设备。对第一版可接受，且会在 Review 中明示。

### 11.2 点评式用户接口 `UserInfo` / `/user/info/{id}` / `/user/{id}` —— 建议**推迟到 Phase 10**

- `tb_user_info`（0 行）与 `UserController.info()` 是纯点评社交残留
  （`city/fans/followee/credits/level`），`GET /user/{id}` 亦为点评式"查他人"。
- 计划 §13.1 / §10.2 把「无用 Entity」「废弃 Controller」明确划给 **Phase 10**。
- Phase 1 时用户已表达偏好：**只删代码、不动 DB 表**（`tb_blog`/`tb_follow` 即如此处理）。

故建议 Phase 3 不动它们，归入 Phase 10 统一清扫；Phase 3 保持"角色 + 鉴权 + logout"的窄切口，
便于一旦出问题能准确定位到 Phase（计划 §19 原则）。

---

## 12. 风险

| 风险 | 级别 | 对策 |
| --- | --- | --- |
| Hutool 枚举 Session 往返失败（§5.1） | 中 | 列为硬验收项；失败则回报重新设计，不许自行改拦截器 |
| 拦截器 order 配错致 `UserHolder` 为空 | 中 | 明确 order 2；403/401 双向实测 |
| `createUserWithPhone` 漏设 role | 中 | 写入任务包 §9.4；用全新手机号实测默认 USER |
| 给 `/user/{id}` 等误加 `@RequireRole` | 低 | §6.5 矩阵逐条对照 |
| 借机引入 JWT / 权限框架 | 低 | §8 禁止清单 + §10.1 依赖扫描 |
| 扩展成复杂 RBAC | 低 | 只有 2 个枚举值、1 个注解、1 个拦截器 |

---

## 13. 完成标志

```text
PHASE 3 — PASS
```
