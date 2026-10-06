package com.yanyuetong;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yanyuetong.dto.ReservationBatchQueryDTO;
import com.yanyuetong.dto.UserDTO;
import com.yanyuetong.entity.Equipment;
import com.yanyuetong.entity.Laboratory;
import com.yanyuetong.entity.ReservationBatch;
import com.yanyuetong.entity.User;
import com.yanyuetong.enums.BookingState;
import com.yanyuetong.enums.EquipmentStatus;
import com.yanyuetong.enums.LaboratoryStatus;
import com.yanyuetong.enums.ReservationBatchStatus;
import com.yanyuetong.enums.UserRole;
import com.yanyuetong.exception.BadRequestException;
import com.yanyuetong.service.IEquipmentService;
import com.yanyuetong.service.ILaboratoryService;
import com.yanyuetong.service.IReservationBatchService;
import com.yanyuetong.service.IUserService;
import com.yanyuetong.utils.UserHolder;
import com.yanyuetong.vo.ReservationBatchVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.yanyuetong.utils.RedisConstants.RESERVATION_BATCH_QUOTA_KEY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ReservationBatchLifecycleTest {

    private static final int CONCURRENCY_ROUNDS = 24;
    private static final long[] UPDATE_STAGGER_NANOS = {
            0L, 100_000L, 200_000L, 500_000L, 1_000_000L, 2_000_000L
    };

    @Resource
    private IReservationBatchService batchService;

    @Resource
    private IEquipmentService equipmentService;

    @Resource
    private ILaboratoryService laboratoryService;

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final List<Long> batchIds = new ArrayList<>();
    private User adminA;
    private User adminB;
    private Laboratory laboratoryA;
    private Laboratory laboratoryB;
    private Equipment equipmentA;
    private Equipment equipmentB;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
        String suffix = String.format("%08d", Math.abs(System.nanoTime() % 100_000_000L));
        adminA = createAdmin("16" + suffix + "1", "批次测试管理员A");
        adminB = createAdmin("16" + suffix + "2", "批次测试管理员B");
        laboratoryA = createLaboratory("批次测试实验室A-" + suffix, adminA.getId());
        laboratoryB = createLaboratory("批次测试实验室B-" + suffix, adminB.getId());
        long categoryId = 8_000_000_000L + Math.abs(System.nanoTime() % 1_000_000L);
        equipmentA = createEquipment("批次测试设备A-" + suffix, laboratoryA.getId(), categoryId);
        equipmentB = createEquipment("批次测试设备B-" + suffix, laboratoryB.getId(), categoryId);
    }

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
        for (Long batchId : batchIds) {
            stringRedisTemplate.delete(RESERVATION_BATCH_QUOTA_KEY + batchId);
            batchService.removeById(batchId);
        }
        if (equipmentA != null && equipmentA.getId() != null) {
            equipmentService.removeById(equipmentA.getId());
        }
        if (equipmentB != null && equipmentB.getId() != null) {
            equipmentService.removeById(equipmentB.getId());
        }
        if (laboratoryA != null && laboratoryA.getId() != null) {
            laboratoryService.removeById(laboratoryA.getId());
        }
        if (laboratoryB != null && laboratoryB.getId() != null) {
            laboratoryService.removeById(laboratoryB.getId());
        }
        if (adminA != null && adminA.getId() != null) {
            userService.removeById(adminA.getId());
        }
        if (adminB != null && adminB.getId() != null) {
            userService.removeById(adminB.getId());
        }
    }

    @Test
    void shouldPublishDraftAndInitializeRedisQuota() {
        ReservationBatch request = validBatch(equipmentA.getId())
                .setStatus(ReservationBatchStatus.CANCELLED)
                .setRemainingQuota(999);
        Long id = createBatch(request, adminA);
        ReservationBatch draft = batchService.getById(id);
        assertEquals(ReservationBatchStatus.DRAFT, draft.getStatus());
        assertEquals(draft.getTotalQuota(), draft.getRemainingQuota());

        assertDoesNotThrow(() -> batchService.publish(id));

        ReservationBatch published = batchService.getById(id);
        String key = quotaKey(id);
        assertEquals(ReservationBatchStatus.PUBLISHED, published.getStatus());
        assertEquals(published.getTotalQuota(), published.getRemainingQuota());
        assertEquals(published.getTotalQuota().toString(), stringRedisTemplate.opsForValue().get(key));
        assertTrue(stringRedisTemplate.getExpire(key, TimeUnit.SECONDS) > 0);
    }

    @Test
    void shouldWhitelistFieldsWhenUpdatingDraft() {
        Long id = createBatch(validBatch(equipmentA.getId()), adminA);
        loginAs(adminA);

        batchService.updateDraft(new ReservationBatch()
                .setId(id)
                .setTotalQuota(7)
                .setRemainingQuota(999)
                .setStatus(ReservationBatchStatus.CANCELLED));

        ReservationBatch updated = batchService.getById(id);
        assertEquals(ReservationBatchStatus.DRAFT, updated.getStatus());
        assertEquals(7, updated.getTotalQuota());
        assertEquals(7, updated.getRemainingQuota());
    }

    @Test
    void shouldRejectUpdatingPublishedBatchWithoutApplyingInjectedFields() {
        Long id = createBatch(validBatch(equipmentA.getId()), adminA);
        batchService.publish(id);
        Integer originalQuota = batchService.getById(id).getRemainingQuota();

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> batchService.updateDraft(new ReservationBatch()
                        .setId(id)
                        .setTotalQuota(999)
                        .setRemainingQuota(999)
                        .setStatus(ReservationBatchStatus.CANCELLED)));

        assertEquals("仅草稿批次可修改", exception.getMessage());
        ReservationBatch unchanged = batchService.getById(id);
        assertEquals(ReservationBatchStatus.PUBLISHED, unchanged.getStatus());
        assertEquals(originalQuota, unchanged.getRemainingQuota());
    }

    @Test
    void shouldRejectI1WhenTotalQuotaIsLessThanOne() {
        ReservationBatch batch = validBatch(equipmentA.getId()).setTotalQuota(0);
        assertPublishRejected(createBatch(batch, adminA), "总名额必须大于等于1");
    }

    @Test
    void shouldRejectI2WhenUsageWindowIsInvalid() {
        ReservationBatch batch = validBatch(equipmentA.getId());
        batch.setEndTime(batch.getStartTime());
        assertPublishRejected(createBatch(batch, adminA), "使用开始时间必须早于结束时间");
    }

    @Test
    void shouldRejectI3WhenBookingWindowIsInvalid() {
        ReservationBatch batch = validBatch(equipmentA.getId());
        batch.setBookingEndTime(batch.getBookingStartTime());
        assertPublishRejected(createBatch(batch, adminA), "预约开放时间必须早于截止时间");
    }

    @Test
    void shouldRejectI4WhenBookingEndsAfterUsageStarts() {
        ReservationBatch batch = validBatch(equipmentA.getId());
        batch.setBookingEndTime(batch.getStartTime().plusHours(1));
        assertPublishRejected(createBatch(batch, adminA), "预约截止时间不能晚于使用开始时间");
    }

    @Test
    void shouldRejectI5WhenBookingWindowHasExpired() {
        LocalDateTime now = LocalDateTime.now();
        ReservationBatch batch = validBatch(equipmentA.getId())
                .setBookingStartTime(now.minusDays(2))
                .setBookingEndTime(now.minusDays(1));
        assertPublishRejected(createBatch(batch, adminA), "预约截止时间必须晚于当前时间");
    }

    @Test
    void shouldRejectI6WhenEquipmentIsUnavailable() {
        equipmentA.setStatus(EquipmentStatus.MAINTENANCE);
        equipmentService.updateById(equipmentA);
        assertPublishRejected(createBatch(validBatch(equipmentA.getId()), adminA), "设备当前不可预约");
    }

    @Test
    void shouldRejectI7WhenUsageWindowOverlapsAnotherBatch() {
        createBatch(validBatch(equipmentA.getId()), adminA);
        ReservationBatch overlapping = validBatch(equipmentA.getId());
        overlapping.setStartTime(overlapping.getStartTime().plusMinutes(30));
        overlapping.setEndTime(overlapping.getEndTime().plusMinutes(30));
        assertPublishRejected(createBatch(overlapping, adminA), "同一设备的使用时段不能重叠");
    }

    @Test
    void shouldClosePublishedBatchDeleteQuotaAndRejectRepeatedClose() {
        Long id = createBatch(validBatch(equipmentA.getId()), adminA);
        batchService.publish(id);
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.hasKey(quotaKey(id))));

        assertDoesNotThrow(() -> batchService.close(id));
        assertEquals(ReservationBatchStatus.CLOSED, batchService.getById(id).getStatus());
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(quotaKey(id))));

        assertThrows(BadRequestException.class, () -> batchService.close(id));
        assertThrows(BadRequestException.class, () -> batchService.publish(id));
        assertEquals(ReservationBatchStatus.CLOSED, batchService.getById(id).getStatus());
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(quotaKey(id))));
    }

    @Test
    void shouldCancelPublishedBatchDeleteQuotaAndRejectFurtherTransitions() {
        Long id = createBatch(validBatch(equipmentA.getId()), adminA);
        batchService.publish(id);

        assertDoesNotThrow(() -> batchService.cancel(id));
        assertEquals(ReservationBatchStatus.CANCELLED, batchService.getById(id).getStatus());
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(quotaKey(id))));
        assertThrows(BadRequestException.class, () -> batchService.publish(id));
        assertThrows(BadRequestException.class, () -> batchService.close(id));
    }

    @Test
    void shouldRejectRepeatedPublishWithoutOverwritingRedisQuota() {
        Long id = createBatch(validBatch(equipmentA.getId()), adminA);
        batchService.publish(id);
        stringRedisTemplate.opsForValue().set(quotaKey(id), "777");

        assertThrows(BadRequestException.class, () -> batchService.publish(id));

        assertEquals("777", stringRedisTemplate.opsForValue().get(quotaKey(id)));
        assertEquals(ReservationBatchStatus.PUBLISHED, batchService.getById(id).getStatus());
    }

    @Test
    void shouldPublishExactlyOnceAndPreserveQuotaUnderConcurrency() throws Exception {
        Long id = createBatch(validBatch(equipmentA.getId()), adminA);
        int attempts = 8;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try {
            for (int i = 0; i < attempts; i++) {
                results.add(executor.submit(() -> {
                    loginAs(adminA);
                    ready.countDown();
                    start.await();
                    try {
                        batchService.publish(id);
                        return true;
                    } catch (BadRequestException e) {
                        return false;
                    } finally {
                        UserHolder.removeUser();
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int successCount = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    successCount++;
                }
            }

            ReservationBatch published = batchService.getById(id);
            String key = quotaKey(id);
            assertEquals(1, successCount);
            assertEquals(ReservationBatchStatus.PUBLISHED, published.getStatus());
            assertEquals(published.getTotalQuota().toString(), stringRedisTemplate.opsForValue().get(key));
            assertTrue(stringRedisTemplate.getExpire(key, TimeUnit.SECONDS) > 0);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldNeverResurrectCancelledBatchWhenPublishAndCancelRace() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < CONCURRENCY_ROUNDS; round++) {
                Long id = createBatch(validBatchAtDay(equipmentA.getId(), 30 + round * 2), adminA);
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> publishResult = executor.submit(
                        () -> runConcurrentAction(ready, start, () -> batchService.publish(id)));
                Future<Boolean> cancelResult = executor.submit(
                        () -> runConcurrentAction(ready, start, () -> batchService.cancel(id)));

                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                publishResult.get(10, TimeUnit.SECONDS);
                cancelResult.get(10, TimeUnit.SECONDS);

                ReservationBatch batch = batchService.getById(id);
                assertNotEquals(ReservationBatchStatus.PUBLISHED, batch.getStatus());
                if (batch.getStatus() == ReservationBatchStatus.CANCELLED) {
                    assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(quotaKey(id))));
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldKeepQuotaConsistentWhenPublishAndDraftUpdateRace() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < CONCURRENCY_ROUNDS; round++) {
                Long id = createBatch(validBatchAtDay(equipmentA.getId(), 100 + round * 2), adminA);
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch startUpdate = new CountDownLatch(1);
                Future<Boolean> publishResult = executor.submit(
                        () -> runConcurrentAction(ready, start, () -> batchService.publish(id)));
                Future<Boolean> updateResult = executor.submit(() -> {
                    loginAs(adminA);
                    ready.countDown();
                    startUpdate.await();
                    try {
                        batchService.updateDraft(new ReservationBatch()
                                .setId(id)
                                .setTotalQuota(99));
                        return true;
                    } catch (BadRequestException e) {
                        return false;
                    } finally {
                        UserHolder.removeUser();
                    }
                });

                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                long staggerNanos = UPDATE_STAGGER_NANOS[round % UPDATE_STAGGER_NANOS.length];
                long deadline = System.nanoTime() + staggerNanos;
                while (System.nanoTime() < deadline) {
                    Thread.yield();
                }
                startUpdate.countDown();
                publishResult.get(10, TimeUnit.SECONDS);
                updateResult.get(10, TimeUnit.SECONDS);

                ReservationBatch batch = batchService.getById(id);
                String quota = stringRedisTemplate.opsForValue().get(quotaKey(id));
                boolean draftWon = batch.getStatus() == ReservationBatchStatus.DRAFT
                        && Integer.valueOf(99).equals(batch.getTotalQuota())
                        && Integer.valueOf(99).equals(batch.getRemainingQuota())
                        && quota == null;
                boolean publishedConsistently = batch.getStatus() == ReservationBatchStatus.PUBLISHED
                        && batch.getTotalQuota() != null
                        && batch.getTotalQuota().equals(batch.getRemainingQuota())
                        && batch.getTotalQuota().toString().equals(quota);
                assertTrue(draftWon || publishedConsistently,
                        () -> "发布与草稿更新并发后状态不一致："
                                + batch.getStatus() + "|"
                                + batch.getTotalQuota() + "|"
                                + batch.getRemainingQuota() + "|"
                                + quota);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldOnlyReturnPublishedAndClosedBatchesOnPublicPage() {
        Long draftId = createBatch(validBatchAtDay(equipmentA.getId(), 3), adminA);
        Long cancelledId = createBatch(validBatchAtDay(equipmentA.getId(), 6), adminA);
        batchService.cancel(cancelledId);
        Long publishedId = createBatch(validBatchAtDay(equipmentA.getId(), 9), adminA);
        batchService.publish(publishedId);
        Long closedId = createBatch(validBatchAtDay(equipmentA.getId(), 12), adminA);
        batchService.publish(closedId);
        batchService.close(closedId);

        IPage<ReservationBatchVO> page = batchService.queryBatchPage(new ReservationBatchQueryDTO());

        assertTrue(page.getRecords().stream().anyMatch(batch -> publishedId.equals(batch.getId())));
        assertTrue(page.getRecords().stream().anyMatch(batch -> closedId.equals(batch.getId())));
        assertFalse(page.getRecords().stream().anyMatch(batch -> draftId.equals(batch.getId())));
        assertFalse(page.getRecords().stream().anyMatch(batch -> cancelledId.equals(batch.getId())));
    }

    @Test
    void shouldRejectNonPublicStatusOnPublicPage() {
        ReservationBatchQueryDTO query = new ReservationBatchQueryDTO();
        query.setStatus(ReservationBatchStatus.DRAFT);

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> batchService.queryBatchPage(query));

        assertEquals("批次状态不合法", exception.getMessage());
    }

    @Test
    void shouldIncludeDraftAndCancelledBatchesOnManagePage() {
        Long draftId = createBatch(validBatchAtDay(equipmentA.getId(), 3), adminA);
        Long cancelledId = createBatch(validBatchAtDay(equipmentA.getId(), 6), adminA);
        batchService.cancel(cancelledId);
        loginAs(adminA);

        IPage<ReservationBatchVO> page = batchService.queryManagePage(new ReservationBatchQueryDTO());

        assertTrue(page.getRecords().stream().anyMatch(batch -> draftId.equals(batch.getId())));
        assertTrue(page.getRecords().stream().anyMatch(batch -> cancelledId.equals(batch.getId())));
    }

    @Test
    void shouldOnlyReturnOwnedBatchesOnManagePage() {
        Long batchAId = createBatch(validBatch(equipmentA.getId()), adminA);
        Long batchBId = createBatch(validBatch(equipmentB.getId()), adminB);
        loginAs(adminA);

        IPage<ReservationBatchVO> page = batchService.queryManagePage(new ReservationBatchQueryDTO());

        assertTrue(page.getRecords().stream().anyMatch(batch -> batchAId.equals(batch.getId())));
        assertFalse(page.getRecords().stream().anyMatch(batch -> batchBId.equals(batch.getId())));
        ReservationBatchVO detail = batchService.queryBatchById(batchAId);
        assertNotNull(detail);
        assertEquals(equipmentA.getName(), detail.getEquipmentName());
        assertEquals(laboratoryA.getId(), detail.getLaboratoryId());
        assertEquals(BookingState.DRAFT, detail.getBookingState());
    }

    private void assertPublishRejected(Long id, String expectedMessage) {
        loginAs(adminA);
        BadRequestException exception = assertThrows(BadRequestException.class, () -> batchService.publish(id));
        assertEquals(expectedMessage, exception.getMessage());
        assertEquals(ReservationBatchStatus.DRAFT, batchService.getById(id).getStatus());
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(quotaKey(id))));
    }

    private Long createBatch(ReservationBatch batch, User admin) {
        loginAs(admin);
        Long id = batchService.createBatch(batch);
        batchIds.add(id);
        return id;
    }

    private boolean runConcurrentAction(
            CountDownLatch ready,
            CountDownLatch start,
            Runnable action) throws InterruptedException {
        loginAs(adminA);
        ready.countDown();
        start.await();
        try {
            action.run();
            return true;
        } catch (BadRequestException e) {
            return false;
        } finally {
            UserHolder.removeUser();
        }
    }

    private ReservationBatch validBatch(Long equipmentId) {
        return validBatchAtDay(equipmentId, 3);
    }

    private ReservationBatch validBatchAtDay(Long equipmentId, int dayOffset) {
        LocalDateTime now = LocalDateTime.now();
        return new ReservationBatch()
                .setEquipmentId(equipmentId)
                .setStartTime(now.plusDays(dayOffset))
                .setEndTime(now.plusDays(dayOffset).plusHours(2))
                .setBookingStartTime(now.minusHours(1))
                .setBookingEndTime(now.plusDays(1))
                .setTotalQuota(4);
    }

    private String quotaKey(Long id) {
        return RESERVATION_BATCH_QUOTA_KEY + id;
    }

    private User createAdmin(String phone, String nickName) {
        User user = new User()
                .setPhone(phone)
                .setNickName(nickName)
                .setIcon("")
                .setRole(UserRole.LAB_ADMIN);
        assertTrue(userService.save(user));
        return user;
    }

    private Laboratory createLaboratory(String name, Long adminUserId) {
        Laboratory laboratory = new Laboratory()
                .setName(name)
                .setCampus("测试校区")
                .setBuilding("测试楼")
                .setRoom("B101")
                .setDescription("批次生命周期自动化测试")
                .setStatus(LaboratoryStatus.OPEN)
                .setAdminUserId(adminUserId);
        assertTrue(laboratoryService.save(laboratory));
        return laboratory;
    }

    private Equipment createEquipment(String name, Long laboratoryId, Long categoryId) {
        Equipment equipment = new Equipment()
                .setLaboratoryId(laboratoryId)
                .setCategoryId(categoryId)
                .setName(name)
                .setDescription("批次生命周期自动化测试")
                .setLocation("B101-TEST")
                .setUsageRequirement("仅供自动化测试")
                .setStatus(EquipmentStatus.AVAILABLE)
                .setBookingEnabled(true);
        assertTrue(equipmentService.save(equipment));
        return equipment;
    }

    private void loginAs(User user) {
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setRole(UserRole.LAB_ADMIN);
        UserHolder.saveUser(userDTO);
    }
}
