# Phase 7 Review

## Result

**PASS**（含一轮返工闭环）

审查对象：Phase 7「RabbitMQ 异步预约持久化」——把 Reserve 的落库从同步链路改为「Redis+Lua 同步抢名额 → MQ 异步落库」
规格依据：`docs/refactor/PHASE7-DESIGN.md`
审查方式：逐行核对 diff → 独立源码审查 → **两条承重论断的字节码复核**（Spring AMQP 2.2.18）→ 真实机器集成测试（90 个用例）→ **8 组证伪实验**核验判别力 → 回归定位与返工 → 全量回归 → 直连 MySQL / Redis / RabbitMQ 核验环境复原
审查人：Claude（Control Plane），2026-10-04～2026-10-05

本 Phase 与前面几个 Phase 的复核重心又不同：**Phase 7 的产物不是"一条链路"，而是"一套失败模型"**。异步化把「成功」从一个返回值变成了一个*最终状态*，于是真正的问题变成：

1. **消息在什么情况下会永久卡死？** 不是"用了 MQ 很好"，而是"哪一类异常会让消息既不入库也不进死信"。§3.2 用字节码回答——答案与直觉相反。
2. **测试真的能发现这套失败模型被破坏吗？** §5 是总账，§6 是它抓出来的**回归**，§7 是返工。

本次审查最有价值的产出不是"全绿"，而是 §6：**全量回归抓到了一个既有测试被异步语义击穿的回归**——它既不在 Codex 的自检范围内（其沙箱跑不了集成测试），也不会在任何单类运行中稳定复现。

---

## 1. 交付物核对

| 类别 | 文件 |
| --- | --- |
| 修改（3） | `config/WebExceptionAdvice.java`（新增 503 处理器）、`service/impl/ReservationServiceImpl.java`（发布+补偿）、`utils/RedisConstants.java`（1 行常量） |
| 新增·基础设施（4） | `config/RabbitMQConfig.java`、`mq/ReservationMessageConsumer.java`、`mq/ReservationMessageDTO.java`、`utils/ReservationQuotaRollbackExecutor.java` |
| 新增·领域（4） | `entity/Reservation.java`、`enums/ReservationPersistResult.java`、`exception/ServiceUnavailableException.java`、`mapper/ReservationMapper.java` |
| 新增·持久化服务（2） | `service/IReservationPersistenceService.java`、`service/impl/ReservationPersistenceServiceImpl.java` |
| 新增·资源（2） | `resources/reservationRollback.lua`、`resources/db/phase7_reservation.sql` |
| 新增·测试（2） | `test/.../ReservationMqTest.java`（10 用例）、`test/.../ReservationPersistenceTest.java`（4 用例） |
| 修改·测试（1） | `test/.../ReservationSubmitTest.java`（返工：夹具隔离 + 清理补齐，**断言本身未动**） |

**改动面与设计 §9 一致**（生产新增 12 个，测试 3 个）。

**测试增量**：Phase 6 结束 76 个 → Phase 7 新增 14 个（Persistence 4 + Mq 10）→ **合计 90 个**。

**`publish` 的三道 CAS 守卫、Phase 6 的 Lua 链路零触碰**：`ReservationBatchServiceImpl.java` 在 `git status` 中**完全未出现**（未修改）。

**生产文件 SHA-256 前 32 位**（全部证伪实验结束后逐字节复原）：

