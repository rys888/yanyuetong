package com.hmdp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.entity.Reservation;
import com.hmdp.entity.ReservationBatch;
import com.hmdp.entity.User;
import com.hmdp.enums.ReservationBatchStatus;
import com.hmdp.enums.ReservationPersistResult;
import com.hmdp.enums.UserRole;
import com.hmdp.mapper.ReservationMapper;
import com.hmdp.service.IReservationBatchService;
import com.hmdp.service.IReservationPersistenceService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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
