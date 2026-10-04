package com.hmdp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Equipment;
import com.hmdp.entity.Laboratory;
import com.hmdp.entity.Reservation;
import com.hmdp.entity.ReservationBatch;
import com.hmdp.entity.User;
import com.hmdp.enums.EquipmentStatus;
import com.hmdp.enums.LaboratoryStatus;
import com.hmdp.enums.ReservationBatchStatus;
import com.hmdp.enums.UserRole;
import com.hmdp.exception.ServiceUnavailableException;
import com.hmdp.mapper.ReservationMapper;
import com.hmdp.mq.ReservationMessageDTO;
import com.hmdp.service.IEquipmentService;
import com.hmdp.service.ILaboratoryService;
import com.hmdp.service.IReservationBatchService;
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
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
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

/**
 * 需要先启动真实 MySQL、Redis、RabbitMQ，并应用 phase7_reservation.sql。
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
    private IEquipmentService equipmentService;

    @Resource
    private ILaboratoryService laboratoryService;

    @Resource
    private IUserService userService;

    @Resource
    private ReservationMapper reservationMapper;

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

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
        rabbitAdmin.declareBinding(reservationCreateBinding());
        resetQueuesAndStartListener();
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
        MessageListenerContainer container = reservationListenerContainer();
        if (container.isRunning()) {
            container.stop();
        }
        try {
            rabbitAdmin.purgeQueue(RESERVATION_CREATE_QUEUE, false);
            rabbitAdmin.purgeQueue(RESERVATION_CREATE_DLQ, false);
            if (!batchIds.isEmpty()) {
                reservationMapper.delete(new LambdaQueryWrapper<Reservation>()
                        .in(Reservation::getBatchId, batchIds));
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
        } finally {
            container.start();
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
        MessageListenerContainer container = reservationListenerContainer();
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

    @Test
    void shouldNotRecreateQuotaKeyWhenRollbackRunsAfterClose() {
        Long batchId = createAndPublishBatch(validBatch());
        loginAs(admin);
        reservationBatchService.close(batchId);
        // 单独重建用户标记，证明名额键缺失分支仍会清理标记，但不会复活名额键。
        stringRedisTemplate.opsForSet().add(usersKey(batchId), user1.getId().toString());

        int code = rollbackExecutor.rollback(batchId, user1.getId());

        assertEquals(0, code);
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
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader("__TypeId__", ReservationMessageDTO.class.getName());
        Message invalidMessage = new Message(
                "{invalid-json".getBytes(StandardCharsets.UTF_8), properties);

        rabbitTemplate.send(RESERVATION_EXCHANGE, RESERVATION_CREATE_ROUTING_KEY, invalidMessage);

        assertNotNull(rabbitTemplate.receive(RESERVATION_CREATE_DLQ, 8_000));
        ReservationMessageDTO probe = message(reservationIdSeed, user1.getId(), batchId);
        publishAndConfirm(probe);
        assertNotNull(waitForReservation(probe.getReservationId()));
    }

    @Test
    void shouldDeadLetterQuotaConflictWithoutMakingMysqlQuotaNegative() throws Exception {
        Long batchId = createAndPublishBatch(validBatch());
        reservationBatchService.updateById(new ReservationBatch()
                .setId(batchId)
                .setRemainingQuota(0));
        ReservationMessageDTO dto = message(reservationIdSeed, user1.getId(), batchId);

        publishAndConfirm(dto);

        assertNotNull(rabbitTemplate.receive(RESERVATION_CREATE_DLQ, 8_000));
        assertNull(reservationMapper.selectById(dto.getReservationId()));
        assertEquals(0, reservationBatchService.getById(batchId).getRemainingQuota());
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

    private void resetQueuesAndStartListener() {
        MessageListenerContainer container = reservationListenerContainer();
        if (container.isRunning()) {
            container.stop();
        }
        rabbitAdmin.purgeQueue(RESERVATION_CREATE_QUEUE, false);
        rabbitAdmin.purgeQueue(RESERVATION_CREATE_DLQ, false);
        container.start();
    }

    private MessageListenerContainer reservationListenerContainer() {
        Iterator<MessageListenerContainer> iterator =
                listenerEndpointRegistry.getListenerContainers().iterator();
        if (!iterator.hasNext()) {
            throw new IllegalStateException("预约消息监听容器不存在");
        }
        return iterator.next();
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