```text
resources/reservationRollback.lua                     4324ed6bcf8871f9bb342efda7de1c77
resources/db/phase7_reservation.sql                   d2f046d20dde5bef32eee3577b0546a6
config/RabbitMQConfig.java                            47c1ed02d3bcebd15deb0f5d977c26fe
config/WebExceptionAdvice.java                        d69700fe731c74a80f11d2a42e4d6c53
entity/Reservation.java                               2ab6b04d5dcdc37a5ba2defd9a083acf
enums/ReservationPersistResult.java                   5163fb95411d7837e4beeb1d3674e6e6
exception/ServiceUnavailableException.java            b3ba389ea725009e0bc54c458d1a5444
mapper/ReservationMapper.java                         21bff129444169a89cebdfd1172081c3
mq/ReservationMessageConsumer.java                    3f45fdf1ec7d104036494e3edb2cd5a5
mq/ReservationMessageDTO.java                         359f56e26428d59258895f04a8974ae5
service/IReservationPersistenceService.java           5b6409599afb983adc300b381f1d90c5
service/impl/ReservationPersistenceServiceImpl.java   35524b8c082401f632c31e73c9fed1a1
service/impl/ReservationServiceImpl.java              fdcea5ab03ce1665c430423a281601b4
utils/ReservationQuotaRollbackExecutor.java           79a2764cec52766ad8278ae006278ff2
utils/RedisConstants.java                             895aa8a1c0013b84306824492e69c24c
test/.../ReservationMqTest.java                       5904a30877d31174d24e43b805c8001b
test/.../ReservationPersistenceTest.java              ac01f7fb34af57292f46b28ad1b62ef9
test/.../ReservationSubmitTest.java                   7092e0c0e6cb8f0aa7fb4e50b6790459
```

---

## 2. 能力清单逐条核验

| 能力 | 结论 | 覆盖用例 / 证据 |
| --- | --- | --- |
| 提交后异步落库，`reservationId` 与 Redis 生成值一致 | ✅ | `shouldSubmitAndEventuallyPersistReturnedReservationId` |
| 同一用户同一批次不产生第二行、不二次扣减 | ✅ | `shouldTreatRepeatedMessageAsDuplicateWithoutSecondQuotaDeduction`；**判别力经 F6 补强后由新用例承担**（见 §7 R-A） |
| 幂等键是 `(user_id, batch_id)` 而非 `id` | ✅ | `shouldTreatSameUserAndBatchWithDifferentReservationIdAsDuplicate`；**F6 变体打红** |
| 批次在消费前被关闭，已接受的预约仍落库 | ✅ | `shouldPersistAcceptedReservationEvenWhenBatchClosesBeforeConsumption` + 读路径回落断言（R-B） |
| 批次关闭后读路径回落 MySQL 拿到**已扣减**的值（Phase 6 §16.7 暂态消解） | ✅ | 同上，`queryBatchById` 断言 `INITIAL_QUOTA-1` |
| 生产者回滚：名额键存在则归还，返回码 1 | ✅ | `shouldRestoreQuotaAndRemoveUserMarkerWhenQuotaKeyExists` |
| 生产者回滚：名额键缺失（已关闭/取消）只清标记、**不复活名额键**，返回码 0 | ✅ | `shouldNotRecreateQuotaKeyWhenRollbackRunsAfterClose`；**F1 变体打红** |
| 不可路由的 mandatory 消息回投到 `CorrelationData` | ✅ | `shouldReturnUnroutableMandatoryMessageToCorrelationData` |
| 发送失败 → 回滚 + 抛 `ServiceUnavailableException`（HTTP 503） | ✅ | `shouldRollbackAndFailSubmitWhenCreateMessageCannotBeRouted`；**F7 / F7b 变体均打红** |
| 非法 JSON 进死信，且不阻塞后续消费 | ✅ | `shouldDeadLetterInvalidJsonAndContinueConsuming` |
| 名额冲突进死信，且 MySQL 名额不为负 | ✅ | `shouldDeadLetterQuotaConflictWithoutMakingMysqlQuotaNegative`；**F2 变体打红** |
| 实体主键由 `RedisIdWorker` 提供（非自增） | ✅ | **F5 变体打红** |
| `mvn test` 全绿 | ✅ | **Tests run: 90, Failures: 0, Errors: 0, Skipped: 0**（其中 31 个是 Codex 沙箱**跑不了**的 Spring 集成测试） |

