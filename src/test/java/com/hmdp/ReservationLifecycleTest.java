package com.hmdp;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hmdp.dto.ReservationQueryDTO;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Equipment;
import com.hmdp.entity.Laboratory;
import com.hmdp.entity.Reservation;
import com.hmdp.entity.ReservationBatch;
import com.hmdp.entity.User;
import com.hmdp.enums.EquipmentStatus;
import com.hmdp.enums.LaboratoryStatus;
import com.hmdp.enums.ReservationBatchStatus;
import com.hmdp.enums.ReservationPersistResult;
import com.hmdp.enums.ReservationStatus;
import com.hmdp.enums.UserRole;
import com.hmdp.exception.BadRequestException;
import com.hmdp.exception.ForbiddenException;
import com.hmdp.mapper.EquipmentMapper;
import com.hmdp.mapper.LaboratoryMapper;
import com.hmdp.mapper.ReservationBatchMapper;
import com.hmdp.mapper.ReservationMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IReservationPersistenceService;
import com.hmdp.service.IReservationService;
import com.hmdp.service.impl.ReservationReconcileServiceImpl;
import com.hmdp.utils.ReservationQuotaRollbackExecutor;
import com.hmdp.utils.UserHolder;
import com.hmdp.vo.ReservationVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static com.hmdp.utils.RedisConstants.RESERVATION_BATCH_QUOTA_KEY;
import static com.hmdp.utils.RedisConstants.RESERVATION_BATCH_USERS_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@AutoConfigureMockMvc
class ReservationLifecycleTest {

    private static final int TOTAL_QUOTA = 4;
    private static final AtomicLong RESERVATION_IDS =
            new AtomicLong(9_100_000_000L + Math.abs(System.nanoTime() % 100_000_000L));

    @Resource
    private IReservationService reservationService;

    @Resource
    private IReservationPersistenceService persistenceService;

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

    @Autowired
    private MockMvc mockMvc;

