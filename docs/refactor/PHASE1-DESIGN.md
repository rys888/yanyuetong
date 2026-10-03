# Phase 1 — Legacy Cleanup 详细设计

> 状态：**待用户批准**
> 依据：《实验资源预约平台-分阶段重构Phase计划》§4
> 基线：tag `before-lab-reservation-refactor`（commit `ba64db6`）
> 本阶段性质：**纯删除 + 保留文件的连带修改**，不引入任何新业务代码。

---

## 1. 目标

让与新业务完全无关、且不会成为后续领域模型基础的黑马点评模块彻底退出代码库：

- Redis GEO / 附近商铺
- Bitmap 用户签到
- HyperLogLog UV
- Blog / 探店笔记
- 评论
- 点赞（依赖 Blog）
- 用户社交关注 / 粉丝 / 共同关注
- Feed / ZSet 社交动态流

**保留不动**（它们是后续 Phase 的迁移参照或技术骨架）：
`Shop`、`ShopType`、`Voucher`、`SeckillVoucher`、`VoucherOrder`、
`CacheClient`、`RedisIdWorker`、`Redisson`、RabbitMQ 全链路、
登录 Session / 拦截器、`SimpleRedisLock`、`ILock`、`unlock.lua`、`seckill*.lua`。

---

## 2. 交叉依赖核验结果（决策依据）

已对全部待删符号做全仓库引用扫描，结果如下 —— **待删集合是封闭的，没有任何集合外的文件引用它们**：

| 符号 | 引用点 | 结论 |
| --- | --- | --- |
| `Blog` | Blog / BlogMapper / BlogController / IBlogService / BlogServiceImpl | 全部在删除集内 ✅ |
| `BlogComments` | BlogComments / Mapper / IBlogCommentsService / BlogCommentsServiceImpl | 全部在删除集内 ✅ |
| `Follow` | Follow / FollowMapper / IFollowService / FollowServiceImpl / **BlogServiceImpl** | 全部在删除集内 ✅ |
| `ScrollResult` | ScrollResult / BlogServiceImpl | 全部在删除集内 ✅ |
| `IFollowService` | FollowController / 自身 / FollowServiceImpl / BlogServiceImpl | 全部在删除集内 ✅ |
| `IBlogService` | BlogController / 自身 / BlogServiceImpl | 全部在删除集内 ✅ |

**唯一需要留意的两个交叉点**（已列入修改清单）：

1. `BlogServiceImpl` 同时引用 `IFollowService` 与 `IUserService` —— 三个文件同批删除，无残留。
2. `BlogCommentsController` 是**空壳**（只有一个空的 `@RestController`，无任何方法），
   与设计文档 §3.5「当前评论模块本身也是空壳」判断一致，直接删除。

---

## 3. 删除清单（17 个文件）

### 3.1 Blog / 探店笔记（5）

| # | 文件 |
| --- | --- |
| 1 | `src/main/java/com/hmdp/controller/BlogController.java` |
| 2 | `src/main/java/com/hmdp/service/IBlogService.java` |
| 3 | `src/main/java/com/hmdp/service/impl/BlogServiceImpl.java` |
| 4 | `src/main/java/com/hmdp/mapper/BlogMapper.java` |
| 5 | `src/main/java/com/hmdp/entity/Blog.java` |

### 3.2 DTO（1）

| # | 文件 | 理由 |
| --- | --- | --- |
| 6 | `src/main/java/com/hmdp/dto/ScrollResult.java` | 唯一消费方是 `BlogServiceImpl.queryBlogOfFollow`，随 Blog 一起退出 |

### 3.3 评论（5）

| # | 文件 |
| --- | --- |
| 7 | `src/main/java/com/hmdp/controller/BlogCommentsController.java`（空壳） |
| 8 | `src/main/java/com/hmdp/service/IBlogCommentsService.java` |
| 9 | `src/main/java/com/hmdp/service/impl/BlogCommentsServiceImpl.java` |
| 10 | `src/main/java/com/hmdp/mapper/BlogCommentsMapper.java` |
| 11 | `src/main/java/com/hmdp/entity/BlogComments.java` |

