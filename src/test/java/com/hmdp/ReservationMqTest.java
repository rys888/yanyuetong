package com.hmdp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Equipment;
import com.hmdp.entity.Laboratory;
import com.hmdp.entity.Reservation;
import com.hmdp.entity.ReservationBatch;
import com.hmdp.entity.ReservationDlq;
import com.hmdp.entity.ReservationRequest;
import com.hmdp.entity.User;
import com.hmdp.enums.EquipmentStatus;
import com.hmdp.enums.LaboratoryStatus;
import com.hmdp.enums.ReservationBatchStatus;
import com.hmdp.enums.ReservationDlqStatus;
import com.hmdp.enums.ReservationRequestStatus;
import com.hmdp.enums.UserRole;
import com.hmdp.exception.ServiceUnavailableException;
import com.hmdp.mapper.ReservationDlqMapper;
import com.hmdp.mapper.ReservationMapper;
import com.hmdp.mapper.ReservationRequestMapper;
import com.hmdp.mq.ReservationMessageDTO;
import com.hmdp.service.IEquipmentService;
import com.hmdp.service.ILaboratoryService;
import com.hmdp.service.IReservationBatchService;
import com.hmdp.service.IReservationDlqService;
import com.hmdp.service.IReservationRequestService;
import com.hmdp.service.IReservationService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.ReservationQuotaRollbackExecutor;
import com.hmdp.utils.UserHolder;
import com.hmdp.vo.ReservationBatchVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.mockito.Mockito;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.config.RabbitMQConfig.RESERVATION_CREATE_DLQ;
import static com.hmdp.config.RabbitMQConfig.RESERVATION_CREATE_QUEUE;
import static com.hmdp.config.RabbitMQConfig.RESERVATION_CREATE_ROUTING_KEY;
import static com.hmdp.config.RabbitMQConfig.RESERVATION_EXCHANGE;
import static com.hmdp.utils.RedisConstants.CACHE_EQUIPMENT_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_RESERVATION_KEY;
import static com.hmdp.utils.RedisConstants.RESERVATION_BATCH_QUOTA_KEY;
import static com.hmdp.utils.RedisConstants.RESERVATION_BATCH_USERS_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;

/**
 * 需要先启动真实 MySQL、Redis、RabbitMQ，并应用 phase7_reservation.sql 和 phase8_consistency.sql。
 * 本类会写入 tb_reservation_dlq，测试结束后仅清理本次新增的死信台账行。
 * 运行前必须关闭其它消费 reservation.create.queue 的应用实例，避免消息被其它消费者取走。
 */
@SpringBootTest
class ReservationMqTest {

    private static final int INITIAL_QUOTA = 4;

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
    private ReservationMapper reservationMapper;

    @Resource
    private ReservationRequestMapper reservationRequestMapper;

    @Resource
    private ReservationDlqMapper reservationDlqMapper;

    @SpyBean
    private IReservationDlqService reservationDlqService;

    @Resource
    private ReservationQuotaRollbackExecutor rollbackExecutor;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private AmqpAdmin rabbitAdmin;

    @Resource
    private RabbitListenerEndpointRegistry listenerEndpointRegistry;

