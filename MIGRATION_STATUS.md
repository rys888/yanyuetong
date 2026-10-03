# Migration Status

> 黑马点评 → 高校实验资源预约平台 分阶段重构进度

## Current Phase

Phase 2 — Laboratory & Equipment（Review PASS，**待用户批准进入 Phase 3**）

## Passed

- Phase 0 — Baseline ✅
- Phase 1 — Legacy Cleanup ✅（Review PASS，详见 `docs/refactor/PHASE1-REVIEW.md`）
- Phase 2 — Laboratory & Equipment ✅（Review PASS，详见 `docs/refactor/PHASE2-REVIEW.md`）

## Current Work

### Phase 0 / 1（已完成）

- [x] JDK 8 compile baseline（BUILD SUCCESS，72 文件）
- [x] 数据库 schema 基线（10 张表）
- [x] Redis Key 基线（15 个 Key Pattern）
- [x] RabbitMQ 拓扑基线（Exchange/Queue/DLX/DLQ）
- [x] 文档第 24 节 10 个问题逐条核验（全部属实）
- [x] Git checkpoint（tag `before-lab-reservation-refactor`）
- [x] Phase 1 删除 17 个遗留文件（Blog/评论/关注/ScrollResult/NormalTest）
- [x] Phase 1 连带修改 10 个保留文件
- [x] Phase 1 独立 Review：`mvn clean test-compile` BUILD SUCCESS（main 56 / test 2）
- [x] Phase 1 主链完好性核验：秒杀/MQ/Lua 11 个文件零改动
- [x] Phase 1 本地提交 `cf687b6`「refactor: remove legacy social and geo modules」（未 push，按方案 A）

### Phase 2（已完成）

- [x] 删除 Shop 域 10 个 Java 文件 + `mapper/VoucherMapper.xml`
- [x] 新增 `tb_laboratory` / `tb_equipment_category` / `tb_equipment` 三表 DDL + 种子数据
- [x] 新增 3 实体 + 2 枚举（`LaboratoryStatus` / `EquipmentStatus`）+ 3 Mapper + 3 Service/Impl + 3 Controller
- [x] 新增 `EquipmentQueryDTO` / `EquipmentVO` / `EquipmentMapper.xml`（双表 LEFT JOIN + 动态条件 + 物理分页）
- [x] `CacheClient` de-Shop 改造（锁 key 改为 `LOCK_CACHE_KEY + keyPrefix + id`，`shopJson`→`json`，算法逻辑不变）
- [x] `RedisConstants` 删 4 个 Shop 常量、新增 `LOCK_CACHE_KEY = "lock:"`
- [x] 凭据外置：`spring.profiles.active: local` + gitignore 的 `application-local.yaml` + `.example` 模板
- [x] JDBC 驱动升级 `mysql:mysql-connector-java:5.1.47` → `com.mysql:mysql-connector-j:8.4.0`（MySQL 9.7.1 `caching_sha2_password`）
- [x] `RedissonConfig` 去掉硬编码 IP/密码，改 `@Value` 注入
- [x] 枚举持久化：`default-enum-type-handler: EnumTypeHandler`，落库为枚举名字符串
- [x] DDL 已实际执行到 `hmdp` 库（4 实验室 / 6 分类 / 12 设备）
- [x] 独立 Review：`information_schema` 逐列核对表结构、`mvn test` 2/2 PASS、全部接口实测、枚举落库实测
- [x] 残留扫描 `grep -rni "shop"` → 0 hits（零例外）

## Pending

- Phase 2 的 Git 提交（建议 `feat(domain): introduce laboratory and equipment model`）
- Phase 3 User & Authorization
- Phase 4 Equipment Cache
- Phase 5 Reservation Batch
- Phase 6 Redis + Lua Reservation
- Phase 7 RabbitMQ Reservation
- Phase 8 Consistency & Reliability
- Phase 9 Cancellation & Fulfillment
- Phase 10 Final Cleanup（含凭据外置入库 + 文档明文凭据清理 + 首次 push）

## Known Issues

### 已解决（原 P0）

- [x] 本机 MySQL 凭据 —— 用户提供，`127.0.0.1:3306 root/******` db `hmdp`（需 `allowPublicKeyRetrieval=true`），连接已验证
- [x] Redis 认证 —— `127.0.0.1:6379`，Redisson/RedisTemplate 连接已验证
- [x] RabbitMQ 认证 —— `localhost:5672` vhost `/`，应用启动即建连成功

### P1（业务/一致性缺陷，后续 Phase 处理）

1. 订单表无任何唯一索引 → Phase 8
2. 消费端扣库存 + INSERT 无事务 → Phase 8
3. Confirm Timeout 立即回滚 Redis → Phase 8
4. 补偿 Lua 不幂等 → Phase 8
5. Lua 不校验预约时间窗口 → Phase 5 / 6
6. DLQ 无治理入口 → Phase 8
7. logout 未实现 → Phase 3
8. MQ 直接发送数据库 Entity → Phase 7
9. **`CacheClient.queryWithMutex` 互斥失效**：抢锁失败的线程递归返回后，其 `finally` 会删除锁持有者的锁。当前无调用方（惰性缺陷），但 Phase 4 将启用 `CacheClient`，**必须在 Phase 4 修复** → Phase 4
10. 设备/实验室写接口无角色校验（设计 §6.5 有意从简）→ Phase 3

### P2（清理项）

1. Redis Key 重复定义（Java 常量 vs Lua 字面量）
2. `seckill:stock:` / `seckill:order:` 无 TTL
3. 四套历史秒杀实现以注释形式残留
4. `SystemConstants.IMAGE_UPLOAD_DIR` 硬编码 Windows 路径
5. `SystemConstants.MAX_PAGE_SIZE` 已无引用（死常量，随 Shop 分页一并退场）
6. 明文密码（`application.yaml` 已占位为 `change_me`，真实凭据在 gitignored 的 `application-local.yaml`）
7. `秒杀消息队列迁移RabbitMQ-交付报告.md:12` 含明文本机密码（**历史遗留，已在提交 `433cdfb` 中**，改工作区无法消除）→ push 前必须改写历史或轮换凭据
8. `UploadController` 的 `deleteBlogImg` / `/blog/delete` / `/blogs/` 语义残留（Phase 1 有意保留）→ Phase 10
9. DB 三表 `tb_blog` / `tb_blog_comments` / `tb_follow` 未删（用户已确认「只删代码」）
10. `EquipmentMapper.xml` 名称搜索前导通配符无法走 `idx_name`（当前 12 行无影响）
11. `phase2_laboratory_equipment.sql` 种子数据 INSERT 不幂等（重复执行会主键冲突）

## Last Review

Phase 2 Review — PASS — 2026-10-03，详见 `docs/refactor/PHASE2-REVIEW.md`
