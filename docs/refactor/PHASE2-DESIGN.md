# Phase 2 — Laboratory & Equipment 领域模型 详细设计

> 状态：**待用户批准**
> 依据：《实验资源预约平台-分阶段重构Phase计划》§5；《黑马点评 → 实验资源预约平台重构》§4 / §8 / §28
> 基线：commit `cf687b6`（Phase 1 完成）
> 本阶段性质：**建立新领域模型 + 让 Shop 语义退出**，不引入缓存/预约/MQ/权限。

---

## 1. 目标

建立新项目最基础的领域：

```
Laboratory 1 ──N Equipment N── 1 EquipmentCategory
```

并让黑马点评的 `Shop` / `ShopType` 领域模型彻底退出代码库
（数据表本身留到 Phase 10 清理，遵循 Phase 1 已确立的「DB 清理归 Phase 10」原则）。

**核心约束（来自计划文档 §5「迁移原则」）**：不允许 `Shop → Equipment` 改个类名了事，
必须真正建立 `Laboratory 1—N Equipment` 与 `EquipmentCategory 1—N Equipment` 两组关系。

---

## 2. 现状核验（本次实测，非推断）

### 2.1 环境（用户提供凭据后实测通过）

| 服务 | 实测结果 |
| --- | --- |
| MySQL | `127.0.0.1:3306`，`root/<见 application-local.yaml>` ✅ 连通，版本 **9.7.1**，库 `hmdp`，11 张表 |
| Redis | `127.0.0.1:6379`，密码 `<见 application-local.yaml>` ✅ `PONG`，`dbsize = 0`（空库） |
| RabbitMQ | `localhost:5672`，`<见 application-local.yaml>`（待验证，Phase 2 不依赖） |

> 真实凭据不入库：集中存放于 gitignored 的 `src/main/resources/application-local.yaml`。

真实库表：`tb_user` `tb_user_info` `tb_shop` `tb_shop_type` `tb_voucher`
`tb_seckill_voucher` `tb_voucher_order` `tb_blog` `tb_blog_comments` `tb_follow` `tb_sign`

数据量：`tb_shop` 14 行 / `tb_shop_type` 10 行 / `tb_user` 1010 行 / `tb_voucher` 1 行。

### 2.2 建表约定（从 `tb_shop` 实测提取，新表沿用）

```
ENGINE=InnoDB  CHARSET=utf8mb4  COLLATE=utf8mb4_general_ci
主键   bigint unsigned AUTO_INCREMENT
外键   仅建普通索引，不建物理 FK 约束（KEY `foreign_key_type` (`type_id`)）
时间   timestamp NULL DEFAULT CURRENT_TIMESTAMP
       update_time ... ON UPDATE CURRENT_TIMESTAMP
```

### 2.3 Shop 耦合点全扫描（决策依据）

**关键结论 1：秒杀主链 `VoucherOrderServiceImpl` 对 Shop 零引用**，
对 `CacheClient` 的引用数也是 0。RabbitMQ 全链路、`seckill*.lua`、`RedisIdWorker` 均与 Shop 无关。

**关键结论 2：`CacheClient` 的使用方只有 `ShopServiceImpl`（本阶段删除）与测试**，
因此清理 `CacheClient` 内的 Shop 命名**不会触碰秒杀主链**。

> ⚠️ **修订记录（v2）**：初版扫描用了大小写敏感的 `Shop`，**漏掉了大写的 `SHOP` 常量与 `shopJson` 变量**，
> 遗漏了 `CacheClient` 这一真实耦合点。由 Codex 在基线审计中发现并回报。
> 现在改为大小写不敏感（`grep -i`）重扫，下表为完整结果。

Shop 的全部耦合点（大小写不敏感，已穷尽）：

