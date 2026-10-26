package com.yanyuetong.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 用时间差与 Redis 日序列组合业务 ID；预约链路将生成值同时用作请求账本、消息和事实表 ID。
 */
@Component
public class RedisIdWorker {
    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix) {
        // 1、生成相对固定起点的秒级时间差，放在 ID 高位；机器时间参与计算，因此依赖应用时钟。
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2、按业务前缀和日期拆分计数器，用 Redis INCR 原子取得序号，供多个应用实例共同分配。
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3、时间差左移 32 位，低位拼接序号；该设计要求日序号不溢出预留的 32 位空间。
        return timestamp << COUNT_BITS | count;
    }
}
