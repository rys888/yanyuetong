# Migration Status

> 黑马点评 → 高校实验资源预约平台（**研约通 / yanyuetong**）分阶段重构进度台账

## Current Phase

**Phase 10 — Final Cleanup & Delivery —— PROJECT MIGRATION COMPLETE（T1–T6 全部完成）**

设计与验收台账：`docs/refactor/PHASE10-DESIGN.md`；用户可读总览：根目录 `README.md`。
本 Phase 交付：残留业务与死代码清除、包名与 Maven 坐标迁移至 `com.yanyuetong`、迁移链重建为编号脚本、
P1/P2 行为修复、测试卫生核对、4 份根目录文档归置入 `docs/`、全仓凭据脱敏、README 15 节从零撰写。

**一项未闭环**：10 张历史课程死表的 `DROP`（脚本已就绪，执行需用户在实时对话中逐表确认，见「Pending」）。

## Passed

- Phase 0 — Baseline ✅
- Phase 1 — Legacy Cleanup ✅（Review PASS，详见 `docs/refactor/PHASE1-REVIEW.md`）
- Phase 2 — Laboratory & Equipment ✅（Review PASS，详见 `docs/refactor/PHASE2-REVIEW.md`）
- Phase 3 — User & Authorization ✅（Review PASS，详见 `docs/refactor/PHASE3-REVIEW.md`）
- Phase 4 — Equipment Cache ✅（Review PASS，详见 `docs/refactor/PHASE4-REVIEW.md`）
- Phase 5 — Reservation Batch ✅（Review PASS，详见 `docs/refactor/PHASE5-REVIEW.md`）
- Phase 6 — Redis + Lua Reservation ✅（Review PASS，详见 `docs/refactor/PHASE6-REVIEW.md`）
- Phase 7 — RabbitMQ Reservation ✅（Review PASS，详见 `docs/refactor/PHASE7-REVIEW.md`；本地提交 `02daa19`）
- Phase 8 — Consistency & Reliability ✅（Review PASS，详见 `docs/refactor/PHASE8-REVIEW.md`；本地提交 `ede414b`）
- Phase 9 — Cancellation & Fulfillment ✅（Review PASS，详见 `docs/refactor/PHASE9-REVIEW.md`；本地提交 `b917535`）
- Phase 10 — Final Cleanup & Delivery ✅（详见 `docs/refactor/PHASE10-DESIGN.md`；T1–T6 提交见下）

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

### Phase 6（已完成）

- [x] `reservation.lua`：**漏斗顺序** EXISTS(1) → 窗口(4/5) → 容量(2) → 去重(3) → `DECR`+`SADD`+`EXPIRE`；
      **无字面量 key**、**无 `DECR` 后夹取**（加了会让「名额永不为负」失去判别力）
- [x] `ReservationQuotaExecutor`：脚本唯一调用点，静态加载（沿用 `SimpleRedisLock` 模式）
- [x] **ARGV 全为 String**：`DefaultScriptExecutor` 会把每个 ARGV 交给 `StringRedisSerializer`，
      传 `Long` 会在运行期抛 `ClassCastException`（`javap -c` 反汇编确认）；已写成硬约束并逐项核对通过
- [x] 返回码 0–5 与 `ReservationResultCode` 一一映射；脏配额值抛错（响亮失败）
- [x] Redis 模型：`reservation:batch:quota:{id}`（`publish` `SET NX` 建、`close`/`cancel` 删）
      + `reservation:batch:users:{id}`（Lua 惰性 `SADD`+`EXPIRE`）；**「名额键存在」⟺「批次可预约」，无状态镜像**
- [x] 双方向论证：撤销方向由 Lua 的 `EXISTS` 守；**激活方向由 Java 状态闸门守**
      （Phase 5.2 的 `publish` 是「先建键、后 CAS 提交状态」，Lua 的 `EXISTS` 在这个方向会误放行）
- [x] 读路径优先取 Redis（用户裁定 D1）；学生读 `DRAFT`/`CANCELLED` 批次详情返回 `null`（用户裁定 D2）
- [x] `requestId` 与 `reservationId` 合一，只留 `reservationId`（用户裁定 D3），且在 Lua 之前生成
- [x] 测试：`ReservationQuotaExecutorTest` 8 个 + `ReservationSubmitTest` 17 个；`mvn test` **76 run / 0 failures**
- [x] **F7 实验证明「必须用 Lua」**：仅把校验与扣减拆成两次往返（守卫一个不少）→ 20 并发抢 5 名额 **9 人成功**、
      同一用户 20 并发 **13 次成功**；Lua 实现下同一批用例恒绿
- [x] 独立 Review：**13 组变体 + 3 组窗口可达性实验**（全部有效、0 组无效）→ 抓出并关闭 **2 处服务层覆盖空洞**：
      ① 服务层从未验证 Lua 的 `EXISTS` 是撤销方向的权威（F1 下 15 个用例全绿）；
      ② 分页路径的读覆盖零覆盖（F6 只打红详情路径）
- [x] 返工补 T21/T22 后用同一实验复验：删 `EXISTS` → 只有 T21 变红；覆盖改空实现 → T22 + T13 变红
- [x] 关闭 P1-5 / P2-1 / P2-2 / P2-3；文档更正 §3.3 的「500」（实为 200，见 P2-25）
- [x] 环境复原：Redis 无残留键；DB 回到基线（batch 0 / equipment 12 / laboratory 4 / user 1013），
      并清理了中途 kill 掉 campaign 所留的一组夹具

### Phase 7（已完成）— 本地提交 `02daa19`「feat: add rabbitmq async reservation persistence」

- [x] 拓扑：`reservation.exchange`(direct/durable) + `reservation.create.queue`(durable,
      `x-dead-letter-exchange=reservation.dlx` + `x-dead-letter-routing-key=reservation.create.dlq`)
      + `reservation.dlx` + `reservation.create.dlq`；broker 侧逐项实测与设计 §2.1 对齐
- [x] 生产者：`submit` 抢到名额后发消息，**显式等待 confirm 5s** 并检查 `getReturnedMessage()`
      —— 字节码复核确认 `doSend` 从不调用 `Future.get`（131 条指令零命中），该等待是承重的；F7b 反向证实
- [x] 消费者：`ReservationMessageConsumer` 把**整个方法体**包在 `try` 中；三处 `basicNack` 的 requeue **全为 false**
- [x] **失败模型（字节码复核，非直觉）**：`isRejectManual` 只看顶层 `instanceof`，`shouldRequeue` 却遍历 cause 链
      —— 二者**不对称**；非致命异常被 `ConditionalRejectingErrorHandler` 静默吞掉 → `rollbackOnExceptionIfNecessary`
      在 `rejectManual=false` 时**不发任何 ack/nack** → **消息永久 unacked、prefetch=1 下彻底卡死**。
      故「监听器必须自己捕获一切异常」定为硬约束（设计 §7.6 / 风险 R10）
- [x] 幂等：先 `selectCount(userId,batchId)` 预检查 → 再条件扣减（`remaining_quota > 0` 守卫）→ 再 INSERT，
      由 `uk_user_batch` 唯一键兜底
- [x] 回滚脚本 `reservationRollback.lua`：**`EXISTS` 守卫** —— 名额键存在才 `INCR`（返回 1），
      缺失则只 `SREM`（返回 0）。防的是「回滚把已关闭批次的名额键复活」（Redis `INCR` 会建键）
- [x] 持久化 `@Transactional` 与监听器**分属两个 Bean**（规避自调用失效）；UPDATE-then-INSERT 规避「返回值不触发回滚」
- [x] 测试：`ReservationPersistenceTest` 4 个 + `ReservationMqTest` 10 个；
      **`mvn test` 90 run / 0 failures / 0 errors / 0 skipped**
- [x] 独立 Review：**8 组证伪实验**（F1/F2/F3/F5/T4/F6/F7/F7b）→ 抓出 1 处**零判别力**覆盖并返工补测，
      F6 复测证明补测有效（同一变体：改造前全绿，改造后仅新用例变红）
- [x] **全量回归抓出 1 个既有测试回归**：异步落库击穿 `ReservationSubmitTest` 四处「MySQL 保持不动」断言
      （行 208 `expected <4> but was <3>`）。修法为**隔离异步参与者**（夹具停容器 + 清队列）而非改期望值，
      从而保住「读 Redis vs 读 MySQL」的差值判别力
- [x] 顺带修复：`ReservationSubmitTest.cleanUp` 不删 `tb_reservation` → 实测跑后残留 **8 行孤儿**
      （批次已删、行未删，且残留数只有成功提交数的一部分——**该"部分残留"本身就是竞态物证**）；
      补按 `batchIds` 删除后跑后残留 **0 行**
- [x] 环境复原：`tb_reservation` 0 / `tb_reservation_batch` 0 / 两个队列均 0-0 / Redis `reservation:*` 与
      `lock:reservation:*` 均 0；`reservation.lua` 仍为 Phase 6 记录指纹；`pom.xml` 与 `application*.yaml` 未动
- [x] **生产代码零改动**（返工阶段）：mtime 窗口（窗口内仅两个测试文件）+ 与返工前备份逐字节比对，两条独立证据

### Phase 8（已完成）— 本地提交 `ede414b`「feat: add consistency reconciliation for reservation」

设计已产出：`docs/refactor/PHASE8-DESIGN.md`（阶段一~四，**待用户批准后进入实现**）。

**范围取准的关键判断**：Phase 8 的新增工作量**不是** 8.1–8.9 全部 —— 8.1/8.2/8.3/8.4
Phase 7 已做掉（本 Phase 只复核），真缺口是 8.5 + 8.6 + 8.7 + 8.8 + 8.9，
且**五者是同一件事的五个面**（要区分「确定没进 broker」与「不知道进没进」就必须有持久化状态；
要能对账/重放，补偿就必须幂等），不能拆成五个独立补丁。

- [x] **T1 幂等补偿 Lua**（`SREM` 返回值为令牌，返回码 `0/1/2`）—— 已完成并经判别力实验
  - `reservationRollback.lua` 改为「先 `SREM`、再判 `EXISTS`」，返回 `0/1/2`
    （`0`=本来就没占 / `1`=已归还 / `2`=占了但名额键已缺失）；执行器接受 `0/1/2`
  - **§7.2 的期望值变更已落实并留痕**：`shouldNotRecreateQuotaKeyWhenRollbackRunsAfterClose`
    由 `0` 改断言 `2`，附注释说明「与『本来就没占用』的 0 区分开」
  - 新增幂等用例 `shouldNotRestoreQuotaTwiceForSameUser`（连补两次：第一次 1、第二次 0，名额不得变 5）
  - **返工 1 次（R-A）**：新用例原名 `...WhenUserNeverHeldOne` 与场景不符（用户其实抢到过名额），
    实为"补偿幂等"而非"从未占用" —— 与 Phase 7「名为重复消息、实为按 id 幂等」同类缺陷，已改名
  - **判别力实验（Claude 亲跑）**：退回旧写法 → 两个用例双双变红；只删 `EXISTS` 守卫 →
    关闭场景用例变红；两次均字节一致复原（`reservationRollback.lua` = `7bc238f05523282d`）
  - **全量回归 91 run / 0 failures / 0 errors / 0 skipped**（Phase 7 为 90，+1 新用例）
  - 环境复原：DB 各表回基线、Redis 三类键均 0、两队列 ready/unacked/consumers 全 0
