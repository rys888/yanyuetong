package com.hmdp.service.impl;

import com.hmdp.dto.UserDTO;
import com.hmdp.config.RabbitMQConfig;
import com.hmdp.entity.Equipment;
import com.hmdp.entity.ReservationBatch;
import com.hmdp.entity.ReservationRequest;
import com.hmdp.enums.EquipmentStatus;
import com.hmdp.enums.ReservationBatchStatus;
import com.hmdp.enums.ReservationRequestStatus;
import com.hmdp.enums.ReservationResultCode;
import com.hmdp.exception.BadRequestException;
import com.hmdp.exception.ForbiddenException;
import com.hmdp.exception.ServiceUnavailableException;
import com.hmdp.mq.ReservationMessageDTO;
import com.hmdp.service.IEquipmentService;
import com.hmdp.service.IReservationBatchService;
import com.hmdp.service.IReservationRequestService;
import com.hmdp.service.IReservationService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.ReservationQuotaExecutor;
import com.hmdp.utils.ReservationQuotaRollbackExecutor;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ReservationServiceImpl implements IReservationService {

    @Resource
    private IReservationBatchService reservationBatchService;

    @Resource
    private IEquipmentService equipmentService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private ReservationQuotaExecutor reservationQuotaExecutor;

    @Resource
    private ReservationQuotaRollbackExecutor reservationQuotaRollbackExecutor;

    @Resource
    private IReservationRequestService reservationRequestService;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Override
    public Long submit(Long batchId) {
        if (batchId == null) {
            throw new BadRequestException("预约批次不存在");
        }
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            throw new ForbiddenException("请先登录");
        }
        ReservationBatch batch = reservationBatchService.getById(batchId);
        if (batch == null) {
            throw new BadRequestException("预约批次不存在");
        }
        if (batch.getStatus() != ReservationBatchStatus.PUBLISHED) {
            throw new BadRequestException("批次当前不可预约");
        }
        Equipment equipment = equipmentService.getById(batch.getEquipmentId());
        if (equipment == null
                || equipment.getStatus() != EquipmentStatus.AVAILABLE
                || !Boolean.TRUE.equals(equipment.getBookingEnabled())) {
            throw new BadRequestException("设备当前不可预约");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(batch.getBookingStartTime())) {
            throw new BadRequestException("预约尚未开始");
        }
        if (now.isAfter(batch.getBookingEndTime())) {
            throw new BadRequestException("预约已截止");
        }
        long reservationId = redisIdWorker.nextId("reservation");
        try {
            reservationRequestService.createPreAccepted(reservationId, user.getId(), batchId);
        } catch (RuntimeException e) {
            log.error("预约请求账本创建失败：reservationId={}，userId={}，batchId={}",
                    reservationId, user.getId(), batchId, e);
            throw new ServiceUnavailableException("预约失败，请重试");
        }
        ReservationResultCode resultCode = reservationQuotaExecutor.tryAcquire(batch, user.getId(), now);
        if (resultCode != ReservationResultCode.SUCCESS) {
            log.warn("预约名额抢占被拒：batchId={}, userId={}, resultCode={}",
                    batchId, user.getId(), resultCode);
            try {
                boolean compensated = reservationRequestService.casStatusWithFailReason(
                        reservationId,
                        ReservationRequestStatus.COMPENSATED,
                        resultCode.name(),
                        ReservationRequestStatus.PRE_ACCEPTED);
                if (!compensated) {
                    log.error("预约名额抢占被拒后账本补偿失败：reservationId={}，resultCode={}",
                            reservationId, resultCode);
                }
            } catch (RuntimeException e) {
                log.error("预约名额抢占被拒后账本补偿异常：reservationId={}，resultCode={}",
                        reservationId, resultCode, e);
            }
            throw new BadRequestException(resultCode.getMessage());
        }
        publishReservationMessage(reservationId, user.getId(), batchId, now);
        return reservationId;
    }

    private void publishReservationMessage(
            long reservationId,
            Long userId,
            Long batchId,
            LocalDateTime createTime) {
        ReservationMessageDTO message = new ReservationMessageDTO();
        message.setReservationId(reservationId);
        message.setUserId(userId);
        message.setBatchId(batchId);
        message.setCreateTime(createTime);
        CorrelationData correlationData = new CorrelationData(String.valueOf(reservationId));

        PublishOutcome outcome;
        String failureReason = null;
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.RESERVATION_EXCHANGE,
                    RabbitMQConfig.RESERVATION_CREATE_ROUTING_KEY,
                    message,
                    correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                outcome = PublishOutcome.DEFINITELY_NOT_DELIVERED;
                failureReason = "CONFIRM_NACK";
            } else if (correlationData.getReturnedMessage() != null) {
                outcome = PublishOutcome.DEFINITELY_NOT_DELIVERED;
                failureReason = "CONFIRM_RETURN";
            } else {
                outcome = PublishOutcome.ACKED;
            }
        } catch (AmqpConnectException e) {
            log.error("预约消息连接 RabbitMQ 失败：reservationId={}，userId={}，batchId={}",
                    reservationId, userId, batchId, e);
            outcome = PublishOutcome.DEFINITELY_NOT_DELIVERED;
            failureReason = "AMQP_CONNECT_FAILED";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("等待预约消息确认时被中断：reservationId={}，userId={}，batchId={}",
                    reservationId, userId, batchId, e);
            outcome = PublishOutcome.UNKNOWN;
        } catch (Exception e) {
            log.error("预约消息发送结果不确定：reservationId={}，userId={}，batchId={}",
                    reservationId, userId, batchId, e);
            outcome = PublishOutcome.UNKNOWN;
        }

        if (outcome == PublishOutcome.ACKED) {
            markPublishedWithRetry(reservationId);
            return;
        }

        if (outcome == PublishOutcome.DEFINITELY_NOT_DELIVERED) {
            try {
                int rollbackCode = reservationQuotaRollbackExecutor.rollback(batchId, userId);
                log.warn("预约消息确定未投递，名额回滚完成：reservationId={}，rollbackCode={}",
                        reservationId, rollbackCode);
                try {
                    boolean compensated = reservationRequestService.casStatusWithFailReason(
                            reservationId,
                            ReservationRequestStatus.COMPENSATED,
                            failureReason,
                            ReservationRequestStatus.PRE_ACCEPTED,
                            ReservationRequestStatus.PUBLISHED);
                    if (!compensated) {
                        log.error("名额回滚后账本置为 COMPENSATED 失败：reservationId={}，reason={}",
                                reservationId, failureReason);
                    }
                } catch (RuntimeException statusException) {
                    log.error("名额回滚后账本置为 COMPENSATED 异常：reservationId={}，reason={}",
                            reservationId, failureReason, statusException);
                }
            } catch (RuntimeException rollbackException) {
                log.error("预约消息确定未投递但名额回滚异常，账本保留非终态：reservationId={}，userId={}，batchId={}",
                        reservationId, userId, batchId, rollbackException);
            }
            throw new ServiceUnavailableException("预约失败，请重试");
        }

        try {
            boolean unknown = reservationRequestService.casStatus(
                    reservationId,
                    ReservationRequestStatus.UNKNOWN,
                    ReservationRequestStatus.PRE_ACCEPTED,
                    ReservationRequestStatus.PUBLISHED);
            if (!unknown) {
                log.error("预约消息结果不确定时账本置为 UNKNOWN 失败：reservationId={}",
                        reservationId);
            }
        } catch (RuntimeException statusException) {
            log.error("预约消息结果不确定时账本置为 UNKNOWN 异常：reservationId={}",
                    reservationId, statusException);
        }
        throw new ServiceUnavailableException("预约结果待确认，请稍后查询");
    }

    private void markPublishedWithRetry(long reservationId) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                if (reservationRequestService.casStatus(
                        reservationId,
                        ReservationRequestStatus.PUBLISHED,
                        ReservationRequestStatus.PRE_ACCEPTED)) {
                    return;
                }
                log.warn("预约消息 ACK 后账本置为 PUBLISHED 失败：reservationId={}，attempt={}",
                        reservationId, attempt);
            } catch (RuntimeException e) {
                log.error("预约消息 ACK 后账本置为 PUBLISHED 异常：reservationId={}，attempt={}",
                        reservationId, attempt, e);
            }
        }
        // 重试耗尽：若账本已被消费者/对账推进为终态，这是正常的先后竞争，不是故障
        try {
            ReservationRequest current = reservationRequestService.getByRequestId(reservationId);
            if (current != null && current.getStatus().isTerminal()) {
                log.info("账本已由消费者或对账推进为 {}，无需再置 PUBLISHED：reservationId={}",
                        current.getStatus(), reservationId);
                return;
            }
        } catch (RuntimeException e) {
            log.error("预约消息 ACK 后账本更新重试耗尽：reservationId={}", reservationId, e);
            return;
        }
        log.error("预约消息 ACK 后账本更新重试耗尽：reservationId={}", reservationId);
    }

    private enum PublishOutcome {
        ACKED,
        DEFINITELY_NOT_DELIVERED,
        UNKNOWN
    }
}
