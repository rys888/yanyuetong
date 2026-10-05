package com.hmdp.config;

import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReservationDlqListenerConfig {

    public static final String DLQ_CONTAINER_FACTORY =
            "reservationDlqListenerContainerFactory";

    @Bean(name = DLQ_CONTAINER_FACTORY)
    public SimpleRabbitListenerContainerFactory reservationDlqListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        // SimpleMessageConverter 对 JSON 只按字符集转成字符串，不会再次反序列化。
        factory.setMessageConverter(new SimpleMessageConverter());

        ConditionalRejectingErrorHandler errorHandler = new ConditionalRejectingErrorHandler();
        errorHandler.setRejectManual(true);
        factory.setErrorHandler(errorHandler);
        return factory;
    }
}