---

## 3. 承重论断的字节码复核

Phase 7 有两条**凭直觉就会写错**的论断。我把它们从"看起来对"降级为"从字节码读出来"，因为这两条一旦错，错误方式是**静默的**（消息永久卡死 / 测试恒真）。

### 3.1 生产者侧：显式等待 confirm 是承重的

对 `RabbitTemplate.doSend` 的 131 条指令做提取：它调用 `setupConfirm` **一次**，且**零次** `getFuture()` / `Future.get()`。

结论：`convertAndSend` 返回时，confirm 与 return 两个回调都**尚未必然**被填充。因此 `submit` 里那句 `getFuture().get(5, SECONDS)` 不是装饰——它给了回调到达的时间窗口。

**这一条后来被 F7b 反向证实**（见 §5）：只删掉这句等待、保留检查，R-C 用例立刻变红。

> **勘误收窄**：§14.2 的实验曾得出"删掉整段等待+检查，全部测试仍绿"，因而判定该等待不承重。该结论**只在 broker 停机路径上成立**（那时异常由 `convertAndSend` 直接抛出，根本走不到等待）。F7b 在"已连接但不可路由"路径上给出了相反结论。两条并不矛盾——是 §14.2 把结论的适用范围写宽了。

### 3.2 消费者侧：ack/nack 的完整失败模型

从 `spring-rabbit-2.2.18.RELEASE.jar` 与 `spring-amqp-2.2.18.RELEASE.jar` 逐条提取：

| 方法 | 实际行为 |
| --- | --- |
| `ContainerUtils.isRejectManual(Throwable)` | **只看顶层 `instanceof AmqpRejectAndDontRequeueException`**，不遍历 cause 链 |
| `ContainerUtils.shouldRequeue(...)` | `defaultRequeueRejected \|\| instanceof MessageRejectedWhileStoppingException`，**然后遍历 cause 链**（ARADRE→false，ImmediateRequeue→true） |
| `BlockingQueueConsumer.rollbackOnExceptionIfNecessary(Throwable)` | `rejectManual = isRejectManual(ex); if (transactional) rollback; if (rejectManual) { basicNack(tag, true, shouldRequeue(...)); } deliveryTags.clear();` |
| `ConditionalRejectingErrorHandler.handleError` | 非致命 → **静默 return，不重抛** |
| `ConditionalRejectingErrorHandler$DefaultExceptionStrategy.isFatal` | 解包 `MessagingException` 层后，只认 `{MessageConversionException ×2, MethodArgumentResolutionException, NoSuchMethodException, ClassCastException}` 及用户自定义致命原因 |
| `AbstractMessageListenerContainer.invokeListener` | `catch (Exception)` → 包成 `ListenerExecutionFailedException` 后 `athrow` |

**推导出的失败模型**：

- **致命异常**（如非法 JSON → `MessageConversionException`）→ `handleError` 抛 `AmqpRejectAndDontRequeueException` → `isRejectManual=true` → `basicNack(requeue=false)` → **进 DLQ** ✅
- **非致命异常**（普通业务异常）→ `handleError` 静默吞掉 → `rollbackOnExceptionIfNecessary(LEFE)` → `isRejectManual=false` → **既不发 ack 也不发 nack → 消息永久 unacked → 在 `prefetch=1` 下彻底卡死** ❌

注意 `isRejectManual`（只看顶层）与 `shouldRequeue`（遍历 cause 链）是**不对称**的——这正是"即使 `default-requeue-rejected` 默认为 true，requeue=false 仍然成立"的原因。

**由此产生的硬性设计要求**（已写入设计 §7.6 与风险 R10）：

> 监听器方法**必须自己捕获一切异常**。任何逃逸到容器的非致命异常都会让消息既不入库也不进死信，而是永久卡住消费者。

