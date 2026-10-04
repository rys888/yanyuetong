# Migration Status

> 黑马点评 → 高校实验资源预约平台 分阶段重构进度

## Current Phase

Phase 5 — Reservation Batch（Review PASS，**待用户批准进入 Phase 6**）

## Passed

- Phase 0 — Baseline ✅
- Phase 1 — Legacy Cleanup ✅（Review PASS，详见 `docs/refactor/PHASE1-REVIEW.md`）
- Phase 2 — Laboratory & Equipment ✅（Review PASS，详见 `docs/refactor/PHASE2-REVIEW.md`）
- Phase 3 — User & Authorization ✅（Review PASS，详见 `docs/refactor/PHASE3-REVIEW.md`）
- Phase 4 — Equipment Cache ✅（Review PASS，详见 `docs/refactor/PHASE4-REVIEW.md`）
- Phase 5 — Reservation Batch ✅（Review PASS，详见 `docs/refactor/PHASE5-REVIEW.md`）

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

### Phase 4（已完成）

- [x] **修复 P1-8**：`CacheClient` 互斥失效 —— per-acquisition 归属令牌 + 复用 `unlock.lua` 原子比对删除 +
      递归改有界循环 + 持锁后双重检查 + 中断位复位；逻辑过期路径同步修复（闭包令牌 + `log.error`）
- [x] `queryWithPassThrough` 未改；`unlock.lua` / `SimpleRedisLock` / `RedisData` 零触碰
- [x] 设备详情缓存：Key `lab:equipment:{id}`，Cache Aside + 空值缓存（2min）+ 互斥重建（方案 A）
- [x] TTL `30min + [0,5min)` 抖动（调用点计算，不侵入 `CacheClient.set` 通用语义）
- [x] 缓存失效下沉 Service 层：`save/update/updateStatus/delete` 四个方法内完成数据变更 + 删缓存
- [x] 回源方法拆为 `private queryEquipmentByIdFromDb`，杜绝 `dbFallback` 自递归
- [x] 新增 7 个测试（互斥 3 + 缓存 4），空值/穿透/失效/Hutool 往返逐字段断言全覆盖
- [x] 独立 Review：**隔离实验**证明旧实现可证伪（T1 失败）、归属校验可证伪（T1b 失败）、修复版 9/9 通过
- [x] 运行时实测：直改 DB 后接口仍返旧值（证明读缓存）、更新后失效、权限 401/403/200 零回归、基线数据未破坏
- [x] 本地提交 `4443ef6`「fix: repair CacheClient mutex mutual exclusion」+ `1814a82`「feat: add equipment detail cache」

### Phase 5（已完成，三次提交）

**5.0 遗留清理** — 本地提交 `3f9f673`「refactor: remove legacy voucher and seckill modules」

- [x] 删除 16 个遗留文件（Voucher/SeckillOrder/RabbitMQConfig/秒杀 Lua 等）
- [x] production Java/Lua 零残留（验收口径修正：`db/hmdp.sql` 建库脚本按设计保留）
- [x] `LoginInterceptor` 白名单同步移除 `/voucher/**`

**5.1 实验室归属** — 本地提交 `1f3824c`「feat: enforce laboratory ownership for lab admins」

- [x] `tb_laboratory.admin_user_id` + 幂等 DDL；种子归属 实验室 1/2 → 1016、3/4 → 1019
- [x] 两层校验落地：角色在拦截器边缘（`RoleInterceptor`），**归属在 Service 层**（`assertManageable` / `assertEquipmentManageable`）
- [x] 关闭 **P1-9**：7 个已标注端点 + 批次端点全部实测 403，且被拒调用零副作用
- [x] 独立 Review：判别实验证明 `assertManageable` 调用可被删除而测试变红（非空转断言）

**5.2 批次生命周期** — 本地提交「feat: add reservation batch lifecycle」

- [x] `tb_reservation_batch` + 幂等 DDL（重复执行 3 次无报错）
- [x] 状态机：持久化 `ReservationBatchStatus{DRAFT,PUBLISHED,CLOSED,CANCELLED}` + 时间派生 `BookingState` 纯函数（六态，注入 `now`，不入库）
- [x] 写端点全部 `@RequireRole(LAB_ADMIN)` + 归属校验；读端点为学生端/管理端两条路径
- [x] 发布不变式 I1–I7 逐条实现并逐条变体验证
- [x] 发布时 Redis 名额键 `reservation:batch:quota:{id}`，TTL = `endTime + 24h - now`
- [x] **修复 H9**：并发 publish 失败方删掉成功方名额键 → `SET NX` + 删除所有权判定
      （复现：20 并发 → `EXISTS=0`；修复后：1×200+19×400，键存在、值=4、TTL=611999）
- [x] **修复 H10**：`updateDraft` TOCTOU 把草稿字段写进已发布批次 → `updateDraft` 加状态 CAS + `publish` 绑定六个快照 CAS
      （复现：`PUBLISHED|99|99` 与 `PUBLISHED|99|4`；修复后 6 轮对撞只有两种合法终态）
- [x] **发现并修复 H12**（变体实验发现，非人工推演）：并发 publish × cancel 把已取消批次复活成 `PUBLISHED`
      （V6 变体实测 3/18 复活；修复版 0/18）
- [x] 学生端 `/page` 状态白名单（用户裁定）：只返回 `PUBLISHED`/`CLOSED`，`?status` 非白名单 → 400
- [x] 测试：`ReservationBatchLifecycleTest` 20 个 + `BookingStateTest` 9 个；`mvn test` **51 run / 0 failures**
- [x] 独立 Review：**17 组变体实验**核验判别力，暴露并关闭 3 处「守卫存在但无测试覆盖」的空洞
      （V6 → R6、V7 → R7、V8 → R7；其中 V8 需改用**错开释放**才可证伪，见 `PHASE5-REVIEW.md` §9.1）