- [x] **T2 请求状态账本** `tb_reservation_request` + 五态枚举 + CAS 更新 —— 已完成并经判别力实验
  - 新增 7 文件：`db/phase8_consistency.sql`（DDL）、`entity/ReservationRequest`、
    `enums/ReservationRequestStatus`、`mapper/ReservationRequestMapper`、
    `service/IReservationRequestService` + `impl/ReservationRequestServiceImpl`、
    `test/ReservationRequestStateTest`。**未改动任何既有文件**
  - DDL 与设计 §4.1 逐字一致；**幂等性以反向对照证实**：连跑 3 次均 exit 0，
    而删掉 `IF NOT EXISTS` 立刻 `ERROR 1050 (42S01) Table already exists` / exit 1
  - 枚举沿用全局 `EnumTypeHandler` 落 `name()` 字符串（普通枚举字段，无 `@EnumValue`/自定义 TypeHandler），
    与 `ReservationBatch.status` 同口径；`isTerminal()`/`nonTerminalStatuses()` 收敛终态判定
  - `casStatus` 用**单条条件 UPDATE**（`eq(requestId) + in(status, expected) + set(status, target)`），
    无"先查后改"的 TOCTOU 窗口；空 `expectedStatuses` 抛 `IllegalArgumentException`；
    `update_time` 一律不显式写，交给 DDL 的 `ON UPDATE CURRENT_TIMESTAMP`
  - 测试 7 个（创建/命名约定/CAS 命中/CAS 未命中/终态冻结语义/空参守卫/`listStale` 边界）
  - **返工 1 次（R-A）**，三项：
    ① **覆盖缺口**：判别实验证实把 `update_time < before` 谓词**整行删掉，7 个用例仍全绿** ——
       而该阈值正是 §8.9 对账「长期未决」的判据，失效会把刚入账、仍在正常流转的请求误判为卡住并误补偿。
       已补「以过去时刻为阈值必须扫不到」的反例断言；重跑同一变体 → 该用例变红，缺口闭合
    ② `listStale(before, 1)` 断言由 `size() <= 1` 收紧为 `assertEquals(1, size())`
    ③ `ReservationRequestServiceImpl` 的 `extends ServiceImpl` 是**死继承**（接口未 `extends IService`，
       `baseMapper` 零使用），已改为与同代兄弟类 `ReservationPersistenceServiceImpl` 一致的
       普通 `@Service` 类 + `@Resource` 注入 Mapper
  - **判别力实验（Claude 亲跑，共 6 个变体）**：F2 拆掉 CAS 条件 → 2 用例红；F3 拆空参守卫 → 1 红；
    F4 拆非终态过滤 → 1 红；F5a 反转 `<` 为 `>` → 1 红；F6 `recordFailReason` 顺手改 status → 1 红；
    F7 建账本写 `PUBLISHED` → 6 红。**F5b 删阈值谓词 → 0 红（即上述缺口，返工后复测变红）**
  - 实验 harness 自身踩坑 3 次（改写守卫用了改前改后都存在的串 / 判定逻辑写反 / 用 grep 退出码当 mysql 退出码），
    均为我侧错误，已逐个修正重跑；两轮实验结束均校验工作区与交付版本**字节一致**
  - **全量回归 98 run / 0 failures / 0 errors / 0 skipped**（T1 后为 91，+7 新用例）
  - 本地库已应用 DDL；环境复原：`tb_reservation_request`=0、`tb_reservation`=0、`tb_reservation_batch`=0、
    `tb_laboratory`=4、`tb_equipment`=12、`tb_user`=1013；Redis `reservation:*`=0、`lock:*`=0；
    两队列 ready/unacked/consumers 全 0
  - 交付文件指纹（SHA-256 前 16 位，**T2 交付时**；其中 `IReservationRequestService` 与
    `ReservationRequestServiceImpl` 已被 T3 更新，见 T3 条目）：`phase8_consistency.sql`=`b72ddcc2329277d3`、
    `ReservationRequest.java`=`a1ab654073e6c9e4`、`ReservationRequestStatus.java`=`2382c962227ca809`、
    `ReservationRequestMapper.java`=`8e7daa7ac82e30e4`、`IReservationRequestService.java`=`4bb39c3de0a69375`、
    `ReservationRequestServiceImpl.java`=`645b29ae4fbb0646`、`ReservationRequestStateTest.java`=`b676574d49b1763d`
  - 注：项目无 SQL 自动初始化配置（与 phase2/3/5/7 一致），脚本需**手工应用**，已在本地库执行
- [x] **T3 生产者改造**（先写账本、后扣名额 + 异常分类 + 双 503 文案）—— 已完成并经判别力实验
  - `ReservationServiceImpl`：账本写入提到 Lua 抢名额**之前**（D3）；`publishReservationMessage` 改为
    **先判定、再动作**（`PublishOutcome` 三值），不再用 `catch (Exception)` 一网打尽后无条件回滚
  - 异常分类严格按 §3.3：`confirm.isAck()==false` / `getReturnedMessage()!=null` / `AmqpConnectException`
    → **确定未投递**（先回滚、再 CAS `COMPENSATED`）；`TimeoutException` 与**其它一切** → `UNKNOWN`
    （**不回滚**）。两处**不再用抛异常来表达 NACK** —— 旧写法会被自己的 `catch (Exception)` 误分类
  - 「确定未投递」分支：回滚成功才 CAS `COMPENSATED`；**回滚自身抛异常时不置终态**，留在 `PRE_ACCEPTED`
    交对账重试（**闭合 Phase 7 §18.2 缺口**，对应不变式 I12）
  - 两个 503 文案：「预约失败，请重试」（确定未投递 / 账本写失败）与
    「预约结果待确认，请稍后查询」（`UNKNOWN`，用户 2026-10-05 裁定的 D-b）
  - `submit` **不加** `@Transactional`（§4.3）；`BadRequestException` 的 400 语义与 Phase 6 完全一致未改
  - `IReservationRequestService` 新增 `casStatusWithFailReason`（单条条件 UPDATE 同时写 status 与
    `fail_reason`，口径与 `casStatus` 一致）—— 避免"状态已改、原因丢失"的中间态
  - 新增 `ReservationPublishFailureTest` 8 个用例（NACK / Return / 连接失败 / Timeout / 普通 AMQP 异常 /
    回滚失败 / 账本首写失败 / ACK）
  - **返工 1 次（R-A）**，两项：
    ① **数据污染（真实缺陷，源于我任务包的漏项）**：T3 让 `submit` 开始写账本后，
       全量套件在 `tb_reservation_request` 留下 **55 行孤儿**（COMPENSATED 38 + PUBLISHED 17），
       而 `tb_reservation`/`tb_reservation_batch` 干净 —— 即**只有账本表在泄漏**。
       `ReservationSubmitTest` 与 `ReservationMqTest` 都经真实 `submit` 产生账本行，其 `@AfterEach` 未清账本；
       已各补一句按 `batchId` 的删除（diff 仅 import + mapper 字段 + 1 条 delete，**断言零改动**）。
       修复后全量复跑残留归零
    ② **覆盖缺口**：`completeConfirm(boolean)` 只被以 `false` 调用，`true` 是**死代码**，
       导致 ACK 后 `markPublishedWithRetry`（正常成功路径上唯一的状态写入）**零覆盖**；
       已补 ACK 用例，断言账本为 `PUBLISHED`、`fail_reason` 为空、名额仍占用（`"0"`）且用户仍在集合
  - **判别力实验（Claude 亲跑 7 个变体，全部有判别力）**：G1 让 `UNKNOWN` 也回滚 → 两个 UNKNOWN 用例
    双双 `expected: <0> but was: <1>`（名额被错误归还）；G2 普通异常也算确定未投递 → 2 红；
    G3 回滚失败仍置 `COMPENSATED` → 1 红；G4 把账本写入挪到扣名额之后（推翻 D3）→ 1 红；
    G5 ACK 后不写 `PUBLISHED` → 1 红；G6 连接失败也当 `UNKNOWN` → 1 红；G7 让 NACK 检测恒不成立 → 2 红
  - 实验 harness 自身再踩 1 次（surefire 报告行格式假设错误，把 7 个有判别力的变体全部误报成无判别力）；
    未重跑，改为从 mvn 日志的 `[ERROR] <Class>.<method>:<line>` 取回真实用例名
  - **全量回归 106 run / 0 failures / 0 errors / 0 skipped**（T2 后为 98，+8 新用例）
  - 环境复原：`tb_reservation_request`=0、`tb_reservation`=0、`tb_reservation_batch`=0、
    `tb_laboratory`=4、`tb_equipment`=12、`tb_user`=1013；Redis `reservation:*`=0、`lock:*`=0；
    两队列 ready/unacked/consumers 全 0
  - 交付文件指纹（SHA-256 前 16 位）：`ReservationServiceImpl.java`=`1dd5647a4b033801`、
    `ReservationPublishFailureTest.java`=`c19bf3d68ba53404`；受 T3 连带影响的 T2 文件更新为
    `IReservationRequestService.java`=`23090cd69f1a96cb`、`ReservationRequestServiceImpl.java`=`d88269958b6b9b89`
  - **登记待 T4 处理**：ACK 后生产者 CAS 到 `PUBLISHED` 与消费者 CAS 到 `CONFIRMED` 存在竞态
    （消费者先完成时生产者 CAS 失败并 `log.error` 重试耗尽）。**状态结果正确**（不会把 `CONFIRMED`
    回退），但会产生误导性的 ERROR 日志。T4 引入消费者 `CONFIRMED` 写入后即变可达，届时在失败分支
    读一次账本：已终态则 `log.info`（正常竞态），否则 `log.error`（确需对账）
- [x] **T4 消费者改造**（`COMPENSATED` 前置检查 + `fail_reason` + 撞键兜底）—— 已完成并经判别力实验
  - `ReservationMessageConsumer`：落库**前**查账本 —— 为 `COMPENSATED` 则拒绝落库并 **ACK**（否则会形成
    「MySQL 有行 + Redis 名额已归还」的超发，对应不变式 I10）；账本行不存在则告警后照常落库
  - 三条成功/重复路径（`PERSISTED` / `DUPLICATE` / 撞键）统一**先尽力推进账本为 `CONFIRMED` 再 ACK**；
    两个"尽力"封装（`markConfirmedSafely` / `recordFailReasonSafely`）**各自吞异常只记日志**，
    **绝不改变 ack/nack 方向** —— 账本写入失败不得影响消息结算（对应 D10）
  - `CONFIRMED` 的 CAS 期望集合 = **非终态全集**（`PRE_ACCEPTED`/`PUBLISHED`/`UNKNOWN`），而非仅 `PUBLISHED`：
    生产者写 `PUBLISHED` 与消费者写 `CONFIRMED` 无先后保证，对账也可能先把行标成 `UNKNOWN`；
    同时**刻意不把 `CONFIRMED` 放进期望集合**，以杜绝 `CONFIRMED → PUBLISHED` 回退
  - 三条失败路径写对应 `fail_reason` 且 **status 不变**：`LOCK_TIMEOUT`、`QUOTA_CONFLICT`、
    `PERSIST_EXCEPTION:<异常类名>`；`ReservationPersistenceServiceImpl` **零改动**（撞键必须穿透而非在
    `persist` 内被吞，否则 `@Transactional` 不回滚 → 扣了名额却没有预约行）
  - `ReservationServiceImpl.markPublishedWithRetry`：ACK 后 CAS `PUBLISHED`（期望仅 `PRE_ACCEPTED`），
    重试 2 次；**耗尽后只读一次账本**，已终态则 `log.info`（正常竞态）、否则 `log.error`（确需对账）
    —— **闭合 T3 登记的「误导性 ERROR 日志」待办**，且该读不参与任何状态写入
  - `ReservationMqTest` 新增 5 个用例；另修 `waitForRequestStatus` 超时返回 `null`
    （原先返回最后一次读到的对象，使 `assertNotNull(waitForRequestStatus(...))` 恒真、什么都没断言）
  - **返工 1 次（R-A）—— 抓到 T4 里唯一一处「零覆盖且行为错误」的分支**：
    我按 T4 交付跑的 8 个变体中，**H7（把 `catch (DuplicateKeyException)` 里的 `basicAck` 改成 `basicNack`）
    与 H8（删掉该分支的 `markConfirmedSafely`）双双全绿** → 该分支零覆盖。进一步核实两个前提：
    ① `persist` 的 `selectCount` 用的正是 `(user_id,batch_id)`，与 `uk_user_batch` 同列；
    ② `persistWithLock` 已按 `userId` 上 Redisson 锁把**同一用户**串行化。
    ⇒ 该分支注释假定的「撞 `uk_user_batch`」在消费者内**不可达**；唯一可达触发是**主键撞键**
    （`reservationId` 相同、(user,batch) 不同），而此处 `basicAck` 会让这条**未落库的消息静默消失、
    DLQ 无任何痕迹**。按 §3.3「代价不对称 → 偏保守」的既定原则改为 `log.error` + `basicNack`
    （**不写 `fail_reason`** —— 该 `requestId` 的账本行可能属于别人，写进去是污染；DLQ 报文与拒绝原因
    由 T6 的 DLQ 台账记录）
  - **判别力实验（Claude 亲跑 12 个变体，全部有判别力；失败用例名改为直接解析 surefire XML，
    从此不再依赖任何文本格式假设）**：
    ① T4 交付版 8 个 —— H1 去掉已补偿前置检查 → I10 用例红；H2 已补偿改 NACK → 红；H3 落库后不写
    `CONFIRMED` → 2 红；H4 期望集合收窄为 `CONFIRMED` → 2 红；H5 QUOTA_CONFLICT 不写原因 → 红；
    H6 账本缺失改 NACK → 4 红；**H7/H8 全绿 ⇒ 命中上述零覆盖分支**（探针达到了探针的目的）
    ② T4-R-A 后 4 个 —— I1 撞键分支改回 `basicAck` → 新用例红；I2 撞键分支写 `CONFIRMED` → 红；
    I3 撞键分支写 `fail_reason` → 红；**I4 抽掉 `persist` 的 `@Transactional` → 只有新用例红**
    （证明「撞键时名额扣减确实随事务回滚」这条设计前提**现已被测试钉住**，不再是我基于 Spring 默认
    行为的推断）。四个变体**每次都只有新用例变红**，说明该测试精确打在这个分支上、未牵连其他用例
  - **全量回归 112 run / 0 failures / 0 errors / 0 skipped**（T3 后为 106；+5 用例，+1 为 R-A 新增）
  - 环境复原：`tb_reservation_request`=0、`tb_reservation`=0、`tb_reservation_batch`=0、
    `tb_laboratory`=4、`tb_equipment`=12、`tb_user`=1013；Redis `reservation:*`=0、`lock:*`=0；两队列均 0
  - 交付文件指纹（SHA-256 前 16 位）：`ReservationMessageConsumer.java`=`3de59bbf921a05ce`、
    `ReservationMqTest.java`=`8ffca089b239006b`、`ReservationServiceImpl.java`=`ae843e1ab599fb10`；
    实验期间被短暂改写以验证前提、已按字节复原的两个文件：
    `ReservationPersistenceServiceImpl.java`=`35524b8c082401f6`（**交付中零改动**）
  - **已知边界（继承 §3.6，仍未闭合）**：消费者读账本与落库之间的**毫秒级窗口** —— 若对账恰在
    这两步之间把该请求判为 `COMPENSATED`，消费者仍会落库。单消费者 `prefetch=1` 下需积压极深
    （>10 分钟）才可能发生，登记为已知限制而非缺陷