`ReservationMessageConsumer.onReservationMessage` 的实现满足这一点：**整个方法体**被 `try` 包住，`catch (Exception e)` 里先恢复中断标志（若为 `InterruptedException`）再 `basicNack(tag, false, false)`。三处 `basicNack` 的 requeue **全部为 false**，无一处 `requeue=true`。

---

## 4. 禁止清单与零接触核验

| 项 | 结论 | 证据 |
| --- | --- | --- |
| `requeue=true` 出现次数 | ✅ 0 | `grep -n "basicNack"` → 三处均为 `(tag,false,false)` |
| 监听器与 `@Transactional` 是否同类 | ✅ 分离 | 消费者在 `mq/`，`@Transactional` 在 `service/impl/ReservationPersistenceServiceImpl`，**规避自调用失效陷阱** |
| 消息转换器 | ✅ 复用 Boot 的 `ObjectMapper` | `Jackson2JsonMessageConverter(ObjectMapper)`，未自建 |
| 消费者是否重复校验状态/窗口 | ✅ 否 | 状态与窗口只在同步链路（Lua + Java 闸门）判定，消费者不做二次判定 |
| `reservation.lua` 是否被改动 | ✅ 未动 | SHA-256 = `ad93be83…c76e`，与 PHASE6-REVIEW §1 记录值**完全一致** |
| `pom.xml` / `application.yaml` / `application-local.yaml` | ✅ 未动 | `git status --porcelain` 无输出 |
| voucher/seckill 残留 | ✅ 未新增 | 仅存在于既有的 `resources/db/hmdp.sql`（Phase 10 登记项），`src/main/java/` 下为 0 |

**拓扑核对**（broker 侧实测，逐项对上设计 §2.1）：

```text
reservation.exchange            direct, durable
  └─ rk reservation.create ──▶ reservation.create.queue  durable
                                 x-dead-letter-exchange   = reservation.dlx
                                 x-dead-letter-routing-key= reservation.create.dlq
reservation.dlx                 direct, durable
  └─ rk reservation.create.dlq ─▶ reservation.create.dlq  durable
```

---

## 5. 判别力实验台账（总账）

**方法**：临时改坏生产实现 → 跑目标测试类 → 看预期用例是否变红 → sha256 校验复原到交付版本字节一致。有效性闸门：`COMPILATION ERROR` 或 `Tests run: 0` 一律判该变体**无效、不产生结论**。

| 编号 | 变体 | 期望变红 | 实测 | 结论 |
| --- | --- | --- | --- | --- |
| F1 | `reservationRollback.lua` 删掉 `EXISTS` 守卫（无条件 INCR） | `shouldNotRecreateQuotaKeyWhenRollbackRunsAfterClose` | 该用例红 | ✅ 有判别力 |
| F2 | 扣减 SQL 删掉 `remaining_quota > 0` 守卫 | `shouldDeadLetterQuotaConflictWithoutMakingMysqlQuotaNegative` | 该用例红 | ✅ 有判别力 |
| F3 | 幂等预检查 `(userId,batchId)` → `id` | `shouldTreatRepeatedMessageAsDuplicateWithoutSecondQuotaDeduction` | **8/8 全绿** | ❌ **零判别力** → 返工 R-A |
| F5 | `Reservation.id` 的 `IdType.INPUT` → `AUTO` | `shouldSubmitAndEventuallyPersistReturnedReservationId` | 该用例红 | ✅ 有判别力 |
| T4 | 临时实验类：broker 停机下走生产者回滚路径 | 证明补偿端到端生效 | 通过（503 + 名额复原 + 标记清除 + 无落库行 + MySQL 未动） | ✅ 实验结论已留档，实验类事后删除 |
| F6 | 幂等预检查 `(userId,batchId)` → `id`（**返工后复测**） | `shouldTreatSameUserAndBatchWithDifferentReservationIdAsDuplicate` | 10 个中**仅该用例红**；旧用例仍绿 | ✅ R-A 补上了判别力 |
| F7 | 删掉生产者"返回消息不可路由"检查 | `shouldRollbackAndFailSubmitWhenCreateMessageCannotBeRouted` | 该用例红 | ✅ 有判别力 |
| F7b | 只删掉 `getFuture().get(5, SECONDS)`、保留检查 | 同上 | 该用例红 | ✅ **显式等待是承重的**（反向证实 §3.1） |

