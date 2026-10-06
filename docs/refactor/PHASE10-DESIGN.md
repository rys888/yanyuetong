# Phase 10 技术设计 — 最终清理与交付（Final Cleanup & Delivery）

> 本文件是 Phase 10 的阶段一–四产出（Claude/Control Plane）。阶段五起由执行层落地。
> 上游依据：`实验资源预约平台-分阶段重构Phase计划.md` §13（Phase 10 原始定义）；
> 以及各 Phase 的 Review/Design 中 "归 Phase 10" 的登记项（§2.5 汇总，共 29 条台账 + 若干）。

---

## 阶段一：需求分析

### Goal

把已经重构完成的「高校实验资源预约平台」整理成**可以直接公开的完整项目**：

1. 仓库不再呈现黑马点评（教程）的业务语义与命名痕迹；
2. 工作区无明文凭据；
3. README 完整；
4. `mvn clean compile` / `mvn test` 全绿；
5. 一次覆盖全项目的最终审计（P0 清零、P1 全解、P2 合理处理）；
6. 收尾含一次运行环境残留检查，而不是只看 Maven 退出码。

### Expected Behavior（可判定的完成态）

| # | 判据 | 判定方式 |
|---|---|---|
| E1 | 生产代码中 `com.hmdp` / `hm-dianping` / `HmDianPing` 出现次数 = **0** | `grep -rn` 计数 |
| E2 | §10.1 关键词在黑马语义下的残留 = **0**（`tb_` 前缀与库名除外，见 §3.12） | 逐个词判定，非计数 |
| E3 | 结构体/实体/接口层的教程残留（`UserInfo`、blog 上传、虎哥注释、无效测试）= **0** | 文件不存在 + grep |
| E4 | 跟踪文件中无明文口令 | 正则扫描（含 `change_me` 白名单） |
| E5 | 根目录只有 `README.md` / `pom.xml` / `src` / `docs` / `.gitignore` / `.claude` / `target`（构建产物） | `ls -A` |
| E6 | README 含 §10.5 要求的 15 个章节 | 章节清单核对 |
| E7 | `mvn clean compile` 与 `mvn test` 全绿 | 退出码 + surefire `Results:` |
| E8 | 收尾时 4 张预约表 0 行、种子数据未损、Redis/RabbitMQ 无遗留键与消息 | 直连实测 |

### Scope（Phase 10 做的）

- **S1 残留业务清除**：blog 上传接口、`UserInfo` 四件套与 `/user/info/{id}`、`/user/{id}`、死代码（`SimpleRedisLock`/`ILock`/`PasswordEncoder`/`LoginFormDTO.password`/死常量）、`@author 虎哥` 注释、2 个无断言测试。
- **S2 命名迁移**：Java 包名、主类名、pom 坐标、配置前缀与应用名、mapper XML 命名空间。
- **S3 数据库**：`db/hmdp.sql` 精简为仅存表的基线；新增幂等 DROP 脚本（用户执行）；Phase 2 种子 INSERT 幂等化。
- **S4 凭据**：清工作区 3 处明文；历史登记为已知边界（用户裁决，不改写）。
- **S5 P1 修复**：F4（删实验室不查依赖）、F3（`admin_user_id` 外泄）、F-C（`listStale` 饥饿）、P2-25（全局兜底 HTTP 状态）。
- **S6 P2 修复（"合理范围内"）**：#22 发布失败文案分层次、#17 缓存测试判据自证、#26 分页用例夹具加固。
- **S7 文档**：4 份根目录文档归位、README 从零撰写、`MIGRATION_STATUS.md` 收口为 PROJECT COMPLETE。
- **S8 最终验收**：见阶段三 §3.13 的验收清单（含判别力实验与残留检查）。

### Non-goals（Phase 10 明确不做）

- **不改写 git 历史**（用户 2026-10-06 裁决）；不动任何提交 SHA。
- **不改库名 `hmdp`**（用户裁决的选项文本已含此边界）、**不去掉 `tb_` 表名前缀**（见 §3.12 理由）。
- **不 push**（长期约束；`origin` 是他人课程仓库）。
- **P0 之外不做新功能**：不补设备图片上传、不加管理端前端、不引入新中间件。
- **不重构已验收的并发/一致性实现**：`reservation.lua`、`reservationRollback.lua`、取消定序、`active_flag` 生成列一律零接触。
- 不做 Phase 9 Review §8 中已明确"留待将来"的加固项（除本文件列举的 P1/P2）。

### Constraints

| 来源 | 约束 |
|---|---|
| 长期 | JDK 8；不过度设计（禁微服务/Cloud/K8s/ES/Kafka/RocketMQ/Seata/XXL-JOB/Sentinel/Nacos/sharding/大型 RBAC/AI 推荐/支付/社交）；不机械删除技术概念；不改变业务方向；每个 Phase 独立提交；未经允许不提前实现后续 Phase |
| 用户 2026-10-06 裁决 | ① 重新命名到 Java 包名一级（彻底）② 只清工作区凭据、**历史不动** ③ 死表由**我直接连库 DROP** ④ 4 份根目录文档全部移入 `docs/` |
| 分工 | Claude = Control Plane（设计/审查/文档/DB 操作/验收）；Codex = Execution Plane（业务代码、测试、脚本、migration、实现相关配置） |
| 交付 | 只本地 commit，不 push |

### Potential Risks