| # | 位置 | 处理 |
| --- | --- | --- |
| 1 | `VoucherMapper.queryVoucherOfShop` + XML `<select>` | 删除 |
| 2 | `VoucherServiceImpl.queryVoucherOfShop` | 删除 |
| 3 | `IVoucherService.queryVoucherOfShop` | 删除 |
| 4 | `VoucherController.GET /voucher/list/{shopId}` | 删除 |
| 5 | `entity/Voucher.java` 的 `shopId` 字段 + 「商铺id」注释 | **删除字段**（列 `shop_id` 在 DB 中 `DEFAULT NULL`，实测确认可空，删除字段不影响秒杀链 INSERT） |
| 6 | `MvcConfig` 白名单 `/shop/**`、`/shop-type/**` | 删除两行 |
| 7 | `RedisConstants` 的 `CACHE_SHOP_KEY` / `CACHE_SHOP_TTL` / `LOCK_SHOP_KEY` / `LOCK_SHOP_TTL` | 全删 |
| 8 | **`utils/CacheClient.java`**：`import static ...LOCK_SHOP_KEY`、2 处 `LOCK_SHOP_KEY + id`、4 处 `shopJson`、3 处「商铺/店铺」注释 | **去 Shop 化改造**（见 §5.6） |
| 9 | `HmDianPingApplicationTests.testSaveShop()` + Shop 相关 import | 删除 |

> **不再保留任何 Shop 例外**：修订后 `src/` 中大小写不敏感检索 `shop` 的目标是 **0 命中**，
> 唯一残留是数据库里的 `tb_voucher.shop_id` 列本身（Phase 10 随表清理）。

---

## 3. 领域模型与数据库设计

### 3.1 ER 关系

```
tb_equipment_category (设备分类)
        │ 1
        │
        │ N
tb_equipment (设备) ──── N ──── 1 ──── tb_laboratory (实验室)
```

### 3.2 `tb_laboratory` — 实验室

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | bigint unsigned | PK, AUTO_INCREMENT | 主键 |
| `name` | varchar(64) | NOT NULL | 实验室名称，如「材料科学实验中心」 |
| `campus` | varchar(64) | NOT NULL | 所属校区（**按校区筛选**的字段） |
| `building` | varchar(64) | NULL | 楼栋 |
| `room` | varchar(32) | NULL | 房间号 |
| `description` | varchar(512) | NULL | 实验室简介 |
| `status` | varchar(16) | NOT NULL DEFAULT 'OPEN' | `OPEN` / `CLOSED` / `MAINTENANCE` |
| `create_time` | timestamp | DEFAULT CURRENT_TIMESTAMP | |
| `update_time` | timestamp | ON UPDATE CURRENT_TIMESTAMP | |

索引：`PRIMARY KEY(id)`、`KEY idx_campus(campus)`、`KEY idx_name(name)`

### 3.3 `tb_equipment_category` — 设备分类

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | bigint unsigned | PK, AUTO_INCREMENT | |
| `name` | varchar(64) | NOT NULL | 分类名，如「显微成像」「化学分析」 |
| `icon` | varchar(255) | NULL | 图标 |
| `sort` | int | NOT NULL DEFAULT 0 | 排序 |
| `create_time` / `update_time` | timestamp | 同上 | |

索引：`PRIMARY KEY(id)`、**`UNIQUE KEY uk_name(name)`**（分类名不可重复）

### 3.4 `tb_equipment` — 实验设备

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | bigint unsigned | PK, AUTO_INCREMENT | |
| `laboratory_id` | bigint unsigned | NOT NULL | 所属实验室（逻辑外键） |
| `category_id` | bigint unsigned | NOT NULL | 设备分类（逻辑外键） |
| `name` | varchar(128) | NOT NULL | 设备名称 |
| `description` | varchar(1024) | NULL | 设备说明 |
| `location` | varchar(128) | NULL | 实验室内具体位置 |
| `image` | varchar(255) | NULL | 图片 URL |
| `usage_requirement` | varchar(512) | NULL | 使用要求 / 资质要求 |
| `status` | varchar(16) | NOT NULL DEFAULT 'AVAILABLE' | `AVAILABLE` / `MAINTENANCE` / `DISABLED` |
| `booking_enabled` | tinyint(1) | NOT NULL DEFAULT 1 | 是否开放预约 |
| `create_time` / `update_time` | timestamp | 同上 | |

索引：`PRIMARY KEY(id)`、`KEY idx_laboratory_id`、`KEY idx_category_id`、`KEY idx_name`、`KEY idx_status`

### 3.5 两项设计决策

1. **`status` 用 VARCHAR 存枚举名，不用整数。**
   理由：可读性（DBA 直接看懂），且避免「1 是未支付还是已支付」这类黑马遗留问题。
   配合 §5.4 显式钉死 MyBatis 枚举处理器，行为确定。

2. **`booking_enabled` 与 `status` 不重复。**
   `status` 是设备的**物理可用性**（坏了→MAINTENANCE）；`booking_enabled` 是**管理员的开放开关**
   （设备完好但被某课程包场→booking_enabled=0）。语义不同，都要保留。

