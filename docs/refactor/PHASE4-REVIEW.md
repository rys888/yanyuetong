# Phase 4 Review

## Result

PASS

审查对象：Phase 4「Equipment Cache 设备缓存」+ 缺陷 P1-8（`CacheClient` 互斥失效）修复
规格依据：`docs/refactor/PHASE4-DESIGN.md`（用户已批准）+ `实验资源预约平台-分阶段重构Phase计划.md` §7
审查方式：逐行核对 diff、独立复跑编译与全量测试、**亲手构造两个变体做判别实验**、启动应用做端到端实测、直连 DB / Redis / Lua 核验
审查人：Claude（Control Plane），2026-10-04

---

## 1. 交付物核对

| 类别 | 文件 |
| --- | --- |
| 修改 | `utils/CacheClient.java`、`utils/RedisConstants.java`、`service/IEquipmentService.java`、`service/impl/EquipmentServiceImpl.java`、`controller/EquipmentController.java` |
| 新增 | `test/java/com/hmdp/CacheClientMutexTest.java`、`test/java/com/hmdp/EquipmentCacheTest.java` |

`git diff --stat`：5 files changed, **+143 / −52**。与设计 §5 完全一致。

**禁止清单零触碰**（`git diff` 无输出）：`pom.xml`、`unlock.lua`、`SimpleRedisLock.java`、`ILock.java`、`RedisData.java`、
`application.yaml`、`application-local.yaml`（mtime 仍为 10-03 17:58，未改）、`config/` 全部、
Phase 3 的 `UserRole`/`RequireRole`/`RoleInterceptor`/`MvcConfig`、遗留秒杀与 blog 代码。

**生产文件 SHA-256 与 Codex 报告逐一一致** —— 证明其在证伪步骤中的临时改动已逐字节恢复（详见 §5.3）：

```text
CacheClient.java            0a2bfc3529a2361a96d3e19cf39e6111f6209df07e051cde8118cccd16bfb7a5
RedisConstants.java         6a5d7030fb14ba6b1632231a573bfbf4852ef609b3929b389b4c034fd0b7bff3
IEquipmentService.java      9a992fc6966a2b11937bdbf1d927692b83365a7cf186c08712579c83f1f74bba
EquipmentServiceImpl.java   38a1a89e7c9f71aad89cf848b3711486c55a19c09d769aff6165e40ba9e87c10
EquipmentController.java    243a79c1c5412b82e0a0afe59aa3af1a742e3e126467cb3cc2b192d11ec611ff
```

---

## 2. 计划 §7 验收标准逐条核验

| 验收标准 | 结论 | 证据 |
| --- | --- | --- |
| Equipment 查询优先 Redis | ✅ | §4.3：**直改 DB 后接口仍返回旧值**，证明命中缓存而非查库 |
| Cache Miss 正确查询数据库 | ✅ | §4.1 冷启动写入缓存；`EquipmentCacheTest` 计数 `selectById` = 1 |
| 不存在数据时有空值缓存 | ✅ | §4.4：`EXISTS=1`、`STRLEN=0`、`TTL=120s` |
| 热点 Key 击穿有明确处理 | ✅ | 方案 A 互斥锁重建；T1 + **T1b** 双重覆盖（§5） |
| Equipment 更新后缓存正确失效 | ✅ | §4.5：更新后 `EXISTS=0`，再查回源拿新值 |
| Key 命名统一 | ✅ | `lab:equipment:` 收敛在 `RedisConstants`，锁键 `lock:lab:equipment:{id}` |
| Maven compile 成功 | ✅ | `mvn clean test-compile` BUILD SUCCESS（68 main + 4 test，JDK 8） |
| 有条件增加缓存测试 | ✅ | 从「有条件」升格为硬要求：新增 7 个测试（3 + 4） |

**附加（设计自加）**：`mvn test` → **Tests run: 9, Failures: 0, Errors: 0**；**P1-8 关闭**（§5 判别实验）。

---

## 3. 实现细节核对（逐行）

### 3.1 `CacheClient`（Stage 1 修复）

