package com.yanyuetong;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yanyuetong.entity.Reservation;
import com.yanyuetong.entity.ReservationBatch;
import com.yanyuetong.entity.User;
import com.yanyuetong.enums.ReservationBatchStatus;
import com.yanyuetong.enums.ReservationPersistResult;
import com.yanyuetong.enums.UserRole;
import com.yanyuetong.mapper.ReservationMapper;
import com.yanyuetong.service.IReservationBatchService;
import com.yanyuetong.service.IReservationPersistenceService;
import com.yanyuetong.service.IUserService;
import com.yanyuetong.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 需要先启动真实 MySQL，并应用 phase7_reservation.sql；测试会自行清理预约、批次和用户 fixture。
 */
@SpringBootTest
class ReservationPersistenceTest {

    private static final int INITIAL_QUOTA = 2;

    @Resource
    private IReservationPersistenceService persistenceService;

    @Resource
    private ReservationMapper reservationMapper;

    @Resource
    private IReservationBatchService batchService;

    @Resource
    private IUserService userService;

    private User user;
    private ReservationBatch batch;
    private long reservationIdSeed;
    private final List<Long> additionalUserIds = new ArrayList<>();
    private final List<Long> additionalBatchIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
        long nonce = Math.abs(System.nanoTime());
        String suffix = String.format("%08d", nonce % 100_000_000L);
        user = new User()
                .setPhone("15" + suffix + "0")
                .setNickName("预约持久化测试用户-" + suffix)
                .setIcon("")
                .setRole(UserRole.USER);
        assertTrue(userService.save(user));

