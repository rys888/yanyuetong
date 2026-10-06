package com.yanyuetong;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yanyuetong.entity.Equipment;
import com.yanyuetong.entity.Laboratory;
import com.yanyuetong.entity.Reservation;
import com.yanyuetong.entity.ReservationBatch;
import com.yanyuetong.entity.User;
import com.yanyuetong.enums.EquipmentStatus;
import com.yanyuetong.enums.LaboratoryStatus;
import com.yanyuetong.enums.ReservationBatchStatus;
import com.yanyuetong.enums.ReservationStatus;
import com.yanyuetong.enums.UserRole;
import com.yanyuetong.mapper.EquipmentMapper;
import com.yanyuetong.mapper.LaboratoryMapper;
import com.yanyuetong.mapper.ReservationBatchMapper;
import com.yanyuetong.mapper.ReservationMapper;
import com.yanyuetong.mapper.UserMapper;
import com.yanyuetong.service.impl.ReservationReconcileServiceImpl;
import com.yanyuetong.utils.ReservationQuotaRollbackExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.yanyuetong.utils.RedisConstants.RESERVATION_BATCH_QUOTA_KEY;
import static com.yanyuetong.utils.RedisConstants.RESERVATION_BATCH_USERS_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "yanyuetong.reservation.reconcile-interval-ms=25",
        "yanyuetong.reservation.reconcile-initial-delay-ms=3600000"
})
class ReservationFulfillmentReconcileTest {

    private static final int TOTAL_QUOTA = 5;
    private static final AtomicLong RESERVATION_IDS =
            new AtomicLong(9_300_000_000L + Math.abs(System.nanoTime() % 100_000_000L));

    @Resource
    private ReservationReconcileServiceImpl reconcileService;

    @SpyBean
    private ReservationQuotaRollbackExecutor rollbackExecutor;

    @Resource
    private ReservationMapper reservationMapper;

    @Resource
    private ReservationBatchMapper batchMapper;

    @Resource
    private EquipmentMapper equipmentMapper;

    @Resource
    private LaboratoryMapper laboratoryMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final List<Long> reservationIds = new ArrayList<>();
    private final List<Long> batchIds = new ArrayList<>();
    private final List<Long> equipmentIds = new ArrayList<>();
    private final List<Long> laboratoryIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    private User userA;
    private User userB;
    private User userC;
    private Equipment equipment;

    @BeforeEach
    void setUp() {
        String suffix = String.format("%08d", Math.abs(System.nanoTime() % 100_000_000L));
        User admin = createUser("22" + suffix + "1", UserRole.LAB_ADMIN);
        userA = createUser("22" + suffix + "2", UserRole.USER);
        userB = createUser("22" + suffix + "3", UserRole.USER);
        userC = createUser("22" + suffix + "4", UserRole.USER);
        Laboratory laboratory = createLaboratory("履约测试实验室-" + suffix, admin.getId());
        equipment = createEquipment("履约测试设备-" + suffix, laboratory.getId());
    }

    @AfterEach
    void cleanUp() {
        reservationIds.forEach(reservationMapper::deleteById);
        for (Long batchId : batchIds) {
            stringRedisTemplate.delete(Arrays.asList(quotaKey(batchId), usersKey(batchId)));
            batchMapper.deleteById(batchId);
        }
        equipmentIds.forEach(equipmentMapper::deleteById);
        laboratoryIds.forEach(laboratoryMapper::deleteById);
        userIds.forEach(userMapper::deleteById);
    }

    @Test
    void shouldReleasePendingQuotaOnlyOnce() {
        ReservationBatch batch = createBatch(LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1), ReservationBatchStatus.PUBLISHED, TOTAL_QUOTA);
        Reservation cancelled = createReservation(userA.getId(), batch.getId(),
                ReservationStatus.CANCELLED, false);
        occupy(batch.getId(), userA.getId(), TOTAL_QUOTA - 1);

        assertEquals(1, reconcileService.releasePendingQuotas(20));
        assertEquals(0, reconcileService.releasePendingQuotas(20));