- [x] **T5 对账服务**（`@Scheduled` 薄壳 + CAS + 幂等补偿）—— 已完成并经判别力实验与 1 次返工
  - 交付：`SchedulingConfig`（`@EnableScheduling`）、`ReconcileSummaryDTO`（`scanned/confirmed/compensated/
    skipped/failed` 五项计数，不可变）、`IReservationReconcileService#reconcile(int)`、
    `ReservationReconcileServiceImpl`、`ReservationReconcileTest`；另改 `RedisConstants`（+`LOCK_RESERVATION_
    RECONCILE_KEY`）、`application.yaml`（`hmdp.reservation` 三项 = 300000 / 600000 / 200）
  - `reconcile(int)` **无 `@Transactional`、无锁**（D15）：扫描谓词完全复用 T3 的 `listStale(before, limit)`
    （非终态 且 `update_time < before`，按 `update_time` 升序，`LIMIT`）；**扫描异常向上抛**（`listStale`
    在 `try` 之外，不吞），**逐条 `try/catch` 隔离**（单条失败只 `failed++`，不中断本轮、不影响其他条）
  - 有行分支（`reservationMapper.selectById` 命中）：**只 CAS 到 `CONFIRMED`**（期望集合 = 非终态全集），
    **不做任何名额补偿**（D14）；CAS 失败记 `skipped`。`fail_reason` 按 D12 **刻意保留**最近一次失败原因
    （`casStatus` 只 set status、不碰 `fail_reason`），有专门断言钉住「`PUBLISH_CONFIRM_WRITE_FAILED` 仍在」
  - 无行分支：**先补偿、后推进终态**（D13）—— `rollbackExecutor.rollback(batchId, userId)` 成功后才
    `casStatusWithFailReason(COMPENSATED, "RECONCILE_NO_RESERVATION")`；补偿抛异常则只 `failed++` 且账本
    **保持非终态**，下一轮继续重试（幂等，I12 的镜像面）
  - 定时壳：`@Scheduled(fixedDelayString = "${hmdp.reservation.reconcile-interval-ms:300000}")`，Redisson
    **非阻塞** `tryLock(0, MILLISECONDS)`（不臆造固定租期，交给 watchdog 续期），仅当
    `lock.isHeldByCurrentThread()` 才 unlock；注释明确「锁只减少多实例重复工作，正确性由 CAS + 幂等补偿保证」
  - **返工 1 次（R-A）—— 2 个用例失败，且 `skipped` 两条断言实为「零覆盖」**：
    交付版里 `shouldCountCasConflictAsSkippedAndConvergeOnNextRun` 与
    `shouldNotReturnQuotaTwiceWhenCompensationCasIsSkipped` 的桩写作
    `casStatus(..., any(ReservationRequestStatus[].class))`。我复跑后两例皆红，失败点分别在 `assertSummary`
    的 confirmed / compensated（expected 0 but was 1）—— 即**真实 CAS 执行成功、桩从未命中**。
    根因用 `/tmp` 独立探针证实（非推断）：**`any(X[].class)` 放在 varargs 位置永远不匹配** —— Mockito 3.3.3
    把它按元素套用（每个元素都要 `isInstance(X[])`，而元素是枚举 → 失败）。探针实测（spy
    `boolean cas(long, Status, Status...)`，真实实现恒真、桩要求假）：`any(Status[].class)` → 真、
    三个 `any(Class)` → 真（**均未命中**）；**每个 vararg 元素一个 `any()`** → 假、**裸值逐元素** → 假、
    显式 `Mockito.<Status>any()` → 假（**均命中**）。同时反证不是 `@SpyBean` / 注入问题：同类里唯一非
    varargs 的桩（`doThrow` 打 `rollback(Long, Long)`）正常生效。
    R-A 改为**每个 vararg 元素一个 `any()`**（3 个，与 `nonTerminalStatuses()` 元素数一致），并加注释说明该
    耦合：元素数增减时桩会失效，由原有精确断言立刻报错，**不会静默通过**
  - **判别力实验（Claude 亲跑 7 个变体，全部有判别力、无预期外红）**：
    ① 针对 T5 交付版 4 个（只改生产代码）—— V1 删掉 `listStale` 的 `update_time < before` 谓词 → 只有
    `shouldIgnoreFreshNonTerminalRequest` 红；V2 无行分支改成先 CAS 后回滚 → 只有
    `shouldLeaveRequestNonTerminalWhenRollbackThrows` 红；V3 有行分支也执行补偿 → 只有
    `shouldConfirmPersistedReservationWithoutRollingBackQuota` 红；V4 `reconcile` 无视 limit → 只有
    `shouldRespectPerRunLimit` 红
    ② R-A 后 3 个 —— V5 把桩退回 `any(X[].class)`（改测试代码）→ **两个冲突用例同时红**（证明这两条测试
    确实依赖「桩生效」，不是恒真）；V6 有行分支把 CAS 失败记成 `confirmed` → 只有 CAS 冲突用例红；
    V7 无行分支把 CAS 失败记成 `compensated` → 只有补偿冲突用例红
  - **全量回归 119 run / 0 failures / 0 errors / 0 skipped**（T4 后为 112；+7 用例）
  - 环境复原：`tb_reservation_request`=0、`tb_reservation`=0、`tb_reservation_batch`=0、`tb_laboratory`=4、
    `tb_equipment`=12、`tb_user`=1013；Redis `reservation:*`=0、`lock:*`=0；两队列均 0
  - 交付文件指纹（SHA-256 前 16 位）：`ReservationReconcileServiceImpl.java`=`5803e4bb7e085e43`、
    `ReservationReconcileTest.java`=`0143e056688616c4`；复查 T3 的 `ReservationRequestServiceImpl.java`=
    `d88269958b6b9b89`（本次**零改动**）；实验中被短暂改写的两个文件已按字节复原（SHA 复核通过）
  - 派发插曲：T5-R-A 因我**手抄 thread id 打错一位**而无法 reply（报 `Process exited with code 1`），
    改为新起 thread 重派（新 thread id `01a10a39-3819-70b2-866f-8af15647cb8f`）；`codex-reply` 本身可用
  - 遗留 nit（**不返工**，下次派发 T6 时顺手处理）：R-A 新增的两处注释是英文，与该文件其余中文注释不一致；
    定时壳在 `InterruptedException` 时抛 `IllegalStateException`（仅关机期噪声，无正确性影响）
- [x] **T6 DLQ 台账 + 原始 `Message` 消费者** —— **已完成（T6-R 返工后验收通过：21 run / 0 failures / 0 errors / 0 skipped）**
      过程：首版交付红灯 5 条 → 我实测定位根因（含 Codex 未报告的"消费者永久失明"）→ 授权 T6-R 窄化返工
      → Codex 撞账户用量上限中断 → 2026-10-05 16:49 重派成功（thread `01a10b40-d458-7011-b42b-c5f21b7c2389`）
  - 交付：`entity/ReservationDlq`、`enums/ReservationDlqStatus`（`NEW/REPLAYED/DISCARDED`）、
    `mapper/ReservationDlqMapper`、`service/IReservationDlqService` + `impl/ReservationDlqServiceImpl`
    （仅 `extractReservationId(String)` 与 `recordDeadLetter(byte[], String)` 两法）、
    `mq/ReservationDlqConsumer`；DDL 追加进 `db/phase8_consistency.sql`（与设计 §4.2 **逐字符一致**，
    已应用本机 MySQL，3 个索引齐全）
  - 已核对**通过**的部分：四档 `failure_reason` 优先级实现正确（账本 `fail_reason` → `PAYLOAD_UNPARSEABLE`
    → `x-death:<reason>` → `null`）；**先解析完整原文、后截断到 60000 字节**（顺序正确且有注释说明"截断会切断 JSON"）；
    `insert != 1` 抛异常；`status`/`replay_count` 显式写 `NEW`/`0`；消费者为 `Message + Channel` 原始签名、
    两个 `basicNack` 第三参数均为 `false`、ACK 已移出落库 `try`（它自查发现并修正，符合"只有落库失败才 NACK"）；
    测试侧改造成立（按队列名查容器、4 处断言升级为台账断言、基线 id 清理、两个新用例）
  - **实测结果（Claude 亲自跑 `ReservationMqTest`，真实 MySQL/Redis/broker）：19 run / 5 failures**，
    按执行顺序：`#1 shouldRecordQuotaConflict…` ✅｜`#6 shouldDeadLetterInvalidJson…` ❌｜
    `#8 shouldDeadLetterQuotaConflictWithoutMakingMysqlQuotaNegative` ❌｜
    `#12 shouldParseReservationIdBeforeTruncatingOversizedPayload` ❌｜
    `#14 shouldDeadLetterPrimaryKeyConflictAndRollbackQuotaDeduction` ❌｜
    `#17 shouldNotLoseLedgerRowWhenPayloadIsEmpty` ❌
  - **根因（Codex 提出、我实测证实 → D18）**：Spring AMQP 2.2.18 的 `MessagingMessageListenerAdapter.onMessage`
    在调用监听方法**之前**无条件执行共享 `Jackson2JsonMessageConverter.fromMessage`，参数写成原始 `Message`
    **不能**绕过它。坏 JSON 在 DLQ 容器内二次转换失败 → `ConditionalRejectingErrorHandler` 的 x-death 分支抛
    `ImmediateAcknowledgeAmqpException: Fatal and x-death present` → **消息被静默丢弃、台账 0 行**。
    ⇒ 设计 §14.2 的 **D7「用原始 `Message` 接收」不充分**，这是**我设计文档的缺陷**，不是 Codex 的实现问题
  - **我实测发现的第二个、更严重的后果（Codex 未报告）：DLQ 容器在此之后彻底失明**。证据：
    ① `#8/#12/#14/#17` 的消息**都是合法 JSON**，本该正常落库，却一条都没落；
    ② 全日志中 `ntContainer#0`（DLQ 容器线程）只出现 10 次，全部集中在 `#1` 与 `#6` 两处；
    ③ 测试结束队列终态 `reservation.create.dlq` = 1 条 ready、0 unacked、0 消费者（未 ack 的投递被重回队列）
  - **机制（从字节码确认，非推测）**：`SimpleMessageListenerContainer.doReceiveAndExecute` 的
    `catch (ImmediateAcknowledgeAmqpException)` 分支只打一条 DEBUG 日志就 `goto 533` → `return`，
    **该路径中没有任何 `basicAck`**（正常路径是 `goto 527` 回到接收循环）。手工确认模式下这条投递永不结算，
    而 `prefetch=1` 使 broker 不再投递 → 消费者线程仍在空转但从此收不到消息。
    这也解释了为什么没有任何"容器重启"日志：**容器没崩，是悬挂**
  - **T6-R 实际交付范围（与我授权的一致，未越界）**：
    **完整任务包见 `docs/refactor/PHASE8-T6-R-PACKET.md`**（含禁止修改清单、验收标准、判别力实验设计、
    以及"Codex 沙箱跑不了集成测试、不得声称跑过"的验证边界）。要点：
    ① 新增 `config/ReservationDlqListenerConfig.java` —— `reservationDlqListenerContainerFactory` bean，
    复用 Boot 的 ack/prefetch/retry 配置但转换器换 `SimpleMessageConverter`；监听器用
    `containerFactory = ReservationDlqListenerConfig.DLQ_CONTAINER_FACTORY` 引用；**`RabbitMQConfig` 与拓扑零改动**
    （我特意选了"新文件"而不是改 `RabbitMQConfig`，把影响面压到最小）；
    ② 新增回归用例"**非法报文之后的合法死信仍必须落台账**"（把"永久失明"这一缺陷钉死，修复前必红）；
    ③ 新增用例"**台账落库失败时消费者必须存活**"（`@SpyBean` 让 `recordDeadLetter` 首次抛异常，
    断言：该条不留行、队列被消费空、**其后的死信仍能落台账**）—— 我把它加进来的理由正是本次踩到的
    "消费者失明"属于同一失效类别，只是发生在**我们自己的**代码路径上；
    ④ 顺带把 `extractReservationId` 解析失败时的 `log.warn(..., e)` 去掉堆栈（非法 JSON 是死信的**正常**形态）
  - 环境复原：`tb_reservation_dlq`=0、`tb_reservation_request`=0、`tb_reservation`=0、`tb_reservation_batch`=0；
    两个队列均已清零（含我实验遗留的那 1 条悬挂死信）；表结构保留（DDL 已应用，幂等）
  - **T6-R 复查与验收（Claude 侧独立执行，不采信 Codex 自述）**：
    - 范围核查：`find src -type f -mmin -40` 显示 Codex **只碰了允许的 4 个文件**
      （新增 `config/ReservationDlqListenerConfig.java`；改 `mq/ReservationDlqConsumer.java`、
      `service/impl/ReservationDlqServiceImpl.java`、`test/ReservationMqTest.java`）；
      `application.yaml`(11:52)、`RabbitMQConfig.java`(前一日 23:39)、`reservationRollback.lua`(10:46)、
      `ReservationMessageConsumer.java`(11:39) 的 mtime 均早于派发时刻 16:49 → **拓扑与默认工厂零改动**
    - 代码核查：`ReservationDlqListenerConfig` 的 **`configurer.configure(...)` 在 `setMessageConverter` 之前**
      （顺序承重，见下）；错误处理器与默认工厂一致；**无 `@Primary`**、未重定义默认 bean 名；
      消费者只多了 `containerFactory` 一个注解属性，`(Message, Channel)` 签名与 ACK/NACK 语义未动；
      生产代码 5 处 `basicNack` 第三参数**全部仍为 `false`**；
      原 5 条红灯用例的断言**逐字未动**（坏 JSON 8 条、名额冲突 9 条、超大报文与空体全部原样保留），
      新用例插在 `shouldDeadLetterInvalidJsonAndContinueConsuming` 之后，**无删改、无 `@Disabled`**
    - **实测（Claude 亲跑真实 `ReservationMqTest`，真实 MySQL/Redis/broker）：`Tests run: 21, Failures: 0,
      Errors: 0, Skipped: 0`，mvn 退出码 0**；surefire XML `tests="21" errors="0" skipped="0" failures="0"`。
      5 条原红灯全部转绿，原 14 条绿灯保持绿
    - **不是"假通过"的证据**：DLQ 容器线程 `ntContainer#0` 在日志中出现次数由修复前的 **10 次（仅前两个用例）
      升至 87 次**；坏 JSON 的短告警出现在**监听器线程**上，且**紧接着同线程就是 `INSERT INTO tb_reservation_dlq`**
      → 消息确实到达了监听方法，而非又被容器在方法前丢弃
    - **机制补充（字节码确认）**：Boot 2.3.12 的 `simpleRabbitListenerContainerFactoryConfigurer()` bean 方法
      **无条件**执行 `setMessageConverter(messageConverter.getIfUnique())`（该 bean 不随默认工厂名一起退让），
      故 `configure()` 会把容器里唯一的 Jackson 转换器装到**任何**工厂上 ——
      这正是"覆盖必须放在 `configure` 之后"的原因，也解释了主消费者为何一直是 Jackson
    - **判别力实验（两个变体，harness `/tmp/p8t6r-variants.py`）**：目标用例
      `shouldKeepRecordingDeadLettersAfterUnparseableOne`，对照用例取主链路的
      `shouldSubmitAndEventuallyPersistReturnedReservationId`。
      **V1** 去掉消费者的 `containerFactory` 绑定（退回默认 Jackson 工厂）→ 目标 **FAIL**、对照 PASS；
      **V2** 去掉 `setMessageConverter(new SimpleMessageConverter())` 覆盖 → 目标 **FAIL**、对照 PASS。
      ⇒ 修复的两半**都是承重的**（只建独立工厂不覆盖转换器、或只覆盖转换器但不绑定，均无效），且无副作用散红。
      实验后工作区经 SHA-256 逐字节复原：`ReservationDlqConsumer.java` = `1204e575b404ed34…`、
      `ReservationDlqListenerConfig.java` = `9b926c64272176c5…`（即全绿那一次的内容），
      `RabbitMQConfig.java` 仍为 `47c1ed02d3bcebd1…`（派发前基线）
    - 遗留（**不返工，已记入设计 §15 item 10/11**）：DLQ 路径若收到 `application/x-java-serialized-object`
      且反序列化失败，`SimpleMessageConverter` 仍会在监听方法前抛错，x-death 分支可再次造成悬挂；
      本项目自身产不出这种报文（我们的死信都是 JSON，已被端到端覆盖）。根治办法是自定义
      `FatalExceptionStrategy` 只抛 `AmqpRejectAndDontRequeueException`，**本 Phase 不实现**
  - 环境复原（测试跑完与判别力实验后各查一次）：`tb_reservation_dlq`=0、`tb_reservation_request`=0、
    `tb_reservation`=0、`tb_reservation_batch`=0；两个队列均 0 ready / 0 unacked；
    表结构保留（DDL 已应用，幂等）
  - **仍未提交**：T6 已完成但尚未 commit，等 Phase 8 全部 Task 与阶段六/七 Review 结束后再一并本地提交

