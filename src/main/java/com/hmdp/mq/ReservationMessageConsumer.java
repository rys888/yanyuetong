package com.hmdp.mq;

import com.hmdp.config.RabbitMQConfig;
import com.hmdp.entity.Reservation;
import com.hmdp.enums.ReservationPersistResult;
import com.hmdp.service.IReservationPersistenceService;
import com.hmdp.utils.RedisConstants;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
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
    private RedissonClient redissonClient;

    @RabbitListener(queues = RabbitMQConfig.RESERVATION_CREATE_QUEUE)
    public void onReservationMessage(
            @Payload ReservationMessageDTO messageDTO,
            Channel channel,
            Message message) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            ReservationPersistResult result = persistWithLock(messageDTO);
            if (result == null) {
                log.error("获取预约锁超时，消息进入死信队列：userId={}，reservationId={}",
                        messageDTO.getUserId(), messageDTO.getReservationId());
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            switch (result) {
                case PERSISTED:
                case DUPLICATE:
                    channel.basicAck(deliveryTag, false);
                    break;
                case QUOTA_CONFLICT:
                    channel.basicNack(deliveryTag, false, false);
                    break;
                default:
                    throw new IllegalStateException("未知的预约持久化结果：" + result);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("预约消息处理异常，进入死信队列：reservationId={}",
                    messageDTO.getReservationId(), e);
            channel.basicNack(deliveryTag, false, false);
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
