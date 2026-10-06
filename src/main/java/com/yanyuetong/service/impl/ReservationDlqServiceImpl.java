package com.yanyuetong.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyuetong.dto.ReservationDlqQueryDTO;
import com.yanyuetong.entity.Reservation;
import com.yanyuetong.entity.ReservationDlq;
import com.yanyuetong.entity.ReservationRequest;
import com.yanyuetong.enums.ReservationDlqStatus;
import com.yanyuetong.enums.ReservationRequestStatus;
import com.yanyuetong.mapper.ReservationDlqMapper;
import com.yanyuetong.mapper.ReservationMapper;
import com.yanyuetong.mq.ReservationMessageDTO;
import com.yanyuetong.service.IReservationDlqService;
import com.yanyuetong.service.IReservationRequestService;
import com.yanyuetong.utils.SystemConstants;
import com.yanyuetong.vo.ReservationDlqReplayVO;
import com.yanyuetong.vo.ReservationDlqVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.yanyuetong.config.RabbitMQConfig.RESERVATION_CREATE_ROUTING_KEY;
import static com.yanyuetong.config.RabbitMQConfig.RESERVATION_EXCHANGE;

@Slf4j
@Service
public class ReservationDlqServiceImpl implements IReservationDlqService {

    private static final int MAX_PAYLOAD_BYTES = 60_000;
    private static final int MAX_FAILURE_REASON_LENGTH = 512;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private ReservationDlqMapper reservationDlqMapper;

    @Resource
    private IReservationRequestService reservationRequestService;

    @Resource
    private ReservationMapper reservationMapper;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Value("${yanyuetong.reservation.dlq-max-replay:3}")
    private int maxReplay;

