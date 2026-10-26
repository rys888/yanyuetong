package com.yanyuetong.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Arrays;

import static com.yanyuetong.utils.RedisConstants.RESERVATION_BATCH_QUOTA_KEY;
import static com.yanyuetong.utils.RedisConstants.RESERVATION_BATCH_USERS_KEY;

/**
 * Redis 名额补偿执行器。回滚 Lua 以用户占位标记作为幂等凭证，
 * 只有成功移除标记的调用才可能归还名额；幂等范围是同一用户占位，调用方仍需防止旧请求释放新占位。
 */
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
     * 补偿指定用户在批次中的 Redis 名额占用。
     *
     * 返回 0 表示用户本来就未占用名额（重复补偿或从未占用），名额不变；
     * 返回 1 表示用户确实占用名额且名额键存在，已归还一个名额；
     * 返回 2 表示用户确实占用名额但名额键已不存在，仅清理用户标记、不归还名额。
     *
     * @param batchId 预约批次 ID
     * @param userId 需要释放占位的用户 ID
     * @return 回滚脚本结果码
     */
    public int rollback(Long batchId, Long userId) {
        // 1、定位批次名额与用户占位。标记里没有 requestId，无法区分同一用户先后两次预约。
        String quotaKey = RESERVATION_BATCH_QUOTA_KEY + batchId;
        String usersKey = RESERVATION_BATCH_USERS_KEY + batchId;
        // 2、Lua 先 SREM；未删到标记就直接返回，删到后仅在名额键存在时 INCR。
        // 检查与修改原子执行，关闭批次后也不会因补偿重新创建已删除的名额键。
        Long result = stringRedisTemplate.execute(
                RESERVATION_ROLLBACK_SCRIPT,
                Arrays.asList(quotaKey, usersKey),
                userId.toString());
        // 3、只接受约定结果；异常交给调用方保留待补偿状态，不能伪装成回滚完成。
        if (result == null || (result != 0L && result != 1L && result != 2L)) {
            throw new IllegalStateException("预约名额回滚脚本返回异常：" + result);
        }
        return result.intValue();
    }
}