| 风险 | 等级 | 处置 |
|---|---|---|
| 包名重命名（124 个 Java 文件）漏改引用 → 编译期暴露 | 低 | 编译 + 154 测试为兜底；`grep com.hmdp` 计数须为 0 |
| 漏改**字符串形式**的包名（`"com.hmdp.mapper.EquipmentMapper.selectById"`）→ **编译不报错、测试静默失效** | **高** | 单独登记为 T2 的显式检查项；`grep '"com\.hmdp'` 必须为 0，并逐个判定语义 |
| 删 `UserInfo` 后 `UserController` 注入缺失 → 启动失败 | 低 | 编译期/上下文启动即暴露 |
| `hmdp.sql` 精简后误导"缺表" | 中 | 在脚本头部写明"本文件只含仍在用的 `tb_user`；历史表 DDL 已删，见 `phase10_drop_legacy_tables.sql` 的注释" |
| DROP 死表不可逆，且用户库里 `tb_shop` 14 行 / `tb_blog` 4 行 / `tb_voucher` 1 行会一并消失 | 中 | 用户已明确授权；**执行前先备份这三张表的行数证据**，并逐表确认无代码/外键引用 |
| 包名重命名导致 `target/` 旧 class 残留 → 假绿 | 中 | 验收一律 `mvn clean compile`，不用增量构建 |
| P1 修复引入回归（F4 加查询会改变 `DELETE /laboratory/{id}` 的既有行为） | 中 | 单独 Task、单独测试；F4 的契约变更写入 README 已知边界 |
| 大 diff 下 Review 只能靠判据，易漏 | 中 | 每项判据必须**先自证**（对已知为"否"的样本返回"否"） |

---

## 阶段二：源码分析

### 2.1 残留盘点（实测，行号为 HEAD `b917535` 时点）

**A. 教程业务残留（生产代码）**

| 位置 | 内容 | 判定 |
|---|---|---|
| `controller/UploadController.java`（整文件 63 行） | `POST /upload/blog`、`GET /upload/blog/delete`、落盘 `/blogs/{d1}/{d2}/`、`deleteBlogImg` | 黑马 blog 上传，删 |
| `config/MvcConfig.java:24` | `/upload/**` 在**免登录白名单**里 | 随上传接口删 |
| `utils/SystemConstants.java:4` | `IMAGE_UPLOAD_DIR = "D:\lesson\nginx-1.18.0\html\hmdp\imgs\"` | §10.3 点名的 Windows 固定路径，删 |
| `utils/SystemConstants.java:7` | `MAX_PAGE_SIZE = 10` 已无引用（Phase 2-REVIEW 登记） | 删 |
| `entity/UserInfo.java`、`mapper/UserInfoMapper.java`、`service/IUserInfoService.java`、`service/impl/UserInfoServiceImpl.java` | 城市/介绍/粉丝/关注/积分/会员级别 | 点评式用户域，删 |
| `controller/UserController.java:74-86` | `GET /user/info/{id}` | 同上，删。实测 `tb_user_info` **0 行**，接口对所有人恒返回空 `Result.ok()` |
| `controller/UserController.java:88-98` | `GET /user/{id}` | 无任何业务流与测试调用方（已 grep 确认），删 |
| `dto/LoginFormDTO.java:8` | `private String password;` | `tb_user.password` 从不校验（MIGRATION_STATUS #13），删字段 |
| `utils/SimpleRedisLock.java` + `utils/ILock.java` | `ILock` 唯一引用者就是 `SimpleRedisLock` | Redisson 已取代（Phase 3），双双零引用，删 |
| `utils/PasswordEncoder.java` | 0 处引用；登录走手机验证码，从不校验密码 | 删 |
| 11 处 `@author 虎哥` | `User`/`UserInfo`/`UserMapper`/`UserInfoMapper`/`RegexPatterns`/`RegexUtils` 等 | 教程注释（§10.2），删 |

**B. 无效测试（§10.2）**

| 位置 | 内容 | 判定 |
|---|---|---|
| `test/HmDianPingApplicationTests.java` | `testIdWorker`：300×100 次取 id 后 `System.out.println`，**零断言** | 删（类名本身也是遗留） |
| `test/RedissonTest.java` | `method1/method2`：可重入锁 demo，**零断言** | 删 |

**C. 保留判定（"不能机械删除技术概念"）**

| 位置 | 内容 | 判定与理由 |
|---|---|---|
| `utils/CacheClient.java` | 三套缓存工具：`queryWithMutex`（生产在用）、`queryWithPassThrough`、`queryWithLogicalExpire` | **保留三套**。后两者是"缓存穿透防御"与"逻辑过期热点重建"的**具名技术概念的完整实现**，属 §10.2 明令不得机械删除的范畴。见 §3.3 |
| `utils/RedisData.java` | `queryWithLogicalExpire` 的载体 | 同上，保留 |
| `entity/User.java` 的 `password` 字段 / `tb_user.password` 列 | 未被使用 | **保留列**（DDL 变更无收益且有风险），只删 DTO 上的入参字段。README 说明管理员走验证码登录 |
| `utils/RedisIdWorker.java` | 雪花式 id 生成器，生产在 `ReservationServiceImpl` 使用 | 保留 |

### 2.2 凭据盘点（实测）

**工作区（HEAD，跟踪文件）**——3 处：

| 位置 | 形态 |
|---|---|
| `docs/refactor/PHASE0-BASELINE.md:226` | 把本机 MySQL/Redis 口令作为"问题清单第 9 条"的**证据**抄了进去 |
| `docs/refactor/PHASE2-DESIGN.md:400` | 安全审计表 B4 行同上 |
| `秒杀消息队列迁移RabbitMQ-交付报告.md:78`（另第 12 行叙述中也有） | 报告里贴了当时的 `application.yaml` 片段 |

**配置本身已达标**：`application.yaml` 三处口令均为 `change_me` 占位；`spring.profiles.active: local`；真值在 `src/main/resources/application-local.yaml`，该文件 **未跟踪**且被 `.gitignore:37` 覆盖（`git check-ignore` 已验），仓库内只有 `.example` 模板。`RedissonConfig` 已改为 `@Value` 注入，无硬编码 IP/口令（Phase 2 已完成）。

**历史**：逐提交扫描（49 个提交全量）确认**每一个提交都含明文口令**——源头是原始课程提交（`63d6575 Initial commit` 起）里 `application.yaml` 就是明文，加上后来的三份文档。**用户裁决：不改写历史，登记为已知边界。**

### 2.3 命名盘点

