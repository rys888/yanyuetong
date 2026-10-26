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

/**
 * 预约消息拓扑与确认策略。主队列绑定死信交换机，消费者明确拒绝的消息会进入 DLQ 留待审计和重放。
 */
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
        // 创建路由：reservation.exchange→主队列；拒绝后的路由：主队列→reservation.dlx→DLQ。
        // 死信参数放在主队列上，使 basicNack(requeue=false) 的失败消息进入治理链路，而非原地循环。
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

    /**
     * 主消费端工厂。复用 Boot 中的 manual ack、prefetch 和重试配置，
     * 并要求监听方法调用前的反序列化异常也被拒绝至 DLQ。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        // 1、复用 application.yaml 中的 manual ack、prefetch 和关闭自动重试配置，业务方法自行确认消息。
        configurer.configure(factory, connectionFactory);
        // 2、JSON 无法转为 DTO 时，消费者方法还没被调用，内部 catch 无法接住。
        // 错误处理器在手动确认模式下也拒绝这类致命错误，由主队列的 DLX 参数送入死信队列。
        ConditionalRejectingErrorHandler errorHandler = new ConditionalRejectingErrorHandler();
        errorHandler.setRejectManual(true);
        factory.setErrorHandler(errorHandler);
        return factory;
    }

    /**
     * 注册发布确认与退回回调，并启用 mandatory；Confirm 连接模式由 application.yaml 配置。
     * 回调负责全局留痕，单次提交的同步决策由 CorrelationData 承载。
     */
    @PostConstruct
    public void init() {
        // 1、全局确认回调只记录 NACK；每次发布如何推进账本，由业务方等待自己的 CorrelationData 决定。
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("预约消息发送未获确认：correlationData={}，cause={}", correlationData, cause);
            }
        });
        // 2、消息到达交换机却无匹配队列时记录 Return；这与消费者处理失败的 NACK 是不同阶段。
        rabbitTemplate.setReturnCallback((message, replyCode, replyText, exchange, routingKey) ->
                log.error("预约消息路由失败：replyCode={}，replyText={}，exchange={}，routingKey={}",
                        replyCode, replyText, exchange, routingKey));
        // 3、要求无法路由时退回消息，生产方才能结合 ACK 和 Return 判断投递，而非只看 broker 已接收。
        rabbitTemplate.setMandatory(true);
    }
}
