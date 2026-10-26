package com.yanyuetong.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端配置，为 MQ 消费串行化和多实例定时对账提供跨 JVM 锁。
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.redis.host}")
    private String host;

    @Value("${spring.redis.port}")
    private Integer port;

    @Value("${spring.redis.password}")
    private String password;

    @Bean
    public RedissonClient redissonClient(){
        // 1、连接同一 Redis 服务，使多个 JVM 对同名锁达成互斥；这里配置的是单节点模式。
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + host + ":" + port).setPassword(password);
        // 2、把客户端交给 Spring 共享使用；锁的粒度、等待时间和租期由消费者与对账任务分别指定。
        return Redisson.create(config);
    }
}