| 对象 | 现值 | 说明 |
|---|---|---|
| Java 包 | `com.hmdp`（106 main + 18 test = 124 文件） | 最显眼的教程痕迹 |
| 主类 | `HmDianPingApplication` | |
| pom | `groupId com.hmdp` / `artifactId hm-dianping` / `name hm-dianping` / `description "Demo project for Spring Boot"` | |
| 配置 | `spring.application.name: hmdp`；`type-aliases-package: com.hmdp.entity`；`logging.level.com.hmdp: debug`；配置前缀 `hmdp.reservation.*`（4 个键，散落在 3 个 `@Value` + 1 个 `@Scheduled`） | |
| mapper XML | 3 个 `namespace="com.hmdp.mapper.*"` | |
| **字符串形式的包名** | `@MapperScan("com.hmdp.mapper")`；`EquipmentCacheTest:235` 的 `"com.hmdp.mapper.EquipmentMapper.selectById"` | 后者**编译不报错**，最易漏 |
| 库名 | JDBC `.../hmdp?` | 用户裁决：**保持** |
| 表前缀 | `tb_`（全部表） | 见 §3.12，**保留** |

**选定目标名（与用户已建 GitHub 仓库 `rys888/yanyuetong` 一致）**：

```
中文名   研约通（高校实验资源预约平台）
英文名   YanYueTong
包名     com.yanyuetong
主类     YanYueTongApplication
坐标     com.yanyuetong : yanyuetong : 0.0.1-SNAPSHOT
应用名   spring.application.name: yanyuetong
配置前缀 yanyuetong.reservation.*
```

命名理由（对应需求文档 §6.1 要求"避免 AI 命名感"）：取自"研"（实验/科研）+"约"（预约），是**功能直述**而非抽象词拼接；与既有仓库名一致，避免二次改名。

### 2.4 测试覆盖 vs §10.4 矩阵（结论：**无缺口**）

现有 18 个测试类 / **154 个用例**，逐项对照 §10.4：

| §10.4 要求 | 覆盖用例 | 状态 |
|---|---|---|
| Cache Hit | `EquipmentCacheTest.shouldUseCacheAfterFirstDatabaseQueryAndCacheMissingEquipment` | ✅ |
| Cache Miss | 同上（首查回源） | ✅ |
| Cache Penetration | `CacheClientMutexTest.shouldCacheNullValueAndAvoidRepeatedFallback` | ✅ |
| 正常预约 | `ReservationMqTest.shouldSubmitAndEventuallyPersistReturnedReservationId` | ✅ |
| quota = 0 | `ReservationQuotaExecutorTest.shouldReturnSoldOutWithoutChangingQuotaOrUsers` + `ReservationPersistenceTest.shouldNotInsertReservationWhenQuotaIsExhausted` | ✅ |
| 重复预约 | `ReservationSubmitTest.shouldRejectDuplicateReservationAndOnlyDeductOnce` | ✅ |
| 并发预约 | `ReservationBatchLifecycleTest.shouldPublishExactlyOnceAndPreserveQuotaUnderConcurrency`、`ReservationSubmitTest.shouldPreserveCapacityInvariantForDifferentConcurrentUsers` / `shouldPreserveDuplicateInvariantForSameConcurrentUser`、`ReservationPersistenceTest.shouldNeverMakeRemainingQuotaNegativeUnderConcurrentPersist` | ✅ |
| 取消 | `ReservationLifecycleTest.shouldCancelExactlyOnceAcrossMysqlAndRedis` | ✅ |
| 重复取消 | 同上（`times(1)` / `never()` 调用次数断言）+ `shouldExposeLifecycleEndpointsWithLoginAndManagerRoleGuards` | ✅ |
| MQ 正常消费 | `shouldSubmitAndEventuallyPersistReturnedReservationId` | ✅ |
| MQ 重复消息 | `shouldTreatRepeatedMessageAsDuplicateWithoutSecondQuotaDeduction` 等 3 条 | ✅ |
| MQ Consumer 异常 | `shouldDeadLetterInvalidJsonAndContinueConsuming`、`shouldKeepRecordingDeadLettersAfterUnparseableOne`、`shouldKeepConsumingAfterLedgerWriteFailure` | ✅ |
| DLQ | `ReservationDlqGovernanceTest` ×10 + `shouldDeadLetterPrimaryKeyConflictAndRollbackQuotaDeduction` | ✅ |
| 重放 | `shouldReplayValidPayloadAndMarkLedgerReplayed` | ✅ |
| Confirm NACK | `ReservationPublishFailureTest.shouldCompensateWhenConfirmIsNack` | ✅ |
| Confirm Timeout | `shouldKeepQuotaAndMarkUnknownWhenConfirmTimesOut` | ✅ |
| 补偿 | `ReservationReconcileTest.shouldCompensateMissingReservationOnceAcrossRepeatedRuns` 等 | ✅ |
| 对账 | `ReservationReconcileTest` ×8 + `ReservationFulfillmentReconcileTest` ×4 | ✅ |

⇒ **Phase 10 不需要为 §10.4 补新用例**；测试域的工作只有"删 2 个无效测试 + 3 处判据加固"（§3.10）。

### 2.5 各 Phase 登记在案的 Phase 10 待办（权威台账）

来源：逐条 grep `docs/refactor/*.md` 与 `MIGRATION_STATUS.md` 中的 "Phase 10"。归并后 29 条：

