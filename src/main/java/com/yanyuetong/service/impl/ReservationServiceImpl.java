package com.yanyuetong.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yanyuetong.dto.UserDTO;
import com.yanyuetong.config.RabbitMQConfig;
import com.yanyuetong.dto.ReservationQueryDTO;
import com.yanyuetong.entity.Equipment;
import com.yanyuetong.entity.Reservation;
import com.yanyuetong.entity.ReservationBatch;
import com.yanyuetong.entity.ReservationRequest;
import com.yanyuetong.enums.EquipmentStatus;
import com.yanyuetong.enums.ReservationBatchStatus;
import com.yanyuetong.enums.ReservationCancelOutcome;
import com.yanyuetong.enums.ReservationRequestStatus;
import com.yanyuetong.enums.ReservationResultCode;
import com.yanyuetong.exception.BadRequestException;
import com.yanyuetong.exception.ForbiddenException;
import com.yanyuetong.exception.ServiceUnavailableException;
import com.yanyuetong.mq.ReservationMessageDTO;
import com.yanyuetong.service.IEquipmentService;
import com.yanyuetong.service.ILaboratoryService;
import com.yanyuetong.service.IReservationBatchService;
import com.yanyuetong.service.IReservationLifecycleService;
import com.yanyuetong.service.IReservationRequestService;
import com.yanyuetong.service.IReservationService;
import com.yanyuetong.mapper.ReservationMapper;
import com.yanyuetong.utils.RedisIdWorker;
import com.yanyuetong.utils.ReservationQuotaExecutor;
import com.yanyuetong.utils.ReservationQuotaRollbackExecutor;
import com.yanyuetong.utils.SystemConstants;
import com.yanyuetong.utils.UserHolder;
import com.yanyuetong.vo.ReservationVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 预约用例编排服务：负责入口校验、Redis 名额预占、消息投递和失败补偿。
 * 预占与 MySQL 落库跨越 Redis、RabbitMQ 和数据库，因此不追求分布式事务，
 * 而是通过请求账本、幂等回滚和定时对账最终收敛。
 */
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

    /**
     * 提交预约并返回全链路共用的请求 ID。
     * 先创建 PRE_ACCEPTED 账本，再预占 Redis 名额，可确保后续投递失败或结果未知时有可对账的持久化记录。
     *
     * @param batchId 预约批次 ID
     * @return 预约请求 ID，同时作为最终预约 ID
     */
    @Override
    public Long submit(Long batchId) {
        // 1、先确认请求参数和当前登录用户。身份由 Web 拦截器写入 UserHolder，不信任客户端传入 userId。
        if (batchId == null) {
            throw new BadRequestException("预约批次不存在");
        }
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            throw new ForbiddenException("请先登录");
        }
        // 2、直接读取 MySQL 的批次、设备状态和预约窗口，避免用设备详情缓存中的旧状态放行预约。
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
        // 3、生成贯穿账本、MQ 和预约事实表的同一个 ID，再创建 PRE_ACCEPTED 请求账本。
        // 必须先建账再预占：即使后面进程中断，定时对账也能找到这次未完成的请求。
        long reservationId = redisIdWorker.nextId("reservation");
        try {
            reservationRequestService.createPreAccepted(reservationId, user.getId(), batchId);
        } catch (RuntimeException e) {
            log.error("预约请求账本创建失败：reservationId={}，userId={}，batchId={}",
                    reservationId, user.getId(), batchId, e);
            throw new ServiceUnavailableException("预约失败，请重试");
        }
        // 4、调用 Lua 原子校验并扣减 Redis 名额、记录用户占位。这里只是预占，尚未插入 MySQL 预约。
        ReservationResultCode resultCode = reservationQuotaExecutor.tryAcquire(batch, user.getId(), now);
        if (resultCode != ReservationResultCode.SUCCESS) {
            log.warn("预约名额抢占被拒：batchId={}, userId={}, resultCode={}",
                    batchId, user.getId(), resultCode);
            // 预占被拒时没有本次名额需要归还；只将账本终结并记录原因，避免一直留在待对账集合。
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
        // 5、投递创建消息，并按“已确认、确定未投递、结果未知”分别推进账本或补偿。
        publishReservationMessage(reservationId, user.getId(), batchId, now);
        // 6、返回的是已受理请求的 ID，不代表消费者已落库；后续查询预约事实仍可能暂时查不到。
        return reservationId;
    }

    /**
     * 取消本人预约。MySQL 状态与名额在一个事务中先完成，事务成功后才尝试归还 Redis 名额；
     * Redis 失败不反向回滚数据库，而由定时任务根据 quota_released 重试。
     *
     * @param reservationId 预约 ID
     */
    @Override
    public void cancel(Long reservationId) {
        // 1、取得本人身份，并保存后续 Redis 回滚需要的用户、批次信息。
        UserDTO user = requireUser("无权取消该预约");
        Reservation reservation = reservationMapper.selectById(reservationId);
        // 2、经独立 Spring Service 调用事务方法：CAS 取消预约并回补 MySQL 名额，返回时事务已完成。
        ReservationCancelOutcome outcome = reservationLifecycleService.cancel(
                reservationId, user.getId(), LocalDateTime.now());
        if (outcome == ReservationCancelOutcome.NOT_FOUND) {
            throw new ForbiddenException("无权取消该预约");
        }
        if (outcome == ReservationCancelOutcome.NOT_CANCELLABLE) {
            throw new BadRequestException("当前状态不可取消");
        }
        // 3、只有真正完成状态跃迁的调用能继续归还 Redis；重复取消应幂等返回。
        if (outcome != ReservationCancelOutcome.TRANSITIONED) {
            // ALREADY_CANCELLED：本次调用没有发生 RESERVED→CANCELLED 的状态跃迁，幂等返回。
            // 绝不能在此路径触碰 Redis 或 quota_released：rollback 的 Lua 虽然自身幂等，
            // 但用户可能在取消 R1 之后重新预约得到 R2，此时对 R1 的重复取消会 SREM 掉
            // R2 在 Redis 中的占位标记 —— Redis 多出名额而 MySQL 仍占用 R2，既超卖又
            // 重新打开名额泄漏（再次抢名额会在落库时被唯一键拒成 DUPLICATE 并静默 ACK）。
            // 只有本事务真正完成跃迁（TRANSITIONED）才允许归还名额。
            return;
        }

        // 4、MySQL 事务之外执行 Redis Lua，再标记 quota_released，供定时任务识别尚未完成的补偿。
        // 两个存储不能一起提交；Redis 异常时保留标记为 false，等待 releasePendingQuotas 重试。
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
        // 1、沿“预约→批次→设备”找到实验室；端点上的 LAB_ADMIN 注解只做角色检查。
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
        // 2、确认该实验室归当前管理员负责，再进入生命周期事务完成核验。
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
        // 1、把预占时的请求快照放入消息，消费线程不能使用 Web 请求线程里的 ThreadLocal。
        // CorrelationData 只关联本次发布确认，不负责消费者处理结果。
        ReservationMessageDTO message = new ReservationMessageDTO();
        message.setReservationId(reservationId);
        message.setUserId(userId);
        message.setBatchId(batchId);
        message.setCreateTime(createTime);
        CorrelationData correlationData = new CorrelationData(String.valueOf(reservationId));

        PublishOutcome outcome;
        String failureReason = null;
        try {
            // 2、发到创建交换机并最多等待 5 秒；ACK 不代表消费完成，更不代表 MySQL 已提交。
            // mandatory return 还要排除“broker 接收但无队列可路由”的情况，两项都满足才记为 ACKED。
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
            // 连 broker 都未连接成功，按本次未投递处理，后面可立即归还预占名额。
            log.error("预约消息连接 RabbitMQ 失败：reservationId={}，userId={}，batchId={}",
                    reservationId, userId, batchId, e);
            outcome = PublishOutcome.DEFINITELY_NOT_DELIVERED;
            failureReason = "AMQP_CONNECT_FAILED";
        } catch (InterruptedException e) {
            // 等待确认被中断不等于消息未到达；保留线程中断标记，并按未知结果处理。
            Thread.currentThread().interrupt();
            log.error("等待预约消息确认时被中断：reservationId={}，userId={}，batchId={}",
                    reservationId, userId, batchId, e);
            outcome = PublishOutcome.UNKNOWN;
        } catch (Exception e) {
            log.error("预约消息发送结果不确定：reservationId={}，userId={}，batchId={}",
                    reservationId, userId, batchId, e);
            outcome = PublishOutcome.UNKNOWN;
        }

        // 3、投递确认后尝试 PRE_ACCEPTED→PUBLISHED；消费者可能已经更早将账本推进到 CONFIRMED。
        if (outcome == PublishOutcome.ACKED) {
            markPublishedWithRetry(reservationId);
            return;
        }

        // 4、确定未投递时先归还 Redis，再将账本置为 COMPENSATED；回滚失败就留给定时任务继续处理。
        if (outcome == PublishOutcome.DEFINITELY_NOT_DELIVERED) {
            // 只有结果确定为未投递时才能安全归还名额，否则可能与已在途中的消息并发导致超发。
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

        // 5、确认超时等异常无法证明消息未达，保留名额并尝试转 UNKNOWN，避免与在途消息同时占用同一名额。
        // 返回“待确认”后，消费者或定时对账仍可推进最终结果；HTTP 失败不等于最终预约失败。
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
        // 1、发布线程和消费者可并发推进账本；只允许从 PRE_ACCEPTED 更新，不能覆盖更晚的状态。
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
        // 2、两次尝试后再读账本：已被消费或对账推进至终态是正常竞争，其余情况留日志并交给对账。
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