### 3.4 关注 / 粉丝 / 共同关注（5）

| # | 文件 |
| --- | --- |
| 12 | `src/main/java/com/hmdp/controller/FollowController.java` |
| 13 | `src/main/java/com/hmdp/service/IFollowService.java` |
| 14 | `src/main/java/com/hmdp/service/impl/FollowServiceImpl.java` |
| 15 | `src/main/java/com/hmdp/mapper/FollowMapper.java` |
| 16 | `src/main/java/com/hmdp/entity/Follow.java` |

### 3.5 测试（1）

| # | 文件 | 理由 |
| --- | --- | --- |
| 17 | `src/test/java/com/hmdp/NormalTest.java` | 全部内容是 Bitmap 位运算验证，无业务价值 |

> 删除后规模：main 72 → **56** 个 Java 文件；test 3 → **2** 个。

---

## 4. 修改清单（10 个保留文件）

### 4.1 `config/MvcConfig.java` —— 删 1 行

`excludePathPatterns` 中的 `"/blog/hot"` 是 `BlogController` 的路径，Blog 删除后必须同步移除。

**保留**：`/shop/**`、`/voucher/**`、`/shop-type/**`、`/upload/**`、`/user/code`、`/user/login`。

> ⚠️ 已知临时状态（Phase 1 有意不处理）：Shop / Voucher / ShopType 在 Phase 1 仍然免登录可访问，
> 与最终「设备查询公开、预约需登录」的鉴权模型不一致。这属于 Phase 2 / 3 / 5 的范围，
> 本阶段只做「删掉已不存在路径」这一必要修改。

### 4.2 `utils/RedisConstants.java` —— 删 4 个常量

```java
public static final String BLOG_LIKED_KEY = "blog:liked:";   // 删
public static final String FEED_KEY       = "feed:";         // 删
public static final String SHOP_GEO_KEY   = "shop:geo:";     // 删
public static final String USER_SIGN_KEY  = "sign:";         // 删
```

**保留**：`LOGIN_CODE_KEY`、`LOGIN_CODE_TTL`、`LOGIN_USER_KEY`、`LOGIN_USER_TTL`、
`CACHE_NULL_TTL`、`CACHE_SHOP_TTL`、`CACHE_SHOP_KEY`、`LOCK_SHOP_KEY`、`LOCK_SHOP_TTL`、`SECKILL_STOCK_KEY`。

理由：这四个常量的**唯一**消费方就是本阶段删除的模块（已 grep 验证）；
`CACHE_SHOP_KEY` / `LOCK_SHOP_KEY` 仍被 `ShopServiceImpl` / `CacheClient` 使用，
要等到 Phase 4 迁移到 Equipment 缓存时才处理。

### 4.3 `service/IShopService.java` —— 改方法签名

```java
// 改前
Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
// 改后
Result queryShopByType(Integer typeId, Integer current);
```

### 4.4 `service/impl/ShopServiceImpl.java` —— 删 GEO 分支

- 删除整个 `queryShopByType` 的 GEO 逻辑（现文件第 80–133 行），
  改为只保留「无坐标按数据库分页查询」这一条路径
- 删除以下 **6 个 GEO 相关 import**：
  `org.springframework.data.geo.Distance`、`org.springframework.data.geo.GeoResult`、
  `org.springframework.data.geo.GeoResults`、`org.springframework.data.redis.connection.RedisGeoCommands`、
  `org.springframework.data.redis.domain.geo.GeoReference`、`cn.hutool.core.util.StrUtil`
- 删除 `import java.util.*;`（通配导入的 `List`/`Map`/`ArrayList`/`HashMap`/`Collections` 全部只被 GEO 分支使用）
- **不动**：`queryById`（含 `cacheClient.queryWithPassThrough` 及被注释的两套击穿方案）、`update`（更新 DB + 删缓存）

> 注：`queryById` 中那两段「互斥锁 / 逻辑过期」注释是 Phase 4 的选型依据，**本阶段保留**。

