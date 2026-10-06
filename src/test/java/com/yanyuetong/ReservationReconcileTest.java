package com.yanyuetong;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yanyuetong.dto.ReconcileSummaryDTO;
import com.yanyuetong.entity.Equipment;
import com.yanyuetong.entity.Laboratory;
import com.yanyuetong.entity.Reservation;
import com.yanyuetong.entity.ReservationBatch;
import com.yanyuetong.entity.ReservationRequest;
import com.yanyuetong.entity.User;
import com.yanyuetong.enums.EquipmentStatus;
import com.yanyuetong.enums.LaboratoryStatus;
import com.yanyuetong.enums.ReservationBatchStatus;
import com.yanyuetong.enums.ReservationRequestStatus;
import com.yanyuetong.enums.UserRole;
import com.yanyuetong.mapper.EquipmentMapper;
import com.yanyuetong.mapper.LaboratoryMapper;
import com.yanyuetong.mapper.ReservationBatchMapper;
import com.yanyuetong.mapper.ReservationMapper;
import com.yanyuetong.mapper.ReservationRequestMapper;
import com.yanyuetong.mapper.UserMapper;
import com.yanyuetong.service.IReservationReconcileService;
import com.yanyuetong.service.IReservationRequestService;
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

import static com.yanyuetong.enums.ReservationRequestStatus.COMPENSATED;
import static com.yanyuetong.enums.ReservationRequestStatus.CONFIRMED;
import static com.yanyuetong.enums.ReservationRequestStatus.PRE_ACCEPTED;
import static com.yanyuetong.enums.ReservationRequestStatus.PUBLISHED;
import static com.yanyuetong.enums.ReservationRequestStatus.UNKNOWN;
import static com.yanyuetong.utils.RedisConstants.RESERVATION_BATCH_QUOTA_KEY;
import static com.yanyuetong.utils.RedisConstants.RESERVATION_BATCH_USERS_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * 需要真实 MySQL、真实 Redis，并已应用 phase2/phase5/phase7/phase8 DDL；RabbitMQ 监听器在本类停用。
 * 启用调度后，每个 Spring 上下文会在启动时立即执行一次 scheduled 薄壳。本类不假设薄壳不会运行：
 * 上下文启动时夹具尚未创建，之后所有行为断言均直接调用 reconcile(int)。
 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class ReservationReconcileTest {

    private static final int INITIAL_QUOTA = 4;
    private static final String RECONCILE_FAILURE_REASON = "RECONCILE_NO_RESERVATION";
    private static final String RECONCILE_ATTEMPT_FAILED_REASON = "RECONCILE_ATTEMPT_FAILED";
    private static final AtomicLong REQUEST_ID_SEQUENCE =
            new AtomicLong(8_800_000_000L + Math.abs(System.nanoTime() % 100_000_000L));

    @Resource
    private IReservationReconcileService reconcileService;

    @SpyBean
    private IReservationRequestService reservationRequestService;

    @SpyBean
    private ReservationQuotaRollbackExecutor rollbackExecutor;

    @Resource
    private ReservationRequestMapper reservationRequestMapper;

    @Resource
    private ReservationMapper reservationMapper;

    @Resource
    private ReservationBatchMapper reservationBatchMapper;

    @Resource
    private EquipmentMapper equipmentMapper;

    @Resource
    private LaboratoryMapper laboratoryMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final List<Long> requestIds = new ArrayList<>();
    private final List<Long> reservationIds = new ArrayList<>();
    private final List<Long> batchIds = new ArrayList<>();
    private final List<Long> equipmentIds = new ArrayList<>();
    private final List<Long> laboratoryIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    private User admin;
    private User user;
    private Laboratory laboratory;
    private Equipment equipment;
    private ReservationBatch batch;

    @BeforeEach
    void setUp() {
        String suffix = String.format("%08d", Math.abs(System.nanoTime() % 100_000_000L));
        admin = createUser("17" + suffix + "1", "对账测试管理员-" + suffix, UserRole.LAB_ADMIN);
        user = createUser("17" + suffix + "2", "对账测试用户-" + suffix, UserRole.USER);
        laboratory = createLaboratory("对账测试实验室-" + suffix, admin.getId());
        equipment = createEquipment("对账测试设备-" + suffix, laboratory.getId());
        batch = createBatch(equipment.getId());
    }

    @AfterEach
    void cleanUp() {
        requestIds.forEach(reservationRequestMapper::deleteById);
        reservationIds.forEach(reservationMapper::deleteById);
        for (Long batchId : batchIds) {
            stringRedisTemplate.delete(Arrays.asList(quotaKey(batchId), usersKey(batchId)));
            reservationBatchMapper.deleteById(batchId);
        }
        equipmentIds.forEach(equipmentMapper::deleteById);
        laboratoryIds.forEach(laboratoryMapper::deleteById);
        userIds.forEach(userMapper::deleteById);
    }

    @Test
    void shouldConfirmPersistedReservationWithoutRollingBackQuota() {
        long requestId = createRequest(PUBLISHED, true);
        reservationRequestMapper.updateById(new ReservationRequest()
                .setRequestId(requestId)
                .setFailReason("PUBLISH_CONFIRM_WRITE_FAILED"));
        makeStale(requestId);
        createReservation(requestId);
        occupyQuota();

        ReconcileSummaryDTO summary = reconcileService.reconcile(200);

        assertSummary(summary, 1, 1, 0, 0, 0);
        ReservationRequest stored = reservationRequestMapper.selectById(requestId);
        assertEquals(CONFIRMED, stored.getStatus());
        assertEquals("PUBLISH_CONFIRM_WRITE_FAILED", stored.getFailReason());
        assertEquals("3", stringRedisTemplate.opsForValue().get(quotaKey(batch.getId())));
        assertTrue(isUserOccupyingQuota());
    }

    @Test
    void shouldCompensateMissingReservationOnceAcrossRepeatedRuns() {
        long requestId = createRequest(PUBLISHED, true);
        occupyQuota();

        ReconcileSummaryDTO first = reconcileService.reconcile(200);

        assertSummary(first, 1, 0, 1, 0, 0);
        ReservationRequest stored = reservationRequestMapper.selectById(requestId);
        assertEquals(COMPENSATED, stored.getStatus());
        assertEquals(RECONCILE_FAILURE_REASON, stored.getFailReason());
        assertEquals("4", stringRedisTemplate.opsForValue().get(quotaKey(batch.getId())));
        assertFalse(isUserOccupyingQuota());

        ReconcileSummaryDTO second = reconcileService.reconcile(200);

        assertSummary(second, 0, 0, 0, 0, 0);
        assertEquals("4", stringRedisTemplate.opsForValue().get(quotaKey(batch.getId())));
        assertFalse(isUserOccupyingQuota());
    }

    @Test
    void shouldScanEveryNonTerminalStatusAndSkipTerminalOnes() {
        long preAcceptedId = createRequest(PRE_ACCEPTED, true);
        long publishedId = createRequest(PUBLISHED, true);
        long unknownId = createRequest(UNKNOWN, true);
        long confirmedId = createRequest(CONFIRMED, true);
        long compensatedId = createRequest(COMPENSATED, true);
        String confirmedFailReason = "CONFIRMED_FIXTURE_FAILURE";
        String compensatedFailReason = "COMPENSATED_FIXTURE_FAILURE";
        assertEquals(1, reservationRequestMapper.updateById(new ReservationRequest()
                .setRequestId(confirmedId)
                .setFailReason(confirmedFailReason)));
        assertEquals(1, reservationRequestMapper.updateById(new ReservationRequest()
                .setRequestId(compensatedId)
                .setFailReason(compensatedFailReason)));
        makeStale(confirmedId);
        makeStale(compensatedId);
        occupyQuota();

        ReconcileSummaryDTO summary = reconcileService.reconcile(200);

        // 防止 nonTerminalStatuses() 漏掉任一非终态，导致该状态的陈旧请求永久无人对账。
        assertSummary(summary, 3, 0, 3, 0, 0);
        assertEquals(COMPENSATED, reservationRequestMapper.selectById(preAcceptedId).getStatus());
        assertEquals(COMPENSATED, reservationRequestMapper.selectById(publishedId).getStatus());
        assertEquals(COMPENSATED, reservationRequestMapper.selectById(unknownId).getStatus());

        ReservationRequest storedConfirmed = reservationRequestMapper.selectById(confirmedId);
        assertEquals(CONFIRMED, storedConfirmed.getStatus());
        assertEquals(confirmedFailReason, storedConfirmed.getFailReason());
        ReservationRequest storedCompensated = reservationRequestMapper.selectById(compensatedId);
        assertEquals(COMPENSATED, storedCompensated.getStatus());
        assertEquals(compensatedFailReason, storedCompensated.getFailReason());
    }

    @Test
    void shouldIgnoreFreshNonTerminalRequest() {
        long requestId = createRequest(PUBLISHED, false);
        occupyQuota();

        ReconcileSummaryDTO summary = reconcileService.reconcile(200);

        assertSummary(summary, 0, 0, 0, 0, 0);
        assertEquals(PUBLISHED, reservationRequestMapper.selectById(requestId).getStatus());
        assertEquals("3", stringRedisTemplate.opsForValue().get(quotaKey(batch.getId())));
        assertTrue(isUserOccupyingQuota());
    }

    @Test
    void shouldBackOffFailedRequestAndRecoverAfterThreshold() {
        long requestId = createRequest(PUBLISHED, true);
        occupyQuota();
        LocalDateTime staleUpdateTime = reservationRequestMapper.selectById(requestId).getUpdateTime();
        doThrow(new IllegalStateException("回滚故障")).doCallRealMethod()
                .when(rollbackExecutor).rollback(batch.getId(), user.getId());

        ReconcileSummaryDTO summary = reconcileService.reconcile(200);

        assertSummary(summary, 1, 0, 0, 0, 1);
        ReservationRequest stored = reservationRequestMapper.selectById(requestId);
        assertEquals(PUBLISHED, stored.getStatus());
        assertEquals(RECONCILE_ATTEMPT_FAILED_REASON, stored.getFailReason());
        assertTrue(stored.getUpdateTime().isAfter(staleUpdateTime));
        assertEquals("3", stringRedisTemplate.opsForValue().get(quotaKey(batch.getId())));
        assertTrue(isUserOccupyingQuota());

        ReconcileSummaryDTO backedOff = reconcileService.reconcile(200);
        assertSummary(backedOff, 0, 0, 0, 0, 0);

        makeStale(requestId);
        ReconcileSummaryDTO recovered = reconcileService.reconcile(200);

        assertSummary(recovered, 1, 0, 1, 0, 0);
        ReservationRequest recoveredRequest = reservationRequestMapper.selectById(requestId);
        assertEquals(COMPENSATED, recoveredRequest.getStatus());
        assertEquals(RECONCILE_FAILURE_REASON, recoveredRequest.getFailReason());
        assertEquals("4", stringRedisTemplate.opsForValue().get(quotaKey(batch.getId())));
        assertFalse(isUserOccupyingQuota());
    }

    @Test
    void shouldAdvanceUpdateTimeWhenSameReconcileFailureRepeats() {
        long requestId = createRequest(PUBLISHED, true);
        doThrow(new IllegalStateException("持续回滚故障"))
                .when(rollbackExecutor).rollback(batch.getId(), user.getId());

        ReconcileSummaryDTO first = reconcileService.reconcile(1);

        assertSummary(first, 1, 0, 0, 0, 1);
        assertEquals(RECONCILE_ATTEMPT_FAILED_REASON,
                reservationRequestMapper.selectById(requestId).getFailReason());

        makeStale(requestId);
        LocalDateTime repeatedFailureStaleTime =
                reservationRequestMapper.selectById(requestId).getUpdateTime();

        ReconcileSummaryDTO second = reconcileService.reconcile(1);

        assertSummary(second, 1, 0, 0, 0, 1);
        ReservationRequest stored = reservationRequestMapper.selectById(requestId);
        assertEquals(RECONCILE_ATTEMPT_FAILED_REASON, stored.getFailReason());
        assertTrue(stored.getUpdateTime().isAfter(repeatedFailureStaleTime));
        List<ReservationRequest> nextWindow = reservationRequestService.listStale(
                LocalDateTime.now().minusMinutes(10), 1);
        assertFalse(nextWindow.stream()
                .anyMatch(request -> request.getRequestId().equals(requestId)));
    }

    @Test
    void shouldMoveFailedRequestOutOfStaleWindowSoLaterRequestCanProgress() {
        int limit = 2;
        LocalDateTime now = LocalDateTime.now();
        ReservationBatch failingBatch = createBatch(equipment.getId());
        long failingRequestId = createRequest(
                PUBLISHED, failingBatch.getId(), now.minusMinutes(14));
        createRequest(PUBLISHED, batch.getId(), now.minusMinutes(13));
        long initiallyOutsideWindowId = createRequest(
                PUBLISHED, batch.getId(), now.minusMinutes(12));
        doThrow(new IllegalStateException("持续回滚故障"))
                .when(rollbackExecutor).rollback(failingBatch.getId(), user.getId());

        ReconcileSummaryDTO first = reconcileService.reconcile(limit);

        assertSummary(first, limit, 0, 1, 0, 1);
        assertEquals(RECONCILE_ATTEMPT_FAILED_REASON,
                reservationRequestMapper.selectById(failingRequestId).getFailReason());
        List<ReservationRequest> nextWindow = reservationRequestService.listStale(
                LocalDateTime.now().minusMinutes(10), limit);
        assertFalse(nextWindow.stream()
                .anyMatch(request -> request.getRequestId().equals(failingRequestId)));
        assertTrue(nextWindow.stream()
                .anyMatch(request -> request.getRequestId().equals(initiallyOutsideWindowId)));

        ReconcileSummaryDTO second = reconcileService.reconcile(limit);

        assertSummary(second, 1, 0, 1, 0, 0);
        assertEquals(COMPENSATED,
                reservationRequestMapper.selectById(initiallyOutsideWindowId).getStatus());
    }

    @Test
    void shouldRespectPerRunLimit() {
        createRequest(PUBLISHED, true);
        createRequest(PUBLISHED, true);
        createRequest(PUBLISHED, true);
        occupyQuota();

        ReconcileSummaryDTO summary = reconcileService.reconcile(1);

        assertEquals(1, summary.getScanned());
        assertEquals(2, countNonTerminalFixtureRequests());
    }

    @Test
    void shouldCountCasConflictAsSkippedAndConvergeOnNextRun() {
        long requestId = createRequest(PUBLISHED, true);
        createReservation(requestId);
        occupyQuota();
        // 可变参数匹配器按元素匹配：这三个匹配器必须与 nonTerminalStatuses() 保持同步。
        // 如果该集合发生变化，此存根必须停止匹配，以便精确的汇总断言明确失败。
        doReturn(false).doCallRealMethod()
                .when(reservationRequestService)
                .casStatus(eq(requestId), eq(CONFIRMED), any(), any(), any());

        ReconcileSummaryDTO first = reconcileService.reconcile(200);

        assertSummary(first, 1, 0, 0, 1, 0);
        assertEquals(PUBLISHED, reservationRequestMapper.selectById(requestId).getStatus());
        assertEquals("3", stringRedisTemplate.opsForValue().get(quotaKey(batch.getId())));
        assertTrue(isUserOccupyingQuota());

        ReconcileSummaryDTO second = reconcileService.reconcile(200);

        assertSummary(second, 1, 1, 0, 0, 0);
        assertEquals(CONFIRMED, reservationRequestMapper.selectById(requestId).getStatus());
        assertEquals("3", stringRedisTemplate.opsForValue().get(quotaKey(batch.getId())));
        assertTrue(isUserOccupyingQuota());
    }

    @Test
    void shouldNotReturnQuotaTwiceWhenCompensationCasIsSkipped() {
        long requestId = createRequest(PUBLISHED, true);
        occupyQuota();
        // 可变参数匹配器按元素匹配：这三个匹配器必须与 nonTerminalStatuses() 保持同步。
        // 如果该集合发生变化，此存根必须停止匹配，以便精确的汇总断言明确失败。
        doReturn(false).doCallRealMethod()
                .when(reservationRequestService)
                .casStatusWithFailReason(
                        eq(requestId), eq(COMPENSATED), anyString(),
                        any(), any(), any());

        ReconcileSummaryDTO first = reconcileService.reconcile(200);

        assertSummary(first, 1, 0, 0, 1, 0);
        assertEquals(PUBLISHED, reservationRequestMapper.selectById(requestId).getStatus());
        assertEquals("4", stringRedisTemplate.opsForValue().get(quotaKey(batch.getId())));
        assertFalse(isUserOccupyingQuota());

        ReconcileSummaryDTO second = reconcileService.reconcile(200);

        assertSummary(second, 1, 0, 1, 0, 0);
        ReservationRequest stored = reservationRequestMapper.selectById(requestId);
        assertEquals(COMPENSATED, stored.getStatus());
        assertEquals(RECONCILE_FAILURE_REASON, stored.getFailReason());
        assertEquals("4", stringRedisTemplate.opsForValue().get(quotaKey(batch.getId())));
        assertFalse(isUserOccupyingQuota());
    }

    private User createUser(String phone, String nickName, UserRole role) {
        User fixture = new User()
                .setPhone(phone)
                .setNickName(nickName)
                .setIcon("")
                .setRole(role);
        assertEquals(1, userMapper.insert(fixture));
        userIds.add(fixture.getId());
        return fixture;
    }

    private Laboratory createLaboratory(String name, Long adminUserId) {
        Laboratory fixture = new Laboratory()
                .setName(name)
                .setCampus("测试校区")
                .setBuilding("测试楼")
                .setRoom("R801")
                .setDescription("预约对账集成测试")
                .setStatus(LaboratoryStatus.OPEN)
                .setAdminUserId(adminUserId);
        assertEquals(1, laboratoryMapper.insert(fixture));
        laboratoryIds.add(fixture.getId());
        return fixture;
    }

    private Equipment createEquipment(String name, Long laboratoryId) {
        Equipment fixture = new Equipment()
                .setLaboratoryId(laboratoryId)
                .setCategoryId(1L)
                .setName(name)
                .setDescription("预约对账集成测试")
                .setLocation("R801-TEST")
                .setUsageRequirement("仅供自动化测试")
                .setStatus(EquipmentStatus.AVAILABLE)
                .setBookingEnabled(true);
        assertEquals(1, equipmentMapper.insert(fixture));
        equipmentIds.add(fixture.getId());
        return fixture;
    }

    private ReservationBatch createBatch(Long equipmentId) {
        LocalDateTime now = LocalDateTime.now();
        ReservationBatch fixture = new ReservationBatch()
                .setEquipmentId(equipmentId)
                .setStartTime(now.plusDays(2))
                .setEndTime(now.plusDays(2).plusHours(2))
                .setBookingStartTime(now.minusHours(1))
                .setBookingEndTime(now.plusDays(1))
                .setTotalQuota(INITIAL_QUOTA)
                .setRemainingQuota(INITIAL_QUOTA)
                .setStatus(ReservationBatchStatus.PUBLISHED);
        assertEquals(1, reservationBatchMapper.insert(fixture));
        batchIds.add(fixture.getId());
        return fixture;
    }

    private long createRequest(ReservationRequestStatus status, boolean stale) {
        long requestId = REQUEST_ID_SEQUENCE.incrementAndGet();
        ReservationRequest request = new ReservationRequest()
                .setRequestId(requestId)
                .setUserId(user.getId())
                .setBatchId(batch.getId())
                .setStatus(status);
        assertEquals(1, reservationRequestMapper.insert(request));
        requestIds.add(requestId);
        if (stale) {
            makeStale(requestId);
        }
        return requestId;
    }

    private long createRequest(ReservationRequestStatus status, Long batchId,
                               LocalDateTime updateTime) {
        long requestId = REQUEST_ID_SEQUENCE.incrementAndGet();
        ReservationRequest request = new ReservationRequest()
                .setRequestId(requestId)
                .setUserId(user.getId())
                .setBatchId(batchId)
                .setStatus(status);
        assertEquals(1, reservationRequestMapper.insert(request));
        requestIds.add(requestId);
        assertEquals(1, reservationRequestMapper.updateById(new ReservationRequest()
                .setRequestId(requestId)
                .setUpdateTime(updateTime)));
        return requestId;
    }

    private void makeStale(long requestId) {
        assertEquals(1, reservationRequestMapper.updateById(new ReservationRequest()
                .setRequestId(requestId)
                .setUpdateTime(LocalDateTime.now().minusMinutes(11))));
    }

    private void createReservation(long requestId) {
        Reservation reservation = new Reservation()
                .setId(requestId)
                .setUserId(user.getId())
                .setBatchId(batch.getId())
                .setCreateTime(LocalDateTime.now());
        assertEquals(1, reservationMapper.insert(reservation));
        reservationIds.add(requestId);
    }

    private void occupyQuota() {
        stringRedisTemplate.opsForValue().set(quotaKey(batch.getId()),
                String.valueOf(INITIAL_QUOTA - 1));
        stringRedisTemplate.opsForSet().add(usersKey(batch.getId()), user.getId().toString());
    }

    private boolean isUserOccupyingQuota() {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                .isMember(usersKey(batch.getId()), user.getId().toString()));
    }

    private int countNonTerminalFixtureRequests() {
        return reservationRequestMapper.selectCount(new LambdaQueryWrapper<ReservationRequest>()
                .in(ReservationRequest::getRequestId, requestIds)
                .in(ReservationRequest::getStatus, ReservationRequestStatus.nonTerminalStatuses()));
    }

    private String quotaKey(Long batchId) {
        return RESERVATION_BATCH_QUOTA_KEY + batchId;
    }

    private String usersKey(Long batchId) {
        return RESERVATION_BATCH_USERS_KEY + batchId;
    }

    private void assertSummary(ReconcileSummaryDTO summary, int scanned, int confirmed,
                               int compensated, int skipped, int failed) {
        assertEquals(scanned, summary.getScanned());
        assertEquals(confirmed, summary.getConfirmed());
        assertEquals(compensated, summary.getCompensated());
        assertEquals(skipped, summary.getSkipped());
        assertEquals(failed, summary.getFailed());
    }
}