| 项 | 设计 | 实际 | 结论 |
| --- | --- | --- | --- |
| 归属令牌 | per-acquisition `UUID.randomUUID().toString(true)` | 循环内每轮生成，`queryWithLogicalExpire` 由闭包捕获 | ✅ |
| 释放 | `unlock.lua` 原子比对删除 | `DefaultRedisScript<Long>` + `ClassPathResource("unlock.lua")` | ✅ |
| `queryWithMutex` | 有界循环，失败 `continue` 绕过 `try` | 逐字一致，`MUTEX_MAX_RETRY = 20` | ✅ |
| 双重检查 | 抢锁成功后重读缓存 | 有 | ✅ |
| 兜底 | 重试耗尽直查 DB（不写缓存） | 有 | ✅ |
| 中断处理 | 复位中断位再抛 | `Thread.currentThread().interrupt()` 在 `throw` 之前 | ✅ |
| 逻辑过期路径 | 闭包令牌 + `log.error` | 一致，异常不再消失在无人 `get()` 的 Future | ✅ |
| `queryWithPassThrough` | 不改 | diff 中无此方法 | ✅ |
| 常量 | 三个私有常量，锁 TTL 沿用 10s | 一致，无魔法数字 | ✅ |
| 类 javadoc | 标明正式方案 A | 已注明 | ✅ |

**原缺陷四项逐一确认关闭**：D1（无归属）→ 令牌+Lua；D2（递归帧误删）→ 循环结构使失败帧不进入 `try`；
D3（无界递归）→ 迭代上界；D4（跨线程盲删 + 静默异常）→ 闭包令牌 + `log.error`。

### 3.2 `EquipmentServiceImpl` / `IEquipmentService` / `EquipmentController`

| 项 | 结论 |
| --- | --- |
| 回源方法拆分 | ✅ `queryEquipmentByIdFromDb` 为 `private`，公开方法走缓存；**无自递归** |
| 缓存失效落点 | ✅ 下沉 Service：`update*` / `delete*` 内数据变更 + 删缓存同方法完成 |
| `saveEquipment` | ✅ 返回 id，`DELETE`/`POST` 语义与 Phase 2 一致 |
| Controller 只留 HTTP 校验 | ✅ `EquipmentStatus.valueOf` + `Result.fail("设备状态不合法")` 留在 Controller |
| `@RequireRole(LAB_ADMIN)` | ✅ 4 个写端点标注原样保留，位置未变；GET 未标注 |
| `Result.fail("设备不存在")` | ✅ 语义不变（`queryWithMutex` 返回 null） |
| TTL 抖动 | ✅ `equipmentCacheTtlMinutes()` 调用点计算，不侵入 `CacheClient.set` 通用语义 |

---

## 4. 运行时实测（Claude 独立执行，非采信 Codex）

应用以 JDK 8 启动于 `18081`，`lsof` 确认监听者为 **java PID 74553**（非 nginx，规避 Phase 2 的误判陷阱）；
所有 curl 加 `--noproxy '*'`。

### 4.1 冷启动：Cache Miss → 查库 → 写缓存

```
GET /equipment/1 → 200，字段完整：
  status="AVAILABLE"  bookingEnabled=true
  createTime="2026-10-03T18:00:48"  updateTime="2026-10-03T18:00:48"
  laboratoryName="材料科学实验中心"  categoryName="显微成像"

Redis: EXISTS lab:equipment:1 = 1 ; TTL = 2040s
```

`TTL 2040s = 34 分钟`，落在 `30min + [0,5min)` 区间内 —— **抖动生效**。

### 4.2 二次请求

返回字段与首次逐项一致（枚举、布尔、两个时间字段、三表聚合字段均正确）—— Hutool 往返在**运行时**同样无损。

### 4.3 读缓存的**决定性证明**

不走服务层、直连 MySQL 把设备 1 的 `name` 改成 `DB-DIRECT-CHANGED`（**缓存未动**）：

```
GET /equipment/1 → name = "扫描电子显微镜"     ← 仍是旧值
```

接口返回的是**缓存中的旧值**，而非数据库中的新值 —— 这是"查询优先 Redis"的直接证据，排除了"每次都查库"的可能。

### 4.4 缓存穿透

```
GET /equipment/99999999 → {"success":false,"errorMsg":"设备不存在"}
Redis: EXISTS lab:equipment:99999999 = 1 ; STRLEN = 0 ; TTL = 120s
```

空值缓存生效，TTL 2 分钟（`CACHE_NULL_TTL` 沿用），语义与 Phase 2 一致。