| # | 事项 | 来源 | 本设计处置 |
|---|---|---|---|
| 1 | 死表 `tb_blog`/`tb_blog_comments`/`tb_follow` 未删（Phase 1「只删代码」裁决） | P1-DESIGN:203 / P1-REVIEW:156 | §3.4 DROP |
| 2 | `UploadController` + `IMAGE_UPLOAD_DIR` | P1-DESIGN:205 / P1-REVIEW:147 | §3.2 删 |
| 3 | `db/hmdp.sql` 未同步（含 voucher/seckill 建表） | P1-REVIEW:157 / P7-REVIEW:279 | §3.4 精简 |
| 4 | 明文口令 / 凭据 | P1-DESIGN:209 / P2-DESIGN:421 | §3.5 |
| 5 | 四套历史秒杀实现注释 | P1-DESIGN:210 / P0-BASELINE:239 | 已在 Phase 5.0 随文件删除消失（P5-REVIEW:393）→ 本轮只做复核 |
| 6 | `UserInfo` 四件套 + `/user/info/{id}` + `/user/{id}` | P3-DESIGN:213/269/333 | §3.2 删 |
| 7 | `SystemConstants.MAX_PAGE_SIZE` 死常量 | P2-REVIEW:191 | §3.2 删 |
| 8 | 迁移脚本规范（DDL 幂等） | P2-REVIEW:190 | §3.4 |
| 9 | `queryWithPassThrough`/`queryWithLogicalExpire` 去留 | P4-DESIGN:383 / P4-REVIEW:231 | §3.3 **保留** |
| 10 | `LOGIN_USER_TTL` 过长 | P3-REVIEW:201 / P4-DESIGN:386 | §3.11 修 |
| 11 | 种子管理员无密码，README 需说明取权方式 | P3-REVIEW:211 | §3.11 README |
| 12 | `CacheClientMutexTest` T1 夹具过度复杂 | P4-REVIEW:229 | §3.10 **保留**并登记理由 |
| 13 | 缓存删除失败无补偿 / 极端竞争惊群 | P8-DESIGN:591 | §3.12 登记为已知边界 |
| 14 | 设备缓存删除失败无补偿（P2-18） | P8-DESIGN:591 | 同上 |
| 15 | F-C `listStale` 饥饿 | P8-REVIEW:179 / P9-DESIGN:45 | §3.9 **修** |
| 16 | P2-25 全局兜底无 HTTP 状态 | P6-REVIEW:316 | §3.8 **修** |
| 17 | P2-26 分页用例隐含"夹具在第 1 页" | P6-REVIEW:317 | §3.10 加固 |
| 18 | P2-9 `EquipmentMapper.xml` 前导通配符无法走索引 | MS #11 | §3.12 登记 |
| 19 | P2-12 Phase 2 种子 INSERT 不幂等 | MS #12 | §3.4 修 |
| 20 | P2-13 `tb_user.password` 从未使用 / 管理员无密码 | MS #13 | §3.2 删 DTO 字段 + README 说明 |
| 21 | P2-14 `RoleInterceptor` 类级分支无使用者、403 空响应体 | MS #14 | §3.12 保留（有意设计） |
| 22 | P2-17 `EquipmentCacheTest` 靠语句 id 匹配，可能**静默失效** | MS #17 | §3.10 修（判据自证） |
| 23 | P2-20 F3 `admin_user_id` 外泄给任意登录用户 | MS #20 | §3.7 **修** |
| 24 | P2-21 F4 `DELETE /laboratory/{id}` 不查依赖 → 孤儿批次 | MS #21 | §3.6 **修** |
| 25 | P2-22 发布失败两分支复用同一句报错 | MS #22 | §3.11 修 |
| 26 | P2-24 并发用例判别力概率性（3/18） | MS #24 | §3.12 登记 |
| 27 | P2-27 夹具手机号碰撞概率 ~1e-5 | MS #27 | §3.10 加固 |
| 28 | P2-28 中断测试留夹具残留，收尾须查 DB | MS #28 | §3.13 验收清单 |
| 29 | `tb_` 前缀是否去除 | P2-DESIGN:170 | §3.12 **保留** |

另：Phase 8 的 R10/I11 变体证据缺口、Phase 9 的零行 warn 与 `initialDelay` 阴性对照，已在 `PHASE9-REVIEW.md` §6 诚实登记，Phase 10 不追补。

### 2.6 已核实的环境事实（本轮实测）

- 库 `hmdp` 现存 19 张表。**在用**：`tb_user`(1013) / `tb_equipment`(12) / `tb_equipment_category`(6) / `tb_laboratory`(4) / `tb_reservation*`(0)。**死表**：`tb_blog`(4) / `tb_shop`(14) / `tb_shop_type`(10) / `tb_voucher`(1) / `tb_blog_comments`(0) / `tb_follow`(0) / `tb_sign`(0) / `tb_seckill_voucher`(0) / `tb_voucher_order`(0) / `tb_user_info`(0)。
- `tb_reservation_request.update_time` 是 `ON UPDATE CURRENT_TIMESTAMP`（`phase8_consistency.sql:8`），且有 `idx_status_update_time` 复合索引 → F-C 的修复可复用现有能力（§3.9）。
- `listStale` 的 `reconcile` 循环 catch 分支**只写日志、不触库**（`ReservationReconcileServiceImpl:135-139`）⇒ 持续失败的行 `update_time` 永不推进 ⇒ 饥饿成立。
- 当前 git：`master` 领先 `origin/master` 15 个提交，工作区干净，HEAD = `b917535`。`origin` = `gitee.com/huyi612/hm-dianping.git`（他人课程仓库）；`github` = `git@github.com:rys888/yanyuetong.git`。
- 跟踪文件 170 个，`.git` 7.7M（全量历史重写成本很低，但用户已选择不改写）。

---

## 阶段三：技术设计

### 3.1 [决策] 命名迁移的目标与边界

**做**：Java 包 `com.hmdp` → `com.yanyuetong`（124 文件，含目录移动与 `package`/`import`）；主类 `HmDianPingApplication` → `YanYueTongApplication`；pom 坐标与 `description`；`spring.application.name`；配置前缀 `hmdp.reservation.*` → `yanyuetong.reservation.*`（4 个键）；`type-aliases-package`；`logging.level`；3 个 mapper XML `namespace`；**2 处字符串形式的包名**（`@MapperScan` 与测试里的语句 id）。

**不做**（边界，理由）：

- **库名 `hmdp` 不改**：用户裁决的选项文本已含此边界；改库名要在用户本机建新库并迁 1013 行 `tb_user` 与 12 台设备，收益（一个 URL 里的单词）远小于风险。写入 README「已知边界」。
- **`tb_` 表名前缀不改**（§3.12）。
- **仓库目录名 `hm-dianping` 不改**：不在提交内容内，改它只影响用户本地路径；README 说明即可。

**危险面（本 Phase 最高风险）**：字符串形式的包名。`"com.hmdp.mapper.EquipmentMapper.selectById"` 在测试里用于匹配 MyBatis 语句 id，**改名后若不改这里，编译通过、测试仍"绿"，但那条断言不再匹配任何语句 → 静默失效**（这正是 MS #17 登记的同一类陷阱）。故 T2 的验收必须包含 `grep -rn '"com\.hmdp' src/` 计数为 0，且逐个判定语义，而不只是"编译通过"。