- [x] **T7 DLQ 管理 API（查询/详情/重放 + 7 步幂等校验）** —— **已完成（首版即全绿，但我的复查发现 3 项 → 返工后验收通过：
      治理 9 run / 0 failures / 0 errors / 0 skipped；T6 回归 21 run / 0 / 0 / 0；mvn 退出码 0）**
  - 派发：MCP `codex`，`gpt-5.6-sol` / `effort=high` / `workspace-write`，thread `01a10b54-0f6a-7240-be30-8de0f238062e`；
    **完整任务包见 `docs/refactor/PHASE8-T7-PACKET.md`**（含 C1–C10 裁定、AC、零触碰清单）。
    返工包见 `docs/refactor/PHASE8-T7-R-PACKET.md`（R1/R2/R3 + 实测证据）
  - **交付物**：新增 `dto/ReservationDlqQueryDTO.java`、`vo/ReservationDlqVO.java`、`vo/ReservationDlqReplayVO.java`、
    `controller/ReservationDlqController.java`、`test/ReservationDlqGovernanceTest.java`；
    修改 `service/IReservationDlqService.java`（增量 3 法，T6 既有两法未动）、
    `service/impl/ReservationDlqServiceImpl.java`、`resources/application.yaml`（`dlq-max-replay: 3`）
  - **七步校验落地**（与设计 §6.2 逐条对应，步 1–5 全在投递之前）：步 1 `status==NEW`；步 2 `replay_count < 3`；
    步 3 payload 必须能解析出 `reservationId`+`userId`+`batchId` 三个整数（D19）；步 4 `tb_reservation` 已有该 id；
    步 5 账本 `COMPENSATED`；步 6 `convertAndSend` + confirm + Return 判定（D20）；步 7 乐观 CAS 置 `REPLAYED` 并 +1（D23、D30）。
    丢弃时把 `DISCARD:<code>` 追加进 `failure_reason`（D22、D31）
  - **我独立核对的关键事实（不采信 Codex 自述）**：
    - **步 4 真会命中**：`tb_reservation.id` 是 `IdType.INPUT`，消费者 `setId(messageDTO.getReservationId())`，
      故 `selectById(message.getReservationId())` 与事实表主键同源 —— 若落库用自增主键，步 4 会**永假**（形同虚设）。
      这是核对后才敢认定"幂等校验有效"的前提
    - `replay` 无 `@Transactional`（C7）；三端点均有 `@RequireRole(UserRole.LAB_ADMIN)`；
      `replay` 无 `config/WebExceptionAdvice.java` 改动；零触碰清单哈希与派发前逐项一致（`RabbitMQConfig`=`47c1ed02d3bcebd1…`）
    - 首版 `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0` + `ReservationMqTest` 21/0/0/0 全绿，**但我没有据此放行**
  - **复查发现的三项（首版全绿也照旧返工）**：
    - **R1｜测试清理泄漏（已实测复现）**：`ReservationDlqGovernanceTest.cleanUp` 只删 `dlqIds` 里显式追踪的行，
      而**系统自己造出来的死信行无人删**。证据：F7c 变体（删步 2 校验）下"已耗尽"的报文被真投递 → 主消费者
      `QUOTA_CONFLICT(requestId=8142355751)` → NACK 进死信 → DLQ 消费者插入新台账行 → 泄漏到下个用例 →
      `shouldPageByStatusNewestFirstWithoutPayload: expected: <2> but was: <3>`。
      交付版本恰好没有用例会触发它（唯一投递的用例投的是能成功落库的报文），所以今天全绿，
      但它使**我用来判定"测试有无判别力"的实验本身不可靠**。→ 改为与 `ReservationMqTest` 一致的**基线水位法**（`id > dlqBaselineId`）
    - **R2｜`appendDiscardReason` 截断顺序（真缺陷，但当前代码路径不可达）**：原实现"先拼接再整体截断到 512"，
      原值 500 → code 被截成 `PAYLOAD_NOT_REPL`；原值 512 → 标记整段消失。
      **可达性核查**：`tb_reservation_request.fail_reason` 是 `varchar(255)`，消费者只写
      `LOCK_TIMEOUT`/`QUOTA_CONFLICT`/`PERSIST_EXCEPTION:<类名>`，x-death reason 也是 AMQP 短串 →
      **当前任何路径都产不出 ≥480 字符的 `failure_reason`，故为潜伏缺陷**。
      这同时**是我任务包 C4 自身的规格矛盾**（原值达 512 时"总长 ≤512"与"原值逐字完整"不可兼得），
      裁定方向记为 **D31：截原值、保标记** → 改为"先算后缀长度、截原值、再拼接"
    - **R3｜`@RequireRole` 在详情/重放端点零测试覆盖**：原守卫测试只测 `GET /page` 的 401/200；
      删掉 `/{id}` 与 `/{id}/replay` 上的注解，全部测试仍全绿。而这是会返回死信完整 payload、
      并能触发真实重投的管理接口 → 扩为三端点 × {401, 200, 403}（403 用 `role=USER` 会话）
  - **返工实际交付范围（与我授权一致，未越界）**：只改 `service/impl/ReservationDlqServiceImpl.java`（仅 `appendDiscardReason`）
    与 `test/ReservationDlqGovernanceTest.java`；其余 6 个首版交付物与全部零触碰文件 SHA-256 **字节未变**
  - **判别力实验（四个变体，harness `/tmp/p8t7r-variants.py`，逐字节复原 `bd98434d97c0f7cf…`）**：
    | 变体 | 改写内容 | 变红用例 | 结果 |
    | --- | --- | --- | --- |
    | F7a | 删步 4 幂等校验 | `shouldDiscardAlreadyPersistedWithoutPublishing` | **仅目标红** |
    | F7b | 删步 5 已补偿校验 | `shouldDiscardCompensatedRequestWithoutPublishing` | **仅目标红** |
    | F7c | 删步 2 次数上限 | `shouldRejectMissingHandledAndReplayLimitRowsWithoutChangingThem` | **仅目标红**（返工前会连带污染分页用例 → R1 修复的直接验证） |
    | F7d | 把 `appendDiscardReason` 还原成旧的"先拼接后截断" | `shouldKeepCompleteDiscardCodeWhenOriginalReasonUsesFullColumnLength` | **仅目标红**（证明 R2 修复承重） |
    ⇒ 三处拒绝校验与 R2 的截断修复**全部承重**，且无副作用散红
  - **一次假警报（已查清，记下来免得下次误判）**：返工后首次验收跑出 `shouldPageByStatusNewestFirstWithoutPayload
    expected: <2> but was: <3>`，看着像返工没修好。查库后确认是**我自己实验造成的环境残留**：
    库里恰好 1 行 `id=55 / reservation_id=8142355751 / status=NEW / create_time=17:20:24`，
    与首轮 F7c 日志里的 `QUOTA_CONFLICT(requestId=8142355751)` 同 id 同时刻 ——
    即**返工前**的 F7c 在**旧清理逻辑**下泄漏的那一行。水位法**正确地**不删它（不属于本次测试），
    于是把绝对计数撑成 3。清掉后复跑即全绿。返工只改测试不改生产，故返工**之后**的 F7c 已能自清理
  - **已知残留（不返工，登记）**：分页用例断言的是**绝对**条数（`getTotal()==2`），前提是死信表在本轮开始前干净。
    R1 的水位法消除了**同轮内**的泄漏，但**历史残留行**（异常中断的旧运行、旧版本测试）仍会撑大计数。
    若要求免疫，需把该断言改成相对基线（`baselineNewCount + 2`）。**判定：不改** ——
    本 Phase 内两个会写死信表的测试类（`ReservationMqTest`、`ReservationDlqGovernanceTest`）现已都用水位法自清理，
    残留只可能来自异常中断，属"测试需要干净前置"这一常规假设；待用户否决可再返工
  - 环境复原（返工验收 + 判别力实验后各查一次）：`tb_reservation`=0、`tb_reservation_request`=0、
    `tb_reservation_dlq`=0、`tb_reservation_batch`=0；两个队列均 0 ready / 0 unacked
  - **更正我自己的记录**：我在首版验收中一度记下 `ReservationDlqServiceImpl.java` 哈希 `49adcf2a3a2fbebf…`，
    那是**判别力实验正在改写该文件时的采样**，非交付内容；首版真实哈希为 `ae16470f22045d07…`，返工后为 `bd98434d97c0f7cf…`
  - **仍未提交**：与 T6 同理，等 Phase 8 全部 Task 与阶段六/七 Review 结束后一并本地提交
