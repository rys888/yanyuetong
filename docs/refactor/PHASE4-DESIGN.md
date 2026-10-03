# Phase 4 Design — Equipment Cache（含 `CacheClient` 互斥缺陷修复）

状态：**待用户批准**
依据：`实验资源预约平台-分阶段重构Phase计划.md` §7 + Phase 2 / Phase 3 Review 登记的 P1-8
日期：2026-10-04

---

## 0. 本 Phase 为什么必须带一个 Bug Fix

Phase 2 Review 登记 P1-8：

> `CacheClient.queryWithMutex` 互斥失效：抢锁失败的线程递归返回后，其 `finally` 会删除锁持有者的锁。
> 当前无调用方（惰性缺陷），但 Phase 4 将启用 `CacheClient`，**必须在 Phase 4 修复**。

`CacheClient` 至今**零调用方**，所以这个缺陷尚未造成任何实际故障。Phase 4「设备详情缓存」正是它的第一个消费者，
而计划 §7 要求「热点 Key 击穿有明确处理」——**若带着这个缺陷启用，击穿防护不仅无效，还会比不加锁更糟**。
因此本 Phase 拆成两步：**先修互斥（4.0），再上缓存（4.1）**。

---

## 1. 缺陷分析（P1-8）

### 1.1 现象

`queryWithMutex` 抢锁失败时**递归**重试，而 `unlock` 放在 `finally` 里无条件执行：

```java
boolean isLock = tryLock(lockKey);
if (!isLock) {
    Thread.sleep(50);
    return queryWithMutex(...);   // ← 递归，本帧的 finally 会在递归返回后执行
}
...
} finally {
    unlock(lockKey);              // ← 无条件 delete，不管本帧是否真的持锁
}
```

### 1.2 四个子缺陷

| 编号 | 缺陷 | 后果 |
| --- | --- | --- |
| **D1** | **锁无归属标识**：`tryLock` 写入常量 `"1"`，`unlock` 是无条件 `DEL` | 任何线程都能删掉别人的锁。这是 D2/D4 无法被局部修复的**根因** |
| **D2** | **递归帧的 `finally` 误删他人锁**：抢锁失败的帧没有"我是否持锁"的状态 | 递归返回时该帧 `finally` 执行 → 删掉**当前持锁线程**的锁 → **互斥彻底失效** |
| **D3** | **无界递归**：每次抢锁失败递归一层，每层 `sleep(50)` | 持锁线程重建慢时栈深无上限 → `StackOverflowError` |
| **D4** | **逻辑过期异步路径同源问题**：`unlock` 在线程池线程中执行，且仍是盲删 | 一旦改用 per-thread 归属标识，跨线程释放必然比对失败、锁只能等 TTL 自然过期；且 `catch (Exception e) { throw new RuntimeException(e); }` 把异常丢进无人 `get()` 的 `Future`，重建失败完全静默 |

### 1.3 后果量化

D2 生效时，N 个并发请求在热点 Key 失效瞬间会**同时**查库重建（锁形同虚设），
而每个失败帧还会 `sleep(50)`。也就是说：**加了锁，却没有保护，还多了延迟**——
这是比"不加锁"更差的工程状态（虚假的安全感）。

---

## 2. 修复设计（4.0）

### 2.1 设计要点

**R1. 引入 per-acquisition 归属令牌（治 D1、D4）**

每次**抢锁动作**生成一个独立 token，写入锁值；释放时只有 token 相等才删除。

```java
String lockValue = UUID.randomUUID().toString(true);
```

> **为什么是 per-acquisition 而不是 per-thread？**
> `SimpleRedisLock` 用的是 `ID_PREFIX + 当前线程 id`（per-thread）。但 `queryWithLogicalExpire`
> 的释放发生在**线程池线程**里（≠ 抢锁线程），per-thread 令牌在跨线程释放时必然比对失败。
> per-acquisition 令牌由 lambda 闭包捕获，跨线程依然正确。
> 这是**本 Phase 不复用 `SimpleRedisLock` 类**的直接技术原因——而不是偏好问题。

**R2. 释放改为原子「比对并删除」（治 D1）**