### 3.2 [决策] 残留清除清单（逐项判定）

一律**删除**，不留"改造"分支：

- **`UploadController` 删除而非改造为设备图片上传**。理由：①`Equipment.image` 是普通 `String` 字段、种子数据未填图片值、无前端消费；②Phase 1-REVIEW 曾建议"改造为 URL 方式"，但补一套文件存储/上传是**新增功能**，与"不过度设计"冲突且超出 §10 的"清理"语义；③§10.1 的判据是"是否仍有不应该存在的业务语义"，blog 语义必须消失。README「已知边界」写明：设备图片以 URL 字符串存储，未提供上传接口（有意不做）。
- `MvcConfig` 免登录白名单同时删掉 `/upload/**`（保留 `/user/code`、`/user/login`）。
- `UserInfo` 四件套 + `GET /user/info/{id}` + `GET /user/{id}` 全删。这两个端点**无任何业务流与测试调用方**（已 grep 确认），且 `tb_user_info` 0 行。
- 死代码：`SimpleRedisLock`、`ILock`、`PasswordEncoder`、`SystemConstants.IMAGE_UPLOAD_DIR`、`SystemConstants.MAX_PAGE_SIZE`、`LoginFormDTO.password`。
- 11 处 `@author 虎哥` 注释整块删除（连带无内容的 `<p></p>` javadoc 壳）。
- 2 个无断言测试整文件删除。

**保留**：`User.password` 字段与 `tb_user.password` 列（见 §2.1 C）、`RedisData`、三套缓存工具、`RedisIdWorker`。

### 3.3 [决策] 三套缓存工具：全部保留

`CacheClient` 的三个方法代表三种具名缓存模式：`queryWithMutex`（互斥重建防击穿，**生产在用**，`EquipmentServiceImpl`）、`queryWithPassThrough`（缓存空值防穿透）、`queryWithLogicalExpire`（逻辑过期 + 异步重建）。

保留理由：§10.2 明令"**不能机械删除技术概念**"，而这三个方法正是技术概念的**完整可读实现**，删掉会让项目少掉一类可展示的能力；且 `queryWithLogicalExpire` 依赖 `RedisData`，删除会连带产生孤儿类。**处置**：保留，并在 README「Redis 设计」章节明确标注"生产链路只用互斥重建，另两套作为可复用工具集保留，无生产调用方"——把"死代码"变成"有说明的工具集"，消除隐晦。

### 3.4 [决策] 数据库：基线精简 + 死表 DROP + 种子幂等

1. **`db/hmdp.sql` → `db/phase0_baseline.sql`**，只保留 `CREATE TABLE tb_user`（原始 dump 里 10 张表中**唯一仍在用的**），并在文件头写明：本文件是迁移链的起点，历史表 DDL 已移除，死表清理见下一项；执行顺序 = baseline → phase2 → phase3 → phase5 ×2 → phase7 → phase8 → phase9。

   **附带决定：不搬运 dump 里的 1010 行教程用户 INSERT**（`hmdp.sql` 共 33 条 INSERT，其中 `tb_user` 那批的昵称是"小鱼同学""可可今天不吃肉"、头像是 `/imgs/blogs/blog1.jpg` —— 既含 blog 语义，又是教程数据）。理由：①新装库的用户由**首次验证码登录自动建号**（`createUserWithPhone`），种子管理员由 `phase3_user_role.sql` 幂等创建，基线**不需要**任何预置用户；②保留教程数据等于把 §10.1 要清的东西换个位置留下。**边界**：用户**现有库里的 1013 行不受影响**（本轮只 DROP 死表，不动 `tb_user` 数据）；排查/需要教程数据时仍可从 git 历史的 `hmdp.sql` 取回。
2. **新增 `db/phase10_drop_legacy_tables.sql`**：对 10 张死表逐表 `DROP TABLE IF EXISTS`，每张表上方一行注释注明"原黑马表义 / 已无代码引用 / 行数（DROP 时点）"。幂等（`IF EXISTS`）。
3. **`phase2_laboratory_equipment.sql` 种子 INSERT 幂等化**：改用 Phase 3 已确立的幂等写法（`ON DUPLICATE KEY UPDATE` 或 `INSERT ... SELECT ... WHERE NOT EXISTS`），与既有脚本风格一致。
4. **执行 DROP 由我（Claude）直连执行**（用户 2026-10-06 裁决）。执行前：记录三张有数据表的行数作为证据；逐表确认无代码引用（`grep -rn` 表名 = 0）与外键依赖；执行后再查一次 `information_schema` 并核对在用表与种子数据未损。

### 3.5 [决策] 凭据清理口径

**做**：把 3 个跟踪文件里的明文口令替换为占位符（如 `<本机口令见 application-local.yaml>`），保留上下文语义（它们是"问题清单第 9 条""审计表 B4 行""报告里的配置片段"，删掉整段会丢失记录价值）。

**不做**：不改写历史、不轮换本机口令（用户裁决）。**登记**：README「已知边界」+ `MIGRATION_STATUS.md` 写明"git 历史的 49 个提交中含本机开发口令（原始课程提交引入），本仓库未 push；若将来要公开，须先改写历史或轮换口令"。

### 3.6 [决策] P1 修复 F4：`DELETE /laboratory/{id}` 加依赖检查

**现状**：不检查是否存在依赖设备/批次 → 删实验室会留下孤儿 `tb_equipment.laboratory_id` 与孤儿 `tb_reservation_batch`。

**设计**：删除前检查 `tb_equipment`（按 `laboratory_id`）与 `tb_reservation_batch`（按 `laboratory_id`）是否存在引用；存在则抛 `BadRequestException`，文案分别指明是设备还是批次，让调用方能自助判断。**必须放在同一 `@Transactional` 内**，且检查与删除之间不留窗口（同事务已足够：`DELETE` 与 `SELECT` 在同一事务，配合既有唯一/外键约束的缺失，接受"检查-删除"不是原子"——登记为已知边界，因为本项目无并发删实验室的场景）。