**F6 的意义**：它把 F3 的"零判别力"从一个*诊断*变成了一个*已修复且可证伪*的状态——同一个变体，改造前 8 个用例全绿，改造后新用例立刻变红。

**F3/F6 并置的教训**：`shouldTreatRepeatedMessageAsDuplicateWithoutSecondQuotaDeduction` 复用的是**同一个 DTO**（同一 `reservationId`）。这意味着按 `id` 查也能命中"已存在"，两种实现行为完全一致。**"重复消息"这个测试名掩盖了一个事实：它测的是 `id` 幂等，不是业务幂等。** 覆盖空洞往往藏在名字最像覆盖的地方。

全部变体的复原均经 sha256 校验，与交付版本字节一致。

---

## 6. 回归：异步持久化击穿了 Phase 6 的既有断言

### 6.1 现象

全量 `mvn test`：**88 个用例，1 个失败**，且失败的是**既有测试**（非本 Phase 新增）：

```text
ReservationSubmitTest.shouldReadPageRemainingQuotaFromRedisAndFallBackForNonPublished:208
expected: <4> but was: <3>
```

值得强调：**8/8 的 MQ 套件是绿的，单独跑任何一类也可能是绿的。** 这个失败只在全量、且消费者恰好抢在断言之前落库时才出现。

### 6.2 根因

该用例断言的是 Phase 6 的旧契约——「submit 只扣 Redis，**MySQL 保持不动**」：

```java
assertEquals(3, batchA.getRemainingQuota());                                     // 行 207：读 Redis → 3
assertEquals(4, reservationBatchService.getById(batchAId).getRemainingQuota());  // 行 208：读 MySQL → 期望 4
```

Phase 7 的消费者会异步执行 `remaining_quota = remaining_quota - 1`，于是 MySQL 变成 3。

**这不是 Task 2 的实现缺陷，而是 Phase 7 的设计目标。** 设计 §15 明确把"close 后读路径回落 MySQL 拿到**已扣减**的值"列为本 Phase 要达成的效果——行 208 断言的恰恰是 Phase 7 存在意义所要消除的那个陈旧状态。

**同时这是一个竞态**：`submit` 是同步扣 Redis、**异步**落 MySQL，所以该类中断言 MySQL 的四处（行 208/209/234/248）现在都依赖消费者时序。本次只有行 208 撞上，其余三处**靠运气过关**——即它们已是天然 flaky。

### 6.3 为什么不能"改期望值"了事

最直觉的修法是把上述四处改成"等消费者落库后断言 3"。**这会永久摧毁这些用例的判别力**：

一旦 MySQL 也收敛成 3，行 207（读 Redis）与行 208（读 MySQL）就**再无差值**，"本页读的是 Redis 而不是 MySQL"这一断言退化为**恒真**——正是设计 §13.2 与 R11 反复警告的恒真空断言。

**正确修法是隔离异步参与者**：让这些读路径用例运行在"监听容器停止"的状态。这既是消除竞态的手段，也是**消费者积压/宕机**这一真实状态；并且它让 Redis(3) 与 MySQL(4) 保持可区分的差值，原判别力完整保留。

### 6.4 第二层问题：清理残留（实测）

审查中发现 `ReservationSubmitTest.cleanUp` **只删 Redis 键、批次、设备、实验室、用户，不删 `tb_reservation` 行**（该类写于 Phase 6，当时还没有这张表）。实测确认：

```text
tb_reservation 行数 = 8      （批次已全部删除，8 行全是孤儿）
tb_reservation_batch 行数 = 0
有存活批次引用的行数 = 0
```