3. **不建物理外键约束**，沿用现有表的逻辑外键 + 索引约定。
   理由：与库内既有 10 张表保持一致；且物理 FK 在后续批量运维时是负担。

---

## 4. 原表 → 新表映射（本阶段部分）

| 原表 | 处理 | 说明 |
| --- | --- | --- |
| `tb_shop` | **代码删除**，表 Phase 10 drop | 业务语义由 Laboratory + Equipment 取代 |
| `tb_shop_type` | **代码删除**，语义重构为 `tb_equipment_category` | 新建表而非改名，字段重新设计 |
| `tb_user` / `tb_user_info` | 保留 | Phase 3 |
| `tb_voucher` / `tb_seckill_voucher` / `tb_voucher_order` | 保留不动 | Phase 5–8 迁移对象 |
| `tb_blog` / `tb_blog_comments` / `tb_follow` / `tb_sign` | 保留（已是死表） | Phase 10 drop |
| — | **新建 `tb_laboratory`** | 本阶段 |
| — | **新建 `tb_equipment_category`** | 本阶段 |
| — | **新建 `tb_equipment`** | 本阶段 |

> 表名前缀 `tb_` 沿用现有约定，Phase 10 统一评估是否去除。本阶段不引入新命名风格。

---

## 5. Java 侧设计

### 5.1 删除清单（11 个文件）

| # | 文件 |
| --- | --- |
| 1 | `controller/ShopController.java` |
| 2 | `controller/ShopTypeController.java` |
| 3 | `service/IShopService.java` |
| 4 | `service/IShopTypeService.java` |
| 5 | `service/impl/ShopServiceImpl.java` |
| 6 | `service/impl/ShopTypeServiceImpl.java` |
| 7 | `mapper/ShopMapper.java` |
| 8 | `mapper/ShopTypeMapper.java` |
| 9 | `entity/Shop.java` |
| 10 | `entity/ShopType.java` |
| 11 | `resources/mapper/VoucherMapper.xml`（删掉唯一语句后成空文件，直接删） |

> **重要说明（关于 Phase 4 的素材）**：
> 删除 `ShopServiceImpl` 会带走 `queryById` 里两套被注释的缓存击穿方案。
> 这不影响 Phase 4 —— **Phase 4 的素材是 `CacheClient`（保留不动），它内部同时具备
> `queryWithPassThrough` / `queryWithMutex` / `queryWithLogicalExpire` 三套完整实现**；
> `ShopServiceImpl` 里那两段只是「三种用法的注释示例」，且已完整留档于
> `PHASE0-BASELINE.md §4` 与 `PHASE1-REVIEW.md §2.6`。Phase 4 直接在 `IEquipmentService` 上做选型。

### 5.2 新增清单（20 个文件）

**entity（3）**
- `entity/Laboratory.java` — `@TableName("tb_laboratory")`
- `entity/EquipmentCategory.java` — `@TableName("tb_equipment_category")`
- `entity/Equipment.java` — `@TableName("tb_equipment")`

**enums（2）**
- `enums/EquipmentStatus.java` — `AVAILABLE` / `MAINTENANCE` / `DISABLED`
- `enums/LaboratoryStatus.java` — `OPEN` / `CLOSED` / `MAINTENANCE`

**mapper（3）**
- `mapper/LaboratoryMapper.java`、`mapper/EquipmentCategoryMapper.java`、`mapper/EquipmentMapper.java`

**service + impl（6）**
- `service/ILaboratoryService.java` + `service/impl/LaboratoryServiceImpl.java`
- `service/IEquipmentCategoryService.java` + `service/impl/EquipmentCategoryServiceImpl.java`
- `service/IEquipmentService.java` + `service/impl/EquipmentServiceImpl.java`

**controller（3）**
- `controller/LaboratoryController.java`、`controller/EquipmentCategoryController.java`、`controller/EquipmentController.java`

**dto / vo（2）**
- `dto/EquipmentQueryDTO.java` — 组合筛选条件
- `vo/EquipmentVO.java` — 设备 + 实验室名 + 校区 + 分类名（JOIN 结果）

**resources（2）**
- `resources/mapper/EquipmentMapper.xml` — 分页 JOIN 查询
- `resources/db/phase2_laboratory_equipment.sql` — DDL + 演示种子数据