- [x] HTTP 端到端：8 端点鉴权矩阵、归属 403、状态白名单、R9 之后的行为复验

## Pending

- Phase 6 Redis + Lua Reservation
- Phase 7 RabbitMQ Reservation
- Phase 8 Consistency & Reliability
- Phase 9 Cancellation & Fulfillment
- Phase 10 Final Cleanup（含凭据外置入库 + 文档明文凭据清理 + 首次 push）

## Known Issues

### 已解决

- [x] **P0** 本机 MySQL / Redis / RabbitMQ 凭据 —— 用户提供，三方连接全部验证通过
- [x] **P1-8** `CacheClient.queryWithMutex` 互斥失效 —— Phase 4 修复，并经**隔离实验**证明可被测试证伪
      （旧实现 T1 失败 `expected: <1> but was: <2>`；去掉归属校验 T1b 失败；修复版 9/9 通过）
      → 详见 `docs/refactor/PHASE4-REVIEW.md` §5
- [x] **P1-9** LAB_ADMIN 无实验室归属约束 —— Phase 5.1 修复，两层校验（角色在拦截器、归属在 Service），
      7 个既有端点 + 批次端点全部实测 403，且经判别实验证明守卫非空转
      → 详见 `docs/refactor/PHASE5-REVIEW.md` §5
- [x] **新增** H9 / H10 / H12 三个并发缺陷 —— Phase 5.2 修复（先运行时复现、再修复、再用同一实验证明修复）
      → 详见 `docs/refactor/PHASE5-REVIEW.md` §3

### P1（业务/一致性缺陷，后续 Phase 处理）

1. 订单表无任何唯一索引 → Phase 8
2. 消费端扣库存 + INSERT 无事务 → Phase 8
3. Confirm Timeout 立即回滚 Redis → Phase 8
4. 补偿 Lua 不幂等 → Phase 8
5. Lua 不校验预约时间窗口 → Phase 6（Phase 5 尚未引入 Lua，预约链路整体在 Phase 6 重建）
6. DLQ 无治理入口 → Phase 8
7. MQ 直接发送数据库 Entity → Phase 7
8. ~~`CacheClient.queryWithMutex` 互斥失效~~ → **已于 Phase 4 修复**（见「已解决」）
9. ~~**LAB_ADMIN 无实验室归属约束**：任何 LAB_ADMIN 可管理任意实验室与设备（计划 §6 称"管理自己负责的实验室"）~~
   → **已于 Phase 5.1 修复**（见「已解决」）
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
15. `CacheClientMutexTest` 的 T1 夹具过度复杂（`spy` + 线程改名 + `doAnswer` 忙等 + 用 `"{}"` 规避 Hutool
    对顶层 JSON 字符串 `toBean(String.class)` 的限制）；断言有效但脆弱 → Phase 10 评估简化
16. `CacheClient.queryWithPassThrough` / `queryWithLogicalExpire` 已无生产调用方（设备详情只用 `queryWithMutex`）
    → Phase 10 决定去留
17. `EquipmentCacheTest` 通过 MyBatis `Interceptor` 匹配 `EquipmentMapper.selectById` 语句 id 计数；
    若回源语句变更会**静默失效**（测试仍通过但不再测任何东西）→ 维护性提醒
18. 缓存删除失败无补偿（靠 30min TTL 兜底）；重试耗尽的兜底直查不写缓存，极端竞争下可能惊群 → Phase 8 范围
19. 设计文档 §7 曾错称 T1「直击 D2」，已更正并补 T1b（自查发现，见 `PHASE4-REVIEW.md` §5）
20. **F3**：`GET /laboratory/list` 与 `GET /laboratory/{id}` 是公开读端点，直接序列化实体，会把 Phase 5.1
    新增的 `admin_user_id` 暴露给任何已登录用户。属信息暴露而非越权（暴露的是"谁管这个实验室"，不构成写权限）；
    修法（返回 VO 或 `@JsonIgnore`）会改动 Phase 2 的既有接口面 → Phase 10
21. **F4**：`DELETE /laboratory/{id}` **不检查是否存在依赖设备/批次**。这是 Phase 2 遗留的既有缺口，
    Phase 5 的批次表让后果更实（删实验室会留下孤儿批次）→ 待决（属 Phase 2 契约变更）
22. **可观测性**：`publish` 的 `SET NX` 拒绝分支与条件 UPDATE 的 CAS 拒绝分支**复用同一句报错文案**
    「批次状态已变化，发布失败」，排障时无法区分是哪一层拦下的（实测 7 个失败方全部报同一句）→ Phase 10
23. 设计文档 §9 的 H 编号在 Phase 5 期间扩到 H12；`PHASE5-DESIGN.md` §10 T9 原写的判别实验
    （"去掉 `WHERE status='DRAFT'` → 必须失败"）**是错的**，已更正（串行路径根本走不到那条 UPDATE）→ 已修文档
24. `ReservationBatchLifecycleTest` 的并发用例依赖**时序**（错开释放扫描）；正确实现下恒绿，但判别力是概率性的
    （V6 实测 3/18 命中）。已登记为固有局限，见 `PHASE5-REVIEW.md` §11

## Last Review

Phase 5 Review — PASS — 2026-10-04，详见 `docs/refactor/PHASE5-REVIEW.md`
（含 3 轮返工闭环：先运行时复现 H9/H10，再修复，再用 17 组变体实验核验测试判别力）
