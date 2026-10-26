package com.yanyuetong.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yanyuetong.entity.Reservation;
import com.yanyuetong.entity.ReservationBatch;
import com.yanyuetong.enums.ReservationCancelOutcome;
import com.yanyuetong.enums.ReservationStatus;
import com.yanyuetong.exception.BadRequestException;
import com.yanyuetong.mapper.ReservationBatchMapper;
import com.yanyuetong.mapper.ReservationMapper;
import com.yanyuetong.service.IReservationLifecycleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * 预约事实的生命周期状态机，负责取消和履约核验的条件转换。
 * 所有转换均在 SQL WHERE 中校验原状态，以影响行数判定谁赢得并发竞争。
 */
@Slf4j
@Service
public class ReservationLifecycleServiceImpl implements IReservationLifecycleService {

    @Resource
    private ReservationMapper reservationMapper;

    @Resource
    private ReservationBatchMapper reservationBatchMapper;

    /**
     * 仅允许本人的 RESERVED 预约转为 CANCELLED，并在同一事务内回补 MySQL 批次名额。
     * 返回细分结果供外层决定是否应回补 Redis；只有 TRANSITIONED 代表本调用真正发生状态跃迁。
     */
    @Override
    @Transactional
    public ReservationCancelOutcome cancel(Long reservationId, Long userId, LocalDateTime now) {
        // 1、检查预约归属及批次是否存在。统一返回 NOT_FOUND，避免向非本人泄露预约信息。
        Reservation reservation = reservationMapper.selectById(reservationId);
        if (reservation == null || !userId.equals(reservation.getUserId())) {
            return ReservationCancelOutcome.NOT_FOUND;
        }

        ReservationBatch batch = reservationBatchMapper.selectById(reservation.getBatchId());
        if (batch == null) {
            return ReservationCancelOutcome.NOT_FOUND;
        }
        // 2、使用结束后不允许取消；该判断早于重复取消判断，所以过期后重复取消也会被拒绝。
        if (now.isAfter(batch.getEndTime())) {
            throw new BadRequestException("预约已结束，无法取消");
        }

        // 3、用 WHERE status=RESERVED 做 CAS。并发取消或核验时，只有抢先更新成功的调用能继续回补。
        int transitioned = reservationMapper.update(null,
                new LambdaUpdateWrapper<Reservation>()
                        .eq(Reservation::getId, reservationId)
                        .eq(Reservation::getUserId, userId)
                        .eq(Reservation::getStatus, ReservationStatus.RESERVED)
                        .set(Reservation::getStatus, ReservationStatus.CANCELLED));
        if (transitioned == 0) {
            // 更新未命中时重读以区分“已取消”的幂等结果与“已核验/爽约”等不可取消结果。
            Reservation current = reservationMapper.selectById(reservationId);
            if (current == null || !userId.equals(current.getUserId())) {
                return ReservationCancelOutcome.NOT_FOUND;
            }
            if (current.getStatus() == ReservationStatus.CANCELLED) {
                return ReservationCancelOutcome.ALREADY_CANCELLED;
            }
            return ReservationCancelOutcome.NOT_CANCELLABLE;
        }

        // 4、在同一个 MySQL 事务内回补批次名额，并用总名额上限避免重复加满后继续增长。
        // Redis 不在此事务内，由外层 ReservationServiceImpl.cancel 在事务返回后处理。
        int quotaRestored = reservationBatchMapper.update(null,
                new LambdaUpdateWrapper<ReservationBatch>()
                        .eq(ReservationBatch::getId, reservation.getBatchId())
                        .lt(ReservationBatch::getRemainingQuota, batch.getTotalQuota())
                        .setSql("remaining_quota = remaining_quota + 1"));
        if (quotaRestored == 0) {
            // 影响 0 行不会抛异常，取消状态仍会提交；外层仍会尝试释放 Redis，占位存在时可能回补。
            // 两侧名额可能不一致，因此保留日志；不能把返回 TRANSITIONED 理解为两侧均已回补。
            log.warn("取消预约后批次名额未回补：reservationId={}，batchId={}，affectedRows=0",
                    reservationId, reservation.getBatchId());
        }
        // 5、明确告知外层本次发生了跃迁，使重复调用不再释放 Redis 中可能属于新预约的占位。
        return ReservationCancelOutcome.TRANSITIONED;
    }

    /**
     * 在批次开始后将 RESERVED 转为 COMPLETED。重复核验幂等返回 false，
     * 与取消等其他并发转换冲突时拒绝本次操作。
     */
    @Override
    @Transactional
    public boolean complete(Long reservationId, LocalDateTime now) {
        // 1、缺失或已完成时返回 false；其他非 RESERVED 状态拒绝，防止复活取消或爽约的预约。
        Reservation reservation = reservationMapper.selectById(reservationId);
        if (reservation == null) {
            return false;
        }
        if (reservation.getStatus() == ReservationStatus.COMPLETED) {
            return false;
        }
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw new BadRequestException("当前状态不可核验");
        }

        // 2、检查是否已到使用开始时间；管理员身份与实验室归属由上层 verify 方法负责校验。
        ReservationBatch batch = reservationBatchMapper.selectById(reservation.getBatchId());
        if (batch == null) {
            return false;
        }
        if (now.isBefore(batch.getStartTime())) {
            throw new BadRequestException("预约尚未开始，无法核验");
        }

        // 3、只将 RESERVED 转为 COMPLETED，不归还名额；完成记录仍占用该用户在批次内的唯一槽位。
        int transitioned = reservationMapper.update(null,
                new LambdaUpdateWrapper<Reservation>()
                        .eq(Reservation::getId, reservationId)
                        .eq(Reservation::getStatus, ReservationStatus.RESERVED)
                        .set(Reservation::getStatus, ReservationStatus.COMPLETED));
        if (transitioned == 1) {
            return true;
        }

        // 4、CAS 未命中时，仅“已被其他调用完成”可幂等返回，其余状态变化均拒绝本次核验。
        Reservation current = reservationMapper.selectById(reservationId);
        if (current != null && current.getStatus() == ReservationStatus.COMPLETED) {
            return false;
        }
        throw new BadRequestException("当前状态不可核验");
    }
}