### 5.3 修改清单（7 个文件）

| 文件 | 改动 | 理由 |
| --- | --- | --- |
| `config/MvcConfig.java` | 白名单移除 `/shop/**`、`/shop-type/**` | 路径已不存在 |
| `utils/RedisConstants.java` | 移除 `CACHE_SHOP_KEY`、`CACHE_SHOP_TTL`、`LOCK_SHOP_KEY`、`LOCK_SHOP_TTL`；**新增 `LOCK_CACHE_KEY = "lock:"`** | 前四个变成死代码；第五个供 §5.6 的泛型锁前缀使用 |
| `mapper/VoucherMapper.java` | 移除 `queryVoucherOfShop` 方法 | 商铺语义 |
| `service/IVoucherService.java` | 移除 `queryVoucherOfShop` 声明 | 同上 |
| `service/impl/VoucherServiceImpl.java` | 移除 `queryVoucherOfShop` 实现 + 连带 `java.util.List` import；**`addSeckillVoucher` 原样保留** | 同上 |
| `controller/VoucherController.java` | 移除 `GET /voucher/list/{shopId}`；**`POST /voucher` 与 `POST /voucher/seckill` 原样保留** | 同上 |
| `entity/Voucher.java` | 移除 `shopId` 字段与「商铺id」注释 | Shop 语义退出（列可空，实测确认） |
| `utils/CacheClient.java` | **去 Shop 化改造**，见 §5.6 | Shop 命名残留 |
| `src/test/java/com/hmdp/HmDianPingApplicationTests.java` | 移除 `testSaveShop()` 与 Shop 相关 import；**保留 `testIdWorker()`** | 依赖已删除的类 |

**`RedisConstants` 保留清单**：`LOGIN_CODE_KEY`、`LOGIN_CODE_TTL`、`LOGIN_USER_KEY`、
`LOGIN_USER_TTL`、`CACHE_NULL_TTL`、`SECKILL_STOCK_KEY`。

> `SECKILL_STOCK_KEY` 必须保留 —— 秒杀主链还活着，Phase 6 才迁移为预约名额。

### 5.4 枚举持久化（显式钉死，消除不确定性）

在 `application.yaml` 增加：

```yaml
mybatis-plus:
  type-aliases-package: com.hmdp.entity
  configuration:
    default-enum-type-handler: org.apache.ibatis.type.EnumTypeHandler
```

**为什么必须显式配置**：MyBatis-Plus 在不同版本下对默认枚举处理器的行为不一致
（`EnumTypeHandler` 存 `name()` vs `MybatisEnumTypeHandler` 处理 `@EnumValue`/`IEnum`）。
显式指定 MyBatis 原生 `EnumTypeHandler` 后，行为确定为「**枚举 ↔ `name()` 字符串**」，
与 §3.5 的 VARCHAR 列设计严格对应。

**验证方式**：运行时插入 `status = AVAILABLE` 的设备，直接查库确认列值为字符串 `'AVAILABLE'`。

### 5.5 `Voucher.shopId` 的处理（修订：删除）

**修订前**：计划保留该字段，理由是「未核实 `shop_id` 是否 NOT NULL，怕破坏秒杀链 INSERT」。

**修订后实测**：`tb_voucher.shop_id` 是 `bigint unsigned DEFAULT NULL` —— **可空**。
且删除 `queryVoucherOfShop` 后，全仓库再无任何代码读写 `shopId`。

→ **本阶段删除 `Voucher.shopId` 字段**（连同「商铺id」注释），
使 `src/` 中大小写不敏感检索 `shop` 真正达到 0 命中；
数据库列 `tb_voucher.shop_id` 本身保留到 Phase 10 随旧表清理。
`Voucher` 整体仍将在 Phase 5 被 `ReservationBatch` 替换。

### 5.6 `CacheClient` 去 Shop 化改造

`CacheClient` 是一个**泛型**缓存工具（`<R, ID>` + `keyPrefix` 参数），
却静态导入了 `LOCK_SHOP_KEY`，并在 `queryWithMutex` 中使用 `shopJson` 变量名与「商铺缓存」注释。
这是原教程代码的遗留耦合 —— 泛型工具不应引用具体业务常量。

**改造内容（仅去耦合，不动三套算法逻辑）**：