复用仓库里**现成的** `src/main/resources/unlock.lua`，不新写 Lua：

```lua
if(redis.call('get', KEYS[1]) == ARGV[1]) then
    return redis.call('del', KEYS[1])
end
return 0
```

```java
private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
static {
    UNLOCK_SCRIPT = new DefaultRedisScript<>();
    UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
    UNLOCK_SCRIPT.setResultType(Long.class);
}
private void unlock(String key, String value) {
    stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), value);
}
```

**R3. 递归 → 有界循环（治 D2、D3）**

这是修复 D2 的**结构性**手段：让"抢锁"与"释放"落在**同一次迭代**里，
抢锁失败直接 `continue`，**根本不进入 `try` 块**，因此不存在"没持锁却执行 `finally`"的帧。

```java
public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type,
                                Function<ID, R> dbFallback, Long time, TimeUnit unit) {
    String key = keyPrefix + id;
    String lockKey = LOCK_CACHE_KEY + keyPrefix + id;

    for (int attempt = 0; attempt < MUTEX_MAX_RETRY; attempt++) {
        // 1) 查缓存（命中即返回，含空值缓存分支）
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) { return JSONUtil.toBean(json, type); }
        if (json != null) { return null; }

        // 2) 抢锁：失败则等待后进入下一轮（下一轮会重新查缓存）
        String lockValue = UUID.randomUUID().toString(true);
        if (!tryLock(lockKey, lockValue)) {
            sleepQuietly(MUTEX_RETRY_INTERVAL_MS);
            continue;                       // ← 不进入 try 块，本帧不持有锁，不会释放
        }

        // 3) 持锁重建：finally 只覆盖真正持锁的这一帧
        try {
            // 3.1 双重检查：等锁期间可能已被他人重建
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(cached)) { return JSONUtil.toBean(cached, type); }
            if (cached != null) { return null; }
            // 3.2 回源
            R r = dbFallback.apply(id);
            if (r == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            this.set(key, r, time, unit);
            return r;
        } finally {
            unlock(lockKey, lockValue);     // 只有令牌匹配者才真正删除
        }
    }

    // 4) 重试耗尽：降级直查数据库（不写缓存），保证缓存故障不导致请求失败
    return dbFallback.apply(id);
}
```

**逐条对应**：
- D2 消除：`tryLock` 与 `try/finally` 同一迭代，失败分支 `continue` 绕过 `try` 块 → **不存在误删帧**。
- D3 消除：迭代次数上界 `MUTEX_MAX_RETRY`，栈深 O(1)。
- 等待中的线程每轮**重新查缓存**，持锁者重建完成后立即命中返回，不会继续空转（原递归版本也查，但代价是栈帧）。

**R4. `queryWithLogicalExpire` 的异步重建同步修复（治 D4）**

```java
String lockValue = UUID.randomUUID().toString(true);
boolean isLock = tryLock(lockKey, lockValue);
if (isLock) {
    CACHE_REBUILD_EXECUTOR.submit(() -> {
        try {
            R newR = dbFallback.apply(id);
            this.setWithLogicalExpire(key, newR, time, unit);
        } catch (Exception e) {
            log.error("缓存逻辑过期重建失败, key={}", key, e);   // 原先异常消失在无人 get 的 Future 里
        } finally {
            unlock(lockKey, lockValue);                        // 闭包捕获令牌，跨线程比对正确
        }
    });
}
```

### 2.2 新增私有常量（`CacheClient` 内部）

```java
private static final int  MUTEX_MAX_RETRY          = 20;    // 20 × 50ms ≈ 1s 等待上限
private static final long MUTEX_RETRY_INTERVAL_MS  = 50L;
private static final long MUTEX_LOCK_TTL_SEC       = 10L;   // 沿用现值，抽成常量而非魔法数字
```

`sleepQuietly` 捕获 `InterruptedException` 时**先 `Thread.currentThread().interrupt()` 复位中断位**再抛出，
不吞中断信号。

### 2.3 为什么不复用 `SimpleRedisLock` / `ILock`（已评估并否决）

