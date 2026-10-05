# Phase 8 · T6-R 返工任务包（Implementation Packet）

> 用途：T6 交付验收红灯后的**返工任务包**。状态：**已备好、尚未派发**
> （2026-10-05 Codex 触达账户用量上限，自报 16:47 恢复；派发时如遇额度未恢复，重试即可，本文件无需改动）。
>
> 派发方式（硬性约定）：MCP `codex`，`model = "gpt-5.6-sol"`，`effort = "high"`，
> `sandbox = "workspace-write"`，`cwd = /Users/eason/Desktop/hm-dianping`。
> 派发前请先读 `MIGRATION_STATUS.md` 的 T6 条目确认环境状态（表已建、队列已清零、未提交）。

---

## Goal

修复 T6 验收红灯：让 `reservation.create.dlq` 上的监听器**真正能收到任意字节形态的死信并写入台账**，
并且**一条不可解析的死信不得让该消费者永久失明**。

## Context

项目：Spring Boot 2.3.12 + Spring AMQP 2.2.18 + JDK 8 + MyBatis-Plus 3.4.3 的「高校实验资源预约平台」。
T6 引入死信台账 `tb_reservation_dlq` + `ReservationDlqConsumer`（原始 `Message` + `Channel` 手工确认）。

**实际缺陷（已由 Claude 在真实 MySQL/Redis/RabbitMQ 上实测确证，非推测）**：

1. Spring AMQP 2.2.18 的 `MessagingMessageListenerAdapter.onMessage` 在调用监听方法**之前**无条件执行
   `toMessagingMessage()` → 共享的 `Jackson2JsonMessageConverter.fromMessage()`。
   把参数声明成 `org.springframework.amqp.core.Message` **不能**绕过它。
2. 因此坏 JSON / 空消息体在 DLQ 容器内二次转换失败 → `MessageConversionException` →
   `ConditionalRejectingErrorHandler.handleError`（该消息已带 `x-death` 头）抛出
   `ImmediateAcknowledgeAmqpException("Fatal and x-death present")`。
3. `SimpleMessageListenerContainer.doReceiveAndExecute` 的 `catch (ImmediateAcknowledgeAmqpException)`
   分支只打一条 DEBUG 日志就 `goto 533` → 直接 `return`，**该路径上没有任何 `basicAck`**
   （正常路径是 `goto 527` 回到接收循环；已用 `javap -c` 在 spring-rabbit 2.2.18 上确认）。
4. **后果一**：坏 JSON 死信被静默丢弃，台账 0 行。
   **后果二（更严重，Codex 上一版未报告）**：手工确认模式下这条投递永不结算，配合 `prefetch=1`，
   broker 不再投递 → 该消费者线程仍在空转但**从此永久收不到任何消息**；容器不重启、日志无痕迹。

实测红灯（Claude 亲跑 `ReservationMqTest`）：`19 run / 5 failures`。
`shouldDeadLetterInvalidJsonAndContinueConsuming`（坏 JSON）红；
**`shouldDeadLetterQuotaConflict…` / `shouldParseReservationIdBeforeTruncatingOversizedPayload` /
`shouldDeadLetterPrimaryKeyConflict…` / `shouldNotLoseLedgerRowWhenPayloadIsEmpty` 这 4 条的报文都是合法 JSON，
本该正常落库，也全红**；全日志中 DLQ 容器线程仅出现在前两个用例；跑完队列残留 1 条 ready。

**已核对正确、不要改动**：四档 `failure_reason` 优先级；**先解析完整原文、后按 60000 字节截断**；
`failure_reason` 512 截断；`insert != 1` 抛异常；`status/replay_count` 初值 `NEW/0`；
两个 `basicNack` 第三参数都是 `false`；`basicAck` 已在落库 `try` 之外。