### 4.5 `controller/ShopController.java` —— 删 2 个参数

`queryShopByType` 去掉 `@RequestParam(value = "x", ...)` 与 `@RequestParam(value = "y", ...)`，
调用改为 `shopService.queryShopByType(typeId, current)`。
其余接口（`queryShopById` / `saveShop` / `updateShop` / `queryShopByName`）不动。

### 4.6 `entity/Shop.java` —— 删 1 个字段

删除仅用于 GEO 结果回填的传输字段：

```java
@TableField(exist = false)
private Double distance;   // 删
```

连带删除 `com.baomidou.mybatisplus.annotation.TableField` import（该类中不再使用）。
**保留** `x` / `y` 经纬度列本身（属于数据库表结构，Phase 2 重新设计 Equipment 时统一处理）。

### 4.7 `service/IUserService.java` —— 删 2 个方法声明

删除 `Result sign();` 与 `Result signCount();`。

### 4.8 `service/impl/UserServiceImpl.java` —— 删 2 个方法 + 6 处 import

- 删除 `sign()`（现第 111–125 行）与 `signCount()`（现第 127–167 行）
- 删除因此变为未使用的 import：
  `org.springframework.data.redis.connection.BitFieldSubCommands`、`com.hmdp.utils.UserHolder`、
  `java.time.LocalDateTime`、`java.time.format.DateTimeFormatter`、`java.util.List`
  （已逐一确认：这些符号在 `sendCode` / `login` / `createUserWithPhone` 中均无使用）
- **不动**：`sendCode`、`login`、`createUserWithPhone` 与 Redis Session 写入逻辑（Phase 3 才重构）

### 4.9 `controller/UserController.java` —— 删 2 个接口

删除 `@PostMapping("/sign")` 与 `@GetMapping("/sign/count")` 两个方法。
**保留** `code` / `login` / `logout`(仍返回「未完成」，Phase 3 实现) / `me` / `info` / `{id}`。
**保留** `IUserInfoService` 注入与 `/info/{id}` 接口（Phase 3 处理）。

### 4.10 `src/test/java/com/hmdp/HmDianPingApplicationTests.java` —— 删 2 个测试

- 删除 `loadShopData()`（GEO 初始化）与 `testHyperLogLog()`
- 删除因此未使用的 import：
  `org.springframework.data.geo.Point`、`org.springframework.data.redis.connection.RedisGeoCommands`、
  `java.util.ArrayList`、`java.util.List`、`java.util.Map`、`java.util.stream.Collectors`、
  `static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY`
- **保留** `testIdWorker()` 与 `testSaveShop()`

> `RedissonTest.java` 整体保留（Redisson 是后续 Phase 的核心技术之一）。

---

## 5. 明确不做的事（边界）

| 项 | 为什么不做 |
| --- | --- |
| 删除数据库表 `tb_blog` / `tb_blog_comments` / `tb_follow` | 用户已确认「只删代码」；DB 清理属 Phase 10。**不产出 DROP TABLE 脚本** |
| 修改 `src/main/resources/db/hmdp.sql` | 同上 |
| 删除 `UploadController` 与 `SystemConstants.IMAGE_UPLOAD_DIR` | 属图片上传策略，Phase 10 统一决定（设备图片将改为 URL 方式） |
| 改动 `VoucherOrderServiceImpl` / `RabbitMQConfig` / `seckill.lua` / `seckillRollback.lua` | 秒杀主链是 Phase 5–8 的迁移对象，Phase 1 一行不动 |
| 改动 `ShopType` / `Voucher` / `SeckillVoucher` / `VoucherOrder` 实体 | Phase 2 / 5 的迁移参照 |
| 实现 `logout` / 引入角色权限 | Phase 3 |
| 改 Redis 连接配置 / MySQL 凭据 / 明文密码 | Phase 10 |
| 清理 `VoucherOrderServiceImpl` 里四套历史实现的注释 | Phase 10（本阶段保持可编译即可） |
| 新增任何业务代码、任何枚举、任何表 | 本阶段是纯清理 |

