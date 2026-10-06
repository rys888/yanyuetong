# RabbitMQ 异步下单迁移 —— 交付报告（历史归档）

> **历史归档说明**：本文记录 2026-10-03 把「优惠券秒杀」异步下单链路从 Redis Stream 迁移到 RabbitMQ 的完整过程。
> Phase 10 已按重构要求**永久删除**秒杀/优惠券业务域，本文描述的业务代码**已不在仓库中**；
> 保留本文仅作为「消息可靠性设计」迁移过程的过程证据。**文中口令字面量已脱敏。**

> 日期：2026-10-03
> 项目：黑马点评 hm-dianping（Maven + Java 8，Spring Boot 2.3.12.RELEASE）
> 任务：把「优惠券秒杀」的异步下单消息队列从 Redis Stream 迁移到 RabbitMQ，对外接口、返回值、前端交互不变。

---

## 0. 开头假设

- RabbitMQ 按默认 `localhost:5672`、`guest/guest`、`virtual-host /` 配置，需要先用 Docker 启动（见第 6 节）。
- 项目里 MySQL/Redis 配置与本机不一致（本机口令见本地配置，**不入库**），属于既有配置，本次未改动；RabbitMQ 配置为全新追加。
- 消费端「处理失败 → 死信」链路通过反编译 Spring AMQP 2.2.18 字节码核对确认（手动 ack 下默认 recoverer 不会真正 nack、会卡死消费者，故改为监听器显式 basicNack，并在容器级用 `ConditionalRejectingErrorHandler(rejectManual=true)` 兜住反序列化失败），运行时行为需按第 7 节实测。

---

## 1. 方案决策

### 1.1 为什么原子性会被打破

原实现中 `xadd` 写在 `seckill.lua` 里，让「扣库存 + 标记用户 + 发消息」是**一个原子操作**，要么全成要么全不成。Lua 脚本无法向 RabbitMQ 发消息，所以迁移后这个原子性必然被打破。

### 1.2 选型：方案 A

**方案 A**：Lua 只保留「判断库存 → 判断重复 → 扣库存 → 标记用户」（删掉 `xadd`），Java 拿到返回 0 后再用 `RabbitTemplate` 发 RabbitMQ，配 `publisher-confirm-type: correlated` + `CorrelationData` 同步等待确认，失败时回滚 Redis。

**否决 B / C 的理由**：
- 方案 B（Lua 继续写 Stream 当 outbox，另起搬运线程投递 RabbitMQ）：多一个中间状态和搬运线程，延迟更高、组件更多，且等于没删掉 Redis Stream 代码，单机教学项目性价比低。
- 方案 C：没有比 A 更简单的方案能在不加新组件的前提下保证资格判断原子性。

### 1.3 具体失败窗口

Lua 返回 0（Redis 已扣库存、已标记用户）**之后**，Java 侧 `RabbitTemplate` 发送失败。这是本次迁移引入的唯一新失败窗口。

### 1.4 三种异常场景下的行为

| 场景 | 行为 |
|---|---|
| 消息重复投递 | 消费者幂等 + `(user_id, voucher_id)` 唯一索引兜底；查到已下单直接 `ack` 丢弃 |
| 消息丢失 | `publisher-confirm` + 发送后 `correlationData.getFuture().get(5s)` 同步等待；nack / 超时 / 连接失败都进 catch → 回滚 Redis → 返回「下单失败，请重试」 |
| 消费者处理失败 | 监听器显式 `basicNack(requeue=false)` → 直接进死信队列；拿锁等待超时同样判为无法自愈进死信（§4.3 / §4.4），全程不使用 `requeue=true` |

---

## 2. 改动清单

| 文件 | 操作 | 原因 |
|---|---|---|
| `pom.xml` | 修改 | 加 `spring-boot-starter-amqp`（版本由 parent 管理） |
| `src/main/resources/application.yaml` | 修改 | 新增 `spring.rabbitmq` 段 |
| `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java` | 修改 | 生产者改发 RabbitMQ、删 Redis Stream 消费者、`@RabbitListener` 消费者、`createVoucherOrder` 返回二态枚举（ACK/DEAD_LETTER）、新增 `rollbackSeckill` |
| `src/main/resources/seckill.lua` | 修改 | 删 `xadd` 与 `ARGV[3]`（orderId 不再进脚本） |
| `src/main/java/com/hmdp/config/RabbitMQConfig.java` | 新增 | 交换机/队列/DLX 拓扑 + JSON 转换器 + confirm/return 回调 + 自定义容器工厂（`ConditionalRejectingErrorHandler(rejectManual=true)`） |
| `src/main/resources/seckillRollback.lua` | 新增 | 发送失败时原子回滚「库存 +1 + 移除用户标记」 |

