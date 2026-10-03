# Phase 2 Review

## Result

PASS

审查对象：Phase 2「Laboratory & Equipment 领域模型」
规格依据：`docs/refactor/PHASE2-DESIGN.md`（v2）+ `实验资源预约平台-分阶段重构Phase计划.md` §5
审查方式：逐行核对 diff、直连数据库核对表结构、启动应用实测全部接口、枚举落库验证
审查人：Claude（Control Plane），2026-10-03

---

## 1. 交付物核对

### 新增（22 个源文件 + 2 个资源文件）

| 类别 | 文件 |
| --- | --- |
| 实体 | `entity/Laboratory.java`、`entity/EquipmentCategory.java`、`entity/Equipment.java` |
| 枚举 | `enums/LaboratoryStatus.java`（OPEN/CLOSED/MAINTENANCE）、`enums/EquipmentStatus.java`（AVAILABLE/MAINTENANCE/DISABLED） |
| Mapper | `mapper/LaboratoryMapper.java`、`mapper/EquipmentCategoryMapper.java`、`mapper/EquipmentMapper.java` |
| Service | `service/ILaboratoryService.java`、`service/IEquipmentCategoryService.java`、`service/IEquipmentService.java` |
| ServiceImpl | `service/impl/LaboratoryServiceImpl.java`、`service/impl/EquipmentCategoryServiceImpl.java`、`service/impl/EquipmentServiceImpl.java` |
| Controller | `controller/LaboratoryController.java`、`controller/EquipmentCategoryController.java`、`controller/EquipmentController.java` |
| DTO/VO | `dto/EquipmentQueryDTO.java`、`vo/EquipmentVO.java` |
| 资源 | `resources/mapper/EquipmentMapper.xml`、`resources/db/phase2_laboratory_equipment.sql`、`resources/application-local.yaml.example` |

### 删除（11 个）

ShopController、ShopTypeController、IShopService、IShopTypeService、ShopServiceImpl、ShopTypeServiceImpl、
ShopMapper、ShopTypeMapper、Shop、ShopType、`resources/mapper/VoucherMapper.xml`

### 修改（13 个）

`.gitignore`、`pom.xml`、`application.yaml`、`MvcConfig`、`RedissonConfig`、`RedisConstants`、`CacheClient`、
`Voucher`、`VoucherController`、`IVoucherService`、`VoucherServiceImpl`、`VoucherMapper`、
`HmDianPingApplicationTests`

**计数核对**：56 − 10 + 19 = 65 个 main 源文件，与 `git status` 实际一致。

---

## 2. 验收标准逐条核验（计划 §5）

| 验收标准 | 结论 | 证据 |
| --- | --- | --- |
| 新数据库表设计正确 | ✅ | `information_schema` 核对 3 表 26 列全字段类型/可空/默认值一致；4 个 `tb_equipment` 索引 + `uk_name` 唯一键 + `idx_campus`/`idx_name` 齐备 |
| Entity/Mapper/Service/Controller 对应正确 | ✅ | 3 实体 ↔ 3 Mapper ↔ 3 Service ↔ 3 Controller 一一对应；`EquipmentMapper.selectEquipmentPage` 由 `EquipmentServiceImpl` 调用，XML namespace 与 id 匹配 |
| Laboratory 与 Equipment 关系明确 | ✅ | `tb_equipment.laboratory_id` + `category_id`；`EquipmentMapper.xml` 双 `LEFT JOIN`，详情接口实测返回 `laboratoryName`/`campus`/`building`/`room`/`categoryName` |
| 黑马 Shop 语义开始退出 | ✅ | `grep -rni "shop" --include=*.java --include=*.xml src/` → **0 hits**（大小写不敏感，零例外） |
| 查询接口可用 | ✅ | 9 类筛选 + 分页 + 详情全部实测通过（见 §3） |
| Mapper/XML 无错误 | ✅ | `EquipmentMapper.xml` 在运行时真实执行成功（物理分页 `total=12`） |
| Maven compile 成功 | ✅ | `mvn clean test-compile` BUILD SUCCESS；`mvn test` 2/2 通过 |
| 本阶段业务：实验室 CRUD | ✅ | POST/GET list/GET detail/PUT/DELETE 全部实测 |
| 本阶段业务：设备分类查询 | ✅ | 返回 6 条，按 `sort` 升序 |
| 本阶段业务：设备 CRUD | ✅ | POST/GET page/GET detail/PUT/PUT status/DELETE 全部实测 |
| 本阶段业务：按校区 / 实验室 / 分类 / 名称筛选 | ✅ | 单条件与组合条件均实测 |
| 本阶段业务：分页 | ✅ | `total=12`，5/5/2 物理分页正确 |
| 本阶段业务：设备状态管理 | ✅ | `PUT /equipment/{id}/status` 生效；非法值返回「设备状态不合法」 |
| 暂时不做（Redis 缓存/预约/Lua/MQ/复杂权限） | ✅ | 未引入任何相关代码 |

