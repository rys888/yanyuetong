package com.yanyuetong;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yanyuetong.dto.UserDTO;
import com.yanyuetong.entity.Equipment;
import com.yanyuetong.entity.Laboratory;
import com.yanyuetong.entity.ReservationBatch;
import com.yanyuetong.entity.ReservationRequest;
import com.yanyuetong.entity.User;
import com.yanyuetong.enums.EquipmentStatus;
import com.yanyuetong.enums.LaboratoryStatus;
import com.yanyuetong.enums.ReservationBatchStatus;
import com.yanyuetong.enums.ReservationRequestStatus;
import com.yanyuetong.enums.UserRole;
import com.yanyuetong.exception.ServiceUnavailableException;
import com.yanyuetong.mapper.ReservationRequestMapper;
import com.yanyuetong.mq.ReservationMessageDTO;
import com.yanyuetong.service.IEquipmentService;
import com.yanyuetong.service.ILaboratoryService;
import com.yanyuetong.service.IReservationBatchService;
import com.yanyuetong.service.IReservationRequestService;
import com.yanyuetong.service.IReservationService;
import com.yanyuetong.service.IUserService;
import com.yanyuetong.utils.RedisIdWorker;
import com.yanyuetong.utils.ReservationQuotaRollbackExecutor;
import com.yanyuetong.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.net.ConnectException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.yanyuetong.enums.ReservationRequestStatus.COMPENSATED;
import static com.yanyuetong.enums.ReservationRequestStatus.PRE_ACCEPTED;
import static com.yanyuetong.enums.ReservationRequestStatus.UNKNOWN;
import static com.yanyuetong.utils.RedisConstants.CACHE_EQUIPMENT_KEY;
import static com.yanyuetong.utils.RedisConstants.RESERVATION_BATCH_QUOTA_KEY;
import static com.yanyuetong.utils.RedisConstants.RESERVATION_BATCH_USERS_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
class ReservationPublishFailureTest {

    @Resource
    private IReservationService reservationService;

    @Resource
    private IReservationBatchService reservationBatchService;

    @Resource
    private IReservationRequestService reservationRequestService;

    @Resource
    private IEquipmentService equipmentService;

    @Resource
    private ILaboratoryService laboratoryService;

    @Resource
    private IUserService userService;

    @Resource
    private ReservationRequestMapper reservationRequestMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private RabbitTemplate rabbitTemplate;

    @SpyBean
    private ReservationQuotaRollbackExecutor reservationQuotaRollbackExecutor;

    @SpyBean
    private RedisIdWorker redisIdWorker;