**未改动**：五段被注释的历史代码（原样保留）、缓存/分布式锁/达人探店/好友关注/附近商户/签到/UV 统计等其它模块、`hmdp.sql`、entity、`RedisIdWorker`、`RedisConstants`。

---

## 3. 完整代码

### 3.1 `pom.xml`（新增依赖）

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

### 3.2 `application.yaml`（新增 spring.rabbitmq 段）

```yaml
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
    virtual-host: /
    publisher-confirm-type: correlated
    publisher-returns: true
    listener:
      simple:
        acknowledge-mode: manual
        prefetch: 1
        # 手动 ack 下默认 recoverer 不会真正 nack、会卡死消费者，故关闭自动重试，由监听器显式 basicNack 进死信
        retry:
          enabled: false
```

### 3.3 `RabbitMQConfig.java`（新文件，完整）

```java
package com.hmdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

@Slf4j
@Configuration
public class RabbitMQConfig {

    public static final String SECKILL_ORDER_EXCHANGE = "seckill.order.exchange";
    public static final String SECKILL_ORDER_QUEUE = "seckill.order.queue";
    public static final String SECKILL_ORDER_ROUTING_KEY = "seckill.order";
    public static final String SECKILL_ORDER_DLX_EXCHANGE = "seckill.order.dlx.exchange";
    public static final String SECKILL_ORDER_DLX_QUEUE = "seckill.order.dlx.queue";
    public static final String SECKILL_ORDER_DLX_ROUTING_KEY = "seckill.order.dlx";

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Bean
    public DirectExchange seckillOrderExchange() {
        return new DirectExchange(SECKILL_ORDER_EXCHANGE, true, false);
    }

    @Bean
    public Queue seckillOrderQueue() {
        return QueueBuilder.durable(SECKILL_ORDER_QUEUE)
                .withArgument("x-dead-letter-exchange", SECKILL_ORDER_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", SECKILL_ORDER_DLX_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding seckillOrderBinding() {
        return BindingBuilder.bind(seckillOrderQueue())
                .to(seckillOrderExchange())
                .with(SECKILL_ORDER_ROUTING_KEY);
    }

    @Bean
    public DirectExchange seckillOrderDlxExchange() {
        return new DirectExchange(SECKILL_ORDER_DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue seckillOrderDlxQueue() {
        return QueueBuilder.durable(SECKILL_ORDER_DLX_QUEUE).build();
    }

    @Bean
    public Binding seckillOrderDlxBinding() {
        return BindingBuilder.bind(seckillOrderDlxQueue())
                .to(seckillOrderDlxExchange())
                .with(SECKILL_ORDER_DLX_ROUTING_KEY);
    }

    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        // 复用 Boot 默认配置：应用 yaml 里的 acknowledge-mode=manual、prefetch=1、retry.enabled=false，并设置消息转换器
        configurer.configure(factory, connectionFactory);
        // 手动 ack 下，反序列化失败等「监听器方法体之前」的致命异常默认会卡死消费者；
        // rejectManual=true 会把这类致命异常转成 basicNack(requeue=false) 进死信队列
        ConditionalRejectingErrorHandler errorHandler = new ConditionalRejectingErrorHandler();
        errorHandler.setRejectManual(true);
        factory.setErrorHandler(errorHandler);
        return factory;
    }

    @PostConstruct
    public void init() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("秒杀订单消息发送失败，correlationData={}，cause={}", correlationData, cause);
            }
        });
        rabbitTemplate.setReturnCallback((message, replyCode, replyText, exchange, routingKey) ->
                log.error("秒杀订单消息路由失败，replyCode={}，replyText={}，exchange={}，routingKey={}",
                        replyCode, replyText, exchange, routingKey));
        rabbitTemplate.setMandatory(true);
    }
}
```

### 3.4 `seckill.lua`（修改后完整）