- [x] **T8 测试（必测异常 + 不变式 I1–I12）** —— **已完成（补 G1/G2 两个缺口后验收通过；
      全量回归另暴露 T7 遗留的两个真实缺陷，返工修复后全量 134 run / 0 failures / 0 errors / 0 skipped，mvn 退出码 0）**
  - 派发：MCP `codex`，`gpt-5.6-sol` / `effort=high` / `workspace-write`，thread `01a10b6d-a98f-7670-8629-4a845081ccb3`；
    **完整任务包见 `docs/refactor/PHASE8-T8-PACKET.md`**（`93848bf5cbde7bb4…`）
  - **交付物（3 个测试文件）**：新增 `test/ReservationReconcileTest.java`（8 用例，`731407d7bf73092e…`）、
    `test/ReservationRequestStateTest.java`（7 用例，`6f1f0831816e979d…`）；
    修改 `test/ReservationPersistenceTest.java`（5 用例，`522228b6a2b18329…`）——三者哈希与 T8 交付时一致，未被返工触碰
  - **只补两个缺口，不重写既有用例**（源码分析阶段我实测确认的取值分布空洞）：
    - **G1｜非终态扫描的取值覆盖缺失**：`nonTerminalStatuses()` 返回 3 个值（`PRE_ACCEPTED`/`PUBLISHED`/`UNKNOWN`），
      但补之前**没有任何夹具处于 `UNKNOWN`**。漏扫一个状态 = 该状态请求永远无人对账、名额永久少一个。
      → 对账与 `listStale` 各补"三种非终态各一条夹具 + 终态负例"
    - **G2｜I5「`remaining_quota` 永不为负」只有单条串行路径**，测不出并发
      → 补真并发用例：恰好 1 条 `PERSISTED` + 该批次恰好 1 行 + `remainingQuota == 0` 且 `>= 0`
  - **更正我在任务包初稿里的错误结论（已回填进 `PHASE8-T8-PACKET.md` §G1 的「⚠️ 事后更正」）**：
    初稿写"把 `nonTerminalStatuses()` 收窄成只含 `PUBLISHED`，全量测试仍全绿"——**这是错的**，实测红 4 条。
    漏洞在于我只核对了**夹具取值分布**、没核对**桩的匹配方式**。实测分三种（F8/F8b/F8c）：
    只有"**丢掉 `UNKNOWN` 且元素数不变**"这一种是 G1 真正且唯一补上的缺口；
    另两种分别由"既有对账夹具全部是 `PUBLISHED`"与"Mockito 可变参数桩硬编码 3 个匹配器"承担 ——
    后者是**元数跳闸**，与丢掉哪个状态无关，**不构成语义覆盖**。结论：G1 价值成立，但理由须收窄。
  - **判别力实验（9 个变体，harness `/tmp/p8t8-variants.py`，逐字节复原并校验 SHA-256）**：
    | 变体 | 改写内容 | 期望变红 | 结果 |
    | --- | --- | --- | --- |
    | F1 | 补偿 Lua 改回只认 `EXISTS` 的旧写法 | I1/I3 | **红集完全一致** |
    | F2 | 把 `TimeoutException` 并入「确定未投递」回滚分支 | I6 | 首轮被守卫判"替换未生效"作废；重跑**完全一致** |
    | F3 | 删消费者落库前的 `COMPENSATED` 前置检查 | I10 | **红集完全一致** |
    | F4 | 对账阈值改 0（把排队中的消息当丢失） | 新鲜非终态误扫 | **红集完全一致** |
    | F5 | 去掉对账补偿分支的状态 CAS | I8 | **红集完全一致** |
    | F6 | `persist` 内联进调用方（自调用令 `@Transactional` 失效） | I4 | 首轮作废；重跑**完全一致** |
    | F8 | `nonTerminalStatuses()` → `[PUBLISHED]` | 新增 2 条 | 有判别力，另 2 条多余红 |
    | F8b | → `[PRE_ACCEPTED, UNKNOWN, UNKNOWN]`（元素数仍 3，丢掉 `PUBLISHED`） | 新增 2 条 | 有判别力，另 6 条多余红（既有夹具全是 `PUBLISHED`） |
    | F8c | → `[PRE_ACCEPTED, PUBLISHED]`（元素数变 2，丢掉 `UNKNOWN`） | 新增 2 条 | 有判别力，另 2 条多余红（元数跳闸） |
    | F9 | 删条件扣减的 `gt(remaining_quota, 0)` 守卫 | 并发 I5 | 有判别力，另 1 条多余红 |
    ⇒ 7 个变体红集与期望**完全一致**；F8 族与 F9 的"多余红"是同一宽改动/同一约束的**连带**（见上），
    不是新增用例误伤 —— 每个变体都**至少**打红了它该打红的用例。
    F2/F6 首轮被 harness 自身守卫判为"替换未生效"而作废（未采信为结论），这正是守卫的意义。
  - **已由变体实验证明「承重」的不变式：I1、I3、I4、I5、I6、I8、I10**（F1/F3/F6/F9/F2/F5）。
    **边界声明**：其余不变式（I2/I7/I9/I11/I12）与必测异常清单的覆盖由任务包矩阵列出，
    **未逐一做变体实验**，属"有覆盖、未验证判别力"，且 F8 族的"多余红"已说明元数匹配类桩不可作为语义覆盖的证据。
  - **全量回归暴露的两个真实缺陷（均为 T7 遗留，不是 T8 引入）**：
    - **缺陷 A｜`ReservationSubmitTest` 的监听容器选择在 T7 之后失效**：
      HEAD 上全应用只有**一个** `@RabbitListener`，故 `getListenerContainers().iterator().next()` 当时是对的；
      T7 新增 `ReservationDlqConsumer` 后变成两个，迭代顺序不再保证 → `stopListenerAndPurgeQueues()` 停错容器，
      create 消费者在整个类运行期间**保持活着**，把 MySQL `remaining_quota` 扣成 3 →
      `shouldFallBackToMysqlRemainingQuotaAfterClose` 与
      `shouldReadPageRemainingQuotaFromRedisAndFallBackForNonPublished` 两条断言 MySQL `== 4` 的用例失败。
      物理佐证：3 行"批次已删、用户不存在"的孤儿 `tb_reservation` 行，时间戳与我三次 Maven 运行逐一对上。
      → 改为**按队列名匹配**（`AbstractMessageListenerContainer#getQueueNames()`），与 `ReservationMqTest` 既有写法一致
    - **缺陷 B｜`ReservationMqTest` 的 `@SpyBean` 使缓存上下文持有幽灵消费者**（`:574`）：
      `@SpyBean` 经 `MockitoContextCustomizer` 计入上下文缓存键 → MqTest 拥有独立上下文，
      **而它之前所有 `@SpringBootTest` 类的上下文被缓存后继续存活**，各自持有 `@RabbitListener` 容器，在同一批队列上抢消息。
      测试直投 DLQ 的那条"必须失败"的消息被**没挂 spy 的上下文**消费 → 台账行照常写入 →
      `assertEquals(0, rowsAfterFailure)` 失败。
      **关键证据（实测，非推测）**：MqTest 单独跑 **21/0 成功 ×3**；与 `ReservationSubmitTest` 配对跑
      **稳定失败 2/2**；失败日志里 `死信台账落库失败` 计数 = **0**（说明 `doThrow` 桩一次都没触发）；
      配对日志有 **2 个 `:: Spring Boot ::` 启动横幅**而单独跑只有 1 个。
      → 新建 `src/test/resources/application.properties` 设 `spring.rabbitmq.listener.simple.auto-startup=false`
      （**只允许用 `.properties`**：测试类路径下同名 `application.yaml` 会**遮蔽主配置**，导致 DB/Redis/MQ 配置整体丢失；
      该键在主 `application.yaml` 中不存在，故无键冲突。此手段本仓库已有先例 —— T8 自己的
      `ReservationReconcileTest:62` 就是这么写的，只是当时只关住了它自己那一个上下文），
      并把容器生命周期完全交给测试类显式掌管：`ReservationMqTest` 显式启动 DLQ 容器、
      `dlqBaselineId` 改到"停容器 → 清队列 → **再读基线**"之后（原顺序把基线读在屏障之前）；
      `ReservationSubmitTest` / `ReservationDlqGovernanceTest` 改为**恢复进入前的运行状态**而非无条件 `start()`
    - **⚠️ 一个反直觉但重要的事实**：修复后配对日志里 Spring 上下文数量**仍是 2**。
      修复消除的不是"第二个上下文"，而是"缓存上下文里的活消费者"。**只看上下文个数会被误导**；
      正确的观测量是 `死信台账落库失败` 计数：**0 → 1**（三份日志各 1 次），证明失败桩这次真的被触发
    - **更正我自己的一个错误推断**：我一度认定是 `ReservationSubmitTest` 污染了 `ReservationMqTest`。
      SubmitTest 修好且 17/0 之后 MqTest **仍然失败** → 该推断**被证伪**，A 与 B 是两个独立缺陷，同属 T7 遗留
  - **返工实际交付范围（与授权一致，未越界）**：只改 4 个文件 —— 新增 `src/test/resources/application.properties`
    （`fe8d85c07975320f…`，内容仅 1 个键 + 4 行说明注释）、
    `ReservationMqTest`（`de60c3c66b15eb66…`）、`ReservationSubmitTest`（`fa6b3b9aff12a2e6…`）、
    `ReservationDlqGovernanceTest`（`7e474ee6662f695e…`）；`ReservationReconcileTest` 经分析**无需改动**。
    **`src/main/**` 全部 8 个基线文件 SHA-256 逐字节未变**：`ReservationRequestStatus`=`2382c962227ca809…`、
    `ReservationRequestServiceImpl`=`d88269958b6b9b89…`、`ReservationPersistenceServiceImpl`=`35524b8c082401f6…`、
    `ReservationReconcileServiceImpl`=`5803e4bb7e085e43…`、`ReservationServiceImpl`=`ae843e1ab599fb10…`、
    `ReservationMessageConsumer`=`3de59bbf921a05ce…`、`application.yaml`=`93cd1f0586067abb…`、
    `reservationRollback.lua`=`7bc238f05523282d…`
  - **验收（三条口径全部由我在本机实跑；Codex 沙箱无 localhost 网络、跑不了任何集成测试，其汇报中已如实声明）**：
    | 口径 | 修复前 | 修复后 |
    | --- | --- | --- |
    | `mvn -B test` 全量 | 134 run / **1 failure** / exit 1 | **134 / 0 / 0 / 0，BUILD SUCCESS** |
    | `-Dtest=ReservationSubmitTest,ReservationMqTest` 配对 | **稳定失败 2/2** | **38 / 0 / 0 / 0，BUILD SUCCESS** |
    | `-Dtest=ReservationMqTest` 单独 | 21 / 0（绿） | **21 / 0 / 0 / 0，BUILD SUCCESS** |
    附带物理痕迹：`ReservationSubmitTest` 17.2s → **1.05s**、`ReservationMqTest` 56.9s → **43.6s**（不再有竞争消费者空转）
  - **用例与断言未被削减**：`ReservationMqTest` 21 条、`ReservationSubmitTest` 17 条、`ReservationDlqGovernanceTest` 9 条不变；
    `assertEquals(0, rowsAfterFailure)` 这类"全局不应多出台账行"的强断言**原样保留**；全仓库已无 `iterator().next()` 选择容器
  - **环境复原（用户批准后执行；删除前逐行确认均为孤儿，再按精确 id 删）**：
    缺陷 A 留下 3 行 `tb_reservation`（批次已删、用户不存在）+ 1 行 DLQ 台账（`id=204`，`reservation_exists=0`）
    → 删除后四表复归 0。**最终状态复核**：`tb_reservation`=0、`tb_reservation_request`=0、`tb_reservation_dlq`=0、
    `tb_reservation_batch`=0；`reservation.create.queue` / `reservation.create.dlq` 均 **0 msg / 0 consumer**
  - **§9.3 环境审计（D36）**：在静默状态下完成并通过。Redis 现存键经逐个溯源确认**都不是本次残留**：
    `icr:reservation:2026:10:04` / `:10:05` 是 `RedisIdWorker` 按设计的每日单调 ID 计数器（D36 边界 7，**不可删** —— 删了会重发 ID）；
    `icr:order:2026:10:*` 是重构前遗留的旧订单计数器（仓库已无任何引用，`TTL=-1`）；
    `hello,queue1/queue2`（各 1 msg）是原始教程队列 —— 三者均与本次工作无关
  - **已知残留（不返工，登记）**：`ReservationDlqGovernanceTest#shouldPageByStatusNewestFirstWithoutPayload` 断言**绝对**条数
    （`getTotal()==2`），前提是死信表在本轮开始前干净。会写死信表的两个测试类现均用水位法自清理，
    残留只可能来自异常中断 —— 与 T7 登记的同类问题同源，判定不变
  - **仍未提交**：与 T6/T7 同理，等 Phase 8 全部 Task 与阶段六/七 Review 结束后一并本地提交（**不 push**）