---

## 6. 验收标准（可验证）

### 6.1 编译（硬性）

```bash
JAVA8=/Users/eason/Library/Java/JavaVirtualMachines/corretto-1.8.0_504/Contents/Home
JAVA_HOME=$JAVA8 mvn clean test-compile
```

必须 **BUILD SUCCESS**。
（用 `test-compile` 而非 `compile`，因为本阶段修改了测试文件，必须连带验证测试源码可编译。）

### 6.2 残留扫描（必须全部无匹配）

| 检索式 | 期望 |
| --- | --- |
| `Blog\|Follow\|ScrollResult`（`*.java`） | 0 匹配 |
| `SHOP_GEO_KEY\|USER_SIGN_KEY\|BLOG_LIKED_KEY\|FEED_KEY` | 0 匹配 |
| `opsForGeo\|GeoResults\|GeoReference\|GeoSearchCommandArgs` | 0 匹配 |
| `opsForHyperLogLog\|HyperLogLog` | 0 匹配 |
| `BitFieldSubCommands\|opsForValue().setBit` | 0 匹配 |
| `signCount\|/sign/count` | 0 匹配 |
| `/blog`、`/blog/hot`、`/blog-comments`、`/follow`（路径与白名单） | 0 匹配 |
| `\bfollows:`（Redis key 字面量） | 0 匹配 |

### 6.3 主链保持完好（人工核对）

以下符号必须仍然存在且未被改动：
`RabbitMQConfig.SECKILL_ORDER_*`、`VoucherOrderServiceImpl.onOrderMessage`、
`VoucherOrderServiceImpl.seckillVoucher`、`seckill.lua`、`seckillRollback.lua`、`unlock.lua`、
`CacheClient`、`RedisIdWorker`、`RefreshTokenInterceptor`、`LoginInterceptor`、`SimpleRedisLock`。

### 6.4 运行时验证

**本阶段不做**。原因：本机 MySQL / Redis 凭据未知（见 `PHASE0-BASELINE.md` §7），
且计划文档对 Phase 1 的验收只要求 Maven 编译成功。
`HmDianPingApplicationTests` 是 `@SpringBootTest`，在凭据补齐前无法通过运行。

---

## 7. 风险与对策

| 风险 | 等级 | 对策 |
| --- | --- | --- |
| 删除 `BlogServiceImpl` 时漏掉它对 `IFollowService` 的引用，导致编译失败 | 低 | 三者同批删除；验收用 `test-compile` 兜底 |
| `MvcConfig` 白名单残留 `/blog/hot`，运行时产生死路径 | 中 | 已列入 6.2 残留扫描 |
| 误删 `CACHE_SHOP_KEY` / `LOCK_SHOP_KEY`，破坏 Phase 4 的缓存参照 | 中 | 4.2 明确列出「保留」清单 |
| 顺手删除 `queryById` 中两套被注释的击穿方案 | 中 | 4.4 明确要求保留（Phase 4 选型依据） |
| 顺手改动秒杀 / MQ 主链 | 中 | 「不做的事」表格明确禁止；Code Review 逐行核对 diff |
| 删除 `java.util.*` 通配 import 后漏掉其他使用点 | 低 | 编译即可暴露（找不到符号） |
| 遗留的 `upload/blog` 接口带 blog 语义 | 低 | 记录为已知遗留，Phase 10 处理 |

---

## 8. 执行方式

严格按 `codex-implementer` 闭环执行：

1. 用户批准本设计
2. Claude 生成 Implementation Packet → 派发 Codex（`gpt-5.6-sol` / `effort=high`）
3. Codex 完成删除与修改，自行运行 `mvn clean test-compile`
4. Claude 复查 `git diff`、逐条执行 §6.2 残留扫描、核对 §6.3 主链完好性
5. Claude 输出 Review 结论；FAIL 则回炉，PASS 则报用户并**停下来等待批准进入 Phase 2**

---

## 9. 完成标志

```
PHASE 1 — PASS
```

（需 Claude Review 通过，且用户批准后才进入 Phase 2）
