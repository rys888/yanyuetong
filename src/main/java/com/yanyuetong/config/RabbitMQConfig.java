package com.yanyuetong.config;

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

    public static final String RESERVATION_EXCHANGE = "reservation.exchange";
    public static final String RESERVATION_CREATE_ROUTING_KEY = "reservation.create";
    public static final String RESERVATION_CREATE_QUEUE = "reservation.create.queue";
    public static final String RESERVATION_DLX = "reservation.dlx";
    public static final String RESERVATION_CREATE_DLQ = "reservation.create.dlq";
    public static final String RESERVATION_CREATE_DLQ_ROUTING_KEY = "reservation.create.dlq";

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Bean
    public DirectExchange reservationExchange() {
        return new DirectExchange(RESERVATION_EXCHANGE, true, false);
    }

    @Bean
    public Queue reservationCreateQueue() {
        return QueueBuilder.durable(RESERVATION_CREATE_QUEUE)
                .withArgument("x-dead-letter-exchange", RESERVATION_DLX)
                .withArgument("x-dead-letter-routing-key", RESERVATION_CREATE_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding reservationCreateBinding() {
        return BindingBuilder.bind(reservationCreateQueue())
                .to(reservationExchange())
                .with(RESERVATION_CREATE_ROUTING_KEY);
    }

    @Bean
    public DirectExchange reservationDlx() {
        return new DirectExchange(RESERVATION_DLX, true, false);
    }

    @Bean
    public Queue reservationCreateDlq() {
        return QueueBuilder.durable(RESERVATION_CREATE_DLQ).build();
    }

    @Bean
    public Binding reservationCreateDlqBinding() {
        return BindingBuilder.bind(reservationCreateDlq())
                .to(reservationDlx())
                .with(RESERVATION_CREATE_DLQ_ROUTING_KEY);
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
        // 复用 Boot 配置，使手动确认、prefetch 和关闭自动重试同时生效。
        configurer.configure(factory, connectionFactory);
        // 反序列化失败发生在监听器方法之前，必须明确拒绝并进入死信队列。
        ConditionalRejectingErrorHandler errorHandler = new ConditionalRejectingErrorHandler();
        errorHandler.setRejectManual(true);
        factory.setErrorHandler(errorHandler);
        return factory;
    }

    @PostConstruct
    public void init() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("预约消息发送未获确认：correlationData={}，cause={}", correlationData, cause);
            }
        });
        rabbitTemplate.setReturnCallback((message, replyCode, replyText, exchange, routingKey) ->
                log.error("预约消息路由失败：replyCode={}，replyText={}，exchange={}，routingKey={}",
                        replyCode, replyText, exchange, routingKey));
        rabbitTemplate.setMandatory(true);
    }
}
