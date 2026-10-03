# Migration Status

> 黑马点评 → 高校实验资源预约平台 分阶段重构进度

## Current Phase

Phase 3 — User & Authorization（Review PASS，**待用户批准进入 Phase 4**）

## Passed

- Phase 0 — Baseline ✅
- Phase 1 — Legacy Cleanup ✅（Review PASS，详见 `docs/refactor/PHASE1-REVIEW.md`）
- Phase 2 — Laboratory & Equipment ✅（Review PASS，详见 `docs/refactor/PHASE2-REVIEW.md`）
- Phase 3 — User & Authorization ✅（Review PASS，详见 `docs/refactor/PHASE3-REVIEW.md`）

## Current Work

### Phase 0 / 1（已完成）

- [x] JDK 8 compile baseline、数据库/Redis/RabbitMQ 基线记录
- [x] 文档第 24 节 10 个问题逐条核验
- [x] Git checkpoint（tag `before-lab-reservation-refactor`）
- [x] Phase 1 删除 17 个遗留文件（Blog/评论/关注/ScrollResult/NormalTest）
- [x] Phase 1 本地提交 `cf687b6`「refactor: remove legacy social and geo modules」

### Phase 2（已完成）

- [x] 删除 Shop 域 10 个 Java 文件 + `mapper/VoucherMapper.xml`
- [x] 新增 `tb_laboratory` / `tb_equipment_category` / `tb_equipment` 三表 + 种子数据
- [x] 新增 3 实体 + 2 枚举 + 3 Mapper + 3 Service/Impl + 3 Controller + DTO/VO + `EquipmentMapper.xml`
- [x] `CacheClient` de-Shop 改造；`RedisConstants` 换 `LOCK_CACHE_KEY`
- [x] 凭据外置：`spring.profiles.active: local` + gitignored `application-local.yaml` + `.example` 模板
- [x] JDBC 驱动升级 `mysql-connector-j:8.4.0`；`RedissonConfig` 改 `@Value` 注入
- [x] 枚举持久化 `EnumTypeHandler`（落库字符串）
- [x] 本地提交 `efa0ac4`「feat: add laboratory and equipment domain」

### Phase 3（已完成）

- [x] 新增 `UserRole` 枚举（`USER` / `LAB_ADMIN`）+ `@RequireRole` 注解 + `RoleInterceptor`
- [x] `tb_user.role varchar(16) NOT NULL DEFAULT 'USER'` + 幂等 DDL 脚本（`information_schema` 守卫）
- [x] 种子管理员账号 `13900000001` / `LAB_ADMIN`（`ON DUPLICATE KEY UPDATE` 幂等）
- [x] `User` / `UserDTO` 加 `role`，经 Redis Session 端到端贯通（枚举 ↔ 字符串往返实测通过）
- [x] `createUserWithPhone` 显式 `setRole(UserRole.USER)`（内存对象陷阱，已实测堵住）
- [x] 7 个管理端点标注 `@RequireRole(LAB_ADMIN)`（Equipment 4 + Laboratory 3），GET 一律不标
- [x] `MvcConfig` 注册 `RoleInterceptor`（`/**`，order 2）
- [x] 实现 `logout`（删除 `login:token:{t}`，幂等）
- [x] 独立 Review：401/403/200 全矩阵亲自实测、遗留 Session fail-safe 验证、DDL 幂等双跑、`mvn test` 2/2 PASS
- [x] 1011 个既有用户全部自动获得默认 `USER`，无一破坏

## Pending

- Phase 3 的 Git 提交（建议 `feat: add user roles and authorization`）
- Phase 4 Equipment Cache
- Phase 5 Reservation Batch
- Phase 6 Redis + Lua Reservation
- Phase 7 RabbitMQ Reservation
- Phase 8 Consistency & Reliability
- Phase 9 Cancellation & Fulfillment
- Phase 10 Final Cleanup（含凭据外置入库 + 文档明文凭据清理 + 首次 push）

## Known Issues

### 已解决（原 P0）

- [x] 本机 MySQL / Redis / RabbitMQ 凭据 —— 用户提供，三方连接全部验证通过

### P1（业务/一致性缺陷，后续 Phase 处理）

1. 订单表无任何唯一索引 → Phase 8
2. 消费端扣库存 + INSERT 无事务 → Phase 8
3. Confirm Timeout 立即回滚 Redis → Phase 8
4. 补偿 Lua 不幂等 → Phase 8
5. Lua 不校验预约时间窗口 → Phase 5 / 6
6. DLQ 无治理入口 → Phase 8
7. MQ 直接发送数据库 Entity → Phase 7
8. **`CacheClient.queryWithMutex` 互斥失效**：抢锁失败的线程递归返回后，其 `finally` 会删除锁持有者的锁。
   当前无调用方（惰性缺陷），但 Phase 4 将启用 `CacheClient`，**必须在 Phase 4 修复** → **Phase 4（准入前置）**
9. **LAB_ADMIN 无实验室归属约束**：任何 LAB_ADMIN 可管理任意实验室与设备（计划 §6 称"管理自己负责的实验室"）。
   经用户 2026-10-04 裁决，归属模型**推迟到 Phase 5** → Phase 5
   （届时需给已标注的 7 个端点补"且该实验室归我管"的判断）
10. **Session TTL 过长**：`LOGIN_USER_TTL = 36000` 分钟 ≈ 25 天，且每次请求续期、无绝对上限。
    既有配置非本阶段引入，登记为待决项 → Phase 10 决定是否缩短

### P2（清理项）

1. Redis Key 重复定义（Java 常量 vs Lua 字面量）
2. `seckill:stock:` / `seckill:order:` 无 TTL
3. 四套历史秒杀实现以注释形式残留
4. `SystemConstants.IMAGE_UPLOAD_DIR` 硬编码 Windows 路径
5. `SystemConstants.MAX_PAGE_SIZE` 已无引用（死常量）
6. 明文密码（`application.yaml` 已占位 `change_me`，真实凭据在 gitignored 的 `application-local.yaml`）
7. `秒杀消息队列迁移RabbitMQ-交付报告.md:12` 含明文本机密码（**历史遗留，已在提交 `433cdfb` 中**，改工作区无法消除）
   → push 前必须改写历史或轮换凭据
8. `UploadController` 的 `deleteBlogImg` / `/blog/delete` / `/blogs/` 语义残留 → Phase 10
9. **点评式用户残留**：`tb_user_info`（0 行）、`UserInfo` 四件套、`GET /user/info/{id}`、`GET /user/{id}`
   —— 经用户 2026-10-04 裁决**归 Phase 10** → Phase 10
10. DB 三表 `tb_blog` / `tb_blog_comments` / `tb_follow` 未删（用户已确认"只删代码"）
11. `EquipmentMapper.xml` 名称搜索前导通配符无法走 `idx_name`（当前 12 行无影响）
12. `phase2_laboratory_equipment.sql` 种子数据 INSERT 不幂等（重复执行会主键冲突）；
    Phase 3 的脚本已改用幂等写法，Phase 2 的待回补
13. `tb_user.password` 从未被使用（密码登录未实现）；种子管理员 `13900000001` 无密码，只能验证码登录
14. `RoleInterceptor` 类级注解分支当前无使用者（按设计保留）；403 响应体为空系有意选择

## Last Review

Phase 3 Review — PASS — 2026-10-04，详见 `docs/refactor/PHASE3-REVIEW.md`
