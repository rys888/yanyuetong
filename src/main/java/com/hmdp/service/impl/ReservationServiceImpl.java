package com.hmdp.service.impl;

import com.hmdp.dto.UserDTO;
import com.hmdp.config.RabbitMQConfig;
import com.hmdp.entity.Equipment;
import com.hmdp.entity.ReservationBatch;
import com.hmdp.enums.EquipmentStatus;
import com.hmdp.enums.ReservationBatchStatus;
import com.hmdp.enums.ReservationResultCode;
import com.hmdp.exception.BadRequestException;
import com.hmdp.exception.ForbiddenException;
import com.hmdp.exception.ServiceUnavailableException;
import com.hmdp.mq.ReservationMessageDTO;
import com.hmdp.service.IEquipmentService;
import com.hmdp.service.IReservationBatchService;
import com.hmdp.service.IReservationService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.ReservationQuotaExecutor;
import com.hmdp.utils.ReservationQuotaRollbackExecutor;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
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
        ReservationResultCode resultCode = reservationQuotaExecutor.tryAcquire(batch, user.getId(), now);
        if (resultCode != ReservationResultCode.SUCCESS) {
            log.warn("预约名额抢占被拒：batchId={}, userId={}, resultCode={}",
                    batchId, user.getId(), resultCode);
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
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.RESERVATION_EXCHANGE,
                    RabbitMQConfig.RESERVATION_CREATE_ROUTING_KEY,
                    message,
                    correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                throw new AmqpException("预约消息未获得 RabbitMQ 确认：" + confirm.getReason());
            }
            if (correlationData.getReturnedMessage() != null) {
                throw new AmqpException("预约消息无法路由到队列");
            }
        } catch (Exception e) {
            log.error("预约消息发送失败，回滚名额：reservationId={}，userId={}，batchId={}",
                    reservationId, userId, batchId, e);
            try {
                int rollbackCode = reservationQuotaRollbackExecutor.rollback(batchId, userId);
                log.warn("预约消息发送失败后的名额回滚完成：reservationId={}，rollbackCode={}",
                        reservationId, rollbackCode);
            } catch (Exception rollbackException) {
                log.error("预约消息发送失败且名额回滚异常：reservationId={}，userId={}，batchId={}",
                        reservationId, userId, batchId, rollbackException);
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ServiceUnavailableException("预约失败，请重试");
        }
    }
}