        assertTrue(reservationMapper.selectById(cancelled.getId()).getQuotaReleased());
        assertEquals(String.valueOf(TOTAL_QUOTA), stringRedisTemplate.opsForValue().get(quotaKey(batch.getId())));
        assertFalse(isOccupying(batch.getId(), userA.getId()));
        assertEquals(TOTAL_QUOTA, batchMapper.selectById(batch.getId()).getRemainingQuota());
    }

    @Test
    void shouldNotRecountQuotaReleaseWhenAnotherThreadWinsTheRaceBeforeUpdate() {
        ReservationBatch batch = createBatch(LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1), ReservationBatchStatus.PUBLISHED, TOTAL_QUOTA);
        Reservation cancelled = createReservation(userA.getId(), batch.getId(),
                ReservationStatus.CANCELLED, false);
        occupy(batch.getId(), userA.getId(), TOTAL_QUOTA - 1);

        // 确定性构造竞态：对账已 SELECT 出这条待补偿行，但在它 UPDATE 之前，取消编排线程
        // 自己完成了归还（SREM + INCR）并置 quota_released=1。此后本次对账行的 rollback
        // 因 SREM==0 不再 INCR —— 因此这一行不该被计入「本轮归还成功」。
        doAnswer(invocation -> {
            stringRedisTemplate.opsForSet().remove(usersKey(batch.getId()), userA.getId().toString());
            stringRedisTemplate.opsForValue().increment(quotaKey(batch.getId()));
            reservationMapper.update(null, new LambdaUpdateWrapper<Reservation>()
                    .eq(Reservation::getId, cancelled.getId())
                    .eq(Reservation::getQuotaReleased, false)
                    .set(Reservation::getQuotaReleased, true));
            return invocation.callRealMethod();
        }).when(rollbackExecutor).rollback(batch.getId(), userA.getId());

        assertEquals(0, reconcileService.releasePendingQuotas(20));
        assertTrue(reservationMapper.selectById(cancelled.getId()).getQuotaReleased());
        assertEquals(String.valueOf(TOTAL_QUOTA),
                stringRedisTemplate.opsForValue().get(quotaKey(batch.getId())));
    }

    @Test
    void shouldMoveFailedQuotaReleaseBehindLaterReservations() {
        int limit = 2;
        LocalDateTime now = LocalDateTime.now();
        ReservationBatch batch = createBatch(now.minusHours(1), now.plusHours(1),
                ReservationBatchStatus.PUBLISHED, TOTAL_QUOTA);
        Reservation failing = createReservation(userA.getId(), batch.getId(),
                ReservationStatus.CANCELLED, false);
        Reservation second = createReservation(userA.getId(), batch.getId(),
                ReservationStatus.CANCELLED, false);
        Reservation initiallyOutsideWindow = createReservation(userA.getId(), batch.getId(),
                ReservationStatus.CANCELLED, false);
        setUpdateTime(failing.getId(), now.minusMinutes(14));
        setUpdateTime(second.getId(), now.minusMinutes(13));
        setUpdateTime(initiallyOutsideWindow.getId(), now.minusMinutes(12));
        doThrow(new IllegalStateException("持续回滚故障")).doCallRealMethod()
                .when(rollbackExecutor).rollback(batch.getId(), userA.getId());

        assertEquals(1, reconcileService.releasePendingQuotas(limit));

        Reservation deferred = reservationMapper.selectById(failing.getId());
        Reservation waiting = reservationMapper.selectById(initiallyOutsideWindow.getId());
        assertTrue(deferred.getUpdateTime().isAfter(waiting.getUpdateTime()));
        List<Reservation> nextWindow = reservationMapper.selectPendingQuotaReleases(limit);
        assertEquals(initiallyOutsideWindow.getId(), nextWindow.get(0).getId());

        reconcileService.releasePendingQuotas(limit);

        assertTrue(reservationMapper.selectById(initiallyOutsideWindow.getId()).getQuotaReleased());
    }

    @Test
    void shouldMarkOnlyExpiredReservationsAsNoShowWithoutChangingQuota() {
        LocalDateTime now = LocalDateTime.now();
        ReservationBatch expired = createBatch(now.minusHours(3), now.minusHours(2),
                ReservationBatchStatus.PUBLISHED, TOTAL_QUOTA - 1);
        ReservationBatch future = createBatch(now.plusHours(1), now.plusHours(2),
                ReservationBatchStatus.PUBLISHED, TOTAL_QUOTA - 2);
        ReservationBatch cancelledBatch = createBatch(now.minusHours(3), now.minusHours(2),
                ReservationBatchStatus.CANCELLED, TOTAL_QUOTA - 3);
        Reservation expiredReservation = createReservation(userA.getId(), expired.getId(),
                ReservationStatus.RESERVED, false);
        Reservation futureReservation = createReservation(userB.getId(), future.getId(),
                ReservationStatus.RESERVED, false);
        Reservation cancelledBatchReservation = createReservation(userC.getId(), cancelledBatch.getId(),
                ReservationStatus.RESERVED, false);
        stringRedisTemplate.opsForValue().set(quotaKey(expired.getId()), String.valueOf(TOTAL_QUOTA - 1));
        stringRedisTemplate.opsForValue().set(quotaKey(future.getId()), String.valueOf(TOTAL_QUOTA - 2));
        stringRedisTemplate.opsForValue().set(quotaKey(cancelledBatch.getId()), String.valueOf(TOTAL_QUOTA - 3));

        assertEquals(1, reconcileService.markNoShows(20));

        assertEquals(ReservationStatus.NO_SHOW,
                reservationMapper.selectById(expiredReservation.getId()).getStatus());
        assertEquals(ReservationStatus.RESERVED,
                reservationMapper.selectById(futureReservation.getId()).getStatus());
        assertEquals(ReservationStatus.RESERVED,
                reservationMapper.selectById(cancelledBatchReservation.getId()).getStatus());
        assertEquals(TOTAL_QUOTA - 1, batchMapper.selectById(expired.getId()).getRemainingQuota());
        assertEquals(TOTAL_QUOTA - 2, batchMapper.selectById(future.getId()).getRemainingQuota());
        assertEquals(TOTAL_QUOTA - 3, batchMapper.selectById(cancelledBatch.getId()).getRemainingQuota());
        assertEquals(String.valueOf(TOTAL_QUOTA - 1), stringRedisTemplate.opsForValue().get(quotaKey(expired.getId())));
        assertEquals(String.valueOf(TOTAL_QUOTA - 2), stringRedisTemplate.opsForValue().get(quotaKey(future.getId())));
        assertEquals(String.valueOf(TOTAL_QUOTA - 3), stringRedisTemplate.opsForValue().get(quotaKey(cancelledBatch.getId())));
    }

    @Test
    void shouldNotRunScheduledReconcileDuringTestContext() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        ReservationBatch expired = createBatch(now.minusHours(3), now.minusHours(2),
                ReservationBatchStatus.PUBLISHED, TOTAL_QUOTA - 1);
        Reservation reservation = createReservation(userA.getId(), expired.getId(),
                ReservationStatus.RESERVED, false);

        Thread.sleep(150);

        assertEquals(ReservationStatus.RESERVED,
                reservationMapper.selectById(reservation.getId()).getStatus());
    }

    private User createUser(String phone, UserRole role) {
        User user = new User().setPhone(phone).setNickName("履约测试-" + phone).setIcon("").setRole(role);
        assertEquals(1, userMapper.insert(user));
        userIds.add(user.getId());
        return user;
    }

    private Laboratory createLaboratory(String name, Long adminUserId) {
        Laboratory laboratory = new Laboratory()
                .setName(name).setCampus("测试校区").setBuilding("测试楼").setRoom("P902")
                .setDescription("Phase 9 履约测试").setStatus(LaboratoryStatus.OPEN)
                .setAdminUserId(adminUserId);
        assertEquals(1, laboratoryMapper.insert(laboratory));
        laboratoryIds.add(laboratory.getId());
        return laboratory;
    }

    private Equipment createEquipment(String name, Long laboratoryId) {
        Equipment fixture = new Equipment()
                .setLaboratoryId(laboratoryId).setCategoryId(1L).setName(name)
                .setDescription("Phase 9 履约测试").setLocation("P902")
                .setUsageRequirement("测试").setStatus(EquipmentStatus.AVAILABLE)
                .setBookingEnabled(true);
        assertEquals(1, equipmentMapper.insert(fixture));
        equipmentIds.add(fixture.getId());
        return fixture;
    }

    private ReservationBatch createBatch(LocalDateTime startTime, LocalDateTime endTime,
                                         ReservationBatchStatus status, int remainingQuota) {
        ReservationBatch batch = new ReservationBatch()
                .setEquipmentId(equipment.getId()).setStartTime(startTime).setEndTime(endTime)
                .setBookingStartTime(startTime.minusDays(1)).setBookingEndTime(startTime.minusMinutes(1))
                .setTotalQuota(TOTAL_QUOTA).setRemainingQuota(remainingQuota).setStatus(status);
        assertEquals(1, batchMapper.insert(batch));
        batchIds.add(batch.getId());
        return batch;
    }

    private Reservation createReservation(Long userId, Long batchId, ReservationStatus status,
                                          boolean quotaReleased) {
        Reservation reservation = new Reservation()
                .setId(RESERVATION_IDS.incrementAndGet()).setUserId(userId).setBatchId(batchId)
                .setStatus(status).setQuotaReleased(quotaReleased).setCreateTime(LocalDateTime.now());
        assertEquals(1, reservationMapper.insert(reservation));
        reservationIds.add(reservation.getId());
        return reservation;
    }

    private void setUpdateTime(Long reservationId, LocalDateTime updateTime) {
        assertEquals(1, reservationMapper.updateById(new Reservation()
                .setId(reservationId)
                .setUpdateTime(updateTime)));
    }

    private void occupy(Long batchId, Long userId, int quota) {
        stringRedisTemplate.opsForValue().set(quotaKey(batchId), String.valueOf(quota));
        stringRedisTemplate.opsForSet().add(usersKey(batchId), userId.toString());
    }

    private boolean isOccupying(Long batchId, Long userId) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(usersKey(batchId), userId.toString()));
    }

    private String quotaKey(Long batchId) {
        return RESERVATION_BATCH_QUOTA_KEY + batchId;
    }

    private String usersKey(Long batchId) {
        return RESERVATION_BATCH_USERS_KEY + batchId;
    }
}