残留数是 **8 而不是全部成功提交数（约 14）**——**这个"部分残留"本身就是竞态的直接物证**：有多少消息在被清理前恰好被消费掉是时序决定的。

另有一个更隐蔽的二阶效应：被消费的消息若落在 `cleanUp` 删批次**之后**，`UPDATE ... WHERE id=? AND remaining_quota>0` 影响 0 行 → `QUOTA_CONFLICT` → **进 DLQ**，污染 broker 基线。

### 6.5 修复与验证

返工 R-D + R-E（见 §7）。验证：

| 项 | 修复前 | 修复后 |
| --- | --- | --- |
| 全量 `mvn test` | 88 个 / **1 失败** | **90 个 / 0 失败 / 0 错误** |
| `ReservationSubmitTest` | 17 个 / 1 失败 | **17 / 0** |
| 跑后 `tb_reservation` 残留 | **8 行** | **0 行** |
| 跑后 broker 队列 | DLQ 可能被污染 | `reservation.create.queue` 0/0、`reservation.create.dlq` 0/0 |
| 跑后 Redis `reservation:*` / `lock:reservation:*` | 干净 | **0 / 0** |

**行 207/208 的差值判别力保留**——修复动的是夹具（隔离与清理），四处竞态断言本身一字未改。

---

## 7. 返工项（R-A / R-B / R-C / R-D / R-E）

全部**只改测试代码**，生产代码零改动（见 §7.6 的证据）。

| 编号 | 问题 | 处置 |
| --- | --- | --- |
| **R-D** | `ReservationSubmitTest` 的四处 MySQL 断言依赖消费者时序（竞态） | `@BeforeEach` 停容器 + 清双队列；`@AfterEach` 先清队列、再清理、`finally` 重启容器；加类级注释说明"本类只验同步契约，异步落库归 ReservationMqTest"。**修隔离而非改期望值** |
| **R-E** | `cleanUp` 不删 `tb_reservation` 行 → 孤儿行 + 基线污染 | 注入 `ReservationMapper`，按 `batchIds` 批量删除；实测残留 8 → 0 |
| **R-A** | 旧幂等用例对幂等键**零判别力**（F3 实证） | 新增 `shouldTreatSameUserAndBatchWithDifferentReservationIdAsDuplicate`：同一 user+batch、**不同 reservationId**。判别点是 **DLQ 必须为空**（`id` 实现会撞 `uk_user_batch` → 抛异常 → nack → 进 DLQ；注意仅断言名额不会变红，因为 `@Transactional` 会回滚那次扣减） |
| **R-B** | T3 只断言裸 `getById`，无法验证"暂态消解"这一设计声称 | 补 `queryBatchById(batchId)` 读路径断言：CLOSED 批次回落 MySQL 必须拿到 `INITIAL_QUOTA-1`，且以**非预约人**身份断言 |
| **R-C** | 生产者"已连接但不可路由"失败路径**零覆盖**（§14.2 证实删掉整段仍全绿） | 新增 `shouldRollbackAndFailSubmitWhenCreateMessageCannotBeRouted`：`rabbitAdmin.removeBinding(...)` 制造不可路由 → 断言 503 + 名额复原 + 标记清除 + 无落库行；`finally` 重声明绑定，`@BeforeEach` 幂等重声明做防御 |

### 7.6 "生产代码零改动"的独立证据

两条互相独立的证据：

1. **mtime 窗口**：返工窗口（最近 4 分钟）内被修改的文件**只有** `ReservationMqTest.java` 与 `ReservationSubmitTest.java`；`src/main/**` 无任何文件落在窗口内。
2. **逐字节比对**：对返工前由证伪脚本留下的备份做比对，`reservationRollback.lua`、`ReservationPersistenceServiceImpl.java`、`Reservation.java` **全部字节一致**。