```lua
-- 1.参数列表
-- 1.1.优惠券id
local voucherId = ARGV[1]
-- 1.2.用户id
local userId = ARGV[2]

-- 2.数据key
-- 2.1.库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2.订单key
local orderKey = 'seckill:order:' .. voucherId

-- 3.脚本业务
-- 3.1.判断库存是否充足 get stockKey
if(tonumber(redis.call('get', stockKey)) <= 0) then
    -- 3.2.库存不足，返回1
    return 1
end
-- 3.2.判断用户是否下单 SISMEMBER orderKey userId
if(redis.call('sismember', orderKey, userId) == 1) then
    -- 3.3.存在，说明是重复下单，返回2
    return 2
end
-- 3.4.扣库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
-- 3.5.下单（保存用户）sadd orderKey userId
redis.call('sadd', orderKey, userId)
return 0
```

### 3.5 `seckillRollback.lua`（新文件，完整）

```lua
-- 回滚秒杀资格：库存 +1，并移除用户下单标记（与 seckill.lua 的 key 前缀保持一致）
-- 1.参数
local voucherId = ARGV[1]
local userId = ARGV[2]

-- 2.数据key
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 3.回滚
redis.call('incrby', stockKey, 1)
redis.call('srem', orderKey, userId)
return 0
```

### 3.6 `VoucherOrderServiceImpl.java`（关键改动，其余原样）

**生产者** `seckillVoucher`（返回文案、`orderId` 语义不变）：

```java
@Override
public Result seckillVoucher(Long voucherId) {
    Long userId = UserHolder.getUser().getId();
    long orderId = redisIdWorker.nextId("order");
    // 1.执行lua脚本（判断资格 + 扣库存 + 标记用户）
    Long result = stringRedisTemplate.execute(
            SECKILL_SCRIPT,
            Collections.emptyList(),
            voucherId.toString(), userId.toString()
    );
    int r = result.intValue();
    // 2.判断结果是否为0
    if (r != 0) {
        return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
    }
    VoucherOrder voucherOrder = new VoucherOrder();
    voucherOrder.setId(orderId);
    voucherOrder.setUserId(userId);
    voucherOrder.setVoucherId(voucherId);
    try {
        CorrelationData correlationData = new CorrelationData(String.valueOf(orderId));
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.SECKILL_ORDER_EXCHANGE,
                RabbitMQConfig.SECKILL_ORDER_ROUTING_KEY,
                voucherOrder,
                correlationData
        );
        // 2.2.18 的 convertAndSend 不会同步等 confirm，这里显式等待，失败时进 catch 回滚
        CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);
        if (!confirm.isAck()) {
            throw new AmqpException("秒杀订单消息未获得 RabbitMQ 确认：" + confirm.getReason());
        }
        if (correlationData.getReturnedMessage() != null) {
            throw new AmqpException("秒杀订单消息无法路由到队列");
        }
    } catch (Exception e) {
        log.error("秒杀订单消息发送失败，voucherId={}，userId={}，orderId={}", voucherId, userId, orderId, e);
        rollbackSeckill(voucherId, userId);
        return Result.fail("下单失败，请重试");
    }
    // 3.返回订单id
    return Result.ok(orderId);
}

private void rollbackSeckill(Long voucherId, Long userId) {
    try {
        stringRedisTemplate.execute(
                SECKILL_ROLLBACK_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
    } catch (Exception e) {
        log.error("回滚秒杀资格失败，voucherId={}，userId={}", voucherId, userId, e);
    }
}
```

**消费者**（替换原 `VoucherOrderHandler` / `init` / `@PostConstruct`）：

```java
/** 订单消息处理结果 */
private enum OrderHandleResult {
    /** 处理成功或幂等重复，确认消息 */
    ACK,
    /** 无法恢复（库存不足/等待拿锁超时），进死信队列人工排查 */
    DEAD_LETTER
}

@RabbitListener(queues = RabbitMQConfig.SECKILL_ORDER_QUEUE)
public void onOrderMessage(@Payload VoucherOrder voucherOrder, Channel channel, Message message) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    try {
        OrderHandleResult result = createVoucherOrder(voucherOrder);
        if (result == OrderHandleResult.ACK) {
            // 处理成功或幂等重复下单，确认消息
            channel.basicAck(deliveryTag, false);
        } else {
            // 无法恢复的错误（库存不足/等待拿锁超时）：不 requeue，直接进死信队列
            channel.basicNack(deliveryTag, false, false);
        }
    } catch (Exception e) {
        // 处理过程抛异常：不 requeue，进死信队列，避免无限重投打死消费者
        log.error("秒杀订单处理异常，orderId={}，进入死信队列", voucherOrder.getId(), e);
        channel.basicNack(deliveryTag, false, false);
    }
}
```