**契约变更**：这是**行为变更**（原先能删的现在会被拒），必须在 README「API 示例 / 已知边界」与 `MIGRATION_STATUS.md` 显式登记，并单独一个测试用例覆盖"有依赖 → 拒绝 + 无副作用"与"无依赖 → 仍可删"。

### 3.7 [决策] P1 修复 F3：`admin_user_id` 不外泄

**现状**：`GET /laboratory/list`、`GET /laboratory/{id}` 直接序列化实体，把 Phase 5.1 新增的 `admin_user_id` 暴露给任意已登录用户。

**设计**：走 **VO**（`LaboratoryVO`），与既有 `EquipmentVO` / `ReservationVO` / `ReservationBatchVO` 的模式一致——`admin_user_id` 不进 VO。**不用 `@JsonIgnore`**：该字段在**管理侧**（`/laboratory/manage*`）需要回显给管理员，`@JsonIgnore` 会把两侧一起掐掉。返回 VO 只改公开读端点，管理侧仍返回实体（或同样用 VO 但含该字段），保持 Phase 5 管理端契约不变。

**登记**：这是接口面变更（响应体少一个字段），写入 README。

### 3.8 [决策] P1 修复 P2-25：全局异常兜底的 HTTP 状态

**现状**：`WebExceptionAdvice.handleRuntimeException` 无 `@ResponseStatus` ⇒ 服务器故障返回 **HTTP 200** + `Result.fail("服务器异常")`；且 `HttpMessageNotReadableException`（**客户端**错误：JSON 解析失败）也落到同一兜底。

**设计**（最小且语义正确）：

- `handleRuntimeException` 加 `@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)`；
- 新增 `handleHttpMessageNotReadable` → `@ResponseStatus(HttpStatus.BAD_REQUEST)`，返回"请求体格式错误"；
- `BadRequestException` / `ForbiddenException` / `ServiceUnavailableException` 三个既有处理器**不动**（已各有正确状态）。

**风险**：既有测试可能断言了 200。**验收**：全量测试跑一遍，逐条判定任何变红的用例是"断言了错误契约"还是"真实回归"——若是前者，按 F-9C 的教训**优先怀疑是契约被写错了**，并与 Phase 6 的设计措辞（已更正为"应区分 400/500"）对齐。

### 3.9 [决策] P1 修复 F-C：`listStale` 饥饿（本 Phase 最巧的一处）

**现状链**：`listStale` = `WHERE status IN (非终态) AND update_time < before ORDER BY update_time ASC, request_id ASC LIMIT n`；`reconcile` 的 catch 分支**只 `log.error`，不触库** ⇒ 持续失败的行 `update_time` 恒定最旧 ⇒ **每轮独占队首的 n 个名额**，其后已达阈值的陈旧行永久饥饿。

**设计**：在 catch 分支内，用**既有且已被测试覆盖**的 `recordFailReason(requestId, "RECONCILE_ATTEMPT_FAILED")` 写一次失败原因。由于 `update_time` 是 `ON UPDATE CURRENT_TIMESTAMP`，该行随即移到队尾 ⇒ **饥饿消除**；同时账本上留下审计线索（原先只有日志，库里无痕）。

**为什么不是游标**：游标要新增持久化状态（Redis 不持久 / 新表要 DDL），属"新机械"；而写 `fail_reason` 是**已有能力 + 一次 UPDATE**，且顺带提升可观测性。

**明确的取舍（必须登记）**：这把"每轮立即重试"改成"失败后按阈值间隔退避重试"（最坏 10 分钟一次）。这是**有意**的：进度保证优先于单行重试延迟。写入设计说明与 README。

**新增测试**：构造 `n+1` 条陈旧行，其中**第 1 条（最旧）的补偿必然失败**（`@SpyBean` 让 `rollback` 对该 batchId 抛异常），断言 ①本轮 `failed=1` 且 ②该行 `fail_reason` 已写入 ③**下一轮 `listStale` 不再选中它**（即它已不在最前），而第 n+1 条能被处理到。这是本轮**唯一的新增行为级测试**，也是 F-C 修复的判别力证据。

### 3.10 [决策] 测试卫生

| 项 | 处置 |
|---|---|
| `RedissonTest`、`HmDianPingApplicationTests` | 删（无断言） |
| #22 P2-17：`EquipmentCacheTest` 靠语句 id 匹配 MyBatis 语句 | **加判据自证**：断言拦截器实际命中次数 > 0（否则"测试仍绿但不再测任何东西"）。这是"判据本身必须先自证"的直接落地 |
| #17 P2-26：`ReservationBatchLifecycleTest.shouldIncludePublishedAndClosedBatchesOnPublicPage` 依赖"夹具在第 1 页" | 加固：像 Phase 6 的分页用例那样用 `equipmentId` 收窄查询，消除对库中累积数据的依赖 |
| #27 P2-27：夹具手机号碰撞概率 | 加固：把随机空间从 `% 10_000_000L` 提到与 Phase 5 一致的 `% 100_000_000L`，并保持唯一性 |
| #12 P2-12：`CacheClientMutexTest` T1 夹具复杂度 | **保留**。它 50 线程确定性构造了"并发未命中只回源一次"，是 Phase 4 P1-8 修复的**判别力证据本身**；简化会削弱证据强度。登记理由 |
| #26 P2-24：并发用例判别力 3/18 概率性 | 保留并登记（正确实现下恒绿，判别力是概率性的固有局限） |

### 3.11 [决策] 文档归置、README、MIGRATION_STATUS 收口

**归置**（用户裁决④）：

| 原位置 | 新位置 |
|---|---|
| `实验资源预约平台-分阶段重构Phase计划.md` | `docs/plan/实验资源预约平台-分阶段重构Phase计划.md` |
| `黑马点评 → 实验资源预约平台重构.md` | `docs/plan/原始需求-实验资源预约平台重构.md`（**改名**：文件名本身即 §10.1 要消除的痕迹；文件头加一行注明原名） |
| `秒杀消息队列迁移RabbitMQ-交付报告.md` | `docs/history/RabbitMQ-异步下单迁移交付报告.md`（**改名** + 清掉明文口令；内容是对已删除的秒杀链路的历史记录，保留作过程证据） |
| `MIGRATION_STATUS.md` | `docs/MIGRATION_STATUS.md`（更新为 Phase 10 / PROJECT COMPLETE） |

