package com.hmdp;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hmdp.dto.ReservationBatchQueryDTO;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Equipment;
import com.hmdp.entity.Laboratory;
import com.hmdp.entity.ReservationBatch;
import com.hmdp.entity.User;
import com.hmdp.enums.EquipmentStatus;
import com.hmdp.enums.LaboratoryStatus;
import com.hmdp.enums.ReservationBatchStatus;
import com.hmdp.enums.UserRole;
import com.hmdp.exception.BadRequestException;
import com.hmdp.exception.ForbiddenException;
import com.hmdp.service.IEquipmentService;
import com.hmdp.service.ILaboratoryService;
import com.hmdp.service.IReservationBatchService;
import com.hmdp.service.IReservationService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import com.hmdp.vo.ReservationBatchVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_EQUIPMENT_KEY;
import static com.hmdp.utils.RedisConstants.RESERVATION_BATCH_QUOTA_KEY;
import static com.hmdp.utils.RedisConstants.RESERVATION_BATCH_USERS_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ReservationSubmitTest {

    @Resource
    private IReservationService reservationService;

    @Resource
    private IReservationBatchService reservationBatchService;

    @Resource
    private IEquipmentService equipmentService;

    @Resource
    private ILaboratoryService laboratoryService;

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final List<Long> batchIds = new ArrayList<>();
    private final List<User> users = new ArrayList<>();
    private String phoneSuffix;
    private User admin;
    private User user1;
    private User user2;
    private Laboratory laboratory;
    private Equipment equipment;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
        phoneSuffix = String.format("%07d", Math.abs(System.nanoTime() % 10_000_000L));
        admin = createUser(98, "预约测试管理员", UserRole.LAB_ADMIN);
        user1 = createUser(1, "预约测试学生1", UserRole.USER);
        user2 = createUser(2, "预约测试学生2", UserRole.USER);
        laboratory = createLaboratory("预约测试实验室-" + phoneSuffix, admin.getId());
        equipment = createEquipment("预约测试设备-" + phoneSuffix, laboratory.getId());
    }

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
        for (Long batchId : batchIds) {
            stringRedisTemplate.delete(Arrays.asList(quotaKey(batchId), usersKey(batchId)));
            reservationBatchService.removeById(batchId);
        }
        if (equipment != null && equipment.getId() != null) {
            stringRedisTemplate.delete(CACHE_EQUIPMENT_KEY + equipment.getId());
            equipmentService.removeById(equipment.getId());
        }
        if (laboratory != null && laboratory.getId() != null) {
            laboratoryService.removeById(laboratory.getId());
        }
        for (User user : users) {
            if (user.getId() != null) {
                userService.removeById(user.getId());
            }
        }
    }

    @Test
    void shouldRejectSecondUserWhenQuotaIsSoldOut() {
        Long batchId = createAndPublishBatch(validBatch(equipment.getId()).setTotalQuota(1));
        submitAs(batchId, user1);

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> submitAs(batchId, user2));

        assertEquals("名额已约满", exception.getMessage());
        assertEquals("0", stringRedisTemplate.opsForValue().get(quotaKey(batchId)));
    }

    @Test
    void shouldRejectDuplicateReservationAndOnlyDeductOnce() {
        Long batchId = createAndPublishBatch(validBatch(equipment.getId()));
        submitAs(batchId, user1);

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> submitAs(batchId, user1));

        assertEquals("你已预约过该批次", exception.getMessage());
        assertEquals("3", stringRedisTemplate.opsForValue().get(quotaKey(batchId)));
    }

    @Test
    void shouldRejectReservationAndDeleteBothKeysAfterClose() {
        Long batchId = createAndPublishBatch(validBatch(equipment.getId()));
        submitAs(batchId, user1);
        loginAs(admin);
        reservationBatchService.close(batchId);

        assertThrows(BadRequestException.class, () -> submitAs(batchId, user2));
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(quotaKey(batchId))));
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(usersKey(batchId))));
    }

    @Test
    void shouldRejectReservationAndDeleteBothKeysAfterCancel() {
        Long batchId = createAndPublishBatch(validBatch(equipment.getId()));
        submitAs(batchId, user1);
        loginAs(admin);
        reservationBatchService.cancel(batchId);

        assertThrows(BadRequestException.class, () -> submitAs(batchId, user2));
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(quotaKey(batchId))));
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(usersKey(batchId))));
    }

    @Test
    void shouldRejectWhenQuotaKeyIsMissingWhileStatusIsPublished() {
        Long batchId = createAndPublishBatch(validBatch(equipment.getId()));
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.hasKey(quotaKey(batchId))));
        assertEquals("4", stringRedisTemplate.opsForValue().get(quotaKey(batchId)));

        // 构造“MySQL 说开放、Redis 说已关闭”的瞬时不一致，覆盖 §4.2 的竞态窗口。
        stringRedisTemplate.delete(quotaKey(batchId));
        assertEquals(ReservationBatchStatus.PUBLISHED,
                reservationBatchService.getById(batchId).getStatus());

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> submitAs(batchId, user1));

        assertEquals("批次未开放预约", exception.getMessage());
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(usersKey(batchId))));
    }

    @Test
    void shouldReadPageRemainingQuotaFromRedisAndFallBackForNonPublished() {
        Long batchAId = createAndPublishBatch(validBatch(equipment.getId()));
        Long batchBId = createAndPublishBatch(validBatchAtDay(equipment.getId(), 5));
        submitAs(batchAId, user1);
        submitAs(batchBId, user1);
        loginAs(admin);
        reservationBatchService.close(batchBId);

        loginAs(user1);
        // 收窄到本用例自己的设备，使断言不依赖分页大小与库中既有数据。
        ReservationBatchQueryDTO query = new ReservationBatchQueryDTO();
        query.setEquipmentId(equipment.getId());
        IPage<ReservationBatchVO> page = reservationBatchService.queryBatchPage(query);
        ReservationBatchVO batchA = page.getRecords().stream()
                .filter(batch -> batchAId.equals(batch.getId()))
                .findFirst()
                .orElse(null);
        ReservationBatchVO batchB = page.getRecords().stream()
                .filter(batch -> batchBId.equals(batch.getId()))
                .findFirst()
                .orElse(null);

        assertNotNull(batchA);
        assertNotNull(batchB);
        assertEquals(3, batchA.getRemainingQuota());
        assertEquals(4, reservationBatchService.getById(batchAId).getRemainingQuota());
        assertEquals(4, batchB.getRemainingQuota());
    }

    @Test
    void shouldRejectDraftEvenWhenArtificialQuotaKeyExists() {
        Long batchId = createBatch(validBatch(equipment.getId()));
        stringRedisTemplate.opsForValue().set(quotaKey(batchId), "4");

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> submitAs(batchId, user1));

        assertEquals("批次当前不可预约", exception.getMessage());
        assertEquals("4", stringRedisTemplate.opsForValue().get(quotaKey(batchId)));
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(usersKey(batchId))));
    }

    @Test
    void shouldReadPublishedRemainingQuotaFromRedis() {
        Long batchId = createAndPublishBatch(validBatch(equipment.getId()));
        submitAs(batchId, user1);

        ReservationBatchVO detail = reservationBatchService.queryBatchById(batchId);

        assertNotNull(detail);
        assertEquals(3, detail.getRemainingQuota());
        assertEquals(4, reservationBatchService.getById(batchId).getRemainingQuota());
    }

    @Test
    void shouldFallBackToMysqlRemainingQuotaAfterClose() {
        Long batchId = createAndPublishBatch(validBatch(equipment.getId()));
        submitAs(batchId, user1);
        loginAs(admin);
        reservationBatchService.close(batchId);
        loginAs(user2);

        ReservationBatchVO detail = reservationBatchService.queryBatchById(batchId);

        assertNotNull(detail);
        assertEquals(4, detail.getRemainingQuota());
    }

    @Test
    void shouldRejectWhenUserIsNotLoggedIn() {
        Long batchId = createAndPublishBatch(validBatch(equipment.getId()));
        UserHolder.removeUser();

        ForbiddenException exception = assertThrows(ForbiddenException.class,
                () -> reservationService.submit(batchId));

        assertEquals("请先登录", exception.getMessage());
    }

    @Test
    void shouldRejectMissingBatch() {
        loginAs(user1);

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> reservationService.submit(Long.MAX_VALUE));

        assertEquals("预约批次不存在", exception.getMessage());
    }

    @Test
    void shouldReadEquipmentFromDatabaseInsteadOfStaleCache() {
        Long batchId = createAndPublishBatch(validBatch(equipment.getId()));
        assertNotNull(equipmentService.queryEquipmentById(equipment.getId()));
        equipmentService.updateById(new Equipment()
                .setId(equipment.getId())
                .setBookingEnabled(false));

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> submitAs(batchId, user1));

        assertEquals("设备当前不可预约", exception.getMessage());
        assertEquals("4", stringRedisTemplate.opsForValue().get(quotaKey(batchId)));
    }

    @Test
    void shouldRejectWhenBookingHasNotStarted() {
        LocalDateTime now = LocalDateTime.now();
        ReservationBatch batch = validBatch(equipment.getId())
                .setBookingStartTime(now.plusHours(1))
                .setBookingEndTime(now.plusDays(1));
        Long batchId = createAndPublishBatch(batch);

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> submitAs(batchId, user1));

        assertEquals("预约尚未开始", exception.getMessage());
        assertEquals("4", stringRedisTemplate.opsForValue().get(quotaKey(batchId)));
    }

    @Test
    void shouldRejectWhenBookingHasExpired() {
        Long batchId = createAndPublishBatch(validBatch(equipment.getId()));
        reservationBatchService.updateById(new ReservationBatch()
                .setId(batchId)
                .setBookingEndTime(LocalDateTime.now().minusSeconds(1)));

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> submitAs(batchId, user1));

        assertEquals("预约已截止", exception.getMessage());
        assertEquals("4", stringRedisTemplate.opsForValue().get(quotaKey(batchId)));
    }

    @Test
    void shouldApplyBatchDetailVisibilityByRoleAndStatus() {
        Long draftId = createBatch(validBatchAtDay(equipment.getId(), 3));
        Long cancelledId = createBatch(validBatchAtDay(equipment.getId(), 6));
        loginAs(admin);
        reservationBatchService.cancel(cancelledId);
        Long publishedId = createAndPublishBatch(validBatchAtDay(equipment.getId(), 9));
        Long closedId = createAndPublishBatch(validBatchAtDay(equipment.getId(), 12));
        loginAs(admin);
        reservationBatchService.close(closedId);

        loginAs(user1);
        assertNull(reservationBatchService.queryBatchById(draftId));
        assertNull(reservationBatchService.queryBatchById(cancelledId));
        assertNotNull(reservationBatchService.queryBatchById(publishedId));
        assertNotNull(reservationBatchService.queryBatchById(closedId));
        loginAs(admin);
        assertNotNull(reservationBatchService.queryBatchById(draftId));
    }

    @Test
    void shouldPreserveCapacityInvariantForDifferentConcurrentUsers() throws Exception {
        Long batchId = createAndPublishBatch(validBatch(equipment.getId()).setTotalQuota(5));
        List<User> competitors = new ArrayList<>();
        for (int i = 10; i < 30; i++) {
            competitors.add(createUser(i, "并发预约学生" + i, UserRole.USER));
        }
        int attempts = competitors.size();
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger minimumObservedQuota = new AtomicInteger(Integer.MAX_VALUE);
        List<Future<Long>> futures = new ArrayList<>();

        try {
            for (User user : competitors) {
                futures.add(executor.submit(() -> {
                    loginAs(user);
                    ready.countDown();
                    start.await();
                    try {
                        reservationService.submit(batchId);
                        return user.getId();
                    } catch (BadRequestException e) {
                        return null;
                    } finally {
                        String value = stringRedisTemplate.opsForValue().get(quotaKey(batchId));
                        if (value != null) {
                            minimumObservedQuota.accumulateAndGet(Integer.parseInt(value), Math::min);
                        }
                        UserHolder.removeUser();
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            Set<Long> successfulUserIds = new HashSet<>();
            for (Future<Long> future : futures) {
                Long successfulUserId = future.get(15, TimeUnit.SECONDS);
                if (successfulUserId != null) {
                    successfulUserIds.add(successfulUserId);
                }
            }
            Set<Long> redisUserIds = stringRedisTemplate.opsForSet().members(usersKey(batchId)).stream()
                    .map(Long::valueOf)
                    .collect(Collectors.toSet());

            assertEquals(5, successfulUserIds.size());
            assertEquals("0", stringRedisTemplate.opsForValue().get(quotaKey(batchId)));
            assertEquals(5L, stringRedisTemplate.opsForSet().size(usersKey(batchId)));
            assertEquals(successfulUserIds, redisUserIds);
            assertTrue(minimumObservedQuota.get() >= 0);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldPreserveDuplicateInvariantForSameConcurrentUser() throws Exception {
        Long batchId = createAndPublishBatch(validBatch(equipment.getId()).setTotalQuota(5));
        int attempts = 20;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < attempts; i++) {
                futures.add(executor.submit(() -> {
                    loginAs(user1);
                    ready.countDown();
                    start.await();
                    try {
                        reservationService.submit(batchId);
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
            for (Future<Boolean> future : futures) {
                if (future.get(15, TimeUnit.SECONDS)) {
                    successCount++;
                }
            }

            assertEquals(1, successCount);
            assertEquals("4", stringRedisTemplate.opsForValue().get(quotaKey(batchId)));
            assertEquals(1L, stringRedisTemplate.opsForSet().size(usersKey(batchId)));
            assertTrue(Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                    .isMember(usersKey(batchId), user1.getId().toString())));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Long createAndPublishBatch(ReservationBatch batch) {
        Long batchId = createBatch(batch);
        loginAs(admin);
        reservationBatchService.publish(batchId);
        return batchId;
    }

    private Long createBatch(ReservationBatch batch) {
        loginAs(admin);
        Long batchId = reservationBatchService.createBatch(batch);
        batchIds.add(batchId);
        return batchId;
    }

    private Long submitAs(Long batchId, User user) {
        loginAs(user);
        return reservationService.submit(batchId);
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

    private User createUser(int sequence, String nickName, UserRole role) {
        User user = new User()
                .setPhone(String.format("17%s%02d", phoneSuffix, sequence))
                .setNickName(nickName)
                .setIcon("")
                .setRole(role);
        assertTrue(userService.save(user));
        users.add(user);
        return user;
    }

    private Laboratory createLaboratory(String name, Long adminUserId) {
        Laboratory entity = new Laboratory()
                .setName(name)
                .setCampus("测试校区")
                .setBuilding("测试楼")
                .setRoom("C101")
                .setDescription("预约提交自动化测试")
                .setStatus(LaboratoryStatus.OPEN)
                .setAdminUserId(adminUserId);
        assertTrue(laboratoryService.save(entity));
        return entity;
    }

    private Equipment createEquipment(String name, Long laboratoryId) {
        Equipment entity = new Equipment()
                .setLaboratoryId(laboratoryId)
                .setCategoryId(9_000_000_000L + Math.abs(System.nanoTime() % 1_000_000L))
                .setName(name)
                .setDescription("预约提交自动化测试")
                .setLocation("C101-TEST")
                .setUsageRequirement("仅供自动化测试")
                .setStatus(EquipmentStatus.AVAILABLE)
                .setBookingEnabled(true);
        assertTrue(equipmentService.save(entity));
        return entity;
    }

    private void loginAs(User user) {
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setRole(user.getRole());
        UserHolder.saveUser(userDTO);
    }

    private String quotaKey(Long batchId) {
        return RESERVATION_BATCH_QUOTA_KEY + batchId;
    }

    private String usersKey(Long batchId) {
        return RESERVATION_BATCH_USERS_KEY + batchId;
    }
}