1. 删除 `import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;`
2. 锁 key 改为**由缓存 key 派生**，不再依赖全局 SHOP 常量：
   ```java
   // 改前
   String lockKey = LOCK_SHOP_KEY + id;
   // 改后（RedisConstants 新增 LOCK_CACHE_KEY = "lock:"）
   String lockKey = LOCK_CACHE_KEY + keyPrefix + id;
   ```
   出现 2 处（`queryWithLogicalExpire` 与 `queryWithMutex`）。
3. `queryWithMutex` 中的 4 处 `shopJson` 变量重命名为 `json`（与 `queryWithPassThrough` /
   `queryWithLogicalExpire` 已有的命名保持一致）
4. 注释中的「商铺缓存」「店铺信息」改为中性表述

> **为什么这是改进而不是单纯改名**：原实现 `LOCK_SHOP_KEY + id` 只用实体 id 做锁粒度，
> 不同缓存类型若 id 相同会**共用同一把锁**。改为 `"lock:" + keyPrefix + id` 后，
> 锁粒度变成「缓存类型 + id」，消除了这个隐患 —— 这正是 Phase 4 给 Equipment 做缓存时需要的形态。

**保留不动**：`queryWithPassThrough` / `queryWithMutex` / `queryWithLogicalExpire` 三套算法的
完整实现、`CACHE_REBUILD_EXECUTOR`、`tryLock` / `unlock`、`RedisData` 逻辑过期机制。
（Phase 4 的选型素材正在于此。）

**`RedisConstants` 新增一个常量**：`public static final String LOCK_CACHE_KEY = "lock:";`

---

## 6. 接口设计

### 6.1 `LaboratoryController` — `/laboratory`

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| GET | `/laboratory/{id}` | 实验室详情 | 登录 |
| GET | `/laboratory/list?campus=&current=` | 分页列表，**按校区筛选** | 登录 |
| POST | `/laboratory` | 新增实验室 | 登录（角色校验 Phase 3） |
| PUT | `/laboratory` | 更新实验室 | 登录 |
| DELETE | `/laboratory/{id}` | 删除实验室 | 登录 |

### 6.2 `EquipmentCategoryController` — `/equipment-category`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/equipment-category/list` | 全部分类，按 `sort` 升序 |

### 6.3 `EquipmentController` — `/equipment`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/equipment/{id}` | 设备详情（返回 `EquipmentVO`，含实验室名/分类名） |
| GET | `/equipment/page` | **组合筛选分页**：`laboratoryId` / `categoryId` / `campus` / `name` / `status` / `bookingEnabled` / `current` |
| POST | `/equipment` | 新增设备 |
| PUT | `/equipment` | 更新设备 |
| PUT | `/equipment/{id}/status?status=MAINTENANCE` | **设备状态管理** |
| DELETE | `/equipment/{id}` | 删除设备 |

`/equipment/page` 覆盖计划要求的全部筛选维度：按校区、按实验室、按分类、按名称、分页。

### 6.4 分页实现

`EquipmentMapper.xml` 提供 JOIN 查询，一次取全展示字段：

```sql
SELECT e.*, l.name AS laboratory_name, l.campus, l.building, l.room,
       c.name AS category_name
FROM tb_equipment e
LEFT JOIN tb_laboratory l ON e.laboratory_id = l.id
LEFT JOIN tb_equipment_category c ON e.category_id = c.id
WHERE ...动态条件...
ORDER BY e.id DESC
```

返回 `IPage<EquipmentVO>`，由 MyBatis-Plus 分页拦截器（`MybatisConfig` 已配置）完成物理分页。
分页大小复用 `SystemConstants.DEFAULT_PAGE_SIZE`。

### 6.5 鉴权边界（本阶段有意从简）

- 新接口**不加入白名单** → 一律需要登录（`LoginInterceptor` 生效，`RefreshTokenInterceptor` 刷新 token）。
- **不做角色校验** —— 计划文档 §5 明确「暂时不做复杂权限」，属 Phase 3。
- `/voucher/**` 白名单**保持原样**（秒杀链仍活着，Phase 3/5 再收敛）。
- 「设备查询公开、预约需登录」的最终鉴权模型由 **Phase 3** 用注解方式实现，
  本阶段不用「按路径通配」的粗糙白名单去凑（`/equipment/**` 会连带放开写接口）。

---

## 7. 明确不做的事（边界）