    private final List<Long> batchIds = new ArrayList<>();
    private final List<User> users = new ArrayList<>();
    private String phoneSuffix;
    private User admin;
    private User user;
    private Laboratory laboratory;
    private Equipment equipment;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
        phoneSuffix = String.format("%07d", Math.abs(System.nanoTime() % 10_000_000L));
        admin = createUser(98, "预约发布失败测试管理员", UserRole.LAB_ADMIN);
        user = createUser(1, "预约发布失败测试学生", UserRole.USER);
        laboratory = createLaboratory("预约发布失败测试实验室-" + phoneSuffix, admin.getId());
        equipment = createEquipment("预约发布失败测试设备-" + phoneSuffix, laboratory.getId());
    }

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
        if (!batchIds.isEmpty()) {
            reservationRequestMapper.delete(new LambdaQueryWrapper<ReservationRequest>()
                    .in(ReservationRequest::getBatchId, batchIds));
        }
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
        for (User fixtureUser : users) {
            if (fixtureUser.getId() != null) {
                userService.removeById(fixtureUser.getId());
            }
        }
    }

    @Test
    void shouldMarkPublishedAndKeepQuotaWhenConfirmIsAck() {
        Long batchId = createAndPublishBatch();
        completeConfirm(true);

        Long reservationId = submitAsUser(batchId);

        ReservationRequest request = getOnlyRequest(batchId);
        assertEquals(reservationId, request.getRequestId());
        assertEquals(ReservationRequestStatus.PUBLISHED, request.getStatus());
        assertNull(request.getFailReason());
        assertEquals("0", stringRedisTemplate.opsForValue().get(quotaKey(batchId)));
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                .isMember(usersKey(batchId), user.getId().toString())));
    }

    @Test
    void shouldCompensateWhenConfirmIsNack() {
        Long batchId = createAndPublishBatch();
        completeConfirm(false);

        ServiceUnavailableException exception = assertThrows(ServiceUnavailableException.class,
                () -> submitAsUser(batchId));

        assertEquals("预约失败，请重试", exception.getMessage());
        assertCompensated(batchId, "CONFIRM_NACK");
    }

    @Test
    void shouldCompensateWhenMessageIsReturned() {
        Long batchId = createAndPublishBatch();
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.setReturnedMessage(new Message(new byte[0], new MessageProperties()));
            correlationData.getFuture().set(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                anyString(), anyString(), any(ReservationMessageDTO.class), any(CorrelationData.class));

        ServiceUnavailableException exception = assertThrows(ServiceUnavailableException.class,
                () -> submitAsUser(batchId));

        assertEquals("预约失败，请重试", exception.getMessage());
        assertCompensated(batchId, "CONFIRM_RETURN");
    }

    @Test
    void shouldCompensateWhenAmqpConnectionFails() {
        Long batchId = createAndPublishBatch();
        doThrow(new AmqpConnectException(new ConnectException("连接失败")))
                .when(rabbitTemplate).convertAndSend(
                        anyString(), anyString(), any(ReservationMessageDTO.class), any(CorrelationData.class));

        ServiceUnavailableException exception = assertThrows(ServiceUnavailableException.class,
                () -> submitAsUser(batchId));

        assertEquals("预约失败，请重试", exception.getMessage());
        assertCompensated(batchId, "AMQP_CONNECT_FAILED");
    }

    @Test
    void shouldKeepQuotaAndMarkUnknownWhenConfirmTimesOut() {
        Long batchId = createAndPublishBatch();
        doAnswer(invocation -> null).when(rabbitTemplate).convertAndSend(
                anyString(), anyString(), any(ReservationMessageDTO.class), any(CorrelationData.class));

        ServiceUnavailableException exception = assertThrows(ServiceUnavailableException.class,
                () -> submitAsUser(batchId));

        assertEquals("预约结果待确认，请稍后查询", exception.getMessage());
        assertUnknownAndQuotaOccupied(batchId);
    }

    @Test
    void shouldKeepQuotaAndMarkUnknownForOtherPublishException() {
        Long batchId = createAndPublishBatch();
        doThrow(new AmqpException("未知发布异常"))
                .when(rabbitTemplate).convertAndSend(
                        anyString(), anyString(), any(ReservationMessageDTO.class), any(CorrelationData.class));

        ServiceUnavailableException exception = assertThrows(ServiceUnavailableException.class,
                () -> submitAsUser(batchId));

        assertEquals("预约结果待确认，请稍后查询", exception.getMessage());
        assertUnknownAndQuotaOccupied(batchId);
    }

    @Test
    void shouldKeepRequestNonTerminalWhenRollbackFails() {
        Long batchId = createAndPublishBatch();
        completeConfirm(false);
        doThrow(new IllegalStateException("回滚故障"))
                .when(reservationQuotaRollbackExecutor).rollback(batchId, user.getId());

        ServiceUnavailableException exception = assertThrows(ServiceUnavailableException.class,
                () -> submitAsUser(batchId));

        assertEquals("预约失败，请重试", exception.getMessage());
        ReservationRequest request = getOnlyRequest(batchId);
        assertFalse(request.getStatus().isTerminal());
        assertEquals(PRE_ACCEPTED, request.getStatus());
        assertEquals("0", stringRedisTemplate.opsForValue().get(quotaKey(batchId)));
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                .isMember(usersKey(batchId), user.getId().toString())));
    }

    @Test
    void shouldHaveNoSideEffectsWhenInitialLedgerInsertFails() {
        Long batchId = createAndPublishBatch();
        long duplicateRequestId = 9_000_000_000L + Math.abs(System.nanoTime() % 100_000_000L);
        reservationRequestService.createPreAccepted(duplicateRequestId, user.getId(), batchId);
        doReturn(duplicateRequestId).when(redisIdWorker).nextId("reservation");

        ServiceUnavailableException exception = assertThrows(ServiceUnavailableException.class,
                () -> submitAsUser(batchId));

        assertEquals("预约失败，请重试", exception.getMessage());
        assertEquals("1", stringRedisTemplate.opsForValue().get(quotaKey(batchId)));
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                .isMember(usersKey(batchId), user.getId().toString())));
        verifyNoInteractions(rabbitTemplate);
        ReservationRequest request = getOnlyRequest(batchId);
        assertEquals(Long.valueOf(duplicateRequestId), request.getRequestId());
        assertEquals(PRE_ACCEPTED, request.getStatus());
        assertNull(request.getFailReason());
    }

    private void completeConfirm(boolean ack) {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().set(new CorrelationData.Confirm(ack, ack ? null : "xx"));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                anyString(), anyString(), any(ReservationMessageDTO.class), any(CorrelationData.class));
    }

    private void assertCompensated(Long batchId, String failReason) {
        ReservationRequest request = getOnlyRequest(batchId);
        assertEquals(COMPENSATED, request.getStatus());
        assertEquals(failReason, request.getFailReason());
        assertEquals("1", stringRedisTemplate.opsForValue().get(quotaKey(batchId)));
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                .isMember(usersKey(batchId), user.getId().toString())));
    }

    private void assertUnknownAndQuotaOccupied(Long batchId) {
        ReservationRequest request = getOnlyRequest(batchId);
        assertEquals(UNKNOWN, request.getStatus());
        assertEquals("0", stringRedisTemplate.opsForValue().get(quotaKey(batchId)));
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                .isMember(usersKey(batchId), user.getId().toString())));
    }

    private ReservationRequest getOnlyRequest(Long batchId) {
        List<ReservationRequest> requests = reservationRequestMapper.selectList(
                new LambdaQueryWrapper<ReservationRequest>()
                        .eq(ReservationRequest::getBatchId, batchId)
                        .eq(ReservationRequest::getUserId, user.getId()));
        assertEquals(1, requests.size());
        return requests.get(0);
    }

    private Long createAndPublishBatch() {
        loginAs(admin);
        Long batchId = reservationBatchService.createBatch(validBatch(equipment.getId()));
        batchIds.add(batchId);
        reservationBatchService.publish(batchId);
        return batchId;
    }

    private Long submitAsUser(Long batchId) {
        loginAs(user);
        return reservationService.submit(batchId);
    }

    private ReservationBatch validBatch(Long equipmentId) {
        LocalDateTime now = LocalDateTime.now();
        return new ReservationBatch()
                .setEquipmentId(equipmentId)
                .setStartTime(now.plusDays(3))
                .setEndTime(now.plusDays(3).plusHours(2))
                .setBookingStartTime(now.minusHours(1))
                .setBookingEndTime(now.plusDays(1))
                .setTotalQuota(1)
                .setStatus(ReservationBatchStatus.DRAFT);
    }

    private User createUser(int sequence, String nickName, UserRole role) {
        User fixtureUser = new User()
                .setPhone(String.format("17%s%02d", phoneSuffix, sequence))
                .setNickName(nickName)
                .setIcon("")
                .setRole(role);
        assertTrue(userService.save(fixtureUser));
        users.add(fixtureUser);
        return fixtureUser;
    }

    private Laboratory createLaboratory(String name, Long adminUserId) {
        Laboratory entity = new Laboratory()
                .setName(name)
                .setCampus("测试校区")
                .setBuilding("测试楼")
                .setRoom("C101")
                .setDescription("预约发布失败自动化测试")
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
                .setDescription("预约发布失败自动化测试")
                .setLocation("C101-TEST")
                .setUsageRequirement("仅供自动化测试")
                .setStatus(EquipmentStatus.AVAILABLE)
                .setBookingEnabled(true);
        assertTrue(equipmentService.save(entity));
        return entity;
    }

    private void loginAs(User fixtureUser) {
        UserDTO userDTO = new UserDTO();
        userDTO.setId(fixtureUser.getId());
        userDTO.setRole(fixtureUser.getRole());
        UserHolder.saveUser(userDTO);
    }

    private String quotaKey(Long batchId) {
        return RESERVATION_BATCH_QUOTA_KEY + batchId;
    }

    private String usersKey(Long batchId) {
        return RESERVATION_BATCH_USERS_KEY + batchId;
    }
}
