package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.UserDTO;
import com.hmdp.config.RabbitMQConfig;
import com.hmdp.dto.ReservationQueryDTO;
import com.hmdp.entity.Equipment;
import com.hmdp.entity.Reservation;
import com.hmdp.entity.ReservationBatch;
import com.hmdp.entity.ReservationRequest;
import com.hmdp.enums.EquipmentStatus;
import com.hmdp.enums.ReservationBatchStatus;
import com.hmdp.enums.ReservationCancelOutcome;
import com.hmdp.enums.ReservationRequestStatus;
import com.hmdp.enums.ReservationResultCode;
import com.hmdp.exception.BadRequestException;
import com.hmdp.exception.ForbiddenException;
import com.hmdp.exception.ServiceUnavailableException;
import com.hmdp.mq.ReservationMessageDTO;
import com.hmdp.service.IEquipmentService;
import com.hmdp.service.ILaboratoryService;
import com.hmdp.service.IReservationBatchService;
import com.hmdp.service.IReservationLifecycleService;
import com.hmdp.service.IReservationRequestService;
import com.hmdp.service.IReservationService;
import com.hmdp.mapper.ReservationMapper;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.ReservationQuotaExecutor;
import com.hmdp.utils.ReservationQuotaRollbackExecutor;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import com.hmdp.vo.ReservationVO;
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
    private IReservationLifecycleService reservationLifecycleService;

    @Resource
    private ReservationMapper reservationMapper;

    @Resource
    private ILaboratoryService laboratoryService;

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

    @Override
    public void cancel(Long reservationId) {
        UserDTO user = requireUser("无权取消该预约");
        Reservation reservation = reservationMapper.selectById(reservationId);
        ReservationCancelOutcome outcome = reservationLifecycleService.cancel(
                reservationId, user.getId(), LocalDateTime.now());
        if (outcome == ReservationCancelOutcome.NOT_FOUND) {
            throw new ForbiddenException("无权取消该预约");
        }
        if (outcome == ReservationCancelOutcome.NOT_CANCELLABLE) {
            throw new BadRequestException("当前状态不可取消");
        }
        if (outcome != ReservationCancelOutcome.TRANSITIONED) {
            // ALREADY_CANCELLED：本次调用没有发生 RESERVED→CANCELLED 的状态跃迁，幂等返回。
            // 绝不能在此路径触碰 Redis 或 quota_released：rollback 的 Lua 虽然自身幂等，
            // 但用户可能在取消 R1 之后重新预约得到 R2，此时对 R1 的重复取消会 SREM 掉
            // R2 在 Redis 中的占位标记 —— Redis 多出名额而 MySQL 仍占用 R2，既超卖又
            // 重新打开名额泄漏（再次抢名额会在落库时被唯一键拒成 DUPLICATE 并静默 ACK）。
            // 只有本事务真正完成跃迁（TRANSITIONED）才允许归还名额。
            return;
        }

        try {
            int rollbackCode = reservationQuotaRollbackExecutor.rollback(
                    reservation.getBatchId(), user.getId());
            reservationMapper.update(null,
                    new LambdaUpdateWrapper<Reservation>()
                            .eq(Reservation::getId, reservationId)
                            .eq(Reservation::getQuotaReleased, false)
                            .set(Reservation::getQuotaReleased, true));
            log.info("取消预约后 Redis 名额归还完成：reservationId={}，rollbackCode={}",
                    reservationId, rollbackCode);
        } catch (RuntimeException e) {
            log.error("取消预约后 Redis 名额归还失败，等待定时补偿：reservationId={}，batchId={}，userId={}",
                    reservationId, reservation.getBatchId(), user.getId(), e);
        }
    }

    @Override
    public void verify(Long reservationId) {
        requireUser("无权核验该预约");
        Reservation reservation = reservationMapper.selectById(reservationId);
        if (reservation == null) {
            throw new ForbiddenException("无权核验该预约");
        }
        ReservationBatch batch = reservationBatchService.getById(reservation.getBatchId());
        if (batch == null) {
            throw new ForbiddenException("无权核验该预约");
        }
        Equipment equipment = equipmentService.getById(batch.getEquipmentId());
        if (equipment == null) {
            throw new ForbiddenException("无权核验该预约");
        }
        laboratoryService.assertManageable(equipment.getLaboratoryId());
        reservationLifecycleService.complete(reservationId, LocalDateTime.now());
    }

    @Override
    public IPage<ReservationVO> queryMyPage(ReservationQueryDTO query) {
        UserDTO user = requireUser("无权查看该预约");
        ReservationQueryDTO actualQuery = query == null ? new ReservationQueryDTO() : query;
        return queryPage(actualQuery, user.getId(), null);
    }

    @Override
    public ReservationVO queryMine(Long reservationId) {
        UserDTO user = requireUser("无权查看该预约");
        ReservationVO reservation = reservationMapper.selectReservationDetailById(reservationId);
        if (reservation == null || !user.getId().equals(reservation.getUserId())) {
            throw new ForbiddenException("无权查看该预约");
        }
        return reservation;
    }

    @Override
    public IPage<ReservationVO> queryManagePage(ReservationQueryDTO query) {
        UserDTO user = requireUser("无权查看管理端预约");
        ReservationQueryDTO actualQuery = query == null ? new ReservationQueryDTO() : query;
        return queryPage(actualQuery, null, user.getId());
    }

    private IPage<ReservationVO> queryPage(
            ReservationQueryDTO query, Long userId, Long adminUserId) {
        Integer current = query.getCurrent() == null ? 1 : query.getCurrent();
        Page<ReservationVO> page = new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE);
        return reservationMapper.selectReservationPage(page, query, userId, adminUserId);
    }

    private UserDTO requireUser(String message) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            throw new ForbiddenException(message);
        }
        return user;
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