### Phase 8 阶段六/七 跨 Task Review（PASS）— 详见 `docs/refactor/PHASE8-REVIEW.md`

  - **范围**：不采信 Codex 自述，由我直读 T1–T8 全部交付代码 + 实跑复核，产出独立 Review 文档（307 行）
  - **变更面（相对 T8 结束时）恰好 2 个生产文件**，均为下述 F-A 修复：
    `service/impl/ReservationDlqServiceImpl`（`queryPage` 排序）、
    `service/impl/ReservationRequestServiceImpl`（`listStale` 排序）。
    **其余 6 个生产基线 SHA-256 逐字节一致**（`ReservationRequestStatus`/`ReservationPersistenceServiceImpl`/
    `ReservationReconcileServiceImpl`/`ReservationServiceImpl`/`ReservationMessageConsumer`/`application.yaml`/
    `reservationRollback.lua`），禁改清单 8 个文件 `git status --porcelain` **全空**，无残留 `iterator().next()`
  - **缺陷 F-A（真实缺陷，已返工关闭）**：`queryPage` / `listStale` 原先只用**秒级** `timestamp` 列单独排序，
    同秒并列行之间无确定全序 → `LIMIT/OFFSET` 分页可能**重复或漏行**（`listStale` 每轮 `LIMIT 200` 的选取也不可复现）。
    既有测试**结构上不可能发现**：分页用例刻意用 `minusMinutes(2)/minusMinutes(1)/now()` 造行，永远不并列。
    → 修复为追加唯一打破者：`ORDER BY create_time DESC, id DESC` / `ORDER BY update_time ASC, request_id ASC`
    （实测 SQL 已确认）。**不动 DDL 加小数秒**：那是结构性变更且只能"减少"而非"消除"并列
  - **F-A 判别力实验（新增，补做 Codex 未能做的一步）**：harness `/tmp/p8fa-variants.py`，
    **每个变体重复 3 次**——V1 删 `orderByDesc(getId)`、V2 删 `orderByAsc(getRequestId)`：
    **各自 3/3 变红，且红集只有目标那一条**（无连带红 ⇒ 断言定向；三次一致 ⇒ 确定性失败而非偶然）。
    实验后两文件 SHA-256 逐字节复原（`506191c6dae56ce3` / `2dd51917489cf94c`）。
    Codex 自述的担忧（"`idx_status_create_time` 隐含主键，删掉显式 `id DESC` 后优化器可能仍碰巧按 `id DESC` 返回，
    削弱判别力"）经实测**排除**——它写完代码、未及观察到 RED 就撞上用量上限（本轮第三次），并**如实声明**了这一点
  - **R1–R11 逐条裁定**（证据强度分档，不混同）：**变体强度 6 条** ——
    R1(F4)、R2(F1)、R3(F2)、R6(F6)、R7(F3)、R8(F5)；**读码/grep 级 4 条** ——
    R4（全仓 `@Transactional` 仅 `ReservationPersistenceServiceImpl:26`，账本 CAS 与之分属不同事务）、
    R5（`submit` 调用链无 `@Transactional`）、R9（`onDeadLetter(Message, Channel)` 全程不过 Jackson）、
    R11（两处 `basicNack(dtag,false,false)`，第三参数为 false）；
    **R6 另有读码佐证**（跨 bean 经 `IReservationPersistenceService` 接口调用，无自调用）；
    **R10 无任何强度证据**（见下）。6 + 4 + 1 = 11 ✓
  - **必须点名的强度缺口**：**R10 指定的判别实验 F7 从未执行** —— 台账变体表列了 F1–F6、F8/F8b/F8c、F9，独缺 F7。
    故 I11（DLQ 重放不产生重复预约）目前**只有读码 + 用例覆盖**，属"有覆盖、未验证判别力"，
    已在 `PHASE8-DESIGN.md` §8 附勘误，不得当作已由变体证明。同理 R1 的设计预期用例名（误写为 I8）也已勘误
  - **登记项（不阻塞，指派后续 Phase）**：
    - **F-B**｜`SchedulingConfig` 的 `@EnableScheduling` 无 profile 守卫，且 `@Scheduled(fixedDelayString=...)`
      **无 `initialDelay`** → 每个 `@SpringBootTest` 上下文启动即跑一次对账。今日无害（夹具要么新鲜、要么已终态），
      但一旦出现"陈旧非终态"夹具就会与用例并行 → **Phase 9**
    - **F-C**｜`listStale` 的 `LIMIT` 无游标推进：长期失败的行 `update_time` 恒最旧，会**每轮独占 200 个名额**导致
      其后陈旧行饥饿。`request_id` 次键只让选取**可复现**，**不解决饥饿** → **Phase 9/10**
    - **F-D**｜消费者把 `DUPLICATE` 与 `PERSISTED` 同样走 `markConfirmedSafely`，而 `DUPLICATE` 的判定条件是
      **(userId,batchId) 已有行**、并非 `reservationId` 已有行 → 该 `requestId` 被置为终态 CONFIRMED 却没有
      `id = reservationId` 的行。正常提交路径被 Redis `SADD` 挡住而不可达，但 **DLQ 重放路径可达**
      （`replay` 第 4 步只按 `reservationId` 查是否已落库）→ **Phase 9**（取消/履约要按 requestId 找预约行，会放大）
    - **F-E**｜设计 §8 对变体 F4 的预期命名有误（写 I8，实为"新鲜非终态被误扫"）→ 已勘误，属文档问题
  - **验收（返工后全部由我在本机实跑）**：
    | 口径 | 数字 |
    | --- | --- |
    | `mvn -B test` 全量 | **136 run / 0 failures / 0 errors / 0 skipped**，BUILD SUCCESS，`Total time: 01:10 min` |
    | 受影响两类 | `ReservationDlqGovernanceTest` 10 + `ReservationRequestStateTest` 8 → **18/0/0/0** |
    | MySQL 四表残留 | `tb_reservation` / `tb_reservation_request` / `tb_reservation_dlq` / `tb_reservation_batch` **各 0 行** |
    | RabbitMQ 队列 | `reservation.create.queue` 与 `reservation.create.dlq` **均 `messages=0`、`consumers=0`** |
    | Redis | 无 `lock:reservation*`；`icr:reservation:2026:10:04/:10:05` **仍在**（设计内每日计数器 D36，不得删除） |
  - **测试耗时评估（用户指定项，只评估不改代码）**：`Total time` 口径抖动大于增量（MqTest 单类实测
    43.575/44.204/44.262/46.411s），逐轮比较无意义；**单项大头是 `ReservationMqTest` 约 44s，占全量 wall-clock 六成以上**。
    主要开销推断为 **RabbitMQ 管理端统计约 5s 的刷新粒度**（"等队列排空"的轮询必须等到统计刷新）——
    故提速的正解是**改判据**（改查台账行或 `rabbitTemplate.receive()`），而非缩短轮询间隔。
    已登记为 Phase 10 可选优化（会动到刚验收的 `ReservationMqTest`，本 Phase 不动）

### Phase 9（已完成）— 本地提交 `b917535`「feat: add reservation cancellation and fulfillment lifecycle」

- [x] 预约取消（`cancel`）与履约核销（`verify`）生命周期；引入 `ReservationStatus`（RESERVED / CANCELLED / COMPLETED / NO_SHOW）与 `active_flag` 条件更新
- [x] 关闭 Phase 8 Review 登记的 **F-D**（`DUPLICATE` 被误置终态致账实不符）与 **F-B**（`@EnableScheduling` 无 `initialDelay`，测试上下文启动即跑对账）
- [x] Review **PASS**（首轮 REWORK REQUIRED，返工后通过）：全量 **154 run / 0 failures / 0 errors**；V1–V5 判别力实验全部 PASS、5 次复原 **byte-exact**；禁改清单 13/13 未改
- 详见 `docs/refactor/PHASE9-DESIGN.md` / `PHASE9-REVIEW.md`

### Phase 10（已完成，T1–T6）

| Task | 内容 | 本地提交 | 结果 |
|---|---|---|---|
| T1 | 残留业务与死代码清除：`UploadController`、`UserInfo` 四件套、`GET /user/info/{id}` 与 `GET /user/{id}`、`SystemConstants` 两个死常量、`LoginFormDTO.password`、`SimpleRedisLock`/`ILock`/`PasswordEncoder`、11 处 `@author 虎哥`、2 个无断言测试 | `7743d47` | ✅ 符号 grep 全 0 |
| T2 | 命名迁移：124 个 Java 文件的包名与目录、主类、pom 坐标、`application.yaml`、3 个 mapper XML namespace、2 处字符串形式包名 → `com.yanyuetong` | `2e94843` | ✅ `com.hmdp` / `hm-dianping` / `HmDianPing` grep 全 0 |
| T3 | 迁移链重建为可安装的编号脚本；新增死表 DROP 脚本；`phase2` 种子 INSERT 幂等化 | `5341193` | ✅（DROP **未执行**，见「Pending」） |
| T4 | P1/P2 行为修复：F4 实验室依赖检查、F3 `admin_user_id` 不外泄、F-C 对账饥饿（两处**显式**写 `update_time`）、P2-25 400/500 状态码、P2-22 发布失败文案拆分、`LOGIN_USER_TTL` → 30 分钟 | `7f3081f` | ✅ 54 例 0 失败；**V10–V12** 证明返工补齐了 V4/V5 的覆盖缺口 |
| T5 | 测试卫生（原计划 #26 分页依赖、#27 手机号碰撞） | `207ed41`（仅文档） | ⚠️ **两项前提均被实测证伪，零代码改动**（W1/W2/W3 阴性对照 + 独立机制演示程序） |
| T6 | 4 份根目录文档归置入 `docs/`、全仓凭据脱敏、README 15 节、本台账收口 | `2855310` | ✅ 凭据两条探测器命中 0 |

**T5 的结论必须按实测理解，不要按初稿读**：`#26`（分页用例隐含依赖"夹具落在第 1 页"）经 W3 阴性对照证明**本就确定**
（`ORDER BY b.id DESC` + 被断言的两行恰好 id 最大 + `assertFalse` 由 WHERE 兜住）；`#27`（手机号夹具 10 ms 周期导致跨类撞号）
经 W1 决定性反证（把两类后缀钉成同一常数后仍**全绿**）证明**当前不可达**。10 ms 周期这个数字本身为真，
但"它能走到失败路径"为假——正确表述是**潜在地雷**：一旦引入 JUnit 并行执行或并发多进程共库跑测，它才会变成响亮失败。

**Phase 10 的三处自我订正（如实登记，详见 `PHASE10-DESIGN.md`）**：

1. T4 派发的任务包里我把 `Result` 的字段名写成 `code`/`msg`，Codex 据 `Result.java` 反驳——**它是对的**（实为 `success` / `errorMsg` / `data` / `total`）。同类"凭记忆写名字"的错误本 Phase 共出现三次（另两次：T5 的假方法名、F4 的列清单）。
2. T5 的两条隐患前提被**我自己的实验**双双证伪（见上），文档已按实测改写，未留下"已加固"的虚假记录。
3. T5 撤销 Codex 改动时我用了 `git checkout --`，**连带丢弃了同文件里未暂存的 #26 加固**；因两项结论一致（均不需改动），未重新引入。教训已记入：复原变体改动必须用实验前的 `cp` 备份做 byte-exact 还原。

**T6 收口审计发现并补上的台账缺口**（§3.13 第 10 条要求"三档逐条给结论与证据"，首轮 T6 提交后逐行核对才发现）：

- P2 清单里有 **6 行只有过程记录、没有结论**：`15`（测试夹具复杂度）、`16`（无调用方的缓存方法）、
  `18`（缓存删除无补偿）停在"→ Phase 10 评估"，`20`（F3）、`21`（F4）虽已由 T4 修掉却**没有回填关闭标记**，
  `29`（F-B）已由 Phase 9 修掉也未回填。**教训**：把任务写进 Phase 10 不等于 Phase 10 会回头改那一行——
  逐行核对是唯一可靠的办法，靠"我记得做过"会漏。
- 核对中**新发现一处未登记残留（第 30 条）**：本机 RabbitMQ 上的 `hello,queue1` / `hello,queue2` 课程死队列。
  这不是仓库里的东西，是 broker 侧状态，只有实际去 `rabbitmqctl list_queues` 看才会发现。
- 三项新结论已同步写入 `README.md` §15 已知边界（保留的无调用方方法、缓存删除不补偿、死队列残留）。

## Pending

**代码与文档层面：无** —— Phase 10 T1–T6 已全部完成，P2 清单 30 行逐行有结论。

两项未闭环（都在仓库之外，均需用户决定）：

- **10 张历史课程死表的 `DROP` 尚未执行**。脚本 `src/main/resources/db/phase10_drop_legacy_tables.sql` 已就绪（`DROP TABLE IF EXISTS`，幂等可重复执行），
  但它删除的是**本机 `hmdp` 库里的既有课程数据**（实测：`tb_shop` 14 行 / `tb_blog` 4 行 / `tb_voucher` 1 行，其余 7 张 0 行），**不可逆**。
  需用户在实时对话中**逐表确认**后由 Claude 执行；执行前建议先备份。新装库不需要该脚本。
