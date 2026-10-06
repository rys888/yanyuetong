package com.yanyuetong;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yanyuetong.dto.ReservationDlqQueryDTO;
import com.yanyuetong.entity.Reservation;
import com.yanyuetong.entity.ReservationBatch;
import com.yanyuetong.entity.ReservationDlq;
import com.yanyuetong.entity.ReservationRequest;
import com.yanyuetong.enums.ReservationBatchStatus;
import com.yanyuetong.enums.ReservationDlqStatus;
import com.yanyuetong.enums.ReservationRequestStatus;
import com.yanyuetong.enums.UserRole;
import com.yanyuetong.mapper.ReservationDlqMapper;
import com.yanyuetong.mapper.ReservationMapper;
import com.yanyuetong.mapper.ReservationRequestMapper;
import com.yanyuetong.service.IReservationBatchService;
import com.yanyuetong.service.IReservationDlqService;
import com.yanyuetong.vo.ReservationDlqReplayVO;
import com.yanyuetong.vo.ReservationDlqVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.yanyuetong.config.RabbitMQConfig.RESERVATION_CREATE_DLQ;
import static com.yanyuetong.config.RabbitMQConfig.RESERVATION_CREATE_QUEUE;
import static com.yanyuetong.utils.RedisConstants.LOGIN_USER_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 需要真实 MySQL、Redis、RabbitMQ，并应用 phase7_reservation.sql 与 phase8_consistency.sql。
 * 本类是 T7 的集成验收测试；普通沙箱只编译，不执行。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationDlqGovernanceTest {

    @Resource
    private IReservationDlqService reservationDlqService;

    @Resource
    private ReservationDlqMapper reservationDlqMapper;

    @Resource
    private ReservationMapper reservationMapper;

    @Resource
    private ReservationRequestMapper reservationRequestMapper;

    @Resource
    private IReservationBatchService reservationBatchService;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private AmqpAdmin rabbitAdmin;

    @Resource
    private RabbitListenerEndpointRegistry listenerEndpointRegistry;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private MockMvc mockMvc;

    private final List<Long> reservationIds = new ArrayList<>();
    private final List<Long> requestIds = new ArrayList<>();
    private final List<Long> batchIds = new ArrayList<>();
    private long dlqBaselineId;
    private long reservationIdSeed;
    private String adminToken;
    private String userToken;
    private boolean listenerWasRunning;

    @BeforeEach
    void setUp() {
        reservationIdSeed = 8_000_000_000L + Math.abs(System.nanoTime() % 1_000_000_000L);
        adminToken = "dlq-admin-" + reservationIdSeed;
        userToken = "dlq-user-" + reservationIdSeed;
        dlqBaselineId = currentDlqMaxId();
        MessageListenerContainer container = listenerContainer();
        listenerWasRunning = container.isRunning();
        if (listenerWasRunning) {
            container.stop();
        }
        rabbitAdmin.purgeQueue(RESERVATION_CREATE_QUEUE, false);
        rabbitAdmin.purgeQueue(RESERVATION_CREATE_DLQ, false);
        container.start();
    }

    @AfterEach
    void cleanUp() {
        MessageListenerContainer container = listenerContainer();
        if (container.isRunning()) {
            container.stop();
        }
        try {
            rabbitAdmin.purgeQueue(RESERVATION_CREATE_QUEUE, false);
            rabbitAdmin.purgeQueue(RESERVATION_CREATE_DLQ, false);
            for (Long id : reservationIds) {
                reservationMapper.deleteById(id);
            }
            for (Long id : requestIds) {
                reservationRequestMapper.deleteById(id);
            }
            reservationDlqMapper.delete(new LambdaQueryWrapper<ReservationDlq>()
                    .gt(ReservationDlq::getId, dlqBaselineId));
            for (Long id : batchIds) {
                reservationBatchService.removeById(id);
            }
            stringRedisTemplate.delete(Arrays.asList(
                    LOGIN_USER_KEY + adminToken,
                    LOGIN_USER_KEY + userToken));
        } finally {
            if (listenerWasRunning) {
                container.start();
            }
        }
    }

    @Test
    void shouldPageByStatusNewestFirstWithoutPayload() {
        ReservationDlq older = insertDlq(reservationIdSeed,
                replayablePayload(reservationIdSeed, 11L, 21L),
                "older", 0, ReservationDlqStatus.NEW,
                LocalDateTime.now().minusMinutes(2));
        ReservationDlq ignored = insertDlq(reservationIdSeed + 1,
                "discarded-payload", "ignored", 0, ReservationDlqStatus.DISCARDED,
                LocalDateTime.now().minusMinutes(1));
        ReservationDlq newer = insertDlq(reservationIdSeed + 2,
                replayablePayload(reservationIdSeed + 2, 12L, 22L),
                "newer", 0, ReservationDlqStatus.NEW,
                LocalDateTime.now());

        ReservationDlqQueryDTO query = new ReservationDlqQueryDTO();
        query.setStatus(ReservationDlqStatus.NEW);
        IPage<ReservationDlqVO> page = reservationDlqService.queryPage(query);

        assertEquals(2L, page.getTotal());
        assertEquals(Arrays.asList(newer.getId(), older.getId()), Arrays.asList(
                page.getRecords().get(0).getId(), page.getRecords().get(1).getId()));
        assertTrue(page.getRecords().stream().allMatch(row -> row.getPayload() == null));
        assertFalse(page.getRecords().stream().anyMatch(row -> row.getId().equals(ignored.getId())));
    }

    @Test
    void shouldOrderTiedCreateTimesDeterministicallyByDescendingId() {
        LocalDateTime tiedCreateTime = LocalDateTime.of(2030, 1, 2, 3, 4, 5);
        ReservationDlq first = insertDlq(reservationIdSeed,
                "first-tied-payload", "first-tied", 0, ReservationDlqStatus.NEW,
                tiedCreateTime);
        ReservationDlq second = insertDlq(reservationIdSeed + 1,
                "second-tied-payload", "second-tied", 0, ReservationDlqStatus.NEW,
                tiedCreateTime);
        ReservationDlqQueryDTO query = new ReservationDlqQueryDTO();

        List<Long> firstOrder = reservationDlqService.queryPage(query).getRecords().stream()
                .map(ReservationDlqVO::getId)
                .filter(id -> id.equals(first.getId()) || id.equals(second.getId()))
                .collect(Collectors.toList());
        List<Long> secondOrder = reservationDlqService.queryPage(query).getRecords().stream()
                .map(ReservationDlqVO::getId)
                .filter(id -> id.equals(first.getId()) || id.equals(second.getId()))
                .collect(Collectors.toList());

        // 秒级时间戳并列时必须由自增主键明确打破，不能依赖数据库的默认返回顺序。
        assertEquals(Arrays.asList(second.getId(), first.getId()), firstOrder);
        assertEquals(firstOrder, secondOrder);
    }

    @Test
    void shouldReturnFullPayloadAndFailureReasonInDetail() {
        String payload = replayablePayload(reservationIdSeed, 31L, 41L);
        ReservationDlq row = insertDlq(reservationIdSeed, payload, "LOCK_TIMEOUT",
                0, ReservationDlqStatus.NEW, LocalDateTime.now());

        ReservationDlqVO detail = reservationDlqService.queryById(row.getId());

        assertNotNull(detail);
        assertEquals(payload, detail.getPayload());
        assertEquals("LOCK_TIMEOUT", detail.getFailureReason());
    }

    @Test
    void shouldRejectMissingHandledAndReplayLimitRowsWithoutChangingThem() {
        assertFalse(reservationDlqService.replay(Long.MAX_VALUE).isReplayed());

        ReservationDlq handled = insertDlq(reservationIdSeed,
                replayablePayload(reservationIdSeed, 51L, 61L),
                "handled", 0, ReservationDlqStatus.REPLAYED, LocalDateTime.now());
        ReservationDlqReplayVO handledResult = reservationDlqService.replay(handled.getId());
        assertFalse(handledResult.isReplayed());
        assertEquals(ReservationDlqStatus.REPLAYED,
                reservationDlqMapper.selectById(handled.getId()).getStatus());

        ReservationDlq exhausted = insertDlq(reservationIdSeed + 1,
                replayablePayload(reservationIdSeed + 1, 52L, 62L),
                "exhausted", 3, ReservationDlqStatus.NEW, LocalDateTime.now());
        ReservationDlqReplayVO exhaustedResult = reservationDlqService.replay(exhausted.getId());
        assertFalse(exhaustedResult.isReplayed());
        ReservationDlq unchanged = reservationDlqMapper.selectById(exhausted.getId());
        assertEquals(ReservationDlqStatus.NEW, unchanged.getStatus());
        assertEquals(3, unchanged.getReplayCount());
    }

    @Test
    void shouldReplayValidPayloadAndMarkLedgerReplayed() throws Exception {
        long reservationId = reservationIdSeed;
        long userId = reservationIdSeed + 100;
        ReservationBatch batch = new ReservationBatch()
                .setEquipmentId(reservationIdSeed + 200)
                .setStartTime(LocalDateTime.now().plusDays(1))
                .setEndTime(LocalDateTime.now().plusDays(1).plusHours(1))
                .setBookingStartTime(LocalDateTime.now().minusHours(1))
                .setBookingEndTime(LocalDateTime.now().plusHours(1))
                .setTotalQuota(1)
                .setRemainingQuota(1)
                .setStatus(ReservationBatchStatus.PUBLISHED);
        assertTrue(reservationBatchService.save(batch));
        batchIds.add(batch.getId());
        insertRequest(reservationId, userId, batch.getId(), ReservationRequestStatus.PUBLISHED);
        ReservationDlq dlq = insertDlq(reservationId,
                replayablePayload(reservationId, userId, batch.getId()),
                "x-death:rejected", 0, ReservationDlqStatus.NEW, LocalDateTime.now());

        ReservationDlqReplayVO result = reservationDlqService.replay(dlq.getId());

        assertTrue(result.isReplayed());
        assertNotNull(waitForReservation(reservationId));
        reservationIds.add(reservationId);
        ReservationDlq replayed = reservationDlqMapper.selectById(dlq.getId());
        assertEquals(ReservationDlqStatus.REPLAYED, replayed.getStatus());
        assertEquals(1, replayed.getReplayCount());
    }

    @Test
    void shouldDiscardAlreadyPersistedWithoutPublishing() {
        long reservationId = reservationIdSeed;
        Reservation existing = new Reservation()
                .setId(reservationId)
                .setUserId(reservationIdSeed + 300)
                .setBatchId(reservationIdSeed + 400)
                .setCreateTime(LocalDateTime.now());
        assertEquals(1, reservationMapper.insert(existing));
        reservationIds.add(reservationId);
        ReservationDlq dlq = insertDlq(reservationId,
                replayablePayload(reservationId, existing.getUserId(), existing.getBatchId()),
                "primary-key-conflict", 0, ReservationDlqStatus.NEW, LocalDateTime.now());

        ReservationDlqReplayVO result = reservationDlqService.replay(dlq.getId());

        assertFalse(result.isReplayed());
        ReservationDlq discarded = reservationDlqMapper.selectById(dlq.getId());
        assertEquals(ReservationDlqStatus.DISCARDED, discarded.getStatus());
        assertEquals(0, discarded.getReplayCount());
        assertTrue(discarded.getFailureReason().startsWith("primary-key-conflict"));
        assertTrue(discarded.getFailureReason().contains("DISCARD:ALREADY_PERSISTED"));
        assertEquals(1, reservationMapper.selectCount(
                new LambdaQueryWrapper<Reservation>()
                        .eq(Reservation::getId, reservationId)));
        assertNull(rabbitTemplate.receive(RESERVATION_CREATE_QUEUE));
        assertNull(rabbitTemplate.receive(RESERVATION_CREATE_DLQ));
    }

    @Test
    void shouldDiscardCompensatedRequestWithoutPublishing() {
        long reservationId = reservationIdSeed;
        long userId = reservationIdSeed + 500;
        long batchId = reservationIdSeed + 600;
        insertRequest(reservationId, userId, batchId, ReservationRequestStatus.COMPENSATED);
        ReservationDlq dlq = insertDlq(reservationId,
                replayablePayload(reservationId, userId, batchId),
                "compensated", 0, ReservationDlqStatus.NEW, LocalDateTime.now());

        ReservationDlqReplayVO result = reservationDlqService.replay(dlq.getId());

        assertFalse(result.isReplayed());
        ReservationDlq discarded = reservationDlqMapper.selectById(dlq.getId());
        assertEquals(ReservationDlqStatus.DISCARDED, discarded.getStatus());
        assertEquals(0, discarded.getReplayCount());
        assertTrue(discarded.getFailureReason().contains("DISCARD:COMPENSATED"));
        assertNull(rabbitTemplate.receive(RESERVATION_CREATE_QUEUE));
        assertNull(rabbitTemplate.receive(RESERVATION_CREATE_DLQ));
    }

    @Test
    void shouldDiscardPayloadMissingReplayFieldsAndPreserveOriginalReasonPrefix() {
        ReservationDlq missingFields = insertDlq(reservationIdSeed,
                "{\"reservationId\":" + reservationIdSeed + "}",
                "original-reason", 0, ReservationDlqStatus.NEW, LocalDateTime.now());
        ReservationDlq truncated = insertDlq(null, "{\"reservationId\":",
                "truncated-reason", 0, ReservationDlqStatus.NEW, LocalDateTime.now());

        assertFalse(reservationDlqService.replay(missingFields.getId()).isReplayed());
        assertFalse(reservationDlqService.replay(truncated.getId()).isReplayed());

        ReservationDlq first = reservationDlqMapper.selectById(missingFields.getId());
        ReservationDlq second = reservationDlqMapper.selectById(truncated.getId());
        assertEquals(ReservationDlqStatus.DISCARDED, first.getStatus());
        assertEquals(ReservationDlqStatus.DISCARDED, second.getStatus());
        assertTrue(first.getFailureReason().startsWith("original-reason"));
        assertTrue(second.getFailureReason().startsWith("truncated-reason"));
        assertTrue(first.getFailureReason().contains("DISCARD:PAYLOAD_NOT_REPLAYABLE"));
        assertTrue(second.getFailureReason().contains("DISCARD:PAYLOAD_NOT_REPLAYABLE"));
    }

    @Test
    void shouldKeepCompleteDiscardCodeWhenOriginalReasonUsesFullColumnLength() {
        char[] originalReasonChars = new char[512];
        Arrays.fill(originalReasonChars, 'x');
        ReservationDlq row = insertDlq(reservationIdSeed,
                "{\"reservationId\":" + reservationIdSeed + "}",
                new String(originalReasonChars), 0, ReservationDlqStatus.NEW,
                LocalDateTime.now());

        assertFalse(reservationDlqService.replay(row.getId()).isReplayed());

        ReservationDlq discarded = reservationDlqMapper.selectById(row.getId());
        assertEquals(ReservationDlqStatus.DISCARDED, discarded.getStatus());
        assertEquals(512, discarded.getFailureReason().length());
        assertTrue(discarded.getFailureReason().startsWith("x"));
        assertTrue(discarded.getFailureReason()
                .endsWith(" | DISCARD:PAYLOAD_NOT_REPLAYABLE"));
    }

    @Test
    void shouldGuardAllDlqEndpointsWithLabAdminRole() throws Exception {
        mockMvc.perform(get("/reservation-dlq/page"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/reservation-dlq/{id}", Long.MAX_VALUE))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/reservation-dlq/{id}/replay", Long.MAX_VALUE))
                .andExpect(status().isUnauthorized());

        saveSession(adminToken, UserRole.LAB_ADMIN, "DLQ 管理员");

        mockMvc.perform(get("/reservation-dlq/page")
                        .header("authorization", adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/reservation-dlq/{id}", Long.MAX_VALUE)
                        .header("authorization", adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/reservation-dlq/{id}/replay", Long.MAX_VALUE)
                        .header("authorization", adminToken))
                .andExpect(status().isOk());

        saveSession(userToken, UserRole.USER, "普通用户");

        mockMvc.perform(get("/reservation-dlq/page")
                        .header("authorization", userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/reservation-dlq/{id}", Long.MAX_VALUE)
                        .header("authorization", userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/reservation-dlq/{id}/replay", Long.MAX_VALUE)
                        .header("authorization", userToken))
                .andExpect(status().isForbidden());
    }

    private ReservationDlq insertDlq(Long reservationId, String payload, String failureReason,
                                     int replayCount, ReservationDlqStatus status,
                                     LocalDateTime createTime) {
        ReservationDlq row = new ReservationDlq()
                .setReservationId(reservationId)
                .setPayload(payload)
                .setFailureReason(failureReason)
                .setReplayCount(replayCount)
                .setStatus(status)
                .setCreateTime(createTime)
                .setUpdateTime(createTime);
        assertEquals(1, reservationDlqMapper.insert(row));
        return row;
    }

    private long currentDlqMaxId() {
        List<ReservationDlq> rows = reservationDlqMapper.selectList(
                new LambdaQueryWrapper<ReservationDlq>()
                        .select(ReservationDlq::getId)
                        .orderByDesc(ReservationDlq::getId)
                        .last("LIMIT 1"));
        return rows == null || rows.isEmpty() ? 0L : rows.get(0).getId();
    }

    private void saveSession(String token, UserRole role, String nickName) {
        Map<String, String> session = new HashMap<>();
        session.put("id", String.valueOf(reservationIdSeed));
        session.put("role", role.name());
        session.put("nickName", nickName);
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, session);
    }

    private void insertRequest(long requestId, long userId, long batchId,
                               ReservationRequestStatus status) {
        ReservationRequest request = new ReservationRequest()
                .setRequestId(requestId)
                .setUserId(userId)
                .setBatchId(batchId)
                .setStatus(status);
        assertEquals(1, reservationRequestMapper.insert(request));
        requestIds.add(requestId);
    }

    private String replayablePayload(long reservationId, long userId, long batchId) {
        return "{\"reservationId\":" + reservationId
                + ",\"userId\":" + userId
                + ",\"batchId\":" + batchId
                + ",\"createTime\":\"" + LocalDateTime.now() + "\"}";
    }

    private Reservation waitForReservation(long reservationId) throws InterruptedException {
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

    private MessageListenerContainer listenerContainer() {
        for (MessageListenerContainer container : listenerEndpointRegistry.getListenerContainers()) {
            if (container instanceof AbstractMessageListenerContainer
                    && Arrays.asList(((AbstractMessageListenerContainer) container)
                    .getQueueNames()).contains(RESERVATION_CREATE_QUEUE)) {
                return container;
            }
        }
        throw new IllegalStateException("消息监听容器不存在：" + RESERVATION_CREATE_QUEUE);
    }
}
