package com.yanyuetong.mq;

import com.yanyuetong.config.RabbitMQConfig;
import com.yanyuetong.config.ReservationDlqListenerConfig;
import com.yanyuetong.service.IReservationDlqService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 使用原始 Message 消费死信，避免反序列化失败的消息再次经过 Jackson 并形成失败循环。
 */
@Slf4j
@Component
public class ReservationDlqConsumer {

    @Resource
    private IReservationDlqService reservationDlqService;

    /**
     * 将死信原始字节与 x-death 原因写入治理台账。
     * 只有台账落库成功才 ACK；落库失败时拒绝且不重入队，避免 DLQ 自身形成无限循环。
     */
    @RabbitListener(
            queues = RabbitMQConfig.RESERVATION_CREATE_DLQ,
            containerFactory = ReservationDlqListenerConfig.DLQ_CONTAINER_FACTORY)
    public void onDeadLetter(Message message, Channel channel) throws IOException {
        // 1、直接读取原始字节，不要求消息能转成 ReservationMessageDTO，非法 JSON 也必须留下证据。
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        byte[] body = message.getBody();
        try {
            // 2、保存消息体和 broker 的 x-death 原因；服务会优先采用请求账本里的具体业务失败原因。
            reservationDlqService.recordDeadLetter(body, extractXDeathReason(message));
        } catch (Exception e) {
            String payload = body == null
                    ? null
                    : new String(body, StandardCharsets.UTF_8);
            // DLQ 没有后继死信交换机，拒绝后日志是消息永久丢弃前的最后痕迹，必须保留完整原文。
            log.error("死信台账落库失败，消息将永久丢弃：payload={}", payload, e);
            channel.basicNack(deliveryTag, false, false);
            return;
        }
        // 3、台账插入成功才确认消费。ACK 仅代表死信证据已保存，不代表预约成功或名额已补偿。
        channel.basicAck(deliveryTag, false);
    }

    private String extractXDeathReason(Message message) {
        try {
            List<Map<String, ?>> deaths =
                    message.getMessageProperties().getXDeathHeader();
            if (deaths == null || deaths.isEmpty() || deaths.get(0) == null) {
                return null;
            }
            Object reason = deaths.get(0).get("reason");
            return reason == null ? null : String.valueOf(reason);
        } catch (Exception e) {
            log.warn("读取死信 x-death 原因失败，降级为空原因", e);
            return null;
        }
    }
}
