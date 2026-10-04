package com.hmdp;

import com.hmdp.entity.ReservationBatch;
import com.hmdp.enums.ReservationResultCode;
import com.hmdp.utils.ReservationQuotaExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.RESERVATION_BATCH_QUOTA_KEY;
import static com.hmdp.utils.RedisConstants.RESERVATION_BATCH_USERS_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ReservationQuotaExecutorTest {

    @Resource
    private ReservationQuotaExecutor reservationQuotaExecutor;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private Long batchId;
    private String quotaKey;
    private String usersKey;
    private LocalDateTime now;
    private ReservationBatch batch;

    @BeforeEach
    void setUp() {
        batchId = 9_000_000_000L + Math.abs(System.nanoTime() % 100_000_000L);
        quotaKey = RESERVATION_BATCH_QUOTA_KEY + batchId;
        usersKey = RESERVATION_BATCH_USERS_KEY + batchId;
        now = LocalDateTime.now().withNano(0);
        batch = new ReservationBatch()
                .setId(batchId)
                .setBookingStartTime(now.minusHours(1))
                .setBookingEndTime(now.plusHours(1))
                .setStartTime(now.plusHours(2))
                .setEndTime(now.plusHours(4));
    }

    @AfterEach
    void cleanUp() {
        stringRedisTemplate.delete(Arrays.asList(quotaKey, usersKey));
    }

    @Test
    void shouldAcquireQuotaAndCreateUsersSetWithTtl() {
        stringRedisTemplate.opsForValue().set(quotaKey, "4");

        ReservationResultCode result = reservationQuotaExecutor.tryAcquire(batch, 101L, now);

        assertEquals(ReservationResultCode.SUCCESS, result);
        assertEquals("3", stringRedisTemplate.opsForValue().get(quotaKey));
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(usersKey, "101")));
        long expectedMaxTtl = Duration.between(now, batch.getEndTime().plusHours(24)).getSeconds();
        Long actualTtl = stringRedisTemplate.getExpire(usersKey, TimeUnit.SECONDS);
        assertTrue(actualTtl != null && actualTtl > 0);
        assertTrue(actualTtl <= expectedMaxTtl);
    }

    @Test
    void shouldReturnBatchNotOpenWhenQuotaKeyDoesNotExist() {
        ReservationResultCode result = reservationQuotaExecutor.tryAcquire(batch, 102L, now);

        assertEquals(ReservationResultCode.BATCH_NOT_OPEN, result);
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(usersKey)));
    }

    @Test
    void shouldReturnSoldOutWithoutChangingQuotaOrUsers() {
        stringRedisTemplate.opsForValue().set(quotaKey, "0");

        ReservationResultCode result = reservationQuotaExecutor.tryAcquire(batch, 103L, now);

        assertEquals(ReservationResultCode.SOLD_OUT, result);
        assertEquals("0", stringRedisTemplate.opsForValue().get(quotaKey));
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(usersKey)));
    }

    @Test
    void shouldReturnDuplicateAndOnlyDeductQuotaOnce() {
        stringRedisTemplate.opsForValue().set(quotaKey, "4");
        assertEquals(ReservationResultCode.SUCCESS,
                reservationQuotaExecutor.tryAcquire(batch, 104L, now));

        ReservationResultCode duplicate = reservationQuotaExecutor.tryAcquire(batch, 104L, now);

        assertEquals(ReservationResultCode.DUPLICATE, duplicate);
        assertEquals("3", stringRedisTemplate.opsForValue().get(quotaKey));
        assertEquals(1L, stringRedisTemplate.opsForSet().size(usersKey));
    }

    @Test
    void shouldReturnBookingNotStartedWithoutChangingQuota() {
        stringRedisTemplate.opsForValue().set(quotaKey, "4");
        LocalDateTime beforeWindow = batch.getBookingStartTime().minusSeconds(1);

        ReservationResultCode result = reservationQuotaExecutor.tryAcquire(batch, 105L, beforeWindow);

        assertEquals(ReservationResultCode.BOOKING_NOT_STARTED, result);
        assertEquals("4", stringRedisTemplate.opsForValue().get(quotaKey));
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(usersKey)));
    }

    @Test
    void shouldReturnBookingExpiredWithoutChangingQuota() {
        stringRedisTemplate.opsForValue().set(quotaKey, "4");
        LocalDateTime afterWindow = batch.getBookingEndTime().plusSeconds(1);

        ReservationResultCode result = reservationQuotaExecutor.tryAcquire(batch, 106L, afterWindow);

        assertEquals(ReservationResultCode.BOOKING_EXPIRED, result);
        assertEquals("4", stringRedisTemplate.opsForValue().get(quotaKey));
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(usersKey)));
    }

    @Test
    void shouldFailLoudlyWhenQuotaValueIsNotNumeric() {
        stringRedisTemplate.opsForValue().set(quotaKey, "dirty");

        assertThrows(RuntimeException.class,
                () -> reservationQuotaExecutor.tryAcquire(batch, 107L, now));
        assertEquals("dirty", stringRedisTemplate.opsForValue().get(quotaKey));
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(usersKey)));
    }

    @Test
    void shouldMapAllReservationResultCodesAndRejectUnknownCode() {
        assertEquals(ReservationResultCode.SUCCESS, ReservationResultCode.fromCode(0));
        assertEquals(ReservationResultCode.BATCH_NOT_OPEN, ReservationResultCode.fromCode(1));
        assertEquals(ReservationResultCode.SOLD_OUT, ReservationResultCode.fromCode(2));
        assertEquals(ReservationResultCode.DUPLICATE, ReservationResultCode.fromCode(3));
        assertEquals(ReservationResultCode.BOOKING_NOT_STARTED, ReservationResultCode.fromCode(4));
        assertEquals(ReservationResultCode.BOOKING_EXPIRED, ReservationResultCode.fromCode(5));
        Arrays.stream(ReservationResultCode.values())
                .filter(code -> code != ReservationResultCode.SUCCESS)
                .forEach(code -> assertFalse(code.getMessage() == null || code.getMessage().isEmpty()));
        assertNull(ReservationResultCode.SUCCESS.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ReservationResultCode.fromCode(99));
    }
}
