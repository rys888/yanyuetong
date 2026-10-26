package com.yanyuetong.mq;

import com.yanyuetong.config.RabbitMQConfig;
import com.yanyuetong.entity.Reservation;
import com.yanyuetong.entity.ReservationRequest;
import com.yanyuetong.enums.ReservationPersistResult;
import com.yanyuetong.enums.ReservationRequestStatus;
import com.yanyuetong.enums.ReservationStatus;
import com.yanyuetong.service.IReservationPersistenceService;
import com.yanyuetong.service.IReservationRequestService;
import com.yanyuetong.utils.RedisConstants;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 预约创建消费者，将 Redis 预占后的消息幂等地落入 MySQL，并推进请求账本。
 * 消息使用手动 ACK：已落库、幂等重复或已补偿终结时确认，业务处理故障拒绝至 DLQ。
 */
@Slf4j
@Component
public class ReservationMessageConsumer {

    @Resource
    private IReservationPersistenceService reservationPersistenceService;

    @Resource
    private IReservationRequestService reservationRequestService;

    @Resource
    private RedissonClient redissonClient;

    /**
     * 处理一条预约消息。账本已 COMPENSATED 时直接 ACK，因为名额已归还，再落库会造成超发。
     *
     * @param messageDTO 预约请求快照
     * @param channel RabbitMQ 手动确认通道
     * @param message 用于取得 deliveryTag 的原始消息
     */
    @RabbitListener(queues = RabbitMQConfig.RESERVATION_CREATE_QUEUE)
    public void onReservationMessage(
            @Payload ReservationMessageDTO messageDTO,
            Channel channel,
            Message message) throws IOException {
        // 1、取当前 Channel 的投递编号，后面的 ACK/NACK 只处理这一条消息，不能用预约 ID 代替。
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            // 2、检查请求是否已由对账终结：COMPENSATED 不再落库；账本缺失则继续尝试保存预约事实。
            // 此查询发生在用户锁外，并未与对账互斥，不能把它理解为跨组件的原子状态检查。
            ReservationRequest request = reservationRequestService.getByRequestId(
                    messageDTO.getReservationId());
            if (request == null) {
                log.warn("预约请求账本不存在，继续落库：reservationId={}，userId={}",
                        messageDTO.getReservationId(), messageDTO.getUserId());
            } else if (request.getStatus() == ReservationRequestStatus.COMPENSATED) {
                // 该请求已被对账判定终结并归还名额；若此时仍落库，就会形成「MySQL 有行 + Redis 名额已归还」的超发。
                log.warn("预约请求已被补偿，拒绝落库并确认消息：reservationId={}，userId={}",
                        messageDTO.getReservationId(), messageDTO.getUserId());
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 3、获取用户分布式锁后进入 MySQL 事务；同一用户消息串行处理，数据库约束再作最终兜底。
            ReservationPersistResult result = persistWithLock(messageDTO);
            if (result == null) {
                log.error("获取预约锁超时，消息进入死信队列：userId={}，reservationId={}",
                        messageDTO.getUserId(), messageDTO.getReservationId());
                recordFailReasonSafely(messageDTO.getReservationId(), "LOCK_TIMEOUT");
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            // 4、落库或业务幂等重复后先尝试确认账本，再 ACK；账本更新失败不撤销已提交的预约事实。
            // 名额冲突、锁超时则 NACK(false,false)：只拒绝当前消息、不重新入主队列，由 DLX 转送死信。
            switch (result) {
                case PERSISTED:
                case DUPLICATE:
                    // DUPLICATE 表示业务事实已存在，对于至少一次投递与 DLQ 重放都应视为幂等成功。
                    markConfirmedSafely(messageDTO.getReservationId());
                    channel.basicAck(deliveryTag, false);
                    break;
                case QUOTA_CONFLICT:
                    recordFailReasonSafely(messageDTO.getReservationId(), "QUOTA_CONFLICT");
                    channel.basicNack(deliveryTag, false, false);
                    break;
                default:
                    throw new IllegalStateException("未知的预约持久化结果：" + result);
            }
        } catch (DuplicateKeyException e) {
            // 5、唯一键异常不能一概当作重复成功，主键相同但归属不同也可能冲突。
            // 下层事务已因异常回滚；这里拒绝至死信，保留排查证据，而不是静默 ACK。
            log.error("预约记录主键冲突，消息进入死信队列：reservationId={}，userId={}",
                    messageDTO.getReservationId(), messageDTO.getUserId(), e);
            channel.basicNack(deliveryTag, false, false);
        } catch (Exception e) {
            // 6、记录业务失败原因供 DLQ 落账优先读取；若是线程中断，还要保留中断标记。
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("预约消息处理异常，进入死信队列：reservationId={}",
                    messageDTO.getReservationId(), e);
            recordFailReasonSafely(
                    messageDTO.getReservationId(),
                    "PERSIST_EXCEPTION:" + e.getClass().getSimpleName());
            channel.basicNack(deliveryTag, false, false);
        }
    }

    private void markConfirmedSafely(Long requestId) {
        try {
            // 1、允许任一非终态转 CONFIRMED：消费可能早于发布线程回写 PUBLISHED。
            // CAS 不允许覆盖 COMPENSATED 等终态；它保护账本写入，但不把前面的落库纳入同一事务。
            boolean confirmed = reservationRequestService.casStatus(
                    requestId,
                    ReservationRequestStatus.CONFIRMED,
                    ReservationRequestStatus.nonTerminalStatuses()
                            .toArray(new ReservationRequestStatus[0]));
            if (!confirmed) {
                log.warn("预约已落库但账本置为 CONFIRMED 未生效：requestId={}", requestId);
            }
        } catch (RuntimeException e) {
            // 2、预约事务已经提交，只记录账本故障；后续对账可根据事实表补写 CONFIRMED。
            log.error("预约已落库但账本置为 CONFIRMED 异常：requestId={}", requestId, e);
        }
    }

    private void recordFailReasonSafely(Long requestId, String failReason) {
        try {
            boolean recorded = reservationRequestService.recordFailReason(requestId, failReason);
            if (!recorded) {
                log.warn("预约消息失败原因写入未生效：requestId={}，failReason={}",
                        requestId, failReason);
            }
        } catch (RuntimeException e) {
            log.error("预约消息失败原因写入异常：requestId={}，failReason={}",
                    requestId, failReason, e);
        }
    }

    private ReservationPersistResult persistWithLock(ReservationMessageDTO messageDTO)
            throws InterruptedException {
        // 1、锁粒度是用户而非消息，同一用户不同批次也会串行，减少重复预检与插入之间的竞态。
        // 最多等待 2 秒，租期固定 10 秒；显式指定租期后没有看门狗续期，慢事务可能超过锁有效期。
        RLock lock = redissonClient.getLock(
                RedisConstants.LOCK_RESERVATION_KEY + messageDTO.getUserId());
        boolean locked = lock.tryLock(2, 10, TimeUnit.SECONDS);
        if (!locked) {
            return null;
        }
        try {
            // 2、从消息快照构造 RESERVED 事实，再经另一个 Spring Bean 调用事务方法，确保事务代理生效。
            Reservation reservation = new Reservation()
                    .setId(messageDTO.getReservationId())
                    .setUserId(messageDTO.getUserId())
                    .setBatchId(messageDTO.getBatchId())
                    .setStatus(ReservationStatus.RESERVED)
                    .setCreateTime(messageDTO.getCreateTime());
            // 下层方法成功返回时 MySQL 事务已提交；锁不覆盖 Redis 预占，也不覆盖外层账本确认与 ACK。
            return reservationPersistenceService.persist(reservation);
        } finally {
            // 3、无论成功或异常都释放自己的锁；若租期已过，不能尝试释放其他线程当前持有的锁。
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
