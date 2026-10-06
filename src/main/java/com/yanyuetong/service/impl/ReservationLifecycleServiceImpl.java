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

@Slf4j
@Service
public class ReservationLifecycleServiceImpl implements IReservationLifecycleService {

    @Resource
    private ReservationMapper reservationMapper;

    @Resource
    private ReservationBatchMapper reservationBatchMapper;

    @Override
    @Transactional
    public ReservationCancelOutcome cancel(Long reservationId, Long userId, LocalDateTime now) {
        Reservation reservation = reservationMapper.selectById(reservationId);
        if (reservation == null || !userId.equals(reservation.getUserId())) {
            return ReservationCancelOutcome.NOT_FOUND;
        }

        ReservationBatch batch = reservationBatchMapper.selectById(reservation.getBatchId());
        if (batch == null) {
            return ReservationCancelOutcome.NOT_FOUND;
        }
        if (now.isAfter(batch.getEndTime())) {
            throw new BadRequestException("预约已结束，无法取消");
        }

        int transitioned = reservationMapper.update(null,
                new LambdaUpdateWrapper<Reservation>()
                        .eq(Reservation::getId, reservationId)
                        .eq(Reservation::getUserId, userId)
                        .eq(Reservation::getStatus, ReservationStatus.RESERVED)
                        .set(Reservation::getStatus, ReservationStatus.CANCELLED));
        if (transitioned == 0) {
            Reservation current = reservationMapper.selectById(reservationId);
            if (current == null || !userId.equals(current.getUserId())) {
                return ReservationCancelOutcome.NOT_FOUND;
            }
            if (current.getStatus() == ReservationStatus.CANCELLED) {
                return ReservationCancelOutcome.ALREADY_CANCELLED;
            }
            return ReservationCancelOutcome.NOT_CANCELLABLE;
        }

        int quotaRestored = reservationBatchMapper.update(null,
                new LambdaUpdateWrapper<ReservationBatch>()
                        .eq(ReservationBatch::getId, reservation.getBatchId())
                        .lt(ReservationBatch::getRemainingQuota, batch.getTotalQuota())
                        .setSql("remaining_quota = remaining_quota + 1"));
        if (quotaRestored == 0) {
            // MySQL 未回补名额（批次名额已达 totalQuota 上限，或批次行在事务内已变更），
            // 而 Redis 侧仍会 +1。方向保守（不会超卖），但会形成台账偏离，必须留痕以便对账排查。
            log.warn("取消预约后批次名额未回补：reservationId={}，batchId={}，affectedRows=0",
                    reservationId, reservation.getBatchId());
        }
        return ReservationCancelOutcome.TRANSITIONED;
    }

    @Override
    @Transactional
    public boolean complete(Long reservationId, LocalDateTime now) {
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

        ReservationBatch batch = reservationBatchMapper.selectById(reservation.getBatchId());
        if (batch == null) {
            return false;
        }
        if (now.isBefore(batch.getStartTime())) {
            throw new BadRequestException("预约尚未开始，无法核验");
        }

        int transitioned = reservationMapper.update(null,
                new LambdaUpdateWrapper<Reservation>()
                        .eq(Reservation::getId, reservationId)
                        .eq(Reservation::getStatus, ReservationStatus.RESERVED)
                        .set(Reservation::getStatus, ReservationStatus.COMPLETED));
        if (transitioned == 1) {
            return true;
        }

        Reservation current = reservationMapper.selectById(reservationId);
        if (current != null && current.getStatus() == ReservationStatus.COMPLETED) {
            return false;
        }
        throw new BadRequestException("当前状态不可核验");
    }
}