| 方案 | 否决理由 |
| --- | --- |
| `CacheClient` 内部持有 `ILock`（`SimpleRedisLock`） | ① 其令牌**绑定线程**，`queryWithLogicalExpire` 跨线程释放必然失效（须先改 `SimpleRedisLock`，而它正被 `VoucherOrderServiceImpl` 使用，改动有回归面）；② 会把 `RedisConstants.LOCK_CACHE_KEY` 架空（`SimpleRedisLock` 内部硬编码 `"lock:"`），与已登记的 P2-1「Redis Key 重复定义」相悖；③ Phase 8 计划引入 Redisson，接口形态届时还要再动 |
| 引入 Redisson `RLock` 修复 | Redisson 属 Phase 8 范围，且会让 `CacheClient` 这个通用缓存工具耦合分布式锁客户端。**提前引入 = 越界** |
| 保留递归，只把 `unlock` 移进"持锁分支" | 能治 D2/D4，但 D3（无界递归 → `StackOverflowError`）仍在；有界循环才是该模式的正确写法 |

> **复用的是脚本 `unlock.lua`（正确性关键的那 3 行），不是类。** 这是本设计 DRY 的真正落点：
> 全仓库只有一份「比对并删除」的 Lua 实现，不新增第二份。
> `unlock.lua` 与 `SimpleRedisLock` **均不修改**。

---

## 3. 缓存击穿主方案选择：**方案 A（互斥锁重建）**

计划 §7 要求「正式业务只选一种主方案」，并授权「Agent 根据现有 `CacheClient` 代码决定」。决定：**方案 A**。

| 维度 | 方案 A 互斥锁重建 ✅ | 方案 B 逻辑过期 |
| --- | --- | --- |
| 是否需要预热 | **不需要**，任意 Key 首次访问即可生效 | **必须预热**：Key 必须已存在且带逻辑过期时间才会触发重建 |
| 本仓库当前是否具备预热设施 | 不依赖 | ❌ 不存在（计划 §8 的「Redis 初始化」在 Phase 5+） |
| 数据新鲜度 | 强（重建后立即返回新值） | 弱：重建期间**持续返回过期数据** |
| 对设备详情的适配性 | 好 | **差**：`status=MAINTENANCE/DISABLED`、`bookingEnabled` 直接影响学生是否发起预约，陈旧读代价高 |
| 并发代价 | 并发请求最多等一次 DB 查询（毫秒级） | 不阻塞 |

**结论**：设备详情是**按 id 点查**，热点集合事前不可知；选方案 B 等于现在就要造一套**零消费者的预热设施**，
违反「不要过度设计」。而设备状态字段对陈旧读的容忍度低，方案 A 的强一致更贴合业务。

### 3.1 关于 `CacheClient` 中三个方法的去留

**保留全部三个方法**（`queryWithPassThrough` / `queryWithMutex` / `queryWithLogicalExpire`），
在类 javadoc 中明确标注：**Equipment 详情正式采用 `queryWithMutex`（方案 A），`queryWithLogicalExpire` 为未启用的备选实现**。

理由：
- 计划 §7 的禁令是「不要为了简历**在正式业务中同时使用两套**」——针对的是**生产调用路径**，
  设备详情只有一条路径（`queryWithMutex`），并未「同时使用两套」；
- `CacheClient` 是**缓存策略工具集**，三者是同一组模式（穿透 / 互斥击穿 / 逻辑过期击穿）的完整实现，
  在 Phase 4 一次性把两个带锁方法的缺陷都修好，成本极低（共用同一对 `tryLock`/`unlock` helper）；
- 只保留一个方法属于**机械删减技术资产**（用户明确禁止「机械删除技术概念」），且不解决任何实际问题。

**同时登记为 P2**：`queryWithPassThrough` 与 `queryWithLogicalExpire` 在本 Phase 后**无生产调用方**，
交由 **Phase 10** 决定去留（保留为工具集 / 删除）。本 Phase 不做该决策，避免在无信息时剪裁。

---

## 4. 设备详情缓存设计（4.1）

### 4.1 缓存对象与 Key