### 4.5 更新后失效

```
PUT /equipment/1/status?status=MAINTENANCE → {"success":true}
Redis: EXISTS lab:equipment:1 = 0                    ← 缓存已删
GET   /equipment/1 → name="DB-DIRECT-CHANGED" status="MAINTENANCE"
```

回源拿到的是数据库**最新**值 —— 失效生效。随后已把设备 1 复原为 `扫描电子显微镜 / AVAILABLE`（见 §4.7）。

### 4.6 权限回归（Phase 3 标注是否被破坏）

```
无 token：  PUT /equipment/1/status → 401   POST /equipment → 401   GET /equipment/1 → 401
普通用户：  PUT status → 403   POST → 403   DELETE → 403   PUT → 403
            GET /equipment/1 → 200   GET /equipment/page → 200
```

401 / 403 / 200 全矩阵正确，**Phase 3 的鉴权零回归**。

### 4.7 数据基线与残留（H5）

```
tb_equipment=12   tb_laboratory=4   tb_equipment_category=6
id>12 残留=0      name LIKE '%测试%'=0
设备 1：扫描电子显微镜 / AVAILABLE        ← 已复原
Redis：lab:equipment:* / test:mutex:* / lock:* 均为空
应用进程：无残留
```

设备 id 1–12 与 4 个实验室（Phase 2 基线，后续 Phase 依赖）**未被破坏**；测试自清理有效。

---

## 5. 修复的判别实验（本 Phase 的核心证据）

> Codex 的沙箱 `.git` 只读，无法执行 `git checkout` 证伪；且第二轮其沙箱失去 Redis 网络权限，两次运行时验证均未取得。
> **以下实验全部由 Claude 亲自执行。**

### 5.1 实验设计

只改动「归属校验」这一个变量，观察测试是否随之翻转，从而确定每个测试**真正**在判别什么：

| 变体 | 有界循环 | 双重检查 | 归属校验 | T1 结果 |
| --- | --- | --- | --- | --- |
| **V1** 旧实现 | ✗ | ✗ | ✗ | **失败** `expected: <1> but was: <2>` |
| **V3** 仅去掉归属校验 | ✓ | ✓ | ✗ | **通过** ← 关键发现 |

### 5.2 发现的问题：T1 未覆盖归属校验

V3 证明：**T1 的判别力 100% 来自「有界循环 + 双重检查」，对归属校验（per-acquisition 令牌 + `unlock.lua` 比对删除）零判别力。**

而归属校验是本 Phase 最核心的设计决策（也是否决复用 `SimpleRedisLock` 的直接理由）。它并非无用 ——
它挡的是「持锁线程回源期间锁 TTL（10s）到期、锁已被别的线程取得，本线程的 `finally` 会删掉**别人**的锁」这个窗口 ——
但**当时没有任何测试能证明它生效**。

→ 已按 §18 流程向 Codex 下发 Revise，要求新增 `shouldNotDeleteLockOwnedByAnotherHolder`（T1b）。

### 5.3 T1b 的判别力（修复后复测）

| 变体 | T1b 结果 |
| --- | --- |
| 修复版 | **通过**（`Tests run: 3, Failures: 0`） |
| 盲删变体 | **失败** `shouldNotDeleteLockOwnedByAnotherHolder:151 expected: <another-holder> but was: <null>` |

T1b 精确地只在归属校验缺失时失败 —— 该设计决策**现已可被测试证明**。
实验后生产文件 SHA-256 与实验前一致，无残留。

### 5.4 底层原语直连验证

不依赖 Java，直连 Redis 验证修复所依赖的 `unlock.lua`：

```
SET  k = "owner-A"
EVAL unlock.lua k "owner-B"  → 返回 0 ; EXISTS k = 1   （错误令牌：锁保留 ✓）
EVAL unlock.lua k "owner-A"  → 返回 1 ; EXISTS k = 0   （正确令牌：锁删除 ✓）
```

**键空间重叠检查**：`SimpleRedisLock` 用 `lock:order:{userId}`（秒杀），`CacheClient` 用 `lock:lab:equipment:{id}`，
两者不重叠，无串扰。

---

## 6. 发现的问题

### P0

无。

### P1

