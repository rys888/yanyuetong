package com.yanyuetong.utils;

import com.yanyuetong.entity.ReservationBatch;
import com.yanyuetong.enums.ReservationResultCode;
import com.yanyuetong.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;

import static com.yanyuetong.utils.RedisConstants.RESERVATION_BATCH_QUOTA_KEY;
import static com.yanyuetong.utils.RedisConstants.RESERVATION_BATCH_QUOTA_TTL_BUFFER_HOURS;
import static com.yanyuetong.utils.RedisConstants.RESERVATION_BATCH_USERS_KEY;

@Slf4j
@Component
public class ReservationQuotaExecutor {

    private static final DefaultRedisScript<Long> RESERVATION_SCRIPT;

    static {
        RESERVATION_SCRIPT = new DefaultRedisScript<>();
        RESERVATION_SCRIPT.setLocation(new ClassPathResource("reservation.lua"));
        RESERVATION_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;

    public ReservationQuotaExecutor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 调用方已确认窗口开放：now ≤ bookingEndTime ≤ startTime < endTime，因此 Set 的 TTL 恒为正。
     */
    public ReservationResultCode tryAcquire(
            ReservationBatch batch,
            Long userId,
            LocalDateTime now) {
        String quotaKey = RESERVATION_BATCH_QUOTA_KEY + batch.getId();
        String usersKey = RESERVATION_BATCH_USERS_KEY + batch.getId();
        long ttlSeconds = Duration.between(
                now,
                batch.getEndTime().plusHours(RESERVATION_BATCH_QUOTA_TTL_BUFFER_HOURS)).getSeconds();
        Long rawCode = stringRedisTemplate.execute(
                RESERVATION_SCRIPT,
                Arrays.asList(quotaKey, usersKey),
                userId.toString(),
                Long.toString(toEpochSecond(now)),
                Long.toString(toEpochSecond(batch.getBookingStartTime())),
                Long.toString(toEpochSecond(batch.getBookingEndTime())),
                Long.toString(ttlSeconds));
        if (rawCode == null || rawCode < Integer.MIN_VALUE || rawCode > Integer.MAX_VALUE) {
            log.error("预约 Lua 返回异常：{}", rawCode);
            throw new BadRequestException("预约失败，请稍后重试");
        }
        try {
            return ReservationResultCode.fromCode(rawCode.intValue());
        } catch (IllegalArgumentException e) {
            log.error("预约 Lua 返回未知码：{}", rawCode);
            throw new BadRequestException("预约失败，请稍后重试");
        }
    }

    private long toEpochSecond(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toEpochSecond();
    }
}