        LocalDateTime now = LocalDateTime.now();
        batch = new ReservationBatch()
                .setEquipmentId(1L)
                .setStartTime(now.plusDays(2))
                .setEndTime(now.plusDays(2).plusHours(2))
                .setBookingStartTime(now.minusHours(1))
                .setBookingEndTime(now.plusDays(1))
                .setTotalQuota(INITIAL_QUOTA)
                .setRemainingQuota(INITIAL_QUOTA)
                .setStatus(ReservationBatchStatus.PUBLISHED);
        assertTrue(batchService.save(batch));
        reservationIdSeed = 8_000_000_000L + nonce % 1_000_000_000L;
    }

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
        for (Long batchId : additionalBatchIds) {
            reservationMapper.delete(new LambdaQueryWrapper<Reservation>()
                    .eq(Reservation::getBatchId, batchId));
            batchService.removeById(batchId);
        }
        for (Long userId : additionalUserIds) {
            userService.removeById(userId);
        }
        if (user != null && user.getId() != null && batch != null && batch.getId() != null) {
            reservationMapper.delete(new LambdaQueryWrapper<Reservation>()
                    .eq(Reservation::getUserId, user.getId())
                    .eq(Reservation::getBatchId, batch.getId()));
        }
        if (batch != null && batch.getId() != null) {
            batchService.removeById(batch.getId());
        }
        if (user != null && user.getId() != null) {
            userService.removeById(user.getId());
        }
    }

    @Test
    void shouldPersistInputIdAndDecrementQuota() {
        Reservation reservation = reservation(reservationIdSeed);

        ReservationPersistResult result = persistenceService.persist(reservation);

        assertEquals(ReservationPersistResult.PERSISTED, result);
        Reservation stored = reservationMapper.selectById(reservationIdSeed);
        assertNotNull(stored);
        assertEquals(reservationIdSeed, stored.getId());
        assertEquals(1, countFixtureReservations());
        assertEquals(INITIAL_QUOTA - 1,
                batchService.getById(batch.getId()).getRemainingQuota());
    }

    @Test
    void shouldTreatSameUserAndBatchAsDuplicate() {
        assertEquals(ReservationPersistResult.PERSISTED,
                persistenceService.persist(reservation(reservationIdSeed)));

        ReservationPersistResult result = persistenceService.persist(
                reservation(reservationIdSeed + 1));

        assertEquals(ReservationPersistResult.DUPLICATE, result);
        assertEquals(1, countFixtureReservations());
        assertNotNull(reservationMapper.selectById(reservationIdSeed));
        assertNull(reservationMapper.selectById(reservationIdSeed + 1));
        assertEquals(INITIAL_QUOTA - 1,
                batchService.getById(batch.getId()).getRemainingQuota());
    }

    @Test
    void shouldNotInsertReservationWhenQuotaIsExhausted() {
        batchService.updateById(new ReservationBatch()
                .setId(batch.getId())
                .setRemainingQuota(0));

        ReservationPersistResult result = persistenceService.persist(
                reservation(reservationIdSeed));

        assertEquals(ReservationPersistResult.QUOTA_CONFLICT, result);
        assertEquals(0, countFixtureReservations());
        assertNull(reservationMapper.selectById(reservationIdSeed));
        assertEquals(0, batchService.getById(batch.getId()).getRemainingQuota());
    }

    @Test
    void shouldEnforceUniqueUserBatchConstraintAtDatabaseLevel() {
        reservationMapper.insert(reservation(reservationIdSeed));

        // 服务层的幂等预检查会短路重复写入，必须绕过服务层直插才能触达数据库唯一约束。
        assertThrows(DuplicateKeyException.class,
                () -> reservationMapper.insert(reservation(reservationIdSeed + 1)));

        assertEquals(1, countFixtureReservations());
    }

    @Test
    void shouldNeverMakeRemainingQuotaNegativeUnderConcurrentPersist() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        ReservationBatch concurrentBatch = new ReservationBatch()
                .setEquipmentId(1L)
                .setStartTime(now.plusDays(3))
                .setEndTime(now.plusDays(3).plusHours(2))
                .setBookingStartTime(now.minusHours(1))
                .setBookingEndTime(now.plusDays(1))
                .setTotalQuota(1)
                .setRemainingQuota(1)
                .setStatus(ReservationBatchStatus.PUBLISHED);
        assertTrue(batchService.save(concurrentBatch));
        additionalBatchIds.add(concurrentBatch.getId());

        int attempts = 8;
        String suffix = String.format("%08d", Math.abs(System.nanoTime() % 100_000_000L));
        List<User> competitors = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            User competitor = new User()
                    .setPhone("16" + suffix + i)
                    .setNickName("持久化并发测试用户-" + suffix + "-" + i)
                    .setIcon("")
                    .setRole(UserRole.USER);
            assertTrue(userService.save(competitor));
            additionalUserIds.add(competitor.getId());
            competitors.add(competitor);
        }

        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReservationPersistResult>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < attempts; i++) {
                Reservation concurrentReservation = new Reservation()
                        .setId(reservationIdSeed + i)
                        .setUserId(competitors.get(i).getId())
                        .setBatchId(concurrentBatch.getId())
                        .setCreateTime(LocalDateTime.now());
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return persistenceService.persist(concurrentReservation);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int persisted = 0;
            int quotaConflicts = 0;
            for (Future<ReservationPersistResult> future : futures) {
                ReservationPersistResult result = future.get(15, TimeUnit.SECONDS);
                if (result == ReservationPersistResult.PERSISTED) {
                    persisted++;
                } else if (result == ReservationPersistResult.QUOTA_CONFLICT) {
                    quotaConflicts++;
                }
            }

            assertEquals(1, persisted);
            assertEquals(attempts - 1, quotaConflicts);
            assertEquals(1, reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                    .eq(Reservation::getBatchId, concurrentBatch.getId())));
            Integer remainingQuota = batchService.getById(concurrentBatch.getId()).getRemainingQuota();
            assertEquals(Integer.valueOf(0), remainingQuota);
            // 显式守住 I5：并发条件扣减无论如何都不能让 MySQL remaining_quota 变成负数。
            assertTrue(remainingQuota >= 0);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Reservation reservation(long id) {
        return new Reservation()
                .setId(id)
                .setUserId(user.getId())
                .setBatchId(batch.getId())
                .setCreateTime(LocalDateTime.now());
    }

    private int countFixtureReservations() {
        return reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, user.getId())
                .eq(Reservation::getBatchId, batch.getId()));
    }
}