**已就绪的环境**：`tb_reservation_dlq` 已建（DDL 与设计 §4.2 逐字符一致，3 索引齐全，0 行）；
`ReservationMqTest` 已有 `dlqBaselineId` / `currentDlqMaxId()` / `waitForDlqRow(Long)` /
`assertDlqQueueEmpty()` / `listenerContainer(String)` 等基础设施。

## Scope

允许新增 / 修改（**仅这 4 个文件**）：

- 新增 `src/main/java/com/hmdp/config/ReservationDlqListenerConfig.java`
- 修改 `src/main/java/com/hmdp/mq/ReservationDlqConsumer.java`（只加一个注解属性）
- 修改 `src/main/java/com/hmdp/service/impl/ReservationDlqServiceImpl.java`（只降级一条日志）
- 修改 `src/test/java/com/hmdp/ReservationMqTest.java`（新增 2 个用例 + 必要 import/字段）

**禁止修改**：

- `src/main/java/com/hmdp/config/RabbitMQConfig.java` —— 默认容器工厂、交换器、队列、绑定、DLX **零改动**
  （验收时会比对它的 SHA-256，改动即视为越界）。理由：改默认工厂会波及主消费者；
  新建独立工厂能把影响面精确压到 DLQ 一个监听器上。
- `src/main/resources/application.yaml` 的 rabbit 配置、`pom.xml`、任何其他生产文件。
- **不得放宽、删除既有断言，不得 `@Disabled`**：5 条红灯必须**靠修复生产代码**转绿。

## Constraints

1. **`ReservationDlqListenerConfig` 内容要求**
   - `public static final String DLQ_CONTAINER_FACTORY = "reservationDlqListenerContainerFactory";`
   - `@Bean(name = DLQ_CONTAINER_FACTORY)` 的 `SimpleRabbitListenerContainerFactory`，
     形参为 `ConnectionFactory connectionFactory, SimpleRabbitListenerContainerFactoryConfigurer configurer`。
   - **顺序关键**：先 `configurer.configure(factory, connectionFactory)`，**之后**再
     `factory.setMessageConverter(new SimpleMessageConverter())`。Boot 的 configurer 会把容器里唯一的
     `MessageConverter`（也就是那个 Jackson 转换器）注入 factory，因此覆盖必须放在 configure 之后。
   - 错误处理器保持与默认工厂一致（`ConditionalRejectingErrorHandler` + `setRejectManual(true)`），
     使两者唯一差异就是转换器。
   - **不要加 `@Primary`**，不要改默认 bean 名；不要新依赖、不要升级 Spring AMQP。
   - 说明（可写进注释）：`SimpleMessageConverter` 对 `application/json` 只做 `new String(body, charset)`，
     不做二次反序列化；只有 `application/x-java-serialized-object` 才可能抛错，而那不是本项目自身的死信形态。
2. **`ReservationDlqConsumer`**：`@RabbitListener` 改为
   `queues = RabbitMQConfig.RESERVATION_CREATE_DLQ, containerFactory = ReservationDlqListenerConfig.DLQ_CONTAINER_FACTORY`。
   方法签名保持 `(Message message, Channel channel)`；ACK/NACK 语义保持（只有落库抛异常才
   `basicNack(tag, false, false)`，`basicAck` 留在 `try` 之外）。
3. **`extractReservationId` 的 catch**：非法 JSON 是死信的**正常**输入形态，改为不打堆栈的短告警
   （如 `log.warn("解析死信 reservationId 失败，按不可解析处理：{}", e.getMessage())`）。
4. **新增用例 A `shouldKeepRecordingDeadLettersAfterUnparseableOne`**（把"永久失明"钉死，修复前必红）
   - 先直接向 `RESERVATION_CREATE_DLQ` 发一条坏 JSON 死信
     （`rabbitTemplate.send("", RESERVATION_CREATE_DLQ, new Message(bytes, new MessageProperties()))`），
     断言 `waitForDlqRow(null)` 非空、`reservationId == null`、`failureReason` 为 `"PAYLOAD_UNPARSEABLE"`。
   - **紧接着**再发一条**合法 JSON** 死信（如 `{"reservationId":<reservationIdSeed>}`），
     断言 `waitForDlqRow(reservationId)` 非空——修复前这条必然拿不到行。
   - 最后 `assertDlqQueueEmpty()`。
   - 顺序必须严格串行：先坏后好（这是"失明"的复现条件）。
