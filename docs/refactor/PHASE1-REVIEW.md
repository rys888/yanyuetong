# Phase 1 Review

> 审查人：Claude（Control Plane）
> 审查时间：2026-10-03
> 被审对象：Codex 执行的 Phase 1 Legacy Cleanup
> 依据：`docs/refactor/PHASE1-DESIGN.md` §6 验收标准
> 审查方式：**不采信 Codex 自述**，独立执行编译、残留扫描、逐行 diff 核对

---

## Result

```
PASS
```

---

## 1. 变更规模（实测）

```
$ git status --short | wc -l
27          → 17 个 D（删除）+ 10 个 M（修改），另 1 个未跟踪设计文档

$ git diff --stat
27 files changed, 9 insertions(+), 1080 deletions(-)
```

9 行新增全部来自「保留代码的连带修改」，**没有任何新业务逻辑**，符合「纯删除阶段」定性。

---

## 2. 验收标准逐条核对

### 2.1 编译（硬性）—— ✅ PASS

独立执行（非采信 Codex 报告）：

```
$ JAVA_HOME=/Users/eason/Library/Java/JavaVirtualMachines/corretto-1.8.0_504/Contents/Home mvn clean test-compile
[INFO] Compiling 56 source files to .../target/classes
[INFO] Compiling 2 source files to .../target/test-classes
[INFO] BUILD SUCCESS
[INFO] Total time:  1.722 s
```

- main 源文件：72 → **56**（删除 16 个 main 文件）
- test 源文件：3 → **2**（删除 `NormalTest.java`）
- 与设计 §3 预期的「72 → 56」完全一致

### 2.2 删除完整性 —— ✅ PASS（17/17）

17 个目标文件全部确认不存在，无一遗漏、无一误删：

| 模块 | 文件数 | 状态 |
| --- | --- | --- |
| Blog / 探店笔记 | 5 | ✅ 全部删除 |
| `ScrollResult` DTO | 1 | ✅ 删除 |
| 评论 | 5 | ✅ 全部删除 |
| 关注 / 粉丝 / 共同关注 | 5 | ✅ 全部删除 |
| `NormalTest` | 1 | ✅ 删除 |

### 2.3 残留扫描 —— ✅ PASS（含 1 处己方规格缺陷，见 §4）

| 检索式 | 期望 | 实测 | 结论 |
| --- | --- | --- | --- |
| `Blog\|Follow\|ScrollResult`（`*.java`） | 0 | **1** | ⚠️ 见 §4，**非 Codex 责任** |
| `SHOP_GEO_KEY\|USER_SIGN_KEY\|BLOG_LIKED_KEY\|FEED_KEY` | 0 | 0 | ✅ |
| `opsForGeo\|GeoResults\|GeoReference\|GeoSearchCommandArgs` | 0 | 0 | ✅ |
| `opsForHyperLogLog\|HyperLogLog` | 0 | 0 | ✅ |
| `BitFieldSubCommands\|setBit` | 0 | 0 | ✅ |
| `signCount\|/sign/count` | 0 | 0 | ✅ |
| `/blog/hot\|/blog-comments\|/follow` | 0 | 0 | ✅ |
| `\bfollows:` | 0 | 0 | ✅ |

### 2.4 主链完好性 —— ✅ PASS（11/11 零改动）

对「禁止修改清单」逐一执行 `git diff --quiet`，**全部返回未改动**：

| 文件 | 状态 |
| --- | --- |
| `VoucherOrderServiceImpl.java` | ✅ 未改动（`onOrderMessage` / `seckillVoucher` 均在，关键符号命中 10 处） |
| `RabbitMQConfig.java` | ✅ 未改动（`SECKILL_ORDER_*` 6 个常量完整） |
| `seckill.lua` / `seckillRollback.lua` / `unlock.lua` | ✅ 未改动 |
| `CacheClient.java` | ✅ 未改动 |
| `RedisIdWorker.java` | ✅ 未改动 |
| `SimpleRedisLock.java` | ✅ 未改动 |
| `RefreshTokenInterceptor.java` / `LoginInterceptor.java` | ✅ 未改动 |
| `RedissonConfig.java` / `application.yaml` / `db/hmdp.sql` | ✅ 未改动 |

### 2.5 逐行 diff 核对 —— ✅ PASS（10/10）

对全部 10 个修改文件的完整 diff 逐行阅读，确认每一处改动都与设计对应、且无夹带：