根目录最终只剩：`README.md`、`pom.xml`、`src/`、`docs/`、`.gitignore`、`.claude/`（+ 构建产物 `target/`）。

**README.md（新建）**：按 §10.5 的 15 节写全——① 项目简介 ② 项目背景（含"由黑马点评课程项目重构而来"的诚实交代）③ 技术栈 ④ 核心功能 ⑤ 系统架构 ⑥ 高并发预约流程 ⑦ Redis 设计 ⑧ RabbitMQ 可靠性 ⑨ 数据一致性设计 ⑩ 数据库设计 ⑪ 启动方式（JDK 8 + `application-local.yaml` + 脚本执行顺序）⑫ 环境依赖 ⑬ API 示例 ⑭ 项目亮点 ⑮ 已知边界（**本文档 §3.12 的登记项 + 管理员取权方式 + 历史凭据 + 库名 `hmdp`**）。

**`LOGIN_USER_TTL`**：`36000L`（25 天）→ 缩短。因为 `RefreshTokenInterceptor` 每次请求都续期，缩短到 **30 分钟**对活跃用户无感，却把"token 泄漏后的可用窗口"从 25 天压到 30 分钟。改动点只有 `RedisConstants` 一行；需检查测试是否依赖该常量（若无则零回归）。

**#25 P2-22 发布失败文案**：`publish` 的 `SET NX` 拒绝分支与条件 UPDATE 的 CAS 拒绝分支目前复用同一句「批次状态已变化，发布失败」。改为两句可区分文案（如"批次状态已变化/被并发修改"vs"发布占用标记已存在，疑似重复发布"），保留原有错误码，便于排障。

### 3.12 [决策] 明确"不做"并登记为已知边界

| 项 | 结论与理由 |
|---|---|
| 库名 `hmdp` | 保留（§3.1） |
| `tb_` 表名前缀 | **保留**。去掉要改 19 张表的 DDL + 全部实体 `@TableName` + 全部 SQL，纯机械 churn 且破坏 Phase 2–9 已验收脚本的可追溯性；`tb_` 本身是中性约定，不承载黑马业务语义 |
| #18 前导通配符 `LIKE '%name%'` 无法走索引 | 保留（12 行设备，搜索语义正确优先于索引）；README 登记 |
| #21 `RoleInterceptor` 类级注解分支无使用者、403 空响应体 | 保留（有意设计，Phase 3 已裁定） |
| #13/#14 缓存删除失败无补偿、极端竞争惊群 | 保留现有行为（30 分钟 TTL 兜底）。补偿需新机械（重试队列/事件表），与"不过度设计"冲突；README 登记为已知边界 |
| #26 并发用例判别力概率性 | 保留并登记 |
| 设备图片上传 | 不提供（§3.2） |
| Phase 8 R10/I11 变体证据缺口、Phase 9 两处无变体强度证据 | 不追补，已在各自 Review 诚实登记 |

### 3.13 验收清单（Claude 侧执行，全部判据必须先自证）

1. **编译/测试**：`mvn clean compile` 与 `mvn test` —— 记录 `Results:` 行与真实退出码（**不取管道退出码**）。
2. **命名零残留**：`grep -rn 'com\.hmdp\|hm-dianping\|HmDianPing' src/ pom.xml` 计数 = 0；`grep -rn '"com\.hmdp' src/` = 0（字符串形式单独查）。
3. **§10.1 关键词**：对 `shop/voucher/seckill/blog/follow/dianping/geo/sign/bitmap/hyperloglog` 在**黑马语义**下逐个判定（先给出"应该为 0 的判据"，再用已知为"有"的样本自证判据有效）。
4. **禁改清单**：`reservation.lua`、`reservationRollback.lua`、`unlock.lua`、`ReservationLifecycleServiceImpl`、`ReservationPersistenceServiceImpl`、`ReservationQuotaRollbackExecutor`、`ReservationQuotaExecutor`、`ReservationRequestStatus`、`ReservationStatus`、`active_flag` DDL —— 用 `git status --porcelain -- <真实路径>` 核验（路径必须先 `find` 定位，不得凭记忆拼）。
5. **凭据**：全仓库正则扫描（白名单 `change_me` / `your_password_here` / `${...}` / 占位符），命中数 = 0。
6. **README 章节**：15 节逐节核对存在且非空。
7. **数据库**：死表已 DROP（`information_schema` 复核）；在用 4 表 + 种子数据（`tb_equipment` 12 / `tb_laboratory` 4）未损；`tb_user` 行数未变。
8. **运行环境残留**（#28）：4 张预约表 0 行；Redis 无 `reservation*` / `lock:reservation*`；RabbitMQ `reservation.create.queue` 与 `.dlq` 均 `messages=0 consumers=0`。
9. **差别力实验（变体法）**：对本轮**新增/修改的行为**逐个做"改坏 → 目标用例变红 → byte-exact 复原 + SHA-256 核对"。至少覆盖：F4 依赖检查、F3 VO 不外泄、F-C 退避、P2-25 状态码、`LOGIN_USER_TTL`（若测试可判）。
10. **P0/P1/P2 审计表**：§10.7 三档逐条给结论与证据；P0 必须为零。
11. **诚实登记**：凡无变体强度证据的改动（纯文案/日志/常量类）在 Review 中显式标注"无判别力证据"，不冒充已验证。

### 3.14 提交与交付

- 每个 Task 独立本地提交（沿用 "每个 Phase 独立提交" 的精神，Phase 10 内部也按 Task 分提交，便于回退）；
- **不 push**；`origin` 是他人仓库，`github` 是用户自有仓库但**未经允许不推**；
- 最终提交信息给出 `PHASE 10 — PASS / PROJECT MIGRATION — COMPLETE` 的收口说明。

---

## 阶段四：任务拆解