**`createVoucherOrder`**（业务逻辑保留，返回值 `void` → 二态枚举 `OrderHandleResult`）：

```java
private OrderHandleResult createVoucherOrder(VoucherOrder voucherOrder) {
    Long userId = voucherOrder.getUserId();
    Long voucherId = voucherOrder.getVoucherId();
    RLock redisLock = redissonClient.getLock("lock:order:" + userId);
    // 等待最多 2 秒抢锁；leaseTime=10 秒（>0 会关闭 watchdog），即使持有者线程挂死锁也最多 10 秒自动过期，有上界
    boolean isLock;
    try {
        isLock = redisLock.tryLock(2, 10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
        // 等待被中断，视为拿不到锁，进死信人工排查
        Thread.currentThread().interrupt();
        return OrderHandleResult.DEAD_LETTER;
    }
    if (!isLock) {
        // 等 2 秒仍拿不到锁，说明持有者异常，进死信人工排查
        log.error("获取订单锁超时，进入死信队列，userId={}", userId);
        return OrderHandleResult.DEAD_LETTER;
    }
    try {
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.warn("用户已下单，幂等确认，userId={}，voucherId={}", userId, voucherId);
            return OrderHandleResult.ACK;   // 已下单，幂等确认丢弃
        }
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足，进入死信队列，voucherId={}", voucherId);
            return OrderHandleResult.DEAD_LETTER;   // 无法自愈，进死信
        }
        save(voucherOrder);
        return OrderHandleResult.ACK;
    } finally {
        redisLock.unlock();
    }
}
```

> 返回语义：`ACK` = 确认（成功 / 幂等重复）；`DEAD_LETTER` = 库存不足 / 等待拿锁超时等无法自愈，进死信；抛异常 = 进死信。全程不使用 `requeue=true`。

---

## 4. 关键设计说明

### 4.1 为什么 confirm 要显式等待

Spring AMQP 2.2.18 的 `RabbitTemplate.doSend` 只调用 `setupConfirm` 异步注册确认，**不会**同步阻塞等待 confirm。因此若只调 `convertAndSend` 不等待，nack / 超时不会被捕获，回滚补偿也就失效。所以生产者用 `correlationData.getFuture().get(5, TimeUnit.SECONDS)` 显式等待，确认失败即抛异常进 catch。

### 4.2 JSON 转换器如何生效

定义唯一的 `Jackson2JsonMessageConverter` bean，并**注入 Spring Boot 自动装配的 `ObjectMapper`**（`new Jackson2JsonMessageConverter(objectMapper)`）。这样转换器会复用 Boot 已配置好的 mapper：自动注册 `JavaTimeModule`（否则 `VoucherOrder` 里 5 个 `LocalDateTime` 字段一旦非空就序列化/反序列化失败）、并遵守 `spring.jackson.default-property-inclusion: non_null`。Spring Boot 2.3.12 的 `rabbitTemplateConfigurer` 通过 `ObjectProvider.getIfUnique()` 把它注入到 `RabbitTemplate`，监听容器工厂也使用它，因此**生产端 JSON 与消费端 `@Payload` 反序列化一致**，避免 Java 序列化与 JSON 的错配。

### 4.3 手动 ack 下，失败消息如何进死信（为什么不依赖自动重试）

处理失败若 `requeue=true`，同一坏消息会被立即重新投递、无限循环打死消费者。所以「无法恢复」的失败要 `requeue=false` 进死信。