| 项 | 归属 |
| --- | --- |
| Redis 设备热点缓存 / `CacheClient` 接入 | Phase 4 |
| 预约批次 / ReservationBatch | Phase 5 |
| Lua / Redis 名额 | Phase 6 |
| RabbitMQ 预约链路 | Phase 7 |
| 取消 / 履约 / 状态机 | Phase 9 |
| 角色权限 / `@RequireRole` / logout | Phase 3 |
| DROP `tb_shop` / `tb_shop_type` / `tb_sign` 等旧表 | Phase 10 |
| 重构 `Voucher` / `SeckillVoucher` / `VoucherOrder` 实体（改字段语义、改表） | Phase 5 |
| 修改 `VoucherOrderServiceImpl` / `RabbitMQConfig` / `*.lua` | Phase 5–8 |
| 改造 `UploadController` 与图片上传策略 | Phase 10 |
| 去掉 `tb_` 表名前缀 | Phase 10 |
| 引入 MyBatis-Plus 代码生成器 / 分页插件之外的新依赖 | 不做 |

---

## 8. 运行时可用性（本阶段新增的必要前置）

Phase 1 只做编译验证，可以不碰配置。但 Phase 2 的验收标准包含
「**查询接口可用**」，必须真正把应用跑起来，因此有三个环境阻塞项必须先解。

### 8.1 阻塞项实测

| # | 问题 | 实测证据 | 影响 |
| --- | --- | --- | --- |
| B1 | **JDBC 驱动太旧** | `pom.xml` 用 `mysql:mysql-connector-java:5.1.47`；服务端是 **MySQL 9.7.1** | 5.1.x 不支持 `caching_sha2_password`，**必然连不上**，应用起不来 |
| B2 | Redis 指向不可达地址 | `application.yaml` 指向 `192.168.150.101:6379` 且密码不符 | 登录 Session 不可用，所有需登录接口直接失败 |
| B3 | RabbitMQ 账号不符 | 配置 `guest/guest`，与本机实际账号不符 | 队列声明失败、控制台刷错误日志 |
| B4 | **凭据明文入库** | `application.yaml` 的 `password` 为**明文课程演示口令**（值已按 Phase 10 §3.13 第 5 条脱敏）；`RedissonConfig.java` 硬编码 IP + 密码 | 一旦 push 到 GitHub 即公开泄露 |

### 8.2 建议方案（推荐，需用户批准）

**B1**：依赖升级
```xml
<dependency>
  <groupId>com.mysql</groupId>
  <artifactId>mysql-connector-j</artifactId>
  <version>8.4.0</version>
  <scope>runtime</scope>
</dependency>
```
`driver-class-name` 改为 `com.mysql.cj.jdbc.Driver`，URL 增加
`allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai`。

> ⚠️ 8.4.0 是否完全兼容 MySQL **9.7.1** 服务端，**必须由 Codex 实际启动应用验证**，
> 不能靠推断。若启动失败，如实回报，由 Claude 决定是否换用 9.x 驱动，不允许自行猜测升级。

**B2 / B3**：连接参数改指本机（`127.0.0.1`），账号密码放入 `application-local.yaml`，不写入受版本控制的文件。

**B4**：**立即做凭据外置**，而不是等 Phase 10。理由：
用户已选择「Phase 1 只本地提交、暂不 push」，此时外置可让**真实密码永远不进入 git 历史**；
若拖到 Phase 10，历史里会永久留存明文，届时只能改写历史或重建仓库。

做法（标准 Spring Profile 方案）：

```
application.yaml            → 保留结构，spring.profiles.active: local
application-local.yaml      → 真实凭据，写入 .gitignore（不进版本库）
application-local.yaml.example → 提交，作为模板（占位值）
```

### 8.3 备选方案

若用户不希望本阶段动依赖与配置，则采用备选：
Phase 2 只做「编译 + DDL 落库 + 纯 SQL 层验证」，**「查询接口可用」这条验收推迟到 Phase 3**。
代价：新写的 Controller / Service / Mapper 在 Phase 2 得不到端到端验证。

---

## 9. 验收标准（可验证）

### 9.1 编译（硬性）

```bash
JAVA8=/Users/eason/Library/Java/JavaVirtualMachines/corretto-1.8.0_504/Contents/Home
JAVA_HOME=$JAVA8 mvn clean test-compile   # 必须 BUILD SUCCESS
```

### 9.2 数据库