返工后生产指纹与 §1 记录值完全一致。

### 7.7 复测结果

```text
ReservationSubmitTest   Tests run: 17, Failures: 0, Errors: 0
ReservationMqTest       Tests run: 10, Failures: 0, Errors: 0
全量                    Tests run: 90, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS
```

---

## 8. 设计文档勘误

审查过程中发现的**己方（Claude）规格缺陷**，已在 `PHASE7-DESIGN.md` 就地更正：

| 位置 | 原内容 | 更正 |
| --- | --- | --- |
| §6.2 回滚脚本 | 两个分支都 `return 0`，与注释及 §13.2 冲突 | 改为 `return 1`（名额已归还）；补勘误说明：双 `return 0` 会让 §13.2 的返回码测试成为**恒真空断言** |
| §15 grep 口径 | "`src/` 范围内 grep = 0" | 收窄为 `src/main/java/`——`.sql` 中的 `tb_voucher*` DDL 使原口径**从未成立** |
| §18 第 9 项 | — | 补登记：`resources/db/hmdp.sql` 仍含 `tb_voucher` / `tb_seckill_voucher` / `tb_voucher_order` 建表语句，归 Phase 10 |
| §14 | 证伪结论（F3/F4） | 重写为实测结果，新增 §14.1/§14.2/§14.3；其中 §14.2 的适用范围由 §3.1 的 F7b 收窄 |
| §2.5 / §7.6 / R10 / R11 | — | 新增：字节码复核、失败模型、以及两条风险登记 |

Codex 独立地在同一处（回滚脚本第二分支）也识别出了同一矛盾，并交付了 `return 1`——两条独立路径得出同一结论。

---

## 9. 审查过程记录（方法论事故，必须留档）

本节记录**我自己的**工具缺陷，因为它们各自都险些产出一个错误结论。

### 9.1 `find -newermt '-60 minutes'` 静默失效，差点得出"返工根本没落盘"

BSD `find` 不认这个相对时间串。我**同时把 stderr 吞掉了**，于是命令返回空集，而同一屏里 `stat` 又显示文件 mtime 在 6 分钟前——**自相矛盾**。若我只看前者，会得出"返工窗口内无任何文件变动"的错误结论。改用 `-mmin` 后结论正常。

教训：**不要吞掉判定性命令的 stderr**；两个独立命令给出矛盾结论时，先怀疑工具而不是先解释现象。

### 9.2 `pgrep ... | head -1 && echo "有实例在跑"` 恒真，报了一个假警报

管道退出码取的是**最后一个命令**（`head`）的状态，而 `head` 对空输入也返回 0，所以 `&&` 分支必然触发。它让我以为"有独立应用实例在抢消息"，从而怀疑自己 90/90 的结果不可信。

用 broker 管理 API 做决定性核查后排除：`reservation.create.queue` 的 `consumers = 0`、`messages = 0`，`pgrep` 复查为空——**不存在竞争消费者**。

教训：**判断"有没有输出"要用 `[ -n "$(cmd)" ]` 或 `cmd | grep -q .`，不要用管道退出码。**

### 9.3 Codex 用量上限（第三次）

本次返工的**最后一步**（`mvn -q clean test-compile` 的最终确认）被用量上限中断：

```text
You've hit your usage limit. ... try again at Oct 5th, 2026 4:24 AM.
Process exited with code 1
```

因此返回内容里所有"已完成/已通过"的陈述**一律不予采信**，改为逐文件独立复核 + 我自己在真实环境跑全量。复核结论：改动确实落盘且正确（§7），但**编译与测试结论来自我的实测，不来自 Codex 的叙述**。

这是第三个 Phase 出现该问题（Phase 5 §6.4、Phase 6 §9.2、本 Phase）。已登记为流程性风险。

### 9.4 一个必须保留的判据