| 项 | 取值 |
| --- | --- |
| 缓存对象 | **仅 `EquipmentVO` 详情**（`GET /equipment/{id}` 的返回值） |
| Key | `lab:equipment:{equipmentId}`（常量 `RedisConstants.CACHE_EQUIPMENT_KEY = "lab:equipment:"`） |
| 锁 Key | `lock:lab:equipment:{equipmentId}`（由 `LOCK_CACHE_KEY + keyPrefix + id` 生成） |
| 序列化 | Hutool JSON 字符串（Redis String） |
| 淘汰策略 | Cache Aside：**更新 MySQL → 删除 Redis** |

**不缓存的内容**：`GET /equipment/page`（所有列表）、`GET /laboratory/**`、`GET /equipment-category/list`。
计划 §7 明确「不要缓存所有列表」。

**不缓存 Laboratory 的论证**（计划要求「根据实际访问决定」）：
`queryEquipmentByIdFromDb` 已经把实验室名 / 校区 / 楼栋 / 房间 / 类别名**内嵌进 `EquipmentVO`**，
读设备详情时无需再读 Lab；`GET /laboratory/{id}` 无高频访问场景（Lab 信息主要在列表页消费，而列表已被排除）。
**当前不存在真实热点 → 不缓存**。若 Phase 5/6 出现真实热点再补。

### 4.2 TTL

| 对象 | TTL | 说明 |
| --- | --- | --- |
| 正常数据 | `30 分钟 + random(0, 5) 分钟` | 基础 TTL 抽成常量；抖动避免同批 Key 同时失效（雪崩） |
| 空值 | 沿用 `CACHE_NULL_TTL = 2` 分钟 | 已有常量，短 TTL 防穿透；**不新增配置** |
| 锁 | `MUTEX_LOCK_TTL_SEC = 10` 秒 | 沿用现值 |

TTL 抖动在**调用点**计算（`EquipmentServiceImpl` 的私有方法），不侵入 `CacheClient.set` 的通用语义；
由于 `queryWithMutex` 每次重建都会用当次传入的 `time`，抖动天然**每次重建重新随机**。

### 4.3 缓存失效落点：下沉到 Service 层（分层修复）

**现状问题**：`EquipmentController` 的 4 个写端点**直接调用** `equipmentService.save/updateById/removeById`。
若把 `stringRedisTemplate.delete(...)` 写在 Controller 里，是把缓存这一**服务层关注点**泄漏到 Web 层。

**设计**：把写操作收敛为 `IEquipmentService` 的显式方法，缓存失效与数据变更在同一方法内完成：

```java
// IEquipmentService 新增
Long saveEquipment(Equipment equipment);                    // 新增无旧缓存，无需清理
void updateEquipment(Equipment equipment);                  // updateById + 删除 lab:equipment:{id}
void updateEquipmentStatus(Long id, EquipmentStatus status);// updateById + 删除 lab:equipment:{id}
void deleteEquipment(Long id);                              // removeById + 删除 lab:equipment:{id}
```

`EquipmentController` 的 4 个 `@RequireRole(LAB_ADMIN)` 端点改为调用上述方法。
**状态字符串的解析与合法性校验（`EquipmentStatus.valueOf` → `Result.fail("设备状态不合法")`）保留在 Controller**，
它属于 HTTP 入参校验；Service 只接收已解析的枚举。

> 注意：`queryEquipmentById` 需拆成**公开的缓存路径**与**私有的回源路径**，避免 `dbFallback` 递归调用自身：
> - `queryEquipmentById(Long id)` → 走 `cacheClient.queryWithMutex(...)`
> - `queryEquipmentByIdFromDb(Long id)` → 原有的三表聚合逻辑（`private`），作为 `dbFallback`

### 4.4 与既有鉴权的兼容

`@RequireRole(LAB_ADMIN)` 标注**原样保留**（Phase 3 的 7 个端点标注不动），本 Phase 只改端点**方法体**的调用目标。
GET 端点依旧不标注。Phase 3 Review 的 P1-1（LAB_ADMIN 无实验室归属约束）仍**推迟到 Phase 5**，本 Phase 不处理。

---

## 5. 改动范围

### 4.0 修复（commit 1）