---

## 3. 运行时实测记录

应用以 JDK 8 启动（`server.port=18081`），MySQL / Redis / RabbitMQ 三中间件连接成功
（日志：`Redisson 3.13.6` 连接 `127.0.0.1:6379`、`amqp://eason@127.0.0.1:5672/`）。

> **环境注记**：本机 8081 被 `redis-cache-experiment-1.0.0.jar`（另一项目，JDK 17）占用；
> 8082 被本机 nginx（"采云台·管理端"）以 IPv4 占用。故验证改用 18081。
> 该冲突与本仓库代码无关，未被修改。所有 curl 均加 `--noproxy '*'`（本机有 `http_proxy=127.0.0.1:7897`）。

### 3.1 鉴权边界（设计 §6.5）

```
GET /laboratory/list       → HTTP 401   （未登录）
GET /equipment/page        → HTTP 401
GET /equipment-category/list → HTTP 401
GET /equipment/1           → HTTP 401
```

新接口未加入白名单，一律要求登录 —— 与 §6.5「本阶段有意从简」一致。

### 3.2 实验室与分类

| 请求 | 结果 |
| --- | --- |
| `GET /laboratory/list` | total=4 |
| `GET /laboratory/list?campus=东校区` | total=2，ids=[4,3] |
| `GET /laboratory/list?campus=主校区` | total=2，ids=[2,1] |
| `GET /laboratory/1` | `status:"OPEN"`（字符串，非序号） |
| `GET /laboratory/999` | `{"success":true}`（无数据） |
| `POST /laboratory` | 新建 id=6，落库 `status='CLOSED'` |
| `PUT /laboratory` | 部分更新 room→Z999、status→MAINTENANCE，其余字段保留 |
| `DELETE /laboratory/{id}` | 生效 |
| `GET /equipment-category/list` | 6 条，`sort` 1→6 升序 |

### 3.3 设备

| 请求 | 结果 |
| --- | --- |
| `GET /equipment/page` | total=12，ids=[12,11,10,9,8] |
| `GET /equipment/page?current=2` | ids=[7,6,5,4,3] |
| `GET /equipment/page?current=3` | ids=[2,1]（尾页 2 条） |
| `?campus=主校区` | total=6，ids=[10,9,4,3,2] |
| `?categoryId=1` | total=2，ids=[2,1] |
| `?laboratoryId=4` | total=4，ids=[12,11,8,7] |
| `?status=MAINTENANCE` | total=3，ids=[11,6,2] |
| `?bookingEnabled=false` | total=2，ids=[6,2] |
| `?name=显微镜`（模糊） | total=2，ids=[2,1] |
| `?campus=东校区&status=MAINTENANCE` | total=2，ids=[11,6]（组合条件） |
| `GET /equipment/1` | 联表字段齐全 |
| `GET /equipment/999` | `{"success":false,"errorMsg":"设备不存在"}` |
| `?current=99999` | `data:[]`, `total:12`（不越界、不报错） |
| `?campus=NOPE` | `data:[]`, `total:0` |
| `GET /equipment/page`（`/page` 未被 `/{id}` 吞掉） | ✅ 路由优先级正确 |

### 3.4 枚举落库验证（本 Phase 关键技术点）

`mybatis-plus.configuration.default-enum-type-handler = EnumTypeHandler` 的实际效果：

```
POST /equipment  {"status":"DISABLED","bookingEnabled":false}
  → 直查 MySQL：id=13  name=验收测试设备  status=DISABLED  booking_enabled=0
```

存入的是**枚举名字符串** `'DISABLED'`，不是序号 `2`；`Boolean` 正确映射为 `tinyint(1)`。
`PUT /equipment/13/status?status=MAINTENANCE` 后回读为 `"MAINTENANCE"`，非法值 `BOGUS` 返回「设备状态不合法」。

验证后测试数据已清理，最终库内为基线：`tb_laboratory=4`、`tb_equipment_category=6`、`tb_equipment=12`。

### 3.5 回归

```
mvn test → HmDianPingApplicationTests: 1/1 PASS
           RedissonTest:                1/1 PASS
```

`RedissonTest` 通过顺带证明了 `RedissonConfig` 由硬编码改为 `@Value` 注入后，运行时确实连上
`127.0.0.1:6379` 并完成加锁/解锁 —— 配置外置改造已运行时验证，非仅编译通过。

### 3.6 主链完好性