- **本机 RabbitMQ 上的 2 个课程死队列** `hello,queue1` / `hello,queue2`（各积压 1 条消息）**未删除**。
  仓库里已无引用，删不删都不影响本项目；删除属对 broker 的破坏性操作，等用户决定。
- **git 历史中的明文口令未改写**（用户裁决：只清工作区、不动历史）。当前工作区已全部脱敏，
  但**本仓库在公开前必须先改写历史或轮换口令**。仓库未推送到任何公开远端。
- **首次 push 未做**（用户裁决：只本地提交）。注意 `origin` 指向他人课程仓库，`github` 指向用户自有仓库，**不要执行裸 `git push`**。

## 文档路径迁移（Phase 10 T6）

Phase 0–9 的 Review / 设计文档中引用的旧路径按此表对应到新位置。**历史文档本身未改写**——它们记录的是当时的状态，
改写会让台账失去可追溯性；本表即为其索引。

| 旧路径（根目录） | 新路径 |
|---|---|
| `实验资源预约平台-分阶段重构Phase计划.md` | `docs/plan/实验资源预约平台-分阶段重构Phase计划.md` |
| `黑马点评 → 实验资源预约平台重构.md` | `docs/plan/原始需求-实验资源预约平台重构.md`（**已改名**，文件头注明原名） |
| `秒杀消息队列迁移RabbitMQ-交付报告.md` | `docs/history/RabbitMQ-异步下单迁移交付报告.md`（**已改名** + 口令脱敏） |
| `MIGRATION_STATUS.md` | `docs/MIGRATION_STATUS.md`（即本文件） |

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
- [x] **新增** 两个服务层测试覆盖空洞 —— Phase 6 变体实验发现并关闭
      （① 服务层从未验证 Lua `EXISTS` 是撤销方向的权威；② 分页路径的读覆盖零覆盖）
      变体证据：删掉 Lua 的 `EXISTS` 后 15 个服务层用例**全绿**；读覆盖改空实现后只有详情路径变红
      → 详见 `docs/refactor/PHASE6-REVIEW.md` §6
- [x] **新增** 一处**零判别力**覆盖（Phase 7 变体实验 F3 发现）：`ReservationMqTest` 的幂等用例复用**同一个
      `reservationId`**，把幂等键从 `(userId,batchId)` 改成 `id` 后 **8/8 全绿** —— 名为"重复消息"，
      实为"按 id 幂等"。返工补「同 user 同 batch、**不同 reservationId**」用例（判别点是 **DLQ 必须为空**：
      按 id 的实现会撞 `uk_user_batch` → 抛异常 → nack → 进 DLQ），F6 复测仅该用例变红
      → 详见 `docs/refactor/PHASE7-REVIEW.md` §5 / §7
- [x] **新增** Phase 7 异步落库引发的**既有测试回归**：`ReservationSubmitTest` 四处断言 MySQL 未被扣减，
      而异步消费者会扣减 → 断言依赖消费者时序（全量 88 run 挂 1 个，单类跑可能全绿，天然 flaky）。
      已修（夹具隔离，断言本身未动），并清除实测的 **8 行** `tb_reservation` 孤儿
      → 详见 `docs/refactor/PHASE7-REVIEW.md` §6
- [x] **新增** 一处「**零覆盖且行为错误**」的分支（Phase 8 T4 变体实验 H7/H8 发现）：
      `ReservationMessageConsumer` 的 `catch (DuplicateKeyException)` 分支 —— 两个变体全绿证明零覆盖；
      进一步核实该分支假定的「撞 `uk_user_batch`」在消费者内**不可达**（`selectCount` 与唯一键同列，
      且同用户已被 `persistWithLock` 的 Redisson 锁串行化），唯一可达触发是**主键撞键**，而原实现的
      `basicAck` 会让未落库的消息**静默消失且 DLQ 无痕迹**。已改为 `log.error` + `basicNack`（不写
      `fail_reason`，避免污染他人账本行），并补「同 ID 不同用户」的确定性用例；I1–I4 四个变体复验，
      其中 I4（抽掉 `persist` 的 `@Transactional`）把「撞键时扣减随事务回滚」这条设计前提**从推断变为被测试钉住**
      → 详见下方 T4 条目

### P1（业务/一致性缺陷，后续 Phase 处理）

1. ~~订单表无任何唯一索引~~ → **已于 Phase 8 复核关闭**：`tb_reservation` 实有
   `UNIQUE KEY uk_user_batch(user_id,batch_id)` 与主键 `id`（D3 把 requestId 与 reservationId 合一，
   故主键**就是** requestId 唯一索引）。Phase 8 **有意保留** `uk_user_batch` 不动（D9）；
   其"取消后重约"所需的索引形态迁移留给 Phase 9（§6.1）
2. ~~消费端扣库存 + INSERT 无事务~~ → **已于 Phase 8 关闭**：`ReservationPersistenceServiceImpl.persist`
   带 `@Transactional`（**全仓唯一一处**），条件扣减 UPDATE 与 INSERT 同事务；
   扣减影响 0 行时直接返回，库里不会留下"未扣名额的预约行"（I4）
3. ~~Confirm Timeout 立即回滚 Redis~~ → **已于 Phase 8 关闭**：`PublishOutcome` 三态化，
   `TimeoutException` 归 `UNKNOWN` 且**不归还名额**；该场景的收敛交给对账，
   以 `tb_reservation` 中该 id 是否存在为唯一事实来源（D5 / R3 / I6）
4. ~~补偿 Lua 不幂等~~ → **已于 Phase 8 关闭**：`reservationRollback.lua` 改为 **SREM 先行**，
   连续调用只归还一次（变体 F1 实测：改回"只认 EXISTS"的旧写法则 I1/I3 变红）
5. ~~Lua 不校验预约时间窗口~~ → **已于 Phase 6 关闭**（`reservation.lua` 已实现窗口校验，返回码 4/5）。
   注意其**可达性**见 `PHASE6-REVIEW.md` §4.3：Java 用同一个 `now` 先判，故当前 Lua 的窗口守卫是被遮蔽的冗余（保留）
6. ~~DLQ 无治理入口~~ → **已于 Phase 8 关闭**：死信台账表 + 分页/详情/重放三个端点
   （全部 `@RequireRole(LAB_ADMIN)`）+ 重放 7 步幂等校验 + `dlq-max-replay` 上限
7. ~~MQ 直接发送数据库 Entity~~ → **已于 Phase 7 关闭**：改为发送 `ReservationMessageDTO`（`Serializable`），
   不发送实体，避免把表结构耦合进消息契约
8. ~~`CacheClient.queryWithMutex` 互斥失效~~ → **已于 Phase 4 修复**（见「已解决」）
9. ~~**LAB_ADMIN 无实验室归属约束**：任何 LAB_ADMIN 可管理任意实验室与设备（计划 §6 称"管理自己负责的实验室"）~~
   → **已于 Phase 5.1 修复**（见「已解决」）
10. **Session TTL 过长**：`LOGIN_USER_TTL = 36000` 分钟 ≈ 25 天，且每次请求续期、无绝对上限。
    既有配置非本阶段引入，登记为待决项 → ✅ **T4 关闭**：`LOGIN_USER_TTL = 30L`（**单位是分钟**，故 30 = 30 分钟；不是 1800L）。活跃用户无感（每请求续期），token 泄漏后的可用窗口由 25 天压到 30 分钟。**诚实登记**：全仓测试无任何断言涉及登录 token TTL，本项**无变体强度证据**（V8 阴性对照证明常量无语义可测），只经"常量值 + 两处调用点单位"人工核对
11. **F-C（Phase 8 Review 发现）**：`listStale` 的 `LIMIT` **无游标推进** —— 长期失败的行 `update_time` 恒最旧，
    会**每轮独占 200 个名额**，使其后达到阈值的陈旧行饥饿。Phase 8 追加的 `request_id` 次键只让选取
    **可复现**，**不解决饥饿** → ✅ **T4 关闭（两处）**：① 对账 catch 里 `recordFailReason` **显式** `SET update_time = now()`，让失败行退出陈旧窗口；② 回滚补偿 catch 里显式 bump `update_time`（**不能写回原值**，否则 `ON UPDATE CURRENT_TIMESTAMP` 不生效、等价于没写）。V10 实测：去掉显式赋值后**恰好 1 例红**（`shouldAdvanceUpdateTimeWhenSameReconcileFailureRepeats`），证明判别力。详见 `PHASE8-REVIEW.md` §6 F-C
12. **F-D（Phase 8 Review 发现）**：`ReservationMessageConsumer` 把 `DUPLICATE` 与 `PERSISTED` 同样走
    `markConfirmedSafely`，而 `DUPLICATE` 的判定条件是 **(userId,batchId) 已有行**、**并非** `reservationId` 已有行
    → 该 `requestId` 被置为终态 `CONFIRMED`，库里却没有 `id = reservationId` 的行。正常提交路径被 Redis `SADD`
    挡住而不可达，**但 DLQ 重放路径可达**（`replay` 第 4 步只按 `reservationId` 查是否已落库）。
    Phase 9 的取消/履约要按 `requestId` 找预约行，会放大该不一致 → ✅ **Phase 9 关闭**（详见 `docs/refactor/PHASE9-REVIEW.md`；判别力证据 V3/V4/V5）。详见 `PHASE8-REVIEW.md` §6 F-D

### P2（清理项）

1. ~~Redis Key 重复定义（Java 常量 vs Lua 字面量）~~ → **已于 Phase 6 关闭**：`reservation.lua` 全部经 `KEYS[]` 寻址，无字面量 key
2. ~~`seckill:stock:` / `seckill:order:` 无 TTL~~ → **已随 Phase 5.0 删除而消失**（Phase 6 全树核查确认零残留）
3. ~~四套历史秒杀实现以注释形式残留~~ → **已随 Phase 5.0 删除而消失**（`src/` 树中 `秒杀`/`seckill`/`voucher` 仅剩保留的建库脚本 `db/hmdp.sql`）
4. ~~`SystemConstants.IMAGE_UPLOAD_DIR` 硬编码 Windows 路径~~ → ✅ **T1 关闭**（常量与全部引用已删）
5. ~~`SystemConstants.MAX_PAGE_SIZE` 已无引用（死常量）~~ → ✅ **T1 关闭**
6. ~~明文密码（`application.yaml` 已占位 `change_me`，真实凭据在 gitignored 的 `application-local.yaml`）~~ → ✅ **T6 关闭（工作区）**；git 历史未改写，见「Pending」
7. `秒杀消息队列迁移RabbitMQ-交付报告.md:12` 含明文本机密码（**历史遗留，已在提交 `433cdfb` 中**，改工作区无法消除）
   → ✅ **T6 关闭（工作区）**：该文件已改名并脱敏为 `docs/history/RabbitMQ-异步下单迁移交付报告.md`（`:12` 改为「见本地配置，不入库」）。**git 历史未改写**，push 前仍须改写历史或轮换凭据
8. ~~`UploadController` 的 `deleteBlogImg` / `/blog/delete` / `/blogs/` 语义残留~~ → ✅ **T1 关闭**（`UploadController` 整体删除，含 `MvcConfig` 白名单项）
9. **点评式用户残留**：`tb_user_info`（0 行）、`UserInfo` 四件套、`GET /user/info/{id}`、`GET /user/{id}`
   —— 经用户 2026-10-04 裁决**归 Phase 10** → ✅ **T1 关闭**（`UserInfo` 四件套与 `GET /user/info/{id}`、`GET /user/{id}` 均已删）
10. DB 三表 `tb_blog` / `tb_blog_comments` / `tb_follow` 未删（用户已确认"只删代码"）→ ✅ **T3 脚本就绪**（`phase10_drop_legacy_tables.sql` 覆盖 10 张死表，幂等性已实测）；**实际执行待用户逐表确认**，见「Pending」
11. `EquipmentMapper.xml` 名称搜索前导通配符无法走 `idx_name`（当前 12 行无影响）
12. `phase2_laboratory_equipment.sql` 种子数据 INSERT 不幂等（重复执行会主键冲突）；
    Phase 3 的脚本已改用幂等写法，Phase 2 的待回补 → ✅ **T3 关闭**（T6 在临时库实测：连跑两次无主键冲突、行数不变 4/6/12/2）
13. `tb_user.password` 从未被使用（密码登录未实现）；种子管理员 `13900000001` 无密码，只能验证码登录
14. `RoleInterceptor` 类级注解分支当前无使用者（按设计保留）；403 响应体为空系有意选择
15. `CacheClientMutexTest` 的 T1 夹具过度复杂（`spy` + 线程改名 + `doAnswer` 忙等 + 用 `"{}"` 规避 Hutool
    对顶层 JSON 字符串 `toBean(String.class)` 的限制）；断言有效但脆弱 → ⚠️ **T6 结论：评估后不改**。
    判据是"有没有判别力"而不是"优不优雅"：该用例的判别力已由 Phase 4 的隔离实验独立证明
    （旧实现 T1 红、再去掉归属校验 T1b 红）。简化属纯重构，不增加任何判别力，
    却要重写一个已被证明有效的探测器。登记为**有意保留的复杂度**，不是漏项