| 文件 | 设计预期 | 实测 | 结论 |
| --- | --- | --- | --- |
| `MvcConfig.java` | 删 `/blog/hot` 1 行 | 恰好删 1 行，其余白名单保留 | ✅ |
| `ShopController.java` | 删 `x`/`y` 两参数 | 恰好删 2 参数 + 改调用签名 | ✅ |
| `UserController.java` | 删 `sign` / `sign/count` | 两方法移除，`logout`（返回"未完成"）保留 | ✅ |
| `Shop.java` | 删 `distance` 字段 | 字段 + `TableField` import 移除，`x`/`y` 保留 | ✅ |
| `IShopService.java` | 改签名 | 与 `ShopServiceImpl` 实现一致 | ✅ |
| `IUserService.java` | 删 2 个声明 | 恰好 2 个 | ✅ |
| `RedisConstants.java` | 删 4 个常量 | 恰好 4 个；`CACHE_SHOP_KEY`/`LOCK_SHOP_KEY` 等 10 个保留 | ✅ |
| `ShopServiceImpl.java` | 删 GEO 分支 + 6 import + `java.util.*` | 完全一致 | ✅ |
| `UserServiceImpl.java` | 删 2 方法 + 5 import | 完全一致 | ✅ |
| `HmDianPingApplicationTests.java` | 删 2 测试 + 7 import | 完全一致，`testIdWorker`/`testSaveShop` 保留 | ✅ |

### 2.6 两项关键「不许动」的深度核验 —— ✅ PASS

这是本阶段最容易出错的点，已单独确认：

1. **`ShopServiceImpl.queryById` 内部两套被注释的击穿方案完整保留**
   （`queryWithMutex` 与 `queryWithLogicalExpire` 注释块均在）—— 它们是 **Phase 4 缓存方案选型的依据**，
   若被顺手删除，Phase 4 将失去「三方案对比」的教学价值与设计参照。
2. **`UserServiceImpl` 的 `sendCode` / `login` / `createUserWithPhone` 与 Redis Session 写入逻辑零改动** ——
   登录链路是 Phase 3 的重构对象，本阶段必须原样保留。

---

## 3. 越界检查 —— ✅ PASS

`git diff --name-only` 对禁止清单做过滤，**零命中**。
无配置改动、无 Lua 改动、无 MQ 改动、无数据库脚本改动、无新增业务代码。

---

## 4. 己方规格缺陷声明（重要）

设计文档 §6.2 要求 `Blog|Follow|ScrollResult` 在 `*.java` 中 **0 匹配**，实测为 **1 匹配**。

**Codex 未达标，但责任在我，不在 Codex。** 明细：

```
src/main/java/com/hmdp/controller/UploadController.java:38
    public Result deleteBlogImg(@RequestParam("name") String filename)
```

该符号位于 `UploadController`，而**我自己的设计 §5「明确不做的事」第 3 行明令禁止修改该文件**
（连同 `SystemConstants.IMAGE_UPLOAD_DIR` 一并推迟到 Phase 10）。

即：**验收标准与修改边界自相矛盾**。Codex 选择了「遵守边界、如实上报未达标」，
而不是「为了让指标变绿而越界改动禁止文件」——这是正确判断，应予肯定。

同一文件另有 2 处小写路径残留（`/blog/delete`、`/blogs/{}/{}`），性质相同：
带 blog 语义的图片上传接口，已在设计 §7 风险表中登记为「已知遗留，Phase 10 处理」。

**结论：不返工。** 建议 Phase 10 将 `UploadController` 改造为设备图片上传（URL 方式）。

---

## 5. 遗留事项（不阻塞 Phase 1）

| # | 事项 | 归属 |
| --- | --- | --- |
| 1 | `UploadController` 的 `deleteBlogImg` / `/blog/delete` / `/blogs/` 语义残留 | Phase 10 |
| 2 | DB 中 `tb_blog` / `tb_blog_comments` / `tb_follow` 三表未删（用户已确认「只删代码」） | Phase 10 |
| 3 | `db/hmdp.sql` 未同步 | Phase 10 |
| 4 | `MvcConfig` 中 `/shop/**`、`/voucher/**` 仍免登录，与最终鉴权模型不符 | Phase 2 / 3 / 5 |
| 5 | `VoucherOrderServiceImpl` 内四套历史实现的注释块仍在 | Phase 10 |
| 6 | 本机 MySQL / Redis / RabbitMQ 管理凭据未知，运行时验证仍不可用 | 用户提供 |

> 第 6 项自 Phase 4 起将成为硬阻塞（缓存迁移需要真实 Redis 验证）。

---

## 6. 与 Phase 计划 §18 格式的对照

```
# Phase 1 Review

## Result

PASS
```

无 P0 / P1 / P2 缺陷，故不输出「Codex 最小修复指令」。

---

## 7. 建议的 Git 提交

依计划 §15：

```
refactor: remove legacy social and geo modules
```

范围：17 个删除 + 10 个修改 + `docs/refactor/PHASE1-DESIGN.md`。

---

## 8. 结论

**Phase 1 — PASS。**

代码库已从「黑马点评社交版」剥离全部与实验资源预约无关的领域模块，
同时完整保留了秒杀/MQ/Lua 技术主链作为 Phase 5–8 的迁移基础。

**停下来等待用户批准后方可进入 Phase 2。**