| 动作 | 文件 |
| --- | --- |
| M | `src/main/java/com/hmdp/utils/CacheClient.java` |

### 4.1 设备缓存（commit 2）

| 动作 | 文件 |
| --- | --- |
| M | `src/main/java/com/hmdp/utils/RedisConstants.java`（+1 常量，+2 TTL/抖动常量） |
| M | `src/main/java/com/hmdp/service/IEquipmentService.java` |
| M | `src/main/java/com/hmdp/service/impl/EquipmentServiceImpl.java` |
| M | `src/main/java/com/hmdp/controller/EquipmentController.java` |
| A | `src/test/java/com/hmdp/CacheClientMutexTest.java` |
| A | `src/test/java/com/hmdp/EquipmentCacheTest.java` |

**预计**：4.0 为 1 文件；4.1 为 4 文件 + 2 测试。

### 5.1 禁止清单（零触碰）

`unlock.lua`、`SimpleRedisLock.java`、`ILock.java`、`RedisData.java`、`LoginInterceptor`、`RefreshTokenInterceptor`、
`UserHolder`、`RoleInterceptor`、`MvcConfig`、`SystemConstants`、`UserRole`、`RequireRole`、`User`/`UserDTO`、
`Laboratory`/`EquipmentCategory`/`Equipment` 实体字段、`EquipmentMapper.xml`、Phase 2/3 域模型的其他部分、
`VoucherOrderServiceImpl` 及全部遗留秒杀/voucher/blog 代码、`RabbitMQConfig`、`MybatisConfig`、`RedissonConfig`、
`UploadController`、`UserInfo` 四件套、`application*.yaml`、`pom.xml`。

**不允许**：引入 Redisson / Spring Cache / 任何新依赖；缓存列表接口；改动 `unlock.lua`。

---

## 6. 高危风险点（Review 必须逐条实测）

| 编号 | 风险 | 为什么危险 | 验证方式 |
| --- | --- | --- | --- |
| **H1** | **Hutool JSON 往返回损**：`EquipmentVO` 含 `EquipmentStatus` 枚举 + `LocalDateTime createTime/updateTime` + `Boolean bookingEnabled` + Lombok `@Accessors(chain = true)` | 写入用 Hutool、读出用 Hutool，与 Jackson 的 HTTP 响应序列化是**两套**；一旦枚举或时间字段往返丢失，接口会静默返回残缺数据 | 断言"缓存命中读出的 VO"与"直查 DB 的 VO"**逐字段相等**（尤其 `status` / `bookingEnabled` / 两个时间字段非 null 且相等） |
| **H2** | **互斥是否真的生效** | 本 Phase 的全部意义所在；且旧实现在低并发下也能"看起来正常"，必须用并发才能证伪 | 见 §7 T1：**该测试必须能在旧实现上失败** |
| **H3** | **更新后缓存未失效** | 管理员改了设备状态但学生仍看到旧状态，直接影响预约决策 | 写后读实测 + Redis `EXISTS` 断言为 0 |
| **H4** | **`dbFallback` 自递归** | 若 `queryEquipmentById` 直接把 `this::queryEquipmentById` 作回源，会无限递归 | 代码审查 + T1 的调用计数断言 |
| **H5** | **测试污染 Phase 2 种子数据** | 设备 id 1–12 与 4 实验室是 Phase 2 基线，后续 Phase 依赖 | 测试使用 id 段外的临时记录，`@AfterEach` 清理；Review 后复核 `COUNT(*)` 与基线一致 |
| **H6** | **重试耗尽后的兜底直查 DB 造成惊群** | 极端情况下 N 个线程同时回源 | 审查兜底分支**不写缓存**的设计意图；确认 `MUTEX_MAX_RETRY × 50ms` 远大于一次 DB 重建耗时 |

---

## 7. 测试计划（验收硬要求）

计划 §7 只说「有条件增加缓存测试」——本 Phase **将其列为硬要求**，因为 P1-8 正是那种"不写并发测试就发现不了"的缺陷。