秒杀主链 11 个文件（`VoucherOrderServiceImpl`、`RabbitMQConfig`、`*.lua`、`RedisIdWorker`、
`SimpleRedisLock`、拦截器、`UserHolder`、`UserController`、`UserServiceImpl` 等）
**零改动**（diff 为空）。`CacheClient` 的 de-Shop 改造经核实其调用方仅原 `ShopServiceImpl`（已删除）+ 测试，
未触及秒杀链路。

---

## 4. 发现的问题

### P0

无。

### P1（不阻塞 Phase 2，须在指定 Phase 处理）

**P1-1｜`CacheClient.queryWithMutex` 存在互斥失效缺陷（继承自原课程代码）**

```java
boolean isLock = tryLock(lockKey);
if (!isLock) {
    Thread.sleep(50);
    return queryWithMutex(...);   // 递归返回后…
}
...
} finally {
    unlock(lockKey);              // …本层 finally 会删掉「别人持有」的锁
}
```

抢锁失败的线程在递归返回后，其外层 `finally` 会执行 `unlock`，**删除锁持有者的锁**，破坏互斥语义。

- 当前**无任何业务调用方**（唯一调用者 `ShopServiceImpl` 已在 Phase 1 删除），故为惰性缺陷，不影响 Phase 2。
- 但 Phase 4「Equipment Cache 热点设备缓存」将正式启用 `CacheClient`，**必须在 Phase 4 修复**
  （建议：仅在 `isLock == true` 时释放，或改为不递归的重试循环 + 仅持有者释放）。
- 本 Phase 刻意未动，因为设计 §5.6 明确「保留三种算法不变」，避免范围蔓延。

**P1-2｜设备/实验室写接口无角色校验**

任意已登录用户可 `POST/PUT/DELETE /equipment`、`/laboratory`。设计 §6.5 已声明本阶段有意从简，
由 Phase 3 用注解方式实现角色模型。**与设计一致，非缺陷**，此处仅登记为待办。

### P2

1. **`phase2_laboratory_equipment.sql` 的 INSERT 不幂等**：重复执行会因主键冲突报错。
   建表语句用了 `IF NOT EXISTS` 但种子数据未用 `INSERT IGNORE` / `ON DUPLICATE KEY UPDATE`。
   当前已成功执行一次，不影响结果；建议 Phase 10 统一 migration 规范时处理。
2. `SystemConstants.MAX_PAGE_SIZE = 10` 现已无引用（原用于商铺分页），成为死常量 → Phase 10。
3. `EquipmentMapper.xml` 的名称搜索用 `LIKE CONCAT('%',?,'%')`，前导通配符无法走 `idx_name`。
   当前数据量（12 行）无影响，登记备查。
4. **既有文档中的明文凭据**：`秒杀消息队列迁移RabbitMQ-交付报告.md:12` 含明文本机密码，
   且**已在提交 `433cdfb` 中**。**非本 Phase 引入**（历史遗留），改工作区无法从历史消除，
   push 前必须改写历史或轮换凭据 → 归入 Phase 10 凭据外置。

   > 本次提交前安全闸另拦下 `PHASE2-DESIGN.md` 第 32–34 行的明文凭据（本 Phase 我在设计阶段写入），
   > 已就地脱敏为指向 `application-local.yaml` 的占位，未进入 git 历史。
5. `SystemConstants.IMAGE_UPLOAD_DIR` 仍为 Windows 路径 `D:\lesson\...` → Phase 10（已登记）。
6. `Equipment` 删除为物理删除且无外键约束。预约表在 Phase 5 才建立，
   届时需明确「有未完成预约的设备不可删」的规则。

---

## 5. 规格缺陷声明（己方，非 Codex 责任）

Phase 2 任务包 v1 中的残留扫描准则写为 `grep "Shop"`（大小写敏感），
**漏掉了全大写常量** `LOCK_SHOP_KEY` 与 `shopJson` 变量，导致 v1 同时要求
「删除 `LOCK_SHOP_KEY`」和「`CacheClient` 不可修改」——自相矛盾。

Codex 在动手前暂停并上报该冲突，判断正确。已据此发布 v2：
扫描准则改为 `grep -rni`（零例外），把 `CacheClient` 移出禁改清单并给出精确 de-Shop 指令，
同时把 `Voucher.shopId` 由「保留」改为「删除」。

**结论**：这是我在 Control Plane 的规格错误，Codex 的暂停上报是正确的工程行为，应记功而非记过。

---

## 6. 结论

Phase 2 全部验收标准达成，无 P0 问题，可以进入下一 Phase。

Phase 3 的准入前置：无（P1-1 归属 Phase 4）。

```text
PHASE 2 — PASS
```