拆成 6 个原子任务。**顺序有依赖**：T1 删文件 → T2 才能干净地重命名；T4 的产物（DROP 脚本）由 Claude 执行。

### T1 — 残留业务与死代码清除（T2 前置）

- **What**：删除 §3.2 清单的全部文件/代码块（UploadController、MvcConfig 白名单项、`SystemConstants` 两个常量、`UserInfo` 四件套、`UserController.info` + `queryUserById`、`LoginFormDTO.password`、`SimpleRedisLock`/`ILock`/`PasswordEncoder`、11 处 `@author 虎哥`），并删掉 2 个无断言测试。
- **Constraints**：**不改包名**（T2 做）；不动 `CacheClient`、`RedisData`、`RedisIdWorker`、`User.password`；不动任何 Lua 与 reservation 域代码。
- **Acceptance**：`mvn clean compile` 通过；全量测试通过（用例数应减少 2 个类）；`UserController` 不再持有 `IUserInfoService`；`grep -rn "IMAGE_UPLOAD_DIR\|MAX_PAGE_SIZE\|UserInfo\|虎哥\|SimpleRedisLock\|PasswordEncoder" src/` = 0。
- **Validation**：`mvn clean test`；给出删除文件清单与用例数变化。

### T2 — 命名迁移（依赖 T1）

- **What**：§3.1 的全部改名项，含 124 个 Java 文件的包名与目录、主类、pom、`application.yaml` 四处、3 个 mapper XML namespace、2 处字符串形式包名。
- **Constraints**：库名 `hmdp` 保持；`tb_` 前缀保持；不改任何业务逻辑；不改 Lua；`@author 虎哥` 已由 T1 删除。
- **Acceptance**：`grep -rn 'com\.hmdp\|hm-dianping\|HmDianPing' src/ pom.xml` = **0**；`grep -rn '"com\.hmdp' src/` = **0**；`mvn clean compile` 通过；全量测试通过；测试里的语句 id 字符串已同步（**必须显式报告这一处**，因为它编译不报错）。
- **Validation**：两条 grep 计数 + `mvn clean test`。

### T3 — 数据库脚本（可与 T2 并行）

- **What**：§3.4 三项——`hmdp.sql` → `phase0_baseline.sql`（仅 `tb_user`）；新增 `phase10_drop_legacy_tables.sql`（10 张死表幂等 DROP，逐表注释行数）；`phase2_laboratory_equipment.sql` 种子 INSERT 幂等化。
- **Constraints**：不得改动任何**在用表**（`tb_user` 的 DDL 原样搬运，除文件位置外不改一个字符）；不得 DROP 在用表；不改 Phase 3/5/7/8/9 脚本。
- **Acceptance**：baseline 脚本可单独在空库执行成功（在临时库实测后删除）；DROP 脚本对不存在的表重复执行不报错；幂等种子脚本**连跑两次**不产生主键冲突且行数不变。
- **Validation**：在临时库上实跑（执行后即删临时库），报告每步输出。

### T4 — P1/P2 行为修复（与 T2/T3 并行）

- **What**：§3.6 F4（实验室依赖检查 + 新测试）、§3.7 F3（`LaboratoryVO` 不外泄 `admin_user_id`）、§3.8 P2-25（异常兜底状态码 + 新增 `HttpMessageNotReadable` 处理）、§3.9 F-C（catch 内写 `recordFailReason` + §3.9 的判别力测试）、§3.11 `LOGIN_USER_TTL`、§3.11 P2-22 发布失败文案。
- **Constraints**：每项独立、最小；不改已验收的定序/CAS/Lua；F4 的契约变更与 F3 的响应体变更必须**单独可辨**（各自独立提交片段，便于 Review 拆分）。
- **Acceptance**：新增/修改的每项都有对应测试；全量测试通过；对 F4/F3/F-C/状态码四项给出"改坏后哪些用例变红"的自测记录（供 Claude 做变体实验时比对）。
- **Validation**：`mvn clean test`；逐项列出测试名。
- **注意（写进任务包）**：若既有用例因 P2-25 状态码变更而变红，**先假定是既有断言写错了契约**（Phase 6 设计已更正为"应区分 400/500"），不要顺手把断言改成迁就实现；任何对**既有**测试的修改必须在汇报中单独列出并说明理由。

### T5 — 测试卫生（与 T4 并行）

- **What**：§3.10 的 #22（语句 id 判据自证）、#17 P2-26（分页用例收窄）、#27 P2-27（手机号随机空间）。
- **Constraints**：只加固判据与夹具，**不得**削弱任何断言；不改动被加固用例的**业务断言本身**。
- **Acceptance**：#22 的加固要能被证伪（人为让拦截器匹配不到 → 新断言必须变红）；#17/#27 加固后全量测试通过。
- **Validation**：`mvn clean test` + 对 #22 的证伪记录。

### T6 — 文档（Claude 自己做，不派发）

§3.11 的文档归置、README 15 节、`MIGRATION_STATUS.md` 收口。**不交 Codex**：文档属 Control Plane 职责，且涉及对全项目设计意图的复述，转述会失真。

---

## 5. 待用户确认/否决事项

1. **目标名 `研约通` / `com.yanyuetong`**：取自用户已建仓库 `rys888/yanyuetong`；需求文档 §6.1 要求给 3–5 个候选，本设计只采用这一个（与既有仓库名一致，避免二次改名）。若你想换成别的名字，请在 T2 派发前告知。
2. **`docs/plan/` 与 `docs/history/` 两个新目录**、以及两份文档的**改名**（`原始需求-…`、`RabbitMQ-异步下单迁移交付报告`）属我对用户裁决④的细化；若你希望保留原文件名，请在 T6 前告知。
3. **F4 是行为变更**（原先能删的实验室现在会被拒）。虽在 §10.7「P1 业务逻辑漏洞」范围内，但它是**对外契约变化**，特此显式标出。
4. **死表 DROP 不可逆**，且会连带 `tb_shop` 14 / `tb_blog` 4 / `tb_voucher` 1 行课程数据消失。用户已授权「我直接连库执行」，此处再次标红：**执行后无法恢复**。