无。（§5.2 的测试覆盖缺口已在本轮闭环：Revise → 复测通过。未遗留到下一 Phase。）

### P2（清理项 / 已知边界）

1. **T1 的夹具过度复杂**：`spy(StringRedisTemplate)` + `spy(opsForValue())` + 线程改名为 `delayed-mutex-worker` +
   `doAnswer` 内忙等 + `Thread.yield()`，且用 `"{}"` 规避 Hutool 对顶层 JSON 字符串 `toBean(String.class)` 的
   `JSONException`。断言（`dbCalls == 1`）有效，但夹具脆弱、可读性差，且**是为制造特定交错而人工构造的**。
   T1b 已覆盖归属校验，故 T1 的复杂度不再承担关键职责。**建议 Phase 10 评估简化**，本 Phase 不动（已通过且确定性）。
2. **设计文档 §7 对 T1 的描述不准确**（称其「直击 D2」）。**已更正**，并补入 T1b 行 —— 见 §8。
3. **`queryWithPassThrough` / `queryWithLogicalExpire` 在本 Phase 后无生产调用方** → 按设计登记，**Phase 10** 决定去留。
4. **重试耗尽后的兜底直查 DB 不写缓存**：极端竞争下可能惊群。当前 `MUTEX_MAX_RETRY × 50ms ≈ 1s`，
   远大于一次设备详情重建（毫秒级），实际触发概率极低。设计有意如此（缓存故障不应导致请求失败）。
5. **缓存删除失败无补偿**（若 `DELETE` 失败则靠 30min TTL 兜底）→ 计划 §8 范围，本 Phase 不引入延迟双删 / binlog 订阅。
6. **`EquipmentCacheTest` 的计数方式与实现耦合**：通过 MyBatis `Interceptor` 精确匹配
   `EquipmentMapper.selectById` 语句 id。若将来回源改用别的语句，计数会静默失效（测试仍「通过」但不再测任何东西）。
   当前实现正确，登记为维护性提醒。
7. **T4 用反射调用私有方法** `queryEquipmentByIdFromDb` 取"直查基准"。私有方法被测试反射调用属可接受的取舍
   （替代方案是放宽可见性或引入额外接口），但重构时该测试会随方法名变动而失败。
8. **`EquipmentController` 的 `saveEquipment` 直接返回 Service 结果**，与其余三个端点"调用后 `return Result.ok()`"
   风格略不统一 —— 无功能影响。

### 关于 Codex 执行过程的说明

Codex 两轮的运行时验证都未取得有效结果（第二轮沙箱失去本机 Redis 网络权限，9 个测试全部在 Spring 上下文
初始化阶段报 `SocketException: Operation not permitted`，**未进入任何测试方法**）。
它**如实标注为环境阻塞而未当作通过**，并提供了生产文件 SHA-256 作为"临时改动已恢复"的证据 —— 该做法正确。
所有运行时结论均由 Claude 独立复跑取得。

---

## 7. 规格复核

本 Phase 设计下发后未出现需要中途改设计的冲突。**唯一的设计缺陷由 Review 自身发现**：
设计 §7 声称 T1「直击 D2」，实测证明不准确（§5.2）；更正已落到设计文档，并通过新增 T1b 补全覆盖。
这与 Phase 2 的教训一致 —— **"写了一个测试"与"测试真的测到了那个缺陷"是两回事，必须做变量隔离实验才能区分。**

---

## 8. 文档更正记录

`docs/refactor/PHASE4-DESIGN.md` §7 测试计划表：

- T1 的「目的」列由 `**直击 D2**` 更正为：判别力来自「有界循环 + 双重检查」，对归属校验无判别力（附实测说明）；
- 新增 T1b 行，描述归属校验测试及其判别力证据。

---

## 9. 结论

Phase 4 全部验收标准达成，P1-8 缺陷关闭且**已由隔离实验证明可被测试证伪**，改动范围精确，
Phase 2/3 基线与鉴权零回归，数据与 Redis 无残留。可以进入下一 Phase。

Phase 5 的准入前置：**P1-1（LAB_ADMIN 无实验室归属约束）** —— 计划 §6 称「管理自己负责的实验室」，
Phase 5 引入归属模型时，需给 Phase 3 已标注的 7 个端点补上"且该实验室归我管"的判断。

```text
PHASE 4 — PASS
```
