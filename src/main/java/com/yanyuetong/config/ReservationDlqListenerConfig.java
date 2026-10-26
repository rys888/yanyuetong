package com.yanyuetong.config;

import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DLQ 专用监听容器。它故意不使用 JSON 转换器，使引发主队列反序列化失败的原始消息仍可被审计。
 */
@Configuration
public class ReservationDlqListenerConfig {

    public static final String DLQ_CONTAINER_FACTORY =
            "reservationDlqListenerContainerFactory";

    @Bean(name = DLQ_CONTAINER_FACTORY)
    public SimpleRabbitListenerContainerFactory reservationDlqListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        // 1、仍复用手动确认等公共设置，让死信消费者在台账插入成功后才 ACK。
        configurer.configure(factory, connectionFactory);
        // 2、覆盖公共 JSON 转换器；监听器接收原始 Message，不再要求畸形 JSON 能还原为 DTO。
        factory.setMessageConverter(new SimpleMessageConverter());

        // 3、容器级致命错误同样明确拒绝，避免无法进入业务方法的消息一直悬而未决。
        ConditionalRejectingErrorHandler errorHandler = new ConditionalRejectingErrorHandler();
        errorHandler.setRejectManual(true);
        factory.setErrorHandler(errorHandler);
        return factory;
    }
}