16. `CacheClient.queryWithPassThrough` / `queryWithLogicalExpire` 已无生产调用方（设备详情只用 `queryWithMutex`）
    → ✅ **T6 结论：保留**。T1 已明定"不动 `CacheClient`"；`CacheClient.java:26` 的类注释已写明
    `queryWithLogicalExpire` 是"未启用的备选实现"，读者不会被误导。**这是全仓唯一一处有意保留的无调用方生产代码**，
    在此显式登记，以免日后被当成疏漏重开
17. `EquipmentCacheTest` 通过 MyBatis `Interceptor` 匹配 `EquipmentMapper.selectById` 语句 id 计数；
    若回源语句变更会**静默失效**（测试仍通过但不再测任何东西）→ 维护性提醒
18. 缓存删除失败无补偿（靠 30min TTL 兜底）；重试耗尽的兜底直查不写缓存，极端竞争下可能惊群
    → **经用户 2026-10-05 裁定：不纳入 Phase 8，退回 Phase 10 评估**
    （属 Phase 4 缓存域、非计划 §11 条目；是"惊群风险"而非"数据不一致"）
    → ✅ **T6 结论：接受为已知边界，不引入补偿任务**。修法（删除失败重试 / 补偿队列）是为一个
    "30 分钟 TTL 自愈、且只影响下一次读"的窗口新增一条异步链路，与"不要过度设计"相冲突。
    已写入 `README.md` §15 已知边界，供使用者自行判断
19. 设计文档 §7 曾错称 T1「直击 D2」，已更正并补 T1b（自查发现，见 `PHASE4-REVIEW.md` §5）
20. **F3**：`GET /laboratory/list` 与 `GET /laboratory/{id}` 是公开读端点，直接序列化实体，会把 Phase 5.1
    新增的 `admin_user_id` 暴露给任何已登录用户。属信息暴露而非越权（暴露的是"谁管这个实验室"，不构成写权限）；
    修法（返回 VO 或 `@JsonIgnore`）会改动 Phase 2 的既有接口面 → Phase 10
    → ✅ **T4 关闭**：新增 `com.yanyuetong.vo.LaboratoryVO`（10 个非敏感字段，无 `adminUserId`），
    `GET /laboratory/list` 与 `GET /laboratory/{id}` 改返回 VO。R3 在原用例内补 typed getter 断言
    （`name`/`campus`/`status` 与库中实体一致、`createTime` 非空，详情与列表各一遍），
    补上"把 VO 所有字段都丢掉也照样通过"的零判别力缺口；同时保留"序列化**实体**确实含 `adminUserId`"
    这条判据自证。**诚实登记**：R3 属断言设计层面的补强，未做变体实验
21. **F4**：`DELETE /laboratory/{id}` **不检查是否存在依赖设备/批次**。这是 Phase 2 遗留的既有缺口，
    Phase 5 的批次表让后果更实（删实验室会留下孤儿批次）→ 待决（属 Phase 2 契约变更）
    → ✅ **T4 关闭**：`deleteLaboratory` 先 `assertManageable(id)` 校验归属，再 `selectCount` 设备依赖，
    有设备则抛 `BadRequestException`（实测顺序：归属 → 依赖 → `removeById`）。属**行为变更**，
    已写入 `README.md` §15。**诚实登记**：其上曾有的 `@Transactional` 已由 R4 收回
    （一致性读关不掉 TOCTOU 窗口，留着只是假象）
22. **可观测性**：`publish` 的 `SET NX` 拒绝分支与条件 UPDATE 的 CAS 拒绝分支**复用同一句报错文案**
    「批次状态已变化，发布失败」，排障时无法区分是哪一层拦下的（实测 7 个失败方全部报同一句）→ ✅ **T4 关闭**：拆成「发布占用标记已存在，疑似重复发布」与「批次状态已被并发修改，发布失败」两句（错误类型仍为 `BadRequestException` / 400）
23. 设计文档 §9 的 H 编号在 Phase 5 期间扩到 H12；`PHASE5-DESIGN.md` §10 T9 原写的判别实验
    （"去掉 `WHERE status='DRAFT'` → 必须失败"）**是错的**，已更正（串行路径根本走不到那条 UPDATE）→ 已修文档
24. `ReservationBatchLifecycleTest` 的并发用例依赖**时序**（错开释放扫描）；正确实现下恒绿，但判别力是概率性的
    （V6 实测 3/18 命中）。已登记为固有局限，见 `PHASE5-REVIEW.md` §11
25. **全局异常兜底的 HTTP 状态码**：`WebExceptionAdvice.handleRuntimeException` 未声明 `@ResponseStatus`，
    因此服务器故障返回 **HTTP 200** + `Result.fail("服务器异常")`；且 `HttpMessageNotReadableException`
    这类**客户端**错误也落到同一兜底。属跨切面契约（正确做法应区分 400/500），Phase 6 已更正设计文档措辞
    但未改代码 → ✅ **T4 关闭**：五处处理器齐备（400 / 403 / 503 / 500 / 400），并由**行为级** `MockMvc` 用例钉住（V11/V12 证明判别力）
26. `ReservationBatchLifecycleTest.shouldIncludePublishedAndClosedBatchesOnPublicPage` 隐含依赖
    「夹具落在第 1 页」（`DEFAULT_PAGE_SIZE = 5` + `ORDER BY b.id DESC`）；库里公开批次累积后会环境相关地变红。
    Phase 6 新增的分页用例已改用 `equipmentId` 收窄查询消除该依赖，Phase 5 的这条待同样加固 → ⚠️ **T5 结论：前提证伪，不改**（`ORDER BY b.id DESC` + 被断言的两行恰好 id 最大 + `assertFalse` 由 WHERE 兜住，该用例本就确定；W3 阴性对照去掉过滤后仍 21/21 全绿）。**另注**：本条此前记的方法名 `shouldIncludePublishedAndClosedBatchesOnPublicPage` 实测不存在，真实名是 `shouldOnlyReturnPublishedAndClosedBatchesOnPublicPage`
27. **测试夹具手机号碰撞概率**：夹具用 `% 10_000_000L` 生成后缀，对 `tb_user.phone` 的唯一索引
    （`uniqe_key_phone`）存在约 1e-5 量级的生日碰撞概率（Phase 5 用 `% 100_000_000L`，空间大 10 倍）。
    当前实测未命中，登记为已知概率性风险，不阻塞 → ⚠️ **T5 结论：机制证伪，撤销**。10 ms 周期这个数字本身为真（独立机制演示程序实测：旧表达式在 200 个"相差 10 ms 整数倍"的时刻 **200/200 撞号**、首个重复点 `10_000_000 ns`；毫秒版 **0/200**、重复点 `10_000_000 ms`），但**它在当前执行模型下不可达**：三个类都在 `@AfterEach` 删用户，Surefire 实测**单 fork 顺序执行**（四类 wall-clock 57.118 s ≈ 各类之和 55.6 s，无任何并行配置）；决定性反证 W1——把两个类的后缀**钉成同一常数**后仍**全绿**。正确表述是**潜在地雷**：一旦引入 JUnit 并行执行或并发多进程共库跑测才会变响亮失败
28. **中断 Spring 集成测试会留下夹具残留**：中途 kill 测试进程时正在执行的用例 `@AfterEach` 不跑，
    会留下实验室/设备/用户/批次各一行（Phase 6 实测残留一组，已清理）。
    收尾动作应包含一次 DB 残留检查，而不是只看 `mvn` 退出码 → ✅ 已纳入 Phase 10 终验清单（§3.13 第 7/8 条）
29. **F-B（Phase 8 Review 发现）**：`SchedulingConfig` 的 `@EnableScheduling` **无 profile 守卫**，
    且 `ReservationReconcileServiceImpl` 的 `@Scheduled(fixedDelayString = ...)` **没有 `initialDelay`**
    → 每个 `@SpringBootTest` 上下文**启动即跑一次对账**（后台线程）。今日无害（夹具要么新鲜、要么已终态），
    但一旦出现"陈旧非终态"夹具，就会有一条后台线程与用例并行地去补偿它 → 环境相关的偶发失败。
    修法：加 `initialDelay`，或把 `@EnableScheduling` 收进 profile → Phase 9。详见 `PHASE8-REVIEW.md` §6 F-B
    → ✅ **Phase 9 关闭**：`@Scheduled` 已加 `initialDelayString`（默认 300000 ms）。**诚实登记**：
    属阴性对照——去掉它不会让任何用例变红（它防的是"测试期后台线程污染夹具"而非功能正确性），
    故该修复**无判别力证据**，只登记为"已实施"（见 `PHASE9-REVIEW.md` §「F-B 修复」）
30. **本机 RabbitMQ 上残留两个课程死队列** `hello,queue1` / `hello,queue2`（durable、非 auto-delete，
    各积压 1 条消息）。声明它们的课程时代监听器/测试类已在 T1 删除，**全仓已无任何引用**
    （`grep -rn 'hello,queue' src/` = 0）。属 broker 侧状态而非仓库状态 → ⚠️ **T6 新发现，未处理**：
    删除属对用户 broker 的破坏性操作，且该 broker 与课程演示共用；已登记，等用户决定
    （同 `icr:order:*` 的处理方式）

## Last Review

Phase 10 Review — PASS — 2026-10-06，详见 `docs/refactor/PHASE10-DESIGN.md` §3.13
（T1–T6 逐项验收：命名零残留、26 个黑马语义关键词 0 命中、凭据两条探测器命中 0、README 15 节齐备、
禁改清单 porcelain 全空；判别力证据 V1–V12 + W1–W3 共 15 组变体实验，全部 byte-exact 复原。
**诚实登记的强度缺口**：`LOGIN_USER_TTL` 常量（V8 阴性对照证明无行为可测）、P2-22 分支二 CAS 拒绝
（无可确定性构造的注入点）、F4 的 `@Transactional`（无行为差异）——三项无变体强度证据，不冒充已验证。
**§3.13 第 10 条（P0/P1/P2 三档逐条结论）已在 T6 后的收口审计中补齐**：P2 全部 30 行逐行有结论，
其中 6 行原本只有过程记录（5 行缺关闭标记、3 行停在"Phase 10 评估"），本轮补结论或补标记；
核对中新发现第 30 条（broker 侧课程死队列）。**这次遗漏本身就是证据**：文档层面的"已安排"不等于"已完成"。
**第 7/8 条的实测状态**：只读部分已验 —— `information_schema` 确认 18 张表（10 张死表**仍在**，8 张在用）、
在用表 4 张 0 行、Redis 6 个 key 全为 `icr:*`（无 `reservation:*` 残留）、
`reservation.create.queue` 与 `.dlq` 均 `messages=0`、DLQ 无死信；
"死表是否消失"这一条**必须等 DROP 执行后**才能判。
**待用户确认后执行的动作**：10 张历史课程死表 DROP；另可一并清理 2 个课程死队列。）


Phase 8 Review — PASS — 2026-10-05，详见 `docs/refactor/PHASE8-REVIEW.md`
（跨 T1–T8 独立复查：R1–R11 逐条裁定并按证据强度分档（变体 6 / 读码-grep 4 / 无证据 1），
Review 抓出 1 个真实缺陷 **F-A**（秒级 `timestamp` 上 `ORDER BY` 不唯一 → 分页可重复或漏行）并返工关闭，
新增 2 条并列用例经 **2×3 次**变体实验证明判别力；另登记 F-B/F-C/F-D/F-E 指派后续 Phase。
核心数字：返工后全量 **136 run / 0 failures / 0 errors**、`Total time: 01:10 min`；
生产变更面恰为 **2 个文件**、其余 6 个生产基线 SHA-256 逐字节一致、禁改清单 8 个文件 porcelain 全空；
MySQL 四表残留 **0 行**、两个预约队列 **`messages=0` / `consumers=0`**。
诚实登记的强度缺口：**R10 的设计验证方式（判别实验 F7）从未执行**，故 I11 不算已由变体证明）

上一轮：Phase 7 Review — PASS — 2026-10-05，详见 `docs/refactor/PHASE7-REVIEW.md`
（含 8 组证伪实验与一轮返工闭环；对 Spring AMQP 2.2.18 的 ack/nack 失败模型做了字节码级复核。
核心数字：返工前全量 **88 run / 1 failure**（既有测试被异步语义击穿）、`tb_reservation` 跑后残留 **8 行**；
返工后 **90 run / 0 failures / 0 errors**、残留 **0 行**。生产代码零改动经 mtime 窗口与逐字节比对双重证实）

上一轮：Phase 6 Review — PASS — 2026-10-04，详见 `docs/refactor/PHASE6-REVIEW.md`
（含 13 组变体 + 3 组窗口可达性实验；抓出并关闭 2 处服务层覆盖空洞后，用同一实验复验通过。
核心数字：把原子脚本换成两段式实现 → 20 并发抢 5 名额 **9 人成功**；同一用户 20 并发 **13 次成功**）