5. **新增用例 B `shouldKeepConsumingAfterLedgerWriteFailure`**（消费者必须在**自己的**落库失败后存活）
   - 用 `@SpyBean`（`org.springframework.boot.test.mock.mockito.SpyBean`）声明 `IReservationDlqService`，
     `Mockito.doThrow(new IllegalStateException("模拟落库失败")).doCallRealMethod().when(spy).recordDeadLetter(any(byte[].class), nullable(String.class));`
     （Mockito 3.3.3：`byte[]` 是普通参数位置，`any(byte[].class)` 安全；但**绝不能**把它写进变参位置）。
   - 发第一条死信 → 断言**该条不留行**（建议：轮询队列到 `getMessageCount()==0` 后，
     统计 `id > dlqBaselineId` 的行数为 0；不要用 8s 的 `waitForDlqRow(null)==null` 做"不存在"断言）。
   - 再发第二条死信（此时 spy 第 2 次调用走真实方法）→ 断言台账**有**该行，证明消费者没死。
   - 用例末尾 `Mockito.reset(spy)` 恢复行为（`@SpyBean` 是跨用例共享 bean，务必 reset，避免污染其他用例）。
6. **代码风格**：与既有文件一致（中文注释、`@Resource` 注入、plain `@Service` + 裸 `BaseMapper`）。

## Acceptance Criteria

- 坏 JSON / 空体死信能到达监听器并落台账（不再在监听方法之前被容器丢弃）。
- 新增用例 A、B 通过；**原 5 条红灯全部转绿**；既有 14 条绿灯保持绿 → 合计 **21 run / 0 failures / 0 errors / 0 skipped**。
- `RabbitMQConfig.java` 的 SHA-256 与改动前一致；拓扑未变。
- 全部 `basicNack` 第三参数仍为 `false`；监听方法签名仍为 `(Message, Channel)`。

## Validation

- 你**只能**执行：`export JAVA_HOME=/Users/eason/Library/Java/JavaVirtualMachines/corretto-1.8.0_504/Contents/Home && mvn -q clean test-compile`
  （**必须**先 export；默认 shell 的 JDK 25 会让 Lombok 1.18.20 注解处理崩掉，制造大量幻影错误）。
- 你的沙箱**连不上** localhost 的 MySQL/Redis/RabbitMQ，**无法运行** `ReservationMqTest`：
  **不要声称跑过集成测试**。要验证转换器/容器行为可在 `/tmp` 写独立 probe 或读 spring-rabbit 源码/字节码，
  但那不算集成验收结论 —— 集成验收由 Claude 侧执行。
- 汇报内容：改动文件清单 + 每个文件关键 diff 摘要 + `test-compile` 结果 +
  你对"为什么这样修能解掉永久失明"的判断 + 仍存在的不确定性。

## Review 要点（Claude 侧验收）

1. 跑真实 `ReservationMqTest`（需 MySQL/Redis/RabbitMQ 本机三件套）→ 必须 21/0/0/0。
2. `RabbitMQConfig.java` SHA-256 前缀比对未变。
3. 判别力实验（至少一条）：把 `ReservationDlqConsumer` 的 `containerFactory` 属性删掉（退回默认工厂）
   → 用例 A 必须变红，其余保持绿；验证完把工作区 SHA-256 复原。
4. 证据核查：`grep -n "basicNack" ` 第三参数全为 `false`；`grep -n "setMessageConverter"` 位于
   `configurer.configure` 之后。
