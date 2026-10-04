package com.hmdp.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Arrays;

import static com.hmdp.utils.RedisConstants.RESERVATION_BATCH_QUOTA_KEY;
import static com.hmdp.utils.RedisConstants.RESERVATION_BATCH_USERS_KEY;

@Component
public class ReservationQuotaRollbackExecutor {

    private static final DefaultRedisScript<Long> RESERVATION_ROLLBACK_SCRIPT;

    static {
        RESERVATION_ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        RESERVATION_ROLLBACK_SCRIPT.setLocation(new ClassPathResource("reservationRollback.lua"));
        RESERVATION_ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;

    public ReservationQuotaRollbackExecutor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 返回 1 表示名额已归还；返回 0 表示名额键已不存在，仅清理用户标记。
     */
    public int rollback(Long batchId, Long userId) {
        String quotaKey = RESERVATION_BATCH_QUOTA_KEY + batchId;
        String usersKey = RESERVATION_BATCH_USERS_KEY + batchId;
        Long result = stringRedisTemplate.execute(
                RESERVATION_ROLLBACK_SCRIPT,
                Arrays.asList(quotaKey, usersKey),
                userId.toString());
        if (result == null || (result != 0L && result != 1L)) {
            throw new IllegalStateException("预约名额回滚脚本返回异常：" + result);
        }
        return result.intValue();
    }
}