    private final List<Long> batchIds = new ArrayList<>();
    private final List<User> users = new ArrayList<>();
    private String phoneSuffix;
    private User admin;
    private User user1;
    private User user2;
    private Laboratory laboratory;
    private Equipment equipment;
    private long reservationIdSeed;
    private long dlqBaselineId;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
        rabbitAdmin.declareBinding(reservationCreateBinding());
        resetQueuesAndStartListeners();
        long nonce = Math.abs(System.nanoTime());
        phoneSuffix = String.format("%07d", nonce % 10_000_000L);
        reservationIdSeed = 7_000_000_000L + nonce % 1_000_000_000L;
        admin = createUser(90, "预约消息测试管理员", UserRole.LAB_ADMIN);
        user1 = createUser(1, "预约消息测试学生1", UserRole.USER);
        user2 = createUser(2, "预约消息测试学生2", UserRole.USER);
        laboratory = createLaboratory("预约消息测试实验室-" + phoneSuffix, admin.getId());
        equipment = createEquipment("预约消息测试设备-" + phoneSuffix, laboratory.getId());
    }

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
        MessageListenerContainer dlqContainer = listenerContainer(RESERVATION_CREATE_DLQ);
        if (dlqContainer.isRunning()) {
            dlqContainer.stop();
        }
        MessageListenerContainer createContainer = listenerContainer(RESERVATION_CREATE_QUEUE);
        if (createContainer.isRunning()) {
            createContainer.stop();
        }
        rabbitAdmin.purgeQueue(RESERVATION_CREATE_QUEUE, false);
        rabbitAdmin.purgeQueue(RESERVATION_CREATE_DLQ, false);
        reservationDlqMapper.delete(new LambdaQueryWrapper<ReservationDlq>()
                .gt(ReservationDlq::getId, dlqBaselineId));
        if (!batchIds.isEmpty()) {
            reservationMapper.delete(new LambdaQueryWrapper<Reservation>()
                    .in(Reservation::getBatchId, batchIds));
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
        for (User user : users) {
            if (user.getId() != null) {
                stringRedisTemplate.delete(LOCK_RESERVATION_KEY + user.getId());
                userService.removeById(user.getId());
            }
        }
    }

    @Test
    void shouldSubmitAndEventuallyPersistReturnedReservationId() throws Exception {
        Long batchId = createAndPublishBatch(validBatch());

        Long reservationId = submitAs(batchId, user1);

        Reservation stored = waitForReservation(reservationId);
        assertNotNull(stored);
        assertEquals(reservationId, stored.getId());
        assertEquals(user1.getId(), stored.getUserId());
        assertEquals(batchId, stored.getBatchId());
        assertEquals(INITIAL_QUOTA - 1,
                reservationBatchService.getById(batchId).getRemainingQuota());
        assertEquals(String.valueOf(INITIAL_QUOTA - 1),
                stringRedisTemplate.opsForValue().get(quotaKey(batchId)));
    }

    @Test
    void shouldAckCompensatedRequestWithoutPersistingOrDeadLettering() throws Exception {
        Long batchId = createAndPublishBatch(validBatch());
        ReservationMessageDTO compensated = message(reservationIdSeed, user1.getId(), batchId);
        reservationRequestService.createPreAccepted(
                compensated.getReservationId(), compensated.getUserId(), compensated.getBatchId());
        assertTrue(reservationRequestService.casStatus(
                compensated.getReservationId(),
                ReservationRequestStatus.COMPENSATED,
                ReservationRequestStatus.PRE_ACCEPTED));

        publishAndConfirm(compensated);
        ReservationMessageDTO barrier = message(reservationIdSeed + 1, user2.getId(), batchId);
        createPublishedRequest(barrier);
        publishAndConfirm(barrier);

        assertNotNull(waitForReservation(barrier.getReservationId()));
        assertNull(reservationMapper.selectById(compensated.getReservationId()));
        assertEquals(ReservationRequestStatus.COMPENSATED,
                reservationRequestMapper.selectById(compensated.getReservationId()).getStatus());
        assertNull(rabbitTemplate.receive(RESERVATION_CREATE_DLQ, 2_000));
        assertNull(rabbitTemplate.receive(RESERVATION_CREATE_QUEUE, 2_000));
    }

    @Test
    void shouldMarkRequestConfirmedAfterReservationIsPersisted() throws Exception {
        Long batchId = createAndPublishBatch(validBatch());

        Long reservationId = submitAs(batchId, user1);

        assertNotNull(waitForReservation(reservationId));
        ReservationRequest request = waitForRequestStatus(
                reservationId, ReservationRequestStatus.CONFIRMED);
        assertNotNull(request);
        assertEquals(ReservationRequestStatus.CONFIRMED, request.getStatus());
        assertNull(rabbitTemplate.receive(RESERVATION_CREATE_DLQ, 2_000));
    }

    @Test
    void shouldAckRepeatedMessageAndKeepRequestConfirmed() throws Exception {
        Long batchId = createAndPublishBatch(validBatch());
        ReservationMessageDTO duplicate = message(reservationIdSeed, user1.getId(), batchId);
        createPublishedRequest(duplicate);
        publishAndConfirm(duplicate);
        assertNotNull(waitForReservation(duplicate.getReservationId()));
        assertNotNull(waitForRequestStatus(
                duplicate.getReservationId(), ReservationRequestStatus.CONFIRMED));

        publishAndConfirm(duplicate);
        ReservationMessageDTO barrier = message(reservationIdSeed + 1, user2.getId(), batchId);
        createPublishedRequest(barrier);
        publishAndConfirm(barrier);

        assertNotNull(waitForReservation(barrier.getReservationId()));
        assertEquals(1, countReservations(user1.getId(), batchId));
        assertEquals(ReservationRequestStatus.CONFIRMED,
                reservationRequestMapper.selectById(duplicate.getReservationId()).getStatus());
        assertNull(rabbitTemplate.receive(RESERVATION_CREATE_DLQ, 2_000));
    }

    @Test
    void shouldDeadLetterPrimaryKeyConflictAndRollbackQuotaDeduction() throws Exception {
        Long batchId = createAndPublishBatch(validBatch());
        Reservation existing = new Reservation()
                .setId(reservationIdSeed)
                .setUserId(user1.getId())
                .setBatchId(batchId)
                .setCreateTime(LocalDateTime.now());
        assertEquals(1, reservationMapper.insert(existing));
        reservationRequestService.createPreAccepted(
                reservationIdSeed, user1.getId(), batchId);
        assertTrue(reservationRequestService.casStatus(
                reservationIdSeed,
                ReservationRequestStatus.PUBLISHED,
                ReservationRequestStatus.PRE_ACCEPTED));
        int quotaBefore = reservationBatchService.getById(batchId).getRemainingQuota();

        ReservationMessageDTO conflict = message(reservationIdSeed, user2.getId(), batchId);
        publishAndConfirm(conflict);
        ReservationMessageDTO barrier = message(reservationIdSeed + 1, user1.getId(), batchId);
        createPublishedRequest(barrier);
        publishAndConfirm(barrier);

        ReservationRequest barrierRequest = waitForRequestStatus(
                barrier.getReservationId(), ReservationRequestStatus.CONFIRMED);
        assertNotNull(barrierRequest);
        ReservationDlq deadLetter = waitForDlqRow(conflict.getReservationId());
        assertNotNull(deadLetter, "主键冲突死信未写入台账");
        assertEquals(conflict.getReservationId(), deadLetter.getReservationId());
        assertNotNull(deadLetter.getFailureReason());
        assertTrue(deadLetter.getFailureReason().startsWith("x-death:"));
        assertEquals(ReservationDlqStatus.NEW, deadLetter.getStatus());
        assertEquals(0, deadLetter.getReplayCount());
        assertDlqQueueEmpty();
        assertEquals(0, countReservations(user2.getId(), batchId));
        assertEquals(quotaBefore,
                reservationBatchService.getById(batchId).getRemainingQuota());
        ReservationRequest conflictRequest = reservationRequestMapper.selectById(reservationIdSeed);
        assertEquals(ReservationRequestStatus.PUBLISHED, conflictRequest.getStatus());
        assertNull(conflictRequest.getFailReason());
    }

    @Test
    void shouldRecordQuotaConflictWithoutChangingRequestStatus() throws Exception {
        Long batchId = createAndPublishBatch(validBatch());
        reservationBatchService.updateById(new ReservationBatch()
                .setId(batchId)
                .setRemainingQuota(0));
        ReservationMessageDTO dto = message(reservationIdSeed, user1.getId(), batchId);
        createPublishedRequest(dto);

        publishAndConfirm(dto);

        ReservationDlq deadLetter = waitForDlqRow(dto.getReservationId());
        assertNotNull(deadLetter, "名额冲突死信未写入台账");
        assertEquals(dto.getReservationId(), deadLetter.getReservationId());
        assertEquals("QUOTA_CONFLICT", deadLetter.getFailureReason());
        assertEquals(ReservationDlqStatus.NEW, deadLetter.getStatus());
        assertEquals(0, deadLetter.getReplayCount());
        assertDlqQueueEmpty();
        assertNull(reservationMapper.selectById(dto.getReservationId()));
        ReservationRequest request = reservationRequestMapper.selectById(dto.getReservationId());
        assertEquals(ReservationRequestStatus.PUBLISHED, request.getStatus());
        assertEquals("QUOTA_CONFLICT", request.getFailReason());
    }

    @Test
    void shouldPersistMessageWhenRequestLedgerIsMissing() throws Exception {
        Long batchId = createAndPublishBatch(validBatch());
        ReservationMessageDTO dto = message(reservationIdSeed, user1.getId(), batchId);

        publishAndConfirm(dto);

        assertNotNull(waitForReservation(dto.getReservationId()));
        assertNull(reservationRequestMapper.selectById(dto.getReservationId()));
        assertNull(rabbitTemplate.receive(RESERVATION_CREATE_DLQ, 2_000));
    }

    @Test
    void shouldTreatRepeatedMessageAsDuplicateWithoutSecondQuotaDeduction() throws Exception {
        Long batchId = createAndPublishBatch(validBatch());
        ReservationMessageDTO duplicate = message(reservationIdSeed, user1.getId(), batchId);
        publishAndConfirm(duplicate);
        assertNotNull(waitForReservation(duplicate.getReservationId()));

        publishAndConfirm(duplicate);
        ReservationMessageDTO barrier = message(reservationIdSeed + 1, user2.getId(), batchId);
        publishAndConfirm(barrier);

        // 同一队列单消费者按序处理；barrier 落库说明前面的重复消息已经完成 ACK。
        assertNotNull(waitForReservation(barrier.getReservationId()));
        assertEquals(1, countReservations(user1.getId(), batchId));
        assertEquals(2, countReservationsForBatch(batchId));
        assertEquals(INITIAL_QUOTA - 2,
                reservationBatchService.getById(batchId).getRemainingQuota());
    }

    @Test
    void shouldTreatSameUserAndBatchWithDifferentReservationIdAsDuplicate() throws Exception {
        Long batchId = createAndPublishBatch(validBatch());
        ReservationMessageDTO first = message(reservationIdSeed, user1.getId(), batchId);
        publishAndConfirm(first);
        assertNotNull(waitForReservation(first.getReservationId()));

        ReservationMessageDTO duplicate = message(
                reservationIdSeed + 1, user1.getId(), batchId);
        publishAndConfirm(duplicate);
        ReservationMessageDTO barrier = message(
                reservationIdSeed + 2, user2.getId(), batchId);
        publishAndConfirm(barrier);

        // barrier 落库说明前面的不同 id 重复消息已完成处理。
        assertNotNull(waitForReservation(barrier.getReservationId()));
        assertEquals(1, countReservations(user1.getId(), batchId));
        assertEquals(2, countReservationsForBatch(batchId));
        assertEquals(INITIAL_QUOTA - 2,
                reservationBatchService.getById(batchId).getRemainingQuota());
        assertNull(rabbitTemplate.receive(RESERVATION_CREATE_DLQ, 2_000));
    }

    @Test
    void shouldPersistAcceptedReservationEvenWhenBatchClosesBeforeConsumption() throws Exception {
        Long batchId = createAndPublishBatch(validBatch());
        MessageListenerContainer container = listenerContainer(RESERVATION_CREATE_QUEUE);
        container.stop();
        Long reservationId;
        try {
            reservationId = submitAs(batchId, user1);
            loginAs(admin);
            reservationBatchService.close(batchId);
            assertEquals(ReservationBatchStatus.CLOSED,
                    reservationBatchService.getById(batchId).getStatus());
            assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(quotaKey(batchId))));
        } finally {
            container.start();
        }

        Reservation stored = waitForReservation(reservationId);
        assertNotNull(stored);
        assertEquals(batchId, stored.getBatchId());
        assertEquals(INITIAL_QUOTA - 1,
                reservationBatchService.getById(batchId).getRemainingQuota());
        loginAs(user2);
        ReservationBatchVO detail = reservationBatchService.queryBatchById(batchId);
        assertNotNull(detail);
        assertEquals(INITIAL_QUOTA - 1, detail.getRemainingQuota());
    }

    @Test
    void shouldRestoreQuotaAndRemoveUserMarkerWhenQuotaKeyExists() {
        Long batchId = createAndPublishBatch(validBatch());
        stringRedisTemplate.opsForValue().set(quotaKey(batchId), "3");
        stringRedisTemplate.opsForSet().add(usersKey(batchId), user1.getId().toString());

        int code = rollbackExecutor.rollback(batchId, user1.getId());

        assertEquals(1, code);
        assertEquals("4", stringRedisTemplate.opsForValue().get(quotaKey(batchId)));
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                .isMember(usersKey(batchId), user1.getId().toString())));
    }

    // 同一用户连续补偿两次：第一次归还 1 个名额，第二次必须无操作（幂等），名额不得变成 5
    @Test
    void shouldNotRestoreQuotaTwiceForSameUser() throws Exception {
        Long batchId = createAndPublishBatch(validBatch());
        Long reservationId = submitAs(batchId, user1);
        assertNotNull(waitForReservation(reservationId));
        assertEquals("3", stringRedisTemplate.opsForValue().get(quotaKey(batchId)));
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                .isMember(usersKey(batchId), user1.getId().toString())));

        int firstCode = rollbackExecutor.rollback(batchId, user1.getId());

        assertEquals(1, firstCode);
        assertEquals("4", stringRedisTemplate.opsForValue().get(quotaKey(batchId)));

        int secondCode = rollbackExecutor.rollback(batchId, user1.getId());

        assertEquals(0, secondCode);
        assertEquals("4", stringRedisTemplate.opsForValue().get(quotaKey(batchId)));
    }

    @Test
    void shouldNotRecreateQuotaKeyWhenRollbackRunsAfterClose() {
        Long batchId = createAndPublishBatch(validBatch());
        loginAs(admin);
        reservationBatchService.close(batchId);
        // 单独重建用户标记，证明名额键缺失分支仍会清理标记，但不会复活名额键。
        stringRedisTemplate.opsForSet().add(usersKey(batchId), user1.getId().toString());

        int code = rollbackExecutor.rollback(batchId, user1.getId());

        // 用户标记存在但名额键已缺失（批次已关闭）→ 新语义返回 2，与「本来就没占用」的 0 区分开
        assertEquals(2, code);
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(quotaKey(batchId))));
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(usersKey(batchId))));
    }

    @Test
    void shouldReturnUnroutableMandatoryMessageToCorrelationData() throws Exception {
        Long batchId = createAndPublishBatch(validBatch());
        CorrelationData correlationData = new CorrelationData(String.valueOf(reservationIdSeed));

        rabbitTemplate.convertAndSend(
                RESERVATION_EXCHANGE,
                "reservation.missing." + reservationIdSeed,
                message(reservationIdSeed, user1.getId(), batchId),
                correlationData);

        CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);
        assertTrue(confirm.isAck());
        assertNotNull(correlationData.getReturnedMessage());
    }

    @Test
    void shouldRollbackAndFailSubmitWhenCreateMessageCannotBeRouted() {
        Long batchId = createAndPublishBatch(validBatch());
        Binding binding = reservationCreateBinding();
        rabbitAdmin.removeBinding(binding);
        try {
            ServiceUnavailableException exception = assertThrows(
                    ServiceUnavailableException.class,
                    () -> submitAs(batchId, user1));

            assertEquals("预约失败，请重试", exception.getMessage());
            assertEquals(String.valueOf(INITIAL_QUOTA),
                    stringRedisTemplate.opsForValue().get(quotaKey(batchId)));
            assertFalse(Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                    .isMember(usersKey(batchId), user1.getId().toString())));
            assertEquals(0, countReservationsForBatch(batchId));
        } finally {
            rabbitAdmin.declareBinding(binding);
        }
    }

    @Test
    void shouldDeadLetterInvalidJsonAndContinueConsuming() throws Exception {
        Long batchId = createAndPublishBatch(validBatch());
        String invalidPayload = "{invalid-json";
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader("__TypeId__", ReservationMessageDTO.class.getName());
        Message invalidMessage = new Message(
                invalidPayload.getBytes(StandardCharsets.UTF_8), properties);

        rabbitTemplate.send(RESERVATION_EXCHANGE, RESERVATION_CREATE_ROUTING_KEY, invalidMessage);

        ReservationDlq deadLetter = waitForDlqRow(null);
        assertNotNull(deadLetter, "坏 JSON 死信未写入台账");
        assertNull(deadLetter.getReservationId());
        assertEquals(invalidPayload, deadLetter.getPayload());
        assertEquals("PAYLOAD_UNPARSEABLE", deadLetter.getFailureReason());
        assertEquals(ReservationDlqStatus.NEW, deadLetter.getStatus());
        assertEquals(0, deadLetter.getReplayCount());
        assertDlqQueueEmpty();
        ReservationMessageDTO probe = message(reservationIdSeed, user1.getId(), batchId);
        publishAndConfirm(probe);
        assertNotNull(waitForReservation(probe.getReservationId()));
    }

    @Test
    void shouldKeepRecordingDeadLettersAfterUnparseableOne() throws Exception {
        byte[] invalidBody = "{invalid-json".getBytes(StandardCharsets.UTF_8);
        rabbitTemplate.send("", RESERVATION_CREATE_DLQ,
                new Message(invalidBody, new MessageProperties()));

        ReservationDlq invalidDeadLetter = waitForDlqRow(null);
        assertNotNull(invalidDeadLetter, "不可解析死信未写入台账");
        assertNull(invalidDeadLetter.getReservationId());
        assertEquals("PAYLOAD_UNPARSEABLE", invalidDeadLetter.getFailureReason());

        String validPayload = "{\"reservationId\":" + reservationIdSeed + "}";
        rabbitTemplate.send("", RESERVATION_CREATE_DLQ, new Message(
                validPayload.getBytes(StandardCharsets.UTF_8), new MessageProperties()));

        ReservationDlq validDeadLetter = waitForDlqRow(reservationIdSeed);
        assertNotNull(validDeadLetter, "不可解析死信之后的合法死信未写入台账");
        assertDlqQueueEmpty();
    }

    @Test
    void shouldKeepConsumingAfterLedgerWriteFailure() throws Exception {
        Mockito.doThrow(new IllegalStateException("模拟落库失败"))
                .doCallRealMethod()
                .when(reservationDlqService)
                .recordDeadLetter(any(byte[].class), nullable(String.class));
        try {
            long failedReservationId = reservationIdSeed;
            String failedPayload = "{\"reservationId\":" + failedReservationId + "}";
            rabbitTemplate.send("", RESERVATION_CREATE_DLQ, new Message(
                    failedPayload.getBytes(StandardCharsets.UTF_8), new MessageProperties()));

            waitForDlqQueueToDrain();
            int rowsAfterFailure = reservationDlqMapper.selectCount(
                    new LambdaQueryWrapper<ReservationDlq>()
                            .gt(ReservationDlq::getId, dlqBaselineId));
            assertEquals(0, rowsAfterFailure, "落库失败的死信不应留下台账行");

            long successfulReservationId = reservationIdSeed + 1;
            String successfulPayload =
                    "{\"reservationId\":" + successfulReservationId + "}";
            rabbitTemplate.send("", RESERVATION_CREATE_DLQ, new Message(
                    successfulPayload.getBytes(StandardCharsets.UTF_8), new MessageProperties()));

            ReservationDlq recorded = waitForDlqRow(successfulReservationId);
            assertNotNull(recorded, "落库失败后消费者未继续处理下一条死信");
            assertNull(reservationDlqMapper.selectOne(
                    new LambdaQueryWrapper<ReservationDlq>()
                            .gt(ReservationDlq::getId, dlqBaselineId)
                            .eq(ReservationDlq::getReservationId, failedReservationId)));
            assertDlqQueueEmpty();
        } finally {
            Mockito.reset(reservationDlqService);
        }
    }

    @Test
    void shouldDeadLetterQuotaConflictWithoutMakingMysqlQuotaNegative() throws Exception {
        Long batchId = createAndPublishBatch(validBatch());
        reservationBatchService.updateById(new ReservationBatch()
                .setId(batchId)
                .setRemainingQuota(0));
        ReservationMessageDTO dto = message(reservationIdSeed, user1.getId(), batchId);

        publishAndConfirm(dto);

        ReservationDlq deadLetter = waitForDlqRow(dto.getReservationId());
        assertNotNull(deadLetter, "无账本的名额冲突死信未写入台账");
        assertEquals(dto.getReservationId(), deadLetter.getReservationId());
        assertNotNull(deadLetter.getFailureReason());
        assertTrue(deadLetter.getFailureReason().startsWith("x-death:"));
        assertEquals(ReservationDlqStatus.NEW, deadLetter.getStatus());
        assertEquals(0, deadLetter.getReplayCount());
        assertDlqQueueEmpty();
        assertNull(reservationMapper.selectById(dto.getReservationId()));
        assertEquals(0, reservationBatchService.getById(batchId).getRemainingQuota());
    }

    @Test
    void shouldParseReservationIdBeforeTruncatingOversizedPayload() throws Exception {
        long reservationId = reservationIdSeed;
        char[] padding = new char[70_000];
        Arrays.fill(padding, 'x');
        String payload = "{\"reservationId\":" + reservationId
                + ",\"padding\":\"" + new String(padding) + "\"}";
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        assertTrue(body.length > 70_000);

        rabbitTemplate.send("", RESERVATION_CREATE_DLQ,
                new Message(body, new MessageProperties()));

        ReservationDlq deadLetter = waitForDlqRow(reservationId);
        assertNotNull(deadLetter, "超大死信未写入台账");
        assertEquals(reservationId, deadLetter.getReservationId());
        assertTrue(deadLetter.getPayload().getBytes(StandardCharsets.UTF_8).length <= 60_000);
        assertNull(deadLetter.getFailureReason());
        assertEquals(ReservationDlqStatus.NEW, deadLetter.getStatus());
        assertEquals(0, deadLetter.getReplayCount());
        assertDlqQueueEmpty();
    }

    @Test
    void shouldNotLoseLedgerRowWhenPayloadIsEmpty() throws Exception {
        rabbitTemplate.send("", RESERVATION_CREATE_DLQ,
                new Message(new byte[0], new MessageProperties()));

        ReservationDlq deadLetter = waitForDlqRow(null);
        assertNotNull(deadLetter, "空消息体死信未写入台账");
        assertNull(deadLetter.getReservationId());
        assertTrue(deadLetter.getPayload() == null || deadLetter.getPayload().isEmpty());
        assertEquals("PAYLOAD_UNPARSEABLE", deadLetter.getFailureReason());
        assertEquals(ReservationDlqStatus.NEW, deadLetter.getStatus());
        assertEquals(0, deadLetter.getReplayCount());
        assertDlqQueueEmpty();
    }

    private void publishAndConfirm(ReservationMessageDTO dto) throws Exception {
        CorrelationData correlationData = new CorrelationData(String.valueOf(dto.getReservationId()));
        rabbitTemplate.convertAndSend(
                RESERVATION_EXCHANGE,
                RESERVATION_CREATE_ROUTING_KEY,
                dto,
                correlationData);
        CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);
        assertTrue(confirm.isAck());
        assertNull(correlationData.getReturnedMessage());
    }

    private Reservation waitForReservation(Long reservationId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        Reservation reservation;
        do {
            reservation = reservationMapper.selectById(reservationId);
            if (reservation != null) {
                return reservation;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        return null;
    }

    private ReservationRequest waitForRequestStatus(
            Long reservationId,
            ReservationRequestStatus expectedStatus) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        ReservationRequest request;
        do {
            request = reservationRequestMapper.selectById(reservationId);
            if (request != null && request.getStatus() == expectedStatus) {
                return request;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        return null;
    }

    private ReservationDlq waitForDlqRow(Long reservationId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        do {
            LambdaQueryWrapper<ReservationDlq> wrapper =
                    new LambdaQueryWrapper<ReservationDlq>()
                            .gt(ReservationDlq::getId, dlqBaselineId)
                            .orderByAsc(ReservationDlq::getId);
            if (reservationId == null) {
                wrapper.isNull(ReservationDlq::getReservationId);
            } else {
                wrapper.eq(ReservationDlq::getReservationId, reservationId);
            }
            List<ReservationDlq> rows = reservationDlqMapper.selectList(wrapper);
            if (rows != null && !rows.isEmpty()) {
                return rows.get(0);
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        return null;
    }

    private long currentDlqMaxId() {
        List<ReservationDlq> rows = reservationDlqMapper.selectList(
                new LambdaQueryWrapper<ReservationDlq>()
                        .select(ReservationDlq::getId)
                        .orderByDesc(ReservationDlq::getId)
                        .last("LIMIT 1"));
        return rows == null || rows.isEmpty() ? 0L : rows.get(0).getId();
    }

    private void assertDlqQueueEmpty() {
        QueueInformation queueInformation = rabbitAdmin.getQueueInfo(RESERVATION_CREATE_DLQ);
        assertNotNull(queueInformation, "死信队列不存在");
        assertEquals(0, queueInformation.getMessageCount(),
                "死信台账已落库，但队列仍有未消费消息");
        assertNull(rabbitTemplate.receive(RESERVATION_CREATE_DLQ));
    }

    private void waitForDlqQueueToDrain() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        QueueInformation queueInformation;
        do {
            queueInformation = rabbitAdmin.getQueueInfo(RESERVATION_CREATE_DLQ);
            assertNotNull(queueInformation, "死信队列不存在");
            if (queueInformation.getMessageCount() == 0) {
                return;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        assertEquals(0, queueInformation.getMessageCount(), "死信队列未在超时前完成消费");
    }

    private void createPublishedRequest(ReservationMessageDTO dto) {
        reservationRequestService.createPreAccepted(
                dto.getReservationId(), dto.getUserId(), dto.getBatchId());
        assertTrue(reservationRequestService.casStatus(
                dto.getReservationId(),
                ReservationRequestStatus.PUBLISHED,
                ReservationRequestStatus.PRE_ACCEPTED));
    }

    private int countReservations(Long userId, Long batchId) {
        return reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .eq(Reservation::getBatchId, batchId));
    }

    private int countReservationsForBatch(Long batchId) {
        return reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getBatchId, batchId));
    }

    private ReservationMessageDTO message(long reservationId, Long userId, Long batchId) {
        ReservationMessageDTO dto = new ReservationMessageDTO();
        dto.setReservationId(reservationId);
        dto.setUserId(userId);
        dto.setBatchId(batchId);
        dto.setCreateTime(LocalDateTime.now());
        return dto;
    }

    private Long createAndPublishBatch(ReservationBatch batch) {
        loginAs(admin);
        Long batchId = reservationBatchService.createBatch(batch);
        batchIds.add(batchId);
        reservationBatchService.publish(batchId);
        return batchId;
    }

    private Long submitAs(Long batchId, User user) {
        loginAs(user);
        return reservationService.submit(batchId);
    }

    private ReservationBatch validBatch() {
        LocalDateTime now = LocalDateTime.now();
        return new ReservationBatch()
                .setEquipmentId(equipment.getId())
                .setStartTime(now.plusDays(3))
                .setEndTime(now.plusDays(3).plusHours(2))
                .setBookingStartTime(now.minusHours(1))
                .setBookingEndTime(now.plusDays(1))
                .setTotalQuota(INITIAL_QUOTA);
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
                .setRoom("MQ101")
                .setDescription("预约消息自动化测试")
                .setStatus(LaboratoryStatus.OPEN)
                .setAdminUserId(adminUserId);
        assertTrue(laboratoryService.save(entity));
        return entity;
    }

    private Equipment createEquipment(String name, Long laboratoryId) {
        Equipment entity = new Equipment()
                .setLaboratoryId(laboratoryId)
                .setCategoryId(9_100_000_000L + Math.abs(System.nanoTime() % 1_000_000L))
                .setName(name)
                .setDescription("预约消息自动化测试")
                .setLocation("MQ101-TEST")
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

    private void resetQueuesAndStartListeners() {
        MessageListenerContainer dlqContainer = listenerContainer(RESERVATION_CREATE_DLQ);
        if (dlqContainer.isRunning()) {
            dlqContainer.stop();
        }
        MessageListenerContainer createContainer = listenerContainer(RESERVATION_CREATE_QUEUE);
        if (createContainer.isRunning()) {
            createContainer.stop();
        }
        rabbitAdmin.purgeQueue(RESERVATION_CREATE_QUEUE, false);
        rabbitAdmin.purgeQueue(RESERVATION_CREATE_DLQ, false);
        // stop() 等待在途死信处理完成；基线必须在这个屏障之后读取。
        dlqBaselineId = currentDlqMaxId();
        dlqContainer.start();
        createContainer.start();
    }

    private MessageListenerContainer listenerContainer(String queueName) {
        for (MessageListenerContainer container :
                listenerEndpointRegistry.getListenerContainers()) {
            if (container instanceof AbstractMessageListenerContainer
                    && Arrays.asList(((AbstractMessageListenerContainer) container)
                            .getQueueNames()).contains(queueName)) {
                return container;
            }
        }
        throw new IllegalStateException("消息监听容器不存在：" + queueName);
    }

    private Binding reservationCreateBinding() {
        return new Binding(
                RESERVATION_CREATE_QUEUE,
                Binding.DestinationType.QUEUE,
                RESERVATION_EXCHANGE,
                RESERVATION_CREATE_ROUTING_KEY,
                null);
    }

    private String quotaKey(Long batchId) {
        return RESERVATION_BATCH_QUOTA_KEY + batchId;
    }

    private String usersKey(Long batchId) {
        return RESERVATION_BATCH_USERS_KEY + batchId;
    }
}
