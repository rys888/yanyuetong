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

/**
 * Redis 预约名额执行器，将时间窗口、剩余名额和用户去重交给同一个 Lua 脚本原子判定。
 * 这避免了 Java 端“先查后改”在并发下出现超卖或同一用户重复扣减。
 */
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
     * 尝试为用户预占一个批次名额。Lua 使用调用方传入的 now 快照校验窗口，
     * 并未读取 Redis 执行时刻，因此不会重新感知排队期间跨过的时间边界。
     * now ≤ bookingEndTime ≤ startTime < endTime，因此用户占位集合的 TTL 恒为正。
     *
     * @param batch 已校验可预约的批次
     * @param userId 当前用户 ID
     * @param now 本次提交的统一时间基准
     * @return Lua 结果码对应的业务结果
     */
    public ReservationResultCode tryAcquire(
            ReservationBatch batch,
            Long userId,
            LocalDateTime now) {
        // 1、使用批次发布时写入的 quotaKey 和该批次的用户 Set，二者共同描述 Redis 预占状态。
        String quotaKey = RESERVATION_BATCH_QUOTA_KEY + batch.getId();
        String usersKey = RESERVATION_BATCH_USERS_KEY + batch.getId();
        // 2、占位集合保留至使用结束后的一段缓冲期，让延迟消息、取消和补偿仍能找到占位标记。
        long ttlSeconds = Duration.between(
                now,
                batch.getEndTime().plusHours(RESERVATION_BATCH_QUOTA_TTL_BUFFER_HOURS)).getSeconds();
        // 3、将 KEYS 和 ARGV 按脚本约定传入：用户、提交时间、开放时间、截止时间、占位 TTL。
        // reservation.lua 依次检查名额键、窗口、剩余名额、用户去重，再以 DECR+SADD 原子预占。
        // 原子性只覆盖这个 Redis 脚本，不包括调用方的 MySQL 建账或后续 MQ 投递。
        // “原子执行”表示不被其他命令穿插，并不表示脚本运行报错时能自动撤销已执行的 Redis 命令。
        Long rawCode = stringRedisTemplate.execute(
                RESERVATION_SCRIPT,
                Arrays.asList(quotaKey, usersKey),
                userId.toString(),
                Long.toString(toEpochSecond(now)),
                Long.toString(toEpochSecond(batch.getBookingStartTime())),
                Long.toString(toEpochSecond(batch.getBookingEndTime())),
                Long.toString(ttlSeconds));
        // 4、脚本结果映射为业务码；脚本异常会向上抛出，已建的非终态账本仍可被后续对账扫描。
        // SUCCESS 仅表示 Redis 已预占，MySQL 预约由 ReservationMessageConsumer 异步创建。
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