| 编号 | 测试 | 断言 | 目的 |
| --- | --- | --- | --- |
| **T1** | `CacheClientMutexTest`：50 线程并发调用 `queryWithMutex`，`dbFallback` 用 `AtomicInteger` 计数并 `sleep(200)`，**不碰数据库** | 全部线程拿到正确值；`dbCalls == 1`；无异常 | 证伪旧实现（旧实现 `dbCalls == 2`）。⚠️ **审查更正（2026-10-04）**：实测该测试的判别力来自「有界循环 + 双重检查」，对归属校验（R1/R2）**无判别力** —— 仅把 `unlock` 改回盲删（保留循环与双重检查），T1 仍通过 |
| **T1b** | `CacheClientMutexTest.shouldNotDeleteLockOwnedByAnotherHolder`：回源过程中把锁键改写为他人的令牌 | 返回正常；**锁键值仍为他人的令牌**（未被删除） | **直击 D1/R2 归属校验**。盲删变体下该断言精确失败：`expected: <another-holder> but was: <null>` |
| **T2** | `CacheClientMutexTest`：`dbFallback` 返回 `null` | Redis Key 存在且值为空串；再查一次 `dbCalls` 不增加 | 空值缓存防穿透 |
| **T3** | `EquipmentCacheTest`：缓存命中/回源/空值 | 首次查库（`dbCalls==1`）→ 二次命中缓存（`dbCalls` 不变）→ 不存在的 id 返回 `null` 且留下空值 Key | 计划 §7 验收「查询优先 Redis / Cache Miss 正确查库 / 不存在数据有空值缓存」 |
| **T4** | `EquipmentCacheTest`：**H1 往返断言** | 缓存命中读出的 `EquipmentVO` 与 `queryEquipmentByIdFromDb` 直查结果**逐字段相等** | **H1** |
| **T5** | `EquipmentCacheTest`：更新后失效 | 查询填充缓存 → `updateEquipmentStatus` → `EXISTS lab:equipment:{id} == 0` → 再查询返回**新状态** | **H3** |

所有测试：`mvn test` 全绿；**确定性**（不依赖 sleep 时序的侥幸通过）；**自清理**（不在 DB 留残渣）。
新增测试不得删除或放宽 `HmDianPingApplicationTests` / `RedissonTest` 的既有断言。

---

## 8. 验收标准（对应计划 §7）

| 计划验收标准 | 本设计对应 |
| --- | --- |
| Equipment 查询优先 Redis | §4.1 + T3 |
| Cache Miss 正确查询数据库 | §4.3 `queryEquipmentByIdFromDb` + T3 |
| 不存在数据时有空值缓存 | §4.2 空值 TTL 2min + T2/T3 |
| 热点 Key 击穿有明确处理 | §2 修复 + §3 方案 A + **T1** |
| Equipment 更新后缓存正确失效 | §4.3 + T5 |
| Key 命名统一 | §4.1 复用 `RedisConstants` 单一来源 |
| Maven compile 成功 | `mvn clean test-compile`（JDK 8） |
| 有条件增加缓存测试 | §7 五条测试，**升格为硬要求** |

**附加（本设计自加）**：`mvn test` 全绿；**P1-8 缺陷关闭**（T1 证伪旧实现、证明新实现）。

---

## 9. 提交计划（本地，不 push）

```text
fix: repair CacheClient mutex mutual exclusion        ← 4.0
feat: add equipment detail cache                     ← 4.1
```

两笔提交、一个 Phase、一次 Review。若 4.0 的修复日后出问题，可独立回滚而不牵连缓存功能。

---

## 10. 已知边界（本 Phase 不解决，显式登记）

1. **LAB_ADMIN 无实验室归属约束**（Phase 3 P1-1）→ **Phase 5**。
2. **`queryWithPassThrough` / `queryWithLogicalExpire` 无生产调用方** → 新增 P2，**Phase 10** 决定去留。
3. **缓存与 MySQL 之间无最终一致性保障**（删除缓存失败时靠 TTL 兜底）→ 计划 §8 范围，本 Phase 不引入延迟双删 / binlog 订阅。
4. **列表接口不缓存** → 计划 §7 明确要求。
5. **`LOGIN_USER_TTL` 过长**（Phase 3 P1-2）→ Phase 10。