    @Override
    public Long extractReservationId(String payload) {
        if (payload == null) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode reservationId = root.get("reservationId");
            if (reservationId == null
                    || !reservationId.isNumber()
                    || !reservationId.canConvertToLong()) {
                return null;
            }
            return reservationId.asLong();
        } catch (Exception e) {
            // 非法 JSON 是死信的正常输入形态；解析失败只降级原因判定，不能阻断台账落库。
            log.warn("解析死信 reservationId 失败，按不可解析处理：{}", e.getMessage());
            return null;
        }
    }

    @Override
    public ReservationDlq recordDeadLetter(byte[] body, String xDeathReason) {
        byte[] safeBody = body == null ? new byte[0] : body;
        String originalPayload = new String(safeBody, StandardCharsets.UTF_8);

        // 必须先解析完整原始字节；若先按列上限截断，合法的大 JSON 会被切断并误判为不可解析。
        Long reservationId = extractReservationId(originalPayload);

        int payloadLength = Math.min(safeBody.length, MAX_PAYLOAD_BYTES);
        String storedPayload = new String(
                safeBody, 0, payloadLength, StandardCharsets.UTF_8);

        String failureReason = resolveFailureReason(reservationId, xDeathReason);
        if (failureReason != null && failureReason.length() > MAX_FAILURE_REASON_LENGTH) {
            failureReason = failureReason.substring(0, MAX_FAILURE_REASON_LENGTH);
        }

        ReservationDlq deadLetter = new ReservationDlq()
                .setReservationId(reservationId)
                .setPayload(storedPayload)
                .setFailureReason(failureReason)
                .setReplayCount(0)
                .setStatus(ReservationDlqStatus.NEW);
        if (reservationDlqMapper.insert(deadLetter) != 1) {
            throw new IllegalStateException("死信台账插入失败");
        }
        return deadLetter;
    }

    @Override
    public IPage<ReservationDlqVO> queryPage(ReservationDlqQueryDTO query) {
        ReservationDlqQueryDTO actualQuery = query == null
                ? new ReservationDlqQueryDTO()
                : query;
        int current = actualQuery.getCurrent() == null ? 1 : actualQuery.getCurrent();
        Page<ReservationDlq> entityPage = new Page<>(
                current, SystemConstants.DEFAULT_PAGE_SIZE);
        IPage<ReservationDlq> result = reservationDlqMapper.selectPage(
                entityPage,
                new LambdaQueryWrapper<ReservationDlq>()
                        .select(
                                ReservationDlq::getId,
                                ReservationDlq::getReservationId,
                                ReservationDlq::getFailureReason,
                                ReservationDlq::getReplayCount,
                                ReservationDlq::getStatus,
                                ReservationDlq::getCreateTime,
                                ReservationDlq::getUpdateTime)
                        .eq(actualQuery.getStatus() != null,
                                ReservationDlq::getStatus, actualQuery.getStatus())
                        .orderByDesc(ReservationDlq::getCreateTime)
                        .orderByDesc(ReservationDlq::getId));

        Page<ReservationDlqVO> voPage = new Page<>(
                result.getCurrent(), result.getSize(), result.getTotal());
        List<ReservationDlqVO> records = result.getRecords().stream()
                .map(this::toVO)
                .collect(Collectors.toList());
        voPage.setRecords(records);
        return voPage;
    }

    @Override
    public ReservationDlqVO queryById(Long id) {
        if (id == null) {
            return null;
        }
        return toVO(reservationDlqMapper.selectById(id));
    }

    @Override
    public ReservationDlqReplayVO replay(Long id) {
        ReservationDlq deadLetter = id == null ? null : reservationDlqMapper.selectById(id);

        // 1. 只有尚未处理的台账记录可以重放。
        if (deadLetter == null) {
            return rejected("死信记录不存在", null);
        }
        if (deadLetter.getStatus() != ReservationDlqStatus.NEW) {
            return rejected("死信记录已处理，不能重复重放", toVO(deadLetter));
        }

        // 2. 达到次数上限的记录保持 NEW，便于管理员继续审计。
        int replayCount = deadLetter.getReplayCount() == null
                ? 0
                : deadLetter.getReplayCount();
        if (replayCount >= maxReplay) {
            return rejected("已达到最大重放次数", toVO(deadLetter));
        }

        // 3. 三个业务 id 都必须是 JSON 数字，保证消息回到主队列后可被消费。
        ReservationMessageDTO message = parseReplayablePayload(deadLetter.getPayload());
        if (message == null) {
            return discard(deadLetter, replayCount,
                    "PAYLOAD_NOT_REPLAYABLE", "payload 无法重放");
        }

        // 4. 预约事实表已有同 id 记录时不得再投递。
        Reservation persisted = reservationMapper.selectById(message.getReservationId());
        if (persisted != null) {
            return discard(deadLetter, replayCount,
                    "ALREADY_PERSISTED", "已落库，无需重放");
        }

        // 5. 已补偿请求的名额已经归还，再投递会造成超发。
        ReservationRequest request = null;
        try {
            request = reservationRequestService.getByRequestId(message.getReservationId());
        } catch (Exception e) {
            // 与主消费者保持一致：账本缺失或暂时不可查询都不阻断事实表落库。
            log.error("重放前查询预约请求账本失败，按未知状态继续：reservationId={}",
                    message.getReservationId(), e);
        }
        if (request != null && request.getStatus() == ReservationRequestStatus.COMPENSATED) {
            return discard(deadLetter, replayCount,
                    "COMPENSATED", "请求已补偿，无法重放");
        }

        // 6. 使用与正常生产者一致的 publisher confirm + return 判定。
        ReservationDlqReplayVO publishFailure = publish(message, deadLetter);
        if (publishFailure != null) {
            return publishFailure;
        }

        // 7. 只在确认成功后以预检值做乐观 CAS；投递失败绝不修改台账。
        boolean updated = reservationDlqMapper.update(null,
                new LambdaUpdateWrapper<ReservationDlq>()
                        .eq(ReservationDlq::getId, deadLetter.getId())
                        .eq(ReservationDlq::getStatus, ReservationDlqStatus.NEW)
                        .eq(ReservationDlq::getReplayCount, replayCount)
                        .set(ReservationDlq::getStatus, ReservationDlqStatus.REPLAYED)
                        .set(ReservationDlq::getReplayCount, replayCount + 1)) == 1;
        if (!updated) {
            return rejected("死信记录已被并发重放", queryById(deadLetter.getId()));
        }
        deadLetter.setStatus(ReservationDlqStatus.REPLAYED)
                .setReplayCount(replayCount + 1);
        return new ReservationDlqReplayVO()
                .setReplayed(true)
                .setRecord(toVO(deadLetter));
    }

    private ReservationMessageDTO parseReplayablePayload(String payload) {
        if (payload == null) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root == null || !root.isObject()
                    || !isLongNumber(root.get("reservationId"))
                    || !isLongNumber(root.get("userId"))
                    || !isLongNumber(root.get("batchId"))) {
                return null;
            }
            return objectMapper.treeToValue(root, ReservationMessageDTO.class);
        } catch (Exception e) {
            log.warn("死信 payload 不可重放：dlqPayloadError={}", e.getMessage());
            return null;
        }
    }

    private boolean isLongNumber(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong();
    }

    private ReservationDlqReplayVO publish(
            ReservationMessageDTO message, ReservationDlq deadLetter) {
        CorrelationData correlationData = new CorrelationData(
                String.valueOf(message.getReservationId()));
        try {
            rabbitTemplate.convertAndSend(
                    RESERVATION_EXCHANGE,
                    RESERVATION_CREATE_ROUTING_KEY,
                    message,
                    correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(5, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                log.error("死信重放收到 confirm NACK：dlqId={}，reservationId={}，reason={}",
                        deadLetter.getId(), message.getReservationId(), confirm.getReason());
                return rejected("REPLAY_CONFIRM_NACK", toVO(deadLetter));
            }
            if (correlationData.getReturnedMessage() != null) {
                log.error("死信重放消息被退回：dlqId={}，reservationId={}",
                        deadLetter.getId(), message.getReservationId());
                return rejected("REPLAY_CONFIRM_RETURN", toVO(deadLetter));
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("等待死信重放确认时被中断：dlqId={}，reservationId={}",
                    deadLetter.getId(), message.getReservationId(), e);
            return rejected("REPLAY_PUBLISH_UNKNOWN", toVO(deadLetter));
        } catch (Exception e) {
            log.error("死信重放发送结果不确定：dlqId={}，reservationId={}",
                    deadLetter.getId(), message.getReservationId(), e);
            return rejected("REPLAY_PUBLISH_UNKNOWN", toVO(deadLetter));
        }
    }

    private ReservationDlqReplayVO discard(
            ReservationDlq deadLetter, int replayCount, String code, String reason) {
        String failureReason = appendDiscardReason(deadLetter.getFailureReason(), code);
        boolean updated = reservationDlqMapper.update(null,
                new LambdaUpdateWrapper<ReservationDlq>()
                        .eq(ReservationDlq::getId, deadLetter.getId())
                        .eq(ReservationDlq::getStatus, ReservationDlqStatus.NEW)
                        .eq(ReservationDlq::getReplayCount, replayCount)
                        .set(ReservationDlq::getStatus, ReservationDlqStatus.DISCARDED)
                        .set(ReservationDlq::getFailureReason, failureReason)) == 1;
        if (!updated) {
            return rejected("死信记录已被并发处理", queryById(deadLetter.getId()));
        }
        deadLetter.setStatus(ReservationDlqStatus.DISCARDED)
                .setFailureReason(failureReason);
        return rejected(reason, toVO(deadLetter));
    }

    /**
     * failure_reason 在首次落库时已经可能被截断，因此追加丢弃原因时优先完整保留新的
     * DISCARD 标记：先为后缀预留空间，再截短原原因，避免 512 字符边界吞掉治理结论。
     */
    private String appendDiscardReason(String original, String code) {
        String prefix = original == null ? "" : original;
        String marker = "DISCARD:" + code;
        if (prefix.isEmpty()) {
            return marker;
        }
        String suffix = " | " + marker;
        int prefixLength = Math.min(
                prefix.length(), MAX_FAILURE_REASON_LENGTH - suffix.length());
        return prefix.substring(0, prefixLength) + suffix;
    }

    private ReservationDlqReplayVO rejected(String reason, ReservationDlqVO record) {
        return new ReservationDlqReplayVO()
                .setReplayed(false)
                .setReason(reason)
                .setRecord(record);
    }

    private ReservationDlqVO toVO(ReservationDlq deadLetter) {
        if (deadLetter == null) {
            return null;
        }
        return new ReservationDlqVO()
                .setId(deadLetter.getId())
                .setReservationId(deadLetter.getReservationId())
                .setPayload(deadLetter.getPayload())
                .setFailureReason(deadLetter.getFailureReason())
                .setReplayCount(deadLetter.getReplayCount())
                .setStatus(deadLetter.getStatus())
                .setCreateTime(deadLetter.getCreateTime())
                .setUpdateTime(deadLetter.getUpdateTime());
    }

    private String resolveFailureReason(Long reservationId, String xDeathReason) {
        // 第一优先级：账本记录的是业务侧失败原因，信息量高于 broker 的通用拒绝原因。
        if (reservationId != null) {
            try {
                ReservationRequest request =
                        reservationRequestService.getByRequestId(reservationId);
                if (request != null
                        && request.getFailReason() != null
                        && !request.getFailReason().isEmpty()) {
                    return request.getFailReason();
                }
            } catch (Exception e) {
                // 账本查询异常时继续使用后续优先级，不能让这条死信失去唯一可查询痕迹。
                log.error("查询预约请求账本失败，降级使用死信元数据：reservationId={}",
                        reservationId, e);
            }
        }

        // 第二优先级：消息体本身无法给出预约 id。
        if (reservationId == null) {
            return "PAYLOAD_UNPARSEABLE";
        }

        // 第三优先级：保留 broker 原因，并显式标注来源。
        if (xDeathReason != null && !xDeathReason.isEmpty()) {
            return "x-death:" + xDeathReason;
        }

        // 第四优先级：可解析但账本与 broker 均没有更多原因。
        return null;
    }
}