但要注意一个 Spring AMQP 2.2.x 的坑：`acknowledge-mode: manual` 时，若让监听器抛异常、指望默认 `RejectAndDontRequeueRecoverer` 在重试耗尽后拒绝消息，**根本进不了死信**。反编译确认——该 recoverer 抛的是 `ListenerExecutionFailedException("Retry Policy Exhausted", new AmqpRejectAndDontRequeueException(cause))`（内层单参构造，`rejectManual=false`），而 `ContainerUtils.isRejectManual(ex)` 只对**顶层异常**做 `instanceof`、不穿透 cause 链，于是 `rollbackOnExceptionIfNecessary` 里 `ackRequired` 为 false，**不执行 `basicNack`**，消息永远 unacked、`prefetch=1` 下消费者直接卡死。

因此这里改为：**关闭自动重试（`retry.enabled: false`）**，由监听器显式决策——
- 成功 / 幂等重复 → `basicAck`；
- 库存不足 / 等待拿锁超时 / 处理抛异常（无法自愈）→ `basicNack(requeue=false)` 进死信队列，供人工排查/重投。

还有一个覆盖不到的角落：「反序列化失败」发生在 `toMessagingMessage` 里（**进入监听器方法体之前**），监听器内部的 try/catch 拦不住，手动 ack 下会卡死消费者。因此在容器级配置 `ConditionalRejectingErrorHandler` 并把 `rejectManual` 置 true：反编译确认 `DefaultExceptionStrategy.isFatal` 会把 `ListenerExecutionFailedException`（cause 为 `MessageConversionException`）判为致命，进而抛 `AmqpRejectAndDontRequeueException(rejectManual=true)`，`rollbackOnExceptionIfNecessary` 据此执行 `basicNack(requeue=false)` 进死信。注意 `@Payload byte[]` 之类的改法无效——`toMessagingMessage` 在进方法体前就已按 `__TypeId__` 头做 JSON 反序列化，与 `@Payload` 类型无关。

### 4.4 Redisson 锁是否还需要

**需要保留，但角色变了**：多实例 / 多消费者并发下它仍是「快路径去重」，把同一用户的消息串行化、减少无效 DB 压力；真正的正确性保障交给唯一索引。`prefetch=1` 下锁几乎不竞争。

锁改用 `tryLock(2, 10, TimeUnit.SECONDS)` 阻塞等待：`waitTime=2s` 给同一用户的多条消息一个等待窗口，`leaseTime=10s`（>0）**关闭 watchdog**，即使持有者线程挂死，锁也最多 10 秒自动过期、有上界（无参 `tryLock()` 是 `waitTime=0` + watchdog，持有者挂死会无限续期）。等 2 秒仍拿不到，说明持有者异常，直接判 `DEAD_LETTER` 进死信人工排查——**不用 `requeue=true`**，因为 RabbitMQ 的 requeue 无延迟、消息立即回到队头，`prefetch=1` 下会形成热循环打死消费者。

### 4.5 `prefetch=1` 的理由

每个消费者一次只处理一条消息，配合手动 ack，避免慢消费者堆积大量未确认消息，也让「一人一单」的锁竞争降到最低。

---

## 5. 唯一索引（最后一道防线）

```sql
ALTER TABLE `tb_voucher_order` ADD UNIQUE KEY `uk_user_voucher` (`user_id`, `voucher_id`);
```

- **作用**：兜底消息重复投递、锁失效等任何导致同一用户同一券重复落库的场景。
- **对现有代码影响**：`count > 0` 预检查不变；`save()` 仅在（已被分布式锁挡掉的）极端竞态下抛 `DuplicateKeyException`，会被监听器 catch 后 `basicNack(requeue=false)` → 进死信，可见而不静默。
- **注意**：若表里已有同 `(user_id, voucher_id)` 的重复数据会执行失败，需先清理。

---

## 6. 改造步骤

1. 启动 RabbitMQ（带管理台）：
   ```bash
   docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 \
     -e RABBITMQ_DEFAULT_USER=guest -e RABBITMQ_DEFAULT_PASS=guest \
     rabbitmq:3-management
   ```
   管理台：`http://localhost:15672`（guest/guest）。
2. 依赖已加好，`mvn` 会自动拉取 `spring-boot-starter-amqp`。
3. 执行唯一索引 SQL（见第 5 节）。
4. 用 JDK 8 启动（老 Lombok 在 JDK 17/25 会挂）：
   ```bash
   export JAVA_HOME=/Users/eason/Library/Java/JavaVirtualMachines/corretto-1.8.0_504/Contents/Home
   mvn spring-boot:run
   ```
