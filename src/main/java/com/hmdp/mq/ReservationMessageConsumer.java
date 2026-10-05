package com.hmdp.mq;

import com.hmdp.config.RabbitMQConfig;
import com.hmdp.entity.Reservation;
import com.hmdp.entity.ReservationRequest;
import com.hmdp.enums.ReservationPersistResult;
import com.hmdp.enums.ReservationRequestStatus;
import com.hmdp.service.IReservationPersistenceService;
import com.hmdp.service.IReservationRequestService;
import com.hmdp.utils.RedisConstants;
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

@Slf4j
@Component
public class ReservationMessageConsumer {

    @Resource
    private IReservationPersistenceService reservationPersistenceService;

    @Resource
    private IReservationRequestService reservationRequestService;

    @Resource
    private RedissonClient redissonClient;

    @RabbitListener(queues = RabbitMQConfig.RESERVATION_CREATE_QUEUE)
    public void onReservationMessage(
            @Payload ReservationMessageDTO messageDTO,
            Channel channel,
            Message message) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
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

            ReservationPersistResult result = persistWithLock(messageDTO);
            if (result == null) {
                log.error("获取预约锁超时，消息进入死信队列：userId={}，reservationId={}",
                        messageDTO.getUserId(), messageDTO.getReservationId());
                recordFailReasonSafely(messageDTO.getReservationId(), "LOCK_TIMEOUT");
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            switch (result) {
                case PERSISTED:
                case DUPLICATE:
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
            // 同一用户的消息已被 persistWithLock 串行化，此处可达的冲突是 reservationId 主键冲突，不是 uk_user_batch。
            // 若 ACK，这条未落库的消息会静默消失且 DLQ 无痕迹；必须 NACK 交给死信治理。
            log.error("预约记录主键冲突，消息进入死信队列：reservationId={}，userId={}",
                    messageDTO.getReservationId(), messageDTO.getUserId(), e);
            channel.basicNack(deliveryTag, false, false);
        } catch (Exception e) {
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
            boolean confirmed = reservationRequestService.casStatus(
                    requestId,
                    ReservationRequestStatus.CONFIRMED,
                    ReservationRequestStatus.nonTerminalStatuses()
                            .toArray(new ReservationRequestStatus[0]));
            if (!confirmed) {
                log.warn("预约已落库但账本置为 CONFIRMED 未生效：requestId={}", requestId);
            }
        } catch (RuntimeException e) {
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
        RLock lock = redissonClient.getLock(
                RedisConstants.LOCK_RESERVATION_KEY + messageDTO.getUserId());
        boolean locked = lock.tryLock(2, 10, TimeUnit.SECONDS);
        if (!locked) {
            return null;
        }
        try {
            Reservation reservation = new Reservation()
                    .setId(messageDTO.getReservationId())
                    .setUserId(messageDTO.getUserId())
                    .setBatchId(messageDTO.getBatchId())
                    .setCreateTime(messageDTO.getCreateTime());
            return reservationPersistenceService.persist(reservation);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