Codex 的自检**天然覆盖不到本次最重要的产出（§6 的回归）**：它的沙箱没有 MySQL / Redis / RabbitMQ，localhost 不通，**跑不了任何 Spring 集成测试**，只能 `mvn -q clean test-compile`。所以"Codex 说测试通过"在这个 Phase 里信息量极低——**集成层的结论只能由真实机器产出**。

---

## 10. 环境复原

全量测试与全部证伪实验结束后实测：

```text
tb_reservation            0 行
tb_reservation_batch      0 行
reservation.create.queue  messages=0  unacknowledged=0
reservation.create.dlq    messages=0  unacknowledged=0
Redis reservation:*       0 个
Redis lock:reservation:*  0 个
```

生产文件逐字节复原（§1 指纹）；`reservation.lua` 仍为 Phase 6 记录值；`pom.xml` / `application*.yaml` 未动。

---

## 11. 已知局限与登记项

**本 Phase 不解决、已在设计登记**：

- **DLQ 只落不消费**：死信队列无消费者、无告警、无重放工具。Phase 8（一致性与可靠性）处理。
- **MySQL 与 Redis 的最终一致缺口（两个方向）**：① 消费者失败/进 DLQ 时，Redis 名额已扣而 MySQL 无行；
  ② `submit` 的 confirm 超时（5s）会回滚名额，但消息可能**实际已投递并被消费**，于是出现「Redis 名额已归还、
  MySQL 已有预约行」的**反向漂移**（后果是 Redis 名额少算一个，用户可能重复提交，但 `uk_user_batch` 保证
  不会产生第二行）。两个方向都需要对账任务。Phase 8（与 `MIGRATION_STATUS.md` P1-2/P1-3 同源）。
- **取消/履约**：Phase 9。

**本次审查新增的登记项**：

- 幂等判定在 `@Transactional` 内做 `selectCount` → **先查后插的 TOCTOU 窗口**由 `uk_user_batch` 兜底（撞键即抛异常 → 进 DLQ）。当前是"靠唯一键兜底"而非"无窗口"，属于可接受的取舍，但应记录。
- `RabbitAdmin.removeBinding` 在连接重建时可能被自动重声明。R-C 用 `finally` + `@BeforeEach` 双重恢复拓扑，且失败方式是**响亮失败**（`assertThrows` 不通过），非静默。
- F7b 的单次测量表明"删掉显式等待"会稳定变红；该子结论基于一次观测，登记为"实测支持、非形式化证明"。

---

## 12. 结论

Phase 7 达成设计目标：同步抢名额 + 异步落库的链路成立，失败模型（致命→DLQ、非致命→必须自行捕获否则永久卡死）经字节码复核而非直觉确认，禁止清单零触碰。

**本次审查的实际价值集中在 §6**：全量回归抓到了一个既不在 Codex 自检范围内、也无法在单类运行中稳定复现的回归——Phase 7 的异步语义击穿了 Phase 6 的四处既有断言。修复选择了"隔离异步参与者"而非"改期望值"，从而**保住了这些用例的判别力**，同时顺带清除了一个实测存在的清理残留缺陷（8 行孤儿）。

**判别力总账**：8 组证伪实验中 7 组给出预期结论，1 组（F3）**暴露了零判别力**并已由 R-A 修复且复测证实（F6）。这正是实验台账存在的意义。

**质量声明**：90 个测试全绿（含 31 个真实机器集成测试）。**Codex 因用量上限未能完成最终编译确认，本 Phase 的全部编译与测试结论均来自 Claude 在真实环境（MySQL + Redis + RabbitMQ）的独立执行**，且"生产代码零改动"由 mtime 窗口与逐字节比对两条独立证据支持。

**遗留**：DLQ 消费/告警/重放、缓存与库的最终一致对账、取消与履约——分别归 Phase 8 / Phase 9。

**建议提交**：`feat: add rabbitmq async reservation persistence`（**仅本地提交，不 push**——凭据外部化与历史清洗归 Phase 10）。