    private final List<Long> reservationIds = new ArrayList<>();
    private final List<Long> batchIds = new ArrayList<>();
    private final List<Long> equipmentIds = new ArrayList<>();
    private final List<Long> laboratoryIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    private User adminA;
    private User adminB;
    private User userA;
    private User userB;
    private Laboratory laboratoryA;
    private Laboratory laboratoryB;
    private Equipment equipmentA;
    private Equipment equipmentB;
    private ReservationBatch batchA;
    private ReservationBatch batchB;
    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
        String suffix = String.format("%08d", Math.abs(System.nanoTime() % 100_000_000L));
        adminA = createUser("21" + suffix + "1", UserRole.LAB_ADMIN);
        adminB = createUser("21" + suffix + "2", UserRole.LAB_ADMIN);
        userA = createUser("21" + suffix + "3", UserRole.USER);
        userB = createUser("21" + suffix + "4", UserRole.USER);
        laboratoryA = createLaboratory("生命周期实验室A-" + suffix, adminA.getId());
        laboratoryB = createLaboratory("生命周期实验室B-" + suffix, adminB.getId());
        equipmentA = createEquipment("生命周期设备A-" + suffix, laboratoryA.getId());
        equipmentB = createEquipment("生命周期设备B-" + suffix, laboratoryB.getId());
        LocalDateTime now = LocalDateTime.now();
        batchA = createBatch(equipmentA.getId(), now.minusHours(1), now.plusHours(2),
                ReservationBatchStatus.PUBLISHED, TOTAL_QUOTA - 1);
        batchB = createBatch(equipmentB.getId(), now.minusHours(1), now.plusHours(2),
                ReservationBatchStatus.PUBLISHED, TOTAL_QUOTA - 1);
        adminToken = "phase9-admin-" + suffix;
        userToken = "phase9-user-" + suffix;
    }

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
        stringRedisTemplate.delete(Arrays.asList(
                LOGIN_USER_KEY + adminToken, LOGIN_USER_KEY + userToken));
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
    void shouldCancelExactlyOnceAcrossMysqlAndRedis() {
        Reservation reservation = createReservation(userA.getId(), batchA.getId(),
                ReservationStatus.RESERVED, false);
        occupy(batchA.getId(), userA.getId(), TOTAL_QUOTA - 1);
        loginAs(userA);

        reservationService.cancel(reservation.getId());
        reservationService.cancel(reservation.getId());

        Reservation stored = reservationMapper.selectById(reservation.getId());
        assertEquals(ReservationStatus.CANCELLED, stored.getStatus());
        assertTrue(stored.getQuotaReleased());
        assertEquals(TOTAL_QUOTA, batchMapper.selectById(batchA.getId()).getRemainingQuota());
        assertEquals(String.valueOf(TOTAL_QUOTA), stringRedisTemplate.opsForValue().get(quotaKey(batchA.getId())));
        assertFalse(isOccupying(batchA.getId(), userA.getId()));
        // 关键：只有真正跃迁的那一次才允许触碰 Redis。第二次取消是 ALREADY_CANCELLED，
        // 必须一次都不调用 rollback —— Redis 数值断言抓不到这点（Lua 自身幂等会掩盖），
        // 只有调用次数能抓到。
        verify(rollbackExecutor, times(1)).rollback(batchA.getId(), userA.getId());
    }

    @Test
    void shouldPersistNewReservationAfterCancellationAndTreatOnlyIdenticalRedeliveryAsDuplicate() {
        Reservation cancelled = createReservation(userA.getId(), batchA.getId(),
                ReservationStatus.CANCELLED, true);
        Reservation rebooked = reservation(userA.getId(), batchA.getId(), ReservationStatus.RESERVED);
        reservationIds.add(rebooked.getId());

        assertEquals(ReservationPersistResult.PERSISTED, persistenceService.persist(rebooked));
        assertEquals(TOTAL_QUOTA - 2, batchMapper.selectById(batchA.getId()).getRemainingQuota());
        assertNotNull(reservationMapper.selectById(cancelled.getId()));
        assertNotNull(reservationMapper.selectById(rebooked.getId()));

        // 同 id、同 user、同 batch 的重投递才是重复投递：幂等确认，不重复扣名额
        assertEquals(ReservationPersistResult.DUPLICATE,
                persistenceService.persist(new Reservation()
                        .setId(rebooked.getId())
                        .setUserId(userA.getId())
                        .setBatchId(batchA.getId())
                        .setStatus(ReservationStatus.RESERVED)));
        assertEquals(TOTAL_QUOTA - 2, batchMapper.selectById(batchA.getId()).getRemainingQuota());

        // 同 id 但归属不同 = 异常的主键冲突，必须抛出让消费端进死信，不得静默 ACK 成 DUPLICATE
        assertThrows(DuplicateKeyException.class,
                () -> persistenceService.persist(new Reservation()
                        .setId(rebooked.getId())
                        .setUserId(userB.getId())
                        .setBatchId(batchB.getId())
                        .setStatus(ReservationStatus.RESERVED)));
        assertEquals(TOTAL_QUOTA - 1, batchMapper.selectById(batchB.getId()).getRemainingQuota());

        // 对已取消行的重复取消：绝不触碰 Redis，更不得抹掉该用户新预约在 Redis 中的占位
        occupy(batchA.getId(), userA.getId(), TOTAL_QUOTA - 2);
        loginAs(userA);
        reservationService.cancel(cancelled.getId());
        assertEquals(String.valueOf(TOTAL_QUOTA - 2),
                stringRedisTemplate.opsForValue().get(quotaKey(batchA.getId())));
        assertTrue(isOccupying(batchA.getId(), userA.getId()));
        verify(rollbackExecutor, never()).rollback(batchA.getId(), userA.getId());
    }

    @Test
    void shouldReportDuplicateWithoutTouchingQuotaWhenAnOccupyingRowAlreadyExists() {
        // COMPLETED / NO_SHOW 仍占用 (user_id, batch_id) 唯一键槽位 —— 与 active_flag 生成列
        // 的判据一致。若前置查重只认 RESERVED，则会先扣名额再被唯一键拒成 DuplicateKeyException，
        // 既拿不到干净的 DUPLICATE，也要靠事务回滚兜底。
        createReservation(userA.getId(), batchA.getId(), ReservationStatus.COMPLETED, false);
        Reservation attempt = reservation(userA.getId(), batchA.getId(), ReservationStatus.RESERVED);

        assertEquals(ReservationPersistResult.DUPLICATE, persistenceService.persist(attempt));
        assertEquals(TOTAL_QUOTA - 1, batchMapper.selectById(batchA.getId()).getRemainingQuota());
        assertNull(reservationMapper.selectById(attempt.getId()));
    }

    @Test
    void shouldAllowMultipleCancelledRowsButOnlyOneNonCancelledRowPerUserAndBatch() {
        createReservation(userA.getId(), batchA.getId(), ReservationStatus.CANCELLED, true);
        createReservation(userA.getId(), batchA.getId(), ReservationStatus.CANCELLED, true);
        createReservation(userA.getId(), batchA.getId(), ReservationStatus.RESERVED, false);

        Reservation conflicting = reservation(userA.getId(), batchA.getId(),
                ReservationStatus.COMPLETED);
        assertThrows(DuplicateKeyException.class, () -> reservationMapper.insert(conflicting));
    }

    @Test
    void shouldLeaveRedisReleasePendingWhenRollbackFailsAndRecoverLater() {
        Reservation reservation = createReservation(userA.getId(), batchA.getId(),
                ReservationStatus.RESERVED, false);
        occupy(batchA.getId(), userA.getId(), TOTAL_QUOTA - 1);
        doThrow(new IllegalStateException("模拟 Redis 回滚故障"))
                .doCallRealMethod()
                .when(rollbackExecutor).rollback(batchA.getId(), userA.getId());
        loginAs(userA);

        assertDoesNotThrow(() -> reservationService.cancel(reservation.getId()));

        Reservation pending = reservationMapper.selectById(reservation.getId());
        assertEquals(ReservationStatus.CANCELLED, pending.getStatus());
        assertFalse(pending.getQuotaReleased());
        assertEquals(TOTAL_QUOTA, batchMapper.selectById(batchA.getId()).getRemainingQuota());
        assertEquals(String.valueOf(TOTAL_QUOTA - 1),
                stringRedisTemplate.opsForValue().get(quotaKey(batchA.getId())));
        assertTrue(isOccupying(batchA.getId(), userA.getId()));

        assertEquals(1, reconcileService.releasePendingQuotas(20));
        assertTrue(reservationMapper.selectById(reservation.getId()).getQuotaReleased());
        assertEquals(String.valueOf(TOTAL_QUOTA),
                stringRedisTemplate.opsForValue().get(quotaKey(batchA.getId())));
        assertFalse(isOccupying(batchA.getId(), userA.getId()));
    }

    @Test
    void shouldRejectCancellingCompletedOrNoShowReservations() {
        Reservation completed = createReservation(userA.getId(), batchA.getId(),
                ReservationStatus.COMPLETED, false);
        Reservation noShow = createReservation(userB.getId(), batchA.getId(),
                ReservationStatus.NO_SHOW, false);

        loginAs(userA);
        assertThrows(BadRequestException.class, () -> reservationService.cancel(completed.getId()));
        loginAs(userB);
        assertThrows(BadRequestException.class, () -> reservationService.cancel(noShow.getId()));

        assertEquals(TOTAL_QUOTA - 1, batchMapper.selectById(batchA.getId()).getRemainingQuota());
    }

    @Test
    void shouldVerifyOwnedReservationIdempotentlyAndRejectInvalidStates() {
        Reservation reserved = createReservation(userA.getId(), batchA.getId(),
                ReservationStatus.RESERVED, false);
        loginAs(adminA);

        reservationService.verify(reserved.getId());
        reservationService.verify(reserved.getId());

        assertEquals(ReservationStatus.COMPLETED,
                reservationMapper.selectById(reserved.getId()).getStatus());

        Reservation cancelled = createReservation(userB.getId(), batchA.getId(),
                ReservationStatus.CANCELLED, true);
        assertThrows(BadRequestException.class, () -> reservationService.verify(cancelled.getId()));
        reservationMapper.updateById(new Reservation()
                .setId(cancelled.getId())
                .setStatus(ReservationStatus.NO_SHOW));
        assertThrows(BadRequestException.class, () -> reservationService.verify(cancelled.getId()));
    }

    @Test
    void shouldRejectVerificationByAdministratorOfAnotherLaboratory() {
        Reservation reserved = createReservation(userA.getId(), batchA.getId(),
                ReservationStatus.RESERVED, false);
        loginAs(adminB);

        assertThrows(ForbiddenException.class, () -> reservationService.verify(reserved.getId()));
        assertEquals(ReservationStatus.RESERVED,
                reservationMapper.selectById(reserved.getId()).getStatus());
    }

    @Test
    void shouldEnforceCancellationEndAndVerificationStartWindows() {
        LocalDateTime now = LocalDateTime.now();
        ReservationBatch expiredBatch = createBatch(equipmentA.getId(), now.minusHours(3),
                now.minusHours(2), ReservationBatchStatus.PUBLISHED, TOTAL_QUOTA - 1);
        Reservation expired = createReservation(userA.getId(), expiredBatch.getId(),
                ReservationStatus.RESERVED, false);
        loginAs(userA);
        assertThrows(BadRequestException.class, () -> reservationService.cancel(expired.getId()));
        assertEquals(ReservationStatus.RESERVED, reservationMapper.selectById(expired.getId()).getStatus());
        assertEquals(TOTAL_QUOTA - 1, batchMapper.selectById(expiredBatch.getId()).getRemainingQuota());

        ReservationBatch futureBatch = createBatch(equipmentA.getId(), now.plusHours(1),
                now.plusHours(2), ReservationBatchStatus.PUBLISHED, TOTAL_QUOTA - 1);
        Reservation future = createReservation(userB.getId(), futureBatch.getId(),
                ReservationStatus.RESERVED, false);
        loginAs(adminA);
        assertThrows(BadRequestException.class, () -> reservationService.verify(future.getId()));
        assertEquals(ReservationStatus.RESERVED, reservationMapper.selectById(future.getId()).getStatus());
    }

    @Test
    void shouldHideWhetherCancellationTargetExistsOrBelongsToAnotherUser() {
        Reservation reservation = createReservation(userA.getId(), batchA.getId(),
                ReservationStatus.RESERVED, false);
        loginAs(userB);

        ForbiddenException wrongOwner = assertThrows(ForbiddenException.class,
                () -> reservationService.cancel(reservation.getId()));
        ForbiddenException missing = assertThrows(ForbiddenException.class,
                () -> reservationService.cancel(Long.MAX_VALUE));

        assertEquals("无权取消该预约", wrongOwner.getMessage());
        assertEquals(wrongOwner.getMessage(), missing.getMessage());
    }

    @Test
    void shouldScopeUserAndManagerQueriesAndProtectDetails() {
        Reservation mine = createReservation(userA.getId(), batchA.getId(),
                ReservationStatus.RESERVED, false);
        Reservation others = createReservation(userB.getId(), batchB.getId(),
                ReservationStatus.RESERVED, false);

        loginAs(userA);
        IPage<ReservationVO> myPage = reservationService.queryMyPage(new ReservationQueryDTO());
        assertEquals(1, myPage.getTotal());
        assertEquals(mine.getId(), myPage.getRecords().get(0).getId());
        assertEquals(equipmentA.getName(), myPage.getRecords().get(0).getEquipmentName());
        assertEquals(laboratoryA.getName(), myPage.getRecords().get(0).getLaboratoryName());
        assertEquals(mine.getId(), reservationService.queryMine(mine.getId()).getId());
        assertThrows(ForbiddenException.class, () -> reservationService.queryMine(others.getId()));

        loginAs(adminA);
        IPage<ReservationVO> managePage = reservationService.queryManagePage(new ReservationQueryDTO());
        assertEquals(1, managePage.getTotal());
        assertEquals(mine.getId(), managePage.getRecords().get(0).getId());
    }

    @Test
    void shouldExposeLifecycleEndpointsWithLoginAndManagerRoleGuards() throws Exception {
        Reservation cancellable = createReservation(userA.getId(), batchA.getId(),
                ReservationStatus.RESERVED, false);
        occupy(batchA.getId(), userA.getId(), TOTAL_QUOTA - 1);
        saveSession(userToken, userA);
        saveSession(adminToken, adminA);

        mockMvc.perform(get("/reservation/page"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/reservation/{id}/cancel", cancellable.getId())
                        .header("authorization", userToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/reservation/page")
                        .header("authorization", userToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/reservation/manage/page")
                        .header("authorization", userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/reservation/manage/page")
                        .header("authorization", adminToken))
                .andExpect(status().isOk());

        Reservation verifiable = createReservation(userA.getId(), batchA.getId(),
                ReservationStatus.RESERVED, false);
        mockMvc.perform(post("/reservation/{id}/verify", verifiable.getId())
                        .header("authorization", userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/reservation/{id}/verify", verifiable.getId())
                        .header("authorization", adminToken))
                .andExpect(status().isOk());
        assertEquals(ReservationStatus.COMPLETED,
                reservationMapper.selectById(verifiable.getId()).getStatus());
    }

    private User createUser(String phone, UserRole role) {
        User user = new User()
                .setPhone(phone)
                .setNickName("生命周期测试-" + phone)
                .setIcon("")
                .setRole(role);
        assertEquals(1, userMapper.insert(user));
        userIds.add(user.getId());
        return user;
    }

    private Laboratory createLaboratory(String name, Long adminUserId) {
        Laboratory laboratory = new Laboratory()
                .setName(name)
                .setCampus("测试校区")
                .setBuilding("测试楼")
                .setRoom("P901")
                .setDescription("Phase 9 生命周期测试")
                .setStatus(LaboratoryStatus.OPEN)
                .setAdminUserId(adminUserId);
        assertEquals(1, laboratoryMapper.insert(laboratory));
        laboratoryIds.add(laboratory.getId());
        return laboratory;
    }

    private Equipment createEquipment(String name, Long laboratoryId) {
        Equipment equipment = new Equipment()
                .setLaboratoryId(laboratoryId)
                .setCategoryId(1L)
                .setName(name)
                .setDescription("Phase 9 生命周期测试")
                .setLocation("P901")
                .setUsageRequirement("测试")
                .setStatus(EquipmentStatus.AVAILABLE)
                .setBookingEnabled(true);
        assertEquals(1, equipmentMapper.insert(equipment));
        equipmentIds.add(equipment.getId());
        return equipment;
    }

    private ReservationBatch createBatch(Long equipmentId, LocalDateTime startTime,
                                         LocalDateTime endTime, ReservationBatchStatus status,
                                         int remainingQuota) {
        ReservationBatch batch = new ReservationBatch()
                .setEquipmentId(equipmentId)
                .setStartTime(startTime)
                .setEndTime(endTime)
                .setBookingStartTime(startTime.minusDays(1))
                .setBookingEndTime(startTime.minusMinutes(1))
                .setTotalQuota(TOTAL_QUOTA)
                .setRemainingQuota(remainingQuota)
                .setStatus(status);
        assertEquals(1, batchMapper.insert(batch));
        batchIds.add(batch.getId());
        return batch;
    }

    private Reservation createReservation(Long userId, Long batchId, ReservationStatus status,
                                              boolean quotaReleased) {
        Reservation reservation = reservation(userId, batchId, status)
                .setQuotaReleased(quotaReleased);
        assertEquals(1, reservationMapper.insert(reservation));
        reservationIds.add(reservation.getId());
        return reservation;
    }

    private Reservation reservation(Long userId, Long batchId, ReservationStatus status) {
        return new Reservation()
                .setId(RESERVATION_IDS.incrementAndGet())
                .setUserId(userId)
                .setBatchId(batchId)
                .setStatus(status)
                .setCreateTime(LocalDateTime.now());
    }

    private void occupy(Long batchId, Long userId, int quota) {
        stringRedisTemplate.opsForValue().set(quotaKey(batchId), String.valueOf(quota));
        stringRedisTemplate.opsForSet().add(usersKey(batchId), userId.toString());
    }

    private boolean isOccupying(Long batchId, Long userId) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                .isMember(usersKey(batchId), userId.toString()));
    }

    private void loginAs(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setNickName(user.getNickName());
        dto.setRole(user.getRole());
        UserHolder.saveUser(dto);
    }

    private void saveSession(String token, User user) {
        Map<String, String> session = new HashMap<>();
        session.put("id", String.valueOf(user.getId()));
        session.put("role", user.getRole().name());
        session.put("nickName", user.getNickName());
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, session);
    }

    private String quotaKey(Long batchId) {
        return RESERVATION_BATCH_QUOTA_KEY + batchId;
    }

    private String usersKey(Long batchId) {
        return RESERVATION_BATCH_USERS_KEY + batchId;
    }
}