```bash
mysql -h127.0.0.1 -uroot -p hmdp -e "show tables like 'tb_equipment%'; show tables like 'tb_laboratory';"
mysql -h127.0.0.1 -uroot -p hmdp -e "show create table tb_equipment;"
```
- 三张新表存在，字段类型/索引与 §3 一致
- 种子数据写入成功

### 9.3 残留扫描（必须全部无匹配）

**⚠️ 必须大小写不敏感（`grep -i`）** —— 初版设计用大小写敏感扫描，漏掉了 `SHOP` 常量与 `shopJson`。

```bash
cd /Users/eason/Desktop/hm-dianping
grep -rni "shop" --include="*.java" --include="*.xml" src/    # 期望：0 命中
```

| 检索式（`-i`） | 期望 |
| --- | --- |
| `shop`（`*.java` 与 `*.xml`） | **0** |
| `queryShopByType\|queryVoucherOfShop\|CACHE_SHOP\|LOCK_SHOP` | 0 |
| `shopJson` | 0 |

> 修订后**不存在任何代码层例外**。唯一保留的是数据库 `tb_voucher.shop_id` 列本身（Phase 10 清理），
> 它不在 `src/` 检索范围内。

### 9.4 主链完好（人工核对，零改动）

`VoucherOrderServiceImpl`、`RabbitMQConfig`、`seckill.lua`、`seckillRollback.lua`、`unlock.lua`、
`CacheClient`、`RedisIdWorker`、`SimpleRedisLock`、`RefreshTokenInterceptor`、`LoginInterceptor`。

### 9.5 运行时（采用 §8.2 方案时）

1. 应用启动成功，无 MySQL / Redis 连接异常
2. 走登录流程取得 token
3. 逐个调用 §6 的查询接口，返回结构正确
4. **插入一台 `status=AVAILABLE` 的设备并回读**，确认库中列值为字符串 `'AVAILABLE'`（验证 §5.4）
5. 组合筛选 `/equipment/page?campus=&categoryId=&name=` 返回结果正确
6. `/equipment/{id}/status` 改状态后回读生效

---

## 10. 风险与对策

| 风险 | 等级 | 对策 |
| --- | --- | --- |
| JDBC 8.4.0 与 MySQL 9.7.1 不兼容，应用起不来 | **高** | §8.2 要求 Codex 实测启动；失败如实回报，不自行猜测版本 |
| 枚举持久化行为不确定 | 中 | §5.4 显式配置 `EnumTypeHandler` + §9.5.4 运行时验证 |
| 删 `ShopServiceImpl` 导致 Phase 4 无素材 | 低 | §5.1 已论证：素材是 `CacheClient`，且已留档 |
| 残留扫描用大小写敏感匹配导致漏项 | **高** | 初版已实际踩中（漏 `CacheClient`）；§9.3 强制 `grep -i` |
| 改造 `CacheClient` 时误动三套算法逻辑 | 中 | §5.6 明确「只去耦合、不动算法」；Review 逐行核对 diff |
| 误改秒杀链（`VoucherServiceImpl.addSeckillVoucher`） | 中 | §5.3 明确保留；Review 逐行核对 diff |
| 删 `Voucher.shopId` 影响秒杀链 INSERT | 低 | 实测 `shop_id` 为 `DEFAULT NULL`；且已无任何代码读写该字段 |
| 新接口未加白名单导致验证麻烦 | 低 | §6.5 说明用登录流程验证；不用通配白名单凑 |
| 真实密码进入 git 历史 | **高** | §8.2 B4 立即外置到 gitignore 文件 |
| 本阶段体量偏大（约 38 个文件变动） | 中 | 严格按 §5 清单执行，超出清单的改动一律回报 |

---

## 11. 执行方式

按 `codex-implementer` 闭环：

1. 用户批准本设计（含 §8 的方案选择）
2. Claude 生成 Implementation Packet → 派发 Codex（`gpt-5.6-sol` / `effort=high`）
3. Codex 执行删除 / 新增 / 修改，自行跑 `mvn clean test-compile`，
   并在 §8.2 方案下**实际启动应用**做运行时验证
4. Claude 独立复查：git diff 逐行核对、残留扫描、主链零改动核验、
   亲自连库核对表结构、亲自跑接口
5. Claude 输出 Review 结论；PASS 则报用户并**停下来等待批准进入 Phase 3**

---

## 12. 完成标志

```
PHASE 2 — PASS
```

（需 Claude Review 通过，且用户批准后才进入 Phase 3）