5. 首次启动会自动声明 `seckill.order.*` 交换机/队列/DLX。

---

## 7. 验证清单

- **1000 并发抢 100 库存**：
  ```sql
  SELECT COUNT(*) FROM tb_voucher_order;                 -- 正好 100
  SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?;  -- 0
  ```
  `redis-cli GET seckill:stock:{voucherId}` → `0`。
- **同一用户并发只一单**：同 token 并发打 `/voucher-order/seckill/{id}`，`tb_voucher_order` 中该 `(user_id, voucher_id)` 只有一条。
- **消费异常 → 死信**：临时让 `createVoucherOrder` 抛异常（或停 MySQL），管理台 `seckill.order.queue` 的 `Ready` 先增，消息直接转入 `seckill.order.dlx.queue`（无自动重试），且 `seckill.order.queue` 的 `Unacked` 归 0。拿锁超时的场景同样进死信（不会 `requeue=true`）。
- **反序列化失败 → 死信**：手动向 `seckill.order.queue` 投一条非法 JSON 的消息（带 `__TypeId__` 头但 body 不可反序列化），确认它被 `ConditionalRejectingErrorHandler` 转 `basicNack(requeue=false)` 进死信、消费者不卡死。
- **停机积压恢复**：先发消息看 `Ready` 堆积，启动应用后 `Ready` 清空、`Ack` 增加。
- **RabbitMQ 挂掉（如实）**：前端返回 `Result.fail("下单失败，请重试")`，不是「返回成功但订单丢了」的静默丢失。
- **管理台核对**：`seckill.order.queue` 的 `Ready/Unacked/Total` 与 `Message rates` 的 `ack/deliver` 正常增长。

---

## 8. 风险与回滚

**已知风险（保留，非新引入、未越界修改）**：
1. `扣库存 + save` 未包 `@Transactional`，极端 DB 崩溃可能「扣了库存没落单」或反之，与迁移前等价。
2. 回滚脚本非幂等 + confirm 超时为「模糊态」：极端下 broker 已收但 confirm 超时，会回滚 Redis 却已发出消息。唯一索引 + DB `stock>0` 兜底限制损害，这是 at-least-once 补偿的固有代价。

**回滚到 Redis Stream 版**：
```bash
git checkout -- pom.xml src/main/resources/application.yaml \
  src/main/resources/seckill.lua \
  src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java
rm src/main/java/com/hmdp/config/RabbitMQConfig.java \
   src/main/resources/seckillRollback.lua
```

---

## 9. 复核结论

- 编译：JDK 8 下 `mvn clean compile`，72 源文件，`BUILD SUCCESS`。
- 范围：仅 4 文件修改 + 2 文件新增，五段历史注释代码原样（起始标记 68/219/249/327/381 全部保留，逐字一致）、`pom.xml` 仅加 `spring-boot-starter-amqp`、其它模块零改动。
- 关键行为核对（拆包 / 反编译确认）：
  - Spring Boot 2.3.12 `RabbitTemplateConfigurer` 将唯一 `MessageConverter` bean 注入 `RabbitTemplate`（JSON 生产/消费一致）；
  - Spring AMQP 2.2.18 `RabbitTemplate.doSend` 不阻塞 confirm，显式 `get(5s)` 等待必要且正确（future 经 `addPendingConfirm` 完成）；
  - 手动 ack + 默认 `RejectAndDontRequeueRecoverer` 存在「不会真正 nack、卡死消费者」的坑，已改为监听器显式 `basicNack`（§4.3）；转换器已复用 Boot `ObjectMapper`（§4.2）；
  - 全程不使用 `requeue=true`：锁用 `tryLock(2, 10, TimeUnit.SECONDS)` 有界等待，超时/中断进死信（§4.4）；反序列化失败由容器级 `ConditionalRejectingErrorHandler(rejectManual=true)` 兜底进死信（§4.3），且 `DefaultExceptionStrategy` 把 `ListenerExecutionFailedException(MessageConversionException)` 判为致命（已反编译确认）。
- 未实测项：消费端「处理失败 → 死信」的运行时链路，需按第 7 节起 RabbitMQ 后验证（代码层面已确认 `basicNack(requeue=false)` 会触发 DLX，容器错误处理器的 rejectManual 路径亦已由字节码核对）。
